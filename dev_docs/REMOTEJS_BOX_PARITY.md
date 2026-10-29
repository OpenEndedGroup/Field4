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

## Kotlin → head boxes (`kotlinjs`)

Measured the same way (port 9601, `--with-playwright`, the head from `remoteAr()`), with
`// {{ remoteAR }}` boxes under a `mainLanguage: kotlinjs` root. "Before" is this branch at
b5fd27f7b; "Now" replays the Kotlin column of the parity transcript (`kparity_*`).

| Feature | Before (measured) | Now (measured) |
| --- | --- | --- |
| Run receipt (backdoor `run` / `launch` settle) | **Missing**: no `ExecutionTrace`, so every awaited run timed out after its output arrived, errors included | Settles on the head's terminal reply (Launch: on RUN-START); `KotlinJsReceipt` |
| `println` beside its line | Present (`__fieldPrintAt`, line 2) | unchanged |
| `console.log` | Printed, but at the generated-JavaScript line (206 for line 3) | Line 3: routed calls carry `jsFrame` and map through the module's source map |
| Runtime error line | Message right, line wrong: generated line 802 | The innermost frame in the box's own Kotlin: a `throw` on line 5 marks line 5. An exception from an *inline* stdlib function (`reduce` on an empty list) marks the user line that called the function it was inlined into |
| Compile error line | Present (line 2) | unchanged |
| backdoor `ok` for an error | Never reached (timed out) | Verdict fails with the message |
| Last value of the box | Not shown (a Kotlin `main` has no value) | unchanged; not planned |
| `display(channel, html)` | **Missing** (`Unresolved reference 'display'`) | Present, bound to the head's `display` |
| `slider` / `checkbox` | **Missing** | Present, Kotlin signatures like a JVM box's; a slider callback that calls `display` verified in Node (smoke) and the controls drawn live. A live click on the control was not re-driven for Kotlin |
| Browser timers | No `setTimeout` (`kotlinx.browser` is not on the classpath) | `setTimeout(callback, ms)` bound |
| Output after the run | Untested before (no timer) | Beside its line ("half a second later", line 4) |
| Launch / Stop (`onStart` / `onFrame`) | Present (lifecycle smoke) | Re-measured live: `frame 270` unchanged 1 s and 3 s after Stop |
| Running with no head connected | Toast only; compiled and reported nothing | Error on the box naming the fix; verdict fails |
| State across runs | Present (`counter 1`, `counter 2`) | unchanged |
| Inherited properties / `fromContext` | Absent | Not done (same reason as Javascript) |

Pieces: `KotlinJsSourceMap.kt` (v3 decoder; `KotlinToJsCompiler` now writes the map),
`kotlinJsEditorLine` in `KotlinJsHeadProfiles.kt`, the bindings in `RemoteLifecycleKotlinJs.kt`
(they call field.js's own `display` / `slider` / `checkbox`, so there is one box surface, not two),
and `KotlinJsReceipt` in `KotlinJs.kt`. Test: the jvm-main smoke `remotejs.kotlinjs-box-surface`
(`KotlinJsBoxSurfaceSmokeTest`) compiles a box against the bindings, runs it in Node with a real
stack, and checks the source-mapped error line.

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
- **`console.log` grouping.** Output beside consecutive lines is shown in one inline panel by the
  editor, so `print` on line 2 and `console.log` on line 3 share a panel. In a Kotlin → head box the
  `console.log` line arrives first (it is sent outside the run's reply), so it is listed above the
  `println` from the line before.
- **Inherited properties** for Javascript boxes need a JVM→head value channel per run; design work.
- Several heads connected at once each answer, so each draws the controls; harmless, not deduplicated.

Demo: `dev_docs/agents/transcripts/remotejs_box_parity.yaml` and the saved document
`dev_docs/agents/demos/remotejs_box_parity/`.
