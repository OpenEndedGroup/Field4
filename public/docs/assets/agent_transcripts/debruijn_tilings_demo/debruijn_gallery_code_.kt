// The same construction at four symmetries, one with its grid lines jittered.
val panel = { title: String, t: DeBruijnTiling ->
    val counts =
        t.typeCounts().entries.joinToString("  ") { (type, n) -> "%.1f°: %d".format(type * 180.0 / t.symmetry, n) }
    "$title\n$counts" to t.lines()
}

showPlaneGrid(
    panel("8-fold, Ammann–Beenker", deBruijnTiling(symmetry = 8, radius = 5.0, scale = 10.0)),
    panel("7-fold", deBruijnTiling(symmetry = 7, radius = 5.0, scale = 10.0)),
    panel("12-fold", deBruijnTiling(symmetry = 12, radius = 4.0, scale = 8.0)),
    panel("6-fold, disorder 0.6", deBruijnTiling(symmetry = 6, radius = 6.0, scale = 10.0, disorder = 0.6)),
    columns = 2,
    cellWidth = 380,
    cellHeight = 380,
)
