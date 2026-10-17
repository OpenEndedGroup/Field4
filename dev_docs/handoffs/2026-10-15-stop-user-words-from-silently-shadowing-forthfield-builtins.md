# Stop user words from silently shadowing ForthField builtins

- **Handoff:** `2b93c577-8f76-4659-baf0-2a823544215e` (project)
- **Filed from:** https://openendedgroup.com/field4/forth_examples/
- **Filed by:** human, 2026-10-15 13:48
- **Close with:** `python tools/docs_notes.py handoff-close 2b93c577 "what happened"`

Rule 25 sharp edge on forth_examples.md ("When a definition misbehaves"):

> Builtins and your own words share one dictionary, and `:` overwrites without warning, so `: dup ... ;` replaces the real `dup` for the rest of the run. `forget` will remove your replacement, but that leaves no `dup` at all; it does not bring the builtin back.

Fix in afield/lang/forth/ForthInterpreter.kt: warn when `:` redefines a builtin, and make `forget` restore the previous (builtin) definition. Delete the paragraph in the same change.
