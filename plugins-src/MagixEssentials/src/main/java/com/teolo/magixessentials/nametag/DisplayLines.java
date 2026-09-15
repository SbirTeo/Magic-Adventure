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
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 * <h2>Il limite, detto chiaro</h2>
 * Un oggetto del mondo lo vedono tutti uguale: qui una targhetta <b>diversa per spettatore</b> non e'
 * possibile — i {@code %rel_...%} vogliono la targhetta del gioco. Si puo' solo nasconderla del tutto
 * a qualcuno, ed e' cosi' che ciascuno non vede la propria.
 */
public final class DisplayLines {

    /** Il marchio delle nostre entita': serve a ritrovarle, anche dopo un crash. */
    private static final String TAG = "magixessentials-nametag";

    private final JavaPlugin plugin;
    /** Le impostazioni della modalita' display: la sezione {@code display} del nametag.yml. */
    private final ConfigurationSection cfg;

    /** Le righe montate su ciascun giocatore, dall'alto verso il basso. */
    private final Map<UUID, List<TextDisplay>> mounted = new HashMap<>();
    /** L'ultimo testo scritto su ciascuna riga: si riscrive solo alla differenza. */
    private final Map<UUID, List<String>> written = new HashMap<>();

    private double height = 0.8;
    private double spacing = 0.29;
    private double scale = 1.0;
    private boolean seeThrough;
    private boolean shadow;
    /** Lo sfondo: {@code null} = quello del gioco, altrimenti il colore scelto (trasparente compreso). */
    private Color background;
    private float viewRange = 1.0f;
    private boolean hideSelf = true;

    public DisplayLines(JavaPlugin plugin, ConfigurationSection cfg) {
        this.plugin = plugin;
        this.cfg = cfg;
    }

    /** Rilegge le impostazioni e ripulisce le righe rimaste in giro da un avvio precedente. */
    public void load() {
        if (cfg != null) {
            height = cfg.getDouble("height", 0.8);
            spacing = cfg.getDouble("line-spacing", 0.29);
            scale = cfg.getDouble("scale", 1.0);
            seeThrough = cfg.getBoolean("see-through", false);
            shadow = cfg.getBoolean("text-shadow", false);
            background = readBackground(cfg.getString("background", "default"));
            viewRange = (float) cfg.getDouble("view-range", 1.0);
            hideSelf = cfg.getBoolean("hide-self", true);
        }
        sweep();
    }

    /**
     * Le righe di un giocatore, dall'alto verso il basso, gia' coi placeholder risolti. Una lista
     * vuota le toglie: e' cosi' che sparisce la targhetta di chi si accuccia o e' invisibile.
     */
    public void update(Player target, List<String> lines) {
        if (lines.isEmpty()) {
            remove(target);
            return;
        }
        List<TextDisplay> rows = mounted.get(target.getUniqueId());
        // Si rifa' da zero quando cambia il numero delle righe, quando una e' stata portata via (un
        // altro plugin, un chunk scaricato) o quando il giocatore ha cambiato mondo: un'entita' vive
        // nel mondo in cui e' nata, e li' resterebbe.
        if (rows == null || rows.size() != lines.size() || !alive(rows, target.getWorld())) {
            remove(target);
            rows = new ArrayList<>();
            for (int i = 0; i < lines.size(); i++) {
                TextDisplay row = spawn(target, i, lines.size(), lines.get(i));
                if (row == null) {
                    // Non e' nata: si annulla tutto il gruppo, meglio niente targhetta che una a meta'.
                    for (TextDisplay done : rows) {
                        done.remove();
                    }
                    return;
                }
                rows.add(row);
            }
            mounted.put(target.getUniqueId(), rows);
            written.put(target.getUniqueId(), new ArrayList<>(lines));
            return;
        }
        List<String> before = written.computeIfAbsent(target.getUniqueId(), id -> new ArrayList<>());
        for (int i = 0; i < rows.size(); i++) {
            TextDisplay row = rows.get(i);
            if (i >= before.size() || !lines.get(i).equals(before.get(i))) {
                row.text(TextFormat.component(lines.get(i)));
            }
            // Il passeggero viene buttato giu' da parecchie cose (la morte, un teletrasporto, una
            // barca): rimontarlo qui e' piu' semplice che inseguire ogni caso con un evento suo.
            if (!target.getPassengers().contains(row)) {
                target.addPassenger(row);
            }
        }
        written.put(target.getUniqueId(), new ArrayList<>(lines));
    }

    /** Toglie le righe di un giocatore. */
    public void remove(Player target) {
        List<TextDisplay> rows = mounted.remove(target.getUniqueId());
        written.remove(target.getUniqueId());
        if (rows == null) {
            return;
        }
        for (TextDisplay row : rows) {
            row.remove();
        }
    }

    /** Toglie tutte le righe di tutti: allo spegnimento del modulo non resta niente appeso. */
    public void clear() {
        for (List<TextDisplay> rows : mounted.values()) {
            for (TextDisplay row : rows) {
                row.remove();
            }
        }
        mounted.clear();
        written.clear();
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
     * {@code line-spacing} sopra l'altra.
     */
    private TextDisplay spawn(Player target, int index, int total, String line) {
        double y = height + (total - 1 - index) * spacing;
        TextDisplay row = target.getWorld().spawn(target.getLocation(), TextDisplay.class, e -> {
            e.addScoreboardTag(TAG);
            // Non finisce nei file del mondo: se il server cade, non si risveglia una targhetta orfana.
            e.setPersistent(false);
            e.text(TextFormat.component(line));
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
        // La propria targhetta, in terza persona, si vedrebbe da dietro le spalle: e' l'unica cosa
        // che di un oggetto del mondo si puo' rendere diversa da spettatore a spettatore.
        if (hideSelf) {
            target.hideEntity(plugin, row);
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
            plugin.getLogger().warning("[Nametag] display.background: non capisco \u00ab" + value
                    + "\u00bb (vale 'default', 'none' o #AARRGGBB): tengo lo sfondo del gioco.");
            return null;
        }
    }
}
