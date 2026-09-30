# MagixBridge

Il ponte fra i server della rete MagicAdventure e il sito (database `magicadventure_web`). Fino
alla 0.12 si chiamava **MagixWeb**: dalla 0.13.0 gira su **ogni modalita'** (`deploy.target` =
`faction hub`, e le modalita' future).

## Cosa fa

| Funzione | Dove gira |
|---|---|
| Gradi LuckPerms e colore del nome verso il sito (`mc_ranks`), a ogni ingresso e cambio | ogni server (per chi e' li') |
| Lingua del giocatore verso il sito (MagixLanguage) | ogni server |
| Chat pubblica del gioco verso la chat in home del sito | ogni server |
| Messaggi scritti sul sito, in chat | ogni server |
| Aureola VIP di MagixCosmetics sul sito | dove c'e' MagixCosmetics |
| Ponte dei placeholder (`%network_<server>_<placeholder>%`) | ogni server |
| Consegna degli acquisti dello store | solo `network.site-jobs: true` |
| Traduzione automatica del sito (via MagixLanguage) | solo `network.site-jobs: true` |
| Elenco dei gruppi (`web_groups`), primo accesso dei vecchi giocatori | solo `network.site-jobs: true` |
| Guida per amministratori (raccoglie i `guida-staff.html`) | solo `network.site-jobs: true` |
| Pulizia della chat del sito (arretrati, storico) | solo `network.site-jobs: true` |

## La rete

- `network.server-name` — il nome del server (faction, hub...). Senza trattini bassi: e' il primo
  pezzo di `%network_<server>_...%`.
- `network.site-jobs` — `true` su **un solo** server (oggi il faction). Su due server un acquisto
  verrebbe consegnato due volte. All'avvio il log dice "lavori del sito QUI" o "su un altro server".

La chat del sito sui server senza `site-jobs`: si leggono i messaggi con id maggiore dell'ultimo
visto, senza marcarli (la marcatura `delivered` la fa il faction). All'avvio si parte dal piu'
recente: quello scritto a server spento non si riversa in chat.

## Il ponte dei placeholder

Ogni modalita' ha i suoi plugin: sull'hub MagixFactions non c'e', e `%magixfactions_faction%` non
vuol dire niente. Il ponte li porta passando dal database del sito:

1. **presenza** — ogni server scrive chi ha online (`network_presence`, riga per giocatore, con
   l'ora: vale per 60 secondi);
2. **pubblicazione** — ogni `bridge.publish-interval-seconds` un server calcola, con
   PlaceholderAPI, i placeholder di `bridge.player-placeholders` per i suoi giocatori **e** per
   quelli connessi sugli altri server (come giocatori offline), piu' quelli di
   `bridge.global-placeholders` (classifiche, totali), e scrive solo i valori cambiati
   (`network_placeholders`). Chi esce lascia l'ultimo valore;
3. **lettura** — ogni `bridge.read-interval-seconds` gli altri server rileggono i valori dei loro
   giocatori e quelli globali; `%network_<server>_<placeholder>%` risponde dalla copia in memoria.

Un placeholder che un server non sa risolvere (il suo plugin li' non c'e') non viene pubblicato:
lo stesso elenco va bene su tutti i server. Esempi sull'hub:

- `%network_faction_magixfactions_faction%` — la fazione del giocatore;
- `%network_faction_magixfactions_top_1_name%` — la prima fazione in classifica;
- `%network_faction_online%` / `%network_online%` — giocatori sul faction / su tutta la rete.

Non ci sono i placeholder relazionali (`%rel_...%`): dipendono da due giocatori sullo stesso server.

## Il vecchio nome

- `%magixweb_namecolor%` ha tenuto il nome di prima apposta: e' nei formati della chat di
  MagixFactions e nei name tag di MagixEssentials, sul VPS.
- La rinomina sul VPS l'ha fatta `deploy-plugin.yml` col file `replaces` (`MagixWeb`): tolto il jar
  vecchio, **copiata** (non spostata) la cartella `plugins/MagixWeb` in `plugins/MagixBridge`.
  La cartella vecchia resta come riserva; la guida per amministratori toglie da sola il capitolo di
  un plugin che non gira piu'.

## Sull'hub

`hub-network-plugins.yml` copia jar e `config.yml` dal faction e mette `network.server-name: hub`,
`network.site-jobs: false`.
