# -*- coding: utf-8 -*-
"""
Shared Java lexer-lite: splits a .java source into typed spans so that tools can act on CODE
without ever touching text a person reads (string/char literals) or prose (comments).

This is the safety net for the rename-to-English work: a blind word-boundary replace once ruined
27 files by hitting Italian words INSIDE strings and comments. Here every replacement or scan is
confined to spans classified as "code"; strings and comments come back byte-for-byte.

A span is a tuple (kind, text) where kind is one of:
  "code"        - real Java tokens (identifiers, keywords, operators, whitespace)
  "string"      - a "..." literal, quotes included (text blocks \"\"\"...\"\"\" too)
  "char"        - a '.' literal, quotes included
  "linecomment" - // ... up to (not including) the newline
  "blockcomment"- /* ... */

Reassembling "".join(text for _, text in spans) reproduces the original source exactly.
"""
import re

# One regex, alternation ordered so the longest/most-specific opener wins. Text blocks before
# plain strings; block comments before line comments (both start with '/').
_TOKEN = re.compile(
    r'"""(?:\\.|[^\\]|\n)*?"""'      # text block  """ ... """
    r'|"(?:\\.|[^"\\\n])*"'           # string      " ... "
    r"|'(?:\\.|[^'\\\n])*'"           # char        ' ... '
    r'|/\*(?:[^*]|\*(?!/))*\*/'       # block comment /* ... */
    r'|//[^\n]*'                      # line comment // ...
    , re.DOTALL)


def spans(src):
    """Yield (kind, text) spans covering the whole source, in order."""
    out = []
    last = 0
    for m in _TOKEN.finditer(src):
        if m.start() > last:
            out.append(("code", src[last:m.start()]))
        tok = m.group(0)
        if tok.startswith('"'):
            kind = "string"
        elif tok.startswith("'"):
            kind = "char"
        elif tok.startswith("/*"):
            kind = "blockcomment"
        else:
            kind = "linecomment"
        out.append((kind, tok))
        last = m.end()
    if last < len(src):
        out.append(("code", src[last:]))
    return out


def map_code(src, fn):
    """Rebuild src with fn applied to the text of every "code" span only."""
    return "".join(fn(text) if kind == "code" else text for kind, text in spans(src))


# CamelCase / snake splitting shared by the renamer and the check, so they agree on "words".
_WORD = re.compile(r'[A-Za-z][a-z]*|[A-Z]+(?![a-z])|\d+')


def words_of(identifier):
    """Split an identifier into lowercase word pieces (camelCase, PascalCase and snake_case)."""
    parts = []
    for chunk in identifier.split('_'):
        parts.extend(_WORD.findall(chunk))
    return [p.lower() for p in parts if p]
