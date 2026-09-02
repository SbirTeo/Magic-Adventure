# -*- coding: utf-8 -*-
"""
English-code check for every Magix plugin.

Why it exists: the project rule is "code in English, Italian only for what a person reads on screen".
This guards the STRUCTURE of the code against Italian creeping back in:
  - .java file names
  - package segments (after com.teolo.<plugin>)
  - top-level type names (class / interface / enum / record declared at column 0)

On purpose it does NOT touch method names, local variables, enum constants or NESTED types: the
project deliberately keeps some of those in Italian (e.g. the nested enum `Esito`, or builder methods
like `crea`/`sezione`), so flagging them would be noise. When one of those is promoted to its own
top-level type or file, this check catches it.

How it recognises Italian: it splits a CamelCase identifier into words and compares each WHOLE word,
case-insensitively, against a list of Italian words (below). Whole-word matching is deliberate — a
substring test would flag English words like "Duration" (contains "durat") or "Composite".

(Code identifiers and comments are in English, per the rule; code and output alike. The word list is meant to grow: add a word and the guard widens.)

Usage:
    python plugins-src/check_english.py            # every plugin
    python plugins-src/check_english.py MagixGuard # a single one

Exits with code 1 if it found anything, so it can be wired to a hook or to the build.
"""
import os, re, sys

HERE = os.path.dirname(os.path.abspath(__file__))

# Italian words that must never appear as a word inside a structural identifier. Lowercase, whole-word.
# Grow this list when a new Italian term shows up. Keep only words that are unambiguously Italian
# (never a legitimate English identifier word).
ITALIAN_WORDS = {
    "azione", "azioni", "comando", "comandi", "gestore", "caricatore", "contenuto", "contenuti",
    "negozio", "requisito", "requisiti", "equazione", "costruttore", "dialogo", "dialoghi",
    "aiuto", "testo", "testi", "valori", "valore", "durata", "durate", "sanzione", "sanzioni",
    "violazione", "violazioni", "guardia", "filtro", "memoria", "normalizza", "normalizzatore",
    "scavo", "ambito", "politica", "regolamento", "rilevatore", "tipo", "sito", "sincronizza",
    "guida", "punti", "punto", "maschera", "esito", "esiti", "provvedimento", "contesto",
    "clic", "aperto", "chiuso", "colori", "colore", "impostazioni", "registro", "servizio",
    "premio", "premi", "bersaglio", "giocatore", "giocatori", "messaggio", "messaggi", "utente",
    "utenti", "fazione", "fazioni", "territorio", "stagione", "meteo", "spawn",  # spawn stays (MC term)
    # Verbs and nouns that show up in function/method names, especially on the website (JS/PHP).
    # Added when the web check started scanning function names too (see check_english_web.py). All
    # unambiguously Italian — never a legitimate English identifier word.
    "salva", "salvato", "scrivi", "carica", "caricato", "ricarica", "aggiorna", "aggiornato",
    "disegna", "ridisegna", "apri", "chiudi", "avvia", "ferma", "riavvia", "riordina", "svuota",
    "copia", "invia", "manda", "leggi", "mostra", "nascondi", "cerca", "seleziona", "scegli",
    "sposta", "aggiungi", "togli", "rimuovi", "crea", "modifica", "elimina", "cancella", "genera",
    "applica", "controlla", "avvisa", "elenca", "icona", "icone", "riquadro", "casella", "caselle",
    # Parole che compaiono come COMANDI o SOTTOCOMANDI o NODI DI PERMESSO (li controlla
    # check_commands.py). La regola vale anche per loro: nessun comando/config in italiano.
    "lista", "aiuto", "ricarica", "registrati", "silenzia", "espelli", "richiamo", "storico",
    "cronologia", "segnala", "esci", "disconnetti", "codice", "revoca", "sanziona", "cambiapassword",
    "cambiapw", "cambia",
    # Parole che compaiono come CHIAVI di config (le controlla check_config_english.py).
    "membri", "membro", "categoria", "categorie", "poteri", "potere", "introduzione", "applicazione",
    "motivo", "pausa", "massimo", "minimo", "grado", "gradi", "modo", "descrizione",
    "attivo", "attiva", "dimezzamento", "soglie", "soglia", "nome", "nomi", "automatico", "automatica",
    "regolamento", "sanzioni", "provvedimento", "provvedimenti",
    # Broadened 2026-09-01 for the deep scan (method/field/local identifiers). Only unambiguously
    # Italian whole words — never a legitimate English identifier word. Homographs with English
    # (fine, via, note, form, real, male, pause) are deliberately left out.
    "apri", "apre", "chiudi", "chiude", "avvia", "ferma", "fermo", "fermi", "carica", "scarica",
    "salva", "leggi", "leggo", "scrivi", "crea", "genera", "cerca", "cerco", "trova", "trovato",
    "aggiungi", "togli", "tolgo", "rimuovi", "mostra", "nascondi", "invia", "manda", "aggiorna",
    "applica", "controlla", "avvisa", "segnala", "revoca", "sanziona", "disegna", "calcola",
    "componi", "prendi", "tieni", "butta", "rompe", "chiama", "annuncia", "separa", "allinea",
    "allineo", "dimentica", "normalizza", "ricongela", "riproponi", "accogli", "libera", "abbandona",
    "registra", "registrato", "verifica", "decidi", "ricorda", "consegna", "piazza", "aggancia",
    "scalda", "impagina", "sostituisci", "descrivi", "riporta", "pulisci", "corrisponde",
    "codici", "comando", "comandi", "messaggio", "messaggi", "sezione", "sezioni", "riga", "righe",
    "chiave", "chiavi", "giocatore", "giocatori", "categoria", "categorie", "punti", "punto",
    "potere", "poteri", "grado", "gradi", "motivo", "durata", "durate", "pausa", "ambito", "contesto",
    "esito", "esiti", "sanzione", "violazione", "violazioni", "rilevatore", "servizio", "registro",
    "memoria", "politica", "descrizione", "introduzione", "dialogo", "dialoghi", "azione", "azioni",
    "casella", "caselle", "requisito", "requisiti", "colore", "colori", "contenuto", "gestore",
    "tipo", "tipi", "campo", "campi", "bersaglio", "storico", "mondo", "dispositivo", "biscotto",
    "gettone", "busta", "cartella", "fase", "fasi", "scelta", "segno", "forma", "corpo", "fonti",
    "blocchi", "operatore", "radice", "filo", "pagina", "pagine", "pezzi", "intestazione", "taglio",
    "spiegazione", "suggerimento", "titolo", "etichetta", "documento", "paragrafi", "problema",
    "versione", "marchio", "modulo", "inizio", "giro", "ordine", "celle", "prova", "tentativo",
    "tentativi", "sessione", "sessioni", "indirizzo", "indirizzi", "posizione", "direzione",
    "aperto", "chiuso", "pulito", "nudo", "grezzo", "corrente", "giusto", "giusta", "uguale",
    "visibile", "visibili", "massimo", "minimo", "finale", "entrante", "entrato", "fissato",
    "occupato", "rimasto", "vera", "vero", "sinistra", "dentro", "fuori", "avanti", "indietro",
    "adesso", "altro", "insieme", "servono", "cifre", "segreto", "passo", "manca", "rifiuto",
    "bloccato", "dove", "modo", "nascondo", "mostro",
}
# Words that look Italian but are accepted (Minecraft/domain jargon or acronyms). Remove from the set.
ITALIAN_WORDS.discard("spawn")

