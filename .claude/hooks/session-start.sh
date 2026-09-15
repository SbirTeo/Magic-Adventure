#!/bin/bash
# ============================================================
#  Sessioni CLOUD: mettere il JDK 25, se no i plugin non si compilano
# ============================================================
# Il container delle sessioni cloud e' un Ubuntu 24.04 con Maven e il JDK di serie della
# distribuzione, che e' il 21. I plugin pero' sono compilati contro paper-api 26.1.2, che e'
# roba Java 25 (class file 69.0): col solo 21 non si riesce nemmeno a leggere l'API, quindi
# "mvn package" non parte e l'unico modo di sapere se il codice compila era aspettare la
# GitHub Action (che infatti usa il 25).
#
# Il 25 nei repo di Ubuntu c'e', solo non e' quello installato di serie: si mette con apt in
# una decina di secondi. L'indice dei pacchetti dentro l'immagine e' vecchio e punta a un .deb
# che non c'e' piu' (404), quindi prima ci vuole un update.
#
# Lo stato del container viene messo in cache dopo l'hook: le sessioni successive trovano il
# JDK gia' li' e questo script esce subito. Sul PC (sessione locale) non gira: li' il JDK c'e'.
set -euo pipefail

# Solo nelle sessioni cloud: in locale il JDK lo gestisce chi usa il PC.
if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

APT_HOME="/usr/lib/jvm/java-25-openjdk-amd64"   # dove lo mette la distribuzione
TAR_HOME="/opt/jdk-25"                          # dove lo mettiamo noi, se apt non ce la fa

if [ ! -x "$APT_HOME/bin/javac" ] && [ ! -x "$TAR_HOME/bin/javac" ]; then
  # Niente jre e niente documentazione: serve il compilatore, non un desktop.
  apt-get update -qq >/dev/null 2>&1 || true
  apt-get install -y --no-install-recommends openjdk-25-jdk-headless >/dev/null 2>&1 || true
fi

# Ripiego: se il pacchetto non c'e' piu' (cambia nome, sparisce dai repo), Temurin dall'API di
# Adoptium — "l'ultima 25 GA per linux/x64", nessuna versione scritta a mano da aggiornare.
if [ ! -x "$APT_HOME/bin/javac" ] && [ ! -x "$TAR_HOME/bin/javac" ]; then
  tmp="$(mktemp -d)"
  url="https://api.adoptium.net/v3/binary/latest/25/ga/linux/x64/jdk/hotspot/normal/eclipse"
  # Se anche questo non riesce la sessione parte lo stesso, solo senza compilatore: meglio una
  # sessione monca di una sessione che non parte.
  if curl -sSL --retry 3 --max-time 600 -o "$tmp/jdk.tar.gz" "$url"; then
    mkdir -p "$TAR_HOME"
    tar xzf "$tmp/jdk.tar.gz" -C "$TAR_HOME" --strip-components=1
  else
    echo "JDK 25 non installato: 'mvn package' non funzionera' in questa sessione." >&2
  fi
  rm -rf "$tmp"
fi

# JAVA_HOME per Maven, e il javac nel PATH, per tutta la sessione.
JAVA_25=""
[ -x "$APT_HOME/bin/javac" ] && JAVA_25="$APT_HOME"
[ -z "$JAVA_25" ] && [ -x "$TAR_HOME/bin/javac" ] && JAVA_25="$TAR_HOME"
if [ -n "$JAVA_25" ] && [ -n "${CLAUDE_ENV_FILE:-}" ]; then
  {
    echo "export JAVA_HOME=$JAVA_25"
    echo "export PATH=$JAVA_25/bin:\$PATH"
  } >> "$CLAUDE_ENV_FILE"
fi
