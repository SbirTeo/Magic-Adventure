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

### Modelli 3D veri (non la semplice icona piatta)

Il layer0 2D e' solo il caso automatico/predefinito. Per un modello 3D vero — un export da
Blockbench, o un JSON scritto a mano con `"elements"`/`"faces"` — metti il file in
`plugins/MagixPack/items/<id>-model.json`: viene usato COSI' COM'E' al posto della generazione
automatica (stesso principio di Oraxen, `generate_model: false, model: ...`). La texture in
`items/<id>.png` resta comunque obbligatoria, referenziata dal modello come `magixpack:item/<id>`.
`/mpack reload` come sempre per vederlo in gioco.

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
```

- `furniture: true` (default `false`) — lo rende piazzabile: **tasto destro** su un blocco lo
  mette sulla faccia cliccata. Chiunque lo tenga in mano lo puo' piazzare, nessun permesso a
  parte — la protezione (chi puo' piazzare/rompere dove) la fa la regione/claim gia' presente sul
  server, esattamente come per un blocco normale messo li'.
- `furniture-solid: true` (default `false`) — aggiunge collisione vera (un blocco invisibile,
  blocca il passaggio); `false` lo lascia attraversabile. Scelta per oggetto, non globale.
- `furniture-shift-required` (default `true`) — se serve tenere premuto **shift** durante il
  tasto destro per piazzarlo. Di default si', per non entrare in conflitto con l'uso normale del
  blocco cliccato (es. aprire un baule); `false` toglie l'obbligo. Scelta per oggetto.
- Si rompe **attaccando** l'entita' piazzata (niente tasto/comando a parte): torna nell'inventario
  di chi l'ha colpita (o cade a terra se non c'e' posto).

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

Il font e' custom (`magixpack:icons`), **mai** `minecraft:default`: quel file il client lo prende
per intero dal pacchetto con priorita' piu' alta, non lo fonde — un provider aggiunto li' sopra
cancellerebbe silenziosamente tutti i provider vanilla (e' il motivo per cui l'esperimento della
cornice, prima di questa funzione, e' stato tolto). Con un font a parte questo rischio non c'e'.

Il **punto di codice** (dove vive l'icona nello spazio Unicode) non si sceglie a mano: viene
assegnato in ordine alfabetico sugli id presenti, a partire da un'area privata Unicode dedicata
(mai in conflitto con un carattere vero). Puo' quindi CAMBIARE se il catalogo cambia — ecco perche'
un'icona si richiama sempre per NOME tramite l'API, mai scrivendo il carattere a mano:

```java
Plugin mp = Bukkit.getPluginManager().getPlugin("MagixPack");
Component icona = (Component) mp.getClass().getMethod("customGlyph", String.class).invoke(mp, "star");
if (icona != null) player.sendMessage(Component.text("Hai trovato una ").append(icona));
```

`/mpack glyph list` (permesso `magixpack.glyph.list`) mostra il catalogo caricato col punto di
codice di ognuna, utile per verificare cosa e' disponibile in questo momento.

## Config

- `public-host` / `port` — da dove i client scaricano lo zip (la porta va aperta sul firewall).
- `required` — pacchetto obbligatorio (default) o facoltativo.
- `send-delay-ticks` / `timeout-seconds` — tempistiche di invio ed espulsione.
- `watchdog-seconds` — ogni quanto si verifica che il pacchetto sia ancora scaricabile.
- `prompt` / `kick-messages.*` — testi mostrati al giocatore (colori `&` e `\n` per andare a capo).

## Comandi

- `/mpack reload` (permesso `magixpack.admin`) — rilegge config.yml, items.yml e glyphs.yml,
  ricostruisce il pacchetto con le registrazioni gia' in mano e lo **rimanda a chi e' gia' online**
  (senza, un client connesso non saprebbe mai che lo zip e' cambiato: si manda da solo solo al
  join). `F3+T` dal client NON basta: ricarica solo i pacchetti gia' scaricati sul disco, non
  ricontatta il server. Non richiede di nuovo il contenuto agli altri plugin: se e' cambiato un
  LORO segnaposto, serve ricaricare (o riavviare) quel plugin.
- `/mpack item give <id> [giocatore]` / `/mpack item list` (permesso `magixpack.item.give`) — vedi
  "Oggetti custom" sopra.
- `/mpack glyph list` (permesso `magixpack.glyph.list`) — vedi "Icone custom via font" sopra.

## Permessi

- `magixpack.admin` (default op) — `/mpack reload`.
- `magixpack.bypass` (default op) — non viene mai espulso se il pacchetto obbligatorio non si
  carica (lo riceve comunque). Salvaguardia per non restare chiusi fuori dal proprio server.
- `magixpack.item.give` (default op) — `/mpack item give` e `/mpack item list`.
- `magixpack.glyph.list` (default op) — `/mpack glyph list`.
