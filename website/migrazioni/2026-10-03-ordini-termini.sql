-- Ordini dello store: quando il giocatore ha accettato i termini di vendita e chiesto la
-- consegna immediata (casella obbligatoria nella pagina del pacchetto). E' la prova che ha
-- rinunciato al diritto di recesso, come chiede l'art. 59, lett. o, del Codice del Consumo.
-- NULL sugli ordini fatti prima di questa colonna.
--
-- Idempotente (IF NOT EXISTS di MariaDB).
ALTER TABLE store_orders
    ADD COLUMN IF NOT EXISTS terms_accepted_at DATETIME NULL DEFAULT NULL AFTER currency;
