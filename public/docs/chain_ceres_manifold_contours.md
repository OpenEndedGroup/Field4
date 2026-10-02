# A polytope, canonicalised, and sliced into layers

This page builds a polytope from Conway notation, makes it canonical, turns it into a closed solid,
and slices it into the layers a 3D printer would print. `afield.stdlib.polytope` holds the Conway
operator algebra and the two ways of making a form canonical (an iteration, and a solver built on
Ceres). `afield.manifold` does exact geometry on closed solids. `Solid.slice` cuts a solid with a
plane and returns the 2D contours.

Canonicalising a polytope can change its layers a great deal while leaving the number of contours
on each layer alone. The layer stack also checks the polytope itself: its areas have to add up to
the solid's volume, and when they do not, the surface passes through itself.

[A polytope, cut open, and traced](chain_polytope_render.md) starts from the same polytopes and ends
in a path-traced picture instead.

## Cutting exactly on a face, a vertex, or the top

A slicer meets this case on every part with a flat top. This box asks what `slice` returns when the
cutting plane is exactly a face plane, exactly a vertex, or exactly the top of the bounding box.

```diffstatic_runner {data-lang=kotlin screenshot="assets/chain_ceres_manifold_contours/degenerate.png" wait="8" capture="box" open-outputs="true" w="1100" h="560" padding="16" timeout="180" name="chain-contours-degenerate" requires="manifold" alt="A thirteen-row table of cuts: each named cut with its z to nine decimals, the area the slice returned, and how many contours it had" embed=true}
:::static
import afield.manifold.Solid

// Geometry with closed-form answers only, so nothing below rests on a tolerance.
val rows = mutableListOf<List<Any?>>()
fun cut(what: String, solid: Solid, z: Double) {
    solid.slice(z).use { s ->
        // Nine decimals, because two of these cuts are one part in a billion apart and a
        // table that printed both as 1.000000 would read as a contradiction.
        rows += listOf(what, "%.9f".format(z), if (s.isEmpty) "—" else "%.4f".format(s.area), s.contourCount)
    }
}

Solid.scope {
    val cube = Solid.cube(2.0, 2.0, 2.0, center = true)     // faces at z = ±1
    cut("2×2×2 cube, through the middle", cube, 0.0)
    cut("… exactly on the bottom face plane", cube, -1.0)
    cut("… exactly on the top face plane", cube, 1.0)
    cut("… one part in a billion below the top", cube, 1.0 - 1e-9)
    cut("… one part in a billion above it", cube, 1.0 + 1e-9)

    val onBed = Solid.cube(2.0, 2.0, 2.0)                   // the same cube, sitting on z = 0
    cut("the same cube on the bed, at z = 0", onBed, 0.0)
    cut("… at z equal to its own height", onBed, 2.0)

    // A 4×4×1 slab with a 2×2×1 block on top of it. z = 1 is the slab's top face *and* the
    // block's bottom face, so it is the case that separates the two possible rules.
    val step = (Solid.cube(4.0, 4.0, 1.0) + Solid.cube(2.0, 2.0, 1.0).translated(1.0, 1.0, 1.0))
        .checked("step")
    cut("a 4×4 slab with a 2×2 block on it, just below the step", step, 0.999999)
    cut("… exactly on the step", step, 1.0)

    val cone = Solid.cone(2.0, 1.0, segments = 256, center = true)
    cut("a cone, exactly through its apex vertex", cone, cone.bounds.max.z.toDouble())
    val tet = Solid.tetrahedron()
    cut("a tetrahedron, exactly on its topmost edge", tet, tet.bounds.max.z.toDouble())
    cut("… and on its lowest edge", tet, tet.bounds.min.z.toDouble())

    // The prediction. Two unit cubes touching along one edge at z = 0.5: the lower one ends
    // there, the upper one begins there. If only material above the plane answers, exactly one
    // square comes back, and it is the upper cube's — centred at x = 1 rather than x = 0.
    val pair = Solid.compose(listOf(
        Solid.cube(1.0, 1.0, 1.0, center = true),
        Solid.cube(1.0, 1.0, 1.0, center = true).translated(1.0, 0.0, 1.0),
    ))
    pair.slice(0.5).use { s ->
        val centre = (s.bounds.first.x + s.bounds.second.x) / 2.0
        rows += listOf(
            "two unit cubes meeting along one edge, cut on it",
            "0.500000000", "%.4f".format(s.area) + " centred at x = %.4f".format(centre), s.contourCount,
        )
    }
    Unit
}

markdown("cuts", Table(listOf("the cut", "z", "area", "contours")).rows(rows).h, width = 1040)
```

