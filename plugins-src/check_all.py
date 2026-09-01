# -*- coding: utf-8 -*-
"""
Single entry point for the plugin guards. Runs BOTH checks and fails if either fails:

  - check_english.py     : plugin file / package / top-level-type names are English (code-in-English rule)
  - check_english_web.py : website (JS/PHP) function/type names are English, on the changed lines
  - check_commands.py    : commands, aliases, sub-commands and permission nodes are English
  - check_config.py      : config <-> README <-> guides <-> tutorial aligned, no gaps

Exits 1 if anything is off, 0 if everything is clean. Wired into:
  - .githooks/pre-commit          -> blocks a commit that would introduce Italian names or gaps
  - .claude/settings.local.json   -> nudges during a Claude Code session (via check_hook.py)
  - the release routine            -> run it before building/deploying

Usage:
    python plugins-src/check_all.py
"""
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
CHECKS = ["check_english.py", "check_english_web.py", "check_commands.py",
          "check_config_english.py", "check_config.py"]


def main():
    failed = []
    for name in CHECKS:
        print(f"\n########## {name} ##########")
        result = subprocess.run([sys.executable, os.path.join(HERE, name)])
        if result.returncode != 0:
            failed.append(name)
    print("\n" + "=" * 50)
    if failed:
        print("FAILED: " + ", ".join(failed) + " -> fix the items above before committing/releasing.")
        return 1
    print("OK: every plugin is in English and every guide/tutorial is aligned.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