# Split CamelCase / snake into words. "SanctionCommands" -> [Sanction, Commands]; "OtpPolicy" -> [Otp, Policy]
CAMEL = re.compile(r"[A-Z]+(?=[A-Z][a-z])|[A-Z]?[a-z]+|[A-Z]+|[0-9]+")


def words_of(identifier):
    return [w.lower() for w in CAMEL.findall(identifier)]


def italian_words_in(identifier):
    return [w for w in words_of(identifier) if w in ITALIAN_WORDS]


# --- deep scan: method/field/local identifiers, not just the structure --------------------------
# The structural check above is the settled, always-fatal gate. The deep scan below reaches into
# ordinary identifiers (methods, fields, params, locals, nested types) using the shared java_spans
# tokenizer, so strings and comments — the Italian a person is meant to read — are never scanned.
sys.path.insert(0, HERE)
import java_spans as _js  # noqa: E402

# A plugin joins FULLY_ENGLISH only once it is fully converted; from then on the deep scan FAILS on
# any Italian left in its code. All seven were converted on 2026-09-01, so the deep scan is now the
# enforced gate everywhere: any Italian method/field/local reintroduced later makes the check fail.
FULLY_ENGLISH = {"MagixAuth", "MagixEntities", "MagixFactions", "MagixGuard",
                 "MagixMenus", "MagixTime", "MagixWeb"}

# Classes byte-identical in every plugin (check_config enforces it): their Italian internals are
# translated in one coordinated pass, so the per-plugin deep scan skips them.
COMMON_FILES = {"Help.java", "StaffGuide.java", "ConfigValues.java", "DurationText.java", "Aiuto.java"}

