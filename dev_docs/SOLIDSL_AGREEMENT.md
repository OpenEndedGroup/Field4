# How `afield.solidsl`'s two lowerings are held to each other

Measurements that used to sit in `public/docs/solid_notation.md` and were crowding out the language
it documents. Moved here 2026-09-13. Everything below is asserted by `SolidSLCrossValidationTest`,
`SolidSLConventionProbe` and `SolidGradientCheck` unless it says otherwise; re-run those rather than
trusting these numbers.

## The two sides share no code

`crossCheck` builds a shape both ways and compares volume, surface area, genus and a symmetric
vertex-sampled Hausdorff distance. Side A is Kotlin sampling a distance field and summing signed
tetrahedra over `Float` triangles. Side B is Manifold's C++ kernel integrating a solid in `double`.
No bug makes both wrong in the same direction.

Eleven shapes, at a march cell of `size/90`, measured against Manifold 3.5.2:

| case | path | volume rel | area rel | dev/size | genus |
|---|---|---|---|---|---|
| `polyPlate` — a plate with four square bores | exact | 2.88e-03 | 3.11e-02 | 1.20e-02 | 4 ✓ |
| `lBracketCut` — an L profile, rotated cut | exact | 1.31e-03 | 1.70e-02 | 1.22e-02 | 0 ✓ |
| `starTaper` — a five-point star, tapered | exact | 2.26e-03 | 2.90e-02 | 1.33e-02 | 0 ✓ |
| `roundedSlot` — a rectangle, round-offset | exact | 1.82e-03 | 1.63e-02 | 7.47e-03 | 0 ✓ |
| `ring` — a circle revolved | exact | 1.55e-03 | 4.62e-04 | 2.61e-04 | 1 ✓ |
| `wedge` — a 270° partial revolve | exact | 4.09e-03 | 2.30e-02 | 9.16e-03 | 0 ✓ |
| `boredBoss` — plate + boss − bore | exact | 1.67e-03 | 2.14e-02 | 1.06e-02 | 1 ✓ |
| `lens` — two spheres intersected | exact | 1.99e-04 | 3.01e-03 | 3.15e-03 | 0 ✓ |
| `blend` — a smooth union | *level set* | 2.78e-04 | 2.14e-04 | 7.18e-04 | 0 ✓ |
| `gyroidCore` — a gyroid, bounded | *level set* | 7.27e-03 | 2.47e-02 | 1.99e-02 | 4 ✓ |
| `hybridPlate` — exact plate + blend | *level set* | 1.48e-03 | 1.75e-02 | 1.03e-02 | 0 ✓ |

Eight took the exact CSG path and three refused. The three are counted separately and excluded from
the exact statistic: a refused shape gets *two marchers on one field*, which catches a broken
lowering but says nothing about whether that field is the shape the notation meant.

## Which path a shape actually took

Reading it off `SolidBuild.isExact` would only be the package restating its own records. The
discriminator is a property the two paths cannot share: the exact lowering has no `edgeLength` input
at all, so refining the march by 2.5× cannot move a genuinely exact solid and must move a level set.

| case | declared | volume @ h=1/40 | volume @ h=1/100 | tris @40 | tris @100 |
|---|---|---|---|---|---|
| `polyPlate` | exact | 11.000000000 | 11.000000000 | 92 | 92 |
| `lBracketCut` | exact | 4.500000000 | 4.500000000 | 20 | 20 |
| `starTaper` | exact | 0.874183638 | 0.874183638 | 36 | 36 |
| `roundedSlot` | exact | 6.126108645 | 6.126108645 | 1036 | 1036 |
| `ring` | exact | 2.901081094 | 2.901081094 | 131072 | 131072 |
| `wedge` | exact | 2.035637074 | 2.035637074 | 2052 | 2052 |
| `boredBoss` | exact | 8.192643611 | 8.192643611 | 3088 | 3088 |
| `lens` | exact | 1.308240759 | 1.308240759 | 16632 | 16632 |
| `blend` | *level set* | 2.219473784 | 2.221887467 | 15240 | 94008 |
| `gyroidCore` | *level set* | 1.020684384 | 1.019038201 | 53928 | 333804 |
| `hybridPlate` | *level set* | 5.863771670 | 5.867193874 | 7904 | 46192 |

