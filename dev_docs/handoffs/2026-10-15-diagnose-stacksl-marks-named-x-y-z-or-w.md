# Diagnose StackSL marks named x, y, z or w

- **Handoff:** `6a353f77-a200-4615-8243-d1d0c7933634` (project)
- **Filed from:** https://openendedgroup.com/field4/stacksl_cheatsheet/
- **Filed by:** human, 2026-10-15 13:47
- **Close with:** `python tools/docs_notes.py handoff-close 6a353f77 "what happened"`

The StackSL cheatsheet (Local marks section) carries this paragraph:

> A mark cannot be called `x`, `y`, `z` or `w`: `>x` is already the word that replaces one lane of a value, so `>x <x` reads a mark that was never made.

This is a sharp edge (rule 25): a name collision that silently misbehaves. Fix in `src/jvmMain/kotlin/afield/lang/stacksl/StackSL.kt`: when `<x`/`<y`/`<z`/`<w` (or `=x` etc.) are used as mark reads/writes, emit a diagnostic naming the collision with the lane-write word, or reject reserved mark names at parse time. Delete the paragraph from `public/docs/stacksl_cheatsheet.md` in the same change (the diagnostic replaces it).
