# MagixFactions — Riepilogo completo del progetto

**Plugin custom tipo Factions** per il server **MAGICADVENTURE** (Paper 26.1.2, VPS OVH).
Autore: teolo. Linguaggio: **Java 21** + **Maven** (jar shaded).
Versione corrente: **0.10.2**.

---

## 1. Cos'è

Un plugin "Factions-like" completo: i giocatori creano **fazioni**, salgono di **grado**, stringono
**alleanze**, accumulano **Potenza**, **conquistano territori** (chunk) e li difendono. Storage su
**SQLite** o **MariaDB** (commutabile a caldo con migrazione dati). Testi e configurazione esternalizzati
e ricaricabili con `/mf reload`.

**Percorso sorgente:** `C:\Users\teolo\progettiCLAUDE\magicadventure\plugins-src\MagixFactions`
**Build:** `mvn package` → `target/MagixFactions-<ver>.jar`
**Classe main:** `com.teolo.magixfactions.MagixFactions`

---

## 2. Architettura

Package `com.teolo.magixfactions`:

| Package / classe | Ruolo |
|---|---|
| `MagixFactions` | main (onEnable: hook, DB, manager, listener, comandi, task) |
| `command/FCommand` | dispatcher di tutti i comandi `/f` e `/mf` |
| `manage/FactionManager` | logica fazioni + cache in memoria + persistenza |
| `manage/PowerManager` | Potenza per giocatore (cache + DB) |
| `manage/ClaimManager` | territori (claim) = chunk posseduti, cache + DB |
| `manage/OverclaimManager` | decadimento territori per sovraccarico |
| `db/Database` | DDL + Hikari, SQLite **o** MariaDB (`storage.type`) |
| `db/Migrator` | migrazione generica per-tabella (legge le colonne a runtime) |
| `config/Ranks` | caricamento gradi da config |
| `model/{Faction,Member,Rank,RelationType}` | modelli dati |
| `chat/{ChatChannel,ChatService,ChatListener}` | canali chat (public/faction/ally) |
| `hook/{Econ,Papi,MagixPlaceholders}` | Vault, PlaceholderAPI (consuma e fornisce placeholder) |
| `lang/Messages` | testi da `messages.yml` (+ fallback ai default del jar) |
| `req/Requirements` | motore costi/condizioni (soldi/item/placeholder/permessi) |
| `util/{Colors,WordFilter}` | colori `&`/`&#RRGGBB`; filtro parole vietate |
| `map/{FactionMapRenderer,MapService}` | item Mappa Fazioni (overlay territori) |
| `listener/{PowerListener,TerritoryListener,MapListener}` | eventi Potenza, titoli territorio, persistenza mappa |

---

## 3. Moduli / funzionalità

### Modulo 1 — Fazioni, gradi, membri
- `/f create <nome>` (diventi leader). Nome: **solo lettere/numeri**, lunghezza `faction-name.min/max-length`
  (3–15), massimo `max-digits` cifre (2). Costo di creazione configurabile (soldi/item/placeholder/permesso).
- Leader unico = creatore; `/f transfer`; **successione automatica** se il leader esce (grado più alto, poi
  anzianità); **auto-scioglimento** + territori neutrali se esce un leader solitario.
- **Gradi configurabili** (`config.yml → ranks` + `leader`): ogni grado ha id, nome, tag e permessi.
- Permessi interni di fazione: `invite, kick, promote, demote, relation, claim, unclaim, description, sethome, home, *`.
- `/f promote|demote` (max un gradino sotto il leader), `/f invite|join|kick|leave|disband|info`.
- Limite membri: `members.base` (5) + permesso VIP `magixfactions.members.<n>`.

### Modulo 2 — Relazioni
- Solo **due stati**: **NEMICO** (default: ogni fazione è nemica di tutte) e **ALLEATO**.
- `/f ally|enemy <fazione>` (alias `/f a|e`). L'alleanza richiede **consenso reciproco** (entrambi `/f ally`);
  rieseguendo `/f ally` annulli la richiesta; `/f enemy` rompe l'alleanza.
- Nomi fazione **colorati per relazione** in tutti i messaggi (tua = verde, alleata = viola, nemica = rosso,
  senza fazione = bianco) — colori in `relations.colors`.
- Chat alleati estesa; permesso di grado `relation`.

