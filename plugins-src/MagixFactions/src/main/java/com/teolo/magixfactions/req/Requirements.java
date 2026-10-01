package com.teolo.magixfactions.req;

import com.teolo.magixfactions.hook.Econ;
import com.teolo.magixfactions.hook.Papi;
import com.teolo.magixfactions.lang.Messages;
import com.teolo.magixfactions.util.DurationText;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Verifica e consuma i requisiti (es. per creare una fazione):
 * soldi (Vault), oggetti nell'inventario, condizioni PlaceholderAPI, permesso.
 * Configurabile dalla sezione passata (es. 'create-cost').
 *
 * <p>Una condizione PlaceholderAPI e' {@code "%segnaposto% OP valore"}, con in coda due pezzi
 * facoltativi separati da {@code |}: {@code "%segnaposto% >= 2223600 | tempo di gioco | seconds"}.
 * Il primo e' il nome che il giocatore legge al posto della formula; il secondo ({@code seconds}) dice
 * che i valori sono secondi e vanno scritti come durata ("4 giorni e 5 ore"). Senza questi pezzi
 * il messaggio mostra i numeri cosi' come sono.</p>
 */
public final class Requirements {

    private final ConfigurationSection cfg;
    private final Messages messages;

    public Requirements(ConfigurationSection section, Messages messages) {
        this.cfg = section;
        this.messages = messages;
    }

    /** @return null se tutti i requisiti sono soddisfatti, altrimenti il messaggio del primo non soddisfatto. */
    public String checkUnmet(Player p) {
        if (cfg == null) return null;

        double money = cfg.getDouble("money", 0);
        if (money > 0) {
            if (!Econ.enabled()) return "&cI costi in denaro richiedono Vault + un plugin economia (non installato).";
            if (!Econ.has(p, money)) return "&cTi servono &e" + money + "&c monete.";
        }

        for (String entry : cfg.getStringList("items")) {
            String[] parts = entry.split(":");
            Material mat = Material.matchMaterial(parts[0].trim());
            int amt = parts.length > 1 ? parseInt(parts[1], 1) : 1;
            if (mat == null) continue;
            if (countItems(p, mat) < amt) {
                return "&cTi servono &e" + amt + "x " + mat.name() + "&c nell'inventario.";
            }
        }

        for (String cond : cfg.getStringList("placeholders")) {
            String[] parts = cond.split("\\|");
            String formula = parts[0].trim();
            if (!evalCondition(p, formula)) {
                String label = parts.length > 1 && !parts[1].isBlank() ? parts[1].trim() : formula;
                boolean seconds = parts.length > 2 && parts[2].trim().equalsIgnoreCase("seconds");
                String current = describe(p, leftOf(formula), seconds);
                String required = describe(p, rightOf(formula), seconds);
                return messages.get(p, "requirements.condition-unmet",
                        "label", label, "current", current, "required", required);
            }
        }

        String perm = cfg.getString("permission", "");
        if (perm != null && !perm.isEmpty() && !p.hasPermission(perm)) {
            return "&cNon hai il permesso richiesto.";
        }
        return null;
    }

    /** Consuma i costi (soldi/oggetti). Da chiamare solo dopo che checkUnmet ha restituito null. */
    public void consume(Player p) {
        if (cfg == null) return;
        double money = cfg.getDouble("money", 0);
        if (money > 0 && Econ.enabled()) Econ.withdraw(p, money);
        for (String entry : cfg.getStringList("items")) {
            String[] parts = entry.split(":");
            Material mat = Material.matchMaterial(parts[0].trim());
            int amt = parts.length > 1 ? parseInt(parts[1], 1) : 1;
            if (mat != null) removeItems(p, mat, amt);
        }
    }

    // ---- helper ----
    private static final String[] OPERATORS = {">=", "<=", "==", "!=", ">", "<"};

    private static String leftOf(String formula) {
        for (String op : OPERATORS) {
            int idx = formula.indexOf(op);
            if (idx > 0) return formula.substring(0, idx).trim();
        }
        return formula;
    }

    private static String rightOf(String formula) {
        for (String op : OPERATORS) {
            int idx = formula.indexOf(op);
            if (idx > 0) return formula.substring(idx + op.length()).trim();
        }
        return "";
    }

    /** Un lato della condizione come lo legge il giocatore: risolto, e come durata se sono secondi. */
    private static String describe(Player p, String side, boolean seconds) {
        String value = Papi.resolve(p, side);
        Double n = tryNum(value);
        if (n == null) return value;
        return seconds ? DurationText.fromMillis((long) (n * 1000)) : DurationText.number(n);
    }

    private boolean evalCondition(Player p, String cond) {
        // formato: "%placeholder% OP valore"  (OP: >= <= > < == !=)
        for (String op : new String[]{">=", "<=", "==", "!=", ">", "<"}) {
            int idx = cond.indexOf(op);
            if (idx > 0) {
                String left = Papi.resolve(p, cond.substring(0, idx).trim());
                String right = Papi.resolve(p, cond.substring(idx + op.length()).trim());
                Double ln = tryNum(left), rn = tryNum(right);
                if (ln != null && rn != null) {
                    return switch (op) {
                        case ">=" -> ln >= rn; case "<=" -> ln <= rn;
                        case ">" -> ln > rn;   case "<" -> ln < rn;
                        case "==" -> ln.doubleValue() == rn.doubleValue();
                        default -> ln.doubleValue() != rn.doubleValue();
                    };
                }
                return op.equals("==") ? left.equalsIgnoreCase(right)
                        : op.equals("!=") != left.equalsIgnoreCase(right);
            }
        }
        return true; // nessun operatore: condizione ignorata
    }

    private static int countItems(Player p, Material mat) {
        int n = 0;
        for (ItemStack is : p.getInventory().getContents()) {
            if (is != null && is.getType() == mat) n += is.getAmount();
        }
        return n;
    }

    private static void removeItems(Player p, Material mat, int amount) {
        int left = amount;
        ItemStack[] contents = p.getInventory().getContents();
        for (int i = 0; i < contents.length && left > 0; i++) {
            ItemStack is = contents[i];
            if (is != null && is.getType() == mat) {
                int take = Math.min(left, is.getAmount());
                is.setAmount(is.getAmount() - take);
                left -= take;
                if (is.getAmount() <= 0) p.getInventory().setItem(i, null);
            }
        }
    }

    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }

    private static Double tryNum(String s) {
        try { return Double.parseDouble(s.trim()); } catch (Exception e) { return null; }
    }
}
