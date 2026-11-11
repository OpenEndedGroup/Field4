/*
One piano played into the bridge of another whose pedal is down: the second piano's
strings answer every chord. Then into a prepared piano, whose strings answer in its own
tuning. `toMono()` turns any stereo source into a signal.
*/
fun through(preparations: List<PianoPreparation>): PianoRender {
    val player = Piano(sampleRate = 44_100, room = Param(0f), dry = Param(1f), micPosition = Param(0.1f))
    player.play(pat("<[a2,e3] [f2,c3]> [e4 a4 c5 e5]"), cycles = 4, cps = 0.8, startFrame = 0)
    val resonator = Piano(
        sampleRate = 44_100,
        pedal = Param(1f),
        coupling = Param(6f),
        soundboard = Param(0.5f),
        room = Param(0.35f),
        preparations = preparations,
        bridgeInput = Param(0f).modulate(player.toMono() * 4f),
    )
    return pianoRender(8.0, resonator)
}
showPianoAudio(through(emptyList()), "through")
showPianoAudio(through(listOf(PianoPreparation.bolt(36..72, at = 0.28, grams = 6.0))), "through prepared")
["through", "through prepared"].forEach { displayOpen(it) }
