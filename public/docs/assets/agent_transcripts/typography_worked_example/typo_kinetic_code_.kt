// Every PlacedGlyph carries mutable handles — offset, rotation, scale, pivot — and the
// pivot defaults to the glyph's own visual centre, so a bare `rotation = 0.3` spins each
// letter about itself. These handles are the API rather than an afterthought: this whole box
// is one layout, computed once, and a function of elapsed seconds written into it.
val face = typeface("SansSerif", 150.0)
val layout = layoutText("KINETIC", face, tracking = 16.0)

// The frame is fixed once from the widest the wave will ever get, so the letters move inside
// a still frame instead of the frame breathing around them.
val window = run {
    val probe = layoutText("KINETIC", face, tracking = 16.0)
    probe.eachGlyph { i -> rotation = 0.5; scale = vec(1.3, 1.3); offset = vec(0.0, 34.0) }
    val a = probe.bounds
    probe.eachGlyph { i -> rotation = -0.5; scale = vec(1.3, 1.3); offset = vec(0.0, -34.0) }
    a.union(probe.bounds)
}

val started = System.nanoTime()

// The line you would change: elapsed seconds in, a travelling wave out.
fun waveAt(elapsed: Double, i: Int): Double = 1.7 * elapsed + i * 0.7

animate {
    val elapsed = (System.nanoTime() - started) / 1e9
    layout.eachGlyph { i ->
        val w = waveAt(elapsed, i)
        rotation = 0.42 * kotlin.math.sin(w)
        val s = 1.0 + 0.22 * kotlin.math.cos(w)
        scale = vec(s, s)
        offset = vec(0.0, 30.0 * kotlin.math.sin(w * 1.3))
    }
    showImgGrid(
        "t = %.1fs".format(elapsed) to rasterizeText(layout, 1100, 420, window = window),
        columns = 1, cellSide = 820
    )
}
