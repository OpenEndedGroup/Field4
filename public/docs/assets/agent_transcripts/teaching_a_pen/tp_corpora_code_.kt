import afield.ai.learning.pen.*

// Three drawings from each generator, every one drawn in the order a hand or a plotter would.
// That order is all a pen learns from, and the thing a scanned drawing cannot give you.
val rows = [
    (0 until 3).map { PenDrawings.hershey(it.toLong(), HersheyFont.names[it * 3], words = 2) },
    (0 until 3).map { PenDrawings.harmonograph(it.toLong()) },
    (0 until 3).map { PenDrawings.plant(it.toLong()) },
    (0 until 3).map { PenDrawings.truchet(it.toLong(), columns = 5, rows = 3) },
    (0 until 3).map { PenDrawings.boids(it.toLong(), count = 6, steps = 100, size = 100.0) },
    (0 until 2).map { PenDrawings.twoHands(it.toLong(), words = 4) },
]
var y = 0.0
val all = mutableListOf<FLine>()
for (row in rows) {
    val laid = sideBySide(row.map { [it.apply { color = vec(0.85, 0.9, 1.0, 1.0) }] }, gap = 30.0, y = y)
    all += laid
    y = laid.maxOf { it.fast2DBounds().let { b -> (b.y + b.h).toDouble() } } + 30
}
lines(all, w = 520.0, h = 640.0, fitToSize = true)
