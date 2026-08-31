# -*- coding: utf-8 -*-
"""
Gap check between config and documentation, for every Magix plugin.

Why it exists: the rule is that every config key must be explained (a comment above the key, accepted
values included) and that no dead or undocumented keys are left behind. As long as the check was
"remember to look", the gaps slipped in anyway. This script lists them in ten seconds.

(Code identifiers and comments are in English, per the project rule; code and output alike.)

Usage:
    python plugins-src/check_config.py            # every plugin
    python plugins-src/check_config.py MagixAuth  # a single one

What it reports, for each .yml config file:
  [1] key WITHOUT A COMMENT above                  -> nobody knows what it does
  [2] string key whose OTHER accepted VALUES appear in the code but not in the comment
  [3] key READ BY THE CODE but missing from the file  -> the default is invisible to whoever configures
  [4] key IN THE FILE but never read by the code   -> leftover, or a typo in the name
  [6] HAND-WRITTEN NUMBER in a guide that matches a config value
                                                   -> use the placeholder, or the guide will lie
  [7] MODE (a key holding one word out of several) that the player tutorial does not tell with a
      {{if:key=value}} block  -> changing mode leaves the guide on the old one.
      If the mode is NOT visible in game, write [staff only] in the key comment.

Exits with code 1 if it found anything: can be wired to a hook or to the build.
"""
import os, re, sys

HERE = os.path.dirname(os.path.abspath(__file__))


def present_plugins():
    """
    The plugins to check, DISCOVERED automatically: every folder here that has a pom.xml and its
    sources. No hand-written list — a new Magix plugin joins the check the moment it exists, without
    anyone having to remember to add it here.
    """
    out = []
    for name in sorted(os.listdir(HERE)):
        folder = os.path.join(HERE, name)
        if os.path.isfile(os.path.join(folder, "pom.xml")) \
                and os.path.isdir(os.path.join(folder, "src", "main", "resources")):
            out.append(name)
    return out


PLUGINS = present_plugins()
# Keys that read by themselves or do not belong to the plugin: not to be reported.
IGNORE_UNREAD = re.compile(r"^(storage\.|mariadb\.|sqlite\.|ranks|leader|forbidden-words|messages)")


def file_keys(path):
    """Keys of the .yml with: line, dotted path, raw value, and whether they have a comment above."""
    with open(path, encoding="utf-8") as f:
        lines = f.read().split("\n")
    out = []
    stack = []       # (indent, name)
    comments = {}    # section path -> comment above it
    for i, line in enumerate(lines):
        m = re.match(r"^(\s*)([A-Za-z0-9_\-]+):(.*)$", line)
        if not m:
            continue
        indent, name, rest = len(m.group(1)), m.group(2), m.group(3).strip()
        while stack and stack[-1][0] >= indent:
            stack.pop()
        key_path = ".".join([p[1] for p in stack] + [name])
        ancestors = [".".join([p[1] for p in stack][:k + 1]) for k in range(len(stack))]
        stack.append((indent, name))
        # Comment on the lines IMMEDIATELY above, with no blank lines in between: a blank line
        # separates, and whatever is higher up talks about something else. (Without this rule the file
        # header was taken as the comment of the first key, and the gap stayed hidden.)
        comment, j = [], i - 1
        while j >= 0 and lines[j].strip().startswith("#"):
            comment.insert(0, lines[j].strip().lstrip("#").strip())
            j -= 1
        comment = " ".join(comment)
        # The INLINE comment counts too: "per: day   # hour | day | week" explains the key perfectly.
        if "#" in rest:
            comment = (comment + " " + rest.split("#", 1)[1]).strip()
        if rest == "" or rest.startswith("#"):
            comments[key_path] = comment   # it is a section: the comment covers what it contains
            # The section still enters the list: the code can read it whole
            # (getConfigurationSection("claims.cost")), and without this it would not appear to exist.
            out.append({"line": i + 1, "key": key_path, "value": "",
                        "comment": comment, "section": True})
            continue
        if not comment:
            # A key inside an already-explained section is documented: the comment is on the block
            # (e.g. the seasons, which are four identical lines under a single header).
            for a in reversed(ancestors):
                if comments.get(a):
                    comment = comments[a]
                    break
        out.append({"line": i + 1, "key": key_path, "value": rest,
                    "comment": comment, "section": False})
    return out


