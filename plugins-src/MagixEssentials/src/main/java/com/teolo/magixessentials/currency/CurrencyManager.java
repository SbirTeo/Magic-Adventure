package com.teolo.magixessentials.currency;

import com.teolo.magixessentials.currency.db.Database;
import com.teolo.magixessentials.lang.Messages;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandMap;
import org.bukkit.command.SimpleCommandMap;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Il modulo delle valute: da ogni voce di {@code currencies.yml} nasce un comando VERO
 * ({@code /<id> add|take|set|reset|give}), coi permessi che gli servono — non c'e' niente da
 * dichiarare nel plugin.yml, perche' l'id lo sceglie lo staff a runtime.
 *
 * <p>Stessa forma degli altri moduli ({@code nametag.NametagManager}): {@link #start()} accende,
 * {@link #stop()} spegne senza lasciare niente in giro — qui vuol dire togliere i comandi
 * registrati, non solo smettere di rispondere, cosi' un {@code /magixessentials reload} che ha
 * tolto una valuta (o spento il modulo) la fa sparire davvero dal TAB, non solo dalle risposte.</p>
 *
 * <h2>Perche' serve il CommandMap</h2>
 * Bukkit non permette di dichiarare nel plugin.yml un comando il cui nome non si conosce in
 * anticipo: qui il nome (l'id della valuta) lo sceglie lo staff nel config. L'unico modo e'
 * agganciarsi al {@code CommandMap} del server, la stessa strada che usano da sempre i plugin con
 * comandi generati da un config (warp, kit...). {@code getCommandMap()} non e' nell'interfaccia
 * pubblica {@code Server}, ma il metodo esiste su ogni versione del server da quando CraftBukkit
 * esiste: se un giorno sparisse, questo modulo lo direbbe nel log invece di far cadere l'avvio.
 *
 * <h2>Locale o in rete</h2>
 * Il server sta dietro Velocity con piu' backend: ogni valuta sceglie da se' ({@code shared} in
 * currencies.yml) se il saldo vive sul file di QUESTO server ({@link LocalCurrencyStore}) o nel
 * database condiviso ({@link SharedCurrencyStore}, da {@code database} in config.yml). Il
 * database si apre una volta sola, solo se almeno una valuta di questo server lo chiede.
 */
public final class CurrencyManager {

    /** Id valido per un comando: comincia con una lettera, poi lettere/cifre/trattino basso. */
    private static final Pattern VALID_ID = Pattern.compile("[a-z][a-z0-9_]{1,15}");

    private final JavaPlugin plugin;
    private final Messages messages;
    private final CommandMap commandMap;
    private final LocalCurrencyStore localStore;
    private final Map<String, Currency> currencies = new LinkedHashMap<>();
    private final Map<String, CurrencyCommand> registered = new LinkedHashMap<>();

    private Database sharedDb;
    private SharedCurrencyStore sharedStore;

    public CurrencyManager(JavaPlugin plugin, YamlConfiguration conf) {
        this.plugin = plugin;
        this.localStore = new LocalCurrencyStore(new Balances(plugin));
        this.messages = new Messages(plugin);
        this.commandMap = resolveCommandMap(plugin);
        leggiValute(conf);
        if (currencies.values().stream().anyMatch(Currency::shared)) {
            apriDatabase();
        }
    }

    /** Registra il comando e i permessi di ogni valuta letta dal config. */
    public void start() {
        if (commandMap == null) {
            return;
        }
        for (Currency currency : currencies.values()) {
            registraPermessi(currency.id());
            CurrencyCommand command = new CurrencyCommand(plugin, this, currency.id(), messages);
            commandMap.register(plugin.getName().toLowerCase(Locale.ROOT), command);
            registered.put(currency.id(), command);
        }
        Bukkit.getOnlinePlayers().forEach(Player::updateCommands);
        plugin.getLogger().info("[Valute] " + registered.size() + " valuta/e attiva/e: "
                + (registered.isEmpty() ? "nessuna" : String.join(", ", registered.keySet())) + ".");
    }

    /** Toglie ogni comando registrato e chiude il database: a modulo spento non deve restare niente in giro. */
    public void stop() {
        if (commandMap instanceof SimpleCommandMap simple) {
            String prefix = plugin.getName().toLowerCase(Locale.ROOT) + ":";
            for (Map.Entry<String, CurrencyCommand> e : registered.entrySet()) {
                e.getValue().unregister(commandMap);
                simple.getKnownCommands().remove(e.getKey());
                simple.getKnownCommands().remove(prefix + e.getKey());
            }
        }
        registered.clear();
        Bukkit.getOnlinePlayers().forEach(Player::updateCommands);
        if (sharedStore != null) {
            sharedStore.shutdown();
            sharedStore = null;
        }
        if (sharedDb != null) {
            sharedDb.close();
            sharedDb = null;
        }
    }

    /** La valuta con questo id, cosi' com'era all'ultimo caricamento del config. */
    public Currency currency(String id) {
        return currencies.get(id);
    }

    /**
     * Dove vivono i saldi di questa valuta. Torna {@code null} solo per una valuta "shared" il
     * cui database non e' raggiungibile — {@link CurrencyCommand} lo tratta come un errore da
     * dire al giocatore, mai come un crash.
     */
    public CurrencyStore storeFor(Currency currency) {
        return currency.shared() ? sharedStore : localStore;
    }

    /** Le valute configurate, nell'ordine del file: per il log e la guida per lo staff. */
    public List<Currency> elenco() {
        return List.copyOf(currencies.values());
    }

    private void leggiValute(YamlConfiguration conf) {
        ConfigurationSection root = conf.getConfigurationSection("currencies");
        if (root == null) {
            return;
        }
        for (String id : root.getKeys(false)) {
            if (!VALID_ID.matcher(id).matches()) {
                plugin.getLogger().warning("[Valute] id valuta non valido, saltata: \"" + id
                        + "\" (solo lettere minuscole, cifre e trattino basso, deve iniziare per lettera).");
                continue;
            }
            if (commandMap != null && commandMap.getCommand(id) != null) {
                plugin.getLogger().warning("[Valute] id valuta \"" + id
                        + "\" gia' usato da un altro comando del server: saltata.");
                continue;
            }
            ConfigurationSection s = root.getConfigurationSection(id);
            if (s == null) {
                continue;
            }
            String name = s.getString("name", id);
            long startingBalance = s.getLong("starting-balance", 0);
            boolean shared = s.getBoolean("shared", false);
            currencies.put(id, new Currency(id, name, startingBalance, shared));
        }
    }

    private void apriDatabase() {
        ConfigurationSection conf = plugin.getConfig().getConfigurationSection("database");
        if (conf == null) {
            plugin.getLogger().severe("[Valute] almeno una valuta e' \"shared: true\" ma manca la sezione"
                    + " \"database\" in config.yml: resta senza saldo condiviso, l'errore si vede a ogni comando.");
            return;
        }
        try {
            sharedDb = new Database(conf);
            sharedDb.createSchema();
            sharedStore = new SharedCurrencyStore(sharedDb, plugin.getLogger());
        } catch (Exception e) {
            plugin.getLogger().severe("[Valute] non sono riuscito a collegarmi al database delle valute di rete ("
                    + e.getClass().getSimpleName() + ": " + e.getMessage() + "): resta senza saldo condiviso.");
            if (sharedDb != null) {
                sharedDb.close();
                sharedDb = null;
            }
        }
    }

    private void registraPermessi(String id) {
        aggiungiPermesso("magixessentials.currency." + id + ".admin",
                "Amministrare la valuta \"" + id + "\" (add, take, set, reset).", PermissionDefault.OP);
        aggiungiPermesso("magixessentials.currency." + id + ".give",
                "Dare la valuta \"" + id + "\" ad altri giocatori.", PermissionDefault.TRUE);
    }

    private void aggiungiPermesso(String node, String description, PermissionDefault def) {
        if (Bukkit.getPluginManager().getPermission(node) != null) {
            return;
        }
        try {
            Bukkit.getPluginManager().addPermission(new Permission(node, description, def));
        } catch (IllegalArgumentException ignored) {
            // Registrato nel frattempo (es. un reload in corsa altrove): non e' un problema.
        }
    }

    private static CommandMap resolveCommandMap(JavaPlugin plugin) {
        try {
            Method m = Bukkit.getServer().getClass().getMethod("getCommandMap");
            return (CommandMap) m.invoke(Bukkit.getServer());
        } catch (ReflectiveOperationException | ClassCastException e) {
            plugin.getLogger().severe("[Valute] non sono riuscito ad agganciare i comandi del server ("
                    + e.getClass().getSimpleName() + "): il modulo resta spento.");
            return null;
        }
    }
}
