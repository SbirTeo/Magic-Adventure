#!/bin/bash
# Tiene la guida del sito allineata a quella del server, da sola.
#
# Il plugin MagixFactions riscrive plugins/MagixFactions/tutorial.html a OGNI avvio del
# server (la prende dal jar, dove finisce docs/tutorial.html). Questa unita' systemd
# guarda quel file: appena cambia, lo ricopia nella cartella pubblica del sito.
# Cosi' non c'e' piu' un passaggio manuale: aggiorni la guida, riavvii il server, il sito
# e' gia' allineato.
#
# Installazione (una volta sola):  sudo bash installa-sync-guida.sh
set -e

SORGENTE=/home/ubuntu/magicadventure/plugins/MagixFactions/tutorial.html
DESTINAZIONE=/var/www/magicadventure/public/assets/guida/magixfactions.html

cat > /usr/local/bin/sync-guida-magixfactions.sh <<'FINE'
#!/bin/bash
# Copia la guida del plugin nella cartella pubblica del sito, solo se e' cambiata.
SORGENTE=/home/ubuntu/magicadventure/plugins/MagixFactions/tutorial.html
DESTINAZIONE=/var/www/magicadventure/public/assets/guida/magixfactions.html
[ -f "$SORGENTE" ] || exit 0
if ! cmp -s "$SORGENTE" "$DESTINAZIONE"; then
  install -o www-data -g www-data -m 644 "$SORGENTE" "$DESTINAZIONE"
  logger -t sync-guida "guida aggiornata sul sito ($(stat -c%s "$DESTINAZIONE") byte)"
fi
FINE
chmod +x /usr/local/bin/sync-guida-magixfactions.sh

cat > /etc/systemd/system/sync-guida.service <<'FINE'
[Unit]
Description=Copia la guida MagixFactions nel sito

[Service]
Type=oneshot
ExecStart=/usr/local/bin/sync-guida-magixfactions.sh
FINE

cat > /etc/systemd/system/sync-guida.path <<FINE
[Unit]
Description=Sorveglia la guida MagixFactions generata dal plugin

[Path]
PathChanged=$SORGENTE
Unit=sync-guida.service

[Install]
WantedBy=multi-user.target
FINE

systemctl daemon-reload
systemctl enable --now sync-guida.path

echo "--- stato ---"
systemctl is-enabled sync-guida.path
systemctl is-active sync-guida.path
ls -l "$SORGENTE" "$DESTINAZIONE"
