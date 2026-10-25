// Launch it (Alt-R). The same sphere as `stipple_hello`.
val sphere =
    Img.generate(480, 360) { x, y ->
        val dx = (x - 220) / 140.0
        val dy = (y - 170) / 140.0
        val r2 = dx * dx + dy * dy
        val tone =
            if (r2 < 1.0) 0.1 + 0.9 * max(0.0, -0.45 * dx - 0.55 * dy + 0.7 * sqrt(1.0 - r2))
            else 1.0 - 0.6 * exp(-2.0 * ((x - 280) / 150.0).pow(2) - 2.0 * ((y - 318) / 30.0).pow(2))
        tone.toFloat()
    }

/*
Where the stippler starts: one dot per equal share of the darkness along a Hilbert curve,
so every region already has the right number of dots. Their spacing is rough, and evening
it out is what relaxation is for.
*/
val s = Stippler(sphere, 6000, invert = true)
showImg(stipplesToImg(s.points(), 480, 360), channel = "start")

// One Lloyd step per frame, until no dot moves.
val moved = mutableListOf<Double>()
loop {
    if (moved.lastOrNull() == 0.0) return@loop
    moved += s.step()
    showImg(stipplesToImg(s.points(), 480, 360), channel = "relaxed")
    showSignals("mean movement (px)" to moved, title = "Lloyd step ${s.iterations}", xTitle = "step")
}
