# Guida per amministratori - tutorial dello staff, sempre aggiornato

Deciso il 2026-08-29. Vale per **tutti** i plugin Magix, non per uno solo.

I giocatori hanno `/tutorial` sul sito, generato da una fonte sola e mai scritto a mano
(vedi la catena di `build_tutorial.py`). Lo **staff** merita la stessa cosa: una guida vera,
leggibile, che spiega cosa fanno i nostri plugin e come si usano — e che **non puo' invecchiare**,
perche' la scrive il plugin stesso a ogni avvio.

Vive nel gestionale, sezione **Guida per amministratori** (`/manage.php?section=guida`).

---

## 1. Il principio

> La guida non si scrive a mano. La scrive il plugin, e il plugin non puo' mentire su se stesso.

Un README aggiornato a mano diverge dal codice nel giro di due versioni: e' gia' successo con i
config. Qui la parte che cambia piu' spesso — comandi, permessi, chiavi di configurazione, versione
— viene **letta dal plugin a runtime**, non ricopiata. Se aggiungo un comando e mi dimentico di
documentarlo, compare lo stesso nella guida.

---

## 2. Come ci arriva

Ogni plugin Magix, all'avvio e a ogni `reload`, compone il proprio **capitolo** e lo consegna.
Non scrive sul database del sito: **le credenziali del sito le ha solo MagixWeb**, ed e' giusto
che resti cosi'.

```
MagixFactions ─┐
MagixAuth      │   ognuno scrive
MagixEntities  ├─→ plugins/<Nome>/guida-staff.html ─→ MagixWeb ─→ magicadventure_web.guide_staff
MagixTime      │                                     (GuideSync)              │
MagixWeb       │                                                              v
MagixGuard    ─┘                                   /manage.php?section=guida  (gestionale)
```

- `StaffGuide` e' la classe comune, **stesso file in ogni plugin** con il solo `package` diverso:
  `src/main/java/com/teolo/<plugin>/util/StaffGuide.java`. Stessa regola di `Help` (vedi
  [STILE-MAGIX.md](STILE-MAGIX.md)): la si modifica in uno, si riporta in tutti.
- Ogni plugin, in fondo a `onEnable()`, compone il capitolo e lo scrive nella **propria cartella**
  come `guida-staff.html`, con un'intestazione fra commenti (`titolo`, `versione`, `ordine`).
- `GuideSync` di MagixWeb passa in rassegna le cartelle dei plugin, legge i capitoli e li riversa
  nella tabella: una sola penna sul database del sito, come per le sanzioni.

**Perche' un file e non un servizio Bukkit.** Un servizio avrebbe richiesto che i plugin si
conoscessero a vicenda e avrebbe reso l'esito dipendente dall'**ordine di avvio**: chi parte prima
di MagixWeb non troverebbe nessuno a cui consegnare. Col file non conta l'ordine, non conta se
MagixWeb e' spento, e se un capitolo non arriva sul sito resta li' sul disco da guardare — che e'
esattamente cio' che serve quando qualcosa non torna.

**README e guida sono la stessa cosa.** `StaffGuide.write()` emette **due formati dello stesso
testo**: `guida-staff.html` per il sito e `README.md` per chi guarda i file del server. La vecchia
copia del README dal jar e' stata tolta da tutti i plugin — era proprio quella a permettere che i
due divergessero. Non vanno riallineati a mano perche' non possono disallinearsi.

**Il tutorial dei giocatori resta separato**: `/tutorial` e' per chi gioca, questa e' per lo staff.
Sono due pubblici diversi e due testi diversi (vedi la sezione 7).

---

## 3. Cosa c'e' dentro un capitolo

Ogni capitolo ha la stessa ossatura, cosi' lo staff sa sempre dove guardare:

1. **A cosa serve** - due righe oneste, in italiano, senza gergo.
2. **Comandi** - tabella generata da `plugin.yml` e dalle voci di `Help`: comando, cosa fa,
   permesso, esempio. Non puo' divergere dal codice perche' e' il codice a fornirla.
