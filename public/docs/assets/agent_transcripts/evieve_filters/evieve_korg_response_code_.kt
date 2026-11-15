// The Korg35 (MS-20) lowpass at 1 kHz, measured: one second of impulse response, then an FFT, so
// bin k is k Hz. The tiny amplitude keeps its tanh linear, so this is the small-signal response.
fun responseDb(filter: SignalProcessor) =
    fft(impulseResponse(filter, 48_000, amplitude = 1e-4f), 48_000.0).magnitudeDbOneSided().take(5000)

showSignals(
    "resonance 0.1" to responseDb(Korg35(1000.p, 0.1.p)),
    "resonance 0.5" to responseDb(Korg35(1000.p, 0.5.p)),
    "resonance 0.95" to responseDb(Korg35(1000.p, 0.95.p)),
    "highpass 0.8" to responseDb(Korg35(1000.p, 0.8.p, Korg35Mode.HP)),
    channel = "main",
    xTitle = "frequency (Hz)",
)
