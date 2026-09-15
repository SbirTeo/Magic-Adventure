# -*- coding: utf-8 -*-
"""
No-Italian check for CONFIG KEYS and CONFIG FILE NAMES, companion to the others.

The rule is "no config in Italian". Values stay Italian (they are text a person reads); it is the
KEYS — the handles the code reads with getString("...") — and the file NAMES that must be English.
`sanzioni.yml` with `durata:` / `punti:` is exactly what this catches.

Scope, per plugin: every YAML file under resources/ EXCEPT
  - plugin.yml           (commands/permissions -> check_commands.py),
  - anything under menus/ (those are user data, not config),
  - renames.yml          (it lists the OLD key names, Italian by definition: that is its job).
For each it flags Italian file names and Italian keys (the text left of the first colon).

It reuses the Italian word list in check_english.py — grow it there.

Usage:
    python plugins-src/check_config_english.py            # every plugin
    python plugins-src/check_config_english.py MagixGuard # one

Exits 1 if it found anything.
"""
import os
import re
import sys

from check_english import italian_words_in, present_plugins

HERE = os.path.dirname(os.path.abspath(__file__))
KEY = re.compile(r'^\s*([A-Za-z0-9_-]+):')


def check_yaml(path, rel):
    problems = []
    name = os.path.basename(path)[:-4]
    for w in italian_words_in(name):
        problems.append((rel, "file name", name, [w]))
    for i, line in enumerate(open(path, encoding="utf-8", errors="ignore"), 1):
        m = KEY.match(line)
        if not m:
            continue
        hits = italian_words_in(m.group(1))
        if hits:
            problems.append((rel, "key L" + str(i), m.group(1), hits))
    return problems


def check(name):
    res = os.path.join(HERE, name, "src", "main", "resources")
    problems = []
    for root, _, files in os.walk(res):
        rel_dir = os.path.relpath(root, res).replace("\\", "/")
        if rel_dir.startswith("menus"):
            continue
        for f in files:
            if not f.endswith(".yml") or f in ("plugin.yml", "renames.yml"):
                continue
            path = os.path.join(root, f)
            rel = os.path.relpath(path, os.path.join(HERE, name)).replace("\\", "/")
            problems += check_yaml(path, rel)
    return problems


def main():
    names = sys.argv[1:] or present_plugins()
    total = 0
    for name in names:
        problems = check(name)
        print(f"\n=== {name}: {len(problems)} in Italian" if problems else f"\n=== {name}: English")
        for rel, kind, ident, hits in problems:
            print(f"  {rel}  {kind} «{ident}» -> Italian: {', '.join(hits)}")
        total += len(problems)
    print(f"\nTOTAL config keys/names not in English: {total}")
    return 1 if total else 0


if __name__ == "__main__":
    sys.exit(main())
