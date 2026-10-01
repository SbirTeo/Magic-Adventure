#!/usr/bin/env bash
# ============================================================
#  Stop PULITO del proxy VELOCITY gestito da systemd (ExecStop).
#  Stessa logica di server/stop-service.sh e server-hub/stop-service.sh:
#  manda "stop" alla SUA screen, aspetta che la SUA JVM termini, poi
#  interrompe il loop di start.sh con CTRL+C e chiude la screen.
#  (Velocity accetta "stop" come "shutdown": scollega i giocatori e chiude.)
# ============================================================
SCREEN=velocity

PID=$(screen -list | grep -oE "[0-9]+\.$SCREEN[[:space:]]" | grep -oE '^[0-9]+' | head -n1)
[ -n "$PID" ] || exit 0

discendenti() { for c in $(pgrep -P "$1"); do echo "$c"; discendenti "$c"; done; }
java_vivo() { for p in $(discendenti "$PID"); do [ "$(cat /proc/$p/comm 2>/dev/null)" = java ] && return 0; done; return 1; }

screen -S "$SCREEN" -X stuff "stop$(printf '\r')" 2>/dev/null || exit 0

# Attende che la JVM di QUESTA screen termini davvero (fino a 30s)
for i in $(seq 1 30); do
    java_vivo || break
    sleep 1
done

# CTRL+C durante il countdown di start.sh: esce dal "while true" invece di riavviare
screen -S "$SCREEN" -X stuff "$(printf '\003')" 2>/dev/null || true
sleep 2
# Fallback: assicura che la screen sia chiusa
screen -S "$SCREEN" -X quit 2>/dev/null || true
exit 0
