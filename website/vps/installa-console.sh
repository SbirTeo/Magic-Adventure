#!/bin/bash
# ============================================================
#  Installa il ponte "console del server" del gestionale.
#
#    sudo bash installa-console.sh
#
#  Da eseguire nella cartella dove sta anche console/ (i due file
#  magix-console e istanze.conf viaggiano insieme a questo script).
#
#  Cosa mette in piedi:
#   1. /usr/local/bin/magix-console  - l'unico programma che il sito puo' lanciare
#   2. /etc/magicadventure/istanze.conf - elenco dei server governabili (non si sovrascrive)
#   3. /etc/sudoers.d/60-magix-console  - permesso a www-data di lanciare SOLO quel programma
#   4. /run/magix-console + registro in /var/log/magix-console.log (con rotazione)
#
#  Rieseguirlo e' sicuro: aggiorna il programma e lascia stare la configurazione.
# ============================================================
set -e

if [ "$(id -u)" != "0" ]; then
    echo "Serve sudo:  sudo bash installa-console.sh"
    exit 1
fi

QUI="$(cd "$(dirname "$0")" && pwd)"
SORGENTE="$QUI/console"

if [ ! -f "$SORGENTE/magix-console" ]; then
    echo "[ERRORE] non trovo $SORGENTE/magix-console (copia sul VPS anche la cartella console/)"
    exit 1
fi

echo "1/5  programma /usr/local/bin/magix-console"
install -o root -g root -m 755 "$SORGENTE/magix-console" /usr/local/bin/magix-console
# Se e' arrivato da Windows puo' avere i fine riga CRLF: bash non lo eseguirebbe.
sed -i 's/\r$//' /usr/local/bin/magix-console

echo "2/5  configurazione /etc/magicadventure/istanze.conf"
mkdir -p /etc/magicadventure
if [ -f /etc/magicadventure/istanze.conf ]; then
    echo "     esiste gia': lasciata com'e' (l'esempio aggiornato e' in istanze.conf.nuovo)"
    install -o root -g root -m 644 "$SORGENTE/istanze.conf" /etc/magicadventure/istanze.conf.nuovo
else
    install -o root -g root -m 644 "$SORGENTE/istanze.conf" /etc/magicadventure/istanze.conf
fi

echo "3/5  permesso sudo per www-data"
cat > /etc/sudoers.d/60-magix-console <<'FINE'
# Il sito (PHP gira come www-data) puo' lanciare SOLO questo programma, senza password.
# Tutti i controlli veri stanno dentro magix-console: le istanze governabili sono
# quelle di /etc/magicadventure/istanze.conf e nient'altro.
# (niente "Defaults !requiretty": il sudo di questa Ubuntu non conosce quel
# comando e rifiuterebbe l'intero file. Senza tty funziona lo stesso.)
www-data ALL=(root) NOPASSWD: /usr/local/bin/magix-console
FINE
chmod 440 /etc/sudoers.d/60-magix-console
visudo -c -q -f /etc/sudoers.d/60-magix-console || {
    echo "[ERRORE] regola sudo non valida: la rimuovo"
    rm -f /etc/sudoers.d/60-magix-console
    exit 1
}

echo "4/5  cartella di stato e registro"
mkdir -p /run/magix-console
chmod 755 /run/magix-console
# /run si svuota a ogni riavvio: cosi' la cartella torna da sola
cat > /etc/tmpfiles.d/magix-console.conf <<'FINE'
d /run/magix-console 0755 root root -
FINE
touch /var/log/magix-console.log
chmod 640 /var/log/magix-console.log
cat > /etc/logrotate.d/magix-console <<'FINE'
/var/log/magix-console.log {
    weekly
    rotate 8
    compress
    missingok
    notifempty
    create 640 root root
}
FINE

echo "5/5  prova (come www-data, esattamente come fa il sito)"
# Se la prova non riesce l'installazione resta buona lo stesso: si segnala e basta,
# invece di far fallire tutto lo script.
if ! sudo -u www-data sudo -n /usr/local/bin/magix-console lista; then
    echo "[AVVISO] la prova come www-data non e' riuscita: controlla /etc/sudoers.d/60-magix-console"
fi

echo
echo "Fatto. Il gestionale ha la scheda \"Server\" in /manage?section=console"
