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
  [8] command argument that MagixLanguage's argument-glossary.yml does not know: a word inside
      < > or [ ] of a help line ("/f join <fazione> :: ..."), or inside < > of any other message
      ("Uso: /f join <fazione>") -> it would stay in Italian in every other language.
      Add it to "words" (argument name, translated) or "keep" (typed as is: on|off, clear...).
  [9] PlaceholderAPI placeholder resolved by the code but missing from the staff guide: every class
      that extends PlaceholderExpansion keeps a DOCS list (placeholder, what it shows) that the
      plugin passes to StaffGuide.placeholders(...). A literal the expansion answers to (case "x",
      equals("x"), startsWith("x_")...) that no DOCS entry contains, a class without DOCS, or a
      DOCS never passed to the guide -> the staff cannot know the placeholder exists.
  [10] config.yml key that the player tutorial does not tell: every key of a plugin with a
      docs/build_tutorial.py must be either USED by the tutorial ({{cfg:key}}, {{if:key=...}}... or read
      by name inside the Java method that builds the derived guide texts, the one with .extra(...)) or
      declared NOT for players by writing [solo staff] / [staff only] in its comment or in the comment
      of a section above it. The tutorial tells only what touches the players; everything else is
      declared, so a new key forces the choice instead of vanishing in silence.
  [11] HAND-WRITTEN NUMBER in the staff guide (the texts of StaffGuide.create(...) ... .write()) that
      matches a config value -> use {{cfg:key}} (the guide is bound to the config with .values(...)).
      A real coincidence (a texture size, an example) goes in STAFF_NUMBER_OK with a bit of its text.
  [12] ACCENT WRITTEN WITH AN APOSTROPHE ("piu'", "e'", "perche'") in what people read: YAML values
      and comments, Java string literals. Write the accented letter (più, è, perché). "po'" is right
      as it is, and a word between single quotes ('dai_soldi') is not an accent.
  [13] COMMANDS WITHOUT THE MAGIX COMMAND LIST: a plugin that declares commands in plugin.yml shows
      them like every other Magix plugin (plugins-src/STILE-MAGIX.md section 3): the shared util/Help
      (Help.show(...) called by the command, so "/<cmd>", "/<cmd> help [page]", "?" and the page
      number alone open the list) and the entries in messages.yml under help.sections, with the
      frame texts under help.chrome (a "usage" message for ONE wrong argument is fine).

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
IGNORE_UNREAD = re.compile(
    r"^(storage\.|mariadb\.|sqlite\.|ranks|leader|forbidden-words|value-blocks|messages|scoreboards)")

# Files with a FIXED SCHEMA — config.yml, modules.yml and the per-feature files (tablist.yml...) —
# are the ones where every key is read by the code, so [3] and [4] apply to them. The exceptions are
# the STAFF CATALOGS, listed here: menus/*.yml is a subfolder (never listed), sanctions.yml holds
# entries the staff adds, items.yml/glyphs.yml (MagixPack) hold the ids of custom items/glyphs the
# staff defines, and currencies.yml (MagixEssentials) holds the ids of the currencies the staff
# creates — all read dynamically (cfg.getKeys), never as a literal string in the code.
# renames.yml is not a config at all: it maps old key paths to new ones.
CATALOGS = {"sanctions.yml", "renames.yml", "items.yml", "glyphs.yml", "currencies.yml"}


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
    # The file's own encoding declaration ("# -*- coding: utf-8 -*-", encoding="utf-8") is not a
    # hand-written config number: without this its "8" collides with any config value that happens to be 8.
    html = re.sub(r"utf-8", " ", html, flags=re.I)
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


STAFF_ONLY = ("[staff only]", "[solo staff]")


