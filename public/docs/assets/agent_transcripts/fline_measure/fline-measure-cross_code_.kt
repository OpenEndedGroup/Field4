val loop = fline {
    moveTo(60, 200)
    cubicTo(420, 40, 0, 40, 340, 210)
    thickness = 2.0
    color = vec(1.0, 1.0, 1.0, 1.0)
}

val ring = fline {
    circle(200, 130, 70)
    thickness = 2.0
    color = vec(0.6, 0.8, 1.0, 1.0)
}

// where the loop crosses the ring, and where it crosses itself
val hits = listOf(loop.crossings(ring), loop.selfCrossings()).flatten().map { it.point }
val dots = polylineFrom(hits.map { it.v2 }).apply {
    pointed = true
    pointSize = 3.0
    stroked = false
    color = vec(1.0, 0.5, 0.2, 1.0)
}

// bounds that hug the curve, against the control-point bounds
val tight = fline { rect(loop.tightBounds2D()); thickness = 1.0; color = vec(0.4, 1.0, 0.6, 1.0) }
val loose = fline { rect(loop.fast2DBounds()); thickness = 1.0; color = vec(1.0, 1.0, 1.0, 0.25) }

print("${hits.size} crossings; clockwise on screen: ${ring.isClockwise()}")

lines(listOf(loose, tight, ring, loop, dots), w = 420.0, h = 260.0, fitToSize = true)
