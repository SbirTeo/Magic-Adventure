package com.teolo.magixfactions.resourcepack;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Legge dal jar di MagixFactions, risolve i SUOI segnaposto (dimensione minimap, forma, cornice,
 * levigatura, dimensione/posizione del logo — tutti letti dal SUO config.yml) e restituisce il
 * contenuto pronto da registrare nel resource pack UNICO del server (vedi
 * {@code hook.MagixPackHook} e il plugin MagixPack).
 *
 * <p>Fino alla v0.56.x MagixFactions costruiva e serviva lo zip da solo ({@code ResourcePackService},
 * ora rimossa): da quando un client applica un solo pacchetto alla volta, quella logica di
 * fusione/servizio HTTP e' comune a tutti i plugin che ci mettono qualcosa (anche MagixAuth), quindi
 * e' stata estratta in MagixPack. Qui resta solo la parte che DEVE stare in MagixFactions: sapere
 * quali file suoi includere e come risolvere i SUOI placeholder dal SUO config.
 */
public final class ResourcePackContent {

    /** File da includere, path relativo alla cartella "resourcepack/" nel classpath del jar.
     *  Da Paper/Minecraft 26.2 (2026-07-23) i due file NON si chiamano piu' "rendertype_text.vsh/.fsh":
     *  Mojang li ha accorpati in un unico "text.vsh/.fsh" condiviso da testo-mondo/GUI/see-through
     *  (selezionati da #if IS_GUI/IS_SEE_THROUGH dentro il file) — verificato scaricando il client jar
     *  vanilla reale ed estraendone gli shader core. Vedi la nota in cima a text.vsh/text.fsh. I vecchi
     *  file (validi solo fino a 26.1.2) restano nel sorgente come *.legacy-26.1.2, non zippati. */
    private static final String[] BUNDLED_FILES = {
            "assets/minecraft/shaders/core/text.vsh",
            "assets/minecraft/shaders/core/text.fsh",

            // --- Logo del server nel tablist (font default esteso) ---
            //
            // Il logo e' iniettato come glifo bitmap (carattere PUA ) DENTRO il font
            // default di Minecraft: cosi' funziona anche nelle stringhe "legacy" con &-codes
            // del tablist di CMI, che non possono specificare un font component. Il provider
            // "reference" verso include/default lascia intatti tutti i glifi vanilla.
            // ascent/height sono placeholder sostituiti da config (tablist.logo.*) per poter
            // calibrare posizione e dimensione con un semplice riavvio, senza ricompilare.
            "assets/minecraft/font/default.json",
            "assets/magicadventure/font/logo.json",
            "assets/magicadventure/textures/gui/logo.png",

            // --- Icona di connessione delle caselle finte del tablist (MagixEssentials) ---
            //
            // Le 80 slot fisse (MagixEssentials, tablist.yml -> fixed-slots) sono voci senza un
            // giocatore vero dietro: gli si manda una latenza NEGATIVA, che nel protocollo vuol
            // dire "non ancora nota" — semanticamente e' proprio quello che sono, una connessione
            // che non esiste. Il client la disegna come un'icona ("connessione persa", una X):
            // qui la sostituiamo con una trasparente, cosi' la casella finta resta muta invece di
            // avere un'icona sopra.
            //
            // Il file vero e il suo percorso sono stati VERIFICATI scaricando il client vanilla
            // reale di QUESTA versione (26.1.2, lo stesso metodo di text.vsh/.fsh sopra):
            // assets/minecraft/textures/gui/sprites/icon/ping_unknown.png, 10x8 RGBA. Le altre
            // cinque (ping_1..ping_5, le barre da 1 a 5) NON si toccano: sono quelle che vedono i
            // giocatori VERI, e a differenza della X non c'e' un valore di ping che i giocatori
            // veri non possano mai avere davvero — spegnerle spegnerebbe anche la LORO icona.
            "assets/minecraft/textures/gui/sprites/icon/ping_unknown.png",
    };

    private ResourcePackContent() {}

