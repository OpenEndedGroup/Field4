import afield.ai.learning.pen.*

// The same yellow stroke, continued by the handwriting pen and by each of the six synthetic
// ones. continueWith takes any model: this is the whole difference between the rows.
val start = fline {
    moveTo(0, 0)
    cubicTo(4, -14, 12, -14, 12, 0)
    cubicTo(12, 8, 20, 8, 24, 0)
    color = vec(1.0, 0.85, 0.3, 1.0)
    thickness = 2.0
}
val models =
    [PenModel.distill] + ["hershey", "harmonograph", "plants", "truchet", "boids", "mixed"].map { PenModel.bundled(it) }
val rows = models.map { model ->
    [
        start.copy().apply {
            color = vec(1.0, 0.85, 0.3, 1.0)
            thickness = 2.0
        },
        start.continueWith(model, steps = 260, seed = 2).apply { color = vec(0.85, 0.9, 1.0, 1.0) },
    ]
}
// two columns: handwriting, Hershey, harmonograph and plants; then truchet, boids and two hands
lines(
    sideBySide([stacked(rows.take(4), gap = 24.0), stacked(rows.drop(4), gap = 24.0)], gap = 60.0),
    w = 520.0,
    h = 420.0,
    fitToSize = true,
)
