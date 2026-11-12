// Outline every stroke drawn by hand from now on, at the width the pen pressed.
val inked = mutableListOf<FLine>()
notation.onStroke { mark ->
    val knots = thinPenSamples(mark.penKnots(10.0))
    inked +=
        sweptCircleStroke(knots, corners = penCorners(knots)).shell.apply {
            filled = true
            stroked = false
            color = vec(0.95, 0.95, 0.9, 1.0)
        }
    lines(inked, w = 900.0, h = 400.0, fitToSize = true)
}