    /** Costruisce la mappa "percorso nello zip" -> bytes pronta per
     *  {@code hook.MagixPackHook.registerOwnPack}. */
    public static Map<String, byte[]> build(JavaPlugin plugin) throws IOException {
        // Dimensione minimap a schermo (frazione, es. 0.22) da config: sostituita nel vertex shader al
        // posto del placeholder __MAP_SIZE__. Clampata in un intervallo ragionevole per non generare uno
        // shader assurdo (0 = invisibile, valori enormi = riempie lo schermo).
        double size = plugin.getConfig().getDouble("map.minimap.screen-size", 0.22);
        size = Math.max(0.05, Math.min(0.6, size));
        String sizeStr = String.format(java.util.Locale.ROOT, "%.4f", size);
        // Pannello info sotto la minimap: righe di testo (px) e spazio dalla minimap (NDC). Le righe sono
        // dedotte dal NUMERO di righe di testo in config (map.minimap.info-panel.lines), con la stessa
        // formula di InfoPanelRenderer.panelRows -> shader e renderer concordano sull'altezza. Cambiare il
        // NUMERO di righe richiede quindi un riavvio (rigenera il pack); il TESTO delle righe e' live.
        int panelLines = Math.max(1, plugin.getConfig().getStringList("map.minimap.info-panel.lines").size());
        int panelRows = com.teolo.magixfactions.minimap.InfoPanelRenderer.panelRows(panelLines);
        String panelRowsStr = String.format(java.util.Locale.ROOT, "%.1f", (double) panelRows);
        double panelGap = plugin.getConfig().getDouble("map.minimap.info-panel.gap", 0.006);
        panelGap = Math.max(0.0, Math.min(0.2, panelGap));
        String panelGapStr = String.format(java.util.Locale.ROOT, "%.4f", panelGap);
        // Colore-CHIAVE dello sfondo trasparente passato allo shader: l'RGB EFFETTIVO reso dalla palette
        // (non il magenta teorico), cosi' il confronto per il discard e' esatto. E' FISSO (non dipende dal
        // config): "trasparente vs riquadro pieno" e' una scelta live del renderer (vedi text.fsh custom==3).
        java.awt.Color panelKey = com.teolo.magixfactions.minimap.InfoPanelRenderer.transparentKeyRendered();
        String panelKeyR = String.format(java.util.Locale.ROOT, "%.4f", panelKey.getRed() / 255f);
        String panelKeyG = String.format(java.util.Locale.ROOT, "%.4f", panelKey.getGreen() / 255f);
        String panelKeyB = String.format(java.util.Locale.ROOT, "%.4f", panelKey.getBlue() / 255f);
        // Forma minimap: quadrata o rotonda (default rotonda) -> #define SQUARE nel fragment shader.
        boolean square = "square".equalsIgnoreCase(plugin.getConfig().getString("map.minimap.shape", "round"));
        String squareStr = square ? "1" : "0";
        // Cornice minimap: spessore (px) e colore, sostituiti nel fragment shader (BORDER_WIDTH / BORDER_INNER).
        double borderSize = plugin.getConfig().getDouble("map.minimap.border-size", 3.0);
        borderSize = Math.max(0.0, Math.min(10.0, borderSize));
        String borderWidthStr = String.format(java.util.Locale.ROOT, "%.1f", borderSize);
        // Levigatura smart dei bordi (Scale2x): attiva solo con stile "magix" (config map.style);
        // con "vanilla" i pixel restano nudi come sulla mappa vanilla pura.
        boolean smooth = !"vanilla".equalsIgnoreCase(plugin.getConfig().getString("map.style", "magix"));
        String smoothStr = smooth ? "1" : "0";
        float[] bc = parseBorderColor(plugin.getConfig().getString("map.minimap.border-color", "#5A5A5A"));
        String brStr = String.format(java.util.Locale.ROOT, "%.4f", bc[0]);
        String bgStr = String.format(java.util.Locale.ROOT, "%.4f", bc[1]);
        String bbStr = String.format(java.util.Locale.ROOT, "%.4f", bc[2]);

        // Logo nel tablist: altezza del glifo (px renderizzati) e "ascent" (quanto la texture sale
        // sopra la linea di base). Clampati in un intervallo sano cosi' un valore sbagliato nel config
        // non genera un font.json rifiutato dal client. Vedi assets/minecraft/font/default.json.
        int logoHeight = plugin.getConfig().getInt("tablist.logo.height", 40);
        logoHeight = Math.max(8, Math.min(256, logoHeight));
        int logoAscent = plugin.getConfig().getInt("tablist.logo.ascent", 22);
        // Regola di Minecraft: ascent non puo' superare height, altrimenti il pacchetto e' invalido.
        logoAscent = Math.max(-128, Math.min(logoHeight, logoAscent));
        String logoHeightStr = String.valueOf(logoHeight);
        String logoAscentStr = String.valueOf(logoAscent);

        Map<String, byte[]> out = new LinkedHashMap<>();
        for (String path : BUNDLED_FILES) {
            try (InputStream in = ResourcePackContent.class.getClassLoader()
                    .getResourceAsStream("resourcepack/" + path)) {
                if (in == null) throw new IOException("Risorsa mancante nel jar: resourcepack/" + path);
                byte[] data = in.readAllBytes();
                if (path.endsWith(".vsh")) { // il vertex shader ha i placeholder di minimap e pannello info
                    data = new String(data, java.nio.charset.StandardCharsets.UTF_8)
                            .replace("__MAP_SIZE__", sizeStr)
                            .replace("__PANEL_ROWS__", panelRowsStr)
                            .replace("__PANEL_GAP__", panelGapStr)
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                } else if (path.endsWith(".fsh")) { // il fragment shader ha i placeholder di forma, cornice e pannello
                    data = new String(data, java.nio.charset.StandardCharsets.UTF_8)
                            .replace("__SQUARE__", squareStr)
                            .replace("__SMOOTH__", smoothStr)
                            .replace("__BORDER_WIDTH__", borderWidthStr)
                            .replace("__BORDER_R__", brStr)
                            .replace("__BORDER_G__", bgStr)
                            .replace("__BORDER_B__", bbStr)
                            .replace("__PANEL_KEY_R__", panelKeyR)
                            .replace("__PANEL_KEY_G__", panelKeyG)
                            .replace("__PANEL_KEY_B__", panelKeyB)
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                } else if (path.endsWith("font/default.json")) { // il font del logo ha i placeholder di dimensione/posizione
                    data = new String(data, java.nio.charset.StandardCharsets.UTF_8)
                            .replace("__LOGO_ASCENT__", logoAscentStr)
                            .replace("__LOGO_HEIGHT__", logoHeightStr)
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                }
                out.put(path, data);
            }
        }
        return out;
    }

