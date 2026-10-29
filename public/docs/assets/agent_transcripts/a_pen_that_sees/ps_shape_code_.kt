import afield.ai.learning.pen.*

// Launch, and the seeing cracks pen fills the yellow shape in "pen" with cracks that stop
// where they meet a line. Draw a closed shape of your own in "draw here" and it cracks yours.
val border = fline {
    moveTo(0, 0)
    lineTo(500, 0)
    lineTo(500, 300)
    lineTo(0, 300)
    lineTo(0, 0)
    color = vec(1, 1, 1, 0.2)
}
val starter = fline {
    breakNext()
    for (i in 0..24) {
        val a = i * 2 * PI / 24
        val r = 90 * (1 + 0.15 * sin(3 * a) + 0.08 * cos(5 * a))
        lineTo(250 + cos(a) * r, 150 + sin(a) * r)
    }
}
    .apply {
        color = vec(1.0, 0.85, 0.3, 1.0)
        thickness = 2.0
    }
val canvas = lines(border, starter, channel = "draw here", w = 520.0, h = 320.0, editable = true, drawing = true)!!
val cracks = PenModel.bundled("cracks")
var writer: PenLine? = null
var shape = starter
var seen = 1
frames {
    while (frame()) {
        val drawn = canvas.currentLines()
        if (drawn.size > seen) {
            seen = drawn.size
            shape = drawn.last().copy().apply {
                color = vec(1.0, 0.85, 0.3, 1.0)
                thickness = 2.0
            }
            writer = PenLine(shape, model = cracks, seed = seen.toLong(), liftAtEnd = true)
        }
        val w = writer ?: continue
        if (w.history.size < 1200) w.grow(4)
        lines(
            border,
            shape,
            w.drawn.apply {
                color = vec(0.9, 0.92, 1.0, 1.0)
                thickness = 1.0
            },
            channel = "pen",
            w = 520.0,
            h = 320.0,
        )
    }
}
