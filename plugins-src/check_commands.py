# -*- coding: utf-8 -*-
"""
No-Italian check for COMMANDS and PERMISSION NODES, companion to check_english.py.

The project rule is "nothing structural in Italian": not code identifiers (check_english.py), not
website function names (check_english_web.py), and — this file — not the words a player TYPES or a
permission node is named after. Those had slipped through: `/menus apri`, `/registrati`,
`magixguard.sanziona`. They are not "UI text you read": they are handles, and handles are English.

What it scans, per plugin:
  - plugin.yml: every command name, every alias, every permission node key;
  - the command/ package .java files: the sub-command keywords in `case "..."`,
    `equalsIgnoreCase("...")` and `equals("...")`.

It reuses the Italian word list from check_english.py — grow it there.

Usage:
    python plugins-src/check_commands.py            # every plugin
    python plugins-src/check_commands.py MagixGuard # one

Exits 1 if it found anything.
"""
import os
import re
import sys

from check_english import italian_words_in, present_plugins, ITALIAN_WORDS

HERE = os.path.dirname(os.path.abspath(__file__))

# Un comando e' un'unica parolina attaccata: "mgviolazione", "cambiapassword". Lo split per parole
# non lo spezza, quindi per gli handle si guarda anche se contengono un GAMBO italiano lungo (>=5,
# per non pescare coincidenze corte dentro parole inglesi). Vale solo per comandi/alias, non per i
# nomi di tipo del codice, dove il match a sottostringa darebbe falsi positivi.
LONG_STEMS = sorted((w for w in ITALIAN_WORDS if len(w) >= 5), key=len, reverse=True)


def italian_in_handle(token):
    hits = set(italian_words_in(token))
    low = token.lower()
    for stem in LONG_STEMS:
        if stem in low:
            hits.add(stem)
    return sorted(hits)

CASE = re.compile(r'\bcase\s+"([a-z][a-z0-9_]*)"')
EQ = re.compile(r'\.(?:equalsIgnoreCase|equals)\("([a-z][a-z0-9_]*)"\)')
ALIASES = re.compile(r'aliases:\s*\[([^\]]*)\]')


def check_plugin_yml(path):
    """Command names, aliases and permission node keys in one plugin.yml."""
    problems = []
    lines = open(path, encoding="utf-8", errors="ignore").read().splitlines()
    section = None          # 'commands' | 'permissions' | None
    for line in lines:
        stripped = line.strip()
        if re.match(r'^(commands|permissions):\s*$', stripped):
            section = stripped[:-1]
            continue
        if line and not line[0].isspace():
            section = None

        # command name / permission node: a key at two-space indent
        m = re.match(r'^  ([A-Za-z0-9_.]+):\s*$', line)
        if m and section == 'commands':
            hits = italian_in_handle(m.group(1))
            if hits:
                problems.append(("command", m.group(1), hits))
        elif m and section == 'permissions':
            hits = italian_in_handle(m.group(1))
            if hits:
                problems.append(("permission", m.group(1), hits))

        ma = ALIASES.search(line)
        if ma and section == 'commands':
            for alias in ma.group(1).split(","):
                alias = alias.strip()
                if alias:
                    hits = italian_in_handle(alias)
                    if hits:
                        problems.append(("alias", alias, hits))
    return problems


def check_command_java(java_root):
    """Sub-command keywords compared inside the command/ package."""
    problems = []
    for root, _, files in os.walk(java_root):
        if os.sep + "command" not in root + os.sep:
            continue
        for f in files:
            if not f.endswith(".java"):
                continue
            path = os.path.join(root, f)
            rel = os.path.relpath(path, java_root).replace("\\", "/")
            src = open(path, encoding="utf-8", errors="ignore").read()
            for rx in (CASE, EQ):
                for m in rx.finditer(src):
                    hits = italian_words_in(m.group(1))
                    if hits:
                        problems.append(("subcommand \"" + m.group(1) + "\" in " + rel, m.group(1), hits))
    return problems


def check(name):
    problems = []
    yml = os.path.join(HERE, name, "src", "main", "resources", "plugin.yml")
    if os.path.isfile(yml):
        problems += check_plugin_yml(yml)
    java_root = os.path.join(HERE, name, "src", "main", "java")
    if os.path.isdir(java_root):
        problems += check_command_java(java_root)
    return problems


def main():
    names = sys.argv[1:] or present_plugins()
    total = 0
    for name in names:
        problems = check(name)
        print(f"\n=== {name}: {len(problems)} in Italian" if problems else f"\n=== {name}: English")
        for kind, ident, hits in problems:
            print(f"  {kind} «{ident}» -> Italian: {', '.join(hits)}")
        total += len(problems)
    print(f"\nTOTAL commands/permissions not in English: {total}")
    return 1 if total else 0


if __name__ == "__main__":
    sys.exit(main())
