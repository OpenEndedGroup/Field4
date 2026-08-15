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

## The headline: two evaluations, and how far apart they land

`crossCheck` builds a shape both ways and compares volume, surface area, genus and a symmetric
vertex-sampled Hausdorff distance. The two sides share no line of code and no arithmetic: side A is
Kotlin sampling a distance field and summing signed tetrahedra over `Float` triangles; side B is
Manifold's C++ kernel integrating a solid in `double`. There is no bug that makes both wrong in the
same direction.

Eleven shapes, at a march cell of `size/90`, all asserted in `SolidSLCrossValidationTest`:

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

### Point membership, which is the sharpest check of the four

Volume, area and genus are all invariant under a mirror or a transposed axis. Membership is not. So
the suite also samples points and asks both lowerings, one at a time, "is this inside?":

> **0 disagreements in 29,635 sampled points**, across the eight shapes with an exact path.

That is the check that caught `Section.revolved`'s KDoc claiming it revolves about the **Y** axis
when Manifold revolves about **Z** — 100% agreement under the Z reading, about 65% under the
documented one. Every axis and handedness convention in this package was settled that way rather
than by reading a header, and `SolidSLConventionProbe` is kept in the tree as the way to settle the
next one.

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
compile perfectly. So `SolidSLGlslTest` parses the emitted characters back with a recursive-descent
evaluator written from the grammar rather than from the emitter, and runs it:

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

Refused on purpose: composing two general Euler rotations (a matrix does not always come back out as
three angles without a gimbal case to get wrong), reassociating `SmoothUnion` (non-associative),
reordering a `Difference` (non-commutative). A rewrite that is nearly right is worse than no rewrite,
which is the same discipline as the refusals. Every rule is checked by sampling the field at 4000
points with **zero sign flips** permitted.

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
