package com.teolo.magixmenus.dialogo;

import com.teolo.magixmenus.MagixMenus;
import com.teolo.magixmenus.azioni.Azioni;
import com.teolo.magixmenus.azioni.Contesto;
import com.teolo.magixmenus.menu.Dialogo;
import com.teolo.magixmenus.menu.MenuAperto;
import com.teolo.magixmenus.menu.MenuDef;
import com.teolo.magixmenus.util.Colors;
import com.teolo.magixmenus.util.Testo;
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
public final class GestoreDialoghi {

    /** Per quanto un bottone resta collegato alle sue azioni. */
    private static final Duration DURATA_BOTTONI = Duration.ofMinutes(10);

    private final MagixMenus plugin;

    public GestoreDialoghi(MagixMenus plugin) {
        this.plugin = plugin;
    }

    public void apri(Player p, MenuDef def, List<String> argomenti, MenuAperto provenienza) {
        Dialogo dlg = def.dialogo();
        if (dlg == null) {
            plugin.getLogger().warning("Il menu \"" + def.nome() + "\" e' di tipo dialogo ma non ha "
                    + "il blocco del dialogo: non lo apro.");
            return;
        }

        Map<String, String> variabili = variabili(def, argomenti);
        Contesto contesto = new ContestoDialogo(plugin, p, variabili, def, provenienza);

        if (!def.apriSe().vuoto() && !def.apriSe().soddisfatti(p, variabili)) {
            if (!def.apriSe().azioniNegate().isEmpty()) {
                Azioni.esegui(plugin, contesto, def.apriSe().azioniNegate());
            } else {
                plugin.messaggi().send(p, "requirements-not-met");
            }
            return;
        }

        List<DialogBody> corpo = new ArrayList<>();
        for (String riga : dlg.corpo()) {
            corpo.add(DialogBody.plainMessage(Colors.component(Testo.grezzo(p, variabili, riga)), 300));
        }

        List<DialogInput> campi = new ArrayList<>();
        for (Dialogo.Campo c : dlg.campi()) {
            DialogInput input = campo(p, variabili, c);
            if (input != null) {
                campi.add(input);
            }
        }

        List<ActionButton> bottoni = new ArrayList<>();
        for (Dialogo.Bottone b : dlg.bottoni()) {
            if (!b.mostraSe().vuoto() && !b.mostraSe().soddisfatti(p, variabili)) {
                continue;
            }
            bottoni.add(bottone(p, variabili, b, contesto));
        }

        DialogBase base = DialogBase.builder(Colors.component(Testo.grezzo(p, variabili, def.titolo())))
                .canCloseWithEscape(def.chiusuraLibera())
                .pause(dlg.mettiInPausa())
                .body(corpo)
                .inputs(campi)
                .build();

        Dialog dialogo = bottoni.isEmpty()
                ? Dialog.create(f -> f.empty().base(base).type(DialogType.notice()))
                : Dialog.create(f -> f.empty().base(base).type(DialogType.multiAction(bottoni).build()));

        if (!def.azioniApertura().isEmpty()) {
            Azioni.esegui(plugin, contesto, def.azioniApertura());
        }
        p.showDialog(dialogo);
    }

    // ------------------------------------------------------------------- pezzi

    private DialogInput campo(Player p, Map<String, String> variabili, Dialogo.Campo c) {
        String etichetta = Testo.grezzo(p, variabili, c.etichetta());
        switch (c.tipo()) {
            case TESTO -> {
                TextDialogInput.Builder b = DialogInput.text(c.chiave(), Colors.component(etichetta))
                        .initial(Testo.grezzo(p, variabili, c.iniziale()))
                        .maxLength(Math.max(1, c.lunghezza()))
                        .width(larghezza(c.larghezza()));
                if (c.piuRighe()) {
                    b.multiline(TextDialogInput.MultilineOptions.create(null, 80));
                }
                return b.build();
            }
            case BOOLEANO -> {
                return DialogInput.bool(c.chiave(), Colors.component(etichetta))
                        .initial(Boolean.parseBoolean(c.iniziale()))
                        .onTrue("true")
                        .onFalse("false")
                        .build();
            }
            case NUMERO -> {
                return DialogInput.numberRange(c.chiave(), Colors.component(etichetta),
                                c.minimo(), c.massimo())
                        .step(c.passo() > 0 ? c.passo() : null)
                        .initial(numeroIniziale(c))
                        .width(larghezza(c.larghezza()))
                        .build();
            }
            case SCELTA -> {
                List<SingleOptionDialogInput.OptionEntry> voci = new ArrayList<>();
                for (int i = 0; i < c.opzioni().size(); i++) {
                    String o = Testo.grezzo(p, variabili, c.opzioni().get(i));
                    voci.add(SingleOptionDialogInput.OptionEntry.create(o, Colors.component(o),
                            i == 0 || o.equalsIgnoreCase(c.iniziale())));
                }
                return DialogInput.singleOption(c.chiave(), Colors.component(etichetta), voci)
                        .width(larghezza(c.larghezza()))
                        .build();
            }
            default -> {
                return null;
            }
        }
    }

