-- Toglie gli account finti creati da 2026-08-21-utenti-finti.sql.
-- Il filtro e' l'uuid: nessun account vero comincia per 'feed0000-'.
DELETE FROM mc_ranks WHERE mc_uuid LIKE 'feed0000-%';
DELETE FROM users WHERE mc_uuid LIKE 'feed0000-%';
