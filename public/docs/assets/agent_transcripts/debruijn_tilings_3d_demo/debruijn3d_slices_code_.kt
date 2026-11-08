val t = deBruijnTiling3D(radius = 5.0, scale = 10.0)
val phi = (1 + sqrt(5.0)) / 2
val window = Rect(-70.0, -70.0, 140.0, 140.0)

val panel = { title: String, normal: Vector3d ->
    val s = t.slice(normal, offset = 0.5)
    val sides = s.sideCounts().entries.joinToString(" ") { (n, count) -> "$n-gons $count" }
    "$title\n$sides" to s.lines()
}

showPlaneGrid(
    panel("across a 5-fold axis", Vector3d(0.0, 1.0, phi)),
    panel("across a 3-fold axis", Vector3d(1.0, 1.0, 1.0)),
    panel("across a 2-fold axis", Vector3d(0.0, 0.0, 1.0)),
    columns = 3,
    cellWidth = 250,
    cellHeight = 250,
    window = window,
)
