package com.teolo.magixpack.avatar;

import com.teolo.magixpack.glyph.GlyphCatalog;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Un carattere VERO (non una sequenza di caratteri + codici colore) per la faccia di UNA skin,
 * assegnato al volo la prima volta che serve — come fa Oraxen per le teste custom: la faccia
 * intera diventa UNA texture, mappata su UN solo punto di codice del font, esattamente come
 * un'icona normale di {@code glyphs.yml} (che infatti funziona gia' incollata in un vero
 * messaggio di chat: nessun codice colore da far filtrare dalla chat firmata, solo un carattere).
 *
 * <p>Perche' serve un secondo sistema oltre a {@link AvatarService#render}/{@code legacy}: quello
 * disegna la faccia con 8 caratteri "riga" ripetuti e colorati via codice §, un trucco che
 * funziona in un {@link net.kyori.adventure.text.Component} costruito dal plugin (tablist,
 * scoreboard, l'anteprima di un comando) ma MAI in un messaggio che un giocatore scrive/incolla
 * lui stesso: dalla chat firmata (1.19+) il testo digitato arriva come stringa semplice, e i
 * codici § al suo interno restano testo letterale invece di diventare colore (verificato in gioco:
 * si vede scritto "§x§1§6..." invece del colore). Un carattere solo, senza nessun codice di
 * formattazione, non ha questo problema.
 *
 * <p>Skin identiche riusano lo stesso carattere (il limite e' sulle skin DIVERSE viste in
 * quest'avvio, non sui giocatori); i punti di codice usati qui non si toccano mai a un reload di
 * {@code glyphs.yml} — sono un'area separata da quella di {@link GlyphCatalog}, apposta perche' un
 * ricalcolo delle icone statiche non deve mai spostare un carattere gia' in mano a un giocatore
 * (magari gia' incollato in un messaggio pronto da mandare).
 */
public final class AvatarGlyphRegistry {

    private static final int FIRST_CODEPOINT = 0xF000;
    // Fino a 0xF8EF, non 0xF8FF: gli ultimi 16 punti di codice della zona privata (0xF8F0-0xF8FF)
    // sono riservati ai caratteri "shift" fissi di GlyphCatalog — vedi la sua Javadoc.
    private static final int LAST_CODEPOINT = 0xF8EF;

    private final Map<String, Character> byFace = new LinkedHashMap<>();
    private final Map<Character, byte[]> textures = new LinkedHashMap<>();
    private int next = FIRST_CODEPOINT;

    /** true se {@code face} non ha ancora un carattere assegnato: chi chiama {@link #glyphFor}
     *  deve ricostruire e rimandare il pacchetto SOLO quando questo era true prima di chiamarlo. */
    public synchronized boolean isNew(int[][] face) {
        return !byFace.containsKey(key(face));
    }

    /** Il carattere per questa faccia esatta: lo stesso di sempre se gia' vista, uno nuovo
     *  altrimenti. {@code 0} se i punti di codice disponibili sono finiti (migliaia di skin
     *  diverse in un avvio: non dovrebbe succedere mai davvero). */
    public synchronized char glyphFor(int[][] face) {
        String k = key(face);
        Character existing = byFace.get(k);
        if (existing != null) return existing;
        if (next > LAST_CODEPOINT) return 0;
        char cp = (char) next++;
        byFace.put(k, cp);
        textures.put(cp, png(face));
        return cp;
    }

    /** Le texture da registrare nel pacchetto, una per faccia vista finora. */
    public synchronized Map<String, byte[]> packFiles(String namespace) {
        Map<String, byte[]> out = new LinkedHashMap<>();
        for (Map.Entry<Character, byte[]> t : textures.entrySet()) {
            out.put("assets/" + namespace + "/textures/font/avatar_char/" + fileName(t.getKey()) + ".png", t.getValue());
        }
        return out;
    }

    /** I provider bitmap (un carattere ciascuno, alti e con l'ascesa di una lettera normale) da
     *  aggiungere a quelli di {@link GlyphCatalog#packFiles()}, che li fonde nel proprio
     *  {@code default.json} — un secondo file allo stesso percorso lo scarterebbe il client. */
    public synchronized List<String> providers(String namespace) {
        List<String> out = new ArrayList<>();
        for (char cp : textures.keySet()) {
            out.add("{\"type\":\"bitmap\",\"file\":\"" + namespace + ":font/avatar_char/" + fileName(cp)
                    + ".png\",\"height\":8,\"ascent\":7,\"chars\":[\"" + GlyphCatalog.esc(cp) + "\"]}");
        }
        return out;
    }

    private static String fileName(char codepoint) {
        return Integer.toHexString(codepoint);
    }

    /** Chiave esatta della faccia (64 pixel ARGB): un confronto per uguaglianza, non un hash che
     *  potrebbe collidere fra due facce diverse. */
    private static String key(int[][] face) {
        StringBuilder sb = new StringBuilder(face.length * face[0].length * 8);
        for (int[] row : face) for (int px : row) sb.append(Integer.toHexString(px)).append(',');
        return sb.toString();
    }

    private static byte[] png(int[][] face) {
        int h = face.length, w = face[0].length;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) img.setRGB(x, y, face[y][x]);
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            ImageIO.write(img, "png", bos);
            return bos.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
