import afield.ai.learning.pen.*

// A few strokes of our own, in canvas pixels. The pen is shown all of them, then carries on
// from where the last one ends. Change the seed for a different continuation.
val start = fline {
    moveTo(0, 0)
    cubicTo(6, -26, 22, -26, 20, -4)
    cubicTo(18, 8, 30, 10, 38, -2)
    lineTo(46, -14)
    color = vec(1.0, 0.85, 0.3, 1.0)
    thickness = 2.0
}
val more = start.continueWith(steps = 500, seed = 3).apply { color = vec(0.85, 0.9, 1.0, 1.0) }
lines(start, more, w = 520.0, h = 260.0, fitToSize = true)
