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
