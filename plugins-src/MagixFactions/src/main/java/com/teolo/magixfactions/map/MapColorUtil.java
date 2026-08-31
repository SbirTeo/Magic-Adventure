package com.teolo.magixfactions.map;

import org.bukkit.map.MapPalette;

import java.awt.Color;

/**
 * Conversione RGB -> colore-mappa (byte della palette) VELOCE + <b>dithering</b> Floyd-Steinberg.
 *
 * <p>La palette delle mappe di Minecraft ha pochissime tinte (circa 4 luminosita' per ogni colore base):
 * un terreno con gradienti morbidi diventa cosi' "a bande" nette. Il dithering distribuisce l'ERRORE di
 * quantizzazione sui pixel vicini, per cui l'occhio percepisce sfumature intermedie che la palette non ha
 * davvero — la mappa appare molto piu' ricca e definita (stessa tecnica del color-engine di Cartographer2,
 * ispirato a JetpImageUtil di Jetp250).
 *
 * <p>Per essere veloce (il dithering chiama la ricerca del colore piu' vicino per OGNI pixel, e con la
 * diffusione dell'errore genera moltissimi RGB distinti) si precalcola una <b>tabella</b> {@link #LOOKUP}
 * che mappa direttamente un RGB ridotto ({@value #BITS} bit per canale) al byte-palette piu' vicino: la
 * ricerca diventa un singolo accesso ad array invece di scorrere tutta la palette. La tabella si costruisce
 * una sola volta all'avvio ({@link #init()}), usando {@link MapPalette#matchColor(int, int, int)} come
 * riferimento (cosi' i colori scelti restano identici a quelli che sceglierebbe Bukkit).
 */
final class MapColorUtil {

    private MapColorUtil() {}

    private static final int BITS = 6;                 // bit per canale nella tabella (64 livelli/canale)
    private static final int SHIFT = 8 - BITS;         // scarto dei bit bassi del canale (2)
    private static final int LEVELS = 1 << BITS;       // 64
    private static final byte[] LOOKUP = new byte[LEVELS * LEVELS * LEVELS]; // 262144 byte
    private static final int[] PALETTE_RGB = new int[256]; // RGB reale di ogni byte-palette (per l'errore)
    private static volatile boolean built = false;

    /** Precalcola la tabella colore. Idempotente; da chiamare una volta all'avvio (costo una-tantum). */
    static synchronized void init() {
        if (built) return;
        for (int v = 0; v < 256; v++) {
            int rgb = 0;
            try {
                Color c = MapPalette.getColor((byte) v);
                rgb = (c.getRed() << 16) | (c.getGreen() << 8) | c.getBlue();
            } catch (Throwable ignored) { /* indice non valido/trasparente: resta 0 */ }
            PALETTE_RGB[v] = rgb;
        }
        for (int r = 0; r < LEVELS; r++) {
            for (int g = 0; g < LEVELS; g++) {
                for (int b = 0; b < LEVELS; b++) {
                    // centro del "secchiello" di quantizzazione, per un match piu' fedele
                    int cr = (r << SHIFT) | (1 << (SHIFT - 1));
                    int cg = (g << SHIFT) | (1 << (SHIFT - 1));
                    int cb = (b << SHIFT) | (1 << (SHIFT - 1));
                    LOOKUP[(r << (BITS * 2)) | (g << BITS) | b] = MapPalette.matchColor(cr, cg, cb);
                }
            }
        }
        built = true;
    }

    static boolean isReady() { return built; }

    /** Byte-palette piu' vicino a (r,g,b) via tabella precalcolata (nessuno scorrimento della palette). */
    static byte nearest(int r, int g, int b) {
        return LOOKUP[((r >> SHIFT) << (BITS * 2)) | ((g >> SHIFT) << BITS) | (b >> SHIFT)];
    }

    /**
     * Applica il dithering Floyd-Steinberg al frame 128x128 ({@code pixels[x][y]}, x=colonna, y=riga) e
     * ritorna i byte-palette, indicizzati {@code y*128 + x} (stesso ordine usato dalla minimap e leggibile
     * pixel-per-pixel per {@code MapCanvas.setPixel}). Non modifica {@code pixels}.
     *
     * <p>{@code protect} (opzionale): pixel da NON ditherare — resi col colore-mappa esatto e senza
     * diffondere errore ai vicini. Serve per avatar/nomi/territori/punti cardinali: su di essi il dithering
     * fa solo "rumore" e li rende irriconoscibili, mentre il beneficio (sfumare i gradienti) e' solo sul
     * terreno. Cosi' il terreno resta ricco ma marcatori e overlay restano netti.
     */
    static byte[] dither(Color[][] pixels, boolean[][] protect) {
        if (!built) init();
        final int W = 128, H = 128;
        // Copie di lavoro in float: l'errore di quantizzazione va accumulato con precisione sub-intera.
        float[] rc = new float[W * H], gc = new float[W * H], bc = new float[W * H];
        for (int x = 0; x < W; x++) {
            for (int y = 0; y < H; y++) {
                Color c = pixels[x][y];
                int i = y * W + x;
                rc[i] = c.getRed(); gc[i] = c.getGreen(); bc[i] = c.getBlue();
            }
        }
        byte[] out = new byte[W * H];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int i = y * W + x;
                if (protect != null && protect[x][y]) {
                    // Pixel protetto: colore esatto sul valore ORIGINALE, nessuna diffusione dell'errore.
                    Color c = pixels[x][y];
                    out[i] = nearest(c.getRed(), c.getGreen(), c.getBlue());
                    continue;
                }
                int r = clamp255(Math.round(rc[i]));
                int g = clamp255(Math.round(gc[i]));
                int b = clamp255(Math.round(bc[i]));
                byte best = nearest(r, g, b);
                out[i] = best;
                int prgb = PALETTE_RGB[best & 0xFF];
                float er = r - ((prgb >> 16) & 0xFF);
                float eg = g - ((prgb >> 8) & 0xFF);
                float eb = b - (prgb & 0xFF);
                // Diffusione Floyd-Steinberg: 7/16 destra, 3/16 giu'-sx, 5/16 giu', 1/16 giu'-dx.
                if (x + 1 < W)      { int j = i + 1;     rc[j]+=er*0.4375f; gc[j]+=eg*0.4375f; bc[j]+=eb*0.4375f; }
                if (y + 1 < H) {
                    if (x - 1 >= 0) { int j = i + W - 1; rc[j]+=er*0.1875f; gc[j]+=eg*0.1875f; bc[j]+=eb*0.1875f; }
                    { int j = i + W;                     rc[j]+=er*0.3125f; gc[j]+=eg*0.3125f; bc[j]+=eb*0.3125f; }
                    if (x + 1 < W)  { int j = i + W + 1; rc[j]+=er*0.0625f; gc[j]+=eg*0.0625f; bc[j]+=eb*0.0625f; }
                }
            }
        }
        return out;
    }

    private static int clamp255(int v) { return v < 0 ? 0 : (v > 255 ? 255 : v); }
}
