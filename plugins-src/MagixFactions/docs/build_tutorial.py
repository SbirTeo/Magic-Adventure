# -*- coding: utf-8 -*-
import base64, os

# Genera tutorial.html nella cartella di questo script (docs/), incorporando la gif in base64.
# Rilancialo dopo ogni modifica al contenuto:  python docs/build_tutorial.py
#
# SEGNAPOSTO {{NOME}}: NON sono da riempire qui. Questo file e' un MODELLO — i numeri che dipendono dal
# config.yml (Potenza guadagnata e ogni quanto, perdita a morte e da offline, tetto territori, tempi del
# decadimento...) li sostituisce il PLUGIN quando riscrive il tutorial all'avvio, leggendoli dalla
# configurazione vera del server: vedi MagixFactions.valoriDelConfig(). Cosi' cambiando una chiave del
# config il tutorial si aggiorna da solo, senza che nessuno debba ricordarsi di venire qui.
# Forme disponibili:  {{cfg:chiave}}  {{secondi:chiave}}  {{ore:chiave}}  {{percento:chiave}}
# {{simbolo:chiave}} (il valore senza i codici colore: "&f&l+" -> "+")
# (con ripiego facoltativo: {{cfg:chiave|10}}). Per una chiave nuova del config NON serve toccare il
# codice Java: basta scriverla qui. Un segnaposto senza valore resta visibile come "{{...}}" e il plugin
# lo segnala nel log all'avvio.
#
# PEZZI CHE VALGONO SOLO IN CERTE CONFIGURAZIONI (una modalita' accesa, una funzione spenta):
#     {{se:map.mode=chat}} ...testo... {{/se}}      (con != per "in tutti gli altri casi")
# Il blocco sparisce se il config dice altro. E' cosi' che il capitolo della mappa racconta la mappa in
# CHAT o la mappa-ITEM a seconda di map.mode, invece di descrivere sempre quella che era in uso il
# giorno in cui e' stata scritta. I blocchi SI ANNIDANO (si risolvono dal piu' interno in fuori).
#
# Restano a MagixFactions.guideValues() solo le frasi che cambiano FORMA (non un semplice c'e'/non c'e'):
# liste lette dal config (gradi, blocchi di valore, oggetti richiesti) e la sequenza dei prezzi dei claim.
#
# OGNI chiave di config.yml che tocca i giocatori va raccontata qui; quelle tecniche o solo estetiche
# hanno [solo staff] nel commento del config. la regola dieci di check_config.py blocca il commit se una chiave non e'
# ne' l'una ne' l'altra cosa.
DOCS = os.path.dirname(os.path.abspath(__file__))
gif_path = os.path.join(DOCS, "decadimento-territori.gif")
with open(gif_path, "rb") as f:
    gif_b64 = base64.b64encode(f.read()).decode("ascii")
GIF = "data:image/gif;base64," + gif_b64

