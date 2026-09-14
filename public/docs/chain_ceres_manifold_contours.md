# A polytope, canonicalised, and sliced into layers

Three parts of Field, one object, and none of the three knows the other two exist.
`afield.stdlib.polytope` holds the Conway operator algebra, the canonicalisation iteration and a
second-order realizer over Ceres. `afield.manifold` does exact geometry on closed solids over
Manifold's C ABI. `Solid.slice` is the single call that crosses from 3D down to 2D, and a stack of
those is a print.

[A polytope, cut open, and traced](chain_polytope_render.md) is this page's sibling: it branches off
the same trunk one stage later and ends in pixels. This one ends in contours, which are harder to
fake, because a contour has an area and the areas have to add up.

The question the page is here to answer is whether canonicalising a polytope changes the layers you
would print. It does, and the way it does is worth the trip — but the more useful finding arrived
sideways. **The layer stack is not only the last stage of this chain. It is a check on the first
one**, and it catches something the realizer's own diagnostics report as a success.

## A cut that lands exactly on something

Before any of that, the case a slicer hits on every part with a flat top. What does `slice` return
when the cutting plane is exactly a face plane, exactly a vertex, exactly the top of the bounding
box?

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

The rule those thirteen cuts agree on:

> `slice(z)` returns the cross-section of the material **at and above** `z`. A face lying in the
> cutting plane counts when the solid is above it and does not count when the solid is below it.

That is half-open in z, and the half is not the one most readers would guess. A cube spanning
`[−1, 1]` hands back its whole bottom face at `z = −1` and **nothing** at `z = +1`, though the face
it declined to return also has area 4; one part in a billion lower and the 4 is back. The step is
the cleanest statement of it: at `z = 0.999999` the cut is the slab's `16.0`, and at `z = 1.0`
exactly it is the block's `4.0` — the slab, entirely below the plane, has stopped contributing.

The two touching cubes are the check rather than another example. The rule predicts, before running,
that exactly one of the two squares answers and that it is the upper one; the capture reads area
`1.0000` centred at `x = 1.0000`, on a geometry that shares nothing with the cube the rule came from.

The zero-area contacts are less surprising and worth stating anyway: a cone cut through its apex,
and a tetrahedron cut on either of its two horizontal edges, all return nothing. A point and a
segment have no area, so there is no region to hand back.

!!! tip "What this means for a layer ladder"

    Put the cuts at layer *midpoints*, not at layer boundaries. Boundaries land on face planes on
    exactly the parts most likely to have them — anything designed flat — and a boundary ladder on
    a part whose height is an exact multiple of the layer height loses its top layer outright.
    Every stack below is `z = zₘᵢₙ + h·(i + ½)/n`.

## Canonicalisation, and the gauge you have to remove first

`canonicalize()` seeks the representative with every edge tangent to one common sphere, the centroid
of those tangency points at the origin, and every face planar
([Polytopes & the Conway Algebra](polytope_examples.md) has the guarantees). Getting there involves
a **gauge**: the mean tangency radius is driven to one. So the canonical form comes out at a scale
the raw per-operator realization never had, and comparing their slice areas as they arrive measures
that normalisation rather than the shape.

The fix is the one a print farm would use anyway. Scale each form to **enclosed volume exactly 1**
and translate it so `z = 0` is its lowest point: the same amount of material, sitting on the bed.

Orientation is the second confound, and it is measured rather than argued away. Vertex indexing
survives canonicalisation, so the angle each vertex's direction from the centroid turns through is
a per-vertex number, and the largest of them says whether one z-ladder cuts both forms comparably.

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

The left panel is a single square, forty-eight times. That is not a drawing error.

**`gC`'s raw realization is a cube, exactly.** Gyro applied to the cube adds 6 face-centre vertices
and 2 per edge to the cube's 8, giving the 38 vertices of the pentagonal icositetrahedron's
combinatorics — and every one of those new vertices lands *on the cube's own surface*. So the solid
that comes out of the operator has surface `6.000000` at volume 1, height `1.000000`, and all
forty-eight layer areas equal to `1.000000`. The gyro is real, and until you canonicalise it is
entirely combinatorial.

Canonicalised, the same topology is the pentagonal icositetrahedron: `1.394283` tall, surface
`5.060666` at the same volume, and layers running from `0.002203` at the bed through `1.257946` and
back down. The worst per-layer area disagreement is 99.8%. `gD` is the less theatrical case and
still disagrees by 44.4% at its worst layer.

Two things the table says that the picture does not:

* **The layer *topology* is untouched.** Both forms of `gC` give 48 contours over 48 layers, 48
  islands, no holes; `gD` likewise. Canonicalisation moved every contour and split none of them.
  A slicer's hardest questions — how many islands, does this layer need support — get the same
  answers from both.
* **The surface ratio is an isoperimetric reading with no free parameter.** At equal volume the
  canonical `gC` has `0.843444` of the raw one's surface, and the canonical `gD` reaches
  `4.926473` against the `4.835976` of a unit-volume sphere: 1.87% above the bound that no solid
  beats. Canonicalisation is, among other things, a rounding.

The vertex turns — at most `9.581°` for `gC` and `5.388°` for `gD` — are why the layer comparison is
allowed. Had they been large, the two stacks would have been cut along different axes and every
difference above would have been a rotation wearing a shape's clothes.

## The layer stack as a check on the realizer

A midpoint Riemann sum over the layer areas has to return the volume the layers came from. That
makes the slicer an *independent* measurement of everything upstream: the area integral and
Manifold's `volume` share no code, one integrating regions in 2D and the other the surface in 3D.

