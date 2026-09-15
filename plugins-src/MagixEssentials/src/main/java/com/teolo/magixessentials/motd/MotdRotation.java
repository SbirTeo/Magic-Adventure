package com.teolo.magixessentials.motd;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Quale voce mostrare adesso, fra quelle scritte nel file: la regola di rotazione della MOTD (e,
 * con un'istanza sua, delle icone).
 *
 * <h2>I tre modi</h2>
 * <ul>
 *   <li><b>random</b> — una a caso. Con {@code avoid-repeat} non esce due volte di fila la stessa:
 *       su una lista di due o tre voci e' la differenza fra "cambia" e "sembra rotto".</li>
 *   <li><b>ordered</b> — una dopo l'altra, dalla prima all'ultima e poi daccapo.</li>
 *   <li><b>fixed</b> — sempre la prima. Le altre restano nel file, pronte.</li>
 * </ul>
 *
 * <h2>Ogni quanto</h2>
 * Il ping arriva ogni volta che qualcuno apre la lista server, cioe' spesso e in modo irregolare.
 * Cambiare a ogni ping fa ballare la MOTD sotto gli occhi di chi tiene la lista aperta; per questo
 * c'e' un intervallo: dentro quella finestra la scelta resta quella, e tutti vedono la stessa cosa.
 * A 0 si torna al cambio a ogni ping.
 *
 * <p>Come {@link MotdText}, questa classe non sa niente di Bukkit: la stessa regola servira' al
 * plugin Velocity, il giorno che al ping rispondera' il proxy.</p>
 *
 * <p><b>Il ping non arriva dal thread principale</b> e possono arrivarne diversi insieme: lo stato
 * (quale voce, da quando) si tocca sotto {@code synchronized}. Sono due campi e nessuna attesa.</p>
 */
public final class MotdRotation {

    /** Una a caso. */
    public static final String RANDOM = "random";
    /** Una dopo l'altra. */
    public static final String ORDERED = "ordered";
    /** Sempre la prima. */
    public static final String FIXED = "fixed";

    private final String modo;
    private final long ogniMillis;
    private final boolean evitaRipetizioni;

    private int ultimo = -1;
    private long sceltoAlle;

    public MotdRotation(String modo, int ogniSecondi, boolean evitaRipetizioni) {
        // Un modo scritto male non deve spegnere la MOTD: si va a caso, che e' il caso normale,
        // e chi guarda il file vede i tre nomi buoni scritti nel commento.
        String m = modo == null ? RANDOM : modo.trim().toLowerCase(java.util.Locale.ROOT);
        this.modo = (m.equals(ORDERED) || m.equals(FIXED)) ? m : RANDOM;
        this.ogniMillis = Math.max(0, ogniSecondi) * 1000L;
        this.evitaRipetizioni = evitaRipetizioni;
    }

    /**
     * L'indice della voce da mostrare adesso, o -1 se non ce n'e' nessuna.
     *
     * @param quante quante voci ci sono nel file in questo momento
     */
    public synchronized int indice(int quante) {
        if (quante <= 0) return -1;
        long adesso = System.currentTimeMillis();

        // Dentro la finestra si tiene quella di prima. Il controllo su 'quante' serve perche' la
        // lista puo' essersi accorciata con un reload: l'indice di prima potrebbe non esistere piu'.
        if (ogniMillis > 0 && ultimo >= 0 && ultimo < quante && adesso - sceltoAlle < ogniMillis) {
            return ultimo;
        }

        int scelto;
        switch (modo) {
            case FIXED -> scelto = 0;
            case ORDERED -> scelto = (ultimo + 1) % quante;
            default -> {
                scelto = ThreadLocalRandom.current().nextInt(quante);
                // Con una voce sola non c'e' niente da evitare, e il ciclo non finirebbe mai.
                if (evitaRipetizioni && quante > 1) {
                    while (scelto == ultimo) scelto = ThreadLocalRandom.current().nextInt(quante);
                }
            }
        }
        ultimo = scelto;
        sceltoAlle = adesso;
        return scelto;
    }
}
