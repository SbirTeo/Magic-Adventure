-- Punto dell'immagine da tenere al centro dell'inquadratura, quando la copertina viene
-- ritagliata nelle tessere della home (sul telefono la fascia e' larga e bassa, quindi di
-- una locandina in piedi se ne vede solo una striscia: con questo si sceglie QUALE).
-- Formato: "<x>% <y>%", come background-position. 50% 50% = centro, com'era prima.
ALTER TABLE blog_posts ADD COLUMN cover_position VARCHAR(20) NOT NULL DEFAULT '50% 50%' AFTER cover_image;
