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
