<?php
/**
 * Alleggerimento delle immagini caricate dal gestionale.
 *
 * Le copertine arrivano dal telefono o da un generatore di immagini e pesano tranquillamente
 * due mega l'una: aperte sulla home sono due mega che ogni visitatore si scarica, ogni volta,
 * per vedere una tessera larga 600 pixel. Qui si fanno due cose, subito dopo il caricamento:
 *
 *  1. si rimpicciolisce l'originale se e' piu' grande del necessario (LATO_MASSIMO);
 *  2. si affianca una copia in WebP, con lo stesso nome piu' ".webp" — la stessa immagine
 *     pesa un quinto. E' nginx a scegliere quale servire: se il browser dice di capire il
 *     WebP riceve quella, se no riceve l'originale. Nessun indirizzo cambia, quindi il
 *     database, le pagine e i link condivisi restano quelli.
 *
 * Se qualcosa non va (formato strano, GD senza WebP, poca memoria) non succede niente di
 * grave: resta l'immagine originale, che e' esattamente la situazione di prima.
 */

/** Oltre questo lato lungo l'immagine viene rimpicciolita: nessuna tessera del sito e' cosi' grande. */
const IMG_LATO_MASSIMO = 1600;

/** Qualita' del WebP: 82 e' il punto in cui si smette di vedere la differenza. */
const IMG_QUALITA_WEBP = 82;

/**
 * Ridimensiona (se serve) e genera la copia WebP accanto al file.
 *
 * @return array{ridotta:bool, webp:bool} cos'e' riuscito a fare
 */
function immagine_ottimizza(string $percorso): array
{
    $esito = ['ridotta' => false, 'webp' => false];
    if (!is_file($percorso) || !extension_loaded('gd')) {
        return $esito;
    }

    $info = @getimagesize($percorso);
    if (!$info) return $esito;
    [$larghezza, $altezza, $tipo] = $info;

    $img = match ($tipo) {
        IMAGETYPE_JPEG => @imagecreatefromjpeg($percorso),
        IMAGETYPE_PNG  => @imagecreatefrompng($percorso),
        IMAGETYPE_WEBP => @imagecreatefromwebp($percorso),
        // Le GIF si lasciano stare: possono essere animate, e ricomprimerle
        // significherebbe buttare via tutti i fotogrammi tranne il primo.
        default        => false,
    };
    if (!$img) return $esito;

    try {
        $lato = max($larghezza, $altezza);
        if ($lato > IMG_LATO_MASSIMO) {
            $scala = IMG_LATO_MASSIMO / $lato;
            $ridotta = imagescale($img, (int) round($larghezza * $scala), (int) round($altezza * $scala));
            if ($ridotta) {
                imagedestroy($img);
                $img = $ridotta;
                // Si riscrive l'originale nel suo formato: chi non capisce il WebP
                // deve comunque ricevere la versione leggera, non quella da due mega.
                $salvata = match ($tipo) {
                    IMAGETYPE_JPEG => @imagejpeg($img, $percorso, 85),
                    IMAGETYPE_PNG  => immagine_salva_png($img, $percorso),
                    IMAGETYPE_WEBP => @imagewebp($img, $percorso, IMG_QUALITA_WEBP),
                    default        => false,
                };
                $esito['ridotta'] = (bool) $salvata;
            }
        }

        if (function_exists('imagewebp') && $tipo !== IMAGETYPE_WEBP) {
            imagepalettetotruecolor($img);
            imagealphablending($img, false);
            imagesavealpha($img, true);
            $esito['webp'] = (bool) @imagewebp($img, $percorso . '.webp', IMG_QUALITA_WEBP);
            if ($esito['webp']) @chmod($percorso . '.webp', 0644);
        }
    } finally {
        imagedestroy($img);
    }

    return $esito;
}

/** PNG con la trasparenza intatta (senza imagesavealpha il fondo diventa nero). */
function immagine_salva_png(\GdImage $img, string $percorso): bool
{
    imagealphablending($img, false);
    imagesavealpha($img, true);
    return @imagepng($img, $percorso, 8);
}

/**
 * Misure vere di un'immagine del sito, per poterle scrivere negli attributi width/height:
 * il browser riserva il posto giusto prima ancora di scaricarla e la pagina non "salta"
 * mentre carica (e' uno dei tre voti di Google sull'esperienza d'uso).
 *
 * Prende solo gli indirizzi interni: di un'immagine ospitata altrove non sappiamo nulla.
 *
 * @return array{0:int,1:int}|null larghezza e altezza, oppure null se non si sa
 */
function immagine_misure(string $url): ?array
{
    if ($url === '' || !str_starts_with($url, '/') || str_starts_with($url, '//')) {
        return null;
    }
    $percorso = __DIR__ . '/../public' . parse_url($url, PHP_URL_PATH);
    if (!is_file($percorso)) return null;

    // Una lettura per pagina e per file: getimagesize legge solo l'intestazione, ma la
    // stessa immagine puo' comparire piu' volte nella stessa pagina.
    static $viste = [];
    if (!array_key_exists($percorso, $viste)) {
        $info = @getimagesize($percorso);
        $viste[$percorso] = $info ? [(int) $info[0], (int) $info[1]] : null;
    }
    return $viste[$percorso];
}
