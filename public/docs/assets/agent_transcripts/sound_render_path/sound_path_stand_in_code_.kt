// patVoice: one oscillator, one one-pole lowpass, one exponential-decay envelope, per
// event. `bd` is a 62 Hz sine; `sd` and `hh` are filtered white noise. This is the default
// everywhere in Field's docs, and it sounds like bursts of white noise because it is.
val audio = showPatAudio(pat("bd ~ sd ~, hh*8") with gain(0.9), cycles = 4, cps = 2.0)
println("stand-in: %.2fs, peak %.3f".format(audio.frames / 44100.0, PatSoundDocs.peak(audio.mono)))
displayOpen("main")
