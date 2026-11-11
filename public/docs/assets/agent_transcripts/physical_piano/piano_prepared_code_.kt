/*
A prepared piano: bolts on the middle strings, rubber in the bass, a loose nut in the
treble. The phrase plain, then prepared, and the spectrum of A4 with and without a bolt.
*/
val tune = pat("<[a2,e3] [f2,c3]> [e4 a4 c5 e5]")
fun take(p: Piano) = pianoRender(6.0, p) { play(tune, cycles = 4, cps = 0.8, startFrame = 0) }
val preparations = listOf(
    PianoPreparation.bolt(52..72, at = 0.28, grams = 4.0),
    PianoPreparation.rubber(36..51, at = 0.13, amount = 25.0),
    PianoPreparation.rattle(73..88, at = 0.2, level = 0.8, gapMm = 0.1),
)
showPianoAudio(take(Piano(sampleRate = 44_100)), "plain")
showPianoAudio(take(Piano(sampleRate = 44_100, preparations = preparations)), "prepared")
fun spectrum(grams: Double, title: String): String {
    val bolt = PianoPreparation.bolt(69..69, at = 0.3, grams = grams)
    val r = pianoRender(1.2, Piano(sampleRate = 44_100, room = Param(0f), preparations = listOf(bolt))) {
        noteOn(69, 0.7, 0)
    }
    return pianoPlotSvg(
        pianoSpectrumDb(r.mono, startSample = 13_230, fftSize = 32_768).copyOfRange(0, 3_715),
        title,
        height = 130,
        xLabel = "0-5 kHz",
        yLabel = "dB",
    )
}
display("bolt", spectrum(0.0, "A4") + spectrum(4.0, "A4, 4 g bolt at x = 0.3"))
["plain", "prepared", "bolt"].forEach { displayOpen(it) }
