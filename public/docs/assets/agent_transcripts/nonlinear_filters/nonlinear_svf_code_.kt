// A self-oscillating ZdfSvf band-pass: faint noise in, a sine-like tone at the cutoff out.
// Its saturating damping holds the oscillation steady; the pitch slider glides over 40 ms.
val pitch = Param(220f)
val resonance = Param(1.05f)
slider("pitch (Hz)", min = 55.0, max = 1760.0, initialValue = 220.0) { pitch.base = it.toFloat() }
slider("resonance", min = 0.9, max = 1.2, initialValue = 1.05) { resonance.base = it.toFloat() }
val voice = WhiteNoise() * 0.02f / ZdfSvf(SVFMode.BP, pitch.smoothed(0.04f), resonance.smoothed(0.04f))
audio(voice, gain = 0.3f)
