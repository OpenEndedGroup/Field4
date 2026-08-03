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

(16 not 17, and the reason turned out to matter — see "A pre-existing drift-guard failure" below.)
The three `ceres/` files — `CeresGeometry.kt` 271 symbols, `ArchitecturalGeometry.kt`
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

## What was done

### 1. Categories (mechanical part, as planned)

- 17 `@file:afield.CompletionCategory("Polytopes")` annotations added to
  `src/jvmMain/kotlin/afield/stdlib/polytope/`.
- 4 files in `src/jvmMain/kotlin/afield/stdlib/ceres/` re-filed from `"Graphics"` to `"Solvers"`
  (`FfmCeresBackend.kt` keeps `"Internal"` and stays unpublished).
- Two `CATEGORY_BLURBS` entries in `StdlibReferenceGenerator.kt`, positioned after `Graphics` in the
  page order. Each ends with a **reading order**, because the generator lists a category's files
  alphabetically and alphabetical is not where a new reader should start.
- `public/zensical.yml`: `Uncategorised` removed from the nav (and the stale comment that explained
  why it existed); `Polytopes` and `Solvers` added.

**Why two pages, not one.** They are independently usable — `ArchitecturalGeometry.kt` solves
gridshells and panel layouts with no polytope anywhere, and `canonicalize` reaches the canonical form
with its own reciprocation/relaxation iteration and never needs Ceres (`CeresRealization.kt` is an
optional bridge). And the sizes settle it: 533 + 641 symbols on one page would be unnavigable.
`CeresRealization.kt` stays in `Polytopes`, because it is the polytope-side adapter and it is what a
reader of `realize` needs next.

**Why not `"Geometry"`.** `KotlinCompletionSupport.normalizedCompletionCategory` maps
`"geometry" -> "Graphics"`. The reference generator reads the raw annotation string; completion reads
the normalized one. A file annotated `"Geometry"` would therefore appear as **Geometry** on the
reference page and **Graphics** in the editor's completion card — the exact divergence this task asks
to avoid. `"Polytopes"` and `"Solvers"` are not aliases in that table and pass through titlecased
unchanged, so both surfaces say the same word. This is a live trap for anyone adding a category.

### 2. The KDoc (the deliverable)

All 21 files were worked. Every **public entry point** in `afield/stdlib/polytope/` now carries KDoc —
`dev_docs/kdoc_worklist.md` goes from 4 files with undocumented entry points to zero for the package.
Each file also gained a file-level overview, which is what supplies the section prose on the category
page and the "What it is" cell on the reference index; the polytope package previously had none at
all, which is why every one of those cells was blank.

The rule held throughout: **skip trivia rather than inflate the number.** Roughly 146 polytope members
are still blank on purpose — `equals`/`hashCode`/`toString`, `Element.rank`, four `Int`s on a summary
named after what they count. In each case the only true thing left to say was the signature.

### 3. What reading the code turned up

This is the part that could not have been written from the signatures. Everything below is now in the
KDoc, and therefore on the reference page and in the completion panel.

**A row that was published wrong.** In `Conway.kt` the KDoc block describing `ConwayExpression` sat
above `ConwayInvocation`. `public/docs/reference/uncategorised.md:297` shipped
`ConwayInvocation` captioned *"Parsed standard prefix expression. `operators` are stored in execution
order, seed outward."* — a description of a different type, referring to a property
`ConwayInvocation` does not have — while `ConwayExpression`'s own row was blank. This is precisely the
structural blind spot `dev_docs/kdoc_reference_contract.md` documents ("What the guard is *not*"), and
`checkKDocAttachment` cannot see it because the block *does* legally attach to something. Fixed, and
both types now have their own.

**The composition rules.** The eight composite Conway operators are declared on `PolyhedralObject`
only, so `cube().ambo().bevel()` does not compile while `cube().ambo().kis()` does; and `needle`, `zip`
and `ortho` have no extension function at all. The escape in both cases is
`transform(ConwayOperator.BEVEL)` or the notation form. Stated in the `Transforms.kt` overview and on
`PolytopeTransformResult.transform`, not papered over.

