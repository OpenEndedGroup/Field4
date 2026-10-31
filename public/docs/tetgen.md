# Filling a solid: tetrahedral meshing with TetGen

How much will a part deflect? Where will it get hot, or crack? To investigate its interior, you
need more than its outer surface. A tetrahedral mesh divides the enclosed volume into elements you
can use for those calculations.

[Manifold](manifold.md) gives Field closed surfaces to start from. [TetGen](https://wias-berlin.de/software/tetgen/)
takes the closed triangle surface and fills the volume it bounds with tetrahedra whose faces match
that surface exactly. In a box, `Tetgen.tetrahedralize` takes a closed `TriMesh` and returns a
`TetMesh`. This one fills an L-shaped part built with Manifold:

```diffstatic {data-lang=kotlin}
import afield.geometry.mesh.tetgen.*
import afield.geometry.solid.manifold.*

val part = Solid.cube(80.0, 40.0, 15.0) + Solid.cube(15.0, 40.0, 70.0) // an L, 30 triangles
val mesh = Tetgen.tetrahedralize(part.toTriMesh(), targetEdgeLength = 5.0)

mesh.nodeCount // 2287
mesh.cellCount // 10093
mesh.volume() // 81000.00000000007, against Manifold's exact 81000.0
```

Every public declaration carries KDoc, browsable on the [Simulation](reference/simulation.md)
reference page: start from [`Tetgen`](reference/simulation.md#tetgen-kt-tetgen),
[`TetMesh`](reference/simulation.md#tetgen-kt-tetmesh) and
[`TetgenLibrary`](reference/simulation.md#tetgen-kt-tetgenlibrary).

## What a tetrahedral mesh is for

Field's Kratos bridge solves conduction and flow on a fixed background grid: it samples a signed
distance function onto the grid and cuts the body out of it. That is an *embedded* formulation, and
it is the cheaper thing to do. Structural mechanics in Kratos cannot work that way, because Kratos
has no immersed structural element, so `structural.bodyFitted` needs a mesh whose boundary really is
the part's surface.

There is a second reason. Sampling a distance function onto grid nodes rounds off a sharp edge
before the mesher ever sees it, and refining the grid only makes the rounded radius smaller.
Structural stress concentrates at exactly those edges. [The sharp edge](#the-sharp-edge) below
shows both.

## The first mesh

A tetrahedral mesh is awkward to look at, which is a real obstacle to trusting one. Render it
naively and you see the boundary — the surface you already had — while everything the mesher
actually did stays hidden. The `afield.geometry.mesh.tetgen` package has three answers, and the figure below is all three:
`boundary()` for the skin, `filtered()` with a half-space test for a cutaway, and
`toTriMesh(shrink = …)` for the view where each element stands slightly apart from its neighbours.

```diffstatic_runner {data-lang=kotlin screenshot="assets/tetgen/cutaway.png" alt="three views of the same bent bracket, side by side. 'the closed surface TetGen is given - 618 triangles' is a smooth flat-shaded grey solid with two cylindrical bores, its faces so large that the flat panels are single triangles. 'half the elements, skinned - 18523 tetrahedra' is the same shape cut in half lengthways and re-drawn from the tetrahedra, so the cut face is a crust of small facets and the mesh visibly tightens into a dense collar around each bore. 'the same half, elements shrunk to 0.72 - colour is radius ratio, dark is worse' pulls every element back from its neighbours so the interior shows as separated tetrahedra, coloured yellow through teal: yellow over most of the bulk, and the darker teal concentrated in the collars around the bores and along the cut" wait="10" capture="box" open-outputs="true" padding="20" requires="tetgen,manifold" name="cutaway" embed=true}
:::static
import afield.geometry.mesh.tetgen.*
import afield.geometry.solid.manifold.*

// An L-bracket: two blocks, three bores. Sharp edges from the L, curved surfaces from the bores.
// This part is used for every measurement on this page.
val part = Solid.scope {
    val foot = Solid.cube(80.0, 40.0, 15.0)
    val post = Solid.cube(15.0, 40.0, 70.0)
    val bolts = [12.0, 28.0].map {
        Solid.cylinder(40.0, 5.0, segments = 48).translated(55.0, it, -5.0)
    }
    val eye = Solid.cylinder(60.0, 6.0, segments = 48).rotated(0.0, 90.0, 0.0).translated(-20.0, 20.0, 52.0)
    ((foot + post) - Solid.union(bolts + eye)).copy()
}

val surface = part.toTriMesh()
val mesh = Tetgen.tetrahedralize(surface, targetEdgeLength = 5.0)

/*
The cutaway: keep the elements whose centroid is behind y = 20, then take that half's skin.
The newly exposed cut face is in it, which is what makes the picture read as a slice through a
body rather than as a pile of loose triangles.
*/
val centroid = mesh.centroids()
val half = mesh.filtered { centroid[3 * it + 1] < 20.0 }

// And the same half with every element pulled 22% towards its own centre, coloured by quality.
val quality = half.qualities()
val colors = FloatArray(3 * half.cellCount)
for (t in 0 until half.cellCount) {
    val c = afield.graphics.color.Colormaps.sample("viridis", quality[t])
    colors[3 * t] = c.r.toFloat()
    colors[3 * t + 1] = c.g.toFloat()
    colors[3 * t + 2] = c.b.toFloat()
}
:::fold measure it, then draw the three views
println("Manifold  ${part.volume.f4} mm³, ${part.triangleCount} triangles, genus ${part.genus}")
// The surface's own digest, printed because the element count below depends on it and on nothing
// else — see "the count moves between sessions" under Sizing.
println(
    "surface   ${surface.vertexCount} vertices, ${surface.triangleCount} triangles, " +
        "digest ${surface.positions.contentHashCode()}/${surface.indices.contentHashCode()}",
)
println("TetGen    $mesh")
println("          volume ${mesh.volume().f4} mm³")
println("          inverted elements ${mesh.volumes().count { it <= 0.0 }}")
println()
println(
    "cutaway   ${half.cellCount} of ${mesh.cellCount} elements, " +
        "${half.boundary().triangleCount} boundary triangles",
)
println(
    "quality   min ${quality.min().f4}, median " + "${quality.sorted()[quality.size / 2].f4}",
)

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

## Sizing

`targetEdgeLength` is in the surface's own units and is translated to the only size control TetGen
takes, a maximum element volume: `h³ / (6 sqrt 2)`, the volume of a regular tetrahedron of that edge.

The input surface sets the coarsest mesh you can get. TetGen keeps every triangle it is given, and
this bracket's 618 triangles are already about 10 mm across, so asking for larger elements than that
changes nothing, and `tetrahedralize` logs a warning saying so.

Finer requests work as expected. Cost grows roughly in proportion to the element count, about
11–14 µs per element, so a 36,000-element mesh takes about half a second: fast enough to re-mesh on
every geometry edit. The `afield.geometry.mesh.tetgen` package has no way to update a mesh in place, so re-meshing is how you
follow an edit. The measured sweep is under [Measurements](#measurements).

## Into Kratos

A mesh from here goes to Kratos's `structural.bodyFitted` solve, which runs a small-displacement
element on it with a clamp region, gravity and a total force spread over a load region. Open it with
`KratosSession.openMeshAsync` from Kotlin (`createBodyMesh` in the protocol); `TetMesh`'s flat
`positions`/`cells` arrays are sent as they are. The mesh decides the element type: four nodes per
cell gives `SmallDisplacementElement3D4N`, ten gives `SmallDisplacementElement3D10N`.

Use 10-node elements for anything that bends. The 4-node tetrahedron is a constant-strain element:
its corners carry a linear displacement field, so strain has one value across the whole element.
Bending needs strain to vary across a section, tension on one face and compression on the other, so
4-node elements resist it and come out far too stiff (this is called locking). They approach the
right answer from below, and slowly.

Refining does not fix this. On a cantilever under self-weight, the 4-node element reaches 62% of
the Euler–Bernoulli tip deflection at 1,080 elements, still 38% short; the 10-node element is within
5% at thirty. If you are bending anything, pass `order = 2` and mesh coarsely, rather than
passing `order = 1` and refining. [`optional/kratos/VERIFICATION.md`](https://github.com/OpenEndedGroup/Field4/blob/main/optional/kratos/VERIFICATION.md) has the per-mesh numbers, the
axial-extension control, and why landing just under 100% is right.

### Asking for 10-node elements

`order = 2` tetrahedralizes exactly as before and then promotes the result.

```diffstatic {data-lang=kotlin}
val linear = Tetgen.tetrahedralize(surface, targetEdgeLength = 5.0)
val quadratic = Tetgen.tetrahedralize(surface, targetEdgeLength = 5.0, order = 2)

// equivalently, from a mesh already in hand: linear.quadratic()

linear.nodeCount // 8279
quadratic.nodeCount // 57495 — 6.94x, one new node per distinct edge
quadratic.cellCount // 36475, the same tetrahedra
quadratic.nodesPerCell // 10
quadratic.volume() // 76958.90896392865 — linear.volume() to the last bit
quadratic.corners() // the 4-node view back, sharing the same node array
```

The shape does not change. The six new nodes sit at the exact midpoints of straight edges, so the
region, the boundary, the volume and both quality measures come out the same numbers; what changes
is the interpolation inside an element. `qualities()`, `boundary()`, `filtered()` and `toTriMesh()`
read a cell's first four indices and are unaffected.

??? aside "TetGen's `-o2` has a use-after-free"
    TetGen 1.6.0's `highorder()` `delete[]`s the `highordertable` holding every element's extra-node
    pointers before it returns, and `outelements()` then dereferences them. Driven from the command
    line it usually survives; driven through `tetgenio` it does not, because `outelements()`
    allocates the output node array first and the allocator hands back the block just freed — so
    TetGen reads its own coordinates as pointers. [`tools/tetgen_o2_use_after_free.py`](https://github.com/OpenEndedGroup/Field4/blob/main/tools/tetgen_o2_use_after_free.py) pins that
    outside the JVM: the same cube built twice from the same TetGen source, SIGSEGV as shipped and
    229 nodes with that one `delete[]` commented out.

    `TetMesh.quadratic()` does the promotion on this side instead, which also means the midside
    nodes are emitted in Kratos's `Tetrahedra3D10` order by construction rather than recovered from
    TetGen's internal edge tables. That ordering is checked geometrically at both ends, because a
    mis-ordered quadratic element does not fail — it assembles, it solves, and it returns a
    plausible displacement field that is wrong.

## Checking a mesh

Check a mesh before you solve on it. Two things go wrong: elements too badly shaped for the solver,
and an input surface that TetGen accepts but that is not the body you meant.

### The two quality measures

Two quality measures get used here and they are not the same number. Confusing them is the easiest
mistake in this subsystem, so both have their own call.

<div class="field-data-table" markdown>

| Measure | what it is | what it tells you |
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
the bound. On the tail, the radius-edge ratio ranks elements in the opposite order to the radius
ratio.

```diffstatic_runner {data-lang=kotlin screenshot="assets/tetgen/quality.png" alt="two stacked output panels. The upper one plots 'radius ratio over 66489 elements' as a histogram, x axis '3 r_in / r_circ' from 0 to about 1, y axis 'elements' to 6000: a long flat tail hugging zero from 0.05 up to about 0.5, then a steep climb to a peak just under 7000 elements around 0.9, and a sharp drop in the last bin. Almost the whole population sits in the good half of the axis. The lower panel, 'the 400 worst elements, in place', draws those elements alone against black, so they hang in space as a few hundred slivers and flat wedges holding the silhouette of the bracket; they are thickest in two dense knots where the bores are and thin everywhere else. Its footer reads 1459 verts, 1584 tris, extent 80.000 x 40.000 x 70.000, chi=709, open (0 boundary loops), and 'radius ratio 0.0362 to 0.2886 - densest around the three bores, and scattered everywhere else'" wait="12" capture="box" open-outputs="true" padding="20" requires="tetgen,manifold" name="quality" embed=true}
:::static
import afield.geometry.mesh.tetgen.*
import afield.geometry.solid.manifold.*

// The same bracket as the figure above, built in the same order — which matters more than it
// looks like it should. See "the same solid, built in a different order" under Sizing.
val part = Solid.scope {
    val foot = Solid.cube(80.0, 40.0, 15.0)
    val post = Solid.cube(15.0, 40.0, 70.0)
    val bolts = [12.0, 28.0].map {
        Solid.cylinder(40.0, 5.0, segments = 48).translated(55.0, it, -5.0)
    }
    val eye = Solid.cylinder(60.0, 6.0, segments = 48).rotated(0.0, 90.0, 0.0).translated(-20.0, 20.0, 52.0)
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
println(
    "radius ratio     min ${sorted.first().f4}  " +
        "1st pct ${sorted[sorted.size / 100].f4}  " +
        "median ${sorted[sorted.size / 2].f4}  max ${sorted.last().f4}",
)
println("below 0.05       ${sorted.count { it < 0.05 }}   (slivers)")
println("below 0.10       ${sorted.count { it < 0.10 }}")
println()
println(
    "radius-edge      min ${radiusEdge.min().f4}  " +
        "(the regular tetrahedron's floor is ${(Math.sqrt(6.0) / 4.0).f4})",
)
println(
    "                 max ${radiusEdge.max().f4}, " +
        "${radiusEdge.count { it > 1.2 }} above the 1.2 bound that was asked for",
)
println()
// The point of printing these together: the two measures do not rank the same elements. The worst
// element by radius-edge is a perfectly usable one, and most of the bad ones are inside the bound.
println(
    "worst by radius-edge: ${radiusEdge.max().f4}, whose radius ratio is " +
        "${quality[radiusEdge.indices.maxBy { radiusEdge[it] }].f4} — a fine element",
)
val inBound = quality.indices.filter { radiusEdge[it] <= 1.2 }
println(
    "of the ${inBound.size} elements inside the bound, ${inBound.count { quality[it] < 0.10 }} " +
        "are below radius ratio 0.10; the worst is ${inBound.minOf { quality[it] }.f5}",
)

showChart(
    Chart(
            title = "radius ratio over ${mesh.cellCount} elements",
            xLabel = "3 r_in / r_circ",
            yLabel = "elements",
        )
        .histogram("elements", quality.toList(), bins = 40),
)
showMesh(
    slivers.boundary(),
    title = "the 400 worst elements, in place",
    caption =
        "radius ratio ${sorted.first().f4} to ${sorted[399].f4} · " +
            "densest around the three bores, and scattered everywhere else",
    view = MeshView(yawDegrees = 34.0, pitchDegrees = 22.0, width = 760, height = 420),
)
part.close()
```

On that bracket at `targetEdgeLength = 3.0`: 66,489 elements, median radius ratio `0.8483`, worst
`0.0362`, and **4** elements below `0.05`. A long thin tail under a healthy median is the normal
shape for Delaunay refinement, so judge a mesh by its tail rather than by its median.

### Input surfaces

What `Tetgen.tetrahedralize` does with common malformed surfaces, next to a closed cube for
comparison:

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
complement of what you meant. If you are pushing a repaired scan through both,
`TriMesh.diagnose()` reporting `INVERTED_ORIENTATION` matters to the Manifold half and not to this
one.

The coincident-corner cube is not a failure either: collapsing one corner of a unit cube onto its
neighbour really does leave a body of volume `2/3`, and TetGen meshed exactly that. This is the
shape the mistake takes in practice: a mesh off disk with an unwelded or over-welded seam produces a
plausible solid of the wrong size. `Tetgen.tetrahedralize` checks the surface first and says so on
`warning,tetgen`, naming the coincident vertices, open loops or collapsed triangles it found, and
warns again if the tetrahedra do not hold the volume the surface encloses.

??? aside "The fatal path"
    Two of the eight — self-intersection and degeneracy — reach TetGen's own fatal handler. Built
    normally TetGen calls `exit()` there, which inside a live Field session ends the session. The
    wrapper is compiled with `TETLIBRARY`, which makes TetGen throw an `int` instead, and
    [`field_tetgen.cpp`](https://github.com/OpenEndedGroup/Field4/blob/main/native/field-tetgen/src/field_tetgen.cpp) catches it: letting a C++ exception unwind through FFM's downcall frames
    aborts the VM. `TetgenTest.aFatalSurfaceIsRefusedWithoutTakingTheProcessDown` feeds it both
    inputs and checks the process survives.

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
import afield.geometry.mesh.tetgen.*

TetgenLibrary.isAvailable // cheap enough to branch on in a box
TetgenLibrary.describe() // "TetGen wrapper at /…/libfield_tetgen_jvm.dylib (TetGen 1.6.0)"
```

`-Dafield.tetgen.library=<path>` or `AFIELD_TETGEN_LIBRARY` point it at a build of your own.
Unlike Manifold there is no package to suggest instead: TetGen has no Homebrew formula and no
distribution package worth linking.

**Licensing.** TetGen is AGPL-3 or a commercial licence, and that is why most projects cannot use
it. Field is AGPL-3, so the first applies and nothing further is needed. Kratos itself gates TetGen
behind `USE_TETGEN_NONFREE_TPL` because *Kratos* is BSD, and its published wheels contain MMG and no
TetGen.

??? aside "Two build settings the wrapper depends on"
    - **`predicates.cxx` is compiled at `-O0`.** Upstream's own makefile says so. Shewchuk's exact
      predicates depend on their arithmetic not being reassociated or kept at extended precision in
      a register; built optimised they return wrong signs on nearly-degenerate input. That does not
      crash — it silently produces an invalid tetrahedralization.
    - **`TETLIBRARY` is defined**, which is what turns `exit()` into a throwable. See "The fatal
      path" above.

    The C ABI passes results through out-parameters rather than a struct, so the Kotlin side never
    has to reproduce this compiler's field offsets and tail padding. Getting that wrong reads
    plausible garbage rather than failing.

## Measurements

The sweeps behind the advice above, all on the bracket from [The first mesh](#the-first-mesh) unless
a section says otherwise.

??? aside "Where the numbers on this page come from"
    They were produced in this repository on an Apple M-series laptop, natively, with no container,
    except the beam deflections under [Into Kratos](#into-kratos), which come from the solver
    container. [`dev_docs/TETGEN.md`](https://github.com/OpenEndedGroup/Field4/blob/main/dev_docs/TETGEN.md) has the mesher comparison that chose TetGen, including a sweep of
    `StructuralMechanicsApplication` for an immersed element.

### The `quality` bound

TetGen's own default bound is `1.414`; `Tetgen.tetrahedralize` defaults to `1.2`. Swept on the same
bracket at `targetEdgeLength = 5.0`:

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
    [That page](https://github.com/OpenEndedGroup/Field4/blob/main/dev_docs/TETGEN.md) records that `1.2` removed the last poor element "for no measurable cost", measured on
    a slab welded to a ball. On this bracket it removes nothing and costs 20%. The difference is
    the geometry: the default is a starting point, so sweep it on your own part.

### Element size

<div class="field-data-table" markdown>

| `targetEdgeLength` | nodes | elements | time |
|---|---|---|---|
| `null` | 7,034 | 30,858 | — |
| 8.0 mm | 7,174 | 31,462 | 379 ms |
| 5.0 mm | 8,279 | 36,475 | 510 ms |
| 3.0 mm | 14,011 | 66,489 | 731 ms |
| 2.0 mm | 31,545 | 163,082 | 1,949 ms |

</div>

An 8 mm request on a part 80 mm across gets almost exactly what no request gets. Downwards, 2 mm
is 4.5× the elements of 5 mm and 3.8× the time, roughly linear in element count at 11–14 µs per
element. A body-fitted structural solve of 36,000 elements spends about half a second meshing and
several seconds solving.

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

### `preserveSurface` on a Boolean result

`preserveSurface = true` is TetGen's `-Y`: forbid Steiner points on the boundary, so the output
surface is the input surface, triangle for triangle. It is off by default and should stay off unless
you have measured your own case. On the bracket at `targetEdgeLength = 3.0`:

<div class="field-data-table" markdown>

| Configuration | nodes | elements | worst radius ratio | slivers |
|---|---|---|---|---|
| `preserveSurface = false` | 14,011 | 66,489 | 0.0362 | 4 |
| `preserveSurface = true` | 306 | 509 | **0.00001** | **285** |

</div>

With `-Y`, 56% of the elements are slivers and the worst is degenerate to five decimal
places. `-Y` refuses to add the boundary points that refinement needs, and on a surface a Boolean
produced — with the long thin triangles a cylinder-through-a-block leaves — there is nowhere else for
the refinement to go. The size request goes with it: 509 elements when you asked for something 130
times finer.

### The sharp edge

TetGen conforms to the surface it is given, so a corner in the input stays a corner in the output.
That is the whole reason to prefer it to a level set, and it shows up two ways: against an exact
volume, and against the formulation TetGen replaced.

A `2 × 3 × 4` box has an exact volume of `24`. Through Manifold's 12-triangle surface and TetGen at
`targetEdgeLength = 0.5`, Field gets `23.999999999999936`, a relative error of `2.7e-15` (three
ulps) on 3,059 tetrahedra: exact, up to double-precision rounding. A mesher that rounded the
corners could not land there.

The comparison that matters is against the route this replaced. The figure below marches the *same*
box out of its own distance function, which is what an embedded formulation does before it ever sees
an edge, and measures how far the result sits from the true surface.

```diffstatic_runner {data-lang=kotlin screenshot="assets/tetgen/sharp_edge.png" alt="two stacked plots. The upper, 'the corner at (1.0, 1.5), in a 0.24 x 0.24 window', zooms far enough in to see one corner four times over: the orange 'exact, and TetGen's' outline turns a true right angle at x=1, y=1.5, while the three marched outlines cut it off short, the red h=0.4 lowest and flattest, the green h=0.2 above it, the blue h=0.1 nearly on the orange but still parting from it as it approaches the corner. Captioned 'halving h halves the bite the level set takes out of the corner'. The lower, 'worst boundary error against cell size', is a log-log plot of worst distance from the true surface against cell size h at 0.05, 0.1, 0.2 and 0.4: two straight lines of different slope, blue 'box - first order' running from about 0.003 to 0.03, green 'sphere - second order' from about 0.0002 to 0.01 and so steeper. Captioned 'log-log. The box halves when h halves; the sphere quarters.'" wait="14" capture="box" open-outputs="true" padding="20" requires="tetgen,manifold" name="sharp_edge" embed=true}
:::static
import afield.geometry.mesh.tetgen.*
import afield.geometry.solid.manifold.*

// The exact distance to the surface of a 2 x 3 x 4 box centred on the origin, and to a sphere.
// One has edges, the other does not; that is the whole experiment.
fun boxDistance(x: Double, y: Double, z: Double): Double {
    val dx = abs(x) - 1.0
    val dy = abs(y) - 1.5
    val dz = abs(z) - 2.0
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
val sizes = [0.4, 0.2, 0.1, 0.05]
val boxError = mutableListOf<Double>()
val sphereError = mutableListOf<Double>()
val corners = mutableListOf<Pair<String, List<Vec2>>>()

println("h      box, worst error        sphere, worst error")
for (h in sizes) {
    val b = Solid.levelSet(bounds, h) { x, y, z -> -boxDistance(x, y, z) }
    val s = Solid.levelSet(bounds, h) { x, y, z -> -sphereDistance(x, y, z) }
    boxError += worstError(coordinates(b.toTriMesh()), ::boxDistance)
    sphereError += worstError(coordinates(s.toTriMesh()), ::sphereDistance)
    println(
        "${h.f2}   ${boxError.last().f6} = " +
            "${(boxError.last() / h).f4} h    " +
            "${sphereError.last().f6} = ${(sphereError.last() / h).f4} h",
    )
    // A real cross section through the marched body, at z = 0. The rounding is 1.5% of the body at
    // h = 0.4: plain in a cross section, invisible in a render of the whole box.
    if (h >= 0.1) corners += "marched, h = $h" to b.slice(0.0).toPolygons().first()
    b.close()
    s.close()
}

// The same box through TetGen, which is handed Manifold's exact triangulation and constrains its
// output to it.
val exactBox = Solid.cube(2.0, 3.0, 4.0, center = true)
val tet = Tetgen.tetrahedralize(exactBox.toTriMesh(), targetEdgeLength = 0.25)
println()
println("TetGen at h = 0.25: ${tet.cellCount} elements, volume ${tet.volume()}")
// Only outward error counts here: an interior node is *supposed* to be inside the box.
println(
    "  worst node outside the true box: " + worstError(tet.positions) { x, y, z -> max(boxDistance(x, y, z), 0.0) },
)

/*
One corner of the true cross section, magnified. The exact outline is what TetGen is given and
what its boundary reproduces; each level set is a different rounded approximation of it.

Pass the complete, explicitly closed contours to Chart; its axes clip the visible segments.
*/
val xLo = 0.82
val xHi = 1.06
val yLo = 1.32
val yHi = 1.56
fun closedCoordinates(loop: List<Vec2>): Pair<List<Double>, List<Double>> {
    val closed = loop.toMutableList()
    if (closed.isNotEmpty()) closed.add(closed.first())
    return closed.map { it.x.toDouble() } to closed.map { it.y.toDouble() }
}
val exactCorner = closedCoordinates(exactBox.slice(0.0).toPolygons().first())
val corner = Chart(
    title = "the corner at (1.0, 1.5), in a 0.24 x 0.24 window",
    xLabel = "x",
    yLabel = "y",
    note = "halving h halves the bite the level set takes out of the corner",
)
corner.width = 780.0
corner.height = 440.0
corner.xDomain = xLo to xHi
corner.yDomain = yLo to yHi
corner.line(
    "exact, and TetGen's",
    exactCorner.first,
    exactCorner.second,
    color = vec(1.0, 0.72, 0.35, 1.0),
    thickness = 3.0,
)
// Coarsest red through finest blue, explicitly. The default palette gives three of these four
// series a warm colour and the figure becomes four orange lines.
val byResolution = [
    vec(0.95, 0.45, 0.45, 1.0),
    vec(0.55, 0.95, 0.55, 1.0),
    vec(0.45, 0.85, 1.0, 1.0),
]
corners.forEachIndexed { i, (label, points) ->
    val contour = closedCoordinates(points)
    corner.line(label, contour.first, contour.second, color = byResolution[i], thickness = 2.0)
}

// And the convergence, which is the actual finding: slope 1 on the box, slope 2 on the sphere.
val rate = Chart(
    title = "worst boundary error against cell size",
    xLabel = "cell size h",
    yLabel = "worst distance from the true surface",
    note = "log-log. The box halves when h halves; the sphere quarters.",
)
rate.width = 780.0
rate.height = 440.0
rate.xKind = ScaleKind.LOG
rate.yKind = ScaleKind.LOG
val blue = vec(0.45, 0.85, 1.0, 1.0)
val green = vec(0.55, 0.95, 0.55, 1.0)
rate
    .line("box — first order", sizes, boxError, color = blue, thickness = 2.4)
    .points("box — first order", sizes, boxError, color = blue, size = 4.5)
rate
    .line("sphere — second order", sizes, sphereError, color = green, thickness = 2.4)
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
constraints, and TetGen's output honours them. For a part with a sharp edge, a body-fitted mesh preserves that edge directly. A refined level-set
mesh approaches it, but still rounds it at the scale of the grid. [`dev_docs/TETGEN.md`](https://github.com/OpenEndedGroup/Field4/blob/main/dev_docs/TETGEN.md) has the per-resolution errors, and the same
comparison run against MMG rather than marching cubes.

### Mesh the `Solid` for full precision

`Tetgen.tetrahedralize` also takes a Manifold `Solid` directly, and that is the route to use when the
volume has to match Manifold's:

```diffstatic {data-lang=kotlin}
val mesh = Tetgen.tetrahedralize(part, targetEdgeLength = 5.0) // part is the Solid itself
```

`TriMesh` stores positions as `Float`, so `part.toTriMesh()` narrows every coordinate before TetGen
sees it. The `Solid` overload reads Manifold's `double` positions instead. On the bracket:

<div class="field-data-table" markdown>

| Representation | volume of the bracket | relative to Manifold |
|---|---|---|
| Manifold, in double | `76958.9090888672` | |
| TetGen's mesh of `part.toTriMesh()` | `76958.90896392895` | `1.6e-9` |
| TetGen's mesh of `part` | `76958.90908886695` | `3.4e-15` |

</div>

The cube is exact either way, because `0`, `2`, `3` and `4` are exactly representable in float32 and
a 48-segment cylinder's vertices are not. The `Solid` overload takes the same `quality`,
`targetEdgeLength`, `preserveSurface` and `order` arguments.

### Promoting to 10 nodes

The promotion is cheap and the elements are not. On the bracket at `targetEdgeLength = 5.0` it costs
71 ms against the mesh's 466 ms, and at 3.0, 73 ms against 782 ms. It is one pass over the edges,
not a re-mesh. Seven times the nodes is seven times the unknowns, though, and that is downstream: on
the 864-element beam Kratos takes 1.05 s at order 2 against 0.20 s at order 1, for 99.8% against
69.5%.

## See also

- [Solids and Booleans](manifold.md) — where the closed surface comes from, and the other half of
  the winding story.
- [Meshes and 3D geometry](mesh_examples.md) — `TriMesh`, and the marching cubes the figure above
  compares against.
- [Geometry from disk](geometry_from_disk.md) — `diagnose()` and `repairToSolid()`, for the meshes
  that arrive not closed.
