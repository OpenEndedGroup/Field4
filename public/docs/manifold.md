# Solids that cannot fail: Manifold in Field

*Every public declaration used below carries its own KDoc — start from `Solid`, `Section` and
`ManifoldLibrary` in `afield.manifold`. The transcript that ships as an openable starting point is
[manifold_solids](agent_transcripts.md).*

Field's mesh family makes triangles. [`TriMesh`](mesh_examples.md) generates them, marches them out
of an SDF, subdivides, smooths, decimates, measures and writes them to disk; the
[ray tracer](ray_tracing_examples.md) intersects analytic solids; [`FLine`](fline_examples.md) draws
outlines. None of it does the one thing that turns a surface into a *part*:

> **subtract one mesh from another and be certain the answer is still a closed solid.**

Coincident faces, near-degenerate triangles and features below the floating-point noise floor are
what make that hard, and [Manifold](https://github.com/elalish/manifold) is the library that solved
it. `afield.manifold` binds it through Panama/FFM, in about 2400 lines of Kotlin and no C++ at all.

```diffstatic {data-lang=kotlin}
val plate = Solid.cube(60.0, 40.0, 8.0, center = true)
val bore  = Solid.cylinder(30.0, 3.0, segments = 64, center = true)
val part  = plate - Solid.union((0..3).map { bore.translated(it * 15.0 - 22.5, 0.0, 0.0) })

part.volume        // 18296.674 — measured, not estimated
part.genus         // 4 — you can count the holes
part.status        // NO_ERROR — and it says so when it is not
part.triangleCount // 1052
display(part.toTriMesh())
```

## Measuring a solid

Manifold measures the **solid** rather than summing over float triangles, so `Solid.cube(2,3,4).volume`
is `24.0` and not `23.999998`. That changes how the API feels: the results are arithmetic you can do
and assert on, instead of a render you eyeball.

<div class="field-data-table" markdown>

| you want | on a `Solid` | on a `Section` |
|---|---|---|
| how much material | `volume` | `area` |
| how much skin | `surfaceArea` | — |
| how many holes | `genus` | `contourCount` |
| the mesh size | `triangleCount`, `vertexCount` | `vertexCount` |
| the extent | `bounds`, an `AxisAlignedBoundingBox3D` | `bounds`, a corner pair |
| the clearance between two of them | `minGap(other, searchLength)` | — |
| whether it worked | `status`, `isEmpty` | `isEmpty` |

</div>

Two of those will read oddly the first time. `triangleCount` on a cube is `12`, the minimal closed
mesh rather than the 24 a per-face mesh would have; and `genus` counts holes through the solid, so
the four-bore plate above is genus 4 before you count anything by hand. The whole census of Field's
answers against their closed forms — including what each `JoinType` does to the area of an offset
square — is in `dev_docs/MANIFOLD_AGREEMENT.md`.

## Booleans

A chain of these cannot stop being a solid without saying so. Note `Solid.union(list)` rather than
folding `+` (Manifold plans the whole batch instead of nesting *n−1* operations), and
`Solid.scope { }`, which releases the intermediates instead of leaving them to a `Cleaner`.

```diffstatic_runner {data-lang=kotlin screenshot="assets/manifold/booleans.png" alt="an output panel headed 'Four bored bosses and a rounded slot, from eleven Booleans', holding a flat-shaded grey triangle mesh of a rectangular plate seen in perspective. A large rectangular window is cut through the middle, a rounded boss stands at each of the four corners with a cylindrical bore through it, and a rounded slot runs along one side. The corner header reads 3312 tri; the footer reads 1648 verts, 3312 tris, extent 60.000 x 40.000 x 14.000, chi=-8 genus 5, watertight, volume 15420.9314, and beneath it 'genus 5 - 3312 triangles - every step guaranteed closed'" wait="4" capture="box" open-outputs="true" padding="20" requires="manifold" name="booleans" embed=true}
:::static
import afield.manifold.*

val part = Solid.scope {
    val plate = Solid.cube(60.0, 40.0, 8.0, center = true)
    val corners = listOf(-20.0 to -12.0, 20.0 to -12.0, -20.0 to 12.0, 20.0 to 12.0)

    // Bosses standing on the plate, and a bore straight through each.
    val bosses = corners.map { (x, y) ->
        Solid.cylinder(14.0, 6.0, segments = 64, center = true).translated(x, y, 0.0)
    }
    val bores = corners.map { (x, y) ->
        Solid.cylinder(30.0, 3.0, segments = 64, center = true).translated(x, y, 0.0)
    }

    // A slot with properly rounded ends: that is a 2D offset, extruded. There is no correct way to
    // get those end caps by moving points around.
    val slot = Section.square(26.0, 9.0, center = true)
        .offset(4.5, JoinType.ROUND, circularSegments = 64)
        .extruded(30.0)
        .translated(0.0, 0.0, -15.0)

    Solid.union(listOf(plate) + bosses) - Solid.union(bores + slot)
}
:::fold measure it — including the wall thickness that decides whether it survives being cut
// The plain mesh for measuring, because `normals = true` splits vertices at every crease and
// Field's isWatertight()/genus() read indices. See "Two meshes" below.
val mesh = part.toTriMesh()
println("volume  ${"%.3f".format(part.volume)} mm³  (${"%.1f".format(part.volume * 0.0027)} g in 6061)")
println("area    ${"%.3f".format(part.surfaceArea)} mm²")
println("genus   ${part.genus}   — four bores and the slot")
println("status  ${part.status}")
println()
println("Field's own mesh family, which shares no code with Manifold, on the same surface:")
println("  watertight ${mesh.isWatertight()},  genus ${mesh.genus()},  " +
    "volume ${"%.3f".format(mesh.signedVolume())}")

// A clearance check. This is the number a drawing would call out, and `minGap` is how you get it.
val nearestBore = Solid.cylinder(30.0, 3.0, segments = 64, center = true).translated(-20.0, -12.0, 0.0)
val slotSolid = Section.square(26.0, 9.0, center = true)
    .offset(4.5, JoinType.ROUND, circularSegments = 64).extruded(30.0).translated(0.0, 0.0, -15.0)
println()
println("wall between the slot and the nearest bore: " +
    "${"%.3f".format(slotSolid.minGap(nearestBore, searchLength = 30.0))} mm")

// The plain mesh, not the shaded one: showMesh prints its own topology footer, and a crease-split
// mesh would make that footer read "open (42 boundary loops)" directly under a caption claiming the
// solid is closed. Both statements would be true of different meshes, which is worse than either.
showMesh(
    mesh,
    title = "Four bored bosses and a rounded slot, from eleven Booleans",
    caption = "genus ${part.genus} · ${part.triangleCount} triangles · every step guaranteed closed",
    view = MeshView(yawDegrees = 34.0, pitchDegrees = 26.0, width = 760, height = 470),
)
```

Manifold's Booleans are **lazy**: `a - b` builds a node and the work happens when something asks for a
number. So `volume`, `genus` and `toTriMesh()` are the expensive calls, not the operators, and timing
an operator measures nothing.

## Invalid input comes back as a status

Manifold never throws and never returns null. A failed operation gives you a `Solid` whose `status` is
not `NO_ERROR` and whose volume is `0.0`. Upstream chose that so a twenty-step chain does not need
twenty checks; the cost is that **an unchecked pipeline can end in silence**. Two ways out:

```diffstatic {data-lang=kotlin}
// 1. `checked()` throws, naming the status and the repair, instead of yielding 0 mm³.
val part = (body - bore).checked("the bracket")

// 2. Or branch on it, when empty is a legitimate answer.
if (part.status.isError) println("could not build it: ${part.status}")
```

The commonest failure by far is a mesh that came off disk. An STL has no index buffer at all, so every
triangle owns its three vertices and the mesh is 36 loose vertices rather than a closed cube.

**Do not guess which repair to try.** `toSolid()`'s refusal carries `TriMesh.diagnose()`'s report, so
it names the defect instead of handing you a list of things to try in order:

```diffstatic {data-lang=text}
this TriMesh is not a valid manifold: NOT_MANIFOLD.
mesh: 8 vertices, 10 triangles, chi=1, 1 component(s)
1 blocking defect(s) — Manifold will refuse this mesh.
1. [BLOCKING] OPEN_BOUNDARY x4
     4 boundary edges in 1 traced loop(s), edge counts [4]
     fix: TriMesh.fillHoles() — flat holes fill cleanly; a non-planar loop gets a flat cap over a wavy rim.
```

Two calls, both in `afield.stdlib`, both documented at length under
[geometry from disk](geometry_from_disk.md):

<div class="field-data-table" markdown>

| you want | call |
|---|---|
| to know *why* this mesh is not a solid | `println(mesh.diagnose().report())` |
| to fix it, and be told what could not be fixed | `mesh.repairToSolid()` |
| to see where the trouble is | `showMeshDiagnosis(mesh)`, or `mesh.defectFLines()` |

</div>

`repairToSolid()` welds, drops degenerate and duplicated faces, removes dangling fins, fixes the
winding, **fills the holes**, and fixes the winding again, then hands back a `MeshRepairResult`
rather than a mesh, because what you need next is `unfixed`: the blocking defects still present,
each with the reason it was left alone. `ToyCar.glb` in this repo goes through it and comes out a
solid you can boolean.

`repaired()` is the right call for a mesh whose only problem is an unwelded seam. It does not fill
holes, which is why it makes 6 of the 21 rejected meshes in the repair test table valid where
`repairToSolid()` makes 19 valid, of which **15 are also the shape you started with**, judged against
the undamaged original. See [geometry from disk](geometry_from_disk.md) for why those two numbers
have to be reported separately, and for the pair of cases proving that no local check can tell them
apart.

**Manifold accepts an inside-out mesh.** A closed, consistently-but-inwardly wound surface is a
legal manifold that reports a *negative* volume — so `status` is `NO_ERROR`, and the next boolean
computes the complement of what you meant. `isValidSolid()` cannot catch that; `diagnose()` reports
it as `INVERTED_ORIENTATION`, and `repairToSolid()`'s second winding pass fixes it. Manifold likewise
accepts zero-area triangles, pinch vertices, and disjoint components — whose volumes it sums.

`NON_FINITE_VERTEX` is a NaN in the positions, usually a degenerate normal upstream.
`repairToSolid()` refuses that one outright rather than repairing around it — dropping the affected
triangles and capping the gap would produce a plausible solid missing an unknown amount of the model.

`TriMesh.isValidSolid()` is the cheap pre-flight, and `toSolid()` checks by default.

## 2D sections and offsets

`Section` is Manifold's `CrossSection`, which is [Clipper2](https://github.com/AngusJohnson/Clipper2)
underneath. Field draws and booleans 2D outlines already, through `FLine` and the Java2D `Area`
bridge, and `FLine.byOffsetting` displaces a curve along its own normals — which self-intersects
wherever the offset exceeds the local radius of curvature. `Section.offset` computes a true parallel
curve at distance *d*, resolves those self-intersections, and gives you a choice of what happens at a
corner.

That single operation is a fillet, a wall thickness, a tool radius, a clearance, a press fit, and a
stroke that is a *shape* rather than a rendering hint.

```diffstatic_runner {data-lang=kotlin screenshot="assets/manifold/offsets.png" alt="six cards in two rows of three. Each of the first four draws the same white source outline - a rectangle with a notch bitten out of its right edge and a ring near its left end - with a blue curve offset 2.5 outside it, and differs only in how that blue curve turns the outside corners: 'offset +2.5 with SQUARE', area 1354.0098 and 78 vertices; 'offset +2.5 with ROUND', area 1352.3844 and 264 vertices; 'offset +2.5 with MITER', area 1360.4438 and 72 vertices; 'offset +2.5 with BEVEL', area 1341.6938 and 78 vertices. The fifth, 'a 3mm wall, following every corner', fills the band between the outline and outline.offset(-3.0) in solid orange and reads 494.1382 mm-squared in 3 contours. The sixth, 'an opening - offset(-2.5) then offset(+2.5)', is captioned 'the corners do not come back sharp' and picks out in green the arcs where four corners and the notch have been rounded off by the round trip" wait="4" capture="box" open-outputs="true" padding="20" requires="manifold" name="offsets" embed=true}
:::static
import afield.manifold.*

// A plate with a hole and a notch — concave and convex corners, so the join types have somewhere
// to show themselves.
val outline = Solid.scope {
    (Section.square(40.0, 26.0, center = true)
        - Section.circle(5.0, segments = 64).translated(-11.0, 0.0)
        - Section.square(10.0, 8.0, center = true).translated(20.0, 0.0)).copy()
}

// Each join type on the same shape, and the areas they come out at.
val joins = JoinType.entries.map { it to outline.offset(2.5, it, circularSegments = 128) }

// A wall of constant thickness, following every corner: the shape minus itself eroded.
val wall = outline - outline.offset(-3.0, JoinType.ROUND, circularSegments = 128)

// And an opening on the real outline, so the corner rounding is visible rather than only measured.
val opened2 = outline.offset(-2.5, JoinType.ROUND, circularSegments = 128)
    .offset(2.5, JoinType.ROUND, circularSegments = 128)
:::fold the numbers, then draw them
println("outline area ${"%.4f".format(outline.area)}, ${outline.contourCount} contours")
joins.forEach { (join, grown) ->
    println("  offset +2.5 ${join.name.padEnd(6)} -> area ${"%.4f".format(grown.area)}, " +
        "${grown.vertexCount} verts")
}
println()
println("a 3mm wall is ${"%.4f".format(wall.area)} mm², in ${wall.contourCount} contours")

// A closing (dilate then erode) is EXACTLY the identity on a convex region — the two offsets are
// true inverses where nothing was rounded away. An opening (erode then dilate) is not, and the
// difference is precisely the corners the erosion cut off.
val square = Section.square(2.0, 2.0, center = true)
val closed = square.offset(0.5, JoinType.ROUND, circularSegments = 512)
    .offset(-0.5, JoinType.ROUND, circularSegments = 512)
val opened = square.offset(-0.5, JoinType.ROUND, circularSegments = 512)
    .offset(0.5, JoinType.ROUND, circularSegments = 512)
println()
println("closing a 2x2 square: ${"%.6f".format(closed.area)} (the identity, 4.0)")
println("opening it:           ${"%.6f".format(opened.area)} (4 - (4-pi)/4 = " +
    "${"%.6f".format(4.0 - (4.0 - Math.PI) * 0.25)})")

fun white() = outline.toFLines().onEach { it.color = vec(1.0, 1.0, 1.0, 0.95); it.thickness = 2.2 }

showPlaneGrid(
    *joins.map { (join, grown) ->
        "offset +2.5 with ${join.name}\narea ${"%.4f".format(grown.area)}, ${grown.vertexCount} vertices" to
            (grown.toFLines().onEach {
                it.color = vec(0.45, 0.85, 1.0, 0.9); it.thickness = 1.8
            } + white())
    }.toTypedArray(),
    "a 3mm wall, following every corner\noutline - outline.offset(-3.0)\n" +
        "${"%.4f".format(wall.area)} mm² in ${wall.contourCount} contours" to
        (wall.toFLines().onEach {
            it.color = vec(1.0, 0.72, 0.35, 0.85); it.filled = true; it.stroked = false
        } + white()),
    "an opening — offset(-2.5) then offset(+2.5)\nthe corners do not come back sharp" to
        (opened2.toFLines().onEach {
            it.color = vec(0.7, 1.0, 0.6, 0.9); it.thickness = 1.8
        } + white()),
    columns = 3,
    cellWidth = 330,
)
```

Winding is what decides which contours are holes, under a `FillRule`. Read a region back with
`toPolygons()` and the outlines are counter-clockwise (positive signed area) and the holes clockwise —
measured on an annulus, `+3.1413` outer and `−0.7853` inner. Field's default is `FillRule.POSITIVE`
rather than Clipper's `EVEN_ODD`, because it is the rule under which winding *means* something: two
overlapping same-wound outlines are one shape, where `EVEN_ODD` would punch their overlap out as a
hole.

## A Kotlin lambda, called from a C++ thread pool

Upcalls are where FFM earns its keep. Manifold's `level_set` takes a
`double (*)(double, double, double, void*)` and marches it into a solid; `Solid.levelSet` turns a
Kotlin lambda into exactly that function pointer, for the duration of one call.

The difference from Field's own [`marchingCubes`](mesh_examples.md) is not resolution or speed, it is
the guarantee: whatever the field did (a sheet thinner than the grid, a surface that touches itself, a
feature below the cell size), **the output is a valid closed manifold**, and therefore something you
can immediately subtract from.

```diffstatic_runner {data-lang=kotlin screenshot="assets/manifold/levelset.png" alt="an output panel headed 'A gyroid-thickened torus, marched from a Kotlin function', holding a grey triangulated ring lying at an angle, its hole still open in the middle. The surface is not a smooth tube: it rises and falls in deep rounded lobes all the way round, the gyroid showing through as thickening and thinning rather than as a pattern on the surface. The triangles are fine and even enough to read as a mesh. The footer reads 14869 verts, 29738 tris, extent 59.498 x 59.498 x 15.870, chi=0 genus 1, watertight, volume 17750.1245" wait="8" capture="box" open-outputs="true" padding="20" requires="manifold" name="levelset" embed=true}
:::static
import afield.manifold.*
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

// A gyroid-thickened torus: a shape that only really exists as a function, and exactly the sort of
// field where plain marching cubes produces a soup wherever the surface nearly touches itself.
// Positive inside, which is Manifold's convention (Field's own sdf* family is negative inside —
// Solid.levelSetOf flips the sign for you).
fun field(x: Double, y: Double, z: Double): Double {
    val q = hypot(x, y) - 22.0
    val tube = 8.0 - sqrt(q * q + z * z)
    val s = 0.32
    val g = sin(s * x) * cos(s * y) + sin(s * y) * cos(s * z) + sin(s * z) * cos(s * x)
    return tube - 0.55 * 8.0 * abs(g) * 0.5
}

val bounds = boundsOf(vec(-32.0, -32.0, -10.0), vec(32.0, 32.0, 10.0))

// `parallel = true` lets Manifold call `field` from its own TBB workers — measured below. The
// lambda must be thread-safe in exchange; pass false for one that is not.
val t0 = System.nanoTime()
val solid = Solid.levelSet(bounds, edgeLength = 1.1, parallel = true, field = ::field)
val parallelMs = (System.nanoTime() - t0) / 1e6

val t1 = System.nanoTime()
val sequential = Solid.levelSet(bounds, edgeLength = 1.1, parallel = false, field = ::field)
val sequentialMs = (System.nanoTime() - t1) / 1e6
:::fold prove the two agree, then draw it
println("parallel   ${"%.0f".format(parallelMs)} ms")
println("sequential ${"%.0f".format(sequentialMs)} ms   " +
    "(${"%.2f".format(sequentialMs / parallelMs)}x)")
println()
// Identical inputs must give a bit-identical mesh, or the parallel path is racing. This is the
// real test of the upcall contract, and it is asserted in ManifoldTest too.
println("same triangle count: ${solid.triangleCount == sequential.triangleCount} " +
    "(${solid.triangleCount})")
println("same volume to the last bit: ${solid.volume == sequential.volume}")
println()
println("status ${solid.status}, genus ${solid.genus}, volume ${"%.1f".format(solid.volume)} mm³")
// And Field's own topology code, which shares nothing with Manifold, agrees it is closed.
println("watertight by Field's own reckoning: ${solid.toTriMesh().isWatertight()}")

// Plain, so showMesh's own footer agrees with the caption — see the note on the Booleans figure.
showMesh(
    solid.toTriMesh(),
    title = "A gyroid-thickened torus, marched from a Kotlin function",
    caption = "${solid.triangleCount} triangles · genus ${solid.genus} · guaranteed closed",
    view = MeshView(yawDegrees = 24.0, pitchDegrees = 52.0, width = 720, height = 440),
)
sequential.close()
solid.close()
```

Measured on this machine, a unit sphere at `edgeLength = 0.04` (465k samples, 40536 triangles out):

| Configuration | wall clock | threads that entered the JVM |
|---|---|---|
| `parallel = true` | 82 ms | 10 |
| `parallel = false` | 151 ms | 1 |

1.84×, from a Kotlin closure invoked concurrently by nine threads the JVM never created. Those
threads, and the C++ frames between them and your code, have two consequences:

- **An exception cannot escape.** There are C++ frames between the lambda and the caller; unwinding
  through them is undefined, and FFM's response to an escaping exception is to abort the VM. So the
  bridge catches it, returns a far-outside sentinel, and `levelSet` rethrows once the native call is
  over: you get an `IllegalStateException` with your own exception as its cause.
- **Your lambda must be thread-safe.** `Solid.levelSetOf`, which takes one of Field's own
  `ScalarField3D`s, therefore defaults to `parallel = false`: a `ScalarField3D` takes a *mutable*
  `Vec3` and the combinators in that family are free to reuse it.

`Solid.warped { }` is the same mechanism for moving every vertex: a bend, a twist, a taper. It does
**not** re-mesh, so put `refined()` in front of it or a 12-triangle cube's eight corners move and
nothing looks different.

## Back down to 2D: slicing

`slice(z)` is a real cross section, not a silhouette — the primitive under every fabrication path.
Each layer is a `Section`, so it offsets, which is how a layer outline becomes a toolpath.

```diffstatic_runner {data-lang=kotlin screenshot="assets/manifold/slices.png" alt="three cards, each a plan view of the same part: an outer circle, a concentric bore, and twelve radial spokes between them, the six webs read end-on. The first, '11 layers superimposed in plan', is drawn in blue and captioned 'blue = each slice's outline'; because the webs exist only in the middle band, the spokes are drawn by fewer layers than the two circles are. The second, 'the same layers offset inward by 0.6mm', is the same figure in orange, captioned 'orange = where the nozzle centre may go', with every corner arrived at as an arc. The third, 'both together', superimposes the two, and the orange sits a constant step into the material everywhere: inside the outer circle, outside the bore, and outside each spoke, with its corners rounded where the blue ones are sharp" wait="6" capture="box" open-outputs="true" padding="20" requires="manifold" name="slices" embed=true}
:::static
import afield.graphics.FLine
import afield.manifold.*

// A part with internal structure, so the slices have something to say.
val part = Solid.scope {
    val body = Solid.cylinder(30.0, 18.0, segments = 96, center = true)
    val bore = Solid.cylinder(40.0, 7.0, segments = 64, center = true)
    val webs = (0 until 6).map {
        Solid.cube(30.0, 3.0, 40.0, center = true).rotated(0.0, 0.0, it * 30.0)
    }
    ((body - bore) - Solid.union(webs).let { w ->
        // Keep the webs only in the middle band, so the top and bottom slices differ from the rest.
        w and Solid.cylinder(14.0, 20.0, segments = 64, center = true)
    }).copy()
}

val nozzle = 1.2
val outlines = mutableListOf<FLine>()
val paths = mutableListOf<FLine>()
var contours = 0
for (i in 0 until 11) {
    val z = -14.0 + 28.0 * i / 10.0
    Solid.scope {
        val layer = part.slice(z)
        contours += layer.contourCount.toInt()
        outlines += layer.toFLines(z = z).onEach {
            it.color = vec(0.55, 0.78, 0.95, 0.5); it.thickness = 1.0
        }
        paths += layer.offset(-nozzle / 2, JoinType.ROUND, circularSegments = 32)
            .toFLines(z = z).onEach { it.color = vec(1.0, 0.72, 0.35, 0.9); it.thickness = 1.7 }
        Unit
    }
}
:::fold and the projection, which is the union of every slice
println("genus ${part.genus}, volume ${"%.1f".format(part.volume)} mm³")
println("11 layers, $contours contours in total")
println("orange = where a ${nozzle}mm nozzle centre may go")
println()
// project() is the shadow of the whole solid — the union of every slice, in one call.
part.project().use { shadow ->
    println("projected footprint ${"%.2f".format(shadow.area)} mm² in ${shadow.contourCount} contours")
}
part.slice(0.0).use { mid ->
    println("the mid slice alone is ${"%.2f".format(mid.area)} mm² in ${mid.contourCount} contours")
}

// These are plan views: all 11 layers superimposed, since showPlaneGrid projects to 2D. The webs
// only exist in the middle band, so the 6 layers outside it contribute the plain annulus you can
// see through the spokes.
showPlaneGrid(
    "11 layers superimposed in plan\nblue = each slice's outline" to outlines,
    "the same layers offset inward by ${nozzle / 2}mm\norange = where the nozzle centre may go" to paths,
    "both together" to (outlines + paths),
    columns = 3,
    cellWidth = 330,
)
part.close()
```

## The bridges, both ways

Nothing here replaces Field's mesh family; the two compose. Manifold does the one thing it is
world-class at and hands the surface back.

```diffstatic {data-lang=kotlin}
// In: a TriMesh from anywhere — a generator, a file, a march, a scan.
val scan = triMeshFromObj(File("scan.obj")).toSolid(weldTolerance = 1e-6)
val trimmed = scan.trimByPlane(0.0, 0.0, 1.0, 0.0)      // keep the half the normal points at

// Out: back into everything Field already does with a mesh.
val mesh = trimmed.toTriMesh()
display(mesh)                                            // the inline mesh card
display(mesh.toFLines())                                 // as a wireframe FLine
mesh.loopSubdivide().saveStl(File("part.stl"))

// And 2D, from anything you drew — here a type outline, swollen by a true offset.
val letters = layoutText("Fi", typeface("Helvetica", 96.0)).toFLines()
val swollen = Section.ofFLines(letters, sampleDistance = 0.5).offset(3.0, JoinType.ROUND)
display(swollen.toFLines())
```

`TriMesh`'s positions are `Float` and Manifold's `MeshGL` is `float32`, so going **in** is exact and
coming **out** narrows Manifold's internal doubles to precisely the precision `TriMesh` stores anyway.

### Two forms of `toTriMesh`

`toTriMesh(normals = true)` is what you want for a picture and *not* what you want for a measurement,
because a vertex on a hard edge needs a different normal per face and therefore cannot be shared:

| Conversion | vertices | `isWatertight()` | `signedVolume()` |
|---|---|---|---|
| `cube.toTriMesh()` | 8 | `true` | `8.0` |
| `cube.toTriMesh(normals = true)` | **24** | **`false`** | `8.0` |

Identical geometry, different *index* topology — and Field's `isWatertight()` and `genus()` read
indices. A crease-split mesh therefore reports the wrong genus and looks exactly like a broken bridge.
Ask for the plain form when you are measuring, the shaded form when you are drawing, and `weld()` if
you need to put a shaded one back together.

The figures above use the plain form, and the reason applies to your own: `showMesh` prints its own
topology footer, so handing it a crease-split mesh puts *"χ=−4 genus 0 · open (42 boundary loop(s))"*
on the line immediately above a caption reading *"genus 5 · guaranteed closed"*. Both lines are true —
of different meshes. Field's own `withVertexNormals()` splits at creases too, so this is a property
of crease normals rather than of this binding.

## Lifetime

A `Solid` owns memory the JVM heap does not account for, so it is `AutoCloseable` — and because a
live-coding box is the last place anyone wants `try`/`finally`, it is *also* registered with a
`Cleaner`. Abandon one and it is released when the JVM collects it; `close()` it and the release is
immediate and the object refuses further use rather than reading freed memory.

For a box that runs every frame, `Solid.scope { }` is the answer: it closes everything built inside it
and keeps whatever came back, including solids nested in a returned `List` or `Pair`.

```diffstatic {data-lang=kotlin}
val part = Solid.scope {
    val body = Solid.cube(2.0, 2.0, 1.0, center = true)
    val bore = Solid.cylinder(4.0, 0.3, center = true)
    body - bore                      // kept; body and bore are released here
}
```

## How the binding is built

Manifold ships a **C** binding library beside its C++ one (`libmanifoldc`, from `manifoldc.h`), and
Homebrew's bottle contains it. That is what makes this binding small, and it is the whole argument for
FFM over JNI, because the alternative needs a compiled shim per platform and this needs nothing:

<div class="field-data-table" markdown>

| Property | Ceres, the other native solver here | Manifold |
|---|---|---|
| upstream API | C++ templates only | C++ **and** a C ABI |
| so Field needs | a hand-written C++ wrapper (`native/ken-ceres`) | nothing |
| build step | `./gradlew buildCeresRedistributable`, minutes | `brew install manifold`, seconds |
| ABI drift guarded by | a version string we invented | upstream's own SO version |

</div>

Three shapes of call, and what each one asks for:

1. **Plain downcalls.** `size_t` is `JAVA_LONG`, `int` is `JAVA_INT`, every pointer is `ADDRESS`.
   Handles are looked up eagerly, so a library that is present but too old fails on first touch,
   naming the missing symbol, rather than mid-pipeline.
2. **Structs returned by value.** `manifold_box_min` returns three doubles, which AArch64 returns in
   `v0..v2`. FFM unpacks that from a `StructLayout` with no help; what it wants in exchange is a
   `SegmentAllocator` as an extra leading argument.
3. **Upcalls**, described above — a thread-safe Kotlin lambda, and a sentinel to carry an exception
   back across the C++ frames.

Memory is the library's own `malloc`, via its `manifold_alloc_*` / `manifold_delete_*` pair, rather
than an FFM `Arena`. A confined `Arena` is thread-confined and a `Solid` built in a box is routinely
read from the runloop thread; a shared one costs a thread handshake to close. `malloc` has neither
problem, and the `Cleaner` sits behind it.

??? aside "If you are writing FFM descriptors yourself"
    Nothing checks them. A `size_t` declared as an `int`, a struct return declared as a pointer, an
    argument in the wrong position — all of them compile, link, and return *something*. The failure
    mode is a plausible wrong number, not an error.

    Two places in this API where `manifoldc.h` is the only warning. `manifold_slice` and
    `manifold_project` return `ManifoldPolygons*`, **not** `ManifoldCrossSection*`; since every
    constructor here builds into memory the caller allocated, handing `slice` a cross-section-sized
    buffer constructs the wrong object in it, and the first read aborts the process inside libc++
    with `mutex lock failed` and no Java stack at all. And `manifold_ray_cast` reports its hit
    distance as a **fraction of the origin-to-end segment**, so a hit 4 units away on a ray of
    length `1e4` arrives as `4.0e-4`.

## Installing it

Manifold is **optional**. A clone of Field builds, tests and runs without it; only boxes that reach for
`Solid` need it, and each one says so when it is missing.

```diffstatic {data-lang=bash}
brew install manifold
```

On Linux, your package manager or a source build with `-DMANIFOLD_CBIND=ON`. The binding then finds
`libmanifoldc` by itself, or takes `-Dafield.manifold.library=<path>` or `AFIELD_MANIFOLD_LIBRARY` if
you built your own. `ManifoldLibrary.describe()` reports what was found, and
`ManifoldLibrary.isAvailable` is cheap enough to branch on in a box.

Verify it end to end with the smoke. It checks every volume, area, genus and offset on this page
against its closed form, prints how many checks it ran, and skips cleanly with exit code 0 if the
library is absent:

```diffstatic {data-lang=bash}
./gradlew smokeManifold
```
