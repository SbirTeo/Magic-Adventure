-- Voce del menu dello store: in italiano "Negozio" invece di "Store". Cambia solo
-- l'etichetta (quella che si modifica anche dal gestionale, Pagine e navigazione): l'indirizzo
-- (/store) e il titolo della pagina restano quelli. Le altre lingue la traducono dal
-- traduttore del sito. Rilanciarla non fa niente (tocca solo la voce che si chiama ancora Store).
UPDATE nav_items SET label = 'Negozio' WHERE url IN ('/store', '/store.php') AND label = 'Store';
