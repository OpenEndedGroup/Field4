# Solids that cannot fail: Manifold in Field

Build a part from solid shapes, subtract holes, measure it and slice it into layers. Field's
[Manifold](https://github.com/elalish/manifold) binding gives you `Solid` for 3D work and `Section`
for 2D outlines. Its Boolean operations keep the result a closed manifold, including difficult
cases such as coincident faces.

The [manifold_solids](agent_transcripts.md) starting point has the examples below ready to run.
We'll begin with a plate and drill four bores through it:

```diffstatic {data-lang=kotlin}
val plate = Solid.cube(60.0, 40.0, 8.0, center = true)
val bore = Solid.cylinder(30.0, 3.0, segments = 64, center = true)
val part = plate - Solid.union((0..3).map { bore.translated(it * 15.0 - 22.5, 0.0, 0.0) })

part.volume // 18296.674 — measured, not estimated
part.genus // 4 — you can count the holes
part.status // NO_ERROR — and it says so when it is not
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
| the clearance between two of them | `minGap(other, searchLength)`; for one in motion, [`sweptClearance`](swept_clearance.md) | — |
| whether it worked | `status`, `isEmpty` | `isEmpty` |

</div>

Two of those will read oddly the first time. `triangleCount` on a cube is `12`, the minimal closed
mesh rather than the 24 a per-face mesh would have; and `genus` counts holes through the solid, so
the four-bore plate above is genus 4.

## Booleans

Use `Solid.union(list)` to combine a batch: Manifold can plan the operations together.
`Solid.scope { }` releases intermediate solids when the block ends.

```diffstatic_runner {data-lang=kotlin screenshot="assets/manifold/booleans.png" alt="an output panel headed 'Four bored bosses and a rounded slot, from eleven Booleans', holding a flat-shaded grey triangle mesh of a rectangular plate seen in perspective. A large rectangular window is cut through the middle, a rounded boss stands at each of the four corners with a cylindrical bore through it, and a rounded slot runs along one side. The corner header reads 3312 tri; the footer reads 1648 verts, 3312 tris, extent 60.000 x 40.000 x 14.000, chi=-8 genus 5, watertight, volume 15420.9314, and beneath it 'genus 5 - 3312 triangles - every step guaranteed closed'" wait="4" capture="box" open-outputs="true" padding="20" requires="manifold" name="booleans" embed=true}
:::static
import afield.geometry.solid.manifold.*

val part = Solid.scope {
    val plate = Solid.cube(60.0, 40.0, 8.0, center = true)
    val corners = [-20.0 to -12.0, 20.0 to -12.0, -20.0 to 12.0, 20.0 to 12.0]

    // Bosses standing on the plate, and a bore straight through each.
    val bosses = corners.map { (x, y) ->
        Solid.cylinder(14.0, 6.0, segments = 64, center = true).translated(x, y, 0.0)
    }
    val bores = corners.map { (x, y) ->
        Solid.cylinder(30.0, 3.0, segments = 64, center = true).translated(x, y, 0.0)
    }

    // A slot with properly rounded ends: that is a 2D offset, extruded. There is no correct way to
    // get those end caps by moving points around.
    val slot =
        Section.square(26.0, 9.0, center = true)
            .offset(4.5, JoinType.ROUND, circularSegments = 64)
            .extruded(30.0)
            .translated(0.0, 0.0, -15.0)

    Solid.union([plate] + bosses) - Solid.union(bores + slot)
}
:::fold measure it — including the wall thickness that decides whether it survives being cut
// The plain mesh for measuring, because `normals = true` splits vertices at every crease and
// Field's isWatertight()/genus() read indices. See "Two meshes" below.
val mesh = part.toTriMesh()
println("volume  ${part.volume.f3} mm³  (${(part.volume * 0.0027).f1} g in 6061)")
println("area    ${part.surfaceArea.f3} mm²")
println("genus   ${part.genus}   — four bores and the slot")
println("status  ${part.status}")
println()
println("Field's own mesh family, which shares no code with Manifold, on the same surface:")
println(
    "  watertight ${mesh.isWatertight()},  genus ${mesh.genus()},  " + "volume ${mesh.signedVolume().f3}",
)

// A clearance check. This is the number a drawing would call out, and `minGap` is how you get it.
val nearestBore = Solid.cylinder(30.0, 3.0, segments = 64, center = true).translated(-20.0, -12.0, 0.0)
val slotSolid =
    Section.square(26.0, 9.0, center = true)
        .offset(4.5, JoinType.ROUND, circularSegments = 64)
        .extruded(30.0)
        .translated(0.0, 0.0, -15.0)
println()
println(
    "wall between the slot and the nearest bore: " + "${slotSolid.minGap(nearestBore, searchLength = 30.0).f3} mm",
)

/*
The plain mesh, not the shaded one: showMesh prints its own topology footer, and a crease-split
mesh would make that footer read "open (42 boundary loops)" directly under a caption claiming the
solid is closed. Both statements would be true of different meshes, which is worse than either.
*/
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
twenty checks; the cost is that **an unchecked pipeline can end in silence**. Either make the
pipeline throw, or check the status yourself:

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

Two calls, both in `afield.geometry.mesh`, both documented at length under
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

`repaired()` is the right call for a mesh whose only problem is unwelded vertices. It does not fill
holes, so it rescues far fewer broken meshes than `repairToSolid()`. A repaired mesh is a valid
solid, but not always the shape you started with; [geometry from disk](geometry_from_disk.md) shows
the difference.

`toSolid()` also refuses an inside-out mesh, one closed and consistently wound but inward, which
Manifold itself would accept with a negative volume; `fixWinding()` flips it. Zero-area triangles,
pinch vertices and disjoint components pass, and the volumes of disjoint pieces add up.

`NON_FINITE_VERTEX` is a NaN in the positions, usually a degenerate normal upstream.
`repairToSolid()` refuses that one outright rather than repairing around it — dropping the affected
triangles and capping the gap would produce a plausible solid missing an unknown amount of the model.

`TriMesh.isValidSolid()` is the cheap pre-flight, and `toSolid()` checks by default. A mesh read
from a file goes the same way: [Geometry from disk](geometry_from_disk.md) for STL, OBJ and glTF,
[Rhino files](rhino_files.md) for the `TriMesh` inside each `RhinoMesh` of a `.3dm`. A Brep
or SubD with no cached mesh is tessellated with its shared edges stitched, so a closed one comes
back with no open edges.

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
import afield.geometry.solid.manifold.*

// A plate with a hole and a notch — concave and convex corners, so the join types have somewhere
// to show themselves.
val outline = Solid.scope {
    (Section.square(40.0, 26.0, center = true) -
            Section.circle(5.0, segments = 64).translated(-11.0, 0.0) -
            Section.square(10.0, 8.0, center = true).translated(20.0, 0.0))
        .copy()
}

// Each join type on the same shape, and the areas they come out at.
val joins = JoinType.entries.map { it to outline.offset(2.5, it, circularSegments = 128) }

// A wall of constant thickness, following every corner: the shape minus itself eroded.
val wall = outline - outline.offset(-3.0, JoinType.ROUND, circularSegments = 128)

// And an opening on the real outline, so the corner rounding is visible rather than only measured.
val opened2 =
    outline.offset(-2.5, JoinType.ROUND, circularSegments = 128).offset(2.5, JoinType.ROUND, circularSegments = 128)
:::fold the numbers, then draw them
println("outline area ${outline.area.f4}, ${outline.contourCount} contours")
joins.forEach { (join, grown) ->
    println(
        "  offset +2.5 ${join.name.padEnd(6)} -> area ${grown.area.f4}, " + "${grown.vertexCount} verts",
    )
}
println()
println("a 3mm wall is ${wall.area.f4} mm², in ${wall.contourCount} contours")

/*
A closing (dilate then erode) is EXACTLY the identity on a convex region — the two offsets are
true inverses where nothing was rounded away. An opening (erode then dilate) is not, and the
difference is precisely the corners the erosion cut off.
*/
val square = Section.square(2.0, 2.0, center = true)
val closed =
    square.offset(0.5, JoinType.ROUND, circularSegments = 512).offset(-0.5, JoinType.ROUND, circularSegments = 512)
val opened =
    square.offset(-0.5, JoinType.ROUND, circularSegments = 512).offset(0.5, JoinType.ROUND, circularSegments = 512)
println()
println("closing a 2x2 square: ${closed.area.f6} (the identity, 4.0)")
println(
    "opening it:           ${opened.area.f6} (4 - (4-pi)/4 = " + "${(4.0 - (4.0 - Math.PI) * 0.25).f6})",
)

fun white() = outline.toFLines().onEach {
    it.color = vec(1.0, 1.0, 1.0, 0.95)
    it.thickness = 2.2
}

showPlaneGrid(
    *joins
        .map { (join, grown) ->
            "offset +2.5 with ${join.name}\narea ${grown.area.f4}, ${grown.vertexCount} vertices" to
                (grown.toFLines().onEach {
                    it.color = vec(0.45, 0.85, 1.0, 0.9)
                    it.thickness = 1.8
                } + white())
        }
        .toTypedArray(),
    "a 3mm wall, following every corner\noutline - outline.offset(-3.0)\n" +
        "${wall.area.f4} mm² in ${wall.contourCount} contours" to
        (wall.toFLines().onEach {
            it.color = vec(1.0, 0.72, 0.35, 0.85)
            it.filled = true
            it.stroked = false
        } + white()),
    "an opening — offset(-2.5) then offset(+2.5)\nthe corners do not come back sharp" to
        (opened2.toFLines().onEach {
            it.color = vec(0.7, 1.0, 0.6, 0.9)
            it.thickness = 1.8
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
hole. Manifold's `FillRule` is a superset of the two rules the rest of Field uses
([Overlaps and fill rules](afield_four_graphics.md#overlaps-and-fill-rules)): `POSITIVE` and `NEGATIVE`
count the sign of the winding, which only a solid boolean needs. Pass a line's own rule with
`Section.ofFLines(lines, rule = line.fillRule)`, or convert one with `toManifold()`.

[A Penrose relief](debruijn_tilings.md#a-relief-through-manifold) passes every rhombus of a tiling
through `Section.ofFLines`, insets it with `offset`, and extrudes and unions the results into one
printable solid.

## Previewing a ball-end mill

A ball-end mill cannot cut a sharp inside corner. Wherever two faces meet in a concave edge, the
ball leaves a fillet of its own radius; a pocket corner comes out rounded; a slot narrower than the
ball is never cut at all. Convex edges are untouched. `ballMilled(radius)` gives you the part as the
mill would leave it, as a `Solid`, so it can be measured, drawn, and compared with the design to see
exactly what the tool adds:

```diffstatic {data-lang=kotlin}
val milled = part.ballMilled(1.5) // a 3 mm ball, from any direction
val fillets = milled.beyond(part) // the material the mill leaves that the design does not have
```

With no directions, the ball may approach from anywhere, the way an idealised five-axis machine
would. A real 3-axis job cuts from one side at a time. Name the tool axis of each setup with `from`,
pointing from the part towards the spindle, and anything the part overhangs as seen from that side
stays solid, as does the stock underneath it. Pass two opposite axes for a part that is milled, then
turned over:

```diffstatic_runner {data-lang=kotlin screenshot="assets/manifold/ball_milled.png" alt="four wide cards stacked, each the same cross-section of a stepped plate drawn as a solid light-blue silhouette with the design's outline in white over it. From the left the plate has a two-step rebate, a narrow slot down from the top, a pocket in its underside and a wide pocket in its top. 'as designed, sectioned at z = 20' is all sharp corners. 'a 3 mm ball from any direction, adds 361.2 mm³' fills every inside corner with a small rounded fillet, fills the narrow slot except for a shallow round dip at its mouth, and rounds the corners of both pockets. 'from above only, adds 1325.4 mm³' is the same except that the underside pocket is filled solid. 'from above, then turned over, adds 361.2 mm³' matches the any-direction card" wait="6" capture="box" open-outputs="true" padding="20" requires="manifold" name="ball_milled" embed=true}
:::static
import afield.geometry.solid.manifold.*

// A plate with a two-step rebate down its left edge, a slot narrower than the ball, a pocket in
// its top and a pocket in its underside. All four cross the plane z = 20, where they are drawn.
val part = Solid.scope {
    val plate = Solid.cube(60.0, 10.0, 40.0)
    val cuts = [
        Solid.cube(12.0, 5.0, 40.0).translated(0.0, 5.0, 0.0),
        Solid.cube(6.0, 2.5, 40.0).translated(0.0, 2.5, 0.0),
        Solid.cube(2.0, 4.0, 30.0).translated(17.0, 6.0, 5.0),
        Solid.cube(14.0, 3.0, 24.0).translated(22.0, 0.0, 8.0),
        Solid.cube(18.0, 4.0, 24.0).translated(38.0, 6.0, 8.0),
    ]
    (plate - Solid.union(cuts)).copy()
}

val ball = 1.5
val up = vec(0, 1, 0)
val down = vec(0, -1, 0)
val t0 = System.nanoTime()
val anyDirection = part.ballMilled(ball)
val fromAbove = part.ballMilled(ball, from = [up])
val turnedOver = part.ballMilled(ball, from = [up, down])
val seconds = (System.nanoTime() - t0) / 1e9
:::fold measure what each one adds, then draw them
println("three previews in ${seconds.f1} s")
for ((name, milled) in ["any direction" to anyDirection, "from above" to fromAbove, "turned over" to turnedOver]) {
    println("$name: the mill leaves ${(milled.volume - part.volume).f1} mm³ the design does not have")
}

// Each version cut through z = 20: the milled section filled, the design's outline in white over it.
val designed = part.slice(20.0)

fun outline() = designed.toFLines().onEach {
    it.color = vec(1.0, 1.0, 1.0, 0.95)
    it.thickness = 1.6
}

fun section(milled: Solid) = milled.slice(20.0).toFLines().onEach {
    it.color = vec(0.45, 0.85, 1.0, 0.85)
    it.filled = true
    it.stroked = false
} + outline()

showPlaneGrid(
    "as designed, sectioned at z = 20" to section(part),
    "a 3 mm ball from any direction\nadds ${(anyDirection.volume - part.volume).f1} mm³" to section(anyDirection),
    "from above only\nadds ${(fromAbove.volume - part.volume).f1} mm³" to section(fromAbove),
    "from above, then turned over\nadds ${(turnedOver.volume - part.volume).f1} mm³" to section(turnedOver),
    columns = 1,
    cellWidth = 720,
    cellHeight = 200,
    yUp = true,
)
```

From above only, the underside pocket never opens: the tool cannot reach it, and the stock under
the part stays. Turned over, the pocket is cut, with its own inside corners filleted. In every
version the 2 mm slot stays filled, apart from the shallow dip the ball makes in its mouth, because a
3 mm ball does not fit into it, and the rebate's two inside corners come out rounded.

`ballMilled` is the morphological closing of the part by the tool, and `closedBy(tool)` does the same
for any tool shape you build as a `Solid`. The ball is Manifold's faceted `Solid.sphere`, so the
fillets are faceted too, and very slightly tighter than the radius: by at most
`radius * (1 - cos(π / segments))`, about 2% at the default of 16 segments. Raising `segments`
costs time steeply, because Manifold's Minkowski operations on a non-convex part cost roughly one
convex hull per boundary triangle.

To see only the fillets, use `milled.beyond(part)` rather than `milled - part`. The Minkowski steps
re-derive every face of the part, which leaves zero-thickness sheets lying on its flat faces. The
plain difference keeps them, and they draw as stray patches; `beyond` removes them.

## Solids from a field

`Solid.levelSet` takes a Kotlin lambda of `x, y, z` and marches it into a solid. Manifold calls the
lambda itself, once per sample, from the calling thread.

The difference from Field's own [`marchingCubes`](mesh_examples.md) is not resolution or speed, it is
the guarantee: whatever the field did (a sheet thinner than the grid, a surface that touches itself, a
feature below the cell size), **the output is a valid closed manifold**, and therefore something you
can immediately subtract from.

```diffstatic_runner {data-lang=kotlin screenshot="assets/manifold/levelset.png" alt="an output panel headed 'A gyroid-thickened torus, marched from a Kotlin function', holding a grey triangulated ring lying at an angle, its hole still open in the middle. The surface is not a smooth tube: it rises and falls in deep rounded lobes all the way round, the gyroid showing through as thickening and thinning rather than as a pattern on the surface. The triangles are fine and even enough to read as a mesh. The footer reads 14869 verts, 29738 tris, extent 59.498 x 59.498 x 15.870, chi=0 genus 1, watertight, volume 17750.1245" wait="8" capture="box" open-outputs="true" padding="20" requires="manifold" name="levelset" embed=true}
:::static
import afield.geometry.solid.manifold.*

/*
A gyroid-thickened torus: a shape that only really exists as a function, and exactly the sort of
field where plain marching cubes produces a soup wherever the surface nearly touches itself.
Positive inside, which is Manifold's convention (Field's own sdf* family is negative inside —
Solid.levelSetOf flips the sign for you).
*/
fun field(x: Double, y: Double, z: Double): Double {
    val q = hypot(x, y) - 22.0
    val tube = 8.0 - sqrt(q * q + z * z)
    val s = 0.32
    val g = sin(s * x) * cos(s * y) + sin(s * y) * cos(s * z) + sin(s * z) * cos(s * x)
    return tube - 0.55 * 8.0 * abs(g) * 0.5
}

val bounds = boundsOf(vec(-32.0, -32.0, -10.0), vec(32.0, 32.0, 10.0))

val t0 = System.nanoTime()
val solid = Solid.levelSet(bounds, edgeLength = 1.1, field = ::field)
val marchMs = (System.nanoTime() - t0) / 1e6

// March the same field again and compare triangle count and volume below.
// These aggregate checks do not establish identical vertex/index arrays.
val again = Solid.levelSet(bounds, edgeLength = 1.1, field = ::field)
:::fold check it is reproducible, then draw it
println("level set ${marchMs.f0} ms")
println(
    "same triangle count twice: ${solid.triangleCount == again.triangleCount} " + "(${solid.triangleCount})",
)
println("same volume to the last bit: ${solid.volume == again.volume}")
println()
println("status ${solid.status}, genus ${solid.genus}, volume ${solid.volume.f1} mm³")
// And Field's own topology code, which shares nothing with Manifold, agrees it is closed.
println("watertight by Field's own reckoning: ${solid.toTriMesh().isWatertight()}")

// Plain, so showMesh's own footer agrees with the caption — see the note on the Booleans figure.
showMesh(
    solid.toTriMesh(),
    title = "A gyroid-thickened torus, marched from a Kotlin function",
    caption = "${solid.triangleCount} triangles · genus ${solid.genus} · guaranteed closed",
    view = MeshView(yawDegrees = 24.0, pitchDegrees = 52.0, width = 720, height = 440),
)
again.close()
solid.close()
```

An exception thrown by the lambda cannot escape. There are C++ frames between the lambda and the
caller; unwinding through them is undefined, and FFM's response to an escaping exception is to abort the VM. So the bridge catches it, returns a
far-outside sentinel, and `levelSet` rethrows once the native call is over: you get an
`IllegalStateException` with your own exception as its cause.

`Solid.warped { }` is the same mechanism for moving every vertex: a bend, a twist, a taper. A warp
moves only the vertices there are, so give it a length, `warped(maxEdge = 0.1) { }`, and it
subdivides until no edge is longer than that before it moves anything.

An image can be the field too: [A photograph, closed and mounted](chain_photo_to_solid.md) marches
a depth map as a level set and compares the result with a relief mesh built directly from the same
image.

## Backing off some faces: clearance between parts

Parts designed to meet exactly have no room to go together. `insetFaces(distance, except = [...])`
moves every face of a solid inward by `distance`, except the faces parallel to the planes whose
normals you name, which stay exactly where they are. Keep the faces parallel to a panel and move the
rest, and panels that met along their edges get a gap between them without getting any thinner:

```diffstatic_runner {data-lang=kotlin screenshot="assets/manifold/inset_faces.png" alt="two wide cards stacked, each a cross-section of two panels joined by a lap, the left panel in light blue and the right in orange, each the full thickness except for a tongue half as thick where they overlap: the blue tongue runs under the orange one. 'as designed, sectioned at z = 15' shows them meeting with no gap anywhere. 'the faces that are not parallel to the panels backed off 0.6 mm' shows the same two panels, the same thickness, still resting on each other along the horizontal step between the tongues, but with a narrow dark vertical gap at each of the two risers where they met side on" wait="4" capture="box" open-outputs="true" padding="20" requires="manifold" name="inset_faces" embed=true}
:::static
import afield.geometry.solid.manifold.*

// Two 10 mm panels joined by a lap: each has a tongue half as thick, and a's runs under b's. They
// touch on the step between the tongues and at both risers.
val a = Solid.cube(60.0, 10.0, 30.0) - Solid.cube(15.0, 5.0, 30.0).translated(45.0, 5.0, 0.0)
val b = Solid.cube(60.0, 10.0, 30.0).translated(45.0, 0.0, 0.0) - Solid.cube(15.0, 5.0, 30.0).translated(45.0, 0.0, 0.0)

// Keep the faces parallel to the panels; back the others off by 0.6 mm.
val up = vec(0, 1, 0)
val clearA = a.insetFaces(0.6, except = [up])
val clearB = b.insetFaces(0.6, except = [up])
:::fold measure the gap, then draw a section through both panels
println("as designed: closest ${a.minGap(b, 5.0).f3} mm, thickness ${a.bounds.size.y.f3} mm")
println("backed off:  closest ${clearA.minGap(clearB, 5.0).f3} mm, thickness ${clearA.bounds.size.y.f3} mm")
// Lift b clear of the step to see the gap the risers now have.
println("at the risers: ${clearA.minGap(clearB.translated(0.0, 2.0, 0.0), 5.0).f3} mm once b is lifted 2 mm")

fun section(part: Solid, colour: Vec4) = part.slice(15.0).toFLines().onEach {
    it.color = colour
    it.filled = true
    it.stroked = false
}

val blue = vec(0.45, 0.85, 1.0, 0.9)
val orange = vec(1.0, 0.62, 0.3, 0.9)
showPlaneGrid(
    "as designed, sectioned at z = 15" to (section(a, blue) + section(b, orange)),
    "the faces that are not parallel to the panels backed off 0.6 mm" to
        (section(clearA, blue) + section(clearB, orange)),
    columns = 1,
    cellWidth = 720,
    cellHeight = 200,
    yUp = true,
)
```

The step the tongues bear on is parallel to the panels, so it stays put and they still rest on each
other there. Every riser and end face moved back 0.6 mm, so the two panels are 1.2 mm apart wherever
they met side on. A face counts as parallel when its normal is within `toleranceDegrees` (default 1°)
of one of the planes, in either direction, so one normal keeps both a panel's top and its bottom.

To keep only the faces that lie *in* a plane, rather than every face parallel to it, name each plane
by a point on it and its normal. `a.insetFaces(0.6, exceptOn = [FacePlane(vec(0, 5, 0), up)])` keeps
just the step at y = 5, whichever side of it the material is on, and moves a's top and bottom in with
the rest. A face is on the plane when it is parallel within `toleranceDegrees` and within
`toleranceDistance` of the point, a tenth of the inset by default. For any other rule, pass a
function of each face's plane, its outward normal and offset as `[x, y, z, c]`:
`part.insetFaces(0.6) { n -> abs(n[1]) < 0.99 }`.

Corners stay sharp: every vertex goes to where its planes meet once the moved ones have shifted, the
same construction as `Section.offset` with `JoinType.MITER`, and a kept face grows or shrinks to meet
the faces beside it. Where more than three planes meet at a point and stop meeting there once some
move, the point opens into an edge: move one side of a pyramid and its apex becomes a short ridge
where the other three planes meet the moved one. `distance` has to be smaller than the part's
narrowest feature across a moved face. Two pieces of one part that only meet along an edge come apart, since no inset can keep an edge
contact.

A part built as a union of cells that meet face to face can come back from `Solid.union` as several
pieces: Manifold keeps pieces that only touch apart, with a zero-width crack between them.
`insetFaces` calls `fused()` first, which fills each crack where its two walls overlap, so a crack
stays closed instead of opening into a slot. `part.fused().decompose().size` tells you how many
pieces the material really makes.

## Back down to 2D: slicing

`slice(z)` is a real cross section, not a silhouette — the primitive under every fabrication path.
Each layer is a `Section`, so it offsets, which is how a layer outline becomes a toolpath.

```diffstatic_runner {data-lang=kotlin screenshot="assets/manifold/slices.png" alt="three cards, each a plan view of the same part: an outer circle, a concentric bore, and twelve radial spokes between them, the six webs read end-on. The first, '11 layers superimposed in plan', is drawn in blue and captioned 'blue = each slice's outline'; because the webs exist only in the middle band, the spokes are drawn by fewer layers than the two circles are. The second, 'the same layers offset inward by 0.6mm', is the same figure in orange, captioned 'orange = where the nozzle centre may go', with every corner arrived at as an arc. The third, 'both together', superimposes the two, and the orange sits a constant step into the material everywhere: inside the outer circle, outside the bore, and outside each spoke, with its corners rounded where the blue ones are sharp" wait="6" capture="box" open-outputs="true" padding="20" requires="manifold" name="slices" embed=true}
:::static
import afield.geometry.solid.manifold.*

// A part with internal structure, so the slices have something to say.
val part = Solid.scope {
    val body = Solid.cylinder(30.0, 18.0, segments = 96, center = true)
    val bore = Solid.cylinder(40.0, 7.0, segments = 64, center = true)
    val webs = (0 until 6).map {
        Solid.cube(30.0, 3.0, 40.0, center = true).rotated(0.0, 0.0, it * 30.0)
    }
    ((body - bore) -
            Solid.union(webs).let { w ->
                // Keep the webs only in the middle band, so the top and bottom slices differ from the rest.
                w and Solid.cylinder(14.0, 20.0, segments = 64, center = true)
            })
        .copy()
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
        outlines +=
            layer.toFLines(z = z).onEach {
                it.color = vec(0.55, 0.78, 0.95, 0.5)
                it.thickness = 1.0
            }
        paths +=
            layer.offset(-nozzle / 2, JoinType.ROUND, circularSegments = 32).toFLines(z = z).onEach {
                it.color = vec(1.0, 0.72, 0.35, 0.9)
                it.thickness = 1.7
            }
        Unit
    }
}
:::fold and the projection, which is the union of every slice
println("genus ${part.genus}, volume ${part.volume.f1} mm³")
println("11 layers, $contours contours in total")
println("orange = where a ${nozzle}mm nozzle centre may go")
println()
// project() is the shadow of the whole solid — the union of every slice, in one call.
part.project().use { shadow ->
    println("projected footprint ${shadow.area.f2} mm² in ${shadow.contourCount} contours")
}
part.slice(0.0).use { mid ->
    println("the mid slice alone is ${mid.area.f2} mm² in ${mid.contourCount} contours")
}

/*
These are plan views: all 11 layers superimposed, since showPlaneGrid projects to 2D. The webs
only exist in the middle band, so the 6 layers outside it contribute the plain annulus you can
see through the spokes.
*/
showPlaneGrid(
    "11 layers superimposed in plan\nblue = each slice's outline" to outlines,
    "the same layers offset inward by ${nozzle / 2}mm\norange = where the nozzle centre may go" to paths,
    "both together" to (outlines + paths),
    columns = 3,
    cellWidth = 330,
)
part.close()
```

[A polytope, canonicalised, and sliced into layers](chain_ceres_manifold_contours.md) slices a
Conway polytope into a stack of layers and checks that their areas add up to the solid's volume.

## The bridges, both ways

Manifold does not replace Field's mesh family; a `Solid` converts to and from a `TriMesh`:

```diffstatic {data-lang=kotlin}
// In: a TriMesh from anywhere — a generator, a file, a march, a scan.
val scan = triMeshFromObj(File("scan.obj")).toSolid(weldTolerance = 1e-6)
val trimmed = scan.trimByPlane(0.0, 0.0, 1.0, 0.0) // keep the half the normal points at

// Out: back into everything Field already does with a mesh.
val mesh = trimmed.toTriMesh()
display(mesh) // the inline mesh card
display(mesh.toFLines()) // as a wireframe FLine
mesh.loopSubdivide().saveStl(File("part.stl"))

// And 2D, from anything you drew — here a type outline, swollen by a true offset.
val letters = layoutText("Fi", typeface("Helvetica", 96.0)).toFLines()
val swollen = Section.ofFLines(letters, sampleDistance = 0.5).offset(3.0, JoinType.ROUND)
display(swollen.toFLines())
```

`TriMesh`'s positions are `Float` and Manifold's `MeshGL` is `float32`, so going **in** is exact and
coming **out** narrows Manifold's internal doubles to precisely the precision `TriMesh` stores anyway.

### Two meshes from `toTriMesh`

`toTriMesh(normals = true)` is the form for a picture, because a vertex on a hard edge needs a
different normal per face and therefore cannot be shared:

| Conversion | vertices | `topology().isWatertight` | `isWatertight()` | `signedVolume()` |
|---|---|---|---|---|
| `cube.toTriMesh()` | 8 | `true` | `true` | `8.0` |
| `cube.toTriMesh(normals = true)` | **24** | **`false`** | `true` | `8.0` |

`isWatertight()`, `genus()` and `showMesh`'s footer weld a mesh that carries normals by position
before measuring, so both forms report the same surface, and `toSolid` welds the same way, so either
form goes back into Manifold. `topology()` reads the indices as they are.

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
    body - bore // kept; body and bore are released here
}
```

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

## Retain precision and inspect finite contacts

Use `solid.toPlanarMesh()` when coordinates must remain double precision. The returned
`SolidPlanarMesh` contains positions, triangle indices and connected polygonal-face
provenance. `mesh.toSolid()` rebuilds a native solid without converting coordinates to
Float32. `mesh.toTriMesh()` is the final conversion for drawing. Face provenance alone
does not establish a crease: adjacent Boolean patches can be coplanar.

`Solid.hullOfPointsD(doubleArrayOf(...))` accepts flat xyz triples at full precision.
`Solid.scope { ... }` also closes its temporary native objects if the block throws.

`planarContact(a, b, plane)` intersects the actual boundary polygons on a specified
plane. It reports a contact only where the finite polygons overlap, not wherever two faces
lie in the same plane. `planarContacts(a, b)` finds those patches without being given a plane.

```diffstatic {data-lang=kotlin}
:::static
import afield.geometry.path.planning.Point3D
import afield.geometry.solid.manifold.*

val area = Solid.scope {
    val a = Solid.cube(10.0).toPlanarMesh()
    val b = Solid.cube(10.0).translated(10.0, 0.0, 0.0).toPlanarMesh()
    val plane = ContactPlane(Point3D(10.0, 0.0, 0.0), Point3D(1.0, 0.0, 0.0))
    planarContact(a, b, plane).area
}
println(area) // 100 square units
```

`auditApertures(...)` compares removed boundary regions on both sides against an
independently supplied expected region. It also reports material outside the contact
and an optional required margin. `GeometryTolerances` separates length, area and volume
thresholds in caller-chosen units. These are geometric comparisons within native
Boolean tolerances. They do not establish mechanical suitability.

## Approximate a cubic with an explicit error bound

`CubicBezier3D` is an immutable cubic over double-precision `Point3D` values.
`valueAt(t)` evaluates its position; `tangentAt(t)` returns its unnormalised derivative.
`flatten(maximumDeviation)` subdivides until each subcurve's control hull lies within
the requested distance of its finite chord, returning a polyline including both endpoints.

```diffstatic {data-lang=kotlin}
:::static
import afield.geometry.path.planning.*

val curve = CubicBezier3D(
    Point3D(10.0, 0.0, 0.0),
    Point3D(10.0, 5.5, 0.0),
    Point3D(5.5, 10.0, 0.0),
    Point3D(0.0, 10.0, 0.0),
)
val polyline = curve.flatten(maximumDeviation = 0.01)
val radiusBound = curve.minimumRadiusBound(minimum = 9.0)
```

Tightening the bound adds vertices only where the curve needs them. The same cubic at three bounds:

```diffstatic_runner {data-lang=kotlin screenshot="assets/manifold/flatten_bounds.png" capture="box" open-outputs="true" wait="1.5" padding="12" embed=true name="manifold-flatten-bounds" alt="The same quarter-turn cubic drawn three times as a polyline with its vertices as orange dots: 5 vertices at maximumDeviation 1.0, 9 at 0.1 and 33 at 0.01, the last indistinguishable from the curve; a line above gives the three counts"}
import afield.geometry.path.planning.*

val curve = CubicBezier3D(
    Point3D(10.0, 0.0, 0.0),
    Point3D(10.0, 5.5, 0.0),
    Point3D(5.5, 10.0, 0.0),
    Point3D(0.0, 10.0, 0.0),
)

// The same quarter-turn flattened at three bounds, side by side, each with its vertices as dots.
val bounds = [1.0, 0.1, 0.01]
val counts = mutableListOf<String>()
val drawn = bounds.flatMapIndexed { i, bound ->
    val points = curve.flatten(maximumDeviation = bound)
    val dx = i * 14.0
    val chord = FLine().apply {
        points.forEachIndexed { k, p -> if (k == 0) moveTo(p.x + dx, p.y) else lineTo(p.x + dx, p.y) }
        color = vec(0.32, 0.82, 1.0, 1.0)
    }
    val dots = chord.copy().apply {
        stroked = false
        pointed = true
        pointSize = 0.9
        color = vec(1.0, 0.5, 0.25, 1.0)
    }
    counts += "maximumDeviation $bound: ${points.size} vertices"
    val pair: List<FLine> = [chord, dots]
    pair
}
display("vertices", counts.joinToString(" · "))
lines(drawn, w = 720.0, h = 260.0, fitToSize = true)
```

`minimumRadiusBound` recursively bounds speed and the derivative cross product using
Bernstein control hulls. It returns a conservative numerical lower bound on radius,
or throws if it cannot establish the requested minimum within its subdivision budget.
Failure does not prove that the curve violates the threshold. Cusps, degenerate chords
and arithmetic overflow are rejected. This uses floating-point arithmetic, not formal
interval certification; the polyline error bound does not prove containment in another solid.

## Internals: how the binding is built

`afield.geometry.solid.manifold` binds Manifold through Java's foreign-function interface (FFM, "Panama"), with no
C++ of its own. Manifold ships a **C** binding library beside its C++ one (`libmanifoldc`, from
`manifoldc.h`), and Homebrew's bottle contains it. That is what makes this binding small, and it is
the whole argument for FFM over JNI, because the alternative needs a compiled shim per platform and
this needs nothing:

<div class="field-data-table" markdown>

| Property | Ceres, the other native solver here | Manifold |
|---|---|---|
| upstream API | C++ templates only | C++ **and** a C ABI |
| so Field needs | a hand-written C++ wrapper (`native/ken-ceres`) | nothing |
| build step | `./gradlew buildCeresRedistributable`, minutes | `brew install manifold`, seconds |
| ABI drift guarded by | a version string we invented | upstream's own SO version |

</div>

The binding makes three shapes of call:

1. **Plain downcalls.** `size_t` is `JAVA_LONG`, `int` is `JAVA_INT`, every pointer is `ADDRESS`.
   Handles are looked up eagerly, so a library that is present but too old fails on first touch,
   naming the missing symbol, rather than mid-pipeline.
2. **Structs returned by value.** `manifold_box_min` returns three doubles, which AArch64 returns in
   `v0..v2`. FFM unpacks that from a `StructLayout` with no help; what it wants in exchange is a
   `SegmentAllocator` as an extra leading argument.
3. **Upcalls**, as in [Solids from a field](#solids-from-a-field): a thread-safe Kotlin lambda,
   and a sentinel to carry an exception back across the C++ frames.

Memory is the library's own `malloc`, via its `manifold_alloc_*` / `manifold_delete_*` pair, rather
than an FFM `Arena`. A confined `Arena` can only be used from the thread that made it, and a `Solid`
built in a box is routinely read from the runloop thread; a shared one costs a thread handshake to
close. `malloc` has neither problem, and the `Cleaner` sits behind it.

## See also

Write a solid as a composition of primitives and operations in
[solidsl](solid_notation.md), fill a closed surface with tetrahedra using [TetGen](tetgen.md),
or bring an existing model in with [Geometry from disk](geometry_from_disk.md).

[SolidCraft](solidcraft.md) builds parametric designs whose parts can be edited before export.