**Parameters that are not what their names suggest.** `KisFaceNormalRealization.heightScale` is a
multiple of the *face's own mean edge length*, not a distance — so the same number gives comparable
spikes on a triangle and a decagon and at any object scale. `TruncateAlongEdgesRealization.fraction`
and `GyroSubdivisionRealization.edgeFraction` are bounded strictly below 0.5 because at 0.5 the two
points on a shared edge coincide and the edge collapses. The default 1/3 for truncation is *not* the
value that makes a truncated Platonic exactly Archimedean; the KDoc says so and points at
`canonicalize`.

**Two genuine defects, documented rather than fixed** (this is a docs branch), and filed as follow-up
task chips:

- `Symmetry.kt`'s `combinatorialOrbits()` tries to exit early with `if (count == classes)
  return@repeat`. Inside `repeat { }` that is *continue*, not break, so the refinement always runs its
  full `elements + 2` rounds. The answer is right; the work is wasted.
- `CeresRealization.relaxWithCeres` wraps `CachedCeresSolvers` in `use { }`, so the cache whose whole
  purpose is to compile an unchanged program once is created and destroyed inside every call.

**Two existing KDoc claims that were false**, both corrected:

- `weightedResidualRms` claimed to be "the one `RealizationSolveResult.rmsResidual` reports". True only
  of the Ceres path: `relax` reports Levenberg-Marquardt's own double-precision value at *pre-gauge*
  coordinates, while `relaxWithCeres` re-evaluates the formula on gauged, float-truncated ones.
- `CentroidConstraint` told callers to `pass doubleArrayOf(0.0, 0.0, 0.0)` to a parameter typed
  `List<Double>?`. Repointed at `AT_ORIGIN`.

**A documented claim that the code does not make.** `dev_docs/POLYTOPE_ALGEBRA.md` says
"`PolyhedralObject` refuses a rank-4 topology", and `Polytope4Test
.aRankFourTopologyCannotBeMistakenForAPolyhedron` appears to prove it. Construction is in fact
permissive; the test passes because `triangulate()` throws on the *null realization*. The real
enforcement is `require(topology.rank == 3)` in `SurfaceContext.init`. The KDoc now describes the
permissive behaviour, and a task chip is filed to fix the dev doc and the test's reason for passing.

**Verified numerically rather than asserted.** Every element count and solid name in the new KDoc was
checked by running the code: `aC` 12v/24e/8 triangles + 6 squares with every vertex of degree 4; `kC`
24 triangles; `tC` 8 triangles + 6 octagons; `jC` 12 quadrilaterals; `eC` 8 + 18; `sC` 32 + 6; `mC` 48
triangles; `bC` 12 + 8 + 6; `gD` 60 pentagons on 92 vertices; `k7C` returns the cube unchanged, which
is the claim that a degree selector matching nothing is a legal no-op. And the seed circumradii: the
cube is the only Platonic seed off the unit sphere, at exactly √3 — now called out in `Solids.kt`
because it is a latent trap when mixing seeds.

**Smaller traps, all now stated:** `NonReciprocalIncidence` is unreachable by construction; an
incidence between non-adjacent ranks is silently dropped rather than repaired;
`isCombinatoriallyIsomorphicTo` returns false for non-rank-3 input but *throws* for a rank-3 face with
no stored cycle, and treats a solid and its mirror as equal; `Realization3.get` returns a fresh `Vec3`
so `realization[i].x = 3f` compiles and does nothing; `Polytope4.dual()` drops the realization so
`dual().project(...)` throws; a selector cannot choose its rank, the operator does, so
`kis(ElementSelectors.rank(0))` silently kises nothing; `PolytopeAttributes.with(e, name, null)`
records an entry while `with(e, emptyMap())` records nothing; `EdgeTangencyConstraint` has a
zero-cost minimum at a collapsed edge that `relax` does not guard against; OFF face indices are
positions in `elements(0)`, not `ElementId` values; and in `CeresExpression.kt` the mixed-arithmetic
operators are declared on the expression only, so `expr * 2.0` compiles and `2.0 * expr` does not.

### 4. A pre-existing drift-guard failure, fixed in passing

The committed `public/docs/reference/` was **already stale at the base commit**. It was last
regenerated by `2f5389877`; `CeresRealization.kt` arrived in `9a71f66d9` and `CeresExpression.kt` in
`e3c1c029a`, and neither has a section anywhere under `public/docs/reference/` — which is why
`uncategorised.md` counted 16 files for a 17-file directory, and why `CeresExpression`'s 143 public
symbols appear nowhere. Both files exist at `a2dc947b7` with public surface, so
`./gradlew checkCodebaseReference` was failing on `main` before this branch touched anything. The
regeneration here fixes that too, and the global count moves from 5709 symbols across 112 files to
5875 across 114 for that reason rather than because anything was added.

### 5. Audit of the other categories

Asked whether any other file is filed wrongly. Every file in `afield/stdlib/` and
`afield/visualization/` was checked. Only `Colormaps.kt` and `heatmap.kt` carry no annotation, and both
are under `afield/visualization/`, where `inferredCategory` files them as `Visualization` — correct, so
they are left alone. The two arguable cases are `quantilewarp.kt` and `graph_connectivity.kt`, both
under `Graphics`: the first packs its knots into shader push constants, the second is computational
geometry over point sets, and the `Graphics` blurb explicitly covers "geometry". Both defensible;
**changed nothing**, to keep generated-output churn to what this change actually requires.

### 6. Cross-links

- `public/docs/polytope_examples.md`'s standing note used to apologise for the `Uncategorised` page;
  it now points at `reference/polytopes.md` and `reference/solvers.md`, and the "See also" section
  gained a two-line entry for each ("this page is the tour; that one is the index").
- The `Polytopes` blurb links back to `polytope_examples.md`, so the reference page and the explainer
  reach each other in both directions.
- `polytope_examples.md` is, as of this branch, still the *only* public page about the Ceres surface —
  `dev_docs/CERES.md` is internal — which is why `reference/solvers.md` matters more than its symbol
  count suggests.

### 7. One generator fix that this change forced

Publishing `CeresExpression.kt` for the first time exposed a real hole in the reference's
"evidence-based" `**Used in:**` links. The rule was "at least two symbol names that exactly one
documented file declares appear in that page's code". `CeresExpression.kt` declares top-level `exp`,
`cos`, `tan`, `min`, `max`, `abs`, `sqrt`, `pow` and `atan2` as expression builders and is the only
file in the corpus declaring them at top level — so it uniquely owned the whole `kotlin.math`
vocabulary, and `functions_examples.md` was about to be published as a worked example of the Ceres
expression VM.

Fixed with a `GENERIC_CALL_NAMES` exclusion set. Uniqueness is a claim about the corpus; that set is a
claim about the reader. Domain verbs that happen to be short (`kis`, `dual`, `stft`) stay in.
**Measured**: regenerating to a scratch directory before and after differs by exactly one line — the
false link. No legitimate evidence link was lost.

### 8. One more thing found by actually looking at the page

Building the page and reading it turned up a rendering bug nothing else would have caught. A file
overview's own `##` subheadings were emitted verbatim inside the file's `## SomeFile.kt` section, so
they rendered as *siblings* of the file heading: `reference/polytopes.md` had 45 `h2`s of which only 17
were files, and its table of contents read as a flat list alternating "Canonical.kt" with "Two
iterations, and why there are two". Worse elsewhere — `contours.kt`, `typography.kt` and
`dynamic_graph_field.kt` start their overviews at `#`, which rendered as a **second `h1`** competing
with the page title.

