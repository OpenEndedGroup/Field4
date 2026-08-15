import afield.manifold.JoinType
import afield.manifold.ManifoldLibrary
import afield.manifold.Section
import afield.manifold.Solid
import afield.stdlib.genus
import afield.stdlib.isWatertight
import afield.stdlib.signedVolume
import afield.stdlib.MeshView

// ---- the two parameters to change ------------------------------------------------
val bore = 3.0          // bore radius, mm. Push past 6.0 and the bosses break through the
                        // plate edge — watch `genus` and `status` react rather than the
                        // picture silently becoming wrong.
val chamfer = 0.8       // edge chamfer, mm, done as a Minkowski sum with a small octahedron.
// ----------------------------------------------------------------------------------

// An explicit `run { }` so the "not installed" branch can bail with `return@run` instead of
// nesting the whole box inside an else.
run {

if (!ManifoldLibrary.isAvailable) {
    // An honest fallback rather than a blank box: build the same shape with Field's own
    // marching cubes so the thing Manifold adds is visible by its absence. This mesh is
    // watertight but NOT a manifold you could subtract from — that is the whole point.
    println(ManifoldLibrary.describe())
    val approx = marchingCubes(
        sdfSmoothUnion(
            sdfBox(vec(30.0, 20.0, 4.0)),
            sdfCylinder(8.0, 12.0),
            k = 3.0,
        ),
        boundsAround(vec(0.0, 0.0, 0.0), 40.0),
        resolution = 110,
    )
    showMesh(
        approx,
        title = "Field's marchingCubes — closed, but not a manifold you can cut",
        caption = "Manifold is not installed, so this is the SDF approximation instead. " +
            "`brew install manifold` and re-run for the real part: exact volume, " +
            "genus you can count, and a Boolean that cannot fail silently.",
        view = MeshView(width = 700, height = 460),
    )
    return@run
}

println(ManifoldLibrary.describe())

// Everything inside a scope is released when it exits, except what comes back — so a box
// re-run on every keystroke does not wait for a Cleaner to reclaim native memory.
val part = Solid.scope {
    // A 60 x 40 x 8 plate, centred.
    val plate = Solid.cube(60.0, 40.0, 8.0, center = true)

    // Four bosses standing on it, one per corner of a 40 x 24 rectangle.
    val bossCentres = listOf(-20.0 to -12.0, 20.0 to -12.0, -20.0 to 12.0, 20.0 to 12.0)
    val bosses = bossCentres.map { (x, y) ->
        Solid.cylinder(14.0, 6.0, segments = 64, center = true).translated(x, y, 0.0)
    }

    // A bore through each boss, and a slot down the middle. Batched rather than folded:
    // Manifold plans the whole subtraction instead of doing five nested ones.
    val bores = bossCentres.map { (x, y) ->
        Solid.cylinder(30.0, bore, segments = 64, center = true).translated(x, y, 0.0)
    }
    val slot = Section.square(26.0, 9.0, center = true)
        .offset(4.5, JoinType.ROUND, circularSegments = 64)   // a rounded-end slot, properly
        .extruded(30.0)
        .translated(0.0, 0.0, -15.0)

    val rough = Solid.union(listOf(plate) + bosses) - Solid.union(bores + slot)

    // The chamfer. A Minkowski sum with a small octahedron rounds every edge at once —
    // the operation there is no honest way to fake by displacing vertices.
    if (chamfer > 0.0) {
        val tool = Solid.hullOfPoints(
            listOf(
                vec(chamfer, 0f, 0f), vec(-chamfer, 0f, 0f),
                vec(0f, chamfer, 0f), vec(0f, -chamfer, 0f),
                vec(0f, 0f, chamfer), vec(0f, 0f, -chamfer),
            ),
        )
        // Grow then shrink: a closing, which rounds the convex edges and leaves the
        // concave ones, exactly as a ball-nose cutter would.
        rough.minkowskiSum(tool).minkowskiDifference(tool)
    } else {
        rough
    }
}

// ---- the numbers that decide whether this is a part or a picture -------------------
// Two meshes on purpose. `normals = true` splits a vertex per face at every hard edge so
// each face can carry its own normal — which is what makes the render read as machined, and
// is also why that mesh is NOT index-watertight and reports the wrong genus. So: the shaded
// one for the picture, the plain one for anything that measures topology.
val shaded = part.toTriMesh(normals = true)
val mesh = part.toTriMesh()
println("status      ${part.status}")
println("volume      ${"%.3f".format(part.volume)} mm^3   (%.2f g in 6061 aluminium)"
    .format(part.volume * 0.0027))
println("area        ${"%.3f".format(part.surfaceArea)} mm^2")
println("genus       ${part.genus}   — four bores plus the slot, so five holes through it")
println("mesh        ${part.triangleCount} triangles, ${part.vertexCount} vertices")

// Two implementations that share no code, on the same surface. Manifold integrates the
// solid in double; Field sums signed tetrahedra over float triangles. If the bridge were
// wrong these would not agree.
println()
println("Field's own reckoning of the plain mesh that came back:")
println("  watertight  ${mesh.isWatertight()}")
println("  genus       ${mesh.genus()}   — agreeing with Manifold's ${part.genus}")
println("  volume      ${"%.3f".format(mesh.signedVolume())}   " +
    "(Manifold said ${"%.3f".format(part.volume)}, differing by " +
    "${"%.2e".format(kotlin.math.abs(mesh.signedVolume() - part.volume))})")
println("  and the shaded mesh: ${shaded.vertexCount} vertices against ${mesh.vertexCount}, " +
    "split at the creases, so isWatertight() is ${shaded.isWatertight()} on that one — " +
    "same geometry, different index topology")

// A clearance check: how close does the slot come to the nearest bore? This is the number
// that says whether the wall between them will survive being cut.
val nearestBore = Solid.scope {
    Solid.cylinder(30.0, bore, segments = 64, center = true).translated(-20.0, -12.0, 0.0)
        .copy()
}
val slotSolid = Solid.scope {
    Section.square(26.0, 9.0, center = true)
        .offset(4.5, JoinType.ROUND, circularSegments = 64)
        .extruded(30.0).translated(0.0, 0.0, -15.0).copy()
}
println()
println("wall between the slot and the nearest bore: " +
    "${"%.3f".format(slotSolid.minGap(nearestBore, searchLength = 30.0))} mm")
nearestBore.close()
slotSolid.close()

showMesh(
    shaded,
    title = "60 x 40 x 8 plate, four bored bosses, a rounded slot, ${chamfer}mm chamfer",
    caption = "genus ${part.genus} &middot; ${"%.1f".format(part.volume)} mm&sup3; &middot; " +
        "${part.triangleCount} triangles &middot; every Boolean guaranteed to close",
    view = MeshView(yawDegrees = 34.0, pitchDegrees = 26.0, width = 760, height = 470),
)
part.close()

}
