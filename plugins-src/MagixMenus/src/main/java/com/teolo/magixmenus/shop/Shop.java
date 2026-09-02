package com.teolo.magixmenus.shop;

import com.teolo.magixmenus.MagixMenus;
import com.teolo.magixmenus.hook.EconomyHook;
import com.teolo.magixmenus.item.ItemBuilder;
import com.teolo.magixmenus.menu.Click;
import com.teolo.magixmenus.menu.ItemDef;
import com.teolo.magixmenus.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Comprare e vendere, senza scrivere una condizione.
 *
 * <h2>Perche' esiste</h2>
 * Un articolo di un negozio, con i soli mattoni generali del plugin, si scrive cosi': un
 * {@code click_requirements} con un'equazione sul saldo, delle {@code deny_actions} che spiegano
 * quanto manca, un {@code take_money}, un {@code console: give}, e una riga di descrizione col prezzo
 * scritta a mano — che poi va cambiata in due posti ogni volta che il prezzo cambia. Sono nove
 * righe per articolo, ripetute per ogni articolo, con il prezzo scritto due volte.
 *
 * Con il prezzo come concetto suo diventano tre chiavi:
 *
 * <pre>
 * spada:
 *   slot: 10
 *   id: DIAMOND_SWORD
 *   price: 1000         &lt;- quanto costa
 *   give: DIAMOND_SWORD &lt;- cosa ricevi
 *   sell: 400           &lt;- a quanto te la ricompra (clic destro)
 * </pre>
 *
 * e il resto — controllare i soldi, controllare che ci sia posto, togliere le monete, dare
 * l'oggetto, spiegare cosa manca, scrivere il prezzo nella descrizione — lo fa il plugin, uguale
 * per tutti gli articoli e senza che il prezzo sia scritto in due posti che possono divergere.
 *
 * <h2>L'ordine dei controlli</h2>
 * Prima si guarda se c'e' posto in inventario, POI si tolgono i soldi. Al contrario, un giocatore
 * con l'inventario pieno pagherebbe e non riceverebbe niente — ed e' il tipo di segnalazione che
 * costa piu' tempo di quanto costi prevenirla.
 */
public final class Shop {

    /** Cosa e' successo: serve a chi ha cliccato per sapere se le azioni devono proseguire. */
    public enum Outcome {
        /** Non c'era niente da comprare o da vendere: si prosegue normalmente. */
        NON_E_UN_ARTICOLO,
        FATTO,
        /** Qualcosa non e' andato: il messaggio e' gia' stato mandato, la catena si ferma. */
        NIENTE_DA_FARE
    }

    private Shop() {
    }

    /**
     * Gestisce il clic su un articolo, se lo e'.
     *
     * @return cosa deve fare chi ha chiamato: proseguire con le azioni o fermarsi
     */
    public static Outcome click(MagixMenus plugin, Player p, ItemDef def, Click tasto,
                             Map<String, String> variabili) {
        boolean compra = def.prezzo() != null && !def.prezzo().isBlank();
        boolean vende = def.vendi() != null && !def.vendi().isBlank();
        if (!compra && !vende) {
            return Outcome.NON_E_UN_ARTICOLO;
        }

        // Sinistro compra, destro vende: e' la convenzione di tutti i negozi che si sono mai
        // visti in un server, e cambiarla vorrebbe dire spiegarla.
        boolean vuoleVendere = vende && (tasto == Click.DESTRO || tasto == Click.SHIFT_DESTRO);
        if (vuoleVendere) {
            return vendi(plugin, p, def, variabili);
        }
        if (!compra) {
            return Outcome.NON_E_UN_ARTICOLO;
        }
        return compra(plugin, p, def, variabili);
    }

    // ------------------------------------------------------------------ comprare

    private static Outcome compra(MagixMenus plugin, Player p, ItemDef def, Map<String, String> variabili) {
        double prezzo = quanto(p, variabili, def.prezzo());
        if (!EconomyHook.disponibile()) {
            plugin.getLogger().warning("L'articolo \"" + def.name() + "\" ha un prezzo ma non c'e' "
                    + "nessuna economia (serve Vault e un plugin che la fornisca).");
            plugin.messages().send(p, "shop.no-economy");
            return Outcome.NIENTE_DA_FARE;
        }

        double saldo = EconomyHook.saldo(p);
        if (saldo < prezzo) {
            plugin.messages().send(p, "shop.not-enough-money",
                    "price", number(prezzo),
                    "missing", number(prezzo - saldo),
                    "balance", number(saldo));
            suono(p, plugin, "shop.sound-fail");
            return Outcome.NIENTE_DA_FARE;
        }

        ItemStack merce = merce(plugin, p, variabili, def);
        if (merce != null && !c_e_posto(p, merce)) {
            // Prima il posto, poi i soldi: vedi il commento in cima alla classe.
            plugin.messages().send(p, "shop.inventory-full");
            suono(p, plugin, "shop.sound-fail");
            return Outcome.NIENTE_DA_FARE;
        }

        if (prezzo > 0 && !EconomyHook.remove(p, prezzo)) {
            plugin.messages().send(p, "shop.payment-failed");
            return Outcome.NIENTE_DA_FARE;
        }
        if (merce != null) {
            p.getInventory().addItem(merce);
        }

        plugin.messages().send(p, "shop.bought",
                "price", number(prezzo),
                "what", productName(def, merce),
                "balance", number(EconomyHook.saldo(p)));
        suono(p, plugin, "shop.sound-ok");
        return Outcome.FATTO;
    }

