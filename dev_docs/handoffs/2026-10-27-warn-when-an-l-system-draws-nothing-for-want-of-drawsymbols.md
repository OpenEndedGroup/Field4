# Warn when an L-system draws nothing for want of drawSymbols

- **Handoff:** `8884b742-d85d-467f-b58a-3dbb5ffd2c94` (project)
- **Filed from:** https://openendedgroup.com/field4/alife_examples/
- **Filed by:** human, 2026-10-27 18:20
- **Close with:** `python tools/docs_notes.py handoff-close 8884b742 "what happened"`

`public/docs/alife_examples.md`, section 9, carries this aside:

> ??? aside "Why `drawSymbols` exists"
>     The Sierpiński arrowhead curve is written `A → B−A−B`, `B → A+B+A`, where `A` and `B` are *both* drawing symbols. Interpreted with the default `F`/`G` alphabet it draws nothing at all, and a blank panel is the only warning you get.

This is a sharp edge (rule 25): a silent empty result. Fix in code (`alife_lsystem.kt` turtle interpretation): when a derived string produces no segments because none of its symbols are drawing symbols, raise an error or box warning naming the symbols that appear in the rules and suggesting `drawSymbols`. Delete the aside's "blank panel is the only warning" paragraph in the same change.
