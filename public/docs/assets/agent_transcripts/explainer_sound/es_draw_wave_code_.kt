// Draw one cycle and hear it repeated. Drag the points of the curve, or draw a new stroke across the
// box; the keys play whatever is drawn at three pitches. The bars are how much of each multiple of
// the key's frequency the shape holds.
val table = FloatArray(2048)
val gates = 3 { Gate() }
val voices = [196f, 247f, 294f].map { WavetableOsc(table, Param(it)) }

// A starting shape: a lopsided hump, as 25 draggable points across one cycle.
fun starter() = fline {
    moveTo(0.0, 140.0)
    for (i in 1..24) {
        val p = i / 24.0
        lineTo(640 * p, 140.0 - 90 * sin(2 * PI * p) - 30 * sin(4 * PI * p))
    }
    thickness = 2.5
    color = vec(0.4, 0.9, 1.0, 1.0)
}

// One cycle, read left to right, as samples between -1 and 1 with no offset.
fun samples(shape: FLine): FloatArray {
    val points = shape.nodes.map { it.to.x.toDouble() to it.to.y.toDouble() }.sortedBy { it.first }
    val raw = DoubleArray(table.size) { i ->
        val x = 640.0 * i / table.size
        val j = points.indexOfFirst { it.first >= x }
        when {
            j < 0 -> (140 - points.last().second) / 100
            j == 0 -> (140 - points.first().second) / 100
            else -> {
                val (ax, ay) = points[j - 1]
                val (bx, by) = points[j]
                val t = if (bx > ax) (x - ax) / (bx - ax) else 0.0
                (140 - (ay + (by - ay) * t)) / 100
            }
        }
    }
    val mean = raw.average()
    val peak = raw.maxOf { abs(it - mean) }.coerceAtLeast(1e-6)
    return FloatArray(raw.size) { ((raw[it] - mean) / peak).toFloat() }
}

// How much of each of the first 24 multiples of the base frequency is in the cycle.
fun partials() {
    val n = table.size
    val amps = (1..24).map { k ->
        var re = 0.0
        var im = 0.0
        for (i in 0 until n) { re += table[i] * cos(2 * PI * k * i / n); im -= table[i] * sin(2 * PI * k * i / n) }
        2 * sqrt(re * re + im * im) / n
    }
    val bars = amps.mapIndexed { i, a ->
        fline { rect(10.0 + i * 26.0, 150 - 140 * a.coerceAtMost(1.0), 18.0, 140 * a.coerceAtMost(1.0)); filled = true; stroked = false; color = vec(1.0, 0.6, 0.2, 1.0) }
    }
    val axis = fline { moveTo(0.0, 150.0); lineTo(640.0, 150.0); thickness = 1.0; color = vec(1.0, 1.0, 1.0, 0.35) }
    lines(bars + axis, channel = "partials", w = 640.0, h = 160.0)
}

fun use(shape: FLine) {
    samples(shape).copyInto(table)
    partials()
}

fun show(shape: FLine) {
    val axis = fline { moveTo(0.0, 140.0); lineTo(640.0, 140.0); thickness = 1.0; color = vec(1.0, 1.0, 1.0, 0.35) }
    lines(axis, shape, channel = "wave", w = 640.0, h = 280.0, editable = true, drawing = true) { edited ->
        // A new stroke replaces the shape; a dragged point reshapes it.
        val latest = edited.last()
        use(latest)
        if (edited.size > 2) show(latest.copy().apply { thickness = 2.5; color = vec(0.4, 0.9, 1.0, 1.0) })
    }
}

val first = starter()
show(first)
use(first)
controlGroup {
    keys("low", "middle", "high", letters = "wer") { key, down -> if (down) gates[key].open() else gates[key].close() }
}

audio(3 { i -> voices[i] * adsr(0.01, 0.05, 1.0, 0.25, gates[i]) }.reduce { a, b -> a + b } / 3.0, gain = 0.15f)
