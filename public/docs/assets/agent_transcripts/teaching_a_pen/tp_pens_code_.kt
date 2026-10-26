import afield.ai.learning.pen.*

// Each pen that ships with Field, given nothing and left to draw: a grey drawing from its
// corpus on the left, then three of its own. What each one learned, and what it missed,
// is easiest to see side by side.
val pens = listOf(
    "hershey" to { s: Long -> PenDrawings.hershey(s, words = 2) },
    "harmonograph" to { s: Long -> PenDrawings.harmonograph(s) },
    "plants" to { s: Long -> PenDrawings.plant(s) },
    "truchet" to { s: Long -> PenDrawings.truchet(s, columns = 5, rows = 3) },
    "boids" to { s: Long -> PenDrawings.boids(s, count = 6, steps = 100, size = 100.0) },
    "mixed" to { s: Long -> PenDrawings.twoHands(s, words = 3) },
)
val rows = pens.map { (name, corpus) ->
    val pen = PenModel.bundled(name)
    val own = (0 until 3).map { seed -> PenLine(model = pen, seed = seed.toLong()).also { it.grow(300) }.line.apply { color = vec(0.6, 0.85, 1.0, 1.0) } }
    sideBySide(listOf(listOf(corpus(900).apply { color = vec(1, 1, 1, 0.5) })) + own.map { listOf(it) }, gap = 30.0)
}
lines(stacked(rows, gap = 30.0), w = 520.0, h = 700.0, fitToSize = true)
