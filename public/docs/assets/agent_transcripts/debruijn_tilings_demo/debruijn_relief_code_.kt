import afield.geometry.solid.manifold.*

/*
A Penrose relief: every rhombus inset by 0.6 and extruded, thin ones taller than thick ones,
then all unioned into one closed solid. Each tile's outline is a closed FLine, so it goes
straight into a Manifold Section.
*/
val tiling = deBruijnTiling(symmetry = 5, radius = 3.5, scale = 10.0)
val relief = Solid.scope {
    val prisms =
        tiling.tiles.map { tile ->
            Section.ofFLines([tile.outline()], sampleDistance = 10.0)
                .offset(-0.6, JoinType.MITER)
                .extruded(if (tile.type == 1) 6.0 else 3.0)
        }
    val base =
        Section.ofFLines(tiling.tiles.map { it.outline() }, sampleDistance = 10.0)
            .offset(0.5, JoinType.MITER)
            .extruded(1.5)
    Solid.union(prisms + base)
}
val mesh = relief.toTriMesh()
showMesh(
    mesh,
    title = "Penrose relief, ${tiling.tiles.size} rhombi",
    caption =
        "thin 36° tiles 6 high, thick 72° tiles 3 high · genus ${relief.genus} · volume %.0f".format(relief.volume),
    view = MeshView(yawDegrees = 20.0, pitchDegrees = 50.0, width = 760, height = 520),
)
