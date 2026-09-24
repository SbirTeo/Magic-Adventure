package com.teolo.magixauth.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;

/**
 * L'elenco dei comandi: a sezioni, a pagine, e cliccabile.
 *
 * Nasce da elenchi lunghi venticinque righe che scorrevano via dalla chat prima ancora che si
 * potessero leggere — la chat ne mostra una decina, quindi tutto quello che sta sopra e' carta
 * sprecata. Qui se ne mostrano poche per volta, con le frecce per sfogliare.
 *
 * Ogni riga si clicca: il comando finisce nella barra della chat gia' scritto, pronto da
 * completare. E' la differenza fra leggere "/f claim" e doverlo ricopiare a mano senza sbagliare.
 *
 * Questa classe e' la STESSA in tutti i plugin Magix (stesso file, cambia solo il package): e' il
 * motivo per cui /mauth, /f, /mentities e /mtime mostrano lo stesso aiuto. Se la si modifica qui,
 * va riportata anche negli altri — vedi plugins-src/STILE-MAGIX.md.
 *
 * Ogni frase che il giocatore legge (titoli di sezione, spiegazioni, il testo di cornice come
 * "Clicca per scriverlo") passa da {@link Text}/{@link Lines}, tradotta nella sua lingua se
 * MagixLanguage c'e' ed e' installato: niente testo scritto a mano qui dentro (vedi CLAUDE.md,
 * "OGNI TESTO CHE UN GIOCATORE LEGGE VA IN messages.yml"). Le due interfacce esistono apposta
 * perche' questa classe non puo' importare la classe Messages di UN plugin specifico (altrimenti
 * non sarebbe piu' lo STESSO file negli altri): chi chiama passa un riferimento a un suo metodo
 * (es. {@code messages::forPlayer}). Le chiavi di cornice sono sotto help.chrome.* in ciascun
 * messages.yml.
 *
 * I colori vengono dal logo del server: il viola di MAGIC e il verde di ADVENTURE.
 */
public final class Help {

    /** Il testo tradotto per "path" per questo destinatario (di solito Messages.forPlayer). */
    public interface Text {
        String get(CommandSender to, String path, String... kv);
    }

    /** Come {@link Text}, per una chiave il cui valore e' una lista di righe. */
    public interface Lines {
        List<String> get(CommandSender to, String path);
    }

    /** Il viola di "MAGIC": il nome del plugin, i titoli, le cose da staff. */
    public static final TextColor PURPLE = TextColor.color(0xC0, 0x46, 0xE8);

    /** Il verde di "ADVENTURE": i comandi, cioe' tutto quello che si puo' cliccare. */
    public static final TextColor GREEN = TextColor.color(0xA8, 0xDC, 0x2C);

    /** Il testo di servizio: spiegazioni e argomenti. */
    public static final TextColor GREY = TextColor.color(0x9A, 0x8C, 0xA8);

    /** Cornici, separatori, frecce spente: si vede che c'e', non ruba l'occhio. */
    public static final TextColor FAINT = TextColor.color(0x5A, 0x50, 0x68);

    /** Quante righe per pagina: la chat ne mostra una decina, e servono titolo e frecce. */
    private static final int PER_PAGE = 8;

    /** Il separatore fra comando e spiegazione nelle voci scritte in messages.yml. */
    private static final String SEPARATOR = "::";

    /**
     * Una voce dell'elenco.
     *
     * @param comando     il comando, es. "/f claim"
     * @param argomenti   quello che ci va dietro, es. "&lt;nome&gt;"
     * @param spiegazione a cosa serve, in una riga (gia' tradotta)
     * @param sezione     il gruppo sotto cui compare, gia' tradotto (vuoto = nessun titolo)
     * @param soloStaff   se la riga si vede solo con il permesso di amministrazione
     */
    public record Entry(String command, String arguments, String explanation,
                       String section, boolean staffOnly) {

        public static Entry di(String command, String arguments, String explanation) {
            return new Entry(command, arguments, explanation, "", false);
        }

        public static Entry staff(String command, String arguments, String explanation) {
            return new Entry(command, arguments, explanation, "", true);
        }

        /** La stessa voce, messa sotto un titolo di sezione. */
        public Entry in(String section) {
            return new Entry(command, arguments, explanation, section, staffOnly);
        }
    }

    private Help() {
    }

