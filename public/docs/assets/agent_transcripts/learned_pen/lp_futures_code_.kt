import afield.ai.learning.pen.*

/*
Twelve things the pen might do next, from the same line, each drawn beside its own copy
of the line. They share the pen's reading of the yellow stroke and differ only in the
random choices after it.
*/
val start = fline {
    moveTo(0, 0)
    cubicTo(4, -14, 12, -14, 12, 0)
    cubicTo(12, 8, 20, 8, 24, 0)
    color = vec(1.0, 0.85, 0.3, 1.0)
    thickness = 1.5
}
val futures = start.futuresWith(count = 12, steps = 80)
val grid = mutableListOf<FLine>()
futures.forEachIndexed { k, future ->
    val at = vec(k % 3 * 170.0, k / 3 * 60.0, 0.0)
    grid += start + at
    grid += (future + at).apply { color = vec(0.75, 0.85, 1.0, 1.0) }
}
lines(grid, w = 520.0, h = 260.0, fitToSize = true)