`StdlibReferenceGenerator.demoteHeadings` now re-levels an overview so its shallowest heading becomes
`###`, a child of its file and a sibling of the `### TypeName` sections that follow, with deeper
headings keeping their relative depth. After: every `##` on every category page is a file section, and
no page has a second `h1`. Six pages moved, 60 lines, all heading levels.

The first attempt was wrong in an instructive way and the fix is commented at the call site:
`fencesToDiffstatic` rewrites an opening fence to `` ```diffstatic {data-lang=…} ``, which
`FENCE_OPENER` no longer matches — so a demotion pass running afterwards mistakes the *closing* fence
for an opening one and silently stops demoting from that point on. Demotion has to run first.

---

## Measured results

From the generator's own output, not estimated.

| | before (`a2dc947b7`) | after |
| --- | --- | --- |
| `reference/uncategorised.md` | 16 files, 510 symbols, 138 documented (27%) | **deleted** |
| `reference/polytopes.md` | — | 17 files, 533 symbols, **387 documented (72%)** |
| `afield/stdlib/ceres/` in `graphics.md` | 3 files, 498 symbols, **0 documented** | — |
| `reference/solvers.md` | — | 4 files, 641 symbols, **245 documented (38%)** |
| corpus-wide | 5709 symbols / 112 files, 3305 documented (57%) | 5875 / 114, **3799 (64%)** |
| `kdoc_worklist.md` undocumented entry points | 315 | **162** |
| undocumented entry points in `afield/stdlib/polytope/` | 82 across 8 files | **0** |

Files touched: **26** — 21 sources (17 `polytope/`, 4 `ceres/`), `StdlibReferenceGenerator.kt`,
`public/zensical.yml`, `public/docs/polytope_examples.md`, plus generated
`public/docs/reference/*.md` (2 added, 1 deleted, 5 modified) and `dev_docs/kdoc_worklist.md`.

Six commits, KDoc kept separate from generated output as asked.

## Verification

**Verified.**

- `./gradlew compileKotlinJvm` — clean, no new warnings. Run after every batch of edits.
- `./gradlew checkKDocAttachment` — green: *"scanned 767 file(s), 9553 KDoc block(s) … every KDoc
  block attaches to a declaration"*. Up from 9458 blocks before the ceres batch.
- `./gradlew generateCodebaseReference` then `./gradlew checkCodebaseReference` — green. **No
  `uncategorised.md` remains**, and no category page is a bucket: every one of the 21 files is filed
  under a name that describes it.
- **Docs built for real**: `uv run --group docs python tools/build_zensical_docs.py --page
  reference/polytopes.md --page reference/solvers.md` → *"No issues found"*, `regenerated=0
  skipped=404 (reused_existing=404 failures=0)`, and the asset guard *"OK — every referenced asset is
  committed."* No capture was regenerated, so no docs asset churn.
- **Served and looked at**, `python3 -m http.server 9603 --bind 127.0.0.1` from `public/field4`:
  - **`http://127.0.0.1:9603/reference/polytopes/`** — 200
  - **`http://127.0.0.1:9603/reference/solvers/`** — 200
  - `…/reference/` 200, `…/polytope_examples/` 200, `…/reference/graphics/` 200,
    `…/reference/uncategorised/` **404** (as intended).
  - Every asset the polytopes page references (14 CSS/JS/PNG/font URLs) returns 200 — checked
    individually, zero failures.
  - Cross-links resolve: `/polytope_examples/`, `/reference/solvers/`, `/completion_doc_browser/`,
    `/linear_algebra_api/` all 200.
  - Structure: `h1` "Polytopes", exactly **17 `h2`** (one per file), **148 `h3`**, 110 tables, every
    heading carrying an explicit anchor; secondary table of contents present. Zero unrendered
    markdown links.
  - Screenshots taken with headless Chrome and inspected: the blurb reads as prose and does real work,
    types in it are linkified, the honest coverage line is there, and the first file section reads
    like documentation rather than a signature dump.
- **The completion-panel claim, confirmed for four symbols.** Ran `KDocExtractor.extractKDocs` — the
  single source of every summary in the in-app completion documentation panel — over the sources via
  `jshell`, and compared its attached first paragraph to the corresponding row on the built reference
  page. `ConwayOperator`, `CanonicalMethod` (polytopes), `LockPosition`, `CeresExpr` (solvers): all
  four **match byte-for-byte** once the page's `[Symbol]` links are un-linkified. The generator's
  claim holds.
- **KDoc claims checked against running code**, not just read: all the Conway element counts and solid
  names, and the seed circumradii (see §3).

**Not verified.**

- The full test suite was not run. This branch changes no behaviour except two generator rendering
  rules, both of which are covered by `checkCodebaseReference` byte-for-byte.
- No Ceres KDoc claim was executed, because the native library is not exercised here; those were
  checked by reading `native/ken-ceres/src/ken_ceres.cpp` and `dev_docs/CERES.md`. The claims about
  *where* `lock_target` and `prior_target` are captured are C++-reading, not measurement.
- The completion **category** shown in the editor for a `Polytopes` symbol was not observed in a live
  Field session — the reasoning is from `KotlinCompletionSupport.normalizedCompletionCategory`, which
  passes both new names through unchanged.
- Line lengths: `ArchitecturalGeometry.kt`'s new comments are reflowed to 120 columns rather than the
  ~100 the rest of the package uses. The repo has no line-length linter, so this is cosmetic.

## Risks & unknowns

- **Merge churn is the main one.** Branch 1 tonight is also extending `afield/stdlib/ceres/`, so
  `solvers.md` and `kdoc_worklist.md` will conflict. **Resolve by running
  `./gradlew generateCodebaseReference` once after all branches land — never by hand.** Generated-file
  churn here was kept to what the change requires: no docs capture was re-run, and the two arguably
  miscategorised files elsewhere were left alone.
- Two generator behaviour changes (`GENERIC_CALL_NAMES`, `demoteHeadings`) affect pages outside this
  subsystem. Both were measured: the first changes exactly one line corpus-wide, the second changes 60
  lines and all of them are heading levels.
- 146 polytope and 355 solver **members** are still blank. That is the intended stopping point, not an
  oversight — `CeresGeometry.kt` in particular is 263 members of `encodeLegacy` overrides and
  constraint fields whose class KDoc already carries the claim.
- Three follow-up task chips were filed rather than fixed here: the `combinatorialOrbits` early exit,
  the `relaxWithCeres` cache, and the wrong "PolyhedralObject refuses rank-4" claim in
  `dev_docs/POLYTOPE_ALGEBRA.md` plus the test that appears to prove it.

## Recommendation

**Merge, without prejudice.**

The success criteria are met and measured: `uncategorised.md` is gone, both new pages build, serve and
read well, the drift guard and the attachment guard are green, and the panel/page equivalence is
confirmed on four symbols across both pages. Coverage on the subsystem the reference presented worst
goes from 27% to 72% (polytopes) and from 0% to 38% (solvers), with every public entry point in
`afield/stdlib/polytope/` now documented.

Three things make it worth merging beyond the coverage number. It fixes **two shipped documentation
bugs** — `ConwayInvocation` was published carrying `ConwayExpression`'s description, and `AbiV2`'s KDoc
was published as `CeresOpcode`'s — both of the kind `checkKDocAttachment` structurally cannot catch. It
fixes a **false claim** the reference was about to publish (`functions_examples.md` as a worked example
of the Ceres expression VM) and a heading hierarchy that gave several pages two `h1`s. And it clears a
**pre-existing drift-guard failure**: the committed reference had not been regenerated since
`CeresRealization.kt` and `CeresExpression.kt` landed, so `checkCodebaseReference` was already red on
`main`.

The risk is confined to generated-file conflicts with the other branches, and the remedy is one Gradle
command.
