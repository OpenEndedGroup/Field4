import afield.ai.learning.pen.*

/*
Launch, and the seeing Michaux pen hatches beside the yellow line in "pen". Draw a line of
your own in "draw here", top to bottom works best, and it starts again beside yours,
seeing everything else you have drawn as well.
*/
val border = fline {
    moveTo(0, 0)
    lineTo(500, 0)
    lineTo(500, 300)
    lineTo(0, 300)
    lineTo(0, 0)
    color = vec(1, 1, 1, 0.2)
}
val starter = fline {
    moveTo(120, 30)
    cubicTo(150, 100, 100, 170, 130, 270)
    color = vec(1.0, 0.85, 0.3, 1.0)
    thickness = 2.0
}
val canvas = lines(border, starter, channel = "draw here", w = 520.0, h = 320.0, editable = true, drawing = true)!!
val michaux = PenModel.bundled("michaux")
var writer: PenLine? = null
var shown = listOf<FLine>()
var seen = 1
frames {
    while (frame()) {
        val drawn = canvas.currentLines()
        if (drawn.size > seen) {
            seen = drawn.size
            val yours = drawn.last().copy().apply {
                color = vec(1.0, 0.85, 0.3, 1.0)
                thickness = 2.0
            }
            shown = drawn.drop(1).dropLast(1).map { it.copy().apply { color = vec(1.0, 0.85, 0.3, 0.6) } } + yours
            writer = PenLine(yours, model = michaux, seed = seen.toLong(), surroundings = drawn.drop(1).dropLast(1))
        }
        val w = writer ?: continue
        // six points a frame; it runs past the end of your line before it turns back
        if (w.history.size < 2400) w.grow(6)
        lines(
            [border] +
                shown +
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
