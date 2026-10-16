# Run a kit starting point's roles when it is opened

- **Handoff:** `0148f821-1cdd-4baf-833c-e0f5c28204c9` (project)
- **Filed from:** https://openendedgroup.com/field4/kits/
- **Filed by:** human, 2026-10-15 13:49
- **Close with:** `python tools/docs_notes.py handoff-close 0148f821 "what happened"`

Rule 25 sharp edge on `public/docs/kits.md` (section "Make a kit from a document"):

> A document opened from a starting point has not run yet. `activate` runs its roles in launch order:

followed by the `tools/field_kit.py activate ~/kits/level-kit --document level-kit` fence and its
output. A document made from a kit's starting point (the Welcome screen's Templates entry) opens with
its role boxes unlaunched, so the reader must run a separate backdoor command before it does
anything. Fix it in Field: when a document is opened from a kit starting point, launch its roles in
the kit's launch order (what `activate` in `tools/field_kit.py` does today). Then delete the
paragraph and its two fences in the same change, or reduce `activate` on the page to relaunching an
existing document.
