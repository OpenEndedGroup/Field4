import afield.ai.learning.pen.*

// Launch, and the pen writes on from the yellow stroke in "pen". Draw a stroke of your own in
// "draw here" and it starts again from yours. The sliders change how it samples as it goes.
var temperature = 0.65
var noise = 0.65
controlGroup("pen controls") {
    slider("temperature", min = 0.05, max = 2.0, initialValue = temperature) { temperature = it }
    slider("noise", min = 0.0, max = 1.5, initialValue = noise) { noise = it }
}

val border = fline {
    moveTo(0, 0)
    lineTo(500, 0)
    lineTo(500, 240)
    lineTo(0, 240)
    lineTo(0, 0)
    color = vec(1, 1, 1, 0.2)
}
val starter = fline {
    moveTo(30, 120)
    cubicTo(36, 96, 52, 96, 50, 116)
    cubicTo(48, 128, 60, 130, 68, 118)
    color = vec(1.0, 0.85, 0.3, 1.0)
    thickness = 2.0
}
val canvas = lines(border, starter, channel = "draw here", w = 520.0, h = 260.0, editable = true, drawing = true)!!
var primer = starter
var writer: PenLine? = null
var seen = 1
frames {
    while (frame()) {
        val drawn = canvas.currentLines()
        if (drawn.size > seen) {
            seen = drawn.size
            primer = drawn.last().copy().apply {
                color = vec(1.0, 0.85, 0.3, 1.0)
                thickness = 2.0
            }
            writer = PenLine(primer)
        }
        val w = writer ?: continue
        w.controls = PenControls(temperature = temperature, noise = noise)
        // four points a frame, until it writes off the right-hand edge
        val x = w.drawn.currentPoint?.x ?: 0f
        if (x < 490f) w.grow(4)
        lines(border, primer, w.drawn.apply { color = vec(0.85, 0.9, 1.0, 1.0) }, channel = "pen", w = 520.0, h = 260.0)
    }
}
