# MagixGuard 0.2.0 - Sorveglianza e sanzioni

Disegno approvato il 2026-08-29. Estende MagixGuard 0.1.0 (profilazione multi-account) fino a
farne **l'unico sistema sanzionatorio** di MAGICADVENTURE, in gioco e sul sito.

Filosofia invariata rispetto alla Fase 1: ogni provvedimento nasce da **prove firmate, leggibili
da chiunque e non ritoccabili dopo**. Quello che il dossier fa per i multi-account, il rapporto
sanzioni lo fa per cheat, chat e AFK.

---

## 1. Decisioni prese

| Tema | Decisione |
|---|---|
| Anticheat | **Grim** (gratuito, open source) dietro uno strato di adattatori; MagixGuard non dipende da lui |
| Anti-xray | `engine-mode 2` nativo di Paper + **modulo statistico interno** a MagixGuard |
| Ban/mute | MagixGuard **prende i comandi standard**; i comandi di moderazione di CMI vengono disabilitati |
| Gioco / sito | Sanzione **unica** con campo **ambito**: `gioco`, `sito`, `entrambi` (predefinito `entrambi`) |
| Automatismo | **Misto** predefinito, **configurabile**: automatico ad alta confidenza, coda di revisione per il resto |
| Progressione | **Punti con decadimento** (dimezzamento ogni 90 giorni, come il LinkScorer) |
| Chat | Spam/flood, insulti, pubblicita', dati personali e adescamento - **tutti e quattro** |
| Filtro chat | Comportamento **diverso per categoria**, con traccia sempre nel rapporto |
| Comportamenti | `/report` dei giocatori + **anti-AFK**. Grief e truffe in-game **non** si toccano: e' survival |
| Anti-AFK | **Niente guadagni da fermo** + **caccia ai dispositivi anti-AFK** (macro, autoclicker, trucchi fisici) |
| Ricorsi | **Modulo privato sul sito + esito pubblico** nella lista |
| Poteri staff | **Tetti di durata per grado** via LuckPerms; oltre il tetto la sanzione diventa proposta |
| Pagina sito | **Lista sanzioni pubblica completa** |
| Regolamento | **Auto-generato** dalla configurazione: catena unica, mai scritto a mano |

---

## 2. Architettura a moduli

```
MagixGuard
+- profilo/        (0.1.0, invariato) sessioni, correlazione, dossier multi-account
+- sanzioni/       IL CUORE: registro, punti, decadimento, scadenze, revoche
|   +- SanctionService     applica, revoca, proroga; unica porta d'ingresso
|   +- PointsLedger        punti per categoria, decadimento, soglie
|   +- EnforcementPolicy   automatico / proposta / misto; tetti per grado
|   +- ScopeResolver       ambito gioco|sito|entrambi
+- fonti/          da dove arrivano le violazioni
|   +- ViolationSource     interfaccia comune
|   +- GrimAdapter         eventi di violazione di Grim
|   +- VulcanAdapter       pronto, non attivo
|   +- CommandAdapter      generico: qualunque anticheat che sa lanciare un comando
|   +- ChatWatcher         spam, insulti, pubblicita', dati personali
|   +- XrayAnalyzer        statistica di scavo
|   +- AfkWatcher          immobilita', macro, autoclicker
|   +- ReportInbox         /report dei giocatori
+- prove/          rapporto ufficiale firmato (estende DossierBuilder)
+- ponte/          scrittura verso il DB del sito + rigenerazione del regolamento
+- comandi/        /ban /mute /kick /warn /storico /sanzioni /report ...
```

**Perche' lo strato adattatori.** Grim potrebbe non avere ancora una build per l'API di Paper 26.1,
e fra un anno potresti volerne un altro. `ViolationSource` fa si' che il sistema sanzioni non ne
sappia nulla: riceve `(giocatore, categoria, gravita', confidenza, dettaglio tecnico)` e basta.

---

## 3. Punti, soglie e categorie

Ogni violazione accredita punti sul giocatore. I punti **decadono**: meta' ogni 90 giorni. Chi ha
sbagliato una volta a marzo non se lo porta dietro per sempre; il recidivo si'.

### Categorie e punti predefiniti (`sanzioni.yml`)

