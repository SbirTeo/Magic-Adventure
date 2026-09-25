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
un livello minimo. Copre qualunque placeholder, non solo quelli dei plugin Magix.

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
(`weight`, `permission`, `worlds`, `regions`, `placeholders`, `title`, `lines`, `interval-ticks`,
`frames`) e cinque scoreboard di esempio gia' pronte (`default`, `vip`, `spawn`, `faction`,
`no-faction`).

La guida generata dal plugin (`plugins/MagixScoreboard/guida-staff.html`, anche nel gestionale
del sito) elenca TUTTE le chiavi in uso col valore reale: e' la fonte piu' aggiornata.
