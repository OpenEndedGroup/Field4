val voice = Impulse(120.p) * 4f / FormantFilter(0.p) // the vowel "a"
val frame = renderSignalsOffline(0.2, voice)[0].copyOfRange(4096, 4096 + 2048)

val bins = 214 // 0 .. 5 kHz at 48 kHz / 2048
val spectrum =
    fft(DoubleArray(frame.size) { frame[it].toDouble() }, WindowType.HANN, 48_000.0).magnitudeDbOneSided().take(bins)
fun envelope(order: Int) = Lpc.analyze(frame, order).envelopeDb(1025).take(bins)
fun peakAtZero(db: List<Double>) = db.max().let { top -> db.map { it - top } }

showSignals(
    "spectrum of the frame" to peakAtZero(spectrum),
    "LPC order 12" to peakAtZero(envelope(12)),
    "LPC order 52" to peakAtZero(envelope(52)),
    x = List(bins) { it * 48_000.0 / 2048 },
    channel = "main",
    xTitle = "frequency (Hz)",
    yTitle = "dB",
)
