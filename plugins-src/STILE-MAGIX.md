# Stile dei plugin Magix

I plugin scritti per MAGICADVENTURE (MagixAuth, MagixFactions, MagixEntities, MagixTime,
MagixGuard, MagixWeb) parlano in chat con la **stessa voce**: stessi colori, stesso
cartellino davanti alle risposte, stesso elenco dei comandi. Chi gioca non deve accorgersi
che dietro ci sono plugin diversi.

Questo documento e' la fonte: se si cambia qualcosa qui, va cambiato in tutti i plugin.

---

## 1. La palette

I due colori principali sono quelli del logo del server: il **viola** di *MAGIC* e il
**verde** di *ADVENTURE*.

| Ruolo | Codice | Dove si usa |
|---|---|---|
| viola marchio | `&#C046E8` | nome del plugin nel prefisso, titoli, sezioni, comandi da staff |
| verde marchio | `&#A8DC2C` | comandi, conferme, tutto cio' che si puo' cliccare |
| grigio | `&#9A8CA8` | testo di servizio: spiegazioni, argomenti, note |
| tenue | `&#5A5068` | cornici, separatori, frecce spente (in chat va bene anche `&8`) |
| errore | `&#FF6B6B` | l'operazione non e' andata |
| avviso | `&#FFD166` | uso sbagliato del comando, avvertimenti |

Regola d'oro: **verde = cosa puoi fare, viola = chi parla e cosa comanda, grigio = cosa
significa**. Il rosso e il giallo restano per errori e avvisi, dove il colore semantico
conta piu' del marchio.

I codici esadecimali `&#RRGGBB` funzionano solo se il testo passa da `Colors.translate`
(o dal `LegacyComponentSerializer` con `hexColors()`): la traduzione classica di Bukkit
li stamperebbe a schermo per esteso.

---

## 2. Il prefisso

```
prefix: "&#C046E8&lMagixNome &8» &r"
```

Nome del plugin in viola grassetto, doppia freccia grigio scuro, poi il messaggio.
Sta in `messages.yml`, cosi' si cambia senza ricompilare.

**Dove va e dove non va:**

- **si'** sulle risposte ai comandi, sugli avvisi e sulle notifiche a un giocatore;
- **no** dentro i pannelli (l'aiuto, `/f info`, `/f list`, `/f map`, `/mtime info`): una
  cornice ha senso attorno a una risposta, non ripetuta dodici volte dentro una scheda;
- **no** su titoli a schermo, action bar e messaggi di kick, che hanno una forma loro.

Nel codice questo si traduce in due metodi distinti: `msg(...)` mette il prefisso,
`panel(...)` no.

---

## 3. L'elenco dei comandi

La classe `Help` e' lo **stesso file** in ogni plugin (cambia solo il `package`):
`src/main/java/com/teolo/<plugin>/util/Help.java`. Modificandola in uno, va riportata
negli altri — la copia identica e' verificata da `check_config.py` (`COMMON_CLASSES`).

Cosa fa:

- mostra **8 righe per pagina** — la chat ne mostra una decina, il resto scorrerebbe via;
- raggruppa i comandi in **sezioni** con un titolo, e ripete il titolo se la sezione
  prosegue nella pagina dopo;
- ogni riga e' **cliccabile**: il comando finisce nella barra della chat gia' scritto
  (*suggerito*, non eseguito: quasi tutti vogliono un argomento);
- in fondo le **frecce** `‹ indietro · avanti ›`, spente dove non c'e' nulla;
- le voci marcate `staff` le vede solo chi ha il permesso di amministrazione.

Ogni testo che il giocatore legge qui (titoli, spiegazioni, la cornice "clicca per
scriverlo"/le frecce) e' **tradotto** tramite MagixLanguage, come qualunque altro testo
(vedi CLAUDE.md, "OGNI TESTO CHE UN GIOCATORE LEGGE VA IN messages.yml"). Ma `Help.java`
non puo' importare la classe `Messages` di UN plugin specifico — altrimenti non sarebbe
piu' lo stesso file negli altri — quindi legge tutto tramite due piccole interfacce
dichiarate al suo interno:

```java
public interface Text  { String get(CommandSender to, String path, String... kv); }
public interface Lines { List<String> get(CommandSender to, String path); }
```

Chi chiama passa un riferimento al proprio metodo (es. `messages::get` o
`messages::forPlayer`, a seconda di come si chiama nel plugin), senza che `Help.java` sappia
nulla della classe `Messages` dietro. Il testo di cornice vive sotto `help.chrome.*` in
ciascun `messages.yml` (stesse sette chiavi ovunque: `no-commands`, `click-to-write`,
`staff-only`, `click-hint`, `back`, `forward`, `page`).

Le voci si scrivono in `messages.yml`:

```yaml
help:
  title: "MagixFactions"
  chrome:
    no-commands: "Nessun comando disponibile."
    click-to-write: "Clicca per scriverlo"
    staff-only: "Riservato allo staff"
    click-hint: "clicca un comando per scriverlo"
    back: " ‹ indietro "
    forward: " avanti › "
    page: "Pagina {numero}"
  sections:
    territorio:
      title: "Territorio"
      staff: false          # true = sezione riservata a chi amministra
      entries:
        - "/f claim :: conquista il territorio in cui ti trovi"
```

Formato di una riga: `comando [argomenti] :: spiegazione`. Il **comando** e' la parte
iniziale fatta di parole semplici (`/f claim`, `/mentities cmd`), ed e' quella che il clic
scrive; tutto cio' che comincia con `<` o `[` resta da riempire a mano e va in grigio.

Il comando le legge con `Help.fromConfig(messages.section("help.sections"), sender,
messages::get, messages::getList)` e le mostra con `Help.show(sender, messages::get, title,
"/plugin help", entries, page, staff)`.

Ogni plugin espone `help [pagina]`, accetta `?` come sinonimo e accetta **il numero da
solo** (`/f 3`): e' quello che mandano le frecce.

| Plugin | Comando | Radice per le frecce |
|---|---|---|
| MagixAuth | `/mauth [pagina]` | `/mauth help` |
| MagixFactions | `/f help [pagina]` | `/f help` |
| MagixEntities | `/mentities help [pagina]` | `/mentities help` |
| MagixTime | `/mtime help [pagina]` | `/mtime help` |
| MagixMenus | `/menus help [pagina]` | `/menus help` |
| MagixCosmetics | `/cosmetics help [pagina]` | `/cosmetics help` |
| MagixMusic | `/radio help [pagina]` | `/radio help` |

`Help.java` e' presente anche in MagixPack, ma inutilizzato (nessun comando lo chiama).

---

## 4. Simboli

Sempre gli stessi, cosi' si riconoscono a colpo d'occhio:

| Simbolo | Significato |
|---|---|
| `✔` | fatto |
| `✖` | errore |
| `▸` | uso del comando / titolo di sezione |
| `‹ ›` | sfogliare le pagine |
| `│` `└` | righe di un elenco |
| `»` | il prefisso |
| `─────` | la cornice dell'intestazione |

---

## 5. Come si prova senza avviare il server

`Help` non ha bisogno di un server: legge un `messages.yml` con `YamlConfiguration` e
scrive su un `CommandSender`. Per vedere l'impaginazione basta un `CommandSender` finto
(un `Proxy` che stampa i componenti come testo) e il `target/classes` del plugin — utile
per controllare dove cadono i tagli di pagina prima di caricare il jar.
