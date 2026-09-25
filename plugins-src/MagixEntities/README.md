# MagixEntities

Plugin per **MAGICADVENTURE** (Paper 26.x) che crea e gestisce **entita' statiche da comando**, stile Citizens,
comprese le **statue con la skin di un giocatore**.

Versione: **0.9.14** — questo file viene riscritto in `plugins/MagixEntities/README.md` ad ogni avvio del server.

---

## Come funziona il tipo `player`

Non servono pacchetti, ProtocolLib o NPC finti: si usa l'entita' **vanilla `Mannequin`** (statua giocatore
introdotta nelle versioni recenti di Minecraft). Il plugin le assegna il **profilo del giocatore indicato**
(quindi la sua skin) e le mette sopra la testa il **displayname** scelto da te.

- La skin viene presa dal **nome dell'entita'** (`<nome>` del comando `create`), e continua a seguirlo
  anche se la rinomini con `/mentities name`.
- Se il giocatore e' online viene copiato direttamente il suo profilo; altrimenti le texture vengono
  scaricate da Mojang in background (`skin.fetch-textures` in `config.yml`).
- La skin si puo' cambiare in qualsiasi momento con `/mentities skin <nome> <nick>`.

Tutti gli altri tipi sono **mob veri** (zombie, blaze, skeleton, villager, ...) con IA disattivata,
invulnerabili e silenziosi per impostazione predefinita.

---

## Comandi

Comando principale: `/magixentities` — alias: `/mentities`, `/mentity`, `/ment`, `/me`.

> **Nota su `/me`**: e' anche l'emote vanilla (e CMI ne registra una sua). Se `/me` finisce all'emote,
> usa `/mentities`, `/ment` oppure la forma esplicita `/magixentities:me`.

| Comando | Cosa fa |
|---|---|
| `/mentities create <nome> <tipo> [displayname]` | Crea l'entita' nel punto in cui sei |
| `/mentities remove <nome>` | Elimina definizione ed entita' |
| `/mentities list [pagina]` | Elenco con tipo, posizione e stato |
| `/mentities info <nome>` | Dettagli completi di una entita' |
| `/mentities tp <nome>` | Ti teletrasporta dall'entita' |
| `/mentities here <nome>` | Sposta l'entita' dove sei tu (anche la rotazione) |
| `/mentities type <nome> <tipo>` | **Cambia il tipo** di un'entita' gia' creata, tenendo posizione, displayname, opzioni, vestiti e comandi al clic (l'entita' viene rifatta sul posto). Skin e posa restano salvate anche su un mob: tornando a `player` si rivedono |
| `/mentities name <nome> <nuovoNome>` | **Rinomina l'entita' stessa** (per il tipo `player` la skin segue) |
| `/mentities displayname <nome> <testo>` | Cambia **solo la scritta sopra la testa** (`reset` = segue il nome) |
| `/mentities displayname <nome> mirror` | Specchio: **ognuno vede il proprio nome** sopra la testa |
| `/mentities displayname <nome> off\|on` | Nasconde/rimostra il nome sopra la testa (stessa opzione di `/mentities set <nome> nametag`) |
| `/mentities skin <nome> <nick>` | Cambia la skin (solo tipo `player`) |
| `/mentities skin <nome> mirror` | Specchio: **ognuno la vede con la propria skin** |
| `/mentities pose <nome> <posa>` | Posa della statua (solo tipo `player`) |
| `/mentities scale <nome> <valore\|reset>` | Ingrandisce/rimpicciolisce l'entita' (1 = normale, qualunque tipo) |
| `/mentities set <nome> <opzione> <on\|off>` | Modifica un'opzione (sotto) |
| `/mentities equip <nome>` | Apre il menu per vestirla (armatura, mano, mano secondaria) |
| `/mentities cmd <nome> add <comando>` | Esegue il comando quando l'entita' viene cliccata |
| `/mentities cmd <nome> list\|remove <n>\|clear` | Gestisce i comandi al clic |
| `/mentities respawn <nome>` | Ricrea l'entita' se e' sparita |
| `/mentities reload` | Ricarica `config.yml`, `messages.yml`, `entities.yml` |
| `/mentities help [pagina]` | Elenco comandi in gioco, a pagine e cliccabile |

