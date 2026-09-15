package com.teolo.magixessentials.motd;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * La MOTD: il testo che si legge nella lista server, prima di entrare. Qui c'e' solo il
 * <b>come si compone</b> — segnaposto, colori, sfumature, le due righe — e nient'altro.
 *
 * <h2>Perche' questa classe non sa niente di Bukkit</h2>
 * La MOTD la scrive chi risponde al ping. Oggi risponde il server Paper, e il ping glielo manda
 * direttamente il client. Il giorno che davanti ci sara' <b>Velocity</b>, al ping rispondera' il
 * proxy: il server dietro non lo vedra' nemmeno, e un listener di Bukkit non potra' farci niente.
 * Quella MOTD la dovra' comporre un plugin Velocity — che pero' e' la stessa MOTD, con lo stesso
 * file e le stesse regole.
 *
 * <p>Percio' qui dentro non si entra con un evento di Bukkit e non si esce con roba di Bukkit: si
 * entra con dei valori (le righe del file, quanti sono online, qual e' il massimo) e si esce con un
 * {@link Component} di Adventure, che <b>Paper e Velocity hanno tutti e due</b> — MiniMessage
 * compreso, che e' quello che disegna le sfumature. Il pezzo legato alla piattaforma e' solo chi
 * ascolta il ping: {@link MotdListener} per Paper, e domani il suo gemello per Velocity. Se un
 * giorno qui compare un {@code import org.bukkit}, quella strada si e' chiusa.
 *
 * @see MotdRotation
 * @see MotdListener
 */
public final class MotdText {

    /** &-codes + &#RRGGBB + il vecchio &x&r&r... di Bungee: lo stesso che usa il tablist. */
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('&')
            .hexColors()
            .useUnusualXRepeatedCharacterHexFormat()
            .build();

    /**
     * Lo stesso, ma col <b>§</b>: e' il carattere che capisce il CLIENT. Le righe della tendina
     * non sono componenti, sono stringhe che il gioco disegna da se', e li' una & resta una &
     * scritta a schermo. Tutto quello che esce di qui passa da questo.
     */
    private static final LegacyComponentSerializer SECTION = LegacyComponentSerializer.builder()
            .character('\u00a7')
            .hexColors()
            .useUnusualXRepeatedCharacterHexFormat()
            .build();

    /** I tag: &lt;bold&gt;, &lt;color:#C046E8&gt;, &lt;gradient:...&gt;, &lt;rainbow&gt;... */
    private static final MiniMessage TAGS = MiniMessage.miniMessage();

    /**
     * Come si riconosce una riga scritta coi tag: un {@code <} seguito da una lettera (o da una
     * barra di chiusura) e chiuso da un {@code >}. Una riga di soli codici {@code &} non ci
     * assomiglia mai, e un {@code <} scritto per dire "minore" nemmeno — gli manca la chiusura.
     */
    private static final Pattern HAS_TAGS = Pattern.compile("<[a-zA-Z/][^<>]*>");

    /** Il client ne disegna due: dalla terza in giu' non si vedrebbero. */
    private static final int LINES_SHOWN = 2;

    private MotdText() {
    }

    /**
     * Il testo grezzo diventa la MOTD vera: segnaposto al loro posto, colori e sfumature
     * applicati, le righe a capo tenute insieme (il client ne disegna due).
     *
     * @param testo   la voce scelta, con le due righe separate da un a capo
     * @param online  quanti giocatori ci sono adesso
     * @param max     il massimo da mostrare
     * @param version la versione del server
     */
    public static Component component(String testo, int online, int max, String version) {
        Component out = Component.empty();
        List<String> righe = righe(testo, online, max, version);
        for (int i = 0; i < righe.size(); i++) {
            if (i > 0) out = out.append(Component.newline());
            out = out.append(riga(righe.get(i)));
        }
        return out;
    }

    /**
     * Una riga diventa un pezzo di MOTD. Se ci sono dei tag la legge MiniMessage (e li' vivono le
     * sfumature), altrimenti valgono i codici {@code &}. Uno dei due, non tutti e due insieme: un
     * testo che li mescola uscirebbe a meta' in un modo e a meta' nell'altro, che e' peggio di una
     * regola netta.
     */
    public static Component riga(String riga) {
        if (riga == null || riga.isEmpty()) return Component.empty();
        if (HAS_TAGS.matcher(riga).find()) {
            try {
                return TAGS.deserialize(riga);
            } catch (RuntimeException e) {
                // Un tag scritto male non deve far sparire la MOTD: si ripiega sui codici &, che
                // al massimo lasciano i <triangoli> scritti a schermo. Meglio brutta che assente.
                return LEGACY.deserialize(riga);
            }
        }
        return LEGACY.deserialize(riga);
    }

    /**
     * Le righe della <b>tendina</b> sopra il numero dei giocatori (quella che esce passandoci
     * sopra col mouse), coi segnaposto risolti.
     *
     * <p>Qui il protocollo non vuole dei componenti ma dei <b>nomi di giocatore</b>: stringhe, che
     * il client colora da se' leggendo i codici <b>§</b>. Percio' ogni riga viene composta come le
     * altre — codici {@code &} o tag, sfumature comprese — e poi riscritta col §: una & lasciata
     * li' resterebbe scritta a schermo, e i tag pure.</p>
     *
     * <p>Una sfumatura sopravvive, ma un carattere alla volta: ogni lettera si porta dietro il suo
     * codice colore, e una riga di trenta lettere diventa una stringa di qualche centinaio di
     * caratteri. Funziona, e conviene tenerla per una riga sola.</p>
     */
    public static List<String> tendina(List<String> righe, int online, int max, String version) {
        List<String> fuori = new ArrayList<>();
        if (righe == null) return fuori;
        for (String riga : righe) {
            fuori.add(SECTION.serialize(riga(segnaposto(riga, online, max, version))));
        }
        return fuori;
    }

    /** Il testo senza colori ne' tag: per il log, dove i codici sarebbero solo rumore. */
    public static String semplice(String testo) {
        if (testo == null || testo.isEmpty()) return "";
        return PlainTextComponentSerializer.plainText().serialize(riga(testo));
    }

    /** Il testo spezzato nelle righe da disegnare: segnaposto risolti, al massimo due. */
    private static List<String> righe(String testo, int online, int max, String version) {
        List<String> fuori = new ArrayList<>();
        if (testo == null || testo.isEmpty()) return fuori;
        // Il \n scritto a mano nel file vale come un a capo vero. In YAML solo le virgolette
        // DOPPIE lo traducono: fra apici singoli restano due caratteri, e la MOTD usciva con un
        // "\n" stampato in mezzo. Meglio accettarli tutti e due che spiegare la differenza.
        String[] pezzi = segnaposto(testo, online, max, version)
                .replace("\\n", "\n")
                .split("\n", -1);
        for (int i = 0; i < pezzi.length && i < LINES_SHOWN; i++) fuori.add(pezzi[i]);
        return fuori;
    }

    /** I segnaposto: al ping si sa quanti sono online, il massimo e la versione. Nient'altro. */
    private static String segnaposto(String testo, int online, int max, String version) {
        if (testo == null) return "";
        return testo.replace("{online}", String.valueOf(online))
                .replace("{max}", String.valueOf(max))
                .replace("{version}", version == null ? "" : version);
    }
}