The cuts all follow one rule:

> `slice(z)` returns the cross-section of the material **at and above** `z`. A face lying in the
> cutting plane counts when the solid is above it and does not count when the solid is below it.

So a cube spanning `[−1, 1]` returns its whole bottom face at `z = −1` and nothing at `z = +1`. Cut
a hair below the top and the top face is back. On the step, a cut just below `z = 1` returns the
wide slab, and a cut at exactly `z = 1` returns only the narrow block above it. Two cubes stacked
corner to corner return only the upper one's square at the plane where they touch.

Zero-area contacts return nothing: a cone cut through its apex, and a tetrahedron cut on either of
its two horizontal edges. A point and a segment have no area, so there is no region to hand back.

!!! tip "What this means for a layer ladder"

    Put the cuts at layer *midpoints*, not at layer boundaries. Boundaries land on face planes on
    exactly the parts most likely to have them — anything designed flat — and a boundary ladder on
    a part whose height is an exact multiple of the layer height loses its top layer outright.
    Every stack below is `z = zₘᵢₙ + h·(i + ½)/n`.

## Canonicalisation, scale and orientation

`canonicalize()` seeks the representative with every edge tangent to one common sphere, the centroid
of those tangency points at the origin, and every face planar
([Polytopes & the Conway Algebra](polytope_examples.md) has the guarantees). Getting there involves
a fixed scale: the mean tangency radius is driven to one. So the canonical form comes out at a
different size from the raw form, and comparing their slices as they are would mostly compare sizes.

So the box scales each form to enclosed volume exactly 1 and moves it so `z = 0` is its lowest
point: the same amount of material, sitting on the print bed.

Orientation matters too. Vertex indexing survives canonicalisation, so the box measures how far each
vertex's direction from the centroid turns. If the largest turn is small, one set of cutting planes
cuts both forms comparably.

