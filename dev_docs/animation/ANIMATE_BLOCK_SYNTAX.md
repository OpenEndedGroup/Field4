# `animate` and `Frames`

`animate { ... }` is a library function, not compiler syntax or a source
rewrite. It returns `eachFrame(block)`, which builds a `Frames` value whose body
runs the block after each successful frame boundary.

For launched box code, only the returned value matters: the `Frames` return-value
handler calls `Box.launchFrames`. Consequently, setup expressions before a final
`animate { ... }` expression run once and the block runs on subsequent Field
Runloop frames. If `animate` is not the returned expression, creating the value
alone does not launch it.

The complete contract is colocated with:

- `Frames`, `frames`, `loop`, `eachFrame`, and `Box.launchFrames` in
  `src/jvmMain/kotlin/afield/Phased.kt`;
- `start`, `animate`, `Box.begin`, and `Box.end` in
  `src/jvmMain/kotlin/afield/stdlib/stdlib.kt`;
- the `Frames` handler in
  `src/jvmMain/kotlin/afield/ReturnValueRegistry.kt`.

Within a `Frames` receiver, `frameNumber` counts completed `frame()` calls,
`delay(ms)` suspends and then calls `frame()`, and suspend `end()` cancels the
current Frames job (or marks an unlaunched value stillborn). `Box.end()` is a
different operation: it routes through the box stop machinery.

`frames {}` requires explicit frame advancement. `eachFrame {}` advances before
each body call. `loop {}` executes the body first and auto-advances afterward
only when the body did not advance. These distinctions should remain in KDoc,
where changes to the scheduling code can update them atomically.

Verification belongs in a backdoor-launched scratch box: return `animate` as the
last expression, observe monotonically increasing `frameNumber`, stop the box,
and confirm `is-running` becomes false. There is no dedicated `FIELD_TEST` for
this small API contract today.
