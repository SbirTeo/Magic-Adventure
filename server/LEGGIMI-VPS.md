# MAGICADVENTURE — deploy su VPS Linux

Questa cartella è un **mirror completo** del server (Spigot 26.2, plugin, config e mondi)
pronto per essere caricato su un VPS Linux e avviato via SSH.

## 1. Carica i file sul VPS
Da Windows (PowerShell), con `scp` (incluso in Windows 10/11):
```powershell
scp -r "C:\Users\teolo\OneDrive\Desktop\MAGICADVENTURE-VPS" utente@IP_DEL_VPS:/home/utente/magicadventure
```
In alternativa usa un client SFTP (FileZilla, WinSCP).

## 2. Installa Java 25 sul VPS
Minecraft 26.x richiede **Java 25**. Esempio (Debian/Ubuntu con Adoptium/Temurin):
```bash
sudo apt update && sudo apt install -y wget apt-transport-https
wget -qO- https://packages.adoptium.net/artifactory/api/gpg/key/public | sudo tee /etc/apt/keyrings/adoptium.asc
echo "deb [signed-by=/etc/apt/keyrings/adoptium.asc] https://packages.adoptium.net/artifactory/deb $(. /etc/os-release; echo $VERSION_CODENAME) main" | sudo tee /etc/apt/sources.list.d/adoptium.list
sudo apt update && sudo apt install -y temurin-25-jdk
java -version   # deve mostrare 25.x
```
Se `java` non è nel PATH, apri `start.sh` e imposta `JAVA` al percorso assoluto del binario.

## 3. Avvia il server
```bash
cd /home/utente/magicadventure
chmod +x start.sh
./start.sh
```
- L'EULA è già accettato (`eula.txt` → `eula=true`).
- Per fermare: scrivi `stop` nella console. Il server riparte da solo dopo 5 s;
  premi **CTRL+C** durante il conto alla rovescia per chiuderlo del tutto.
- La RAM si regola in `start.sh` (`MEM_MIN` / `MEM_MAX`).

## 4. Tenerlo acceso senza SSH aperto
Usa `screen` (o `tmux`):
```bash
sudo apt install -y screen
screen -S mc ./start.sh      # avvia dentro una sessione "mc"
# stacca:  CTRL+A poi D       riattacca:  screen -r mc
```
Per un avvio automatico al boot, valuta un servizio **systemd** (posso crearlo io).

## 5. Firewall / porta
Apri la porta del server (default **25565/tcp**):
```bash
sudo ufw allow 25565/tcp
```
Su VPS con pannello, apri la porta anche nel firewall del provider.
`server.properties`: `server-ip=` (vuoto = ascolta su tutte le interfacce),
`server-port=25565`, `online-mode=true` (account premium).

## 6. Backup
Il plugin **AutoBackup** è stato tolto dal server (vedi sotto): al momento **non c'è nessun
backup automatico schedulato**. `start.sh` ha ancora il controllo del file-segnale `backup.flag`
a server spento (esegue `./backup.sh` se presente ed eseguibile) — è innocuo lasciarlo: senza
un plugin che crea quel file, semplicemente non scatta mai. Se serve di nuovo un backup
schedulato, va reintrodotto un plugin (o un cron) che lasci quel segnale, oppure affidarsi a uno
snapshot del provider VPS.

## Plugin custom inclusi
- **MagixFactions** — fazioni, gradi, relazioni alleati/nemici, chat.
- **MagixEssentials** — utilita' di base: tablist e **MOTD** della lista server.

Il vecchio **CustomMOTD** e' stato tolto: la MOTD la fa il modulo `motd` di MagixEssentials.
Anche **AutoBackup** e' stato tolto (nessun sostituto per i backup automatici, vedi sopra). I
loro jar non sono cancellati, stanno in `plugins/.rimossi/` (vedi il workflow `rimuovi-plugin.yml`).
Più CMI, LuckPerms, Vault, PlaceholderAPI.
