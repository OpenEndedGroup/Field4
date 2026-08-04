// A little piece. Change any string or any number and re-run this box: the roll and the
// audio player both come from the same pattern, so they can never disagree.
val kick = struct(euclid(3, 8), pat("bd"))
val snare = pat("~ sd ~ sd") with gain(0.8)
val hats = pat("hh*16?0.3") with gain(sine().range(0.1, 0.55))
val drums = stack(kick, snare, hats).off(1, 16) { it with gain(0.18) }

// A bass line spelled in a scale, with a filter that opens across two cycles.
val bass = run(8).scale("minPent", root = 36).texts()
    .with(sustain(0.5), cutoff(saw().rangex(200, 2400)))

// A chord pad, voice-led so it does not leap, arpeggiated up.
val pad = chords("<c'min7 a'maj7> f'maj7").voiceLead(center = 67).arpeggiate().texts()

showPatRollOf(
    "drums" to drums,
    "bass" to bass,
    "pad" to pad,
    title = "a groove, from the arrangement surface",
    subtitle = "euclid / off / ?degrade / scale / voiceLead / arpeggiate",
    cycles = 2,
)
displayOpen("main")

showPatAudio(drums, cycles = 4, cps = 2.0,
    voice = patVoice(decay = 0.01, sustain = 0.08), channel = "drums")
showPatAudio(bass, cycles = 4, cps = 2.0,
    voice = patVoice(wave = PatWave.SAW, decay = 0.03, sustain = 0.5), channel = "bass")
showPatAudio(pad, cycles = 4, cps = 2.0,
    voice = patVoice(wave = PatWave.TRIANGLE, decay = 0.05, sustain = 0.6), channel = "pad")
displayOpen("drums"); displayOpen("bass"); displayOpen("pad")

println("drums: ${drums.events(PatArc(PatQ.ZERO, PatQ.ONE)).size} events/cycle")
println("bass : ${bass.events(PatArc(PatQ.ZERO, PatQ.ONE)).map { it.value }}")
println("pad  : ${pad.events(PatArc(PatQ.ZERO, PatQ.ONE)).map { it.value }}")
