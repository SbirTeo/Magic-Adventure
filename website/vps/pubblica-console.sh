#!/bin/bash
# ============================================================
#  Mette in linea la scheda "Server" del gestionale.
#
#    cd ~/deploy-console && sudo bash pubblica-console.sh
#
#  Fa due cose in fila:
#   1. installa il ponte con la macchina (vedi installa-console.sh)
#   2. copia nel sito i file della scheda, con proprietario www-data
#
#  I file arrivano qui dal PC con scp e stanno tutti in questa cartella
#  (nomi appiattiti: sul sito ognuno va al suo posto).
# ============================================================
set -e

if [ "$(id -u)" != "0" ]; then
    echo "Serve sudo:  sudo bash pubblica-console.sh"
    exit 1
fi

QUI="$(cd "$(dirname "$0")" && pwd)"
SITO=/var/www/magicadventure

echo "=== 1) ponte con la macchina ==="
bash "$QUI/installa-console.sh"

echo
echo "=== 2) file del sito ==="
metti() {  # <file qui> <destinazione nel sito>
    [ -f "$QUI/$1" ] || { echo "     manca $1: salto"; return 0; }
    install -o www-data -g www-data -m 644 "$QUI/$1" "$SITO/$2"
    echo "     $2"
}
metti console.php            includes/console.php
metti api-console.php        public/api/console.php
metti manage.php             public/manage.php
metti console-server.js      public/assets/js/console-server.js
metti style.css              public/assets/css/style.css

echo
echo "=== 3) controllo sintassi ==="
php -l "$SITO/public/manage.php" >/dev/null && echo "     manage.php ok"
php -l "$SITO/public/api/console.php" >/dev/null && echo "     api/console.php ok"
php -l "$SITO/includes/console.php" >/dev/null && echo "     includes/console.php ok"

echo
echo "Fatto: https://magicadventure.it/manage?section=console"
