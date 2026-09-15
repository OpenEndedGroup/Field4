# Filling a solid: tetrahedral meshing with TetGen

*Every public declaration used below carries its own KDoc, and all of it is browsable on the
[Simulation](reference/simulation.md) reference page, beside the Kratos solver this feeds and the
real-time particle stack it does not — start from
[`Tetgen`](reference/simulation.md#tetgen-kt-tetgen),
[`TetMesh`](reference/simulation.md#tetgen-kt-tetmesh) and
[`TetgenLibrary`](reference/simulation.md#tetgen-kt-tetgenlibrary) in `afield.tetgen`.
`dev_docs/TETGEN.md` carries the mesher comparison that chose
this route, and the numbers on this page were produced in this repository on an Apple M-series
laptop, natively, with no container. The one exception is labelled where it appears: the beam
deflections under [Into Kratos](#into-kratos) come from the solver container.*

[Manifold](manifold.md) gives Field surfaces that are guaranteed closed. A closed surface is a
boundary: it says where the part stops. It says nothing about the inside, and every question worth
asking about a part — how much does it deflect, where does it get hot, where does it crack — is a
question about the inside.

A tetrahedral mesh is the inside, written down. [TetGen](https://wias-berlin.de/software/tetgen/)
takes the closed triangle surface and fills the volume it bounds with tetrahedra whose faces match
that surface exactly. `afield.tetgen` is 685 lines of Kotlin over 263 lines of C++ over TetGen 1.6.0,
through Panama/FFM.

```diffstatic {data-lang=kotlin}
import afield.manifold.*
import afield.tetgen.*

val part = Solid.cube(80.0, 40.0, 15.0) + Solid.cube(15.0, 40.0, 70.0)   // an L, 30 triangles
val mesh = Tetgen.tetrahedralize(part.toTriMesh(), targetEdgeLength = 5.0)

mesh.nodeCount     // 2287
mesh.cellCount     // 10093
mesh.volume()      // 81000.00000000007, against Manifold's exact 81000.0
```

## Why this exists

Field's Kratos bridge solves conduction and flow on a fixed background grid: it samples a signed
distance function onto the grid and cuts the body out of it. That is an *embedded* formulation, and
it is the cheaper thing to do. Structural mechanics cannot work that way, because
**Kratos has no immersed structural element**: `dev_docs/TETGEN.md` records a sweep of the 312 public
names in `StructuralMechanicsApplication` finding not one matching `embedded`, `shifted` or `sbm`.
`structural.bodyFitted` therefore needs a mesh whose boundary really is the part's surface.

A second reason decided the choice, rather than merely forcing it.
Sampling a distance function onto grid nodes destroys a sharp edge *before the mesher ever sees it*.
Refining the grid only buys a smaller rounded radius. Structural stress concentrates at exactly
those edges. [The sharp edge](#the-sharp-edge) below measures both halves of that on this machine.

## The first mesh

A tetrahedral mesh is awkward to look at, which is a real obstacle to trusting one. Render it
naively and you see the boundary — the surface you already had — while everything the mesher
actually did stays hidden. `afield.tetgen` has three answers, and the figure below is all three:
`boundary()` for the skin, `filtered()` with a half-space test for a cutaway, and
`toTriMesh(shrink = …)` for the view where each element stands slightly apart from its neighbours.

```diffstatic_runner {data-lang=kotlin screenshot="assets/tetgen/cutaway.png" wait="10" capture="box" open-outputs="true" padding="20" requires="tetgen,manifold" name="cutaway" embed=true}
:::static
import afield.manifold.*
import afield.tetgen.*

// An L-bracket: two blocks, three bores. Sharp edges from the L, curved surfaces from the bores.
// This part is used for every measurement on this page.
val part = Solid.scope {
    val foot = Solid.cube(80.0, 40.0, 15.0)
    val post = Solid.cube(15.0, 40.0, 70.0)
    val bolts = listOf(12.0, 28.0).map {
        Solid.cylinder(40.0, 5.0, segments = 48).translated(55.0, it, -5.0)
    }
    val eye = Solid.cylinder(60.0, 6.0, segments = 48)
        .rotated(0.0, 90.0, 0.0).translated(-20.0, 20.0, 52.0)
    ((foot + post) - Solid.union(bolts + eye)).copy()
}

val surface = part.toTriMesh()
val mesh = Tetgen.tetrahedralize(surface, targetEdgeLength = 5.0)

// The cutaway: keep the elements whose centroid is behind y = 20, then take that half's skin.
// The newly exposed cut face is in it, which is what makes the picture read as a slice through a
// body rather than as a pile of loose triangles.
val centroid = mesh.centroids()
val half = mesh.filtered { centroid[3 * it + 1] < 20.0 }

// And the same half with every element pulled 22% towards its own centre, coloured by quality.
val quality = half.qualities()
val colors = FloatArray(3 * half.cellCount)
for (t in 0 until half.cellCount) {
    val c = afield.Colormaps.sample("viridis", quality[t])
    colors[3 * t] = c.r.toFloat(); colors[3 * t + 1] = c.g.toFloat(); colors[3 * t + 2] = c.b.toFloat()
}
:::fold measure it, then draw the three views
println("Manifold  ${"%.4f".format(part.volume)} mm³, ${part.triangleCount} triangles, genus ${part.genus}")
// The surface's own digest, printed because the element count below depends on it and on nothing
// else — see "the count moves between sessions" under Sizing.
println("surface   ${surface.vertexCount} vertices, ${surface.triangleCount} triangles, " +
    "digest ${surface.positions.contentHashCode()}/${surface.indices.contentHashCode()}")
println("TetGen    $mesh")
println("          volume ${"%.4f".format(mesh.volume())} mm³")
println("          inverted elements ${mesh.volumes().count { it <= 0.0 }}")
println()
println("cutaway   ${half.cellCount} of ${mesh.cellCount} elements, " +
    "${half.boundary().triangleCount} boundary triangles")
println("quality   min ${"%.4f".format(quality.min())}, median " +
    "${"%.4f".format(quality.sorted()[quality.size / 2])}")

// Pitch well over the horizon, because the cut plane is horizontal in this frame and a low camera
// shows the skin instead of the slice.
val view = MeshView(yawDegrees = 34.0, pitchDegrees = 58.0, distance = 2.2, width = 380, height = 320)
showMeshGrid(
    "the closed surface TetGen is given · ${surface.triangleCount} triangles" to surface,
    "half the elements, skinned · ${half.cellCount} tetrahedra" to half.boundary(),
    "the same half, elements shrunk to 0.72 · colour is radius ratio, dark is worse" to
        half.toTriMesh(shrink = 0.72, cellColors = colors),
    columns = 3,
    cellWidth = 380,
    view = view,
)
part.close()
```

The middle panel is the one that answers "did it work". A conforming mesh's boundary is closed and
encloses the body's volume; if the mesher had cheated anywhere, the cut face would be ragged and the
bores would not come through it round.

## The two quality measures

Two quality measures get used here and they are not the same number. Confusing them is the easiest
mistake in this subsystem, so both have their own call.

<div class="field-data-table" markdown>

| | what it is | what it tells you |
|---|---|---|
| `radiusEdgeRatios()` | circumradius ÷ shortest edge | whether TetGen honoured the bound you asked for |
| `qualities()` | radius ratio, `3 r_in / r_circ` | whether your solver is going to be ill-conditioned |

</div>

The radius-edge ratio is what TetGen's `-q` switch — `Tetgen.tetrahedralize`'s `quality` argument —
actually bounds. Its floor is `sqrt(6)/4 = 0.6124`, reached by the regular tetrahedron.

The radius ratio is `1.0` for the regular tetrahedron and falls to `0` as an element flattens. Below
about `0.05` you have a **sliver**: an element with a respectable volume and a nearly degenerate
shape. Slivers are what wreck a stiffness matrix, and the reason the two measures are separated is
that **the radius-edge criterion cannot see one**. A sliver's four corners lie nearly on a circle,
so its circumradius stays small relative to its edges and it satisfies the bound.

The bracket below makes that concrete. At `targetEdgeLength = 3.0` its worst element *by radius-edge*
scores `1.7361` and has a radius ratio of `0.5851` — a perfectly usable element. Meanwhile 26 of the
31 elements below radius ratio `0.10` sit **inside** the `1.2` bound, and the worst of those has a
radius-edge ratio of `0.9263`, which is nearer the regular tetrahedron's floor of `0.6124` than to
the bound. The criterion is not merely a loose proxy for the thing you care about; on the tail it
ranks the two in opposite orders.

```diffstatic_runner {data-lang=kotlin screenshot="assets/tetgen/quality.png" wait="12" capture="box" open-outputs="true" padding="20" requires="tetgen,manifold" name="quality" embed=true}
:::static
import afield.manifold.*
import afield.tetgen.*

// The same bracket as the figure above, built in the same order — which matters more than it
// looks like it should. See "the same solid, built in a different order" under Sizing.
val part = Solid.scope {
    val foot = Solid.cube(80.0, 40.0, 15.0)
    val post = Solid.cube(15.0, 40.0, 70.0)
    val bolts = listOf(12.0, 28.0).map {
        Solid.cylinder(40.0, 5.0, segments = 48).translated(55.0, it, -5.0)
    }
    val eye = Solid.cylinder(60.0, 6.0, segments = 48)
        .rotated(0.0, 90.0, 0.0).translated(-20.0, 20.0, 52.0)
    ((foot + post) - Solid.union(bolts + eye)).copy()
}
val surface = part.toTriMesh()
val mesh = Tetgen.tetrahedralize(surface, targetEdgeLength = 3.0)
val quality = mesh.qualities()
val radiusEdge = mesh.radiusEdgeRatios()

// Where the worst elements are. Not "how many" — where. On a body built from Booleans the answer
// is almost always "against a feature the surface itself put there".
val worst = quality.indices.sortedBy { quality[it] }.take(400).toHashSet()
val slivers = mesh.filtered { it in worst }
:::fold the distribution, and where the tail lives
val sorted = quality.sorted()
println("${mesh.cellCount} elements at targetEdgeLength = 3.0")
println("radius ratio     min ${"%.4f".format(sorted.first())}  " +
    "1st pct ${"%.4f".format(sorted[sorted.size / 100])}  " +
    "median ${"%.4f".format(sorted[sorted.size / 2])}  max ${"%.4f".format(sorted.last())}")
println("below 0.05       ${sorted.count { it < 0.05 }}   (slivers)")
println("below 0.10       ${sorted.count { it < 0.10 }}")
println()
println("radius-edge      min ${"%.4f".format(radiusEdge.min())}  " +
    "(the regular tetrahedron's floor is ${"%.4f".format(Math.sqrt(6.0) / 4.0)})")
println("                 max ${"%.4f".format(radiusEdge.max())}, " +
    "${radiusEdge.count { it > 1.2 }} above the 1.2 bound that was asked for")
println()
// The point of printing these together: the two measures do not rank the same elements. The worst
// element by radius-edge is a perfectly usable one, and most of the bad ones are inside the bound.
println("worst by radius-edge: ${"%.4f".format(radiusEdge.max())}, whose radius ratio is " +
    "${"%.4f".format(quality[radiusEdge.indices.maxBy { radiusEdge[it] }])} — a fine element")
val inBound = quality.indices.filter { radiusEdge[it] <= 1.2 }
println("of the ${inBound.size} elements inside the bound, ${inBound.count { quality[it] < 0.10 }} " +
    "are below radius ratio 0.10; the worst is ${"%.5f".format(inBound.minOf { quality[it] })}")

showChart(
    Chart(title = "radius ratio over ${mesh.cellCount} elements",
          xLabel = "3 r_in / r_circ", yLabel = "elements")
        .histogram("elements", quality.toList(), bins = 40),
)
showMesh(
    slivers.boundary(),
    title = "the 400 worst elements, in place",
    caption = "radius ratio ${"%.4f".format(sorted.first())} to ${"%.4f".format(sorted[399])} · " +
        "densest around the three bores, and scattered everywhere else",
    view = MeshView(yawDegrees = 34.0, pitchDegrees = 22.0, width = 760, height = 420),
)
part.close()
```

On that bracket at `targetEdgeLength = 3.0`: 66,489 elements, median radius ratio `0.8483`, worst
`0.0362`, and **4** elements below `0.05`. A long thin tail under a healthy median is the normal
shape for Delaunay refinement, so judge a mesh by its tail rather than by its median.

### The `quality` argument buys less than you would think

TetGen's own default bound is `1.414`; `Tetgen.tetrahedralize` defaults to `1.2`. Swept on the same
bracket at `targetEdgeLength = 5.0`, timing the median of three runs:

<div class="field-data-table" markdown>

| `quality` | elements | time | radius ratio, 1st percentile | below 0.10 | below 0.05 |
|---|---|---|---|---|---|
| 1.05 | 56,130 | 640 ms | 0.2980 | 38 | 6 |
| 1.1 | 46,206 | 517 ms | 0.3072 | 39 | 6 |
| **1.2** | **36,475** | **430 ms** | **0.3318** | **18** | **3** |
| 1.414 | 28,071 | 357 ms | 0.2838 | 24 | 1 |
| 2.0 | 20,798 | 268 ms | 0.2236 | 30 | 5 |

</div>

Tightening the bound does not remove slivers, which is the last column: `1.414` leaves one element
below `0.05` and `1.2` leaves three. That is the separation above, made concrete — `-q` bounds a
quantity a sliver already satisfies.

What tightening does buy is the *bulk* of the tail. The first percentile improves from `0.2838` to
`0.3318` and the count below `0.10` drops from 24 to 18, for 30% more elements and 20% more time.
`1.2` is a reasonable default on that trade and `1.05` is not: it costs twice the elements of
`1.414` and makes the tail worse again.

??? aside "This disagrees with `dev_docs/TETGEN.md`, on a different body"
    That page records that `1.2` removed the last poor element "for no measurable cost", measured on
    a slab welded to a ball. On this bracket it removes nothing and costs 20%. Both runs are real;
    the difference is the geometry, and the useful conclusion is that the default is a default and
    not a finding. Sweep it on your own part — the sweep above is fifteen lines and five seconds.

## Sizing

`targetEdgeLength` is in the surface's own units and is translated to the only size control TetGen
takes, a maximum element volume: `h³ / (6 sqrt 2)`, the volume of a regular tetrahedron of that edge.

<div class="field-data-table" markdown>

| `targetEdgeLength` | nodes | elements | time |
|---|---|---|---|
| `null` | 7,034 | 30,858 | — |
| 8.0 mm | 7,174 | 31,462 | 379 ms |
| 5.0 mm | 8,279 | 36,475 | 510 ms |
| 3.0 mm | 14,011 | 66,489 | 731 ms |
| 2.0 mm | 31,545 | 163,082 | 1,949 ms |

</div>

Asking for 8 mm elements on a part 80 mm across gets you
almost exactly what asking for nothing gets you, because **the input surface sets a floor that
`targetEdgeLength` cannot lift**. TetGen conforms to the triangles it is given, and this bracket's
618 triangles are already about 10 mm across; the quality refinement then subdivides from there. A
coarse request is silently ignored, and there is no warning, so check `cellCount` rather than
assuming the number you passed did something.

Downwards it behaves: 2 mm is 4.5× the elements of 5 mm and 3.8× the time. Cost is roughly linear in
element count at 11–14 µs per element, which is the number to plan with — a body-fitted structural
solve of 36,000 elements spends about half a second meshing and several seconds solving, so
re-meshing on every geometry edit is the right design and no part of `afield.tetgen` tries to update
a mesh in place.

These are costs to plan with rather than a benchmark: the same configuration comes out at 430 ms in
the `quality` table above and 510 ms here.

??? aside "The same solid, built in a different order, meshes differently"
    TetGen is deterministic. The same surface, meshed five times in one process, three times in
    separate JVMs, and once inside a live Field server, gives 66,489 elements every time with the
    same worst element to six decimals.

    But *the order you wrote the Booleans in* changes the answer. These two build the same bracket:

    ```
    val holes = Solid.union(bolts + eye)                     // holes first
    (Solid.cube(80.0, 40.0, 15.0) + Solid.cube(15.0, 40.0, 70.0)) - holes

    val foot = Solid.cube(80.0, 40.0, 15.0)                  // blocks first
    val post = Solid.cube(15.0, 40.0, 70.0)
    (foot + post) - Solid.union(bolts + eye)
    ```

    Both give volume `76958.9090888672`, and their `TriMesh` **positions arrays are bit-identical**
    — same digest, same order. The **index** arrays are not: the first triangle is `[0, 1, 53]` one
    way and `[110, 109, 126]` the other. The wrapper turns each triangle into one TetGen facet in
    the order given, and Delaunay refinement depends on insertion order, so the two produce
    **66,489 and 66,108** elements with worst radius ratios `0.03618` and `0.03724`.

    Neither is more correct. Both mesh to the same volume to six decimal places. But it means an
    element count is not a property of your geometry, it is a property of your geometry *and the
    expression that built it*. Assert on volume, on the sliver count, or on a quality percentile —
    never on `cellCount`. Every figure on this page builds the bracket the same way for exactly
    this reason, and the cutaway prints the surface digest so a mismatch is diagnosable rather than
    mysterious.

## The sharp edge

TetGen conforms to the surface it is given, so a corner in the input stays a corner in the output.
That is the whole reason to prefer it to a level set, and it shows up two ways: against an exact
volume, and against the formulation TetGen replaced.

A `2 × 3 × 4` box has an exact volume of `24`. Through Manifold's 12-triangle surface and TetGen at
`targetEdgeLength = 0.5`, Field gets `23.999999999999936` — a relative error of `2.7e-15`, three
ulps. Not close: the same number, to within double-precision arithmetic on 3,059 tetrahedra. A
mesher that rounded the corners could not land there.

The comparison that matters is against the route this replaced. The figure below marches the *same*
box out of its own distance function, which is what an embedded formulation does before it ever sees
an edge, and measures how far the result sits from the true surface.

```diffstatic_runner {data-lang=kotlin screenshot="assets/tetgen/sharp_edge.png" wait="14" capture="box" open-outputs="true" padding="20" requires="tetgen,manifold" name="sharp_edge" embed=true}
:::static
import afield.manifold.*
import afield.tetgen.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

// The exact distance to the surface of a 2 x 3 x 4 box centred on the origin, and to a sphere.
// One has edges, the other does not; that is the whole experiment.
fun boxDistance(x: Double, y: Double, z: Double): Double {
    val dx = abs(x) - 1.0; val dy = abs(y) - 1.5; val dz = abs(z) - 2.0
    val out = sqrt(max(dx, 0.0) * max(dx, 0.0) + max(dy, 0.0) * max(dy, 0.0) + max(dz, 0.0) * max(dz, 0.0))
    return out + min(max(dx, max(dy, dz)), 0.0)
}
fun sphereDistance(x: Double, y: Double, z: Double) = sqrt(x * x + y * y + z * z) - 1.5

val bounds = boundsOf(vec(-1.6, -2.1, -2.6), vec(1.6, 2.1, 2.6))

// How far the worst point of a surface sits from the body it was meant to be. Takes the flat
// coordinate array so the same measurement works on a marched TriMesh and on a TetMesh's nodes.
fun worstError(xyz: DoubleArray, distance: (Double, Double, Double) -> Double): Double {
    var worst = 0.0
    for (v in 0 until xyz.size / 3) {
        worst = max(worst, abs(distance(xyz[3 * v], xyz[3 * v + 1], xyz[3 * v + 2])))
    }
    return worst
}
fun coordinates(m: TriMesh) = DoubleArray(m.positions.size) { m.positions[it].toDouble() }
:::fold march both bodies at four resolutions, then plot the corner and the convergence
val sizes = listOf(0.4, 0.2, 0.1, 0.05)
val boxError = mutableListOf<Double>()
val sphereError = mutableListOf<Double>()
val corners = mutableListOf<Pair<String, List<Vec2>>>()

println("h      box, worst error        sphere, worst error")
for (h in sizes) {
    val b = Solid.levelSet(bounds, h, parallel = true) { x, y, z -> -boxDistance(x, y, z) }
    val s = Solid.levelSet(bounds, h, parallel = true) { x, y, z -> -sphereDistance(x, y, z) }
    boxError += worstError(coordinates(b.toTriMesh()), ::boxDistance)
    sphereError += worstError(coordinates(s.toTriMesh()), ::sphereDistance)
    println("${"%.2f".format(h)}   ${"%.6f".format(boxError.last())} = " +
        "${"%.4f".format(boxError.last() / h)} h    " +
        "${"%.6f".format(sphereError.last())} = ${"%.4f".format(sphereError.last() / h)} h")
    // A real cross section through the marched body, at z = 0. This is the only honest way to see
    // the rounding: it is 1.5% of the body at h = 0.4, invisible in a render of the whole box.
    if (h >= 0.1) corners += "marched, h = $h" to b.slice(0.0).toPolygons().first()
    b.close(); s.close()
}

// The same box through TetGen, which is handed Manifold's exact triangulation and constrains its
// output to it.
val exactBox = Solid.cube(2.0, 3.0, 4.0, center = true)
val tet = Tetgen.tetrahedralize(exactBox.toTriMesh(), targetEdgeLength = 0.25)
println()
println("TetGen at h = 0.25: ${tet.cellCount} elements, volume ${tet.volume()}")
// Only outward error counts here: an interior node is *supposed* to be inside the box.
println("  worst node outside the true box: " +
    worstError(tet.positions) { x, y, z -> max(boxDistance(x, y, z), 0.0) })

// One corner of the true cross section, magnified. The exact outline is what TetGen is given and
// what its boundary reproduces; each level set is a different rounded approximation of it.
//
// Clipping to the window before charting was load-bearing here, and is now belt and braces. A
// Chart used not to clip to its own axes: handed a cross section 2 x 3 across for a 0.24 x 0.24
// window, it put the outline 5137 px outside a 780 x 440 frame, the output panel fitted THOSE
// bounds, and the axes ended up an illegible stamp in one corner at 9% of their declared size.
// `Chart.clip` does this in the library now, for every mark kind. This is left standing only
// because taking it out would mean recapturing the two unrelated figures on this page as well; it
// cuts against the same rectangle and draws the same picture.
val xLo = 0.82; val xHi = 1.06; val yLo = 1.32; val yHi = 1.56
fun clippedToWindow(loop: List<Vec2>): Pair<List<Double>, List<Double>> {
    val xs = mutableListOf<Double>()
    val ys = mutableListOf<Double>()
    for (i in loop.indices) {
        val a = loop[i]
        val b = loop[(i + 1) % loop.size]
        val ax = a.x.toDouble(); val ay = a.y.toDouble()
        val dx = b.x.toDouble() - ax; val dy = b.y.toDouble() - ay
        // Liang–Barsky: the parameter interval of this segment that lies inside the window.
        val edge = doubleArrayOf(-dx, dx, -dy, dy)
        val slack = doubleArrayOf(ax - xLo, xHi - ax, ay - yLo, yHi - ay)
        var t0 = 0.0
        var t1 = 1.0
        var outside = false
        for (k in 0..3) {
            if (edge[k] == 0.0) { if (slack[k] < 0.0) outside = true }
            else {
                val t = slack[k] / edge[k]
                if (edge[k] < 0.0 && t > t0) t0 = t
                if (edge[k] > 0.0 && t < t1) t1 = t
            }
        }
        if (outside || t0 > t1) continue
        if (xs.isNotEmpty()) { xs += Double.NaN; ys += Double.NaN }
        xs += ax + t0 * dx; ys += ay + t0 * dy
        xs += ax + t1 * dx; ys += ay + t1 * dy
    }
    return xs to ys
}

val exactCorner = clippedToWindow(exactBox.slice(0.0).toPolygons().first())
// This note had to be short by hand. The one it started with ran to 142 characters, 1197 px against
// 711 px of room, and it drew the whole figure at 63% of its declared size. A note wraps to the
// figure width now, so its length is a matter of taste again rather than of layout.
val corner = Chart(title = "the corner at (1.0, 1.5), in a 0.24 x 0.24 window",
                   xLabel = "x", yLabel = "y",
                   note = "halving h halves the bite the level set takes out of the corner")
corner.width = 780.0; corner.height = 440.0
corner.xDomain = xLo to xHi
corner.yDomain = yLo to yHi
corner.line("exact, and TetGen's", exactCorner.first, exactCorner.second,
    color = vec(1.0, 0.72, 0.35, 1.0), thickness = 3.0)
// Coarsest red through finest blue, explicitly. The default palette gives three of these four
// series a warm colour and the figure becomes four orange lines.
val byResolution = listOf(
    vec(0.95, 0.45, 0.45, 1.0), vec(0.55, 0.95, 0.55, 1.0), vec(0.45, 0.85, 1.0, 1.0))
corners.forEachIndexed { i, (label, points) ->
    val clipped = clippedToWindow(points)
    corner.line(label, clipped.first, clipped.second, color = byResolution[i], thickness = 2.0)
}

// And the convergence, which is the actual finding: slope 1 on the box, slope 2 on the sphere.
val rate = Chart(title = "worst boundary error against cell size",
                 xLabel = "cell size h", yLabel = "worst distance from the true surface",
                 note = "log-log. The box halves when h halves; the sphere quarters.")
rate.width = 780.0; rate.height = 440.0
rate.xKind = ScaleKind.LOG; rate.yKind = ScaleKind.LOG
val blue = vec(0.45, 0.85, 1.0, 1.0)
val green = vec(0.55, 0.95, 0.55, 1.0)
rate.line("box — first order", sizes, boxError, color = blue, thickness = 2.4)
    .points("box — first order", sizes, boxError, color = blue, size = 4.5)
rate.line("sphere — second order", sizes, sphereError, color = green, thickness = 2.4)
    .points("sphere — second order", sizes, sphereError, color = green, size = 4.5)

// Two channels, not two displays on one: a second display on the same channel replaces the first.
display("output", corner)
display("main", rate)
exactBox.close()
```

Two panels. The upper one is the corner itself, in a window `0.24` across on a body `2` across, with
the marched outlines closing on it as `h` falls; the lower one is the same error over the whole body,
which is what gives the rate.

Halve `h` and the sphere's error falls by four — second order, converging on the true surface. The
box's falls by two, first order, and stays a fixed fraction of `h` — about `0.07 h` — at every
resolution. The error *is* a rounded edge whose radius is proportional to the cell size. Refining a
level set does not sharpen an edge; it makes a smaller rounded one.

TetGen's worst node lies `0.0` outside the true box, exactly, because it never resamples the
surface: Manifold's triangulation goes in as a piecewise-linear complex whose facet edges are
constraints, and TetGen's output honours them. So this is the one choice on the page that is not a
trade-off — if your part has a sharp edge and you care what happens there, the level-set route cannot
get you closer by refining. `dev_docs/TETGEN.md` has the per-resolution errors, and the same
comparison run against MMG rather than marching cubes.

### Where precision is lost: `TriMesh` stores float32

The bracket's meshed volume differs from Manifold's by `1.6e-9` relative, where the cube's differs by
`2.7e-15`. That gap is not the mesher. `TriMesh` stores positions as `Float`, so `Solid.toTriMesh()`
narrows every coordinate on the way in:

<div class="field-data-table" markdown>

| | volume of the bracket |
|---|---|
| Manifold, in double | `76958.9090888672` |
| its `TriMesh`, after narrowing to float32 | `76958.90896392896` |
| TetGen's mesh of that `TriMesh` | `76958.90896392865` |

</div>

TetGen reproduces what it was handed to `4e-15`. The cube lands at `2.7e-15` because `0`, `2`, `3`
and `4` are exactly representable in float32 and a 48-segment cylinder's vertices are not. If you
need better than 1 part in `10⁹` on a curved body, the bridge is where to spend the effort, not the
mesher.

## Input surfaces

Eight malformed surfaces, all fed to `Tetgen.tetrahedralize` on this machine:

<div class="field-data-table" markdown>

| surface | outcome |
|---|---|
| a closed cube (control) | 213 elements, volume `1.000000` |
| **five faces of a cube** | `TetgenException: TetGen failed (status 2): TetGen returned an empty mesh` |
| one face of a cube | `TetgenException: ... (status 1): a closed surface needs at least 4 vertices and 4 triangles` |
| **two interpenetrating cubes** | `TetgenException: ... (status 2): TetGen aborted with code 3; the surface is probably not closed, self-intersects, or has duplicate vertices` |
| **a zero-volume "solid"** | `TetgenException: ... (status 2): TetGen aborted with code 10; ...` |
| an inside-out cube | *accepted*: 210 elements, volume `1.000000` |
| a cube with one face duplicated | *accepted*: 228 elements, volume `1.000000` |
| a cube with two coincident corners | *accepted*: 145 elements, volume `0.666667` |

</div>

The three acceptances are all correct answers.

**TetGen does not care which way your surface is wound.** Its input is a piecewise-linear complex,
which is unoriented — a set of facets, not an oriented boundary. So an inside-out cube meshes the
cube. [Manifold behaves differently on the same input](manifold.md): an inwardly-wound closed
surface is a legal manifold there with a *negative* volume, and the next Boolean computes the
complement of what you meant. Two libraries, one mesh, opposite answers, neither of them wrong. If
you are pushing a repaired scan through both, `TriMesh.diagnose()` reporting `INVERTED_ORIENTATION`
matters to the Manifold half and not to this one.

The coincident-corner cube is not a failure either: collapsing one corner of a unit cube onto its
neighbour really does leave a body of volume `2/3`, and TetGen meshed exactly that. That is worth
knowing precisely because it is the shape of the mistake you will actually make — a mesh off disk
with an unwelded or over-welded seam produces a plausible solid of the wrong size, and nothing in
this path will tell you. Compare `mesh.volume()` against `Solid.volume` before you trust a result.

??? aside "The fatal path"
    Two of the eight — self-intersection and degeneracy — reach TetGen's own fatal handler. Built
    normally TetGen calls `exit()` there, which inside a live Field session ends the session. The
    wrapper is compiled with `TETLIBRARY`, which makes TetGen throw an `int` instead, and
    `field_tetgen.cpp` catches it: letting a C++ exception unwind through FFM's downcall frames
    aborts the VM. `TetgenTest.anOpenSurfaceIsRefusedWithoutTakingTheProcessDown` guards that the
    process survives — though note that on this build the five-faced cube it uses comes back through
    the *empty mesh* path rather than the throw, so the two inputs that genuinely exercise the catch
    are the self-intersecting pair and the degenerate one.

    TetGen also writes to the process's stdout on that path — `Exception: All vertices are (nearly)
    collinear (Tol = 1e-08).` — despite the `Q` (quiet) switch. The message reaching Kotlin is the
    wrapper's; the line in your terminal is TetGen's.

!!! warning "And it writes files into your working directory"
    Refusing a self-intersecting surface leaves `tetgen-tmpfile_skipped.node` and
    `tetgen-tmpfile_skipped.face` in the **process's current working directory**, holding the facets
    it gave up on. `Q` does not suppress it and `TETLIBRARY` does not redirect it. In a live Field
    session that directory is wherever the server was launched from.

    They are TetGen's own debugging aid and they are genuinely useful — the pair from a failed
    Boolean is a Medit-readable dump of exactly which facets tangled — but nothing asks before
    writing them. `TetgenWorkingDirectoryLitterTest` pins the behaviour and cleans up after itself.
    The obvious fix, `chdir` around the call, is worse than the problem: the working directory is
    process-global, and moving it under a mesh running on a background thread moves it for every
    relative path in the session.

### `preserveSurface` is a trap on a Boolean result

`preserveSurface = true` is TetGen's `-Y`: forbid Steiner points on the boundary, so the output
surface is the input surface, triangle for triangle. It is off by default and should stay off unless
you have measured your own case. On the bracket at `targetEdgeLength = 3.0`:

<div class="field-data-table" markdown>

| | nodes | elements | worst radius ratio | slivers |
|---|---|---|---|---|
| `preserveSurface = false` | 14,011 | 66,489 | 0.0362 | 4 |
| `preserveSurface = true` | 306 | 509 | **0.00001** | **285** |

</div>

Not "somewhat worse": 56% of the elements are slivers and the worst is degenerate to five decimal
places. `-Y` refuses to add the boundary points that refinement needs, and on a surface a Boolean
produced — with the long thin triangles a cylinder-through-a-block leaves — there is nowhere else for
the refinement to go. The size request goes with it: 509 elements when you asked for something 130
times finer.

## Into Kratos

`structural.bodyFitted` is what this feeds. It runs a small-displacement element on the supplied mesh
with a clamp region, gravity and a total force spread over a load region; `createBodyMesh` is the
protocol verb and `KratosSession.openMeshAsync` the Kotlin one. `TetMesh`'s flat `positions`/`cells`
layout matches the wire format, so nothing is reshaped in between. The mesh
picks the element: four nodes per cell gets `SmallDisplacementElement3D4N`, ten gets
`SmallDisplacementElement3D10N`. There is no parameter, so there is nothing for the two ends to
disagree about.

That matters, because the 4-node tetrahedron is a **constant-strain** element. Its four corners
carry a linear displacement field, so strain is one value for the whole element and cannot vary
inside it. Bending is precisely the case where strain has to vary across a section: tension on one
face, compression on the other. An element that cannot represent that resists it instead. It
**locks**, and is far too stiff, converging on the right answer from below and slowly.

Refining is not a way out of it. On a cantilever under self-weight, the 4-node element reaches 62% of
the Euler–Bernoulli tip deflection at 1,080 elements, still 38% short; the 10-node element is within
5% at *thirty*. So if you are bending anything, pass `order = 2` and mesh coarsely, rather than
passing `order = 1` and refining. `optional/kratos/VERIFICATION.md` has the per-mesh numbers, the
axial-extension control, and why landing just under 100% is right.

### Asking for 10-node elements

`order = 2` tetrahedralizes exactly as before and then promotes the result.

```diffstatic {data-lang=kotlin}
val linear = Tetgen.tetrahedralize(surface, targetEdgeLength = 5.0)
val quadratic = Tetgen.tetrahedralize(surface, targetEdgeLength = 5.0, order = 2)
// equivalently, from a mesh already in hand: linear.quadratic()

linear.nodeCount        // 8279
quadratic.nodeCount     // 57495 — 6.94x, one new node per distinct edge
quadratic.cellCount     // 36475, the same tetrahedra
quadratic.nodesPerCell  // 10
quadratic.volume()      // 76958.90896392865 — linear.volume() to the last bit
quadratic.corners()     // the 4-node view back, sharing the same node array
```

The shape does not change. The six new nodes sit at the exact midpoints of straight edges, so the
region, the boundary, the volume and both quality measures come out the same numbers; what changes
is the interpolation inside an element. `qualities()`, `boundary()`, `filtered()` and `toTriMesh()`
read a cell's first four indices and are unaffected.

The promotion is cheap and the elements are not. On the bracket at `targetEdgeLength = 5.0` it costs
71 ms against the mesh's 466 ms, and at 3.0, 73 ms against 782 ms. It is one pass over the edges,
not a re-mesh. Seven times the nodes is seven times the unknowns, though, and that is downstream: on
the 864-element beam Kratos takes 1.05 s at order 2 against 0.20 s at order 1, for 99.8% against
69.5%.

??? aside "TetGen's `-o2` has a use-after-free"
    TetGen 1.6.0's `highorder()` `delete[]`s the `highordertable` holding every element's extra-node
    pointers before it returns, and `outelements()` then dereferences them. Driven from the command
    line it usually survives; driven through `tetgenio` it does not, because `outelements()`
    allocates the output node array first and the allocator hands back the block just freed — so
    TetGen reads its own coordinates as pointers. `tools/tetgen_o2_use_after_free.py` pins that
    outside the JVM: the same cube built twice from the same TetGen source, SIGSEGV as shipped and
    229 nodes with that one `delete[]` commented out.

    `TetMesh.quadratic()` does the promotion on this side instead, which also means the midside
    nodes are emitted in Kratos's `Tetrahedra3D10` order by construction rather than recovered from
    TetGen's internal edge tables. That ordering is checked geometrically at both ends, because a
    mis-ordered quadratic element does not fail — it assembles, it solves, and it returns a
    plausible displacement field that is wrong.

## Installing it

TetGen is **optional** and is not vendored. A clone of Field builds, tests and runs without it, and
`TetgenTest` skips cleanly when it is absent.

```diffstatic {data-lang=bash}
python3 tools/build_tetgen_native.py      # or: ./gradlew buildTetgenNative
```

That downloads a pinned, checksummed TetGen 1.6.0 tarball from the Weierstrass Institute — the first
run needs the network, after which it is cached under `build/field-tetgen/downloads` — and compiles
it straight into the wrapper. There is nothing to `find_package`: TetGen is two source files with no
dependencies, which is why there is a C++ wrapper here at all and why it is only 320 lines.

```diffstatic {data-lang=kotlin}
import afield.tetgen.*

TetgenLibrary.isAvailable       // cheap enough to branch on in a box
TetgenLibrary.describe()        // "TetGen wrapper at /…/libfield_tetgen_jvm.dylib (TetGen 1.6.0)"
```

`-Dafield.tetgen.library=<path>` or `AFIELD_TETGEN_LIBRARY` point it at a build of your own.
Unlike Manifold there is no package to suggest instead: TetGen has no Homebrew formula and no
distribution package worth linking.

**Licensing.** TetGen is AGPL-3 or a commercial licence, and that is why most projects cannot use
it. Field is AGPL-3, so the first applies and nothing further is needed. Kratos itself gates TetGen
behind `USE_TETGEN_NONFREE_TPL` because *Kratos* is BSD, and its published wheels contain MMG and no
TetGen.

Two things in the build are required and neither is negotiable:

- **`predicates.cxx` is compiled at `-O0`.** Upstream's own makefile says so. Shewchuk's exact
  predicates depend on their arithmetic not being reassociated or kept at extended precision in a
  register; built optimised they return wrong signs on nearly-degenerate input. That does not crash
  — it silently produces an invalid tetrahedralization.
- **`TETLIBRARY` is defined**, which is what turns `exit()` into a throwable. See the warning above.

The C ABI passes results through out-parameters rather than a struct, so the Kotlin side never has
to reproduce this compiler's field offsets and tail padding. Getting that wrong reads plausible
garbage rather than failing, which is the same class of mistake as everything else on this page.

## See also

- [Solids and Booleans](manifold.md) — where the closed surface comes from, and the other half of
  the winding story.
- [Meshes and 3D geometry](mesh_examples.md) — `TriMesh`, and the marching cubes the figure above
  compares against.
- [Geometry from disk](geometry_from_disk.md) — `diagnose()` and `repairToSolid()`, for the meshes
  that arrive not closed.
