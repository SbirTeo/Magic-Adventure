-- Interruttore generale dei VELI sulle copertine (le sfumature scure che rendono
-- leggibile il testo sopra le immagini): vale per gli articoli in home e per le card
-- dello store insieme. Acceso di serie, com'e' sempre stato.
INSERT IGNORE INTO site_settings (setting_key, setting_value) VALUES ('card_overlay_enabled', '1');
