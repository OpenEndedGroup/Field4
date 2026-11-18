// A 55 Hz saw through the zero-delay-feedback ladder. The sliders write plain Params;
// smoothed() glides each change over 30 ms so dragging a slider never clicks.
val cutoff = Param(800f)
val resonance = Param(0.7f)
val drive = Param(1.5f)
slider("cutoff (Hz)", min = 60.0, max = 6000.0, initialValue = 800.0) { cutoff.base = it.toFloat() }
slider("resonance (sings past 1)", min = 0.0, max = 1.2, initialValue = 0.7) { resonance.base = it.toFloat() }
slider("drive", min = 0.2, max = 8.0, initialValue = 1.5) { drive.base = it.toFloat() }
val voice = SawOsc(55.p) * 0.5f / ZdfLadder(cutoff.smoothed(0.03f), resonance.smoothed(0.03f), drive.smoothed(0.03f))
audio(voice, gain = 0.3f)