| Categoria | Punti | Ambito | Automatismo |
|---|---|---|---|
| `chat.spam` | 3 | entrambi | automatico |
| `chat.insulti` | 8 | entrambi | automatico |
| `chat.pubblicita` | 25 | entrambi | automatico |
| `chat.dati-personali` | 20 | entrambi | **mai automatico**: alert immediato allo staff |
| `cheat.movimento` | 40 | gioco | automatico solo ad alta confidenza |
| `cheat.combat` | 40 | gioco | automatico solo ad alta confidenza |
| `cheat.xray` | 50 | gioco | automatico solo oltre la soglia estrema |
| `afk.elusione` | 15 | gioco | automatico |
| `report.confermato` | deciso dallo staff | a scelta | mai automatico |

### Soglie

| Punti | Provvedimento |
|---|---|
| 10 | mute 30 minuti |
| 25 | mute 6 ore |
| 50 | ban 3 giorni |
| 80 | ban 30 giorni |
| 120 | ban permanente - **sempre e solo umano** |

`enforcement.auto-max-duration` (predefinito 30 giorni) e' il tetto oltre il quale nessun
automatismo puo' spingersi: la sanzione diventa una proposta in coda di revisione.

`enforcement.mode`: `misto` (predefinito) | `automatico` | `proposta`.

### Dati personali e adescamento

Questa categoria non sanziona mai da sola. Il pubblico e' in larga parte minorenne e un falso
positivo su un numero di telefono e' molto meno grave di uno staff che non viene avvisato: il
messaggio viene bloccato, l'alert parte subito (in gioco + Discord), la decisione resta umana.

---

## 4. Filtro chat: cosa succede al messaggio

| Categoria | Messaggio | Perche' |
|---|---|---|
| Pubblicita' / link | **bloccato** | l'IP di un altro server non deve comparire nemmeno un istante |
| Dati personali | **bloccato** | protezione dei minori |
| Insulti | **censurato** (asterischi), passa | il contesto della lite resta leggibile allo staff |
| Spam / flood | **bloccato in silenzio** | il mittente lo vede, gli altri no: nessuna soddisfazione a chi provoca |

In tutti e quattro i casi il messaggio **originale integro** finisce nel registro delle prove, con
data, canale e i messaggi immediatamente precedenti e successivi come contesto. E' quello che
rende il rapporto una prova utilizzabile e non un'accusa a memoria.

Il dizionario italiano (varianti e offuscamenti: `c4zz0`, `s p a m`) diventa una libreria condivisa:
MagixFactions ha gia' un word filter per i nomi fazione, va estratto in `util/` come le altre
classi comuni ai plugin Magix.

---

## 5. Anti-AFK

Due misure, nessun kick.

**Niente guadagni da fermo.** Dopo `afk.idle-minutes` (predefinito 10) senza input reale, il
giocatore smette di generare valore: niente spawn di mob ostili nel suo raggio, niente drop ne'
XP raccolti, colture e dispositivi vicini non contano ai fini del bottino. Chi resta collegato per
chiacchierare non viene toccato; la farm AFK si spegne da sola.

**Caccia ai dispositivi anti-AFK.** Elusione deliberata, quindi sanzionabile: movimenti a periodo
perfettamente costante (barca in cerchio, pistone che spinge), click a cadenza inumana per durate
impossibili, rotazioni della visuale identiche al millesimo. La prova allegata al rapporto e' la
serie temporale degli intervalli, che si legge a colpo d'occhio anche senza competenze tecniche.

---

## 6. Il rapporto ufficiale

Estende `DossierBuilder`. Per ogni sanzione, un documento in due versioni - interna e pubblica -
firmato SHA-256 e agganciato alla catena di controllo gia' esistente (`/mg verify`).

Contiene: chi, cosa, quando, la categoria e i punti applicati, lo storico dei punti con il
decadimento gia' calcolato, **le prove per esteso** (righe di chat con contesto, violazioni Grim
con i valori misurati, statistiche di scavo, serie temporali AFK) e una sezione finale che
dichiara apertamente i limiti del rilevamento usato.

E' il documento che alleghi al ricorso. Esiste **prima** della decisione, e la firma lo dimostra.

---

## 7. Modello dati

Due schemi sulla stessa istanza MariaDB, con permessi diversi. E' una scelta di sicurezza: se il
sito venisse compromesso, gli IP e le impronte dei client non sarebbero raggiungibili.

