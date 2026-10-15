# Keep the native window drawing during a propUp pull

- **Handoff:** `48884767-b670-4320-899a-1c935f19a5c1` (project)
- **Filed from:** https://openendedgroup.com/field4/reactive_properties/
- **Filed by:** human, 2026-10-15 13:42
- **Close with:** `python tools/docs_notes.py handoff-close 48884767 "what happened"`

`public/docs/reactive_properties.md` says:

> While a pull runs, other boxes' `frames {}` loops keep advancing, but a native graphics window shows no new frames until the pull returns. A supplier that blocks the thread (`Thread.sleep`, a busy loop) blocks everything for as long as it blocks.

Rule 25: the native window stalling during a `propUp` pull is a limitation of the nested runloop pump. Keep the renderer's frames advancing during a pull (or run the supplier off the main thread), then delete the paragraph in the same change.
