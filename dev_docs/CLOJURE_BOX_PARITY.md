# Clojure box parity with Kotlin and Python boxes

Audit of 2026-10-18 (nightly), the Clojure counterpart of `PYTHON_BOX_PARITY.md`. Every row was
checked by running a real Clojure box through `tools/field_backdoor.py` on port 9531 (boxes from
`dev_docs/agents/transcripts/clojure_box_parity.yaml` plus a scratch audit document), not by reading
code. The 10-15 backlog item N1015-4 ("Clojure `field.core` controls wrapper") is closed by this.

| Feature | Before | Now | Run evidence |
| --- | --- | --- | --- |
| `println` beside its form | Present (per-form read/eval loop) | unchanged | `cparity_context`: `speed 2.5` on line 3, `palette [...]` on 4, `missing 1.0` on 5 |
| Last value when nothing printed | Present | unchanged | covered by `docs.clojure.last-value` |
| `display` into a named channel | Present (`(display "main" v)`) | unchanged | `cparity_controls` shows `sine at 3.0 Hz` in `main` (screenshot) |
| Own properties, read/write | Present (`prop`, `prop!`) | unchanged | scratch `a_misc`; `docs.clojure.box-object` |
| Reader/compile error line | Present (reader line, `CompilerException` line) | unchanged | `docs.clojure.unresolved` |
| Runtime error inside a `defn` | Marked on the top-level *call* (`(mean xs)`, line 6 of the audit box) | Marked on the raising line inside the fn | audit box: before line 6 (call); demo `cparity_error`: now line 6 = the `/` inside `mean`, call is line 8 |
| `slider` / `checkbox` with callbacks | Absent | `field.core/slider`, `checkbox`; handler is a fn or an atom; one `controlGroup` per channel at run end | `cparity_controls`: controls drawn; a click on the checkbox from the page redrew `main` as `square at 3.0 Hz`; a re-run kept it ticked and the handler heard it (screenshots) |
| Inherited properties | Only by walking `(.getParents (box))` by hand | `(context :name [default])` via `ContextResolution.walk` | `cparity_context`: `(context :speed)` = 2.5 from the root's property |
| Kotlin values from a parent | Only by Java reflection on the Kotlin engine | `(context "palette")` reads a parent Kotlin box's top-level `val` | `cparity_context`: `palette [tomato teal gold]` |
| `fromContext<T>()` | Absent | `(context SomeClass)` | `ClojureFieldCoreTest` (`StringBuilder` found on the grandparent); no live renderer run |
| Launch / animate / stop | Present (`animate`, `frame`, `end`) | unchanged | covered by `docs.clojure.animate` |

## How the pieces fit

- `src/jvmMain/resources/field/core.clj`: `slider`, `checkbox`, `context` are thin wrappers over
  `ClojureInterface` statics, like the rest of `field.core`.
- `ClojureInterface.slider/checkbox` record a `PendingControl` (with the form's line) per box;
  `ClojureExecution.evaluateReporting` clears them before a run and calls `flushControls` in its
  `finally`, so controls asked for before a throw still appear (Python does the same).
  `ControlGroupBuilder` restores a moved control's value and calls the handler synchronously during
  the flush; moves from the page go through `controlDispatch` (= `inMain`).
- Raising line: `ClojureHost` binds `*file*` (`Compiler.SOURCE`) to a per-host name while each form
  is evaluated, so fns compiled from the box carry that file and the reader's line numbers.
  `atRaisingLine` takes the innermost stack frame with that file name. Compile-phase
  `CompilerException`s keep their own line; a throw entirely inside library code keeps the calling
  form's line.

## Not done, and why

- **No live `fromContext` by class against a real renderer.** The class form is unit-tested; a
  graphics run was out of scope for the night.
- **Clojure `def`s are not visible to Kotlin children's `fromContext`.** `ContextResolution` reads
  Kotlin engine variables only; Clojure boxes share one namespace (`live.user`), so a per-box
  variable table would need per-box namespaces first.
- **Code sidecars are saved as `.kt`.** `SavingContext.toTransfer` names every `code` sidecar from
  `Box.ioSeparateProperties` regardless of `code_language` (the committed Python demo has `.py`
  sidecars, written some other way). Harmless for loading; `FileWatchManager` would not decode a `.clj` name, which is why the
  demo keeps `.kt`.
- **No JVM test drives a click through the websocket.** `ClojureFieldCoreTest` calls the registered
  slider/checkbox callbacks directly, which is what the `slider-update` / `checkbox-update` handlers
  do; the click itself was exercised through Playwright on the demo.

Demo: `dev_docs/agents/transcripts/clojure_box_parity.yaml` and the saved document
`dev_docs/agents/demos/clojure_box_parity/`.