    /**
     * Mostra una pagina dell'elenco.
     *
     * @param a      chi legge: decide anche in che lingua, tramite text/lines
     * @param text   il testo di cornice tradotto per un destinatario (di solito messages::forPlayer)
     * @param titolo il nome del plugin, come compare in cima
     * @param radice il comando da cui si sfoglia (per le frecce), es. "/f help"
     * @param voci   tutte le voci; quelle da staff si tolgono a chi non ha il permesso
     * @param pagina la pagina chiesta, a partire da 1
     * @param staff  se chi legge puo' vedere anche i comandi di amministrazione
     */
    public static void show(CommandSender a, Text text, String title, String root,
                              List<Entry> entries, int page, boolean staff) {
        List<Entry> visible = new ArrayList<>();
        for (Entry v : entries) {
            if (!v.staffOnly() || staff) {
                visible.add(v);
            }
        }
        if (visible.isEmpty()) {
            a.sendMessage(Component.text(text.get(a, "help.chrome.no-commands"), GREY));
            return;
        }

        List<List<Component>> pages = paginate(visible, a, text);
        int p = Math.max(1, Math.min(page, pages.size()));

        a.sendMessage(header(title, p, pages.size()));
        for (Component row : pages.get(p - 1)) {
            a.sendMessage(row);
        }
        a.sendMessage(footer(root, p, pages.size(), a, text));
    }

    /**
     * Legge le voci da messages.yml, gia' tradotte per chi le vedra'.
     *
     * Il formato di una riga e' "/f claim &lt;nome&gt; :: conquista il territorio": davanti al
     * "::" il comando com'e' da scrivere, dietro la spiegazione. Il primo pezzo e' il comando
     * vero, quello che il clic scrive in chat; il resto sono gli argomenti, che finiscono in
     * grigio perche' vanno riempiti a mano.
     *
     * <pre>
     * help:
     *   sections:
     *     territorio:
     *       title: "Territorio"
     *       staff: false          # true = sezione riservata a chi amministra
     *       entries:
     *         - "/f claim :: conquista il territorio in cui ti trovi"
     * </pre>
     */
    public static List<Entry> fromConfig(ConfigurationSection sections, CommandSender viewer,
                                          Text text, Lines lines) {
        List<Entry> entries = new ArrayList<>();
        if (sections == null) {
            return entries;
        }
        for (String key : sections.getKeys(false)) {
            ConfigurationSection s = sections.getConfigurationSection(key);
            if (s == null) {
                continue;
            }
            String title = text.get(viewer, "help.sections." + key + ".title");
            boolean staffOnly = s.getBoolean("staff", false);
            for (String row : lines.get(viewer, "help.sections." + key + ".entries")) {
                Entry v = entry(row, title, staffOnly);
                if (v != null) {
                    entries.add(v);
                }
            }
        }
        return entries;
    }

    /** Una riga "comando <args> :: spiegazione" gia' tradotta. */
    private static Entry entry(String row, String section, boolean staffOnly) {
        if (row == null || row.isBlank()) {
            return null;
        }
        int cut = row.indexOf(SEPARATOR);
        String left = (cut >= 0 ? row.substring(0, cut) : row).trim();
        String explanation = cut >= 0 ? row.substring(cut + SEPARATOR.length()).trim() : "";
        if (left.isEmpty()) {
            return null;
        }
        // Il comando e' il primo pezzo: "/f claim <nome>" -> comando "/f claim", argomenti "<nome>".
        // Il taglio cade dove finiscono le parole semplici: cosi' i sottocomandi restano attaccati
        // al comando (e il clic li scrive), mentre <nome> e [pagina] restano da riempire a mano.
        String[] pieces = left.split("\\s+");
        StringBuilder command = new StringBuilder(pieces[0]);
        int i = 1;
        while (i < pieces.length && pieces[i].matches("[A-Za-z0-9_-]+")) {
            command.append(' ').append(pieces[i]);
            i++;
        }
        StringBuilder arguments = new StringBuilder();
        for (; i < pieces.length; i++) {
            if (arguments.length() > 0) {
                arguments.append(' ');
            }
            arguments.append(pieces[i]);
        }
        return new Entry(command.toString(), arguments.toString(), explanation, section, staffOnly);
    }

    // -------------------------------------------------------------------------------

