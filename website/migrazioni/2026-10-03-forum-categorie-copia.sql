-- Categorie del forum nate con "Duplica": l'indirizzo era rimasto quello della copia
-- (/forum/cerca-fazione-copia apriva "Cerca membri", /forum/cerca-membri-copia "Cerca alleanze"),
-- e due sezioni avevano la stessa descrizione copiata. Da ora il gestionale rifa' l'indirizzo
-- da solo al primo salvataggio (manage.php, forum_cat_save); qui si sistemano quelle che ci sono.
--
-- Ogni riga tocca solo la categoria se e' ancora com'era (stesso indirizzo E stesso nome, o
-- descrizione ancora quella copiata): una modifica fatta nel frattempo dal gestionale resta.
-- Idempotente.
UPDATE forum_categories
   SET slug = 'cerca-membri'
 WHERE slug = 'cerca-fazione-copia' AND name = 'Cerca membri'
   AND NOT EXISTS (SELECT 1 FROM (SELECT slug FROM forum_categories) AS t WHERE t.slug = 'cerca-membri');

UPDATE forum_categories
   SET slug = 'cerca-alleanze'
 WHERE slug = 'cerca-membri-copia' AND name = 'Cerca alleanze'
   AND NOT EXISTS (SELECT 1 FROM (SELECT slug FROM forum_categories) AS t WHERE t.slug = 'cerca-alleanze');

UPDATE forum_categories
   SET description = 'Sei senza fazione? Presentati: come giochi, quando ci sei, cosa sai fare. Le fazioni che cercano gente passano di qui.'
 WHERE slug = 'cerca-fazione'
   AND description = 'Presentati nel modo migliore e trova la tua squadra di questa magica avventura.';

UPDATE forum_categories
   SET description = 'La tua fazione cerca membri? Racconta chi siete, dove giocate e chi state cercando.'
 WHERE slug = 'cerca-membri'
   AND description = 'Presentati nel modo migliore e trova la tua squadra di questa magica avventura.';
