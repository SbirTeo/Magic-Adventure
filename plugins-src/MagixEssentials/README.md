# MagixEssentials

Plugin per **MAGICADVENTURE** (Paper 26.x) che raccoglie le **utilita' di base** del server: quelle
cose che non appartengono a nessun gioco in particolare ma che ci sono sempre. Oggi ne fa otto —
il **tablist**, la **MOTD**, il **nametag**, la **chat**, il **filtro dell'autocompletamento**, le **valute**, gli **oggetti fissi dell'inventario** e la **lobby dell'hub** — e
a lungo andare dovrebbe assorbire cio' che oggi fa CMI.

Il server sta dietro **Velocity**, con piu' backend (hub, factions...): questo jar gira su ognuno,
con la propria cartella dati e il proprio `modules.yml`.

Versione: **0.12.1**

---

## Come e' organizzato: moduli, come in CMI

Accendere una funzione e regolarla sono due gesti diversi, fatti in momenti diversi. Qui stanno in
file diversi:

| File | A cosa serve |
|---|---|
| `modules.yml` | L'elenco delle funzioni, una riga ciascuna: **acceso o spento**. Si apre questo per sapere che cosa sta facendo il plugin. |
| `tablist.yml` | Come e' fatto il tablist: intervallo, intestazione, fondo, nomi, caselle fisse. |
| `motd.yml` | Come e' fatta la MOTD: le varianti e come ruotano, la tendina, il conto dei giocatori, le icone. |
| `nametag.yml` | Com'e' fatta la targhetta sopra la testa: le righe, chi la disegna, altezze, quando sparisce. |
| `chat.yml` | Il formato della chat pubblica (grado, nome, fazione) e dei messaggi che arrivano dal sito. |
| `currencies.yml` | Le valute create dallo staff: un catalogo, non uno schema fisso — una voce per valuta. |
| `customjoinitems.yml` | Gli oggetti fissi dell'inventario (sezione `items`, un catalogo: una voce per oggetto), quando e come si danno e le regole generali (buttare, raccogliere, rompere...). |
| `hub-lobby.yml` | Le regole della lobby dell'hub: per ora, lo spawn a ogni ingresso. |
| `config.yml` | Solo cio' che vale per il **plugin intero**: oggi il database delle valute condivise. |

Il filtro dell'autocompletamento non ha un file suo: l'interruttore `tabcomplete` in `modules.yml`
e' tutto quello che c'e' da regolare — vedi sotto.

Una funzione spenta non parte affatto: niente task, niente aggancio agli eventi. Il suo file resta
dov'e', intatto, e torna in uso appena la si riaccende — spegnere non e' buttare via la
configurazione.

I file sul server si tengono aggiornati da soli (`util/ConfigAlign`): a ogni avvio e a ogni
`/magixessentials reload` le chiavi nuove compaiono al loro posto col loro commento, le rinomine
dichiarate si applicano portandosi dietro il valore scelto, e le righe che il codice non legge piu'
spariscono — con una copia di scorta in `.bak/MagixEssentials/` (fuori da `plugins/` sul server),
prima di ogni scrittura.

**Per aggiungere una funzione**: una riga in `modules.yml`, un `<funzione>.yml` accanto, e la classe
che la gestisce riceve quel file nel costruttore. Non c'e' nessun elenco da aggiornare a mano: i
file nuovi il plugin li crea e li allinea da solo.

---

## Tablist

La lista giocatori del tasto Tab: intestazione, fondo e nome dei giocatori, riscritti a intervalli
regolari e a ogni ingresso. Supporta i colori `&` e `&#RRGGBB`, i tag di MiniMessage (`<bold>`,
`<gradient:#C046E8:#A8DC2C>`, `<rainbow>` — stesso motore di MOTD e nametag, `util/TextFormat`), i
placeholder di PlaceholderAPI (ricalcolati per ogni giocatore: ping, fazione, coordinate) e il
segnaposto `{logo}`, che diventa il carattere del logo nel resource pack di MagixFactions.

