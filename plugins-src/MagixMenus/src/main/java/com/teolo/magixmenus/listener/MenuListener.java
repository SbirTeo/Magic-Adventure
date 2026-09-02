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
    public void onClick(InventoryClickEvent e) {
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
            plugin.messages().send(p, "cooldown-wait", "seconds", String.valueOf(attesa));
            return;
        }

        Click click = Click.da(e.getClick());
        Map<String, String> variabili = new LinkedHashMap<>(menu.variabiliPer(e.getSlot()));
        if (alto instanceof AnvilInventory incudine) {
            // Il testo scritto nell'incudine e' l'unica ragione per cui si sceglie questo tipo
            // di menu: arriva alle azioni come %input%.
            String scritto = incudine.getRenameText();
            variabili.put("input", scritto == null ? "" : scritto);
        }

        // Prima i requisiti del tasto premuto, poi quelli validi per qualunque tasto: se uno dei
        // due dice no, il clic non ha effetto e partono le sue azioni di rifiuto.
        for (Click quale : List.of(click, Click.QUALSIASI)) {
            Requirements r = item.clickIf(quale);
            if (r.vuoto() || r.soddisfatti(p, variabili)) {
                continue;
            }
            if (!r.deniedActions().isEmpty()) {
                Actions.esegui(plugin, menu.contextWith(variabili), r.deniedActions());
            } else {
                plugin.messages().send(p, "requirements-not-met");
            }
            return;
        }

        // Se e' un articolo di negozio, prima passa di li': soldi, posto in inventario e
        // scambio della merce. Se qualcosa non va, le azioni NON partono — altrimenti un
        // "console: give" scritto sotto regalerebbe la merce a chi non ha pagato.
        if (item.articolo()) {
            Shop.Outcome outcome = Shop.click(plugin, p, item, click, variabili);
            if (outcome == Shop.Outcome.NIENTE_DA_FARE) {
                return;
            }
        }

        menu.markClick(item);
        Actions.esegui(plugin, menu.contextWith(variabili), item.actionsFor(click));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void suTrascinamento(InventoryDragEvent e) {
        Inventory alto = e.getView().getTopInventory();
        if (!(alto.getHolder() instanceof OpenMenu)) {
            return;
        }
        for (int slot : e.getRawSlots()) {
            if (slot < alto.getSize()) {
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

        if (!menu.definizione().freeClose() && p.isOnline()) {
            // Si riapre un tick dopo: riaprire dentro l'evento di chiusura lascia il client e il
            // server con due idee diverse su cosa sia aperto.
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (p.isOnline() && plugin.menu().openedBy(p) == menu) {
                    plugin.menu().open(p, menu.definizione(), List.of(), null);
                }
            });
            return;
        }

        menu.stop();
        if (!menu.definizione().closeActions().isEmpty()) {
            Actions.esegui(plugin, menu, menu.definizione().closeActions());
        }
        if (plugin.menu().openedBy(p) == menu) {
            plugin.menu().forget(p);
        }
    }

    @EventHandler
    public void suUscita(PlayerQuitEvent e) {
        plugin.menu().forget(e.getPlayer());
    }
}
