#version 330

// MagixFactions minimap HUD — porting a Paper/Minecraft 26.2 (2026-07-23): il vanilla "rendertype_text"
// e' stato ACCORPATO da Mojang in QUESTO singolo file "text.vsh/.fsh", condiviso da tre varianti (testo
// del mondo, testo GUI, testo "see-through") selezionate a compile-time dai define IS_GUI/IS_SEE_THROUGH.
// Verificato scaricando ed estraendo lo shader vanilla reale dal client jar 26.2 (piston-meta Mojang):
// su 26.1.2 esisteva un file dedicato "rendertype_text.vsh/.fsh" (mai IS_GUI/IS_SEE_THROUGH, era GIA'
// solo la variante "mondo") — la nostra logica andava quindi bene com'era, senza guardie. Ora la stessa
// variante e' il ramo "!IS_GUI && !IS_SEE_THROUGH" di questo file unico: la nostra aggiunta va percio'
// racchiusa nello stesso #if, altrimenti non compila piu' per le altre due varianti (che non hanno
// sphericalVertexDistance/cylindricalVertexDistance/UV2/Sampler2). Il CONTENUTO di una mappa in un item
// frame passa per la variante "mondo" (verificato su 26.1.2, ipotesi ragionevole che valga ancora qui
// dato che Mojang ha solo RINOMINATO/ACCORPATO i file, non cambiato il render-type concettuale) — DA
// CONFERMARE in-game dall'utente dopo il deploy.

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:sample_lightmap.glsl>
#endif

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

in vec3 Position;
in vec4 Color;
in vec2 UV0;
#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
in ivec2 UV2;
#endif

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
uniform sampler2D Sampler2;
out float sphericalVertexDistance;
out float cylindricalVertexDistance;
#endif

out vec4 vertexColor;
out vec2 texCoord0;

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
// --- MagixFactions minimap HUD (solo variante "mondo", vedi nota sopra) --------------------------
// Frecce dei giocatori sulla minimap (una per giocatore visibile, come i cursori nativi della mappa-
// item). Ogni slot: .xy = posizione in pixel-mappa (0..127), .z = angolo di sguardo (radianti),
// .w = tipo (0 = tu / 1 = fazione / 2 = alleato / 3 = nemico), oppure .w < 0 = slot vuoto. Vengono
// DECODIFICATE qui dall'header (bit impacchettati nelle prime 2 righe della map texture) e DISEGNATE dal
// fragment shader a risoluzione SCHERMO: restano nitide e ruotano fluide a qualsiasi angolo, come i
// cursori nativi — che pero' non si agganciano alla minimap HUD (vedi MapContentBuilder), per questo le
// ridisegniamo noi. ARROW_MAX e il formato dei record DEVONO combaciare con MapService (ARROW_MAX/
// ARROW_BITS/ARROW_ANGLE_STEPS e computePlayerArrows).
uniform sampler2D Sampler0;
out vec2 uvCoord;
flat out int custom;
#define ARROW_MAX 6
flat out vec4 arrows[ARROW_MAX];

// MagixFactions minimap HUD (metodo NMinimap, riscritto). Il CONTENUTO di una mappa dentro un item
// frame e' renderizzato da questo shader (variante "mondo" di text), non da entity/item. La mappa della
// minimap ha un HEADER MAGICO nei primi pixel della riga 0 (byte palette 18/4/49 -> colori 0xFF0000/
// 0x597D27/0x3737DC, verificati): se lo shader lo riconosce, questo non e' testo/mappa nel mondo 3D ma
// la minimap HUD, e la sua posizione a schermo viene sovrascritta con coordinate fisse (angolo alto a
// destra). L'header (prime 2 righe) viene poi nascosto nel fragment per non mostrarlo.
//
// texelFetch (coordinate intere) nel VERTEX shader FUNZIONA per la map texture in questo contesto,
// mentre texture()/textureLod() nell'entity shader restituivano nero (verificato empiricamente).

