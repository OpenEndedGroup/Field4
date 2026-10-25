# Make the obvious interaction-net termination check correct (ROOT active pair)

- **Handoff:** `32bfca0b-9de0-48c9-a235-111c6a6e2e57` (project)
- **Filed from:** https://openendedgroup.com/field4/chain_lambda_sound/
- **Filed by:** human, 2026-10-15 13:42
- **Close with:** `python tools/docs_notes.py handoff-close 32bfca0b "what happened"`

Rule 25 sharp edge on `public/docs/chain_lambda_sound.md`, note "A normal form still has an active pair in it":

> `ROOT`'s principal port faces the answer's principal port, and nothing in `Lambda.rules` covers `ROOT ⟂ LAM`. A reduction has finished when no rule matches, so a termination check written as `net.activePairs().isEmpty()` never fires.

The obvious termination check is wrong. Fix in code: either have `activePairs()` exclude pairs with no matching rule (or add a `reduciblePairs()`/`isNormal()` and make the obvious spelling correct), or wire `ROOT` so it does not form an active pair with the answer. Update the "last row says one" readout in the first box accordingly, and delete the note in the same change.
