# -*- coding: utf-8 -*-
"""
English-code check for the WEBSITE (JS and PHP), companion to check_english.py.

Why a separate one: the plugins' check scans Java structure (file / package / top-level type names)
of a fixed tree. The website is different: no packages, a lot of legacy code still in Italian from the
old convention, and the thing that actually slips back to Italian there is FUNCTION names
(`scriviMenu`, `ricaricaSulServer`...). Flagging every Italian local variable in the legacy files would
drown the signal, so — like the Java check, which deliberately leaves methods and locals alone — this
one checks only the STRUCTURE that must be English: function names, plus PHP class/interface/trait/enum
names.

Scope, on purpose, is the DIFF: only the lines ADDED versus HEAD (staged or not). That way it catches
new Italian without demanding the whole legacy website be converted first; and touching a legacy Italian
function turns its declaration into an added line, which nudges it English on the next edit.

The Italian word list lives in check_english.py and is shared — grow it there.

Usage:
    python plugins-src/check_english_web.py          # only the added lines vs HEAD
    python plugins-src/check_english_web.py --all     # scan every website JS/PHP file (audit; still exits 1 if any)

Exits 1 if it found anything, so it can be wired to a hook or the build.
"""
import os
import re
import subprocess
import sys

from check_english import italian_words_in

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
WEB = os.path.join(ROOT, "website")

# Files we never judge: third-party bundles and anything minified (not ours, and not readable anyway).
SKIP = re.compile(r"(^|/)(vendor|node_modules)/|\.min\.(js|css)$")

# A named function, in JS or PHP: `function name(...)`, plus JS `name = function` / `name: function`.
FUNC = re.compile(r"\bfunction\s+([A-Za-z_$][\w$]*)\s*\(")
FUNC_EXPR = re.compile(r"([A-Za-z_$][\w$]*)\s*[:=]\s*function\b")
# PHP top-level types (the website has no packages; classes are the structural names that matter).
PHP_TYPE = re.compile(r"\b(class|interface|trait|enum)\s+([A-Za-z_]\w*)")


def names_in_line(line, is_php):
    """Every declared function/type name on one line of source."""
    names = []
    for m in FUNC.finditer(line):
        names.append(("function", m.group(1)))
    for m in FUNC_EXPR.finditer(line):
        names.append(("function", m.group(1)))
    if is_php:
        for m in PHP_TYPE.finditer(line):
            names.append((m.group(1), m.group(2)))
    return names


def is_web_source(path):
    return (path.endswith(".js") or path.endswith(".php")) and not SKIP.search(path)


def problems_in(path_rel, line):
    is_php = path_rel.endswith(".php")
    out = []
    for kind, name in names_in_line(line, is_php):
        hits = italian_words_in(name)
        if hits:
            out.append((path_rel, kind, name, hits))
    return out


def added_lines_vs_head():
    """(relative_path, added_line) for every added line in website JS/PHP, vs HEAD."""
    try:
        diff = subprocess.run(
            ["git", "-C", ROOT, "diff", "HEAD", "-U0", "--", "website"],
            capture_output=True, text=True, encoding="utf-8", errors="replace", check=True,
        ).stdout or ""
    except (subprocess.CalledProcessError, FileNotFoundError):
        return []
    out = []
    current = None
    for line in diff.splitlines():
        if line.startswith("+++ b/"):
            current = line[6:]
        elif line.startswith("+") and not line.startswith("+++") and current and is_web_source(current):
            out.append((current, line[1:]))
    return out


def all_web_lines():
    """(relative_path, line) for every line of every website JS/PHP file — the --all audit."""
    out = []
    for root, _, files in os.walk(WEB):
        for f in files:
            path = os.path.join(root, f)
            rel = os.path.relpath(path, ROOT).replace("\\", "/")
            if not is_web_source(rel):
                continue
            for line in open(path, encoding="utf-8", errors="ignore").read().splitlines():
                out.append((rel, line))
    return out


def main():
    scan_all = "--all" in sys.argv[1:]
    lines = all_web_lines() if scan_all else added_lines_vs_head()

    problems = []
    for rel, line in lines:
        problems.extend(problems_in(rel, line))

    where = "every website JS/PHP file" if scan_all else "the changed website lines"
    if problems:
        print(f"=== website: {len(problems)} name(s) in Italian (in {where})")
        for rel, kind, name, hits in problems:
            print(f"  {rel}  {kind} «{name}» -> Italian words: {', '.join(hits)}")
        print("\nRename them in English (comments and on-screen text stay Italian).")
        return 1
    print(f"=== website: English (checked {where})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
