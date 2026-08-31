-- ============================================================================
--  Seguito della parte 2, dopo un intoppo.
--
--  Cos'e' successo: i jar nuovi di MagixGuard erano gia' al loro posto quando il
--  server e' ripartito da solo (start.sh lo rimette su appena esce), e il suo
--  `CREATE TABLE IF NOT EXISTS` ha creato `punishment_violations` da zero — con
--  le colonne inglesi giuste, ma VUOTA. Cosi' la RENAME della tabella vecchia si
--  e' trovata il posto occupato e la migrazione si e' fermata li'.
--
--  Erano gia' passate: ospiti_online -> guests_online e
--  regolamento_sanzioni -> punishment_rules. Questo file fa tutto il resto.
--
--  La tabella vecchia delle violazioni NON si cancella: si mette da parte con un
--  nome che dice cos'e'. Ha zero righe (contate prima), quindi non si perde
--  niente, ma cancellare e' una cosa che decide chi possiede i dati, non io.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- Tabelle
-- ---------------------------------------------------------------------------
RENAME TABLE sanzioni_violazioni TO sanzioni_violazioni_vuota_2026_08_31;
RENAME TABLE sanzioni_controlli  TO punishment_checks;
RENAME TABLE sanzioni_ricorsi    TO punishment_appeals;
RENAME TABLE sanzioni_coda       TO punishment_queue;
RENAME TABLE sanzioni            TO punishments;

-- ---------------------------------------------------------------------------
-- Colonne del sito
-- ---------------------------------------------------------------------------
ALTER TABLE guests_online RENAME COLUMN chiave TO guest_key;

ALTER TABLE guide_staff RENAME COLUMN titolo        TO title;
ALTER TABLE guide_staff RENAME COLUMN versione      TO version;
ALTER TABLE guide_staff RENAME COLUMN ordine        TO sort_order;
ALTER TABLE guide_staff RENAME COLUMN corpo_html    TO body_html;
ALTER TABLE guide_staff RENAME COLUMN aggiornata_il TO updated_at;

ALTER TABLE store_categories RENAME COLUMN overlay_color_chiaro     TO overlay_color_light;
ALTER TABLE store_categories RENAME COLUMN overlay_intensity_chiaro TO overlay_intensity_light;
ALTER TABLE store_categories RENAME COLUMN overlay_stop_chiaro      TO overlay_stop_light;
ALTER TABLE store_categories RENAME COLUMN border_color_chiaro      TO border_color_light;
ALTER TABLE store_categories RENAME COLUMN text_color_chiaro        TO text_color_light;
ALTER TABLE store_categories RENAME COLUMN price_color_chiaro       TO price_color_light;
ALTER TABLE store_categories RENAME COLUMN sconto_color             TO discount_color;

-- ---------------------------------------------------------------------------
-- Colonne delle sanzioni
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

-- punishment_violations non compare qui: l'ha gia' creata il plugin con i nomi
-- inglesi giusti. La vecchia, vuota, e' parcheggiata li' sopra.
