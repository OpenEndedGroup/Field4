# Make script-local vals outrank Field box members inside receiver lambdas

- **Handoff:** `25caf4b0-ab05-40c7-be8c-f1567dde8bb6` (project)
- **Filed from:** https://openendedgroup.com/field4/wfc_examples/
- **Filed by:** human, 2026-10-16 05:40
- **Close with:** `python tools/docs_notes.py handoff-close 25caf4b0 "what happened"`

Refiled from the code batch (original: "Fix K2 box codegen failures for local funs and implicit-receiver helpers").

Measured 2026-10-16 on this branch, through the backdoor:

- A local `fun` inside a lambda works: `listOf(1,2,3).map { x -> fun twice(y: Int) = y * 2; twice(x) }` prints `[2, 4, 6]`.
- A named function that calls `println` (the implicit Box receiver) works: it prints `n = 3`. The fork's REPL patches cover it.

Both bullets were deleted from wfc_examples.md.

Still real: **a script-local `val` loses to a Field definition of the same name inside a lambda with its own receiver.**

```kotlin
val h = 7
val sky = "blue"
println(listOf(1).map { with(StringBuilder()) { append("$h $sky"); toString() } })
```

This prints Field's `h` (an HTML value), not `7`. Kotlin ranks implicit-receiver members (here the Box's own `h`, through the `with` chain) ahead of the snippet class's properties. The fix is in the Kotlin fork's REPL resolution: snippet-level declarations should outrank the script's implicit receiver. The alternative is a diagnostic naming the collision (a FIR checker or a Field lint over the box's script-local names). This is compiler work; it needs a decision on which way to go. The page's "crowded scope" bullet stays until then.
