package com.teolo.magixessentials.currency;

import com.teolo.magixessentials.lang.Messages;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Il comando di UNA valuta (es. {@code /magix}): {@code add}, {@code take}, {@code set},
 * {@code reset} sono per lo staff, {@code give} lo usano i giocatori fra loro. Non e' dichiarato
 * in {@code plugin.yml} — {@link CurrencyManager} lo registra a runtime, uno per valuta — quindi
 * qui non si presume niente: la {@link Currency} si rilegge da {@link CurrencyManager} a ogni
 * esecuzione, cosi' un {@code /magixessentials reload} che ha cambiato nome o saldo di partenza
 * vale subito, senza dover ri-registrare il comando.
 *
 * <p>Ogni operazione passa da {@link CurrencyStore}, locale o in rete a seconda della valuta: il
 * risultato puo' arrivare da un altro thread (il database), quindi si torna sempre sul thread
 * principale prima di toccare l'API di Bukkit ({@link #runSync}) — anche per le valute locali,
 * dove sarebbe gia' li', cosi' il codice resta lo stesso per tutte e due.</p>
 */
final class CurrencyCommand extends Command {

    private final JavaPlugin plugin;
    private final CurrencyManager manager;
    private final String currencyId;
    private final Messages messages;

    CurrencyCommand(JavaPlugin plugin, CurrencyManager manager, String currencyId, Messages messages) {
        super(currencyId);
        this.plugin = plugin;
        this.manager = manager;
        this.currencyId = currencyId;
        this.messages = messages;
        setDescription("Comando della valuta \"" + currencyId + "\" (MagixEssentials).");
        setUsage("/" + currencyId + " add|take|set|reset|give <giocatore> [importo]");
        setPermission("magixessentials.currency." + currencyId + ".admin;"
                + "magixessentials.currency." + currencyId + ".give");
    }

    @Override
    public boolean execute(@NotNull CommandSender sender, @NotNull String label, String[] args) {
        Currency currency = manager.currency(currencyId);
        if (currency == null) {
            messages.send(sender, "currency.unavailable", "id", currencyId);
            return true;
        }
        if (args.length == 0) {
            if (sender instanceof Player player) {
                withStore(sender, currency, store -> store.balance(currency, player.getUniqueId())
                        .whenComplete((balance, err) -> runSync(() -> {
                            if (failed(sender, err)) return;
                            messages.send(sender, "currency.balance-self", "currency", currency.name(),
                                    "balance", String.valueOf(balance));
                        })));
            } else {
                messages.send(sender, "currency.usage", "command", label);
            }
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "add" -> adminChange(sender, currency, label, args, true);
            case "take" -> adminChange(sender, currency, label, args, false);
            case "set" -> set(sender, currency, label, args);
            case "reset" -> reset(sender, currency, label, args);
            case "give" -> give(sender, currency, label, args);
            default -> messages.send(sender, "currency.usage", "command", label);
        }
        return true;
    }

    // ------------------------------------------------------------------ sottocomandi

    private void adminChange(CommandSender sender, Currency currency, String label, String[] args, boolean add) {
        if (!admin(sender, currency)) return;
        if (args.length < 3) { messages.send(sender, "currency.usage", "command", label); return; }
        OfflinePlayer target = resolve(sender, args[1]);
        if (target == null) return;
        Long amount = amount(sender, args[2], true);
        if (amount == null) return;
        UUID who = target.getUniqueId();

        withStore(sender, currency, store -> store.add(currency, who, add ? amount : -amount)
                .whenComplete((updated, err) -> runSync(() -> {
                    if (failed(sender, err)) return;
                    String path = add ? "currency.added" : "currency.taken";
                    messages.send(sender, path, "player", displayName(target), "currency", currency.name(),
                            "amount", String.valueOf(amount), "balance", String.valueOf(updated));
                    Player online = target.getPlayer();
                    if (online != null) {
                        messages.send(online, add ? "currency.added-target" : "currency.taken-target",
                                "currency", currency.name(), "amount", String.valueOf(amount),
                                "balance", String.valueOf(updated));
                    }
                })));
    }

    private void set(CommandSender sender, Currency currency, String label, String[] args) {
        if (!admin(sender, currency)) return;
        if (args.length < 3) { messages.send(sender, "currency.usage", "command", label); return; }
        OfflinePlayer target = resolve(sender, args[1]);
        if (target == null) return;
        Long amount = amount(sender, args[2], false);
        if (amount == null) return;

        withStore(sender, currency, store -> store.set(currency, target.getUniqueId(), amount)
                .whenComplete((updated, err) -> runSync(() -> {
                    if (failed(sender, err)) return;
                    messages.send(sender, "currency.set", "player", displayName(target), "currency", currency.name(),
                            "amount", String.valueOf(amount));
                    Player online = target.getPlayer();
                    if (online != null) {
                        messages.send(online, "currency.set-target", "currency", currency.name(),
                                "amount", String.valueOf(amount));
                    }
                })));
    }

