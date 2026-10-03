-- Voce "Voice" nel menu del sito: la chat vocale (/voice), subito prima dello Store.
-- Se qualcuno l'ha già aggiunta a mano (o la migrazione gira due volte), non si duplica.
-- Senza colonna laterale, come le altre pagine (vedi 2026-10-03-sidebar-solo-home.sql).
--
-- Idempotente.
INSERT INTO nav_items (label, url, sort_order, enabled, show_sidebar)
SELECT 'Voice', '/voice',
       COALESCE((SELECT MAX(n.sort_order) FROM nav_items n WHERE n.url NOT LIKE '/store%'), 0) + 1,
       1, 0
  FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM nav_items WHERE TRIM(TRAILING '/' FROM url) IN ('/voice', '/voce'));
