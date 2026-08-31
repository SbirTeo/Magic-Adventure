# MagixGuard

Profilazione silenziosa dei giocatori e rilevamento multi-account per MAGICADVENTURE.
Pensato per un server **non premium** (offline mode), dove l'UUID non identifica nessuno e
un nuovo account costa zero.

**Versione 0.1.0 - Fase 1: sola osservazione.** Il plugin non blocca, non caccia, non limita e
non manda alcun messaggio ai giocatori. Raccoglie segnali, li correla, avvisa lo staff e produce
dossier firmati da usare come prova quando un giocatore fa ricorso sul forum.

---

## Perche' esiste

In offline mode l'identita' e' solo un nickname. L'IP da solo non basta: le VPN costano due euro,
l'hotspot del telefono cambia rete, e sulle reti mobili italiane (CGNAT) centinaia di persone
estranee fra loro escono con lo stesso indirizzo pubblico. Serve incrociare piu' segnali deboli e
pesarli con onesta', tenendo da parte anche gli elementi che scagionano.

## Cosa raccoglie, a ogni accesso

**Rete**
- indirizzo IP e sottorete /24
- nome di rete inverso (rDNS): identifica la linea anche quando l'IP cambia
- indirizzo con cui il giocatore si e' connesso (dominio, sottodominio o IP nudo)

**Client** (arriva qualche secondo dopo il login, per questo si fanno due passaggi)
- brand del client: vanilla, fabric, forge, lunarclient...
- **canali plugin dichiarati**: di fatto la lista delle mod, spesso unica come un'impronta digitale
- lingua, distanza visiva, parti della skin attivate, mano principale, opzioni di chat
- esito e tempo di scaricamento del resource pack
- ping mediano della sessione

**Token di installazione (cookie)**
Un token casuale scritto nel client con l'API cookie di Minecraft (1.20.5+). Due nickname che
presentano lo stesso token girano sulla stessa copia del gioco: e' l'indizio piu' forte che un
server non premium possa avere, e nessuna VPN lo nasconde.
Limite: in vanilla il cookie vive in memoria, quindi si perde quando il giocatore **chiude**
Minecraft. La sua assenza non prova niente; la sua presenza si'.

## Come ragiona

Ogni indizio ha un peso configurabile. La somma viene corretta da tre fattori, tutti dichiarati
nel dossier:

1. **Affollamento dell'IP** - un indirizzo usato da 30 account vale quasi zero (CGNAT, scuole,
   connessioni condivise); uno usato da due account vale molto.
2. **Decadimento** - una coincidenza di sei mesi fa pesa la meta' di una di oggi, perche' gli
   indirizzi domestici vengono riassegnati.
3. **Elementi a discolpa** - due account visti online **nello stesso momento** sono, con ogni
   probabilita', due persone diverse: il punteggio scende. E' il controllo che tiene fuori
   fratelli e coinquilini, ed e' anche il piu' efficace.

Le occorrenze di un indizio non moltiplicano il punteggio: descrivono il fenomeno, non lo
aggravano. Altrimenti chi gioca molto risulterebbe piu' colpevole di chi gioca poco.

### Indizi riconosciuti

| Indizio | Peso di partenza |
|---|---|
| Stessa installazione di gioco (token client) | +100 |
| Stesso IP nello stesso periodo | +50 |
| Staffetta: uno esce, l'altro entra dallo stesso IP | +40 |
| Stesso IP in momenti diversi | +30 |
| Stesso nome di rete inverso | +25 |
| Impronta del client identica | +25 |
| Stessa lista di mod | +20 |
| Primo accesso ravvicinato dallo stesso IP | +20 |
| Stessa sottorete /24 | +15 |
| Mai online insieme | +15 |
| Nickname simili | +10 |
| **Visti online insieme** | -8 per sessione (fino a -60) |
| **Client nettamente diversi** | -15 |

Soglie predefinite: collegamento a **60**, segnalazione allo staff a **85**.

## Comandi (`/mg`, alias `/magixguard`, `/guard`, `/alts`)

| Comando | Cosa fa |
|---|---|
| `/mg alts <nick>` | account collegati, con il dettaglio degli indizi al passaggio del mouse |
| `/mg dossier <nick> [nick2] [pubblico]` | genera il documento completo in `plugins/MagixGuard/dossier/` |
| `/mg sessions <nick> [n]` | ultimi accessi con tutti i dati tecnici |
| `/mg alerts [n]` | ultime segnalazioni |
| `/mg link <a> <b> [motivo]` | collega due account a mano (admin) |
| `/mg unlink <a> <b> [motivo]` | dichiara la coppia legittima: niente piu' segnalazioni (admin) |
| `/mg exempt <nick> <on\|off>` | esclude un account dall'analisi (admin) |
| `/mg verify` | verifica che il registro non sia stato manomesso |
| `/mg stats` | numeri generali |
| `/mg reload` | ricarica la configurazione (admin) |

Permessi: `magixguard.staff` (consultazione), `magixguard.admin` (modifiche),
`magixguard.alerts` (ricevere le segnalazioni in chat), `magixguard.exempt` (non essere profilato).

## Il dossier

Due versioni dello stesso documento:

- **interna** (`_staff.md`): tutto, IP compresi. Non va pubblicata.
- **pubblica** (`_pubblico.md`): IP mascherati (`87.12.x.x`) e nomi di rete ridotti al solo
  operatore. E' quella da allegare alla risposta a un ricorso.

Ogni dossier contiene: gli account esaminati, il punteggio con il verdetto in italiano, la tabella
degli indizi **a carico e a discolpa** con il peso applicato e il perche' di ogni correzione, la
cronologia degli accessi e una sezione finale che dichiara apertamente i limiti del metodo.

In fondo c'e' l'impronta SHA-256 del documento, registrata anche nel registro interno a catena:
serve a dimostrare che il dossier esisteva gia' in quella forma **prima** della decisione e che
non e' stato ritoccato dopo. `/mg verify` controlla l'intera catena.

## Privacy

- gli IP sono conservati in chiaro per il numero di giorni impostato in `privacy.session-retention-days`
  (180 di default), poi restano solo in forma cifrata (HMAC) per i confronti;
- l'HMAC usa il segreto `privacy.pepper`, che **va cambiato al primo avvio** e non deve mai finire
  in un dossier: senza di esso gli hash nel database non sono riconducibili a un indirizzo;
- il registro a catena traccia chi ha generato ogni dossier e quando.

Il pubblico di un server Minecraft e' in larga parte minorenne: conviene indicare nella privacy
policy del sito che il server registra indirizzi IP e dati tecnici del client a fini di sicurezza.

## Cosa NON fa (per ora)

Nessuna sanzione automatica. Fase 2, da decidere quando ci saranno abbastanza dati veri per
tarare le soglie: limitazioni silenziose per gli account marchiati (niente reward, niente kit,
niente fazione in comune col principale), oppure blocco al login.

## Installazione

1. `mvn -q clean package` (serve **JDK 25**, vedi `pom.xml`)
2. copia `target/MagixGuard-0.1.0.jar` in `plugins/`
3. avvia una volta, poi in `plugins/MagixGuard/config.yml`:
   - cambia `privacy.pepper` con una stringa casuale lunga
   - imposta le credenziali MariaDB (o lascia `type: sqlite` per iniziare)
4. riavvia.
