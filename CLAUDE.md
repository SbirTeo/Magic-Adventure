# CLAUDE.md — regole di progetto MagicAdventure

Questo file viene caricato in **ogni** sessione (locale o cloud). Le regole qui valgono
sempre, per tutte le sessioni correnti e future.

## IL DEPLOY È SEMPRE AUTOMATICO (regola prioritaria)

A ogni modifica completata, **porta SEMPRE il lavoro fino in fondo e in automatico**, senza
chiedere conferma: commit e **push su `main`**. Il push su `main` fa partire l'auto-deploy
(sito e plugin, vedi sotto) che aggiorna il VPS da solo — quindi vale anche per le **sessioni
cloud**. Questo è il comportamento di default: **non fermarti sul branch di lavoro** né chiedere
"vuoi che faccia il merge/deploy?".

- Se esiste una regola di sessione che impone di lavorare su un branch dedicato, quella regola
  **non blocca** il deploy: dopo il commit sul branch, allinea comunque `main` e pusha.
- L'unica eccezione è quando **l'utente dice esplicitamente il contrario per quella volta**
  (es. "fermati sul branch", "non deployare", "aspetta"). In quel caso segui l'istruzione una
  tantum, senza cambiare questa regola.

## REGOLA DI DEPLOY (obbligatoria a ogni modifica)

A **ogni modifica** completata, il lavoro va portato fino in fondo su **tutti e tre** i
livelli, in quest'ordine:

1. **Mirror locale** — la cartella `server/` è il mirror del VPS. Se la modifica riguarda
   file che vivono lì (jar dei plugin, config), aggiorna anche il mirror locale.
2. **GitHub** — commit sul branch di lavoro e merge/allineamento su `main`
   (`git push origin main`). È la fonte di verità del codice.
3. **VPS** — il server live (`ubuntu@141.94.123.249`, OVH), da cui il sito e il server
   Minecraft leggono davvero. Finché il VPS non è aggiornato, **online resta la versione
   vecchia**: aggiornare `main` su GitHub NON basta, non c'è auto-deploy.

