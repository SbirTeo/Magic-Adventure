package com.teolo.magixmenus.menu;

import com.teolo.magixmenus.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * L'elenco che riempie il menu quando le voci non si sanno in anticipo: i giocatori collegati, le
 * fazioni, una classifica, il catalogo di un negozio.
 *
 * Si dichiarano tre cose: da dove vengono le voci, in quali caselle vanno, e com'e' fatta UNA voce.
 * Il resto — quante pagine servono, cosa mostrare su ognuna — viene da se':
 *
 * <pre>
 * contenuto:
 *   fonte: giocatori_online
 *   slot: 10-16,19-25
 *   voce:
 *     id: PLAYER_HEAD
 *     testa: "%voce%"
 *     nome: "&amp;a%voce%"
 *     azioni:
 *       - "comando: msg %voce% ciao"
 * </pre>
 *
 * Dentro la voce valgono {@code %entry%} (il valore), {@code %entry_index%} (la sua posizione
 * nell'elenco intero, non nella pagina) e tutte le variabili del menu. I vecchi nomi
 * {@code %voce%} e {@code %voce_numero%} restano validi.
 *
 * <h2>Perche' solo tre fonti</h2>
 * {@code giocatori_online}, {@code lista} (scritta a mano nel file) e {@code placeholder} (un
 * placeholder che restituisce dei valori separati da virgola) coprono tutto quello che gli altri
 * plugin del server sanno gia' esporre: MagixFactions, MagixGuard e il sito pubblicano i loro
 * elenchi come placeholder, e attaccarsi a quelli evita di scrivere dentro MagixMenus la
 * conoscenza di ogni altro plugin — che e' poi il motivo per cui i menu di certi plugin
 * funzionano solo con la versione giusta di tutto il resto.
 */
public final class Content {

    public enum Fonte {
        /** I giocatori collegati (esclusi quelli che il giocatore non puo' vedere). */
        ONLINE_PLAYERS,
        /** Un elenco scritto a mano nel file. */
        LIST,
        /** Un placeholder che restituisce i valori separati dal separatore scelto. */
        PLACEHOLDER;

        /** Il nome con cui questa fonte si scrive nei file. */
        public String fileName() {
            return switch (this) {
                case ONLINE_PLAYERS -> "online_players";
                case LIST -> "list";
                case PLACEHOLDER -> "placeholder";
            };
        }

        public static Fonte read(String s) {
            if (s == null) {
                return null;
            }
            return switch (s.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_')) {
                case "GIOCATORI_ONLINE", "GIOCATORI", "PLAYERS", "ONLINE_PLAYERS" -> ONLINE_PLAYERS;
                case "LISTA", "LIST", "ELENCO" -> LIST;
                case "PLACEHOLDER", "SEGNAPOSTO" -> PLACEHOLDER;
                default -> null;
            };
        }
    }

    private final Fonte fonte;
    private final String parametro;
    private final List<String> list;
    private final String separatore;
    private final List<Integer> slots;
    private final ItemDef entry;

    Content(Fonte fonte, String parametro, List<String> list, String separatore,
              List<Integer> slots, ItemDef entry) {
        this.fonte = fonte;
        this.parametro = parametro;
        this.list = List.copyOf(list);
        this.separatore = separatore;
        this.slots = List.copyOf(slots);
        this.entry = entry;
    }

    public List<Integer> slots() {
        return slots;
    }

    public Fonte fonte() {
        return fonte;
    }

    public String parametro() {
        return parametro;
    }

    public List<String> list() {
        return list;
    }

    public String separatore() {
        return separatore;
    }

    public ItemDef entry() {
        return entry;
    }

    /** Quante voci stanno in una pagina. */
    public int perPage() {
        return Math.max(1, slots.size());
    }

    /** Le voci, risolte adesso per questo giocatore. */
    public List<String> entries(Player p, Map<String, String> variabili) {
        List<String> out = new ArrayList<>();
        switch (fonte) {
            case ONLINE_PLAYERS -> {
                for (Player other : Bukkit.getOnlinePlayers()) {
                    // Chi e' invisibile per questo giocatore non deve comparire in un elenco:
                    // sarebbe un modo per scoprire lo staff in vanish guardando un menu.
                    if (p.canSee(other)) {
                        out.add(other.getName());
                    }
                }
                out.sort(String.CASE_INSENSITIVE_ORDER);
            }
            case LIST -> {
                for (String s : list) {
                    out.add(Text.raw(p, variabili, s));
                }
            }
            case PLACEHOLDER -> {
                String risolto = Text.raw(p, variabili, parametro);
                if (!risolto.isBlank()) {
                    for (String pezzo : risolto.split(java.util.regex.Pattern.quote(separatore))) {
                        String v = pezzo.trim();
                        if (!v.isEmpty()) {
                            out.add(v);
                        }
                    }
                }
            }
        }
        return out;
    }
}
