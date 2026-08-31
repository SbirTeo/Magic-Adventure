# Verifica in due passaggi (OTP) — come funziona

Aggiornato il 23 agosto 2026. Riguarda il sito `magicadventure.it` **e** l'ingresso in gioco
su `mc.magicadventure.it`: è la stessa verifica, con lo stesso codice.

---

## 1. In due parole

Chi amministra il sito non entra più con la sola password: serve anche un **codice a sei
cifre che cambia ogni trenta secondi** (TOTP, lo standard di Google Authenticator, Aegis,
Bitwarden, 1Password). Vale in due punti:

| dove | quando lo chiede |
|---|---|
| sito | a ogni accesso da un dispositivo nuovo (con "Resta collegato" il dispositivo diventa fidato) |
| server Minecraft | all'ingresso in partita, poi non lo richiede per 12 ore dallo stesso indirizzo di rete |

**Chi è soggetto**: i **web-admin** sempre. Lo staff con permessi delegati (blog, forum,
chat) solo se accendi l'interruttore in *Gestione → Sicurezza*.

Chi non amministra niente non vede alcuna differenza, né sul sito né in gioco.

---

## 2. La prima volta

1. Vai su `magicadventure.it/login` e inserisci nome e password come sempre.
2. Il sito ti porta su `/otp` e ti mostra un **QR** più la chiave scritta.
3. Inquadra il QR con l'app, digita il codice che compare: la verifica è attiva.
4. Il sito ti mostra **dieci codici di recupero**, una volta sola. Sotto l'elenco ci sono
   tre pulsanti: **Scarica** (un file `.txt` con i codici, il nome dell'account e la data),
   **Copia** e **Stampa**. Salvali — sono l'unico modo per rientrare da solo se perdi il
   telefono. Il file lo prepara il browser dai codici già presenti nella pagina: non fa
   nessun giro in più sulla rete.

Se sei già passato oltre senza salvarli, dal **profilo** puoi rigenerarli (serve il codice
dell'app): ne escono dieci nuovi, con gli stessi tre pulsanti, e i precedenti smettono di
valere.

Da quel momento lo stesso codice vale anche in gioco: entri, il server ti congela, scrivi
`/otp <codice>` e prosegui.

---

## 3. Dov'è ogni pezzo

### Sito
| file | cosa fa |
|---|---|
| `includes/otp.php` | i conti: base32, TOTP, cifratura del segreto, codici di recupero, blocchi |
| `includes/auth.php` | `accesso_completato()`, `otp_metti_in_attesa()`, `otp_guardia()` |
| `public/otp.php` | le tre schermate: attivazione, codici di recupero, verifica |
| `public/profilo.php` | riquadro "Verifica in due passaggi": stato e rigenerazione dei codici |
| `public/manage.php` | scheda *Sicurezza*: interruttore staff, stato di ogni account, azzeramento |
| `public/assets/js/vendor/qrcode.min.js` | disegna il QR **nel browser** (il segreto non passa da servizi esterni) |
| `public/assets/js/site.js` (in fondo) | i pulsanti Scarica / Copia / Stampa sotto i codici di recupero |
| `includes/config.php` | `OTP_CHIAVE` — vedi sotto |

### Plugin (MagixWeb 0.9.1)
| file | cosa fa |
|---|---|
| `otp/OtpCodici.java` | gli stessi conti del sito, in Java (TOTP + apertura del segreto cifrato) |
| `otp/OtpServizio.java` | chi è soggetto, verifica sul database, sessioni di gioco |
| `otp/OtpGate.java` | il cancello: decide al pre-login, congela al join, disconnette allo scadere, applica le revoche dal sito |
| `command/OtpCommand.java` | `/otp <codice>` |
| `config.yml` → `otp:` | interruttore, chiave, secondi per digitare, ore di validità |

---

## 4. Le difese, e perché ci sono

- **Il segreto è cifrato nel database** (AES-256-GCM) con `OTP_CHIAVE`, che vive solo in
  `includes/config.php`, fuori dal docroot. Senza, chi legge la tabella `users` — o entra
  da phpMyAdmin, che è esposto su `db.magicadventure.it` — potrebbe generare i codici degli
  amministratori.
- **Un codice usato non si riusa.** Il numero dell'intervallo speso finisce in
  `users.totp_ultimo_passo`, che sito e plugin leggono e scrivono **nella stessa riga**:
  un codice speso sul sito è bruciato anche in gioco, e viceversa.
- **Cinque tentativi sbagliati e l'account si ferma un quarto d'ora**, su entrambe le porte.
- **`/link` non è più una scorciatoia.** Prima, chiunque entrasse in gioco col nome di un
  admin poteva rifarsi la password dal sito e prendersi l'account: il server non è premium,
  quindi il nome non prova niente. Ora anche quella strada passa dal codice.
- **I "resta collegato" vecchi non valgono.** Il cookie fa da dispositivo fidato solo se è
  nato dopo una verifica riuscita (`remember_tokens.otp_ok`); quelli precedenti fanno
  ripassare da `/otp`.
- **Database irraggiungibile, in gioco**: il plugin tiene in memoria l'elenco degli account
  da proteggere e, se il sito non risponde, ferma **loro** e lascia entrare tutti gli altri.
  È la scelta prudente: su un server non premium il nome di un admin lo scrive chiunque.


---

## 5. Le due sessioni sono separate (e come chiuderle)

Sito e server hanno **due ricordi diversi** della verifica, e uscire da uno non tocca l'altro:

| | dove sta la fiducia | quanto dura | come si chiude |
|---|---|---|---|
| sito | cookie di sessione + `remember_tokens` | finché non esci (col cookie "resta collegato", un anno) | *Esci* / *Esci dagli altri dispositivi* |
| gioco | riga in `otp_game_sessions` (UUID + indirizzo di rete) | `sessione-ore`, di serie 12 | **Chiudi la sessione di gioco** (profilo, o Gestione → Sicurezza) |

È voluto: se le due cose fossero legate, chiudere il sito vorrebbe dire ridigitare il codice
in partita ogni volta. Ma serviva un modo per revocare la fiducia del gioco **subito**, senza
aspettare la scadenza — è quello che fa il pulsante.

**In gioco il codice viene chiesto quando** non c'è una verifica valida per quella coppia
account + rete: prima volta, più di 12 ore dall'ultima, rete diversa (casa → cellulare, altro
PC, VPN), oppure dopo un azzeramento o una chiusura di sessione.

### Cosa fa il pulsante
1. Cancella le righe di `otp_game_sessions` di quell'account: dal prossimo ingresso il codice
   torna obbligatorio.
2. Lascia un biglietto in `otp_game_revoke`. Il plugin lo raccoglie entro pochi secondi
   (`controllo-revoche-secondi`, di serie 5) e, se l'account è **in partita in quel momento**,
   lo ricongela sul posto chiedendo di nuovo `/otp`: se sei tu digiti il codice e riprendi a
   giocare, se è un altro ha 120 secondi e poi viene disconnesso.

Il secondo passo è il motivo per cui il pulsante è utile: cancellare e basta avrebbe effetto
solo dal prossimo ingresso, e chi fosse già dentro continuerebbe indisturbato.

Nel profilo si vede anche **lo stato**: da quanto è verificata e da quale rete (l'indirizzo è
mostrato accorciato, `79.27.x.x`). In *Gestione → Sicurezza* lo stesso riquadro c'è per ogni
altro amministratore, per quando è un collega ad avere il problema e non è raggiungibile.

---

## 6. Se qualcuno resta fuori

1. **Codice di recupero** — dieci consegnati all'attivazione, uno ciascuno. Funzionano sia
   sul sito sia in gioco (`/otp ABCDE-FGHIJ`). Se ne restano pochi, se ne rigenerano dal
   profilo.
2. **Un altro web-admin lo azzera** da *Gestione → Sicurezza → Azzera verifica*: l'account
   viene fatto uscire da ogni dispositivo e riconfigura tutto al primo accesso.
3. **Nessun altro web-admin disponibile**, dal server via SSH:
   ```bash
   sudo mariadb magicadventure_web -e "UPDATE users SET totp_secret=NULL, totp_attivato_il=NULL, totp_tentativi=0, totp_bloccato_fino=NULL WHERE mc_username='NOME'"
   ```
   L'account non resta scoperto: al primo accesso il sito gli fa rifare l'attivazione.

---

## 7. La chiave

`OTP_CHIAVE` (in `includes/config.php`) e `otp.chiave` (in `plugins/MagixWeb/config.yml`)
**devono essere identiche**: sono la stessa chiave, letta da due programmi diversi.

Se la chiave cambia o si perde, i segreti già salvati diventano illeggibili e **tutti gli
amministratori devono riconfigurare la verifica** — il sito lo chiede da solo al primo
accesso, quindi nessuno resta fuori per sempre, ma è un pomeriggio perso. Va nei backup
insieme alla password del database.

---

## 8. Verifiche fatte al momento del rilascio

- Motore TOTP confrontato con i **vettori ufficiali dell'RFC 6238**: sei valori su sei.
- Il segreto cifrato da PHP viene riaperto correttamente da Java, e le due parti generano
  lo stesso codice nello stesso istante.
- Flusso completo sul sito: password → `/otp` → attivazione → codici di recupero → accesso.
- Gestionale irraggiungibile senza codice (`/manage` rimanda all'accesso).
- Stesso codice rifiutato al secondo uso, da un altro browser.
- Codice di recupero valido una volta sola; il contatore nel profilo scende da 10 a 9.
- Quattro tentativi sbagliati e l'account si blocca per quindici minuti.
- Pulsanti dei codici: il file scaricato contiene intestazione e dieci codici (verificato
  leggendo il contenuto generato dal browser), sia in attivazione sia in rigenerazione.
- In gioco: MagixWeb 0.9.0 avviato sul VPS — *"verifica in due passaggi attiva (120s per
  digitare il codice, poi valida 12h per indirizzo)"*, nessun errore nel log.
- Pulsante "Chiudi la sessione di gioco": la riga della fiducia sparisce, il biglietto viene
  raccolto dal server in meno di sei secondi, e il profilo passa a "nessuna verifica in corso".
