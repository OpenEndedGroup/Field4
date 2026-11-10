import afield.math.distribution.*

val scale = categorical(listOf(0, 3, 5, 7, 10, 12), listOf(4, 1, 2, 3, 1, 2))
val tune = (60 + scale).pat().segment(8).texts()

showPatRollOf("60 + scale" to tune, title = "eight draws a cycle from a weighted pentatonic", cycles = 4, pitched = true)
showPatAudio(tune, cycles = 4, cps = 0.5, voice = patVoice(wave = PatWave.TRIANGLE, decay = 0.05, sustain = 0.7), channel = "audio")
