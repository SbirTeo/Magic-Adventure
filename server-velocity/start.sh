#!/usr/bin/env bash
# ============================================================
#  MAGICADVENTURE - Avvio del proxy VELOCITY su Linux (VPS)
# ============================================================
#  Velocity 4.x richiede Java 25 (sul VPS: Temurin 25, lo stesso dei Paper).
#  Un proxy non tiene mondi: heap FISSO 512M basta per centinaia di giocatori.
#
#  Stessa struttura di server/start.sh (faction) e server-hub/start.sh, senza CDS e
#  backup (qui non c'e' niente da salvare). Flag JVM: quelli consigliati da Velocity.
#
#  Il proxy riparte da solo dopo lo "stop". Per fermarlo
#  DEFINITIVAMENTE premi CTRL+C durante il conto alla rovescia.
# ============================================================

cd "$(dirname "$0")" || exit 1

JAVA="java"
JAR="velocity.jar"
MEM="512M"

if [ ! -f "$JAR" ]; then
    echo "[ERRORE] File \"$JAR\" non trovato in questa cartella."
    exit 1
fi

VELOCITY_FLAGS="-XX:+UseG1GC -XX:G1HeapRegionSize=4M -XX:+UnlockExperimentalVMOptions \
-XX:+ParallelRefProcEnabled -XX:+AlwaysPreTouch -XX:MaxInlineLevel=15"

while true; do
    echo
    echo "=== Avvio VELOCITY ==="
    "$JAVA" -Xms"$MEM" -Xmx"$MEM" $VELOCITY_FLAGS -jar "$JAR"

    echo
    echo "============================================================"
    echo " Il proxy si e' fermato. Riavvio tra 5 secondi..."
    echo " Premi CTRL+C ORA per chiuderlo DEFINITIVAMENTE."
    echo "============================================================"
    sleep 5
done
