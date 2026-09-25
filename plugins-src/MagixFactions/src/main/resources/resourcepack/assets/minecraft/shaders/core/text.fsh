#version 330

// MagixFactions minimap HUD — porting a Paper/Minecraft 26.2 (2026-07-23): vedi la nota gemella in
// text.vsh. Il vanilla "rendertype_text.fsh" e' diventato questo "text.fsh" unico, condiviso da tre
// varianti (mondo/GUI/see-through). La nostra logica (custom==1 minimap, custom==2 mappa-item) esiste
// SOLO nella variante "mondo" (le altre due non hanno neppure `custom`/`uvCoord`/`arrows` dichiarati,
// dato che il vertex shader li dichiara nello stesso #if) — va quindi eseguita PRIMA e dentro lo stesso
// #if, con un default identico al vanilla per le altre due varianti (invariato, solo re-indentato).

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
#moj_import <minecraft:fog.glsl>
#endif

#moj_import <minecraft:dynamictransforms.glsl>

uniform sampler2D Sampler0;

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
in float sphericalVertexDistance;
in float cylindricalVertexDistance;
#endif

in vec4 vertexColor;
in vec2 texCoord0;

out vec4 fragColor;

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
in vec2 uvCoord;
flat in int custom;

// Frecce dei giocatori (decodificate nel vertex shader dall'header). .xy = pixel-mappa, .z = angolo,
// .w = tipo (0 tu / 1 fazione / 2 alleato / 3 nemico) oppure < 0 = slot vuoto. ARROW_MAX combacia col
// vertex shader e con MapService.ARROW_MAX.
#define ARROW_MAX 6
flat in vec4 arrows[ARROW_MAX];

// Cornice della minimap a due tinte (bordo esterno piu' scuro, interno = colore scelto), per restare
// leggibile su qualunque terreno sotto. Colore e spessore sono sostituiti dal plugin dai placeholder
// __BORDER_R/G/B__ e __BORDER_WIDTH__ (config map.minimap.border-color / border-size). Il bordo esterno
// e' lo stesso colore scurito (x0.44) -> effetto in rilievo automatico.
const vec4 BORDER_INNER = vec4(__BORDER_R__, __BORDER_G__, __BORDER_B__, 1.0);
const vec4 BORDER_OUTER = vec4(BORDER_INNER.rgb * 0.44, 1.0);
const float RADIUS = 58.0;                  // raggio del contenuto mappa (in pixel della mappa, centro a 64,64)
const float BORDER_WIDTH = __BORDER_WIDTH__; // spessore della cornice (px), oltre RADIUS

// Le prime 2 righe della map texture contengono i BIT delle frecce (header): vanno sempre nascoste.
// Sulla minimap rotonda cadono gia' fuori dal cerchio; sulla quadrata le nascondiamo esplicitamente
// (indipendentemente dallo spessore cornice) sotto.
const float HEADER_ROWS = 2.0;

// Forma minimap: __SQUARE__ e' sostituito dal plugin (0 = rotonda, 1 = quadrata) da config map.minimap.shape.
#define SQUARE __SQUARE__
// Levigatura smart dei bordi: __SMOOTH__ e' sostituito dal plugin (1 = stile "magix", 0 = "vanilla"
// puro, pixel nudi) da config map.style.
#define SMOOTH __SMOOTH__

// Pannello info (custom==3): colore-CHIAVE dello sfondo trasparente. Lo shader scarta SEMPRE i pixel di
// questo colore (fisso, InfoPanelRenderer.TRANSPARENT_KEY): cosi' "trasparente vs riquadro pieno" e' una
// scelta LIVE del renderer (fondo = chiave -> scartato; fondo = colore vero -> resta), senza riavvio. Un
// colore di riquadro vero non coincide mai col magenta-chiave, quindi il riquadro pieno funziona lo stesso.
// __PANEL_KEY_*__ = RGB EFFETTIVO reso dalla palette dal byte-chiave (non il magenta teorico), sostituito
// dal plugin cosi' il confronto e' esatto.
const vec3 PANEL_KEY = vec3(__PANEL_KEY_R__, __PANEL_KEY_G__, __PANEL_KEY_B__);