Eight bit-identical, three moved. `lBracketCut`'s exact solid is 20 triangles against 71,288 for its
marched counterpart; this is the triangle-count claim the page makes.

## Point membership, and what the census cannot show

Volume, area and genus are invariant under a mirror or a transposed axis. Membership is not, so
sampling points and asking both lowerings "is this inside?" is what settled which axis a lathe turns
about: `Profile.revolved` agrees on 100% of samples under a **Z** reading and about 65% under a
**Y** one, which is how `SolidSLConventionProbe` decided it.

That census does **not** show surface fidelity, and 0 disagreements in 29,635 sampled points reads as
though it did. The points are uniform in a box and the sampler skips everything within `0.03·span`
of a surface, while the largest distance at which the two lowerings can legitimately differ is
Manifold's faceting sagitta — measured at 1.25e-03 of that skip band. None of the 29,635 points is
ever in a position to disagree for a geometric reason. It is a check against a gross error visible
from anywhere in the volume, and nothing more.

The stronger question is how close to the surface you have to get before they stop agreeing, and
whether that distance is the sagitta. Points are projected onto the field's own zero set by Newton
iteration, then offset along the gradient by a band `δ` that shrinks by decades:

| case | polyhedral | 1e-2 | 1e-3 | 1e-4 | 1e-5 | 1e-6 | sagitta/span |
|---|---|---|---|---|---|---|---|
| `polyPlate` | yes | 0.00% | 0.00% | 0.00% | 0.00% | 0.00% | 0 |
| `lBracketCut` | yes | 0.00% | 0.00% | 0.00% | 0.00% | 0.00% | 0 |
| `starTaper` | yes | 0.00% | 0.00% | 0.00% | 0.00% | 0.00% | 0 |
| `roundedSlot` | no | 0.00% | 0.00% | 0.00% | 0.00% | 6.42% | 9.68e-06 |
| `boredBoss` | no | 0.00% | 0.00% | 0.00% | 4.08% | 11.00% | 2.01e-05 |
| `wedge` | no | 0.00% | 0.00% | 0.00% | 13.42% | 22.33% | 3.76e-05 |
| `ring` | no | 0.00% | 0.00% | 0.00% | 28.17% | 47.75% | 3.76e-05 |
| `lens` | no | 0.00% | 0.00% | 0.00% | 49.67% | 50.00% | 3.76e-05 |

The crossover sits at the sagitta on every curved shape, and `lens` reaches 50%, which is what two
surfaces genuinely in different places must produce. The three polyhedral shapes are the control:
0.00% at every band down to 1e-6 of the span, so the curved numbers are geometry rather than the
raycast's own precision. The test asserts both directions, including that the deepest band really is
inside the sagitta, so a shape whose crossover was never probed fails rather than passes.

## Agreement does not converge to zero

**Polyhedral shapes** converge — cuboids, polygon-profile extrusions, booleans of those. The exact
lowering is the truth and the marched one approaches it. Six-point log-log fit over a 7.6×
refinement of `polyPlate`:

| `h/size` | volume rel | dev/size |
|---|---|---|
| 1/32 | 2.33e-02 | 3.21e-02 |
| 1/48 | 9.13e-03 | 2.12e-02 |
| 1/72 | 4.16e-03 | 1.47e-02 |
| 1/108 | 1.41e-03 | 1.03e-02 |
| 1/162 | 1.91e-04 | 4.79e-03 |
| 1/243 | 2.68e-04 | 3.88e-03 |

Fitted order 2.47 on volume, 1.08 on surface deviation. A slope through six points and not a ratio
between two, because grid alignment moves any single ratio between 1.4× and 9×: whether a box face
lands on a grid plane changes the answer by a factor of two. `polyPlate`'s closed-form volume is
11.0 and Manifold returns 11.0, delta exactly 0.0, through a symbolic notation, a CSG kernel and an
FFM boundary.

