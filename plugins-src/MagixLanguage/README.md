# MagixLanguage

Plugin per **MAGICADVENTURE** (Paper 26.x) che rileva la lingua di chi entra dal paese di
provenienza (GeoIP sull'IP di ingresso) e tiene sincronizzata una traduzione dei messaggi degli
altri plugin Magix, cosi' chi lo desidera puo' rispondere gia' nella lingua giusta invece di
sempre e solo in italiano.

Versione: **0.1.0** — questo file viene riscritto in `plugins/MagixLanguage/README.md` ad ogni
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

**Traduzione dei messaggi degli altri plugin.** Ad ogni avvio (e a comando) il plugin legge
`messages.yml` di ogni plugin Magix elencato nel config e ne copia il testo in
`plugins/MagixLanguage/translations/<Plugin>/it.yml` — uno **specchio** di quello che il plugin sta
davvero usando, non una traduzione. Per ciascuna delle altre lingue, le chiavi nuove arrivano nel
file corrispondente (`en.yml`, `es.yml`, `de.yml`) con il testo italiano come segnaposto, pronte
perche' lo staff le traduca cambiando solo il valore. Le chiavi ancora da tradurre finiscono in
`translations/PENDING-<lingua>.txt`, rigenerato ad ogni sincronizzazione.

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
dei file da cercare nella loro cartella dati (`translations.files`, di serie solo `messages.yml`).

I testi mostrati ai giocatori (`/language ...`) sono in `messages.yml`, come in ogni plugin Magix.

### Come si traduce una chiave nuova

1. Lancia `/language sync` (o aspetta il prossimo riavvio).
2. Apri `plugins/MagixLanguage/translations/PENDING-<lingua>.txt`: elenca ogni chiave ancora
   identica al testo italiano, per plugin.
3. Apri `plugins/MagixLanguage/translations/<Plugin>/<lingua>.yml` e cambia il **valore** delle
   chiavi elencate (mai il nome a sinistra dei due punti).
4. Questi file vengono riscritti per intero ad ogni sincronizzazione: solo i valori sono al
   sicuro, eventuali commenti aggiunti a mano vengono persi.

`translations/<Plugin>/it.yml` non si modifica mai a mano: e' uno specchio del `messages.yml`
vero, che si cambia nella cartella del plugin originale.

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

---

## Note

- Il servizio GeoIP di serie (`ip-api.com`) e' gratuito e non richiede una chiave, ma manda l'IP di
  chi si collega a un servizio esterno: disattivabile con `geoip.enabled: false`.
- Le lingue multi-paese (es. la Svizzera) usano una mappatura approssimata: la lingua piu' diffusa.
