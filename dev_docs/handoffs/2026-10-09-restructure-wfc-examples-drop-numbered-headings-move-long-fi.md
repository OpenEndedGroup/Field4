# Restructure wfc_examples: drop numbered headings, move long figure code to internals

- **Handoff:** `a37a8cb7-5f21-44c4-8474-1042a14b6173` (project)
- **Filed from:** https://openendedgroup.com/field4/wfc_examples/
- **Filed by:** human, 2026-10-09 06:12
- **Close with:** `python tools/docs_notes.py handoff-close a37a8cb7 "what happened"`

`wfc_examples.md` runs to about 1500 lines, with numbered section headings (`## 1.` to `## 8.`) and in-page `§n` cross-references. The 2026-10-09 language pass edited only the prose. Still to do:

- Drop the numbering from the headings, and change the `§n` links to named anchors (`grep -n '§' public/docs/wfc_examples.md`).
- Move the long figure-building fences (the inline-view and scalar-field sections) into an internals section at the end, or fold their montage code, so a beginner meets short examples first.
- The "Source file" section names `afield.stdlib.wfc` as a package; link it to the source file instead.
- Consider splitting the page: the scalar-field section and the "Looking at the rules" diagnostics could each be their own page.
