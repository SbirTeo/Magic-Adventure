-- Inquadratura della copertina dei pacchetti dello store, come per gli articoli
-- (blog_posts.cover_position / cover_position_pc).
--
-- Sulle card dello store l'immagine viene ritagliata (background-position). Con questi due
-- valori l'admin sceglie QUALE parte tenere in vista, separatamente per telefono e computer.
-- Formato: "<x>% <y>%", come background-position. 50% 50% = centro (com'era prima).
-- IF NOT EXISTS (MariaDB): la migrazione si puo' rilanciare senza errori se e' gia' passata.
ALTER TABLE store_packages
    ADD COLUMN IF NOT EXISTS image_position    VARCHAR(20) NOT NULL DEFAULT '50% 50%' AFTER image_url,       -- telefono
    ADD COLUMN IF NOT EXISTS image_position_pc VARCHAR(20) NOT NULL DEFAULT '50% 50%' AFTER image_position;  -- computer