    /**
     * Divide le righe in pagine.
     *
     * L'unica astuzia sta nei titoli di sezione: un titolo da solo in fondo alla pagina non dice
     * niente a nessuno, quindi in quel caso la pagina si chiude prima e il titolo scende insieme
     * alle sue voci. Se una sezione prosegue nella pagina dopo, il titolo si ripete.
     */
    private static List<List<Component>> paginate(List<Entry> entries, CommandSender a, Text text) {
        List<List<Component>> pages = new ArrayList<>();
        List<Component> current = new ArrayList<>();
        String printedSection = null;

        for (Entry v : entries) {
            boolean newSection = !v.section().isEmpty() && !v.section().equals(printedSection);
            int needed = newSection ? 2 : 1; // il titolo non va mai lasciato solo in fondo

            if (current.size() + needed > PER_PAGE && !current.isEmpty()) {
                pages.add(current);
                current = new ArrayList<>();
                // A pagina nuova il titolo si riscrive: chi sfoglia deve sapere dove si trova.
                newSection = !v.section().isEmpty();
                printedSection = null;
            }
            if (newSection) {
                current.add(sectionTitle(v.section()));
                printedSection = v.section();
            }
            current.add(row(v, a, text));
        }
        if (!current.isEmpty()) {
            pages.add(current);
        }
        return pages;
    }

    /** Una riga di titolo con dei trattini ai lati, per staccare l'elenco dalla chat. */
    private static Component header(String title, int page, int pages) {
        Component rule = Component.text("─────", FAINT);
        Component name = Component.text(" " + title + " ", PURPLE)
                .decorate(TextDecoration.BOLD);
        Component counter = pages > 1
                ? Component.text(" " + page + "/" + pages + " ", GREY)
                : Component.text(" ");
        return Component.empty().append(rule).append(name).append(counter).append(rule);
    }

    /** Il titolo di un gruppo di comandi (gia' tradotto). */
    private static Component sectionTitle(String title) {
        return Component.empty()
                .append(Component.text(" ▸ ", FAINT))
                .append(Component.text(title, PURPLE).decorate(TextDecoration.BOLD));
    }

    /**
     * Una voce: il comando in verde, gli argomenti piu' tenui, la spiegazione in grigio.
     *
     * Cliccandola il comando si scrive da solo nella barra della chat — suggerito, non eseguito:
     * quasi tutti vogliono un argomento, e mandarli in esecuzione a vuoto produrrebbe solo un
     * messaggio d'errore.
     */
    private static Component row(Entry v, CommandSender a, Text text) {
        Component component = Component.text("  " + v.command(), v.staffOnly() ? PURPLE : GREEN);
        if (!v.arguments().isEmpty()) {
            component = component.append(Component.text(" " + v.arguments(), GREY));
        }
        if (!v.explanation().isEmpty()) {
            component = component.append(Component.text("  " + v.explanation(), GREY));
        }

        Component suggestion = Component.text(text.get(a, "help.chrome.click-to-write"), NamedTextColor.WHITE)
                .append(Component.newline())
                .append(Component.text(v.command()
                        + (v.arguments().isEmpty() ? "" : " " + v.arguments()), GREEN));
        if (v.staffOnly()) {
            suggestion = suggestion.append(Component.newline())
                    .append(Component.text(text.get(a, "help.chrome.staff-only"), PURPLE));
        }

        return component
                .clickEvent(ClickEvent.suggestCommand(v.command() + " "))
                .hoverEvent(HoverEvent.showText(suggestion));
    }

    /** Le frecce per sfogliare (spente dove non c'e' nulla) e il promemoria del clic. */
    private static Component footer(String root, int page, int pages, CommandSender a, Text text) {
        Component note = Component.text(text.get(a, "help.chrome.click-hint"), FAINT);
        if (pages <= 1) {
            return Component.text("  ").append(note);
        }

        String backLabel = text.get(a, "help.chrome.back");
        String forwardLabel = text.get(a, "help.chrome.forward");
        Component back = page > 1
                ? Component.text(backLabel, GREEN)
                        .clickEvent(ClickEvent.runCommand(root + " " + (page - 1)))
                        .hoverEvent(HoverEvent.showText(Component.text(
                                text.get(a, "help.chrome.page", "numero", String.valueOf(page - 1)), GREY)))
                : Component.text(backLabel, FAINT);

        Component forward = page < pages
                ? Component.text(forwardLabel, GREEN)
                        .clickEvent(ClickEvent.runCommand(root + " " + (page + 1)))
                        .hoverEvent(HoverEvent.showText(Component.text(
                                text.get(a, "help.chrome.page", "numero", String.valueOf(page + 1)), GREY)))
                : Component.text(forwardLabel, FAINT);

        return Component.empty()
                .append(Component.text("  "))
                .append(back)
                .append(Component.text("·", FAINT))
                .append(forward)
                .append(Component.text("  "))
                .append(note);
    }
}
