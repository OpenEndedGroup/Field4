# nightly/2026-09-10/3-simulation-reference-page

Kind **C** (documentation / explainer). Base `c08b19003`. Worktree
`/Users/marc/field4-worktrees/2026-09-10-3-simulation-reference-page`. Ports 19103 (Field, unused so
far) / 19313 (docs HTTP).

---

## Step 0 — falsification verdicts

Every verdict below is one command, quoted, with its population.

**0a. Is `Simulation` really a category eight files chose, with no page?** — **Confirmed, and the
brief's guess about *which* eight was one third wrong.**

```
$ git grep -h -o -E 'CompletionCategory\("[^"]+"\)' -- src/ | sort | uniq -c | sort -rn
```
Population: every `CompletionCategory("…")` literal in the 20 distinct spellings that command
returns. `Simulation` is 8, ranked 13th of 20. Naming them:

```
$ git grep -l -E 'CompletionCategory\("Simulation"\)' -- src/     # 8 files
src/jvmMain/kotlin/afield/kratos/KratosLaunch.kt
src/jvmMain/kotlin/afield/kratos/KratosSession.kt
src/jvmMain/kotlin/afield/kratos/KratosStudy.kt
src/jvmMain/kotlin/afield/kratos/KratosStudyFigure.kt
src/jvmMain/kotlin/afield/kratos/KratosStudyRun.kt
src/jvmMain/kotlin/afield/kratos/SimulationResult.kt
src/jvmMain/kotlin/afield/tetgen/TetMeshViews.kt
src/jvmMain/kotlin/afield/tetgen/Tetgen.kt
```

**The other two are `afield/tetgen`, not `afield/four/physics`.** All 8 are `@file:` annotations on
line 1 (`git grep -n`, above). So `afield/tetgen` is not a *stretch* candidate — its own source has
already voted for this page, and a Simulation page without it would be missing a quarter of its
electorate. It moves into the core plan. `afield/four/physics` carries **zero**
`CompletionCategory` of any kind (population: all 16 `.kt` under that directory; the only two `@file:`
lines there are `@file:Suppress("NOTHING_TO_INLINE")` on `SimdVerlet.kt` and
`SimdVerletColliders.kt`), so it is the `Languages` case exactly and needs `CATEGORY_BY_PATH`.

**0b. Are these packages genuinely absent from every committed reference page?** — **Confirmed, four
predicates, whole population, no `head`.**

Population: the 17 files in `public/docs/reference/` (16 category pages + `index.md`) — `ls -1`,
not a sample.

| predicate | matches |
| --- | --- |
| `grep -ril kratos public/docs/reference/*.md` | 0 files (exit 1) |
| `grep -ril jolt public/docs/reference/*.md` | 0 files (exit 1) |
| `grep -ril -E 'four[./]physics' public/docs/reference/*.md` | 0 files (exit 1) |
| `grep -rl Simulation public/docs/reference/*.md` | 0 files (exit 1) |

Not one of the 17 pages contains the string `Simulation`, in any case, anywhere.

**0c. Is the absence *deliberate*, recorded somewhere?** — **No. Checked three places, all negative.**

- `EXCLUDE_PREFIXES` (`StdlibReferenceGenerator.kt:190`) has 18 entries; none names `kratos`,
  `four/physics` or `tetgen`. (Read in full, not grepped.)
- `dev_docs/kdoc_reference_contract.md` "Which packages are on the Languages page, and which are not"
  lists five deliberate non-members — `petri`, `solidsl`, `brief`, `macros`, `graphquery` — and there
  is no row for any of these three.
- `grep -rni -E 'kratos|four/physics|tetgen' dev_docs/ src/jvmMain/kotlin/afield/docs/
  src/jvmTest/kotlin/afield/docs/` returns 90-odd lines, all of them about the *subsystems*
  (`dev_docs/TETGEN.md`, four operation transcripts, two SimdVerlet notes, the worktree-retention
  policy). **Nothing in the docs machinery mentions them at all** — which is the point: they are
  absent because nobody added the root, not because anyone decided against it.

The reason the exclusion is *plausible* is worth writing down anyway: the whole `Simulation`
subsystem landed yesterday (`6788871c5` and friends), and `afield/four/physics` predates the
reference entirely.

**0d. A fourth check the brief did not ask for, and its answer matters.** `Simulation` is not the
only annotated category with no page. `Machine learning` (4 files, all `afield/onnx`) is the other.
The two placeholder spellings, `"…"` (3, all in test prose about the annotation) and `"Two Words"`
(1, in a probe fixture), are not real. `Search` has exactly 1 file and *does* have a page and a nav
entry, and no `CATEGORY_BLURBS` entry — so a one-file page with no blurb is precedent, and
`LanguagesCategoryTest` permits it because it only demands a blurb for *path*-assigned categories.

---

## Plan

**Scope: three roots, not two.** `afield/kratos` (9 files, 3544 lines), `afield/four/physics`
(16 files, 8738 lines), `afield/tetgen` (2 files, 685 lines). tetgen is promoted out of the stretch
list by 0a.

1. `SOURCE_ROOTS` 13 → 16. `SourcePageGenerator` reads the same list via `sourceRoots()`, so the
   "read the source" pages follow.
