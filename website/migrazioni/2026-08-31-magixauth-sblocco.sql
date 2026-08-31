-- ============================================================================
--  MagixAuth: poter sbloccare un giocatore chiuso fuori per troppi tentativi.
--
--  Il blocco sta sull'INDIRIZZO di rete (vedi il commento di auth_tentativi), e
--  giustamente: contarlo sul nome vorrebbe dire che chiunque, sbagliando apposta
--  la password di un altro, puo' chiuderlo fuori sapendone solo il nick.
--
--  Il rovescio della medaglia e' che chi amministra vede arrivare un giocatore,
--  non un indirizzo: "sbloccami Tizio" non si puo' eseguire se della riga bloccata
--  si conosce soltanto l'IP. E il giocatore, appena bloccato, non e' nemmeno
--  online: viene respinto al cancello, quindi il suo indirizzo non si puo'
--  chiedere al server.
--
--  Qui si annota quindi l'ULTIMO nome provato da quell'indirizzo. Serve solo a
--  ritrovare la riga da sbloccare: il blocco continua a valere per indirizzo, e
--  il nome annotato non concede e non toglie niente a nessuno.
-- ============================================================================

ALTER TABLE auth_tentativi
    ADD COLUMN last_username VARCHAR(32) NULL
        COMMENT 'Ultimo nick provato da questo indirizzo: serve a /mauth unlock per ritrovare la riga',
    ADD KEY idx_last_username (last_username);
