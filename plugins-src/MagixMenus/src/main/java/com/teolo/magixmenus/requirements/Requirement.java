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
public record Requirement(Type type, String key, String value, int quantita, boolean equal) {

    /** I tipi di condizione. Fra parentesi il nome accettato in alternativa, per chi arriva da altri plugin. */
    public enum Type {
        /** Il giocatore ha il permesso scritto in "chiave". */
        PERMESSO,
        /** La condizione scritta in "chiave" e' vera: "%vault_eco_balance% >= 1000". Vedi {@link Equation}. */
        EQUATION,
        /** "chiave" e "valore" sono la stessa cosa (maiuscole ignorate). */
        TEXT_EQUALS,
        /** "chiave" contiene "valore". */
        TEXT_CONTAINS,
        /** "chiave" corrisponde all'espressione regolare in "valore". */
        REGEX,
        /** Il giocatore ha in inventario "quantita" item di tipo "chiave" (e col nome in "valore", se scritto). */
        HA_ITEM,
        /** Il giocatore ha almeno "quantita" monete (serve Vault). */
        HA_SOLDI,
        /** Il giocatore ha almeno "quantita" livelli di esperienza. */
        HA_LIVELLI,
        /** Il giocatore si trova in uno dei mondi elencati in "chiave" (separati da virgola). */
        WORLD,
        /** Nell'inventario ci sono almeno "quantita" caselle libere. */
        POSTO_LIBERO;

        /** Il nome con cui questo tipo si scrive nei file (inglese, come tutte le chiavi). */
        public String fileName() {
            return switch (this) {
                case PERMESSO -> "PERMISSION";
                case EQUATION -> "EQUATION";
                case TEXT_EQUALS -> "STRING_EQUALS";
                case TEXT_CONTAINS -> "STRING_CONTAINS";
                case REGEX -> "REGEX";
                case HA_ITEM -> "HAS_ITEM";
                case HA_SOLDI -> "HAS_MONEY";
                case HA_LIVELLI -> "HAS_LEVEL";
                case WORLD -> "WORLD";
                case POSTO_LIBERO -> "EMPTY_SLOTS";
            };
        }

        /** Il nome scritto nel file: quello inglese, o il vecchio nome italiano. */
        public static Type read(String s) {
            if (s == null) {
                return null;
            }
            String n = s.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
            return switch (n) {
                case "PERMESSO", "PERMISSION", "HAS_PERMISSION" -> PERMESSO;
                case "EQUAZIONE", "EQUATION", "MATH", "EXPRESSION" -> EQUATION;
                case "TESTO_UGUALE", "STRING_EQUALS", "STRING_EQUALS_IGNORECASE" -> TEXT_EQUALS;
                case "TESTO_CONTIENE", "STRING_CONTAINS" -> TEXT_CONTAINS;
                case "REGEX", "REGEX_MATCHES" -> REGEX;
                case "HA_ITEM", "HAS_ITEM" -> HA_ITEM;
                case "HA_SOLDI", "HAS_MONEY" -> HA_SOLDI;
                case "HA_LIVELLI", "HAS_LEVEL", "HAS_EXP" -> HA_LIVELLI;
                case "MONDO", "WORLD", "IS_IN_WORLD" -> WORLD;
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
        return trueValue(p, variabili) == equal;
    }

    private boolean trueValue(Player p, Map<String, String> variabili) {
        String k = Text.raw(p, variabili, key);
        String v = Text.raw(p, variabili, value);

        return switch (type) {
            case PERMESSO -> !k.isEmpty() && p.hasPermission(k);

            case EQUATION -> {
                try {
                    yield Equation.real(k);
                } catch (Equation.Errore e) {
                    // Una condizione scritta male non deve far sparire mezzo menu senza spiegazione:
                    // vale "no", ma l'errore finisce nel log con la condizione gia' risolta, che e'
                    // l'unica forma in cui si capisce davvero cosa non torna.
                    org.bukkit.Bukkit.getLogger().warning("[MagixMenus] condizione non valida \""
                            + key + "\" (diventata \"" + k + "\"): " + e.getMessage());
                    yield false;
                }
            }

            case TEXT_EQUALS -> k.equalsIgnoreCase(v);
            case TEXT_CONTAINS -> k.toLowerCase(Locale.ROOT).contains(v.toLowerCase(Locale.ROOT));

            case REGEX -> {
                try {
                    yield Pattern.compile(v).matcher(k).matches();
                } catch (PatternSyntaxException e) {
                    org.bukkit.Bukkit.getLogger().warning("[MagixMenus] espressione regolare non valida \""
                            + value + "\": " + e.getDescription());
                    yield false;
                }
            }

            case HA_ITEM -> haItem(p, k, v);
            case HA_SOLDI -> EconomyHook.disponibile() && EconomyHook.saldo(p) >= quantita;
            case HA_LIVELLI -> p.getLevel() >= quantita;

            case WORLD -> {
                String world = p.getWorld().getName();
                for (String uno : k.split(",")) {
                    if (uno.trim().equalsIgnoreCase(world)) {
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
    private boolean haItem(Player p, String materiale, String name) {
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
            if (!name.isEmpty()) {
                ItemMeta meta = s.getItemMeta();
                String suo = meta == null ? "" : org.bukkit.ChatColor.stripColor(
                        net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                                .legacySection().serialize(meta.displayName() == null
                                        ? net.kyori.adventure.text.Component.empty() : meta.displayName()));
                if (!suo.equalsIgnoreCase(org.bukkit.ChatColor.stripColor(
                        com.teolo.magixmenus.util.Colors.translate(name)))) {
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
    public String description() {
        String base = switch (type) {
            case PERMESSO -> "ha il permesso " + key;
            case EQUATION -> "e' vero che " + key;
            case TEXT_EQUALS -> key + " e' uguale a " + value;
            case TEXT_CONTAINS -> key + " contiene " + value;
            case REGEX -> key + " corrisponde a " + value;
            case HA_ITEM -> "ha " + quantita + " " + key;
            case HA_SOLDI -> "ha almeno " + quantita + " monete";
            case HA_LIVELLI -> "ha almeno " + quantita + " livelli";
            case WORLD -> "si trova in " + key;
            case POSTO_LIBERO -> "ha " + quantita + " caselle libere";
        };
        return equal ? base : "NON " + base;
    }

    /** Elenco dei tipi, per i messaggi d'errore del caricatore e per l'editor sul sito. */
    public static List<String> availableTypes() {
        List<String> out = new java.util.ArrayList<>();
        for (Type t : Type.values()) {
            out.add(t.fileName());
        }
        return out;
    }
}
