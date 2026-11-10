// Two sine tones a few hertz apart. Hold one key, then both: the sum swells and fades as the two
// drift in and out of step. The picture is one second of what you hear; each column spans the
// wave's highest and lowest points in a 640th of a second, so the outline is the loudness.
var difference = 3.0
val held = BooleanArray(2)
val gates = 2 { Gate() }
val second = Param(223f)

// An invisible frame the size of the figure, so its bounds, and so its scale, never change.
val frame = fline { rect(0.0, 0.0, 640.0, 240.0); thickness = 1.0; color = vec(1.0, 1.0, 1.0, 0.0) }

fun draw() {
    val f = [220.0, 220.0 + difference]
    val columns = (0 until 640).map { x ->
        val values = (0 until 24).map { k ->
            val t = (x + k / 24.0) / 640
            (0..1).sumOf { i -> if (held[i]) sin(2 * PI * f[i] * t) else 0.0 }
        }
        fline {
            moveTo(x.toDouble(), 120 - 50 * values.max())
            lineTo(x.toDouble(), 120 - 50 * values.min() + 0.5)
            thickness = 1.0
            color = vec(0.4, 0.9, 1.0, 1.0)
        }
    }
    val axis = fline { moveTo(0.0, 120.0); lineTo(640.0, 120.0); thickness = 1.0; color = vec(1.0, 1.0, 1.0, 0.35) }
    lines([frame, axis] + columns, channel = "sum", w = 640.0, h = 240.0)
}

draw()
controlGroup {
    keys("220 Hz", "220 Hz + difference", letters = "we") { key, down ->
        held[key] = down
        if (down) gates[key].open() else gates[key].close()
        draw()
    }
    slider("difference", 0.5, 20.0, step = 0.1, initialValue = 3.0) {
        difference = it
        second.base = (220 + it).toFloat()
        draw()
    }
}

val tones = listOf(SineOsc(Param(220f)), SineOsc(second))
audio(2 { i -> tones[i] * adsr(0.01, 0.05, 1.0, 0.25, gates[i]) }.reduce { a, b -> a + b } / 2.0, gain = 0.15f)
