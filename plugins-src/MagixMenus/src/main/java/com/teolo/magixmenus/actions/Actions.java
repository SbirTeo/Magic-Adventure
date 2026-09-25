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

    public static void esegui(MagixMenus plugin, Context context, List<Action> actions) {
        esegui(plugin, context, actions, 0);
    }

    private static void esegui(MagixMenus plugin, Context context, List<Action> actions, int da) {
        Player p = context.player();
        for (int i = da; i < actions.size(); i++) {
            Action a = actions.get(i);

            if (a.type() == Action.Type.SE) {
                List<Action> ramo = a.condizione().soddisfatti(p, context.variabili())
                        ? a.allora() : a.altrimenti();
                esegui(plugin, context, ramo, 0);
                continue;
            }

            if (a.type() == Action.Type.ATTESA) {
                long tick = tick(a.argomento());
                final int riprendiDa = i + 1;
                if (tick <= 0) {
                    continue;
                }
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (context.player().isOnline()) {
                        esegui(plugin, context, actions, riprendiDa);
                    }
                }, tick);
                return;
            }

            if (!una(plugin, context, a)) {
                // Un'azione che fallisce (soldi insufficienti) ferma il resto: le azioni dopo
                // davano per scontato che quella fosse riuscita.
                return;
            }
        }
    }

    /** Tipi la cui azione e' testo per il giocatore: vedi {@link Text#translated}. */
    private static final java.util.Set<Action.Type> TEXT_ACTION_TYPES = java.util.EnumSet.of(
            Action.Type.MESSAGE, Action.Type.ANNUNCIO, Action.Type.TITLE, Action.Type.ACTIONBAR);

    /** @return false se la catena si deve fermare qui. */
    private static boolean una(MagixMenus plugin, Context context, Action a) {
        Player p = context.player();
        String template = TEXT_ACTION_TYPES.contains(a.type()) ? Text.translated(p, a.argomento()) : a.argomento();
        String arg = Text.raw(p, context.variabili(), template);

        switch (a.type()) {
            case COMMAND -> p.performCommand(removeBar(arg));

            case CONSOLE -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), removeBar(arg));

            case COMMAND_OP -> {
                if (!plugin.getConfig().getBoolean("actions.allow-op-commands", false)) {
                    plugin.getLogger().warning("Azione 'comando_op' ignorata (" + arg + "): e' spenta nel "
                            + "config, chiave azioni.permetti-comandi-op. Quasi sempre 'console' fa lo stesso.");
                    return true;
                }
                boolean eraOp = p.isOp();
                try {
                    p.setOp(true);
                    p.performCommand(removeBar(arg));
                } finally {
                    // Nel "finally" apposta: se il comando esplode, l'op deve tornare com'era
                    // lo stesso, altrimenti un errore lascerebbe un giocatore operatore.
                    p.setOp(eraOp);
                }
            }

            case MESSAGE -> p.sendMessage(Colors.translate(arg));

            case ANNUNCIO -> Bukkit.broadcast(Colors.component(arg));

            case TITLE -> {
                String[] pieces = arg.split("\\|", 2);
                p.showTitle(net.kyori.adventure.title.Title.title(
                        Colors.component(pieces[0]),
                        pieces.length > 1 ? Colors.component(pieces[1]) : Component.empty(),
                        net.kyori.adventure.title.Title.Times.times(
                                Duration.ofMillis(250), Duration.ofSeconds(2), Duration.ofMillis(500))));
            }

            case ACTIONBAR -> p.sendActionBar(Colors.component(arg));

            case SUONO -> suono(plugin, p, arg);

            case OPEN_MENU -> context.openMenu(arg);

            case BACK -> context.back();

            case CLOSE -> context.close();

            case REFRESH -> context.refresh();

            case PAGE -> context.page(arg.isBlank() ? "avanti" : arg.trim());

            case DAI_SOLDI -> {
                if (!EconomyHook.disponibile()) {
                    plugin.getLogger().warning("Azione 'dai_soldi' ignorata: Vault non e' installato.");
                    return true;
                }
                EconomyHook.dai(p, quanto(arg));
            }

            case TAKE_MONEY -> {
                if (!EconomyHook.disponibile()) {
                    plugin.getLogger().warning("Azione 'togli_soldi' ignorata: Vault non e' installato.");
                    return true;
                }
                if (!EconomyHook.remove(p, quanto(arg))) {
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
        String[] pieces = arg.split("\\|");
        String name = pieces[0].trim().toUpperCase(Locale.ROOT).replace('.', '_');
        float volume = pieces.length > 1 ? (float) quanto(pieces[1]) : 1f;
        float tono = pieces.length > 2 ? (float) quanto(pieces[2]) : 1f;
        Sound suono;
        try {
            // Il nome com'e' scritto ovunque ("BLOCK_NOTE_BLOCK_PLING"); se non e' quello,
            // ci prova il registro, che accetta anche la forma "block.note_block.pling".
            suono = Sound.valueOf(name);
        } catch (IllegalArgumentException e) {
            suono = org.bukkit.Registry.SOUND_EVENT.match(pieces[0].trim());
        }
        if (suono == null) {
            plugin.getLogger().warning("Suono sconosciuto: " + pieces[0]);
            return;
        }
        p.playSound(p.getLocation(), suono, volume, tono);
    }

    private static String removeBar(String command) {
        String c = command.trim();
        return c.startsWith("/") ? c.substring(1) : c;
    }

    private static long tick(String s) {
        Double d = Text.number(s);
        return d == null ? 0 : Math.max(0, Math.round(d));
    }

    private static double quanto(String s) {
        Double d = Text.number(s);
        return d == null ? 0 : d;
    }
}
