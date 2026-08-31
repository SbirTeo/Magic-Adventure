-- Colonna laterale (chat + scheda giocatore + "sul sito ora") accesa o spenta pagina per
-- pagina, dal gestionale: Pagine e navigazione.
--   nav_items.show_sidebar  -> vale per la pagina della voce e per tutto quel che ci sta
--                              sotto (spuntando /forum l'hanno anche categorie e discussioni)
--   site_pages.show_sidebar -> le pagine create dal gestionale (/pagina/<slug>)
-- Home e Store non la guardano: quelle due si posizionano la colonna da sole, dentro la pagina.
USE magicadventure_web;

ALTER TABLE nav_items  ADD COLUMN IF NOT EXISTS show_sidebar TINYINT(1) NOT NULL DEFAULT 1;
ALTER TABLE site_pages ADD COLUMN IF NOT EXISTS show_sidebar TINYINT(1) NOT NULL DEFAULT 1;

-- Home e Store: spunta spenta, cosi' il gestionale non promette una cosa che li' non vale.
UPDATE nav_items SET show_sidebar = 0 WHERE url IN ('/', '/store', '/store.php', '/index.php');
