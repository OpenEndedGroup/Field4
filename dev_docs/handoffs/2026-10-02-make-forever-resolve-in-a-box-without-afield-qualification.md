# Make forever { } resolve in a box without afield. qualification

- **Handoff:** `50bea83f-fa2f-4d4f-9ca4-d894add9df74` (project)
- **Filed from:** https://openendedgroup.com/field4/animation_time_model/
- **Filed by:** human, 2026-10-02 06:25
- **Close with:** `python tools/docs_notes.py handoff-close 50bea83f "what happened"`

`animation_time_model.md` has to tell readers that `forever { }` must be written `afield.forever { }` in a box, because the box preamble imports `afield.four.util.*`, whose own `forever` (returning `Sequence<Boolean>`) makes the bare call an overload ambiguity. That is rule 11 (fix the name, not the explanation).

Do: rename or un-export one of the two `forever`s (likely the `afield.four.util` sequence one, checking its callers), so `forever { }` resolves in a box. Then delete the "In a box it has to be written `afield.forever`" paragraph and update any fences that spell `afield.forever` (fence bytes are pinned by `tools/completion-corpus/rank-carets.tsv`; recapture as needed).
