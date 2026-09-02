package com.teolo.magixmenus.dialog;

import com.teolo.magixmenus.MagixMenus;
import com.teolo.magixmenus.actions.Actions;
import com.teolo.magixmenus.actions.Context;
import com.teolo.magixmenus.menu.MenuDialog;
import com.teolo.magixmenus.menu.OpenMenu;
import com.teolo.magixmenus.menu.MenuDef;
import com.teolo.magixmenus.util.Colors;
import com.teolo.magixmenus.util.Text;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput;
import io.papermc.paper.registry.data.dialog.input.TextDialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.event.ClickCallback;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * I menu che non sono inventari: le finestre di dialogo di Minecraft.
 *
 * <h2>Cosa cambia rispetto a un baule</h2>
 * Un dialogo ha un testo che si legge come si legge un testo (a capo, spazi, nessuna riga di lore
 * larga otto parole), dei campi in cui scrivere, e dei bottoni che sembrano bottoni. Non ci sono
 * item, quindi non c'e' niente da proteggere: nessuno puo' provare a trascinarsi via il vetro di
 * sfondo, perche' non c'e' nessun vetro.
 *
 * <h2>Il collegamento fra bottone e azioni</h2>
 * Minecraft, quando si preme un bottone, rimanda al server un identificativo. Paper permette di
 * legare quell'identificativo a del codice ({@code customClick}) invece che a un comando: le
 * azioni restano quindi le nostre, identiche a quelle di un item, e non serve inventarsi dei
 * comandi nascosti da far eseguire al giocatore — che sarebbero comandi veri, digitabili a mano da
 * chiunque li scoprisse.
 *
 * <h2>Quanto vive un bottone</h2>
 * Il collegamento dura dieci minuti e vale per un numero illimitato di pressioni finche' la
 * finestra e' aperta. Un dialogo lasciato aperto tutto il pomeriggio smette di rispondere: e'
 * voluto, perche' l'alternativa e' tenere in memoria per sempre le azioni di ogni finestra mai
 * aperta da chiunque.
 */
public final class DialogManager {

    /** Per quanto un bottone resta collegato alle sue azioni. */
    private static final Duration BUTTON_DURATION = Duration.ofMinutes(10);

    private final MagixMenus plugin;

    public DialogManager(MagixMenus plugin) {
        this.plugin = plugin;
    }

    public void open(Player p, MenuDef def, List<String> arguments, OpenMenu provenienza) {
        MenuDialog dlg = def.dialog();
        if (dlg == null) {
            plugin.getLogger().warning("Il menu \"" + def.name() + "\" e' di tipo dialogo ma non ha "
                    + "il blocco del dialogo: non lo apro.");
            return;
        }

        Map<String, String> variabili = variabili(def, arguments);
        Context context = new DialogContext(plugin, p, variabili, def, provenienza);

        if (!def.openIf().vuoto() && !def.openIf().soddisfatti(p, variabili)) {
            if (!def.openIf().deniedActions().isEmpty()) {
                Actions.esegui(plugin, context, def.openIf().deniedActions());
            } else {
                plugin.messages().send(p, "requirements-not-met");
            }
            return;
        }

        List<DialogBody> body = new ArrayList<>();
        for (String row : dlg.body()) {
            body.add(DialogBody.plainMessage(Colors.component(Text.raw(p, variabili, row)), 300));
        }

        List<DialogInput> fields = new ArrayList<>();
        for (MenuDialog.Field c : dlg.fields()) {
            DialogInput input = field(p, variabili, c);
            if (input != null) {
                fields.add(input);
            }
        }

        List<ActionButton> bottoni = new ArrayList<>();
        for (MenuDialog.Bottone b : dlg.bottoni()) {
            if (!b.showIf().vuoto() && !b.showIf().soddisfatti(p, variabili)) {
                continue;
            }
            bottoni.add(bottone(p, variabili, b, context));
        }

        DialogBase base = DialogBase.builder(Colors.component(Text.raw(p, variabili, def.title())))
                .canCloseWithEscape(def.freeClose())
                .pause(dlg.pauseUpdates())
                .body(body)
                .inputs(fields)
                .build();

        Dialog dialog = bottoni.isEmpty()
                ? Dialog.create(f -> f.empty().base(base).type(DialogType.notice()))
                : Dialog.create(f -> f.empty().base(base).type(DialogType.multiAction(bottoni).build()));

        if (!def.openActions().isEmpty()) {
            Actions.esegui(plugin, context, def.openActions());
        }
        p.showDialog(dialog);
    }

    // ------------------------------------------------------------------- pezzi

