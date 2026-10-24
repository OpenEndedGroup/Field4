# RemoteJS box parity with Kotlin, Python and Clojure boxes

Audit of 2026-10-24 (nightly), the RemoteJS counterpart of `PYTHON_BOX_PARITY.md` and
`CLOJURE_BOX_PARITY.md`. Every row was measured by running real Javascript boxes through
`tools/field_backdoor.py --with-playwright` on port 9601, with the head embedded on the canvas by
`remoteAr()` in a Kotlin language root (the `remote-ar-embed` setup). "Before" is `main` at
a48ac3f44; "Now" is this branch, replaying `dev_docs/agents/transcripts/remotejs_box_parity.yaml`.

| Feature | Before (measured) | Now (measured) |
| --- | --- | --- |
| `print` beside its line | Printed, but always on line 0 | Beside the calling line (from the `box_…` stack frame): `from print` on line 2 |
| `console.log` / `info` / `warn` / `error` | Browser console only; nothing in the box | Also printed beside the calling line (`from console.log {answer: 42}` on line 3) |
| Last value of a multi-line box | Lost: every multi-line box ran in a generator trampoline, value `undefined` | Shown beside the last line (`true` on line 5); the trampoline is kept only for code that `yield`s |
| Run that ends with a value settles | Single-line `6*7`-style values never settled the receipt (only a bare "OK" was terminal); the backdoor `run` timed out after the value arrived | Settles; `RemoteJsBoxSurface.isTerminal` |
| Runtime error on the raising line | Present: line 3 inside `mean`, not the call (`rp_err`) | unchanged (demo: line 4) |
| Syntax error line | Present (ASTA location; `remote-ar-head` regression) | unchanged |
| backdoor `ok` for an error | Present: verdict fails with the message | unchanged |
| Browser globals in box code | **Missing**: the sandbox's `with` proxy claimed every name, so `setTimeout`, `fetch`, `JSON`, `Promise`… were `undefined` (`setTimeout is not a function`) | Resolved through the sandbox; plain functions bound to `window` |
| Output after the run (timers, handlers, frames) | Dropped: its reply channel had closed and the JVM ignored the address | Routed to the box by its code name ("half a second later" beside line 3) |
| `display("channel", html)` | Absent (`display is not a function`) | Present (`box.display` message) |
| `slider` / `checkbox` with callbacks | Absent | Present; one `controlGroup` per channel; a checkbox click on the page redrew `main` as `square at 3.0 Hz` |
| Launch `_r` / Stop | Present: ticks frozen after Stop | unchanged |
| Stop a `launch(fn, name)` loop | **Missing**: task `box_…\|uid` + name survived Stop and kept counting | Stopped (every task whose name starts with the box's code name); stripe lowered |
| Stray "OK" output from `launch(...)` inside a run | Printed as output on line 0 | Raises/lowers the running stripe instead |
| Running with no head connected | Reported success (`ok=true`), nothing ran | Error on the box naming the fix; verdict fails |
| State across runs | Present (sandbox per box: `counter 1`, `counter 2`) | unchanged |
| Completion | Present (`window.completeMe` in the head) | unchanged, not re-measured |
| Inherited properties / `fromContext` | Absent (the sandbox's `_` / `__` maps are head-side only) | Not done |

## How the pieces fit

- Head (`src/commonMain/resources/extensions/remote_ar/field/`): `field.js` tags each `print` with the
  innermost `box_<name>|<uid>` stack frame (`boxCaller`), wraps `console.*`, and defines `display`,
  `slider`, `checkbox` and `__fieldControl`. Controls a run asks for are sent once, in a microtask
  after its synchronous part. `maps.js` resolves window globals for sandboxed code.
- JVM (`src/jvmMain/kotlin/afield/lang/remotejs/RemoteJsBoxSurface.kt`): `box.display`,
  `box.controls` and late output, the Stop script, and `isTerminal`. `RemoteJS.kt` calls it from the
  `remote` raw handler; a control move runs `__fieldControl(id, value)` on every head.
- Tests: `RemoteJsBoxSurfaceTest` (6 tests, the JVM half). The head half has no automated test
  yet; it was driven live with the transcript.

## Not done, and why

- **No browser regression for the new head surface.** The natural home is a `remote-ar-parity` case
  in `tools/run_ui_regressions.py` built from the transcript's boxes; the night ran out first.
- **Kotlin→JS boxes (`kotlinjs`)** use the same transport but their own profiles and printing
  (`KotlinJsHeadProfiles.kt`); not audited row by row.
- **`console.log` grouping.** Output beside consecutive lines is shown in one inline panel by the
  editor, so `print` on line 2 and `console.log` on line 3 share a panel.
- **Inherited properties** for Javascript boxes need a JVM→head value channel per run; design work.
- Several heads connected at once each answer, so each draws the controls; harmless, not deduplicated.

Demo: `dev_docs/agents/transcripts/remotejs_box_parity.yaml` and the saved document
`dev_docs/agents/demos/remotejs_box_parity/`.
