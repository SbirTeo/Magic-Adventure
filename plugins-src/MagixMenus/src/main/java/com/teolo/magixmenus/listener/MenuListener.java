package com.teolo.magixmenus.listener;

import com.teolo.magixmenus.MagixMenus;
import com.teolo.magixmenus.actions.Actions;
import com.teolo.magixmenus.menu.Click;
import com.teolo.magixmenus.menu.ItemDef;
import com.teolo.magixmenus.menu.OpenMenu;
import com.teolo.magixmenus.shop.Shop;
import com.teolo.magixmenus.requirements.Requirements;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.Inventory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Quello che succede quando si tocca un menu.
 *
 * <h2>Si annulla tutto, sempre</h2>
 * Ogni clic dentro un menu viene annullato prima ancora di guardare cosa fosse. Non e' prudenza
 * eccessiva: un inventario e' fatto per spostare oggetti, e senza l'annullamento un giocatore puo'
 * prendersi il vetro di sfondo, i bottoni, e con un doppio clic svuotare il menu dentro il proprio
 * inventario. Anche i clic nel proprio inventario, mentre un menu e' aperto, vengono fermati se
 * cercano di spostare qualcosa dentro (lo shift-clic e il tasto numerico).
 *
 * <h2>La chiusura</h2>
 * Chiudere un menu e chiudere un menu per aprirne un altro sono due cose diverse: nel secondo caso
 * le azioni di chiusura non devono partire, altrimenti ogni sottomenu farebbe scattare il saluto
 * del menu da cui si e' arrivati. Il menu aperto se lo ricorda da solo (chiusuraVoluta).
 */
public final class MenuListener implements Listener {

    private final MagixMenus plugin;

    public MenuListener(MagixMenus plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void suClic(InventoryClickEvent e) {
        Inventory alto = e.getView().getTopInventory();
        if (!(alto.getHolder() instanceof OpenMenu menu)) {
            return;
        }
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) {
            return;
        }

        if (e.getClickedInventory() != alto) {
            return;   // ha cliccato nel proprio inventario: annullato e basta
        }

        ItemDef item = menu.itemIn(e.getSlot());
        // Un articolo di negozio fa qualcosa anche senza avere azioni scritte: comprare e'
        // gia' un'azione. Senza questo, un articolo con solo prezzo e "dai" sarebbe inerte.
        if (item == null || (!item.cliccabile() && !item.articolo())) {
            return;
        }

        long attesa = menu.attesaRimasta(item);
        if (attesa > 0) {
            plugin.messaggi().send(p, "cooldown-wait", "seconds", String.valueOf(attesa));
            return;
        }

        Click clic = Click.da(e.getClick());
        Map<String, String> variabili = new LinkedHashMap<>(menu.variabiliPer(e.getSlot()));
        if (alto instanceof AnvilInventory incudine) {
            // Il testo scritto nell'incudine e' l'unica ragione per cui si sceglie questo tipo
            // di menu: arriva alle azioni come %input%.
            String scritto = incudine.getRenameText();
            variabili.put("input", scritto == null ? "" : scritto);
        }

        // Prima i requisiti del tasto premuto, poi quelli validi per qualunque tasto: se uno dei
        // due dice no, il clic non ha effetto e partono le sue azioni di rifiuto.
        for (Click quale : List.of(clic, Click.QUALSIASI)) {
            Requirements r = item.clicSe(quale);
            if (r.vuoto() || r.soddisfatti(p, variabili)) {
                continue;
            }
            if (!r.azioniNegate().isEmpty()) {
                Actions.esegui(plugin, menu.contestoCon(variabili), r.azioniNegate());
            } else {
                plugin.messaggi().send(p, "requirements-not-met");
            }
            return;
        }

        // Se e' un articolo di negozio, prima passa di li': soldi, posto in inventario e
        // scambio della merce. Se qualcosa non va, le azioni NON partono — altrimenti un
        // "console: give" scritto sotto regalerebbe la merce a chi non ha pagato.
        if (item.articolo()) {
            Shop.Esito esito = Shop.clic(plugin, p, item, clic, variabili);
            if (esito == Shop.Esito.NIENTE_DA_FARE) {
                return;
            }
        }

        menu.segnaClic(item);
        Actions.esegui(plugin, menu.contestoCon(variabili), item.azioniPer(clic));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void suTrascinamento(InventoryDragEvent e) {
        Inventory alto = e.getView().getTopInventory();
        if (!(alto.getHolder() instanceof OpenMenu)) {
            return;
        }
        for (int casella : e.getRawSlots()) {
            if (casella < alto.getSize()) {
                e.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler
    public void suChiusura(InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder() instanceof OpenMenu menu)) {
            return;
        }
        if (!(e.getPlayer() instanceof Player p)) {
            return;
        }
        if (menu.chiusuraVoluta()) {
            menu.chiusuraVoluta(false);
            return;
        }

        if (!menu.definizione().chiusuraLibera() && p.isOnline()) {
            // Si riapre un tick dopo: riaprire dentro l'evento di chiusura lascia il client e il
            // server con due idee diverse su cosa sia aperto.
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (p.isOnline() && plugin.menu().apertoDi(p) == menu) {
                    plugin.menu().apri(p, menu.definizione(), List.of(), null);
                }
            });
            return;
        }

        menu.ferma();
        if (!menu.definizione().azioniChiusura().isEmpty()) {
            Actions.esegui(plugin, menu, menu.definizione().azioniChiusura());
        }
        if (plugin.menu().apertoDi(p) == menu) {
            plugin.menu().dimentica(p);
        }
    }

    @EventHandler
    public void suUscita(PlayerQuitEvent e) {
        plugin.menu().dimentica(e.getPlayer());
    }
}
