# de Bruijn multigrid tilings — implementation notes

`deBruijnTiling(...)` lives in `src/jvmMain/kotlin/afield/geometry/path/DeBruijnTiling.kt` (package
`afield.geometry.path`, so box code sees it without imports). Public page:
`public/docs/debruijn_tilings.md`; demo: `dev_docs/agents/transcripts/debruijn_tilings_demo.yaml`;
tests: `src/jvmTest/kotlin/afield/geometry/path/DeBruijnTilingTest.kt`.

Source: Aatish Bhatia's Pattern Collider (https://github.com/aatishb/patterncollider, MIT), and
de Bruijn 1981. The code is a re-derivation, not a port of the JS.

- Directions: `2πk/n` for odd n, `πk/n` for even n (even n with `2πk/n` would make families
  k and k+n/2 parallel).
- Line j of family k sits at `x·e_k = j - γ_k (+ jitter)`. Jitter applies only to lines within the
  computed range; strips beyond it fall back to the unjittered lattice.
- Default offsets are a fixed sin-hash minus their mean. Evenly stepped offsets (golden-ratio
  steps) are rationally related and put three lines through a point at 7-fold; 3-fold with
  offsets summing to an integer always has triple points, so it sums to 0.3. A triple point shows
  up as an edge shared by three tiles; `DeBruijnTilingTest` checks for that.
- Singular user-supplied offsets are not detected; such a tiling has overlapping tiles near
  the triple points.

Not done (stretch from the scout card `7e6f81bfaf83`): GPU instancing domain, extrusion via
Manifold booleans, an exact tile-matching check against Ammann–Beenker substitution, shared-edge
dedupe for plotting.

## Later additions (same night)
- Concurrency check: a crossing with a third line within 1e-9 throws `DeBruijnDegenerateException`
  (`onDegenerate = ERROR`) or, by default, retries with offsets nudged by ~1e-6·attempt (up to 8).
- Corners are counter-clockwise so outlines feed Manifold `Section.ofFLines` as solids; with
  clockwise corners the union came back empty ("shadedNormals ... 0 vertices").
- `Section.ofFLines(sampleDistance = 1.0)` resamples straight edges (45k tris for 295 tiles); 10.0
  gives 5.4k with the same volume; 50.0 returned an empty solid.
- `recenter = false` for animation, so unchanged tiles do not drift with the mean centre.

## Three dimensions (`deBruijnTiling3D`)

`src/jvmMain/kotlin/afield/geometry/path/DeBruijnTiling3D.kt`; page `public/docs/debruijn_tilings_3d.md`;
demo `dev_docs/agents/transcripts/debruijn_tilings_3d_demo.yaml`; tests `DeBruijnTiling3DTest`.

- References: Socolar & Steinhardt, PRB 32, 5547 (1985) introduced the "generalized dual method"
  (the 3D grid construction used here). Kramer & Neri, Acta Cryst. A40, 580 (1984) got the 3D
  Penrose tiling by projection from a 6D cubic lattice (their paper calls it a 12-grid). Socolar &
  Steinhardt, PRB 34, 617 (1986) is the unit-cell configurations follow-up, not the method paper.
- Corners are computed from the integer lift (one integer per family) summed in family order, so a
  shared vertex has identical bits in every cell. The tests rely on it, and so does Manifold's weld.
- Shape classes are congruence classes: (volume, sorted |cos| of the three edge pairs, sign of
  their product). Volume alone merged the cube face-diagonal star's two rhombohedra.
- `planes = 0..0` gives the zonotope tiling of the rhombic triacontahedron (20 cells); `0 until m`
  gives the m-times-larger one with 20m³ cells. Volume check: Σ|det|² over triples = det(Σ e eᵀ) =
  8 for the six icosahedral axes (Cauchy–Binet), so the cells fill 8× the crossing ball.
- Manifold's batch union of face-sharing hulls leaves zero-volume 4-vertex shells where many coplanar
  faces meet; `simplified(tol)` at any tolerance does not remove them in a slab. `toSolid()` drops
  every component with volume under 1e-6 of the smallest cell. Selecting cells by centre in a slab
  gives edge-only contacts (non-manifold TriMesh, χ = −15); `between(family, from, to)` selects by
  plane instead and its boundary is manifold (dual of the 2D line arrangement in a plane).
- Not done: a live Vulkan view of a patch; animated slice sweeps; matching rules / decorations on
  the rhombohedra; disorder (jitter) as in the 2D version.
