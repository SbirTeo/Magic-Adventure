# overrides-vps/MagixMenus

Cartella di staging per `deploy-plugin-override.yml` con `dest_subdir: menus` — vedi
CLAUDE.md, "Copiare un file BINARIO nuovo sul VPS". Il contenuto (a parte questo file)
viene copiato dentro `plugins/MagixMenus/menus/` sul VPS.

Questo file resta vuoto apposta: serve solo a tenere la cartella non vuota (il workflow
si rifiuta di partire su una cartella vuota), cosi' si puo' usare `remove_path` per
togliere un singolo file dal VPS senza doverne aggiungere uno vero. MenuManager ignora
i file che non finiscono in `.yml`, quindi non ha nessun effetto sui menu.
