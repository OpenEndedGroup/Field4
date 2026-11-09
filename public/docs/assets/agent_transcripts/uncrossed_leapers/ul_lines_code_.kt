import afield.math.linalg.*
import kotlin.math.*

/*
Zipped layers: use [a, b, c] or a scalar. Short lists hold their last value.
All geometry parameters are deliberately UNCLAMPED.
Trims are fractions of the original move removed from each end.
*/
val startTrim = [0]
val endTrim = [0]
val nextPull = [5.0]
val previousPull = [-15.0, -10.0, -5.0, 0, 5.0, 10.0, 15.0]
val rotationRadians = [0]
val perpendicularOffset = [0.0]
val alongSlide = 7 { it * 4 }
val strokeWidth = 7 { 1 / (1 + it.d) }
val gap = 12.0
val cell = knightCellSize
val w = knightBoardWidth
val h = knightBoardHeight
val boardW = w * cell
val boardH = h * cell

data class StrokeParameters(
    val startTrim: Double = 0.0,
    val endTrim: Double = 0.0,
    val nextPull: Double = 0.0,
    val previousPull: Double = 0.0,
    val rotation: Double = 0.0,
    val offset: Double = 0.0,
    val slide: Double = 0.0,
)
data class QuadraticStroke(val start: Vector2f, val control: Vector2f, val end: Vector2f)
// Convert only at the parameter boundary; geometry stays strongly typed.
fun parameterValues(value: Any): List<Double> = when (value) {
    is Number -> [value.toDouble()]
    is List<*> -> {
        require(value.isNotEmpty()) { "Parameter lists need at least one value." }
        value.map {
            require(it is Number) { "Parameter values must be numbers." }
            it.toDouble()
        }
    }
    else -> error("Use a number or a nonempty list of numbers.")
}
fun held(values: List<Double>, index: Int) = values.getOrElse(index) { values.last() }
val channels = [startTrim, endTrim, nextPull, previousPull, rotationRadians, perpendicularOffset, alongSlide, strokeWidth].map {
    parameterValues(it)
}
val layerCount = channels.maxOf { it.size }
val parameterSets =
    (0 until layerCount).map { i ->
        StrokeParameters(
            held(channels[0], i),
            held(channels[1], i),
            held(channels[2], i),
            held(channels[3], i),
            held(channels[4], i),
            held(channels[5], i),
            held(channels[6], i),
        )
    }
val layerWidths = (0 until layerCount).map { held(channels[7], it) }
// Hold-last and scalar checks, including deliberately out-of-range values.
check(parameterValues(-1.0) == [-1.0])
check((0..3).map { held(parameterValues([11.0, -1.0]), it) } == [11.0, -1.0, -1.0, -1.0])
check((0..2).map { held(parameterValues(4.0), it) } == [4.0, 4.0, 4.0])

/*
For move A->B, previous means the point before A; next means the point after B.
Control pulls use unit directions from the trimmed midpoint to those ORIGINAL neighbours.
Zero-length pull directions contribute zero; neither pulls nor trims are restricted.
*/
fun makeStroke(a: Vector2f, b: Vector2f, previous: Vector2f?, next: Vector2f?, p: StrokeParameters): QuadraticStroke {
    val delta = b - a
    val start = a + delta * p.startTrim
    val end = b - delta * p.endTrim
    val middle = (start + end) * 0.5
    var control = middle
    if (next != null) control = control + (next - middle).normalized(1e-12f) * p.nextPull
    if (previous != null) control = control + (previous - middle).normalized(1e-12f) * p.previousPull
    // Axes follow the ORIGINAL A->B orientation, then rotate. Over-trimming does not flip them.
    val rotation = quat(p.rotation)
    val axis = delta.normalized(1e-12f) * rotation
    val normal = vec(-axis.y(), axis.x())
    val translation = normal * p.offset + axis * p.slide
    fun transform(q: Vector2f) = middle + (q - middle) * rotation + translation
    return QuadraticStroke(transform(start), transform(control), transform(end))
}

// Mathematical checks include extension, reversed endpoints and parameters outside [0,1].
fun near(a: Vector2f, b: Vector2f) = (a - b).length() < 1e-5
val aTest = vec(0.0, 0.0)
val bTest = vec(10.0, 0.0)
val identity = makeStroke(aTest, bTest, null, null, StrokeParameters())
check(near(identity.start, aTest) && near(identity.end, bTest) && near(identity.control, vec(5.0, 0.0)))
val extended = makeStroke(aTest, bTest, null, null, StrokeParameters(startTrim = -1.0, endTrim = -1.0))
check(near(extended.start, vec(-10.0, 0.0)) && near(extended.end, vec(20.0, 0.0)))
val reversed = makeStroke(aTest, bTest, null, null, StrokeParameters(startTrim = 2.0, endTrim = 2.0))
check(near(reversed.start, vec(20.0, 0.0)) && near(reversed.end, vec(-10.0, 0.0)))
val moved = makeStroke(aTest, bTest, null, null, StrokeParameters(rotation = PI / 2, offset = 2.0, slide = 3.0))
check(near(moved.start, vec(3.0, -2.0)) && near(moved.end, vec(3.0, 8.0)))
val pulled = makeStroke(aTest, bTest, null, vec(10.0, 10.0), StrokeParameters(nextPull = 11.0))
check(abs((pulled.control - vec(5.0, 0.0)).length() - 11.0) < 1e-5)
val pushed = makeStroke(aTest, bTest, vec(0.0, 10.0), null, StrokeParameters(previousPull = -11.0))
check(near(pushed.control, vec(5.0, 0.0) + (vec(5.0, -10.0).normalized(1e-12f) * 11.0)))

val pointRows = knightTourRows.map { row ->
    row.map { tour ->
        tour.map { i -> vec((i % w + 0.5) * cell, (i / w + 0.5) * cell) }
    }
}
val strokeRows = pointRows.map { row ->
    row.map { points ->
        parameterSets.map { parameters ->
            (0 until points.size - 1).map { k ->
                makeStroke(points[k], points[k + 1], points.getOrNull(k - 1), points.getOrNull(k + 2), parameters)
            }
        }
    }
}
val tiles = strokeRows.flatMapIndexed { r, row ->
    row.flatMapIndexed { c, layers ->
        val background = fline {
            moveTo(0, 0)
            lineTo(boardW, 0)
            lineTo(boardW, boardH)
            lineTo(0, boardH)
            close()
            filled = true
            stroked = false
            color = vec(1.0, 1.0, 1.0, 0.06)
        }
        val paths = layers.mapIndexed { layerIndex, strokes ->
            fline {
                for (q in strokes) {
                    moveTo(q.start.x, q.start.y)
                    // Exact quadratic represented as a cubic (no sampling approximation).
                    val c1 = q.start + (q.control - q.start) * (2.0 / 3.0)
                    val c2 = q.end + (q.control - q.end) * (2.0 / 3.0)
                    cubicTo(c1.x, c1.y, c2.x, c2.y, q.end.x, q.end.y)
                }
                thickness = layerWidths[layerIndex]
                color = vec(9.98 / (10 + r + c), 0.65, 0.3, 0.9)
            }
        }
        (listOf(background) + paths).map { it + vec(c * (w * cell + gap), r * (h * cell + gap), 0.0) }
    }
}
lines(*tiles.toTypedArray(), fitToSize = true)
println(
    "Parametric quadratic moves: " +
        strokeRows.sumOf { row -> row.sumOf { layers -> layers.sumOf { it.size } } } +
        ". " +
        parameterSets +
        ". Layers: $layerCount. Unclamped geometry and hold-last checks passed; inherited tours unchanged.",
)