# Warn or fix when box-scope receiver members shadow script-local vals (h, sky, error)

- **Handoff:** `34aad513-050f-4938-9043-cad5bd564eff` (crosscutting)
- **Filed from:** https://openendedgroup.com/field4/wfc_examples/
- **Filed by:** human, 2026-10-20 11:09
- **Close with:** `python tools/docs_notes.py handoff-close 34aad513 "what happened"`

Rule 25 sharp edge on public/docs/wfc_examples.md ("Recipes"):

> - **A box's scope is crowded.** `h`, `sky` and `error` all resolve to something Field already defines
>   ahead of a script-local `val` of the same name once you are inside a lambda with its own receiver.
>   `outW`/`outH`/`skyPattern` are not paranoia, and `throw` beats `error(...)`.

Fix in code: a script-local `val` should win over implicit-receiver members inside nested lambdas (or the
compiler/box-idiom checker should warn when a local is shadowed by a receiver member such as `h`, `sky`,
`error`). Delete this bullet in the same change.
