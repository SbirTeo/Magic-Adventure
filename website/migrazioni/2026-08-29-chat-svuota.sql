-- Tasto "svuota la chat" nella chat live della home.
--
-- Cancellare le righe di `web_chat` non basta: chi ha la home gia' aperta continuerebbe a
-- vedere sullo schermo messaggi che nel database non ci sono piu', fino al ricaricamento.
-- Qui si tiene l'id piu' alto cancellato nell'ultimo svuotamento: la chat lo legge a ogni
-- giro e, quando lo vede salire, ripulisce l'elenco da sola.
--
-- Il permesso che abilita il tasto (chat.clear) NON sta nel database: e' nel catalogo in
-- includes/permissions.php e si assegna ai gruppi dal gestionale, come tutti gli altri.
INSERT IGNORE INTO site_settings (setting_key, setting_value) VALUES
    ('chat_purge_id', '0');