    /** Colore cornice minimap -> {r,g,b} normalizzati 0..1 per lo shader. Accetta "#RRGGBB"/"RRGGBB" oppure
     *  un codice colore Minecraft "&X" (i 16 classici). Fallback: grigio (90,90,90) come il default storico. */
    private static float[] parseBorderColor(String s) {
        int r = 90, g = 90, b = 90;
        if (s != null && !s.isBlank()) {
            String t = s.trim();
            if (t.startsWith("#")) t = t.substring(1);
            if (t.length() == 6 && t.chars().allMatch(c -> Character.digit(c, 16) >= 0)) {
                r = Integer.parseInt(t.substring(0, 2), 16);
                g = Integer.parseInt(t.substring(2, 4), 16);
                b = Integer.parseInt(t.substring(4, 6), 16);
            } else if (t.length() >= 2 && (t.charAt(0) == '&' || t.charAt(0) == '§')) {
                int[] c = mcCodeColor(Character.toLowerCase(t.charAt(1)));
                r = c[0]; g = c[1]; b = c[2];
            }
        }
        return new float[]{ r / 255f, g / 255f, b / 255f };
    }

    private static int[] mcCodeColor(char c) {
        return switch (c) {
            case '0' -> new int[]{0, 0, 0};        case '1' -> new int[]{0, 0, 170};
            case '2' -> new int[]{0, 170, 0};      case '3' -> new int[]{0, 170, 170};
            case '4' -> new int[]{170, 0, 0};      case '5' -> new int[]{170, 0, 170};
            case '6' -> new int[]{255, 170, 0};    case '7' -> new int[]{170, 170, 170};
            case '8' -> new int[]{85, 85, 85};     case '9' -> new int[]{85, 85, 255};
            case 'a' -> new int[]{85, 255, 85};    case 'b' -> new int[]{85, 255, 255};
            case 'c' -> new int[]{255, 85, 85};    case 'd' -> new int[]{255, 85, 255};
            case 'e' -> new int[]{255, 255, 85};   case 'f' -> new int[]{255, 255, 255};
            default  -> new int[]{90, 90, 90};
        };
    }
}
