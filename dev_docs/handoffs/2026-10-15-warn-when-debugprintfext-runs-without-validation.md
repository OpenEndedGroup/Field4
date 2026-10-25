# Warn when debugPrintfEXT runs without validation

- **Handoff:** `7b871831-9ac5-4a1a-b5d6-45d4695f7b7d` (project)
- **Filed from:** https://openendedgroup.com/field4/afield_four_graphics/
- **Filed by:** human, 2026-10-15 13:48
- **Close with:** `python tools/docs_notes.py handoff-close 7b871831 "what happened"`

`public/docs/afield_four_graphics.md`, section "Debugging: printf from a shader":

> printing needs validation layers switched on (`Options.validation`); without them the call compiles and silently does nothing.

Silent no-op is a sharp edge (rule 25). Fix in code: when a compiled shader uses `GL_EXT_debug_printf`/`debugPrintfEXT` and validation (debug printf) is off, log a one-time warning on the box naming `Options.validation`, or enable the debug-printf layer on demand. Delete the "silently does nothing" clause in the same change.
