# A solid, written down: `afield.lang.solidsl`

In `afield.lang.solidsl` a shape is a **value**. Subtracting a cylinder from a cuboid builds an expression
tree, and from that one tree you can march a mesh, build an exact CSG solid, emit GLSL, or simplify
it. A document you can open and run is [at the bottom of the page](#open-this); the KDoc for
`SolidExpr`, `Profile`, `SolidLowering` and `SolidTemplate` covers every declaration used here.

The signed-distance vocabulary in `mesh_sdf.kt` works the other way round. It declares

```diffstatic {data-lang=kotlin}
typealias ScalarField3D = (Vec3) -> Float
```

and builds its primitives and combinators on top of it: `sdfSphere`, `sdfBox`, `sdfTorus`,
`sdfUnion`, `sdfSmoothUnion`, `twisted`, `repeated`. Every one of them returns a Kotlin lambda, and a
lambda cannot be printed, compared, serialized, rewritten, or emitted as GLSL.
[RectSL](rectsl_examples.md)'s 2D tracks are expression trees for that reason, and SolidSL does the
same in three dimensions. Here is one part, and the four things you can ask of it:

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

## The vocabulary

Solids, booleans, transforms, and a 2D `Profile` you sweep into the third dimension. Everything else
in the package reads a tree built from these.

<div class="field-data-table" markdown>

<div class="field-reference" data-code-lang="kotlin" markdown>

| Example | Behavior |
| --- | --- |
| `sphere(1.0)` | Constructs a sphere centred on the origin. Its radius uses the same coordinate units as the rest of the expression. |
| `cuboid(2.0, 1.0, 0.5)` / `cuboid(1.0)` | Constructs an origin-centred box from full extents, or a cube from one side length. Dimensions are not half-extents. |
| `cylinder(2.0, 0.5, 0.25)` / `cone(2.0, 0.5)` | Builds a cylinder or frustum along z, centred on the origin. The cone is the zero-top-radius case, with its tip at positive half-height. |
| `torus(1.0, 0.2)` | Revolves a circular tube around the z axis, with the ring in the XY plane. The major radius is the ring radius and the minor radius is the tube radius. |
| `gyroid(3.0, 0.2)` | Defines an unbounded gyroid sheet field. Intersect it with a finite shape before expecting a bounded object. |
| `Profile.circle(0.5)` / `Profile.rect(1.0, 0.5)` | Builds a two-dimensional circular or rectangular profile. Extrude or revolve it to create a solid. |
| `Profile.star(5, 1.0, 0.4)` / `Profile.polygon(points)` | Builds a star from alternating radii or a polygon from supplied points. The profile can then enter the same sweep and Boolean operations. |
| `profile.offset(0.1)` | Offsets the profile boundary before a sweep. Use a profile offset when the two-dimensional cross-section is what should grow. |
| `profile.translated(1.0, 0.0).turned(30.0)` | Moves and rotates the profile in its plane. Rotation is in degrees. |
| `profile.sized(2.0)` | Scales a profile before extrusion or revolution. This changes the cross-section rather than the later solid’s height. |
| `profile.extruded(2.0, twistDegrees = 45.0)` | Sweeps the profile through the given height with optional twist in degrees. The result is a three-dimensional expression. |
| `profile.revolved(360.0)` | Revolves a profile through the supplied angle in degrees. A full turn closes the revolution around its axis. |
| `shape.at(1.0, 0.0, 0.0)` | Translates a solid expression by the given displacement. The source expression is retained as a child of the transform. |
| `shape.turned(0.0, 0.0, 45.0)` | Rotates about the origin using Euler angles in degrees, applied x then y then z. Translation order therefore matters. |
| `shape.sized(2.0)` / `shape.sized(2.0, 1.0, 0.5)` | Scales uniformly or separately along each axis. Use the uniform form when proportions should remain unchanged. |
| `shape.mirrored(1.0, 0.0, 0.0)` | Reflects across the plane specified by its normal. This reverses the side of the shape along that normal. |
| `shape.rounded(0.1)` / `shape.shelled(0.1)` | Rounds the field boundary outward by `0.1`, or creates a shell with wall thickness `0.2` (twice the argument). These field operations require sampled lowering when the exact backend cannot represent them. |
| `a + b` / `a - b` / `a and b` | Forms union, ordered subtraction or intersection. The same Boolean spellings apply to profiles; subtraction removes b from a. |
| `unionOf(listOf(a, b, c))` / `differenceOf(listOf(a, b, c))` / `intersectionOf(listOf(a, b, c))` | Combines more than two solids in one expression. Difference preserves the first shape and removes the later shapes. |
| `smoothUnion(a, b, 0.2)` / `smoothDifference(a, b, 0.2)` | Blends field boundaries with the supplied smoothing scale. Use sampled lowering for these smooth combinations. |

</div>

</div>

One knob controls resolution for the whole tree. `SolidQuality(segments = 64, edgeLengthFraction =
1.0 / 55.0)` sets how many sides a circle gets on the exact path and how fine the march is where one
is needed; `segments = 0` lets the backend choose per radius.

Each primitive built through `buildSolid` and drawn in its own panel, with a swept star profile for
the sixth. `gyroid` is missing because this picture goes through the exact path and the gyroid is the
one primitive with no exact form — see [refusals](#refusals-are-in-the-type).

```diffstatic_runner {data-lang=kotlin screenshot="assets/solid_notation/vocabulary.png" wait="8.0" capture="box" open-outputs="true" padding="20"}
:::static
import afield.lang.solidsl.*

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

In the cylinder and the cone panels, `cylinder(1.6, 0.7)` with `segments = 64` comes out of the
exact path as a 64-gon prism, and the straight segments are visible around the flat end. Raise `segments`
and the silhouette tightens. The field lowering of the same expression has a true circular
cross-section at every resolution, and is wrong in a different way instead.

## Two lowerings of one source

A `SolidExpr` lowers two ways, and [`afield.geometry.solid.manifold`](manifold.md) supplies the second of them.
Neither is a superset of the other, so which one you want depends on what you are going to do next:

<div class="field-data-table" markdown>

| Property | `toScalarField()` | `exactSolid()` |
|---|---|---|
| what the shape becomes | a distance field, sampled on a grid | a CSG tree, evaluated exactly |
| how a surface appears | marching cubes, `O(h²)` | it **is** the surface |
| a cylinder is | a true cylinder | an inscribed *n*-gon prism |
| a smooth blend is | native | **refused**, with a reason |
| a gyroid is | native | **refused** |
| runs on a GPU | yes, via `toGlsl()` | no |
| safe to subtract from | not reliably | always |

</div>

The practical consequence is a rule about which knob to turn. On the exact path the mesh is as coarse
as the shape allows — a flat face is two triangles, an L-bracket cut from a plate is **20 triangles**
where the marched version of the same expression is 71,288 — and refining the march does nothing at
all, because there is no march. What you turn there is `segments`. On the field path, refining the
march improves a polyhedral shape and bottoms out on a curved one at Manifold's faceting floor, so
past a certain point you are paying for triangles that buy nothing.

`crossCheck` builds a shape both ways and compares volume, area, genus and a symmetric Hausdorff
distance, which is how the two are held to each other; [`dev_docs/SOLIDSL_AGREEMENT.md`](https://github.com/OpenEndedGroup/Field4/blob/main/dev_docs/SOLIDSL_AGREEMENT.md) records what
that costs and where the floors are.

## Refusals are in the type

`exactSolid()` does not return a `Solid`. It returns a `SolidLowering`, which is either the solid or
a refusal carrying a sentence about why, following `RectSLFnLowering`'s convention: a `null` invites
a caller to substitute something plausible, and an exception loses the reason at the first
`runCatching`.

```diffstatic {data-lang=kotlin}
when (val l = shape.exactSolid()) {
    is SolidLowering.Lowered -> println("volume ${l.value.volume}, genus ${l.value.genus}")
    is SolidLowering.Refused -> println("no exact form: ${l.why}")
}
```

There are two flavours:

| node | why | flavour |
|---|---|---|
| `SmoothUnion`, `SmoothDifference` | the fillet surface lies on **neither** operand, so no union, intersection or difference of the two can produce it | permanent |
| `Gyroid` | a trigonometric field on all of space, not a bounded volume | permanent |
| `Round` | `minkowskiSum` with a sphere *exists* — but that sphere is an inscribed polyhedron, so the result is strictly inside the true offset by up to its sagitta, and **refining the march does not close a gap that is not the march's** | would not converge |
| `Shell` | a grow minus an erode: `Round`'s error twice over, with a concave decomposition between them | would not converge |

The difference between the flavours is whether an exact-looking answer exists. A permanent refusal
has none. A would-not-converge refusal has one available and it is wrong by up to the sagitta of a
polyhedral ball, in a direction refining the march cannot close.

Every node in that table still draws: the field lowering takes all of them, so a refusal costs you
the exact backend, not the shape. `allRefusals()` lists every refusal in a
tree, and `exactRefusal()` answers the yes-or-no.

```diffstatic_runner {data-lang=kotlin screenshot="assets/solid_notation/refusals.png" wait="8.0" capture="box" open-outputs="true" padding="20"}
:::static
import afield.lang.solidsl.*

val q = SolidQuality(segments = 64, edgeLengthFraction = 1.0 / 70.0)

val blend = smoothUnion(sphere(0.5).at(0.0, 0.0, 1.0), cylinder(1.9, 0.26), k = 0.28)
val rounded = cuboid(1.2, 1.2, 1.2).rounded(0.18)
// A shelled sphere is a sphere from the outside, so cut it open — otherwise the picture
// shows nothing the plain sphere does not.
val shellCut = sphere(0.9).shelled(0.08) - cuboid(1.2, 1.2, 1.2).at(0.9, 0.0, 0.9)
// The gyroid has no window of its own and borrows the cuboid's, which is the bounds story below.
val gyr = gyroid(6.0, 0.30) and cuboid(2.6, 2.6, 2.6)

["blend" to blend, "rounded" to rounded, "shell" to shellCut, "gyroid" to gyr].forEach { (n, e) ->
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

Every `Profile` node lowers exactly, `Profile.offset` included, because
`Section.offset(d, JoinType.ROUND)` is the Minkowski sum with a disc, which is what `sdf − d` means.
So a rounded *slot* has an exact form where a rounded *solid* does not: Clipper offsets a 2D region
as a region, and the 3D case has to go through a polyhedral ball.

## The hybrid: march the smallest subtree that needs it

A naive fallback asks "does the whole tree lower exactly?" and marches everything if the answer is
no. One smooth blend anywhere then buys a hundred-thousand-triangle approximation of a plate that
was perfectly exact. `buildSolid` walks down instead.

```diffstatic {data-lang=kotlin}
val build = (plate + smoothUnion(ball, post, k = 0.28)).buildSolid()
println(build.report())
// hybrid: 1 subtree(s) marched, the rest exact (39552 triangles, genus 4)
//   marched SmoothUnion at edgeLength 0.0416 — a smooth blend's fillet surface lies on neither operand…
```

Measured on that part: **39,552 triangles against 110,168** for the naive whole-tree march at the
same edge length, a factor of 2.79. The plate's six faces stay six faces, and the result is still
one guaranteed-closed Manifold solid, because the exact operand and the marched one meet in a real
batch boolean.

How much the hybrid saves depends on the part. The plate below is smaller relative to its boss, so
the marched subtree's bounds cover most of the shape and the triangle counts come out close:
**29,632 against 31,240**. The difference is in where the triangles go, which the
*tessellation* in the two panels shows. On the left the plate is a handful of large triangles
with long diagonals across each face, and each bore is a clean swept strip: those are exact planes
and exact cylinders, and they are coarse because a plane needs two triangles and no more. Only the
blended boss carries a fine mesh, because only the boss was sampled. On the right the same part is
uniformly dense: the flat faces are diced into thousands of triangles that buy nothing, because
marching cubes has no way to know a plane is a plane. The code builds the part both ways and draws
them side by side:

```diffstatic_runner {data-lang=kotlin screenshot="assets/solid_notation/hybrid.png" wait="8.0" capture="box" open-outputs="true" padding="20"}
:::static
import afield.lang.solidsl.*

val q = SolidQuality(segments = 96, edgeLengthFraction = 1.0 / 80.0)

val plate = cuboid(3.2, 2.2, 0.42)
val bores = unionOf([-1.1 to -0.7, 1.1 to -0.7, -1.1 to 0.7, 1.1 to 0.7].map { (x, y) ->
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

The hybrid build needs the expression to know its own extent, which a closure cannot tell you:
`SolidExpr.bounds()` is computed **symbolically**, with nothing sampled and nothing
built. It is also what lets `gyroid() and cuboid(2, 2, 2)` work at all: the gyroid child has no
window of its own and borrows its parent's.

## The same source as a shader

Two calls turn an expression into GLSL:

```diffstatic {data-lang=kotlin}
val emitted = scene.toGlsl()          // GlslSolid(sdSolid, 174 statements, stepScale 0.4, 2 notes)
println(scene.toRaymarchFragment())   // sphere tracing, a normal, two lights, ready to paste
```

`toRaymarchFragment()` hands back a whole fragment shader, with the normal taken from the field's
gradient. `toGlsl()` is the function alone, if you have a pipeline of
your own to put it in.

The emitted code is straight-line, single-assignment and entirely scalar — no `vec3` locals, no
swizzles, no loops. GPU drivers re-vectorise it anyway, and code that plain is easy to parse back,
which is how the emitter is tested.

Read `GlslSolid.stepScale` before you trace with it. Sphere tracing is only safe where the field is a
lower bound on the true distance, and several nodes break that: `Intersect` and `Difference` are
pointwise maxima, and a twisted `Extrude` shears space. The emitter reports `1.0`, `0.7` or `0.4` with
the offending node named; multiply your march step by it.

## The simplifier

Most of `simplified()` is housekeeping any algebra accumulates the moment somebody generates code
into it: identity transforms drop, adjacent translations fold, nested booleans of one kind flatten
into a single n-ary node — which matters here because n-ary is what reaches Manifold's *batch*
boolean rather than a fold of *n−1* nested ones.

Two rules go further, and both need a tree rather than a closure. **Duplicate operands collapse**,
because two `SolidExpr.Sphere(1.0)` are `==` where two `sdfSphere(1.0)` closures are different
objects forever. And **an operand that cannot reach the body is dropped**, decided from symbolic
bounds: on a plate with fifty bores of which three actually meet it, the emitted shader goes from
**610 statements to 46**.

One rule can change which backend you get. `SmoothUnion(a, b, k = 0)` *is* a union, and a union has
an exact CSG form where a smooth blend never will, so simplifying can move an expression off the
level-set fallback and onto the exact path:

```diffstatic {data-lang=kotlin}
println(smoothUnion(a, b, k = 0.0).simplificationReport())
// nodes 3 -> 1, GLSL 19 -> 10 statements, path level set -> exact
```

Three rewrites are refused, so do not expect them: composing two general Euler rotations (a matrix
does not always come back out as three angles), reassociating `SmoothUnion`, reordering a
`Difference`. The last two are simply not associative and not commutative.

## Solving for the numbers

A tree you can read is also a tree you can differentiate, and that is the third thing you can do with
one source. Declare which numbers in a shape are free, hand over some samples of the shape you
actually want, and a gradient finds them — the 3D version of what
[`afield.lang.rectsl.RectSLFn`](rectsl_inverse_animation.md) does for 2D tracks.

The machinery is `afield.math.autodiff.AD`, reverse-mode automatic differentiation over `Double`:
`SolidGradient.kt` is `SolidField.kt`'s `toField3` transposed line for line into `AD` arithmetic.
Reverse mode because the objective is one scalar loss over many sample points and a handful of
parameters, so one forward pass and one backward sweep give every parameter derivative at once. The
position gradient falls off the same tape for free, which is what makes the fitted result still a
field you can march.

### Naming the numbers

A `SolidExpr` is an anonymous tree of `data class`es with no names anywhere in it. A parameter is
bound instead to a **structural address**:

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

`segments` is missing from that list, and cannot be fitted. `toScalarField()` ignores it entirely — a
`Sphere`'s field is a true sphere whatever its `segments` says — and the exact lowering consumes it
as a count of polygon sides, where its effect is a jump. Neither has a derivative with respect to it.

### What `parameterize` refuses

Differentiating refuses nothing: every node with a field form has a gradient form, because `AD` is
closed over everything `Field3` uses. It is *binding a parameter* that can be refused, and the
refusals fall into three groups you can act on:

- **the address is wrong** — a path that does not resolve, a predicate that matched nothing, or no
  `param(…)` call at all. The message lists the slots that *do* exist, so `numericSlots()` and the
  refusal tell you the same thing from either end.
- **two names, one number** — a name declared twice, two names claiming the same slot, or one name
  tying slots that currently hold different values, which would make the seed ambiguous.
- **the seed is singular before the first step** — a `Scale` factor at zero (the field divides by it
  and the derivative goes as `1/s²`) or a blend `k` at zero (its field clamps at `1e-9`, below which
  the blend is a hard boolean with no derivative in `k`, so the parameter would never move).

Which nodes are *good* to fit is the inverse of which nodes lower exactly:

| node | `toScalarField` | `exactSolid` | the gradient |
|---|---|---|---|
| `SmoothUnion` | native | **refused** — the fillet lies on neither operand | native, and **C¹**: no kink at all |
| `SmoothDifference` | native | **refused** | native, C¹ |
| `Gyroid` | native | **refused** — not a bounded volume | native; one `abs` kink on the mid-sheet |
| `Round` | native | **refused** — would not converge | native, exactly `f − r` |
| `Union` | native | native | a subgradient on the seam, split 0.5/0.5 |

The two nodes the exact kernel refuses outright are the two the gradient handles best, so if a shape
is going to be fitted, write it with `smoothUnion` and `smoothDifference`. `gradientNotes()` says
which of these apply to your own expression, beside `fieldNotes()` and `allRefusals()`.

### Where the gradient has a kink

A distance field is full of kinks: `min` and `max` *are* CSG, `abs` is `Shell` and `Gyroid`,
`sqrt` at zero is every radial primitive's own centre. `abs`, `min` and `max` split the derivative
**0.5/0.5 at an exact tie**, so a sample that lands exactly where two CSG children meet gets half the
gradient from each side rather than an arbitrary one of them, and the step it takes still decreases
the loss.

`gradientNotes()` also flags the two places a smaller step cannot help. At a radial primitive's own
centre `√·` has an infinite derivative, so a sample there gives `Infinity` in the *position* gradient
and `NaN` as soon as it meets a zero: sample away from centres, or use the parameter gradient, which
is finite there. And a parameter that only feeds an empty subtree, or a `segments` count, has
derivative exactly zero for every value, so a descent on it reports convergence immediately. That
second one arrives looking like success.

### Inverse design

This fit samples a target shape made from the template itself, starts from wrong values for all
three parameters, and recovers them:

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

The parameters come back to about `1e-10`, and `fit.fitted` is an ordinary expression you can use
like any other.

Target, seed and fitted, all three marched in the same run, each panel labelled with the loss
measured for it:

```diffstatic_runner {data-lang=kotlin screenshot="assets/solid_notation/inverse.png" wait="12.0" capture="box" open-outputs="true" padding="20"}
:::static
import afield.lang.solidsl.*

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

The middle and right panels are the same shape, to about `1e-10`. All three panels share one camera,
because `MeshView.distance` is in bounding-sphere radii and auto-framing would draw a shape and a
1.5× copy of it at the same apparent size.

### When only half the model can fit

The fit above is the easy case: the target was generated from the model, so a correct answer
existed, and the loss reaches `4.6e-22`. A real target is rarely something your model can reproduce
exactly.

Here is the same fit on a problem with no exact answer. The target is a **sphere and a
cylinder**. The model is a **cube and a cylinder**. The cylinder is the same primitive on both sides,
so its numbers are recoverable. The cube is being asked to be a sphere, and no side length achieves
that.

```diffstatic {data-lang=kotlin}
val truth  = sphere(0.62) + cylinder(2.20, 0.28)
val target = truth.sampleField(count = 3000, seed = 17)

val model = (cuboid(1.0) + cylinder(1.0, 1.0).turned(y = 1.0)).parameterize {
    param("cubeSize")  { it.node == "Cuboid" }                                  // one number, all three extents
    param("barHeight") { it.node == "Cylinder" && it.field == "height" }
    param("barRadius") { it.node == "Cylinder" && it.field.startsWith("radius") }
    param("barTilt")   { it.node == "Rotate" && it.field == "y" }               // an ANGLE, in degrees
}.orThrow()

val fit = model.fitParameters(
    target,
    start = doubleArrayOf(1.35, 1.60, 0.42, 25.0),
    SolidFitOptions(method = OptimizeMethod.ADAM, iterations = 6000, learningRate = 0.015),
)

fit.report()
// fit 4 parameter(s) in 4176 iterations (gradient)
//   cubeSize           1.350000  ->      0.855279
//   barHeight          1.600000  ->      2.197225
//   barRadius          0.420000  ->      0.281335
//   barTilt           25.000000  ->     -0.009525
//   loss           7.044887e-02  ->  3.766267e-03
//   rms distance   2.654221e-01  ->  6.136992e-02
//   improvement    18.71x
```

Marched, the seed, the fit and the target:

```diffstatic_runner {data-lang=kotlin screenshot="assets/solid_notation/partial.png" wait="12.0" capture="box" open-outputs="true" padding="20"}
:::static
import afield.lang.solidsl.*

// A sphere and a cylinder. The model below gets a CUBE and a cylinder.
val truth = sphere(0.62) + cylinder(2.20, 0.28)
val target = truth.sampleField(count = 3000, seed = 17)

val model = (cuboid(1.0) + cylinder(1.0, 1.0).turned(y = 1.0)).parameterize {
    param("cubeSize") { it.node == "Cuboid" }
    param("barHeight") { it.node == "Cylinder" && it.field == "height" }
    param("barRadius") { it.node == "Cylinder" && it.field.startsWith("radius") }
    param("barTilt") { it.node == "Rotate" && it.field == "y" }
}.orThrow()

val seed = doubleArrayOf(1.35, 1.60, 0.42, 25.0)
val fit = model.fitParameters(
    target, seed,
    SolidFitOptions(method = OptimizeMethod.ADAM, iterations = 6000, learningRate = 0.015),
)
println(fit.report())

// `margin` is not decoration here. `march`'s window defaults to the shape's OWN bounding box, and
// a cuboid's faces lie exactly ON that boundary — the outermost grid samples sit at the zero
// crossing, the side faces never close, and the cube renders as a flat sheet. A sphere only touches
// its box tangentially, at six points, which is why the fit above gets away with the default.
fun m(e: SolidExpr) = e.march(resolution = 104, margin = 0.06)
threeGrid(
    "model at the seed  ·  loss %.3e".format(fit.initialLoss) to m(model.withValues(seed)),
    "fitted  ·  loss %.3e  (%.1fx)".format(fit.loss, fit.improvementFactor) to m(fit.fitted),
    "target  ·  a SPHERE and a cylinder" to m(truth),
    channel = "partial", columns = 3, height = 300,
    view = MeshView(width = 320, height = 250, yawDegrees = 32.0, pitchDegrees = 22.0, wireframe = true),
)
```

![Three panels in a row, flat grey wireframe on a dark background. Left, labelled "model at the seed, loss 7.045e-02", a large cube with its rod almost entirely buried inside it — the rod shows only as a slanted outline on the cube's front face, tilted about 25 degrees off the axis. Middle, labelled "fitted, loss 3.766e-03 (18.7x)", a smaller cube with the rod now square to it, passing right through and protruding well beyond. Right, labelled "target, a SPHERE and a cylinder", a sphere with a rod of visibly the same length, thickness and orientation as the middle panel's, so the two rods match while one body is a cube and the other is a sphere](assets/solid_notation/partial.png)

Compare the rods: same length, same thickness, in the middle and right panels. Then compare the
bodies: a cube and a sphere, and no amount of further descent changes that.

#### The descent, as frames

`fit.run.trajectory` is the parameter vector at every recorded iteration, and `fit.shapeAt(i)`
substitutes one back into the template, so you can march the shapes the optimizer really held. That
shows something the two ends cannot: *when* each parameter stops moving.

```diffstatic_runner {data-lang=kotlin screenshot="assets/solid_notation/converging.png" wait="14.0" capture="box" open-outputs="true" padding="20"}
:::static
import afield.lang.solidsl.*

val truth = sphere(0.62) + cylinder(2.20, 0.28)
val target = truth.sampleField(count = 3000, seed = 17)

val model = (cuboid(1.0) + cylinder(1.0, 1.0).turned(y = 1.0)).parameterize {
    param("cubeSize") { it.node == "Cuboid" }
    param("barHeight") { it.node == "Cylinder" && it.field == "height" }
    param("barRadius") { it.node == "Cylinder" && it.field.startsWith("radius") }
    param("barTilt") { it.node == "Rotate" && it.field == "y" }
}.orThrow()

val fit = model.fitParameters(
    target, doubleArrayOf(1.35, 1.60, 0.42, 25.0),
    SolidFitOptions(method = OptimizeMethod.ADAM, iterations = 6000, learningRate = 0.015),
)

// `run.trajectory` is the parameter vector at every recorded iteration, indexed exactly like
// `run.losses`. `shapeAt` substitutes one back into the template, so these are the shapes the
// optimizer really held -- not six descents truncated at six different caps.
val frames = [0, 150, 500, 1200, 2400, fit.run.trajectory.lastIndex]
val tilt = model.names.indexOf("barTilt")

threeGrid(
    *frames.map { i ->
        "iter %d  ·  tilt %.1f deg  ·  loss %.2e"
            .format(i, fit.run.trajectory[i][tilt], fit.run.losses[i]) to
            fit.shapeAt(i).march(resolution = 88, margin = 0.06)
    }.toTypedArray(),
    channel = "converging", columns = 6, height = 210,
    view = MeshView(width = 190, height = 170, yawDegrees = 32.0, pitchDegrees = 22.0, wireframe = true),
)
```

![Six small wireframe panels in a row, a cube with a rod through it, each labelled with its iteration, tilt and loss. In the first the cube is large and the rod is buried inside it, showing only as an outline on the front face. In the second the cube has shrunk to roughly its final size and a short fat rod juts out of it at a steep angle. Across the next three the cube barely changes while the rod thins and lengthens and swings progressively upright. In the last two the rod is square to the cube and the two panels are indistinguishable](assets/solid_notation/converging.png)

The cube settles almost immediately, and the angle takes the whole descent. `cubeSize` goes
`1.350 → 0.847` inside the first 150 iterations and then spends the remaining four thousand between
`0.879` and `0.855`; `barTilt` goes `25.0° → 22.5°` in that same span and needs until iteration 2400
to reach `0.18°`. Both are visible in the strip: the body settles in frame two, the rod is still
turning in frame five.

`barRadius` gets worse before it gets better. It goes `0.420 → 0.464` over the first 150 iterations before coming back down through
`0.356` and `0.290` to `0.281`, which is the fat stub in frame two. While the cube is still far too
big the rod is the only thing that can carry the target's volume, and it is rewarded for thickening
until the cube gets out of the way. The loss falls at every frame, so only a per-parameter trace
shows this.

The cube reaches its compromise early because a compromise is a shallow, wide minimum: it is already
as close to a sphere as a cube gets. The angle has a real minimum at the bottom of a narrow valley,
so it walks all the way down. Stopped at iteration 500, this fit reports a reasonable-looking
`loss 1.38e-02`, five times lower than the seed, and a `barTilt` of `16.3°` that is still far off.

At the end the cylinder is recovered: `2.197225` against a true `2.20`, `0.281335` against a true
`0.28`, and `barTilt`, seeded 25° off, back to `-0.009525°`. The cube settled at `0.855279`. No side
length makes a cube a sphere, but there is a sensible range: a cube that fits a sphere of radius `R` at all has a side between the inscribed
`2R/√3 = 0.7159` and the circumscribed `2R = 1.2400`. `0.855279` sits inside it, nearer the inscribed
end, which is where a least-squares fit on a *field* belongs.

Adding the tilt does not change the final loss: the four-parameter residual and the three-parameter
one agree to six figures. All of the residual comes from the cube.

The report prints all four numbers the same way, and nothing in the loss says which are recovered
values and which is a compromise. Only you know which parts of your model can represent which parts
of the target, so read the report with that in mind.

??? aside "Why this fit needs 6000 iterations when the recovery fit needed 14"

    `barTilt` is measured in **degrees** and everything else is measured in **units of the shape**,
    and `SolidFitOptions` carries one `learningRate` for all of them. ADAM's step is bounded by that
    rate, so a parameter whose natural scale is ~25 moves at the same ~0.015 per iteration as one
    whose natural scale is ~1. At `iterations = 1200` this fit stopped on `max iterations` with
    `barTilt 25.000000 -> 5.752590`. It was still moving: 19.25° in 1200 steps is the step cap times
    the iteration count. At 6000 it converges on the gradient tolerance in 4176.

    So the angle costs about ten times the iterations of the same fit without it (4176 against
    387). If you add an angle to an ADAM fit and it seems to stall, check the arithmetic:
    `iterations x learningRate` against the distance the parameter has to travel. Or leave
    `method` at its LBFGS default, whose line search is indifferent to units: on this problem it
    reaches the same loss in 36 iterations. The figures use ADAM because its small steps make a
    readable filmstrip ([`dev_docs/SOLIDSL_OPTIMIZER_MEASUREMENTS.md`](https://github.com/OpenEndedGroup/Field4/blob/main/dev_docs/SOLIDSL_OPTIMIZER_MEASUREMENTS.md)).

A cube small enough to sit wholly inside the bar never moves at all: seeded at `0.40` against a bar
of radius `0.55`, `cubeSize` comes back `0.400000` and `fit.deadParameters` names it.

### What makes a parameter refuse to move

This is the failure mode of inverse design, and it presents as a *converged* optimisation. A hard
`Union` is a pointwise `min`, so it hands the **whole** derivative to whichever child is nearest. A
child that is nowhere nearest (a small sphere swallowed inside a large one) receives exactly `0.0`
from every sample, and a descent on its radius stops immediately because the gradient really is
zero.

```diffstatic {data-lang=kotlin}
unionOf([sphere(1.0), sphere(0.25)]).parameterize { … }
    .fitParameters(target, …).deadParameters      // [inner]

smoothUnion(sphere(1.0), sphere(0.25), k = 0.9).parameterize { … }
    .fitParameters(target, …).deadParameters      // []
```

`SolidFit.deadParameters` names the parameters whose gradient was exactly zero at the start, and
those the descent never moved off their seed. It is
reported rather than thrown, because a legitimately dead parameter is a fine thing to have in a
vector — a fit over six holes where one is genuinely irrelevant — and it is the *unexpectedly* dead
one that looks like a success.

### Fitting to a point cloud

`fitParameters` wants signed distances at its sample points, which is a rich target and a slightly
circular one: the distances come from `toScalarField()`, so the oracle already speaks the model's
language. `fitToPointCloud` takes what you actually have — unoriented points on a surface, no sign,
no normals, no correspondence, some noise:

```diffstatic {data-lang=kotlin}
val cloud = target.samplePointCloud(count = 3000, seed = 4, noise = 0.004)
val fit = template.fitToPointCloud(cloud, start = doubleArrayOf(0.3, 1.1, 0.4))
```

The loss is `f(pᵢ)²` averaged over the cloud, and everything that goes wrong follows from what it
leaves out. Nothing in it wants the interior filled, so a shape that puts *some* surface through the
points scores as well as the one that generated them. A cloud that surrounds a closed surface pins it
from every side and recovers well; crop it to one hemisphere and every parameter that only shows
itself on the missing side wanders freely, with the loss never going up. `Shell` and `Round` are
riskier here than under `fitParameters` for the same reason: `abs(f) − t` is zero on two surfaces, so
a shell can satisfy a cloud with the wrong one. `SolidCloudFit.unconstrainedParameters` is the
point-cloud analogue of `deadParameters` and exists for the same reason — the failure arrives looking
like convergence.

#### A one-sided view

Here the target is three lobes in a row, each with its own radius, and the cloud is cropped to
`x < 0.10`, as a scanner looking from one side would see it. The right-hand lobe is centred at
`x = 0.8`, so no point lands on it. `unconstrainedParameters` is computed from the gradient at the
start, so a fit with `iterations = 0` reports it before the descent takes a single step:

```diffstatic_runner {data-lang=kotlin screenshot="assets/solid_notation/one_sided.png" wait="12.0" capture="box" open-outputs="true" padding="20"}
:::static
import afield.lang.solidsl.*
import afield.geometry.mesh.TriMesh

// Three lobes on the X axis with independent radii.
val template = smoothUnion(
    smoothUnion(sphere(0.50), sphere(0.35).at(-0.80, 0.0, 0.0), 0.25),
    sphere(0.42).at(0.80, 0.0, 0.0),
    0.25,
).parameterize {
    param("middle", "SmoothUnion/a/SmoothUnion/a/Sphere.radius")
    param("left", "SmoothUnion/a/SmoothUnion/b/Translate/Sphere.radius")
    param("right", "SmoothUnion/b/Translate/Sphere.radius")
}.orThrow()

val truth = doubleArrayOf(0.50, 0.35, 0.42)
val seed = doubleArrayOf(0.30, 0.56, 0.20)

// The view from one side: everything right of x = 0.10 is never seen.
val cloud = template.withValues(truth).samplePointCloud(count = 4000, seed = 4)
    .filter { x, _, _ -> x < 0.10 }

// Before the descent: zero iterations, so this is only the gradient at the seed.
val before = template.fitToPointCloud(cloud, seed, SolidFitOptions(iterations = 0))
println("before any step, unconstrained: ${before.unconstrainedParameters}")

val fit = template.fitToPointCloud(cloud, seed)
println(fit.report())

// Each cloud point drawn as a tiny octahedron, so the cloud can sit in a mesh panel.
fun dots(c: SolidPointCloud, r: Float = 0.014f): TriMesh {
    val pos = FloatArray(c.size * 18)
    val faces = intArrayOf(0, 2, 4, 2, 1, 4, 1, 3, 4, 3, 0, 4, 2, 0, 5, 1, 2, 5, 3, 1, 5, 0, 3, 5)
    val idx = IntArray(c.size * 24)
    for (i in 0 until c.size) {
        val x = c.xs[i].toFloat(); val y = c.ys[i].toFloat(); val z = c.zs[i].toFloat()
        floatArrayOf(x + r, y, z, x - r, y, z, x, y + r, z, x, y - r, z, x, y, z + r, x, y, z - r)
            .copyInto(pos, i * 18)
        for (k in faces.indices) idx[i * 24 + k] = i * 6 + faces[k]
    }
    return TriMesh(pos, idx)
}

val right = template.names.indexOf("right")
fun m(e: SolidExpr) = e.march(resolution = 96, margin = 0.05)
threeGrid(
    "the cloud  ·  ${cloud.size} points, x < 0.10" to dots(cloud),
    "seed  ·  right = %.2f".format(seed[right]) to m(template.withValues(seed)),
    "fitted  ·  right = %.6f".format(fit.values[right]) to m(fit.fitted),
    "truth  ·  right = %.2f".format(truth[right]) to m(template.withValues(truth)),
    channel = "one_sided", columns = 4, height = 230,
    view = MeshView(width = 250, height = 190, yawDegrees = 0.0, pitchDegrees = 30.0, distance = 3.6, wireframe = false), padding = 1.41,
)
```

![Four panels in a row, grey on a dark background, all seen from above with X running left to right. First, labelled "the cloud, 2065 points, x < 0.10", a scatter of small grey dots tracing the left and middle lobes, cut off sharply at its right edge. Second, labelled "seed, right = 0.20", a large left lobe joined to a smaller middle lobe, and a small separate ball far to the right. Third, labelled "fitted, right = 0.200000", a medium left lobe and a large middle lobe joined smoothly, with the same small ball now hanging off the middle by a thin neck. Fourth, labelled "truth, right = 0.42", three well-joined lobes with the right one nearly as large as the left](assets/solid_notation/one_sided.png)

The left and middle lobes come back to within `4e-4` of the truth. `right` is still `0.200000`,
the seed, after a descent that reports convergence, and `unconstrainedParameters` named it before
the first step. The loss cannot see it: there are no points out there to be off the surface. More
iterations, a different optimiser or a restart do not change that; more of the view does.

## Axis conventions

Primitives are centred on the origin. Everything else follows Manifold's conventions unchanged:

- `cylinder` runs along **Z**.
- `Profile.extruded(h)` runs from `z = 0` to `z = h` — *not* centred, because `Section.extruded` is not.
- `Profile.revolved()` turns about **Z**, profile at `x ≥ 0`, counter-clockwise from `+X`. The mesh
  lathe `revolveMesh` defaults to Y; pass `axis = RevolveAxis.Z` to get the same solid from it.
- `turned(x, y, z)` is in **degrees**, applied X then Y then Z.

There is no `Torus` node:
`torus(R, r)` is a function that builds `Profile.circle(r).translated(R, 0).revolved()`, which is
what a torus is.

## Open this {#open-this}

<iframe
  style="width:100%;height:520px;border:1px solid #2a3038;border-radius:8px"
  src="offline.html?file=assets/solidsl_gallery/metadata.edn"
  loading="lazy"></iframe>

The [`solidsl_gallery`](agent_transcripts.md#solidsl_gallery) starting point has three boxes. Open it and run them:

- **one shape, two evaluations** — one part put through both lowerings, with the meshes side by side.
  Change `boreRadius` or drop `quality` to 24 and watch the agreement degrade to the faceting floor.
- **the same source as a GLSL raymarcher** — the emitted shader, the step scale, and the simplifier
  dropping 40 extra bores, most of them nowhere near the part.
- **refusals, and the hybrid build** — the whole refusal catalogue with its reasons, and the
  smallest-subtree level set beside what the naive one would have cost.

All three need nothing but Field; the second needs no Manifold at all. The first and third print a
clear "install Manifold" message and fall back to the field lowering when it is absent.

For fitting there is a second starting point, [`solidsl_inverse`](agent_transcripts.md#solidsl_inverse),
which needs no Manifold at all. Open it and run its three boxes:

- **a solid, and its exact gradient** — the slot census, a template, and `checkGradient` against
  central differences computed in the box. The last probe is the ball's own centre, where the
  *parameter* gradient is exact and the *position* gradient is `NaN` and the check says so.
- **inverse design** — the fit above, with the loss curve, the seed and the fitted solid in one
  output.
- **what refuses, and the one that does not** — every parameterisation refusal with its reason, then
  a swallowed child dying under a hard `Union` and living under a smooth one.
