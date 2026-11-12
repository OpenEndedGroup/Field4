// Two close microphones, one near the hammers and one further along, panned apart; then two
// on single strings of each note, so the unison strings beat between the speakers.
val tune = pat("<[a2,e3] [f2,c3]> [e4 a4 c5 e5]")
fun take(p: Piano) = pianoRender(6.0, p) { play(tune, cycles = 4, cps = 0.8, startFrame = 0) }
val pair = [PianoMic(position = Param(0.08f), pan = Param(-0.8f)), PianoMic(position = Param(0.33f), pan = Param(0.8f))]
val strings = [
    PianoMic(position = Param(0.12f), pan = Param(-1f), string = 0),
    PianoMic(position = Param(0.12f), pan = Param(1f), string = 2),
]
showPianoAudio(take(Piano(sampleRate = 44_100, dry = Param(1f), room = Param(0.12f), mics = pair)), "pair")
showPianoAudio(
    take(Piano(sampleRate = 44_100, dry = Param(1f), room = Param(0.12f), mics = strings)),
    "strings 0 and 2",
)
["pair", "strings 0 and 2"].forEach { displayOpen(it) }
