-- Il selettore di lingua nell'header (includes/header.php) mostra il nome NATIVO di ogni
-- lingua ("Italiano", "English", "Español", "Deutsch"): quelle parole non vanno mai tradotte,
-- ma prima di questa migrazione non avevano l'attributo data-no-tr, quindi il traduttore
-- automatico del sito (includes/translate.php) le prendeva per testo italiano normale e le
-- rimpiazzava con la traduzione in cache — sbagliata per queste parole isolate (es. "Italiano"
-- diventato "Deutsch" nel menu in tedesco, "Deutsch" diventato "Libiare").
--
-- header.php ora le marca con data-no-tr (non piu' toccate), ma le righe gia' in cache
-- restano nel database: qualunque altra pagina che in futuro contenga per caso lo stesso
-- testo esatto (es. "Italiano") le eredita cosi' come sono, senza ripassare dalla traduzione
-- automatica. Le tolgo cosi' una nuova occorrenza riparte da zero (stato "pending") invece di
-- ripescare la traduzione sbagliata.
DELETE FROM site_translations
WHERE source_text IN ('Italiano', 'English', 'Español', 'Deutsch');
