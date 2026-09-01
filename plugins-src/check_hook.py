# -*- coding: utf-8 -*-
"""
Automatic hook for the plugin checks (check_config.py + check_english.py).

Claude Code runs it by itself, so nobody has to remember. It reads the event JSON from stdin, decides
whether it is relevant, runs BOTH checks on EVERY plugin, and, if anything is off, hands the findings
back. If everything is fine it stays quiet.

Two guards, run every time:
  - check_config.py  : config <-> README <-> guides <-> tutorial are aligned, no gaps.
  - check_english.py : file/package/top-level-type names are English (code-in-English rule).

When it fires (configured in .claude/settings.local.json):
  1. after an Edit/Write on a file inside plugins-src        -> event with tool_input.file_path
  2. after a Bash command that names plugins-src             -> event with tool_input.command
     (needed because many changes go through python or sed, not the editing tools)
  3. when the user submits a message                         -> no tool: just check
     (so hand edits made in the editor are seen too, not only mine)

Running on all plugins every time costs a second, and an open problem in one plugin has no reason to
stay hidden just because someone was working on another.

(Code identifiers and comments are in English, per the rule; code and output alike.)

To try it by hand:
    echo '{"tool_input":{"file_path":"...plugins-src/x.yml"}}' | python plugins-src/check_hook.py
"""
import json
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
CHECKS = [os.path.join(HERE, name) for name in (
    "check_english.py",         # nomi file/package/tipi in inglese
    "check_english_web.py",     # funzioni JS/PHP del sito in inglese (righe cambiate)
    "check_commands.py",        # comandi, alias, sottocomandi, permessi in inglese
    "check_config_english.py",  # chiavi e nomi dei file di config in inglese
    "check_config.py",          # config <-> README <-> guide <-> tutorial allineati
)]
# I due alberi che i check guardano: i plugin E il sito (le funzioni JS/PHP, i comandi, i config).
# Toccare uno dei due fa scattare l'hook.
FOLDERS = ("plugins-src", "website")


def touches_watched(text):
    t = str(text).replace("\\", "/")
    return any(f in t for f in FOLDERS)


def concerns_the_repo(event):
    """True if this event may have changed something under plugins-src or website."""
    tool_input = event.get("tool_input") or {}
    tool_response = event.get("tool_response") or {}
    if not isinstance(tool_input, dict):
        tool_input = {}
    if not isinstance(tool_response, dict):
        tool_response = {}

    # 1. Edit/Write: the file path is there
    path = tool_input.get("file_path") or tool_response.get("filePath") or ""
    if path:
        return touches_watched(path)

    # 2. Bash: no path, but if the command names a watched folder it may have written inside it
    command = tool_input.get("command") or ""
    if command:
        return touches_watched(command)

    # 3. No tool (the user typed a message): may have edited by hand, so check
    return not event.get("tool_name")


def main():
    try:
        event = json.load(sys.stdin)
    except Exception:
        return 0  # no valid JSON: no reason to disturb
    if not concerns_the_repo(event):
        return 0

    reports = []
    for check in CHECKS:
        try:
            result = subprocess.run([sys.executable, check], capture_output=True, text=True, timeout=120)
        except Exception as e:
            print(json.dumps({"systemMessage": f"{os.path.basename(check)} not run: {e}"}))
            continue
        if result.returncode != 0:
            text = (result.stdout or "").strip() or (result.stderr or "").strip()
            reports.append((os.path.basename(check), text))

    if not reports:
        return 0  # everything aligned and in English: silence

    blocks = "\n\n".join(f"### {name}\n{text}" for name, text in reports)
    print(json.dumps({
        "systemMessage": "Plugin check: there are things to fix (see below).",
        "hookSpecificOutput": {
            "hookEventName": event.get("hook_event_name") or "PostToolUse",
            "additionalContext": "The plugin checks found something to close before the change can be "
                                 "considered done:\n\n" + blocks,
        },
    }))
    return 0


if __name__ == "__main__":
    sys.exit(main())
