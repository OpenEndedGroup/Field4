val cut = Vector3d(0.31, 0.52, 0.80)

val panels =
    DeBruijnStar.entries.map { star ->
        val t = deBruijnTiling3D(star, radius = if (star.directions.size > 6) 3.0 else 4.0, scale = 10.0)
        val shapes = t.shapeCounts().values.joinToString(" : ")
        val name = star.name.lowercase().replace('_', ' ')
        "$name, ${t.directions.size} families\ncell shapes ${t.shapeVolumes.size}, counts $shapes" to
            t.slice(cut, offset = 0.5).lines()
    }

showPlaneGrid(
    *panels.toTypedArray(),
    columns = 3,
    cellWidth = 240,
    cellHeight = 240,
    window = Rect(-55.0, -55.0, 110.0, 110.0),
)
