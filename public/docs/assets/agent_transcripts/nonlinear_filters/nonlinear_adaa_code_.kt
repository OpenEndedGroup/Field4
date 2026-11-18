/*
A sine driven into tanh twice, once naive and once with ADAA; the crossfade slider picks
between them. Raise the frequency past 3 kHz and listen for
the inharmonic tones under the naive one.
*/
val freq = Param(1000f)
val mix = Param(0f)
slider("frequency (Hz)", min = 100.0, max = 8000.0, initialValue = 1000.0) { freq.base = it.toFloat() }
slider("naive 0 … ADAA 1", min = 0.0, max = 1.0, initialValue = 0.0) { mix.base = it.toFloat() }
val sine = SineOsc(freq.smoothed(0.03f))
val smoothMix = mix.smoothed(0.03f)
val naive = sine / Waveshaper.tanh(6.p)
val adaa = sine / AdaaShaper(AdaaShape.TANH, 6.p)
audio(naive * (Param(1f) - smoothMix) + adaa * smoothMix, gain = 0.15f)