```diffstatic_runner {data-lang=kotlin screenshot="assets/chain_ceres_manifold_contours/gyro.png" wait="14" capture="box" open-outputs="true" w="1240" h="980" padding="16" timeout="360" name="chain-contours-gyro" requires="manifold" alt="Two plan views of a 48-layer stack seen from overhead: on the left one orange square, because all of raw gC's layers are the same square; on the right the canonical form's forty-eight nested rings twisting from cyan at the bed to orange at the top. Below them a table of the four forms and a plot of layer area against height, in which gC raw is a flat line at 1 and the other three are domes peaking near 1.25" embed=true}
:::static
import afield.manifold.Solid
import afield.manifold.toSolid
import afield.stdlib.polytope.NormalPolicy
import afield.stdlib.polytope.PolyhedralObject
import afield.stdlib.polytope.canonicalize
import afield.stdlib.polytope.conway
import afield.stdlib.polytope.triangulate
import afield.util.PngEncoder

val LAYERS = 48
val PANEL = 600

:::fold the chain, the bed gauge, the layer stack, and a plan-view rasteriser
// Conway notation to a Manifold solid. The triangulator cuts each polygon on its own, so a
// corner shared by three faces arrives as three vertices; weld before handing it over,
// because being a manifold is a property of the indices.
fun solidOf(p: PolyhedralObject): Solid =
    p.triangulate(normals = NormalPolicy.Flat).mesh.weld(1e-6).toSolid()

// Enclosed volume exactly 1, lowest point at z = 0, centred in x and y. Without this,
// every area below would be a statement about the canonicaliser's scale gauge.
fun onTheBed(s: Solid): Solid {
    val k = s.scaled(1.0 / Math.cbrt(s.volume))
    val b = k.bounds
    return k.translated(-b.center.x.toDouble(), -b.center.y.toDouble(), -b.min.z.toDouble())
}

fun cutHeights(s: Solid, n: Int): List<Double> {
    val low = s.bounds.min.z.toDouble()
    val h = s.bounds.max.z.toDouble() - low
    return (0 until n).map { low + h * (it + 0.5) / n }
}

fun contoursOf(s: Solid, n: Int): List<List<List<Vec2>>> =
    cutHeights(s, n).map { z -> s.slice(z).use { it.toPolygons() } }

fun areasOf(s: Solid, n: Int): List<Double> =
    cutHeights(s, n).map { z -> s.slice(z).use { it.area } }

fun centroidOf(v: List<Vec3>): Triple<Double, Double, Double> = Triple(
    v.sumOf { it.x.toDouble() } / v.size,
    v.sumOf { it.y.toDouble() } / v.size,
    v.sumOf { it.z.toDouble() } / v.size,
)

// The largest and mean angle any vertex's direction from the centroid turns through.
// Canonicalisation keeps the vertex indexing, so this is a true per-vertex comparison
// rather than a shape descriptor that might agree by accident.
fun directionTurn(a: PolyhedralObject, b: PolyhedralObject): Pair<Double, Double> {
    val p = a.realization!!.vertexCoordinates
    val q = b.realization!!.vertexCoordinates
    val cp = centroidOf(p)
    val cq = centroidOf(q)
    var worst = 0.0
    var total = 0.0
    for (i in p.indices) {
        val ux = p[i].x - cp.first
        val uy = p[i].y - cp.second
        val uz = p[i].z - cp.third
        val vx = q[i].x - cq.first
        val vy = q[i].y - cq.second
        val vz = q[i].z - cq.third
        val nu = sqrt(ux * ux + uy * uy + uz * uz)
        val nv = sqrt(vx * vx + vy * vy + vz * vz)
        if (nu < 1e-9 || nv < 1e-9) continue
        val d = Math.toDegrees(acos(((ux * vx + uy * vy + uz * vz) / (nu * nv)).coerceIn(-1.0, 1.0)))
        if (d > worst) worst = d
        total += d
    }
    return worst to total / p.size
}

// A plan view of a whole layer stack, drawn with no AWT: the stack seen from overhead,
// each contour coloured by how far up it sits.
fun mark(px: IntArray, w: Int, x: Int, y: Int, c: Int) {
    if (x >= 0 && x < w && y >= 0 && y < PANEL) px[y * w + x] = c
}

fun segment(px: IntArray, w: Int, x0: Double, y0: Double, x1: Double, y1: Double, c: Int) {
    val steps = maxOf(abs(x1 - x0), abs(y1 - y0)).toInt() + 1
    for (s in 0..steps) {
        val t = s.toDouble() / steps
        mark(px, w, (x0 + (x1 - x0) * t).toInt(), (y0 + (y1 - y0) * t).toInt(), c)
    }
}

fun heightColour(t: Double): Int {
    // Cyan at the bed, orange at the top: the signal palette's two leads.
    val r = (0x7a + (0xff - 0x7a) * t).toInt()
    val g = (0xd9 + (0x9e - 0xd9) * t).toInt()
    val b = (0xff + (0x4a - 0xff) * t).toInt()
    return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}

fun plan(stacks: List<List<List<List<Vec2>>>>): ByteArray {
    val w = PANEL * stacks.size
    val px = IntArray(w * PANEL) { 0xFF0D1017.toInt() }
    // One scale for every panel, or the comparison would be about the framing.
    var reach = 1e-9
    for (stack in stacks) for (layer in stack) for (ring in layer) for (p in ring) {
        reach = maxOf(reach, abs(p.x).toDouble(), abs(p.y).toDouble())
    }
    val k = (PANEL / 2.0 - 16.0) / reach
    stacks.forEachIndexed { panel, stack ->
        val ox = panel * PANEL + PANEL / 2.0
        stack.forEachIndexed { i, layer ->
            val colour = heightColour(i.toDouble() / maxOf(1, stack.size - 1))
            for (ring in layer) {
                for (j in ring.indices) {
                    val a = ring[j]
                    val b = ring[(j + 1) % ring.size]
                    segment(px, w, ox + a.x * k, PANEL / 2.0 - a.y * k,
                        ox + b.x * k, PANEL / 2.0 - b.y * k, colour)
                }
            }
        }
    }
    return PngEncoder.encode(px, w, PANEL)
}
:::

val rows = mutableListOf<List<Any?>>()
val profiles = mutableListOf<Pair<String, List<Number>>>()
val panels = mutableListOf<List<List<List<Vec2>>>>()

for (expression in listOf("gC", "gD")) {
    val raw = conway(expression).polyhedron
    val canonical = raw.canonicalize().polyhedron
    val turn = directionTurn(raw, canonical)

    Solid.scope {
        for (label in listOf("raw", "canonical")) {
            val solid = onTheBed(solidOf(if (label == "raw") raw else canonical))
            val height = solid.bounds.max.z.toDouble()
            val a = areasOf(solid, LAYERS)
            val layers = contoursOf(solid, LAYERS)
            profiles.add("$expression $label" to a)
            if (expression == "gC") panels.add(layers)
            rows.add(listOf(
                "$expression, $label",
                "%.6f".format(height),
                "%.6f".format(solid.surfaceArea),
                "%.6f".format(a.sumOf { it } * height / LAYERS),
                layers.sumOf { it.size },
                "%.6f".format(a.min()),
                "%.6f".format(a.max()),
                if (label == "raw") "" else "%.2f° / %.2f°".format(turn.first, turn.second),
            ))
        }
        Unit
    }
}

display("plan", plan(panels))
markdown("forms", Table(listOf(
    "form", "height", "surface", "Σ area·h/n", "contours over $LAYERS layers",
    "smallest layer", "largest layer", "vertex turn, worst / mean",
)).rows(rows).h, width = 1180)
showSignals(
    profiles[0], profiles[1], profiles[2], profiles[3],
    x = (0 until LAYERS).map { (it + 0.5) / LAYERS },
    channel = "profile",
    xTitle = "height, as a fraction of the object's own",
    yTitle = "layer area (each form has volume 1)",
)
```

