/*
The identical pattern, mixed from the genuine Tidal sample library — offline, with no
audio device. Compare the synth and Dirt audio boxes to hear the two render paths.

No library on this machine? The synth fallback plays, and describe() will say so and
name the override rather than leaving you to guess from the sound.
*/
val played = showDirtAudio(pat("bd ~ sd ~, hh*8") with gain(0.9), cycles = 4, cps = 2.0)
println(played.describe())
println("usedSamples = ${played.usedSamples}")
displayOpen("main")
