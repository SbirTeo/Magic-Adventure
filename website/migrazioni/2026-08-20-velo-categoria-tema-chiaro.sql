-- Velo e barretta di una categoria dello store: una versione per il tema CHIARO.
-- Un velo quasi nero al 95% e' perfetto sul fondo scuro del sito e diventa una macchia
-- sulla pagina bianca; queste colonne permettono di regolarlo a parte, come si fa gia' per
-- il velo generale dello store e per le tessere degli articoli.
--
-- NULL = "usa il valore del tema scuro", cioe' esattamente il comportamento di prima:
-- nessuna categoria cambia aspetto finche' non si entra a impostare la colonna chiara.
ALTER TABLE store_categories
    ADD COLUMN overlay_color_chiaro     VARCHAR(7)   NULL DEFAULT NULL COMMENT 'Velo sul tema chiaro; NULL = come il tema scuro',
    ADD COLUMN overlay_intensity_chiaro TINYINT      NULL DEFAULT NULL COMMENT 'Intensita 0-100 del velo chiaro',
    ADD COLUMN overlay_stop_chiaro      TINYINT      NULL DEFAULT NULL COMMENT 'Altezza 20-100 della sfumatura chiara',
    ADD COLUMN border_color_chiaro      VARCHAR(7)   NULL DEFAULT NULL COMMENT 'Barretta laterale sul tema chiaro; NULL = come il tema scuro';
