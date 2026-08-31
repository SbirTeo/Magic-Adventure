-- Colore scelto a mano per la categoria del forum (#rrggbb).
-- NULL = colore automatico, come prima: lo sceglie forum_tinta() dall'id della categoria
-- pescando dalle tinte del tema, cosi' le vecchie categorie non cambiano aspetto.
ALTER TABLE forum_categories
    ADD COLUMN color VARCHAR(7) NULL DEFAULT NULL COMMENT 'Tinta della categoria; NULL = automatica';
