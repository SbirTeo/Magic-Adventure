-- Una categoria dello store puo' ora ridefinire anche quello che finora si poteva scegliere
-- solo per tutto lo store: colore del testo sopra il velo e colore del prezzo (uno per tema),
-- piu' la tinta della targhetta dello sconto.
--
-- NULL = "eredita": si usa la scelta generale dello store e, se non c'e' nemmeno quella, il
-- colore automatico calcolato dal velo. Nessuna categoria cambia aspetto finche' non si
-- riempiono queste colonne.
ALTER TABLE store_categories
    ADD COLUMN text_color         VARCHAR(7) NULL DEFAULT NULL COMMENT 'Testo sopra il velo, tema scuro; NULL = eredita',
    ADD COLUMN text_color_chiaro  VARCHAR(7) NULL DEFAULT NULL COMMENT 'Testo sopra il velo, tema chiaro; NULL = eredita',
    ADD COLUMN price_color        VARCHAR(7) NULL DEFAULT NULL COMMENT 'Prezzo, tema scuro; NULL = eredita',
    ADD COLUMN price_color_chiaro VARCHAR(7) NULL DEFAULT NULL COMMENT 'Prezzo, tema chiaro; NULL = eredita',
    ADD COLUMN sconto_color       VARCHAR(7) NULL DEFAULT NULL COMMENT 'Targhetta dello sconto; NULL = eredita (testo calcolato per contrasto)';