**`magixguard`** - solo il plugin
- tabelle 0.1.0 (sessioni, prove, collegamenti, catena) invariate
- `violazioni` - ogni rilevamento grezzo, con fonte, confidenza e dettaglio tecnico
- `punti` - movimenti del registro punti, con data e categoria
- `prove_chat` - righe di chat conservate come prova (retention configurabile, 90 giorni)

**`magicadventure_web`** - scritto dal plugin, letto dal sito
- `sanzioni` - id, uuid, nick, tipo (`warn|mute|ban|kick`), categoria, motivo, ambito, durata,
  inizio, fine, staff, automatica si/no, stato (`attiva|scaduta|revocata`), impronta del rapporto
- `sanzioni_ricorsi` - sanzione, testo, stato (`aperto|accolto|respinto`), risposta, staff, date
- `sanzioni_coda` - proposte in attesa di conferma umana
- `regolamento_sanzioni` - il blocco HTML generato dalla configurazione (vedi 10)

Il sito non scrive mai in `sanzioni`: apre ricorsi e li fa decidere allo staff, il plugin resta
l'unica penna. Cosi' non esistono due verita'.

---

## 8. Comandi, permessi e tetti

`/ban` `/tempban` `/mute` `/tempmute` `/kick` `/warn` `/unban` `/unmute` `/storico <nick>`
`/sanzioni [nick]` `/note <nick> <testo>` `/report <nick> <motivo>` (per tutti)
`/mg coda` (proposte in attesa) e i comandi 0.1.0 restano dove sono.

