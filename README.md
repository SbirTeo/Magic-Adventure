# MagicAdventure — workspace

Tutto il mondo del server Minecraft **MAGICADVENTURE** in un'unica cartella locale.
Si lavora su questo PC; il server live gira sul **VPS OVH** (`ubuntu@141.94.123.249`).

## Struttura
```
magicadventure/
├─ server/                cartella server / deploy locale (mirror del VPS: jar, plugins, mondi, start.bat/start.sh)
├─ plugins-src/           sorgenti dei plugin sviluppati da noi
│   └─ MagixFactions/      progetto Maven (build: mvn package -> target/MagixFactions-<ver>.jar)
└─ README.md             questo file
```

## Build di un plugin
```powershell
cd plugins-src\MagixFactions
mvn package
# output: target\MagixFactions-<versione del pom>.jar
```
Toolchain: **JDK 25** (`JAVA_HOME`) + **Maven 3.9.9** (vedi memoria `java-maven-env`).
I `pom.xml` chiedono `<java.version>25</java.version>` dal 3 settembre 2026: con la 21 la build
muore subito con *"release version 25 not supported"*, e non e' un problema di Maven.

## Deploy sul VPS
Il jar va in `server/plugins/` (rimuovendo la versione vecchia) e poi caricato sul VPS in
`/home/ubuntu/magicadventure/plugins/` via SSH/SCP (chiave `~/.ssh/ovh_vps`).

## Come lavorare con Claude
Avvia Claude **da questa cartella** (`cd C:\Users\teolo\progettiCLAUDE\magicadventure` poi `claude`)
così sessioni e memoria restano legate al progetto. NON tenere il codice dentro `~/.claude`.

## Stato sviluppo MagixFactions
Modulo 1 ✅ (fazioni/gradi) · Modulo 2 ✅ (relazioni alleato/nemico) · **Modulo 3 (prossimo): territori/claim** + protezione + power.
