// A pattern on the piano. Atoms are note names or MIDI numbers; `,` stacks a chord.
val tune = pat("<[c3,g3] [a2,e3] [f2,c3] [g2,d3]> [e4 g4 c5 g4]")
val r = pianoRender(11.0, Piano(sampleRate = 44_100)) { play(tune, cycles = 8, cps = 0.8, startFrame = 0) }
showPianoAudio(r, "piece")
displayOpen("piece")
