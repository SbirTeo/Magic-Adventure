# MagixPack

Il resource pack **UNICO** del server. Un client Minecraft applica un solo pacchetto risorse alla
volta: invece che ogni plugin (MagixFactions per lo shader della minimap/mappa e il logo del
tablist, MagixAuth per le schermate di accesso e il tastierino OTP...) ne spedisca uno proprio,
tutti registrano qui il loro contenuto e MagixPack li fonde in un unico zip, lo serve da solo via
un piccolo server HTTP integrato (nessun hosting esterno richiesto) e lo rende obbligatorio al
join.

Non ha comandi ne' permessi per i giocatori: e' infrastruttura.

## Come funziona

1. Ogni plugin contributore (MagixFactions, MagixAuth, e ogni plugin futuro che vuole aggiungere
   texture/font/suoni propri) si registra al proprio `onEnable`, passando una mappa
   `percorso-nello-zip -> bytes` gia' pronta (segnaposto propri gia' risolti, se ne aveva).
2. MagixPack aspetta che TUTTI i plugin abbiano finito il loro `onEnable` (evento
   `ServerLoadEvent`, che scatta una volta sola a fine avvio) prima di costruire davvero lo zip:
   cosi' l'ordine fra i vari contributori non conta.
3. Lo zip viene servito via un server HTTP integrato (`com.sun.net.httpserver.HttpServer`, gia'
   nella JDK) e inviato a ogni giocatore al join. Con `required: true` (default) chi non lo carica
   viene espulso — vedi `pack.PackListener`.
4. Un watchdog periodico verifica da solo che il pacchetto sia ancora scaricabile e riavvia il
   server HTTP se serve (rete di sicurezza contro un guasto reale gia' visto: dopo ore di uptime il
   server HTTP smetteva di rispondere).

## Registrarsi da un altro plugin

Nessuna dipendenza Maven: ogni plugin di questo repository si compila per conto suo (vedi
`.github/workflows/deploy-plugin.yml`), quindi due plugin non condividono un'interfaccia a
compile-time. Ci si parla per **riflessione** — stesso schema gia' usato per la chat live del sito
verso MagixFactions (vedi `MagixFactions.broadcastWebChat`) e per l'hook di Vault (`hook.Econ`):

```java
Plugin mp = Bukkit.getPluginManager().getPlugin("MagixPack");
if (mp != null && mp.isEnabled()) {
    Map<String, byte[]> files = new LinkedHashMap<>();
    files.put("assets/miomod/textures/item/cosa.png", bytes);
    mp.getClass().getMethod("registerPack", Plugin.class, Map.class).invoke(mp, this, files);
}
```

Metodi pubblici esposti da `MagixPack` (tutti raggiungibili solo per riflessione):

