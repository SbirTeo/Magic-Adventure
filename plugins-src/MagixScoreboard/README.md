# MagixScoreboard

Plugin per **MAGICADVENTURE** (Paper 26.x) che mostra una **scoreboard laterale** diversa a
seconda di chi e' il giocatore, dove si trova e in quale regione WorldGuard, coi placeholder di
**PlaceholderAPI** (compresi quelli di tutti gli altri plugin Magix, che si espongono ognuno come
propria expansion) gia' risolti.

Versione: **0.1.0**.

---

## Cosa fa

**Piu' scoreboard, una sola regola per scegliere.** Ogni scoreboard ha condizioni facoltative
(permesso, mondi, regioni WorldGuard, condizioni sui placeholder) e un **peso** (`weight`): fra
tutte quelle che corrispondono al giocatore in quel momento, vince quella col peso piu' alto. Un
giocatore col permesso di tre scoreboard diverse vede quella col peso maggiore.

**Condizioni sui placeholder.** Oltre a permesso/mondo/regione, una scoreboard puo' richiedere
che un placeholder di PlaceholderAPI soddisfi un confronto (`%placeholder% OP valore`, con
`OP` fra `>= <= == != > <`) — es. mostrarla solo a chi ha una fazione (MagixFactions) o a chi ha
un livello minimo. Copre qualunque placeholder, non solo quelli dei plugin Magix. Con piu' di
una condizione, `placeholders-mode` decide se servono TUTTE (`all`, il default) o ne basta UNA
(`any`).

**Spareggio configurabile.** A parita' di peso decide `priority-order` nel config: un elenco che
dice quale TIPO di condizione conta di piu' (`region`, `permission`, `world`, `placeholder`). Di
serie una regione batte un permesso, che batte un mondo, che batte una condizione sui
placeholder — ma l'ordine si puo' invertire cambiando quella lista, senza toccare il codice.

**Titolo e righe animabili.** Ogni riga (e il titolo) ha una lista di "frame" e un intervallo in
tick: con un solo frame il testo e' fisso (i placeholder si aggiornano comunque), con piu' di uno
si alternano in ordine — sia per un'animazione del singolo testo, sia per righe del tutto diverse
che si alternano nello stesso posto (es. saldo e statistiche a turno). L'avanzamento e'
sincronizzato per tutti i giocatori.

**WorldGuard e PlaceholderAPI sono entrambi opzionali (softdepend).** Senza WorldGuard le
condizioni `regions` semplicemente non corrispondono mai (avviso in console all'avvio). Senza
PlaceholderAPI i placeholder restano testo letterale, non risolto.

## Comandi

| Comando | Cosa fa | Permesso |
|---|---|---|
| `/mscoreboard` | La scoreboard che stai vedendo ora | `magixscoreboard.use` |
| `/mscoreboard toggle` | Nasconde o rimostra la scoreboard | `magixscoreboard.use` |
| `/mscoreboard help [pagina]` | L'elenco dei comandi | `magixscoreboard.use` |
| `/mscoreboard list` | Elenco delle scoreboard configurate | `magixscoreboard.admin` |
| `/mscoreboard debug <giocatore>` | Quale scoreboard vede e perche' | `magixscoreboard.admin` |
| `/mscoreboard reload` | Ricarica config.yml e messages.yml | `magixscoreboard.admin` |

Alias: `/msb`.

## Configurazione

Tutto in `config.yml`, sotto `scoreboards:`. Vedi i commenti nel file per lo schema completo
(`weight`, `permission`, `worlds`, `regions`, `placeholders`, `placeholders-mode`, `title`,
`lines`, `interval-ticks`, `frames`) e cinque scoreboard di esempio gia' pronte (`default`,
`vip`, `spawn`, `faction`, `no-faction`).

Un titolo o una riga possono **scorrere** da destra verso sinistra, come un'insegna: basta aggiungere
`scroll:` con `width` (caratteri visibili), `gap` (spazi prima di ricominciare) e `speed-ticks`
(ogni quanti tick avanza di un carattere). Scorre il testo gia' risolto, placeholder e colori
compresi, e la scoreboard si ridisegna da sola a quel passo:

```yaml
- interval-ticks: 20
  frames:
    - "%magixfactions_status%"
  scroll:
    width: 24
    gap: 6
    speed-ticks: 2
```

### Sfondo e bordo della sidebar

Il riquadro semitrasparente dietro la sidebar lo disegna il client: MagixScoreboard lo cambia con uno
shader (`gui.vsh`/`gui.fsh`, registrati nel pacchetto unico di **MagixPack**; senza MagixPack resta
vanilla). In `config.yml`, sezione `sidebar-background`:

