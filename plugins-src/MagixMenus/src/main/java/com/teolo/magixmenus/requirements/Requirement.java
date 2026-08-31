package com.teolo.magixmenus.requirements;

import com.teolo.magixmenus.hook.EconomyHook;
import com.teolo.magixmenus.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Una condizione singola: "ha questo permesso", "ha almeno mille monete", "questo numero e'
 * maggiore di quello".
 *
 * Serve in due posti che sembrano uguali ma non lo sono:
 * <ul>
 *   <li><b>mostra_se</b> — decide se l'item si VEDE. Se non e' soddisfatto, la casella resta
 *       vuota (o la prende l'item successivo, vedi la priorita' in {@code ItemDef});</li>
 *   <li><b>click_se</b> — decide se il clic ha EFFETTO. L'item si vede lo stesso, e chi clicca
 *       riceve la spiegazione scritta in {@code azioni_negate}.</li>
 * </ul>
 * Sono due gesti diversi: nascondere un bottone e dire "non puoi ancora" non si equivalgono, e
 * un menu fatto bene usa il secondo molto piu' del primo.
 *
 * <h2>La chiave "uguale"</h2>
 * Ogni requisito ha {@code uguale} (in altri plugin: {@code match}): a {@code false} il requisito
 * si ribalta. "Non ha il permesso", "il numero NON e' maggiore di dieci". Evita di dover scrivere
 * la condizione contraria, che e' l'occasione tipica per sbagliarla.
 */
public record Requirement(Tipo tipo, String chiave, String valore, int quantita, boolean uguale) {

    /** I tipi di condizione. Fra parentesi il nome accettato in alternativa, per chi arriva da altri plugin. */
    public enum Tipo {
        /** Il giocatore ha il permesso scritto in "chiave". */
        PERMESSO,
        /** La condizione scritta in "chiave" e' vera: "%vault_eco_balance% >= 1000". Vedi {@link Equation}. */
        EQUAZIONE,
        /** "chiave" e "valore" sono la stessa cosa (maiuscole ignorate). */
        TESTO_UGUALE,
        /** "chiave" contiene "valore". */
        TESTO_CONTIENE,
        /** "chiave" corrisponde all'espressione regolare in "valore". */
        REGEX,
        /** Il giocatore ha in inventario "quantita" item di tipo "chiave" (e col nome in "valore", se scritto). */
        HA_ITEM,
        /** Il giocatore ha almeno "quantita" monete (serve Vault). */
        HA_SOLDI,
        /** Il giocatore ha almeno "quantita" livelli di esperienza. */
        HA_LIVELLI,
        /** Il giocatore si trova in uno dei mondi elencati in "chiave" (separati da virgola). */
        MONDO,
        /** Nell'inventario ci sono almeno "quantita" caselle libere. */
        POSTO_LIBERO;

        /** Il nome con cui questo tipo si scrive nei file (inglese, come tutte le chiavi). */
        public String nomeFile() {
            return switch (this) {
                case PERMESSO -> "PERMISSION";
                case EQUAZIONE -> "EQUATION";
                case TESTO_UGUALE -> "STRING_EQUALS";
                case TESTO_CONTIENE -> "STRING_CONTAINS";
                case REGEX -> "REGEX";
                case HA_ITEM -> "HAS_ITEM";
                case HA_SOLDI -> "HAS_MONEY";
                case HA_LIVELLI -> "HAS_LEVEL";
                case MONDO -> "WORLD";
                case POSTO_LIBERO -> "EMPTY_SLOTS";
            };
        }

        /** Il nome scritto nel file: quello inglese, o il vecchio nome italiano. */
        public static Tipo leggi(String s) {
            if (s == null) {
                return null;
            }
            String n = s.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
            return switch (n) {
                case "PERMESSO", "PERMISSION", "HAS_PERMISSION" -> PERMESSO;
                case "EQUAZIONE", "EQUATION", "MATH", "EXPRESSION" -> EQUAZIONE;
                case "TESTO_UGUALE", "STRING_EQUALS", "STRING_EQUALS_IGNORECASE" -> TESTO_UGUALE;
                case "TESTO_CONTIENE", "STRING_CONTAINS" -> TESTO_CONTIENE;
                case "REGEX", "REGEX_MATCHES" -> REGEX;
                case "HA_ITEM", "HAS_ITEM" -> HA_ITEM;
                case "HA_SOLDI", "HAS_MONEY" -> HA_SOLDI;
                case "HA_LIVELLI", "HAS_LEVEL", "HAS_EXP" -> HA_LIVELLI;
                case "MONDO", "WORLD", "IS_IN_WORLD" -> MONDO;
                case "POSTO_LIBERO", "HAS_EMPTY_SLOTS", "EMPTY_SLOTS" -> POSTO_LIBERO;
                default -> null;
            };
        }
    }

