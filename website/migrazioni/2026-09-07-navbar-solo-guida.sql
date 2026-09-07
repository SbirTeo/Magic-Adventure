-- Guida e Regolamento condividono una sola voce di menu: "Guida" (/tutorial).
-- Dentro la pagina un interruttore Guida/Regolamento passa dall'una all'altra.
-- Qui si toglie la voce "Regolamento" dalla barra (la pagina /regolamento resta,
-- ci si arriva dall'interruttore).
DELETE FROM nav_items WHERE url IN ('/regolamento', '/regolamento.php');