- `enabled` — `false` toglie lo sfondo (restano le scritte, e il bordo se acceso);
- `color` / `opacity` — colore `#RRGGBB` e opacita' 0-100, **uguali per titolo e righe**;
- `border.enabled`, `border.width` (pixel GUI), `border.color-start` / `border.color-end` (sfumato
  da sinistra a destra, come i bordi del sito), `border.opacity` — bordo su sopra, sinistra e sotto.

Si applica con `/mscoreboard reload`; chi e' gia' connesso lo vede al prossimo ingresso. Lo shader
riconosce la sidebar dal colore vanilla (nero al 30% le righe, al 40% il titolo, verificato nel client
26.2): chi ha disattivato "Sfondo testo solo per la chat" nelle opzioni la vede di serie.

### Posizione verticale della sidebar

Il client la mette sempre un po' sopra la meta' dello schermo: `sidebar-position.offset-y` la sposta
di N pixel dell'interfaccia (positivo = in basso, 0 = vanilla). Lo sfondo lo sposta lo shader `gui`
di MagixScoreboard; scritte e icone lo shader del testo di **MagixFactions**, che legge lo stesso
valore da questo config (MagixPack tiene un solo `text.vsh`, ed e' di MagixFactions). Lo shader
riconosce le scritte della sidebar da un marchio nel colore: con `offset-y` diverso da 0
MagixScoreboard porta ogni colore della sidebar a rosso, verde e blu = 3 modulo 8 (al massimo 4
sfumature su 255, `util/SidebarMark`), cosa che nessun colore vanilla fa. Cosi' tooltip degli
oggetti, menu e chat non si spostano piu' (prima scivolavano giu' fuori dal loro riquadro). In piu'
devono cadere nella fascia destra larga `zone-width` all'altezza della sidebar. `/mscoreboard reload` ricostruisce sia il pezzo di MagixScoreboard sia quello di
MagixFactions.

### Grandezza della sidebar uguale per tutti

Il client disegna la sidebar alla Scala GUI di ogni giocatore (con 4 e' grande il doppio che con 2).
`sidebar-scale` la sgancia: lo shader ricava la Scala GUI del giocatore (`ScreenSize` del blocco
`Globals` ÷ larghezza dell'interfaccia, verificato nel client 26.2: le pipeline `gui` e `text`
ereditano `GLOBALS_SNIPPET`) e moltiplica le distanze dal punto a cui il client aggancia la sidebar
(bordo destro, meta' altezza) per scala voluta ÷ scala del giocatore. Il risultato e' la sidebar che
vanilla disegnerebbe alla scala voluta, `offset-y` compreso. Sfondo in `gui.vsh` di MagixScoreboard,
scritte e icone nel `text.vsh` di MagixFactions (stesso marchio nel colore e stessa fascia dello
spostamento): la funzione `sbScaleFactor` e' copiata identica nei due file.

- `mode: minimap` (di serie) — le lettere grandi esattamente come quelle del pannello info sotto la
  minimap di MagixFactions: quel testo e' disegnato col font della mappa (1 pixel del font = 1 pixel
  della mappa) su una minimap larga `screen-size` × (16/9) / 2 dell'altezza per 128 pixel, quindi il
  fattore e' `screen-size` × 16 / 9 / 256 per pixel di altezza (2,25 a 1080p con `screen-size` 0.3).
  Per rimpicciolire o ingrandire entrambe si cambia `map.minimap.screen-size`; `size` non conta.
  Il fattore lo calcolano i due plugin con la stessa regola (`SidebarPack.scaleFactor` qui,
  `ResourcePackContent` in MagixFactions) e lo passano agli shader come `SB_SCALE_FACTOR`.
- `mode: screen` — in proporzione all'altezza dello schermo: `size` 3 = la Scala GUI 3 a 1080
  pixel, 2 a 720, 4 a 1440. Alle risoluzioni che non sono multiple di 360 la scala non e' intera e
  qualche pixel delle lettere esce un filo piu' largo.
- `mode: integer` — la scala intera piu' vicina alla stessa proporzione: lettere sempre nitide, grandezza
  a scatti fra una risoluzione e l'altra.
- `mode: off` — vanilla.

La minimap di MagixFactions e' gia' indipendente dalla Scala GUI (e dalla 0.63 e' riferita all'altezza
dello schermo), quindi con `sidebar-scale` acceso le due restano nelle stesse proporzioni per tutti.

La guida generata dal plugin (`plugins/MagixScoreboard/guida-staff.html`, anche nel gestionale
del sito) elenca TUTTE le chiavi in uso col valore reale: e' la fonte piu' aggiornata.
