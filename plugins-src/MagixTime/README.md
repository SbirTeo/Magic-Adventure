# MagixTime

Plugin per **MAGICADVENTURE** (Paper 26.x) che allinea **l'ora di Minecraft all'orologio reale** e
**la stagione al calendario reale**, con piogge, temporali e nevicate che cambiano di conseguenza.

Versione: **0.3.2** — questo file viene riscritto in `plugins/MagixTime/README.md` ad ogni avvio del server.

---

## Cosa fa

**Ora reale.** L'orologio del mondo segue l'ora vera del fuso configurato (`Europe/Rome`): se in
Italia sono le 21:30, in gioco e' notte fonda; a luglio alle 20:00 c'e' ancora luce, a dicembre no.
Un giorno di gioco dura **24 ore reali** invece dei soliti 20 minuti.

**Stagione reale.** La stagione deriva dalla data di oggi (equinozi e solstizi, oppure date scelte da te).
Ogni stagione ha le sue probabilita' di pioggia e temporale e le sue durate.

**Meteo stagionale.** Il ciclo meteo vanilla viene spento e sostituito: d'autunno piove spesso e a lungo,
d'estate raramente ma con temporali violenti, d'inverno la neve si accumula e l'acqua ghiaccia,
in primavera tutto si scioglie.

Tutto e' regolabile da `config.yml` e tutti i testi da `messages.yml`. Ogni modulo (ora / stagioni /
meteo / neve) si puo' **disattivare da solo** lasciando attivi gli altri.

---

## Comandi

Comando principale: `/magixtime` — alias: `/mtime`, `/mxtime`.

| Comando | Cosa fa | Permesso |
|---|---|---|
| `/mtime` o `/mtime info` | Ora reale, ora di gioco, stagione, meteo e tempo al prossimo cambio | `magixtime.use` |
| `/mtime help [pagina]` | Elenco dei comandi, a pagine e cliccabile | `magixtime.use` |
| `/mtime season` | Dettagli della stagione in corso (% pioggia, % temporale, neve, giorni al cambio) | `magixtime.use` |
| `/mtime season set <stagione>` | Forza una stagione ignorando il calendario | `magixtime.admin` |
| `/mtime season set auto` | Torna alla stagione automatica | `magixtime.admin` |
| `/mtime weather <clear\|rain\|storm> [minuti]` | Forza una fase meteo (default 30 minuti) | `magixtime.admin` |
| `/mtime worlds` | Diagnostica: stato reale di ogni mondo gestito | `magixtime.admin` |
| `/mtime sync` | Riallinea subito l'ora di tutti i mondi | `magixtime.admin` |
| `/mtime pause` / `/mtime resume` | Ferma e riprende l'allineamento dell'ora | `magixtime.admin` |
| `/mtime reload` | Ricarica `config.yml` e `messages.yml` a caldo | `magixtime.admin` |

L'elenco dei comandi e' quello comune ai plugin Magix: righe cliccabili che si scrivono da sole in chat,
sezioni, frecce per sfogliare quando serve, e i comandi da staff visibili solo con `magixtime.admin`.
Colori e prefisso seguono `plugins-src/STILE-MAGIX.md`.

### Permessi

- `magixtime.use` — default **true** (tutti possono vedere ora e stagione)
- `magixtime.admin` — default **op**
- `magixtime.bypass` — default **false** (nessuno): esenta dall'intercettazione dei comandi qui sotto

---

## Come funziona l'ora

La conversione e' quella naturale di Minecraft: **tick 0 = 06:00**, 6000 = mezzogiorno,
12000 = tramonto, 18000 = mezzanotte.

```
tick = (secondi_dall_inizio_del_giorno x 24000 / 86400 + 18000) mod 24000
```

Per tenere il mondo allineato il plugin spegne la gamerule `doDaylightCycle` e riscrive l'ora
ogni secondo. A ritmo reale il tempo di Minecraft avanza di **1 tick ogni 3,6 secondi**, quindi
un aggiornamento al secondo e' piu' che sufficiente: il passaggio resta fluido.

### Perche' `doDaylightCycle` e' il punto critico

Finche' quella gamerule resta **attiva**, il client avanza il tempo per conto suo — 20 tick al
secondo — e ogni riallineamento del server lo riporta indietro al valore vero. Il risultato a
schermo e' **il sole e la luna che saltano avanti e indietro senza fermarsi mai**: non e' un
conflitto fra plugin, e' il client che corre e il server che lo richiama.

