-- Toglie dalla cache delle traduzioni del sito le "frasi" che erano solo misure: cifre con al
-- piu' tre lettere di unita' ("0m", "2g", "1h 5m", "100 EUR"). MyMemory le rovinava ("0m" era
-- diventato "%0M" nelle classifiche in inglese). Da translate.php (translatable_text) non
-- vengono piu' accodate: tolte da qui, le pagine le mostrano come sono. Rilanciarla non fa niente.
DELETE FROM site_translations
WHERE source_text REGEXP '[0-9]'
  AND CHAR_LENGTH(REGEXP_REPLACE(source_text, '[^[:alpha:]]', '')) <= 3;
