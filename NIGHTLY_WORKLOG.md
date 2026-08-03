# Nightly 2026-08-03 — branch 3: a real Geometry & solvers reference section

Kind: **C (documentation)**. Branch `nightly/2026-08-03/3-geometry-reference-section`,
based on `a2dc947b7`.

## Plan

### The problem, restated against the code

`public/docs/reference/uncategorised.md` exists because `StdlibReferenceGenerator.inferredCategory`
(`:388`) has a last-resort bucket, and none of the 17 files in
`src/jvmMain/kotlin/afield/stdlib/polytope/` carries a `@file:afield.CompletionCategory`. Measured
baseline, from the generator's own output:

| where | files | symbols | with KDoc |
| --- | --- | --- | --- |
| `reference/uncategorised.md` (all of `afield/stdlib/polytope/`) | 16 | 510 | 138 (27%) |
| `afield/stdlib/ceres/` inside `reference/graphics.md` | 3 | 498 | **0** |

(16 not 17: `Off.kt` has no public symbols the parser emits — checked at plan time, confirm on
regeneration.) The three `ceres/` files — `CeresGeometry.kt` 271 symbols, `ArchitecturalGeometry.kt`
184, `InteractiveSolving.kt` 43 — are filed under `"Graphics"`, which is wrong twice over: a
nonlinear-least-squares solver is not drawing, and 498 symbols of solver vocabulary is the single
largest undocumented block in the corpus.

`public/zensical.yml:36-38` already carries a comment admitting the whole thing.

### Part one — categories (mechanical, but two real decisions)

**Two pages, not one.** `"Polytopes"` for `afield/stdlib/polytope/`, `"Solvers"` for
`afield/stdlib/ceres/`. Why two:

- They are independently usable. `ArchitecturalGeometry.kt` solves gridshell/panel layouts with no
  polytope anywhere; `Realize.kt`/`Canonical.kt` reach a canonical embedding with their own
  gradient solver and never require Ceres (`CeresRealization.kt` is an *optional* bridge).
- Size. One page would be ~1000 symbols and unnavigable; the existing pages top out around 700.
- The names have to read well in the completion card too (`**Category** `Polytopes``), and they do.

**Why not `"Geometry"`.** `KotlinCompletionSupport.normalizedCompletionCategory` maps
`"geometry" -> "Graphics"`, so a file annotated `"Geometry"` would appear as **Geometry** on the
reference page and as **Graphics** in the editor's completion card — the two surfaces the mission
asks to agree. `"Polytopes"` and `"Solvers"` are not aliases in that table and pass through
titlecased-unchanged, so both surfaces say the same word. This is a real trap, recorded here.

`CeresRealization.kt` stays in `"Polytopes"`: it is the polytope-side adapter, and it is what a
reader of `realize` needs next.

Work: 17 + 4 file annotations (`FfmCeresBackend.kt` keeps `"Internal"`), two `CATEGORY_BLURBS`
entries that introduce the subsystems rather than label them, `zensical.yml` nav (drop
`Uncategorised`, add the two, remove the stale comment).

### Part two — the KDoc (the actual deliverable)

Order of attack, taken from `dev_docs/kdoc_worklist.md`'s own payoff ranking rather than invented:

1. `Conway.kt`, `Transforms.kt` — the operators and their composition rules. `Transforms.kt` alone
   is 36 undocumented entry points at 15% coverage and is the file every worked example calls.
2. `Realize.kt`, `Canonical.kt`, `RealizationSolver.kt`, `Constraints.kt` — `realize`/`relax`/
   `canonicalize` and the solver behind them.
3. `FieldAdapters.kt` — the FLine bridge.
4. `Attributes.kt`, `Parameters.kt` — attribute/parameter algebra.
5. `Topology.kt`, `Polytope4.kt`, `Solids.kt`, `Symmetry.kt`, `Summaries.kt`, `Off.kt`,
   `PolytopeVisualizers.kt`, `CeresRealization.kt`.
6. `CeresGeometry.kt` constraint vocabulary + `CeresExpression.kt` VM; `ArchitecturalGeometry.kt`
   and `InteractiveSolving.kt` as far as time allows.

Rules I am holding myself to, from `dev_docs/kdoc_reference_contract.md`:

- First paragraph is a claim about *behaviour*, not a restatement of the signature. What it is for,
  what it costs, what it will not do.
- Verify against the implementation, not the name. Where behaviour is surprising, the KDoc says so.
  (Already found one: the composite Conway operators are declared on `PolyhedralObject` only, so
  `cube().ambo().bevel()` does not compile — to be confirmed and documented, not papered over.)
- Never a nested `/**` or `*/`; block immediately above the declaration, whole-line annotations only
  in between; skip trivia rather than inflate the coverage number.
- Cross-link `public/docs/polytope_examples.md` <-> the new reference pages both ways.

### Verification

`./gradlew compileKotlinJvm`, `generateCodebaseReference`, `checkCodebaseReference`,
`checkKDocAttachment`; then a real docs build of the page and a look at it over
`http://127.0.0.1:9603/`; then a spot-check that two or three of the new summaries are the same text
the editor's completion documentation panel resolves.

---