Il plugin quindi non si limita a spegnerla: **rilegge il valore** per verificare che abbia preso,
la **rimette a `false`** se un altro plugin la riaccende, e se proprio non riesce a spegnerla lo
scrive in console e passa da solo ad allineare **a ogni tick**, cosi' lo scarto scende a 1 tick e
diventa invisibile. Lo stato si controlla con `/mtime info`, che avvisa se la gamerule e' rimasta su.

Due conseguenze da conoscere:

- **I letti non saltano piu' la notte** (e' la stessa gamerule a impedirlo). Chi va a dormire riceve
  un avviso in action bar, personalizzabile con `time.sleep-notify` e la voce `sleep-notice`.
- Il **contatore dei giorni** del mondo avanza di uno ogni giorno reale, alle 06:00. Se vuoi che il
  numero del giorno corrisponda proprio alla data reale c'e' `time.sync-day-counter`, ma su un mondo
  gia' avviato fa un salto secco del contatore: meglio lasciarlo `false`.

### Fuso orario — importante

Il VPS gira su **UTC**, non sull'ora italiana. Senza `time.timezone: "Europe/Rome"` l'orologio di
gioco sarebbe sfasato di 1-2 ore rispetto all'Italia (a seconda dell'ora legale). Il valore accetta
qualunque ID IANA; `system` usa il fuso del sistema operativo.

Con `time.offset-minutes` si puo' spostare l'orologio di gioco rispetto a quello reale: per esempio
`120` fa arrivare la sera due ore prima, utile se la maggior parte dei giocatori entra nel tardo
pomeriggio e si vuole comunque un po' di notte.

---

## Stagioni

`seasons.mode` decide come si calcolano le date d'inizio:

| Modalita' | Primavera | Estate | Autunno | Inverno |
|---|---|---|---|---|
| `astronomical` (default) | 20/03 | 21/06 | 22/09 | 21/12 |
| `meteorological` | 01/03 | 01/06 | 01/09 | 01/12 |
| `custom` | il campo `start` di ogni stagione (formato `MM-DD`) |