3. **Chi puo' fare cosa** - i permessi raggruppati per grado LuckPerms, con i tetti dove esistono
   (in MagixGuard: fino a che durata puo' sanzionare ogni grado).
4. **Configurazione** - le chiavi che lo staff puo' toccare davvero, con il valore **attualmente
   in uso** letto dal config vivo, non quello di esempio.
5. **Quando qualcosa non va** - i tre o quattro guasti tipici di quel plugin e cosa fare, scritti
   da chi il plugin l'ha scritto.
6. **Cosa non fare** - le trappole vere. Es. "non modificare il config live a mano: al prossimo
   deploy viene resettato dal sorgente".

I punti 1, 5 e 6 sono prosa scritta a mano, ma vive **nel sorgente del plugin**, nella chiamata a
`StaffGuide` in fondo a `onEnable()`: si aggiorna nella stessa commit della funzione che descrive.
I punti 2, 3 e 4 sono generati e non si scrivono affatto.

```java
StaffGuide.create(this, "MagixTime — ora, stagioni e meteo reali", 60)
        .intro("A cosa serve, in due righe oneste.")
        .section("Come funziona", "…")
        .commands()        // tabella da plugin.yml
        .permissions()     // tabella da plugin.yml
        .settings("time.timezone", "cosa cambia")   // TUTTE le chiavi, col valore ORA in uso
        .issue("Il problema tipico", "Cosa fare")
        .never("La trappola da non fare")
        .write();
```

---

## 4. Nel gestionale

`/manage.php?section=guida`, dietro permesso web-admin (quindi gia' coperto dall'OTP obbligatorio,
vedi `website/VERIFICA-DUE-PASSAGGI.md`).

- indice a sinistra con un plugin per voce, capitolo a destra;
- per ogni capitolo: **versione del plugin** e **data dell'ultimo aggiornamento** in testa, cosi'
  si vede a colpo d'occhio se il server sta girando una versione vecchia;
- ricerca testuale su tutti i capitoli: quando serve una guida, serve in fretta;
- niente riquadri decorativi: elenco sobrio e leggibile, come il forum
  (vedi la regola "niente layout a blocchi").

**Avviso di disallineamento:** se un plugin non consegna il capitolo da piu' di N giorni (server
riavviato senza quel plugin, o plugin in errore), la voce resta visibile ma marcata *non
aggiornata dal ...*. Una guida che tace e' peggio di una guida vecchia.

---

## 5. Tabella

```sql
CREATE TABLE IF NOT EXISTS guide_staff (
  plugin        VARCHAR(64)  NOT NULL PRIMARY KEY,
  titolo        VARCHAR(160) NOT NULL,
  versione      VARCHAR(32)  NOT NULL,
  ordine        INT          NOT NULL DEFAULT 100,
  corpo_html    MEDIUMTEXT   NOT NULL,
  aggiornata_il DATETIME     NOT NULL
);
```

Una riga per plugin, sovrascritta a ogni avvio: non serve storico, la verita' e' sempre l'ultima.

---

## 6. Da fare, plugin per plugin

| Plugin | Ordine | Stato |
|---|---|---|
| MagixGuard | 10 | **in linea** |
| MagixAuth | 20 | **in linea** |
| MagixFactions | 30 | **in linea** |
| MagixWeb | 40 | **in linea** |
| MagixTime | 60 | **in linea** |
| MagixEntities | 70 | **in linea** |
| MagixMenus | 75 | **in linea** |
| MagixCosmetics | 80 | **in linea** |
| MagixEssentials | 90 | **in linea** (dal 2026-09-14) |
| AutoBackup / CustomMOTD | 100-110 | da fare: non sono "Magix" ma sono nostri, stesso trattamento |

La colonna delle versioni non c'e' piu' apposta: era gia' vecchia di dieci rilasci mentre la
tabella diceva "in linea". La versione vera di ogni capitolo si legge **nel gestionale**, in testa
al capitolo, ed e' quella che il plugin sta girando davvero — che e' tutto il punto di questa guida.
Qui resta solo chi c'e' e in che ordine.

L'ordine 50 e' lasciato libero apposta: e' il posto del capitolo sulle **sanzioni**, che nascera'
con MagixGuard 0.2.0 accanto a quello sui multi-account.

---

## 7. L'altra guida: il tutorial dei giocatori diventa la guida della **modalita'**

Stesso principio, pubblico diverso — e una correzione di rotta decisa il 2026-08-29.

Oggi il tutorial e' *"MagixFactions - Guida per nuovi giocatori"* e vive in
`plugins-src/MagixFactions/docs/build_tutorial.py`. E' sbagliato di inquadratura: chi entra su
MAGICADVENTURE non gioca "a un plugin", gioca a una **modalita'**. Il login, le stagioni, i gradi,
la mappa, il sito e lo store non sono capitoli di MagixFactions: sono la stessa esperienza.

**Cosa cambia**

- il generatore esce da `MagixFactions/docs/` e sale a livello di server, in `guida/` — la
  modalita' non appartiene a nessun plugin;
- il titolo e l'indice si riorganizzano **per argomento di gioco**, non per plugin: primi passi e
  accesso, fazioni e territori, protezioni, potere e conquista, mappa, stagioni e meteo, gradi e
  vantaggi, sito e store, regole e sanzioni;
- la catena di allineamento verso `/tutorial` (l'unita' `sync-guida.path` sul VPS) va puntata al
  nuovo percorso: e' l'unico passaggio che rompe qualcosa se lo si dimentica;
- resta invariata la regola di ferro: **`tutorial.html` non si modifica mai a mano**, si rigenera.

**Le due guide, in simmetria**

