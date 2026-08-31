# Ricerca nella guida — come funziona

Aggiunta il 29 agosto 2026. Vale per le due guide del sito:

- **la guida dei giocatori**, in `/tutorial` (il riquadro "Chiedi alla guida");
- **la guida per amministratori**, nel gestionale alla scheda *Guida* (il campo "Cerca nella guida").

Si scrive una domanda in italiano e si riceve la risposta presa dalla guida, con il link al
capitolo da cui viene.

---

## 1. Non c'è nessuna intelligenza artificiale esterna

Nessuna chiave, nessun servizio da pagare, nessun testo che esce dal server. Il motore sta
tutto in `includes/guida_ricerca.php` e la risposta è **citata**, non riscritta: ogni parola
che il giocatore legge è scritta nella guida.

È una scelta, non un ripiego. Una guida che si riassume da sola prima o poi dice una cosa che
nella guida non c'è, e a quel punto vale meno di niente: qui, se la risposta non c'è, la
ricerca lo dice e propone i capitoli vicini.

## 2. I tre passaggi

1. **Spezza** la guida in *passaggi*: un paragrafo, un elenco, un riquadro col consiglio, una
   riga della tabella dei comandi. Ogni passaggio si porta dietro capitolo, sotto-titolo e
   ancora.
2. **Pesa** i passaggi rispetto alla domanda:
   - le parole rare valgono più delle comuni (una parola che compare in due passaggi su
     trecento dice molto di più di "fazione");
   - il titolo del capitolo pesa più del sotto-titolo, che pesa più del corpo;
   - i comandi (`/f home`) contano come parole a sé: chi li scrive sa già cosa cerca;
   - conta soprattutto **quante** parole diverse della domanda vengono coperte: rispondere a
     una parola non è rispondere alla domanda.
3. **Compone** la risposta col passaggio migliore più i suoi vicini dello stesso capitolo, e
   rimanda al capitolo.

Se la domanda resta coperta per meno di un terzo, non si risponde: si dice che nella guida non
c'è e si mostrano i capitoli più vicini.

## 3. I file

| file | cosa fa |
|---|---|
| `includes/guida_ricerca.php` | il motore: indice, punteggi, risposta |
| `public/api/guida-cerca.php` | `GET /api/guida-cerca?q=…&ambito=pubblica\|staff` |
| `public/assets/js/guida-ia.js` | la pagina: chat in `/tutorial`, barra sobria nel gestionale |
| `public/tutorial.php` | il riquadro "Chiedi alla guida" + `window.guidaVaiA()` |
| `public/manage.php` | il campo "Cerca nella guida" nella scheda *Guida* |
| `assets/css/style.css` | le regole `.guida-ia*` |

L'ambito `staff` è riservato a chi entra nel gestionale (stesso controllo di `manage.php`):
la guida dei plugin racconta comandi e permessi dello staff.

## 4. Dove sono le due guide

- **Giocatori**: `public/assets/guida/magixfactions.html`, il file autonomo generato da
  `plugins-src/MagixFactions/docs/build_tutorial.py` e riscritto dal plugin a ogni avvio.
  **Non si modifica a mano** — per questo la ricerca vive nella pagina del sito e non dentro
  la guida: infilarcela dentro vorrebbe dire rimetterla lì a ogni rigenerazione.
- **Amministratori**: tabella `guide_staff`, un capitolo per plugin, riscritto dai plugin a
  ogni avvio (vedi `plugins-src/GUIDA-STAFF.md`).

Cambia la guida, l'indice si rifà da solo: l'impronta è la data del file per la prima e
l'ultima riscrittura dei plugin per la seconda. L'indice sta in un file nella cartella
temporanea; se non è scrivibile la ricerca funziona lo stesso, solo un po' più lenta.

## 5. Come si migliora

Quasi tutti gli sbagli di una ricerca così sono **parole**, non punteggi. In
`guida_ia_sinonimi()` c'è il lessico del server: quello che il giocatore scrive non è sempre
quello che la guida usa — chi chiede "come rubo un terreno" non troverà mai "terreno" (nella
guida si chiama *territorio*), e chi arriva da altri server dirà "claim" e "power".

Se una domanda finisce sul capitolo sbagliato, nell'ordine:

1. guarda se manca un sinonimo e aggiungilo (vale solo dalla domanda alla guida, con peso
   ridotto: è un'ipotesi, non un dato);
2. guarda se una parola vuota sta pesando (`guida_ia_stopword()`): "a cosa **serve** la
   minimappa" rispondeva sui *server* finché "serve" contava come parola vera;
3. solo alla fine tocca i pesi in `guida_ia_punteggio()`.

Per provare senza aprire il browser, sul VPS:

```bash
php -r 'require "/var/www/magicadventure/includes/guida_ricerca.php"; print_r(guida_ia_cerca("come si conquista un territorio", "pubblica"));'
```
