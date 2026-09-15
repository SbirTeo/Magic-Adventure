# MagixEssentials

Plugin per **MAGICADVENTURE** (Paper 26.x) che raccoglie le **utilita' di base** del server: quelle
cose che non appartengono a nessun gioco in particolare ma che ci sono sempre. Oggi ne fa due — il
**tablist** e la **MOTD** — e a lungo andare dovrebbe assorbire cio' che oggi fa CMI.

Versione: **0.7.0**

---

## Come e' organizzato: moduli, come in CMI

Accendere una funzione e regolarla sono due gesti diversi, fatti in momenti diversi. Qui stanno in
file diversi:

| File | A cosa serve |
|---|---|
| `modules.yml` | L'elenco delle funzioni, una riga ciascuna: **acceso o spento**. Si apre questo per sapere che cosa sta facendo il plugin. |
| `tablist.yml` | Come e' fatto il tablist: intervallo, intestazione, fondo, nomi, caselle fisse. |
| `motd.yml` | Come e' fatta la MOTD: le varianti e come ruotano, la tendina, il conto dei giocatori, le icone. |
| `config.yml` | Solo cio' che vale per il **plugin intero**. Per ora niente, e lo dice. |

Una funzione spenta non parte affatto: niente task, niente aggancio agli eventi. Il suo file resta
dov'e', intatto, e torna in uso appena la si riaccende — spegnere non e' buttare via la
configurazione.

I file sul server si tengono aggiornati da soli (`util/ConfigAlign`): a ogni avvio e a ogni
`/magixessentials reload` le chiavi nuove compaiono al loro posto col loro commento, le rinomine
dichiarate si applicano portandosi dietro il valore scelto, e le righe che il codice non legge piu'
spariscono — con una copia di scorta `.bak-<data>` accanto, prima di ogni scrittura.

**Per aggiungere una funzione**: una riga in `modules.yml`, un `<funzione>.yml` accanto, e la classe
che la gestisce riceve quel file nel costruttore. Non c'e' nessun elenco da aggiornare a mano: i
file nuovi il plugin li crea e li allinea da solo.

---

## Tablist

La lista giocatori del tasto Tab: intestazione, fondo e nome dei giocatori, riscritti a intervalli
regolari e a ogni ingresso. Supporta i colori `&` e `&#RRGGBB`, i placeholder di PlaceholderAPI
(ricalcolati per ogni giocatore: ping, fazione, coordinate) e il segnaposto `{logo}`, che diventa il
carattere del logo nel resource pack di MagixFactions.

**Le 80 slot fisse.** Il gioco decide da solo quante colonne disegnare in base a quante voci ci
sono: con pochi giocatori il tab e' una colonna sottile, con tanti si allarga. Con `fixed-slots`
acceso il tab mostra sempre lo stesso numero di caselle, riempiendo con voci decorative **senza
testa** (skin trasparente) e **senza tacchette** (latenza -1). Richiede ProtocolLib; se manca, la
funzione si spegne da sola e resta il tablist dinamico.

**Il tablist ce l'ha chi scrive per ultimo.** Se anche CMI lo gestisce, i due si sovrascrivono a
vicenda: `priority` ci fa riscrivere poco dopo di lui, ma le sue caselle finte non si tolgono da
qui. La via pulita resta spegnere il suo modulo (`plugins/CMI/Settings/Modules.yml` →
`tablist: false`).

---

## MOTD

Le due righe che si leggono nella lista server prima di entrare, l'icona, il numero dei giocatori e
la tendina che esce passandoci sopra col mouse. Sostituisce il vecchio plugin **CustomMOTD**, che e'
stato tolto dal server: due plugin sulla stessa MOTD se la strappano di mano.

**Le MOTD** stanno tutte in `messages`, una voce ciascuna. Quale si vede lo decide `selection`:

| `selection` | Cosa fa |
|---|---|
| `random` | una a caso; con `avoid-repeat` non esce due volte di fila la stessa |
| `ordered` | una dopo l'altra, dalla prima all'ultima e poi daccapo |
| `fixed` | sempre la prima; le altre restano nel file, pronte |

`change-every-seconds` dice ogni quanto cambia: a 0 cambia **a ogni ping** (ogni volta che qualcuno
apre la lista), altrimenti resta la stessa per tutti dentro quella finestra. Il ping arriva spesso e
in modo irregolare: cambiare a ogni ping fa ballare la MOTD sotto gli occhi di chi tiene la lista
aperta.

**Come si scrive una riga** — due modi, uno *o* l'altro nella stessa riga:

- **codici classici**: `&a`, `&7`, `&l`, e `&#RRGGBB` per l'esadecimale;
- **tag** (MiniMessage): `<bold>`, `<color:#C046E8>`, `<rainbow>`, e soprattutto
  `<gradient:#C046E8:#A8DC2C>TESTO</gradient>` per le **sfumature**.

