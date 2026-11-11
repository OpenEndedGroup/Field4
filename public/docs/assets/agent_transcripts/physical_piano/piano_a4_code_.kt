// A4 at velocity 0.5 for eight seconds, without the room: `./coopiano A4 0.5 8` in coopiano.
val r = pianoRender(8.0, Piano(sampleRate = 44_100, room = Param(0f))) { noteOn(69, 0.5, 0) }
showPianoAudio(r, "a4")
// The spectrum from 0.5 s to 1.24 s, up to 5 kHz: the partials stretch sharp of 440 * n.
val spec = pianoSpectrumDb(r.mono, startSample = 22_050, fftSize = 32_768)
display("spectrum", pianoPlotSvg(spec.copyOfRange(0, 3_715), "A4, 0.5-1.24 s, 0-5 kHz", xLabel = "Hz", yLabel = "dB"))
displayOpen("a4")
displayOpen("spectrum")
println("peak %.3f, %.1f s".format(r.peak, r.seconds))
