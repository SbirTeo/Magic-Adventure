# Auto-deploy del sito sul VPS

Come funziona, e cosa configurare **una volta sola** perche' parta.

## Cosa fa

Il workflow `.github/workflows/deploy-sito.yml` si attiva a ogni push su `main` che tocca
`website/`. Su un runner di GitHub:

1. scarica il codice aggiornato;
2. lo copia sul VPS via `rsync` over SSH, dentro `/var/www/magicadventure/`
   (il contenuto di `website/` -> web root, dove `public/` e `includes/` stanno affiancati).

Risultato: aggiornare `main` mette il sito online **da solo**, senza entrare nel VPS a mano
e anche dalle sessioni cloud che non hanno accesso SSH.

### Cosa NON tocca (di proposito)
- `includes/config.php` — sul VPS ha la password DB **vera**; nel repo c'e' solo un
  segnaposto. E' escluso dalla copia: resta quello del server.
- `public/assets/img/caricate/` e ogni altro file presente solo sul VPS (upload utenti):
  `rsync` gira **senza `--delete`**, quindi aggiunge e aggiorna soltanto, non cancella mai.
- Il **server Minecraft**: questo workflow riguarda solo il sito web.

## Setup (una volta sola)

### 1) Secret su GitHub
Repo -> **Settings -> Secrets and variables -> Actions -> New repository secret**. Crearne tre:

| Nome | Valore |
|------|--------|
| `VPS_HOST` | `141.94.123.249` |
| `VPS_USER` | `ubuntu` |
| `VPS_SSH_KEY` | il contenuto della chiave **privata** che entra nel VPS (il file `~/.ssh/ovh_vps` sul PC, tutto, incluse le righe `BEGIN/END`) |

Finche' mancano, il workflow non fallisce: si salta con un avviso.

### 2) Permesso di scrittura sul web root (lato VPS)
Il web root e' di proprieta' di `root`/`www-data`, quindi `rsync` scrive con `sudo`. Serve
autorizzare l'utente `ubuntu` a usare `rsync` con sudo **senza password**. Sul VPS:

```bash
echo 'ubuntu ALL=(root) NOPASSWD: /usr/bin/rsync' | sudo tee /etc/sudoers.d/deploy-rsync
sudo chmod 440 /etc/sudoers.d/deploy-rsync
# verifica che il percorso di rsync sia proprio /usr/bin/rsync:
command -v rsync
```

Se `rsync` sta altrove (es. `/bin/rsync`), correggi il percorso nel file sudoers.

> Se invece il web root fosse gia' di proprieta' di `ubuntu`, puoi togliere
> `--rsync-path="sudo rsync"` dal workflow e saltare questo passo.

### 3) La chiave deve essere autorizzata sul VPS
La chiave pubblica corrispondente a `VPS_SSH_KEY` deve stare in
`~ubuntu/.ssh/authorized_keys` sul VPS (se usi la stessa `ovh_vps` con cui entri gia', c'e' gia').

## Provarlo
- **Manuale:** tab **Actions -> Deploy sito sul VPS -> Run workflow**.
- **Automatico:** un qualsiasi push su `main` che tocca `website/`.

Se qualcosa non va, il log del job dice a che passo si e' fermato (di solito: permessi
sudo o chiave non autorizzata).

## Deploy dei plugin Minecraft (attivo)

Il workflow `.github/workflows/deploy-plugin.yml` fa lo stesso per i **plugin**: a ogni push
su `main` che tocca `plugins-src/`, compila i plugin cambiati (Maven/JDK 21), copia il jar
sul VPS in `/home/ubuntu/magicadventure/plugins/` (togliendo la versione vecchia) e **riavvia
il server** per caricarli, con un **preavviso in chat** ai giocatori online (say a -60s, -20s,
-5s, poi `save-all` e riavvio).

Usa gli **stessi tre secret** del deploy sito. In piu' serve un permesso sudo lato VPS,
perche' il riavvio usa systemd (come il pulsante "Riavvia" del gestionale):

```bash
echo 'ubuntu ALL=(root) NOPASSWD: /usr/bin/systemctl restart magicadventure.service' \
  | sudo tee /etc/sudoers.d/deploy-mc
sudo chmod 440 /etc/sudoers.d/deploy-mc
```

Dettagli d'ambiente (da `istanze.conf`): sessione screen `mc`, utente `ubuntu`, servizio
`magicadventure.service`, cartella `/home/ubuntu/magicadventure`.

- **Manuale:** Actions -> "Deploy plugin sul server Minecraft" -> Run workflow (campo
  `plugin`: nome cartella o `all`).
- **Automatico:** un push che tocca `plugins-src/<Plugin>/`.

> Convenzione: il nome della cartella in `plugins-src/` deve coincidere col prefisso del jar
> (es. `MagixFactions` -> `MagixFactions-<versione>.jar`), cosi' la rimozione della vecchia
> versione (`MagixFactions-*.jar`) e' precisa.

## Riavvio notturno automatico (attivo)

Ogni notte alle 03:00 **ora italiana** il server si riavvia da solo, con preavviso ai
giocatori: 5 minuti, 1 minuto, 30 secondi, poi un conto alla rovescia 10..1 (anche come
titolo a schermo negli ultimi 10 secondi), un `save-all` e infine il riavvio vero.

Non e' un job di GitHub Actions (il minimo per uno `schedule` li' e' un'ora, troppo grezzo
per un countdown al secondo): e' `website/vps/riavvio-notturno.sh`, lanciato dal **crontab
dell'utente `ubuntu`** sul VPS (nessun sudo per installarlo: e' il crontab dell'utente
stesso). Il VPS gira in UTC (verificato via diagnostica-vps), quindi il crontab lo lancia
**ogni minuto**: e' lo script stesso, ragionando sempre in fuso `Europe/Rome`, a uscire
subito a vuoto finche' non sono esattamente le 02:55 ora italiana — cosi' il cambio tra ora
solare e legale non lo manda un'ora fuori, cosa che capiterebbe con un orario fisso scritto
nel crontab in UTC. Lo script calcola poi da solo il tempo che manca alle 03:00 e manda i
messaggi in game via `screen` (stesso meccanismo del reload dei plugin); il riavvio finale
usa `systemctl restart magicadventure.service` con gli **stessi** permessi sudo del deploy
plugin qui sopra — necessario perche' il servizio ha `Restart=no`: un semplice `/stop` dentro
al gioco lascerebbe il server giu' per sempre, non lo farebbe ripartire da solo.

Setup/aggiornamento: `.github/workflows/deploy-riavvio-notturno.yml`, automatico su push che
tocca lo script, oppure manuale (utile per re-installare il cron senza cambiare il file).
Usa gli stessi tre secret VPS del deploy sito/plugin. Log delle esecuzioni:
`/home/ubuntu/magicadventure/logs/riavvio-notturno.log` sul VPS.
