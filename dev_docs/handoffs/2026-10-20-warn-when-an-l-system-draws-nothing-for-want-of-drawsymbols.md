# Warn when an L-system draws nothing for want of drawSymbols

- **Handoff:** `c62aa8fc-d795-4f6a-896b-7538546c66b0` (project)
- **Filed from:** https://openendedgroup.com/field4/alife_examples/
- **Filed by:** human, 2026-10-20 11:09
- **Close with:** `python tools/docs_notes.py handoff-close c62aa8fc "what happened"`

Rule 25 sharp edge on `public/docs/alife_examples.md`, section 9:

> ??? aside "Why `drawSymbols` exists"
>     The Sierpiński arrowhead curve is written `A → B−A−B`, `B → A+B+A`, where `A` and `B` are *both* drawing symbols. Interpreted with the default `F`/`G` alphabet it draws nothing at all, and a blank panel is the only warning you get.

Fix in code (`afield/simulation/agents/alife_lsystem.kt`, `drawLSystem`): when a derived string emits zero segments but contains non-turtle letters, warn or throw naming the undrawn symbols and pointing at `drawSymbols` (or infer drawing symbols from the rules). Delete this aside in the same change.
