package com.teolo.magixcosmetics.cosmetic;

import java.util.ArrayList;
import java.util.List;

/**
 * Come appare un firework: i colori, la sfumatura finale, la forma, lo sfarfallio e la scia.
 * I colori sono token testuali: il nome di un colore della tavolozza ({@code magenta}) oppure un
 * esadecimale {@code #RRGGBB}. Un campo {@code null} vuol dire "non scelto": vale il predefinito.
 */
public final class FireworkLook {

    public List<String> colors;
    public List<String> fade;
    public String shape;
    public Boolean flicker;
    public Boolean trail;

    boolean isEmpty() {
        return colors == null && fade == null && shape == null && flicker == null && trail == null;
    }

    FireworkLook copy() {
        FireworkLook c = new FireworkLook();
        c.colors = colors == null ? null : new ArrayList<>(colors);
        c.fade = fade == null ? null : new ArrayList<>(fade);
        c.shape = shape;
        c.flicker = flicker;
        c.trail = trail;
        return c;
    }
}