// Dimensione e margine dell'HUD in NDC (frazione di schermo), compensati per l'aspect ratio nel calcolo.
// La mappa e' 128x128 pixel FISSI (limite Minecraft): piu' e' grande a schermo, piu' ogni pixel viene
// ingrandito e appare "sgranato". Un riquadro piu' piccolo distribuisce i 128px su meno spazio -> piu'
// nitido. Il quad resta quadrato (bounding box): il ritaglio CIRCOLARE avviene nel fragment shader.
// __MAP_SIZE__ e' sostituito a runtime dal plugin col valore di config map.minimap.screen-size
// (frazione di schermo). Se il pack viene usato cosi' com'e' (senza sostituzione), resta 0.22.
const vec2 MAP_SIZE = vec2(__MAP_SIZE__, __MAP_SIZE__);
const vec2 MAP_OFFSET = vec2(0.03, 0.03);

// Colori magici dell'header (RGB impacchettati come 0xRRGGBB), come li legge la GPU dalla map texture.
const int MK0 = 0xFF0000;
const int MK1 = 0x597D27;
const int MK2 = 0x3737DC;

int packColor(vec4 c) {
    ivec3 i = ivec3(round(c.rgb * 255.0));
    return (i.r << 16) + (i.g << 8) + i.b;
}

int idAt(ivec2 uv) {
    return packColor(texelFetch(Sampler0, uv, 0));
}

// Un bit dell'header: pixel = MK0 (0xFF0000) -> 1, qualunque altro colore -> 0. idx e' l'indice LINEARE
// del pixel (riga*128 + colonna) a partire dall'angolo (0,0) della map texture; i record delle frecce
// stanno dopo i 3 pixel magici e possono sconfinare nella riga 1 (percio' idx/128 per la riga).
int bitAt(ivec2 base, int idx) {
    ivec2 off = ivec2(idx % 128, idx / 128);
    return (idAt(base + off) == MK0) ? 1 : 0;
}

// Legge n bit (LSB-first) a partire dall'indice lineare start.
int readBits(ivec2 base, int start, int n) {
    int v = 0;
    for (int b = 0; b < 7; b++) {   // 7 = larghezza massima di un campo (px/py)
        if (b >= n) break;
        v |= bitAt(base, start + b) << b;
    }
    return v;
}
#endif

void main() {
    // Default: comportamento vanilla identico allo stock, per ognuna delle tre varianti.
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);
    vertexColor = Color * sample_lightmap(Sampler2, UV2);
#else
    vertexColor = Color;
