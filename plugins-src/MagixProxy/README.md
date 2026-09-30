# MagixProxy

Plugin **Velocity** (non Paper) della rete MagicAdventure. Vive sul proxy, in
`/home/ubuntu/magicadventure/velocity/plugins/`, e ci arriva da solo: il file `deploy.target`
(`velocity`) dice a `deploy-plugin.yml` di copiarlo li' invece che nel faction, e di **non**
riavviare il faction per lui.

## Cosa fa

**Decide UUID e skin di chi entra, una volta sola, alla porta del proxy.** Con le stesse regole
del gate di MagixAuth (`AuthGate.decide`):

| Caso | UUID | Skin |
|---|---|---|
| Nome con un account sul sito (`users.mc_username`) | quello dell'account (`users.mc_uuid`), anche quello premium dei tempi dell'online mode | quella firmata di Mojang, se il nome e' un account premium |
| Nome mai visto, o account con `mc_uuid` storto | quello offline del nome (`OfflinePlayer:<nome>`) | come sopra |
| Database del sito irraggiungibile | — | il giocatore resta fuori con `login.kick-database` |

Perche' qui e non sul server: il proxy e tutti i server dietro devono vedere **lo stesso UUID**.
Se lo cambiasse il server (come fa MagixAuth quando i giocatori entrano diretti), Velocity non lo
saprebbe. Col modern forwarding il server riceve il profilo gia' giusto: MagixAuth vede l'UUID
proposto uguale al suo e non tocca niente, e non richiede la skin a Mojang (e' gia' arrivata).

La **password** resta a MagixAuth sui server: il proxy non chiede niente. Cambiando server, la
sessione di MagixAuth (stesso computer, stesso indirizzo) fa entrare senza ridigitarla.

`profile/MojangLookup` e' una **copia** di quella di MagixAuth (cambia solo il logger, slf4j su
Velocity): una correzione a una va portata anche nell'altra.

## Server principale e altre modalita' (0.2.0)

`network.main_server` (di serie `faction`) e' la porta della rete: il **primo** server di ogni
connessione e' sempre quello, qualunque cosa chieda il client o la lista `try` di velocity.toml.
Li' si fa il login.

Con `network.others_require_login: true` ogni altro server (hub, modalita' future) si apre solo a
chi ha gia' fatto il login: una sessione valida di MagixAuth nel database (`auth_sessions`, stesso
UUID e stesso indirizzo), cioe' la stessa cosa che MagixAuth controlla per farlo entrare senza
password. Prima del login `/server`, i menu o qualunque altro cambio di server lo lasciano dov'e'
con `network.login-first`. MagixAuth resta comunque su ogni server: questa e' la regola che tiene
il giro della rete, non l'unica serratura.

## Chi esce da un altro server torna al principale (0.4.0)

`network.fallback_to_main: true`: chi viene fatto uscire da un server che **non** e' il principale
(si chiude, si riavvia, lo butta fuori) viene portato sul principale con `network.moved-to-main`,
invece di essere scollegato. Oggi hub -> faction; il giorno che il principale sara' l'hub
(`network.main_server: hub`), un riavvio del faction porta tutti sull'hub. Dal principale si esce
dalla rete. Un ban dato sul server che lascia resta valido: il principale lo ferma all'ingresso.

## Cambio server e cookie (0.5.0)

Mentre si entra in un altro server (`/server hub`), MagixAuth di quel server chiede al client il suo
gettone di sessione (un cookie di Minecraft). Il client pero' e' ancora sul server di prima, e la
risposta arrivava li': il server di prima, che non l'aveva chiesta, buttava fuori il giocatore con
"Ricevuti dal client dei dati personalizzati non previsti" (`unexpected_query_response`), e il
nuovo aspettava invano ("Took too long to log in"). Successo il 30/09 al primo `/server hub`.

`network/SwitchCookies`: durante un cambio server (dal `ServerPreConnectEvent` di chi e' gia' su un
server fino a quando il nuovo e' collegato, o fallisce) le richieste di cookie non escono dal proxy.
MagixAuth smette di aspettare dopo `login.cookie_wait_millis` (1,5 secondi) e riconosce il
dispositivo dall'indirizzo, che subito dopo il login sul server principale va sempre. Il primo
ingresso nella rete non cambia: li' il gettone funziona come prima.

## La MOTD della lista server (0.3.0)

Con Velocity davanti al ping risponde il proxy: il server dietro non lo vede nemmeno. MagixProxy
risponde con la **stessa MOTD di MagixEssentials**: legge lo stesso `motd.yml`
(`motd.shared_with`, di serie quello del faction) e la compone con le stesse classi (`motd/MotdText`
e `motd/MotdRotation` sono **copie** di quelle di MagixEssentials: una correzione a una va portata
anche nell'altra). Tendina, "posto libero", versione e icone compresi; il conto dei giocatori e'
quello di tutta la rete. Il file si rilegge da solo quando cambia: la modifica si vede al ping
dopo. Senza icone in `motd.yml` si usa `motd.default_icon` (il `server-icon.png` del faction).
Il `ping-passthrough` di velocity.toml e' spento: non serve.

## Configurazione

- `config.yml` — `database.shared_with` (di serie `../faction/plugins/MagixAuth/config.yml`,
  relativo alla cartella di Velocity): le credenziali del database si leggono **da li'**, cosi' la
  password sta in un file solo. Se quel file non c'e', valgono le righe `database.*` di questo.
  `premium.*`: come in MagixAuth.
- `messages.yml` — i testi per il giocatore. Sul proxy non c'e' MagixLanguage: restano in italiano.

Su Velocity non c'e' `ConfigAlign`: una chiave che manca nel file del server prende il valore
di serie del jar (letto dal jar, non scritto nel codice), quindi funziona anche prima di
comparire nel file.

## Prova

`predisponi-velocity.yml` (con `prova=si`) accende il proxy solo in locale e simula due ingressi:
`SbirTeo` (deve entrare con l'UUID del database del sito) e un nome inventato (UUID offline).
