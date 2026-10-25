# Derive sonicPiProgram cycle count from the pattern's period

- **Handoff:** `80434bbd-6a46-4741-911c-1bc73437b5f2` (project)
- **Filed from:** https://openendedgroup.com/field4/sonic_pi/
- **Filed by:** human, 2026-10-15 13:45
- **Close with:** `python tools/docs_notes.py handoff-close 80434bbd "what happened"`

public/docs/sonic_pi.md ("compile: the pattern becomes a program") says:

> Choose `cycles` with care. A pattern that varies per cycle (`<a b>`,
> `.every(4)`, `.iter`) needs a loop long enough to contain the variation, or the emitted program
> plays cycle 0 for ever.

Rule 25 sharp edge: a silent wrong result the user must avoid by hand. Fix in code: have sonicPiProgram/sonicPiSource compute (or bound) the pattern's period when `cycles` is omitted, and warn in the generated program's header comment (like the unmatched-atom comment) when the given `cycles` is shorter than a detectable period. Delete or shrink the paragraph in the same change.
