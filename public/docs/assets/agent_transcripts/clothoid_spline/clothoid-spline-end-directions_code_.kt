val knots = [vec(100, 200), vec(180, 80), vec(260, 60), vec(330, 140)]

val free = clothoidSplineFrom(knots).apply {
    thickness = 1.5
    color = vec(1.0, 0.7, 0.3, 0.9)
}
val joined = clothoidSplineFrom(knots, startDirection = vec(1, 0), endDirection = vec(70, 140)).apply {
    thickness = 3.0
    color = vec(0.5, 0.8, 1.0, 1.0)
}

// the straight lines the spline joins
val leadIn = polylineFrom([vec(20, 200), vec(100, 200)]).apply { thickness = 3.0 }
val leadOut = polylineFrom([vec(330, 140), vec(400, 280)]).apply { thickness = 3.0 }

lines(leadIn, leadOut, free, joined, w = 420.0, h = 320.0, fitToSize = true)