**Curved shapes do not.** Manifold's circle is an inscribed *n*-gon whose area falls short of the
true one by `1 − (n/2π)·sin(2π/n) ≈ 2π²/3n²`, which `facetingDeficit(n)` computes. For a cylinder —
a right prism over exactly that *n*-gon — the prediction is exact:

| segments | predicted | measured | ratio |
|---|---|---|---|
| 16 | 0.02550464 | 0.02550464 | 1.0000 |
| 32 | 0.00641315 | 0.00641315 | 1.0000 |
| 64 | 0.00160561 | 0.00160561 | 1.0000 |
| 128 | 0.00040155 | 0.00040155 | 1.0000 |

So a 32-segment cylinder's cross-check bottoms out at 6.29e-03 against a predicted floor of
6.41e-03, and refining the march from `size/48` to `size/192` makes it *worse*: the coarser march
was accidentally cancelling against the faceting.

## The GLSL emitter is checked by a reader, not by shaderc

shaderc proves the emitted text is valid — 29 of 29 raymarch shaders produce SPIR-V — but a dropped
negation, a swapped `min` and `max`, or an argument in the wrong position all compile perfectly. So
the build parses the emitted characters back with a recursive-descent evaluator written from the
grammar rather than from the emitter, and runs it: **29 shapes, 58,000 point comparisons, worst
relative disagreement 6.3e-15**, nineteen of the 29 bit-identical.

Every simplifier rule is checked by sampling the field at 4000 points with zero sign flips
permitted.

## The gradient census

Over every construct in the algebra: **18 of 18 `SolidExpr` nodes and 11 of 11 `Profile` nodes**,
179 bound parameters, one exclusion (a full 360° revolve, where a central difference straddles two
different lowerings). Worst floored relative disagreement 1.7e-8 on the parameter gradient and
1.5e-8 on the position gradient.

The `abs`/`min`/`max` 0.5/0.5 split at an exact tie is precisely what a central difference reports
at a kink, so the analytic gradient and the finite-difference oracle agree there too and the CSG
seam does not have to be carved out of the check.

The census also reports how much of itself the oracle was willing to speak to, declining where a
central difference disagrees with itself between step `h` and `h/2`. 100.0% of parameter-gradient
components (4272 of 4272) and 100.0% of position components (1944 of 1944) were usable, both printed
and both floored by the test at 98%, because a differential test whose oracle refused everything
reads as a pass.

That floor only means something because two questions are asked separately: whether the oracle is
trustworthy, and whether the lowering returned a number at all. A `NaN` out of the *lowering* files
under "the oracle has no opinion here", which is the verdict a real kink gets too, and a census can
then report 95.9% coverage of a derivative that does not exist. So
`SolidGradientCheck.usableComponents` speaks for the oracle alone, `nonFiniteAnalytic` is the other
question, and the test asserts it is always empty.

The `.value` half has a second implementation to check against: worst relative disagreement 2.9e-7
against `toScalarField()`, which shares no line of code with it and reaches a caller through
`Float`, whose own resolution is about 1.2e-7.

## The render proofs

`SolidSLInverseRenderProofTest` measures the inverse-design comparison rather than eyeballing it. It
pins one camera across all three frames (`MeshView.distance` is in bounding-sphere radii, so
auto-framing would render a shape and a 1.5× copy of it at the same apparent size) and compares
silhouettes: the seed misplaces 32.1% of the target's own silhouette, and the fitted solid's raster
is byte-identical to the target's. Both ends are checked, because a "before" that already looked
like the answer would make the "after" free.

`SolidSLPartialRecoveryTest` asserts the two halves of the cube-and-cylinder fit separately — the
cylinder against its truth, the cube against the inscribed/circumscribed interval — because the
report prints all four numbers identically and three are measurements while the fourth is a
negotiation.