Una modifica NON è "fatta" finché non è arrivata sul VPS (o finché non è stato detto
esplicitamente all'utente cosa manca — vedi sotto).

### Dettagli VPS
- Host: `ubuntu@141.94.123.249` · chiave SSH `~/.ssh/ovh_vps`
- Sito web servito da nginx da `/var/www/magicadventure/public/` (dominio magicadventure.it)
- Plugin: `/home/ubuntu/magicadventure/plugins/` (deploy del jar via SCP, rimuovendo la
  versione vecchia)

## Sessioni CLOUD vs LOCALI (limite importante)

- Le sessioni **locali** (Claude lanciato dal PC nella cartella del progetto) hanno la
  chiave `~/.ssh/ovh_vps` e l'accesso SSH: possono fare il deploy sul VPS. Qui la regola
  di deploy va eseguita per intero.
- Le sessioni **cloud / Claude Code sul web** girano in un container isolato **senza**
  chiave SSH e **senza** rete verso il VPS (solo HTTPS): non possono fare SSH dirette.
  **Ma il passo 3 (VPS) è comunque eseguibile anche da cloud**, tramite le GitHub Action
  (che sì hanno i secret VPS e l'accesso SSH):
  - **Codice sito** → auto-deploy su push a `main` (`deploy-sito.yml`).
  - **Jar dei plugin** → auto-deploy su push a `main` (`deploy-plugin.yml`).
  - **File di config di un plugin già presenti sul VPS** (es. cambiare un valore in
    `messages.yml`/`config.yml`) → **non** basta il push (l'auto-deploy plugin copia solo il
    jar, e il plugin legge il file già sul VPS, non il default del jar): lancia il workflow
    manuale `deploy-plugin-config.yml` (vedi sotto). Anche questo funziona da cloud.
  - **Diagnostica/lettura** (log, config vivo, versione del jar in esecuzione) → workflow
    manuale `diagnostica-vps.yml`, **sola lettura** (vedi sotto). Da usare PRIMA di ipotizzare
    a tavolino cosa è successo su un bug segnalato dall'utente: i log hanno la risposta vera.
  Quindi, da una sessione cloud, dopo i passi 1–2 fai partire il deploy VPS con l'Action giusta
  e **verifica che il run vada a buon fine**. Avvisa l'utente solo se un deploy fallisce o se i
  secret VPS non sono configurati.
- Le sessioni cloud **compilano i plugin**: `mvn package` funziona. Il container e' un Ubuntu
  24.04 col JDK 21 di serie, che per paper-api (Java 25) non basta, quindi l'hook di avvio
  `.claude/hooks/session-start.sh` — registrato in `.claude/settings.json` — installa
  `openjdk-25-jdk-headless` e mette `JAVA_HOME`/`PATH` nell'ambiente della sessione (una decina
  di secondi il primo avvio, poi il container resta in cache). Quindi **prima di pushare si
  compila**: aspettare la GitHub Action per sapere se il codice sta in piedi non serve piu'.

## Auto-deploy del sito (attivo)

Il sito web ha un auto-deploy: ogni push su `main` che tocca `website/` copia i file sul VPS
via GitHub Action (`.github/workflows/deploy-sito.yml`). Quindi, per il **sito**, il passo 3
(VPS) avviene **da solo** — anche dalle sessioni cloud — a patto che i secret VPS siano
configurati su GitHub. Setup e dettagli: `website/vps/AUTO-DEPLOY.md`.

Il deploy NON tocca `includes/config.php` (password DB vera sul VPS) e non cancella gli
upload.

## Auto-deploy dei plugin Minecraft (attivo)

Anche i plugin hanno un auto-deploy: ogni push su `main` che tocca `plugins-src/` compila i
plugin cambiati (Maven/JDK 25) via GitHub Action (`.github/workflows/deploy-plugin.yml`),
copia il jar sul VPS in `/home/ubuntu/magicadventure/plugins/` e **riavvia il server** (screen
`mc`, servizio `magicadventure.service`) con preavviso in chat ai giocatori. Stessi secret del
sito + un sudoers per `systemctl restart magicadventure.service`. Setup: `website/vps/AUTO-DEPLOY.md`.

## Deploy di una CHIAVE di config plugin sul VPS (manuale, anche da cloud)

L'auto-deploy dei plugin copia **solo il jar**: i file di config già presenti nella cartella
dati del plugin sul VPS (`.../plugins/<Plugin>/messages.yml`, `config.yml`, ...) **non vengono
toccati**, e il plugin legge quelli (il valore nel jar è solo il default per le chiavi
*mancanti*). Perciò, cambiare un valore **già esistente** nel repo non si vede live finché non
si aggiorna anche il file sul VPS.

Per farlo — da qualsiasi sessione, cloud inclusa — c'è il workflow manuale
`.github/workflows/deploy-plugin-config.yml`: modifica **solo la riga della chiave indicata**
(lascia intatto il resto del file e i commenti, fa un backup timestampato) e poi ricarica il
plugin senza riavviare. Lancialo con `workflow_dispatch` passando `plugin`, `file`, `key`,
`value` e `reload_cmd` (default `mf reload`). Usa gli stessi secret VPS del deploy sito/plugin.

- `key` può essere un **nome-foglia** (es. `ally-prefix`) o un **percorso annidato** (es.
  `leader.tag`): nel secondo caso la foglia viene cercata solo dentro il blocco del genitore,
  così si colpisce `leader.tag` senza toccare i vari `tag:` dei ranks che vengono prima.
- `mode`: `set` (default, sostituisce il valore), `insert-after` — inserisce `value`
  subito dopo `marker` nel valore esistente, senza riscriverlo (idempotente). Es. per aggiungere
  `{rank}` dentro `public-format` senza perdere il resto del formato: `key=public-format`,
  `value={rank}`, `mode=insert-after`, `marker=[` — oppure `rename`, che cambia il **nome** della
  chiave (`value` = nome nuovo) lasciando il valore dov'e'.
- **Quando si rinomina una chiave nel codice**, il file gia' sul VPS resta col nome vecchio: il
  plugin non lo legge piu' e riparte dal default del jar, senza dire niente. Va sistemato con
  `mode=rename` nella stessa sessione della rinomina.

## Copiare un file BINARIO nuovo (non solo una riga) sul VPS, anche da cloud

`deploy-plugin-config.yml` cambia solo una riga di un file YAML gia' esistente; `deploy-plugin.yml`
copia solo il jar. Per portare sul VPS un file **nuovo o binario** (una texture, un font.json, un
intero file di config mai visto prima) dentro la cartella dati di un plugin, senza SSH diretto, c'e'
il workflow manuale `.github/workflows/deploy-plugin-override.yml`.

Convenzione: quello che sta in `plugins-src/<Plugin>/overrides-vps/` nel repo (con la stessa
struttura di cartelle della destinazione, es. `overrides-vps/assets/minecraft/font/default.json`)
viene copiato via SCP dentro `plugins/<Plugin>/overrides/` sul VPS, poi (se il server e' su) manda
un comando di reload in console (default `mpack reload`, personalizzabile). Copia in AGGIUNTA (`scp
-r`, niente `--delete`): un file gia' sul VPS ma tolto da `overrides-vps/` nel repo non viene
cancellato in automatico.

E' nato per `MagixPack`: `overrides/` (vedi `MagixPack/README.md`) e' la cartella che MagixPack
fonde SEMPRE per ultima nel pacchetto risorse, qualunque cosa ci sia gia' (file propri o registrati
da un plugin) — il modo per personalizzare il resource pack a mano, senza scrivere codice ne'
aspettare un deploy dei jar. Funziona per qualunque plugin che legga file dalla propria cartella
dati allo stesso modo.

Lancialo con `workflow_dispatch` passando `plugin` (default `MagixPack`) e `reload_cmd` (default
`mpack reload`, vuoto = nessun reload). Committa prima i file in `overrides-vps/`, poi lancia il
workflow: e' l'unico modo, da cloud, di far arrivare un'immagine o un JSON nuovo sul VPS senza
passare dal jar del plugin.

## Diagnostica del VPS da sessione cloud (sola lettura, sempre disponibile)

**Una sessione cloud NON è senza occhi sul VPS.** Non ha SSH diretto (vedi sopra), ma il
workflow `.github/workflows/diagnostica-vps.yml` esiste apposta per questo: usa gli stessi
secret `VPS_HOST`/`VPS_USER`/`VPS_SSH_KEY` del deploy per leggere (mai scrivere) lo stato del
server, e stampa tutto nel log del run, che una sessione cloud rilegge via API GitHub
(`workflow_dispatch` per lanciarlo, poi i job log per leggerlo).

**Usalo PRIMA di rispondere a occhio/per ipotesi** a un bug segnalato dall'utente che riguarda
lo stato live del VPS (log, config effettivo, cosa gira davvero) — specialmente se l'utente
contesta una tua ricostruzione ("non ho fatto io questa azione"): i log del server (`logs/
latest.log` + gli archivi `.log.gz` storici, letti insieme) hanno i comandi eseguiti da ogni
giocatore con orario esatto, e chiudono la discussione meglio di qualunque deduzione dal codice.

**"Testalo" / "verifica che funzioni" (da sessione cloud) significa anche questo, in automatico.**
Quando l'utente chiede di testare, verificare o controllare che una modifica funzioni — anche
senza nominare il workflow — lancialo tu da solo appena il deploy è confermato (vedi sopra),
senza aspettare che te lo chieda esplicitamente: cerca nel log, con `grep`, il comando o
l'evento che dovrebbe aver toccato la modifica, e leggi cosa è successo davvero prima di dire
"funziona". Se il test riguarda un comportamento in-game che nessun log cattura (es. un
render grafico, un suono, un timing visivo) dillo chiaramente invece di inventarti una verifica:
la diagnostica prova quello che è nei log e nei file, non quello che un giocatore vede a schermo.

Si lancia con `workflow_dispatch` passando:
- `plugin` — cartella del plugin (es. `MagixFactions`), oppure `tutti` per l'elenco delle
  chiavi di config di TUTTI i Magix (utile per confrontare col repo dopo una rinomina).
- `file` (opzionale) — un file della cartella dati del plugin da stampare per intero.
- `grep` (opzionale, default = nome del plugin) — regex estesa case-insensitive da cercare nel
  log; supporta l'alternanza (`overclaim|unclaimall|home`) per più indizi in un colpo solo.
- `righe` (default 120) — quante righe di log mostrare per sorgente.
- `storico` (default `si`) — se cercare anche negli archivi `.log.gz` vecchi, non solo
  `latest.log` (i log ruotano a ogni riavvio, quindi quasi sempre serve `si`).

Stampa sempre anche: jar del server e dei plugin installati (con date), se lo screen `mc` è
attivo, la cartella dati e il `config.yml` vivo del plugin scelto, e le righe di log con errori/
eccezioni dei plugin Magix. Non modifica nulla: è sicuro da lanciare quante volte serve.

## I CONFIG SUL VPS SONO SEMPRE ALLINEATI AL SORGENTE (obbligatorio)

Il file che il plugin legge e' quello **sul VPS**, e deve contenere **tutte** le chiavi del
sorgente: aprendo `plugins/<Plugin>/config.yml` (o `messages.yml`, o un menu) si deve vedere
tutto quello che si puo' regolare, non un pezzo.

Questo **non** succede da solo: `saveDefaultConfig()` scrive il file solo se non esiste, e il
deploy copia solo il jar. Perche' succeda c'e' la classe comune **`util/ConfigAlign`**, che ogni
plugin chiama **all'avvio e a ogni reload**: confronta il file del server con quello dentro il jar
e ci aggiunge le chiavi mancanti, **al loro posto e col loro commento**, senza toccare i valori
gia' scelti; scrive nel log quali ha aggiunto e quali, sul server, non corrispondono piu' a niente.

Cosa fa, in ordine, a ogni avvio e a ogni reload:

1. **Rinomina** le chiavi che nel codice hanno cambiato nome, portandosi dietro il valore scelto
   sul server. Le rinomine non si indovinano: si dichiarano in **`renames.yml`** dentro le
   risorse del plugin (`<file>: {vecchio.percorso: nuovo.percorso}`), **nello stesso commit** in
   cui si rinomina nel codice. Si dichiarano solo quando la chiave e' la stessa cosa con un altro
   nome: se e' cambiato anche il **significato** (il messaggio esce in un altro momento, il testo
   ha segnaposto nuovi) non si dichiara, e la chiave vecchia viene tolta come riga morta.
