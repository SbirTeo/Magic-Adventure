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
  chiave SSH e **senza** rete verso il VPS (solo HTTPS). Da lì il passo 3 (VPS) **non è
  eseguibile**. In una sessione cloud:
  1. esegui i passi 1–2 (mirror se serve, e GitHub `main`);
  2. **avvisa esplicitamente l'utente** che il deploy sul VPS è ancora da fare e va
     lanciato da una sessione locale (o dallo stesso utente via SSH).
  Non dare mai per fatto il deploy sul VPS da una sessione cloud.

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

## Note

- `.claude/settings.local.json` è per-macchina e non va versionato (vedi `.gitignore`).
