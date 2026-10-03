-- Colonna laterale (chat, accesso, "sul sito ora") solo in home, su richiesta.
--
-- Sulle altre pagine ripeteva le stesse tre schede ovunque (classifiche, forum, guida,
-- sanzioni...) e da telefono le metteva in fondo a ogni pagina, allungandola di ~900px.
-- Resta un'impostazione: si riaccende voce per voce dal gestionale (Pagine e navigazione).
-- La colonna dello Store (ultimi acquisti, miglior sostenitore) e' un'altra cosa e non cambia.
--
-- Idempotente.
UPDATE nav_items SET show_sidebar = 0 WHERE TRIM(TRAILING '/' FROM url) <> '';
UPDATE site_pages SET show_sidebar = 0;
