# SEO e prestazioni del sito — come funziona adesso

Aggiornato il 22 agosto 2026. Riguarda `magicadventure.it` (sorgente in questa cartella,
copia viva in `/var/www/magicadventure` sul VPS).

---

## 1. Cosa vede Google

### Le informazioni della pagina le costruisce `includes/seo.php`
Le pagine non devono ricordarsi niente: `includes/header.php` scrive da solo titolo,
descrizione, indirizzo ufficiale, anteprima social e dati strutturati. Una pagina può però
dire la sua riempiendo queste variabili **prima** di includere `header.php`:

| variabile | a cosa serve |
|---|---|
| `$page_title` | titolo, a cui viene aggiunto « — MAGICADVENTURE » |
| `$page_title_full` | titolo scritto per intero (lo usa la home) |
| `$page_description` | la riga sotto il titolo nei risultati di ricerca |
| `$page_image` | immagine dell'anteprima quando il link si incolla su Discord/WhatsApp |
| `$page_type` | `website` (di serie), `article`, `product` |
| `$page_noindex` | `true` = pagina da tenere fuori dai motori di ricerca |
| `$page_canonical` | indirizzo ufficiale, se diverso da quello chiesto |
| `$page_jsonld` | una scheda di dati strutturati, o un elenco di schede |
| `$page_published` / `$page_modified` / `$page_author` | date e autore, solo per gli articoli |

Funzioni utili dentro `seo.php`: `seo_riassunto()` (riduce un testo a descrizione),
`seo_briciole()` (il percorso Home › Forum › Categoria), `seo_url()`, `seo_data()`.

**L'indirizzo ufficiale (canonical)** si calcola da solo togliendo `.php`, la barra finale,
`page=1` e tutti i parametri appiccicati dai social (`utm_*`, `fbclid`). Senza, la stessa
pagina raggiunta in cinque modi diversi conta come cinque pagine che si fanno concorrenza.

**Le pagine private** (accesso, gestionale, profilo, acquisto, "nuovo articolo") escono dai
motori di ricerca da sole: l'elenco è `SEO_PERCORSI_PRIVATI` in `seo.php`, e c'è la seconda
rete in `public/robots.txt`.

### Dati strutturati già presenti
- ogni pagina: `Organization` + `WebSite`
- articoli del blog: `BlogPosting` (autore, date, immagine) + briciole
- discussioni del forum: `DiscussionForumPosting` (risposte, visite) + briciole
- pacchetti dello store: `Product` con `Offer` — **il prezzo è quello vero**, sconti
  compresi, perché lo calcola `store_prezzo()`, la stessa funzione della cassa
- regolamento, pagine del gestionale, categorie del forum: briciole

### Mappa del sito
`https://magicadventure.it/sitemap.xml` — la genera `public/sitemap.php` leggendo il
database, quindi un articolo o una discussione nuova ci finiscono da soli. Nginx la serve
da `/sitemap.xml` (vedi `location = /sitemap.xml`).

### Indirizzi parlanti
| prima | adesso |
|---|---|
| `/blog/post?slug=articolo-1` | `/blog/articolo-1` |
| `/forum/category?slug=annunci` | `/forum/annunci` |
| `/forum/topic?id=3` | `/forum/discussione/3` |

I vecchi indirizzi non si rompono: rispondono **301** verso i nuovi (la mappa
`$indirizzo_pulito` in cima a `nginx-magicadventure.conf`). Le riscritture stanno a livello
di `server`, non dentro una `location`, e i nomi dei file veri (`post`, `new`, `index`,
`topic`, `category`) sono esclusi apposta: se non lo fossero, `/blog/post?slug=x` finirebbe
su se stesso perdendo lo slug.

### Da fare a mano (non lo può fare il codice)
1. **Google Search Console** — registrare `magicadventure.it` e inviare la sitemap. È il
   passo che fa partire tutto: senza, l'indicizzazione va avanti da sola ma alla cieca.
2. Nel gestionale, *Aspetto*: controllare **Titolo della home**, **Descrizione per i motori
   di ricerca** e caricare un'**immagine per le anteprime dei link** 1200×630.
3. Le copertine degli articoli caricate con un indirizzo esterno (immagini prese da altri
   siti) restano su quei server: non passano dall'alleggerimento, possono sparire da un
   giorno all'altro e non hanno la copia WebP. Meglio caricare il file con **Scegli**.