    private DialogInput field(Player p, Map<String, String> variabili, MenuDialog.Field c) {
        String label = Text.raw(p, variabili, c.label());
        switch (c.type()) {
            case TEXT -> {
                TextDialogInput.Builder b = DialogInput.text(c.key(), Colors.component(label))
                        .initial(Text.raw(p, variabili, c.iniziale()))
                        .maxLength(Math.max(1, c.lunghezza()))
                        .width(larghezza(c.larghezza()));
                if (c.multiLine()) {
                    b.multiline(TextDialogInput.MultilineOptions.create(null, 80));
                }
                return b.build();
            }
            case BOOLEANO -> {
                return DialogInput.bool(c.key(), Colors.component(label))
                        .initial(Boolean.parseBoolean(c.iniziale()))
                        .onTrue("true")
                        .onFalse("false")
                        .build();
            }
            case NUMERO -> {
                return DialogInput.numberRange(c.key(), Colors.component(label),
                                c.minimum(), c.maximum())
                        .step(c.step() > 0 ? c.step() : null)
                        .initial(numeroIniziale(c))
                        .width(larghezza(c.larghezza()))
                        .build();
            }
            case CHOICE -> {
                List<SingleOptionDialogInput.OptionEntry> entries = new ArrayList<>();
                for (int i = 0; i < c.opzioni().size(); i++) {
                    String o = Text.raw(p, variabili, c.opzioni().get(i));
                    entries.add(SingleOptionDialogInput.OptionEntry.create(o, Colors.component(o),
                            i == 0 || o.equalsIgnoreCase(c.iniziale())));
                }
                return DialogInput.singleOption(c.key(), Colors.component(label), entries)
                        .width(larghezza(c.larghezza()))
                        .build();
            }
            default -> {
                return null;
            }
        }
    }

    private static Float numeroIniziale(MenuDialog.Field c) {
        Double n = Text.number(c.iniziale());
        return n == null ? null : (float) (double) n;
    }

    /** La finestra e' larga 400: fuori da questi limiti il client disegna male. */
    private static int larghezza(int scritta) {
        return Math.max(1, Math.min(1024, scritta <= 0 ? 200 : scritta));
    }

    private ActionButton bottone(Player p, Map<String, String> variabili, MenuDialog.Bottone b,
                                 Context context) {
        ActionButton.Builder builder = ActionButton
                .builder(Colors.component(Text.raw(p, variabili, b.label())))
                .width(Math.max(1, Math.min(1024, b.larghezza())));
        if (b.suggestion() != null && !b.suggestion().isBlank()) {
            builder.tooltip(Colors.component(Text.raw(p, variabili, b.suggestion())));
        }

        ClickCallback.Options opzioni = ClickCallback.Options.builder()
                .uses(ClickCallback.UNLIMITED_USES)
                .lifetime(BUTTON_DURATION)
                .build();

        builder.action(DialogAction.customClick((risposta, chi) -> {
            // Quello che il giocatore ha scritto nei campi diventa %field_<nome>%, disponibile
            // alle azioni come qualunque altra variabile.
            Map<String, String> withFields = new LinkedHashMap<>(variabili);
            for (MenuDialog.Field c : fieldsOf(context)) {
                String v = value(risposta, c);
                withFields.put("field_" + c.key(), v);
                withFields.put("campo_" + c.key(), v);   // il vecchio nome, per i menu gia' scritti
            }
            // Le azioni toccano il mondo: vanno eseguite sul filo principale del server, non da
            // dove arriva la risposta del client.
            Bukkit.getScheduler().runTask(plugin, () ->
                    Actions.esegui(plugin, ((DialogContext) context).con(withFields), b.actions()));
        }, opzioni));

        return builder.build();
    }

    private static List<MenuDialog.Field> fieldsOf(Context context) {
        if (context instanceof DialogContext d && d.menu().dialog() != null) {
            return d.menu().dialog().fields();
        }
        return List.of();
    }

    private static String value(io.papermc.paper.dialog.DialogResponseView risposta, MenuDialog.Field c) {
        try {
            return switch (c.type()) {
                case TEXT, CHOICE -> {
                    String s = risposta.getText(c.key());
                    yield s == null ? "" : s;
                }
                case BOOLEANO -> String.valueOf(Boolean.TRUE.equals(risposta.getBoolean(c.key())));
                case NUMERO -> {
                    Float f = risposta.getFloat(c.key());
                    if (f == null) {
                        yield "";
                    }
                    // I numeri interi si scrivono senza il ",0" finale: finiscono dentro dei
                    // comandi, e "give ... 3.0" non e' un comando valido.
                    yield f == Math.rint(f) ? String.valueOf((long) (float) f) : String.valueOf(f);
                }
            };
        } catch (Exception e) {
            return "";
        }
    }

    private Map<String, String> variabili(MenuDef def, List<String> arguments) {
        Map<String, String> v = new LinkedHashMap<>();
        v.put("menu", def.name());
        v.put("page", "1");
        v.put("pagina", "1");
        v.put("pages", "1");
        v.put("pagine", "1");
        for (int i = 0; i < arguments.size(); i++) {
            v.put("arg_" + (i + 1), arguments.get(i));
            if (i < def.arguments().size()) {
                v.put("arg_" + def.arguments().get(i), arguments.get(i));
            }
        }
        for (int i = arguments.size(); i < def.arguments().size(); i++) {
            v.putIfAbsent("arg_" + def.arguments().get(i), "");
            v.putIfAbsent("arg_" + (i + 1), "");
        }
        return v;
    }
}
