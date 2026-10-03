-- Seconda copertina dei pacchetti dello store, per il tema CHIARO del sito.
--
-- image_url resta la copertina di sempre (tema scuro, quello di casa); image_url_light e' quella
-- che si vede col tema chiaro. Se una delle due manca vale l'altra in tutti e due i temi, quindi i
-- pacchetti che hanno solo image_url si vedono esattamente come prima.
-- L'inquadratura (image_position / image_position_pc) e' una sola e vale per tutte e due.
-- IF NOT EXISTS (MariaDB): la migrazione si puo' rilanciare senza errori se e' gia' passata.
ALTER TABLE store_packages
    ADD COLUMN IF NOT EXISTS image_url_light VARCHAR(500) NULL AFTER image_url;
