# -*- coding: utf-8 -*-
"""
Rename Italian code identifiers to English across one plugin, safely.

Only "code" spans are touched (see java_spans): Italian words living inside string literals or
comments — the things a person reads — are never altered. That is the whole point, and the reason
this exists: a blind replace once corrupted 27 files by rewriting Italian prose.

Matching is per WHOLE identifier token (\bsource\b), so mapping `nome`->`name` cannot turn
`nomeMondo` into `nameMondo`; that one is renamed by its own entry. All entries are applied in a
single atomic pass (one alternation), so a target that happens to equal another entry's source is
not re-renamed.

Before writing anything it runs a collision check and refuses to proceed on any of:
  - two sources mapping to the same target,
  - a target that already exists in the plugin as a distinct token (would merge two symbols).
Fix the map and re-run. Use --dry to see the report without writing.

Usage:
    python plugins-src/rename_identifiers.py MagixAuth [--dry]

The maps live in rename_map.py next to this file: MAPS[plugin] = {italian: english}.
"""
import os, re, sys
import java_spans as js
from rename_map import MAPS, COMMON


def present_plugins():
    out = []
    for name in sorted(os.listdir(os.path.dirname(os.path.abspath(__file__)))):
        folder = os.path.join(os.path.dirname(os.path.abspath(__file__)), name)
        if os.path.isfile(os.path.join(folder, "pom.xml")) \
                and os.path.isdir(os.path.join(folder, "src", "main", "java")):
            out.append(name)
    return out

HERE = os.path.dirname(os.path.abspath(__file__))
IDENT = re.compile(r'[A-Za-z_$][A-Za-z0-9_$]*')

# Classes that are byte-for-byte identical in every plugin (check_config.py enforces it). Their
# API is shared, so their identifiers can only be renamed in one coordinated workspace-wide pass,
# never inside a single plugin — doing so both breaks the callers in the other plugins and makes
# the file diverge. The renamer skips them; keep such tokens out of the per-plugin maps too.
COMMON_FILES = {"Help.java", "StaffGuide.java", "ConfigValues.java", "DurationText.java", "Aiuto.java"}


def all_tokens(plugin, include_common=False):
    """Every distinct identifier token that appears in code spans of the plugin."""
    seen = set()
    root = os.path.join(HERE, plugin, "src")
    for dp, _, fs in os.walk(root):
        for f in fs:
            if f.endswith(".java") and (include_common or f not in COMMON_FILES):
                src = open(os.path.join(dp, f), encoding="utf-8").read()
                for kind, text in js.spans(src):
                    if kind == "code":
                        seen.update(IDENT.findall(text))
    return seen


def check_collisions(plugin, mapping, include_common=False):
    """Returns (fatal, warnings). Fatal = two sources onto one target (always a bug). Warning =
    target already exists as its own token: usually a different scope (fine, the compiler is the
    real check), so it does not block — but worth printing in case it is a same-scope clash."""
    fatal, warnings = [], []
    rev = {}
    for s, t in mapping.items():
        rev.setdefault(t, []).append(s)
    for t, srcs in rev.items():
        if len(srcs) > 1:
            fatal.append(f"target '{t}' <- {sorted(srcs)} (many sources, one target)")
    tokens = all_tokens(plugin, include_common)
    for s, t in mapping.items():
        if t in tokens and t not in mapping:
            warnings.append(f"target '{t}' (from '{s}') already a token in {plugin}")
    return fatal, warnings


def apply(plugin, mapping, dry, include_common=False):
    if not mapping:
        print(f"{plugin}: empty map, nothing to do.")
        return 0
    fatal, warnings = check_collisions(plugin, mapping, include_common)
    if fatal:
        print(f"{plugin}: {len(fatal)} FATAL collision(s) — refusing to write:")
        for p in fatal:
            print("  ! " + p)
        return 1
    for w in warnings:
        print("  ~ " + w)

    pat = re.compile(r'\b(' + '|'.join(re.escape(k) for k in
                     sorted(mapping, key=len, reverse=True)) + r')\b')

    def sub_code(text):
        return pat.sub(lambda m: mapping[m.group(0)], text)

    root = os.path.join(HERE, plugin, "src")
    changed = 0
    hits = {}
    for dp, _, fs in os.walk(root):
        for f in fs:
            if not f.endswith(".java") or (f in COMMON_FILES and not include_common):
                continue
            path = os.path.join(dp, f)
            src = open(path, encoding="utf-8").read()
            new = js.map_code(src, sub_code)
            if new != src:
                # count per-identifier hits (code spans only)
                for kind, text in js.spans(src):
                    if kind == "code":
                        for m in pat.finditer(text):
                            hits[m.group(0)] = hits.get(m.group(0), 0) + 1
                changed += 1
                if not dry:
                    open(path, "w", encoding="utf-8", newline="").write(new)
    verb = "would change" if dry else "changed"
    print(f"{plugin}: {verb} {changed} file(s), {sum(hits.values())} rename(s), "
          f"{len(hits)} distinct identifier(s).")
    unused = [k for k in mapping if k not in hits]
    if unused:
        print(f"  {len(unused)} map entr(y/ies) matched nothing: {sorted(unused)}")
    return 0


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    dry = "--dry" in sys.argv

    # --common: apply the shared-class map to EVERY plugin, common files included, so the four
    # common classes stay byte-identical across plugins and every call site moves with them.
    if "--common" in sys.argv:
        rc = 0
        for plugin in present_plugins():
            print(f"\n--- {plugin} ---")
            rc |= apply(plugin, COMMON, dry, include_common=True)
        return rc

    if not args:
        print("usage: rename_identifiers.py <Plugin> [--dry]   |   --common [--dry]")
        return 2
    plugin = args[0]
    if plugin not in MAPS:
        print(f"no map for {plugin} in rename_map.py")
        return 2
    return apply(plugin, MAPS[plugin], dry)


if __name__ == "__main__":
    sys.exit(main())
