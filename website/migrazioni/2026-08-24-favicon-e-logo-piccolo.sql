-- Due immagini nuove nella scheda "Aspetto" del gestionale:
--   favicon_url    = l'icona che si vede nella linguetta del browser e nei preferiti.
--   logo_small_url = il logo in versione ridotta (quadrata). Compare accanto alla voce
--                    Home nella barra in alto e fa da immagine di scorta nelle anteprime
--                    dei link condivisi, quando l'articolo non ha una copertina sua: il
--                    logo grande, largo e disteso, in quel riquadro veniva tagliato.
INSERT IGNORE INTO site_settings (setting_key, setting_value) VALUES
    ('favicon_url', ''),
    ('logo_small_url', '');
