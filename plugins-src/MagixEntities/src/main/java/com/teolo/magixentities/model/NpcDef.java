package com.teolo.magixentities.model;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Definizione persistente di un'entita' creata dal plugin (una riga di entities.yml).
 * E' un semplice contenitore di dati: la logica sta in NpcManager.
 */
public final class NpcDef {

    /** Opzioni booleane modificabili con /mentities set. */
    public static final List<String> OPTIONS = List.of(
            "invulnerable", "nametag", "ai", "gravity", "silent", "glowing", "collidable",
            "immovable", "interact", "follow");

    /** Chiave univoca (nome in minuscolo). Cambia con /mentities name. */
    public String id;
    /** Nome come scritto dall'utente; per il tipo player e' anche il nick della skin di default. */
    public String name;
    public EntityType type;
    /** Nick della skin (solo tipo player); se null si usa {@link #name}. */
    public String skin;
    /** Displayname sopra la testa, con codici &. Se null segue il nome dell'entita'. */
    public String display;

    public String world;
    public double x, y, z;
    public float yaw, pitch;

    /** UUID dell'entita' viva nel mondo; null se non e' mai stata creata. */
    public UUID uuid;
    /** Posa (solo tipo player/Mannequin), es. STANDING, SITTING, SLEEPING. */
    public String pose;
    /** Scala dell'entita' (1.0 = normale). Vale per qualunque tipo, non solo player. */
    public double scale = 1.0;
    /** Raggio del follow di QUESTA entita', in blocchi; null = quello del config (follow.radius). */
    public Double followRadius;
    /**
     * UUID del sedile invisibile usato dalla posa "sitting" (vedi NpcManager#applySeat); null se
     * non serve. Solo per l'entita' vera, mai per le copie mirror (vedi il commento in apply()).
     */
    public UUID seatUuid;

    public final Map<String, Boolean> options = new LinkedHashMap<>();

    /**
     * Comandi eseguiti quando un giocatore clicca l'entita', in ordine. Prefissi:
     * {@code console:} esegue da console, {@code msg:} manda un messaggio al giocatore
     * ({@code \n} o {@code %nl%} vanno a capo), nessun prefisso = comando eseguito dal
     * giocatore. Placeholder {player} e {name}.
     */
    public final List<String> commands = new ArrayList<>();

    /** Chiavi dell'equipaggiamento, nell'ordine in cui compaiono nel menu /mentities equip. */
    public static final List<String> EQUIPMENT =
            List.of("helmet", "chestplate", "leggings", "boots", "hand", "offhand");

    /** Equipaggiamento indossato dall'entita' (chiavi di {@link #EQUIPMENT}). */
    public final Map<String, ItemStack> equipment = new LinkedHashMap<>();

    public NpcDef(String name, EntityType type) {
        this.name = name;
        this.id = name.toLowerCase(Locale.ROOT);
        this.type = type;
    }

    /**
     * Parola riservata per la modalita' specchio: su {@link #skin} ognuno vede la PROPRIA skin,
     * su {@link #display} ognuno vede il PROPRIO nome sopra la testa.
     */
    public static final String MIRROR = "mirror";

    public boolean isPlayerType() {
        return type == EntityType.MANNEQUIN;
    }

    /** Skin a specchio: solo per il tipo player. */
    public boolean isSkinMirror() {
        return isPlayerType() && MIRROR.equalsIgnoreCase(skin);
    }

    /** Displayname a specchio: vale per qualsiasi tipo di entita'. */
    public boolean isDisplayMirror() {
        return MIRROR.equalsIgnoreCase(display);
    }

    /** true se l'entita' va mostrata come copia personalizzata per ogni giocatore. */
    public boolean needsClones() {
        return hidesReal() || isFollowPersonal();
    }

    /**
     * true se l'entita' vera va nascosta a TUTTI: skin o nome a specchio, dove l'entita' vera non ha
     * nulla di giusto da mostrare a nessuno.
     */
    public boolean hidesReal() {
        return isSkinMirror() || isDisplayMirror();
    }

    /**
     * Opzione "follow" su un'entita' senza specchio: ognuno vicino vede una sua copia che guarda LUI
     * (non il giocatore piu' vicino: una testa sola non puo' guardare due persone). Da lontano si vede
     * l'entita' vera, nascosta solo a chi ha la sua copia.
     */
    /** Il raggio del follow di questa entita': il suo, o {@code fallback} (follow.radius) se non ne ha uno. */
    public double followRadiusOr(double fallback) {
        return followRadius != null ? followRadius : fallback;
    }

    public boolean isFollowPersonal() {
        return opt("follow", false) && !hidesReal();
    }

    /** Nick usato per la skin (solo tipo player). */
    public String skinNick() {
        return (skin == null || skin.isBlank()) ? name : skin;
    }

    /**
     * Testo mostrato sopra la testa: il displayname se impostato, altrimenti il nome.
     * In modalita' specchio e' un segnaposto: la scritta vera la mette MirrorManager,
     * ed e' il nome del giocatore che sta guardando.
     */
    public String displayText() {
        if (display == null || display.isBlank() || isDisplayMirror()) return name;
        return display;
    }

    /** true se il displayname e' stato impostato a mano (non segue piu' il nome). */
    public boolean hasCustomDisplay() {
        return display != null && !display.isBlank();
    }

    /** Rinomina l'entita' (cambia anche la chiave: il chiamante deve ri-registrarla nella mappa). */
    public void rename(String newName) {
        this.name = newName;
        this.id = newName.toLowerCase(Locale.ROOT);
    }

    public boolean opt(String key, boolean def) {
        Boolean b = options.get(key);
        return b == null ? def : b;
    }

    public void setLocation(Location loc) {
        this.world = loc.getWorld().getName();
        this.x = loc.getX();
        this.y = loc.getY();
        this.z = loc.getZ();
        this.yaw = loc.getYaw();
        this.pitch = loc.getPitch();
    }

    /** Posizione salvata, o null se il mondo non e' caricato. */
    public Location location() {
        World w = Bukkit.getWorld(world);
        return w == null ? null : new Location(w, x, y, z, yaw, pitch);
    }

    /** true se il chunk in cui vive l'entita' e' caricato (fuori dal chunk non possiamo verificarla). */
    public boolean chunkLoaded() {
        World w = Bukkit.getWorld(world);
        return w != null && w.isChunkLoaded(((int) Math.floor(x)) >> 4, ((int) Math.floor(z)) >> 4);
    }
}
