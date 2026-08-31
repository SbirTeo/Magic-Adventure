-- Voce di menu per la guida del server (/tutorial).
-- Va fra Regolamento (4) e Store: lo Store scala a 6 per restare l'ultimo.
UPDATE nav_items SET sort_order = 6 WHERE url = '/store';

-- Il SELECT con la tabella derivata evita di inserirla due volte se la migrazione
-- viene rilanciata (nav_items non ha un indice unico sull'indirizzo).
INSERT INTO nav_items (label, url, sort_order, enabled)
SELECT * FROM (SELECT 'Guida' AS label, '/tutorial' AS url, 5 AS sort_order, 1 AS enabled) AS nuova
WHERE NOT EXISTS (SELECT 1 FROM (SELECT url FROM nav_items) AS esistenti WHERE esistenti.url = '/tutorial');
