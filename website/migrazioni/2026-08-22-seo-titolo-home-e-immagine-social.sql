-- Due impostazioni nuove nella scheda "Aspetto" del gestionale (vedi manage.php):
--   meta_title_home = la riga cliccabile della home su Google. "Home — MAGICADVENTURE"
--                     non contiene nessuna delle parole che la gente cerca davvero.
--   og_image        = l'immagine dell'anteprima quando si incolla un link del sito su
--                     Discord, WhatsApp o Telegram. Vuota = il logo.
INSERT IGNORE INTO site_settings (setting_key, setting_value) VALUES
    ('meta_title_home', 'MAGICADVENTURE — Server Minecraft italiano con Fazioni'),
    ('og_image', '');
