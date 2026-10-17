# Let a box's named fun capture the implicit Box receiver under K2

- **Handoff:** `3b9a73a1-20ae-49b4-8f2a-76a4bb5d0fa0` (crosscutting)
- **Filed from:** https://openendedgroup.com/field4/animation_time_model/
- **Filed by:** human, 2026-10-15 13:42
- **Close with:** `python tools/docs_notes.py handoff-close 3b9a73a1 "what happened"`

The first recorded fence of `public/docs/animation_time_model.md` (`animation-...` disc loop) carries:

> // A lambda, not a named `fun`: a named function that captures the script's implicit Box receiver —
> // which `lines(...)` needs, since it is a Box extension — is something K2 cannot currently codegen.

A box's named local function that uses the implicit Box receiver should compile. Fix the K2 REPL
codegen (see the K2 snippet-declaration notes), then rewrite the fence as a plain `fun draw(...)`,
drop the comment, and recapture. Fence bytes are pinned, so this is a code + capture change.