HTML = r"""<!DOCTYPE html>
<html lang="it">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>MagixFactions — Guida per nuovi giocatori</title>
<style>
  :root{
    /* Dichiara che questo e' un documento SCURO: dentro il sito la guida sta in un iframe e
       Chrome gli dipinge il fondo di BIANCO se il suo schema di colori non combacia con
       quello della pagina che lo ospita (si vedeva una fascia bianca dietro ai capitoli). */
    color-scheme:dark;
    --bg:#12131a; --card:#1b1d26; --card2:#22242f; --line:#2c2f3a;
    --txt:#e8e9ee; --sub:#a9adbd; --green:#4bbd5a; --gold:#f4c531;
    --magenta:#d876e0; --red:#e05a5a; --cyan:#7ec8ff; --chip:#262a36;
    /* I due colori del titolo sono presi dalla locandina "Factions Hardcore":
       arancione della prima parola, giallo della seconda. */
    --arancio:#fd7702; --giallo:#fdd101;
  }
  *{box-sizing:border-box}
  body{margin:0;background:var(--bg);color:var(--txt);
    font-family:ui-sans-serif,system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;line-height:1.6}
  .wrap{max-width:880px;margin:0 auto;padding:32px 20px 80px}
  header{text-align:center;padding:34px 20px 26px;border-bottom:1px solid var(--line);margin-bottom:8px}
  header .logo{font-size:34px;font-weight:800;letter-spacing:.5px}
  header .logo b{color:var(--arancio)} header .logo i{color:var(--giallo);font-style:normal}
  header p{color:var(--sub);margin:.5rem 0 0;font-size:15px}
  .toc{background:var(--card);border:1px solid var(--line);border-radius:12px;padding:16px 20px;margin:22px 0}
  .toc h3{margin:.1rem 0 .6rem;font-size:13px;letter-spacing:.12em;text-transform:uppercase;color:var(--sub)}
  .toc ol{margin:0;padding-left:20px;columns:2;column-gap:30px}
  .toc a{color:var(--cyan);text-decoration:none} .toc a:hover{text-decoration:underline}
  section{background:var(--card);border:1px solid var(--line);border-radius:14px;padding:22px 24px;margin:20px 0;
    /* Dentro il sito questo file vive in un iframe sotto la barra fissa del sito (che qui non
       esiste: questo documento e' scuro e autonomo, si apre anche da solo). Il salto a un
       capitolo di solito lo aggancia il JS del sito (tutorial.php), che calcola lo scarto giusto
       — ma se un click arriva PRIMA che quell'aggancio sia pronto (pagina appena aperta), il
       browser fa un salto NATIVO, non intercettato: senza margine il titolo finiva nascosto
       sotto la barra (segnalato da un giocatore loggato). Questo valore approssima l'altezza
       della barra del sito (a schermo largo ~65px, stretta ~98px) piu' un margine: non e' esatto
       in ogni caso ma e' sempre meglio di zero, ed e' innocuo quando il file si apre da solo
       (nessuna barra sopra, resta solo un margine in piu' prima del titolo). */
    scroll-margin-top:110px}
  h2{margin:.1rem 0 .7rem;font-size:22px;display:flex;align-items:center;gap:10px}
  h2 .n{display:inline-flex;width:30px;height:30px;flex:none;align-items:center;justify-content:center;
    background:var(--green);color:#0c1a0e;border-radius:9px;font-size:15px;font-weight:800}
  h3{margin:1.1rem 0 .4rem;font-size:16px;color:var(--gold)}
  p{margin:.5rem 0} .sub{color:var(--sub)}
  code,.cmd{font-family:ui-monospace,SFMono-Regular,Menlo,monospace}
  .cmd{background:var(--chip);color:#9fe6a8;border:1px solid var(--line);border-radius:7px;
    padding:2px 8px;font-size:13.5px;white-space:nowrap;display:inline-block}
  .tip,.warn{border-radius:10px;padding:12px 15px;margin:14px 0;font-size:14.5px}
  .tip{background:rgba(75,189,90,.09);border-left:4px solid var(--green)}
  .warn{background:rgba(224,90,90,.10);border-left:4px solid var(--red)}
  ul{margin:.4rem 0 .4rem;padding-left:22px} li{margin:.25rem 0}
  table{width:100%;border-collapse:collapse;margin:12px 0;font-size:14px}
  th,td{text-align:left;padding:8px 10px;border-bottom:1px solid var(--line)}
  th{color:var(--sub);font-weight:600;font-size:12.5px;text-transform:uppercase;letter-spacing:.05em}
  td .cmd{font-size:13px}
  .grid{display:grid;grid-template-columns:repeat(auto-fill,26px);gap:5px;margin:10px 0}
  .c{width:26px;height:26px;border-radius:5px;display:flex;align-items:center;justify-content:center;font-size:14px}
  .own{background:var(--green)} .ally{background:var(--magenta)} .enemy{background:var(--red)}
  .neu{background:#2c2f3a;color:#5b6070} .home{background:var(--gold);color:#5a4300}
  .me{background:#eaeaea;color:#111;font-weight:800}
  .legend{display:flex;flex-wrap:wrap;gap:16px;margin:10px 0 4px;font-size:13.5px;color:var(--sub)}
  .legend span{display:flex;align-items:center;gap:7px}
  .sw{width:14px;height:14px;border-radius:4px;display:inline-block}
  .chat{background:#0e0f14;border:1px solid var(--line);border-radius:10px;padding:12px 14px;
    font-family:ui-monospace,Menlo,monospace;font-size:13px;line-height:1.7;margin:10px 0}
  .g{color:var(--gold)} .w{color:#e8e9ee} .gr{color:#8b8f9e} .rd{color:var(--red)} .gn{color:var(--green)}
  .mg{color:var(--magenta)} .cy{color:var(--cyan)}
  /* Il margine SOTTO tiene conto delle etichette -10/0/+10, che sono in posizione assoluta a top:30px
     e quindi escono dalla barra: con un margine piccolo finivano sopra la prima riga dell'elenco. */
  .powerbar{position:relative;height:26px;border-radius:13px;margin:14px 0 30px;
    background:linear-gradient(90deg,#e05a5a 0%,#3a3d49 50%,#4bbd5a 100%);border:1px solid var(--line)}
  .powerbar .mk{position:absolute;top:-6px;width:3px;height:38px;background:#fff;border-radius:2px}
  .powerbar .lab{position:absolute;top:30px;transform:translateX(-50%);font-size:11px;color:var(--sub)}
  .steps{counter-reset:s;list-style:none;padding-left:0}
  .steps li{position:relative;padding:8px 0 8px 42px;border-bottom:1px dashed var(--line)}
  .steps li:before{counter-increment:s;content:counter(s);position:absolute;left:0;top:8px;width:28px;height:28px;
    background:var(--card2);border:1px solid var(--line);border-radius:50%;display:flex;align-items:center;
    justify-content:center;color:var(--gold);font-weight:700;font-size:13px}
  figure{margin:16px 0;text-align:center} figure img{max-width:100%;border-radius:12px;border:1px solid var(--line)}
  figcaption{color:var(--sub);font-size:13px;margin-top:8px}
  footer{color:var(--sub);text-align:center;font-size:13px;margin-top:34px;border-top:1px solid var(--line);padding-top:18px}
  a.top{color:var(--cyan);text-decoration:none;font-size:12.5px}
  /* Telefono: la tabella dei comandi e' piu' larga dello schermo, qui scorre da sola
     invece di allargare tutta la pagina. L'indice passa a una colonna. */
  @media (max-width:520px){
    .wrap{padding:20px 14px 60px}
    section{padding:18px 16px}
    .toc ol{columns:1}
    table{display:block;overflow-x:auto}
    .cmd{white-space:normal}
  }
</style>
</head>
<body>
<div class="wrap">
<header>
  <div class="logo"><b>Magix</b><i>Factions</i></div>
  <p>Guida rapida per nuovi giocatori — fazioni, potenza e territori su MagicAdventure</p>
</header>

<div class="warn">
  <b>Prima di tutto: il pacchetto risorse è obbligatorio.</b> Quando entri, il gioco ti chiede di
  scaricare il <b>pacchetto risorse</b> di MagicAdventure: <b>accettalo</b>. Serve per la grafica del
  server{{se:map.mode=item}} (mappa e minimap comprese){{/se}}{{se:map.mode=chat}} (minimap compresa){{/se}} e <b>senza non si può giocare</b> — chi lo rifiuta o non riesce a
  scaricarlo viene disconnesso.<br>
  Se ti viene rifiutato in automatico, controlla in <i>Opzioni → Multigiocatore → Pacchetti risorse del
  server</i> che siano <b>consentiti</b>, poi rientra.
</div>

<div class="toc">
  <h3>Indice</h3>
  <ol>
    <li><a href="#s1">Cos'è una fazione</a></li>
    <li><a href="#s2">Creare la tua fazione</a></li>
    <li><a href="#s3">Membri, gradi e permessi</a></li>
    <li><a href="#s4">Chat di fazione</a></li>
    <li><a href="#s5">Alleati e nemici</a></li>
    <li><a href="#s6">La Potenza</a></li>
    <li><a href="#s7">Conquistare territori</a></li>
    <li><a href="#s8">La banca della fazione</a></li>
    <li><a href="#s9">La mappa (/f map)</a></li>
    <li><a href="#s10">La casa (/f home)</a></li>
    <li><a href="#s11">Perdere territori (sovraccarico)</a></li>
    <li><a href="#s12">Guardare le info</a></li>
    <li><a href="#sPvp">PvP: colpire i nemici</a></li>
    <li><a href="#sTop">La classifica del server</a></li>
    <li><a href="#s13">Tutti i comandi</a></li>
  </ol>
</div>

<section id="s1">
  <h2><span class="n">1</span>Cos'è una fazione</h2>
  <p>Una <b>fazione</b> è un gruppo di giocatori che gioca insieme: ha un <b>leader</b>, dei <b>membri</b> con
  <b>gradi</b>, può stringere <b>alleanze</b>, accumulare <b>Potenza</b> e <b>conquistare territori</b> (i chunk del
  mondo) per difendere la propria zona.</p>
  <div class="tip">Il comando base è <span class="cmd">/f</span> (funzionano anche <span class="cmd">/mf</span> e
  <span class="cmd">/factions</span>). Scrivi <span class="cmd">/f help</span> per la lista dei comandi in gioco: è
  divisa in pagine (le frecce <b>‹ indietro</b> e <b>avanti ›</b> in fondo, oppure <span class="cmd">/f help 3</span>)
  e <b>ogni riga si clicca</b> per ritrovarsi il comando già scritto nella barra della chat.</div>
</section>

<section id="s2">
  <h2><span class="n">2</span>Creare la tua fazione</h2>
  <p>Per fondare una fazione usa:</p>
  <p><span class="cmd">/f create &lt;nome&gt;</span> — diventi automaticamente il <b>leader</b>.</p>
  {{se:create-cost.money!=0}}{{se:create-cost.money!=0.0}}<div class="warn">Fondare una fazione <b>costa {{cfg:create-cost.money}}</b>: il denaro viene prelevato dal tuo portafoglio nel momento in cui crei la fazione, quindi controlla di averne abbastanza prima di scrivere il comando.</div>{{/se}}{{/se}}
  {{OGGETTI_CREATE}}
  {{se:create-cost.min-playtime-seconds!=0}}<div class="warn">Per fondare una fazione devi aver giocato sul server almeno <b>{{secondi:create-cost.min-playtime-seconds}}</b> in tutto: prima di allora il comando viene rifiutato e ti dice quanto hai giocato finora.</div>{{/se}}
  <h3>Regole del nome</h3>
  <ul>
    <li>solo <b>lettere e numeri</b> (niente spazi, simboli o punteggiatura);</li>
    <li>da <b>{{cfg:faction-name.min-length}} a {{cfg:faction-name.max-length}}</b> caratteri, con al massimo <b>{{cfg:faction-name.max-digits}} cifre</b>;</li>{{se:faction-name.max-consecutive!=0}}
    <li>la stessa lettera può comparire quante volte vuoi, ma al massimo <b>{{cfg:faction-name.max-consecutive}} volte di fila</b>;</li>{{/se}}
    <li>niente parolacce o termini offensivi: un <b>filtro</b> blocca nomi e descrizioni vietati.</li>
  </ul>
  <div class="tip">{{se:faction-description.default!=}}Ogni nuova fazione parte con la descrizione
  «{{cfg:faction-description.default}}». {{/se}}Puoi scrivere la tua con
  <span class="cmd">/f description &lt;testo&gt;</span> (max {{cfg:faction-description.max-length}} caratteri).</div>
  <div class="tip">Il <b>Leader</b> può cambiare il nome della fazione con
  <span class="cmd">/f rename &lt;nuovonome&gt;</span> (stesse regole del nome), al massimo una volta ogni
  <b>{{cfg:rename.cooldown-days}} giorni</b>. Il nuovo nome compare da solo anche sul sito.{{se:rename.cost.money!=0}}{{se:rename.cost.money!=0.0}}
  Il cambio nome costa <b>{{cfg:rename.cost.money}}</b>, pagati dal leader.{{/se}}{{/se}}</div>
  {{OGGETTI_RENAME}}
</section>

<section id="s3">
  <h2><span class="n">3</span>Membri, gradi e permessi</h2>
  <p>Fai crescere la fazione invitando altri giocatori. Una fazione può avere fino a
  <b>{{cfg:members.base}} membri</b>; se il leader è <b>VIP</b> il limite sale.</p>
  <ul>
    <li><span class="cmd">/f invite &lt;giocatore&gt;</span> — invita qualcuno;</li>
    <li><span class="cmd">/f join &lt;fazione&gt;</span> — entra, se sei stato invitato;</li>
    <li><span class="cmd">/f kick &lt;giocatore&gt;</span> — espelli un membro;</li>
    <li><span class="cmd">/f leave</span> — esci dalla fazione.</li>
  </ul>
  <h3>Gradi (rank)</h3>
  <p>Ogni membro ha un <b>grado</b> con un tag colorato. Dal più basso al più alto: {{GRADI_ELENCO}}, che sta
  sopra tutti. Il {{cfg:leader.name}} gestisce i gradi:</p>
  <ul>
    <li><span class="cmd">/f promote &lt;gioc&gt;</span> / <span class="cmd">/f demote &lt;gioc&gt;</span> — sali/scendi di grado;</li>
    <li><span class="cmd">/f transfer &lt;gioc&gt;</span> — cedi il comando della fazione.</li>
  </ul>
  <p class="sub">Ogni grado ha dei <b>permessi</b> (invitare, espellere, gestire relazioni, claimare, usare la home…).
  Di default i comandi "importanti" sono riservati al Leader.</p>
  <div class="tip">Se il leader esce, il comando passa in automatico al membro di grado più alto (a parità, a chi è
  in quel grado da più tempo). Se il leader è l'unico membro e se ne va, la fazione si scioglie.</div>
</section>

<section id="s4">
  <h2><span class="n">4</span>Chat di fazione</h2>
  <p>Con <span class="cmd">/f chat</span> scegli in quale canale parli:</p>
  <ul>
    <li><b>PUBBLICA</b> — la chat normale del server;</li>
    <li><b>FAZIONE</b> — solo i tuoi compagni ti leggono;</li>
    <li><b>ALLEATI</b> — ti leggono la tua fazione e tutte le fazioni alleate.</li>
  </ul>
  <p class="sub">Senza argomento, <span class="cmd">/f chat</span> cambia canale a rotazione; oppure
  <span class="cmd">/f chat faction</span>, ecc.</p>
</section>

<section id="s5">
  <h2><span class="n">5</span>Alleati e nemici</h2>
  <p>Esistono solo due relazioni: <b>alleato</b> e <b>nemico</b>. <b>Di default ogni fazione è nemica di tutte.</b></p>
  <ul>
    <li><span class="cmd">/f ally &lt;fazione&gt;</span> — chiedi un'alleanza. Diventa reale solo quando <b>anche
      l'altra</b> fazione fa lo stesso. Ri-eseguendolo, <b>annulli</b> la richiesta.</li>
    <li><span class="cmd">/f enemy &lt;fazione&gt;</span> — torni nemico: rompe un'alleanza o rifiuta/annulla una richiesta.</li>
  </ul>
  {{se:relations.max-allies!=0}}<div class="warn">Ogni fazione può avere al massimo <b>{{cfg:relations.max-allies}} alleati</b>.</div>{{/se}}
  <div class="legend">
    <span><i class="sw own"></i> la tua fazione</span>
    <span><i class="sw ally"></i> alleata</span>
    <span><i class="sw enemy"></i> nemica</span>
  </div>
  <p class="sub">I nomi delle fazioni sono sempre <b>colorati in base alla tua relazione</b> con loro: verde = tua,
  magenta = alleata, rosso = nemica.</p>
  <div class="tip">Chi incontri lo riconosci <b>prima di avvicinarti</b>: il nome della sua fazione si legge
  <b>sopra la sua testa</b>, sopra il nome. Chi non è in nessuna fazione non ha quella riga.</div>
  <div class="tip">In <span class="cmd">/f info</span> le richieste di alleanza in sospeso compaiono come
  <b>"(in attesa)"</b>: passandoci sopra col mouse leggi se devi accettarle, e cliccando scrivi il comando pronto.</div>
</section>

<section id="s6">
  <h2><span class="n">6</span>La Potenza</h2>
  <p>Ogni giocatore ha un valore di <b>Potenza</b> (da {{POWER_MAX_NEG}} a {{POWER_MAX_POS}}). Serve a conquistare e tenere i territori.</p>
  <div class="powerbar">
    <div class="mk" style="left:15%"></div>
    <div class="lab" style="left:2%">{{POWER_MAX_NEG}}</div>
    <div class="lab" style="left:50%">0</div>
    <div class="lab" style="left:98%">{{POWER_MAX_POS}}</div>
  </div>
  <ul>
    <li>parti da <b>{{cfg:power.start}}</b> e <b>sali di {{cfg:power.gain-amount}}</b> ogni {{secondi:power.gain-interval-seconds}} passati <b>online</b>;</li>
    <li>ad ogni <b>morte perdi {{cfg:power.death-loss}}</b> (puoi andare in negativo);</li>
    {{PERDITA_OFFLINE_LI}}
    <li>la <b>Potenza della fazione</b> è la somma di quella dei membri.</li>
  </ul>
  {{PERDITA_OFFLINE_NOTA}}
  <div class="tip">Più membri (e membri VIP) = più Potenza massima = più territori possibili.</div>
  <div class="tip">I <b>VIP</b> hanno un tetto di Potenza più alto, la <b>recuperano più in fretta</b> (chi ha
  il doppio della velocità guadagna un punto in metà tempo) e la <b>perdono più lentamente</b> stando via.
  Scrivi <span class="cmd">/f power</span>: se le tue velocità sono diverse dal normale te lo dice lì.</div>
  <div class="tip">Si vede anche da fuori: i VIP hanno un'<b>aureola</b> gialla che gira sopra la testa.
  Compare da sola, senza nessun comando; chi preferisce non averla la spegne con
  <span class="cmd">/cosmetics halo off</span> e la rimette con <span class="cmd">/cosmetics halo on</span>
  (dopo un riavvio del server torna accesa).</div>
</section>

<section id="s7">
  <h2><span class="n">7</span>Conquistare territori</h2>
  <p>Un <b>territorio</b> è un <b>chunk</b> (16×16 blocchi). Mettiti dove vuoi e usa:</p>
  <p><span class="cmd">/f claim</span> — conquista il chunk in cui ti trovi.</p>
  <div class="tip">Puoi rivendicare terreno <b>{{MONDI_CLAIM_FRASE}}</b>: nel Nether, nell'End e negli altri mondi il claim non è permesso.</div>
  {{se:claims.protected-spawn.enabled=true}}<div class="warn">Attorno allo <b>spawn</b> c'è un'<b>area protetta</b>: nel mondo «{{cfg:claims.protected-spawn.world}}», un quadrato di <b>{{cfg:claims.protected-spawn.radius}} blocchi</b> su ogni lato dal centro (X {{cfg:claims.protected-spawn.center-x}}, Z {{cfg:claims.protected-spawn.center-z}}). Lì <b>non puoi fondare la fazione né conquistare territori</b>: devi uscire da questo quadrato. I blocchi però <b>non</b> sono protetti — puoi costruire e rompere liberamente, semplicemente non si claima.</div>{{/se}}
  <h3>Quanto costa un territorio</h3>
  <p>{{PREZZI_CLAIM}}</p>
  {{OGGETTI_CLAIM}}
  <h3>Quando puoi claimare (territorio neutrale)</h3>
  <ul>
    <li>non devi aver superato il <b>tetto</b> di territori (il <b>{{percento:claims.max-percent}}</b> della Potenza massima di fazione);</li>
    <li>la <b>Potenza attuale</b> della fazione deve essere <b>maggiore</b> del numero di territori già posseduti.</li>
  </ul>
  <h3>Rubare un territorio nemico (overclaim)</h3>
  <ul>
    <li>oltre alle condizioni sopra, la fazione nemica dev'essere <b>raidabile</b> (Potenza inferiore ai suoi territori);</li>
    <li>puoi prendere solo il chunk <b>più esterno</b> del nemico, non dall'interno.</li>
    <li>nessun chunk è intoccabile: se è sul bordo, anche quello della <b>home</b> nemica si può conquistare.</li>
  </ul>
  <div class="warn">Non puoi conquistare i territori di una fazione <b>alleata</b>.</div>{{se:claims.overclaim-alert.enabled=true}}
  <p class="sub">Una conquista non passa inosservata: quando una fazione ruba un territorio nemico, <b>tutto il
  server</b> riceve un avviso al centro dello schermo con un suono.</p>{{/se}}
  <h3>Rilasciare territori</h3>
  <p><span class="cmd">/f unclaim</span> — rilascia (rende neutrale) il chunk in cui ti trovi. La fazione recupera in banca una <b>parte di quanto aveva pagato</b> per quel territorio (di base il {{percento:claims.unclaim-refund-percent}}).</p>
  <p><span class="cmd">/f unclaimall</span> — rilascia TUTTI i territori della fazione in un colpo solo.</p>
  <div class="warn"><b>/f unclaimall è IRREVERSIBILE.</b> La prima volta mostra solo un avviso a schermo (con un
  suono di pericolo): devi rieseguire il comando entro {{secondi:claims.unclaim-all-confirm-seconds}} per confermare davvero.</div>
  <div class="tip">Se il chunk che rilasci (con <span class="cmd">/f unclaim</span> o <span class="cmd">/f unclaimall</span>)
  conteneva la <b>casa</b> della fazione, la casa viene tolta insieme al territorio: dovrai impostarne una nuova
  con <span class="cmd">/f sethome</span> in un territorio che possiedi ancora.</div>
{{se:protection.enabled=true}}
  <h3>Chi può costruire nei territori</h3>
  <p>Dentro un territorio di una fazione <b>solo i suoi membri</b> possono rompere e piazzare blocchi o usare
  casse, porte, leve, secchi e cornici.{{se:protection.ally-can-build=true}} Lo stesso vale per le fazioni
  <b>alleate</b>: anche loro possono costruire da voi, e voi da loro.{{/se}}{{se:protection.ally-can-build!=true}}
  Nemmeno gli <b>alleati</b> possono costruire da voi.{{/se}} Il terreno di nessuno resta libero per tutti.</p>
  <ul>{{se:protection.block-explosions=true}}
    <li>TNT, creeper ed esplosioni <b>non distruggono</b> blocchi dentro un territorio;</li>{{/se}}{{se:protection.block-fire=true}}
    <li>il <b>fuoco</b> non brucia e non si propaga sui blocchi di un territorio;</li>{{/se}}{{se:protection.block-pistons=true}}
    <li>i <b>pistoni</b> non possono spingere o tirare blocchi oltre il confine di un territorio altrui;</li>{{/se}}{{se:protection.block-liquid-flow=true}}
    <li><b>acqua e lava</b> non possono scorrere dentro un territorio da fuori.</li>{{/se}}
  </ul>
{{/se}}
{{se:protection.enabled!=true}}
  <div class="warn">Su questo server i territori <b>non proteggono i blocchi</b>: chiunque può costruire e
  rompere anche dentro le land altrui. Il territorio conta per la Potenza, la classifica e la casa.</div>
{{/se}}
  <h3>Assegnare una land a un membro — <span class="cmd" style="font-size:14px">/f owner</span></h3>
  <p>Di norma tutti i membri della fazione possono costruire e aprire le casse in ogni vostro territorio. Se
  però vuoi che un <b>singolo chunk</b> sia <b>riservato a una persona</b> — la sua casa, il suo magazzino — il
  <b>leader</b> può assegnarne il proprietario:</p>
  <ul>
    <li>mettiti nel chunk e usa <span class="cmd">/f owner &lt;giocatore&gt;</span> — da quel momento, in quella
      land, <b>solo lui e il leader</b> possono piazzare/rompere blocchi e aprire i contenitori; gli altri
      compagni no;</li>
    <li><span class="cmd">/f owner</span> da solo ti dice <b>chi</b> è il proprietario del chunk;</li>
    <li><span class="cmd">/f owner clear</span> toglie il proprietario e la land torna aperta a tutti i membri.</li>
  </ul>
  <div class="tip">È il modo per fidarti a metà: fai entrare qualcuno nella fazione senza dargli le chiavi di
  <b>tutto</b>. Il proprietario decade da solo se il chunk viene conquistato da un nemico.</div>
</section>

<section id="s8">
  <h2><span class="n">8</span>La banca della fazione</h2>
  <p>Ogni fazione ha una <b>banca comune</b>: un salvadanaio condiviso per far crescere la fazione
  (costi di claim, progetti comuni, guerre...).</p>
  <p><span class="cmd">/f deposit &lt;soldi&gt;</span> (o <span class="cmd">/f d</span>) — <b>versa</b> i tuoi
  soldi nella banca. Possono farlo <b>tutti i membri</b>.</p>
  <p><span class="cmd">/f withdraw &lt;soldi&gt;</span> (o <span class="cmd">/f w</span>) — <b>preleva</b> dalla
  banca. Riservato a chi ha il permesso <b>withdraw</b> del grado: con i gradi di questo server {{GRADI_PRELIEVO}}.</p>
  <p class="sub">Il saldo della banca è sempre visibile in <span class="cmd">/f info</span>.</p>
  <p class="sub"><b>A cosa servono i soldi della banca?</b> Soprattutto a <b>pagare i territori</b>: ogni
  <span class="cmd">/f claim</span> si paga dalla banca della fazione, non dal tuo portafoglio. I prezzi sono
  nel capitolo <a href="#s7">Conquistare territori</a>.</p>
</section>

<section id="s9">
  <h2><span class="n">9</span>La Mappa Fazioni — <span class="cmd" style="font-size:15px">/f map</span></h2>
{{se:map.mode=chat}}
  <p>Il comando <span class="cmd">/f map</span> ti stampa <b>in chat</b> la mappa dei territori attorno a te: un
  quadrato con <b>te al centro</b>, grande quanto lo <b>zoom</b> della tua mappa/minimap (più sei zoomato
  fuori, più territorio vedi in chat — fino a un massimo di <b>{{cfg:map.chat.max-rows}}×{{cfg:map.chat.max-rows}}</b>
  caselle, oltre il quale ogni casella comincia a valere più di un pezzo di terreno). Ogni casella è
  normalmente un pezzo di terreno (un <i>chunk</i>, 16×16 blocchi). Se appartiene a una fazione ci trovi una
  <b>lettera</b>, colorata secondo la tua <b>relazione</b> con chi lo possiede — <b>verde</b> = la tua fazione,
  <b>viola</b> = alleata, <b>rossa</b> = nemica — e sotto la mappa la <b>legenda</b> ti dice di quale fazione è
  ogni lettera.</p>
  <div class="chat">
    <span class="gr">--</span> <span class="g">Mappa Fazioni</span> <span class="gr">(zoom: {{cfg:map.default-zoom}}) --</span><br>
    <span class="gr">- - - - - -</span> <span class="rd">C C</span> <span class="gr">-</span><br>
    <span class="gr">- -</span> <span class="gn">A A</span> <span class="gr">- - -</span> <span class="rd">C</span> <span class="gr">-</span><br>
    <span class="gr">-</span> <span class="gn">A A A A</span> <span class="gr">- - - -</span><br>
    <span class="gr">-</span> <span class="gn">A A</span> <span class="g">⌂</span> <span class="gn">A A</span> <span class="gr">- - -</span><br>
    <span class="gr">-</span> <span class="gn">A A</span> <span class="w"><b>+</b></span> <span class="gn">A</span> <span class="gr">- - - -</span><br>
    <span class="gr">- -</span> <span class="gn">A A A</span> <span class="gr">- - - -</span><br>
    <span class="gr">- - -</span> <span class="gn">A</span> <span class="gr">- -</span> <span class="mg">B B</span> <span class="gr">-</span><br>
    <span class="gr">- - - - -</span> <span class="mg">B B B</span> <span class="gr">-</span><br>
    <span class="gr">- - - - - -</span> <span class="mg">B</span> <span class="gr">- -</span><br>
    <span class="gr">Legenda:</span> <span class="w"><b>+</b></span> <span class="gr">tu</span>
    <span class="g">⌂</span> <span class="gr">casa</span> <span class="gr">- libero</span><br>
    <span class="gn">&nbsp;&nbsp;A = Draghi</span><br>
    <span class="mg">&nbsp;&nbsp;B = Fenix</span><br>
    <span class="rd">&nbsp;&nbsp;C = Nova</span>
  </div>
  <p class="sub">Il <b>{{simbolo:map.chat.symbols.you}}</b> sei tu (sei sempre al centro), il
  <b>{{simbolo:map.chat.symbols.home}}</b> è la <b>casa</b> della tua fazione, il
  <b>{{simbolo:map.chat.symbols.neutral}}</b> è terreno di nessuno. Le lettere sono assegnate lì per lì, quindi
  la stessa fazione può avere una lettera diversa la prossima volta: fidati della legenda, non della lettera.</p>
  <div class="tip">La mappa in chat è una <b>fotografia dell'istante</b>: non ti resta in mano e non occupa posto
  nell'inventario, ma non si aggiorna da sola — cammina e <b>rilancia</b> <span class="cmd">/f map</span> per
  vedere la situazione nuova. La <b>minimap</b> sempre a schermo — un riquadro
  {{se:map.minimap.shape=round}}<b>rotondo</b>{{/se}}{{se:map.minimap.shape=square}}<b>quadrato</b>{{/se}}
  in alto {{se:map.minimap.position=left}}a <b>sinistra</b>{{/se}}{{se:map.minimap.position!=left}}a <b>destra</b>{{/se}}, quello sì che si aggiorna da solo mentre cammini — spetta a chi ha il permesso apposta.
  Se ce l'hai puoi <b>accenderla o spegnerla</b> quando vuoi con <span class="cmd">/f minimap on</span> /
  <span class="cmd">/f minimap off</span>: anche da spenta, la mappa in chat con <span class="cmd">/f map</span>
  resta sempre a disposizione. La scelta resta salvata anche dopo il logout.{{se:map.minimap.info-panel.enabled=true}}
  Sotto la minimap una piccola <b>striscia informativa</b> ti mostra alcune informazioni utili.{{/se}}</div>
{{/se}}
{{se:map.mode=item}}
  <p>Il comando <span class="cmd">/f map</span> ti consegna un <b>item mappa</b> (una mappa vera, come quella di
  Minecraft). <b>Tienila in mano</b> e sopra il terreno vedrai colorati i territori delle fazioni, in base alla tua
  <b>relazione</b> con chi li possiede. È <b>dinamica</b>: si ricentra mentre cammini, come una mappa dei territori in
  tempo reale. La <b>freccia bianca</b> sei <b>tu</b>: punta verso dove guardi e resta sempre nitida (non si sgrana
  ruotando). Gli altri giocatori sono frecce colorate secondo la relazione — <b>verde</b> = compagno di fazione,
  <b>blu</b> = alleato, <b>rossa</b> = nemico — col loro nome accanto.</p>
  <div class="grid">
    <div class="c neu"></div><div class="c neu"></div><div class="c enemy"></div><div class="c enemy"></div><div class="c neu"></div>
    <div class="c neu"></div><div class="c own"></div><div class="c own"></div><div class="c enemy"></div><div class="c neu"></div>
    <div class="c own"></div><div class="c own"></div><div class="c own"></div><div class="c own"></div><div class="c neu"></div>
    <div class="c neu"></div><div class="c own"></div><div class="c ally"></div><div class="c ally"></div><div class="c neu"></div>
  </div>
  <div class="legend">
    <span><i class="sw own"></i> tua fazione</span>
    <span><i class="sw ally"></i> alleata</span>
    <span><i class="sw enemy"></i> nemica</span>
    <span><i class="sw neu"></i> terreno libero</span>
  </div>
  <p class="sub">Bordo e interno di ogni territorio sono una tinta sul terreno reale (il bordo un po' più marcato).</p>
  <div class="warn">La mappa è <b>tua e basta</b>: rifare <span class="cmd">/f map</span> sostituisce sempre quella
  che hai già (non puoi averne due), ed è taggata con il tuo nome — se la regali o la fai tenere a un altro
  giocatore, per lui <b>non funziona</b> (niente territori colorati). Se hai l'inventario pieno non la ricevi:
  libera uno slot e riprova.</div>
{{/se}}
{{se:borders.enabled=true}}
  <div class="tip"><b>Dove finisce una land?</b> Con <span class="cmd">/f borders on</span> vedi delle
  <b>particelle colorate</b> lungo i confini dei territori entro <b>{{cfg:borders.radius}} chunk</b> da te
  (verde i tuoi, magenta gli alleati, rosso i nemici). Le vedi solo tu; <span class="cmd">/f borders off</span>
  le spegne.</div>
{{/se}}
</section>

<section id="s10">
  <h2><span class="n">10</span>La casa della fazione — <span class="cmd" style="font-size:15px">/f home</span></h2>
  <ol class="steps">
    <li>Mettiti in un <b>tuo territorio</b> e usa <span class="cmd">/f sethome</span> per impostare la casa.{{se:sethome-cost.money!=0}}{{se:sethome-cost.money!=0.0}}
      Impostarla costa <b>{{cfg:sethome-cost.money}}</b>.{{/se}}{{/se}}</li>
    <li>Da qualsiasi punto, <span class="cmd">/f home</span> ti <b>teletrasporta</b> alla casa della fazione dopo
      <b>{{secondi:home-warmup.seconds}}</b> di attesa immobile: se ti muovi (anche di un blocco) o <b>subisci un
      danno</b> (un mob, una caduta, il fuoco, un altro giocatore...) il teletrasporto si annulla e va ripetuto
      il comando — non è una via di fuga. Attaccare un altro giocatore annulla il tuo, anche se non subisci
      danno tu.</li>
    <li><span class="cmd">/f unsethome</span> toglie la casa: la fazione resta senza, e <span class="cmd">/f home</span>
      non porta più da nessuna parte finché non ne imposti un'altra.</li>
  </ol>
  {{OGGETTI_SETHOME}}
  <div class="tip">Il chunk della home è il <b>cuore</b> della fazione: per <b>sovraccarico</b> non lo perdi
  mai, perché il decadimento mangia sempre i territori più lontani e si ferma prima di toccarlo (vedi sotto).</div>
  <div class="warn"><b>Ma conquistare si può.</b> Un nemico che ti trova <b>raidabile</b> può prendersi anche il
  chunk della home, come qualsiasi altro. Se succede <b>perdi la casa</b>: tutta la fazione riceve l'avviso, la
  home viene <b>cancellata</b> e <span class="cmd">/f home</span> non porta più da nessuna parte finché non ne
  imposti un'altra con <span class="cmd">/f sethome</span> in un territorio tuo. È voluto: se restasse dov'era,
  <span class="cmd">/f home</span> vi teletrasporterebbe uno alla volta <b>dentro la base del nemico</b>.</div>
</section>

<section id="s11">
  <h2><span class="n">11</span>Perdere territori (sovraccarico)</h2>
  <p>Se la fazione possiede <b>più territori del tetto</b> — ad esempio dopo aver perso un membro — scatta un
  allarme: tutti i membri online ricevono un <b>avviso al centro dello schermo con un suono di pericolo</b>,
  ripetuto ogni {{secondi:decay.warn-interval-seconds}} finché la situazione non si sistema.</p>
  <p>Se non rientri nel limite (di solito <b>invitando un altro giocatore</b>) entro <b>{{ore:decay.grace-hours}}</b>, la fazione
  inizia a <b>perdere 1 territorio ogni {{ore:decay.loss-interval-hours}}</b>. Si perdono sempre i chunk <b>più lontani dalla home</b>, e il chunk
  della <b>home non si perde mai</b>.</p>
  <p>Non è una punizione a tempo: <b>la perdita si ferma da sola</b> appena la fazione torna stabile. I territori
  continuano a cadere uno per volta finché quelli posseduti non tornano <b>pari o inferiori al tetto</b>, cioè al
  <b>{{percento:claims.max-percent}} della Potenza massima</b> della fazione. Puoi fermarla in qualsiasi momento in due modi: far entrare un
  membro (alza il tetto) oppure rilasciare tu i territori di troppo con <span class="cmd">/f unclaim</span>.</p>
  <div class="tip">Non confondere le due soglie, perché guardano numeri diversi. Il <b>tetto</b>, che ferma il
  decadimento, dipende dalla Potenza <b>massima</b> della fazione. L'essere <b>attaccabile</b> dai nemici dipende
  invece dalla Potenza <b>attuale</b>: finché resta pari o superiore al numero di territori posseduti, nessuno può
  conquistarti terreno — anche se sei in pieno decadimento.</div>
  <figure>
    <img src="__GIF__" alt="Animazione del decadimento dei territori verso la home">
    <figcaption>Il territorio si "restringe" verso la home (in oro), perdendo prima i gruppi più lontani.</figcaption>
  </figure>
  <div class="warn">Regola d'oro: <b>tieni la Potenza alta e i membri attivi</b>, e imposta la <b>home</b> nel cuore
  del tuo territorio.</div>
</section>

<section id="s12">
  <h2><span class="n">12</span>Guardare le info</h2>
  <p><span class="cmd">/f info</span> mostra tutto sulla tua fazione (o su un'altra: <span class="cmd">/f info &lt;nome&gt;</span>):</p>
  <div class="chat">
    <span class="g">Fazione:</span> <span class="gn">Draghi</span><br>
    <span class="g">Descrizione:</span> <span class="w">Questa è una fazione di MagicAdventure!</span><br>
    <span class="g">Membri:</span> <span class="w">3/5</span><br>
    <span class="gr">([L] Teo, [U] Alex, [R] Sam)</span><br>
    <span class="g">Stato:</span> <span class="gn">3/5/30</span> <span class="gr">(territori/potenza/potenza-max)</span><br>
    <span class="gn">La fazione è forte. Non puoi conquistarla.</span><br>
    <span class="g">Banca:</span> <span class="w">12.500</span><br>
    <span class="g">Punteggio:</span> <span class="cy">72,5</span> <span class="gr">(#1 in classifica)</span><br>
    <span class="g">Alleati:</span> <span class="mg">Fenix</span><span class="gr">,</span> <span class="w">Nova (in attesa)</span>
  </div>
  <p class="sub">Lo <b>Stato</b> è verde se la fazione è al sicuro, rosso se è <b>raidabile</b>, bianco se non ha territori.</p>
  <p class="sub">Il <b>Punteggio</b> e la posizione in classifica li spieghiamo qui sotto.</p>
  <p><span class="cmd">/f list</span> elenca invece <b>tutte</b> le fazioni del server.</p>
</section>

<section id="sPvp">
  <h2><span class="n">⚔</span>PvP: colpire i nemici</h2>
  <p>Fuori dai territori protetti si combatte.{{se:combat.friendly-fire.faction=true}} Tra <b>compagni di
  fazione</b> il PvP è <b>disattivato</b>: se provi a colpirli, <b>il colpo non fa danno</b>.{{/se}}{{se:combat.friendly-fire.faction!=true}}
  Attenzione: anche i <b>compagni di fazione</b> possono colpirsi.{{/se}}{{se:combat.friendly-fire.allies=true}}
  Lo stesso vale per gli <b>alleati</b>: tra fazioni alleate i colpi non fanno danno.{{/se}}{{se:combat.friendly-fire.allies!=true}}
  Gli <b>alleati</b> invece possono colpirsi fra loro.{{/se}}</p>
  <p>Ogni nemico che abbatti conta come <b>uccisione</b>, e ogni volta che ti uccidono è una <b>morte</b>: da
  questi due numeri esce il tuo <b>K/D</b> (uccisioni ÷ morti), che vedi nella <b>classifica giocatori</b> sul
  sito. Le uccisioni valide contano anche per la voce <b>Uccisioni</b> del punteggio della tua fazione.</p>
  <div class="warn"><b>Le «fake kill» non contano.</b> Per evitare che qualcuno gonfi il K/D facendosi uccidere
  da un amico, un'uccisione <b>non</b> conta:
  <ul>{{se:combat.min-victim-lifetime-seconds!=0}}
    <li>se la vittima era viva da meno di <b>{{secondi:combat.min-victim-lifetime-seconds}}</b> (appena rinata);</li>{{/se}}{{se:combat.kill-cooldown-minutes!=0}}
    <li>se uccidi di nuovo <b>la stessa persona</b> entro <b>{{cfg:combat.kill-cooldown-minutes}} minuti</b>;</li>{{/se}}{{se:combat.same-ip-no-credit=true}}
    <li>se tu e la vittima siete collegati <b>dalla stessa rete</b> (doppi account);</li>{{/se}}
    <li>se colpire quella persona non era permesso (compagni e alleati protetti).</li>
  </ul>
  In quei casi né tu prendi l'uccisione né la vittima prende la morte.</div>
</section>

<section id="sTop">
  <h2><span class="n">C</span>La classifica del server — <span class="cmd" style="font-size:15px">/f top</span></h2>
  <p><span class="cmd">/f top</span> mostra la <b>classifica</b> delle fazioni. Non conta chi ha più territori
  e basta, o più soldi e basta: ogni fazione ha un <b>Punteggio</b> che la confronta con le altre <b>voce
  per voce</b>.</p>
  <div class="chat">
    <span class="g">— Classifica fazioni (4) —</span><br>
    <span class="gr">#1</span> <span class="gn">Draghi</span> <span class="gr">-</span> <span class="cy">3,84</span><br>
    <span class="gr">#2</span> <span class="rd">Corvi</span> <span class="gr">-</span> <span class="cy">2,91</span><br>
    <span class="gr">#3</span> <span class="rd">Lupi</span> <span class="gr">-</span> <span class="cy">1,57</span><br>
    <span class="gr">#4</span> <span class="rd">Volpi</span> <span class="gr">-</span> <span class="cy">0,88</span>
  </div>
  <p><b>Come funziona:</b> per ogni caratteristica, la fazione <b>migliore</b> vale il massimo e tu vali
  <b>in proporzione a lei</b> (metà dei suoi territori = metà del punteggio di quella voce). Sommando tutte
  le voci esce il tuo Punteggio.</p>
  <p>Le caratteristiche che contano, ognuna col massimo di punti che può dare a chi è il migliore:</p>
  <ul>{{se:score.weights.land!=0}}
    <li><b>Territori</b> (fino a {{cfg:score.weights.land}}) — quanti chunk possiede la fazione.</li>{{/se}}{{se:score.weights.members!=0}}
    <li><b>Membri</b> (fino a {{cfg:score.weights.members}}) — quanti giocatori ha la fazione.</li>{{/se}}{{se:score.weights.bank!=0}}
    <li><b>Banca</b> (fino a {{cfg:score.weights.bank}}) — {{se:score.bank-value=average}}la <b>giacenza media</b>, cioè quanti soldi tenete <b>nel tempo</b>.
      Sul sito la stessa voce si chiama <b>Ricchezza media</b>: è lo stesso numero, cambia solo il nome.{{/se}}{{se:score.bank-value!=average}}i soldi che ci sono <b>adesso</b> nella banca.{{/se}}</li>{{/se}}{{se:score.weights.longevity!=0}}
    <li><b>Longevità</b> (fino a {{cfg:score.weights.longevity}}) — da quanti giorni esiste la fazione.</li>{{/se}}{{se:score.weights.power!=0}}
    <li><b>Potenza</b> (fino a {{cfg:score.weights.power}}) — {{se:score.power-value=average}}la potenza <b>media nel tempo</b> della fazione.{{/se}}{{se:score.power-value!=average}}la potenza <b>attuale</b> della fazione.{{/se}}</li>{{/se}}{{se:score.weights.kills!=0}}
    <li><b>Combattimento</b> (fino a {{cfg:score.weights.kills}}) — {{se:score.combat-metric=kd}}il rapporto <b>uccisioni/morti</b> (K/D) dei membri{{/se}}{{se:score.combat-metric!=kd}}quanti nemici ha abbattuto la fazione{{/se}} (solo uccisioni <b>valide</b>, vedi il capitolo sul PvP).</li>{{/se}}{{se:score.weights.value!=0}}
    <li><b>Valore</b> (fino a {{cfg:score.weights.value}}) — i <b>blocchi preziosi</b> piazzati dentro le vostre land
      (romperli toglie valore, e il valore passa a chi conquista il territorio). Ogni blocco vale:
      {{BLOCCHI_VALORE}}</li>{{/se}}
  </ul>
  <p class="sub">{{se:score.bank-value=average}}La <b>banca</b> conta la <b>media nel tempo</b> (solo il tempo in
  cui qualcuno della fazione è online): mettere un milione un attimo prima di guardare la classifica <b>non
  serve</b> — contano i soldi che tieni davvero. {{/se}}Il punteggio è <b>relativo</b>: può scendere anche senza che tu faccia nulla, se un'altra fazione batte un
  record. Per salire, <b>cresci su tutto</b> e prova a essere il migliore in qualche voce.</p>
  <p class="sub"><span class="cmd">/f top</span> mostra le prime <b>{{cfg:score.top-size}}</b> fazioni; se la tua
  è più in basso, la trovi in fondo con la sua posizione.</p>
  {{se:score.inactive-days!=0}}<p class="sub">Le fazioni <b>inattive</b> spariscono dalla classifica: se <b>tutti</b> i membri non entrano
  da più di <b>{{cfg:score.inactive-days}} giorni</b>, la fazione viene nascosta (e torna da sola appena qualcuno si ricollega). La classifica mostra
  chi <b>gioca davvero</b>.</p>{{/se}}
  <p class="sub">Il tuo Punteggio e la tua posizione li vedi anche in <span class="cmd">/f info</span> e sul
  <b>sito</b>. Sul sito, oltre alla classifica delle fazioni, c'è anche quella dei <b>giocatori</b>: per
  <b>tempo di gioco</b>, <b>ricchezza media</b> e <b>uccisioni/K-D</b>.</p>
  <p class="sub">Il <b>tempo di gioco</b> è quello <b>vero</b>: te lo conta Minecraft da sempre, quindi ci sono
  dentro anche le ore che hai giocato qui prima che arrivassero le classifiche. <b>Uccisioni</b> e
  <b>ricchezza media</b> no: quelle partono da quando è arrivato il sistema di punteggio, e crescono da lì.</p>
</section>

<section id="s13">
  <h2><span class="n">13</span>Tutti i comandi</h2>
  <table>
    <tr><th>Comando</th><th>Cosa fa</th></tr>
    <tr><td><span class="cmd">/f create &lt;nome&gt;</span></td><td>Crea una fazione (diventi leader)</td></tr>
    <tr><td><span class="cmd">/f invite &lt;gioc&gt;</span></td><td>Invita un giocatore</td></tr>
    <tr><td><span class="cmd">/f join &lt;fazione&gt;</span></td><td>Entra se invitato</td></tr>
    <tr><td><span class="cmd">/f leave</span></td><td>Esci dalla fazione</td></tr>
    <tr><td><span class="cmd">/f kick &lt;gioc&gt;</span></td><td>Espelli un membro</td></tr>
    <tr><td><span class="cmd">/f promote / demote &lt;gioc&gt;</span></td><td>Cambia grado a un membro</td></tr>
    <tr><td><span class="cmd">/f transfer &lt;gioc&gt;</span></td><td>Cedi il comando (leader)</td></tr>
    <tr><td><span class="cmd">/f chat [public|faction|ally]</span></td><td>Cambia canale chat</td></tr>
    <tr><td><span class="cmd">/f ally &lt;fazione&gt;</span></td><td>Chiedi/annulla un'alleanza</td></tr>
    <tr><td><span class="cmd">/f enemy &lt;fazione&gt;</span></td><td>Torna nemico / rompi alleanza</td></tr>
    <tr><td><span class="cmd">/f claim</span></td><td>Conquista il chunk dove sei</td></tr>
    <tr><td><span class="cmd">/f unclaim</span></td><td>Rilascia il chunk dove sei</td></tr>
    <tr><td><span class="cmd">/f unclaimall</span></td><td>Rilascia TUTTI i territori (irreversibile, chiede conferma)</td></tr>
    <tr><td><span class="cmd">/f owner [gioc|clear]</span></td><td>Proprietario della land dove sei (solo leader)</td></tr>
    <tr><td><span class="cmd">/f map</span></td><td>{{se:map.mode=chat}}Stampa in chat la mappa dei territori attorno a te{{/se}}{{se:map.mode=item}}Ricevi la Mappa Fazioni (item dinamico){{/se}}</td></tr>
{{se:borders.enabled=true}}    <tr><td><span class="cmd">/f borders &lt;on|off&gt;</span></td><td>Accendi/spegni le particelle sui confini delle land</td></tr>
{{/se}}    <tr><td><span class="cmd">/f minimap &lt;on|off&gt;</span></td><td>Accendi/spegni la minimap a schermo (se hai il permesso)</td></tr>
    <tr><td><span class="cmd">/f sethome / home</span></td><td>Imposta / vai alla casa della fazione</td></tr>
    <tr><td><span class="cmd">/f unsethome</span></td><td>Toglie la casa della fazione</td></tr>
    <tr><td><span class="cmd">/f description &lt;testo&gt;</span></td><td>Imposta la descrizione</td></tr>
    <tr><td><span class="cmd">/f rename &lt;nome&gt;</span></td><td>Cambia il nome della fazione (leader, 1 ogni {{cfg:rename.cooldown-days}} giorni)</td></tr>
    <tr><td><span class="cmd">/f info [fazione]</span></td><td>Info sulla fazione</td></tr>
    <tr><td><span class="cmd">/f list</span></td><td>Elenca tutte le fazioni</td></tr>
    <tr><td><span class="cmd">/f top</span></td><td>La classifica delle fazioni per punteggio</td></tr>
    <tr><td><span class="cmd">/f disband</span></td><td>Scioglie la fazione (leader)</td></tr>
  </table>
  <p style="text-align:center;margin-top:16px"><a class="top" href="#">↑ Torna su</a></p>
</section>

<footer>
  MagixFactions · Guida per giocatori di <b>MagicAdventure</b> · buon divertimento!
</footer>
</div>
</body>
</html>
"""

HTML = HTML.replace("__GIF__", GIF)
out = os.path.join(DOCS, "tutorial.html")
with open(out, "w", encoding="utf-8") as f:
    f.write(HTML)
print("Tutorial salvato:", out)
print("dimensione:", os.path.getsize(out), "byte")
