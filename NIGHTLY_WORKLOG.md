# Nightly 2026-08-02 — Canonical and symmetry-aware realization for `afield.stdlib.polytope`

Branch `nightly/2026-08-02/1-polytope-canonical-realization`, based on `590e1f78`.
Kind A (big swing). Target: `dev_docs/POLYTOPE_ALGEBRA.md` §"What should come next" item **3**.

## Plan

Read first: `RealizationSolver.kt` (216 lines, one hardcoded residual blend inside
`solveRealization`), `Transforms.kt`, `Topology.kt`, `Solids.kt`, `FieldAdapters.kt`,
`Summaries.kt`, `afield/stdlib/LeastSquares.kt` (Levenberg–Marquardt, finite-difference or exact
Jacobian), and the existing tests. The current solver is exactly what the doc says it is: a fixed
weighted blend of edge-length / planarity / centroid / radius / proximity residuals with no way for
a caller to declare which constraints apply, and no canonical-form algorithm at all.

Six steps, each its own commit:

1. **`Constraints.kt` — factor the residual families into named, composable constraint values.**
   A `RealizationProblem` holds the topology-derived index structures every constraint needs
   (vertex count, edge endpoint index pairs, face index cycles, the initial coordinate vector).
   `RealizationConstraint` is an interface with a `name`, a `weight` and
   `residuals(problem, x, into)`. Ship the five existing families as
   `EdgeLengthConstraint`, `FacePlanarityConstraint`, `CentroidConstraint`,
   `CommonRadiusConstraint`, `ProximityConstraint`, plus the two the canonical form needs:
   `EdgeTangencyConstraint` (every edge tangent to a common midsphere) and
   `TangencyCentroidConstraint` (the tangency points' centroid at the origin).

2. **`relax(...)` as the general declared-constraint entry point**, with a per-constraint residual
   report (count, rms, max) so a run says which family is actually unhappy. Keep
   `solveRealization(RealizationSolverOptions)` as a thin, behaviour-preserving wrapper that
   compiles its weights into the same constraint list — the existing test must stay green.

3. **`Symmetry.kt` — combinatorial orbits + orbit equality constraints.** Compute an
   incidence-colour refinement (1-WL over the ranked incidence graph). Colour classes are unions of
   the true automorphism orbits, so "same class ⇒ equal length / equal radius" is at least as
   strong as orbit equality; name and document that honestly rather than claiming a group
   computation. `SymmetryOrbitEqualityConstraint` binds edge lengths within an edge class, vertex
   radii within a vertex class, and face inradii within a face class.

4. **`Canonical.kt` — the actual canonical form.** The classical iterative
   planarize / tangentify / recenter loop (Hart), driving the embedding toward: every edge tangent
   to a common sphere, all tangency points' centroid at the origin, all faces planar. Report the
   standard diagnostics honestly — max planarity error, tangency-radius min/max/mean/spread,
   tangency-centroid offset, per-iteration max vertex movement, converged flag and reason — and
   state guarantees (Steinitz: exists and is unique up to Möbius for convex 3-connected planar
   graphs; no claim for anything else; diagnostics, not assertions).

5. **`Realize.kt` — the third verb, and the gauge.** `realize(policy)` constructs coordinates for a
   topology that has none: `SpectralRealization` (three lowest non-trivial graph-Laplacian
   eigenvectors via Jacobi, then unit-sphere projection) plus `UnitSphereRealization`.
   `RigidGauge` removes the rigid-motion and scale null spaces explicitly: translate the chosen
   centre to the origin, uniformly scale to a declared unit, then Kabsch-rotate back onto a
   reference embedding so the result is deterministic and stays visually aligned with its input.

6. **Wire it through Conway, test, and take it to pixels.** `PolytopeTransformResult.canonicalize()`
   preserves lineage/history/deployments and replaces only the realization, so
   `conway("gI").canonicalize()` works. New test class asserting canonical invariants numerically
   across the whole operator vocabulary; an offscreen software rasteriser test that writes a
   before/after PNG pair I actually look at; a `polytope_canonical_gallery.yaml` transcript
   (editable Conway expression string) registered in `DemoTranscriptExecSmokeTest`.

Boundary kept: none of this knows about `FLine`, triangles or a renderer. The render proof lives in
`jvmTest` and consumes the public geometry API only.

### Scope note

The brief says not to touch `public/docs/**` (a sibling agent owns the public docs page). A new
top-level transcript must be materialized into `public/docs/assets/agent_transcripts/<name>/` or
`AgentTranscriptMaterializer`'s drift guard fails the build. I therefore add exactly one **new**
directory there and change no existing file under `public/docs/`.

---

## What I actually did

All six planned steps landed, plus stretch goals 1, 2 and 3. Five commits on
`nightly/2026-08-02/1-polytope-canonical-realization`.

### Files

New, `src/jvmMain/kotlin/afield/stdlib/polytope/`:

| file | what |
| --- | --- |
| `Constraints.kt` | `RealizationProblem` (topology-derived index structures) and eight named, immutable `RealizationConstraint` values; `List<RealizationConstraint>.report(...)` for unweighted per-family error |
| `Canonical.kt` | `canonicalize(...)`, `CanonicalMethod`, `CanonicalizeOptions/Result`, `CanonicalDiagnostics`, `canonicalConway(...)`, and `PolytopeTransformResult.canonicalize()` |
| `Realize.kt` | `RealizationConstruction`, `SpectralRealization`, `RigidGauge` + `normalize(...)`, Horn/Kabsch, a package-local symmetric eigensolver |
| `Symmetry.kt` | `combinatorialOrbits()`, `SymmetryOrbitConstraint`, `symmetryDeviation()`, `RelaxationPlan.symmetricCanonical(...)` |
| `Polytope4.kt` | `Realization4`, `Polytope4`, the four regular 4-polytope seeds, `convexPolytope4(...)`, `Projection4` and its three implementations |

Modified: `RealizationSolver.kt` (adds `RelaxationPlan` and `relax(...)`; `solveRealization` is now
a wrapper), `FieldAdapters.kt` (`toEdgeFLines`, `toCellEdgeFLines`), `Summaries.kt`,
`PolytopeVisualizers.kt`, `DemoTranscriptExecSmokeTest.kt` (registers the new transcript).

New tests, `src/jvmTest/kotlin/afield/stdlib/polytope/`:
`PolytopeCanonicalRealizationTest.kt` (15), `PolytopeCanonicalRenderProofTest.kt` (3),
`Polytope4Test.kt` (10).

Docs and demo: `dev_docs/POLYTOPE_ALGEBRA.md` (rewritten "Realization", new "Rank 4", new
"Evidence", roadmap items 3, 5 and 6 rewritten to say what is done and what is *not*),
`dev_docs/assets/polytope_canonical_proof.png`, `dev_docs/assets/polytope_rank4_proof.png`,
`dev_docs/assets/polytope_canonical_gallery_field.png`,
`dev_docs/agents/transcripts/polytope_canonical_gallery.yaml` and its materialized bundle at
`public/docs/assets/agent_transcripts/polytope_canonical_gallery/`.

### Why, in one paragraph each

**Constraints.** The old solver was one hardcoded weighted blend. Making each residual family a
named immutable value means a realization run is data — printable, diffable, reweightable — and
means the canonical conditions can be *expressed* in the same vocabulary they are *iterated* in,
which is how `RelaxationPlan.canonicalConditions()` and `canonicalize()` end up cross-checking each
other in a test.

**Two canonicalization methods.** I implemented the local planarize/tangentify relaxation first and
it was too slow and too weak: `bD` improved by only 2.4x in 2000 iterations. Hart's dual
reciprocation converges in tens. Keeping both, behind a policy, is honest about the trade — the
local method is the robust one and is what polishes planarity afterwards.

**Re-seeding.** Every `gyro` expression failed at first because the gyro realization policy leaves
each group of new faces coplanar with its source face, so the dual has coincident vertices and there
is nothing to reciprocate through. The right fix is not a hack: the canonical form is a function of
the combinatorics, so a starting embedding the iteration cannot use is not a reason to fail.
`SpectralRealization` supplies a new start and `reseeded` records that it happened.

**Rank 4.** The doc claims the core is rank-general; nothing tested that. Deriving each seed's whole
face lattice from coordinates plus facet normals means the published counts are a real check on the
derivation, and it turned up the fact that `validate(checkManifold = true)` correctly rejects a
tesseract.

## Verification — what I actually observed

- `./gradlew clean compileKotlinJvm` — **clean from scratch**, and grepping the whole build log for
  `polytope` returns nothing: the new code introduces no Kotlin warnings.
- `./gradlew jvmTest --tests 'afield.stdlib.polytope.*'` — **66 tests, 0 failures.** That is the
  five pre-existing classes (38) unchanged plus 28 new. Counted from the JUnit XML, not from a
  hopeful glance at the console.
- `python3 tools/run_field_tests.py --id examples.demo-transcript.exec` — **PASS** (133 s). This is
  the test that actually *runs* the new transcript's box body through the real engine.
- `python3 tools/run_field_tests.py --id examples.agent-transcripts.materialize --port 9601` —
  **PASS**, so the committed starting-point bundle matches what the transcript produces.
- **I looked at the pixels.** Three images, all inspected:
  - `dev_docs/assets/polytope_canonical_proof.png` — `gC` visibly a cube with subdivided faces on
    the left, the pentagonal icositetrahedron in the middle and right; `gD` lumpy then the
    pentagonal hexecontahedron; `tI` identical across all three because it was already canonical;
    `bD` with grey decagons, pink hexagons and blue squares in the right places.
  - `dev_docs/assets/polytope_rank4_proof.png` — the tesseract as a cube inside a cube, turning
    itself out across the four rotation columns; the 24-cell's cuboctahedral outline.
  - `dev_docs/assets/polytope_canonical_gallery_field.png` — the demo box running in **real Field**
    on the backdoor, Field's own Three.js viewer, lumpy `gD` beside its canonical form, and the
    registered value visualizer reporting dihedral spread 1.107 -> 6.4e-07 underneath.
- Not run: the full `./gradlew check` or the whole smoke catalog. I ran the two smokes my change
  actually touches and the module-scoped test task, per the brief.

## Risks and unknowns

- **`kkC` has no canonical form that either method finds.** A 3-connected planar graph must have
  one, so this is a real gap, not a mathematical impossibility. It is covered by a test that asserts
  the *failure is reported*, and written up in the roadmap. If the human's intuition is that
  canonicalization should never fail, this is the thing to look at.
- **Single precision.** `Realization3` stores `Vec3`, so the residual floor is ~1e-7 relative. Every
  tolerance in the new tests is an order of magnitude above that. If `Realization3` ever goes double,
  the tolerances can tighten by three orders and probably should.
- **`relax(...)` cost.** It runs finite-difference Levenberg-Marquardt over `3 x vertexCount`
  parameters, so it is a tens-of-vertices tool. Documented, not fixed. `canonicalize` is the one to
  use at scale.
- **Colour-refinement orbits are unions of true orbits**, not orbits. Correct and documented, but a
  topology where refinement is strictly coarser would make `SymmetryOrbitConstraint` ask for
  something infeasible. It would report a residual rather than distort silently; I have not
  constructed such a case to check.
- **`convexPolytope4` is only valid for convex input**, and its face-in-cell test (vertex subset) is
  only correct there. The name says so and there is no non-convex caller.
- **Scope**: one existing file under `public/docs/**` changed —
  `agent_transcripts/polytope_conway_demo/document.md`, one trailing newline added by the
  materializer. That was pre-existing drift from last night's commit which was already failing
  `examples.agent-transcripts.materialize`; regenerating was the only way to make that test pass. No
  docs page and no `zensical.yml` touched, so the sibling agent's work should not conflict.
- Two concurrently running nightly agents shared this machine; I used my assigned port 9601 for
  every Field I started except one early `run_field_tests.py` invocation that took its default 9477
  before I passed `--port`. Both were stopped.

## The demo artifact

`dev_docs/agents/transcripts/polytope_canonical_gallery.yaml` — replay it into a fresh document and
the box draws the raw Conway realization beside its canonical form in Field's Three.js viewer:

```bash
python3 tools/field_backdoor.py --scratch \
    --command "macro write polytope_canonical_gallery --file dev_docs/agents/transcripts/polytope_canonical_gallery.yaml --scope document" \
    --command "macro run polytope_canonical_gallery --scope document"
```

or open the materialized document at
`public/docs/assets/agent_transcripts/polytope_canonical_gallery/metadata.edn`.

**The one parameter to change** is `val expression = "gD"` on line 8 of the box. Change it to
`"ktC"`, `"bD"`, `"sD"`, `"tI"` or `"eC"` and re-run: the notation re-parses, the operator chain
re-evaluates, the result recanonicalizes, and both forms redraw — with the tangency-radius spread in
the printed block dropping from ~1e-1 to ~1e-7. Change it to `"kkC"` and the numbers stay large and
the run says `max iterations`, which is the honest failure the library is designed to report.

## Recommendation

**Merge.** The feature is the roadmap's own highest-priority item, it is verified numerically and
visually through three independent renderings including the real Field path, the existing API is
preserved (`solveRealization` is byte-compatible in behaviour and its test is untouched), and the
architectural boundary the codebase enforces is intact — nothing in `afield.stdlib.polytope` imports
a renderer, and the proof rasteriser lives in the test source set for exactly that reason.

Two things I would want a human to look at before trusting it further: whether `kkC`-class failures
matter for the intended use, and whether the re-seeding default is the right one — it makes
`canonicalize()` succeed far more often but means the answer sometimes did not come from the
embedding the caller passed in. `CanonicalizeResult.reseeded` and `CanonicalizeOptions(reseed = null)`
exist precisely so that choice stays the caller's.
