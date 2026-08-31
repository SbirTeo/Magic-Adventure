-- Sottotitolo degli articoli: la riga che sta SOTTO il titolo, non dopo i due punti.
-- Vuoto = l'articolo non ne ha, e sotto al titolo non compare niente.
ALTER TABLE blog_posts ADD COLUMN subtitle VARCHAR(255) NOT NULL DEFAULT '' AFTER title;
