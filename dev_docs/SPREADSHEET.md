# The spreadsheet: audit, fixes and how it works (2026-10-26)

User page: `public/docs/spreadsheets.md` (Spreadsheets in a Box). Code:
`src/jvmMain/kotlin/afield/data/table/spreadsheet.kt` (state, evaluator, websocket service, box API)
and `src/commonMain/resources/spreadsheet.js` (the editor iframe). Tests:
`src/jvmTest/kotlin/afield/data/table/SpreadsheetTest.kt` (real K2 formula engine) and the smoke
`examples.spreadsheet.formulas` (`SpreadsheetFormulaSmoke.kt`). Starting point:
`dev_docs/agents/transcripts/spreadsheet_tour.yaml`.

## How it works

- The sheet is a `SpreadsheetStoredState` (rows, cols, sizes, cell inputs) stored as JSON in a box
  property (`spreadsheet` by default, sidecar `.json`). `Box.sheet { }` seeds it only when empty.
- `Box.spreadsheet` displays `/spreadsheet.html?uid=…&property=…` in an output iframe. The iframe
  opens a websocket, sends `hello`, then `open`; the server (`SpreadsheetService.handle`, on the
  main thread via `inMain`) evaluates every formula and replies with a `SpreadsheetSnapshot`.
  Edits are debounced `update` requests carrying the whole state.
- A formula `=expr` is rewritten (`rewriteSpreadsheetFormula`): `B2` → `cell(__ref(0))`,
  `A1:A3` → `range(__ref(1))`, `x | m` → `x.withUnit(m)`, string-template code included. The refs
  are passed by position so a filled-down column is one template. Each template is compiled once
  by a per-sheet `KotlinInterface2` into a `() -> Any?` (helpers are local functions in a `run {}`)
  and called on every recompute with the sheet runtime in a ThreadLocal.
- Values are wrapped in `SpreadsheetScalar`, whose operators dispatch through
  `SpreadsheetDynamicRegistry` (lists broadcast; FLine, vector and quantity rules; blank is 0).
- The sweep: each computed snapshot records which box members formulas read (`box(..)[..]`,
  `self[..]`) and fingerprints them. `Runloop.preRun["__spreadsheet_poll__"]` calls
  `pollLiveSheets` every frame for sheets with subscribers; a changed fingerprint recomputes and
  pushes the snapshot.

## Claim inventory (funFeatures blurb and the box API)

| Claim | Verdict | Evidence |
| --- | --- | --- |
| Formulas are Kotlin | true | SpreadsheetTest `formulasAreKotlinOverCellsAndRanges`; capture `kotlin.png` |
| Integration with other boxes' variables | true | `box("Rates · run me first")["tax"]` in the demo, run live |
| Changing a dependency re-sweeps on the next frame | **was false for a sheet whose only formula read the box** (stale REPL result); fixed | `aOneFormulaSheetFollowsToo`, `aSheetFollowsAnotherBoxOnTheNextFrame`; demo: Rates re-run moved D6 45→25, D7 48.6→30 without re-running the sheet |
| Auto-promoting scalars / units | true; units lost their written unit when scaled (6.3 dm); fixed | `valuesPromoteThroughUnitsVectorsAndLines`; `promotion.png` |
| Linear algebra types | true | same test (`vec(1,2) * 3`, `B1.x + B1.y`) |
| FLine in formulas | true; `.w` is the bounds' *height*; added `.width`/`.height` | same test |
| `getSpreadsheetCellValue` returns the value | **was the SpreadsheetScalar wrapper**; fixed | `assertTrue(... is FLine)` |
| `spreadsheetState {}` / seed / edit | true | `theBuilderSeedsOnceAndEditsInPlace` |
| `#ERR` / `#CYCLE` | **errors read as empty in dependants; cycles showed blank**; fixed | `anErrorPropagatesToTheCellsThatReadIt`, `aCycleIsReportedOnEveryCellInIt`; `errors.png` |
| A sheet works after a bad first formula | **was false** (`NoClassDefFoundError: ReplState` for every later formula); fixed by bootstrapping the engine | `anErrorPropagatesToTheCellsThatReadIt` (its first formula does not compile) |
| `sqrt(A1)` etc. | **did not compile** (cells are not Double); added cell-taking math and vararg aggregates | `mathFunctionsTakeCellsDirectly` |
| Paste adjusts references | **shifted by offset inside the block, not by distance moved**; fixed; Delete, Cmd/Ctrl+D/R added | driven live with `page-eval` (B1 `=A1 * 10` pasted to C2 became `=B2 * 10`) |
| Sheet visible when its output opens | **lazy iframe + width:100% left it empty and narrow**; fixed (eager load, `autosize="off"`, natural width, capture honours declared width) | docs captures |

## Risks and follow-ups

- Formula evaluation runs on the main thread (`inMain`). A first open compiles one template per
  distinct formula shape (0.3–2 s each) and the UI waits. Moving compile off the main thread is the
  next real improvement.
- A template that fails to compile is retried on every recompute (no negative cache).
- `declaredFixedIframeWidth` in `Main.kt` changes the screenshot sizing for any iframe with
  `data-afield-autosize="off"` and an inline pixel width; before this, only `Box.spreadsheet`'s
  iframe declared both. The UI regressions `viewer-panel-sizing` / `h2-panel-sizing` were not run on this branch; run them before landing.
- The test JVM now opens `java.base/java.net` (as Field does), so a jvmTest can build a
  `KotlinInterface2`.
