/*
The same staccato arpeggio three times. With the pedal down the dampers stay off every
string, so the struck notes ring on and the unstruck strings answer them. The third take
keeps the pedal down but turns the sympathetic strings off (coupling = 0).
*/
val arpeggio = pat("[c2 g2 e3 c4 g4 e5 ~ ~]")
fun take(pedal: Float, coupling: Float) = pianoRender(5.0, Piano(sampleRate = 44_100, room = Param(0f), pedal = Param(pedal), coupling = Param(coupling))) {
    play(arpeggio, cycles = 1, cps = 0.5, legato = 0.15, startFrame = 0)
}
fun tailDb(r: PianoRender): Double {
    val x = r.mono
    val from = (2.5 * r.sampleRate).toInt()
    val e = (from until x.size).sumOf { x[it].toDouble() * x[it] } / (x.size - from)
    return 10 * kotlin.math.log10(e + 1e-30)
}
val up = take(0f, 1f)
val down = take(1f, 1f)
val dry = take(1f, 0f)
showPianoAudio(up, "pedal up")
showPianoAudio(down, "pedal down")
showPianoAudio(dry, "pedal down, no sympathetic strings")
["pedal up", "pedal down", "pedal down, no sympathetic strings"].forEach { displayOpen(it) }
println(
    "level from 2.5 s to 5 s: pedal up %.1f dB, down %.1f dB, down without sympathetic strings %.1f dB"
        .format(tailDb(up), tailDb(down), tailDb(dry)),
)
