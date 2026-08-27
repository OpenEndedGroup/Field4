# A solid, written down: `afield.solidsl`

*Every public declaration used below carries its own KDoc — start from `SolidExpr`, `Profile`,
`SolidLowering` and `SolidAgreement` in `afield.solidsl`. There is an openable
[starting point](#open-this) at the bottom of the page.*

Field has had a signed-distance vocabulary for years. `mesh_sdf.kt` declares

```diffstatic {data-lang=kotlin}
typealias ScalarField3D = (Vec3) -> Float
```

and builds twenty-five primitives and combinators on top of it — `sdfSphere`, `sdfBox`, `sdfTorus`,
`sdfUnion`, `sdfSmoothUnion`, `twisted`, `repeated`. Every one of them returns a Kotlin lambda, and a
lambda, as the commit that made [RectSL](rectsl_examples.md)'s 2D tracks symbolic put it,

> cannot be printed, compared, serialized, rewritten, or emitted as GLSL.

`afield.solidsl` is the same move for 3D. A shape is a **value**:

```diffstatic {data-lang=kotlin}
val part = (cuboid(4, 2.6, 0.7) + cylinder(1.4, 0.8)) -
    cylinder(3.0, 0.42) -
    Profile.rect(2.9, 0.85).offset(0.22).extruded(3.0).at(0, 1.05, -1.5)

part.toScalarField()   // (Vec3) -> Float — marchable, and everything mesh_sdf.kt does
part.exactSolid()      // Manifold's exact CSG — a guaranteed-closed Solid, or a stated refusal
part.toGlsl()          // float sdSolid(vec3 p) — a real-time raymarch of the same source
part.simplified()      // a smaller expression that means the same thing
println(part)          // Difference(children=[Union(children=[Cuboid(x=4.0, …
```

## Why now, and not three years ago

Because until [`afield.manifold`](manifold.md) landed there was only one thing to lower to. Now there
are two, and they could hardly be more different:

| | `toScalarField()` | `exactSolid()` |
|---|---|---|
| what the shape becomes | a distance field, sampled on a grid | a CSG tree, evaluated exactly |
| how a surface appears | marching cubes, `O(h²)` | it **is** the surface |
| a cylinder is | a true cylinder | an inscribed *n*-gon prism |
| a smooth blend is | native | **refused**, with a reason |
| a gyroid is | native | **refused** |
| runs on a GPU | yes, via `toGlsl()` | no |
| safe to subtract from | not reliably | always |

Neither is a superset of the other. That is what makes running both on one source a *measurement*
rather than a formality.

### The vocabulary, drawn

Six primitives and a swept profile, each built through `buildSolid` and drawn in its own panel. Five
of the six lower **exactly** — `exactRefusal()` returns `null` for `sphere`, `cuboid`, `cylinder`,
`cone` and `torus`, and for `Profile.star(…).extruded(…)` too, twist and all. `gyroid` is the one
that refuses, which is why it is not in the picture: there is no CSG tree whose surface is a triply
periodic minimal surface.

```diffstatic_runner {data-lang=kotlin screenshot="assets/solid_notation/vocabulary.png" wait="8.0" capture="box" open-outputs="true" padding="20"}
:::static
import afield.solidsl.*

// Every panel frames its own bounds, so the small shapes are not lost beside the large ones.
val q = SolidQuality(segments = 64, edgeLengthFraction = 1.0 / 55.0)
fun mesh(e: SolidExpr) = e.buildSolid(q).let { b ->
    val m = b.solid.toTriMesh(normals = true); b.close(); m
}

threeGrid(
    "sphere(1.0)" to mesh(sphere(1.0)),
    "cuboid(1.6, 1.2, 0.8)" to mesh(cuboid(1.6, 1.2, 0.8)),
    "cylinder(1.6, 0.7)" to mesh(cylinder(1.6, 0.7)),
    "cone(1.6, 0.8)" to mesh(cone(1.6, 0.8)),
    "torus(1.0, 0.32)" to mesh(torus(1.0, 0.32)),
    "star(6).extruded(twist 90)" to mesh(Profile.star(6, 0.9, 0.45).extruded(1.2, twistDegrees = 90.0)),
    channel = "vocab", columns = 3, height = 470,
    view = MeshView(width = 300, height = 210, yawDegrees = 32.0, pitchDegrees = 16.0, wireframe = true),
)
```

![Six panels in two rows of three: a sphere; a rectangular block drawn with only a few large triangles per face; a cylinder lying at an angle with its flat end visibly faceted into straight segments; a cone seen close to base-on with its apex to the lower left; a torus; and a six-pointed star column seen nearly end-on, its points sweeping round as the extrusion twists. All are flat grey with the triangle wireframe drawn, each framed to its own bounds so the small shapes are not lost beside the large ones](assets/solid_notation/vocabulary.png)

The faceting is the part to be careful about. `cylinder(1.6, 0.7)` with `segments = 64` is a 64-gon prism on the
exact path, not a true cylinder — the table above says so and the picture shows it. Raise `segments`
and the silhouette tightens; the *field* lowering of the same expression has a true circular
cross-section at every resolution and gets the surface wrong in a different way instead.

## The headline: two evaluations, and how far apart they land

`crossCheck` builds a shape both ways and compares volume, surface area, genus and a symmetric
vertex-sampled Hausdorff distance. The two sides share no line of code and no arithmetic: side A is
Kotlin sampling a distance field and summing signed tetrahedra over `Float` triangles; side B is
Manifold's C++ kernel integrating a solid in `double`. There is no bug that makes both wrong in the
same direction.

Eleven shapes, at a march cell of `size/90`, every row of it asserted:

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

**Eight of eleven took the exact CSG path. Three refused.** Those three are counted separately and
excluded from the exact statistic, because a refused shape gets *two marchers on one field* — which
catches a broken lowering but says nothing about whether that field is the shape the notation meant.
"Eleven of eleven agree" where three of them agreed with themselves is not the claim being made.

### That split is measured, not read off the package's own bookkeeping

Asserting it from `SolidBuild.isExact` would only be this package restating its own records. The
discriminator used instead is a property the two paths cannot share: **the exact lowering has no
`edgeLength` input at all.** Refine the march by 2.5× and a genuinely exact solid cannot move; a
level set must.

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

Eight bit-identical, three moved. Corroborated by a fact that needs no argument at all:
`lBracketCut`'s exact solid is **20 triangles** against 71,288 for its marched counterpart. There is
no march that produces a twenty-triangle surface.

### Point membership: a convention check, and *not* a fidelity one

Volume, area and genus are all invariant under a mirror or a transposed axis. Membership is not, so
sampling points and asking both lowerings "is this inside?" is what pins the conventions. It is how
`Section.revolved`'s KDoc was caught claiming the **Y** axis where Manifold revolves about **Z** —
100% agreement under the Z reading, about 65% under the documented one.

An earlier draft of this page reported **0 disagreements in 29,635 sampled points** as if that were
evidence of surface fidelity. **It is not, and the claim is withdrawn.** Those points are uniform in
a box and the sampler skips everything within `0.03·span` of a surface — while the largest distance
at which the two lowerings can *legitimately* differ is Manifold's faceting sagitta, measured at
**1.25e-03 of that skip band**. Not one of the 29,635 points was ever in a position to disagree for a
geometric reason. It is a real check against a gross error, visible from anywhere in the volume, and
nothing more than that.

The stronger version asks a different question: **how close to the surface do you have to get before
they stop agreeing, and is that distance the sagitta?** Points are projected onto the field's own
zero set by Newton iteration, then offset along the gradient by a band `δ` that shrinks by decades:

| case | polyhedral | 1e-2 | 1e-3 | 1e-4 | 1e-5 | 1e-6 | sagitta/span |
|---|---|---|---|---|---|---|---|
| `polyPlate` | yes | 0.00% | 0.00% | 0.00% | 0.00% | **0.00%** | 0 |
| `lBracketCut` | yes | 0.00% | 0.00% | 0.00% | 0.00% | **0.00%** | 0 |
| `starTaper` | yes | 0.00% | 0.00% | 0.00% | 0.00% | **0.00%** | 0 |
| `roundedSlot` | no | 0.00% | 0.00% | 0.00% | 0.00% | 6.42% | 9.68e-06 |
| `boredBoss` | no | 0.00% | 0.00% | 0.00% | 4.08% | 11.00% | 2.01e-05 |
| `wedge` | no | 0.00% | 0.00% | 0.00% | 13.42% | 22.33% | 3.76e-05 |
| `ring` | no | 0.00% | 0.00% | 0.00% | 28.17% | 47.75% | 3.76e-05 |
| `lens` | no | 0.00% | 0.00% | 0.00% | 49.67% | 50.00% | 3.76e-05 |

The crossover sits at the sagitta on every curved shape, and `lens` reaches 50% — a coin flip, which
is exactly what two surfaces genuinely in different places must produce. The three polyhedral shapes
are the control: no faceting, and **0.00% at every band down to 1e-6 of the span**, so the curved
numbers are geometry rather than the raycast's own precision. Both directions are asserted, including
that the deepest band really is inside the sagitta — so a shape whose crossover was never probed
fails rather than passes.

## Agreement does not converge to zero, and that is the interesting part

There are two regimes, and flattening them would be the lie worth avoiding.

**Polyhedral shapes** — cuboids, polygon-profile extrusions, booleans of those — converge. The exact
lowering *is* the truth and the marched one approaches it. Six-point log-log fit over a 7.6×
refinement of `polyPlate`:

| `h/size` | volume rel | dev/size |
|---|---|---|
| 1/32 | 2.33e-02 | 3.21e-02 |
| 1/48 | 9.13e-03 | 2.12e-02 |
| 1/72 | 4.16e-03 | 1.47e-02 |
| 1/108 | 1.41e-03 | 1.03e-02 |
| 1/162 | 1.91e-04 | 4.79e-03 |
| 1/243 | 2.68e-04 | 3.88e-03 |

**Fitted order: 2.47 on volume, 1.08 on surface deviation.** A slope through six points and not a
ratio between two, because grid alignment moves any single ratio between 1.4× and 9× — whether a box
face happens to land on a grid plane changes the answer by a factor of two.

And a fact worth stating on its own: `polyPlate`'s closed-form volume is `11.0`, and Manifold returns
`11.0`. Delta exactly `0.0`, through a symbolic notation, a CSG kernel and an FFM boundary.

**Curved shapes do not converge to zero.** Manifold's circle is an inscribed *n*-gon, whose area
falls short of the true one by `1 − (n/2π)·sin(2π/n) ≈ 2π²/3n²`. `facetingDeficit(n)` computes it —
and for a cylinder, which is a right prism over exactly that *n*-gon, the prediction is not
approximately right:

| segments | predicted | measured | ratio |
|---|---|---|---|
| 16 | 0.02550464 | 0.02550464 | 1.0000 |
| 32 | 0.00641315 | 0.00641315 | 1.0000 |
| 64 | 0.00160561 | 0.00160561 | 1.0000 |
| 128 | 0.00040155 | 0.00040155 | 1.0000 |

So a 32-segment cylinder's cross-check bottoms out at `6.29e-03` against a predicted floor of
`6.41e-03`, and refining the march from `size/48` to `size/192` makes it *worse*, not better —
because the march was accidentally cancelling against the faceting. That is the difference between a
tolerance and an explanation.

## Refusals are in the type

Stolen wholesale from `RectSLFnLowering`, and for the reason that file gives: a wrong answer is worse
than no answer, so the lowering never guesses. A `null` invites a caller to substitute something
plausible; an exception loses the reason at the first `runCatching`. A stated reason survives into a
test, a diagnostic and this page.

```diffstatic {data-lang=kotlin}
when (val l = shape.exactSolid()) {
    is SolidLowering.Lowered -> println("volume ${l.value.volume}, genus ${l.value.genus}")
    is SolidLowering.Refused -> println("no exact form: ${l.why}")
}
```

There are two flavours, and keeping them apart is the point:

| node | why | flavour |
|---|---|---|
| `SmoothUnion`, `SmoothDifference` | the fillet surface lies on **neither** operand, so no union, intersection or difference of the two can produce it | permanent |
| `Gyroid` | a trigonometric field on all of space, not a bounded volume | permanent |
| `Round` | `minkowskiSum` with a sphere *exists* — but that sphere is an inscribed polyhedron, so the result is strictly inside the true offset by up to its sagitta, and **refining the march does not close a gap that is not the march's** | would not converge |
| `Shell` | a grow minus an erode: `Round`'s error twice over, with a concave decomposition between them | would not converge |

The last two are not "we did not get to it". An exact-looking answer is available and it is the
*wrong* answer, systematically, in a direction that does not shrink. Filing them beside the smooth
blends would flatten a real distinction.

All four refuse, and each says something different. They are still perfectly drawable — the field
lowering takes every one of them — so the refusal costs you the *exact* backend, not the shape.

```diffstatic_runner {data-lang=kotlin screenshot="assets/solid_notation/refusals.png" wait="8.0" capture="box" open-outputs="true" padding="20"}
:::static
import afield.solidsl.*

val q = SolidQuality(segments = 64, edgeLengthFraction = 1.0 / 70.0)

val blend = smoothUnion(sphere(0.5).at(0.0, 0.0, 1.0), cylinder(1.9, 0.26), k = 0.28)
val rounded = cuboid(1.2, 1.2, 1.2).rounded(0.18)
// A shelled sphere is a sphere from the outside, so cut it open — otherwise the picture
// shows nothing the plain sphere does not.
val shellCut = sphere(0.9).shelled(0.08) - cuboid(1.2, 1.2, 1.2).at(0.9, 0.0, 0.9)
// The gyroid has no window of its own and borrows the cuboid's, which is the bounds story below.
val gyr = gyroid(6.0, 0.30) and cuboid(2.6, 2.6, 2.6)

listOf("blend" to blend, "rounded" to rounded, "shell" to shellCut, "gyroid" to gyr).forEach { (n, e) ->
    println("%-8s refuses=%s".format(n, e.exactRefusal() != null))
}

threeGrid(
    "smoothUnion(k = 0.28)" to blend.marchedMesh(q),
    "cuboid.rounded(0.18)" to rounded.marchedMesh(q),
    "sphere.shelled(0.08), cut open" to shellCut.marchedMesh(q),
    "gyroid(6.0) and cuboid(2.6)" to gyr.marchedMesh(q),
    channel = "refusals", columns = 2, height = 540,
    view = MeshView(width = 330, height = 250, yawDegrees = 24.0, pitchDegrees = 8.0, wireframe = true),
)
```

![Four panels. Top left, a ball with a cylindrical post emerging from it, the two merging into each other without a crease at the join. Top right, a cube whose edges and corners are all rounded, drawn in a dense uniform mesh. Bottom left, a sphere with a rectangular notch cut out of its side, the opening revealing a hollow interior and a wall of visible, even thickness. Bottom right, a triply periodic lattice of smooth interlocking surfaces filling a cube and cut off flat at the cube's six faces](assets/solid_notation/refusals.png)

Note what is **not** on the list. Every `Profile` node lowers exactly — including `Profile.offset`,
because `Section.offset(d, JoinType.ROUND)` is definitionally the Minkowski sum with a disc, which is
*exactly* what `sdf − d` means. A rounded slot has an exact form and a rounded solid does not, and
the asymmetry is real: Clipper offsets a 2D region as a region, where the 3D story has to go through
a polyhedral ball.

## The hybrid: march the smallest subtree that needs it

A naive fallback asks "does the whole tree lower exactly?" and marches everything if the answer is
no — so one smooth blend anywhere costs a hundred-thousand-triangle approximation of a plate that was
perfectly exact. `buildSolid` walks down instead.

```diffstatic {data-lang=kotlin}
val build = (plate + smoothUnion(ball, post, k = 0.28)).buildSolid()
println(build.report())
// hybrid: 1 subtree(s) marched, the rest exact (39552 triangles, genus 4)
//   marched SmoothUnion at edgeLength 0.0416 — a smooth blend's fillet surface lies on neither operand…
```

Measured on that part: **39,552 triangles against 110,168** for the naive whole-tree march at the
same edge length — 2.79×, and the plate's six faces stay six faces. The result is still one
guaranteed-closed Manifold solid, because the exact operand and the marched one meet in a real batch
boolean.

The ratio is a property of the part, not of the method, and it is worth seeing a case where it is
nearly 1. The plate below is smaller relative to its boss, so the marched subtree's bounds cover most
of the shape and the hybrid saves almost nothing in triangle count — **29,632 against 31,240**, a
factor of 1.05.

Which makes it the more useful picture, because the count is not where the difference lives. Look at
the *tessellation* in the two panels below. On the left the plate is a handful of large triangles
with long diagonals across each face, and each bore is a clean swept strip: those are exact planes
and exact cylinders, and they are coarse because a plane needs two triangles and no more. Only the
blended boss carries a fine mesh, because only the boss was sampled. On the right the same part is
uniformly dense — the flat faces are diced into thousands of triangles that buy nothing, because
marching cubes has no way to know a plane is a plane. Nearly the same triangle budget, spent
completely differently.

```diffstatic_runner {data-lang=kotlin screenshot="assets/solid_notation/hybrid.png" wait="8.0" capture="box" open-outputs="true" padding="20"}
:::static
import afield.solidsl.*

val q = SolidQuality(segments = 96, edgeLengthFraction = 1.0 / 80.0)

val plate = cuboid(3.2, 2.2, 0.42)
val bores = unionOf(listOf(-1.1 to -0.7, 1.1 to -0.7, -1.1 to 0.7, 1.1 to 0.7).map { (x, y) ->
    cylinder(2.0, 0.30, segments = 96).at(x, y, 0.0)
})
val boss = smoothUnion(
    sphere(0.42, segments = 96).at(0.0, 0.0, 0.62),
    cylinder(1.1, 0.26, segments = 96).at(0.0, 0.0, 0.30),
    k = 0.22,
)
val part = (plate - bores) + boss

val build = part.buildSolid(q)
println(build.report())
val naive = part.marchedMesh(q)
println("hybrid ${build.solid.triangleCount} triangles, naive ${naive.triangleCount}")

threeGrid(
    "buildSolid — exact plate, marched boss" to build.solid.toTriMesh(normals = true),
    "marchedMesh — everything approximated" to naive,
    channel = "hybrid", columns = 2, height = 360,
    view = MeshView(width = 360, height = 300, yawDegrees = 34.0, pitchDegrees = 24.0, wireframe = true),
)
build.close()
```

![Two panels of the same part: a rectangular plate with four corner holes and a domed boss blended into its centre. The left panel, the hybrid build, draws the plate as a handful of large flat triangles with long diagonals and the bores as clean swept strips, while only the central boss carries a fine mesh. The right panel, fully marched, is uniformly finely triangulated everywhere including the flat faces](assets/solid_notation/hybrid.png)

This needs the expression to know its own extent, which is the first thing a notation buys that a
closure cannot: `SolidExpr.bounds()` is computed **symbolically**, with nothing sampled and nothing
built. It is also what lets `gyroid() and cuboid(2, 2, 2)` work at all — the gyroid child has no
window of its own and borrows its parent's.

## A third backend: GLSL

```diffstatic {data-lang=kotlin}
val emitted = scene.toGlsl()          // GlslSolid(sdSolid, 174 statements, stepScale 0.4, 2 notes)
println(scene.toRaymarchFragment())   // sphere tracing, a normal, two lights, ready to paste
```

The emitted code is straight-line, single-assignment and **entirely scalar** — no `vec3` locals, no
swizzles, no loops. That looks like a strange thing to hand a shader compiler and it is a deliberate
trade: every driver re-vectorises it in the first pass, and what it buys is a grammar small enough to
write a *reader* for.

Which is the whole point. shaderc proves the text is *valid* — 29 of 29 raymarch shaders produce
SPIR-V — but a dropped negation, a swapped `min` and `max`, or an argument in the wrong position all
compile perfectly. So the build parses the emitted characters back with a recursive-descent evaluator
written from the grammar rather than from the emitter, and runs it:

> **29 shapes, 58,000 point comparisons, worst relative disagreement `6.3e-15`.** Nineteen of the 29
> are bit-identical.

`GlslSolid.stepScale` travels with the shader rather than being folklore in it. Sphere tracing is
only safe where the field is a lower bound on the true distance, and several nodes are not —
`Intersect` and `Difference` are pointwise maxima, a twisted `Extrude` shears space. The emitter
reports `1.0`, `0.7` or `0.4` with the offending node attached.

## The simplifier, and the rule that removes a refusal

Most of `simplified()` is housekeeping any algebra accumulates the moment somebody generates code
into it: identity transforms drop, adjacent translations fold, nested booleans of one kind flatten
into a single n-ary node — which matters here, because n-ary is what reaches Manifold's *batch*
boolean rather than a fold of *n−1* nested ones.

Two rules are worth more. **Duplicate operands collapse**, because two `SolidExpr.Sphere(1.0)` are
`==` where two `sdfSphere(1.0)` closures are different objects forever. And **an operand that cannot
reach the body is dropped**, decided from symbolic bounds. Measured on a plate with fifty bores of
which three actually meet it:

> the emitted shader goes from **610 statements to 46**, a 13× cut, from a rule that runs in
> microseconds on the tree.

And one that is simply strange. `SmoothUnion(a, b, k = 0)` *is* a union, and a union has an exact CSG
form where a smooth blend never will — so simplification can move an expression off the level-set
fallback and onto the exact path:

```diffstatic {data-lang=kotlin}
println(smoothUnion(a, b, k = 0.0).simplificationReport())
// nodes 3 -> 1, GLSL 19 -> 10 statements, path level set -> exact
```

Refused: composing two general Euler rotations (a matrix does not always come back out as
three angles without a gimbal case to get wrong), reassociating `SmoothUnion` (non-associative),
reordering a `Difference` (non-commutative). A rewrite that is nearly right is worse than no rewrite,
which is the same discipline as the refusals. Every rule is checked by sampling the field at 4000
points with **zero sign flips** permitted.

## A third reading: the derivative

The two readings above are both *evaluations*. Neither changes what you can **ask** the notation
for. `afield.rectsl.RectSLFn` made the argument one step further in 2D and it ports:

> a track that lowers to an `Fn` is a track you can take the derivative of, and therefore a track you
> can **solve**: declare where a rect should be at some instant and let a gradient find the numbers
> that put it there.

`Fn` itself does not port — it is `(Double) -> Double`, and a solid is a function of `(x, y, z)`
*and* of however many numbers the author wants to fit. The tool that does is
`afield.stdlib.AD`: reverse-mode automatic differentiation over `Double`. So `SolidGradient.kt` is
`SolidField.kt`'s `toField3` transposed line for line into `AD` arithmetic, and the two are meant to
be read side by side.

Reverse mode and not forward, because the objective is one scalar loss over many sample points and a
handful of parameters: one forward pass and one backward sweep give *every* parameter derivative,
where forward mode costs a pass per parameter. The position gradient is three more inputs and falls
off the same tape for free.

### Naming the numbers

A `SolidExpr` has no names in it — it is an anonymous tree of `data class`es — so a parameter is
bound to a **structural address**:

```diffstatic {data-lang=kotlin}
val part = smoothUnion(sphere(0.45), cylinder(1.90, 0.16), k = 0.18)

part.numericSlots()
// SmoothUnion/a/Sphere.radius = 0.45
// SmoothUnion/b/Cylinder.height = 1.9
// SmoothUnion/b/Cylinder.radiusLow = 0.16
// SmoothUnion/b/Cylinder.radiusHigh = 0.16
// SmoothUnion.k = 0.18

val template = part.parameterize {
    param("sphereRadius", "SmoothUnion/a/Sphere.radius")
    param("barHeight", "SmoothUnion/b/Cylinder.height")
    param("barRadius") { it.node == "Cylinder" && it.field.startsWith("radius") }   // both, tied
}.orThrow()

template.gradientAt(0.9, 0.0, 0.0, template.seed)
// value 0.450000  dPos 1.000, 0.000, 0.000  dParam -1.000, 0.000, 0.000
```

`barRadius` drives **two** slots, which is how a symmetry constraint is written: "the bar is a
cylinder, not a frustum", in the parameterisation rather than in a comment. A fit over two
independently-varying radii and a fit over one shared radius are different problems and the notation
should be able to say which is meant.

`segments` is not in that list, and its absence is the first refusal. It is an integer facet count
that `toScalarField()` ignores entirely — a `Sphere`'s field is a true sphere whatever its
`segments` says — and that the exact lowering consumes as a number of polygon sides. Neither has a
derivative with respect to it, for opposite reasons: one because the number has no effect at all, the
other because its effect is a jump.

### The reading is total; the *parameterisation* is where the refusals live

`exactSolid()` refuses five nodes. This reading refuses none, and that is a finding rather than an
omission: every node with a field form has a differentiable form, because `AD` is closed over
everything `Field3` uses. What is not total is *binding a parameter*, and there are seven refusals
there — an address that does not resolve (with the ones that do, in the message), a predicate that
matched nothing, a name declared twice, two names claiming one number, tied slots holding
inconsistent seeds, and two seed values that would make the lowering singular before the first step
(a `Scale` factor at zero, a blend radius at zero).

The interesting part is that the shape of it **inverts** the exact lowering's:

| node | `toScalarField` | `exactSolid` | the gradient |
|---|---|---|---|
| `SmoothUnion` | native | **refused** — the fillet lies on neither operand | native, and **C¹**: no kink at all |
| `SmoothDifference` | native | **refused** | native, C¹ |
| `Gyroid` | native | **refused** — not a bounded volume | native; one `abs` kink on the mid-sheet |
| `Round` | native | **refused** — would not converge | native, exactly `f − r` |
| `Union` | native | native | a subgradient on the seam, split 0.5/0.5 |

The two nodes the exact kernel refuses hardest are the two this reading is *best* at. If a shape is
going to be fitted, `smoothUnion` and `smoothDifference` are the operators to write it with, and
`gradientNotes()` — the third notes function, beside `fieldNotes()` and `allRefusals()` — will say so
about your own expression.

### The kink convention was already decided, and that is what makes it checkable

A distance field is nothing but kinks: `min` and `max` *are* CSG, `abs` is `Shell` and `Gyroid`,
`sqrt` at zero is every radial primitive's own centre. `autodiff.kt` had already chosen — `abs`,
`min` and `max` split the derivative **0.5/0.5 at an exact tie** — and had already said why: that is
"precisely what a central difference reports at a kink, so the analytic gradient and the
finite-difference oracle *agree there too*".

Which means the CSG seam does not have to be carved out of a finite-difference census. Over a census
of every construct in the algebra:

> **18 of 18 `SolidExpr` nodes and 11 of 11 `Profile` nodes**, 179 bound parameters, one exclusion
> (a full 360° revolve, where a central difference straddles two different lowerings). Worst floored
> relative disagreement **1.7e-8** on the parameter gradient and **1.5e-8** on the position gradient.

And the number that keeps that honest: the oracle declines to speak where a central difference
disagrees with itself between step `h` and `h/2`, which happens at genuine singularities. **95.9% of
parameter-gradient components and 93.8% of position components were usable**, both printed and both
floored by the test — because a differential test where the oracle refused everything reads as a
pass.

The `.value` half has a second implementation to check against instead, which is
[the same argument](#the-headline-two-evaluations-and-how-far-apart-they-land) applied to a third
column: **worst relative disagreement 2.9e-7** against `toScalarField()`, which shares no line of
code with it and reaches a caller through `Float`, whose own resolution is about `1.2e-7`.

### And therefore: inverse design

```diffstatic {data-lang=kotlin}
val target = template.withValues(doubleArrayOf(0.45, 1.90, 0.16)).sampleField(count = 2400, seed = 91)
val fit = template.fitParameters(target, start = doubleArrayOf(0.26, 1.05, 0.34))

fit.report()
// fit 3 parameter(s) in 14 iterations (gradient)
//   sphereRadius       0.260000  ->      0.450000
//   barHeight          1.050000  ->      1.900000
//   barRadius          0.340000  ->      0.160000
//   loss           2.140904e-02  ->  4.609346e-22
//   improvement    4.645e+19x

fit.fitted        // an ordinary SolidExpr again: marchable, GLSL-able, printable
```

Three parameters, seeded 42%, 45% and 112% wrong, recovered to about `1e-10` in fourteen iterations.
`initialLoss` is in the same object as `loss`, measured in the same run, because "the fit improved
things" is unfalsifiable without the counterfactual beside it.

And that is a claim about a picture, so here is the picture — target, seed and fitted, all three
marched in the same run, with each panel's label carrying the loss the run measured for it:

```diffstatic_runner {data-lang=kotlin screenshot="assets/solid_notation/inverse.png" wait="12.0" capture="box" open-outputs="true" padding="20"}
:::static
import afield.solidsl.*

// The model, and the three numbers to be solved for. `barRadius` drives BOTH of the cylinder's
// radii, which is how "the bar is a cylinder, not a frustum" gets written down.
val template = smoothUnion(sphere(0.45), cylinder(1.90, 0.16), k = 0.18).parameterize {
    param("sphereRadius", "SmoothUnion/a/Sphere.radius")
    param("barHeight", "SmoothUnion/b/Cylinder.height")
    param("barRadius") { it.node == "Cylinder" && it.field.startsWith("radius") }
}.orThrow()

val truth = doubleArrayOf(0.45, 1.90, 0.16)
val seed = doubleArrayOf(0.26, 1.05, 0.34)          // wrong by 42%, 45% and 112%

// The target is a bag of points with a signed distance at each. Nothing below sees `truth` again.
val target = template.withValues(truth).sampleField(count = 2400, seed = 91)
val fit = template.fitParameters(target, seed)

println(fit.report())

// Every number in the three labels is measured in THIS run. There is no remembered before-value:
// `initialLoss` is the seed's loss, computed by the same code that computed the fitted one.
fun m(e: SolidExpr) = e.march(resolution = 104)
threeGrid(
    "seed  ·  loss %.3e".format(fit.initialLoss) to m(template.withValues(seed)),
    "fitted  ·  loss %.3e  (%.2gx)".format(fit.loss, fit.improvementFactor) to m(fit.fitted),
    "target  ·  the shape the samples came from" to m(template.withValues(truth)),
    channel = "inverse", columns = 3, height = 300,
    view = MeshView(width = 320, height = 250, yawDegrees = 32.0, pitchDegrees = 22.0, wireframe = true),
)
```

![Three panels in a row, flat grey with the triangle wireframe drawn on a dark background. Left, labelled "seed, loss 2.141e-02", a short fat rounded capsule lying at an angle. Middle, labelled "fitted, loss 4.609e-22 (4.6e+19x)", a ball with a rod emerging from it, the rod meeting the ball in a smooth fillet. Right, labelled "target, the shape the samples came from", the same ball-and-rod shape at the same size and orientation as the middle panel and indistinguishable from it](assets/solid_notation/inverse.png)

The middle and right panels are the same shape because the fit found the target's numbers to about
`1e-10`, and the left one is there so that "the same shape" means something. `SolidSLInverseRenderProofTest`
makes that comparison an assertion rather than an impression: it pins one camera across all three
frames — `MeshView.distance` is in bounding-sphere radii, so auto-framing would have rendered a
shape and a 1.5x copy of it at the same apparent size — and measures the silhouettes.

Measured there: the seed misplaces **32.1% of the target's own silhouette**, and the fitted solid's
raster is **byte-identical** to the target's. Both directions are asserted, because a "before" that
already looked like the answer would make the "after" free.

### What makes a parameter refuse to move

This is the failure mode of inverse design, and it is worth naming because it presents as a
*converged* optimisation. A hard `Union` is a pointwise `min`, so it hands the **whole** derivative to
whichever child is nearest. A child that is nowhere nearest — a small sphere swallowed inside a large
one — receives exactly `0.0` from every sample, and a descent on its radius stops immediately because
the gradient really is zero.

```diffstatic {data-lang=kotlin}
unionOf(listOf(sphere(1.0), sphere(0.25))).parameterize { … }
    .fitParameters(target, …).deadParameters      // [inner]

smoothUnion(sphere(1.0), sphere(0.25), k = 0.9).parameterize { … }
    .fitParameters(target, …).deadParameters      // []
```

`SolidFit.deadParameters` reports which parameters had an exactly-zero gradient at the start, so that
reads as a diagnosis. It is reported rather than thrown on, because a legitimately dead parameter is a
fine thing to have in a vector — a fit over six holes where one is genuinely irrelevant — and it is
the *unexpectedly* dead one that looks like a success.

## Conventions, all of them upstream's

Primitives are centred on the origin. Beyond that, nothing is tidied up:

- `cylinder` runs along **Z**.
- `Profile.extruded(h)` runs from `z = 0` to `z = h` — *not* centred, because `Section.extruded` is not.
- `Profile.revolved()` turns about **Z**, profile at `x ≥ 0`, counter-clockwise from `+X`.
- `turned(x, y, z)` is in **degrees**, applied X then Y then Z.

Inventing a nicer convention and translating at the boundary would add exactly one class of bug — a
silently mirrored or transposed solid — to the one thing this package exists to rule out. And there
is no `Torus`: `torus(R, r)` is a *function* that builds `Profile.circle(r).translated(R, 0).revolved()`,
which is what a torus is.

!!! warning "Two lathes, ninety degrees apart"

    Field has two of these and they do not agree, which is worth knowing before you port a profile
    between them.

    | | axis | profile |
    |---|---|---|
    | `afield.stdlib.revolveMesh` | **Y** | `(radius, y)` |
    | `afield.manifold.Section.revolved`, and therefore `Profile.revolved` | **Z** | `(radius, z)` |

    Measured on one asymmetric profile: Manifold's comes back with bounds
    `(-1.6, -1.6, 0.2)…(1.6, 1.6, 1.0)` and `revolveMesh`'s with `(-1.6, 0.0, -1.6)…(1.6, 0.8, 1.6)`.

    `Section.revolved`'s own KDoc currently says it revolves about the **Y** axis. That is a
    documentation error and not an implementation one — `manifold_revolve` takes no axis argument at
    all, so the binding has nothing to get wrong — but it is the reason this package's `Revolve`
    carries a paragraph of measurement rather than a citation.

## Open this {#open-this}

<iframe
  style="width:100%;height:520px;border:1px solid #2a3038;border-radius:8px"
  src="/offline.html?file=/field4/assets/solidsl_gallery/metadata.edn"
  loading="lazy"></iframe>

`assets/solidsl_gallery/metadata.edn` is a Field document with three boxes. Open it and run them:

- **one shape, two evaluations** — the cross-check above, live, with both meshes side by side. Change
  `boreRadius` or drop `quality` to 24 and watch the agreement degrade to exactly the faceting floor.
- **the same source as a GLSL raymarcher** — the emitted shader, the step scale, and the simplifier
  cutting 40 unreachable bores out of a part.
- **refusals, and the hybrid build** — the whole refusal catalogue with its reasons, and the
  smallest-subtree level set beside what the naive one would have cost.

All three need nothing but Field; the second needs no Manifold at all. The first and third print a
clear "install Manifold" message and fall back to the field lowering when it is absent.

For the differentiable reading there is a second document, `dev_docs/agents/demos/solidsl_inverse/metadata.edn`,
and it needs **no Manifold at all** — marching cubes and the mesh rasteriser are both pure Kotlin.
Open it in Field (**File ▸ Open**) and run its three boxes:

- **a solid, and its exact gradient** — the slot census, a template, and `checkGradient` against
  central differences computed in the box. The last probe is the ball's own centre, where the
  *parameter* gradient is exact and the *position* gradient is `NaN` and the check says so.
- **inverse design** — the fit above, with the loss curve, the seed and the fitted solid all in the
  same output, so the counterfactual is a picture in the frame rather than a number in a comment.
- **what refuses, and the one that does not** — all seven parameterisation refusals with their
  reasons, then a swallowed child dying under a hard `Union` and living under a smooth one.
