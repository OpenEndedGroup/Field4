/*
Slide every line of family 0 through one full spacing over six seconds. Most tiles hold still;
small groups flip as each line passes a crossing. At offset +1 the lines are back where they
started, relabelled, so the loop has no seam. The red ribbon is line 0 of family 0.
*/
val base = defaultDeBruijnOffsets(5)
val line = DeBruijnLine(family = 0, index = 0)
val window = Rect(-190.0, -190.0, 380.0, 380.0)
val seconds = 6.0
val started = AnimationClock.nowSeconds()

animate {
    val shift = ((AnimationClock.nowSeconds() - started) / seconds) % 1.0
    val offsets = base.toMutableList().also { it[0] += shift }
    // recenter = false: unchanged tiles stay put rather than shifting with the mean.
    val t = deBruijnTiling(symmetry = 5, offsets = offsets, radius = 5.0, scale = 12.0, recenter = false)
    showPlane(
        t.lines(highlight = line),
        title = "family 0 offset +%.2f".format(shift),
        caption = "thin ${t.typeCounts()[1]}, thick ${t.typeCounts()[2]}",
        width = 560,
        height = 560,
        window = window,
    )
}