---

## 2. Prestazioni

Misure sulla home, prima e dopo (22 agosto 2026):

| | prima | dopo |
|---|---|---|
| protocollo | HTTP/1.1 | **HTTP/2** |
| foglio di stile | 146 kB non compressi | **36 kB** (gzip) |
| logo della home | 1 170 kB (PNG) | **158 kB** (WebP) |
| caratteri | 2 connessioni a Google | **serviti da qui** |
| cache di stile/copioni | nessuna (solo ETag) | **1 anno**, immutable |
| peso totale prima pagina | ~1,9 MB | **~77 kB** |

**Come**:
- `http2 on` e `gzip` con la lista dei tipi (di serie nginx comprime **solo** l'HTML: era
  questo il motivo dei 146 kB di CSS a ogni prima visita);
- cache lunga su `/assets/`: un anno per CSS e JS (l'indirizzo porta sempre `?v=<data del
  file>`, quindi un aggiornamento si vede subito), un mese per immagini e caratteri;
- **WebP automatico**: `includes/immagini.php` rimpicciolisce le immagini caricate oltre
  1600 px e affianca una copia `.webp`; nginx sceglie quale servire guardando cosa dice di
  capire il browser (`map $http_accept $webp_gemello` + `Vary: Accept`). Gli indirizzi non
  cambiano, quindi database e link condivisi restano intatti;
- **caratteri sul nostro server** (`/assets/fonts`, dichiarazioni in
  `/assets/css/caratteri.css` incollate dentro la pagina): niente più due connessioni a
  fonts.googleapis.com prima di poter disegnare il testo, e nessun indirizzo IP di
  visitatore spedito a Google. Sono file *variabili*: uno solo per famiglia copre tutti i
  pesi da 400 a 700;
- misure dichiarate sulle immagini (`width`/`height`) e `fetchpriority="high"` sul logo,
  così la pagina non salta mentre carica.

### Rifare la conversione delle immagini (se serve)
```bash
sudo -u www-data php -r 'require "/var/www/magicadventure/includes/immagini.php";
foreach (glob("/var/www/magicadventure/public/assets/img/{,caricate/}*.{png,jpg,jpeg}", GLOB_BRACE) as $f) immagine_ottimizza($f);'
```
Le immagini caricate da qui in avanti passano già da sole per `immagine_ottimizza()`
(vedi `public/api/carica-immagine.php`).

---

## 3. Sicurezza (intestazioni)

`nginx-intestazioni-sicurezza.conf` → `/etc/nginx/snippets/magicadventure-sicurezza.conf`:
HSTS un anno, `nosniff`, `X-Frame-Options: SAMEORIGIN`, `Referrer-Policy`,
`Permissions-Policy`. Sta in un file a parte perché nginx, appena una `location` scrive un
suo `add_header`, **perde tutti quelli del blocco `server`**: ogni location che aggiunge
intestazioni deve quindi includere di nuovo lo snippet.

Non c'è una `Content-Security-Policy`: il sito usa stili e script scritti dentro la pagina
(colori del tema, interruttore chiaro/scuro), quindi una regola stretta li spegnerebbe. Se
un giorno serve, va costruita con i nonce, non a mano.

---

## 4. File toccati

```
includes/seo.php                  NUOVO — titoli, canonical, social, dati strutturati
includes/immagini.php             NUOVO — WebP, ridimensionamento, misure
includes/header.php               meta tag, JSON-LD, caratteri locali
public/sitemap.php                NUOVO — mappa del sito dal database
public/robots.txt                 NUOVO
public/assets/css/caratteri.css   NUOVO — @font-face locali
public/assets/fonts/*.woff2       NUOVI — 4 file variabili
public/index.php, blog/post.php, forum/*.php, pacchetto.php, store.php,
public/page.php, regolamento.php, classifiche.php   descrizioni + schede + indirizzi nuovi
public/manage.php                 due campi nuovi in Aspetto (titolo home, immagine social)
public/api/carica-immagine.php    alleggerimento al caricamento
nginx-magicadventure.conf         HTTP/2, gzip, cache, WebP, indirizzi parlanti, sitemap
nginx-intestazioni-sicurezza.conf NUOVO — snippet delle intestazioni
migrazioni/2026-08-22-seo-titolo-home-e-immagine-social.sql
```
