package com.teolo.magixfactions.resourcepack;

import org.bukkit.entity.Player;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;

/**
 * Renderer TEMPORANEO (Fase 1 del piano minimap v3, solo per test in gioco): riempie una mappa di
 * prova di ciano e scrive l'HEADER MAGICO nei primi 3 pixel della riga 0. Serve a verificare che lo
 * shader {@code rendertype_text} del resource pack riconosca la mappa e la "agganci" in un riquadro
 * fisso in alto a destra dello schermo (test dell'assunto: in 26.x il contenuto di una mappa dentro
 * un item frame e' renderizzato da rendertype_text).
 *
 * <p>Header: byte palette 18/4/49 nei pixel (0,0)/(1,0)/(2,0). Questi byte rendono ESATTAMENTE i colori
 * 0xFF0000 / 0x597D27 / 0x3737DC che lo shader cerca (metodo NMinimap, verificato: byte 18 = COLOR_RED
 * shade pieno, byte 4 = GRASS shade scuro, byte 49 = WATER shade). Scritti come BYTE diretti (setPixel,
 * non setPixelColor) per garantire il valore esatto senza arrotondamento della palette. Lo shader
 * scarta la prima riga di pixel, quindi l'header non si vede.
 */
public final class MarkerTestRenderer extends MapRenderer {

    static final byte HDR0 = 18;   // -> 0xFF0000
    static final byte HDR1 = 4;    // -> 0x597D27
    static final byte HDR2 = 49;   // -> 0x3737DC

    public MarkerTestRenderer() {
        super(false);
    }

    @Override
    public void render(MapView view, MapCanvas canvas, Player player) {
        // Ridipinge ad ogni chiamata (nessuna guardia "una volta"): Bukkit apre sessioni di rendering
        // separate per contesti diversi (in mano vs. nel quadro) e una guardia globale ne lascerebbe
        // qualcuna con canvas vuoto (bug gia' osservato). Contenuto statico, costo trascurabile.
        for (int x = 0; x < 128; x++) {
            for (int y = 0; y < 128; y++) {
                canvas.setPixelColor(x, y, java.awt.Color.CYAN);
            }
        }
        canvas.setPixel(0, 0, HDR0);
        canvas.setPixel(1, 0, HDR1);
        canvas.setPixel(2, 0, HDR2);
    }
}
