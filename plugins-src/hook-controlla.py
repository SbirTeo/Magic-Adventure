# -*- coding: utf-8 -*-
"""
Compatibility shim: this file was renamed to check_hook.py (code-in-English rule).

.claude/settings.local.json now points at check_hook.py, but a Claude Code session reads its hook
configuration at startup, so a session already running when the rename happened still calls this old
path. To avoid errors until the next session restart, this stub simply forwards to check_hook.py.

Safe to delete once no running session references it any more.
"""
import os
import runpy

runpy.run_path(os.path.join(os.path.dirname(os.path.abspath(__file__)), "check_hook.py"),
               run_name="__main__")