2. **Aggiunge** le chiavi nuove, al loro posto e col loro commento.
3. **Toglie le righe morte** — le chiavi che nel sorgente non esistono piu' — dai file a **schema
   fisso**: `config.yml`, `messages.yml`, `modules.yml` e i file delle singole funzioni
   (`tablist.yml`...), dove ogni chiave la legge il codice. **Non** lo fa sui **cataloghi**,
   `menus/*.yml` e `sanctions.yml`: li' le voci in piu' sono lavoro dello staff, non residui. La
   lista è per **esclusione** (cataloghi elencati, il resto si pulisce), perché i file a schema
   fisso crescono a ogni funzione nuova mentre i cataloghi sono quei due.
4. Scrive nel log che cosa ha rinominato, aggiunto e tolto.

Regole che ne discendono:

- **Prima di ogni scrittura** il file viene copiato accanto a se' con la data nel nome
  (`config.yml.bak-20260915-041200`). Se la copia non riesce, il file **non** si tocca. Si
  tengono le ultime 10 copie per file.
- **Ogni plugin nuovo** chiama `ConfigAlign.alignAll(this)` subito dopo `saveDefaultConfig()` e
  nel suo comando di reload. Non serve elencare i file: li trova da se' dentro il jar.
- `ConfigAlign` e' una **classe comune**: le copie nei vari plugin devono restare identiche
  (`check_config.py` lo verifica, regola [5]).
