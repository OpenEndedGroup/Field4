// Overlap is a hinge: relu(r1 + r2 - distance)^2, zero when the discs are disjoint and
// growing quadratically once they interpenetrate. Walls are the same shape. Every term is
// differentiable, so the packing is a descent rather than a relaxation loop of nudges.
val radii = doubleArrayOf(38.0, 32.0, 30.0, 26.0, 24.0, 22.0, 20.0, 18.0, 16.0, 14.0, 12.0, 10.0)
val boxWidth = 340.0
val boxHeight = 250.0
val packing = packCircles(radii, boxWidth, boxHeight, rngSeed = 5L)

// run.initialX is the seeded scatter the descent started from, so "before" is real.
val before = radii.indices.map {
    PackedCircle(vec(packing.run.initialX[2 * it], packing.run.initialX[2 * it + 1]), radii[it])
}

fun frame() = fline {
    moveTo(0.0, 0.0); lineTo(boxWidth, 0.0); lineTo(boxWidth, boxHeight); lineTo(0.0, boxHeight)
    close()
    color = vec(0.36, 0.44, 0.56, 0.7)
    thickness = 1.2
}
fun rings(circles: List<PackedCircle>, r: Double, g: Double, b: Double) = circles.map {
    it.toFLine(52).apply {
        color = vec(r, g, b, 0.95)
        thickness = 1.8
    }
}

fun worstOverlapOf(circles: List<PackedCircle>): Double {
    var worst = 0.0
    for (i in circles.indices) for (j in i + 1 until circles.size) {
        val d = kotlin.math.hypot(
            (circles[i].centre.x - circles[j].centre.x).toDouble(),
            (circles[i].centre.y - circles[j].centre.y).toDouble()
        )
        worst = kotlin.math.max(worst, circles[i].radius + circles[j].radius - d)
    }
    return worst
}

val window = Rect(-20.0, -20.0, boxWidth + 40.0, boxHeight + 40.0)
showPlaneGrid(
    "before\nseeded scatter, worst overlap " + worstOverlapOf(before) + " units" to
        (rings(before, 0.52, 0.58, 0.72) + frame()),
    "after\n" + packing.run.iterations + " L-BFGS iterations, worst overlap " + packing.worstOverlap + " units" to
        (rings(packing.circles, 0.42, 0.86, 0.62) + frame()),
    channel = "1 packing",
    columns = 2,
    cellWidth = 380,
    window = window
)
showSignals(
    "overlap + wall + pull loss" to packing.run.losses,
    channel = "2 loss",
    title = "packing loss per L-BFGS iteration",
    xTitle = "iteration"
)

"worst pairwise overlap " + worstOverlapOf(before) + " -> " + packing.worstOverlap + " units\n" +
"worst escape beyond the box wall: " + packing.worstEscape + " units\n" +
"loss " + packing.run.initialValue + " -> " + packing.run.value +
" in " + packing.run.iterations + " iterations (" + packing.run.reason + ")"