#endif
    texCoord0 = UV0;

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
    uvCoord = vec2(0.0);
    custom = 0;
    for (int i = 0; i < ARROW_MAX; i++) arrows[i] = vec4(0.0, 0.0, 0.0, -1.0); // nessuna freccia

    // Riconoscimento minimap: normalizza al pixel (0,0) della mappa e controlla i 3 pixel header.
    vec2 texSize = vec2(textureSize(Sampler0, 0));
    if (texSize == vec2(128.0)) {
        // ANGOLO del quad ricavato da UV0 (round -> 0/1 su ciascun asse), NON piu' da gl_VertexID.
        // FIX 26.2 (2026-07-23, causa VERA del bug "18 minimappe fluttuanti che flashano"): da MC 26.2 la
        // geometria della mappa passa per il "SubmitNodeCollector" BATCHATO (verificato decompilando
        // MapRenderer/RenderPipelines del client 26.2) -> gl_VertexID non e' piu' 0..3 per singolo quad-
        // mappa, ma un indice progressivo nell'INTERO batch. La vecchia formula corners[(gl_VertexID+1)%4]
        // leggeva quindi l'angolo SBAGLIATO: l'header magico non veniva mai riconosciuto, custom restava 0
        // e ogni quadro veniva disegnato NEL MONDO (le 18 ancore della corona = 18 mappe fluttuanti, con il
        // flash del frustum culling). L'angolo in UV e' invece SEMPRE corretto per-vertice: i 4 vertici
        // della mappa hanno UV0 = (0,0)/(0,1)/(1,1)/(1,0). round(clamp(...)) li riporta a 0/1 esatti.
        vec2 cornerUV = round(clamp(UV0, 0.0, 1.0));
        ivec2 uv = ivec2(UV0 * texSize);
        ivec2 mapUV = uv - ivec2(cornerUV * 128.0);
        if (idAt(mapUV + ivec2(0, 0)) == MK0 && idAt(mapUV + ivec2(1, 0)) == MK1 && idAt(mapUV + ivec2(2, 0)) == MK2) {
            // Riquadro in alto a DESTRA, quadrato (nessuna rotazione in questa fase).
            vec2 map = cornerUV * MAP_SIZE;
            map = map + MAP_OFFSET * vec2(-1.0, 1.0) - vec2(MAP_SIZE.x, 0.0);
            // z = 0.0 (piano vicino), NON piu' -0.9999. FIX 26.2 (2026-07-23, causa CONFERMATA del "non si
            // vede piu' NIENTE" dopo che il riconoscimento header ha ripreso a funzionare): da MC 26.2 il
            // client usa glClipControl ZERO_TO_ONE (verificato nel client jar: Projection.isZZeroToOne,
            // GlDevice.glClipControl) -> il clip-space z valido e' [0, w], non piu' [-w, w]. Il vecchio
            // -0.9999 (piano vicino sotto il range [-1,1] di prima) ora CADE FUORI dal range -> l'intero
            // quad HUD viene scartato dal clipping (invisibile). 0.0 e' il piano vicino sotto ZERO_TO_ONE
            // ed e' comunque valido anche sotto il vecchio range; il gl_FragDepth=0.0 nel fragment tiene
            // l'HUD davanti a tutto (nearest in window-space [0,1], indipendente dalla convenzione).
            gl_Position = vec4(vec2(1.0, -ProjMat[1][1] / ProjMat[0][0]) * map + vec2(1.0, 1.0), 0.0, 1.0);
            vertexColor = vec4(1.0);
            uvCoord = cornerUV * 128.0;
            custom = 1;
            sphericalVertexDistance = 0.0;
            cylindricalVertexDistance = 0.0;
            // Frecce: ARROW_MAX record da 22 bit, a partire dal pixel 3 (dopo i 3 magici). Formato di un
            // record (LSB-first): 1 bit valido, 7 px, 7 py, 5 angolo (bucket 0..31 -> bucket/32*2pi), 2
            // tipo. Combacia con MapService.packArrow/renderPaletteWithHeader.
            for (int i = 0; i < ARROW_MAX; i++) {
                int b = 3 + i * 22;
                if (bitAt(mapUV, b) == 0) continue;                 // slot vuoto -> resta .w = -1
                int px  = readBits(mapUV, b + 1, 7);
                int py  = readBits(mapUV, b + 8, 7);
                int ang = readBits(mapUV, b + 15, 5);
                int typ = readBits(mapUV, b + 20, 2);
                arrows[i] = vec4(float(px), float(py), (float(ang) / 32.0) * 6.28318530718, float(typ));
            }
        } else if (idAt(mapUV + ivec2(0, 0)) == MK2 && idAt(mapUV + ivec2(1, 0)) == MK1 && idAt(mapUV + ivec2(2, 0)) == MK0) {
            // Firma INVERSA (49/4/18) = MAPPA-ITEM Fazioni (in mano o in cornice): resta al suo posto nel
            // mondo (nessun riposizionamento HUD), ma il fragment la rende FULLBRIGHT (leggibile di notte)
            // e con l'upscaling smart, identica alla minimap. Scritta da FactionMapRenderer.
            custom = 2;
            uvCoord = cornerUV * 128.0;
        }
    }
#endif
}
