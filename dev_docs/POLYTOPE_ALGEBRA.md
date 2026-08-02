# Polytope algebra: current architecture and next work

The first complete vertical slice of `afield.stdlib.polytope` is now in place: immutable incidence
topology, realized solids, Conway transforms, realization repair, Field-native polygon output, and
provenance-preserving triangle output. The loadable worked example is
`dev_docs/agents/transcripts/polytope_conway_demo.yaml`.

## Architectural boundary

The dependency direction is deliberate:

```text
Conway expression / element selectors / deployed parameters
                         ↓
immutable ranked incidence topology + ordered face cycles
                         ↓
optional Realization3 using Field Vec3
                         ↓
Field adapters
    ├── one polygonal FLine per face
    └── flat-normal TriMesh + polygon provenance
```

Topology owns identity and incidence. It does not own coordinates, FLine, triangles, normals, or a
renderer. `Realization3` is an immutable ownership boundary around Field's mutable `Vec3`; public
reads return copies. Triangles are derived late and duplicate corners between polygon faces so a
flat normal can differ across a crease without corrupting the abstract vertex identity.

The new value-printer registrations preserve this boundary too. The topology classes contain no
HTML. `PolytopeVisualizers.kt` reduces the larger values to public data-class summaries and hands
those summaries to Field's ordinary value visualizer. Consequently any of these is a useful final
expression in a live box:

```kotlin
polyhedron
polyhedron.topology
result
result.deployments.first()
polyhedron.triangulate()
polyhedron.solveRealization()
```

The last value is already a data class; the other larger wrappers have registered printers. The
summary functions are also ordinary public Kotlin APIs, so `println(result.summary())` and
`viz("inspection", result)` report the same underlying facts.

## What is implemented

### Incidence topology

- Arbitrary rank and globally scoped `ElementId` values.
- Immutable elements-by-rank, adjacent-rank up/down incidence, and defensive array copies.
- Explicit ordered vertex cycles for rank-2 faces.
- Structured validation, with closed-manifold checking as an optional stricter pass.
- Euler characteristic over proper ranks, excluding the whole-polytope element.
- Abstract dualization and a small combinatorial-isomorphism checker.
- Tetrahedron, cube, octahedron, icosahedron, and dodecahedron seeds.

### Conway algebra and provenance

- Primitive `dual`, `ambo`, `kis`, `truncate`, and genuine `gyro` topology transforms.
- Named composites `join`, `needle`, `zip`, `ortho`, `expand`, `snub`, `meta`, and `bevel`.
- Prefix parser over `T`, `C`, `O`, `I`, and `D`, including indexed `kN` and `tN` selection.
- Full lineage from every output element back to source elements.
- Explicit deployment records containing selected source elements and resolved scalar parameters.
- Element selectors by rank, id, degree, attribute, and provenance role, with boolean composition.
- Scalar parameters from constants, attributes, or selector-controlled fields.
- Selective/non-uniform `kis` and `truncate`; edge-varying `gyro`.
- Attribute propagation kept behind a policy rather than embedded in each operator.

### Realization

- The topology transform and realization policy are separate calls.
- Centroid dual, edge-midpoint ambo, face-normal kis, along-edge truncation, and gyro subdivision
  policies cover the implemented primitive transforms.
- A pure-Kotlin nonlinear least-squares repair pass constrains edge lengths, face planarity,
  centroid, common radius, and optional proximity to the input realization.
- Solver results include before/after geometric diagnostics, convergence state, iterations,
  residual, and termination reason.

This is a useful repair/regularization solver, not yet a canonical realization algorithm. It does
not claim to find the unique most symmetric embedding of an arbitrary abstract polytope.

### Field output

- `toFaceFLines()` produces one closed, outward-wound 3D path per authoritative polygon face.
- Face id/rank and propagated face attributes stay attached to each line.
- Field's `filled`, `stroked`, hover, and click properties work without a parallel rendering model.
- `triangulate()` defaults to Field's existing `FloatEarcut`, after projecting each face from its
  actual 3D plane. The old GLU tessellator is not involved.
- The same dominant-axis projection was added to central `FLine.tessellate`, so filled FLine faces
  are no longer accidentally treated as XY polygons.
