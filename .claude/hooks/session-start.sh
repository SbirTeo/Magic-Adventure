#!/bin/bash
# ============================================================
#  Sessioni CLOUD: mettere il JDK 25, se no i plugin non si compilano
# ============================================================
# Il container delle sessioni cloud ha Maven e un JDK 21. I plugin pero' sono compilati
# contro paper-api 26.1.2, che e' roba Java 25 (class file 69.0): col solo 21 non si riesce
# nemmeno a leggere l'API, quindi "mvn package" non parte e l'unico modo di sapere se il
# codice compila era aspettare la GitHub Action.
#
# Questo script scarica Temurin 25 la prima volta e lo lascia in /opt/jdk-25: lo stato del
# container viene messo in cache dopo l'hook, quindi le sessioni successive se lo ritrovano
# gia' li' e lo script non fa niente. Sul PC (sessione locale) non gira: li' il JDK c'e'.
set -euo pipefail

# Solo nelle sessioni cloud: in locale il JDK lo gestisce chi usa il PC.
if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

JDK_DIR="/opt/jdk-25"
# API di Adoptium: "l'ultima 25 GA per linux/x64". Nessuna versione scritta a mano da
# aggiornare a ogni patch.
JDK_URL="https://api.adoptium.net/v3/binary/latest/25/ga/linux/x64/jdk/hotspot/normal/eclipse"

if [ ! -x "$JDK_DIR/bin/javac" ]; then
  tmp="$(mktemp -d)"
  # Se il download non riesce la sessione parte lo stesso, solo senza compilatore: meglio
  # una sessione monca di una sessione che non parte.
  if curl -sSL --retry 3 --max-time 600 -o "$tmp/jdk.tar.gz" "$JDK_URL"; then
    mkdir -p "$JDK_DIR"
    tar xzf "$tmp/jdk.tar.gz" -C "$JDK_DIR" --strip-components=1
  else
    echo "JDK 25 non scaricato: 'mvn package' non funzionera' in questa sessione." >&2
  fi
  rm -rf "$tmp"
fi

# JAVA_HOME per Maven, e il javac nel PATH, per tutta la sessione.
if [ -x "$JDK_DIR/bin/javac" ] && [ -n "${CLAUDE_ENV_FILE:-}" ]; then
  {
    echo "export JAVA_HOME=$JDK_DIR"
    echo "export PATH=$JDK_DIR/bin:\$PATH"
  } >> "$CLAUDE_ENV_FILE"
fi
