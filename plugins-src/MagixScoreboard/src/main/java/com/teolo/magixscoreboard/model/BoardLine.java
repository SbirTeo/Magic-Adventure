package com.teolo.magixscoreboard.model;

import java.util.List;

/**
 * Uno slot animato del titolo o di una riga: uno o piu' "frame" di testo che si alternano ogni
 * {@code intervalTicks} tick. Con un solo frame lo slot e' fisso (i placeholder si aggiornano
 * comunque a ogni ciclo di refresh). Con piu' frame copre sia un'animazione del singolo testo
 * (frame quasi identici, es. un puntino che si sposta) sia piu' righe del tutto diverse che si
 * alternano nello stesso posto, in ordine.
 *
 * @param scroll se non null, il testo (gia' risolto) scorre da destra verso sinistra in una finestra
 *               di caratteri fissa, come un'insegna luminosa
 */
public record BoardLine(int intervalTicks, List<String> frames, Scroll scroll) {

    /**
     * Lo scorrimento di uno slot.
     *
     * @param width      caratteri visibili alla volta
     * @param gap        spazi vuoti fra la fine del testo e il suo nuovo inizio
     * @param speedTicks ogni quanti tick il testo avanza di un carattere
     */
    public record Scroll(int width, int gap, int speedTicks) {}

    public BoardLine {
        frames = List.copyOf(frames);
    }

    public BoardLine(int intervalTicks, List<String> frames) {
        this(intervalTicks, frames, null);
    }

    /** Il testo grezzo (non ancora risolto/colorato) da mostrare al tick indicato. */
    public String frameAt(long tick) {
        if (frames.isEmpty()) return "";
        if (frames.size() == 1 || intervalTicks <= 0) return frames.get(0);
        int index = (int) ((tick / intervalTicks) % frames.size());
        return frames.get(index);
    }
}
