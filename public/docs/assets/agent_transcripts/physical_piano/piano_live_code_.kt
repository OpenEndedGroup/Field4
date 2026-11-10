// The piano on the audio engine. Move the sliders while it plays: each one is a Param, read
// every sample, so a slider and an oscillator are the same kind of input.
val pedal = Param(0f)
val stiffness = Param(1f)
val board = Param(1f)
slider("pedal", 0.0, 1.0, initialValue = 0.0, channel = "pedal", onUpdate = { pedal.base = it.toFloat() })
slider(
    "string stiffness",
    0.0,
    6.0,
    initialValue = 1.0,
    channel = "stiffness",
    onUpdate = { stiffness.base = it.toFloat() },
)
slider("soundboard", 0.0, 2.0, initialValue = 1.0, channel = "soundboard", onUpdate = { board.base = it.toFloat() })
listOf("pedal", "stiffness", "soundboard").forEach { displayOpen(it) }
val piano = Piano(pedal = pedal, inharmonicity = stiffness, soundboard = board)
piano.play(pat("<[c3,g3] [a2,e3] [f2,c3] [g2,d3]> [e4 g4 c5 g4]"), cycles = 32, cps = 0.8)
audio(piano, gain = 1f)
