-- Regolamento riscritto (2026-10-02): stesso tono della guida (col tu), capitoli con <h2> che la pagina
-- numera e mette in indice (rulebook_chapters in includes/sanzioni.php), nessun doppione sui punti e
-- ricorsi SOLO dal modulo nella pagina del provvedimento (prima il testo mandava sul forum).
--
-- Il testo di prima NON si perde: va in site_pages_backup. Si applica una volta sola: il segno
-- <!--regolamento-v2--> in testa al nuovo testo impedisce di sovrascrivere le modifiche successive
-- fatte dallo staff nel gestionale rilanciando la migrazione.

CREATE TABLE IF NOT EXISTS site_pages_backup (
    id INT AUTO_INCREMENT PRIMARY KEY,
    slug VARCHAR(50) NOT NULL,
    title VARCHAR(200) NOT NULL DEFAULT '',
    body MEDIUMTEXT NOT NULL,
    saved_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO site_pages_backup (slug, title, body)
SELECT slug, title, body FROM site_pages
WHERE slug = 'regolamento' AND body NOT LIKE '%<!--regolamento-v2-->%';

INSERT IGNORE INTO site_pages (slug, title, body) VALUES ('regolamento', 'Regolamento del server', '');

UPDATE site_pages SET body = '<!--regolamento-v2-->
<p>Queste sono le regole di <b>MAGICADVENTURE</b>. Valgono in gioco, sul sito e sul forum. Se le rispetti
non succede niente; se le infrangi, ogni violazione vale dei <b>punti</b> e i punti portano a un
provvedimento: trovi tutto nel capitolo <b>Sanzioni</b>.</p>

<h2>Rispetto</h2>
<ul>
<li>Niente insulti, discriminazioni, molestie o linguaggio d''odio verso nessuno.</li>
<li>Niente spam, flood o pubblicità di altri server, né in chat né sul forum.</li>
<li>Niente dati personali: non chiedere e non diffondere numeri di telefono, indirizzi, contatti o foto di altri giocatori.</li>
<li>Rispetta lo staff e le sue decisioni. Se pensi che un provvedimento sia sbagliato non discuterne in chat: fai ricorso (vedi <b>Sanzioni</b>).</li>
</ul>

<h2>Gioco leale</h2>
<ul>
<li>Vietati cheat e client modificati: volo, velocità, mira automatica, x-ray e simili.</li>
<li>Vietato duplicare oggetti e sfruttare i bug.</li>
<li>Vietato restare «attivi» da fermi con macro, autoclicker o altri trucchi contro l''anti-AFK.</li>
<li>Vietato il griefing fuori dalle regole delle fazioni.</li>
</ul>
<div class="nota">Hai trovato un bug? <b>Segnalalo allo staff</b> invece di sfruttarlo: chi segnala viene premiato.</div>

<h2>Fazioni e territori</h2>
<ul>
<li>I territori conquistati con <code>/f claim</code> sono protetti: non entrarci e non romperli con mezzi che il gioco non prevede.</li>
<li>Raid e conquiste fra fazioni nemiche fanno parte del gioco e seguono le regole del plugin delle fazioni.</li>
<li>Un nome di fazione offensivo viene cambiato dallo staff.</li>
</ul>
<div class="nota">Come funzionano Potenza, territori e conquiste lo trovi nella scheda <b>Guida</b>.</div>

<h2>Account e sicurezza</h2>
<ul>
<li>Il tuo account è solo tuo: non condividerlo con nessuno.</li>
<li>Il codice per collegare l''account al sito (<code>/link</code>) è personale: non darlo a nessuno, nemmeno a chi dice di essere dello staff.</li>
<li>Non fingere di essere un membro dello staff o un altro giocatore.</li>
</ul>

<h2>Sanzioni</h2>
[[SANZIONI]]

<p>Il regolamento può cambiare nel tempo: la versione valida è sempre quella su questa pagina.
Buon divertimento su <b>MAGICADVENTURE</b>!</p>'
WHERE slug = 'regolamento' AND body NOT LIKE '%<!--regolamento-v2-->%';
