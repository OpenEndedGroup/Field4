val raw = mutableListOf<Float>()
val smooth = mutableListOf<Float>()
val filtered = mutableListOf<Float>()

val ema = EMAFilter(alpha = 0.08f)
val oneEuro = OneEuroFilter(minCutoff = 1.0f, beta = 0.02f, sampleRate = 120f)
val band = BandPassFilter(frequency = 5f, sampleRate = 120f, q = 0.8f)

for (i in 0 until 420) {
    val t = i / 120f
    val signal =
        (sin(2.0 * PI * 1.2 * t).toFloat() * 0.7f +
            sin(2.0 * PI * 4.7 * t + 0.3).toFloat() * 0.22f +
            (-0.12f + random().toFloat() * 0.24f))
    raw += signal
    val s = oneEuro(ema(signal))
    smooth += s
    filtered += band(s)
}

// One call puts all three streams on one axis — raw, the smoothed chain, and the band-passed
// component each get their own trace (dark theme + cyan/orange palette applied).
showSignals(
    "raw" to raw,
    "EMA + One-Euro (smoothed)" to smooth,
    "band-pass (5 Hz)" to filtered,
    channel = "main",
    xTitle = "sample",
)
