// Three keys. Hold one to hear it; the line is the shape of the air pressure it makes.
val shapes = listOf<(Double) -> Double>(
    { t -> sin(2 * PI * t) },
    { t -> 2 * abs(2 * (t - floor(t + 0.5))) - 1 },
    { t -> 2 * (t - floor(t + 0.5)) },
)
val pitches = [196.0, 247.0, 294.0]
val voices = listOf<Signal>(SineOsc(Param(196f)), TriOsc(Param(247f)), SawOsc(Param(294f)) / lpf(2400))
val gates = 3 { Gate() }
val held = BooleanArray(3)

// Twenty milliseconds of whatever is held, summed.
// An invisible frame the size of the figure, so its bounds, and so its scale, never change.
val frame = fline {
    rect(0.0, 0.0, 640.0, 240.0)
    thickness = 1.0
    color = vec(1.0, 1.0, 1.0, 0.0)
}

fun draw() = lines(
    frame,
    fline {
        moveTo(0.0, 120.0)
        for (x in 1..640) {
            val t = 0.02 * x / 640
            val y = (0..2).sumOf { i -> if (held[i]) shapes[i](pitches[i] * t) / 3 else 0.0 }
            lineTo(x.toDouble(), 120.0 - 200.0 * y)
        }
        thickness = 3.0
        color = vec(0.4, 0.9, 1.0, 1.0)
    },
    channel = "wave",
    w = 640.0,
    h = 240.0,
)

draw()
controlGroup {
    keys("low", "middle", "high", letters = "wer") { key, down ->
        held[key] = down
        if (down) gates[key].open() else gates[key].close()
        draw()
    }
}

audio(3 { i -> voices[i] * adsr(0.01, 0.05, 1.0, 0.25, gates[i]) }.reduce { a, b -> a + b } / 3.0, gain = 0.15f)