| Metodo | Cosa fa |
| --- | --- |
| `registerPack(Plugin owner, Map<String,byte[]> files)` | Registra (o sostituisce) il contenuto di `owner`. |
| `unregisterPack(Plugin owner)` | Toglie il contenuto registrato da `owner` (da chiamare al suo `onDisable`). |
| `isPackAvailable()` | true se il pacchetto e' pronto e scaricabile. |
| `isPackRequired()` | true se il pacchetto e' obbligatorio (config `required`). |
| `sendPackTo(Player p)` | Manda il pacchetto al giocatore. |
| `packPublicUrl()` | URL pubblico dello zip (null finche' il servizio non e' partito). |
| `customItem(String id)` | L'`ItemStack` di `items.yml` pronto da dare, o null se `id` non c'e'. |
| `customItem(String id, Player p)` | Come sopra, costruito per `p` (serve alle voci `player-avatar`). |
| `playerAvatar(Player p)` | L'avatar di `p` (la faccia della skin) come `Component`, o null se non e' ancora scaricato. |
| `playerAvatarAsync(String nome)` | Come sopra, aspettando il download (`CompletableFuture`, thread in sottofondo). |
| `customGlyph(String id, Player p)` | Come sotto, per `p`: la voce `player-avatar` e' il suo avatar (la faccia della skin). |
| `customGlyph(String id)` | Il `Component` Adventure (font gia' impostato) di `glyphs.yml`, o null se `id` non c'e'. |

Il momento giusto per registrarsi e' il proprio `onEnable`, con `softdepend: [MagixPack]` nel
proprio `plugin.yml`: cosi' MagixPack e' gia' abilitato (il metodo esiste) quando il chiamante
prova a usarlo, anche se lo zip vero e proprio si costruisce solo dopo.

## Personalizzare a mano, senza scrivere un plugin (come Oraxen)

`plugins/MagixPack/overrides/` (creata vuota gia' al primo avvio) e' per chi vuole aggiungere o
sostituire un file del pacchetto senza codice: ogni file li' dentro entra nello zip allo stesso
percorso relativo a quella cartella —

```
plugins/MagixPack/overrides/assets/minecraft/textures/gui/container/inventory.png
```

diventa `assets/minecraft/textures/gui/container/inventory.png` nel pacchetto — e **vince sempre**
su qualunque contenuto gia' presente (file propri di MagixPack o registrato da un plugin). Basta un
file + `/mpack reload`, nessuna ricompilazione ne' redeploy: stesso principio di Oraxen, che tiene
le proprie risorse nella cartella dati del plugin, non nel jar.

Attenzione alle texture vanilla con un layout fisso (es. l'inventario e' 176x166 px con le caselle
in posizioni scritte nel CLIENT, non nell'immagine): un file con proporzioni diverse viene scalato
comunque a quella dimensione, e puo' venire illeggibile se non e' stato disegnato apposta per quel
formato.

## Oggetti custom (texture e modello propri, come Oraxen)

`items.yml` (creata vuota gia' al primo avvio) e' il catalogo degli oggetti con texture E modello
propri — non un glifo: un vero modello 2D generato, come le icone vanilla. Per aggiungerne uno:

1. Metti la texture in `plugins/MagixPack/items/<id>.png` (stesso nome della chiave, es.
   `flaming_sword.png`).
2. Aggiungi la voce in `items.yml`:
   ```yaml
   flaming_sword:
     material: DIAMOND_SWORD
     name: "&cSpada Ardente"
     lore:
       - "&7Forgiata nel fuoco del Nether"
   ```
   `material` e' l'item base di Minecraft: decide le meccaniche (danno, durabilita',
   impilabilita'...), MAI l'aspetto — quello viene sempre dalla texture.
3. `/mpack reload`. Il plugin genera da solo tutto il JSON, non serve scriverlo a mano:
   - il modello (`assets/magixpack/models/item/<id>.json`, `parent: item/generated`, `layer0`
     sulla texture appena messa);
   - la DEFINIZIONE dell'oggetto (`assets/magixpack/items/<id>.json`, che richiama il modello) —
     quella che il componente `item_model` (impostato da `ItemMeta#setItemModel`) va a risolvere
     da quando i modelli degli item sono passati al sistema a componenti;
   - PER COMPATIBILITA', anche il meccanismo "vecchio" (`CustomModelData` + un predicate override
     sul modello vanilla dell'item base, es. `assets/minecraft/models/item/paper.json`) — la
     stessa doppia strada che raccomanda Oraxen (item_properties + model_data_ids) quando non e'
     certo quale dei due il client risolve davvero. Il file vanilla viene ricostruito per intero:
     sicuro solo per material semplici a icona piatta (vedi il limite sotto).

   Un oggetto senza la sua texture viene ignorato con un avviso in console.

In gioco: `/mpack item give <id> [giocatore]` (permesso `magixpack.item.give`), `/mpack item list`
per vedere il catalogo caricato. Niente crafting/shop qui dentro: quello si fa con altri strumenti
gia' presenti sul server (es. CMI).

### L'avatar del giocatore (`type: player-avatar`)

Una voce di `items.yml` con `type: player-avatar` non ha texture ne' `material`: e' un oggetto
costruito **per un giocatore**. L'icona e' la sua testa (con la sua skin vera) e, passandoci sopra,
la descrizione finisce con il suo **avatar** — la faccia piatta della skin, secondo strato
compreso, alta come una lettera, disegnata con caratteri-pixel del pacchetto (vedi "L'avatar come glifo" sotto). Nel catalogo
di default c'e' gia':

```yaml
avatar:
  type: player-avatar
  name: "&e{player}"
  lore:
    - "&7Il tuo avatar"
```

`{player}` diventa il nome del giocatore; le righe di `lore` vengono prima dell'avatar.

- `/mpack item give avatar [giocatore]` — l'avatar di chi lo riceve (aspetta, se serve, che la
  skin sia scaricata).
- In un menu di **MagixMenus**: `magixpack: avatar` al posto di `id:` — l'oggetto viene costruito
  per **chi guarda** il menu (`display_name` del menu ne cambia il nome, le righe di `lore` del
  menu vanno sopra la sua descrizione).
- Da un altro plugin: `customItem(String id, Player player)`.

### Modelli 3D veri (non la semplice icona piatta)

Il layer0 2D e' solo il caso automatico/predefinito. Per un modello 3D vero — un export da
Blockbench, o un JSON scritto a mano con `"elements"`/`"faces"` — metti il file in
`plugins/MagixPack/items/<id>-model.json`: viene usato COSI' COM'E' al posto della generazione
automatica (stesso principio di Oraxen, `generate_model: false, model: ...`). La texture in
`items/<id>.png` resta comunque obbligatoria, referenziata dal modello come `magixpack:item/<id>`.
`/mpack reload` come sempre per vederlo in gioco.

Se poi l'oggetto ha anche `furniture: true`, il fondo del modello viene appoggiato al terreno da
solo: la vera altezza del modello (letta dal suo `"elements"`) decide quanto va sollevato, non un
valore fisso — un cubo piu' basso di un blocco intero non resta ne' sprofondato ne' sospeso a
mezz'aria. Non serve regolare niente a mano, nemmeno per un modello futuro fatto in Blockbench.

Da un altro plugin: `MagixPack.customItem(String id)` (via riflessione, come `registerPack`)
restituisce l'`ItemStack` pronto, o null se l'id non e' nel catalogo.

### Piazzarlo per terra (furniture)

Un oggetto custom puo' anche diventare una "furniture": non solo in mano/inventario, ma piazzato
nel mondo col suo aspetto vero (texture/modello, non un blocco vanilla travestito). Nel catalogo:

```yaml
flaming_sword:
  material: DIAMOND_SWORD
  name: "&cSpada Ardente"
  furniture: true
  furniture-solid: true
  furniture-shift-required: false
  furniture-hits: 3
  furniture-drop: true
  furniture-hit-sound: BLOCK_STONE_HIT
  furniture-break-sound: BLOCK_STONE_BREAK
```

- `furniture: true` (default `false`) — lo rende piazzabile: **tasto destro sulla faccia
  SUPERIORE** di un blocco lo mette sopra (mai sui lati, apposta: su un terreno sconnesso
  cliccare il lato di un blocco piu' basso lo farebbe nascere piu' in basso del terreno intorno,
  con l'effetto di essere "affondato" — non e' un problema del modello). Chiunque lo tenga in
  mano lo puo' piazzare, nessun permesso a parte — la protezione (chi puo' piazzare/rompere dove)
  la fa la regione/claim gia' presente sul server, esattamente come per un blocco normale messo
  li'.
- `furniture-solid: true` (default `false`) — aggiunge collisione vera (un blocco invisibile,
  blocca il passaggio); `false` lo lascia attraversabile. Scelta per oggetto, non globale.
- `furniture-shift-required` (default `true`) — se serve tenere premuto **shift** durante il
  tasto destro per piazzarlo. Di default si', per non entrare in conflitto con l'uso normale del
  blocco cliccato (es. aprire un baule); `false` toglie l'obbligo. Scelta per oggetto.
- Si rompe **attaccando** l'entita' piazzata (niente tasto/comando a parte). `furniture-hits`
  (default `1`) — quanti colpi servono, contati sull'entita' stessa: due copie piazzate della
  stessa furniture si rompono in modo indipendente. `furniture-drop` (default `false`) — cosa
  succede all'ultimo colpo: `false` da' l'oggetto direttamente a chi ha colpito (o lo fa cadere se
  non c'e' posto), `true` lo fa cadere per terra come un blocco normale. `furniture-hit-sound` /
  `furniture-break-sound` (default `BLOCK_WOOD_HIT` / `BLOCK_WOOD_BREAK`) — un nome dell'enum
  Bukkit `Sound` (es. `BLOCK_STONE_HIT`, `ENTITY_VILLAGER_HURT`...) per il colpo che non rompe
  ancora e per quello che rompe davvero; un nome sbagliato o inesistente usa il default con un
  avviso in console, non blocca l'oggetto.

Sotto il cofano: una coppia `ItemDisplay` (l'aspetto) + `Interaction` (l'entita' invisibile su cui
si clicca/attacca davvero — un `ItemDisplay` da solo non e' interagibile), vedi
`furniture.FurnitureListener`. **Limite noto**: un plugin di protezione claim/regione pensato per
i BLOCCHI potrebbe non coprire da solo queste entita' — e' un rischio accettato, non un bug di
MagixPack.

## Icone custom via font (per chat/tablist, non per gli oggetti)

`glyphs.yml` (creata vuota gia' al primo avvio) e' il catalogo delle icone via font — per simboli
dentro un messaggio di chat o nel tablist, MAI per gli oggetti (quelli hanno il loro modello vero,
vedi sopra). Per aggiungerne una:

1. Texture in `plugins/MagixPack/glyphs/<id>.png` (stesso nome della chiave) — un'unica icona per
   immagine, non un atlas.
2. Voce in `glyphs.yml`:
   ```yaml
   star:
     height: 8
     ascent: 7
   ```
   `height`/`ascent` sono le stesse misure dei font bitmap vanilla (quanto e' alta l'icona e dove
   sta la base del testo rispetto al bordo alto dell'immagine).
3. `/mpack reload`.

### Grandezza e posizione, voce per voce

Ogni voce di `glyphs.yml` (icone e avatar) accetta tre chiavi facoltative:

| Chiave | Esempio | Effetto |
| --- | --- | --- |
| `scale` | `2` / `0.8` | 2 volte piu' grande / un quinto piu' piccola (1 = come e' disegnata; decimali ammessi). |
| `offset-x` | `3` / `-1.5` | pixel a destra / a sinistra. Il testo che la segue non si sposta (due spazi invisibili attorno all'icona). |
| `offset-y` | `2` / `-3` | pixel in su / in giu'. |

L'altezza nel font e' un numero intero di pixel: `scale` viene arrotondata al pixel (un avatar a
`0.8` e' alto 6 invece di 6,4). Per spostare un'icona piu' in alto di "base sulla riga" il client
non accetta il valore cosi' com'e': MagixPack aggiunge da solo righe trasparenti sotto l'immagine
(vedi `glyph.BitmapFit`), quindi non serve preparare immagini apposta. Un'icona piu' alta di una
lettera sporge sopra la riga: `/mpack glyph show` e la descrizione dell'oggetto avatar lasciano da
sole le righe vuote che servono.

### Carattere, placeholder o API: tre modi di usarla

Le icone vivono nel font **normale** di Minecraft (`minecraft:default`), come in Oraxen: il loro
carattere funziona in QUALUNQUE testo, non solo dove si puo' scegliere un font.

- **Il carattere** — lo assegna il plugin a **ogni avvio/reload**, in ordine alfabetico sugli id,
  nell'area privata Unicode da `U+E800` (sotto restano liberi per gli altri plugin: il logo del
  tablist di MagixFactions e' `U+E010`). `/mpack glyph list` lo mostra per ogni icona, coi pulsanti
  **[copia]** e **[in chat]**: si incolla in un messaggio, un config di CMI, un cartello... Stesso catalogo,
  stessi caratteri; ma se il catalogo cambia possono spostarsi.
- **Il placeholder** (PlaceholderAPI) — `%magixpack_glyph_<id>%`: non cambia mai, anche se il
  carattere si sposta. E' il modo giusto in un config che deve durare. Per una voce avatar da'
  l'avatar di chi legge; `%magixpack_glyph_<id>:<giocatore>%` quello di un altro giocatore online.
  Un'icona esce bianca (`§f`: il glifo prende il colore del testo prima di lui); un avatar finisce
  con `§r`, quindi il testo dopo riparte dal colore di base.
- **L'API** — `customGlyph(id)` / `customGlyph(id, giocatore)`, un `Component` Adventure.

Il file `default.json` il client lo prende **per intero** dal pacchetto, non lo fonde col suo: un
file con le sole icone cancellerebbe tutte le lettere (e' il motivo per cui l'esperimento della
cornice, prima di questa funzione, era stato tolto). Per questo quello generato qui richiama anche
i font vanilla (`include/space`, `include/default`, `include/unifont`), e il pacchetto **fonde** i
`default.json` di tutti i plugin (quello del logo di MagixFactions compreso) invece di tenerne uno
solo: prima tutti i caratteri custom, poi i font vanilla una volta sola. Se due plugin definiscono lo
stesso carattere, vince il primo e il log lo segnala.

Da un altro plugin:

```java
Plugin mp = Bukkit.getPluginManager().getPlugin("MagixPack");
Component icona = (Component) mp.getClass().getMethod("customGlyph", String.class).invoke(mp, "star");
if (icona != null) player.sendMessage(Component.text("Hai trovato una ").append(icona));
```

`/mpack glyph list` (permesso `magixpack.glyph.list`) mostra il catalogo caricato: per ogni icona il
carattere, il suo codice Unicode e il placeholder, con due pulsanti: `[copia]` (negli appunti)
e `[in chat]` (lo scrive nella barra della chat, da dove si usa subito o si copia con Ctrl+A e
Ctrl+C). Per un avatar i pulsanti danno il placeholder.

## L'avatar come glifo (chat, tablist)

In `glyphs.yml` c'e' la voce `avatar` (`type: player-avatar`, niente texture): compare in
`/mpack glyph list` e `/mpack glyph show avatar [giocatore]` la mostra in chat. `scale`, `offset-x`,
`offset-y` come ogni altra voce; se ne possono fare piu' di una con grandezze diverse (es.
`avatar_big` con `scale: 2`), ognuna coi suoi caratteri, e l'oggetto `avatar` di
`items.yml` sceglie quale usare con `glyph:` (default `avatar`). Da un altro plugin:
`customGlyph("avatar", giocatore)`.

La faccia della skin come **testo**, per chat e tablist (alta come una lettera): ovunque ci sia
PlaceholderAPI basta `%magixpack_glyph_avatar%`. Da codice, `playerAvatar(Player)` restituisce
il `Component` pronto (null finche' non e' scaricato), `playerAvatarAsync(String nome)` un
`CompletableFuture<Component>` che si completa su un thread in sottofondo (null se la skin non si
trova). E' lo stesso che finisce nella descrizione dell'oggetto `avatar`.

Come e' fatto: un resource pack e' uguale per tutti, quindi non puo' contenere un'immagine per
giocatore. Il pacchetto contiene solo, per ogni voce avatar, 8 caratteri "pixel" nel font normale
(il carattere `i` e' un pixel bianco sulla riga `i`) piu' due spazi, uno che torna indietro e uno che
avanza. L'avatar si compone a runtime colonna per colonna, un carattere-pixel COLORATO
per ogni pixel della faccia: 8x8 pixel al posto di un carattere, dentro la riga di testo.

Da dove arriva la skin: il server e' in offline-mode, quindi il profilo del giocatore di solito
non la ha. Si prova prima il profilo (un plugin di skin tipo SkinsRestorer ce la mette), poi, con
`avatar.mojang-lookup: true`, Mojang per NOME (la skin dell'account premium con quel nome). Tutto
in sottofondo, in cache per nome, riscaricato a ogni ingresso; chi non ha skin viene ritentato
solo dopo 10 minuti.

### Incollarlo in un vero messaggio di chat (non solo in un Component del plugin)

Gli 8 caratteri "pixel" + codici colore qui sopra funzionano SOLO dentro un `Component` che il
plugin costruisce lui stesso (tablist, scoreboard, l'anteprima di un comando): un giocatore che
scrive o incolla un messaggio di chat vero non puo' usarli — dalla chat firmata (1.19+) il testo
digitato arriva come stringa semplice, e un codice colore al suo interno resta testo letterale
invece di diventare colore (verificato in gioco).

Per questo `/mpack glyph show avatar` ha un secondo pulsante, **[copia]**, che da' tutt'altra cosa:
UN carattere vero (nessun codice colore), assegnato al volo la prima volta che quella faccia
esatta serve — come fa Oraxen per le teste custom, la faccia intera diventa una texture mappata su
un punto di codice, non piu' 8 caratteri riga colorati. Skin identiche riusano lo stesso carattere.
Incollato in un messaggio normale funziona per chiunque veda il pacchetto: nessuna risoluzione
lato server necessaria, e non serve riavviare — il pacchetto si ricostruisce e si rimanda da solo
a chi e' gia' online (vedi `avatar.AvatarGlyphRegistry`).

## Impilare piu' glifi: chi sta sopra (priority)

Due immagini si sovrappongono solo se sono nello stesso punto, e in Minecraft il font non ha uno
"z-index": un carattere scritto DOPO in una riga si disegna SOPRA quelli prima. MagixPack sfrutta
questo: ogni voce di `glyphs.yml` ha `priority` (da 0 a 100, anche decimali, default 0), e uno **stack** disegna i
glifi uno sopra l'altro nello stesso punto, in ordine di `priority` crescente — quello col numero
piu' alto e' scritto per ultimo, quindi sta sopra. A parita' vale l'ordine in cui li scrivi.

```yaml
avatar:
  type: player-avatar
  priority: 1
cornice:          # glyphs/cornice.png: bordo opaco, centro trasparente
  height: 8
  ascent: 7
  priority: 2     # sopra l'avatar; con 0 finirebbe sotto
```

- Placeholder: `%magixpack_stack_avatar,cornice%` (anche `...:<giocatore>` per l'avatar di un
  altro giocatore).
- Anteprima: `/mpack glyph show avatar,cornice [giocatore]`.
- API: `customGlyphStack(List.of("avatar", "cornice"), giocatore)`.

Come funziona: ogni voce ha nel font due spazi in piu', uno che torna indietro esattamente della
sua larghezza e uno che avanza della stessa (larghezza calcolata come fa il client: ultima colonna
non trasparente dell'immagine per la scala, +1). Nello stack ogni glifo e' seguito dal suo "torna
indietro", cosi' il successivo parte dallo stesso punto; alla fine l'"avanti" del piu' largo, cosi'
il testo dopo riparte dopo di lui. I glifi sono CENTRATI uno sull'altro, in larghezza e in
altezza. In larghezza: ognuno piu' stretto del piu' largo viene spostato di meta' della differenza
(pixel interi, i caratteri shift). In altezza la posizione di un glifo la fissa il font (il suo
ascent), non il testo: per questo ogni voce ha nel pacchetto anche una SECONDA copia, centrata su una
linea comune (il centro di un glifo alto come una lettera), ed e' quella che usano gli stack — da
sola la voce resta dov'e'. Per ritoccare a mano restano `offset-x` e `offset-y`, che valgono anche
nello stack (quindi per un centraggio esatto lasciali a 0).

## Spostare un carattere senza un glyphs.yml (shift)

Un set fisso di caratteri trasparenti, sempre disponibili, come lo `shifts.yml` di Oraxen — ma
generati invece che configurati: spostano quello che li segue di un tot di pixel GUI (negativo =
a sinistra), senza bisogno di una voce propria in `glyphs.yml` ne' del suo `offset-x` (che sposta
solo QUELLA icona, non un testo qualunque). `GlyphCatalog.shift(int pixel)` combina il minimo
numero di caratteri via il solito trucco binario (potenze di due: 1, 2, 4, 8, 16, 32, 64, 128 —
quindi qualunque spostamento fino a ±255 pixel con al massimo 8 caratteri); negativo per
spostare a sinistra.

```java
Plugin mp = Bukkit.getPluginManager().getPlugin("MagixPack");
String destra10px = (String) mp.getClass().getMethod("shift", int.class).invoke(mp, 10);
player.sendMessage(Component.text(destra10px + "testo spostato di 10px"));
```

Sono un'area di punti di codice a parte (gli ultimi 16 della zona privata Unicode, mai negli altri
due usati da MagixPack per icone/avatar): non cambiano mai, sicuri da scrivere a mano in un
messaggio o in un altro plugin.

## Config

- `public-host` / `port` — da dove i client scaricano lo zip (la porta va aperta sul firewall).
- `required` — pacchetto obbligatorio (default) o facoltativo.
- `send-delay-ticks` / `timeout-seconds` — tempistiche di invio ed espulsione.
- `watchdog-seconds` — ogni quanto si verifica che il pacchetto sia ancora scaricabile.
- `prompt` / `kick-messages.*` — testi mostrati al giocatore (colori `&` e `\n` per andare a capo).
- `avatar.mojang-lookup` — se chiedere la skin a Mojang per nome quando il profilo non la ha.

## Comandi

- `/mpack reload` (permesso `magixpack.admin`) — rilegge config.yml, items.yml e glyphs.yml,
  ricostruisce il pacchetto con le registrazioni gia' in mano e lo **rimanda a chi e' gia' online**
  (senza, un client connesso non saprebbe mai che lo zip e' cambiato: si manda da solo solo al
  join). `F3+T` dal client NON basta: ricarica solo i pacchetti gia' scaricati sul disco, non
  ricontatta il server. Non richiede di nuovo il contenuto agli altri plugin: se e' cambiato un
  LORO segnaposto, serve ricaricare (o riavviare) quel plugin.
- `/mpack item give <id> [giocatore]` / `/mpack item list` (permesso `magixpack.item.give`) — vedi
  "Oggetti custom" sopra.
- `/mpack glyph list` / `/mpack glyph show <id> [giocatore]` (permesso `magixpack.glyph.list`) — vedi
  "Icone custom via font" e "L'avatar come glifo" sopra.

## Permessi

- `magixpack.admin` (default op) — `/mpack reload`.
- `magixpack.bypass` (default op) — non viene mai espulso se il pacchetto obbligatorio non si
  carica (lo riceve comunque). Salvaguardia per non restare chiusi fuori dal proprio server.
- `magixpack.item.give` (default op) — `/mpack item give` e `/mpack item list`.
- `magixpack.glyph.list` (default op) — `/mpack glyph list` e `/mpack glyph show`.