    private void reset(CommandSender sender, Currency currency, String label, String[] args) {
        if (!admin(sender, currency)) return;
        if (args.length < 2) { messages.send(sender, "currency.usage", "command", label); return; }
        OfflinePlayer target = resolve(sender, args[1]);
        if (target == null) return;

        withStore(sender, currency, store -> store.set(currency, target.getUniqueId(), currency.startingBalance())
                .whenComplete((updated, err) -> runSync(() -> {
                    if (failed(sender, err)) return;
                    messages.send(sender, "currency.reset", "player", displayName(target), "currency", currency.name(),
                            "balance", String.valueOf(updated));
                    Player online = target.getPlayer();
                    if (online != null) {
                        messages.send(online, "currency.reset-target", "currency", currency.name(),
                                "balance", String.valueOf(updated));
                    }
                })));
    }

    private void give(CommandSender sender, Currency currency, String label, String[] args) {
        if (!(sender instanceof Player from)) { messages.send(sender, "currency.player-only"); return; }
        if (!sender.hasPermission("magixessentials.currency." + currency.id() + ".give")) {
            messages.send(sender, "currency.no-permission");
            return;
        }
        if (args.length < 3) { messages.send(sender, "currency.usage", "command", label); return; }
        OfflinePlayer target = resolve(sender, args[1]);
        if (target == null) return;
        Long amount = amount(sender, args[2], true);
        if (amount == null) return;

        withStore(sender, currency, store -> store.give(currency, from.getUniqueId(), target.getUniqueId(), amount)
                .whenComplete((outcome, err) -> runSync(() -> {
                    if (failed(sender, err)) return;
                    if (!outcome.success()) {
                        messages.send(sender, "currency.insufficient-balance", "currency", currency.name(),
                                "amount", String.valueOf(amount), "balance", String.valueOf(outcome.senderBalance()));
                        return;
                    }
                    messages.send(sender, "currency.give-sent", "player", displayName(target), "currency", currency.name(),
                            "amount", String.valueOf(amount), "balance", String.valueOf(outcome.senderBalance()));
                    Player online = target.getPlayer();
                    if (online != null) {
                        messages.send(online, "currency.give-received", "sender", from.getName(),
                                "currency", currency.name(), "amount", String.valueOf(amount),
                                "balance", String.valueOf(outcome.targetBalance()));
                    }
                })));
    }

    // ------------------------------------------------------------------ appoggio

    private boolean admin(CommandSender sender, Currency currency) {
        if (sender.hasPermission("magixessentials.currency." + currency.id() + ".admin")) return true;
        messages.send(sender, "currency.no-permission");
        return false;
    }

    /** Lo store di questa valuta se c'e', altrimenti il messaggio di database non raggiungibile. */
    private void withStore(CommandSender sender, Currency currency, java.util.function.Consumer<CurrencyStore> action) {
        CurrencyStore store = manager.storeFor(currency);
        if (store == null) {
            messages.send(sender, "currency.store-error");
            return;
        }
        action.accept(store);
    }

    /** true (e il messaggio d'errore mandato) se l'operazione asincrona e' fallita. */
    private boolean failed(CommandSender sender, Throwable err) {
        if (err == null) return false;
        messages.send(sender, "currency.store-error");
        return true;
    }

    private void runSync(Runnable task) {
        Bukkit.getScheduler().runTask(plugin, task);
    }

    /** Il giocatore per nome (online prima, poi chi ha gia' giocato); null (e messaggio) se non esiste. */
    private OfflinePlayer resolve(CommandSender sender, String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online;
        @SuppressWarnings("deprecation")
        OfflinePlayer offline = Bukkit.getOfflinePlayer(name);
        if (offline.hasPlayedBefore()) return offline;
        messages.send(sender, "currency.player-not-found", "player", name);
        return null;
    }

    private Long amount(CommandSender sender, String raw, boolean mustBePositive) {
        long value;
        try {
            value = Long.parseLong(raw);
        } catch (NumberFormatException e) {
            messages.send(sender, mustBePositive ? "currency.invalid-amount" : "currency.invalid-amount-set");
            return null;
        }
        if (mustBePositive ? value <= 0 : value < 0) {
            messages.send(sender, mustBePositive ? "currency.invalid-amount" : "currency.invalid-amount-set");
            return null;
        }
        return value;
    }

    private static String displayName(OfflinePlayer player) {
        String name = player.getName();
        return name != null ? name : player.getUniqueId().toString();
    }

    // ------------------------------------------------------------------ tab-complete

    @Override
    public @NotNull List<String> tabComplete(@NotNull CommandSender sender, @NotNull String alias, String[] args) {
        if (manager.currency(currencyId) == null) return List.of();
        if (args.length <= 1) {
            List<String> subs = new ArrayList<>();
            if (sender.hasPermission("magixessentials.currency." + currencyId + ".admin")) {
                subs.add("add"); subs.add("take"); subs.add("set"); subs.add("reset");
            }
            if (sender.hasPermission("magixessentials.currency." + currencyId + ".give")) subs.add("give");
            return startingWith(subs, args.length == 0 ? "" : args[0]);
        }
        if (args.length == 2) {
            List<String> names = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) names.add(p.getName());
            return startingWith(names, args[1]);
        }
        return List.of();
    }

    private static List<String> startingWith(List<String> options, String prefix) {
        List<String> out = new ArrayList<>();
        String low = prefix.toLowerCase(Locale.ROOT);
        for (String option : options) if (option.toLowerCase(Locale.ROOT).startsWith(low)) out.add(option);
        return out;
    }
}
