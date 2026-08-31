package com.teolo.magixmenus.actions;

import com.teolo.magixmenus.MagixMenus;
import com.teolo.magixmenus.hook.EconomyHook;
import com.teolo.magixmenus.util.Colors;
import com.teolo.magixmenus.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * Esegue una fila di azioni, una dopo l'altra.
 *
 * <h2>L'attesa</h2>
 * Quando incontra un'{@code attesa}, la catena si spezza: quello che resta viene rimandato al
 * momento giusto e ripreso da li'. Non c'e' nessun thread fermo ad aspettare — sarebbe un thread
 * per ogni clic di ogni giocatore — e l'ordine resta quello scritto nel file.
 *
 * <h2>Chi se ne va nel frattempo</h2>
 * Fra un'attesa e la ripresa il giocatore puo' essersi disconnesso. Prima di riprendere si
 * controlla che sia ancora collegato: senza questo controllo un {@code console: give} finirebbe
 * nel vuoto e la console si riempirebbe di errori che sembrano un guasto del plugin.
 */
public final class Actions {

    private Actions() {
    }

    public static void esegui(MagixMenus plugin, Context contesto, List<Action> azioni) {
        esegui(plugin, contesto, azioni, 0);
    }

    private static void esegui(MagixMenus plugin, Context contesto, List<Action> azioni, int da) {
        Player p = contesto.giocatore();
        for (int i = da; i < azioni.size(); i++) {
            Action a = azioni.get(i);

            if (a.tipo() == Action.Tipo.SE) {
                List<Action> ramo = a.condizione().soddisfatti(p, contesto.variabili())
                        ? a.allora() : a.altrimenti();
                esegui(plugin, contesto, ramo, 0);
                continue;
            }

            if (a.tipo() == Action.Tipo.ATTESA) {
                long tick = tick(a.argomento());
                final int riprendiDa = i + 1;
                if (tick <= 0) {
                    continue;
                }
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (contesto.giocatore().isOnline()) {
                        esegui(plugin, contesto, azioni, riprendiDa);
                    }
                }, tick);
                return;
            }

            if (!una(plugin, contesto, a)) {
                // Un'azione che fallisce (soldi insufficienti) ferma il resto: le azioni dopo
                // davano per scontato che quella fosse riuscita.
                return;
            }
        }
    }

    /** @return false se la catena si deve fermare qui. */
    private static boolean una(MagixMenus plugin, Context contesto, Action a) {
        Player p = contesto.giocatore();
        String arg = Text.grezzo(p, contesto.variabili(), a.argomento());

        switch (a.tipo()) {
            case COMANDO -> p.performCommand(togliBarra(arg));

            case CONSOLE -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), togliBarra(arg));

            case COMANDO_OP -> {
                if (!plugin.getConfig().getBoolean("actions.allow-op-commands", false)) {
                    plugin.getLogger().warning("Azione 'comando_op' ignorata (" + arg + "): e' spenta nel "
                            + "config, chiave azioni.permetti-comandi-op. Quasi sempre 'console' fa lo stesso.");
                    return true;
                }
                boolean eraOp = p.isOp();
                try {
                    p.setOp(true);
                    p.performCommand(togliBarra(arg));
                } finally {
                    // Nel "finally" apposta: se il comando esplode, l'op deve tornare com'era
                    // lo stesso, altrimenti un errore lascerebbe un giocatore operatore.
                    p.setOp(eraOp);
                }
            }

            case MESSAGGIO -> p.sendMessage(Colors.translate(arg));

            case ANNUNCIO -> Bukkit.broadcast(Colors.component(arg));

            case TITOLO -> {
                String[] pezzi = arg.split("\\|", 2);
                p.showTitle(net.kyori.adventure.title.Title.title(
                        Colors.component(pezzi[0]),
                        pezzi.length > 1 ? Colors.component(pezzi[1]) : Component.empty(),
                        net.kyori.adventure.title.Title.Times.times(
                                Duration.ofMillis(250), Duration.ofSeconds(2), Duration.ofMillis(500))));
            }

            case ACTIONBAR -> p.sendActionBar(Colors.component(arg));

            case SUONO -> suono(plugin, p, arg);

            case APRI_MENU -> contesto.apriMenu(arg);

            case INDIETRO -> contesto.indietro();

            case CHIUDI -> contesto.chiudi();

            case AGGIORNA -> contesto.aggiorna();

            case PAGINA -> contesto.pagina(arg.isBlank() ? "avanti" : arg.trim());

            case DAI_SOLDI -> {
                if (!EconomyHook.disponibile()) {
                    plugin.getLogger().warning("Azione 'dai_soldi' ignorata: Vault non e' installato.");
                    return true;
                }
                EconomyHook.dai(p, quanto(arg));
            }

            case TOGLI_SOLDI -> {
                if (!EconomyHook.disponibile()) {
                    plugin.getLogger().warning("Azione 'togli_soldi' ignorata: Vault non e' installato.");
                    return true;
                }
                if (!EconomyHook.togli(p, quanto(arg))) {
                    return false;
                }
            }

            default -> {
            }
        }
        return true;
    }

    /** "BLOCK_NOTE_BLOCK_PLING", oppure "SUONO|volume|tono". */
    private static void suono(MagixMenus plugin, Player p, String arg) {
        String[] pezzi = arg.split("\\|");
        String nome = pezzi[0].trim().toUpperCase(Locale.ROOT).replace('.', '_');
        float volume = pezzi.length > 1 ? (float) quanto(pezzi[1]) : 1f;
        float tono = pezzi.length > 2 ? (float) quanto(pezzi[2]) : 1f;
        Sound suono;
        try {
            // Il nome com'e' scritto ovunque ("BLOCK_NOTE_BLOCK_PLING"); se non e' quello,
            // ci prova il registro, che accetta anche la forma "block.note_block.pling".
            suono = Sound.valueOf(nome);
        } catch (IllegalArgumentException e) {
            suono = org.bukkit.Registry.SOUND_EVENT.match(pezzi[0].trim());
        }
        if (suono == null) {
            plugin.getLogger().warning("Suono sconosciuto: " + pezzi[0]);
            return;
        }
        p.playSound(p.getLocation(), suono, volume, tono);
    }

    private static String togliBarra(String comando) {
        String c = comando.trim();
        return c.startsWith("/") ? c.substring(1) : c;
    }

    private static long tick(String s) {
        Double d = Text.numero(s);
        return d == null ? 0 : Math.max(0, Math.round(d));
    }

    private static double quanto(String s) {
        Double d = Text.numero(s);
        return d == null ? 0 : d;
    }
}
