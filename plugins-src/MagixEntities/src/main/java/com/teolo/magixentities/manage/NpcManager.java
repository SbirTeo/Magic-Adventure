package com.teolo.magixentities.manage;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.teolo.magixentities.model.NpcDef;
import com.teolo.magixentities.util.Colors;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Cuore del plugin: crea, applica, ritrova e ricrea le entita'.
 *
 * Le entita' sono entita' vere del mondo (persistono nel salvataggio come qualsiasi mob):
 * di ognuna teniamo l'UUID in entities.yml e un tag nel PersistentDataContainer, cosi'
 * possiamo riconoscerle anche se l'UUID cambia (mondo copiato, entita' ricreata a mano).
 */
public final class NpcManager {

    private final JavaPlugin plugin;
    private final NpcStore store;
    private final NamespacedKey key;
    private final NamespacedKey cloneKey;
    private final Map<String, NpcDef> npcs = new LinkedHashMap<>();
    private MirrorManager mirror;
    private final EntityNameTags nameTags = new EntityNameTags();
    /** Skin gia' risolte: nick in minuscolo -> profilo completo di texture. */
    private final Map<String, PlayerProfile> skinCache = new ConcurrentHashMap<>();
    /** Nick senza texture: da quando (millis) ha senso riprovare. */
    private final Map<String, Long> skinRetry = new ConcurrentHashMap<>();
    /** Nick con una richiesta a Mojang gia' in corso. */
    private final Set<String> skinPending = ConcurrentHashMap.newKeySet();
    /** Entita' a cui il mondo ha detto di no: serve a non ripetere l'avviso ad ogni controllo. */
    private final Set<String> refused = ConcurrentHashMap.newKeySet();