_JAVA_KW = set("abstract assert boolean break byte case catch char class const continue default do "
               "double else enum extends final finally float for goto if implements import instanceof "
               "int interface long native new package private protected public return short static "
               "strictfp super switch synchronized this throw throws transient try void volatile while "
               "var record yield sealed permits true false null".split())
_IDENT = re.compile(r'[A-Za-z_$][A-Za-z0-9_$]*')


def deep_italian(name):
    """Distinct code identifiers carrying an Italian word (minus common classes and keywords)."""
    java_root = os.path.join(HERE, name, "src", "main", "java")
    found = {}
    for root, _, files in os.walk(java_root):
        for f in files:
            if not f.endswith(".java") or f in COMMON_FILES:
                continue
            src = open(os.path.join(root, f), encoding="utf-8", errors="ignore").read()
            for kind, text in _js.spans(src):
                if kind != "code":
                    continue
                for m in _IDENT.finditer(text):
                    w = m.group(0)
                    if w in _JAVA_KW:
                        continue
                    hits = italian_words_in(w)
                    if hits:
                        found.setdefault(w, set()).update(hits)
    return found


def present_plugins():
    out = []
    for name in sorted(os.listdir(HERE)):
        folder = os.path.join(HERE, name)
        if os.path.isfile(os.path.join(folder, "pom.xml")) \
                and os.path.isdir(os.path.join(folder, "src", "main", "java")):
            out.append(name)
    return out


PLUGINS = present_plugins()
# A top-level type: declared at column 0 (Java puts nested types under indentation).
TOP_TYPE = re.compile(r"^(?:public\s+|final\s+|abstract\s+|sealed\s+|non-sealed\s+)*"
                      r"(class|interface|enum|record)\s+([A-Za-z_]\w*)", re.M)
PACKAGE = re.compile(r"^package\s+([\w.]+)\s*;", re.M)


def check(name):
    """Returns a list of (relative_path, kind, identifier, italian_words)."""
    java_root = os.path.join(HERE, name, "src", "main", "java")
    problems = []
    for root, _, files in os.walk(java_root):
        for f in files:
            if not f.endswith(".java"):
                continue
            path = os.path.join(root, f)
            rel = os.path.relpath(path, os.path.join(HERE, name)).replace("\\", "/")

            # 1) file name (without extension)
            hits = italian_words_in(f[:-5])
            if hits:
                problems.append((rel, "file name", f, hits))

            src = open(path, encoding="utf-8", errors="ignore").read()

            # 2) package segments after com.teolo.<plugin>
            mp = PACKAGE.search(src)
            if mp:
                segs = mp.group(1).split(".")
                # drop the fixed prefix com.teolo.<plugin>
                tail = segs[3:] if segs[:2] == ["com", "teolo"] else segs
                for seg in tail:
                    hits = italian_words_in(seg)
                    if hits:
                        problems.append((rel, "package", seg, hits))

            # 3) top-level type names (column 0 only -> nested types are left alone)
            for m in TOP_TYPE.finditer(src):
                if m.start() == 0 or src[m.start() - 1] == "\n":   # really at line start
                    typ = m.group(2)
                    hits = italian_words_in(typ)
                    if hits:
                        problems.append((rel, m.group(1), typ, hits))
    return problems


def main():
    names = sys.argv[1:] or PLUGINS
    total = 0
    for name in names:
        problems = check(name)
        print(f"\n=== {name}: {len(problems)} in Italian" if problems else f"\n=== {name}: English")
        for rel, kind, ident, hits in problems:
            print(f"  {rel}  {kind} «{ident}» -> Italian words: {', '.join(hits)}")
        total += len(problems)

        # Deep scan: enforced for fully-converted plugins, informational for the rest.
        deep = deep_italian(name)
        if name in FULLY_ENGLISH:
            if deep:
                print(f"  DEEP: {len(deep)} Italian identifier(s) — must be English (FULLY_ENGLISH):")
                for w in sorted(deep):
                    print(f"    «{w}» ({', '.join(sorted(deep[w]))})")
                total += len(deep)
            else:
                print("  DEEP: every identifier is English.")
        elif deep:
            sample = ", ".join(sorted(deep)[:8])
            print(f"  deep: {len(deep)} Italian code identifier(s) still to translate "
                  f"(informational) — e.g. {sample}")

    print(f"\nTOTAL not in English: {total}")
    return 1 if total else 0


if __name__ == "__main__":
    sys.exit(main())
