package com.teolo.magixmenus.util;

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
 * I colori vengono dal logo del server: il viola di MAGIC e il verde di ADVENTURE.
 */
public final class Help {

    /** Il viola di "MAGIC": il nome del plugin, i titoli, le cose da staff. */
    public static final TextColor VIOLA = TextColor.color(0xC0, 0x46, 0xE8);

    /** Il verde di "ADVENTURE": i comandi, cioe' tutto quello che si puo' cliccare. */
    public static final TextColor VERDE = TextColor.color(0xA8, 0xDC, 0x2C);

    /** Il testo di servizio: spiegazioni e argomenti. */
    public static final TextColor GRIGIO = TextColor.color(0x9A, 0x8C, 0xA8);

    /** Cornici, separatori, frecce spente: si vede che c'e', non ruba l'occhio. */
    public static final TextColor TENUE = TextColor.color(0x5A, 0x50, 0x68);

    /** Quante righe per pagina: la chat ne mostra una decina, e servono titolo e frecce. */
    private static final int PER_PAGINA = 8;

    /** Il separatore fra comando e spiegazione nelle voci scritte in messages.yml. */
    private static final String SEPARATORE = "::";

    /**
     * Una voce dell'elenco.
     *
     * @param comando     il comando, es. "/f claim"
     * @param argomenti   quello che ci va dietro, es. "&lt;nome&gt;"
     * @param spiegazione a cosa serve, in una riga
     * @param sezione     il gruppo sotto cui compare (vuoto = nessun titolo)
     * @param soloStaff   se la riga si vede solo con il permesso di amministrazione
     */
    public record Voce(String comando, String argomenti, String spiegazione,
                       String sezione, boolean soloStaff) {

        public static Voce di(String comando, String argomenti, String spiegazione) {
            return new Voce(comando, argomenti, spiegazione, "", false);
        }

        public static Voce staff(String comando, String argomenti, String spiegazione) {
            return new Voce(comando, argomenti, spiegazione, "", true);
        }

        /** La stessa voce, messa sotto un titolo di sezione. */
        public Voce in(String sezione) {
            return new Voce(comando, argomenti, spiegazione, sezione, soloStaff);
        }
    }

    private Help() {
    }

    /**
     * Mostra una pagina dell'elenco.
     *
     * @param titolo il nome del plugin, come compare in cima
     * @param radice il comando da cui si sfoglia (per le frecce), es. "/f help"
     * @param voci   tutte le voci; quelle da staff si tolgono a chi non ha il permesso
     * @param pagina la pagina chiesta, a partire da 1
     * @param staff  se chi legge puo' vedere anche i comandi di amministrazione
     */
    public static void mostra(CommandSender a, String titolo, String radice,
                              List<Voce> voci, int pagina, boolean staff) {
        List<Voce> visibili = new ArrayList<>();
        for (Voce v : voci) {
            if (!v.soloStaff() || staff) {
                visibili.add(v);
            }
        }
        if (visibili.isEmpty()) {
            a.sendMessage(Component.text("Nessun comando disponibile.", GRIGIO));
            return;
        }

        List<List<Component>> pagine = impagina(visibili);
        int p = Math.max(1, Math.min(pagina, pagine.size()));

        a.sendMessage(intestazione(titolo, p, pagine.size()));
        for (Component riga : pagine.get(p - 1)) {
            a.sendMessage(riga);
        }
        a.sendMessage(pieDiPagina(radice, p, pagine.size()));
    }

    /**
     * Legge le voci da messages.yml.
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
    public static List<Voce> daConfig(ConfigurationSection sezioni) {
        List<Voce> voci = new ArrayList<>();
        if (sezioni == null) {
            return voci;
        }
        for (String chiave : sezioni.getKeys(false)) {
            ConfigurationSection s = sezioni.getConfigurationSection(chiave);
            if (s == null) {
                continue;
            }
            String titolo = s.getString("title", "");
            boolean soloStaff = s.getBoolean("staff", false);
            for (String riga : s.getStringList("entries")) {
                Voce v = voce(riga, titolo, soloStaff);
                if (v != null) {
                    voci.add(v);
                }
            }
        }
        return voci;
    }

    /** Una riga "comando <args> :: spiegazione" letta da messages.yml. */
    private static Voce voce(String riga, String sezione, boolean soloStaff) {
        if (riga == null || riga.isBlank()) {
            return null;
        }
        int taglio = riga.indexOf(SEPARATORE);
        String sinistra = (taglio >= 0 ? riga.substring(0, taglio) : riga).trim();
        String spiegazione = taglio >= 0 ? riga.substring(taglio + SEPARATORE.length()).trim() : "";
        if (sinistra.isEmpty()) {
            return null;
        }
        // Il comando e' il primo pezzo: "/f claim <nome>" -> comando "/f claim", argomenti "<nome>".
        // Il taglio cade dove finiscono le parole semplici: cosi' i sottocomandi restano attaccati
        // al comando (e il clic li scrive), mentre <nome> e [pagina] restano da riempire a mano.
        String[] pezzi = sinistra.split("\\s+");
        StringBuilder comando = new StringBuilder(pezzi[0]);
        int i = 1;
        while (i < pezzi.length && pezzi[i].matches("[A-Za-z0-9_-]+")) {
            comando.append(' ').append(pezzi[i]);
            i++;
        }
        StringBuilder argomenti = new StringBuilder();
        for (; i < pezzi.length; i++) {
            if (argomenti.length() > 0) {
                argomenti.append(' ');
            }
            argomenti.append(pezzi[i]);
        }
        return new Voce(comando.toString(), argomenti.toString(), spiegazione, sezione, soloStaff);
    }