    // ------------------------------------------------------------------- vendere

    private static Outcome vendi(MagixMenus plugin, Player p, ItemDef def, Map<String, String> variabili) {
        double prezzo = quanto(p, variabili, def.vendi());
        if (!EconomyHook.disponibile()) {
            plugin.messages().send(p, "shop.no-economy");
            return Outcome.NIENTE_DA_FARE;
        }

        ItemStack merce = merce(plugin, p, variabili, def);
        if (merce == null) {
            plugin.getLogger().warning("L'articolo \"" + def.name() + "\" si puo' vendere ma non dice "
                    + "cosa (manca la chiave give).");
            return Outcome.NIENTE_DA_FARE;
        }
        if (!p.getInventory().containsAtLeast(merce, merce.getAmount())) {
            plugin.messages().send(p, "shop.nothing-to-sell", "what", productName(def, merce));
            suono(p, plugin, "shop.sound-fail");
            return Outcome.NIENTE_DA_FARE;
        }

        p.getInventory().removeItem(merce);
        EconomyHook.dai(p, prezzo);
        plugin.messages().send(p, "shop.sold",
                "price", number(prezzo),
                "what", productName(def, merce),
                "balance", number(EconomyHook.saldo(p)));
        suono(p, plugin, "shop.sound-ok");
        return Outcome.FATTO;
    }

    // -------------------------------------------------------------------- pezzi

    /**
     * Cosa passa di mano.
     *
     * {@code give: self} da' una copia dell'item che si vede nel menu — comodo, ma con
     * dentro anche la descrizione scritta per il menu ("clicca per comprare"), che sull'oggetto
     * vero non ci sta bene. Sta scritto nella guida: quando la descrizione e' da menu, meglio
     * dire per esteso cosa si da'.
     */
    public static ItemStack merce(MagixMenus plugin, Player p, Map<String, String> variabili, ItemDef def) {
        String what = def.dai();
        if (what == null || what.isBlank()) {
            return null;
        }
        String risolto = Text.raw(p, variabili, what).trim();
        if (risolto.equalsIgnoreCase("self") || risolto.equalsIgnoreCase("se_stesso")
                || risolto.equalsIgnoreCase("questo")) {
            ItemStack copy = ItemBuilder.costruisci(plugin, p, variabili, def, false);
            return copy.getType().isAir() ? null : copy;
        }

        String[] pieces = risolto.split("[\\s:x*]+");
        Material m = Material.matchMaterial(pieces[0]);
        if (m == null || m.isAir()) {
            plugin.getLogger().warning("L'articolo \"" + def.name() + "\": non esiste l'item \""
                    + pieces[0] + "\" scritto in \"dai\".");
            return null;
        }
        int quanti = 1;
        if (pieces.length > 1) {
            Double n = Text.number(pieces[1]);
            if (n != null) {
                quanti = Math.max(1, (int) (double) n);
            }
        }
        return new ItemStack(m, quanti);
    }

    /**
     * C'e' posto per questa roba?
     *
     * Una casella libera basta di sicuro; altrimenti va bene anche una pila dello stesso tipo
     * che non sia ancora piena. Non si simula l'inventario intero: serve una risposta prudente,
     * e "quasi pieno" va trattato come pieno.
     */
    private static boolean c_e_posto(Player p, ItemStack merce) {
        if (p.getInventory().firstEmpty() >= 0) {
            return true;
        }
        int posto = 0;
        for (ItemStack s : p.getInventory().getStorageContents()) {
            if (s != null && s.isSimilar(merce)) {
                posto += Math.max(0, s.getMaxStackSize() - s.getAmount());
            }
        }
        return posto >= merce.getAmount();
    }

    private static String productName(ItemDef def, ItemStack merce) {
        if (merce == null) {
            return def.name();
        }
        String name = merce.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return merce.getAmount() > 1 ? merce.getAmount() + " " + name : name;
    }

    private static double quanto(Player p, Map<String, String> variabili, String scritto) {
        Double n = Text.number(Text.raw(p, variabili, scritto));
        return n == null ? 0 : Math.max(0, n);
    }

    /** Il prezzo come lo legge una persona: 1000 e non 1000.0, 12,5 e non 12.5. */
    public static String number(double v) {
        if (v == Math.rint(v)) {
            return String.valueOf((long) v);
        }
        return String.format(Locale.ITALIAN, "%.2f", v);
    }

    private static void suono(Player p, MagixMenus plugin, String key) {
        String name = plugin.messages().raw(key);
        if (name == null || name.isBlank()) {
            return;
        }
        try {
            p.playSound(p.getLocation(), org.bukkit.Sound.valueOf(name.trim().toUpperCase(Locale.ROOT)), 1f, 1f);
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Suono sconosciuto in " + key + ": " + name);
        }
    }

    /** Le righe di descrizione che il plugin aggiunge da solo a un articolo. */
    public static Map<String, String> valuesForDescription(Player p, Map<String, String> variabili,
                                                             ItemDef def) {
        Map<String, String> v = new HashMap<>();
        v.put("price", number(quanto(p, variabili, def.prezzo())));
        v.put("sell", number(quanto(p, variabili, def.vendi())));
        return v;
    }
}
