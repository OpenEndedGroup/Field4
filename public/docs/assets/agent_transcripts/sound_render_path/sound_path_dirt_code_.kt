// The identical pattern, mixed from the genuine Tidal sample library — offline, with no
// audio device. Play box 1 and box 2 back to back: that difference is the whole point.
//
// No library on this machine? You will hear box 1 again, and describe() will say so and
// name the override rather than leaving you to guess from the sound.
val played = showDirtAudio(pat("bd ~ sd ~, hh*8") with gain(0.9), cycles = 4, cps = 2.0)
println(played.describe())
println("usedSamples = ${played.usedSamples}")
displayOpen("main")
