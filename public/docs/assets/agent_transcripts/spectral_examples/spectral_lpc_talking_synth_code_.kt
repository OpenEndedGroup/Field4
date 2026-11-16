// The talking synth: a synthesized voice's LPC envelope on a chord of four saws.
val pitch = Param(120f).also { it.mod = SineOsc(5.p) * 3f + SineOsc(0.2.p) * 15f }
val vowel = Param(0f).also { it.mod = Phasor(0.4.p) * 4f }
val voice = (Impulse(pitch) * 4f + WhiteNoise() * 0.02f) / FormantFilter(vowel)
val chord = SawOsc(110.p) + SawOsc(138.6.p) + SawOsc(164.8.p) + SawOsc(220.5.p)
val talking = chord / LpcVocoder(voice)

// Five seconds rendered offline into a player in this box's output.
val clip = renderSignalsOffline(5.0, talking)[0]
display(
    "talking synth",
    afield.audio.engine.WavFloatArrayWriter.encodeStereo16(clip, clip, 48_000, normalizePeak = 0.7f),
)
displayOpen("talking synth")

// Flip to true to hear it live instead. False by default because `audio { }` opens a real output
// device, and this transcript is replayed end to end on machines that have none.
val live = false
if (live) audio { chord / LpcVocoder(voice) * 0.3f }
