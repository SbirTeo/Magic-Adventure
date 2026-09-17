# MagixEssentials

Plugin per **MAGICADVENTURE** (Paper 26.x) che raccoglie le **utilita' di base** del server: quelle
cose che non appartengono a nessun gioco in particolare ma che ci sono sempre. Oggi ne fa tre — il
**tablist**, la **MOTD** e il **nametag** — e a lungo andare dovrebbe assorbire cio' che oggi fa CMI.

Versione: **0.8.7**

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
| `config.yml` | Solo cio' che vale per il **plugin intero**. Per ora niente, e lo dice. |

Una funzione spenta non parte affatto: niente task, niente aggancio agli eventi. Il suo file resta
dov'e', intatto, e torna in uso appena la si riaccende — spegnere non e' buttare via la
configurazione.

I file sul server si tengono aggiornati da soli (`util/ConfigAlign`): a ogni avvio e a ogni
`/magixessentials reload` le chiavi nuove compaiono al loro posto col loro commento, le rinomine
dichiarate si applicano portandosi dietro il valore scelto, e le righe che il codice non legge piu'
spariscono — con una copia di scorta `.bak-<data>` accanto, prima di ogni scrittura.

**Per aggiungere una funzione**: una riga in `modules.yml`, un `<funzione>.yml` accanto, e la classe
che la gestisce riceve quel file nel costruttore. Non c'e' nessun elenco da aggiornare a mano: i
file nuovi il plugin li crea e li allinea da solo.

---

## Tablist

La lista giocatori del tasto Tab: intestazione, fondo e nome dei giocatori, riscritti a intervalli
regolari e a ogni ingresso. Supporta i colori `&` e `&#RRGGBB`, i placeholder di PlaceholderAPI
(ricalcolati per ogni giocatore: ping, fazione, coordinate) e il segnaposto `{logo}`, che diventa il
carattere del logo nel resource pack di MagixFactions.

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
`tablist: false`).

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
stile non ha senso**, non tutti quelli che compaiono nei suoi segnaposto: `MagixWeb`
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
`Settings/Modules.yml`, cambiando quella riga sola e lasciando una copia di scorta del file accanto.
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

## Comandi

| Comando | Cosa fa | Permesso |
|---|---|---|
| `/magixessentials reload` (alias `/mess`, `/magixess`) | Riallinea i file, li rilegge e fa ripartire i moduli accesi | `magixessentials.admin` |

Il reload risponde in chat con l'elenco dei moduli e il loro stato, e lo stesso elenco finisce nel
log a ogni avvio.

---

## Guida per lo staff

Il capitolo sul gestionale (`/manage.php?section=guida`) lo scrive il plugin stesso a ogni avvio e a
ogni reload, leggendo i valori **vivi** dei file: comandi, permessi e impostazioni non si ricopiano
a mano. Vedi `plugins-src/GUIDA-STAFF.md`.
