# Make c(x) constants stop clipping an oscillator's domain

- **Handoff:** `14ee7526-40f1-4085-b697-0044188a074f` (project)
- **Filed from:** https://openendedgroup.com/field4/functions_examples/
- **Filed by:** human, 2026-10-15 13:43
- **Close with:** `python tools/docs_notes.py handoff-close 14ee7526 "what happened"`

`public/docs/functions_examples.md` (the `constant` section) carries this paragraph:

> `0.5.c` is the same value as a *parameter signal*: a constant over `Domain.ALL`, so it does not shrink the domain of anything it is combined with. `c(0.5)` and `constant(0.5)` carry the default `0.0..1.0`, and domains intersect, so `sin(frequency = c(2.0))` clips the oscillator to `0.0..1.0` where `sin(frequency = 2.0.c)` (or plain `2.0`) leaves it unbounded. Use `c(...)` when the constant is a signal in its own right and `.c` when it is a knob.

This is a trap (rule 25): `c(2.0)` and `2.0.c` read as the same thing but silently clip an oscillator differently. Fix in code in `afield.math.functions`: either have a constant passed as a parameter not contribute its domain (parameter domains should not intersect the parent's), or give `c(...)`/`constant(...)` `Domain.ALL` by default. Then delete the paragraph (or reduce it to one line on what `.c` is) in the same change, and recapture any figure that depends on the clipping.
