-- Zoom della copertina dei pacchetti dello store, accanto all'inquadratura
-- (image_position / image_position_pc, vedi 2026-09-14-store-inquadratura.sql).
--
-- In percentuale: 100 = l'immagine riempie la card e basta (com'era prima), 200 = due volte
-- piu' grande. Lo zoom e' centrato sul punto dell'inquadratura, quindi quel punto resta fermo.
-- Uno per il telefono e uno per il computer, come l'inquadratura.
-- IF NOT EXISTS (MariaDB): la migrazione si puo' rilanciare senza errori se e' gia' passata.
ALTER TABLE store_packages
    ADD COLUMN IF NOT EXISTS image_zoom    SMALLINT UNSIGNED NOT NULL DEFAULT 100 AFTER image_position_pc,  -- telefono
    ADD COLUMN IF NOT EXISTS image_zoom_pc SMALLINT UNSIGNED NOT NULL DEFAULT 100 AFTER image_zoom;         -- computer
