import afield.ai.learning.pen.*

// Launch, and the pen hatches around the drawing in "draw here", stroke by stroke, as you
// watch. Draw more and it starts again around everything. The checkbox switches the pen's hand.
val border = fline { moveTo(0, 0); lineTo(500, 0); lineTo(500, 300); lineTo(0, 300); lineTo(0, 0); color = vec(1, 1, 1, 0.2) }
val starter = listOf(
    fline { moveTo(90, 60); cubicTo(200, 20, 260, 220, 400, 160) },
    fline { moveTo(150, 250); cubicTo(180, 150, 300, 300, 330, 230) },
).onEach { it.color = vec(1.0, 0.85, 0.3, 1.0); it.thickness = 2.0 }
val canvas = lines(listOf(border) + starter, channel = "draw here", w = 520.0, h = 320.0, editable = true, drawing = true)!!
val hands = listOf("echoes", "michaux")
var hand = 0
var choice = 0
checkbox("Michaux's hand", channel = "hand") { choice = if (it) 1 else 0 }
var hatcher: Hatcher? = null
var drawing = listOf<FLine>()
var seen = -1
frames {
    while (frame()) {
        val drawn = canvas.currentLines().drop(1)
        if (drawn.size != seen || choice != hand) {
            seen = drawn.size
            hand = choice
            drawing = drawn.map { it.copy().apply { color = vec(1.0, 0.85, 0.3, 1.0); thickness = 2.0 } }
            hatcher = Hatcher(drawing, PenModel.bundled(hands[hand]), passes = 8, gap = 4.0, seed = seen.toLong())
        }
        val h = hatcher ?: continue
        if (!h.done) h.grow(24)
        lines(listOf(border) + drawing + h.hatching.map { it.apply { color = vec(0.9, 0.92, 1.0, 1.0); thickness = 0.8 } },
            channel = "pen", w = 520.0, h = 320.0)
    }
}
