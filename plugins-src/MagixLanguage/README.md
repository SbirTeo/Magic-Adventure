# MagixLanguage

Plugin per **MAGICADVENTURE** (Paper 26.x) che rileva la lingua di chi entra dal paese di
provenienza (GeoIP sull'IP di ingresso) e tiene sincronizzata una traduzione dei messaggi degli
altri plugin Magix, cosi' chi lo desidera puo' rispondere gia' nella lingua giusta invece di
sempre e solo in italiano.

Versione: **0.3.0** — questo file viene riscritto in `plugins/MagixLanguage/README.md` ad ogni
avvio del server.

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
legge `messages.yml` di ogni plugin Magix elencato nel config e ne copia il testo in
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
oltre a `translations.auto-translate` (`enabled`, `timeout-ms`, `delay-ms`, `contact-email`).

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
MagixWeb) deve tradurre testo SENZA una chiave stabile ne' un catalogo di plugin — il sito, che
accoda le frasi delle sue pagine in un database e chiede a MagixLanguage di smaltirle un lotto
alla volta. Una sola chiamata per tutto il lotto condivide un solo `Translator` (un solo circuit
breaker), e usa la stessa `translations.auto-translate` (quota, contatto, ritmo) dei plugin.

---

## Note

- Il servizio GeoIP di serie (`ip-api.com`) e' gratuito e non richiede una chiave, ma manda l'IP di
  chi si collega a un servizio esterno: disattivabile con `geoip.enabled: false`.
- Le lingue multi-paese (es. la Svizzera) usano una mappatura approssimata: la lingua piu' diffusa.
