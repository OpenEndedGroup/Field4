# Fix K2 box codegen failures for local funs and implicit-receiver helpers

- **Handoff:** `729b1426-c53e-4e7a-9a68-85d81f4577c4` (project)
- **Filed from:** https://openendedgroup.com/field4/wfc_examples/
- **Filed by:** human, 2026-10-15 13:46
- **Close with:** `python tools/docs_notes.py handoff-close 729b1426 "what happened"`

public/docs/wfc_examples.md, "Names and shapes that bite inside a Field box", warns:

> - A **local `fun` nested inside a lambda** trips a K2 codegen assertion (`IrFunctionImpl has no callableId`). Declare helpers at the top level of the box.
> - A **named function that touches the box's implicit `Box` receiver** — and `println` does — cannot be code-generated either (`No mapping for symbol: SCRIPT_IMPLICIT_RECEIVER`).
> - **A box's scope is crowded.** `h`, `sky` and `error` all resolve to something Field already defines ahead of a script-local `val` of the same name once you are inside a lambda with its own receiver.

These are compiler/runtime sharp edges (rule 25), not WFC documentation. Fix the K2 REPL snippet lowering for local funs in lambdas and named functions using the script implicit receiver; make script-local vals shadow inherited box members (or emit a diagnostic naming the collision). Delete the corresponding bullets from wfc_examples.md in the same change.