def plugin_code(folder):
    text = []
    for root, _, files in os.walk(os.path.join(folder, "src", "main", "java")):
        for f in files:
            if f.endswith(".java"):
                with open(os.path.join(root, f), encoding="utf-8", errors="ignore") as fh:
                    text.append(fh.read())
    return "\n".join(text)


# Text that players and staff READ: the illustrated tutorial and the hand-written sentences in the
# guides (section/intro/failure/never of StaffGuide). This is where a hand-copied number does harm.
HTML_TAG = re.compile(r"<[^>]+>", re.S)
STYLE = re.compile(r"<style.*?</style>|<script.*?</script>", re.S)
CHAPTER_NUMBER = re.compile(r'<span class="n">[^<]*</span>')
TOC = re.compile(r'<div class="toc">.*?</div>', re.S)
# The boxes that mimic the in-game screen are EXAMPLES ("Members: 3/5"): made-up numbers to show what
# a screen looks like, not config values.
EXAMPLES = re.compile(r'<div class="chat">.*?</div>', re.S)
# Example commands have their own numbers too ("/f help 3" is the help page).
COMMANDS = re.compile(r'<span class="cmd">.*?</span>', re.S)
NUMBER = re.compile(r"(?<![\w.&#])(\d+(?:[.,]\d+)?)(?![\w.])")


def guide_texts(folder):
    """
    The text PLAYERS read: the illustrated tutorial. It is the only place where hand-written sentences
    remain — the staff guide no longer has numbers of its own (the settings list is generated by
    StaffGuide from the live config, value and comment included), so there is no point digging there.
    """
    tut = os.path.join(folder, "docs", "build_tutorial.py")
    if not os.path.isfile(tut):
        return []
    with open(tut, encoding="utf-8") as f:
        html = f.read()
    html = STYLE.sub(" ", html)
    html = CHAPTER_NUMBER.sub(" ", html)   # "8" in the title bubble is not a config value
    html = TOC.sub(" ", html)              # nor the table-of-contents numbering
    html = EXAMPLES.sub(" ", html)         # nor the made-up numbers of the example screens
    html = COMMANDS.sub(" ", html)         # nor the ones inside example commands
    return [("docs/build_tutorial.py", HTML_TAG.sub(" ", html))]


def hand_written_numbers(folder, keys):
    """
    [6] A number that appears in a guide and is ALSO the value of a config key: almost always it was
    hand-copied, and at the next config change the guide starts to lie. 0/1/2 and percentages up to two
    digits equal by chance are ignored: they only make noise.
    """
    values = {}
    for c in keys:
        v = c["value"].split("#")[0].strip().strip('"\'')
        if re.fullmatch(r"\d+(?:\.\d+)?", v or "") and float(v) >= 3:
            values.setdefault(v.rstrip("0").rstrip(".") if "." in v else v, []).append(c["key"])
    problems = []
    for filename, text in guide_texts(folder):
        # a number already written as a placeholder is not hand-written: remove it before looking
        text = re.sub(r"\{\{[^}]{1,80}\}\}", " ", text)
        seen = set()
        for m in NUMBER.finditer(text):
            n = m.group(1).replace(",", ".")
            n = n.rstrip("0").rstrip(".") if "." in n else n
            if n in values and n not in seen:
                seen.add(n)
                problems.append((filename, 0, "[6] hand-written number (" + n + "), matches",
                                 ", ".join(values[n][:3])))
    return problems


def derived_methods(code):
    """The body of the methods that build the derived guide texts (the ones with .extra(...))."""
    out = []
    header = re.compile(r"^    (?:private|public|protected)[^\n]*\{$", re.M)
    cuts = [m.start() for m in header.finditer(code)] + [len(code)]
    for i in range(len(cuts) - 1):
        body = code[cuts[i]:cuts[i + 1]]
        if ".extra(" in body:
            out.append(body)
    return "\n".join(out)