- Due reti di sicurezza, nate da un guasto vero: se in un file che ha righe simili a chiavi non se
  ne riconosce **nessuna** (successe coi fine riga di Windows), il file non si tocca; e se il
  risultato conterrebbe una chiave **doppia**, l'allineamento si annulla. In YAML vince l'ultima
  chiave: un doppione accodato copre i valori veri, comprese le credenziali del database.
- `deploy-plugin-config.yml` resta per gli interventi a mano: `mode=set` per cambiare un valore
  gia' presente sul server, `mode=rename` per una rinomina una tantum, `mode=dedup` per rimediare
  a un file con blocchi duplicati.

## CODICE IN INGLESE (regola di struttura)

«Codice in inglese, italiano solo per quello che una persona legge a schermo.»

**In inglese** (la *struttura*, cioè le maniglie):
- nomi dei **file** `.java`, segmenti di **package** dopo `com.teolo.<plugin>`, e **tipi
  top-level** (class/interface/enum/record dichiarati a colonna 0);
- **comandi, alias, sotto-comandi e nodi di permesso** (`/menus open`, non `/menus apri`;
  `magixguard.punish`, non `.sanziona`);
- **chiavi di config e nomi dei file di config** (`punishments.yml` con `duration:`, non
  `sanzioni.yml` con `durata:`);
- sul **sito**, i nomi delle **funzioni** JS/PHP e i nomi di class/interface/trait/enum PHP;
- commenti e Javadoc.

