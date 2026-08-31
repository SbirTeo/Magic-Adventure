#!/usr/bin/env bash
# ============================================================
#  MAGICADVENTURE - Avvio server Paper 26.1.2 su Linux (VPS)
# ============================================================
#  Minecraft 26.x richiede Java 25 (sul VPS: Temurin 25).
#  VPS: 11 GB RAM / 6 vCPU -> heap FISSO 8G + Aikar's flags (G1GC).
#
#  NB: mondi rigenerati da zero in formato 26.1.2 (2026-06-29) per
#  usare Paper 26.1.2. spigot-26.2.jar resta come fallback.
#
#  Il server riparte da solo dopo lo "stop". Per fermarlo
#  DEFINITIVAMENTE premi CTRL+C durante il conto alla rovescia.
# ============================================================

cd "$(dirname "$0")" || exit 1

# "java" se e' nel PATH; altrimenti percorso assoluto, es:
#   JAVA="/usr/lib/jvm/temurin-25-jdk/bin/java"
JAVA="java"
JAR="paper.jar"
MEM="8G"

# --- Cache classi JVM (CDS): al primo stop pulito la JVM salva l'archivio delle classi caricate;
#     dagli avvii successivi le carica gia' pronte -> il "riscaldamento" (tick lenti al primo join
#     dopo un riavvio, diagnosticato con spark: era tutto classloading in libjvm) quasi sparisce.
CDS_FLAGS="-XX:+AutoCreateSharedArchive -XX:SharedArchiveFile=cache/paper-cds.jsa"

if [ ! -f "$JAR" ]; then
    echo "[ERRORE] File \"$JAR\" non trovato in questa cartella."
    exit 1
fi

# --- Aikar's flags (G1GC): riducono i freeze da Garbage Collector,
#     causa tipica di lag a scatti dopo i login. ---
AIKAR_FLAGS="-XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200 \
-XX:+UnlockExperimentalVMOptions -XX:+DisableExplicitGC -XX:+AlwaysPreTouch \
-XX:G1NewSizePercent=30 -XX:G1MaxNewSizePercent=40 -XX:G1HeapRegionSize=8M \
-XX:G1ReservePercent=20 -XX:G1HeapWastePercent=5 -XX:G1MixedGCCountTarget=4 \
-XX:InitiatingHeapOccupancyPercent=15 -XX:G1MixedGCLiveThresholdPercent=90 \
-XX:G1RSetUpdatingPauseTimePercent=5 -XX:SurvivorRatio=32 -XX:+PerfDisableSharedMem \
-XX:MaxTenuringThreshold=1 -Dusing.aikars.flags=https://mcflags.emc.gs -Daikars.new.flags=true"

while true; do
    echo
    echo "=== Avvio MAGICADVENTURE su Paper ==="
    "$JAVA" -Xms"$MEM" -Xmx"$MEM" $AIKAR_FLAGS $CDS_FLAGS -jar "$JAR" nogui

    # --- Backup: se il plugin AutoBackup ha lasciato il file-segnale,
    #     esegui il backup ORA che il server e' spento (copia coerente). ---
    if [ -f "backup.flag" ]; then
        rm -f "backup.flag"
        echo
        echo "============================================================"
        echo " BACKUP IN CORSO (server spento) - non chiudere..."
        echo "============================================================"
        if [ -x "./backup.sh" ]; then
            ./backup.sh
            echo " Backup terminato."
        else
            echo " [AVVISO] backup.sh non trovato/eseguibile: backup saltato."
        fi
    fi

    echo
    echo "============================================================"
    echo " Il server si e' fermato. Riavvio tra 5 secondi..."
    echo " Premi CTRL+C ORA per chiuderlo DEFINITIVAMENTE."
    echo "============================================================"
    sleep 5
done
