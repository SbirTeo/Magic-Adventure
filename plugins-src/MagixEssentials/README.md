# MagixEssentials

Plugin per **MAGICADVENTURE** (Paper 26.x) che raccoglie le **utilita' di base** del server: quelle
cose che non appartengono a nessun gioco in particolare ma che ci sono sempre. Oggi ne fa due — il
**tablist** e la **MOTD** — e a lungo andare dovrebbe assorbire cio' che oggi fa CMI.

Versione: **0.6.0**

---

## Come e' organizzato: moduli, come in CMI

Accendere una funzione e regolarla sono due gesti diversi, fatti in momenti diversi. Qui stanno in
file diversi:

| File | A cosa serve |
|---|---|
| `modules.yml` | L'elenco delle funzioni, una riga ciascuna: **acceso o spento**. Si apre questo per sapere che cosa sta facendo il plugin. |
| `tablist.yml` | Come e' fatto il tablist: intervallo, intestazione, fondo, nomi, caselle fisse. |
| `motd.yml` | Come e' fatta la MOTD: le due righe, le varianti, la tendina, il numero dei giocatori. |
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

Le due righe che si leggono nella lista server prima di entrare, il numero dei giocatori e la
tendina che esce passandoci sopra col mouse. Sostituisce il vecchio plugin **CustomMOTD**, che e'
stato tolto dal server: due plugin sulla stessa MOTD se la strappano di mano.

- **Due righe fisse** (`first-line`, `second-line`) oppure **varianti a caso** (`random.enabled` +
  `random.messages`): a ogni ping ne esce una, e chi apre la lista dieci volte al giorno non legge
  sempre la stessa cosa.
- **Colori** `&` e `&#RRGGBB`. **Segnaposto**: `{online}` e `{max}`, e basta — al ping il server non
  sa CHI sta guardando, quindi niente placeholder per-giocatore e niente PlaceholderAPI.
- **Tendina** (`hover`): le righe che prendono il posto dell'elenco dei giocatori online.
- **Numero giocatori**: `player-count.max` cambia il numero mostrato senza far entrare nessuno in
  piu'; `player-count.hide` lo nasconde (e con lui la tendina).

### Quando davanti ci sara' Velocity

La MOTD la scrive **chi risponde al ping**. Oggi risponde il server, perche' il client ci parla
diretto. Con un proxy **Velocity** davanti, al ping risponde il proxy: il server dietro non lo vede
nemmeno, e un plugin del server non puo' farci niente. Non e' un limite di questo modulo, e' come
funziona il protocollo.

Il codice e' gia' diviso in vista di quel giorno:

| Classe | Cosa fa | Dipende da |
|---|---|---|
| `motd/MotdText` | **Come si compone** la MOTD: scelta della variante, segnaposto, colori, le due righe, la tendina | solo Adventure — niente Bukkit |
| `motd/MotdListener` | **Chi ascolta il ping** e ci mette dentro il risultato | Paper (`PaperServerListPingEvent`) |

Adventure (`net.kyori.adventure`) ce l'hanno **sia Paper sia Velocity**, e i `Component` sono gli
stessi. Quindi, quando arrivera' il proxy, la strada e': un plugin Velocity che legge un `motd.yml`
con lo **stesso formato**, chiama lo **stesso** `MotdText` e mette il risultato nel `ProxyPingEvent`
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
