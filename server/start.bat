@echo off
REM ============================================================
REM  MAGICADVENTURE - Avvio server Spigot 26.2 (con auto-restart)
REM ============================================================
REM  Usa esplicitamente Java 25 (richiesto da Minecraft 26.x).
REM  Modifica -Xms / -Xmx per la RAM (es. -Xmx4G per 4 GB).
REM
REM  Quando fermi il server (comando "stop") riparte da solo
REM  dopo 5 secondi. Per chiuderlo DEFINITIVAMENTE premi CTRL+C
REM  durante il conto alla rovescia e conferma con S.
REM ============================================================

title MAGICADVENTURE - Spigot 26.2
cd /d "%~dp0"

set "JAVA=C:\Program Files\Microsoft\jdk-25.0.3.9-hotspot\bin\java.exe"
set "JAR=spigot-26.2.jar"

if not exist "%JAR%" (
    echo [ERRORE] File "%JAR%" non trovato in questa cartella.
    echo Assicurati che la build di Spigot sia terminata.
    pause
    exit /b 1
)

:start
echo.
echo === Avvio MAGICADVENTURE su Spigot 26.2 ===
REM Cache classi JVM (CDS): riscaldamento ridotto agli avvii successivi (vedi start.sh del VPS).
"%JAVA%" -Xms1G -Xmx2G -XX:+AutoCreateSharedArchive -XX:SharedArchiveFile=cache/paper-cds.jsa -jar "%JAR%" nogui

REM --- Backup: se il plugin AutoBackup ha lasciato il file-segnale,
REM     esegui il backup ORA che il server e' spento (copia coerente). ---
if exist "backup.flag" (
    del /f /q "backup.flag"
    echo.
    echo ============================================================
    echo  BACKUP IN CORSO ^(server spento^) - non chiudere...
    echo ============================================================
    powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0..\Backup\backup.ps1"
    echo  Backup terminato.
)

echo.
echo ============================================================
echo  Il server si e' fermato.
echo  Riavvio automatico tra 5 secondi...
echo  Premi CTRL+C ORA per chiuderlo DEFINITIVAMENTE.
echo ============================================================
timeout /t 5 /nobreak
goto start
