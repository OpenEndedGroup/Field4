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
