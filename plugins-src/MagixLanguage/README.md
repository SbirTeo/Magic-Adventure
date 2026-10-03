# MagixLanguage

Plugin per **MAGICADVENTURE** (Paper 26.x) che rileva la lingua di chi entra dal paese di
provenienza (GeoIP sull'IP di ingresso) e tiene sincronizzata una traduzione dei messaggi degli
altri plugin Magix, cosi' chi lo desidera puo' rispondere gia' nella lingua giusta invece di
sempre e solo in italiano.

Versione: **0.5.0** — questo file viene riscritto in `plugins/MagixLanguage/README.md` ad ogni
avvio del server.

---

## Un plugin per tutta la rete

MagixLanguage gira su **ogni** server della rete: faction, hub, il proxy Velocity e le modalità
future. È lo stesso jar dappertutto (`deploy.target` = `faction hub velocity`): Paper legge
`plugin.yml` e avvia `MagixLanguage`, Velocity legge `velocity-plugin.json` e avvia
`velocity/MagixLanguageVelocity`. Il nucleo (traduttore, pacing, glossario, database) non usa né
Bukkit né Velocity.

- **Traduce da solo i plugin Magix installati dove gira**: ogni plugin `Magix*` caricato su quel
  server, più quelli di `translations.plugins`. Un plugin nuovo non va aggiunto a mano.
- **La lingua del giocatore vale su tutta la rete**: tabella `language_players` del database del sito
  (credenziali dal config di MagixAuth, `database.shared_with`, primo file che esiste). Si scrive a
  ogni scelta (GeoIP o `/language set`) e si rilegge a ogni ingresso; `players.yml` resta la copia
  locale se il database non risponde. Al primo avvio col database i `players.yml` dei server ci
  vengono portati (una scelta manuale vince su una rilevata dal GeoIP).
- **Sul proxy**: traduce MagixProxy in `velocity/plugins/magixlanguage/translations/magixproxy/`
  (stesse regole, stessi `-overrides.yml`) e risponde ai plugin del proxy con
  `translate(plugin, uuid, chiave, segnaposti)`. Rilevazione GeoIP e comando `/language` restano sui
  server di gioco; finché un giocatore nuovo non è classificato, il proxy usa `default-language`.

---

## Cosa fa

**Rilevazione automatica.** Al primo ingresso di un account, prima ancora che entri nel mondo, si
interroga un servizio GeoIP gratuito con l'IP di chi si sta collegando: dal paese restituito si
ricava la lingua tramite la mappa `country-language` del config. Un IP privato (127.x, 192.168.x,
un tunnel di sviluppo), un servizio non raggiunto entro il timeout, o un paese non elencato: si usa
`default-language`. Le lingue di serie sono **italiano, inglese, spagnolo e tedesco**.

**Scelta manuale.** La lingua rilevata si salva per sempre: un ingresso successivo non la cambia
piu' da solo. Si corregge con `/language set <it|en|es|de>` (per se stessi) o, con il permesso da
staff, per un altro giocatore.

**Traduzione automatica dei messaggi degli altri plugin.** Ad ogni avvio (e a comando) il plugin
legge `messages.yml` di **ogni plugin Magix installato** — trovati da soli, MagixLanguage compreso
(anche i suoi messaggi passano dai cataloghi); `translations.plugins` serve solo ad aggiungere
plugin di altri — e ne copia il testo in
`plugins/MagixLanguage/translations/<Plugin>/it.yml` — uno **specchio** di quello che il plugin sta
davvero usando, non una traduzione. Per ciascuna delle altre lingue, ogni chiave nuova o il cui
testo italiano e' cambiato (un colore, una formattazione...) viene tradotta **da sola**, tramite
l'API gratuita di MyMemory, e scritta in `en.yml`/`es.yml`/`de.yml`: non serve alcun intervento
per avere subito un testo in ogni lingua. Una cache (`.cache-<lingua>.yml`, per uso interno) evita
di ritradurre le chiavi rimaste invariate.

**Quante chiavi mancano ancora.** Ad ogni sincronizzazione (avvio o `/language sync`) il log del
server stampa una riga per ogni plugin scandito, con quante chiavi ha in italiano, quante sono
state tradotte in questo giro, quante gia' in cache e quante restano ancora mancanti. Lo stesso
dato, sempre dell'ultima sincronizzazione, si vede in gioco con `/language status` — utile quando
il servizio di traduzione ha un limite giornaliero (vedi sotto) e conviene sapere a che punto e'
rimasto, senza dover leggere il log della console.

**Correzioni dello staff.** Se una traduzione automatica non convince, la si corregge mettendo la
STESSA chiave in `translations/<Plugin>/<lingua>-overrides.yml`: quel file non viene mai letto ne'
toccato dalla sincronizzazione, e vince sempre su quanto tradotto in automatico — anche se il testo
italiano cambia di nuovo in seguito. Un file overrides vuoto (con le istruzioni) viene creato da
solo la prima volta per ogni plugin/lingua.

Le chiavi che la traduzione automatica non riesce a tradurre (rete, quota giornaliera del servizio
esaurita) restano temporaneamente in italiano e finiscono in
`translations/TRANSLATION-FAILED-<lingua>.txt`, rigenerato ad ogni sincronizzazione: si riprova da
sola al giro successivo, senza bisogno di intervenire.

**Le righe di aiuto.** In una riga come `/f join <fazione> :: entra se invitato`, a MyMemory va solo
la descrizione dopo `::`. La sintassi del comando resta com'e', tranne le parole tra `< >` e `[ ]`
elencate in `src/main/resources/argument-glossary.yml` (`words`: nomi degli argomenti, tradotti a
mano); quelle in `keep` (`on|off`, `clear`, `pubblico`...) il giocatore le scrive cosi' e non si
toccano. Lo stesso glossario traduce ogni `<...>` negli altri messaggi (`Uso: /f join <fazione>`):
il traduttore lo protegge per intero e al ritorno lo rimette tradotto. Un argomento nuovo va
aggiunto al glossario: `check_config.py` [8] blocca il commit altrimenti.

**Colori, placeholder e comandi.** Prima di mandare il testo a MyMemory, colori (`&7`, `&#RRGGBB`),
placeholder (`%...%`, `{...}`), `« »`, `|` e i nomi dei comandi (`/missioni`, `/f`) diventano
segnaposto che il servizio non tocca, e al ritorno si rimettono com'erano. Pezzi attaccati fra loro
(`&e%magixfactions_claims%`) formano un segnaposto solo, e devono restare attaccati anche nella
traduzione: una traduzione in cache che li ha separati o persi (quelle fatte fino al 3/10, con `?` o
`and` in mezzo) si butta e si rifà da sola al giro successivo, sia per i messaggi sia per le frasi
fuori da `messages.yml` (scoreboard, menu).

**Quando si richiama MyMemory.** Due regole, condivise fra i plugin e il sito e salvate in
`translation-pacing.properties` perche' un riavvio non le azzeri:
1. dopo un **blocco** (tre richieste rifiutate di fila, di solito la quota del giorno finita) nessuno
   richiama MyMemory fino alla fine della pausa — quella che indica MyMemory stessa nella risposta,
   altrimenti `pause-after-block-minutes` — e poi si riprende **da soli**, senza aspettare un riavvio;
2. senza blocchi, le chiavi dei plugin rimaste in italiano si riprovano al massimo ogni
   `retry-interval-minutes`, riavvii compresi (ogni tentativo consuma quota).

`/language status` dice fra quanto si riprova; `/language sync force` ignora entrambe le regole e
riprova subito. Prima c'era un solo tentativo al giorno: se capitava mentre MyMemory era ancora
bloccato (al riavvio notturno), la giornata andava persa a zero chiavi tradotte.

**Il testo dei MENU (MagixMenus).** `messages.yml` copre i comandi di un plugin, ma i menu vivono
in file YAML liberi (`plugins/MagixMenus/menus/*.yml`) senza chiavi stabili: il nome di un item e'
un identificatore tecnico, non una frase. Per questo il testo dei menu si traduce per **FRASE**
invece che per chiave: ogni riga italiana (titolo del menu, nome/descrizione di un item, corpo e
bottoni di una finestra di dialogo, il testo dentro `message:`/`broadcast:`/`title:`/`actionbar:`)
diventa la propria chiave, e chi la mostra manda la stessa frase italiana alla ricerca — se c'e' una
traduzione la usa, altrimenti resta in italiano. Materiali, permessi, equazioni, suoni, nomi di menu
e comandi non vengono mai toccati. I file (per plugin, per lingua) sono
`translations/<Plugin>/menu-phrases-<lingua>.yml` (generato) e
`translations/<Plugin>/menu-phrases-<lingua>-overrides.yml` (le correzioni dello staff: qui la
chiave e' la frase italiana esatta, non un percorso — funziona anche per una frase che la scansione
automatica non ha trovato da sola, es. dentro un blocco `if/then/else` di un'azione). Le chiavi
tradotte/mancanti dei menu sono gia' incluse nei numeri di `/language status` e nel log di
sincronizzazione, insieme a quelle di `messages.yml`.

**Una lingua alla volta, dalla più usata.** Si traduce una lingua su tutti i plugin prima di
passare alla successiva, in ordine di quanti giocatori l'hanno scelta: quando la quota giornaliera
di MyMemory finisce a metà giro, restano indietro le lingue usate di meno, non metà dei plugin.

**I testi fuori da `messages.yml` (`translatable.yml`).** Righe della scoreboard, pannello sotto la
minimap, titoli dei territori, nomi di relazioni/gradi/stagioni, richiesta e kick del pacchetto
risorse, messaggi di ban/kick/mute, suggerimento della chat, oggetti fissi, i `msg:` delle entità
stanno nei config dei plugin. Ogni plugin li dichiara nel `translatable.yml` dentro il proprio jar:

```yaml
config.yml:
  - "scoreboards.*.lines.*.frames"     # '*' = ogni chiave di una sezione o ogni voce di una lista
entities.yml:
  - path: "entities.*.commands"        # solo le righe che cominciano con prefix,
    prefix: "msg:"                     # e la frase è quello che segue
```

MagixLanguage legge quei percorsi nel file **vero** del server (quello scritto dallo staff) e li
traduce per frase, negli stessi file `menu-phrases-<lingua>.yml` dei menu (correzioni in
`menu-phrases-<lingua>-overrides.yml`). Chi mostra il testo lo passa da `Messages.phrase(giocatore,
testo)` prima di sostituire segnaposti e placeholder. Un testo nuovo per i giocatori fuori da
`messages.yml` va dichiarato lì nello stesso commit. Un testo su più righe (i kick) si traduce riga
per riga. `MagixLanguageAPI.translatePhrase(plugin, lingua, testo)` serve per chi non è ancora in
gioco (il messaggio di un ban, prima dell'ingresso).

**Nessuna traduzione automatica dei messaggi in gioco.** MagixLanguage non intercetta i messaggi
degli altri plugin da solo: mette a disposizione i cataloghi tradotti tramite `MagixLanguageAPI`
(softdepend, `ServicesManager`), che un altro plugin puo' chiamare per mandare un testo gia' nella
lingua del giocatore.

---

## Comandi

Comando principale: `/magixlanguage` — alias: `/language`, `/lang`.

| Comando | Cosa fa | Permesso |
|---|---|---|
| `/language` | Mostra la lingua attuale, come e' stata scelta e il paese rilevato | `magixlanguage.use` |
| `/language set <it\|en\|es\|de>` | Cambia la propria lingua | `magixlanguage.use` |
| `/language set <it\|en\|es\|de> <giocatore>` | Cambia la lingua di un altro giocatore | `magixlanguage.admin` |
| `/language sync` | Ricopia i messaggi degli altri plugin da tradurre | `magixlanguage.admin` |
| `/language sync force` | Come sopra, ma riprova subito su MyMemory anche durante una pausa dopo un blocco | `magixlanguage.admin` |
| `/language status` | Quante chiavi sono tradotte/in cache/mancanti, plugin per plugin (ultima sincronizzazione) | `magixlanguage.admin` |
| `/language reload` | Ricarica `config.yml` e `messages.yml` a caldo | `magixlanguage.admin` |
| `/language help` | Elenco dei comandi | `magixlanguage.use` |

### Permessi

| Permesso | Significato | Di serie |
|---|---|---|
| `magixlanguage.use` | Vedere e cambiare la propria lingua | tutti |
| `magixlanguage.admin` | Sincronizzazione, reload, lingua di un altro giocatore | operatori |

---

## Configurazione

Tutto in `config.yml`: `default-language`, `supported-languages`, la sezione `geoip` (servizio
usato, timeout, durata della cache) e la mappa `country-language` (paese ISO 3166-1 alpha-2 ->
lingua). La sezione `translations` elenca i plugin da scandire (`translations.plugins`) e i nomi
dei file da cercare nella loro cartella dati (`translations.files`, di serie solo `messages.yml`),
oltre a `translations.auto-translate` (`enabled`, `timeout-ms`, `delay-ms`, `contact-email`,
`pause-after-block-minutes`, `retry-interval-minutes`).

I testi mostrati ai giocatori (`/language ...`) sono in `messages.yml`, come in ogni plugin Magix.

### Come si corregge una traduzione automatica

1. Apri `plugins/MagixLanguage/translations/<Plugin>/<lingua>.yml` per vedere cosa e' stato
   tradotto in automatico (NON si modifica qui: viene riscritto per intero a ogni
   sincronizzazione).
2. Copia la chiave che non convince, con lo stesso percorso annidato, in
   `plugins/MagixLanguage/translations/<Plugin>/<lingua>-overrides.yml` (creato gia' vuoto, con le
   istruzioni, al primo avvio) e scrivi li' il testo corretto.
3. Lancia `/language sync` (o aspetta il prossimo riavvio): quella chiave da quel momento viene
   presa SEMPRE da `<lingua>-overrides.yml`, mai piu' dalla traduzione automatica — anche se il
   testo italiano cambia di nuovo in seguito.
4. Per tornare alla traduzione automatica basta togliere la chiave da `<lingua>-overrides.yml`.

`translations/<Plugin>/it.yml` non si modifica mai a mano: e' uno specchio del `messages.yml`
vero, che si cambia nella cartella del plugin originale — cambiarlo li' (compreso un semplice
colore) fa ripartire da sola la traduzione automatica di quella chiave in tutte le lingue non
corrette a mano.

---

## API per gli altri plugin

Un altro plugin (softdepend) puo' chiedere un testo gia' tradotto:

```java
RegisteredServiceProvider<MagixLanguageAPI> rsp =
        Bukkit.getServicesManager().getRegistration(MagixLanguageAPI.class);
if (rsp != null) {
    String testo = rsp.getProvider().translate("MagixTime", player, "info-paused", Map.of());
}
```

Senza quella chiamata un plugin continua a parlare solo in italiano, come sempre: MagixLanguage non
cambia da solo il comportamento di nessun altro plugin.

C'e' anche `translateRawBatch(List<String> testiItaliani, String lingua)`, per chi (oggi solo
MagixBridge) deve tradurre testo SENZA una chiave stabile ne' un catalogo di plugin — il sito, che
accoda le frasi delle sue pagine in un database e chiede a MagixLanguage di smaltirle un lotto
alla volta. Una sola chiamata per tutto il lotto condivide un solo `Translator` (un solo circuit
breaker), e usa la stessa `translations.auto-translate` (quota, contatto, ritmo) dei plugin.

---

## Note

- Il servizio GeoIP di serie (`ip-api.com`) e' gratuito e non richiede una chiave, ma manda l'IP di
  chi si collega a un servizio esterno: disattivabile con `geoip.enabled: false`.
- Le lingue multi-paese (es. la Svizzera) usano una mappatura approssimata: la lingua piu' diffusa.
