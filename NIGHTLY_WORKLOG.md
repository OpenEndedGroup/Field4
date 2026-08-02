# Nightly worklog — 2026-08-02 — polytope public explainer (kind C, documentation)

Branch: `nightly/2026-08-02/3-polytope-public-explainer`
Base: `590e1f7812bd174a4b2868ddd8a5c955312df0ae`
Worktree: `/Users/marc/field4-worktrees/2026-08-02-3-polytope-public-explainer`
Assigned port: **9603**

## Plan

`e4b309379` landed ~4200 lines of `afield.stdlib.polytope` with excellent KDoc, five test files,
one loadable transcript (`polytope_conway_demo.yaml`) and an architecture doc
(`dev_docs/POLYTOPE_ALGEBRA.md`) — and **zero public presence**: nothing under `public/docs/`, no
nav entry, and the committed codebase reference under `public/docs/reference/` does not mention it
at all.

### Scope

I own `public/docs/**` and `public/zensical.yml`. Two sibling nightly agents own
`src/jvmMain/kotlin/afield/stdlib/polytope/**` + `dev_docs/POLYTOPE_ALGEBRA.md` (canonical
realization) and `FLine.tessellate` (audit). I will not edit those. Defects found while documenting
get recorded here with `file:line`, not fixed.

### The page

One substantial page, `public/docs/polytope_examples.md`, nav-wired under
*Staging area → Drawing From Data* next to *Meshes & 3D Geometry*. Working title:
**"Polytopes: topology first, coordinates later"**. The thesis the page has to teach, in order:

1. **An incidence topology owns identity.** Ranked elements + adjacent-rank incidence + explicit
   ordered face cycles. Not a mesh. `PolytopeTopology` has no coordinates, no `FLine`, no
   triangles, no renderer (`Topology.kt:105-118`).
2. **Coordinates are a separate, optional layer.** `Realization3` is an immutable ownership
   boundary around Field's mutable `Vec3` (`Solids.kt:10`); `PolyhedralObject.realization` is
   nullable (`Solids.kt:24`).
3. **The Conway operator vocabulary**, with a figure per operator: primitives `d a k t g`,
   composites `j n z o e s m b`, the prefix parser over seeds `T C O I D`, and indexed `kN`/`tN`.
4. **Provenance survives every operator.** `lineage` / `sourceElements` / `provenanceRole`, and
   what that buys: selecting by where an element *came from*, not by index.
5. **Selectors and deployed scalar parameters.** Non-uniform `kis`, edge-varying `gyro`,
   attribute-driven `truncate` fractions.
6. **Realization comes last, and the solver is honest about what it is.** `solveRealization` is
   constraint *repair/relaxation* with before/after diagnostics — explicitly **not** a canonical
   realization algorithm (`RealizationSolver.kt:51-57`). The page must preserve that honesty.
7. **Field output.** One pickable `FLine` per polygon face; provenance-preserving triangles.

### Evidence plan — every figure a real capture

House mechanism: `diffstatic_runner` fences with `capture="box" open-outputs="true"`, which run the
snippet in a real Field box and screenshot the real output areas. `threeLines(...)` is already
proven capturable this way (`public/docs/fline_examples.md:157`, asset
`assets/fline_examples/three_lines_static.png` — verified by eye: real WebGL render, not a stub).

Figures, and what makes each honest:

