// What the microphone hears, taken apart into sines. Tick "listen"; each bar is how much of one
// frequency, 20 Hz wide, is in the last tenth of a second, from 0 to 2,500 Hz. A whistle stands up
// as one bar; a sung vowel as a row of bars at whole multiples of the note. Nothing is played back.
var listening = false
val mic = audioIn.scope(4096)

fun bars(amps: List<Double>) {
    // Scaled to the loudest bar, but never so far that room noise fills the picture.
    val top = amps.maxOrNull()?.coerceAtLeast(2e-3) ?: 2e-3
    val shapes = amps.mapIndexed { i, a ->
        val h = 200 * (a / top).coerceIn(0.0, 1.0)
        fline { rect(i * 5.0, 220 - h, 4.0, h.coerceAtLeast(0.5)); filled = true; stroked = false; color = vec(0.4, 0.9, 1.0, 1.0) }
    }
    // A tick every 500 Hz along the bottom.
    val ticks = fline { (0..5).forEach { k -> moveTo(k * 125.0, 224.0); lineTo(k * 125.0, 234.0) }; thickness = 1.0; color = vec(1.0, 1.0, 1.0, 0.35) }
    val frame = fline { rect(0.0, 0.0, 640.0, 240.0); thickness = 1.0; color = vec(1.0, 1.0, 1.0, 0.0) }
    lines([frame, ticks] + shapes, channel = "spectrum", w = 640.0, h = 240.0)
}

bars(List(128) { 0.0 })
controlGroup {
    checkbox("listen") { listening = it }
}

loop {
    if (listening) {
        val snap = mic.snapshot()
        val x = snap.left
        val n = x.size
        if (n > 0) {
            // A Hann window, then the strength of each 20 Hz step: a slow Fourier transform, 128 bins.
            val windowed = DoubleArray(n) { x[it] * (0.5 - 0.5 * cos(2 * PI * it / n)) }
            bars(List(128) { b ->
                val f = (b + 0.5) * 20.0 / snap.sampleRate
                var re = 0.0
                var im = 0.0
                for (i in 0 until n) { re += windowed[i] * cos(2 * PI * f * i); im -= windowed[i] * sin(2 * PI * f * i) }
                sqrt(re * re + im * im) / n
            })
        }
    }
    delay(60)
} + audio(mic, gain = 0f)
