-- Cestino degli articoli: eliminare un articolo non lo cancella piu', gli mette una data
-- in deleted_at. Sparisce dal sito (home, pagina dell'articolo, mappa per Google) ma resta
-- intero e si ripesca dal gestionale, scheda Blog > "Articoli eliminati".
ALTER TABLE blog_posts
    ADD COLUMN deleted_at DATETIME NULL DEFAULT NULL,
    ADD KEY idx_cestino (deleted_at);