def keys_not_told(folder, keys, code):
    """
    [10] The tutorial tells players the rules that config.yml sets. A key it does not use, and that is
    not declared staff-only, is a rule the players cannot read anywhere: it happened for real with the
    price of /f create, changed in the config and missing from the tutorial for a day.

    Told = the tutorial uses it in a placeholder or an {{if:}} block, or the method building the derived
    texts reads it (or one of its parent sections) by name. Staff-only = [solo staff] in the comment of
    the key or of any section above it.
    """
    tut = os.path.join(folder, "docs", "build_tutorial.py")
    if not os.path.isfile(tut):
        return []
    with open(tut, encoding="utf-8") as f:
        text = f.read()
    used = set(re.findall(r"\{\{(?:cfg|secondi|ore|percento|simbolo|se|if):([A-Za-z0-9_./\-]+?)(?:!?=[^}]*)?(?:\|[^}]*)?\}\}", text))
    derived = derived_methods(code)
    sections = {c["key"]: c["comment"] for c in keys if c["section"]}
    problems = []
    for c in keys:
        if c["section"]:
            continue
        k = c["key"]
        parts = k.split(".")
        chain = [".".join(parts[:i]) for i in range(1, len(parts) + 1)]
        own = c["comment"] if not c["section"] else ""
        if any(t in own for t in STAFF_ONLY) or \
                any(t in sections.get(a, "") for a in chain[:-1] for t in STAFF_ONLY):
            continue
        # the key itself by name, or a parent section read WHOLE (getConfigurationSection("value-blocks"),
        # getMapList("ranks")): a bare "chat" literal elsewhere in the method is not a reading of chat.*
        if k in used or ('"' + k + '"') in derived or \
                any(re.search(r'get(?:ConfigurationSection|MapList|StringList|List)\(\s*"' + re.escape(a) + '"', derived)
                    for a in chain[:-1]):
            continue
        problems.append(("docs/build_tutorial.py", c["line"],
                         "[10] config key neither told in the tutorial nor marked [solo staff]", k))
    return problems


# [11] Numbers in the staff guide that happen to equal a config value but have nothing to do with
# it: (plugin, a piece of the sentence). Checked by eye, one by one.
STAFF_NUMBER_OK = [
    ("MagixFactions", "powermax.20 = tetto 20"),        # example of the permission syntax
    ("MagixFactions", "servono almeno 10 righe vuote"),  # tablist layout, from the logo height
    ("MagixFactions", "le 80 caselle finte"),            # fixed texture of the tablist
    ("MagixFactions", "di serie 6 fazioni da 2-5"),       # defaults of /mf admin fake create
    ("MagixGuard", "40:40 mgviolation"),                 # example line of another plugin's file
]
STRING_LITERAL = re.compile(r'"(?:[^"\\\n]|\\.)*"')
NUMBER_IN_TEXT = re.compile(r"(?<![\w.&#])(\d+(?:[.,]\d+)?)(?![\w.])")


def staff_guide_numbers(name, keys):
    """[11] see the header: hand-written numbers in the staff guide that match a config value."""
    values = {}
    for c in keys:
        v = c["value"].split("#")[0].strip().strip('"\'')
        if re.fullmatch(r"\d+(?:\.\d+)?", v or "") and float(v) >= 3:
            values.setdefault(v, []).append(c["key"])
    problems = []
    for root, _, files in os.walk(os.path.join(HERE, name, "src", "main", "java")):
        for f in files:
            if not f.endswith(".java") or f == "StaffGuide.java":
                continue
            with open(os.path.join(root, f), encoding="utf-8", errors="ignore") as fh:
                src = fh.read()
            start = src.find("StaffGuide.create(")
            if start < 0:
                continue
            end = src.find(".write()", start)
            for lit in STRING_LITERAL.findall(src[start:end if end > 0 else len(src)]):
                if any(p == name and piece in lit for p, piece in STAFF_NUMBER_OK):
                    continue
                text = re.sub(r"\{\{[^}]{1,80}\}\}|`[^`]*`", " ", lit)
                for n in NUMBER_IN_TEXT.findall(text):
                    if n in values:
                        problems.append((f, 0, "[11] hand-written number (" + n + ") in the staff guide, matches",
                                         ", ".join(values[n][:3])))
    return problems


ACCENT_APOSTROPHE = re.compile(r"(?<![A-Za-zÀ-ÿ'])([A-Za-z]*[aeiouAEIOU])'(?=[\s.,:;!?)\"&<>{}\[\]§*~/|-]|$)")
ACCENT_OK = {"po", "de", "mo", "to", "pe", "ca", "fa", "sta", "va", "di", "tu", "be", "ma", "fe", "pie"}
SINGLE_QUOTED = re.compile(r"(?<![A-Za-zÀ-ÿ])'[^'\n\"]{1,80}'(?![A-Za-zÀ-ÿ])")
TRANSLATIONS = ("en.yml", "es.yml", "de.yml")


def apostrophe_accents(text):
    text = SINGLE_QUOTED.sub(" ", text)
    out = [w for w in ACCENT_APOSTROPHE.findall(text) if w.lower() not in ACCENT_OK]
    out += re.findall(r"[cC]'[eE]'(?=[\s.,:;!?)\"&<]|$)", text)
    return out


