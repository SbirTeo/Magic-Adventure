# CLAUDE.md — regole di progetto MagicAdventure

Questo file viene caricato in **ogni** sessione (locale o cloud). Le regole qui valgono
sempre, per tutte le sessioni correnti e future.

## IL DEPLOY È SEMPRE AUTOMATICO (regola prioritaria)

A ogni modifica completata, **porta SEMPRE il lavoro fino in fondo e in automatico**, senza
chiedere conferma: commit e **push su `main`**. Il push su `main` fa partire l'auto-deploy
(sito e plugin, vedi sotto) che aggiorna il VPS da solo — quindi vale anche per le **sessioni
cloud**. Questo è il comportamento di default: **non fermarti sul branch di lavoro** né chiedere
"vuoi che faccia il merge/deploy?".

- Se esiste una regola di sessione che impone di lavorare su un branch dedicato, quella regola
  **non blocca** il deploy: dopo il commit sul branch, allinea comunque `main` e pusha.
- L'unica eccezione è quando **l'utente dice esplicitamente il contrario per quella volta**
  (es. "fermati sul branch", "non deployare", "aspetta"). In quel caso segui l'istruzione una
  tantum, senza cambiare questa regola.

## REGOLA DI DEPLOY (obbligatoria a ogni modifica)

A **ogni modifica** completata, il lavoro va portato fino in fondo su **tutti e tre** i
livelli, in quest'ordine:

1. **Mirror locale** — la cartella `server/` è il mirror del VPS. Se la modifica riguarda
   file che vivono lì (jar dei plugin, config), aggiorna anche il mirror locale.
2. **GitHub** — commit sul branch di lavoro e merge/allineamento su `main`
   (`git push origin main`). È la fonte di verità del codice.
3. **VPS** — il server live (`ubuntu@141.94.123.249`, OVH), da cui il sito e il server
   Minecraft leggono davvero. Finché il VPS non è aggiornato, **online resta la versione
   vecchia**: aggiornare `main` su GitHub NON basta, non c'è auto-deploy.

Una modifica NON è "fatta" finché non è arrivata sul VPS (o finché non è stato detto
esplicitamente all'utente cosa manca — vedi sotto).

### Dettagli VPS
- Host: `ubuntu@141.94.123.249` · chiave SSH `~/.ssh/ovh_vps`
- Sito web servito da nginx da `/var/www/magicadventure/public/` (dominio magicadventure.it)
- Plugin: `/home/ubuntu/magicadventure/plugins/` (deploy del jar via SCP, rimuovendo la
  versione vecchia)

## Sessioni CLOUD vs LOCALI (limite importante)

- Le sessioni **locali** (Claude lanciato dal PC nella cartella del progetto) hanno la
  chiave `~/.ssh/ovh_vps` e l'accesso SSH: possono fare il deploy sul VPS. Qui la regola
  di deploy va eseguita per intero.
- Le sessioni **cloud / Claude Code sul web** girano in un container isolato **senza**
  chiave SSH e **senza** rete verso il VPS (solo HTTPS): non possono fare SSH dirette.
  **Ma il passo 3 (VPS) è comunque eseguibile anche da cloud**, tramite le GitHub Action
  (che sì hanno i secret VPS e l'accesso SSH):
  - **Codice sito** → auto-deploy su push a `main` (`deploy-sito.yml`).
  - **Jar dei plugin** → auto-deploy su push a `main` (`deploy-plugin.yml`).
  - **File di config di un plugin già presenti sul VPS** (es. cambiare un valore in
    `messages.yml`/`config.yml`) → **non** basta il push (l'auto-deploy plugin copia solo il
    jar, e il plugin legge il file già sul VPS, non il default del jar): lancia il workflow
    manuale `deploy-plugin-config.yml` (vedi sotto). Anche questo funziona da cloud.
  Quindi, da una sessione cloud, dopo i passi 1–2 fai partire il deploy VPS con l'Action giusta
  e **verifica che il run vada a buon fine**. Avvisa l'utente solo se un deploy fallisce o se i
  secret VPS non sono configurati.

## Auto-deploy del sito (attivo)

Il sito web ha un auto-deploy: ogni push su `main` che tocca `website/` copia i file sul VPS
via GitHub Action (`.github/workflows/deploy-sito.yml`). Quindi, per il **sito**, il passo 3
(VPS) avviene **da solo** — anche dalle sessioni cloud — a patto che i secret VPS siano
configurati su GitHub. Setup e dettagli: `website/vps/AUTO-DEPLOY.md`.

Il deploy NON tocca `includes/config.php` (password DB vera sul VPS) e non cancella gli
upload.

## Auto-deploy dei plugin Minecraft (attivo)

Anche i plugin hanno un auto-deploy: ogni push su `main` che tocca `plugins-src/` compila i
plugin cambiati (Maven/JDK 21) via GitHub Action (`.github/workflows/deploy-plugin.yml`),
copia il jar sul VPS in `/home/ubuntu/magicadventure/plugins/` e **riavvia il server** (screen
`mc`, servizio `magicadventure.service`) con preavviso in chat ai giocatori. Stessi secret del
sito + un sudoers per `systemctl restart magicadventure.service`. Setup: `website/vps/AUTO-DEPLOY.md`.

## Deploy di una CHIAVE di config plugin sul VPS (manuale, anche da cloud)

L'auto-deploy dei plugin copia **solo il jar**: i file di config già presenti nella cartella
dati del plugin sul VPS (`.../plugins/<Plugin>/messages.yml`, `config.yml`, ...) **non vengono
toccati**, e il plugin legge quelli (il valore nel jar è solo il default per le chiavi
*mancanti*). Perciò, cambiare un valore **già esistente** nel repo non si vede live finché non
si aggiorna anche il file sul VPS.

Per farlo — da qualsiasi sessione, cloud inclusa — c'è il workflow manuale
`.github/workflows/deploy-plugin-config.yml`: modifica **solo la riga della chiave indicata**
(lascia intatto il resto del file e i commenti, fa un backup timestampato) e poi ricarica il
plugin senza riavviare. Lancialo con `workflow_dispatch` passando `plugin`, `file`, `key`
(nome-foglia, es. `ally-prefix`), `value` (il nuovo valore, virgolette comprese) e
`reload_cmd` (default `mf reload`). Usa gli stessi secret VPS del deploy sito/plugin.

## Note

- `.claude/settings.local.json` è per-macchina e non va versionato (vedi `.gitignore`).
