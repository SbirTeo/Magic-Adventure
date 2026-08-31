-- Primo accesso al server di ogni giocatore, mostrato nel profilo del sito.
-- La riempie il plugin MagixWeb (RankSync): al join la scrive se manca, e all'avvio
-- recupera quella di chi ha gia' giocato leggendo il "primo accesso" di Bukkit.
-- Finche' il server non riparte col jar nuovo resta NULL e il profilo mostra un trattino.
ALTER TABLE mc_ranks
    ADD COLUMN IF NOT EXISTS first_join DATETIME NULL DEFAULT NULL COMMENT 'Primo accesso al server';
