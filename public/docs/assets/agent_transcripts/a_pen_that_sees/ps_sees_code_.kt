import afield.ai.learning.pen.*
import kotlin.math.*

// The seeing Michaux pen hatching beside a wavering line, stopped at four moments. At each,
// the faint circle is as far as it can see and each orange ray runs to the nearest ink in one
// of its sixteen directions, brighter the closer it is.
val yours = FLine().also { f -> for (i in 0..120) { val y = i * 2.0; val x = 14 * sin(y / 40) + 5 * sin(y / 13); if (i == 0) f.moveTo(x, y) else f.lineTo(x, y) } }
val writer = PenLine(yours, model = PenModel.bundled("michaux"), seed = 1)
val moments = listOf(60, 180, 320, 520).map { until ->
    writer.grow(until - writer.history.size)
    listOf(yours.copy().apply { color = vec(1.0, 0.85, 0.3, 1.0); thickness = 1.5 },
        writer.drawn.copy().apply { color = vec(0.9, 0.92, 1.0, 1.0); thickness = 1.0 }) + writer.seeing()
}
lines(sideBySide(moments, gap = 40.0), w = 520.0, h = 300.0, fitToSize = true)
