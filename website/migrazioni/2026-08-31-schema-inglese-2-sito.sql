-- ============================================================================
--  Nomi di TABELLE e COLONNE in inglese — parte 2: il sito e le sanzioni.
--  (La parte 1, l'autenticazione, sta nel file -1-auth.sql.)
--
--  NON ANCORA APPLICATA. Tocca `sanzioni` e le sue quattro tabelle sorelle, che
--  sono lette da MagixGuard e da una decina di pagine PHP: va fatta con lo stesso
--  metodo dell'altra — codice e database nello stesso momento, server fermo.
--  Backup: ~/backup-db-prima-rinomina-2026-08-31.sql.gz
-- ============================================================================

RENAME TABLE ospiti_online        TO guests_online;
RENAME TABLE regolamento_sanzioni TO punishment_rules;
RENAME TABLE sanzioni_violazioni  TO punishment_violations;
RENAME TABLE sanzioni_controlli   TO punishment_checks;
RENAME TABLE sanzioni_ricorsi     TO punishment_appeals;
RENAME TABLE sanzioni_coda        TO punishment_queue;
RENAME TABLE sanzioni             TO punishments;
-- ---------------------------------------------------------------------------
-- 3. Le colonne del sito
-- ---------------------------------------------------------------------------
ALTER TABLE guests_online RENAME COLUMN chiave TO guest_key;

ALTER TABLE guide_staff RENAME COLUMN titolo        TO title;
ALTER TABLE guide_staff RENAME COLUMN versione      TO version;
ALTER TABLE guide_staff RENAME COLUMN ordine        TO sort_order;
ALTER TABLE guide_staff RENAME COLUMN corpo_html    TO body_html;
ALTER TABLE guide_staff RENAME COLUMN aggiornata_il TO updated_at;

-- il "_chiaro" delle categorie dello store e' il tema chiaro del sito
ALTER TABLE store_categories RENAME COLUMN overlay_color_chiaro     TO overlay_color_light;
ALTER TABLE store_categories RENAME COLUMN overlay_intensity_chiaro TO overlay_intensity_light;
ALTER TABLE store_categories RENAME COLUMN overlay_stop_chiaro      TO overlay_stop_light;
ALTER TABLE store_categories RENAME COLUMN border_color_chiaro      TO border_color_light;
ALTER TABLE store_categories RENAME COLUMN text_color_chiaro        TO text_color_light;
ALTER TABLE store_categories RENAME COLUMN price_color_chiaro       TO price_color_light;
ALTER TABLE store_categories RENAME COLUMN sconto_color             TO discount_color;

-- ---------------------------------------------------------------------------
-- 4. Le colonne delle sanzioni
-- ---------------------------------------------------------------------------
ALTER TABLE punishment_rules RENAME COLUMN corpo_html    TO body_html;
ALTER TABLE punishment_rules RENAME COLUMN versione      TO version;
ALTER TABLE punishment_rules RENAME COLUMN aggiornato_il TO updated_at;

ALTER TABLE punishments RENAME COLUMN tipo              TO type;
ALTER TABLE punishments RENAME COLUMN categoria         TO category;
ALTER TABLE punishments RENAME COLUMN motivo            TO reason;
ALTER TABLE punishments RENAME COLUMN ambito            TO scope;
ALTER TABLE punishments RENAME COLUMN punti             TO points;
ALTER TABLE punishments RENAME COLUMN inizio            TO starts_at;
ALTER TABLE punishments RENAME COLUMN fine              TO ends_at;
ALTER TABLE punishments RENAME COLUMN staff_nome        TO staff_name;
ALTER TABLE punishments RENAME COLUMN automatica        TO automatic;
ALTER TABLE punishments RENAME COLUMN stato             TO status;
ALTER TABLE punishments RENAME COLUMN revocata_da       TO revoked_by;
ALTER TABLE punishments RENAME COLUMN revocata_il       TO revoked_at;
ALTER TABLE punishments RENAME COLUMN revoca_motivo     TO revoke_reason;
ALTER TABLE punishments RENAME COLUMN revoca_applicata  TO revoke_applied;
ALTER TABLE punishments RENAME COLUMN rapporto_hash     TO report_hash;
ALTER TABLE punishments RENAME COLUMN rapporto_pubblico TO report_public;
ALTER TABLE punishments RENAME COLUMN creata_il         TO created_at;

ALTER TABLE punishment_queue RENAME COLUMN tipo           TO type;
ALTER TABLE punishment_queue RENAME COLUMN categoria      TO category;
ALTER TABLE punishment_queue RENAME COLUMN motivo         TO reason;
ALTER TABLE punishment_queue RENAME COLUMN ambito         TO scope;
ALTER TABLE punishment_queue RENAME COLUMN durata_secondi TO duration_seconds;
ALTER TABLE punishment_queue RENAME COLUMN punti          TO points;
ALTER TABLE punishment_queue RENAME COLUMN fonte          TO source;
ALTER TABLE punishment_queue RENAME COLUMN proposta_da    TO proposed_by;
ALTER TABLE punishment_queue RENAME COLUMN dettaglio      TO detail;
ALTER TABLE punishment_queue RENAME COLUMN rapporto_hash  TO report_hash;
ALTER TABLE punishment_queue RENAME COLUMN stato          TO status;
ALTER TABLE punishment_queue RENAME COLUMN decisa_da      TO decided_by;
ALTER TABLE punishment_queue RENAME COLUMN decisa_il      TO decided_at;
ALTER TABLE punishment_queue RENAME COLUMN sanzione_id    TO punishment_id;
ALTER TABLE punishment_queue RENAME COLUMN creata_il      TO created_at;

ALTER TABLE punishment_checks RENAME COLUMN staff_nome     TO staff_name;
ALTER TABLE punishment_checks RENAME COLUMN nota           TO note;
ALTER TABLE punishment_checks RENAME COLUMN esito          TO outcome;
ALTER TABLE punishment_checks RENAME COLUMN controllato_il TO checked_at;

ALTER TABLE punishment_appeals RENAME COLUMN sanzione_id    TO punishment_id;
ALTER TABLE punishment_appeals RENAME COLUMN testo          TO text;
ALTER TABLE punishment_appeals RENAME COLUMN stato          TO status;
ALTER TABLE punishment_appeals RENAME COLUMN risposta       TO reply;
ALTER TABLE punishment_appeals RENAME COLUMN esito_pubblico TO outcome_public;
ALTER TABLE punishment_appeals RENAME COLUMN staff_nome     TO staff_name;
ALTER TABLE punishment_appeals RENAME COLUMN aperto_il      TO opened_at;
ALTER TABLE punishment_appeals RENAME COLUMN deciso_il      TO decided_at;

ALTER TABLE punishment_violations RENAME COLUMN categoria   TO category;
ALTER TABLE punishment_violations RENAME COLUMN punti       TO points;
ALTER TABLE punishment_violations RENAME COLUMN fonte       TO source;
ALTER TABLE punishment_violations RENAME COLUMN dettaglio   TO detail;
ALTER TABLE punishment_violations RENAME COLUMN annullata   TO cancelled;
ALTER TABLE punishment_violations RENAME COLUMN sanzione_id TO punishment_id;
ALTER TABLE punishment_violations RENAME COLUMN creata_il   TO created_at;
