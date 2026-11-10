// Sines at 1, 3, 5, 7… times a base frequency, each 1/n as tall, add up to a square wave. "sines" is
// how many are in the sum; the faint line is the square they are approaching.
var count = 1
var on = false
val level = Param(0f)
val weights = 50 { Param(0f) }

// An invisible frame the size of the figure, so its bounds, and so its scale, never change.
val frame = fline {
    rect(0.0, 0.0, 640.0, 260.0)
    thickness = 1.0
    color = vec(1.0, 1.0, 1.0, 0.0)
}

fun partial(i: Int) = 2 * i + 1 // 1, 3, 5, …
fun sum(t: Double) = (0 until count).sumOf { i -> 4 / PI / partial(i) * sin(2 * PI * partial(i) * t) }
fun square(t: Double) = if (t - floor(t) < 0.5) 1.0 else -1.0

fun update() {
    weights.forEachIndexed { i, w -> w.base = if (i < count) (4 / PI / partial(i)).toFloat() else 0f }
    level.base = if (on) 0.12f else 0f
    // Two cycles across the figure.
    fun wave(f: (Double) -> Double, c: Vec4, width: Double) = fline {
        moveTo(0.0, 130 - 90 * f(0.0))
        for (x in 1..640) lineTo(x.toDouble(), 130 - 90 * f(2.0 * x / 640))
        thickness = width
        color = c
    }
    lines(
        frame,
        wave(::square, vec(1.0, 1.0, 1.0, 0.35), 1.5),
        wave(::sum, vec(0.4, 0.9, 1.0, 1.0), 3.0),
        channel = "wave",
        w = 640.0,
        h = 260.0,
    )
}

update()
controlGroup {
    slider("sines", 1.0, 50.0, step = 1.0, initialValue = 1.0) {
        count = it.toInt()
        update()
    }
    checkbox("sound") {
        on = it
        update()
    }
}

// One oscillator per odd multiple of 110 Hz, each at the weight the sum gives it.
audio(50 { i -> SineOsc(Param(110f * partial(i))) * weights[i] }.reduce { a, b -> a + b } / 1.25, gain = level)
