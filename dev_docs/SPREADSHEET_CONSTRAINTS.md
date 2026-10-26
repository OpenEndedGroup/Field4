# Constraint-solving spreadsheets (2026-10-26)

User section: `public/docs/spreadsheets.md#solving-for-cells`. Code:
`src/jvmMain/kotlin/afield/data/table/spreadsheetConstraints.kt` (traced values, assertions, the
solver) and the hooks in `spreadsheet.kt` (`computeSnapshot`, `solveConstraints`, `recordSolved`,
the `traced` registry rules, the formula-source helpers). Tests:
`src/jvmTest/kotlin/afield/data/table/SpreadsheetConstraintsTest.kt` (real K2 formulas, real Ceres).
Starting point: `dev_docs/agents/transcripts/spreadsheet_constraints.yaml`. Background:
`dev_docs/SPREADSHEET.md`.

## Shape

- **Variable cell**: the whole formula is `=free(<number>)`. The number is its previous value — there
  is no cell without one. `SheetConstraintCollector.declare` numbers it and returns a
  `SpreadsheetTraced(value, ExprVar(i, 0))`. `free()` inside a larger formula is refused (there would
  be nowhere to write the answer).
- **Traced values**: `SpreadsheetTraced` is a number plus the `CeresExpr` that computed it from the
  variables, in the sheet's own numbering. The first rule in `SpreadsheetDynamicRegistry` handles
  `+ - * /` when either side is traced (lists and vectors fall through to the broadcast rules, which
  come back per element); a `traced` scalar coercion makes it read as its number everywhere else
  (comparisons, `min`, `avg`, `if`). `sum`, `sqrt`, `abs`, `exp`, `ln`, `sin`, `cos`, `tan`,
  `pow`, `atan2` keep the trace and now return `SpreadsheetScalar` (which gained `toInt`/`toDouble`/
  `toLong` so `sum(A1:A3).toInt()` still compiles). No new expression type: the trace *is* a
  `CeresExpr`.
- **Assertions**: `eq`, `le`, `ge` infix on `SpreadsheetScalar` (and `Number` inside the formula
  source) make a `SpreadsheetAssertion` and register it. Residuals: `l - r`,
  `barrierAbove(l, r)`, `barrierBelow(l, r)`. A lone variable against a constant (`B2 ge 0`,
  `0 le B2`) becomes a Ceres parameter bound instead; contradictory bounds on one variable fall back
  to barriers so the conflict is reported, not refused.
- **Residual remapping**: a Ceres residual block may only name the variables it reads, and
  `ExprVar.block` is block-local. `variableBlocksRead` / `remapVariableBlocks` (internal, in
  `CeresExpression.kt`) rewrite each sheet-numbered expression to its own block list. `evaluateOnJvm`
  reads residuals back at a solution.
- **Loss**: `RobustLoss.NONE`. The default Huber(1e-3) would cap the influence of exactly the
  residuals that are meant to reach zero.

## Strengths and the solve

Residual multipliers (Ceres squares them): `REQUIRED = 1`, `EDIT = 1e-2`, `STAY = 1e-4`.

1. **Phase A, edit held.** Variables whose input differs from `SpreadsheetStoredState.solved` were
   edited; they are substituted as constants (clamped into their own bounds). Others are free with a
   STAY pull to their previous value. If every assertion holds, done: the edit is exact.
2. **Phase B, edit yields.** Only if phase A leaves something violated: the edited variables become
   free, pulled at EDIT toward what was typed. Taken only if it violates fewer assertions.
3. **Re-anchoring.** Each phase runs `PASSES = 3` solves, re-anchoring the stays at the last
   solution (edit pulls keep pointing at what was typed).

Measured on the temperature sheet (C prev 20, F prev 0, `F eq C*1.8+32`), equality residual after
1/2/3/4 passes: `1.6e-3`, `3.8e-8`, `8.9e-13`, `0`. With C edited to 100: F off by `1.4e-2`,
`1.4e-6`, `1.4e-10`, `2.8e-14`. One weighted pass is visibly wrong at four decimals; three is
below the 10-significant-digit write-back. A solve of this size took 1.3–4 ms in the jvmTest probe (compile included) and 1.6–30 ms in a
live Field, the slow one being the first after the library loads.

Weights instead of a true lexicographic solve because phase A makes the common case (an edit that
can be met) exact anyway, and re-anchoring removes the remaining stay leak geometrically.

## Traps found

- **A weighted pull into a Ceres bound stalls.** Phase B with the edit at EDIT strength pushing a
  variable into its bound (`C le 50`, C typed as 100) left F `8e-4` off its equality on every later
  pass: the projected LM step is rejected and the trust region collapses. Fix: clamp pinned edits
  into their bounds in phase A, so a bound conflict never reaches the minimiser.
- **Bounds are harder than other requireds.** A simple bound is a Ceres hard bound; an equality is a
  weighted residual. When they conflict (the budget page example) the equality is the one shown
  violated. Documented on the page; a true priority order among requireds is not attempted.
- **The formula REPL cannot see `internal`.** Functions the formula source calls must be public
  (`SpreadsheetEvalHelpers.sqrt` etc.); an internal one fails every formula's compile.
- **Edit detection needs a record that survives.** An in-memory "last solved inputs" missed edits
  made before the sheet document existed (`editSheet` before the iframe opened) and across restarts.
  It is now `SpreadsheetStoredState.solved` (additive JSON field), carried through the editor's
  whole-state `update`.
- Write-back is 10 significant digits; a re-solve that changes nothing at that precision writes
  nothing, so recomputes (every `sheetSnapshot()`, every poll) do not churn the property.

## Not done

- Strength per variable (`free(3, weak)`), vector variables, drag-to-edit on a variable cell.
- Variables from other boxes' code are read as numbers; another box cannot assert over them.
- The solve runs on the main thread inside `computeSnapshot`, like formula evaluation.
