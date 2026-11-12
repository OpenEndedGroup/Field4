/*
The same phrase twice: as the audience hears it, through the soundboard and room, and
through a microphone a few centimetres over the strings, 8% of the way along them, with
the hammer thump and the keys turned up and the room off.
*/
val tune = pat("<[a2,e3] [f2,c3]> [e4 a4 c5 e5]")
fun take(p: Piano) = pianoRender(6.0, p) { play(tune, cycles = 4, cps = 0.8, startFrame = 0) }
val board = take(Piano(sampleRate = 44_100))
val close = take(
    Piano(
        sampleRate = 44_100,
        dry = Param(1f),
        room = Param(0f),
        micPosition = Param(0.08f),
        thump = Param(1f),
        keyNoise = Param(1f),
        damperNoise = Param(2f),
    ),
)
showPianoAudio(board, "soundboard")
showPianoAudio(close, "close mic")
fun spectrum(r: PianoRender, title: String) = pianoPlotSvg(
    pianoSpectrumDb(r.mono, startSample = 22_050, fftSize = 32_768).copyOfRange(0, 3_715),
    title,
    height = 140,
    xLabel = "0-5 kHz",
    yLabel = "dB",
)
display("spectra", spectrum(board, "soundboard") + spectrum(close, "close mic at 0.08"))
["soundboard", "close mic", "spectra"].forEach { displayOpen(it) }
