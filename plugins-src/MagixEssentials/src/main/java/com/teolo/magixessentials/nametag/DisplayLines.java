package com.teolo.magixessentials.nametag;

import com.teolo.magixessentials.util.TextFormat;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Le righe della targhetta disegnate <b>da noi</b>: una entita' di testo ({@code text_display}) per
 * riga, agganciata al giocatore come un passeggero.
 *
 * <h2>Perche' non basta la targhetta del gioco</h2>
 * Quella e' una riga sola, e il nome vero che porta in mezzo accetta solo i 16 colori storici (vedi
 * {@link NameTeams}). Due righe sopra la testa, un esadecimale o una sfumatura sul nome, una misura
 * diversa: nel protocollo della targhetta non esistono. Esistono invece per le entita' di testo, che
 * sono oggetti del mondo e si possono scrivere come si vuole.
 *
 * <h2>Agganciate, non inseguite</h2>
 * Le righe non vengono teletrasportate dietro al giocatore a ogni tick: si <b>montano</b> su di lui
 * come un passeggero, e da quel momento e' il client a farle seguire il movimento, senza scatti e
 * senza lavoro per il server. L'altezza si da' con la trasformazione dell'entita' (una traslazione
 * verso l'alto), non con la posizione.
 *
 * <h2>Non lasciano niente in giro</h2>
 * Ogni riga nasce con un <b>marchio</b> ({@link #TAG}) e con "non salvare nel mondo" acceso: allo
 * spegnimento le tolgono, e all'avvio si fa una passata a cercare quelle marchiate rimaste in giro
 * (un {@code /reload} a caldo, o un crash) e si buttano. Una targhetta orfana che galleggia in mezzo
 * al mondo e' il difetto peggiore di questo modo di fare le cose: si paga una volta, all'avvio.
 *
 * <h2>Diversa per chi guarda, anche se e' un oggetto del mondo</h2>
 * Un'entita' del mondo la vedono tutti uguale: e' vero del singolo oggetto, non di cosa <b>mostriamo</b>.
 * Quando le righe cambiano da spettatore a spettatore (i {@code %rel_...%}: il verde dell'alleato, il
 * rosso del nemico) non c'e' <b>una</b> targhetta ma un gruppo per ciascun testo diverso — tutti
 * montati sullo stesso giocatore, nello stesso punto — e a ognuno si <b>nasconde</b> quello che non e'
 * il suo ({@link Variant}). Gli spettatori con lo stesso testo condividono un gruppo solo: quasi
 * sempre e' uno, e diventano pochi solo quando i colori in gioco sono pochi. I passeggeri nascosti non
 * occupano posto — il client non li conosce nemmeno — quindi ciascuno vede solo le sue righe, dove
 * vanno. Resta un limite del mezzo, non piu' della relazione: {@code hide-self} nasconde a ciascuno la
 * propria.
 */
public final class DisplayLines {

    /** Il marchio delle nostre entita': serve a ritrovarle, anche dopo un crash. */
    private static final String TAG = "magixessentials-nametag";

    /**
     * Un testo (le righe, dall'alto verso il basso) e <b>chi lo deve vedere</b>. Un {@code viewers}
     * nullo vuol dire "tutti" (la targhetta uguale per ogni spettatore); un insieme di UUID vuol dire
     * solo quei giocatori — e' cosi' che il colore relazionale diventa diverso per chi guarda.
     */
    public record Variant(List<String> lines, Set<UUID> viewers) {
    }

    /** Un gruppo di righe montate, con l'insieme di chi le vede ({@code null} = tutti). */
    private static final class Group {
        final List<TextDisplay> rows;
        Set<UUID> viewers;

        Group(List<TextDisplay> rows, Set<UUID> viewers) {
            this.rows = rows;
            this.viewers = viewers;
        }
    }

    private final JavaPlugin plugin;
    /** Le impostazioni della modalita' display: la sezione {@code display} del nametag.yml. */
    private final ConfigurationSection cfg;

    /**
     * Per ciascun giocatore, i gruppi montati su di lui, uno per ogni testo diverso in gioco. La
     * chiave e' il testo (le righe): due spettatori che vedono lo stesso testo condividono il gruppo.
     */
    private final Map<UUID, Map<List<String>, Group>> mounted = new HashMap<>();
    /** L'ultima opacita' scritta (vedi {@link NametagManager#opacity}): si riscrive solo alla differenza. */
    private final Map<UUID, Byte> writtenOpacity = new HashMap<>();
    /**
     * L'ultimo "attraversa i muri" scritto (vedi {@link NametagManager#seeThrough}): si riscrive solo
     * alla differenza. Come vanilla cambia quando il giocatore si accuccia, quindi va aggiornato a caldo.
     */
    private final Map<UUID, Boolean> writtenSeeThrough = new HashMap<>();

    private double height = 0.8;
    private double spacing = 0.29;
    private double scale = 1.0;
    private boolean shadow;
    /** Lo sfondo: {@code null} = quello del gioco, altrimenti il colore scelto (trasparente compreso). */
    private Color background;
    private float viewRange = 1.0f;
    private boolean hideSelf = true;

    public DisplayLines(JavaPlugin plugin, ConfigurationSection cfg) {
        this.plugin = plugin;
        this.cfg = cfg;
    }

    /** Se ciascuno non deve vedere la propria targhetta. Lo legge il manager per non mettersi fra i viewer. */
    public boolean hideSelf() {
        return hideSelf;
    }

    /** Rilegge le impostazioni e ripulisce le righe rimaste in giro da un avvio precedente. */
    public void load() {
        if (cfg != null) {
            height = cfg.getDouble("height", 0.8);
            spacing = cfg.getDouble("line-spacing", 0.29);
            scale = cfg.getDouble("scale", 1.0);
            shadow = cfg.getBoolean("text-shadow", false);
            background = readBackground(cfg.getString("background", "default"));
            viewRange = (float) cfg.getDouble("view-range", 1.0);
            hideSelf = cfg.getBoolean("hide-self", true);
        }
        sweep();
    }

    /**
     * La targhetta uguale per tutti: una lista di righe sola, mostrata a ogni spettatore (tranne la
     * propria, con {@code hide-self}). Una lista vuota la toglie.
     */
    public void update(Player target, List<String> lines, byte opacity, boolean seeThrough) {
        if (lines.isEmpty()) {
            remove(target);
            return;
        }
        apply(target, List.of(new Variant(List.copyOf(lines), null)), opacity, seeThrough);
    }

    /**
     * La targhetta <b>diversa per chi guarda</b>: una variante per ogni testo, ciascuna coi suoi
     * spettatori (vedi {@link Variant}). Una lista vuota la toglie. Ci pensa il chiamante a mettere lo
     * stesso testo una volta sola, con l'insieme degli spettatori che lo vedono.
     */
    public void updateVariants(Player target, List<Variant> variants, byte opacity, boolean seeThrough) {
        if (variants.isEmpty()) {
            remove(target);
            return;
        }
        apply(target, variants, opacity, seeThrough);
    }

    /**
     * Il cuore: porta i gruppi montati sul giocatore a coincidere con le varianti chieste. Crea i
     * gruppi nuovi, toglie quelli che non servono piu' (o rimasti nel mondo sbagliato), e per quelli
     * che restano aggiorna l'opacita' e chi li vede. Si rifa' da zero solo il gruppo che serve, non
     * tutta la targhetta.
     */
    private void apply(Player target, List<Variant> variants, byte opacity, boolean seeThrough) {
        UUID id = target.getUniqueId();
        World world = target.getWorld();
        Map<List<String>, Group> groups = mounted.computeIfAbsent(id, k -> new LinkedHashMap<>());

        // Il testo voluto, con chi lo vede. Se due varianti hanno lo stesso testo si fondono i loro
        // spettatori (e "tutti" vince: se una lo vuole per tutti, e' per tutti).
        Map<List<String>, Set<UUID>> want = new LinkedHashMap<>();
        for (Variant v : variants) {
            List<String> key = List.copyOf(v.lines());
            if (want.containsKey(key)) {
                Set<UUID> have = want.get(key);
                if (have != null && v.viewers() != null) {
                    have.addAll(v.viewers());
                } else {
                    want.put(key, null);   // uno dei due e' "tutti"
                }
            } else {
                want.put(key, v.viewers() == null ? null : new java.util.HashSet<>(v.viewers()));
            }
        }

        // Via i gruppi che non servono piu', o morti, o finiti nel mondo sbagliato (l'entita' vive nel
        // mondo in cui e' nata: cambiato mondo, va rifatta di la').
        Iterator<Map.Entry<List<String>, Group>> it = groups.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<List<String>, Group> e = it.next();
            if (!want.containsKey(e.getKey()) || !alive(e.getValue().rows, world)) {
                for (TextDisplay row : e.getValue().rows) {
                    row.remove();
                }
                it.remove();
            }
        }

        // I gruppi nuovi: si creano dal loro testo. Se una riga non nasce si annulla quel gruppo (meglio
        // una variante in meno che una a meta'), ma gli altri gruppi restano.
        for (Map.Entry<List<String>, Set<UUID>> e : want.entrySet()) {
            if (groups.containsKey(e.getKey())) {
                continue;
            }
            List<String> lines = e.getKey();
            List<TextDisplay> rows = new ArrayList<>(lines.size());
            boolean ok = true;
            for (int i = 0; i < lines.size(); i++) {
                TextDisplay row = spawn(target, i, lines.size(), lines.get(i), opacity, seeThrough);
                if (row == null) {
                    ok = false;
                    break;
                }
                rows.add(row);
            }
            if (!ok) {
                for (TextDisplay row : rows) {
                    row.remove();
                }
                continue;
            }
            groups.put(e.getKey(), new Group(rows, e.getValue()));
        }

        // Opacita', "attraversa i muri" e visibilita' per i gruppi che restano.
        boolean opacityChanged = !Byte.valueOf(opacity).equals(writtenOpacity.get(id));
        boolean seeThroughChanged = !Boolean.valueOf(seeThrough).equals(writtenSeeThrough.get(id));
        for (Map.Entry<List<String>, Group> e : groups.entrySet()) {
            Group g = e.getValue();
            g.viewers = want.get(e.getKey());
            reconcile(target, g, opacityChanged, opacity, seeThroughChanged, seeThrough);
        }
        writtenOpacity.put(id, opacity);
        writtenSeeThrough.put(id, seeThrough);
    }

    /**
     * Aggiorna un gruppo: riscrive l'opacita' se e' cambiata, rimonta le righe cadute (la morte, un
     * teletrasporto, una barca buttano giu' i passeggeri) e sistema <b>chi lo vede</b>. Con
     * {@code viewers} nullo e' di tutti (solo la propria si nasconde, se {@code hide-self}); con un
     * insieme, si mostra a chi c'e' dentro e si nasconde a tutti gli altri. Sia {@code showEntity} sia
     * {@code hideEntity} non fanno niente se lo stato e' gia' quello, quindi ripassarli a ogni giro non
     * costa pacchetti: e' anche cosi' che un nuovo arrivato smette di vedere le varianti che non sono
     * la sua.
     */
    private void reconcile(Player target, Group g, boolean opacityChanged, byte opacity,
                           boolean seeThroughChanged, boolean seeThrough) {
        for (TextDisplay row : g.rows) {
            if (opacityChanged) {
                row.setTextOpacity(opacity);
            }
            if (seeThroughChanged) {
                row.setSeeThrough(seeThrough);
            }
            if (!target.getPassengers().contains(row)) {
                target.addPassenger(row);
            }
        }
        if (g.viewers == null) {
            if (hideSelf) {
                for (TextDisplay row : g.rows) {
                    target.hideEntity(plugin, row);
                }
            }
            return;
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            boolean canSee = g.viewers.contains(p.getUniqueId());
            for (TextDisplay row : g.rows) {
                if (canSee) {
                    p.showEntity(plugin, row);
                } else {
                    p.hideEntity(plugin, row);
                }
            }
        }
    }

    /** Toglie le righe di un giocatore. */
    public void remove(Player target) {
        Map<List<String>, Group> groups = mounted.remove(target.getUniqueId());
        writtenOpacity.remove(target.getUniqueId());
        writtenSeeThrough.remove(target.getUniqueId());
        if (groups == null) {
            return;
        }
        for (Group g : groups.values()) {
            for (TextDisplay row : g.rows) {
                row.remove();
            }
        }
    }

    /** Toglie tutte le righe di tutti: allo spegnimento del modulo non resta niente appeso. */
    public void clear() {
        for (Map<List<String>, Group> groups : mounted.values()) {
            for (Group g : groups.values()) {
                for (TextDisplay row : g.rows) {
                    row.remove();
                }
            }
        }
        mounted.clear();
        writtenOpacity.clear();
        writtenSeeThrough.clear();
        sweep();
    }

    /**
     * Butta le entita' marchiate che sono in giro nei mondi caricati: quelle rimaste da un
     * {@code /reload} a caldo o da un crash, che nessuno aggiorna piu' e che nessuno toglierebbe.
     */
    private void sweep() {
        int found = 0;
        for (World world : Bukkit.getWorlds()) {
            for (TextDisplay row : world.getEntitiesByClass(TextDisplay.class)) {
                if (row.getScoreboardTags().contains(TAG)) {
                    row.remove();
                    found++;
                }
            }
        }
        if (found > 0) {
            plugin.getLogger().info("[Nametag] tolte " + found + " righe rimaste da prima.");
        }
    }

    /** Se tutte le righe sono ancora vive e nel mondo del giocatore. */
    private boolean alive(List<TextDisplay> rows, World world) {
        for (TextDisplay row : rows) {
            if (!row.isValid() || row.getWorld() != world) {
                return false;
            }
        }
        return true;
    }

    /**
     * Crea una riga e la monta sul giocatore. {@code index} e' la posizione dall'alto (0 = la riga
     * piu' in alto), {@code total} quante sono: la piu' bassa sta a {@code height}, le altre una
     * {@code line-spacing} sopra l'altra. Chi la vede lo decide dopo {@link #reconcile}: qui nasce e
     * basta.
     */
    private TextDisplay spawn(Player target, int index, int total, String line, byte opacity,
                             boolean seeThrough) {
        double y = height + (total - 1 - index) * spacing;
        TextDisplay row = target.getWorld().spawn(target.getLocation(), TextDisplay.class, e -> {
            e.addScoreboardTag(TAG);
            // Non finisce nei file del mondo: se il server cade, non si risveglia una targhetta orfana.
            e.setPersistent(false);
            e.text(TextFormat.component(line));
            e.setTextOpacity(opacity);
            e.setBillboard(Display.Billboard.CENTER);      // sempre girata verso chi guarda
            e.setAlignment(TextDisplay.TextAlignment.CENTER);
            e.setSeeThrough(seeThrough);
            e.setShadowed(shadow);
            e.setViewRange(viewRange);
            // Luce piena, come la targhetta del gioco: altrimenti di notte o in una grotta il testo
            // si spegne insieme al blocco su cui capita.
            e.setBrightness(new Display.Brightness(15, 15));
            e.setTransformation(new Transformation(
                    new Vector3f(0f, (float) y, 0f), new AxisAngle4f(),
                    new Vector3f((float) scale, (float) scale, (float) scale), new AxisAngle4f()));
            // Lo sfondo scelto una volta all'avvio: null = il rettangolo scuro del gioco.
            e.setDefaultBackground(background == null);
            if (background != null) {
                e.setBackgroundColor(background);
            }
        });
        if (!target.addPassenger(row)) {
            row.remove();
            return null;
        }
        return row;
    }

    /**
     * Lo sfondo dietro il testo, letto una volta all'avvio: {@code default} il rettangolo scuro del
     * gioco (torna {@code null}), {@code none} niente sfondo (nero del tutto trasparente), oppure un
     * {@code #AARRGGBB} — la trasparenza davanti, come la scrive il gioco.
     */
    private Color readBackground(String value) {
        String text = value == null ? "default" : value.trim().toLowerCase(Locale.ROOT);
        if (text.equals("default")) {
            return null;
        }
        if (text.equals("none")) {
            return Color.fromARGB(0, 0, 0, 0);
        }
        try {
            long argb = Long.parseLong(text.replace("#", ""), 16);
            return Color.fromARGB((int) (argb >>> 24) & 0xFF, (int) (argb >>> 16) & 0xFF,
                    (int) (argb >>> 8) & 0xFF, (int) argb & 0xFF);
        } catch (NumberFormatException e) {
            // Uno sfondo scritto male non deve far sparire la targhetta: resta quello del gioco, e il
            // log dice, una volta sola, quale valore non si e' capito.
            plugin.getLogger().warning("[Nametag] display.background: non capisco «" + value
                    + "» (vale 'default', 'none' o #AARRGGBB): tengo lo sfondo del gioco.");
            return null;
        }
    }
}
