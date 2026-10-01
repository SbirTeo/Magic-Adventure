#!/usr/bin/env bash
# ============================================================
#  Stop PULITO di un server MAGICADVENTURE gestito da systemd (ExecStop).
#  Manda "stop" alla SUA screen (salvataggio mondi), aspetta che la
#  SUA JVM termini, poi interrompe il loop di start.sh con CTRL+C e
#  chiude la screen. Ogni server ha la sua copia con SCREEN diverso:
#  sulla stessa macchina girano piu' paper.jar, e cercarli per nome
#  (pgrep -f paper.jar) farebbe aspettare anche quello degli altri.
# ============================================================
SCREEN=hub

PID=$(screen -list | grep -oE "[0-9]+\.$SCREEN[[:space:]]" | grep -oE '^[0-9]+' | head -n1)
[ -n "$PID" ] || exit 0

discendenti() { for c in $(pgrep -P "$1"); do echo "$c"; discendenti "$c"; done; }
java_vivo() { for p in $(discendenti "$PID"); do [ "$(cat /proc/$p/comm 2>/dev/null)" = java ] && return 0; done; return 1; }

screen -S "$SCREEN" -X stuff "stop$(printf '\r')" 2>/dev/null || exit 0

# Attende che la JVM di QUESTA screen termini davvero (fino a 45s)
for i in $(seq 1 45); do
    java_vivo || break
    sleep 1
done

# CTRL+C durante il countdown di start.sh: esce dal "while true" invece di riavviare
screen -S "$SCREEN" -X stuff "$(printf '\003')" 2>/dev/null || true
sleep 2
# Fallback: assicura che la screen sia chiusa
screen -S "$SCREEN" -X quit 2>/dev/null || true
exit 0