def modes_not_told(folder, keys, code):
    """
    [7] The case numbers do not cover: a key holding one WORD out of several (map.mode = chat | item)
    changes what the player sees, but no number changes — so check [6] does not notice and the tutorial
    keeps telling yesterday's mode. It happened for real: map.mode set to "chat" and chapter 8 still
    explaining the ITEM map.

    The rule: if a mode is visible in game, the tutorial must handle it with a {{if:key=value}} block.
    If it is not visible (storage, ports, machine stuff), declare it by writing [staff only] in the key
    comment — which is useful documentation for whoever configures anyway.
    """
    tut = os.path.join(folder, "docs", "build_tutorial.py")
    if not os.path.isfile(tut):
        return []          # a plugin without a player tutorial has nothing to tell
    with open(tut, encoding="utf-8") as f:
        text = f.read()
    problems = []
    for c in keys:
        if c["section"]:
            continue
        value = c["value"].split("#")[0].strip().strip('"\'')
        if not re.fullmatch(r"[a-z][a-z0-9_\-]{1,24}", value or "") or value in ("true", "false"):
            continue
        if "[staff only]" in c["comment"] or "[solo staff]" in c["comment"]:
            continue
        base = c["key"].split(".")[-1]
        nearby = re.findall(r'"' + re.escape(c["key"]) + r'"[^;]{0,200}', code)
        nearby += re.findall(r'get\w+\(\s*"[^"]*' + re.escape(base) + r'"\)[^;]{0,200}', code)
        alternatives = set()
        for chunk in nearby:
            for lit in re.findall(r'(?:case\s+|equals(?:IgnoreCase)?\(\s*)"([a-z][a-z0-9_\-]{1,24})"', chunk):
                if lit != value and lit != c["key"] and not lit.endswith(base):
                    alternatives.add(lit)
        # Another LEGITIMATE way to tell it: a DERIVED text built in Java (e.g. the offline-loss
        # sentence, which changes shape with the chosen period). It counts ONLY if the key is read
        # INSIDE the method that builds those texts: a first attempt looked "around there" and it was
        # enough for the key to appear on a nearby line (map.mode appears in the settings table, a few
        # lines above) to pass for told. Tried: with the wide window the check said "clean" even on
        # yesterday's wrong tutorial.
        told = ("{{if:" + c["key"] + "=") in text or ("{{se:" + c["key"] + "=") in text \
            or ('"' + c["key"] + '"') in derived_methods(code)
        # The FALLBACK written in the code is a possible value too: getString("map.mode", "item") says
        # the "item" mode exists even if no case/equals names it. Without this the check stayed silent
        # exactly on the case it was born from (tried: it said "clean").
        for fb in re.findall(r'get\w+\(\s*"' + re.escape(c["key"]) + r'"\s*,\s*"([a-z][a-z0-9_\-]{1,24})"', code):
            if fb != value:
                alternatives.add(fb)
        if alternatives and not told:
            problems.append(("docs/build_tutorial.py", c["line"],
                             "[7] mode not told in the tutorial (" + "|".join(sorted(alternatives | {value})) + ")",
                             c["key"]))
    return problems