In the left panel every layer of raw `gC` is the same square. Gyro applied to the cube adds new
vertices, but before canonicalisation every one of them lies on the cube's own surface, so the solid
that comes out is still a unit cube. The new faces are there in the topology and not yet in the
shape.

Canonicalised, the same topology becomes the pentagonal icositetrahedron: taller, with less surface
at the same volume, and with layers that start almost at zero area on the bed and swell in the
middle. `gD` changes less, but its layers still differ noticeably.

The table shows two more things:

* **The number of contours does not change.** Both forms of each polytope have one island per
  layer and no holes. Canonicalisation moves every contour and splits none, so the questions a
  slicer cares most about (how many islands, does this layer need support) get the same answers.
* **Canonicalisation rounds the form.** At equal volume the canonical forms have less surface than
  the raw ones, and the canonical `gD` comes within a few percent of a sphere, which no solid can
  beat.

The vertex turns are small for both polytopes, so the two stacks are cut along nearly the same axis
and the differences in the plot come from the shape, not from a rotation.

## `kkC`, and the solver that reaches it

A midpoint Riemann sum over the layer areas has to return the volume the layers came from, and the
two numbers share no code: the area integral works on regions in 2D, Manifold's `volume` on the
surface in 3D. When the sum comes out wrong, the fault is in the solid, not the slicer.

Every form above sums correctly. `kkC` (kis applied twice to the cube) is a polytope that
`canonicalize()` cannot make canonical, and
[Polytopes & the Conway Algebra](polytope_examples.md) uses it as its counter-example. The Ceres
solver, `relaxWithCeres`, does reach a canonical form. This box tries both and slices each result.

