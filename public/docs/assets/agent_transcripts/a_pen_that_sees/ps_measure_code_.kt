import afield.ai.learning.pen.*
import kotlin.math.*

// following: the share of the pen's points with older ink within 8 pixels. joins: the share
// of its strokes that end on ink. Twenty seeds each, the same primers as boxes 2 and 3.
val yours = FLine().also { f -> for (i in 0..120) { val y = i * 2.0; val x = 14 * sin(y / 40) + 5 * sin(y / 13); if (i == 0) f.moveTo(x, y) else f.lineTo(x, y) } }
val outline = FLine().also { f -> for (i in 0..90) { val a = i * 2 * PI / 90; val r = 60 * (1 + 0.15 * sin(3 * a) + 0.08 * cos(5 * a)); if (i == 0) f.moveTo(cos(a) * r, sin(a) * r) else f.lineTo(cos(a) * r, sin(a) * r) } }
fun mean(f: (Long) -> Double) = "%.2f".format((0 until 20).map { f(it.toLong()) }.average())
val hatching = { m: String -> mean { s -> following(yours.continueWith(PenModel.bundled(m), steps = 1500, seed = s)) } }
val cracking = { m: String -> mean { s -> joins(outline.continueWith(PenModel.bundled(m), steps = 700, seed = s, liftAtEnd = true)) } }
markdown("measures", """
| | seeing | blind |
|---|---|---|
| Michaux beside a line: following | ${hatching("michaux")} | ${hatching("michaux-blind")} |
| cracks in an outline: joins | ${cracking("cracks")} | ${cracking("cracks-blind")} |
""".trimIndent())
