// Sixteen hats a cycle. `whenCycle` is the existing operator for "from cycle 2 on, play
// this instead", so the first half of the file is what the old surface could express and
// the second half is the identical pattern with the gain following a sine.
val hats = pat("hh*16")
val demo = hats.whenCycle({ it >= 2 }) { hats with gain(sine().range(0.08, 1.0)) }

showPatRollOf(
    "flat (cycles 0-1)" to hats,
    "gain(sine()) (cycles 2-3)" to (hats with gain(sine().range(0.08, 1.0))),
    title = "the same sixteen hats, before and after",
    subtitle = "rectangle height is the gain each note latched",
    cycles = 2, paramKey = "gain",
)
displayOpen("main")

val audio = showPatAudio(
    demo, cycles = 4, cps = 2.0,
    voice = patVoice(wave = PatWave.NOISE, decay = 0.006, sustain = 0.05),
    channel = "sound",
)
displayOpen("sound")

// The same measurement the smoke test makes, live: per-note level, flat then modulated.
val onsets = PatSoundDocs.onsetPeaks(audio.mono)
val flat = onsets.take(onsets.size / 2).map { it.rms }
val modulated = onsets.drop(onsets.size / 2).map { it.rms }
println("${onsets.size} hats detected")
println("flat half level CV      = %.4f".format(PatSoundDocs.variation(flat)))
println("modulated half level CV = %.4f".format(PatSoundDocs.variation(modulated)))
