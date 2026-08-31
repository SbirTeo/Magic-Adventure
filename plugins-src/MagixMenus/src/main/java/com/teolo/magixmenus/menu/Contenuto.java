package com.teolo.magixmenus.menu;

import com.teolo.magixmenus.util.Testo;
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
public final class Contenuto {

    public enum Fonte {
        /** I giocatori collegati (esclusi quelli che il giocatore non puo' vedere). */
        GIOCATORI_ONLINE,
        /** Un elenco scritto a mano nel file. */
        LISTA,
        /** Un placeholder che restituisce i valori separati dal separatore scelto. */
        PLACEHOLDER;

        /** Il nome con cui questa fonte si scrive nei file. */
        public String nomeFile() {
            return switch (this) {
                case GIOCATORI_ONLINE -> "online_players";
                case LISTA -> "list";
                case PLACEHOLDER -> "placeholder";
            };
        }

        public static Fonte leggi(String s) {
            if (s == null) {
                return null;
            }
            return switch (s.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_')) {
                case "GIOCATORI_ONLINE", "GIOCATORI", "PLAYERS", "ONLINE_PLAYERS" -> GIOCATORI_ONLINE;
                case "LISTA", "LIST", "ELENCO" -> LISTA;
                case "PLACEHOLDER", "SEGNAPOSTO" -> PLACEHOLDER;
                default -> null;
            };
        }
    }

    private final Fonte fonte;
    private final String parametro;
    private final List<String> lista;
    private final String separatore;
    private final List<Integer> caselle;
    private final ItemDef voce;

    Contenuto(Fonte fonte, String parametro, List<String> lista, String separatore,
              List<Integer> caselle, ItemDef voce) {
        this.fonte = fonte;
        this.parametro = parametro;
        this.lista = List.copyOf(lista);
        this.separatore = separatore;
        this.caselle = List.copyOf(caselle);
        this.voce = voce;
    }

    public List<Integer> caselle() {
        return caselle;
    }

    public Fonte fonte() {
        return fonte;
    }

    public String parametro() {
        return parametro;
    }

    public List<String> lista() {
        return lista;
    }

    public String separatore() {
        return separatore;
    }

    public ItemDef voce() {
        return voce;
    }

    /** Quante voci stanno in una pagina. */
    public int perPagina() {
        return Math.max(1, caselle.size());
    }

    /** Le voci, risolte adesso per questo giocatore. */
    public List<String> voci(Player p, Map<String, String> variabili) {
        List<String> out = new ArrayList<>();
        switch (fonte) {
            case GIOCATORI_ONLINE -> {
                for (Player altro : Bukkit.getOnlinePlayers()) {
                    // Chi e' invisibile per questo giocatore non deve comparire in un elenco:
                    // sarebbe un modo per scoprire lo staff in vanish guardando un menu.
                    if (p.canSee(altro)) {
                        out.add(altro.getName());
                    }
                }
                out.sort(String.CASE_INSENSITIVE_ORDER);
            }
            case LISTA -> {
                for (String s : lista) {
                    out.add(Testo.grezzo(p, variabili, s));
                }
            }
            case PLACEHOLDER -> {
                String risolto = Testo.grezzo(p, variabili, parametro);
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
