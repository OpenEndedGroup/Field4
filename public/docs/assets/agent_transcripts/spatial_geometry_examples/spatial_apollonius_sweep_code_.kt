// The animated twin of "When the bisector is a circle". Three stills at three weights argue
// that a straight bisector becomes a circle; sweeping the weight SHOWS it. Launch this box
// (it ends in `animate`, so it runs until you stop it) and watch three things:
//   - only the edges that TOUCH site 1 bow. A bisector between two equally weighted sites
//     stays exactly straight, which is why both counts are in the caption.
//   - the counts JUMP rather than drift: the total piece count falls when a light neighbour
//     is swallowed, because an enclosed cell needs one full circle where it needed several
//     arcs and a line. `enclosed` goes 0 -> 2 between weight 2.02 and 2.45.
//   - at exactly 1.0 the whole diagram snaps to straight lines. The piece type is decided
//     by an EXACT equality of weights, so it really is discontinuous.
val positions = listOf(
    vec(0.0, 0.0), vec(2.2, 0.3), vec(0.9, 2.0), vec(-1.4, 1.1), vec(1.1, -1.6),
)
val clip = CircularDirichlet.Aabb(vec(-3.4, -3.4), vec(4.6, 4.6))
// A FIXED window. Left to fit its own bounds each frame the picture rescales as the arcs
// swell, and the sweep appears to stand still.
val window = Rect(-3.4, -3.4, 8.0, 8.0)
val palette = okhslSet(5, saturation = 0.8, lightness = 0.66)
val cloud = pointCloudFLine(positions, color = vec(1.0, 1.0, 1.0, 0.95), pointSize = 1.6)
val heavy = 1                 // the site whose weight is swept; the other four stay at 1.0
val sweepSeconds = 8.0
val started = AnimationClock.nowSeconds()

// The line that turns elapsed time into the swept parameter — the one you would change. A
// triangle ramp rather than a saw, so it runs out to 2.7 and back to 1.0 with no jump.
fun weightAt(elapsed: Double): Double {
    val phase = (elapsed / sweepSeconds) % 1.0
    return 1.0 + 1.7 * (1.0 - abs(1.0 - 2.0 * phase))
}

animate {
    val w = weightAt(AnimationClock.nowSeconds() - started)
    val diagram = CircularDirichlet.build(
        positions.mapIndexed { i, p -> vec(p.x, p.y, if (i == heavy) w else 1.0) }
    )
    val arcs = diagram.cells.sumOf { c -> c.pieces.count { it is CircularDirichlet.ArcPiece } }
    val straight = diagram.cells.sumOf { c -> c.pieces.count { it is CircularDirichlet.LinePiece } }
    val enclosed = diagram.cells.count { c ->
        c.pieces.size == 1 &&
            (c.pieces[0] as? CircularDirichlet.ArcPiece)?.let { it.sweepAngle > 6.283 } == true
    }
    showPlane(
        diagram.toFLines(clip = clip, maxChordError = 0.005f, palette = palette, thickness = 1.6) + cloud,
        title = "site 1 weight %.2f".format(w),
        caption = "$arcs arcs, $straight straight, enclosed $enclosed",
        width = 560,
        height = 560,
        window = window,
    )
}
