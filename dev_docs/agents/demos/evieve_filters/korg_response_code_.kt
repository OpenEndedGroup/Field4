// The Korg35 (MS-20) lowpass at 1 kHz, measured: an impulse through a fresh filter, then a DFT.
// The tiny amplitude keeps its tanh linear, so this is the small-signal response.
val freqs = logFrequencies(20.0, 20_000.0, 200)
showSignals(
    "resonance 0.1" to frequencyResponseDb(Korg35(1000.p, 0.1.p), freqs, amplitude = 1e-4f),
    "resonance 0.5" to frequencyResponseDb(Korg35(1000.p, 0.5.p), freqs, amplitude = 1e-4f),
    "resonance 0.95" to frequencyResponseDb(Korg35(1000.p, 0.95.p), freqs, amplitude = 1e-4f),
    "highpass 0.8" to frequencyResponseDb(Korg35(1000.p, 0.8.p, Korg35Mode.HP), freqs, amplitude = 1e-4f),
    channel = "main",
    xTitle = "frequency, 20 Hz to 20 kHz (log)",
)
