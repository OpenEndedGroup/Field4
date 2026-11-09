import afield.geometry.solid.manifold.*

// Stand a 5-fold axis upright and keep two layers: the cells between planes 0 and 2 of family 0.
val t = deBruijnTiling3D(radius = 4.0, scale = 10.0).oriented(DeBruijnStar.ICOSAHEDRAL.directions[0])
val slab = t.between(family = 0, from = 0, to = 2).filter { hypot(it.center.x, it.center.y) < 60.0 }
val floor = slab.sumOf { it.center.z } / slab.size

val (welded, relief) = Solid.scope {
    val union = t.toSolid(slab)
    // A disk 90 across, trimmed flat at the slab's middle height.
    val disk = Solid.cylinder(400.0, 45.0, segments = 96, center = true)
    union.volume to (union and disk).trimByPlane(0.0, 0.0, 1.0, floor)
}

showMesh(
    relief.toTriMesh().withColor(0.85, 0.80, 0.68),
    title = "two layers of the 3D Penrose tiling, 5-fold axis up · genus ${relief.genus}",
    caption =
        "${slab.size} cells · sum of cell volumes %.1f · welded by Manifold %.1f"
            .format(
                slab.sumOf { it.volume },
                welded,
            ),
    view = MeshView(yawDegrees = 20.0, pitchDegrees = 50.0, width = 760, height = 520),
)
