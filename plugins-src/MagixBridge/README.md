# MagixBridge

Il ponte fra i server della rete MagicAdventure e il sito (database `magicadventure_web`). Fino
alla 0.12 si chiamava **MagixWeb**: dalla 0.13.0 gira su **ogni modalita'** (`deploy.target` =
`faction hub`, e le modalita' future).

## Cosa fa

| Funzione | Dove gira |
|---|---|
| Gradi LuckPerms e colore del nome verso il sito (`mc_ranks`), a ogni ingresso e cambio | ogni server (per chi e' li') |
| Lingua del giocatore verso il sito (MagixLanguage) | ogni server |
| Chat pubblica del gioco verso la chat in home del sito (nella scheda di quel server) | ogni server |
| Messaggi scritti sul sito, in chat | il server della scheda in cui sono stati scritti |
| Aureola VIP di MagixCosmetics sul sito | dove c'e' MagixCosmetics |
| Ponte dei placeholder (`%network_<server>_<placeholder>%`) | ogni server |
| Consegna degli acquisti dello store (i comandi della sua modalita') | ogni server |
| Chat vocale di prossimità del sito (stanza `near-<server>` di Voice) | ogni server |
| Traduzione automatica del sito (via MagixLanguage) | solo `network.site-jobs: true` |
| Elenco dei gruppi (`web_groups`), primo accesso dei vecchi giocatori | solo `network.site-jobs: true` |
| Guida per amministratori (raccoglie i `guida-staff.html`) | solo `network.site-jobs: true` |
| Pulizia dello storico della chat del sito | solo `network.site-jobs: true` |

## La chat vocale di prossimità (dalla 0.16.0)

Sul sito, in **Voice** (`/voice`), ogni modalità ha la stanza `near-<network.server-name>`: chi è in gioco
qui ed entra nella stanza sente solo i giocatori vicini. La voce la trasporta il server della voce
(LiveKit, servizio `magix-voce`, vedi `server-voce/` e `predisponi-voce.yml`); questo plugin
(`voice/ProximityVoice`) fa solo i conti:

- `voice.updates-per-second` volte al secondo prende le posizioni dei giocatori che sono anche nella
  stanza (sul thread principale) e, fuori dal thread principale, manda a OGNUNO un pacchetto suo con,
  per ogni vicino, volume (0-1, pieno entro `voice.full-volume-distance`, zero a `voice.hear-distance`)
  e lato (-1 sinistra, 1 destra). Nessuna coordinata esce dal server e nessuno riceve i dati di un altro.
- Lo stesso elenco dice al browser di ognuno a chi lasciar sentire il proprio microfono (i vicini, con
  8 blocchi di margine): lo fa rispettare il server della voce, quindi nemmeno una pagina modificata
  sente chi è lontano.
- Le chiavi del server della voce le legge da `voice.keys-file` (il file del server della voce, mai una
  copia nei config: il repository è pubblico). Le rende leggibili `predisponi-voce.yml` (azione
  `installa`); se non può leggerle scrive «chat vocale di prossimità spenta», riprova da solo ogni minuto e
  si accende appena ci riesce, senza riavvio.

### Fase 3 (dalla 0.17.0): chi parla e sanzioni dal vivo

- **Chi parla, in gioco** (`voice/SpeakingIndicator`, ogni server, `voice.speaking-indicator`): il browser di
  chi parla nella stanza dei vicini lo dice al sito (`api/voice.php`, azione `speaking`, solo se è davvero in
  gioco su quella modalità e senza mute), che lo scrive in `voice_speaking` con 2 secondi di scadenza. Il
  plugin la legge 4 volte al secondo e mostra note musicali sopra la testa (mai su invisibili, vanish,
  spettatori; mai per le stanze di fazione e di rete).
- **Sanzioni dal vivo** (`voice/VoiceModeration`, solo `network.site-jobs: true`, ogni
  `voice.moderation-interval-seconds`): ban e mute attivi di MagixGuard (`punishments`, qualunque ambito) e
  mute di sola voce del gestionale (`voice_mutes`) applicati in tutte le stanze: ban = fuori, mute = senza
  microfono, mute finito = microfono ridato senza uscire.
- Le tabelle `voice_speaking` e `voice_mutes` le crea `db/Database` (e la migrazione
  `2026-10-03-voice-moderazione.sql`).

## La rete

- `network.server-name` — il nome del server (faction, hub...). Senza trattini bassi: e' il primo
  pezzo di `%network_<server>_...%`.
- `network.site-jobs` — `true` su **un solo** server (oggi il faction): i lavori da fare una volta
  sola. All'avvio il log dice "lavori del sito QUI" o "su un altro server".

**I comandi dello store sono divisi per server** (dalla 0.15.0): nel gestionale ogni pacchetto ha
un riquadro di comandi per ogni modalita' di `GAME_SERVERS` (Factions, Hub...). Il sito li salva in
`store_packages.server_commands` (JSON `{"faction": "...", "hub": "..."}`; la vecchia colonna
`commands` resta con quelli del faction) e, a pagamento confermato, li accoda in
`store_command_queue` con la colonna `server`. Ogni server esegue solo le righe col suo
`network.server-name`: un pacchetto dell'hub non tocca il faction. I comandi di un server spento
restano in coda e partono quando riaccende. Due server con lo stesso `server-name` consegnerebbero
due volte. Migrazione: `website/migrazioni/2026-10-03-store-comandi-per-server.sql` (la colonna
`server` della coda la aggiunge anche il plugin all'avvio).

**La chat del sito ha una scheda per server** (dalla 0.14.0, colonna `web_chat.server` =
`network.server-name`): quello che si scrive in gioco finisce nella scheda del server su cui lo
si scrive, e un messaggio scritto in una scheda del sito lo ripubblica in gioco (e lo marca
consegnato) solo quel server. L'elenco delle schede sta nel sito (`GAME_SERVERS` in
`website/includes/helpers.php`): una modalita' nuova va aggiunta anche li'. Migrazione:
`website/migrazioni/2026-09-30-chat-per-server.sql` (la stessa colonna la aggiunge il plugin
all'avvio).

## Il ponte dei placeholder

Ogni modalita' ha i suoi plugin: sull'hub MagixFactions non c'e', e `%magixfactions_faction%` non
vuol dire niente. Il ponte li porta passando dal database del sito:

1. **presenza** — ogni server scrive chi ha online (`network_presence`, riga per giocatore, con
   l'ora: vale per 60 secondi);
2. **pubblicazione** — ogni `bridge.publish-interval-seconds` un server calcola, con
   PlaceholderAPI, i placeholder di `bridge.player-placeholders` per i suoi giocatori **e** per
   quelli connessi sugli altri server (come giocatori offline), piu' quelli di
   `bridge.global-placeholders` (classifiche, totali), e scrive solo i valori cambiati
   (`network_placeholders`). Chi esce lascia l'ultimo valore;
3. **lettura** — ogni `bridge.read-interval-seconds` gli altri server rileggono i valori dei loro
   giocatori e quelli globali; `%network_<server>_<placeholder>%` risponde dalla copia in memoria.

Un placeholder che un server non sa risolvere (il suo plugin li' non c'e') non viene pubblicato:
lo stesso elenco va bene su tutti i server. Esempi sull'hub:

- `%network_faction_magixfactions_faction%` — la fazione del giocatore;
- `%network_faction_magixfactions_top_1_name%` — la prima fazione in classifica;
- `%network_faction_online%` / `%network_online%` — giocatori sul faction / su tutta la rete.

Non ci sono i placeholder relazionali (`%rel_...%`): dipendono da due giocatori sullo stesso server.

## Il vecchio nome

- `%magixweb_namecolor%` ha tenuto il nome di prima apposta: e' nei formati della chat di
  MagixFactions e nei name tag di MagixEssentials, sul VPS.
- La rinomina sul VPS l'ha fatta `deploy-plugin.yml` col file `replaces` (`MagixWeb`): tolto il jar
  vecchio, **copiata** (non spostata) la cartella `plugins/MagixWeb` in `plugins/MagixBridge`.
  La cartella vecchia resta come riserva; la guida per amministratori toglie da sola il capitolo di
  un plugin che non gira piu'.

## Sull'hub

`hub-network-plugins.yml` copia jar e `config.yml` dal faction e mette `network.server-name: hub`,
`network.site-jobs: false`.