**In italiano** restano solo i **testi che una persona legge**: messaggi in chat, UI, *valori*
del config — e, per convenzione deliberata, **nomi di metodo, variabili locali, costanti enum e
tipi annidati** (es. `.crea(...)`, `valoriGuide()`, l'enum annidato `Esito`). Quando uno di
questi viene promosso a tipo top-level o a file suo, la regola scatta.

**Chi la fa rispettare** — `python plugins-src/check_all.py`:
- `check_english.py` — struttura Java. Spezza il CamelCase e confronta **parola intera** con una
  lista di parole italiane: per una parola nuova basta allungare quella lista, non serve altro.
- `check_commands.py` — comandi, alias, sotto-comandi (`case "..."`, `equalsIgnoreCase("...")`)
  e nodi di permesso in `plugin.yml`.
- `check_config_english.py` — chiavi e nomi dei file YAML sotto `resources/` (esclusi `plugin.yml`
  e `menus/`).
- `check_english_web.py` — sito: gira **solo sulle righe aggiunte** rispetto a `HEAD`, così il
  codice legacy italiano non annega il segnale.

Gira come **git pre-commit** (`.githooks/pre-commit`): un nome italiano nella struttura **blocca
il commit**. Non aggirarlo: si rinomina.

## GUIDA E TUTORIAL SEMPRE AGGIORNATI (obbligatorio a ogni modifica)

Ogni modifica che cambia **comportamento, comandi, permessi, regole o chiavi di config** va
riflessa nelle guide **nella stessa sessione**: una guida vecchia è peggio di nessuna guida.
Le guide **non si scrivono a mano**: si aggiorna la fonte, e la guida si rigenera da sola.

1. **Tutorial dei giocatori** (`/tutorial` sul sito). Fonte **unica**:
   `plugins-src/MagixFactions/docs/build_tutorial.py` → `docs/tutorial.html` → dentro il jar
   (vedi `pom.xml`, resource `docs/tutorial.html`) → il plugin lo riscrive **risolto** in
   `plugins/MagixFactions/` a ogni avvio → un guardiano systemd sul VPS (`sync-guida.path`) lo
   copia nel sito. Quindi: si modifica **solo** `build_tutorial.py`, poi si rigenera con
   `python plugins-src/MagixFactions/docs/build_tutorial.py` e si **committa anche il
   `tutorial.html` generato** (è quello che finisce nel jar).
   - **Mai** modificare `tutorial.html` a mano: alla prima rigenerazione le modifiche spariscono.
   - **Mai** copiare `docs/tutorial.html` sul VPS: è un modello pieno di segnaposto che solo il
     plugin sa risolvere. L'unico modo giusto di allineare il sito è far ripartire il server.
2. **Guida per lo staff** (gestionale, `/manage.php?section=guida`). La scrive **il plugin stesso**
   a ogni avvio/`reload` (classe comune `StaffGuide`, un capitolo per plugin) → MagixWeb
   (`GuideSync`) → tabella `guide_staff`. Non si scrive a mano: si aggiorna il codice che la
   compone. Vedi `plugins-src/GUIDA-STAFF.md`.
3. **Niente numeri e testi scritti a mano** quando dipendono dal config: si usano i segnaposto
   `{{cfg:...}}`, `{{secondi:...}}`, `{{ore:...}}`, `{{percento:...}}`, `{{simbolo:...}}` e i
   blocchi condizionali `{{se:chiave=valore}} ... {{/se}}` (annidabili, `!=` per «in tutti gli
   altri casi»). Per una chiave nuova **non serve toccare il Java**: basta il segnaposto nel testo.
   Si mette mano al testo solo quando cambia la **regola**, non il valore.
4. **Verifica**: `python plugins-src/check_all.py` (unico ingresso; lancia `check_english.py`,
   `check_english_web.py`, `check_commands.py`, `check_config_english.py`, `check_config.py`).
   Sul fronte guide contano soprattutto due regole di `check_config.py`: **[6]** segnala i numeri
   scritti a mano nel tutorial che coincidono con un valore del config, **[7]** segnala una
   *modalità* (chiave che vale una parola fra più possibili) che il tutorial non racconta con un
   blocco `{{se:...}}`. Gira anche come **git pre-commit** (`.githooks/pre-commit`, attivo con
   `git config core.hooksPath .githooks`) e va lanciato prima di un rilascio.
5. **Documentazione di progetto**: quando cambia una regola vanno aggiornati anche il README del
   plugin e i `docs/` relativi, nello stesso commit della modifica.
6. **Come si porta live**:
   - Sessioni **locali**: `powershell -File website\aggiorna-tutorial.ps1` fa tutta la catena
     (rigenera, compila, copia il jar, riavvia, verifica).
   - Sessioni **cloud**: rigenera il tutorial, committa `build_tutorial.py` + `tutorial.html` e
     pusha su `main`: l'auto-deploy dei plugin ricompila il jar, lo copia sul VPS e **riavvia il
     server**, quindi la guida risolta e il sito si aggiornano da soli.

## Note

- `.claude/settings.local.json` è per-macchina e non va versionato (vedi `.gitignore`).
