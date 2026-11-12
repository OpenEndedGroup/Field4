/*
Two of Piano's inputs driven by oscillators. The string stiffness swings by 0.9 either side
of normal 110 times a second, which puts sidebands on every ringing partial. The hammer
hardness drifts between a tenth and twice normal every few seconds, read at each strike.
*/
val stiffness = Param(1f).modulate(SineOsc(freq = Param(110f)) * 0.9f)
val hammer = Param(1.05f).modulate(SineOsc(freq = Param(0.15f)) * 0.95f)
val piano = Piano(sampleRate = 44_100, inharmonicity = stiffness, hardness = hammer)
val r = pianoRender(10.0, piano) { play(pat("[a2,e3,a3] ~ [d3,a3,f4] ~"), cycles = 4, cps = 0.4, startFrame = 0) }
showPianoAudio(r, "wobble")
val spec = pianoSpectrumDb(r.mono, startSample = 44_100, fftSize = 32_768)
display(
    "wobble spectrum",
    pianoPlotSvg(spec.copyOfRange(0, 3_715), "1.0-1.74 s, 0-5 kHz", xLabel = "Hz", yLabel = "dB"),
)
displayOpen("wobble")
displayOpen("wobble spectrum")
