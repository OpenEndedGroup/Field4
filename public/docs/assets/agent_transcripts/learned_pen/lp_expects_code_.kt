import afield.ai.learning.pen.*

// Launch, and the pen writes two points a frame. "page" draws its line thick where it is sure
// to stay on the paper and thin where it expects to lift. "tip" is a magnifier on the last
// dozen points, with an ellipse for each of its guesses about where the next point will land.
val start = fline { moveTo(0, 0); cubicTo(4, -14, 12, -14, 12, 0); cubicTo(12, 8, 20, 8, 24, 0); color = vec(1.0, 0.85, 0.3, 1.0); thickness = 2.0 }
var noise = 1.0
slider("noise", min = 0.1, max = 1.5, initialValue = noise) { noise = it }
val writer = PenLine(start, seed = 9)
frames {
    while (frame()) {
        if (writer.history.size > 600) continue
        writer.controls = PenControls(noise = noise)
        writer.grow(2)
        lines([start] + writer.widthByLift(), channel = "page", w = 520.0, h = 200.0, fitToSize = true)
        lines(writer.closeUp(12).lines(), channel = "tip", w = 520.0, h = 260.0, fitToSize = true)
    }
}