### Modulo 3 — Potenza & Territori
- **Potenza (per giocatore, INT):** parte da `power.start` (0), da `-max` a `+max` (`power.max`, 10).
  - `+gain-amount` (1) ogni `gain-interval-seconds` (600) **online**;
  - `-death-loss` (4) a ogni **morte**;
  - **decadimento offline** opzionale (`offline-decay.amount` per `hour|day|month`).
  - Potenza di fazione = **somma** dei membri; maxpower di fazione = somma dei loro max.
- **Territori (claim):** `/f claim` conquista il chunk in cui sei (permesso di grado **`claim`**, default leader).
  - Costo = `claims.cost` (come create, default gratis).
  - **Tetto** territori = `floor(maxpowerFazione × claims.max-percent/100)`, default **20%**.
  - Condizioni claim neutrale: `posseduti < tetto` **e** `potenzaFazione > posseduti`.
  - **Overclaim** su nemico: solo se il nemico è **raidabile** (`potenza < territori`) **e** il chunk è sul
    **bordo** (non dall'interno). Non si conquista terreno **alleato**.
- `/f info` mostra riga stato `Territori/Potenza/Powermax` — **verde** se sicura, **rossa** se raidabile.

### Item "Mappa Fazioni" (`/f map`) — dettaglio in `RIEPILOGO-SESSIONE-mappa-item.md`
- Item `FILLED_MAP` dinamico: overlay dei territori sul terreno, colorati per relazione, aree a tinta piena
  con bordo scuro. Si ricentra sul giocatore. Config `map.item.scale` / `map.item.fill-alpha`.
- **Persistente ai riavvii** (v0.10.2): `MapService` marchia l'item e `MapListener` riaggancia il renderer.

### Minimap HUD (sperimentale) — `/mf admin minimap <gioc> on|off`
- Quadro fittizio (ItemFrame) invisibile, montato via ProtocolLib come passeggero del giocatore, con dentro una
  mappa headless (`MapService#createHeadless`/`renderPaletteWithHeader`) ricentrata su di lui. Header magico nei
  primi pixel + shader del resource pack (`rendertype_text` override) che riposiziona il contenuto in un angolo
  fisso dello schermo (architettura v3, vedi Javadoc di `minimap/MinimapManager`).
- Resource pack (shader + logo tablist) registrato — coi propri segnaposto gia' risolti — nel plugin
  **MagixPack** (`resourcepack/ResourcePackContent` + `hook/MagixPackHook`), che lo fonde con quello degli
  altri plugin contributori (es. MagixAuth) e lo costruisce/serve/rende obbligatorio da solo: un client
  applica un solo pacchetto alla volta, quindi da v0.57.0 MagixFactions non lo serve piu' in proprio
  (porta/host/messaggi sono nel config.yml di MagixPack, non piu' in `map.minimap.resourcepack`).
- **Persistente** (v0.14.0): flag per giocatore su colonna DB `players.minimap_on` (`PowerManager#isMinimapEnabled`/
  `setMinimapEnabled`); si riattacca da sola al login e dopo `/reload` (`PowerManager#reattachMinimap`), stesso
  principio della Mappa Fazioni.
- Comandi di debug rimasti utili per diagnosi future: `/mf admin minimapdump` (struttura pacchetto MAP),
  `/mf admin minimaprptest` (pipeline resource pack), `/mf admin minimapmarker` (test shader senza montare il quadro).

### Altre funzionalità
- **Titoli territorio** (`listener/TerritoryListener`): entrando in una zona appare un titolo al centro schermo,
  distinto per relazione (tua/alleata/nemica/**neutrale**). Config `territory-titles`.
- **`/f description <testo>`** (alias `/f desc`): descrizione fazione (max `faction-description.max-length`, 100),
  permesso di grado `description`. Nuove fazioni ricevono `faction-description.default`.
- **`/f sethome` / `/f home`**: home della fazione (devi stare in un tuo chunk), permessi di grado `sethome`/`home`.
  Costo `sethome-cost` configurabile.
- **Filtro parole** (`util/WordFilter`): applicato a nomi e descrizioni; lista `forbidden-words` (editabile),
  case-insensitive, anti-leetspeak, match per sottostringa.
- **Decadimento per sovraccarico** (`OverclaimManager`): se una fazione supera il tetto (es. perde un membro),
  i membri online vengono avvisati (titolo + suono); dopo `grace-hours` (48) perde 1 territorio ogni
  `loss-interval-hours` (24) partendo dai chunk **più esterni** rispetto alla home (la home **non** si perde mai).
  Il timer parte **esattamente** all'evento (uscita/kick) e scorre anche offline.
  > Distinzione confermata: **sovraccarico** (auto-decadimento) è **separato** da **raidabile** (conquistabile dai nemici).

---

## 4. Comandi

### Giocatore (`/f`, alias `/factions`)
| Comando | Descrizione |
|---|---|
| `/f create <nome>` | crea una fazione (diventi leader) |
| `/f invite <gioc>` | invita un giocatore |
| `/f join <fazione>` | entra se invitato |
| `/f claim` | conquista il chunk in cui sei |
| `/f map` | ricevi l'item Mappa Fazioni (dinamico) |
| `/f sethome` / `/f home` | imposta / vai alla home della fazione |
| `/f leave` | esci (se unico leader, scioglie) |
| `/f promote` / `/f demote <gioc>` | cambia grado |
| `/f transfer <gioc>` | cedi il comando (leader) |
| `/f kick <gioc>` | espelli un membro |
| `/f chat [public\|faction\|ally]` | cambia canale chat |
| `/f ally <fazione>` (`/f a`) | chiedi/accetta/annulla alleanza |
| `/f enemy <fazione>` (`/f e`) | rompi alleanza / torna nemici |
| `/f list` (`/f l`) | elenca tutte le fazioni |
| `/f description <testo>` (`/f desc`) | imposta la descrizione |
| `/f info [fazione]` | info fazione |
| `/f disband` | sciogli la fazione (leader) |

### Admin (`/mf`, permesso `magixfactions.admin`, default OP)
| Comando | Descrizione |
|---|---|
| `/mf db info` | backend DB e conteggi |
| `/mf db migrate <sqlite\|mariadb>` | copia i dati nell'altro DB |
| `/mf admin setpower <gioc> <val\|reset>` | imposta la Potenza attuale |
| `/mf admin setpowermax <gioc> <val\|reset>` | imposta il tetto maxpower |
| `/mf reload` | ricarica config, gradi e messaggi |

> **Regola:** ogni sottocomando `/mf admin …` è **solo per admin/OP** (gate `magixfactions.admin` in cima al dispatcher).

---

## 5. Permessi (Bukkit)
- `magixfactions.use` — usare il plugin (default: tutti)
- `magixfactions.admin` — comandi `/mf db`, `/mf admin`, `/mf reload` (default: op)
- `magixfactions.members.<n>` — alza il limite membri del leader (VIP)

> I permessi **di grado** (`claim`, `relation`, ecc.) NON sono permessi Bukkit: sono gestiti da `FactionManager.hasPerm`
> in base a `config.yml → ranks`. `/f map` e `/f claim` non usano permessi Bukkit dedicati.

---

## 6. Configurazione (`config.yml`) — sezioni principali
`storage` · `faction-name` · `faction-description` · `forbidden-words` · `members` · `create-cost` ·
`sethome-cost` · `power` · `claims` · `overclaim` · `map` (`item.scale`, `item.fill-alpha`, `symbols.*-color`) ·
`territory-titles` · `relations` (`max-allies`, `colors`, `names`) · `ranks` + `leader`.

Testi in `messages.yml` ({braces} come placeholder, colori `&`/`&#RRGGBB`). Ricarica: `/mf reload`.

---

## 7. Database
Tabelle: `players` (uuid, name, power, max_power, last_seen, map_rows*), `factions`, `faction_members`,
`claims`, `relations`, `member_permissions`, `overclaim_timers`, `faction_homes`.
Migrazioni idempotenti (`ALTER TABLE … ADD COLUMN` in try/catch). `Database.TABLES` guida la migrazione.
`/mf db migrate` copia tutto tra SQLite e MariaDB.
*(`map_rows` è una colonna **dormiente**, residuo della vecchia mappa in chat.)*

---

## 8. Placeholder (PlaceholderAPI)
`%magixfactions_faction%`, `_factionstot%`, `_rank%`, `_allies%`, `_enemies%`, `_power%`, `_maxpower%`,
`_claims%`, `_maxclaims_fazione%`, `_power_player%`, `_maxpower_player%`,
`%magixfactions_relation_<fazione>%`, relazionale `%rel_magixfactions_relation_color%` (per groupformat CMI).

---

## 9. Documentazione (regola: due doc sempre allineati)
- **README.md** (root progetto) — riferimento tecnico per il **configuratore** (tu).
- **tutorial.html** — guida illustrata per i **giocatori**, generata da `docs/build_tutorial.py`.

Entrambi sono **inclusi nel jar** e **riscritti in `plugins/MagixFactions/` a ogni avvio**
(`writeReadme()` + `writeTutorial()` in `onEnable`). Ad ogni modifica di feature vanno aggiornati **entrambi**.

---

## 10. Build & Deploy
1. `mvn -DskipTests package` → `target/MagixFactions-<ver>.jar`
2. Copia jar nel mirror `magicadventure/server/plugins/` (togli la versione vecchia)
3. `scp` jar (+ `config.yml`/`messages.yml` se cambiati) → VPS `ubuntu@141.94.123.249:/home/ubuntu/magicadventure/plugins/…`
4. Rimuovi il jar vecchio sul VPS
5. Riavvio: `screen -S mc -X stuff "stop\r"` → `start.sh` riavvia → attendi `Done (`

Note shade: **non rilocare `org.sqlite`** (JNI nativo); Hikari/MariaDB rilocati sotto `com.teolo.magixfactions.lib.*`.

---

## 11. Cronologia versioni (sintesi)
- **0.1.0** Modulo 1 (fazioni, gradi, chat, limiti).
- **0.2.x–0.3.x** Modulo 2 (relazioni ally/enemy con consenso), nomi colorati, placeholder.
- **0.4.0** Modulo 3 (Potenza & Territori), `/factions`, `/f list`, README auto-generato.
- **0.5.0–0.6.x** `/f map` (chat) + titoli territorio (enter-based, incl. neutrale), `/f description`.
- **0.7.x** descrizione default, regole nome, filtro parole, polish `/f info` (lista membri, alleati con hover).
- **0.8.0–0.9.x** decadimento sovraccarico + home fazione; `/mf admin setpower/setpowermax/setmap`; mappa quadrata,
  simboli/colori/legenda configurabili; timer al minuto d'evento, persistente offline. Rimozione permessi
  `maxpower.<n>`, `command.map`, `command.claim`.
- **0.10.0** `/f map` diventa **item mappa dinamico**; rimozione mappa-chat + `/mf admin setmap`.
- **0.10.1** leggibilità mappa (tinta piena, bordo scuro, zoom CLOSE).
- **0.10.2** persistenza del renderer mappa ai riavvii (`MapService` + `MapListener`).
- **0.11.0–0.13.0** minimap HUD sperimentale: quadro fittizio montato via ProtocolLib, shader di
  riposizionamento via resource pack auto-servito, comandi di debug a fasi (`minimaptest`/`minimaprptest`/`minimapmarker`/`minimapdump`).
- **0.14.0** comando definitivo `/mf admin minimap <gioc> on|off`; persistenza (`players.minimap_on`) e
  riattacco automatico al login/`reload` (`PowerManager#reattachMinimap`), stesso principio della Mappa Fazioni.
- **0.15.0–0.16.0** minimap: frequenza render configurabile, avatar volto (skin) al posto della freccia +
  indicatore direzione + N/S/O/E, invalidazione live del terreno su piazza/rompi blocco, ogni giocatore online
  visibile con bordo colorato per relazione; fix cornice/depth-fighting/direzione da feedback in-game.
- **0.17.0** zoom `/f map` esteso con **CLOSER** (0.5 blocchi/pixel, piu' vicino del vanilla "closest" —
  `MapContentBuilder`/`FactionMapRenderer`/`MapService` ora usano un `double` blocchi-per-pixel invece di
  `MapView.Scale`, che supporta solo potenze di 2); simbolo **home** della propria fazione al centro del
  chunk, su entrambe le mappe (`map.home-marker`).

---

## 12. Roadmap / in arrivo (differiti)
- **Protezione territori** (blocchi/contenitori/PvP nei claim) + `/f unclaim` / `/f claims`.
- **GUI** per permessi di grado e singoli membri.
- **Banca/economia** di fazione, **classifiche**, **missioni**.
- Pulizia residui: colonna `players.map_rows` e metodi `getMapRows/setMapRows` (dormienti).
- Scala mappa **per-giocatore** (in valutazione: `MapService.create(Player, Scale, String)`).
