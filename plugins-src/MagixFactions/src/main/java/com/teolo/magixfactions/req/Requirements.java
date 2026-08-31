package com.teolo.magixfactions.req;

import com.teolo.magixfactions.hook.Econ;
import com.teolo.magixfactions.hook.Papi;
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
 */
public final class Requirements {

    private final ConfigurationSection cfg;

    public Requirements(ConfigurationSection section) {
        this.cfg = section;
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
            if (!evalCondition(p, cond)) {
                return "&cCondizione non soddisfatta: &7" + cond;
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