```diffstatic_runner {data-lang=kotlin screenshot="assets/chain_ceres_manifold_contours/kkc.png" wait="14" capture="box" open-outputs="true" w="1240" h="820" padding="16" timeout="360" name="chain-contours-kkc" requires="ceres,manifold" alt="A three-row table of the kkC embeddings and a convergence plot. The plot has two traces against layer count: kkC as built climbing to 1.00000, and relaxWithCeres() flattening out at 0.90029 and staying there" embed=true}
:::static
import afield.manifold.FillRule
import afield.manifold.Solid
import afield.manifold.toSolid
import afield.stdlib.polytope.CeresRelaxationOptions
import afield.stdlib.polytope.NormalPolicy
import afield.stdlib.polytope.PolyhedralObject
import afield.stdlib.polytope.RelaxationPlan
import afield.stdlib.polytope.canonicalDiagnostics
import afield.stdlib.polytope.canonicalize
import afield.stdlib.polytope.conway
import afield.stdlib.polytope.relaxWithCeres
import afield.stdlib.polytope.triangulate

// relaxWithCeres needs the native wrapper: ./gradlew buildCeresRedistributable
val seed = conway("kkC").polyhedron
val iterated = seed.canonicalize().polyhedron
val solved = seed.relaxWithCeres(
    RelaxationPlan.canonicalConditions(), CeresRelaxationOptions(maxIterations = 500),
).realization.polyhedron

:::fold the same chain, plus a convergence sweep and a fill-rule comparison
fun solidOf(p: PolyhedralObject): Solid =
    p.triangulate(normals = NormalPolicy.Flat).mesh.weld(1e-6).toSolid()

fun onTheBed(s: Solid): Solid {
    val k = s.scaled(1.0 / Math.cbrt(s.volume))
    val b = k.bounds
    return k.translated(-b.center.x.toDouble(), -b.center.y.toDouble(), -b.min.z.toDouble())
}

// Σ area·h/n over n layers, at the layer midpoints.
fun riemann(s: Solid, n: Int): Double {
    val low = s.bounds.min.z.toDouble()
    val h = s.bounds.max.z.toDouble() - low
    return (0 until n).sumOf { i -> s.slice(low + h * (i + 0.5) / n).use { it.area } } * h / n
}

// POSITIVE unions overlapping same-wound contours; EVEN_ODD punches their overlap out. Equal
// areas on every layer means the contours do not overlap, and a gap is twice the overlap.
fun overlap(s: Solid, n: Int = 192): Pair<Int, Double> {
    val low = s.bounds.min.z.toDouble()
    val h = s.bounds.max.z.toDouble() - low
    var disagreeing = 0
    var gap = 0.0
    for (i in 0 until n) {
        val z = low + h * (i + 0.5) / n
        val p = s.slice(z, FillRule.POSITIVE).use { it.area }
        val e = s.slice(z, FillRule.EVEN_ODD).use { it.area }
        if (abs(p - e) > 1e-9 * maxOf(1.0, p)) disagreeing++
        gap += p - e
    }
    return disagreeing to gap * h / n / 2.0
}

val SWEEP = listOf(12, 24, 48, 96, 192, 384, 768)
:::

val rows = mutableListOf<List<Any?>>()
val sweeps = mutableListOf<Pair<String, List<Number>>>()

Solid.scope {
    for ((label, p) in listOf("kkC as built" to seed, "canonicalize()" to iterated, "relaxWithCeres()" to solved)) {
        val d = p.canonicalDiagnostics()
        val bare = solidOf(p)
        // A non-positive signed volume is not a printable body, and scaling it onto the bed
        // would mirror it into looking like one. So it is reported rather than rescued.
        if (bare.volume <= 0.0) {
            rows += listOf(label, "%.3e".format(d.tangencyRadiusSpread), "${bare.status}", bare.genus,
                "%.4f".format(bare.volume), "—", "—", "inside out")
            continue
        }
        val solid = onTheBed(bare)
        val sweep = SWEEP.map { riemann(solid, it) }
        val (disagreeing, over) = overlap(solid)
        sweeps += label to sweep
        rows += listOf(
            label, "%.3e".format(d.tangencyRadiusSpread), "${bare.status}", bare.genus,
            "%.4f".format(bare.volume), "%.5f".format(sweep.last()),
            "$disagreeing / 192, %.6f".format(over),
            "%.5f".format(1.0 - 2.0 * over),
        )
    }
    Unit
}

markdown("kkc", Table(listOf(
    "embedding", "tangency spread", "Manifold status", "genus", "signed volume",
    "Σ area·h/n at 768 layers", "layers where POSITIVE ≠ EVEN_ODD, overlap", "1 − 2·overlap",
)).rows(rows).h, width = 1180)
showSignals(
    *sweeps.map { (k, v) -> k to v }.toTypedArray(),
    x = SWEEP,
    channel = "sweep",
    xTitle = "layers in the stack",
    yTitle = "Σ area·h/n (the volume is 1)",
)
```