// --- Frecce dei giocatori (minimap): SPRITE del cursore-mappa VANILLA -----------------------------
// Copia PIXEL-PER-PIXEL della texture ufficiale del cursore giocatore di Minecraft
// (assets/minecraft/textures/map/decorations/player.png, 8x8 — estratta dal client e ricodificata
// qui, non ricostruita a memoria): contorno nero, corpo con TRE livelli di ombreggiatura
// (255 / 224 / 188, l'effetto "in rilievo" del cursore vero). Stessa scala del nativo (1 px-sprite =
// 1 px-mappa, come gli 8x8 sui 128 dell'item in mano) e rotazione rigida: la forma non cambia MAI,
// a qualsiasi angolo — i cursori nativi veri non si possono agganciare alla minimap HUD (vedi
// MapContentBuilder), quindi lo sprite lo campioniamo qui, identico.
// Ogni freccia arriva gia' decodificata dal vertex shader (posizione pixel-mappa, angolo, tipo).
// Convenzione mappa: la direzione di sguardo e' (-sin(yaw), cos(yaw)) con +x=est, +y=sud.
//
// Sprite 8x8, riga 0 = punta, 3 bit per colonna (LSB = colonna 0): 0=trasparente, 1=contorno NERO,
// 2=corpo pieno (lum 255), 3=corpo medio (224), 4=corpo scuro (188). Layout della texture ufficiale
// (B=nero, X=255, W=224, g=188, .=vuoto):
//   row0: . . . . B . . .
//   row1: . . . B g B . .
//   row2: . . B g W g B .
//   row3: . . B W X W B .
//   row4: . . B W X W B .
//   row5: . . B g W g B .
//   row6: . . . B B B . .
//   row7: . . . . . . . .
const int ARROW_SPRITE[8] = int[8](4096, 49664, 407616, 370240, 370240, 407616, 37376, 0);

// Colore del corpo per tipo. Combacia coi cursori nativi della mappa-item (bianco/verde/blu/rosso),
// cosi' le due mappe restano identiche. L'ombreggiatura dello sprite MOLTIPLICA questo colore (per
// gli altri giocatori la freccia e' la stessa ma tinta, come le varianti colorate dei cursori nativi).
vec3 arrowColor(int t) {
    if (t == 0) return vec3(1.0);               // tu: bianco (come il cursore PLAYER)
    if (t == 1) return vec3(0.33, 0.85, 0.33);  // stessa fazione: verde
    if (t == 2) return vec3(0.36, 0.62, 1.0);   // alleato: blu
    return vec3(1.0, 0.33, 0.33);               // nemico: rosso
}

// --- Upscaling "intelligente" del contenuto minimap (stile Scale2x/cleanEdge) ---------------------
// La mappa e' 128x128 FISSI (limite Minecraft) e viene ingrandita parecchio a schermo: col campionamento
// nearest puro le diagonali (coste, fiumi, confini) mostrano le "scalette" dei pixel. Qui, per ogni
// fragment, guardiamo i vicini del texel: se i due vicini adiacenti all'angolo sono UGUALI tra loro e
// diversi dal texel centrale (= angolo di una scaletta), l'angolo viene TAGLIATO a 45° col colore dei
// vicini -> la scaletta diventa una linea continua liscia. E' la stessa classe di ricostruzione-bordi
// degli upscaler pixel-art (Scale2x/xBR): niente sfocatura (il bilineare "annebbiava", gia' bocciata),
// i colori restano netti, solo i gradini spariscono. Le guardie sugli opposti (regola Scale2x) proteggono
// linee da 1px e pattern del dithering dall'essere "mangiati".
vec3 mapTexel(ivec2 t) {
    return texelFetch(Sampler0, clamp(t, ivec2(0), ivec2(127)), 0).rgb;
}

bool sameCol(vec3 a, vec3 b) {
    return dot(abs(a - b), vec3(1.0)) < 0.004;
}

vec3 smartSample(vec2 uv) {
    ivec2 c = ivec2(clamp(uv, vec2(0.0), vec2(127.999)));
    vec2 f = fract(uv);
    vec3 E = mapTexel(c);
    ivec2 sx = ivec2(f.x < 0.5 ? -1 : 1, 0);            // vicino orizzontale dal lato del quadrante
    ivec2 sy = ivec2(0, f.y < 0.5 ? -1 : 1);            // vicino verticale dal lato del quadrante
    vec3 H  = mapTexel(c + sx);
    vec3 V  = mapTexel(c + sy);
    vec3 Ho = mapTexel(c - sx);                          // opposti: guardie anti-distruzione linee sottili
    vec3 Vo = mapTexel(c - sy);
    vec2 g = vec2(f.x < 0.5 ? f.x : 1.0 - f.x, f.y < 0.5 ? f.y : 1.0 - f.y); // distanza dall'angolo
    if (sameCol(H, V) && !sameCol(H, E) && !sameCol(V, Ho) && !sameCol(H, Vo) && g.x + g.y < 0.5) {
        return H;                                        // taglio a 45°: il gradino diventa diagonale liscia
    }
    return E;
}