    // -------------------------------------------------------------------------------

    /**
     * Divide le righe in pagine.
     *
     * L'unica astuzia sta nei titoli di sezione: un titolo da solo in fondo alla pagina non dice
     * niente a nessuno, quindi in quel caso la pagina si chiude prima e il titolo scende insieme
     * alle sue voci. Se una sezione prosegue nella pagina dopo, il titolo si ripete.
     */
    private static List<List<Component>> impagina(List<Voce> voci) {
        List<List<Component>> pagine = new ArrayList<>();
        List<Component> corrente = new ArrayList<>();
        String sezioneStampata = null;

        for (Voce v : voci) {
            boolean nuovaSezione = !v.sezione().isEmpty() && !v.sezione().equals(sezioneStampata);
            int servono = nuovaSezione ? 2 : 1; // il titolo non va mai lasciato solo in fondo

            if (corrente.size() + servono > PER_PAGINA && !corrente.isEmpty()) {
                pagine.add(corrente);
                corrente = new ArrayList<>();
                // A pagina nuova il titolo si riscrive: chi sfoglia deve sapere dove si trova.
                nuovaSezione = !v.sezione().isEmpty();
                sezioneStampata = null;
            }
            if (nuovaSezione) {
                corrente.add(titoloSezione(v.sezione()));
                sezioneStampata = v.sezione();
            }
            corrente.add(riga(v));
        }
        if (!corrente.isEmpty()) {
            pagine.add(corrente);
        }
        return pagine;
    }

    /** Una riga di titolo con dei trattini ai lati, per staccare l'elenco dalla chat. */
    private static Component intestazione(String titolo, int pagina, int pagine) {
        Component filo = Component.text("─────", TENUE);
        Component nome = Component.text(" " + titolo + " ", VIOLA)
                .decorate(TextDecoration.BOLD);
        Component conta = pagine > 1
                ? Component.text(" " + pagina + "/" + pagine + " ", GRIGIO)
                : Component.text(" ");
        return Component.empty().append(filo).append(nome).append(conta).append(filo);
    }

    /** Il titolo di un gruppo di comandi. */
    private static Component titoloSezione(String titolo) {
        return Component.empty()
                .append(Component.text(" ▸ ", TENUE))
                .append(Component.text(titolo, VIOLA).decorate(TextDecoration.BOLD));
    }

    /**
     * Una voce: il comando in verde, gli argomenti piu' tenui, la spiegazione in grigio.
     *
     * Cliccandola il comando si scrive da solo nella barra della chat — suggerito, non eseguito:
     * quasi tutti vogliono un argomento, e mandarli in esecuzione a vuoto produrrebbe solo un
     * messaggio d'errore.
     */
    private static Component riga(Voce v) {
        Component testo = Component.text("  " + v.comando(), v.soloStaff() ? VIOLA : VERDE);
        if (!v.argomenti().isEmpty()) {
            testo = testo.append(Component.text(" " + v.argomenti(), GRIGIO));
        }
        if (!v.spiegazione().isEmpty()) {
            testo = testo.append(Component.text("  " + v.spiegazione(), GRIGIO));
        }

        Component suggerimento = Component.text("Clicca per scriverlo", NamedTextColor.WHITE)
                .append(Component.newline())
                .append(Component.text(v.comando()
                        + (v.argomenti().isEmpty() ? "" : " " + v.argomenti()), VERDE));
        if (v.soloStaff()) {
            suggerimento = suggerimento.append(Component.newline())
                    .append(Component.text("Riservato allo staff", VIOLA));
        }

        return testo
                .clickEvent(ClickEvent.suggestCommand(v.comando() + " "))
                .hoverEvent(HoverEvent.showText(suggerimento));
    }

    /** Le frecce per sfogliare (spente dove non c'e' nulla) e il promemoria del clic. */
    private static Component pieDiPagina(String radice, int pagina, int pagine) {
        Component nota = Component.text("clicca un comando per scriverlo", TENUE);
        if (pagine <= 1) {
            return Component.text("  ").append(nota);
        }

        Component indietro = pagina > 1
                ? Component.text(" ‹ indietro ", VERDE)
                        .clickEvent(ClickEvent.runCommand(radice + " " + (pagina - 1)))
                        .hoverEvent(HoverEvent.showText(Component.text("Pagina " + (pagina - 1), GRIGIO)))
                : Component.text(" ‹ indietro ", TENUE);

        Component avanti = pagina < pagine
                ? Component.text(" avanti › ", VERDE)
                        .clickEvent(ClickEvent.runCommand(radice + " " + (pagina + 1)))
                        .hoverEvent(HoverEvent.showText(Component.text("Pagina " + (pagina + 1), GRIGIO)))
                : Component.text(" avanti › ", TENUE);

        return Component.empty()
                .append(Component.text("  "))
                .append(indietro)
                .append(Component.text("·", TENUE))
                .append(avanti)
                .append(Component.text("  "))
                .append(nota);
    }
}
