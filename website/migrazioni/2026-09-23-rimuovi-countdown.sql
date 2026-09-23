-- Rimozione definitiva della sezione "conto alla rovescia" (il portale del Nether in home).
--
-- Il codice che la mostrava e il modulo del gestionale che la regolava sono stati tolti:
-- queste righe in `site_settings` non le legge piu' nessuno. Si cancellano per non lasciare
-- residui morti nel database. Non toccano nient'altro.
DELETE FROM site_settings WHERE setting_key IN (
    'countdown_enabled',
    'countdown_target',
    'countdown_start',
    'countdown_color',
    'countdown_fuse_color',
    'countdown_text_color',
    'countdown_tag',
    'countdown_title',
    'countdown_text',
    'countdown_done_title',
    'countdown_done_text',
    'countdown_piglin_text',
    'countdown_button_text',
    'countdown_button_url'
);
