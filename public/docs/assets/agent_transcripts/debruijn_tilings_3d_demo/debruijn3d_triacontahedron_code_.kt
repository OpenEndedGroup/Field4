import afield.geometry.solid.manifold.*

// One plane per family: six planes, twenty triple crossings, twenty rhombohedra.
val t = deBruijnTiling3D(planes = 0..0, scale = 10.0)
val counts = t.shapeCounts()

val (union, hull) = Solid.scope {
    // The control: the convex hull of every corner, which is the triacontahedron.
    val corners = Solid.hullOfPointsD(t.cells.flatMap { it.cornerArray().toList() }.toDoubleArray())
    t.toSolid().toTriMesh() to corners.volume
}

showMeshGrid(
    "${counts[1]} prolate + ${counts[2]} oblate, pulled apart" to t.toTriMesh(shrink = 0.8),
    "their Manifold union · cells %.1f · hull %.1f".format(t.volume(), hull) to union.withColor(0.75, 0.78, 0.84),
    columns = 2,
    cellWidth = 370,
)