Si riconoscono dai triangoli: se in una riga c'e' un tag, quella riga viene letta come tag e le `&`
restano scritte. Un tag scritto male non fa sparire la MOTD — resta scritto com'e', e si vede subito.

**Come si va a capo** — `\n` dentro le virgolette doppie, oppure un blocco `- |` con le righe sotto
(piu' leggibile quando sono lunghe). Il client ne disegna **due**: la terza viene tagliata.

**Segnaposto**: `{online}`, `{max}`, `{version}`. Non ce ne sono per-giocatore e non possono
essercene: al ping il server non sa CHI sta guardando. Per lo stesso motivo PlaceholderAPI qui non
c'entra.

**La tendina** (`hover`) prende il posto dell'elenco dei giocatori online. Li' il protocollo non
vuole componenti ma nomi, quindi le righe vengono riscritte nei codici `§` che il client capisce:
funziona tutto, sfumature comprese — ma una sfumatura colora *una lettera alla volta*, e una riga di
trenta lettere diventa una stringa di centinaia di caratteri. Tienila per una riga sola.

**Il numero dei giocatori**: `player-count.max` cambia il numero mostrato; `player-count.extra` e' il
vecchio trucco del posto sempre libero (massimo = online + N, e il server non sembra mai pieno);
`player-count.hide` lo nasconde del tutto. Nessuno dei tre fa entrare un giocatore in piu': il limite
vero resta quello del server.

**L'icona**: con `icons` si mettono piu' PNG **64x64** nella cartella del plugin, e ruotano con la
stessa regola delle MOTD. Uno che manca o che non e' 64x64 viene saltato, col motivo nel log.

**La versione**: `version.text` si vede solo dai client non compatibili; `version.always-show` lo
mostra a tutti, ma fa apparire il server come non compatibile (barra rossa, niente conto dei
giocatori). Si entra lo stesso, ma spaventa.

### Quando davanti ci sara' Velocity

La MOTD la scrive **chi risponde al ping**. Oggi risponde il server, perche' il client ci parla
diretto. Con un proxy **Velocity** davanti, al ping risponde il proxy: il server dietro non lo vede
nemmeno, e un plugin del server non puo' farci niente. Non e' un limite di questo modulo, e' come
funziona il protocollo.

Il codice e' gia' diviso in vista di quel giorno:

| Classe | Cosa fa | Dipende da |
|---|---|---|
| `motd/MotdText` | **Come si compone** la MOTD: segnaposto, colori, tag e sfumature, le due righe, la tendina | solo Adventure (MiniMessage compreso) — niente Bukkit |
| `motd/MotdRotation` | **Quale voce adesso**: random / ordered / fixed, ogni quanto cambia, niente ripetizioni | niente — solo Java |
| `motd/MotdListener` | **Chi ascolta il ping** e ci mette dentro il risultato, piu' icona e conto | Paper (`PaperServerListPingEvent`) |

Adventure (`net.kyori.adventure`) ce l'hanno **sia Paper sia Velocity**, e i `Component` sono gli
stessi. Quindi, quando arrivera' il proxy, la strada e': un plugin Velocity che legge un `motd.yml`
con lo **stesso formato**, chiama gli **stessi** `MotdText` e `MotdRotation` e mette il risultato nel `ProxyPingEvent`
invece che nel `PaperServerListPingEvent`. Di nuovo c'e' solo il listener, una trentina di righe.

Due cose da decidere quel giorno, non prima:

1. **Un jar solo o due.** Un jar puo' portarsi dentro sia il `plugin.yml` di Paper sia il
   `velocity-plugin.json` di Velocity: ciascuna piattaforma legge il suo e ignora l'altro. In
   alternativa, un plugin Velocity a parte che si porta dietro la copia di `MotdText` — che e' poi
   il trattamento che in questo repo hanno gia' le classi comuni (`ConfigAlign`, `StaffGuide`),
   controllate da `check_config.py` perche' restino identiche.
2. **Chi legge il file.** Su Velocity non c'e' il `YamlConfiguration` di Bukkit: il file lo legge la
   piattaforma e passa i valori a `MotdText`, che i valori li prende gia' cosi' (stringhe, liste,
   numeri) e non sa da dove vengano.

Regola pratica: se in `MotdText` compare un `import org.bukkit`, quella strada si e' chiusa.

---

## Comandi

| Comando | Cosa fa | Permesso |
|---|---|---|
| `/magixessentials reload` (alias `/mess`, `/magixess`) | Riallinea i file, li rilegge e fa ripartire i moduli accesi | `magixessentials.admin` |

Il reload risponde in chat con l'elenco dei moduli e il loro stato, e lo stesso elenco finisce nel
log a ogni avvio.

---

## Guida per lo staff

Il capitolo sul gestionale (`/manage.php?section=guida`) lo scrive il plugin stesso a ogni avvio e a
ogni reload, leggendo i valori **vivi** dei file: comandi, permessi e impostazioni non si ricopiano
a mano. Vedi `plugins-src/GUIDA-STAFF.md`.