    private static Float numeroIniziale(Dialogo.Campo c) {
        Double n = Testo.numero(c.iniziale());
        return n == null ? null : (float) (double) n;
    }

    /** La finestra e' larga 400: fuori da questi limiti il client disegna male. */
    private static int larghezza(int scritta) {
        return Math.max(1, Math.min(1024, scritta <= 0 ? 200 : scritta));
    }

    private ActionButton bottone(Player p, Map<String, String> variabili, Dialogo.Bottone b,
                                 Contesto contesto) {
        ActionButton.Builder builder = ActionButton
                .builder(Colors.component(Testo.grezzo(p, variabili, b.etichetta())))
                .width(Math.max(1, Math.min(1024, b.larghezza())));
        if (b.suggerimento() != null && !b.suggerimento().isBlank()) {
            builder.tooltip(Colors.component(Testo.grezzo(p, variabili, b.suggerimento())));
        }

        ClickCallback.Options opzioni = ClickCallback.Options.builder()
                .uses(ClickCallback.UNLIMITED_USES)
                .lifetime(DURATA_BOTTONI)
                .build();

        builder.action(DialogAction.customClick((risposta, chi) -> {
            // Quello che il giocatore ha scritto nei campi diventa %field_<nome>%, disponibile
            // alle azioni come qualunque altra variabile.
            Map<String, String> conCampi = new LinkedHashMap<>(variabili);
            for (Dialogo.Campo c : campiDi(contesto)) {
                String v = valore(risposta, c);
                conCampi.put("field_" + c.chiave(), v);
                conCampi.put("campo_" + c.chiave(), v);   // il vecchio nome, per i menu gia' scritti
            }
            // Le azioni toccano il mondo: vanno eseguite sul filo principale del server, non da
            // dove arriva la risposta del client.
            Bukkit.getScheduler().runTask(plugin, () ->
                    Azioni.esegui(plugin, ((ContestoDialogo) contesto).con(conCampi), b.azioni()));
        }, opzioni));

        return builder.build();
    }

    private static List<Dialogo.Campo> campiDi(Contesto contesto) {
        if (contesto instanceof ContestoDialogo d && d.menu().dialogo() != null) {
            return d.menu().dialogo().campi();
        }
        return List.of();
    }

    private static String valore(io.papermc.paper.dialog.DialogResponseView risposta, Dialogo.Campo c) {
        try {
            return switch (c.tipo()) {
                case TESTO, SCELTA -> {
                    String s = risposta.getText(c.chiave());
                    yield s == null ? "" : s;
                }
                case BOOLEANO -> String.valueOf(Boolean.TRUE.equals(risposta.getBoolean(c.chiave())));
                case NUMERO -> {
                    Float f = risposta.getFloat(c.chiave());
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

    private Map<String, String> variabili(MenuDef def, List<String> argomenti) {
        Map<String, String> v = new LinkedHashMap<>();
        v.put("menu", def.nome());
        v.put("page", "1");
        v.put("pagina", "1");
        v.put("pages", "1");
        v.put("pagine", "1");
        for (int i = 0; i < argomenti.size(); i++) {
            v.put("arg_" + (i + 1), argomenti.get(i));
            if (i < def.argomenti().size()) {
                v.put("arg_" + def.argomenti().get(i), argomenti.get(i));
            }
        }
        for (int i = argomenti.size(); i < def.argomenti().size(); i++) {
            v.putIfAbsent("arg_" + def.argomenti().get(i), "");
            v.putIfAbsent("arg_" + (i + 1), "");
        }
        return v;
    }
}
