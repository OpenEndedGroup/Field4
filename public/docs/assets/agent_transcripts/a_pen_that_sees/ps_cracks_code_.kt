import afield.ai.learning.pen.*

// A wobbling outline handed to the seeing cracks pen three times and its blind twin twice.
// liftAtEnd = true: after a finished outline, the next thing in its corpus is a new stroke.
val outline = fline {
    breakNext()
    for (i in 0..90) {
        val a = i * 2 * PI / 90
        val r = 60 * (1 + 0.15 * sin(3 * a) + 0.08 * cos(5 * a))
        lineTo(cos(a) * r, sin(a) * r)
    }
}
fun inside(model: String, seed: Long) = [
    outline.copy().apply {
        color = vec(1.0, 0.85, 0.3, 1.0)
        thickness = 1.5
    },
    outline.continueWith(PenModel.bundled(model), steps = 700, seed = seed, liftAtEnd = true).apply {
        color = if (model.endsWith("blind")) vec(1.0, 0.6, 0.6, 1.0) else vec(0.9, 0.92, 1.0, 1.0)
        thickness = 1.0
    },
]
lines(
    sideBySide(
        [
            inside("cracks", 1),
            inside("cracks", 2),
            inside("cracks", 3),
            inside("cracks-blind", 1),
            inside("cracks-blind", 2),
        ],
        gap = 30.0,
    ),
    w = 520.0,
    h = 300.0,
    fitToSize = true,
)