def check(name):
    folder = os.path.join(HERE, name)
    resources = os.path.join(folder, "src", "main", "resources")
    if not os.path.isdir(resources):
        return []
    code = plugin_code(folder)
    read = set(re.findall(r'get\w+\(\s*"([A-Za-z0-9_.\-]+)"', code))
    problems = []

    for f in sorted(os.listdir(resources)):
        if not f.endswith(".yml") or f in ("plugin.yml", "messages.yml"):
            continue
        path = os.path.join(resources, f)
        keys = file_keys(path)
        names = {c["key"] for c in keys}

        for c in keys:
            if c["section"]:
                continue   # a section is judged by the keys it contains
            if not c["comment"]:
                problems.append((f, c["line"], "[1] no comment above", c["key"]))
                continue
            # [2] alternative values: the key holds a word, and the code compares it against ANOTHER
            # word for the same key -> the comment must name it.
            value = c["value"].split("#")[0].strip().strip('"\'')
            if re.fullmatch(r"[a-z][a-z0-9_\-]{1,24}", value or "") and value not in ("true", "false"):
                base = c["key"].split(".")[-1]
                nearby = re.findall(r'"' + re.escape(c["key"]) + r'"[^;]{0,200}', code)
                nearby += re.findall(r'get\w+\(\s*"[^"]*' + re.escape(base) + r'"\)[^;]{0,200}', code)
                alternatives = set()
                for chunk in nearby:
                    # Only REAL comparisons with a literal: case "x", equals("x"), equalsIgnoreCase("x").
                    # Without this filter the database column names and nearby keys, which are not
                    # alternative values at all, ended up in the list.
                    for lit in re.findall(r'(?:case\s+|equals(?:IgnoreCase)?\(\s*)"([a-z][a-z0-9_\-]{1,24})"', chunk):
                        if lit != value and lit != c["key"] and not lit.endswith(base):
                            alternatives.add(lit)
                missing = sorted(a for a in alternatives if a not in c["comment"])
                if missing:
                    problems.append((f, c["line"], "[2] other accepted values not explained: " + ", ".join(missing[:4]),
                                     c["key"]))

        if f == "config.yml":
            for k in sorted(read):
                if k.endswith("."):
                    continue   # prefix built in the code ("map.colors." + name), not a key
                if "." in k and k not in names and not IGNORE_UNREAD.match(k) \
                        and k.split(".")[0] in {n.split(".")[0] for n in names}:
                    problems.append((f, 0, "[3] read by the code, missing from the file", k))
            for c in keys:
                k = c["key"]
                # A key can be read with the path BUILT in the code ("territory-titles." + node +
                # ".title"): in that case the parent prefix followed by a concatenation appears in the
                # source, and the key is alive even if its full name is nowhere to be found.
                parent = k.rsplit(".", 1)[0]
                if parent != k and ('"' + parent + '." +') in code:
                    continue
                if k not in read and k.split(".")[-1] not in code and not IGNORE_UNREAD.match(k):
                    problems.append((f, c["line"], "[4] in the file but never read by the code", k))
            problems += hand_written_numbers(folder, keys)
            problems += modes_not_told(folder, keys, code)
    return problems


# Classes the plugins SHARE: they are copies, not a library, so they must stay identical (apart from
# the package line). If they diverge, a fix made in one plugin never reaches the others.
COMMON_CLASSES = ["ConfigValues.java", "DurationText.java", "StaffGuide.java", "Help.java"]


def util_path(name):
    return os.path.join(HERE, name, "src", "main", "java", "com", "teolo", name.lower(), "util")


def body_without_package(path):
    """The file without the package line: that is the only difference allowed between the copies."""
    with open(path, encoding="utf-8") as f:
        return "\n".join(r for r in f.read().split("\n") if not r.startswith("package "))


def check_common_classes(names):
    """Reports the common classes that differ from one plugin to another (or that are missing)."""
    problems = []
    for cls in COMMON_CLASSES:
        versions = {}
        for name in names:
            p = os.path.join(util_path(name), cls)
            if os.path.isfile(p):
                versions.setdefault(body_without_package(p), []).append(name)
        if len(versions) <= 1:
            continue
        # The "good" copy is the most widespread one; the others need realigning.
        groups = sorted(versions.values(), key=len, reverse=True)
        reference = groups[0]
        for different in groups[1:]:
            problems.append(("common classes", 0,
                             f"[5] {cls} DIFFERS from {', '.join(reference)}",
                             ", ".join(different)))
    return problems


def main():
    names = sys.argv[1:] or PLUGINS
    total = 0
    for name in names:
        problems = check(name)
        print(f"\n=== {name}: {len(problems)} to fix" if problems else f"\n=== {name}: clean")
        for f, line, kind, key in problems:
            print(f"  {f}:{line or '?'}  {kind}  -> {key}")
        total += len(problems)

    if len(names) > 1:
        common = check_common_classes(names)
        print(f"\n=== common classes: {len(common)} misaligned" if common else "\n=== common classes: aligned")
        for _, _, kind, who in common:
            print(f"  {kind}  -> in: {who}")
        total += len(common)

    print(f"\nTOTAL: {total}")
    return 1 if total else 0


if __name__ == "__main__":
    sys.exit(main())