// Campiona lo sprite del cursore centrato sul pixel-mappa `center`, ruotato di `angle`, corpo `col`.
vec3 drawArrow(vec3 base, vec2 uv, vec2 center, float angle, vec3 col) {
    vec2 p = uv - center;
    vec2 dir = vec2(-sin(angle), cos(angle));       // direzione di sguardo sulla mappa
    vec2 perp = vec2(dir.y, -dir.x);
    float sx = dot(p, perp) + 4.0;                   // colonna sprite (0..8), centro sprite = posizione
    float sy = dot(p, -dir) + 4.0;                   // riga sprite (0 = punta, verso la direzione di sguardo)
    if (sx < 0.0 || sx >= 8.0 || sy < 0.0 || sy >= 8.0) return base;
    int v = (ARROW_SPRITE[int(sy)] >> (int(sx) * 3)) & 7;
    if (v == 0) return base;                         // trasparente
    if (v == 1) return vec3(0.0);                    // contorno nero
    float lum = v == 2 ? 1.0 : (v == 3 ? 0.8784 : 0.7373); // 255 / 224 / 188 della texture ufficiale
    return col * lum;
}
#endif

void main() {
#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
    // IMPORTANTE (bug "mappa cartacea beige"): gl_FragDepth va scritto in OGNI percorso di esecuzione di
    // QUESTA variante, non solo nel ramo della minimap. In GLSL, se un fragment shader scrive gl_FragDepth
    // in un ramo ma non in un altro, la profondita' dei fragment che NON lo scrivono e' INDEFINITA ->
    // falliscono il depth test in modo imprevedibile. La mappa cartacea in mano (custom==0) veniva percio'
    // resa beige appena il resource pack era attivo. Impostando qui il depth di default (quello
    // dell'hardware) per TUTTI i fragment di questa variante, il ramo minimap puo' poi sovrascriverlo
    // senza rompere il resto.
    gl_FragDepth = gl_FragCoord.z;

    // MagixFactions minimap: se questo e' il quad della minimap (custom==1), ritaglia in CERCHIO o
    // QUADRATO. Il centro (64,64) e' sempre il giocatore (la mappa e' ricentrata su di lui ad ogni
    // refresh). L'header (prime 2 righe) resta fuori dall'area visibile e non si vede.
    if (custom == 1) {
        // Forza il depth al valore piu' VICINO alla camera, cosi' la minimap sta SEMPRE davanti ai blocchi.
        // FIX 26.2 (2026-07-23, causa CONFERMATA del "la minimap si nasconde dietro i blocchi"): da MC 26.2
        // il depth e' REVERSED-Z (verificato nel client jar: DepthStencilState/RenderPipelines usano il test
        // GREATER_THAN_OR_EQUAL, e clip-space ZERO_TO_ONE) -> "vicino" = 1.0, "lontano" = 0.0, e passa il
        // fragment con depth MAGGIORE. Il vecchio gl_FragDepth = 0.0 (vicino sotto la convenzione classica)
        // ora e' il valore piu' LONTANO -> perde contro qualunque blocco -> la minimap ci finiva dietro.
        // 1.0 = il piu' vicino sotto reversed-Z -> vince sempre (>= di tutto) -> HUD sopra ogni cosa.
        gl_FragDepth = 1.0;
#if SQUARE
        // Minimap QUADRATA: mostra tutto il quadrato 128x128, con la cornice sui pixel esterni. Le prime
        // HEADER_ROWS righe (dati frecce) sono sempre nascoste come cornice, anche se border-size e' piccolo.
        // NB: uvCoord va da 0 a 128 (non 127!) attraverso il quad: la distanza dal bordo destro/basso e'
        // 128-uv, non 127-uv — col 127 la cornice usciva 1px PIU' SPESSA a destra e in basso (bug segnalato).
        float edge = min(min(uvCoord.x, 128.0 - uvCoord.x), min(uvCoord.y, 128.0 - uvCoord.y));
        if (uvCoord.y < HEADER_ROWS || edge < BORDER_WIDTH) {
            float t = BORDER_WIDTH > 0.001 ? clamp(edge / BORDER_WIDTH, 0.0, 1.0) : 1.0;
            fragColor = mix(BORDER_OUTER, BORDER_INNER, t);
            return;
        }
#else
        // Minimap ROTONDA: ritaglio circolare + anello-cornice. L'header (angoli/righe alte) resta fuori
        // dal cerchio e viene scartato con discard.
        float dist = length(uvCoord - vec2(64.0));
        if (dist > RADIUS + BORDER_WIDTH) {
            discard;
        }
        if (dist > RADIUS) {
            fragColor = mix(BORDER_INNER, BORDER_OUTER, (dist - RADIUS) / BORDER_WIDTH);
            return;
        }
#endif
        // Campionamento del contenuto minimap: base nearest (niente sfocatura bilineare, gia' bocciata
        // come "annebbiata"); con SMOOTH (stile "magix") si aggiunge la ricostruzione dei bordi stile
        // Scale2x (vedi smartSample): scalette diagonali levigate, colori netti. Con stile "vanilla"
        // i pixel restano nudi. FULLBRIGHT: niente vertexColor (lightmap) ne' fog -> il colore e' gia'
        // quello finale (di notte non si scurisce). Poi le FRECCE dei giocatori (a risoluzione schermo).
#if SMOOTH
        vec3 rgb = smartSample(uvCoord) * ColorModulator.rgb;
#else
        ivec2 tx = clamp(ivec2(uvCoord), ivec2(0), ivec2(127));
        vec3 rgb = texelFetch(Sampler0, tx, 0).rgb * ColorModulator.rgb;
#endif
        // Ordine: gli slot pieni in ordine di array; il PROPRIO marcatore e' l'ULTIMO slot (disegnato per
        // ultimo -> sopra gli altri). Vedi MapContentBuilder.computePlayerArrows.
        for (int i = 0; i < ARROW_MAX; i++) {
            if (arrows[i].w < -0.5) continue;        // slot vuoto
            rgb = drawArrow(rgb, uvCoord, arrows[i].xy, arrows[i].z, arrowColor(int(arrows[i].w)));
        }
        fragColor = vec4(rgb, 1.0);
        return;
    }

    // MAPPA-ITEM Fazioni (custom==2, firma inversa nei pixel): resta dov'e' nel mondo ma viene resa
    // FULLBRIGHT (niente lightmap/fog: leggibile anche a mezzanotte, come se fosse "luminosa") e con lo
    // stesso upscaling smart della minimap -> le due mappe hanno la stessa identica resa. La riga 0
    // (firma) viene nascosta duplicando la riga 1.
    if (custom == 2) {
        vec2 uv = vec2(uvCoord.x, max(uvCoord.y, 1.0));
#if SMOOTH
        vec3 rgb = smartSample(uv) * ColorModulator.rgb;
#else
        ivec2 tx = clamp(ivec2(uv), ivec2(0), ivec2(127));
        vec3 rgb = texelFetch(Sampler0, tx, 0).rgb * ColorModulator.rgb;
#endif
        fragColor = vec4(rgb, 1.0);
        return;
    }

    // PANNELLO INFO (custom==3): striscia sotto la minimap con orologio/coordinate/info. Come la minimap
    // sta SEMPRE davanti a tutto (gl_FragDepth = 1.0, reversed-Z). Testo campionato NEAREST (niente
    // upscaling smart: sul testo distorcerebbe i glifi). Le prime HEADER_ROWS righe portano la firma
    // magica e vanno nascoste: si campiona da riga >= HEADER_ROWS.
    if (custom == 3) {
        gl_FragDepth = 1.0;
        // Le prime HEADER_ROWS righe portano la firma magica: si SCARTANO (discard), non si clampano. Col
        // clamp (max(y,HEADER_ROWS)) le righe-schermo 0..HEADER_ROWS leggevano tutte la stessa riga di
        // texture -> il bordo alto della prima riga di testo veniva duplicato e appariva "stirato".
        if (uvCoord.y < HEADER_ROWS) discard;
        ivec2 tx = clamp(ivec2(uvCoord), ivec2(0), ivec2(127));
        vec3 rgb = texelFetch(Sampler0, tx, 0).rgb;
        // Sfondo trasparente (scelta LIVE del renderer): i pixel del colore-CHIAVE non si disegnano ->
        // resta solo il testo con la sua ombra. Con un riquadro pieno il fondo non e' il colore-chiave e
        // nessun pixel viene scartato.
        if (dot(abs(rgb - PANEL_KEY), vec3(1.0)) < 0.02) discard;
        fragColor = vec4(rgb * ColorModulator.rgb, 1.0);
        return;
    }
#endif

#ifdef IS_GRAYSCALE
    vec4 texColor = texture(Sampler0, texCoord0).rrrr;
#else
    vec4 texColor = texture(Sampler0, texCoord0);
#endif

#ifdef IS_SEE_THROUGH
    vec4 color = texColor * vertexColor;
#else
    vec4 color = texColor * vertexColor * ColorModulator;
#endif
    if (color.a < 0.1) {
        discard;
    }

#ifdef IS_SEE_THROUGH
    fragColor = color * ColorModulator;
#elif defined(IS_GUI)
    fragColor = color;
#else
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
#endif
}
