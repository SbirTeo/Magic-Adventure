# Correzioni a mano di MagixLanguage

Le traduzioni scritte a mano (i file `*-overrides.yml`, che
vincono sempre su quelle automatiche) stanno in `overrides-vps/translations/<Plugin>/`, con la stessa
struttura della cartella `translations/` sul server: oggi le righe della scoreboard
(`MagixScoreboard/menu-phrases-<lingua>-overrides.yml`, chiave = frase italiana del config vero) e
`/f info`, `/f top`, canali della chat e allerta di conquista (`MagixFactions/<lingua>-overrides.yml`).
Si cambiano lì e si portano sul VPS con `deploy-plugin-override.yml` (`plugin: MagixLanguage`,
`src_subdir: translations`, `dest_subdir: translations`, `reload_cmd: language sync`). Una correzione
per chiave vince anche quando il testo italiano cambia: se si cambia una di quelle chiavi in
`messages.yml`, va aggiornata anche qui. Una correzione per frase, invece, smette da sola di valere
quando la frase italiana cambia.
