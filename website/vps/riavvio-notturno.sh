#!/bin/bash
# Riavvio automatico del server Minecraft ogni notte alle 03:00 ORA ITALIANA, con
# preavviso ai giocatori (5 minuti, 1 minuto, 30 secondi, poi conto alla rovescia 10..1
# con titolo a schermo).
#
# Il VPS gira in UTC (verificato via diagnostica-vps), quindi "le 3 del mattino" non e'
# un orario fisso in UTC: cambia di un'ora tra ora solare e legale. Anziche' calcolare a
# mano il turno legale/solare nel crontab, il crontab lancia questo script OGNI MINUTO
# (installato/aggiornato da .github/workflows/deploy-riavvio-notturno.yml) e lo script
# stesso, ragionando SEMPRE in fuso Europe/Rome (TZ sotto), esce subito a vuoto finche'
# non e' esattamente 02:55 ora italiana: e' quel controllo interno, non il crontab, a
# scattare sempre alla stessa ora italiana tutto l'anno (Europe/Rome gestisce da solo il
# cambio ora legale/solare). Il costo di un controllo a vuoto ogni minuto e' trascurabile.
#
# Da qui in poi (trovato il minuto giusto) il tempo che manca alle 03:00 si ricalcola ogni
# volta dall'orologio (aspetta_fino_a), invece di sommare sleep fissi in sequenza: cosi'
# non accumula ritardo se l'avvio ha anche solo un secondo di scarto.
#
# Il riavvio VERO (che rimette su il server) deve passare da qui, non da un /stop dentro
# al gioco: plugins/CMI/Settings/Schedules.yml puo' mandare solo comandi di Minecraft, e
# systemd (magicadventure.service) ha Restart=no - un /stop lascerebbe il server giu' finche'
# non lo riavvia qualcuno a mano. Il comando di restart usa la stessa regola sudoers gia'
# in uso per l'auto-deploy dei plugin (systemctl restart magicadventure.service, NOPASSWD).
set -uo pipefail
export TZ=Europe/Rome

SCREEN_SESSION=mc
BASE=/home/ubuntu/magicadventure
LOG="$BASE/logs/riavvio-notturno.log"
LOCKFILE=/tmp/riavvio-notturno.lock

# Il crontab lancia lo script ogni minuto: esce subito se non e' il minuto giusto (ora
# italiana). Il lockfile e' una sicurezza in piu' contro un doppio avvio nello stesso
# minuto (es. un'esecuzione precedente ancora in corso per qualche motivo).
[ "$(date +%H:%M)" = "02:55" ] || exit 0
if [ -e "$LOCKFILE" ]; then
  echo "$(date '+%F %T') gia' in corso (lockfile presente), esco" >> "$LOG"
  exit 0
fi
touch "$LOCKFILE"
trap 'rm -f "$LOCKFILE"' EXIT

manda() {
  # $1 = comando da eseguire in game (senza lo slash iniziale, come in console)
  if screen -list 2>/dev/null | grep -qE '[0-9]+\.'"$SCREEN_SESSION"'[[:space:]]'; then
    screen -S "$SCREEN_SESSION" -p 0 -X stuff "$(printf '%s\r' "$1")"
  else
    echo "$(date '+%F %T') screen $SCREEN_SESSION non attivo, comando saltato: $1" >> "$LOG"
  fi
}

aspetta_fino_a() {
  # $1 = timestamp epoch di destinazione. Dorme il tempo che manca, ricalcolandolo da capo
  # (non sleep fissi accumulati): se il cron o uno sleep precedente e' partito con un attimo
  # di ritardo, il countdown resta comunque agganciato all'orologio reale.
  local restano
  restano=$(( $1 - $(date +%s) ))
  [ "$restano" -gt 0 ] && sleep "$restano"
}

ORA_RIAVVIO=$(date -d "today 03:00:00" +%s)
# Se lo script parte gia' dopo le 03:00 (cron in ritardo di piu' di 5 minuti), punta a
# domani invece di aspettare quasi 24 ore nel posto sbagliato.
if [ "$(date +%s)" -ge "$ORA_RIAVVIO" ]; then
  ORA_RIAVVIO=$(date -d "tomorrow 03:00:00" +%s)
fi

echo "$(date '+%F %T') sequenza di riavvio avviata, obiettivo $(date -d "@$ORA_RIAVVIO" '+%F %T')" >> "$LOG"

aspetta_fino_a $(( ORA_RIAVVIO - 300 ))
manda 'broadcast! &e&lRiavvio del server &7tra &c&l5 minuti&7. Salva quello che stai facendo!'

aspetta_fino_a $(( ORA_RIAVVIO - 60 ))
manda 'broadcast! &e&lRiavvio del server &7tra &c&l1 minuto&7!'

aspetta_fino_a $(( ORA_RIAVVIO - 30 ))
manda 'broadcast! &e&lRiavvio del server &7tra &c&l30 secondi&7!'

for i in 10 9 8 7 6 5 4 3 2 1; do
  aspetta_fino_a $(( ORA_RIAVVIO - i ))
  manda "actionbar! &c&lRiavvio tra &f$i&c&l..."
  manda "title @a title {\"text\":\"$i\",\"color\":\"red\",\"bold\":true}"
  manda 'title @a subtitle {"text":"Riavvio del server","color":"gray"}'
done

aspetta_fino_a "$ORA_RIAVVIO"
manda 'broadcast! &c&lRiavvio in corso... &7a tra pochissimo!'
manda 'save-all'
# Da' tempo al mondo di finire di scrivere su disco prima di terminare il processo.
sleep 5

echo "$(date '+%F %T') save-all inviato, riavvio il servizio con systemd" >> "$LOG"
sudo systemctl restart magicadventure.service
echo "$(date '+%F %T') systemctl restart eseguito" >> "$LOG"