2. `CATEGORY_BY_PATH` gains `afield/kratos/` and `afield/four/physics/` → `"Simulation"`. **Not**
   `afield/tetgen/`: both its files annotate themselves, so a prefix there would label nothing today,
   and if a third file lands unannotated the `Uncategorised` build failure telling the author to
   "add the annotation its package siblings carry" is the correct advice in a package where every
   sibling has one. In `kratos` it is not — 3 of 9 are unannotated — so the prefix is real work there
   (`KratosDemo.kt`, `KratosProtocol.kt`, `SimulationGeometry.kt`).
3. `CATEGORY_BLURBS` gains a `Simulation` entry. Required: once `Simulation` is path-assigned,
   `LanguagesCategoryTest.every category assigned by path is published as a page with a blurb`
   fails without one.
4. `EXCLUDE_PREFIXES`: **fewer exclusions than the brief proposed, and here is the disagreement.**
   The brief nominated `KratosDemo.kt` and `KratosStudyFigure.kt`.
   - `KratosStudyFigure.kt` is **not** a docs-figure generator and I am keeping it. Its KDoc calls
     itself "a CPU raster on purpose, through `RasterImage`: no AWT, no window, no GPU", and
     `dev_docs/agents/transcripts/kratos_parameter_study.yaml:144,146` calls
     `KratosStudyFigure.landscape(...)` and `.convergence(...)` from inside a *box*. It is a figure
     *library* a loadable document imports, which is reference material by any reading of the
     contract's bar.
   - `KratosDemo.kt` is the genuinely hard call, and I am also keeping it, against the brief. It is
     not "a FIELD_TEST runner and nothing more": its three top-level objects are `KratosDemo`
     (whose one function `body()` is imported by three committed transcripts —
     `kratos_thermal_playground.yaml:59,74`, `kratos_parameter_study.yaml:159`, and the smoke test),
     `KratosSmokeTest` and `KratosStructuralSmokeTest`. Excluding the file hides a symbol a reader
     following those transcripts must look up; including it publishes two `main` rows. Two noise rows
     cost less than a dead end. Recorded here so it can be reversed cheaply.
   - Same reasoning keeps `KratosStudyRun.kt` (`KratosStudyDemo` + `KratosStudyRun` +
     `KratosStudySmokeTest` in one file), and it is moot anyway — that file annotates itself.
   - `afield/four/physics` contributes **no** exclusions: no file under it contains `FIELD_TEST`
     (population: all 16), the one `java.awt` import is `java.awt.geom` path flattening in
     `Dyn4jScene.kt` rather than PNG writing, and the one stray top-level `fun main()` is a
     14-line "Example Usage" at `CSVReader.kt:130` sitting under real API (`MotionClip`, `Frame`).
     Deleting it would be a source change on a documentation branch; it is left, and noted.
5. Regenerate; read the **whole** diff, especially pages I did not expect to touch — `Used in:` is
   corpus-global, ranked, capped at 3, and name-matched, so links can move or vanish on untouched
   pages. Sanity-check new `Used in:` rows by hand for faked attribution.
6. Measure the completion-collision counterfactual with three denominators (see below), the way
   `EXCLUDE_PREFIXES` records its 11 / 574 / 235.
7. `public/zensical.yml`: add `Simulation: reference/simulation.md`.
8. Prose: orienting text in the blurb, plus **two-way** cross-links with `public/docs/physics.md`
   and `public/docs/tetgen.md` (derived: those are the only two of 116 top-level `public/docs/*.md`
   that carry this material — `grep -ril` per pattern over the whole directory gave
   kratos → {agent_transcripts, tetgen}, jolt → {physics}, simdverlet → {agent_transcripts,
   animation_time_model, physics}). **There is no standalone Kratos prose page**, which is a real
   gap and one the reference page has to carry more weight for.
9. Demo artifact: four Kratos operation transcripts already exist in
   `dev_docs/agents/transcripts/`; the deliverable here links them rather than inventing a fifth.

### What the completion measurement will and will not say

Structurally the widening cannot touch completion: `KotlinSourceIndex.fileCompletionCategory` reads
only a literal `@file:` annotation, `CATEGORY_BY_PATH` is read by the generator and nothing else, and
this branch adds **zero** annotations. The measurement is therefore the *counterfactual* — what
annotating instead would have cost — which is the number that justifies the mechanism. Reported with
denominators.

### Risk register (opened now, resolved at the bottom)

- **R1 — I own `public/docs/reference/**` tonight, and agents 1 and 2 are adding public symbols
  under other roots.** Their symbols change `Used in:` ranking and `[Symbol]` resolution corpus-wide.
  My regenerated pages *will* be stale the moment either lands. This is the contract's
  "several branches in flight" case (`kdoc_reference_contract.md:180-186`). **The human must run
  `./gradlew generateCodebaseReference` once after all three merge.** My page must survive that: it
  will, because the page's existence, its file list and its blurb are all source-side, and only the
  evidence links can move.
- **R2** — `four/physics` KDoc coverage is thin (2 of 16 files have a file-overview block in their
  first 8 lines). The page will honestly show blanks. That is the design, but it makes the blurb do
  more work.
- **R3** — 8738 + 3544 + 685 = 12967 new lines of corpus is a big widening; the `Used in:` uniqueness
  rule is corpus-global, so tokens can go ambiguous and links can *disappear* from pages I never
  touched.
