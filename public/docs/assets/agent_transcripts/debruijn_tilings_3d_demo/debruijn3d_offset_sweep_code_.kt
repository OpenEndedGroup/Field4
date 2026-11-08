val base = defaultDeBruijnOffsets(6)
val build = { shift: Double ->
    val offsets = base.toMutableList().also { it[0] += shift }
    deBruijnTiling3D(offsets = offsets, radius = 4.0, scale = 10.0, recenter = false)
}
// A cell is the same cell if it has the same corner lift and the same three families.
val identity = { c: DeBruijnCell -> c.lift(0).toList() + c.families }
val central = { t: DeBruijnTiling3D -> t.cells.filter { it.center.length() < 35.0 } }
val before = central(build(0.0)).map(identity).toSet()

val panels =
    [0.0, 0.1, 0.3, 0.6].map { shift ->
        val t = build(shift)
        val kept = central(t).count { identity(it) in before }
        val title = "family 0 offset +%.1f".format(shift)
        "$title\n$kept of ${central(t).size} central cells unchanged" to t.slice(t.directions[0], offset = 0.5).lines()
    }

showPlaneGrid(
    *panels.toTypedArray(),
    columns = 2,
    cellWidth = 360,
    cellHeight = 360,
    window = Rect(-60.0, -60.0, 120.0, 120.0),
)