**Sfumature animate.** `<gradient:...>` e `<rainbow>` accettano un ultimo numero, la fase: cambiarlo
nel tempo la fa scorrere. Il plugin lo calcola da solo — un giro ogni `animation-period-seconds`
(default 4s) — e lo sostituisce a due segnaposto, uno per tag perche' i due numeri non sono
compatibili: `{gradient-phase}` (decimale, un dente di sega da -1.0 a 1.0 che poi ricomincia da
-1.0: verificato facendo disegnare a MiniMessage la sfumatura a fase -1.0 e a fase 1.0, il colore
che esce e' IDENTICO, quindi il punto di ripartenza e' gia' continuo da solo, un giro sempre nello
stesso verso) e `{rainbow-phase}` (intero 0..9, qui basta contare perche' l'arcobaleno e'
gia' ciclico). Funzionano solo dentro quei due tag: `<gradient:#C046E8:#A8DC2C:{gradient-phase}>`,
`<rainbow:{rainbow-phase}>`. Le caselle finte (`fixed-slots.empty-text`) non animano: sono profili
costruiti una volta sola all'avvio, non righe ricalcolate a ogni giro.

**Banda piu' stretta: `<rainbow-xN>`/`<gradient-xN:colori>`.** Un tag solo fa un giro di colori
largo quanto tutto il testo dentro; per farlo ripetere (bande piu' strette) servirebbe spezzare il
testo a mano in piu' tag identici. Scorciatoia: `<rainbow-x3>Testo</rainbow-x3>` o
`<gradient-x3:#C046E8:#A8DC2C>Testo</gradient-x3>` — il plugin spezza "Testo" in altrettanti pezzi
(il piu' possibile uguali, i caratteri in avanzo vanno ai primi) e genera da solo i tag veri, gia'
con la fase dentro. Non serve scrivere `{gradient-phase}`/`{rainbow-phase}` a mano in questo caso:
ci pensa il plugin. Un numero piu' alto di quante lettere ha il testo si accorcia da solo (non ha
senso fare pezzi piu' piccoli di un carattere).

**Un `update-interval-ticks` basso (per un'animazione fluida) NON rimanda anche le 80 slot finte.**
Sono due cadenze separate: intestazione/fondo/nome seguono `update-interval-ticks`, le slot finte
si rimandano al massimo una volta al secondo per conto loro, fisso. Prima erano la stessa cosa:
abbassare `update-interval-ticks` per un'animazione faceva rimandare anche il pacchetto ProtocolLib
da 80 voci alla stessa velocita' (10-20 volte al secondo per giocatore online) — la cosa piu' pesante
di questo modulo, ed era quello a far scattare e bloccare il tablist, non l'animazione in se'
(segnalato dall'utente). Le slot finte non hanno bisogno di piu' di un rinvio al secondo: il loro
contenuto non cambia da solo.

**Il logo non e' testo, e' un'immagine**: la sua altezza non spinge giu' da sola le righe che
vengono dopo, quindi servono delle righe VUOTE sotto `{logo}` per non farci scrivere sopra le
informazioni. Quante: `(height - ascent) / 9`, arrotondato per eccesso — `height`/`ascent` sono in
MagixFactions (`config.yml -> tablist.logo`, in pixel), 9 e' l'altezza di una riga di testo normale.
Con `height: 78, ascent: -8` (i valori di ora) servono almeno 10 righe; l'header qui ne tiene 11. Se
quei due numeri cambiano, il conto va rifatto — altrimenti il logo torna a coprire le informazioni.

**L'ordine dei giocatori.** Da solo il gioco mette avanti chi non ha una squadra (scoreboard team)
e ordina per nome, non per grado. Con `sort-by-rank-weight` acceso i giocatori VERI vanno sempre
davanti a tutto — caselle finte comprese, sempre in fondo — ordinati fra loro dal **peso piu' alto
al piu' basso** del gruppo LuckPerms, lo stesso che decide `%magixweb_namecolor%`. E' il campo
**Priority** del protocollo (Paper lo chiama `player list order`): vince prima di squadra e nome.
Softdepend: senza LuckPerms la chiave non fa niente, e lo dice nel log una volta sola.

**Le 80 slot fisse.** Il gioco decide da solo quante colonne disegnare in base a quante voci ci
sono: con pochi giocatori il tab e' una colonna sottile, con tanti si allarga. Con `fixed-slots`
acceso il tab mostra sempre lo stesso numero di caselle, riempiendo con voci decorative **senza
testa** (skin trasparente, verificata pixel per pixel — non solo scritta) e **senza icona di
connessione**: latenza `-1` (negativa apposta — nel protocollo vuol dire "non ancora nota", ed e'
semanticamente quello che una casella finta e': una connessione che non esiste), che il client
disegna con l'icona "connessione sconosciuta" (`ping_unknown.png`). Quell'icona e' l'unica delle
sei del protocollo che un giocatore VERO non puo' mai avere davvero, quindi l'unica che si puo'
rendere trasparente nel resource pack di MagixFactions senza spegnere anche la barra di qualcun
altro — le 5 barre vere (giocatori veri) non si toccano. Richiede ProtocolLib; se manca, la
funzione si spegne da sola e resta il tablist dinamico. Richiede anche che il client abbia
scaricato il resource pack di MagixFactions: chi ha il permesso di bypassarlo vede ancora l'icona.

**Le colonne sono larghe quanto un nickname vanilla puo' esserlo, non strette e non a tutto
schermo.** Verificato decompilando `PlayerTabOverlay.extractRenderState` nel client vanilla reale
di questa versione: il gioco sceglie UNA sola larghezza per tutte le colonne, quella del nome PIU'
LARGO fra le 80 voci (vere e finte insieme). Un nome finto corto (una casella vuota e' quasi sempre
solo uno spazio) tiene quindi le colonne strette quanto il nome vero piu' corto in lista — non
quanto un nome vero potrebbe davvero essere. Un nickname di Minecraft e' lungo al massimo 16
caratteri, e nel font di gioco nessuna lettera/cifra/underscore valida in un nickname avanza piu' di
6 pixel (verificato decompilando `BitmapProvider` e rifacendo lo stesso calcolo sul vero
`ascii.png`): il nickname vanilla piu' largo possibile e' quindi 16 × 6 = 96 pixel, mai di piu'. Il
plugin aggiunge da solo 24 spazi invisibili in coda al testo di ogni casella vuota
(`FixedSlots.WIDTH_PADDING`, 4 pixel di avanzamento l'uno = 96 in tutto, verificato nel vero
`assets/minecraft/font/include/space.json` del client): non si vedono, ma pareggiano esattamente il
nickname vanilla piu' lungo possibile — ne' uno stretto quanto un nome corto, ne' uno che sfonda lo
schermo.

**Cosa NON si puo' nascondere.** Dietro ogni voce del tablist — vera o finta — il client disegna
sempre un rettangolo semitrasparente largo quanto la colonna: non e' una texture del resource pack,
e' un `fill()` scritto nel codice del client (stesso `PlayerTabOverlay`), quindi non dipende da
niente che il plugin manda nel pacchetto e non si puo' spegnere per le sole caselle finte senza
spegnerlo anche per i giocatori veri. Il colore lo decide un'opzione **del client di chi guarda**
(la stessa usata per lo sfondo del testo in chat), non il server: chi lo vuole invisibile lo spegne
da solo (Opzioni → Chat → Trasparenza sfondo chat a 0) — sparisce per tutte le voci, non solo per
quelle finte.

Sia la testa che l'icona possono marcire **senza un errore nel log**: un link a una skin che smette
di rispondere non lancia un'eccezione, fa solo riapparire la skin di serie (Steve/Alex) — e' successo
per davvero, l'hash di prima era morto da chissa' quanto. Il sintomo e' silenzioso: se le teste
tornano visibili, si verifica scaricando l'URL dentro `TRANSPARENT_TEXTURE` (un base64 di una riga)
invece di controllare il log, che li' non dira' niente. Se torna visibile l'icona di connessione
sconosciuta, il sospetto e' il resource pack di MagixFactions (file mancante o non ricaricato dai
client — serve un riavvio, non basta un reload).

Il campo del pacchetto in cui finiscono le voci **non e' un indice scritto a mano**: si scrive
nell'ultimo campo che accetta l'elenco, partendo dal fondo. L'indice fisso (era `1`) ha smesso di
esistere a un aggiornamento del gioco — `Field index 1 is out of bounds for length 1` — e la funzione
si spegneva da sola a ogni avvio. Nel log le righe sono due: **pronte** quando i profili esistono,
**attive** al primo invio riuscito; se c'e' solo la prima, il pacchetto non e' partito e accanto c'e'
il motivo.

**Il tablist ce l'ha chi scrive per ultimo.** Se anche CMI lo gestisce, i due si sovrascrivono a
vicenda: `priority` ci fa riscrivere poco dopo di lui, ma le sue caselle finte non si tolgono da
qui. La via pulita resta spegnere il suo modulo (`plugins/CMI/Settings/Modules.yml` →
`tablist: false`): con `cmi.disable-module: true` (di serie) lo fa il plugin da solo all'avvio, con
la copia di scorta in `.bak/`, e vale dal riavvio dopo.

**L'avatar nel nome.** Di serie `player-name` comincia con `%magixpack_glyph_avatar%` (la faccia
della skin, da MagixPack) e `%magixpack_shift_2%` (2 pixel d'aria prima del nome). L'avatar arriva
coi colori `§x...`, uno per pixel: in una riga coi codici `&` va bene così, in una riga coi tag
MiniMessage ogni placeholder si risolve da solo e i suoi colori diventano tag
(`TextFormat.legacyToTags`), altrimenti resterebbero scritti a schermo. Nome e intestazione si
rimandano solo quando cambiano (più un ripasso forzato al secondo contro chi li riscrive): con
`update-interval-ticks` basso non si manda a tutti lo stesso avatar a ogni tick.

---

## MOTD

Le due righe che si leggono nella lista server prima di entrare, l'icona, il numero dei giocatori e
la tendina che esce passandoci sopra col mouse. Sostituisce il vecchio plugin **CustomMOTD**, che e'
stato tolto dal server: due plugin sulla stessa MOTD se la strappano di mano.

**Le MOTD** stanno tutte in `messages`, una voce ciascuna. Quale si vede lo decide `selection`:

| `selection` | Cosa fa |
|---|---|
| `random` | una a caso; con `avoid-repeat` non esce due volte di fila la stessa |
| `ordered` | una dopo l'altra, dalla prima all'ultima e poi daccapo |
| `fixed` | sempre la prima; le altre restano nel file, pronte |

`change-every-seconds` dice ogni quanto cambia: a 0 cambia **a ogni ping** (ogni volta che qualcuno
apre la lista), altrimenti resta la stessa per tutti dentro quella finestra. Il ping arriva spesso e
in modo irregolare: cambiare a ogni ping fa ballare la MOTD sotto gli occhi di chi tiene la lista
aperta.

**Come si scrive una riga** — due modi, uno *o* l'altro nella stessa riga:

- **codici classici**: `&a`, `&7`, `&l`, e `&#RRGGBB` per l'esadecimale;
- **tag** (MiniMessage): `<bold>`, `<color:#C046E8>`, `<rainbow>`, e soprattutto
  `<gradient:#C046E8:#A8DC2C>TESTO</gradient>` per le **sfumature**.

Si riconoscono dai triangoli: se in una riga c'e' un tag, quella riga viene letta come tag e le `&`
restano scritte. Un tag scritto male non fa sparire la MOTD — resta scritto com'e', e si vede subito.

**Come si va a capo** — `\n` dentro le virgolette doppie, oppure un blocco `- |` con le righe sotto
(piu' leggibile quando sono lunghe). Il client ne disegna **due**: la terza viene tagliata.

**Segnaposto**: `{online}`, `{max}`, `{version}`. Non ce ne sono per-giocatore e non possono
essercene: al ping il server non sa CHI sta guardando. Per lo stesso motivo PlaceholderAPI qui non
c'entra.

**La tendina** (`hover`) prende il posto dell'elenco dei giocatori online. Li' il protocollo non
vuole componenti ma nomi, quindi le righe vengono riscritte nei codici `§` che il client capisce:
funziona tutto, sfumature comprese — ma una sfumatura colora *una lettera alla volta*, e una riga di
trenta lettere diventa una stringa di centinaia di caratteri. Tienila per una riga sola.

**Il numero dei giocatori**: `player-count.max` cambia il numero mostrato; `player-count.extra` e' il
vecchio trucco del posto sempre libero (massimo = online + N, e il server non sembra mai pieno);
`player-count.hide` lo nasconde del tutto. Nessuno dei tre fa entrare un giocatore in piu': il limite
vero resta quello del server.

**L'icona**: con `icons` si mettono piu' PNG **64x64** nella cartella del plugin, e ruotano con la
stessa regola delle MOTD. Uno che manca o che non e' 64x64 viene saltato, col motivo nel log.

**La versione**: `version.text` si vede solo dai client non compatibili; `version.always-show` lo
mostra a tutti, ma fa apparire il server come non compatibile (barra rossa, niente conto dei
giocatori). Si entra lo stesso, ma spaventa.

### Quando davanti ci sara' Velocity

**Fatto (30/09):** la MOTD dal proxy la scrive `MagixProxy` (0.3.0), che legge **questo stesso
`motd.yml`** del faction e lo compone con le copie di `MotdText` e `MotdRotation` (vedi il suo
README). Si continua a modificare qui: vale sia per chi entra diretto sia per chi passa dal proxy.
MagixEssentials e' anche sull'hub (name tag, completamento dei comandi), con config suoi.
Sotto, il ragionamento di allora.

#### Il ragionamento

La MOTD la scrive **chi risponde al ping**. Oggi risponde il server, perche' il client ci parla
diretto. Con un proxy **Velocity** davanti, al ping risponde il proxy: il server dietro non lo vede
nemmeno, e un plugin del server non puo' farci niente. Non e' un limite di questo modulo, e' come
funziona il protocollo.

Il codice e' gia' diviso in vista di quel giorno:

| Classe | Cosa fa | Dipende da |
|---|---|---|
| `motd/MotdText` | **Come si compone** la MOTD: segnaposto, colori, tag e sfumature, le due righe, la tendina | solo Adventure (MiniMessage compreso) — niente Bukkit |
| `motd/MotdRotation` | **Quale voce adesso**: random / ordered / fixed, ogni quanto cambia, niente ripetizioni | niente — solo Java |
| `motd/MotdListener` | **Chi ascolta il ping** e ci mette dentro il risultato, piu' icona e conto | Paper (`PaperServerListPingEvent`) |

Adventure (`net.kyori.adventure`) ce l'hanno **sia Paper sia Velocity**, e i `Component` sono gli
stessi. Quindi, quando arrivera' il proxy, la strada e': un plugin Velocity che legge un `motd.yml`
con lo **stesso formato**, chiama gli **stessi** `MotdText` e `MotdRotation` e mette il risultato nel `ProxyPingEvent`
invece che nel `PaperServerListPingEvent`. Di nuovo c'e' solo il listener, una trentina di righe.

Due cose da decidere quel giorno, non prima:

1. **Un jar solo o due.** Un jar puo' portarsi dentro sia il `plugin.yml` di Paper sia il
   `velocity-plugin.json` di Velocity: ciascuna piattaforma legge il suo e ignora l'altro. In
   alternativa, un plugin Velocity a parte che si porta dietro la copia di `MotdText` — che e' poi
   il trattamento che in questo repo hanno gia' le classi comuni (`ConfigAlign`, `StaffGuide`),
   controllate da `check_config.py` perche' restino identiche.
2. **Chi legge il file.** Su Velocity non c'e' il `YamlConfiguration` di Bukkit: il file lo legge la
   piattaforma e passa i valori a `MotdText`, che i valori li prende gia' cosi' (stringhe, liste,
   numeri) e non sa da dove vengano.

Regola pratica: se in `MotdText` compare un `import org.bukkit`, quella strada si e' chiusa.

---

## Nametag

La targhetta che si legge **sopra la testa** dei giocatori, in gioco: non il tablist, non la chat.
Le righe stanno in `lines`, dall'alto verso il basso, e **l'ultima e' quella del nome** — e' li' che
va `{name}`. Valgono i codici `&` e `&#RRGGBB`, i tag MiniMessage (`<gradient:...>`) e **tutti** i
placeholder di PlaceholderAPI: quindi anche tutti quelli dei plugin Magix, che di PAPI sono
espansioni, e i **relazionali** `%rel_...%`.

### Una modalita', uno stile

Lo stesso jar gira su server di **modalita' diverse**, e una targhetta che parla di fazioni sarebbe
sbagliata su tutti gli altri. Percio' di fabbrica `lines` e' **vuota** e le righe le decide uno
**stile**: `styles` e' un elenco, uno per modalita', e ogni voce dichiara in `requires` i plugin che
le servono.

```yaml
lines: []          # scritta a mano vince su tutto; vuota = decide lo stile
style: auto        # il primo stile i cui plugin ci sono TUTTI (o il nome di uno, per imporlo)
styles:
  - name: factions
    requires: [MagixFactions]
    lines: ['&8[&d%magixfactions_faction%&8]', '%magixweb_namecolor%{name}']
  - name: plain
    requires: []                       # non chiede niente: ultima spiaggia, percio' sta in fondo
    lines: ['%magixweb_namecolor%{name}']
```

`requires` e' una **E**, non una O: lo stile vale solo dove ci sono **tutti** i plugin elencati —
`[MagixFactions, BedWars]` significa «solo dove ci sono tutti e due insieme», e «fazioni *oppure*
bedwars» sono **due voci**, una per modalita'. E fra i requisiti vanno solo i plugin **senza cui lo
stile non ha senso**, non tutti quelli che compaiono nei suoi segnaposto: `MagixBridge`
(`%magixweb_namecolor%`) non ci va — se manca, il nome si vede comunque, solo senza colore, mentre
metterlo li' butterebbe via tutto lo stile, fazione compresa, per una questione di colore.

Con `style: auto` la **rilevazione della modalita'** non e' un indovinello sul nome del server: e'
quali plugin sono **caricati** (non «gia' accesi»: l'ordine di accensione non e' garantito, e uno
stile scartato perche' il suo plugin parte un istante dopo di noi sarebbe un guasto senza errore). L'ordine conta (vince il primo che va bene) e lo stile scelto
finisce nel **log all'avvio**, insieme a quante righe sono e a chi le disegna — se sopra la testa non
si vede quello che si aspettava, la risposta e' li'.

**E su un server di un'altra modalita'?** Oggi, senza uno stile per quella modalita', vale `plain`:
il nome e basta. Per darle la sua targhetta ci sono due strade, e la prima e' quasi sempre quella
giusta: scrivere le righe in `lines` **su quel server** (ogni server ha il suo file), oppure
aggiungere una voce a `styles` sopra quella senza requisiti. Gli stili sono un **elenco** e non delle
chiavi, e la differenza conta: `ConfigAlign` toglie le chiavi che il jar non conosce, mentre le voci
di un elenco le lascia stare — quindi una modalita' nuova non aspetta una versione del plugin. Il
rovescio della stessa medaglia: uno stile aggiunto da un aggiornamento **non compare da solo** in un
`nametag.yml` che esiste gia' (su un server nuovo si', perche' il file nasce dal jar).

**I segnaposto di un plugin che non c'e' restano vuoti**, non scritti a schermo: su un server senza
fazioni `%magixfactions_faction%` non diventa spazzatura sopra la testa della gente, e se la riga
resta senza niente da leggere `skip-empty-lines` non la disegna nemmeno. Cioe' la decorazione di una
modalita' sparisce da sola dove quella modalita' non esiste. Nel log si dice una volta per segnaposto,
perche' sparire in silenzio e' comodo oggi e un mistero domani.

**Due maniere di disegnarla, e nessuna vince sempre** — lo decide `mode`:

| `mode` | Chi disegna | Cosa si guadagna | Cosa si perde |
|---|---|---|---|
| `vanilla` | il gioco, con le squadre dello scoreboard | costa quasi niente, sfuma con la distanza, sparisce da sola quando uno si accuccia, e **puo' essere diversa per chi guarda** | **una riga sola**, e il nome vero accetta solo i **16 colori** storici |
| `display` | noi, con entita' di testo agganciate al giocatore | **piu' righe**, colori esatti e sfumature anche sul nome, misura e altezza regolabili | e' un **oggetto del mondo**: lo vedono tutti uguale |
| `auto` | il gioco con una riga sola, noi da due in su | la scelta giusta senza pensarci | — |

**Targhetta diversa per chi guarda.** Il verde dell'alleato e il rosso del nemico non sono una
proprieta' di chi viene guardato: dipendono da **chi guarda**, e in PlaceholderAPI sono i segnaposto
relazionali (`%rel_magixfactions_relation_color%`). `per-viewer` li accende — `auto` da sola se in
`lines` ce n'e' almeno uno — e funziona **solo** in modalita' `vanilla`: le entita' della modalita'
`display` sono oggetti del mondo. Il prezzo e' che serve una **lavagna** (scoreboard) per giocatore,
la stessa su cui un altro plugin disegnerebbe il pannello laterale: se CMI tiene ancora il suo, o
`per-viewer: never` o il suo pannello spento. Se un `%rel_` finisce dove non puo' funzionare, il log
all'avvio lo dice.

**Una riga che non ha niente da dire sparisce.** Con `skip-empty-lines` una riga i cui segnaposto
risolvono *tutti* a vuoto non viene disegnata: la riga della fazione non compare sopra la testa di
chi non ne ha nessuna, invece di lasciare appeso un `[]`. Una riga senza segnaposto — una decorazione
scritta a mano — si vede sempre, e la riga del nome non si salta mai.

**Quando non si vede.** Chi si accuccia, chi e' invisibile (pozione o `/vanish`), chi e' in
spettatore: le righe nostre vengono tolte, perche' un rettangolo di testo che galleggia da solo
direbbe a tutti dov'e' chi non si dovrebbe vedere. La targhetta del gioco queste cose le fa da se'.
`hide-self` nasconde a ciascuno la propria (in terza persona la vedrebbe da dietro le spalle), e
`disabled-worlds` lascia interi mondi con la targhetta nuda del gioco.

**Non lascia niente in giro.** Le righe nascono col divieto di essere salvate nel mondo e con un
marchio nostro: allo spegnimento del modulo si tolgono, e a ogni avvio si fa una passata a cercare
quelle marchiate rimaste in piedi (un `/reload` a caldo, un crash) e si buttano, scrivendo nel log
quante erano.

**CMI.** Anche la targhetta ce l'ha chi scrive per ultimo. Qui, a differenza del tablist, non ci
limitiamo ad avvisare: con `cmi.disable-module` acceso spegniamo noi il suo modulo dei nametag nel suo
`Settings/Modules.yml`, cambiando quella riga sola e lasciando una copia di scorta del file in
`.bak/CMI/Settings/` (fuori da `plugins/` sul server).
CMI quel file lo legge all'avvio, quindi **serve un riavvio** perche' smetta di scrivere anche lui.

Da lui quella riga si chiama **`namePlates`** — «name plates», non «nametag»: il plugin ne cerca
qualche grafia (`namePlates`, `nameplate`, `nametag`, `nametags`, `playerNameTag`, senza badare a
maiuscole e trattini) perche' fra una versione e l'altra gli cambia sotto le mani. Se nel log il suo
modulo risulta **non leggibile**, nessuno dei nomi conosciuti e' nel suo file: va guardato a mano e
aggiunto alla lista in `nametag/NametagManager`.

| Classe | Cosa fa |
|---|---|
| `nametag/NametagManager` | Il giro: compone le righe, sceglie chi le disegna, decide quando non si vedono |
| `nametag/NameTeams` | La targhetta **del gioco**: le squadre dello scoreboard, su tutte le lavagne che i giocatori hanno davvero |
| `nametag/DisplayLines` | Le righe **nostre**: le entita' di testo agganciate al giocatore |
| `util/CmiModules` | L'unico punto che sa dove CMI tiene i suoi interruttori e come si spengono |

---

## Chat

La riga della **chat pubblica** in gioco, su ogni server della rete (modulo `chat`, dalla 0.9.0; si
regola in `chat.yml`). Prima la scriveva MagixFactions sul faction e CMI sull'hub.

- **Riconosce da solo la modalita'** (0.9.1), come gli stili del nametag: con `style: auto` vince il
  primo stile di `styles` i cui plugin (`requires`) sono tutti caricati. Sul faction esce `factions`
  (c'e' MagixFactions), sull'hub `plain`. Lo stile scelto e' nel log all'avvio e nella guida staff.
  Una modalita' nuova: una voce in `styles`, sopra `plain`. `custom-format`, se scritta, vince su tutto.
- `{faction}`, `{relcolor}` e `{rank}` li chiede a MagixFactions (`chatTokens(lettore, mittente)`, per
  riflessione). Ogni lettore riceve la sua riga: il colore della relazione e' quello di chi legge.
- Pezzi facoltativi fra `[[ ]]`: spariscono se tutto quello che contengono risulta vuoto (chi non ha
  una fazione non si ritrova `[]` davanti al nome).
- `{name}`, `{message}` e tutti i placeholder di PlaceholderAPI (per chi scrive), compresi i
  relazionali `%rel_...%`. Il messaggio entra per ultimo, come testo semplice.
- Suggerimento con ora e provenienza (`tooltip.*`), link cliccabili con `magixessentials.chat.links`
  (vale anche il vecchio `magixfactions.chat.links`).
- I messaggi della chat del sito: MagixBridge chiama `broadcastWebChat(uuid, nome, testo, grado)`
  per riflessione; stesso formato, con `web-prefix` davanti.
- Ordine sull'evento: MagixGuard LOWEST/LOW (silenziati, filtro), MagixFactions (canali fazione e
  alleati, restano suoi) e MagixBridge (copia sul sito) NORMAL, questo modulo HIGH: annulla
  l'evento e manda la riga a ciascuno come messaggio di sistema.
- `cmi.disable-module: true` spegne la chat di CMI nei suoi file (`Settings/Chat.yml`: formato,
  ClickHoverMessages, colori, [item], menzioni, fumetti; `Settings/ChatFilter.yml`: filtro, doppioni,
  maiuscole, sostituzioni; `Settings/Modules.yml`: playerChatTag, chatBubble), con copia di scorta in
  `.bak/CMI/Settings/`. CMI li rilegge al riavvio. I messaggi privati (`/msg`) restano a CMI.

## Filtro dell'autocompletamento

Digitando `/` e premendo **TAB**, il client mostra un elenco di comandi da completare. Di suo il
server lo compila da **tutti** i comandi registrati da **ogni** plugin, permesso o no: senza
questo filtro, uno staff member vedrebbe (e potrebbe completare col TAB) anche i comandi di staff
di ogni altro plugin del server, pur non potendoli eseguire.

Il modulo `tabcomplete` ascolta `PlayerCommandSendEvent` — l'evento con cui il server compila
quell'elenco per ciascun giocatore — e toglie i comandi per cui il giocatore non ha il permesso,
di qualunque plugin, non solo dei Magix. L'esecuzione vera e propria non cambia: qui si pulisce
solo il suggerimento. Un comando senza `permission` dichiarato (o con `default: true`) resta
visibile a chiunque, com'e' giusto che sia.

Il client tiene in memoria l'elenco ricevuto al login: un permesso tolto o dato a caldo (LuckPerms,
`/pex`) si vede nel TAB solo dopo un ri-login.

---

## Valute

Una moneta di gioco creata dallo staff — gemme, punti, gettoni... — senza scrivere codice. Ogni
voce di `currencies.yml` e' una valuta, e la CHIAVE che le si da' e' insieme l'**id**, il nome del
**comando** che nasce da sola (`/<id>`) e il pezzo centrale dei suoi **permessi**: niente da
dichiarare nel `plugin.yml`, il comando lo registra il plugin quando legge il config (vedi
`currency/CurrencyManager`, che si aggancia al `CommandMap` del server — l'unico modo di registrare
un comando il cui nome non si conosce finche' non si legge il config).

```yaml
currencies:
  magix:
    name: "Gemme"           # nome mostrato nei messaggi
    starting-balance: 0     # saldo di chi non l'ha mai vista
    shared: false           # false = locale a questo server, true = in rete (vedi sotto)
```

Un id scritto male (solo lettere minuscole, cifre e trattino basso, deve iniziare per lettera) o
gia' usato da un altro comando del server viene saltato, col motivo nel log all'avvio. **Aggiungere
o togliere una valuta vale subito con `/magixessentials reload`**, senza riavviare: il comando
compare o sparisce davvero, anche dal TAB di chi e' gia' online.

Ogni valuta porta cinque sottocomandi:

| Comando | Cosa fa | Permesso | Di serie |
|---|---|---|---|
| `/<id>` | Mostra il proprio saldo | — | tutti |
| `/<id> add <giocatore> <importo>` | Aggiunge al saldo | `magixessentials.currency.<id>.admin` | operatori |
| `/<id> take <giocatore> <importo>` | Toglie dal saldo (mai sotto zero) | `magixessentials.currency.<id>.admin` | operatori |
| `/<id> set <giocatore> <importo>` | Fissa il saldo | `magixessentials.currency.<id>.admin` | operatori |
| `/<id> reset <giocatore>` | Riporta il saldo a `starting-balance` | `magixessentials.currency.<id>.admin` | operatori |
| `/<id> give <giocatore> <importo>` | Sposta valuta dal proprio saldo a un altro giocatore | `magixessentials.currency.<id>.give` | tutti |

### Locale o in rete

Il server sta dietro **Velocity**, con piu' backend (hub, factions...): `shared` decide dove vive
il saldo di ciascuna valuta.

| `shared` | Dove vive il saldo | Serve il database? |
|---|---|---|
| `false` (di fabbrica) | `balances.yml`, su QUESTO server — un'economia per server | no |
| `true` | Il database di `database` (config.yml), condiviso da tutti i server che lo puntano | si |

Una valuta `shared: true` va dichiarata con lo **stesso id** su ogni server dove deve esistere, con
`database` che punta allo stesso database ovunque — altrimenti sarebbero due saldi scollegati con
lo stesso nome, non uno condiviso. Il plugin crea da solo la tabella che gli serve, al primo avvio
con una valuta condivisa: non c'e' niente da preparare a mano.

### Placeholder

Ogni valuta genera da sola, senza altro da scrivere, due placeholder PlaceholderAPI:

| Placeholder | Cosa mostra |
|---|---|
| `%magixessentials_balance_<id>%` | Il saldo del giocatore per la valuta `<id>` (es. `magix`). `0` (o il saldo di partenza) se non l'ha mai vista |
| `%magixessentials_name_<id>%` | Il nome mostrato della valuta `<id>` (es. "Magix"), quello di `currencies.yml` |
| `%magixessentials_playtime%` | Il tempo di gioco del giocatore su questo server, in **secondi** (numero crudo, storico vanilla incluso). `0` se non disponibile |

Per una valuta `shared: true` il saldo risponde da una cache tenuta aggiornata in background (ogni
scrittura, piu' un giro periodico per chi e' online): non blocca mai il server per una query al
database, ma puo' restare indietro di qualche secondo rispetto a un'operazione appena fatta su un
altro server.

Per farli funzionare anche sui server **senza MagixEssentials**, l'id della valuta condivisa va
aggiunto a `bridge.player-placeholders` nel config di MagixBridge: li' si legge come
`%network_<server>_magixessentials_balance_<id>%` (vedi il README di MagixBridge).

---

## Oggetti fissi nell'inventario (customjoinitems)

Mette negli inventari dei giocatori gli oggetti decisi dallo staff — la bussola dei server sull'hub,
un oggetto fisso nella barra rapida sul faction — e dice cosa i giocatori possono farci. **Di serie
e' spento**: si accende con `customjoinitems: true` in `modules.yml`, server per server.

Tutto sta in `customjoinitems.yml`: quando e come si danno, le regole generali e, nella sezione
`items` (un catalogo: le voci le aggiunge lo staff e non vengono ripulite), gli oggetti con slot,
materiale, nome, descrizione, texture, permesso e azioni al clic; ogni chiave e' spiegata nel file.
Gli oggetti si riconoscono da un marchio con l'id della voce nei dati dell'oggetto, non dal nome.

| Chiave | Cosa fa |
|---|---|
| `give-on.join` / `respawn` / `world-change` | Quando si danno. Al login si aspetta MagixAuth (`wait-for-login`). |
| `clear-inventory` | Svuota tutto prima di dare: per l'hub, mai per il faction. |
| `if-slot-occupied` | `move` (sposta la cosa del giocatore, se non c'e' posto non da l'oggetto), `replace`, `keep`. |
| `rules.allow-*` | Regole generali: spostare, buttare, raccogliere, rompere e piazzare blocchi, scambiare le mani, durabilita'. `false` = vietato, per tutto e non solo per gli oggetti del modulo. |
| `left-click` / `right-click` / `shift-left-click` / `shift-right-click` / `commands` (per oggetto) | Le azioni al clic, vince la lista piu' precisa. Prefissi: `player:`, `console:`, `server:` (cambio server via Velocity), `message:`, `sound:`. Con `run-in-inventory` partono anche a inventario aperto. |
| `movable` / `droppable` / `vanilla-use` (per oggetto) | Spostarlo, buttarlo, usarlo come l'oggetto vero. Di serie tutti `false`. |

Gli oggetti del modulo non cadono mai a terra alla morte e tornano alla rinascita. Il permesso
`magixessentials.customjoinitems.bypass` (di serie op) salta le regole, non la consegna.
Comandi: `/mess joinitems give [giocatore|all]` e `/mess joinitems remove [giocatore|all]`.

---

## Lobby dell'hub (hub-lobby)

Le regole di un server che fa da **hub**. **Di serie e' spento**: si accende con `hub-lobby: true`
nel `modules.yml` **dell'hub soltanto**. Si regola in `hub-lobby.yml`, una sezione per funzione,
ciascuna col suo `enabled`.

**Spawn a ogni ingresso** (`spawn-on-join`): chi entra compare allo spawn, non dove era uscito —
entrando nella rete o arrivando da un'altra modalita' (`/server hub`, ritorno dal faction).

| Chiave | Cosa fa |
|---|---|
| `spawn-on-join.enabled` | Acceso/spento. |
| `spawn-on-join.world` | Il mondo dello spawn. Vuoto = il mondo principale. |
| `spawn-on-join.use-world-spawn` | `true` = lo spawn del mondo (`/setworldspawn`, centrato nel blocco, sguardo compreso); `false` = il punto fisso `x`/`y`/`z`/`yaw`/`pitch`. |

`/mess lobby setspawn` (in gioco) scrive in `hub-lobby.yml` il mondo e il punto in cui ti trovi,
sguardo compreso, mette `use-world-spawn: false` e fa ripartire il modulo: niente coordinate a mano.

Come convive con **MagixAuth**: il punto di comparsa si cambia su `AsyncPlayerSpawnLocationEvent` a
priorita' LOW, prima di MagixAuth (HIGH), che quindi lo prende come "posizione vera" e dopo il login
riporta il giocatore li' invece che alla vecchia posizione. Dopo il login un controllo porta allo
spawn chi non c'e' (primo ingresso, rimasto al cancello; altri plugin che lo spostano al join).

---

## Comandi

| Comando | Cosa fa | Permesso |
|---|---|---|
| `/magixessentials reload` (alias `/mess`, `/magixess`) | Riallinea i file, li rilegge e fa ripartire i moduli accesi | `magixessentials.admin` |
| `/mess joinitems give\|remove [giocatore\|all]` | Rimette o toglie gli oggetti fissi dell'inventario (modulo `customjoinitems`) | `magixessentials.admin` |
| `/mess lobby setspawn` | Lo spawn della lobby diventa il punto in cui ti trovi (modulo `hub-lobby`) | `magixessentials.admin` |
| `/<id valuta>` | I comandi delle valute (`/magix`, `/gems`...), vedi sopra | dinamico, per valuta |

Il reload risponde in chat con l'elenco dei moduli e il loro stato, e lo stesso elenco finisce nel
log a ogni avvio.

---

## Guida per lo staff

Il capitolo sul gestionale (`/manage.php?section=guida`) lo scrive il plugin stesso a ogni avvio e a
ogni reload, leggendo i valori **vivi** dei file: comandi, permessi e impostazioni non si ricopiano
a mano. Vedi `plugins-src/GUIDA-STAFF.md`.
