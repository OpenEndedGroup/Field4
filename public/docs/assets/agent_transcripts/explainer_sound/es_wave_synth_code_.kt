// Three keys, each a repeating pattern of the plate, and a fourth that plays the sum of the first two
// as one pattern of its own. Each row is twenty milliseconds of a key;
// the bottom row, below the line, is what you hear.
// An invisible frame the size of the figure, so its bounds, and so its scale, never change.
val frame = fline { rect(0.0, 0.0, 640.0, 300.0); thickness = 1.0; color = vec(1.0, 1.0, 1.0, 0.0) }

fun square(t: Double) = if (t - floor(t) < 0.5) 1.0 else -1.0
fun triangle(t: Double) = 1 - 4 * abs(t - floor(t + 0.5))
fun saw(t: Double) = 2 * (t - floor(t + 0.5))

val patterns = listOf<(Double) -> Double>(
    { t -> 0.5 * square(220 * t) },
    { t -> 0.5 * triangle(330 * t) },
    { t -> 0.5 * saw(275 * t) },
    { t -> 0.5 * square(220 * t) + 0.5 * triangle(330 * t) },
)
val names = ["A", "B", "C", "A + B"]

// The fourth key reads one cycle of A + B from a table: 220 and 330 Hz repeat together 110 times a second.
val table = FloatArray(2048) { patterns[3](it / 2048.0 / 110).toFloat() }
val voices = listOf<Signal>(
    SquareOsc(Param(220f)) * 0.5, TriOsc(Param(330f)) * 0.5, SawOsc(Param(275f)) * 0.5, WavetableOsc(table, Param(110f)),
)
val gates = 4 { Gate() }
val held = BooleanArray(4)

fun row(y: Double, f: (Double) -> Double, c: Vec4, scale: Double, width: Double) = fline {
    fun at(x: Int) = (y - scale * f(0.02 * x / 640)).coerceIn(2.0, 298.0)
    moveTo(0.0, at(0))
    for (x in 1..640) lineTo(x.toDouble(), at(x))
    thickness = width
    color = c
}

fun draw() {
    val cyan = vec(0.4, 0.9, 1.0, 1.0)
    val idle = vec(0.4, 0.9, 1.0, 0.35)
    val rows = 4 { i -> row(24.0 + 44 * i, patterns[i], if (held[i]) cyan else idle, if (i == 3) 19.0 else 38.0, if (held[i]) 3.0 else 2.0) }
    val sum = row(250.0, { t -> (0..3).sumOf { if (held[it]) patterns[it](t) else 0.0 } }, vec(1.0, 0.6, 0.2, 1.0), 40.0, 3.0)
    val rule = fline { moveTo(0.0, 198.0); lineTo(640.0, 198.0); thickness = 1.0; color = vec(1.0, 1.0, 1.0, 0.3) }
    lines([frame] + rows + rule + sum, channel = "patterns", w = 640.0, h = 300.0)
}

draw()
controlGroup {
    keys(*names.toTypedArray(), letters = "asdf") { key, down ->
        held[key] = down
        if (down) gates[key].open() else gates[key].close()
        draw()
    }
}

audio(4 { i -> voices[i] * adsr(0.01, 0.05, 1.0, 0.2, gates[i]) }.reduce { a, b -> a + b } / 2.0, gain = 0.15f)