- `TriangulatedGeometry` retains render positions/indices, flat normals, source polygon per
  triangle, source abstract vertex per render vertex, and propagated attributes.
- Fan triangulation remains available as an explicitly convex-only strategy.
- Minimal OFF output remains a diagnostic, not an architectural dependency.

## The worked Field box

`polytope_conway_demo.yaml` intentionally has one small `440 × 72` code box. Output areas grow from
that box; the operation transcript does not reserve a fake desktop-sized rectangle for them.

The box:

1. creates a cube;
2. marks two opposite faces with `raise` and different `height` attributes;
3. selects those faces and deploys non-uniform `kis`;
4. triangulates once to expose polygon-to-triangle provenance;
5. prints data-class summaries using a triple-quoted debug block;
6. converts the polygon faces to styled, pickable FLines;
7. sends them to the on-canvas `threeLines(...)` viewer; and
8. displays the registered transform and triangulation visualizers beneath the 3D view.

Hovering identifies a polygon. Clicking it opens a multiline provenance card with the output face,
transform role, deployed height, source elements, and 3D hit position.

## What should come next

### 1. Generalize face boundaries

Replace the single `IntArray` face cycle with an oriented boundary value that can hold an outer
cycle plus zero or more holes. This should be a topology type, not an Earcut-shaped array. It is the
prerequisite for faces with holes, annuli, and robust propagation of orientation through transforms.

Decide separately how star/self-intersecting polygons are represented. They are not merely concave
simple polygons, and pretending that Earcut handles them would make their winding and filling rules
ambiguous.

### 2. Turn transform parameters into a typed field algebra

The selector and scalar-parameter interfaces establish the right execution boundary, but named
parameters are currently maps of strings to doubles in deployment records. Add typed parameter
descriptors, domains, defaults, units, and composition. Then extend Conway syntax without hiding
selection or parameter deployment inside parser special cases.

Attribute work belongs here too: typed keys, explicit interpolation/merge policies, and tests for
face/edge/vertex attributes surviving long transform chains into both FLine and triangles.

### 3. Add canonical and symmetry-aware realization

Factor the current residual families into composable constraints. Add symmetry-orbit equality,
coplanarity groups, tangency/sphericity policies, self-intersection diagnostics, and normalization
conditions that remove rigid and scale null spaces explicitly.

The important product distinction should remain visible in the API:

- `realize(...)`: construct coordinates according to a declared policy;
- `relax(...)`: improve an existing embedding under chosen constraints; and
- `canonicalize(...)`: seek a symmetry-respecting representative, with stated guarantees.

Wythoff construction should eventually supply both the symmetry specification and a high-quality
initial realization rather than asking a generic solver to rediscover all symmetry numerically.

### 4. Complete the rendering bridge

- Add smooth and crease-aware normal generation while preserving abstract vertex provenance.
- Provide a direct upload/emission helper for Field graphics buffers when repeated rendering makes
  the library-local `TriangulatedGeometry` intermediate unnecessarily expensive.
- Preserve stable face/edge/vertex picking ids through the triangle path, as the FLine demo already
  does through callbacks.
- Define update semantics for animated realizations so topology and buffers can be retained while
  only coordinates change.

### 5. Broaden the topology domain

Add explicit tests and policies for boundaries, disconnected objects, and legal non-manifold
incidence rather than quietly treating every surface as a closed 2-manifold. Then add periodic
quotient topology, finite patches of periodic structures, and dimension-general realizations.

Higher-rank support should first exercise the existing incidence core with concrete rank-4 seeds,
sections, facets, and duals. Rendering a rank-4 object is a projection policy above realization, not
a reason to flatten the topology into triangles.

### 6. Grow the demos alongside those capabilities

The next public demo set should remain small-box and one-question-per-box:

- a Conway-expression gallery with editable notation;
- a realization before/after comparison with diagnostics left as the final value;
- a concave face and, once represented, a face with a hole;
- a non-uniform parameter field painted onto its output faces; and
- a topology-only dual whose lack of coordinates is shown honestly before a realization policy is
  applied.

Each belongs in the transcript execution harness. Visible Three.js screenshots should continue to
come from the real Field/browser path rather than from a parallel documentation renderer.