In the middle row, `canonicalize()` turns `kkC` inside out: its signed volume goes negative.
Manifold still reports `NO_ERROR` and genus 0, because a surface that passes through itself can
still be a valid manifold as far as `status` is concerned. A negative volume is reason enough to
refuse the result, so the box does.

In the bottom row, `relaxWithCeres(RelaxationPlan.canonicalConditions())` converges in a few
milliseconds and returns a positive volume, `NO_ERROR` and genus 0. Every canonical diagnostic
reports success, but the layer stack does not agree:

* The Riemann sum levels off below 1 and stays there as the layers get thinner. A sum that is short
  only because the layers are coarse keeps climbing, as `kkC` as built does in the same plot.
* `FillRule.POSITIVE` and `FillRule.EVEN_ODD` disagree on most of the layers. On every other form on
  this page they agree on every layer.
* The overlap the fill rules imply accounts exactly for the missing volume.

So the surface passes through itself. `volume` integrates over the boundary and counts the
overlapping region twice; the slicer resolves each layer into a region and counts it once. The
`riemann` and `overlap` lines in the box are a self-intersection test you can run on any solid,
which the canonical diagnostics do not include.

!!! note "A Boolean will not find the crossings for you"

    Intersecting the solid with a box that already contains it returns the same volume it went in
    with. Manifold's Booleans assume their input does not intersect itself and do not resolve
    crossings. Compare fill rules instead.

## What crosses each hop

| hop | the call | what crosses | what stops here |
| --- | --- | --- | --- |
| notation to topology | [`conway`](reference/polytopes.md) | the ranked incidence structure, a realization from the operator's own policy, and the lineage | nothing; this hop only adds |
| realization to realization | [`canonicalize`](reference/polytopes.md) / [`relaxWithCeres`](reference/polytopes.md) | the same vertex indexing, so a per-vertex before/after comparison is meaningful | the scale and the centring, both replaced by the canonical scale, which is why every form here is rescaled to volume 1 before anything is compared |
| topology to triangles | [`triangulate`](reference/polytopes.md) | positions, indices, and a map from each triangle back to its polygon | shared corners: each polygon is cut on its own, so `weld` comes next or the surface has no edges |
| triangles to a solid | [`toSolid`](manifold.md#the-bridges-both-ways) | positions and indices | every attribute, and any guarantee about self-intersection — `status` checks that the indices describe a manifold, not that the surface avoids itself |
| a solid to a contour | [`slice`](manifold.md#back-down-to-2d-slicing) | a `Section`: contours wound so holes are negative, with an area, a count and `decompose()` | the third dimension, and any material strictly below the cutting plane, including a coplanar face the solid sits on top of |

## Where each piece is documented

* [Polytopes & the Conway Algebra](polytope_examples.md) for the operator algebra, the three
  realization verbs and the `kkC` counter-example in its own terms, and
  [Polytopes](reference/polytopes.md) for the declarations.
* [Solids & Booleans (Manifold)](manifold.md) for `Solid`, `Section`, the fill rules and the status
  model.
* [A polytope, cut open, and traced](chain_polytope_render.md) is the sibling chain: the same first
  two hops, then Booleans and a path tracer instead of a slicer.
* [`PolytopeLayerContours.kt`](https://github.com/OpenEndedGroup/Field4/blob/main/src/jvmMain/kotlin/afield/examples/manifold/PolytopeLayerContours.kt)
  runs everything on this page as a single program.