def accents_with_apostrophe(name):
    """[12] see the header: accents written with an apostrophe in what people read."""
    problems = []
    base = os.path.join(HERE, name, "src", "main")
    for root, _, files in os.walk(base):
        for f in files:
            path = os.path.join(root, f)
            if f.endswith(".yml") and f not in ("plugin.yml", "value-fixes.yml", "renames.yml") + TRANSLATIONS:
                with open(path, encoding="utf-8", errors="ignore") as fh:
                    for i, line in enumerate(fh, 1):
                        body = line
                        m = re.match(r"^(\s*(?:-\s+)?(?:[^\s:#\"'][^:#]*:[ \t]*)?)'((?:[^']|'')*)'(.*)$", line)
                        if m:   # a single-quoted YAML value: its apostrophes are written twice
                            body = m.group(2).replace("''", "\x01") + m.group(3)
                        for w in apostrophe_accents(body):
                            problems.append((os.path.relpath(path, base), i, "[12] accent written with an apostrophe", w + "'"))
            elif f.endswith(".java"):
                with open(path, encoding="utf-8", errors="ignore") as fh:
                    for i, line in enumerate(fh, 1):
                        if line.strip().startswith(("//", "*", "/*")):
                            continue
                        for lit in STRING_LITERAL.findall(line):
                            for w in apostrophe_accents(lit):
                                problems.append((f, i, "[12] accent written with an apostrophe", w + "'"))
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
        # value-fixes.yml non e' configurazione: e' l'elenco interno dei testi di serie da
        # aggiornare sul server (vedi MagixGuard.applyValueFixes), nessuno lo regola.
        if not f.endswith(".yml") or f in ("plugin.yml", "messages.yml", "value-fixes.yml"):
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

        if f not in CATALOGS:
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
            if f == "config.yml":
                problems += keys_not_told(folder, keys, code)
                problems += staff_guide_numbers(name, keys)
    problems += accents_with_apostrophe(name)
    problems += help_page_missing(name, code)
    problems += help_arguments_unknown(name)
    problems += placeholders_undocumented(name)
    return problems


def help_page_missing(name, code):
    """[13] see the header: a plugin with commands shows them with the shared Help page."""
    resources = os.path.join(HERE, name, "src", "main", "resources")
    plugin_yml = os.path.join(resources, "plugin.yml")
    if not os.path.isfile(plugin_yml):
        return []
    text = open(plugin_yml, encoding="utf-8").read()
    block = re.search(r"^commands:\s*\n((?:[ \t]+.*\n?|\s*\n)+)", text, re.M)
    if not block or not re.search(r"^  [A-Za-z0-9_-]+:", block.group(1), re.M):
        return []
    problems = []
    if "Help.show(" not in code:
        problems.append(("plugin.yml", 0, "[13] commands without the Magix command list", "Help.show(...) never called"))
    messages = os.path.join(resources, "messages.yml")
    msg = open(messages, encoding="utf-8").read() if os.path.isfile(messages) else ""
    if not re.search(r"^help:\s*$", msg, re.M) or not re.search(r"^  sections:\s*$", msg, re.M) \
            or not re.search(r"^  chrome:\s*$", msg, re.M):
        problems.append(("messages.yml", 0, "[13] commands without the Magix command list", "help.chrome / help.sections"))
    return problems


PLACEHOLDER_LITERAL = re.compile(
    r'(?:case\s+|equals(?:IgnoreCase)?\(\s*|startsWith\(\s*)"([^"]+)"|"([^"]+)"\.equals(?:IgnoreCase)?\(')


def method_bodies(source, names):
    """The bodies of the named methods, found by matching braces from the signature."""
    bodies = []
    for m in re.finditer(r'\b(?:' + "|".join(names) + r')\s*\([^)]*\)\s*\{', source):
        depth, i = 1, m.end()
        while i < len(source) and depth:
            depth += {"{": 1, "}": -1}.get(source[i], 0)
            i += 1
        bodies.append(source[m.end():i])
    return "\n".join(bodies)


def placeholders_undocumented(name):
    """
    [9] Every placeholder a plugin exposes must be in its staff guide. The list lives next to the
    code that resolves them (DOCS in the expansion class), and the plugin passes it to
    StaffGuide.placeholders(...): before this rule no guide listed a single placeholder.
    """
    root = os.path.join(HERE, name, "src", "main", "java")
    if not os.path.isdir(root):
        return []
    code = plugin_code(os.path.join(HERE, name))
    problems = []
    for folder, _, files in os.walk(root):
        for f in files:
            if not f.endswith(".java"):
                continue
            with open(os.path.join(folder, f), encoding="utf-8") as fh:
                source = fh.read()
            if "extends PlaceholderExpansion" not in source:
                continue
            docs = re.search(r'\bDOCS\s*=\s*\{(.*?)\};', source, re.S)
            if not docs:
                problems.append((f, 0, "[9] PlaceholderExpansion without a DOCS list for the staff guide", f))
                continue
            documented = " ".join(re.findall(r'%[^%\s"]+%', docs.group(1))).lower()
            if not re.search(r'\.placeholders\([^)]*\b' + re.escape(f[:-5]) + r'\.DOCS', code):
                problems.append((f, 0, "[9] DOCS never passed to StaffGuide.placeholders(...)", f[:-5]))
            body = method_bodies(source, ["onRequest", "onPlaceholderRequest"])
            for a, b in PLACEHOLDER_LITERAL.findall(body):
                literal = (a or b).lower()
                if literal not in documented:
                    problems.append((f, 0, "[9] placeholder resolved but missing from DOCS (staff guide)",
                                     literal))
    return problems