| # | Figure | Capture path |
|---|--------|--------------|
| 1 | Cube incidence read out (counts, a real face cycle, an edge's endpoints, Euler χ) | `capture="box"`, markdown from real API calls |
| 2 | Operator gallery — every primitive + composite on one seed, laid out in one `threeLines` grid with a generated legend | `capture="box"`, real 3D render |
| 3 | Topology-only dual: `realization == null`, `toFaceFLines()` genuinely refusing, then a realization policy applied | `capture="box"`; the refusal is a caught real exception message, not a quoted one |
| 4 | Lineage/provenance card for a fixed output face — the *same* query the demo's click handler runs | `capture="box"`; caption states it is invoked for a fixed face id rather than mouse-picked |
| 5 | Non-uniform parameter field painted onto output faces (selective `kis`, per-face colour from the deployed scalar) | `capture="box"` + `threeLines` |
| 6 | Realization before/after with real solver diagnostics | `capture="box"`; numbers printed by `geometryDiagnostics()` |
| 7 | Triangulation provenance (polygon → triangles, flat normals, duplicated corners) | `capture="box"` |

No figure will be a hand-drawn SVG and no number will be typed by me. If a capture cannot be made
to work, the figure is dropped and the page says so.

### Codebase reference

`StdlibReferenceGenerator` walks `src/jvmMain/kotlin/afield/stdlib` recursively
(`StdlibReferenceGenerator.kt:318`), so the polytope KDoc *should* already be reference material —
but no committed page mentions it, which means `checkCodebaseReference` is drifted on `main`.
Verify, then regenerate `public/docs/reference/**` so the polytope KDoc is browsable, and link the
new page to it. Do this **late** so a sibling's source change causes the least churn.

### Loadable artifact

A second operation transcript, modelled exactly on `polytope_conway_demo.yaml`: same one-question
-per-box discipline, materialized bundle under `public/docs/assets/agent_transcripts/<name>/` via
`AgentTranscriptMaterializer`, registered in `DemoTranscriptExecSmokeTest.kt`'s `EXECUTE` set
(`src/jvmMain/kotlin/afield/examples/DemoTranscriptExecSmokeTest.kt:119`).

### Verification

`uv run --group docs python tools/build_zensical_docs.py --page polytope_examples`, then serve
`public/field4` on 127.0.0.1:9603, fetch the page and **every** asset it references, confirm 200,
and look at each figure.

---

# Result

**Status: done. Recommendation: merge.**

Served and verified at **`http://127.0.0.1:9603/polytope_examples/`**
(`cd <WT>/public/field4 && python3 -m http.server 9603 --bind 127.0.0.1`).

## What changed

36 files, +2941 / −55. Five commits.

| area | files |
|---|---|
| the page | `public/docs/polytope_examples.md` (new, ~900 lines) + 16 PNGs under `public/docs/assets/polytope_examples/` |
| nav | `public/zensical.yml` — *Drawing From Data → Polytopes & the Conway Algebra*, and *Reference → Codebase Reference → Uncategorised* |
| loadable demo | `dev_docs/agents/transcripts/polytope_conway_gallery.yaml` (new) + its materialized bundle `public/docs/assets/agent_transcripts/polytope_conway_gallery/` (7 files) |
| demo registration | `src/jvmMain/kotlin/afield/examples/DemoTranscriptExecSmokeTest.kt` (added to `EXECUTE`), `dev_docs/agents/transcripts/README.md` |
| gallery index | `public/docs/agent_transcripts.md` — entries for **both** polytope transcripts (`polytope_conway_demo` had been materialized since it landed but never listed) |
| codebase reference | `public/docs/reference/uncategorised.md` (new), `reference/index.md`, `reference/graphics.md`, `dev_docs/kdoc_worklist.md` — all regenerated |

I did **not** touch `src/jvmMain/kotlin/afield/stdlib/polytope/**` or `dev_docs/POLYTOPE_ALGEBRA.md`,
which the two sibling agents own tonight.

## The page

`public/docs/polytope_examples.md` — *"Polytopes: topology first, coordinates later"*. It teaches
the model in the order the library is built:

1. a polyhedron is not a mesh — ranked incidence, adjacent-rank only, explicit face cycles,
   globally-scoped element ids, manifoldness as an opt-in stricter pass;
2. coordinates are a second, **nullable**, replaceable layer;
3. the thirteen-operator vocabulary, with galleries for the seeds, the five primitives and the eight
   composites, and the two orders (`notationHistory` vs `history`) made explicit;
4. prefix notation, right-to-left, `kN`/`tN`, and the parser's own refusals;
5. provenance — lineage trees, role histograms, `sourceElements`;
6. selectors, including the negated-provenance trap;
7. deployed scalar parameters, and a parameter field painted back onto the *triangles* through the
   provenance map;
8. realization as **repair, not canonicalisation** — the source's own caveat is quoted verbatim and
   the "does not claim" list (local method, no symmetry model, no self-intersection check) is kept;
9. `toFaceFLines()` and provenance-preserving `triangulate()`;
10. the registered value printers;
11. **what this does not do yet**, taken from the architecture note rather than smoothed away.

## Verification — what I actually observed

- **Built**: `FIELD_DOCS_RUNNER_PORT=9603 uv run --group docs python tools/build_zensical_docs.py --page polytope_examples`
  → `regenerated=16 … failures=0`, `EXIT=0`, doc-asset check `No issues found` (every referenced
  asset git-tracked).
- **Served + fetched**: page 200; **109/109** referenced URLs on the built page return 200; 16
  `<img>` elements, all with non-zero `naturalWidth`; **zero** console errors and **zero** failed
  requests (driven with Playwright against the served site).
  `reference/uncategorised/` 200, `agent_transcripts/` 200,
  `assets/agent_transcripts/polytope_conway_gallery/metadata.edn` 200.
- **Eyeballed**: every one of the 16 PNGs opened and read individually, plus 20 full-page
  screenshots of the served page at 1400×1100 from top to bottom. Nothing black, clipped, garbled,
  or overlapping; code fences highlight, `:::fold` blocks collapse with their labels, the admonition
  renders, the nav entries resolve.
- **Transcript**: `macro run polytope_conway_gallery` against a live Field → `applied=9 skipped=0
  errors=0`, all four boxes producing output. Then the real gate:
  `python3 tools/run_field_tests.py --id examples.demo-transcript.exec --port 9603` → **PASS**
  (130 s), which compiles/runs every shipped transcript's box bodies including the new one.
- **Materializer drift guard**: `AgentTranscriptMaterializer` in default (temp) mode →
  `ok — materialized 39 document(s)`, committed bundle clean.
- **Codebase reference**: `./gradlew --no-daemon checkCodebaseReference` → **BUILD SUCCESSFUL**
  (it was **failing** at BASE — see below).

### Which figures are real captures, and how

**All sixteen.** Every one is a `diffstatic_runner` fence: the docs runner creates a real box in a
real Field, runs the Kotlin shown above the figure, and screenshots the box's output area.

| figure | what produced it |
|---|---|
| `topology_readout`, `abstract_dual`, `operator_table`, `parser_table`, `lineage`, `selectors`, `face_fline_properties`, `triangulation` | `markdown(...)` built from live API calls — every count, boolean and message is printed by the library. The two refusals in `abstract_dual` and all five parser errors are **caught exceptions**, not quoted text. |
| `seeds_gallery`, `primitives_gallery`, `composites_gallery`, `gyro_boundaries`, `parameter_field`, `realization` | Field's own CPU mesh rasteriser (`TriMesh.renderPng`, the AWT-free `PngEncoder` path) fed with `toTriMesh()` / `triangulate()`. Captions are read off the **topology**, not the mesh. |
| `face_flines` | the live on-canvas `threeLines(...)` WebGL viewer, screenshotted through the same Playwright page the docs runner always uses. |
| `value_printers` | Field's reflective value visualizer via `viz(...)`, showing the registered polytope printers. |

**Nothing is synthesised, and nothing is omitted for lack of a capture path.** The one thing the
page describes but cannot show is the *interactive* click-to-inspect provenance card — that needs a
mouse. The page says so and points at the loadable transcripts, which do exactly that.

## The loadable demo

`dev_docs/agents/transcripts/polytope_conway_gallery.yaml` — four small boxes (480×88 each; output
areas grow from them, no fake desktop-sized rectangles), one question each, laid out 2×2 with wide
spacing.

Replay from the repository root:

```bash
uv run --project . --group backdoor python tools/field_backdoor.py --json --port 9477 \
  --command 'macro write polytope_conway_gallery --file dev_docs/agents/transcripts/polytope_conway_gallery.yaml' \
  --command 'macro run polytope_conway_gallery'
```

**The one thing to change:** box 1's first statement,
`val expressions = listOf("C", "dC", "aC", "kC", "tC", "gC", "bC", "k4aC", "sD")`. Put any prefix
expression over the seeds `T C O I D` and the operators `d a k t g j n z o e s m b` (with optional
`kN`/`tN`) in that list and re-run the box. The whole contact sheet rebuilds — including the
captions, which are element counts, face-degree histograms and Euler characteristics read off the
resulting **topology**, so they cannot agree with the pictures by accident. Try `k5D`, `sI`, `bbC`.
A bad expression does not crash the box: `conway` throws a `ConwayParseException` naming the
character offset. (Each of the other three boxes carries its own `***** CHANGE THIS *****` marker.)

## Defects found and deliberately NOT fixed

1. **`./gradlew checkCodebaseReference` was already red at BASE** — `e4b309379` added 11 source
   files and 299 public symbols without regenerating, so `check` was failing on `main`. I *did* fix
   this (it is `public/docs/**`, my scope, and the mission asked for the KDoc to be surfaced), but
   the underlying cause is unfixed:
   **`src/jvmMain/kotlin/afield/stdlib/polytope/*.kt` carry no `@file:CompletionCategory`**, so
   `StdlibReferenceGenerator.inferredCategory` (`src/jvmMain/kotlin/afield/docs/StdlibReferenceGenerator.kt:387`)
   drops all nine of them into a bucket literally titled **"Uncategorised"**, which is now a public
   nav entry. That is also why the editor's autocomplete has no category for them.
   *Fix (one line per file, sibling's territory tonight):* add
   `@file:CompletionCategory("Graphics")` — or a new `"Geometry"` — above the `package` line of each
   polytope source, then `./gradlew generateCodebaseReference`. The `uncategorised.md` page and its
   nav entry then disappear on their own. *Reproducer:* `./gradlew checkCodebaseReference` at
   `590e1f781` → `uncategorised.md: missing from public/docs/reference`.

2. **KDoc coverage on the polytope family is 19%** (57 of 299 public symbols) — the *file* and
   *type* KDoc is excellent, but most members and top-level functions have none, so the reference
   page has many blank Summary cells. See `dev_docs/kdoc_worklist.md`. Not a bug; a gap.

3. **Three operators have no named Kotlin extension.** `NEEDLE`, `ZIP` and `ORTHO` exist in the enum
   and in `primitiveExpansion` and are reachable through the parser and
   `transform(ConwayOperator.NEEDLE)`, but there is no `PolyhedralObject.needle()` /`.zip()`/`.ortho()`
   next to `join`/`expand`/`snub`/`meta`/`bevel` (`Transforms.kt:938-950`). The page uses the
   notation form throughout so it reads consistently, and says so.

4. **The composites have no chainable overload.** `join`/`expand`/`snub`/`meta`/`bevel` are declared
   on `PolyhedralObject` only, not on `PolytopeTransformResult` (`Transforms.kt:938-950`), so
   `cube().ambo().bevel()` does not compile while `cube().ambo().kis()` does.

5. **`showMesh`'s automatic caption is actively misleading for a polytope.** It reports the *mesh's*
   topology, and because `triangulate()` deliberately duplicates every polygon corner, a closed cube
   renders as "χ=6 · open (6 boundary loop(s))" (`mesh_field.kt:379-392`). True, but it reads as a
   bug. Every figure on the page therefore captions itself from the topology; the one place the
   mesh's own view appears is the triangulation figure, where it is the point.

Two docs-runner facts that cost me a build each, recorded for the next person: a `capture="box"`
clip only reliably contains **one** output disclosure, and `println` output is not in it at all —
so a fence that wants a picture *and* numbers must emit both from a single `display(...)`. And a
failed capture still leaves a bogus PNG on disk from the trailing `screenshot-box` command, which
the next build then happily reuses as "existing checked-in asset"; delete it before re-running.

## Risks

Low, and confined to review churn: the two sibling agents own the polytope sources, and if either
adds or renames a public symbol tonight then `public/docs/reference/*.md` drifts again and conflicts
with this branch. The resolution is mechanical — `./gradlew generateCodebaseReference` after the
merge. Nothing else here depends on their work; the page pins no behaviour those agents are
changing except `solveRealization`, and it deliberately documents that as relaxation rather than
canonicalisation, which stays true if a `canonicalize` is added beside it.

## Recommendation

**Merge.** The page is complete and verified, it makes a 4200-line library findable for the first
time, and it drags the codebase-reference guard from red back to green on the way.