    /**
     * Il requisito e' soddisfatto?
     *
     * I placeholder si risolvono qui, non al caricamento: il saldo di un giocatore cambia fra
     * l'apertura del menu e il clic, ed e' proprio quel cambiamento che il requisito deve vedere.
     */
    public boolean soddisfatto(Player p, Map<String, String> variabili) {
        return vero(p, variabili) == uguale;
    }

    private boolean vero(Player p, Map<String, String> variabili) {
        String k = Text.grezzo(p, variabili, chiave);
        String v = Text.grezzo(p, variabili, valore);

        return switch (tipo) {
            case PERMESSO -> !k.isEmpty() && p.hasPermission(k);

            case EQUAZIONE -> {
                try {
                    yield Equation.vera(k);
                } catch (Equation.Errore e) {
                    // Una condizione scritta male non deve far sparire mezzo menu senza spiegazione:
                    // vale "no", ma l'errore finisce nel log con la condizione gia' risolta, che e'
                    // l'unica forma in cui si capisce davvero cosa non torna.
                    org.bukkit.Bukkit.getLogger().warning("[MagixMenus] condizione non valida \""
                            + chiave + "\" (diventata \"" + k + "\"): " + e.getMessage());
                    yield false;
                }
            }

            case TESTO_UGUALE -> k.equalsIgnoreCase(v);
            case TESTO_CONTIENE -> k.toLowerCase(Locale.ROOT).contains(v.toLowerCase(Locale.ROOT));

            case REGEX -> {
                try {
                    yield Pattern.compile(v).matcher(k).matches();
                } catch (PatternSyntaxException e) {
                    org.bukkit.Bukkit.getLogger().warning("[MagixMenus] espressione regolare non valida \""
                            + valore + "\": " + e.getDescription());
                    yield false;
                }
            }

            case HA_ITEM -> haItem(p, k, v);
            case HA_SOLDI -> EconomyHook.disponibile() && EconomyHook.saldo(p) >= quantita;
            case HA_LIVELLI -> p.getLevel() >= quantita;

            case MONDO -> {
                String mondo = p.getWorld().getName();
                for (String uno : k.split(",")) {
                    if (uno.trim().equalsIgnoreCase(mondo)) {
                        yield true;
                    }
                }
                yield false;
            }

            case POSTO_LIBERO -> {
                int liberi = 0;
                for (ItemStack s : p.getInventory().getStorageContents()) {
                    if (s == null || s.getType().isAir()) {
                        liberi++;
                    }
                }
                yield liberi >= Math.max(1, quantita);
            }
        };
    }

    /** Conta gli item del tipo chiesto, tenendo conto del nome se e' stato scritto. */
    private boolean haItem(Player p, String materiale, String nome) {
        Material m = Material.matchMaterial(materiale);
        if (m == null) {
            org.bukkit.Bukkit.getLogger().warning("[MagixMenus] requisito HA_ITEM: non esiste l'item \""
                    + materiale + "\".");
            return false;
        }
        int trovati = 0;
        for (ItemStack s : p.getInventory().getStorageContents()) {
            if (s == null || s.getType() != m) {
                continue;
            }
            if (!nome.isEmpty()) {
                ItemMeta meta = s.getItemMeta();
                String suo = meta == null ? "" : org.bukkit.ChatColor.stripColor(
                        net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                                .legacySection().serialize(meta.displayName() == null
                                        ? net.kyori.adventure.text.Component.empty() : meta.displayName()));
                if (!suo.equalsIgnoreCase(org.bukkit.ChatColor.stripColor(
                        com.teolo.magixmenus.util.Colors.translate(nome)))) {
                    continue;
                }
            }
            trovati += s.getAmount();
            if (trovati >= Math.max(1, quantita)) {
                return true;
            }
        }
        return trovati >= Math.max(1, quantita);
    }

    /** Come si legge questo requisito, per il log e per la guida. */
    public String descrizione() {
        String base = switch (tipo) {
            case PERMESSO -> "ha il permesso " + chiave;
            case EQUAZIONE -> "e' vero che " + chiave;
            case TESTO_UGUALE -> chiave + " e' uguale a " + valore;
            case TESTO_CONTIENE -> chiave + " contiene " + valore;
            case REGEX -> chiave + " corrisponde a " + valore;
            case HA_ITEM -> "ha " + quantita + " " + chiave;
            case HA_SOLDI -> "ha almeno " + quantita + " monete";
            case HA_LIVELLI -> "ha almeno " + quantita + " livelli";
            case MONDO -> "si trova in " + chiave;
            case POSTO_LIBERO -> "ha " + quantita + " caselle libere";
        };
        return uguale ? base : "NON " + base;
    }

    /** Elenco dei tipi, per i messaggi d'errore del caricatore e per l'editor sul sito. */
    public static List<String> tipiDisponibili() {
        List<String> out = new java.util.ArrayList<>();
        for (Tipo t : Tipo.values()) {
            out.add(t.nomeFile());
        }
        return out;
    }
}