GLOSSARY = os.path.join(HERE, "MagixLanguage", "src", "main", "resources", "argument-glossary.yml")
_glossary_words = None


def glossary_words():
    """Every word argument-glossary.yml knows: the Italian side of "words" plus "keep"."""
    global _glossary_words
    if _glossary_words is None:
        _glossary_words = set()
        if os.path.isfile(GLOSSARY):
            with open(GLOSSARY, encoding="utf-8") as f:
                text = f.read()
            _glossary_words |= set(re.findall(r'\bit:\s*"([^"]+)"', text))
            keep = re.search(r'^keep:\s*\[(.*?)\]', text, re.S | re.M)
            if keep:
                _glossary_words |= set(re.findall(r'"([^"]+)"', keep.group(1)))
    return _glossary_words


ANGLE_ARGUMENT = re.compile(r'<(?:[^<>]|<[^<>]*>)*>')
ARGUMENT_WORD = re.compile(r'[^\W\d_][^\W_]*(?:-[^\W_]+)*')   # "blocchi-per-pixel" is one entry


def unknown_argument_words(syntax, known):
    """Words inside < > or [ ] of `syntax` the glossary does not know (same scan as HelpSyntax)."""
    phrases = sorted((k for k in known if " " in k), key=len, reverse=True)
    out, depth, i = [], 0, 0
    while i < len(syntax):
        ch = syntax[i]
        if ch in "<[":
            depth += 1
        elif ch in ">]" and depth > 0:
            depth -= 1
        if depth > 0 and ch.isalpha():
            phrase = next((p for p in phrases if syntax.startswith(p, i)
                           and not syntax[i + len(p):i + len(p) + 1].isalnum()), None)
            if phrase:
                i += len(phrase)
                continue
            word = ARGUMENT_WORD.match(syntax, i).group(0)
            if word not in known:
                out.append(word)
            i += len(word)
            continue
        i += 1
    return out


def help_arguments_unknown(name):
    """
    [8] Command arguments are translated by MagixLanguage's argument-glossary.yml, never by the machine
    translator: the words inside < > and [ ] of a help line's syntax, and every <...> in any other
    message ("Usage: /f join <fazione>"). A word the glossary does not know would silently stay in
    Italian in every other language.
    """
    path = os.path.join(HERE, name, "src", "main", "resources", "messages.yml")
    known = glossary_words()
    if not os.path.isfile(path) or not known:
        return []
    problems = []
    with open(path, encoding="utf-8") as f:
        for n, line in enumerate(f, 1):
            if line.lstrip().startswith("#"):
                continue
            words = []
            help_line = re.match(r'(\s*-\s*"\s*/[^"]*?)\s*::(.*)$', line)
            if help_line:
                words += unknown_argument_words(help_line.group(1), known)
                line = help_line.group(2)
            for segment in ANGLE_ARGUMENT.findall(line):
                words += unknown_argument_words(segment, known)
            for word in words:
                problems.append(("messages.yml", n,
                                 "[8] command argument not in MagixLanguage argument-glossary.yml", word))
    return problems


# Classes the plugins SHARE: they are copies, not a library, so they must stay identical (apart from
# the package line). If they diverge, a fix made in one plugin never reaches the others. Maps the
# file name to the package (relative to com.teolo.<plugin>) it lives in.
COMMON_CLASSES = {
    "ConfigAlign.java": "util", "ConfigValues.java": "util", "DurationText.java": "util",
    "StaffGuide.java": "util", "Help.java": "util",
    "Papi.java": "hook",
}


def class_path(name, pkg):
    return os.path.join(HERE, name, "src", "main", "java", "com", "teolo", name.lower(), pkg)


def body_without_package(path):
    """The file without the package line: that is the only difference allowed between the copies."""
    with open(path, encoding="utf-8") as f:
        return "\n".join(r for r in f.read().split("\n") if not r.startswith("package "))


def check_common_classes(names):
    """Reports the common classes that differ from one plugin to another (or that are missing)."""
    problems = []
    for cls, pkg in COMMON_CLASSES.items():
        versions = {}
        for name in names:
            p = os.path.join(class_path(name, pkg), cls)
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
