# Magic Adventure — regole di lavoro

Appunti per Claude. Valgono per ogni sessione, locale o cloud.

## Toolchain

- **JDK 25** (`<java.version>25</java.version>` nei pom dal 3 settembre 2026) + Maven 3.9.9.
  Con la 21 la build muore con *"release version 25 not supported"*: non è un problema di Maven.
- Nelle **sessioni cloud** il container parte senza JDK 25 (`apt` ha solo la 21). Si installa così,
  una volta per sessione, e da lì `mvn package` funziona:

  ```bash
  curl -sSL -o /tmp/jdk25.tar.gz \
    "https://api.adoptium.net/v3/binary/latest/25/ga/linux/x64/jdk/hotspot/normal/eclipse"
  mkdir -p /opt/jdks && tar -xzf /tmp/jdk25.tar.gz -C /opt/jdks
  export JAVA_HOME=/opt/jdks/jdk-25.0.4.1+1 && export PATH="$JAVA_HOME/bin:$PATH"
  ```

## Deploy sul VPS — cosa può fare una sessione cloud e cosa no

Il server live è `ubuntu@141.94.123.249`, la chiave è `~/.ssh/ovh_vps` **sul PC**, e il deploy
completo lo fa `website\aggiorna-tutorial.ps1` (rigenera la guida, ricostruisce il jar, scp sul VPS,
riavvio, il guardiano `sync-guida.path` porta la guida risolta sul sito).

**Dalle sessioni cloud il deploy non si può fare.** Non è una scelta di Claude: nel container
`~/.ssh` è vuota, i client `ssh` e `scp` non sono nemmeno installati, la porta 22 del VPS è chiusa
dalla policy di rete dell'ambiente, e fra le variabili d'ambiente non c'è nessuna credenziale del
VPS (ci sono solo i token di GitHub/AWS/GCloud iniettati dall'harness). **Non chiedere di nuovo se
l'accesso c'è: verificalo con questi quattro controlli e, se sono come qui sotto, fermati al push.**

```bash
ls -A ~/.ssh | wc -l                                    # 0
command -v ssh scp                                      # niente
bash -c 'cat </dev/null >/dev/tcp/141.94.123.249/22'    # fallisce
env | cut -d= -f1 | grep -iE 'ssh|vps|ovh|deploy'       # vuoto
```

Quindi, in cloud, il lavoro **finisce con il push sul branch**: build verde + commit + push. Il
`mvn package` e `aggiorna-tutorial.ps1` si lanciano dal PC. Dirlo una volta nel riepilogo, senza
ripeterlo a ogni messaggio.

**La via che funziona da GitHub: `.github/workflows/deploy-vps.yml`.** I secret del progetto non
sono leggibili da nessuno (nemmeno dal proprietario): l'unica cosa che può usarli è un workflow di
Actions, e i runner di GitHub la porta 22 ce l'hanno. Quindi il deploy in cloud si fa da
**Actions → Deploy plugin sul VPS → Run workflow**, scegliendo il plugin.

- Parte **solo a mano** (`workflow_dispatch`): un deploy in produzione non dev'essere l'effetto
  collaterale di un push.
- `workflow_dispatch` compare nella UI solo se il file è sul **branch di default**: finché sta su un
  branch di lavoro, il pulsante non c'è.
- Servono i secret `VPS_SSH_KEY` (la chiave privata per intero) e, facoltativo,
  `VPS_KNOWN_HOSTS` (`ssh-keyscan -H 141.94.123.249`).
- Claude può **lanciarlo** (`actions_run_trigger`) e leggerne i log, ma non può leggere i secret né
  scavalcare un'eventuale approvazione dell'environment `produzione`.

In alternativa, per abilitare il deploy direttamente dal container servirebbero tre cose lato
ambiente: la chiave privata come variabile d'ambiente dell'environment (Claude Code on the web →
impostazioni dell'ambiente), `openssh-client` installato dallo script di setup, e la policy di rete
che permetta la 22 verso quell'IP.

## Guide: la regola di ferro

Tre guide, e **nessuna delle tre si scrive a mano nel risultato**:

| | Dove | Fonte |
|---|---|---|
| Tutorial giocatori | `/tutorial` sul sito | `plugins-src/MagixFactions/docs/build_tutorial.py` |
| Guida amministratori | `/manage.php?section=guida` | il capitolo che ogni plugin genera in `onEnable()` con `StaffGuide` |
| Regolamento | `/tutorial#regolamento` | testo nel gestionale (`site_pages`) |

- `docs/tutorial.html` **non si modifica mai a mano**: si rigenera con `python build_tutorial.py`.
- `website/public/assets/guida/magixfactions.html` **non si copia a mano**: è un modello pieno di
  segnaposto che solo il plugin sa risolvere. Si allinea da sé al riavvio del server.
- I numeri nelle guide non si scrivono a mano: `{{cfg:chiave}}`, `{{secondi:}}`, `{{ore:}}`,
  `{{percento:}}`. Le frasi che valgono solo per una modalità stanno in `{{se:chiave=valore}}…{{/se}}`.
- Prima di ogni commit: `python3 plugins-src/check_all.py` deve essere verde.

## Controllare che le guide siano allineate

Non basta guardare se un commit ha toccato una guida: un rilascio può cambiare il **significato** di
una voce senza toccare nessun file di documentazione (è successo con v0.47.2, tempo di gioco). Il
controllo giusto è **rilascio per rilascio** — un rilascio = un commit che alza la versione nel pom:

```bash
git log --date=short --pretty="%h|%ad|%s" --since="<data>" -- '*/pom.xml'
```

e per ognuno chiedersi cosa è cambiato per il giocatore e per lo staff, non se il commit toccava
`build_tutorial.py`. Utile anche confrontare i sottocomandi veri con la tabella del tutorial:

```bash
grep -oP 'case "\K[a-z0-9]+' .../command/FCommand.java | sort -u
```

## Convenzioni

- Un commit = un rilascio = una versione alzata nel pom, anche per le sole guide
  (es. `MagixFactions v0.47.1: guide allineate (...)`). Messaggi in italiano.
- I jar non entrano in git (`server/` è un mirror del VPS, gitignorato).
- Un plugin **nuovo** deve avere subito il capitolo `StaffGuide` e le classi comuni in `util/`
  (`StaffGuide`, `ConfigValues`, `DurationText`, `Help`), identiche agli altri a parte il `package`.
  Il comando di reload deve riscrivere le guide.
- Vedi anche `plugins-src/GUIDA-STAFF.md` e `plugins-src/STILE-MAGIX.md`.