I comandi di moderazione di CMI vanno **disabilitati** in `CMI/config.yml` e nella sua lista
comandi, altrimenti due sistemi si contendono lo stesso giocatore. **Nessuna migrazione**: CMI non
ha sanzioni pregresse da importare (confermato dall'utente il 2026-08-29).

### Tetti per grado (LuckPerms, gruppo primario)

```yaml
poteri:
  helper:      { mute: 1h,  ban: 0 }
  moderatore:  { mute: 24h, ban: 7d }
  admin:       { mute: -1,  ban: -1 }   # -1 = nessun limite
```

Oltre il tetto la sanzione **non viene rifiutata**: diventa una proposta motivata in coda per il
grado superiore, con tutte le prove gia' allegate. Chi vede il problema non perde il lavoro fatto.

---

## 9. Sito

**`/sanzioni`** - lista pubblica completa: nick con avatar (corona al miglior sostenitore),
tipo, categoria, motivo, durata, staff, data, stato del ricorso. Filtri per tipo/categoria/stato,
paginazione. Nessun riquadro generico: impostazione grafica presa dal gioco, come il resto del sito.

**`/sanzione/<id>`** - dettaglio. L'interessato, se collegato, vede il pulsante **Fai ricorso**;
lo staff vede il rapporto completo. Il ricorso si svolge in privato; quando e' deciso, **l'esito e
la motivazione breve compaiono nella lista pubblica**.

**`/manage.php` -> sezione Sanzioni** - coda delle proposte da confermare con un click, ricorsi
aperti, revoche, ricerca per giocatore.

**Effetti dell'ambito.** Ban con ambito `sito` o `entrambi`: login e forum bloccati con la
motivazione e il collegamento al ricorso. Mute: chat live e nuovi messaggi del forum bloccati. Il
ricorso resta **sempre** raggiungibile, altrimenti la sanzione diventa inappellabile di fatto.

---

## 10. Regolamento auto-generato (catena unica)

Il regolamento del sito **non descrive** le sanzioni: le **rispecchia**, sempre.

```
sanzioni.yml  ->  MagixGuard genera il blocco HTML  ->  magicadventure_web.regolamento_sanzioni
                                                              |
                                    regolamento.php sostituisce il segnaposto [[SANZIONI]]
```

La pagina `regolamento` in `site_pages` continua a contenere la prosa scritta a mano (spirito del
server, buon senso, come si fa ricorso) e in un punto qualsiasi il segnaposto `[[SANZIONI]]`. Alla
resa, `regolamento.php` lo sostituisce con la tabella generata: categorie, punti, soglie, durate,
ambito e decadimento, in italiano leggibile.

La rigenerazione scatta all'avvio del server e a ogni `/mg reload`. Cambi una soglia nella
configurazione, il regolamento pubblico e' gia' allineato.

> **Regola:** la tabella delle sanzioni nel regolamento non si scrive mai a mano. Stessa catena
> unica della guida (`build_tutorial.py` -> `/tutorial`): la fonte e' una sola.

---

## 10-bis. Guida per lo staff

Come il regolamento si genera da solo per i giocatori, la **guida dello staff** si genera da sola
per chi modera: capitolo MagixGuard nella sezione *Guida per amministratori* del gestionale,
riscritto a ogni avvio dal plugin stesso. Meccanismo comune a tutti i plugin Magix, descritto in
[../GUIDA-STAFF.md](../GUIDA-STAFF.md). Il capitolo di MagixGuard e' il piu' importante di tutti:
e' lo strumento che lo staff usa ogni giorno.

---

## 11. Piano di lavoro

1. ~~**Verifica e installazione di Grim**~~ - **fatto il 2026-08-29**: GrimAC `2.3.74-58c8b92`
   (alpha, unico canale che dichiara MC 26.2) installato su mirror locale e VPS, hash verificato.
   Le punizioni predefinite di Grim sono gia' **solo alert e log**: nessun kick, nessun ban.
   Il ponte verso MagixGuard passera' dai **comandi di punizione** di Grim
   (`"40:40 magixguard violazione %player% cheat.movimento %check_name% %vl%"`), quindi il
   `CommandAdapter` e' la strada principale e non dipendiamo dalla sua API.
2. ~~**Nucleo sanzioni**~~ - **fatto il 2026-08-29, versione 0.2.0 in linea.** Registro sul
   database del sito, punti con decadimento, soglie, ambito, tetti per grado via LuckPerms,
   comandi `/ban /tempban /mute /kick /warn /unban /unmute /storico /sanzioni`, blocco
   all'ingresso e in chat, coda delle proposte, esecuzione delle decisioni prese dal
   gestionale, rigenerazione del regolamento pubblico.
   **Resta da fare:** disabilitare i comandi di moderazione di CMI (niente da migrare).
3. ~~**Chat**~~ - **fatto il 2026-08-29.** Quattro categorie con quattro reazioni; il messaggio
   originale e le righe intorno finiscono sempre nelle prove. Riconoscimento sulla forma "nuda"
   (c4zz0 / c a z z o / cazzzzo = stessa parola), confronto su parola intera per non trovare
   parole vietate dentro parole innocenti. Permesso `magixguard.chat.bypass` per lo staff.
4. ~~**Anti-xray e anti-AFK**~~ - **fatto il 2026-08-29.** Anti-xray statistico (resa di minerali
   preziosi ogni 1000 blocchi + minerali rotti mentre erano chiusi), **in sola osservazione** finche'
   le soglie non sono tarate sui dati veri. Anti-AFK: niente spawn/raccolta/esperienza da fermo (solo
   se nel raggio non c'e' nessun giocatore sveglio) e caccia ai dispositivi sulla regolarita' dei
   click. **Resta da fare:** verificare che `anti-xray: engine-mode 2` sia attivo in paper-world.yml.
5. **Sito** - tabelle, pagina pubblica, dettaglio con ricorso, sezione nella dashboard, effetti
   dell'ambito su login/forum/chat.
6. **Regolamento auto-generato** e rapporto ufficiale firmato per ogni sanzione.
7. ~~**`/report`** e coda di revisione unificata~~ - **fatto il 2026-08-29.** Aperto a tutti,
   apre un caso nella stessa coda dei rilevamenti automatici **senza proporre una pena**: nel
   gestionale e' marcato "Segnalazione" e chi lo chiude sceglie provvedimento e durata. Al caso
   sono allegati da soli chi segnala, l'ora, le posizioni dei due e la distanza. Freni contro
   l'abuso: pausa fra due segnalazioni, tetto di casi aperti a testa, motivo di lunghezza minima.

---

## 11-bis. Contratto col sito — gia' in funzione dal 2026-08-29

Il lato sito e' **fatto e in linea**. Il plugin non deve inventarsi niente: deve solo parlare
con queste tabelle di `magicadventure_web` (migrazione `2026-08-29-sanzioni-e-guida-staff.sql`).

**Cosa SCRIVE il plugin**

| Dove | Quando |
|---|---|
| `sanzioni` | a ogni provvedimento applicato: e' l'unica penna che crea righe qui |
| `sanzioni_coda` | quando una violazione non puo' essere decisa da sola (modo `misto`) o supera il tetto del grado |
| `regolamento_sanzioni` (id=1) | all'avvio e a ogni `/mg reload`: il blocco HTML generato da `sanzioni.yml` |
| `guide_staff` | all'avvio: il capitolo della guida per amministratori (via il servizio di MagixWeb) |

**Cosa LEGGE il plugin, perche' lo decide lo staff dal sito**

| Cosa | Come si riconosce | Cosa deve fare il plugin |
|---|---|---|
| revoca decisa nel gestionale | `sanzioni.stato = 'revocata' AND revoca_applicata = 0` | togliere ban/mute in gioco, poi `revoca_applicata = 1` |
| proposta confermata | `sanzioni_coda.stato = 'confermata' AND sanzione_id IS NULL` | applicare la sanzione, scrivere la riga in `sanzioni` e riportarne l'id in `sanzione_id` |
| proposta respinta | `sanzioni_coda.stato = 'respinta'` | niente: la proposta muore li' |

Il giro di lettura puo' essere lo stesso della coda dello store in MagixWeb (qualche secondo).

**Campi che il sito si aspetta popolati**: `mc_uuid`, `mc_username`, `tipo`
(`warn|mute|ban|kick`), `categoria` (i codici di `sanzioni.yml`), `motivo`, `ambito`
(`gioco|sito|entrambi`), `punti`, `inizio`, `fine` (NULL = permanente), `staff_nome` +
`automatica`, `stato`, e — quando c'e' — `rapporto_hash` e `rapporto_pubblico` (il rapporto
gia' mascherato: quello interno non esce mai dal plugin).

**Ambito, in pratica**: il sito fa valere `sito` ed `entrambi` su login, forum e chat live,
tramite `sanzioni_blocco_sito()`; il gioco fa valere `gioco` ed `entrambi`. Il ricorso resta
raggiungibile in ogni caso.

**Pagine gia' in linea**: `/sanzioni` (elenco pubblico), `/sanzione/<id>` (dettaglio + ricorso),
`/manage?section=sanzioni` — **una maschera sola** con, in quest'ordine: Da controllare (la
classifica di rischio), Proposte, Ricorsi, Revoche in attesa, Archivio — e `/manage?section=guida`
(guida per amministratori). Il vecchio `section=rischio` reindirizza li'. Nel regolamento c'e' gia' il segnaposto `[[SANZIONI]]`, in attesa
del blocco generato.

---

## 11-ter. Com'e' fatto il modulo sanzioni (0.2.0)

Package `com.teolo.magixguard.sanzioni`:

| Classe | Cosa fa |
|---|---|
| `Tipo`, `Ambito`, `Durata`, `Sanzione` | il vocabolario: i quattro provvedimenti, dove valgono, le durate scritte a mano (`30m`, `3d`, `permanente`), la riga di archivio |
| `SanzioniConfig` | tutto `sanzioni.yml`. **Va caricato con `SanzioniConfig.carica()`**: le categorie hanno il punto nel nome e col separatore YAML di serie sparirebbero |
| `SitoDb`, `SanzioniDao` | la connessione al database del sito e tutte le query, in un posto solo |
| `RegistroPunti` | punti con dimezzamento, e la soglia raggiunta |
| `Politica` | due domande separate: puo' questa persona? puo' il plugin da solo? Il no non butta via niente, diventa una proposta |
| `ServizioSanzioni` | l'unica porta d'ingresso: applica, fa valere in partita, avvisa. Tiene in memoria i silenziati, perche' la chat non puo' aspettare una query per messaggio |
| `SanzioniListener` | ingresso (ban) e chat (mute) |
| `ComandiSanzioni` | i comandi standard, con gli alias `/mgban` e simili se il nome breve e' occupato |
| `SincronizzaSito` | revoche e proposte confermate dal gestionale, eseguite in partita |
| `Regolamento` | genera il blocco `[[SANZIONI]]` della pagina pubblica |

**Verificato in linea il 2026-08-29:** `/mgban` da console -> riga nel database -> elenco pubblico;
`/mgunban` -> revoca; regolamento rigenerato con categorie e soglie; capitolo della guida pubblicato.

---

## 11-quater. I rilevatori (0.2.1)

| Dove | Classe | Cosa manda al registro |
|---|---|---|
| Chat | `chat/FiltroChat` (+ `Normalizza`, `MemoriaChat`) | `chat.spam`, `chat.insulti`, `chat.pubblicita`, `chat.dati-personali` |
| Scavo | `xray/AnalisiScavo` | `cheat.xray` (solo oltre la soglia estrema, e solo in `modo: attivo`) |
| Fermo | `afk/GuardiaAfk` | `afk.elusione` (solo dispositivi; il "niente guadagni" non sanziona) |
| Anticheat | `sanzioni/ComandoViolazione` | quello che gli passa Grim dal suo `punishments.yml` |

Tutti entrano da `sanzioni/Rilevatore`, che e' l'unico posto dove esistono punti e soglie: i
rilevatori dicono solo **cosa hanno visto**. E' quello che permette di aggiungerne un quinto
domani senza toccare il resto.

**Il registro punti** vive in `sanzioni_violazioni` (creata dal plugin, non serve migrazione).
Una violazione porta punti anche quando non produce nessuna sanzione — senza, il terzo spam di
un giocatore sarebbe identico al primo. Il provvedimento scatta solo sulla soglia **appena
superata**, e revocando una sanzione le sue violazioni vengono **annullate**: se il ricorso e'
stato accolto, quei punti non sono mai esistiti.

**Grim** e' agganciato dal suo `punishments.yml` (9 categorie, backup accanto al file): a soglie
volutamente alte chiama `mgviolazione %player% <categoria> %check_name% (vl %vl%)`. Nessuna
dipendenza dalla sua API ne' dalla sua versione.

**Verificato in linea il 2026-08-29:** `mgviolazione SbirTeo cheat.movimento` -> violazione da 40
punti -> soglia 25 superata -> mute automatico di 6 ore -> `unmute` -> violazione annullata.

---

## 11-quinquies. "Da controllare": la classifica di rischio (sito)

`/manage?section=rischio` mette i giocatori **in ordine di quanto conviene andarli a guardare**,
non di quanto sono colpevoli. Vive tutta sul sito (`website/includes/rischio.php`): non serve
niente di nuovo dal plugin, legge quello che il plugin gia' scrive.

Il punteggio somma tre cose, e accanto si mostra **sempre** da cosa e' fatto:

| Ingrediente | Come pesa |
|---|---|
| Punti delle violazioni | col decadimento del gioco: 16 punti di sei mesi fa diventano 3,7 |
| Segnalazioni aperte | 10 per ogni **giocatore diverso** che ha segnalato, 3 per le successive dello stesso |
| Provvedimenti attivi | ban 20, mute 10 |

**Perche' per segnalatori distinti.** Tre segnalazioni della stessa persona sono una ripicca;
tre persone che segnalano lo stesso nome sono un fatto. Contarle allo stesso modo renderebbe la
classifica manovrabile da chiunque abbia un nemico.

**Il pulsante "controllato"** (tabella `sanzioni_controlli`) toglie quel nome dalla lista per una
settimana e registra chi l'ha guardato, quando e cosa ha visto. Senza, la lista mostra sempre gli
stessi in cima e due membri dello staff controllano due volte la stessa persona. Chi resta
sospetto ha il suo pulsante e non esce dalla lista.

**Non e' un verdetto**: il numero dice dove guardare, mai cosa decidere. Per questo la pagina non
ha nessun pulsante per sanzionare.

---

## 12. Punti aperti

- **Compatibilita' di Grim con Paper 26.1**: da verificare prima di scrivere l'adattatore.
- **Conservazione delle prove di chat**: 90 giorni proposti. Il pubblico e' in larga parte
  minorenne, va dichiarato nella privacy policy del sito come gia' fatto per gli IP.
- **Migrazione delle sanzioni CMI**: quante ce ne sono e se vale la pena importarle tutte.
- **Taratura dell'anti-xray**: le soglie statistiche vanno calibrate sui dati veri del server,
  quindi il modulo parte in sola osservazione per qualche settimana prima di poter sanzionare.
