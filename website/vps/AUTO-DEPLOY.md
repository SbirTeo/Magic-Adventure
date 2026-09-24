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
il server** per caricarli. Lo **stop** si fa con il comando CMI `stopserverfast` (salva e chiude
pulito, regola in `CLAUDE.md`): il **preavviso in chat** ai giocatori e il conto alla rovescia
sono già dentro quel comando (CMI), quindi il workflow non li ripete. Poi `server/start.sh`
(`while true; do java ...; done` nello screen `mc`) rilancia il server da solo, come per il
riavvio notturno. `systemctl restart` si usa solo se il server è spento (nessuno screen `mc`).

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

Ogni notte il server si riavvia da solo, con preavviso ai giocatori: 5 minuti, 1 minuto,
30 secondi, poi un conto alla rovescia 10..1 (anche come titolo a schermo negli ultimi 10
secondi) e infine un semplice `stop`.

Non serve systemd ne' un cron esterno: `server/start.sh` (`while true; do java ...; done`)
gia' riavvia da solo il processo qualche secondo dopo QUALSIASI stop — mandare "stop" da un
plugin di gioco basta e avanza. Il countdown vive quindi
interamente in `plugins/CMI/Settings/Schedules.yml` come una voce dello scheduler di CMI
(`PerformOn`, con `delay!` tra un avviso e l'altro — stesso meccanismo dell'esempio
`StopServer` gia' presente di default in quel file), aggiunta/corretta con gli stessi
workflow usati per gli altri interventi mirati su un file di config gia' sul VPS
(`deploy-plugin-config.yml`, modalita' `append-block`/`replace-block`).

ATTENZIONE fuso orario: il VPS gira in UTC (verificato via diagnostica-vps), e CMI legge
l'ora dalla JVM del server — anche lei in UTC, non in ora italiana. L'orario scritto nello
schedule (`PerformOn: Hour: ...`) va quindi letto come UTC: nella pratica, con l'Italia in
ora legale (CEST, UTC+2) le 3 di notte italiane sono `Hour: 1` nello schedule, e in ora
solare (CET, UTC+1) diventano `Hour: 2` — da aggiustare a mano ai due cambi d'ora annuali
finche' nessuno dei due (server o schedule) tiene conto del fuso italiano.