| | Guida di gioco | Guida per amministratori |
|---|---|---|
| Per chi | giocatori | staff |
| Dove | `/tutorial` (pubblico) | `/manage.php?section=guida` (web-admin) |
| Fonte | `guida/build_tutorial.py` | capitoli generati dai plugin |
| Organizzata per | argomento di gioco | plugin |
| Scritta a mano? | mai il risultato, solo la fonte | mai il risultato, solo la prosa nelle risorse |

---

## Un plugin Magix NUOVO: cosa deve avere (dal 2026-08-30)

Il controllo `python plugins-src/check_all.py` (lancia `check_english.py` + `check_config.py`) **trova
i plugin da solo** — ogni cartella qui dentro con un `pom.xml` e i suoi sorgenti entra nel giro dal
momento in cui esiste, senza toccare nessun elenco. Gira in tre punti: gli hook in
`.claude/settings.local.json` lo lanciano a ogni modifica (mia via Edit/Write/Bash, o tua a mano al
primo messaggio utile); il **git pre-commit** in `.githooks/pre-commit` (attivo con
`git config core.hooksPath .githooks`) **blocca il commit** se qualcosa e' rosso; e va lanciato a mano
prima di un rilascio. Quindi un plugin nuovo **e' controllato da subito** — ma il controllo si aspetta
di trovare queste cose:

0. **Codice in inglese.** Nomi di file, segmenti di package e tipi **top-level** (class/enum/interface/
   record) vanno in inglese; in italiano restano solo i testi che una persona legge (messaggi, UI) e —
   per convenzione — i nomi di metodo, le variabili locali, le costanti enum e i tipi annidati.
   `check_english.py` lo verifica spezzando il CamelCase e confrontando parola per parola con un
   dizionario italiano estendibile (aggiungi una parola e la rete si allarga). Anche chiavi di config e
   messaggi vanno in inglese; italiano solo nei testi.

1. **Ogni chiave del `config.yml` ha un commento** immediatamente sopra (o in linea dopo il valore).
   Una riga vuota separa: il commento del blocco precedente non conta. Se la chiave accetta piu'
   valori, il commento **deve nominarli tutti** (`# hour | day | week | month`).
2. **Niente chiavi morte** (nel file ma mai lette) e **niente chiavi invisibili** (lette dal codice ma
   assenti dal file: chi configura non le vedrebbe mai).
3. **Le classi comuni copiate** in `util/` devono restare **identiche** agli altri plugin, a parte la
   riga del `package`: `ConfigValues`, `DurationText`, `StaffGuide`, `Help`. Il controllo confronta le
   copie e segnala chi diverge. **Prima di copiarne una, verificare che il nome non esista gia'** nel
   plugin di destinazione (e' gia' successo di sovrascrivere una classe omonima).
4. **I numeri nelle guide non si scrivono a mano**: si usano i segnaposto `{{cfg:chiave}}`,
   `{{secondi:chiave}}`, `{{ore:chiave}}`, `{{percento:chiave}}`, `{{simbolo:chiave}}`, e si aggancia
   la guida al config con `StaffGuide.create(...).values(new ConfigValues(this))`. Un segnaposto senza
   valore resta visibile come `{{...}}` e finisce nel log all'avvio. **Nemmeno le FRASI che descrivono
   una modalita'**: quelle stanno dentro un blocco `{{se:map.mode=chat}} …testo… {{/se}}`, che sparisce
   quando il config dice altro (con `!=` per «in tutti gli altri casi»); i blocchi si annidano. Due
   controlli tengono la regola: **[6]** segnala i numeri rimasti a mano nel tutorial che coincidono con un
   valore del config, **[7]** segnala una MODALITA' (chiave che vale una parola fra piu' possibili) che il
   tutorial non racconta con un blocco `{{se:...}}` — se quella modalita' in gioco non si vede, lo si
   dichiara scrivendo `[solo staff]` nel commento della chiave.
5. **Il comando di reload, se c'e', riscrive le guide** (vedi `MagixFactions.riscriviGuide()`):
   altrimenti si cambia un valore e la documentazione resta indietro fino al riavvio.
6. **Tabella delle impostazioni nella guida staff**: `.settings(...)` elenca **TUTTE** le chiavi
   del `config.yml` — nessuna esclusa — con il valore vivo (*"Ora vale"*) e la spiegazione presa dal
   **commento della chiave nel config**. Non e' piu' un elenco scelto a mano: le coppie che si passano
   servono solo a dare una spiegazione migliore alle chiavi che lo staff tocca ogni giorno, tutte le
   altre compaiono comunque. Quindi **una chiave nuova nel config e' documentata dal momento in cui
   esiste**, e il commento che deve avere (regola 1) e' anche la sua voce di guida. Per un secondo file
   di config c'e' `.settingsFrom(conf, "titolo")` (le sanzioni di MagixGuard).