Every form above passes it. `kkC` — kis applied twice to the cube — does not, and it is the input
`dev_docs/POLYTOPE_ALGEBRA.md` has on record as the one neither iteration in `canonicalize()`
solves. `relaxWithCeres` reaches it, in twelve iterations, and this is where the page stops being
about contours.

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

Read the middle row first, because it is the easy failure. `canonicalize()` takes `kkC`'s signed
volume of `+26.3032` to **`−254.0791`**, having moved vertex directions by up to `151.6°` on the way.
Manifold reports `NO_ERROR` and genus 0 throughout — a self-intersecting surface is still a
combinatorial manifold, and `status` is not asked to notice. The sign is enough to refuse it, and
this page refuses it rather than taking a cube root of a negative number and getting a mirrored
solid that looks plausible.

The bottom row is the interesting one. `relaxWithCeres(RelaxationPlan.canonicalConditions())` reaches
a tangency-radius spread of `2.682e-08` in 12 iterations and about 5 ms on
`SPARSE_NORMAL_CHOLESKY`, and comes back with signed volume `+12.4853`, `NO_ERROR`, genus 0.
**Every canonical diagnostic reports a success.** Its layer stack does not agree.

* The Riemann sum settles at **`0.90029`** and stays there — `0.88300`, `0.89592`, `0.89935`,
  `0.90006`, `0.90023`, `0.90028`, `0.90029` across 12 to 768 layers. A sum that is short because
  the layers are too coarse keeps climbing; this one converges to the wrong number, so the layer
  height is not the explanation. (`kkC` as built converges the other way, `0.99150` to `1.00000`
  over the same sweep, which is the control that the sweep works.)
* `FillRule.POSITIVE` and `FillRule.EVEN_ODD` disagree on **130 of 192 layers**, for an implied
  overlapped volume of **`0.049855`**. On every other form on this page — `gC` and `gD`, raw and
  canonical, and `kkC` as built — they disagree on **0 of 192** and the overlap is exactly
  `0.000000`.
* And **`1 − 2(0.049855) = 0.90029`**, which is the plateau, to five decimal places, from a second
  measurement that shares no code with the first.

So the surface passes through itself. `volume` integrates the boundary and counts the overlapped
region twice; the slicer resolves each layer into a region and counts it once; the gap between them
is exactly twice the overlap, which is what the arithmetic above says and what the two independent
numbers confirm.

`dev_docs/POLYTOPE_ALGEBRA.md` lists this as an open item, in these words: *`CanonicalDiagnostics`
reports tangency, planarity, centring and dihedral spread; it does not detect a face passing through
another face. Adding it would let `canonicalize` distinguish "did not converge" from "converged to
something self-intersecting", which is currently the reader's job.* That paragraph is what this
section is: a detector for it, assembled out of the slicer, in the two lines `riemann` and `overlap`
above. It costs a few hundred `slice` calls on a 72-triangle solid and it is the only thing on this
page that noticed.

!!! warning "One Boolean that does not notice"

    Intersecting the solid with a box that already contains it is the identity on a clean body, and
    it looked like a cheaper detector: a self-intersecting input should come back re-meshed and
    smaller. Measured, it returns `1.000000` — the same signed volume it went in with. Manifold's
    Boolean takes its input's manifoldness as given and does not resolve the crossings, so that
    route reports nothing. The fill-rule comparison is the one that works.

## What crosses each hop, and what does not

| hop | the call | what crosses | what stops here |
| --- | --- | --- | --- |
| notation to topology | [`conway`](reference/polytopes.md) | the ranked incidence structure, a realization from the operator's own policy, and the lineage | nothing; this hop only adds |
| realization to realization | [`canonicalize`](reference/polytopes.md) / [`relaxWithCeres`](reference/polytopes.md) | the same vertex indexing, so a per-vertex before/after comparison is meaningful | the scale and the centring, both replaced by the canonical gauge — which is why every form here is re-gauged to volume 1 before anything is compared |
| topology to triangles | [`triangulate`](reference/polytopes.md) | positions, indices, and a map from each triangle back to its polygon | shared corners: each polygon is cut on its own, so `weld` comes next or the surface has no edges |
| triangles to a solid | [`toSolid`](reference/graphics.md) | positions and indices | every attribute, and any guarantee about self-intersection — `status` checks that the indices describe a manifold, not that the surface avoids itself |
| a solid to a contour | [`slice`](reference/graphics.md) | a `Section`: contours wound so holes are negative, with an area, a count and `decompose()` | the third dimension, and any material strictly below the cutting plane, including a coplanar face the solid sits on top of |

## Where each piece is documented

* [Polytopes & the Conway Algebra](polytope_examples.md) for the operator algebra, the three
  realization verbs and the `kkC` counter-example in its own terms, and
  [Polytopes](reference/polytopes.md) for the declarations.
* [Solids & Booleans (Manifold)](manifold.md) for `Solid`, `Section`, the fill rules and the status
  model.
* [A polytope, cut open, and traced](chain_polytope_render.md) is the sibling chain: the same first
  two hops, then Booleans and a path tracer instead of a slicer.
* `src/jvmMain/kotlin/afield/examples/manifold/PolytopeLayerContours.kt` is the whole measurement as
  one runnable smoke — `FIELD_TEST` id `manifold.polytope.contours`, 37 checks, and it skips
  cleanly when Manifold or the Ceres wrapper is not installed.
