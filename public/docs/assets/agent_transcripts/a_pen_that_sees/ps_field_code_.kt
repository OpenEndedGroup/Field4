import afield.ai.learning.pen.*
import kotlin.math.*

// One short wavering stroke, then five thousand points from the seeing Michaux pen: columns
// of trembling lines, each pass following the one before it.
val first = FLine().also { f -> for (i in 0..60) { val y = i * 2.5; val x = 6 * sin(y / 25); if (i == 0) f.moveTo(x, y) else f.lineTo(x, y) } }
val writer = PenLine(first, model = PenModel.bundled("michaux"), seed = 7)
writer.grow(5000)
lines(writer.line.apply { color = vec(0.9, 0.92, 1.0, 1.0); thickness = 0.8 }, w = 520.0, h = 320.0, fitToSize = true)
