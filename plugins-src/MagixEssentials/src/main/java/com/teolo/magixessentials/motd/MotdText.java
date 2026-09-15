package com.teolo.magixessentials.motd;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * La MOTD: il testo che si legge nella lista server, prima di entrare. Qui c'e' solo il
 * <b>come si compone</b> — scelta della variante, segnaposto, colori, le due righe — e nient'altro.
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
 * {@link Component} di Adventure, che <b>Paper e Velocity hanno tutti e due</b>. Il pezzo legato
 * alla piattaforma e' solo chi ascolta il ping: {@link MotdListener} per Paper, e domani il suo
 * gemello di tre righe per Velocity. Se un giorno qui compare un {@code import org.bukkit}, quella
 * strada si e' chiusa.
 *
 * @see MotdListener
 */
public final class MotdText {

    /** &-codes + &#RRGGBB + il vecchio &x&r&r... di Bungee: lo stesso che usa il tablist. */
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('&')
            .hexColors()
            .useUnusualXRepeatedCharacterHexFormat()
            .build();

    /** Il client ne disegna due: dalla terza in giu' non si vedrebbero. */
    private static final int LINES_SHOWN = 2;

    private MotdText() {
    }

    /**
     * Il testo grezzo della MOTD da mostrare adesso: una voce a caso di {@code messages} se la
     * rotazione e' accesa, altrimenti le due righe fisse. Le righe restano separate da un a capo,
     * i colori e i segnaposto li risolve {@link #component(String, int, int)}.
     *
     * <p>Rotazione accesa e lista vuota vuol dire che qualcuno ha acceso l'interruttore e non ha
     * scritto le varianti: si torna alle due righe fisse invece di mostrare una MOTD vuota.</p>
     */
    public static String scegli(boolean aCaso, List<String> messaggi, String prima, String seconda) {
        if (aCaso && messaggi != null && !messaggi.isEmpty()) {
            return messaggi.get(ThreadLocalRandom.current().nextInt(messaggi.size()));
        }
        String uno = prima == null ? "" : prima;
        String due = seconda == null ? "" : seconda;
        return due.isEmpty() ? uno : uno + "\n" + due;
    }

    /**
     * Il testo grezzo diventa la MOTD vera: {@code {online}} e {@code {max}} al loro posto, i
     * colori applicati, le righe a capo tenute insieme (il client ne disegna due).
     */
    public static Component component(String testo, int online, int max) {
        Component out = Component.empty();
        List<String> righe = righe(testo, online, max);
        for (int i = 0; i < righe.size(); i++) {
            if (i > 0) out = out.append(Component.newline());
            out = out.append(LEGACY.deserialize(righe.get(i)));
        }
        return out;
    }

    /**
     * Le righe della <b>tendina</b> sopra il numero dei giocatori (quella che esce passandoci
     * sopra col mouse), coi segnaposto risolti. Restano stringhe coi codici colore: sia Paper sia
     * Velocity vogliono dei nomi li' dentro, non dei Component.
     */
    public static List<String> tendina(List<String> righe, int online, int max) {
        List<String> fuori = new ArrayList<>();
        if (righe == null) return fuori;
        for (String riga : righe) fuori.add(segnaposto(riga, online, max));
        return fuori;
    }

    /** Il testo spezzato nelle righe da disegnare: segnaposto risolti, al massimo due. */
    private static List<String> righe(String testo, int online, int max) {
        List<String> fuori = new ArrayList<>();
        if (testo == null || testo.isEmpty()) return fuori;
        // Il \n scritto a mano nel file vale come un a capo vero. In YAML solo le virgolette
        // DOPPIE lo traducono: fra apici singoli restano due caratteri, e la MOTD usciva con un
        // "\n" stampato in mezzo. Meglio accettarli tutti e due che spiegare la differenza.
        String[] pezzi = segnaposto(testo, online, max).replace("\\n", "\n").split("\n", -1);
        for (int i = 0; i < pezzi.length && i < LINES_SHOWN; i++) fuori.add(pezzi[i]);
        return fuori;
    }

    /** {online} e {max}: gli unici due segnaposto che al ping si sanno davvero. */
    private static String segnaposto(String testo, int online, int max) {
        if (testo == null) return "";
        return testo.replace("{online}", String.valueOf(online))
                .replace("{max}", String.valueOf(max));
    }
}
