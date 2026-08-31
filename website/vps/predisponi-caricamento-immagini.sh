#!/bin/bash
# Prepara il VPS ad accettare le immagini caricate dal gestionale.
#
#   sudo bash predisponi-caricamento-immagini.sh
#
# Fa tre cose:
#  1. crea la cartella delle immagini caricate, di proprieta' di www-data (e' PHP a scriverci);
#  2. alza i limiti di PHP e nginx: di serie nginx accetta 1 MB e PHP 2 MB, troppo poco per
#     una copertina;
#  3. spegne l'esecuzione di PHP dentro /assets/: anche se il nome dei file lo decidiamo noi,
#     una cartella scrivibile dal web non deve poter eseguire codice. Difesa in piu'.
set -e

CARTELLA=/var/www/magicadventure/public/assets/img/caricate

echo "1/3  cartella $CARTELLA"
mkdir -p "$CARTELLA"
chown www-data:www-data "$CARTELLA"
chmod 755 "$CARTELLA"

echo "2/3  limiti di PHP"
INI=$(ls -d /etc/php/*/fpm/conf.d | head -n1)
cat > "$INI/99-magicadventure-upload.ini" <<'FINE'
; Immagini caricate dal gestionale (il codice ne rifiuta comunque oltre 5 MB)
upload_max_filesize = 6M
post_max_size = 8M
FINE

echo "3/3  nginx"
systemctl reload "php$(ls /etc/php | head -n1)-fpm" 2>/dev/null || systemctl reload php8.5-fpm
nginx -t
systemctl reload nginx

echo "--- verifica ---"
php -r 'echo "cli: ", ini_get("upload_max_filesize"), "\n";'
ls -ld "$CARTELLA"
