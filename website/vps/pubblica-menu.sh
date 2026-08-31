#!/bin/bash
# ============================================================
#  Mette in linea la scheda "Menu di gioco" del gestionale
#  (l'editor dei menu di MagixMenus).
#
#    cd ~/deploy-menu && sudo bash pubblica-menu.sh
#
#  Fa due cose in fila:
#   1. aggiorna il ponte magix-console (ci sono i comandi menu-*)
#   2. copia nel sito i file della scheda, con proprietario www-data
#
#  I file arrivano qui dal PC con scp e stanno tutti in questa cartella
#  (nomi appiattiti: sul sito ognuno va al suo posto).
#
#  Rieseguirlo e' sicuro: sovrascrive gli stessi file e non tocca ne' i menu
#  gia' scritti ne' la configurazione delle istanze.
# ============================================================
set -e

if [ "$(id -u)" != "0" ]; then
    echo "Serve sudo:  sudo bash pubblica-menu.sh"
    exit 1
fi

QUI="$(cd "$(dirname "$0")" && pwd)"
SITO=/var/www/magicadventure
PONTE=/usr/local/bin/magix-console

echo "=== 1) ponte con la macchina ==="
if [ -f "$QUI/magix-console" ]; then
    install -o root -g root -m 755 "$QUI/magix-console" "$PONTE"
    # I file del progetto arrivano da Windows e hanno il ritorno a capo in due caratteri:
    # bash non li digerisce e si ferma alla prima funzione. Lo fa anche installa-console.sh,
    # ed e' esattamente il passo che mancava qui la prima volta.
    sed -i 's/\r$//' "$PONTE"
    echo "     $PONTE aggiornato"
    # Una prova a vuoto: se il file ha un errore di sintassi meglio scoprirlo adesso
    # che al primo clic sul sito.
    bash -n "$PONTE" && echo "     sintassi ok"
else
    echo "     manca magix-console: salto (il ponte resta com'era)"
fi

echo
echo "=== 2) file del sito ==="
metti() {  # <file qui> <destinazione nel sito>
    [ -f "$QUI/$1" ] || { echo "     manca $1: salto"; return 0; }
    install -o www-data -g www-data -m 644 "$QUI/$1" "$SITO/$2"
    echo "     $2"
}
metti menu_yaml.php    includes/menu_yaml.php
metti console.php      includes/console.php
metti api-menu.php     public/api/menu.php
metti manage.php       public/manage.php
metti menu-editor.js   public/assets/js/menu-editor.js
metti style.css        public/assets/css/style.css

echo
echo "=== 3) controllo sintassi ==="
php -l "$SITO/public/manage.php" >/dev/null && echo "     manage.php ok"
php -l "$SITO/public/api/menu.php" >/dev/null && echo "     api/menu.php ok"
php -l "$SITO/includes/menu_yaml.php" >/dev/null && echo "     includes/menu_yaml.php ok"
php -l "$SITO/includes/console.php" >/dev/null && echo "     includes/console.php ok"

echo
echo "Fatto: https://magicadventure.it/manage?section=menu"
