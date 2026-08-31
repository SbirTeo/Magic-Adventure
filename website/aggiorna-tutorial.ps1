# Aggiorna la guida dei giocatori ovunque, in un colpo solo.
#
#   powershell -File website\aggiorna-tutorial.ps1
#
# La fonte e' UNA SOLA: plugins-src\MagixFactions\docs\build_tutorial.py.
# Non modificare mai tutorial.html a mano: alla prima rigenerazione le modifiche spariscono.
#
# La catena e':
#   build_tutorial.py  ->  docs\tutorial.html  ->  dentro il jar  ->  il plugin lo riscrive
#   in plugins\MagixFactions\ a ogni avvio del server  ->  un guardiano systemd sul VPS
#   (sync-guida.path) lo copia nel sito appena cambia.
#
# Questo script fa tutti i passi, riavvio compreso.
#
# NB: la guida NON si copia a mano sul VPS. Il file in docs\ e' un MODELLO pieno di segnaposto
# ({{cfg:...}}, {{se:map.mode=chat}}...{{/se}}) che solo il PLUGIN sa risolvere, leggendo il config
# vero del server: copiarlo di peso nella cartella del plugin metterebbe sul sito i segnaposto grezzi
# e, peggio, TUTTE le modalita' insieme (mappa in chat e mappa-item nello stesso capitolo).
# L'unico modo giusto di allineare il sito e' far ripartire il server: il plugin riscrive la guida
# risolta e il guardiano systemd la porta sul sito da solo.

$ErrorActionPreference = 'Stop'

$radice   = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$plugin   = Join-Path $radice 'plugins-src\MagixFactions'
$script   = Join-Path $plugin 'docs\build_tutorial.py'
$generato = Join-Path $plugin 'docs\tutorial.html'
# Il numero di versione si legge dal pom, cosi' non resta indietro a ogni rilascio.
$versione = ([xml](Get-Content (Join-Path $plugin 'pom.xml'))).project.version
$jar      = "MagixFactions-$versione.jar"
$chiave   = Join-Path $env:USERPROFILE '.ssh\ovh_vps'
$vps      = 'ubuntu@141.94.123.249'
$suVps    = '/home/ubuntu/magicadventure/plugins/MagixFactions/tutorial.html'

Write-Host '1/5  Rigenero la guida...' -ForegroundColor Cyan
python $script
if (-not (Test-Path $generato)) { throw "Non trovo il file generato: $generato" }

Write-Host '2/5  Ricostruisco il jar (ci finisce dentro la guida)...' -ForegroundColor Cyan
Push-Location $plugin
try { mvn -q -DskipTests package } finally { Pop-Location }

Write-Host '3/5  Copio il jar nel mirror locale e sul VPS...' -ForegroundColor Cyan
Copy-Item (Join-Path $plugin "target\$jar") (Join-Path $radice "server\plugins\$jar") -Force
scp -i $chiave (Join-Path $radice "server\plugins\$jar") "${vps}:/home/ubuntu/magicadventure/plugins/$jar"

Write-Host '4/5  Riavvio il server (la guida risolta la scrive lui)...' -ForegroundColor Cyan
# Il loop di start.sh fa ripartire tutto da solo: basta lo stop. Mai 'screen -X quit' con java vivo.
ssh -i $chiave $vps "screen -S mc -X stuff 'stop^M'"
Write-Host '     ...aspetto che risalga (~60s)' -ForegroundColor DarkGray
Start-Sleep -Seconds 60

Write-Host '5/5  Verifico che server e sito abbiano la stessa guida...' -ForegroundColor Cyan
ssh -i $chiave $vps "md5sum $suVps /var/www/magicadventure/public/assets/guida/magixfactions.html; grep -c '{{' $suVps"

Write-Host ''
Write-Host 'Fatto. Se i due codici qui sopra coincidono, sito e server hanno la stessa guida' -ForegroundColor Green
Write-Host '(e il conteggio dei {{ deve essere 0: nessun segnaposto rimasto):' -ForegroundColor Green
Write-Host '  https://magicadventure.it/tutorial' -ForegroundColor Green
