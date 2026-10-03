# CLAUDE.md — regole di progetto MagicAdventure

Questo file viene caricato in **ogni** sessione (locale o cloud). Le regole qui valgono
sempre, per tutte le sessioni correnti e future.

## LINGUA DELLA CHAT: SEMPRE ITALIANO

In ogni sessione (locale o cloud), le risposte in chat all'utente sono **sempre in italiano**,
qualunque sia la lingua del messaggio ricevuto o del contenuto tecnico coinvolto (log, codice,
nomi in inglese per struttura come da regola sotto). Non chiedere conferma su questo: è la lingua
di default e basta.

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
- Plugin: `/home/ubuntu/magicadventure/faction/plugins/` (deploy del jar via SCP, rimuovendo la
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
copia il jar sul VPS in `/home/ubuntu/magicadventure/faction/plugins/` e **riavvia il server** (screen
`mc`, servizio `magicadventure.service`) con preavviso in chat ai giocatori. Stessi secret del
sito + un sudoers per `systemctl restart magicadventure.service`. Setup: `website/vps/AUTO-DEPLOY.md`.

## IL RIAVVIO DEL SERVER SI FA SEMPRE CON `stopserverfast` DI CMI (obbligatorio)

Ogni volta che un riavvio del server Minecraft viene innescato — dall'auto-deploy dei plugin o
da **qualunque** altra automazione, presente o futura — lo **STOP** si fa mandando in console
il comando CMI **`stopserverfast`** (salva tutto e chiude pulito), **non** con un
`systemctl restart` "secco" (che manderebbe un SIGTERM al processo). Non serve fare altro per
riportarlo su: lo screen `faction` (prima si chiamava `mc`) esegue `server/start.sh` (`while true; do java ...; done`), che
**rilancia da solo** il server qualche secondo dopo qualsiasi stop (è lo stesso meccanismo del
riavvio notturno, che manda solo `stop`) — e i jar nuovi, già copiati, vengono caricati.
`systemctl restart magicadventure.service` si usa **solo** quando il server è **spento** (nessuno
screen `faction`: non c'è nulla da fermare con `stopserverfast`, lo si avvia via systemd). Questo è
implementato in `deploy-plugin.yml`; qualsiasi nuovo meccanismo di riavvio deve seguire la stessa
regola.

Gli **avvisi ai giocatori e il conto alla rovescia** sono già configurati **dentro** il comando
CMI `stopserverfast`: l'automazione **non** deve aggiungere un proprio preavviso (`say`/countdown),
altrimenti i giocatori vedono due countdown sovrapposti. Manda solo `stopserverfast` e lascia fare
a CMI.

## Server sul VPS: velocity davanti, faction e hub dietro (collegato dal 30/09)

- **faction** — il server fazioni: `/home/ubuntu/magicadventure/faction` (fino al 30/09 stava direttamente in `magicadventure/`: i workflow accettano ancora entrambe le cartelle, e `magicadventure/plugins` e `logs` sono link verso `faction/`), screen `faction` (fino al 30/09
  si chiamava `mc`: i workflow accettano ancora entrambi i nomi), servizio
  `magicadventure.service`, porta **25701 solo su 127.0.0.1** (ci si arriva solo da Velocity; fino
  al 30/09 era sulla 25565 pubblica), heap 7G. E' il **server principale**: chi entra finisce qui.
  Mirror nel repo: `server/`.
- **hub** — `/home/ubuntu/magicadventure/hub`, screen `hub`, servizio `magix-hub.service`, porta 25600 **solo su
  127.0.0.1**, whitelist spenta: ci si arriva **solo** con `/server hub` dal proxy (cioe' solo il
  gruppo admin, vedi LuckPerms), heap 1G, mondo
  vuoto. Sorgente nel repo: `server-hub/` (`start.sh` e' la copia di `server/start.sh`: se si
  tocca uno dei due si allinea l'altro). Installato e riallineato dal workflow idempotente
  `predisponi-hub.yml`. Plugin di rete (LuckPerms condiviso, PlaceholderAPI, ProtocolLib, CMI,
  MagixAuth, MagixLanguage, MagixGuard, MagixBridge, MagixEssentials, MagixMenus, MagixPack, MagixTime, con i
  config copiati dal faction) installati da `hub-network-plugins.yml`; poi gli aggiornamenti dei plugin
  Magix con `deploy.target` = `faction hub` arrivano dal deploy automatico. Il deploy riavvia l'hub
  con `stopserverfast` (c'e' CMI). MagixPack dell'hub serve il suo pacchetto sulla porta **8444**
  (8443 e' del faction); texture e menu dell'hub: `overrides-hub/` + `deploy-plugin-override.yml`
  con `server: hub`.
  Per costruire l'hub ci sono FastAsyncWorldEdit (copiato dal faction) e **FastAsyncVoxelSniper**
  (3.2.5, dal 30/09). I plugin di altri (non Magix) si installano con `install-external-plugin.yml`
  (`server`, `url` da Modrinth o GitHub, `sha512` obbligatorio, riavvio con `stopserverfast`).
  Prima di Velocity per **costruirlo** c'era `hub-costruzione.yml`: `azione=apri` lo apriva a UNA
  persona sola (firewall sulla porta dell'hub solo per il suo IP + whitelist + op, FastAsyncWorldEdit copiato
  dal faction, `mondo=nuovo` rigenera il mondo vuoto tenendo il vecchio in `~/.bak/`);
  `azione=chiudi` lo riporta solo su 127.0.0.1. Con Velocity collegato `apri` **si rifiuta**: l'hub
  non si apre piu' da fuori. `azione=mondo-nuovo` rigenera il mondo **totalmente vuoto** senza aprire niente
  (stopserverfast, il vecchio mondo in `~/.bak/hub-mondo-<data>/`, spawn in 0 64 0).
- Ogni server nuovo va anche in `website/vps/console/istanze.conf` (e in
  `/etc/magicadventure/istanze.conf` sul VPS): e' cosi' che compare nella console del sito.
- **velocity** — il proxy, `/home/ubuntu/magicadventure/velocity`, screen `velocity`, servizio
  `magix-velocity.service` **abilitato** (parte con la macchina), heap 512M. Ascolta su
  **`0.0.0.0:25565`**, la porta pubblica, che il firewall apre solo agli IP di TCPShield. Sorgente
  nel repo: `server-velocity/` (`velocity.toml` si cambia li', mai a mano sul VPS: lo reinstalla
  con backup `predisponi-velocity.yml`, che col proxy collegato installa solo i file, senza prove
  ne' spegnimenti). Il deploy dei plugin **non** riavvia il proxy (butterebbe fuori tutta la rete). Dopo un MagixProxy nuovo
  il proxy si riavvia con `velocity-restart.yml` (scollega tutti per una decina di secondi).
  Offline mode (autentica MagixAuth), modern forwarding, TCPShield sul proxy, MOTD scritta da
  MagixProxy (stesso motd.yml di MagixEssentials), `log-command-executions` **sempre false** (loggherebbe le
  password di /login). `forwarding.secret` lo genera Velocity e resta **solo sul VPS**.
  Il passaggio l'ha fatto `passaggio-velocity.yml` (`controlla` / `attiva` / `annulla`, backup in
  `~/.bak/passaggio-velocity/<data>/`): faction su `127.0.0.1:25701`, backend con `proxies.velocity`
  in `paper-global.yml` e `network-compression-threshold=-1`, TCPShield tolto dai backend (e'
  nel backup). `annulla` rimette tutto com'era prima. Dettaglio in testa a
  `server-velocity/velocity.toml`.
- **MagixProxy** (`plugins-src/MagixProxy`) e' il plugin Velocity della rete: decide UUID e skin
  all'ingresso con le stesse regole di MagixAuth (vedi il suo README), e tiene il giro della rete:
  **il server principale per ora e' il faction** (`network.main_server`, e `try = ["faction"]` in
  velocity.toml): chi entra finisce li' e li' fa il login; hub e modalita' future si aprono solo
  dopo il login (sessione MagixAuth valida). Nessun ripiego sull'hub se il faction e' giu'.
  Chi esce da un server che NON e' il principale (chiusura, riavvio, kick) viene riportato sul
  principale (`network.fallback_to_main`): oggi hub -> faction, domani (principale = hub) un riavvio
  del faction porta tutti sull'hub.
  La **MOTD** dal proxy la scrive MagixProxy leggendo il `motd.yml` di MagixEssentials del faction
  (si modifica li', vale per entrambe le strade); `ping-passthrough` spento. Un plugin con il file
  `deploy.target` in `plugins-src/<Plugin>/` va dove dice lui (`faction`, `velocity`):
  `deploy-plugin.yml` riavvia il faction solo se un jar e' andato nel faction.
- **LuckPerms e' condiviso** (dal 30/09): storage `mariadb` sul database del sito (tabelle
  `luckperms_*`, credenziali di MagixAuth), `messaging-service: sql` (una modifica fatta su un
  server arriva da sola sugli altri), `server: faction` sul faction (sull'hub sara' `hub`, per
  i permessi diversi per server). Migrato con `luckperms-condiviso.yml`; il vecchio file H2 e il
  backup restano (`~/.bak/luckperms-<data>/`): per tornare indietro `storage-method: h2` e riavvio.
  Anche il **proxy** ha LuckPerms (jar a parte, **LuckPerms-Velocity**, `server: velocity`), sullo
  stesso database: lo installa `velocity-luckperms.yml` (versione e sha512 fissati li', config in
  `server-velocity/luckperms/config.yml`, credenziali copiate dal faction). I comandi del proxy
  (`/server`, `/send`, `/glist`, `/velocity ...`) li ha **solo il gruppo admin** (`velocity.*`
  true); tutti gli altri gruppi hanno `velocity.*` **false scritto apposta**, perche' `/server` di
  Velocity e' aperto a chi non ha il permesso negato. Un gruppo nuovo va negato allo stesso modo
  (rilanciare il workflow lo fa da solo). I permessi si danno anche dal faction con `/lp`.
- **MagixGuard e' su tutta la rete** (dalla 0.4.0, `deploy.target` = `faction hub`): archivio
  condiviso nel database del sito (tabelle `mg_*`, importate dal vecchio `magixguard.db` al primo
  avvio su mariadb), stesso `privacy.pepper` ovunque, `network.server-name` per server,
  `network.site-jobs: true` SOLO sul faction (decisioni del gestionale, regolamento, pulizia), e le
  sanzioni sincronizzate fra server ogni pochi secondi (un ban dato sull'hub butta fuori anche dal
  faction). Passaggio: `magixguard-shared-db.yml` (faction) poi `hub-network-plugins.yml` (hub).
- **MagixBridge** (fino alla 0.12 si chiamava **MagixWeb**; dalla 0.13.0, `deploy.target` =
  `faction hub`) e' il ponte fra i server e il sito, su ogni modalita'. Stessa coppia di chiavi di
  MagixGuard: `network.server-name` e `network.site-jobs` (true SOLO sul faction: consegna degli
  acquisti, traduzione del sito, gruppi, guida staff, pulizia della chat — su due server un
  acquisto arriverebbe due volte). La chat in home del sito ha una **scheda per server** (Hub,
  Factions: `GAME_SERVERS` in `website/includes/helpers.php`, colonna `web_chat.server`): un
  messaggio scritto in una scheda lo ripubblica in gioco solo quel server. I giocatori connessi
  in home sono il totale della rete (`mc_network_status()`: lo chiede a Velocity).
- **MagixLanguage e' su tutta la rete** (`deploy.target` = `faction hub velocity`, stesso jar: su
  Velocity parte `velocity/MagixLanguageVelocity`): su ogni server traduce da solo i plugin Magix
  installati li' (`translations.auto-discover`), e la lingua di ogni giocatore sta nel database del
  sito (tabella `language_players`, credenziali dal config di MagixAuth), quindi vale ovunque. I
  plugin del proxy (MagixProxy) chiedono i testi tradotti a MagixLanguage di Velocity. Il jar resta
  alla **0.4.14**: gli altri plugin compilano contro quella versione (cambiarla vuol dire cambiare
  tutti i pom). Dopo un MagixLanguage nuovo il proxy va riavviato con `velocity-restart.yml`.
- **La chat pubblica in gioco la scrive MagixEssentials** (modulo `chat`, dalla 0.9.0, su faction e
  hub): formato in `chat.yml`, scelto da solo come gli stili del nametag (`style: auto`: il primo
  stile di `styles` i cui plugin `requires` ci sono tutti; `factions` sul faction, `plain` sull'hub;
  pezzi facoltativi fra `[[ ]]`; `{faction}`/`{relcolor}`/`{rank}` glieli da' MagixFactions con `chatTokens`),
  suggerimento con ora e provenienza, link cliccabili, messaggi del sito (MagixBridge chiama il suo
  `broadcastWebChat`). MagixFactions tiene solo i canali fazione/alleati (`/f chat`). Ordine
  sull'evento: MagixGuard LOWEST (silenziati) e LOW (filtro), MagixFactions canali e MagixBridge
  (copia sul sito) NORMAL, MagixEssentials HIGH. **La chat di CMI e' spenta** (formato, colori,
  filtri, menzioni, fumetti): la spegne MagixEssentials nei file di CMI (`cmi.disable-module` in `chat.yml`); i
  messaggi privati `/msg` restano a CMI. Il **ponte dei
  placeholder** porta i valori di una modalita' sulle altre via database (`network_presence`,
  `network_placeholders`): ogni server pubblica quelli di `bridge.player-placeholders` /
  `bridge.global-placeholders` che sa calcolare, gli altri li leggono come
  `%network_<server>_<placeholder>%` (es. sull'hub `%network_faction_magixfactions_faction%`).
  `%magixweb_namecolor%` ha tenuto il vecchio nome apposta (e' nei formati di chat e nametag).
  La rinomina sul VPS l'ha fatta `deploy-plugin.yml` col file `replaces` (vedi li').
- **Velocity e' acceso dal 30/09.** I plugin adattati prima del passaggio: MagixAuth (UUID e skin
  decisi dal proxy: MagixProxy + MagixAuth 0.7.27), MagixGuard, MagixBridge e MagixPack (l'hub
  usa la porta 8444). Il cambio di pacchetto risorse fra faction e hub va ancora provato in gioco.

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
  `{rank}` dentro `faction-format` (chat.yml di MagixEssentials) senza perdere il resto del formato: `key=faction-format`,
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

Cambiare un file in `overrides-vps/` o `overrides-hub/` **non riavvia il server**: `deploy-plugin.yml`
ignora quelle cartelle (non finiscono nel jar). Si committa su `main` e poi si lancia questo workflow,
che fa solo un reload. Con `src_subdir` si copia una sola sottocartella di `overrides-vps/` (es.
`src_subdir: models` + `dest_subdir: models` per i modelli `.bbmodel` di MagixPack).

Lancialo con `workflow_dispatch` passando `plugin` (default `MagixPack`) e `reload_cmd` (default
`mpack reload`, vuoto = nessun reload). Con `server: hub` fa lo stesso per l'**hub**, prendendo i file
da `plugins-src/<Plugin>/overrides-hub/` (l'hub ha pacchetto e menu suoi: texture e menu dell'hub
vanno li', non in `overrides-vps/`). Committa prima i file in `overrides-vps/`, poi lancia il
workflow: e' l'unico modo, da cloud, di far arrivare un'immagine o un JSON nuovo sul VPS senza
passare dal jar del plugin.

Il quarto input, `dest_subdir` (default `overrides`), serve quando il file da portare non va
fuso in un `overrides/` ma scritto in un'ALTRA sottocartella della cartella dati del plugin — es.
un menu YAML nuovo di MagixMenus: quel plugin legge i menu solo da `plugins/MagixMenus/menus/`
(niente merge li', vedi `MenuManager.copyExample`: un file di menu aggiunto al jar non arriva da
solo sul server, a differenza delle chiavi di config che `ConfigAlign` allinea da solo). In quel
caso si mette il file in `plugins-src/MagixMenus/overrides-vps/` (stessa cartella di staging,
percorso relativo a `menus/` invece che a `overrides/`) e si lancia con `dest_subdir: menus`.

## Configurazione di nginx sul VPS (manuale, anche da cloud)

`website/nginx-magicadventure.conf` e `website/nginx-intestazioni-sicurezza.conf` vivono in
`/etc/nginx`, non in `/var/www`: l'auto-deploy del sito **non** li porta sul VPS. Dopo averli
cambiati si lancia il workflow manuale `.github/workflows/deploy-nginx.yml`: con `applica=no`
mostra le differenze fra il file vivo e quello del repo (da fare prima, per non sovrascrivere
modifiche fatte a mano sul VPS); con `applica=si` fa il backup in `~/.bak/nginx/`, sostituisce,
lancia `nginx -t` e ricarica solo se passa, altrimenti rimette il file di prima.

Se una modifica al sito **dipende** da una regola nginx nuova (es. un indirizzo che prima non
esisteva), l'ordine e': prima il commit della regola nginx su `main` + `deploy-nginx.yml`, poi il
push del PHP che la usa.

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
- `plugin` — `database` per lo stato di MariaDB (connessioni, chi le tiene, riavvii nel suo diario).
  Altrimenti cartella del plugin (es. `MagixFactions`), oppure `tutti` per l'elenco delle
  chiavi di config di TUTTI i Magix (utile per confrontare col repo dopo una rinomina).
- `file` (opzionale) — un file della cartella dati del plugin da stampare per intero.
- `grep` (opzionale, default = nome del plugin) — regex estesa case-insensitive da cercare nel
  log; supporta l'alternanza (`overclaim|unclaimall|home`) per più indizi in un colpo solo.
- `righe` (default 120) — quante righe di log mostrare per sorgente.
- `server` (default `faction`) — `hub` o `velocity` per leggere cartella, log e config dei plugin di quel server.
- `storico` (default `si`) — se cercare anche negli archivi `.log.gz` vecchi, non solo
  `latest.log` (i log ruotano a ogni riavvio, quindi quasi sempre serve `si`).

Stampa sempre anche: jar del server e dei plugin installati (con date), se lo screen `faction` è
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
4. **Riallinea i commenti** che vengono dal jar: l'intestazione del file, i titoli di sezione e il
   commento sopra ogni chiave che esiste anche nel sorgente prendono il testo del sorgente (i valori
   non si toccano). Restano come sono i commenti sopra le chiavi che il sorgente non ha (aggiunte
   dallo staff) e i menu (`menus/*.yml`). Quindi un commento si corregge **nel repo**, mai a mano sul
   VPS: al prossimo avvio verrebbe riscritto.
5. Scrive nel log che cosa ha rinominato, aggiunto, tolto e di quali chiavi ha aggiornato i commenti.

Regole che ne discendono:

- **Prima di ogni scrittura** il file viene copiato nella cartella `.bak/<Plugin>/...` — la
  cartella `plugins/` sostituita con `.bak/`, fuori da `plugins/` sul server — con la data nel
  nome (es. `.bak/MagixFactions/config.yml.bak-20260915-041200`). E' la stessa cartella che usa
  il workflow manuale `pulizia-bak-vps.yml` (`.github/workflows/pulizia-bak-vps.yml`): i backup
  nascono gia' li', quel workflow serve solo come rete di sicurezza per eventuali `.bak-*`
  lasciati in giro da altri script (o da versioni precedenti di questo meccanismo). Se la copia
  non riesce, il file **non** si tocca. Si tengono le ultime 10 copie per file.
- **Ogni plugin nuovo** chiama `ConfigAlign.alignAll(this)` subito dopo `saveDefaultConfig()` e
  nel suo comando di reload. Non serve elencare i file: li trova da se' dentro il jar.
- `ConfigAlign` e' una **classe comune**: le copie nei vari plugin devono restare identiche
  (`check_config.py` lo verifica, regola [5]).
- Due reti di sicurezza, nate da un guasto vero: se in un file che ha righe simili a chiavi non se
  ne riconosce **nessuna** (successe coi fine riga di Windows), il file non si tocca; e se il
  risultato conterrebbe una chiave **doppia**, l'allineamento si annulla. In YAML vince l'ultima
  chiave: un doppione accodato copre i valori veri, comprese le credenziali del database.
- **Un testo di serie cambiato** (es. un accento corretto) non arriva da solo sul server: i valori
  gia' presenti non si toccano. Si dichiara in **`value-fixes.yml`** nelle risorse del plugin
  (`<file>: [{old: "testo vecchio", new: "testo nuovo"}]`): `ConfigAlign` all'avvio e a ogni reload
  sostituisce SOLO un valore identico a quello vecchio (uno cambiato a mano dallo staff resta), con
  la copia in `.bak/`. MagixProxy (Velocity, senza ConfigAlign) fa lo stesso in `ProxyConfig`.
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

## OGNI TESTO CHE UN GIOCATORE LEGGE VA IN messages.yml (obbligatorio)

Nessun testo che un giocatore può vedere in chat, in un pannello, in un titolo/action bar o in un
messaggio di kick va scritto a mano dentro il `.java`: vive in `messages.yml` (o nel file di
funzione competente, es. `tablist.yml`), con una chiave, e il codice lo legge da lì. Questo vale
per **tutti** i plugin Magix, non solo per quelli già collegati a MagixLanguageAPI: una stringa
hardcoded in Java non è mai traducibile, quindi resta per sempre in italiano anche se il giocatore
ha scelto un'altra lingua — è esattamente il tipo di buco che MagixLanguage non può chiudere da
solo, per quanto sia fatto bene.

Non è solo una preferenza di stile: è la premessa perché la traduzione automatica funzioni. Una
chiave in `messages.yml` la sincronizza da sola `TranslationSync` verso `en.yml`/`es.yml`/`de.yml`
(vedi il capitolo di MagixLanguage); una stringa dentro `sender.sendMessage("...")` non la vede
nessuno, e resta un buco silenzioso finché qualcuno non lo nota giocando in un'altra lingua (è
successo davvero con l'elenco comandi di MagixAuth: sembrava tradotto, non lo era per niente).

**Cosa NON è testo del giocatore** (resta pure nel codice): nodi di permesso, nomi di comando,
chiavi di config, log di console, nomi tecnici (materiali, suoni, permessi) — tutto quello che la
regola "codice in inglese" qui sopra già copre.

**La classe condivisa `util/Help.java`** (identica in ogni plugin che ce l'ha, vedi
`plugins-src/STILE-MAGIX.md` §3) fa eccezione alla regola "una chiave per stringa": le sue frasi
di cornice (`Nessun comando disponibile.`, `Clicca per scriverlo`, `Riservato allo staff`,
`indietro`/`avanti`...) vivono sotto una manciata di chiavi fisse `help.chrome.*` in ogni
`messages.yml`, lette e tradotte da `Help` stesso tramite MagixLanguageAPI (softdepend, come ogni
altro plugin) — non serve toccare `Help.java` per aggiungerne una nuova, la classe le legge per
nome. Le voci dei comandi (`help.sections.<nome>.entries`) sono liste, tradotte con
`translateList`, non con `translate`.

Quando si trova una stringa hardcoded in un plugin già "migrato", non è un'eccezione da lasciar
stare: è lo stesso buco di MagixAuth, e si tratta allo stesso modo — chiave nuova in
`messages.yml`, lettura tramite MagixLanguageAPI, mai testo diretto nel `.java`.

## OGNI PLUGIN MAGIX RISOLVE I PLACEHOLDER DI PLACEHOLDERAPI NEI MESSAGGI (obbligatorio)

Il testo finale che un plugin manda a un giocatore (quello che esce da `Messages`/`messages.yml`,
dopo la sostituzione `{chiave}` e l'eventuale traduzione di MagixLanguage) passa **sempre** anche
da PlaceholderAPI, se è installato: così un messaggio può contenere `%magixpack_glyph_<id>%` (un
glifo/icona), `%magixessentials_balance_<id>%` (il saldo di una valuta), o qualunque altro
placeholder — proprio, di un altro plugin Magix, o di terzi — senza che il plugin che scrive il
messaggio debba sapere niente di chi lo risolve.

**La classe condivisa `hook/Papi.java`** (identica in ogni plugin, stesso principio di
`util/ConfigAlign.java`/`util/Help.java`: `check_config.py` [5] la allinea) espone:

```java
Papi.setup();                        // in onEnable, una volta sola
Papi.enabled();                      // true se PlaceholderAPI e' installato
Papi.resolve(player, testo);         // testo invariato se PAPI manca o il resolve fallisce
Papi.resolve(offlinePlayer, testo);  // per chi non e' per forza online (es. la chat del sito)
```

Mai un'eccezione, mai un testo diverso da quello scritto se PlaceholderAPI non c'è: `resolve` non
lancia mai, e senza placeholder dentro il testo torna com'era. Un plugin nuovo (o uno che manda
testo ai giocatori per la prima volta) aggiunge `hook/Papi.java`, lo richiama in `onEnable`, e
dichiara `PlaceholderAPI` nei `softdepend` del `plugin.yml` (mai `depend`: deve funzionare anche
senza).

**Dove si chiama `resolve`**: nel punto in cui `Messages` costruisce il testo finale da mandare —
dopo la sostituzione `{chiave}`/la traduzione, prima (o come parte) della colorazione — non prima,
altrimenti un placeholder dentro `{argomento}` non verrebbe mai risolto. Un `text.indexOf('%') < 0`
prima della chiamata evita il giro a vuoto sui messaggi senza placeholder (la stessa guardia già
usata in `chat/ChatModule.java` e `nametag/NametagManager.java` di MagixEssentials).

## OGNI COMANDO MAGIX HA L'ELENCO COMANDI NELLO STILE COMUNE (obbligatorio)

Ogni plugin Magix che dichiara comandi nel `plugin.yml` mostra il suo elenco comandi con la classe
condivisa **`util/Help.java`** (`Help.show` + `Help.fromConfig`, identica in ogni plugin): stessa
intestazione, colori del logo, comandi cliccabili, pagine con le frecce. `/<comando> help [pagina]`,
`?` come sinonimo e il numero da solo (`/mpack 2`); il comando senza argomenti apre l'elenco. Le voci
stanno in `help.sections` del `messages.yml`, la cornice in `help.chrome` (tradotte da MagixLanguage
come ogni altro messaggio). **Niente** righe `Uso: ...` o `§d<Plugin> » ...` scritte a mano: un
sottocomando sconosciuto risponde con la chiave `unknown-subcommand`, che rimanda a `help`. Dettagli e
tabella dei plugin: `plugins-src/STILE-MAGIX.md` §3. Lo fa rispettare `check_config.py`, regola
**[13]** (blocca il commit).

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
   a ogni avvio/`reload` (classe comune `StaffGuide`, un capitolo per plugin) → MagixBridge
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
   blocco `{{se:...}}`, **[9]** segnala un placeholder PlaceholderAPI che il plugin risolve ma che
   la guida staff non elenca (costante `DOCS` della classe dei placeholder, passata a
   `StaffGuide.placeholders(...)`), **[10]** segnala una chiave di `config.yml` che il tutorial non racconta e
   che non e' marcata `[solo staff]` nel commento (della chiave o di una sezione sopra): il tutorial cita
   **solo** quello che tocca i giocatori, e ogni chiave nuova obbliga a scegliere. **[11]** segnala un numero
   scritto a mano nelle frasi della guida staff che coincide con un valore del config (si usa
   `{{cfg:...}}`; le coincidenze vere vanno in `STAFF_NUMBER_OK`), **[12]** segnala un accento scritto
   con l'apostrofo ("piu'", "e'") nei testi dei plugin — valori e commenti YAML, stringhe Java: si
   scrive la lettera accentata (più, è, perché; "po'" resta così). **[13]** segnala un plugin con comandi che non usa la pagina comune `Help` (vedi sopra). Gira anche come **git pre-commit** (`.githooks/pre-commit`, attivo con
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
