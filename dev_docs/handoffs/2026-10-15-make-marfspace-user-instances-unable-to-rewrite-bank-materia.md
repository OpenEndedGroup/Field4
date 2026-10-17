# Make MarfSpace user instances unable to rewrite bank material

- **Handoff:** `96feed5d-561c-4f45-86c3-ec2aecf353dc` (project)
- **Filed from:** https://openendedgroup.com/field4/marf/
- **Filed by:** human, 2026-10-15 13:46
- **Close with:** `python tools/docs_notes.py handoff-close 96feed5d "what happened"`

Rule 25 sharp edge on public/docs/marf.md, warning "Watch for mutation in your own space":

> In Field's generated linalg, `add(other)`, `sub(other)`, `mul(scalar)` and `normalize()` are in-place members that return the receiver, and `/` resolves to the mutating member operator rather than the allocating extension. ... Write yours with the out-parameter forms ... and read your bank twice: a mutating implementation gives you a different answer the second time, and nothing in the type signature will have warned you.

Fix in code: make a user-written `MarfSpace<T>` unable to corrupt bank material — e.g. `marfBankIn` copies stage targets into reader-private storage per read, or `MarfSpace` gains a `copy` member the reader applies before `add/sub/scale`, or a debug-mode check that reads a stage twice and fails naming the space. (The wider issue, linalg `/` resolving to the mutating member, is a separate API question.) Delete the warning in the same change.
