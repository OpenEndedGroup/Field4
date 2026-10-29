import afield.ai.learning.pen.*

// The same yellow line continued three times by the seeing Michaux pen (white) and twice by
// its blind twin (red), trained on the same drawings without sight.
val yours = fline {
    breakNext()
    for (i in 0..120) {
        val y = i * 2.0
        val x = 14 * sin(y / 40) + 5 * sin(y / 13)
        lineTo(x, y)
    }
}
fun beside(model: String, seed: Long) = [
    yours.copy().apply {
        color = vec(1.0, 0.85, 0.3, 1.0)
        thickness = 1.5
    },
    yours.continueWith(PenModel.bundled(model), steps = 1500, seed = seed).apply {
        color = if (model.endsWith("blind")) vec(1.0, 0.6, 0.6, 1.0) else vec(0.9, 0.92, 1.0, 1.0)
        thickness = 1.0
    },
]
lines(
    sideBySide(
        [
            beside("michaux", 1),
            beside("michaux", 2),
            beside("michaux", 3),
            beside("michaux-blind", 1),
            beside("michaux-blind", 2),
        ],
        gap = 30.0,
    ),
    w = 520.0,
    h = 440.0,
    fitToSize = true,
)
