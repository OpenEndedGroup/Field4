# Filling a solid: tetrahedral meshing with TetGen

*Every public declaration used below carries its own KDoc — start from `Tetgen`, `TetMesh` and
`TetgenLibrary` in `afield.tetgen`. `dev_docs/TETGEN.md` carries the mesher comparison that chose
this route, and the numbers on this page were all produced in this repository on an Apple M-series
laptop, natively, with no container.*

[Manifold](manifold.md) gives Field surfaces that are guaranteed closed. A closed surface is a
boundary: it says where the part stops. It says nothing about the inside, and every question worth
asking about a part — how much does it deflect, where does it get hot, where does it crack — is a
question about the inside.

A tetrahedral mesh is the inside, written down. [TetGen](https://wias-berlin.de/software/tetgen/)
takes the closed triangle surface and fills the volume it bounds with tetrahedra whose faces match
that surface exactly. `afield.tetgen` is 564 lines of Kotlin over 263 lines of C++ over TetGen 1.6.0,
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

There is a second reason, and it is the one that decided the choice rather than merely forcing it.
Sampling a distance function onto grid nodes destroys a sharp edge *before the mesher ever sees it*.
Refining the grid only buys a smaller rounded radius. Structural stress concentrates at exactly
those edges. [The sharp edge](#the-sharp-edge) below measures both halves of that on this machine.

## The first mesh, and how to look at it

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

## Quality, and which number means what

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

val part = Solid.scope {
    val bolts = listOf(12.0, 28.0).map {
        Solid.cylinder(40.0, 5.0, segments = 48).translated(55.0, it, -5.0)
    }
    val eye = Solid.cylinder(60.0, 6.0, segments = 48)
        .rotated(0.0, 90.0, 0.0).translated(-20.0, 20.0, 52.0)
    ((Solid.cube(80.0, 40.0, 15.0) + Solid.cube(15.0, 40.0, 70.0)) -
        Solid.union(bolts + eye)).copy()
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

Measured on that bracket at `targetEdgeLength = 3.0`: 66,489 elements, median radius ratio `0.8483`,
mean `0.8191`, minimum `0.0362`, **4** below `0.05` and 31 below `0.10`. The distribution is strongly
left-skewed with a long thin tail, which is the normal shape for Delaunay refinement and is why the
median and the mean differ by three points.

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

Read the last column first, because it is the one that does not behave. Tightening the bound does
not remove slivers: `1.414` leaves one element below `0.05` and `1.2` leaves three. That is the
separation above, made concrete — `-q` bounds a quantity a sliver already satisfies.

What tightening does buy is the *bulk* of the tail. The first percentile improves from `0.2838` to
`0.3318` and the count below `0.10` drops from 24 to 18, for 30% more elements and 20% more time.
`1.2` is a reasonable default on that trade and `1.05` is not: it costs twice the elements of
`1.414` and makes the tail worse again.

!!! note "This disagrees with `dev_docs/TETGEN.md`, on a different body"
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

The first two rows are the useful ones. Asking for 8 mm elements on a part 80 mm across gets you
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

(The `quality` table above and this one are separate runs of the same code, and the same
configuration comes out at 430 ms there and 510 ms here. That spread is what a wall-clock median of a
handful of runs on a laptop is worth; treat these as costs to plan with, not as a benchmark.)

!!! note "The element count moves between sessions, and the mesher is not the reason"
    TetGen is deterministic: the same surface meshed five times in one process, and in three
    separate JVMs, gives the same 66,489 elements with the same worst element to six decimals. But
    the figure at the top of this page — same code, run inside the docs capture rather than in a
    test — reported 66,108. A third of a percent, on a surface built by the same three Booleans.

    The surface is what to suspect, not the mesh: the count depends on nothing else, so the
    Boolean's vertex ordering must have differed between the two processes. The cutaway figure now
    prints the surface's own digest for exactly this reason. Do not assert on an element count
    across processes; assert on volume, on the sliver count, or on a quality percentile.

## The sharp edge

This is the claim the whole route rests on, so here it is measured rather than asserted, twice.

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
    if (h >= 0.1) corners += "level set, h = $h" to b.slice(0.0).toPolygons().first()
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
val exactCorner = exactBox.slice(0.0).toPolygons().first()
val corner = Chart(title = "one corner of the z = 0 cross section",
                   xLabel = "x", yLabel = "y",
                   note = "a 0.24 x 0.24 window on a body 2 x 3 across. The true corner is at " +
                       "(1.0, 1.5); every level set cuts it off, and halving h halves what is left.")
corner.width = 470.0; corner.height = 430.0
corner.xDomain = 0.82 to 1.06
corner.yDomain = 1.32 to 1.56
corner.line("the exact surface, and TetGen's",
    exactCorner.map { it.x.toDouble() }, exactCorner.map { it.y.toDouble() }, thickness = 2.6)
corners.forEach { (label, points) ->
    corner.line(label, points.map { it.x.toDouble() }, points.map { it.y.toDouble() })
}

// And the convergence, which is the actual finding: slope 1 on the box, slope 2 on the sphere.
val rate = Chart(title = "worst boundary error against cell size",
                 xLabel = "cell size h", yLabel = "worst distance from the true surface",
                 note = "log-log. The box halves when h halves; the sphere quarters.")
rate.width = 470.0; rate.height = 430.0
rate.xKind = ScaleKind.LOG; rate.yKind = ScaleKind.LOG
rate.line("box — first order", sizes, boxError).points("box — first order", sizes, boxError)
rate.line("sphere — second order", sizes, sphereError)
    .points("sphere — second order", sizes, sphereError)

display("main", corner)
display("main", rate)
exactBox.close()
```

The numbers, from that run:

<div class="field-data-table" markdown>

| cell size `h` | box, worst error | sphere, worst error |
|---|---|---|
| 0.4 | 0.030303 = 0.0758 `h` | 0.010447 = 0.0261 `h` |
| 0.2 | 0.015000 = 0.0750 `h` | 0.002789 = 0.0139 `h` |
| 0.1 | 0.006699 = 0.0670 `h` | 0.000698 = 0.0070 `h` |
| 0.05 | 0.003160 = 0.0632 `h` | 0.000166 = 0.0033 `h` |

</div>

Read down the two columns. On the sphere the error falls by four each time `h` halves — second
order, converging. On the box it falls by two — first order, and the ratio to `h` barely moves. The
error *is* a rounded edge whose radius is proportional to the cell size, at every resolution,
forever. Refining a level set does not sharpen an edge; it makes a smaller rounded one.

TetGen's worst node lies `0.0` outside the true box, exactly, because it never resamples the
surface: Manifold's triangulation goes in as a piecewise-linear complex whose facet edges are
constraints, and TetGen's output honours them.

This is not a criticism of marching cubes, and the constant here (`0.07 h`) is far better than the
`0.35–0.40 h` `dev_docs/TETGEN.md` measured for MMG's tetrahedral background grid on the same
question. The *order* is the finding, and it is the same one at either constant.

### The one place precision is lost, and it is not TetGen

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

## What it refuses, and what it quietly accepts

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

The three acceptances are all correct answers, and the third is the interesting one.

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

!!! warning "The fatal path, and why it does not take the JVM with it"
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
the refinement to go. The size request is ignored along with everything else, which is the tell:
509 elements when you asked for something 130 times finer.

## Into Kratos

`structural.bodyFitted` is what this feeds. It runs `SmallDisplacementElement3D4N` on the supplied
mesh with a clamp region, gravity and a total force spread over a load region; `createBodyMesh` is
the protocol verb and `KratosSession.openMeshAsync` the Kotlin one. `TetMesh`'s flat
`positions`/`cells` layout is deliberately the layout that goes on the wire, so nothing is reshaped
in between.

One limit, recorded rather than worked around, and it belongs to the element rather than to the
mesh. The 4-node constant-strain tetrahedron **locks** in bending. `optional/kratos/VERIFICATION.md`
records the solver runs behind that — against closed-form beam theory, 35.6% of `wL⁴/8EI` on a
10×2×2 mesh rising to 75.9% on 30×4×4, converging from below and slowly, while axial extension comes
out at 99.44% and 100.04% on the same meshes. Those numbers need the solver container and are not
re-derived here. Axial being right is what pins the material, the clamp, the loads and the solver;
read a bending deflection as a lower bound. The fix is quadratic tetrahedra,
`SmallDisplacementElement3D10N` with TetGen's `-o2`, and the wrapper currently rejects anything but
4-node output, which makes that a visible gap rather than a silent one.

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

Two things in the build are load-bearing and neither is negotiable:

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