L'interfaccia in chat e' interattiva. L'**help** e' impaginato (otto righe per volta, sezioni
Entita'/Aspetto/Interazione/Posizione/Staff, frecce `‹ indietro · avanti ›` in fondo): ogni riga e'
cliccabile e scrive il comando in chat, `/mentities help 2` (o il numero da solo) salta a una pagina, e
la sezione *Staff* la vede solo chi ha `magixentities.admin`. In **`list`** ogni entita' e' cliccabile e apre il suo `info`, il tooltip
mostra mondo/coordinate/displayname, e con piu' pagine compaiono le frecce &#9664;/&#9654;; sotto **`info`**
c'e' una riga di pulsanti &#9654; Vai / &#8644; Porta qui / &#10006; Rimuovi (gli ultimi due propongono il
comando senza eseguirlo, cosi' la rimozione resta una scelta consapevole).

Tutti i testi e i colori stanno in `messages.yml` e sono modificabili. La palette e' quella comune ai
plugin Magix (vedi `plugins-src/STILE-MAGIX.md`): viola marchio `&#C046E8`, verde marchio `&#A8DC2C`,
grigio `&#9A8CA8`, tenue `&#5A5068`, errore `&#FF6B6B`, avviso `&#FFD166`. Le voci dell'help stanno
sotto `help.sections`, scritte come `comando <argomenti> :: descrizione`.

### Esempi

```
/mentities create Notch player &6&lIl Fabbro
/mentities create Guardiano blaze &cGuardiano del Nether
/mentities create Mercante villager &aMercante &7(clic per parlare)
/mentities name Notch Steve
/mentities displayname Guardiano &4&lGuardiano Infernale
/mentities displayname Guardiano reset
/mentities displayname Notch mirror
/mentities skin Notch teolo
/mentities skin Notch mirror
/mentities pose Notch sitting
/mentities set Mercante glowing on
```

Il displayname accetta i codici colore `&a &c &l ...` e gli esadecimali `&#RRGGBB`.
Il `<nome>` e' l'identificativo: solo lettere, numeri, `_` e `-`, massimo 32 caratteri.

### Nome vs displayname

Sono due cose distinte:

- il **nome** e' l'identificativo con cui richiami l'entita' nei comandi e, per il tipo `player`,
  e' anche il **nick da cui viene presa la skin**. Si cambia con `/mentities name`;
- il **displayname** e' solo la scritta sopra la testa, con i colori. Si cambia con `/mentities displayname`.

**Se non imposti un displayname, questo segue il nome**: crei `/mentities create Fabbro player`, sopra la
testa leggi "Fabbro", e se poi fai `/mentities name Fabbro Mastro` la scritta diventa "Mastro" da sola.
Appena assegni un displayname esplicito, quello resta anche se rinomini l'entita' — per tornare a farlo
seguire il nome usa `/mentities displayname <nome> reset`.

`off`/`on` su `displayname` non toccano il testo: nascondono/rimostrano solo il nametag, la
stessa opzione booleana `nametag` di `/mentities set` (vedi sotto) letta e scritta da un altro
comando piu' comodo da ricordare quando si sta gia' lavorando sul displayname.

`nametag off` **toglie** il nome (`customName(null)`), non lo nasconde soltanto: in Minecraft
`CustomNameVisible=false` non vuol dire "nome invisibile", vuol dire "visibile solo mirando
l'entita' da vicino" (il comportamento normale di un mob rinominato col name tag) — `true` vuol
dire "sempre visibile", come un cartello. Impostare solo la visibilita' a `false` lascerebbe
comunque un nome sull'entita', che il client mostrerebbe ugualmente mirandola. Vale per qualunque
tipo di entita' (statua o mob).

**Solo per il tipo `player`**, `nametag off` toglie anche il nome dal PROFILO stesso (stessa
skin, texture intatte, solo senza l'etichetta): la targhetta VANILLA che il client disegna da
solo (col nome del profilo/skin) quando ci si punta vicino a una statua e' un meccanismo
**diverso e indipendente** dal customName sopra — legge il nome dal profilo, non dal cartello —
e resterebbe visibile anche a nametag spento se si toccasse solo il customName. Funziona allo
stesso modo anche sulle skin a specchio.

`mirror` e' una **parola riservata** su `displayname` e su `skin` (vedi sotto), **non** su `name`:
il nome e' un identificativo, li' `mirror` sarebbe solo un nome come un altro.

### Tipi accettati

- `player` (sinonimi: `npc`, `mannequin`) → statua con skin del giocatore
- qualunque mob vivo e spawnabile: `zombie`, `skeleton`, `blaze`, `villager`, `iron_golem`, `piglin`,
  `wither_skeleton`, `allay`, `creeper`, ... (il tab-completion mostra la lista completa)

---

## Opzioni per entita' (`/mentities set`)

| Opzione | Default | Effetto |
|---|---|---|
| `invulnerable` | on | Nessun danno (fuoco, mob, giocatori, caduta) |
| `nametag` | on | Mostra il displayname sopra la testa |
| `ai` | off | IA del mob: se `on` cammina, insegue e attacca |
| `gravity` | on | Resta appoggiata al terreno |
| `silent` | on | Nessun verso |
| `glowing` | off | Contorno luminoso |
| `collidable` | off | Se `on` i giocatori la spingono/urtano |
| `immovable` | on | Solo `player`: blocca completamente la statua |
| `interact` | off | Se `on` il clic destro **non** viene annullato: lo vedono anche gli altri plugin |
| `follow` | off | L'entita' **gira verso il giocatore piu' vicino** (raggio in `config.yml`) |

I default delle nuove entita' si cambiano nella sezione `defaults` di `config.yml`.

> Il Mannequin ha anche un campo vanilla **`description`**: una riga disegnata sopra la testa *in aggiunta*
> al nametag e visibile **solo da vicino**. Se lo attivi (`player.description: true` in `config.yml`) il
> displayname compare **due volte** e avvicinandoti il nametag sale di una riga. Di default il plugin lo
> lascia **assente** (`null`, non "vuoto": una description vuota occuperebbe comunque la riga) e il nome
> viene dal solo nametag.

---

## Sguardo che segue (`follow`)

```
/mentities set <nome> follow on
```

L'entita' gira verso il giocatore piu' vicino entro `follow.radius` (default 12 blocchi) e torna
all'orientamento salvato quando non c'e' piu' nessuno. L'aggiornamento avviene ogni
`follow.interval-ticks` (default 5 tick = 4 volte al secondo): alzalo se vuoi meno lavoro, abbassalo
per un movimento piu' fluido.

Ruota **testa e corpo** (solo la testa lascerebbe il busto storto). Sulle entita' a specchio ogni
copia segue il **proprio** proprietario, quindi ciascuno si vede guardato dalla sua.

## Scala (`scale`)

```
/mentities scale <nome> <valore>
/mentities scale <nome> reset
```

Ingrandisce o rimpicciolisce l'intera entita' — skin, equipaggiamento e hitbox insieme — con
l'attributo vanilla `scale` (1 = grandezza normale). Vale per **qualunque tipo**, non solo per il
tipo `player`. I limiti accettati sono in `config.yml` (`scale.min`/`scale.max`, default 0.0625–10).

Combinata con `pose` e `follow` e' cosi' che si fa una statua gigante con la skin di un giocatore
che gira lo sguardo verso chi le passa vicino.

## Equipaggiamento (`/mentities equip`)

```
/mentities equip <nome>
```

I clic dentro il menu sono gestiti dal plugin: i vetri di riempimento e le etichette **non** si possono
prendere, spostare o portare via nell'inventario. Negli slot buoni si puo' solo posare, prendere o
scambiare un oggetto (anche con lo shift-clic dal proprio inventario).

Apre una cassa 3x9 con sei slot: **testa, corpo, gambe, piedi** (fila di sinistra) e
**mano principale, mano secondaria** (a destra). Trascina gli oggetti negli slot azzurri e
**chiudi la finestra per salvare**: gli oggetti finiscono in `entities.yml` e vengono rimessi
ad ogni ricreazione dell'entita' (e su ogni copia mirror). Per svuotare uno slot, togli l'oggetto
e chiudi.

Le probabilita' di drop sono azzerate, quindi l'equipaggiamento non cade a terra in nessun caso.

---

## Comandi al clic (NPC interattivi)

```
/mentities cmd <nome> add <comando>
/mentities cmd <nome> list
/mentities cmd <nome> remove <n>
/mentities cmd <nome> clear
```

Al **clic destro** sull'entita' i comandi vengono eseguiti in ordine. Prefissi:

| Sintassi | Esegue |
|---|---|
| `/mentities cmd Fabbro add warp fucina` | il comando **come il giocatore** (con i suoi permessi) |
| `... add console: give {player} diamond 1` | il comando **da console** (per cose che il giocatore non puo' fare) |
| `... add msg: &aCiao {player}!` | manda un **messaggio** al giocatore |

Placeholder disponibili: `{player}` (chi clicca) e `{name}` (nome dell'entita').
Un cooldown per giocatore (`commands.cooldown-ms`, default 500 ms) evita i doppi clic.

**Funziona anche in modalita' mirror**: l'entita' cliccata viene riconosciuta dal tag MagixEntities,
quindi copia personalizzata e originale portano agli stessi comandi.

### Usare CMI (o un altro plugin) al posto di questi comandi

Di default il plugin **annulla** il clic destro sulle sue entita' (serve a non far aprire la GUI di
scambio dei villager), quindi gli altri plugin non lo vedono. Per lasciarglielo passare:

```
/mentities set <nome> interact on
```

Attenzione pero': con la **skin/displayname a specchio** quello che clicchi non e' l'entita' salvata ma
una **copia con un altro UUID**, ricreata di continuo. Un plugin che lega l'azione all'UUID
(come `cmi:interactivecommand`) non puo' funzionare: su un'entita' mirror usa i comandi al clic
di MagixEntities, che sono legati alla definizione e non all'UUID.

---

## Modalita' `mirror` (specchio)

```
/mentities skin <nome> mirror          # ognuno la vede con la PROPRIA skin (solo tipo player)
/mentities displayname <nome> mirror   # ognuno vede il PROPRIO nome sopra la testa (qualsiasi tipo)
```

Le due sono indipendenti e combinabili: una statua con entrambe e' uno specchio completo, uno zombie con
il solo displayname a specchio mostra a ciascuno il suo nick. Si disattivano tornando a un valore normale
(`/mentities skin <nome> <nick>`, `/mentities displayname <nome> <testo>` o `... reset`).

**Come funziona:** skin e nome di un'entita' sono unici per tutti i client, quindi non basta cambiarli.
Il plugin nasconde l'entita' vera (`setVisibleByDefault(false)`) e, per ogni giocatore entro
`mirror.radius` (default 48 blocchi), crea nella stessa posizione una **copia** personalizzata,
mostrata solo a lui (`showEntity`). Le copie:

- **non sono persistenti**: non vengono scritte nel salvataggio del mondo;
- vengono create/eliminate ogni `mirror.interval-ticks` (default 20 tick = 1 s) man mano che i giocatori
  entrano ed escono dal raggio, e alla disconnessione;
- ereditano posa, opzioni e protezioni dell'entita' vera, e ne personalizzano skin e/o nametag;
- portano un tag distinto (`magixentities:clone`), cosi' non vengono mai scambiate per l'entita' vera.

**Costo:** una copia per ogni giocatore nel raggio. Con molti giocatori nello stesso punto (es. spawn)
conviene tenere `mirror.radius` basso.

**Aureola VIP sulla skin a specchio:** se il plugin **MagixCosmetics** e' installato e abilitato
(`softdepend`, nessuna dipendenza obbligatoria), la copia in modalita' skin `mirror` di un giocatore
riproduce sopra la testa la sua stessa aureola colorata da VIP — ma solo se in quel momento
quel giocatore ce l'ha **davvero attiva** (permesso `magixcosmetics.halo` + un permesso colore,
non spenta con `/halo off`, non in combattimento PvP, non in spettatore/vanish/invisibile: le
stesse condizioni della sua aureola vera). Un giocatore senza aureola attiva vede/mostra la sua
copia senza. Si regola con `mirror.halo.enabled` e `mirror.halo.interval-ticks` nel config.

---

## Persistenza e robustezza

- Le entita' sono **entita' vere del mondo**, salvate nei chunk come qualsiasi mob: sopravvivono ai riavvii.
- `entities.yml` conserva nome, tipo, skin, displayname, posizione, opzioni e UUID dell'entita'.
- Ogni entita' porta un tag nel `PersistentDataContainer` (`magixentities:npc`): serve a **riconoscerla**
  anche se l'UUID cambia e a **ripulire doppioni/residui** al caricamento dei chunk.
- Un controllo periodico (`check-interval-seconds`, default 20s) **ricrea** le entita' sparite —
  ma **solo se il chunk e' caricato**, cosi' non si creano duplicati con i chunk scarichi.
- Se un'entita' viene uccisa (`/kill`, creativa, comandi di altri plugin) viene ricreata al tick successivo,
  senza drop ne' esperienza.

### Quando un altro plugin blocca la nascita

La nascita di un'entita' e' un evento **annullabile**: un plugin di protezione puo' dire di no. In quel
caso `World#spawn` restituisce comunque l'oggetto, ma nel mondo non ci e' mai entrato. Il plugin lo
verifica (`isInWorld()`), butta l'entita' fantasma e lo dice:

- in console, una volta per entita', con la causa tipica e come sistemarla;
- in chat a chi ha lanciato il comando (`spawn-refused`);
- in `/mentities list` e `/mentities info` con il pallino **giallo**.

La definizione resta salvata in `entities.yml`: tolta la protezione, il controllo periodico ricrea
l'entita' da sola (o subito con `/mentities respawn <nome>`).

**Caso tipico — WorldGuard.** Con il flag `mob-spawning: deny` su una regione (spesso `__global__`
del mondo dello spawn) e `mobs.block-plugin-spawning: true` nel suo `config.yml`, WorldGuard annulla
anche le nascite chieste dai plugin. Si mette `block-plugin-spawning: false` e si fa `/wg reload`: i
mob naturali restano bloccati dal flag, le entita' di MagixEntities passano. Le entita' gia' esistenti
non se ne accorgono perche' vivono nel salvataggio del mondo e non vengono ricreate — per questo il
problema si vede solo creandone di nuove.

## Protezioni (`config.yml`, sezione `protezioni`)

`no-target` (i mob ostili le ignorano), `no-combust` (non bruciano al sole), `no-transform`
(zombie non diventano annegati, ecc.), `no-pickup` (non raccolgono item), `no-portal`
(non entrano nei portali), `no-interact` (niente GUI di scambio del villager).

## Permessi

| Permesso | Default | Cosa da' |
|---|---|---|
| `magixentities.use` | op | Tutti i comandi di creazione/modifica |
| `magixentities.admin` | op | `/mentities reload` |

## File generati in `plugins/MagixEntities/`

- `config.yml` — default delle nuove entita', protezioni, intervallo di controllo
- `messages.yml` — tutti i testi (modificabili; le chiavi mancanti ricadono su quelle del jar)
- `entities.yml` — le entita' create (non modificarlo a server acceso)
- `README.md` — questo file, rigenerato ad ogni avvio

---

## Build

Serve **JDK 25** (paper-api 26.1.2 e' compilata per class file 69) + Maven:

```
mvn -q package
```

Il jar finisce in `target/MagixEntities-0.7.2.jar` e va copiato in `server/plugins/` (mirror locale) e
in `/home/ubuntu/magicadventure/plugins/` sul VPS.
