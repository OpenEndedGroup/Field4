// The second, finer fallback. `bd` and `hh` are in every Dirt install; `zzzznotadrum` is in
// none, so THAT EVENT ALONE is voiced by the synth and mixed on top of the sampled ones.
// A pattern must not go silent just because the library loaded successfully.
val mixed = showDirtAudio(
    pat("bd zzzznotadrum bd hh") with gain(0.9),
    cycles = 4, cps = 2.0,
    voice = patVoice(wave = PatWave.SQUARE, baseHz = 330.0, decay = 0.05, sustain = 0.3),
)
println(mixed.describe())
println("matched   = ${mixed.matched}")
println("unmatched = ${mixed.unmatched}")   // <- the tokens that fell back, named
displayOpen("main")
