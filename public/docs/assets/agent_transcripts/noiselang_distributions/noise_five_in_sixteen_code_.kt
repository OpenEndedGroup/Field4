import afield.math.distribution.*

val steps = List(16) { bernoulli(0.4) }
val rhythm = steps.given(steps.sum() eq 5)
val hats = struct(rhythm.pat().map { it != 0.0 }, pat("hh"))
val beat = stack(hats, pat("bd"))

showPatRollOf("kick" to pat("bd"), "hats" to hats, title = "five hits in sixteen, a new five each cycle", cycles = 4)
showPatAudio(beat, cycles = 4, cps = 0.5, voice = patVoice(wave = PatWave.TRIANGLE, decay = 0.03, sustain = 0.3), channel = "audio")
