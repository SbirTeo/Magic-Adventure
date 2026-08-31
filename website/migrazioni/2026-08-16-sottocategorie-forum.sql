-- Sotto-categorie del forum: una categoria puo' stare dentro un'altra.
-- Un livello solo (una sotto-categoria non puo' avere figlie a sua volta): lo garantisce
-- il gestionale, che come "categoria superiore" propone solo quelle di primo livello.
-- NULL = categoria principale, come tutte quelle esistenti.
ALTER TABLE forum_categories
    ADD COLUMN IF NOT EXISTS parent_id INT NULL DEFAULT NULL COMMENT 'Categoria superiore; NULL = principale';

-- Indice per ritrovare in fretta le figlie di una categoria.
ALTER TABLE forum_categories
    ADD KEY IF NOT EXISTS idx_parent (parent_id);
