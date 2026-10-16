# Run a kit starting point's roles when it is opened

- **Handoff:** `e4bf8976-f2a3-4622-a3cc-acefd4e9619d` (project)
- **Filed from:** https://openendedgroup.com/field4/kits/
- **Filed by:** human, 2026-10-16 09:17
- **Close with:** `python tools/docs_notes.py handoff-close e4bf8976 "what happened"`

Refiled from the code batch on 2026-10-16, not started. It is a new Field behaviour: launch a kit starting point's roles in launch order when the document is first opened. It needs the open path in Server.kt to know a document came from a kit template, and a test.


Rule 25 sharp edge on `public/docs/kits.md` (section "Make a kit from a document"):

> A document opened from a starting point has not run yet. `activate` runs its roles in launch order:

followed by the `tools/field_kit.py activate ~/kits/level-kit --document level-kit` fence and its
output. A document made from a kit's starting point (the Welcome screen's Templates entry) opens with
its role boxes unlaunched, so the reader must run a separate backdoor command before it does
anything. Fix it in Field: when a document is opened from a kit starting point, launch its roles in
the kit's launch order (what `activate` in `tools/field_kit.py` does today). Then delete the
paragraph and its two fences in the same change, or reduce `activate` on the page to relaunching an
existing document.
