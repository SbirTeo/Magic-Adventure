# -*- coding: utf-8 -*-
"""
Aggancio automatico del controllo dei buchi (controlla-config.py).

Lo lancia Claude Code da solo, senza che nessuno debba ricordarsene. Legge da stdin il JSON
dell'evento, decide se la cosa lo riguarda, esegue il controllo su TUTTI i plugin e, se trova
buchi, li rimanda indietro. Se e' tutto a posto resta zitto.

Quando scatta (configurato in .claude/settings.local.json):
  1. dopo una Edit/Write su un file dentro plugins-src        -> evento con tool_input.file_path
  2. dopo un comando Bash che nomina plugins-src              -> evento con tool_input.command
     (serve perche' molte modifiche passano da script python o sed, non dagli strumenti di modifica)
  3. quando l'utente scrive un messaggio                      -> nessun tool: si controlla e basta
     (cosi' anche le modifiche fatte A MANO nell'editor vengono viste, non solo le mie)

Il controllo gira su tutti e sei i plugin ogni volta: costa un secondo, e un buco aperto in un
plugin non ha motivo di restare nascosto solo perche' si stava lavorando su un altro.

Per provarlo a mano:
    echo '{"tool_input":{"file_path":"...plugins-src/x.yml"}}' | python plugins-src/hook-controlla.py
"""
import json
import os
import subprocess
import sys

QUI = os.path.dirname(os.path.abspath(__file__))
CONTROLLO = os.path.join(QUI, "controlla-config.py")
CARTELLA = "plugins-src"


def riguarda_i_plugin(evento):
    """True se questo evento puo' aver cambiato qualcosa dentro plugins-src."""
    ingresso = evento.get("tool_input") or {}
    risposta = evento.get("tool_response") or {}
    if not isinstance(ingresso, dict):
        ingresso = {}
    if not isinstance(risposta, dict):
        risposta = {}

    # 1. Edit/Write: c'e' il percorso del file
    percorso = ingresso.get("file_path") or risposta.get("filePath") or ""
    if percorso:
        return CARTELLA in str(percorso).replace("\\", "/")

    # 2. Bash: il percorso non c'e', ma se il comando nomina plugins-src puo' averci scritto dentro
    comando = ingresso.get("command") or ""
    if comando:
        return CARTELLA in str(comando).replace("\\", "/")

    # 3. Nessun tool (l'utente ha scritto un messaggio): puo' aver modificato a mano, si controlla
    return not evento.get("tool_name")


def main():
    try:
        evento = json.load(sys.stdin)
    except Exception:
        return 0  # niente JSON valido: non e' il caso di disturbare
    if not riguarda_i_plugin(evento):
        return 0

    try:
        esito = subprocess.run([sys.executable, CONTROLLO], capture_output=True, text=True, timeout=120)
    except Exception as e:
        print(json.dumps({"systemMessage": f"controlla-config non eseguito: {e}"}))
        return 0
    if esito.returncode == 0:
        return 0  # tutto allineato: silenzio

    testo = (esito.stdout or "").strip() or (esito.stderr or "").strip()
    print(json.dumps({
        "systemMessage": "Controllo plugin: ci sono buchi da sistemare (vedi sotto).",
        "hookSpecificOutput": {
            "hookEventName": evento.get("hook_event_name") or "PostToolUse",
            "additionalContext": "controlla-config.py ha trovato dei buchi fra config, README e guide.\n"
                                 "Vanno chiusi prima di considerare finita la modifica:\n\n" + testo,
        },
    }))
    return 0


if __name__ == "__main__":
    sys.exit(main())
