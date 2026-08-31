-- Codice di verifica di Google Search Console (scheda "Aspetto" del gestionale).
-- Lo assegna Google e serve solo a dimostrargli che il sito e' nostro: finisce in un
-- <meta name="google-site-verification"> nella testa di ogni pagina.
INSERT INTO site_settings (setting_key, setting_value)
VALUES ('google_site_verification', 'K23wDPyRQ9TV1XtwLmZNpAKPqkkxrBwdbLsOsF_CEtg')
ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value);
