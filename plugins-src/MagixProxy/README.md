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
