-- Le due inquadrature vanno tenute SEPARATE: la fascia del telefono e la colonna del
-- computer ritagliano l'immagine in modo diverso, quindi il punto buono per una non e'
-- quasi mai quello buono per l'altra.
--   cover_position    = telefono (fascia larga e bassa)
--   cover_position_pc = computer (colonna stretta e alta)
ALTER TABLE blog_posts
    ADD COLUMN cover_position_pc VARCHAR(20) NOT NULL DEFAULT '50% 50%' AFTER cover_position;
-- Chi aveva gia' scelto un punto se lo ritrova su tutte e due, come prima.
UPDATE blog_posts SET cover_position_pc = cover_position;