Con `seasons.hemisphere: south` le date vengono spostate di sei mesi (a dicembre e' estate).

In modalita' `custom` puoi anche **aggiungere o togliere stagioni**: conta solo il loro `start`,
e il plugin le ordina da solo. Il cambio viene annunciato in chat e con un titolo a schermo
(`seasons.announce`, `announce-title`, `announce-sound`).

Per ogni stagione si regolano:

| Chiave | Significato |
|---|---|
| `display` | Nome colorato mostrato ovunque |
| `rain-chance` | Probabilita' (%) che, finito il sereno, arrivi la pioggia |
| `thunder-chance` | Probabilita' (%) che quella pioggia sia un temporale |
| `rain-min-minutes` / `rain-max-minutes` | Durata in minuti **reali** di una fase di pioggia |
| `clear-min-minutes` / `clear-max-minutes` | Durata in minuti **reali** di una fase di sereno |
| `snow-accumulate` | Durante la pioggia deposita neve e ghiaccio |
| `snow-melt` | Col sereno scioglie neve e ghiaccio |

Valori di partenza: primavera 45% di pioggia, estate 18% (ma il 55% di quelle sono temporali),
autunno 60%, inverno 50% con accumulo di neve attivo.

---

## Meteo

Con `weather.enabled: true` il plugin spegne `doWeatherCycle` e gestisce lui le fasi: alla fine di
ogni fase tira i dadi con le probabilita' della stagione ed estrae la durata della successiva.
Dopo la pioggia si torna sempre al sereno — e' il sereno a decidere se e quando ripiovera'.

- `weather.override-seconds: 120` — se qualcuno cambia il meteo dall'esterno, il cambio **vale** per
  due minuti e poi torna la fase della stagione. `0` = il plugin riprende il controllo subito.
- `weather.persist: true` salva la fase in `data.yml`: dopo un riavvio la pioggia riprende da dove
  era rimasta invece di essere ritirata a caso.
- `weather.announce` (default `false`) annuncia in chat l'inizio di pioggia, temporale e sereno.

---

## Comandi esterni: il cambio vale, poi rientra da solo

MagixTime non fa il braccio di ferro con nessuno — sarebbe proprio quello a far saltare il sole.
Quando qualcuno usa `/day`, `/time set`, `/weather` (di CMI o di vanilla), con
`block-commands.mode: revert` (impostazione di serie) succede questo:

1. **il comando funziona davvero** — diventa giorno, inizia a piovere;
2. parte un **avviso in chat** che dice fra quanti secondi tornera' tutto normale;
3. il plugin **non tocca piu' niente** per `time.override-seconds` (60) o
   `weather.override-seconds` (120);
4. scaduto il tempo, **il sole corre in avanti** a `time.catchup-speed` (60 volte il normale) finche'
   non riaggancia l'ora vera, e il meteo torna alla fase della stagione.

Il recupero va **sempre in avanti**, mai all'indietro: un sole che corre e' naturale, uno che torna
indietro si nota subito. Mezza giornata di scarto si recupera in una decina di secondi.

I comandi intercettati di serie (`block-commands` nel config):

| Modulo | Comandi |
|---|---|
| Ora (`time.enabled: true`) | `/time`, `/ctime`, `/day`, `/night`, `/ptime`, `/cptime`, `/playertime`, `/cmi time`, `/cmi ptime` |
| Meteo (`weather.enabled: true`) | `/weather`, `/cweather`, `/sun`, `/rain`, `/storm`, `/thunder`, `/pweather`, `/cpweather`, `/toggledownfall`, `/cmi weather`, `/cmi pweather` |

Il prefisso di namespace viene tolto da solo, quindi `/minecraft:time` e `/cmi:cmi weather` sono
coperti quanto le forme brevi. Vale **anche dalla console** (`block-commands.console`).

Con `block-commands.mode: block` si torna al rifiuto secco: il comando non fa niente e il giocatore
riceve i messaggi `blocked-time` / `blocked-weather`.

**Come tenere davvero il controllo:**

1. `/mtime weather <clear|rain|storm> [minuti]` — cambio di meteo che dura quanto dici tu;
2. `/mtime pause` — congela l'allineamento dell'ora, tutto il resto continua;
3. `time.enabled: false` o `weather.enabled: false` + `/mtime reload` — spegne il singolo modulo, e i
   comandi corrispondenti tornano liberi;
4. disattivare del tutto il plugin.

Tutto e' configurabile: `block-commands.enabled`, la modalita', la lista dei comandi, il suono e i
testi (`revert-time` / `revert-weather` / `blocked-time` / `blocked-weather` in `messages.yml`).
Il nodo `magixtime.bypass` esiste ma **non ce l'ha nessuno** finche' non lo assegni con LuckPerms;
per non lasciare scampatoie basta mettere `bypass-permission: ""`.

---

## Neve e ghiaccio

**Un limite da conoscere:** se la precipitazione appare come pioggia o come neve lo decide il client
in base alla **temperatura del bioma**, e nessuna API server-side permette di cambiarlo. Il plugin
non puo' quindi "far nevicare" graficamente in un bioma caldo.

Quello che governa e' il **deposito**:

- d'inverno la neve si accumula fino a `snow.max-layers` strati (vanilla si ferma a 1) e l'acqua
  ferma di superficie ghiaccia (`snow.freeze-water`);
- nelle stagioni con `snow-melt` gli strati si assottigliano e il ghiaccio semplice torna acqua.

Difese contro i danni collaterali, tutte nel config:

- `snow.melt-cold-biomes: false` — nei biomi gelidi (taiga innevata, picchi) la neve **non** viene
  sciolta: fa parte del paesaggio, e vanilla la rimette solo mentre nevica.
- `snow.force-anywhere: false` — la neve si deposita solo dove Minecraft la farebbe cadere davvero
  (temperatura del blocco sotto 0.15). Mettendolo `true` nevica ovunque, ma nei biomi caldi il cielo
  continua a mostrare pioggia e vanilla tende a sciogliere subito quello che il plugin posa.
- `snow.skip-blocks` — blocchi su cui non depositare mai la neve (di serie `FARMLAND` e `DIRT_PATH`).
- `SNOW_BLOCK`, `PACKED_ICE` e `BLUE_ICE` non vengono **mai** toccati: quasi sempre sono costruzioni.

Il lavoro e' campionato: a ogni passaggio (`snow.interval-seconds`) vengono valutati
`snow.blocks-per-player` punti a caso entro `snow.radius` blocchi da ogni giocatore online,
**senza mai caricare chunk nuovi**. `blocks-per-player` e' la manopola principale del carico.

> Nota: gli strati di neve vengono posati direttamente, senza passare da un `BlockPlaceEvent`,
> quindi **le regioni WorldGuard non vengono consultate**. Se una zona non deve mai innevarsi,
> mettila in un mondo escluso o abbassa il raggio.

---

## Mondi gestiti

`worlds: ["*"]` significa **tutti i mondi di tipo NORMAL**. Nether ed End vengono sempre ignorati:
non hanno ne' ciclo giorno/notte ne' meteo. Con `excluded-worlds` si tolgono singoli mondi anche
usando `"*"` (di serie `creative` ed `eventi`, se esistono).

### Dimensioni che condividono l'orologio

Su questo server esiste anche il mondo **`spawn`**, che non e' un save a se': e' una **dimensione
custom dentro il save principale** (`world/dimensions/minecraft/spawn`). Le dimensioni dello stesso
save **condividono l'orologio vanilla** con l'overworld: non hanno un tempo proprio.

Il plugin se ne accorge da solo — dopo ogni scrittura rilegge il valore e, se il mondo non l'ha
accettato, lo **toglie dalla gestione dell'ora** annotandolo in console. L'ora giusta gli arriva
comunque, perche' e' la stessa dell'overworld. Senza questo controllo si avrebbero due scrittori
sullo stesso orologio e una raffica di falsi "cambio dall'esterno".

`/mtime worlds` mostra la situazione reale mondo per mondo: ora, giorno, meteo e stato
(`allineato`, `forzato`, `recupero in corso`, `orologio condiviso`).

All'arresto del plugin, se `restore-gamerules-on-disable: true`, `doDaylightCycle` e `doWeatherCycle`
tornano a `true`: cosi' un server riavviato senza MagixTime non resta con il tempo congelato.

---

## Placeholder (PlaceholderAPI)

Registrati automaticamente se PlaceholderAPI e' presente. Utilizzabili in scoreboard CMI, tab, sito, ecc.

| Placeholder | Valore |
|---|---|
| `%magixtime_season%` | Nome colorato della stagione |
| `%magixtime_season_key%` | Chiave della stagione (`winter`, `summer`, ...) |
| `%magixtime_next_season%` | Nome della prossima stagione |
| `%magixtime_days_to_next%` | Giorni reali al cambio di stagione |
| `%magixtime_time%` | Ora reale `HH:mm` |
| `%magixtime_time_seconds%` | Ora reale `HH:mm:ss` |
| `%magixtime_date%` | Data reale `dd/MM/yyyy` |
| `%magixtime_timezone%` | Fuso orario in uso |
| `%magixtime_mc_time%` | Ora di gioco `HH:mm` |
| `%magixtime_mc_ticks%` | Tick del mondo (0-23999) |
| `%magixtime_weather%` | `Sereno` / `Pioggia` / `Temporale` |
| `%magixtime_weather_next%` | Minuti al prossimo cambio di meteo |
| `%magixtime_snow%` | `si` / `no`, se la stagione accumula neve |

---

## Convivenza con gli altri plugin

- **CMI** — i suoi comandi di ora e meteo funzionano, ma il cambio e' temporaneo: vedi la sezione
  "Comandi esterni: il cambio vale, poi rientra da solo". Tutto il resto di CMI e' intatto.
  Verificato che `Time.TimeSpeed` e `Time.AutoTime` di CMI siano disattivati: se li accendi,
  CMI riscrive l'ora per conto suo e i due plugin si contendono il sole.

  **Perche' la modalita' `revert` non e' un vezzo.** CMI ha la transizione *smooth*
  (`Time.AutoTime.Smooth: true`, `SmoothSpeed: 100`): con `/day` non salta all'ora nuova, avvia un
  task che sposta il sole **100 volte piu' veloce del normale, tick dopo tick**, finche' non
  raggiunge le 12:00. Se MagixTime riallineasse l'ora ogni secondo, quel task non arriverebbe mai a
  destinazione: CMI spinge avanti, MagixTime tira indietro, **all'infinito** — ed e' esattamente il
  sole e la luna che vanno avanti e indietro senza fermarsi. Cedendo il passo per
  `time.override-seconds`, la transizione di CMI arriva in fondo indisturbata (7 secondi circa) e
  solo dopo MagixTime rientra con la sua. Nessuno dei due si contende piu' niente.
- **Multiverse** — i mondi caricati a runtime vengono presi in carico da soli: l'elenco dei mondi
  gestiti viene ricalcolato ad ogni passaggio, non solo all'avvio.

---

## File generati

```
plugins/MagixTime/
├── config.yml     tutte le impostazioni
├── messages.yml   tutti i testi mostrati ai giocatori
├── data.yml       fase meteo in corso (solo con weather.persist: true)
└── README.md      questo file, riscritto ad ogni avvio
```

---

## Build

Serve **JDK 25** (`paper-api` 26.1.2 e' compilata per class file 69.0):

```bash
export JAVA_HOME="/c/Users/teolo/.jdks/jdk-25.0.3+9"
export PATH="$JAVA_HOME/bin:$PATH"
mvn package
```

Il jar esce in `target/MagixTime-<versione>.jar`.
