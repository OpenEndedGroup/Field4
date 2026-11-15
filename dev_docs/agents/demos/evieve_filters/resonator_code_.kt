// Three Resonators on white noise, each frequency drifting on its own slow sine,
// with the 1 kHz resonator's response at three decays plotted beside it.
fun responseDb(filter: SignalProcessor) =
    fft(impulseResponse(filter, 48_000), 48_000.0).magnitudeDbOneSided().take(5000)

showSignals(
    "decay 0.02 s" to responseDb(Resonator(1000.p, 0.02.p)),
    "decay 0.1 s" to responseDb(Resonator(1000.p, 0.1.p)),
    "decay 0.5 s" to responseDb(Resonator(1000.p, 0.5.p)),
    channel = "main",
    xTitle = "frequency (Hz)",
)
val noise = WhiteNoise() * 0.3f
fun band(f: Float, drift: Float, rate: Float, decay: Float) =
    noise / Resonator(Param(f).also { it.mod = SineOsc(rate.p) * drift }, decay.p)
audio(band(300f, 80f, 0.2f, 0.6f) + band(750f, 150f, 0.45f, 0.4f) + band(2100f, 300f, 0.7f, 0.3f), gain = 0.5f)
