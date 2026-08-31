-- Il velo sulle copertine si spegne separatamente per il blog e per lo store.
--
-- Prima era un interruttore solo ('card_overlay_enabled'): chi voleva le copertine pulite
-- negli articoli se le ritrovava pulite anche nei pacchetti, e viceversa. I due posti hanno
-- esigenze diverse — nello store il velo tiene leggibile il prezzo — quindi ora sono due.
--
-- Il valore di partenza e' quello dell'interruttore unico, cosi' il sito resta com'e' adesso:
-- se il velo era spento, restano spenti tutti e due. Il valore si legge PRIMA di inserire,
-- perche' leggere e scrivere la stessa tabella nella stessa istruzione non e' permesso.
SELECT COALESCE(MAX(setting_value), '1') INTO @velo
  FROM site_settings WHERE setting_key = 'card_overlay_enabled';

INSERT IGNORE INTO site_settings (setting_key, setting_value) VALUES
    ('card_overlay_blog',  @velo),
    ('card_overlay_store', @velo);

DELETE FROM site_settings WHERE setting_key = 'card_overlay_enabled';