    public NpcManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.store = new NpcStore(plugin);
        this.key = new NamespacedKey(plugin, "npc");
        this.cloneKey = new NamespacedKey(plugin, "clone");
    }

    /** Iniettato dopo la costruzione (MirrorManager ha bisogno di NpcManager). */
    public void setMirror(MirrorManager mirror) {
        this.mirror = mirror;
    }

    // ---------------------------------------------------------------- dati

    public NamespacedKey key() {
        return key;
    }

    public Collection<NpcDef> all() {
        return npcs.values();
    }

    public NpcDef get(String name) {
        return name == null ? null : npcs.get(name.toLowerCase(Locale.ROOT));
    }

    /** Id dell'entita' "vera" (quella salvata), o null: NON considera le copie mirror. */
    public String tagOf(Entity e) {
        if (e == null) return null;
        return e.getPersistentDataContainer().get(key, PersistentDataType.STRING);
    }

    /** Id dell'entita' se e' una copia mirror, altrimenti null. */
    public String cloneTagOf(Entity e) {
        if (e == null) return null;
        return e.getPersistentDataContainer().get(cloneKey, PersistentDataType.STRING);
    }

    /** Id dell'entita' vera O di una copia mirror: usato dalle protezioni. */
    public String anyTagOf(Entity e) {
        String id = tagOf(e);
        return id != null ? id : cloneTagOf(e);
    }

    /** Definizione a partire da una entita' vera o da una sua copia mirror. */
    public NpcDef defOf(Entity e) {
        String id = anyTagOf(e);
        return id == null ? null : npcs.get(id);
    }

    public void load() {
        npcs.clear();
        npcs.putAll(store.load());
    }

    public void save() {
        store.save(npcs);
    }

    // ------------------------------------------------------------ creazione

    /** Crea la definizione, la salva e prova subito a materializzare l'entita'. */
    public NpcDef create(String name, EntityType type, String display, Location loc) {
        NpcDef d = new NpcDef(name, type);
        // display null = segue il nome dell'entita' (anche dopo un /mentities name)
        d.display = (display == null || display.isBlank()) ? null : display;
        d.setLocation(loc);
        for (String o : NpcDef.OPTIONS) {
            d.options.put(o, plugin.getConfig().getBoolean("defaults." + o, defaultOf(o)));
        }
        npcs.put(d.id, d);
        spawn(d);
        save();
        return d;
    }

    private boolean defaultOf(String option) {
        return switch (option) {
            case "invulnerable", "nametag", "silent", "gravity", "immovable" -> true;
            default -> false; // ai, glowing, collidable
        };
    }

    /**
     * Rinomina l'entita': cambia la chiave in entities.yml, il tag sull'entita' viva e — per il
     * tipo player senza skin esplicita — anche la skin, che segue sempre il nome.
     *
     * @return false se il nome e' gia' occupato da un'altra entita'
     */
    public boolean rename(NpcDef d, String newName) {
        String newId = newName.toLowerCase(Locale.ROOT);
        if (!newId.equals(d.id) && npcs.containsKey(newId)) return false;
        if (mirror != null) mirror.clear(d);
        npcs.remove(d.id);
        d.rename(newName);
        npcs.put(d.id, d);
        Entity e = entityOf(d);
        if (e != null) apply(d, e);
        save();
        return true;
    }

    /**
     * Cambia il TIPO di un'entita' tenendo tutto il resto: posizione, displayname, opzioni,
     * equipaggiamento e comandi al clic. Skin e posa restano scritte nella definizione anche
     * quando il nuovo tipo non le usa, cosi' tornando al tipo player si ritrovano com'erano.
     *
     * Il tipo di un'entita' viva non si cambia: va tolta e rifatta. E' la stessa strada di
     * {@code /mentities respawn}, che sa gia' rimettere in piedi tutto dalla definizione —
     * quindi qui basta scrivere il tipo nuovo e ricreare.
     *
     * @return l'entita' nuova, o null se il mondo non e' caricato
     */
    public Entity changeType(NpcDef d, EntityType type) {
        // Le copie mirror in giro sono ancora del tipo vecchio: si buttano, MirrorManager le rifa'.
        if (mirror != null) mirror.clear(d);
        d.type = type;
        Entity e = spawn(d);
        save();
        return e;
    }

    /** Rimuove definizione ed entita' (comprese le copie mirror). */
    public void delete(NpcDef d) {
        if (mirror != null) mirror.clear(d);
        despawn(d);
        npcs.remove(d.id);
        refused.remove(d.id);
        save();
        nameTags.sync(npcs.values());
    }

    /**
     * Ricalcola subito chi deve avere la targhetta vanilla nascosta (vedi {@link EntityNameTags}):
     * da chiamare dopo un comando che cambia nome, skin, tipo o l'opzione {@code nametag}, cosi'
     * l'effetto si vede subito invece di aspettare il prossimo {@link #ensureAll()}.
     */
    public void syncNameTags() {
        nameTags.sync(npcs.values());
    }

    /** Toglie tutte le targhette nascoste (spegnimento del plugin). */
    public void clearNameTags() {
        nameTags.clear();
    }

    // ------------------------------------------------------------- runtime

    /** Entita' viva corrispondente alla definizione, o null (anche se il chunk e' scarico). */
    public Entity entityOf(NpcDef d) {
        if (d.uuid == null) return null;
        Entity e = Bukkit.getEntity(d.uuid);
        return (e == null || e.isDead()) ? null : e;
    }

    /** Crea l'entita' nel mondo (rimuovendo prima l'eventuale vecchia). Null se il mondo manca. */
    public Entity spawn(NpcDef d) {
        Location loc = d.location();
        if (loc == null) return null;
        despawn(d);

        Class<? extends Entity> cls = d.type.getEntityClass();
        if (cls == null) return null;
        Consumer<Entity> pre = e -> apply(d, e);
        Entity e;
        try {
            e = loc.getWorld().spawn(loc, cls, pre, CreatureSpawnEvent.SpawnReason.CUSTOM);
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("Impossibile creare '" + d.name + "' (" + d.type + "): " + ex.getMessage());
            return null;
        }
        // La nascita di un'entita' e' un evento ANNULLABILE: un plugin di protezione puo' dire di
        // no. In quel caso spawn() restituisce lo stesso l'oggetto, ma nel mondo non ci e' mai
        // entrato: senza questo controllo l'entita' risulterebbe creata e non la vedrebbe nessuno.
        if (!e.isInWorld() || !e.isValid()) {
            e.remove();
            d.uuid = null;
            if (refused.add(d.id)) plugin.getLogger().warning(spawnRefusedText(d));
            return null;
        }
        refused.remove(d.id);
        d.uuid = e.getUniqueId();
        return e;
    }

    /**
     * true se l'ultimo tentativo di creare questa entita' e' stato rifiutato dal mondo.
     * Resta vero finche' l'entita' non riesce a nascere: il controllo periodico riprova da solo,
     * quindi appena la protezione viene sistemata l'entita' compare senza toccare niente.
     */
    public boolean refused(NpcDef d) {
        return refused.contains(d.id);
    }

    /** Spiegazione (console) di una nascita annullata: il caso tipico e' WorldGuard. */
    private static String spawnRefusedText(NpcDef d) {
        return "Nascita di '" + d.name + "' annullata da un altro plugin nel mondo '" + d.world
                + "': l'entita' e' salvata in entities.yml ma non e' nel mondo. Caso tipico:"
                + " WorldGuard con il flag 'mob-spawning: deny' e 'mobs.block-plugin-spawning: true'"
                + " nel suo config.yml — mettendo quest'ultimo a false le entita' del plugin passano"
                + " e i mob naturali restano bloccati. Sistemato quello, l'entita' compare da sola"
                + " al controllo successivo (o subito con /mentities respawn " + d.name + ").";
    }

    /** Rimuove dal mondo l'entita' associata, se presente e caricata. */
    public void despawn(NpcDef d) {
        Entity e = d.uuid == null ? null : Bukkit.getEntity(d.uuid);
        if (e != null) e.remove();
        d.uuid = null;
    }

    /**
     * Si assicura che l'entita' esista: se e' caricata riapplica le proprieta', se il chunk
     * e' caricato ma l'entita' non c'e' piu' la ricrea. Se il chunk e' scarico non fa nulla
     * (l'entita' e' salvata nel chunk e tornera' da sola, ricrearla farebbe doppioni).
     *
     * @return true se ora l'entita' esiste
     */
    public boolean ensure(NpcDef d) {
        Entity e = entityOf(d);
        if (e != null) {
            apply(d, e);
            return true;
        }
        if (!d.chunkLoaded()) return false;
        // il chunk e' caricato: prima proviamo a riagganciare un'entita' taggata gia' presente
        // (es. UUID perso in entities.yml), poi in ultima istanza la ricreiamo.
        Entity tagged = findTagged(d);
        if (tagged != null) {
            d.uuid = tagged.getUniqueId();
            apply(d, tagged);
            return true;
        }
        return spawn(d) != null;
    }

    /** Controlla tutte le entita' (chiamato periodicamente e all'avvio). */
    public void ensureAll() {
        boolean changed = false;
        for (NpcDef d : new ArrayList<>(npcs.values())) {
            UUID before = d.uuid;
            ensure(d);
            if (before == null ? d.uuid != null : !before.equals(d.uuid)) changed = true;
        }
        // Si ricalcola per intero a ogni giro, mai inseguendo la singola modifica: cosi' una
        // rinomina, un cambio skin o una rimozione non lasciano mai un nome dimenticato nella
        // squadra dello scoreboard (vedi EntityNameTags).
        nameTags.sync(npcs.values());
        if (changed) save();
    }

    /** Cerca nei dintorni della posizione salvata un'entita' con il nostro tag. */
    private Entity findTagged(NpcDef d) {
        Location loc = d.location();
        if (loc == null) return null;
        for (Entity e : loc.getWorld().getNearbyEntities(loc, 4, 4, 4)) {
            if (d.id.equals(tagOf(e)) && !e.isDead()) return e;
        }
        return null;
    }

    /**
     * Chunk appena caricato: riaggancia le nostre entita' (aggiornando l'UUID) ed elimina
     * doppioni e residui di entita' cancellate mentre il chunk era scarico.
     */
    public void onChunkLoad(Chunk chunk) {
        boolean purge = plugin.getConfig().getBoolean("clean-orphans", true);
        boolean changed = false;
        for (Entity e : chunk.getEntities()) {
            String id = tagOf(e);
            if (id == null) continue;
            NpcDef d = npcs.get(id);
            if (d == null) {
                if (purge) e.remove();
                continue;
            }
            if (d.uuid == null || d.uuid.equals(e.getUniqueId())) {
                d.uuid = e.getUniqueId();
                apply(d, e);
                changed = true;
            } else if (Bukkit.getEntity(d.uuid) == null) {
                // l'entita' "ufficiale" non esiste piu': adottiamo questa
                d.uuid = e.getUniqueId();
                apply(d, e);
                changed = true;
            } else {
                e.remove(); // doppione
            }
        }
        if (changed) save();
    }

    // ------------------------------------------------------- applicazione

    /** Applica alla entita' tutte le proprieta' della definizione (anche prima dello spawn). */
    public void apply(NpcDef d, Entity e) {
        e.getPersistentDataContainer().set(key, PersistentDataType.STRING, d.id);
        e.setPersistent(true);

        Component name = Colors.component(d.displayText());
        e.customName(name);
        e.setCustomNameVisible(d.opt("nametag", true));
        e.setInvulnerable(d.opt("invulnerable", true));
        e.setGravity(d.opt("gravity", true));
        e.setSilent(d.opt("silent", true));
        e.setGlowing(d.opt("glowing", false));

        boolean ai = d.opt("ai", false);
        if (e instanceof LivingEntity le) {
            le.setRemoveWhenFarAway(false);
            le.setCollidable(d.opt("collidable", false));
            le.setCanPickupItems(false);
            try { le.setAI(ai); } catch (UnsupportedOperationException ignored) {}
            // Difesa in profondita': qualsiasi problema sull'equipaggiamento non deve impedire
            // la creazione dell'entita' (apply() gira anche nel consumer di spawn).
            try {
                applyEquipment(d, le);
            } catch (RuntimeException ex) {
                plugin.getLogger().warning("Equipaggiamento di '" + d.name + "' non applicato: " + ex.getMessage());
            }
        }
        if (e instanceof Mob mob) {
            mob.setAware(ai);
            if (!ai) mob.setTarget(null);
        }
        // In modalita' specchio (skin o displayname) l'entita' vera resta nascosta a tutti:
        // quello che i giocatori vedono sono le copie create da MirrorManager.
        e.setVisibleByDefault(!d.needsClones());

        if (e instanceof Mannequin man) {
            man.setImmovable(d.opt("immovable", true));
            // La "description" del Mannequin e' una riga disegnata sopra la testa IN AGGIUNTA al
            // customName, e solo da vicino: col displayname il nome comparirebbe due volte.
            // Deve essere null (= assente): con Component.empty() la riga viene comunque
            // riservata e avvicinandosi il nametag "salta" su di una riga.
            man.setDescription(plugin.getConfig().getBoolean("player.description", false)
                    ? name : null);
            applyPose(d, man);
            if (!d.isSkinMirror()) applySkin(d, man);
        }
    }

    /**
     * Copia mirror: stesse proprieta' dell'entita' vera, ma con la skin e/o il nome del
     * proprietario, non persistente (non finisce nel salvataggio del mondo) e visibile solo a lui.
     */
    public void applyClone(NpcDef d, Entity e, Player owner) {
        apply(d, e);
        e.getPersistentDataContainer().remove(key);
        e.getPersistentDataContainer().set(cloneKey, PersistentDataType.STRING, d.id);
        e.setPersistent(false);
        e.setVisibleByDefault(false);
        if (d.isDisplayMirror()) e.customName(Component.text(owner.getName()));
        if (d.isSkinMirror() && e instanceof Mannequin man) {
            man.setProfile(profiloSpecchio(d, owner));
        }
    }

    /**
     * Il profilo della copia specchio: nome e texture del proprietario, ma UUID SUO.
     *
     * Copiare di peso il profilo del giocatore — com'era prima — significa mettere nel mondo
     * una seconda cosa con lo STESSO UUID di profilo del giocatore. Il client, che le skin le
     * tiene in cache per UUID, non ha modo di distinguerle: se la copia nasce mentre il
     * proprietario e' senza texture (succede se la richiesta a Mojang fallisce al primo
     * ingresso — vedi MagixAuth), quel profilo vuoto resta li' a dire "l'UUID X non ha skin"
     * finche' la copia esiste, e a rimetterci la faccia e' il giocatore vero.
     *
     * L'UUID della copia si ricava dall'id dell'entita' + l'UUID del proprietario: sempre lo
     * stesso a parita' dei due, cosi' ricreando la copia il client non la vede come una cosa
     * nuova, e mai uguale a quello di un giocatore.
     *
     * Se il proprietario e' senza texture il profilo resta con solo nome e UUID: a quel punto
     * e' il gioco a risolverlo dal nome, che e' comunque meglio di una faccia vuota.
     */
    private static ResolvableProfile profiloSpecchio(NpcDef d, Player owner) {
        return ResolvableProfile.resolvableProfile()
                .uuid(UUID.nameUUIDFromBytes(
                        ("magixentities:copia:" + d.id + ":" + owner.getUniqueId())
                                .getBytes(StandardCharsets.UTF_8)))
                .name(owner.getName())
                .addProperties(owner.getPlayerProfile().getProperties())
                .build();
    }

    /** Veste l'entita' con l'equipaggiamento salvato (vedi /mentities equip), senza drop. */
    private void applyEquipment(NpcDef d, LivingEntity le) {
        EntityEquipment eq = le.getEquipment();
        if (eq == null) return;
        eq.setHelmet(d.equipment.get("helmet"));
        eq.setChestplate(d.equipment.get("chestplate"));
        eq.setLeggings(d.equipment.get("leggings"));
        eq.setBoots(d.equipment.get("boots"));
        eq.setItemInMainHand(d.equipment.get("hand"));
        eq.setItemInOffHand(d.equipment.get("offhand"));
        // Le probabilita' di drop esistono solo sui Mob: sui Mannequin (che Mob non sono)
        // ogni setter lancia IllegalArgumentException. Il catch e' la rete di sicurezza:
        // se saltasse qui, l'eccezione uscirebbe da apply() e l'entita' non verrebbe creata.
        if (!(le instanceof Mob)) return;
        try {
            eq.setHelmetDropChance(0f);
            eq.setChestplateDropChance(0f);
            eq.setLeggingsDropChance(0f);
            eq.setBootsDropChance(0f);
            eq.setItemInMainHandDropChance(0f);
            eq.setItemInOffHandDropChance(0f);
        } catch (RuntimeException ignored) {
            // niente drop chance su questa entita': l'invulnerabilita' evita comunque i drop
        }
    }

    private void applyPose(NpcDef d, Mannequin man) {
        if (d.pose == null || d.pose.isBlank()) return;
        try {
            Pose p = Pose.valueOf(d.pose.toUpperCase(Locale.ROOT));
            if (Mannequin.validPoses().contains(p)) man.setPose(p, true);
        } catch (IllegalArgumentException ignored) {}
    }

    /** Pose accettate dalle entita' di tipo player, in minuscolo. */
    public List<String> validPoses() {
        List<String> out = new ArrayList<>();
        for (Pose p : Mannequin.validPoses()) out.add(p.name().toLowerCase(Locale.ROOT));
        return out;
    }

    /**
     * Skin dell'entita' player. Il profilo con il solo nome e' applicato subito (il server lo
     * risolve da se'); se il giocatore e' online copiamo il suo profilo completo, altrimenti
     * scarichiamo le texture da Mojang in async e le applichiamo al ritorno sul main thread.
     *
     * Il risultato resta in cache per nick: senza cache il controllo periodico
     * (check-interval-seconds) rifarebbe una richiesta a Mojang per ogni entita' ogni pochi
     * secondi, riempiendo la console e rischiando il blocco per troppe richieste.
     */
    public void applySkin(NpcDef d, Mannequin man) {
        String nick = d.skinNick();
        String key = nick.toLowerCase(Locale.ROOT);

        PlayerProfile inCache = skinCache.get(key);
        if (inCache != null) {
            if (!vestita(man, nick)) man.setProfile(ResolvableProfile.resolvableProfile(inCache));
            return;
        }

        Player online = Bukkit.getPlayerExact(nick);
        if (online != null) {
            PlayerProfile profilo = online.getPlayerProfile();
            skinCache.put(key, profilo);
            skinRetry.remove(key);
            man.setProfile(ResolvableProfile.resolvableProfile(profilo));
            return;
        }

        try {
            // Se il nome e' gia' quello giusto non ripetiamo il setter: rimandare il profilo a
            // ogni controllo farebbe ricaricare l'entita' ai client vicini.
            if (!nick.equalsIgnoreCase(profileName(man))) {
                man.setProfile(ResolvableProfile.resolvableProfile().name(nick).build());
            }
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("Profilo skin non valido per '" + nick + "': " + ex.getMessage());
            return;
        }
        if (!plugin.getConfig().getBoolean("skin.fetch-textures", true)) return;

        // Nick gia' bocciato da poco (non esiste su Mojang, o Mojang non risponde): si riprova
        // solo allo scadere dell'attesa, non a ogni controllo.
        Long riprovaDa = skinRetry.get(key);
        if (riprovaDa != null && System.currentTimeMillis() < riprovaDa) return;
        if (!skinPending.add(key)) return; // richiesta gia' in volo per questo nick

        UUID entityId = man.getUniqueId();
        int minuti = Math.max(1, plugin.getConfig().getInt("skin.retry-minutes", 30));
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            PlayerProfile profile = Bukkit.createProfile(nick);
            boolean ok;
            try {
                ok = profile.complete(true) && profile.hasTextures();
            } catch (Exception ex) {
                ok = false;
            } finally {
                skinPending.remove(key);
            }
            if (!ok) {
                // Il messaggio esce una volta sola per nick: senza questo filtro il controllo
                // periodico lo ripeterebbe ogni pochi secondi per sempre.
                boolean primaVolta = skinRetry.put(key,
                        System.currentTimeMillis() + minuti * 60_000L) == null;
                if (primaVolta) plugin.getLogger().info("Texture skin non trovate per '" + nick
                        + "' (entita' " + d.name + "): riprovo tra " + minuti + " minuti.");
                return;
            }
            skinRetry.remove(key);
            skinCache.put(key, profile);
            Bukkit.getScheduler().runTask(plugin, () -> {
                Entity e = Bukkit.getEntity(entityId);
                if (e instanceof Mannequin m) m.setProfile(ResolvableProfile.resolvableProfile(profile));
            });
        });
    }

    /** true se il Mannequin ha gia' addosso le texture di questo nick: niente da riapplicare. */
    private boolean vestita(Mannequin man, String nick) {
        ResolvableProfile p = man.getProfile();
        return p != null && !p.properties().isEmpty() && nick.equalsIgnoreCase(p.name());
    }

    /** Nome del profilo attualmente addosso all'entita' (null se non ne ha). */
    private String profileName(Mannequin man) {
        ResolvableProfile p = man.getProfile();
        return p == null ? null : p.name();
    }

    /** Dimentica le skin risolte e i nick bocciati: al prossimo controllo si riparte da capo. */
    public void forgetSkins() {
        skinCache.clear();
        skinRetry.clear();
    }
}
