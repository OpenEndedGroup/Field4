// Run this, open its AI Chat with Cmd-4 and ask it to play. AI4 writes into the grid while the
// music runs; rewinding the chat puts the board back. Channels: 0 lead, 1 bass, 2 hat, 3 square.
val synth = PatSynthBank(voices = 24)
val o =
    orca(
        rows = 16,
        grid =
            """
    #.JAM.#
    .
    .4C4
    D4.4TCEGE
    .:03
""",
    )
o.onNote {
    when (it.channel) {
        0 -> synth.play(it, pan = 0.4, wave = PatWave.TRIANGLE)
        1 -> synth.play(it, pan = 0.5, wave = PatWave.SINE)
        2 -> synth.play(it, pan = 0.65, wave = PatWave.NOISE, gain = 0.3, cutoffHz = 9000.0)
        else -> synth.play(it, pan = 0.6, wave = PatWave.SQUARE, gain = 0.4, cutoffHz = 3000.0)
    }
}
o.exposeTools(
    about =
        "Channel 0 is a soft triangle lead, 1 a sine bass (octaves 2 and 3 suit it), " +
            "2 a noise hi-hat (any note works), 3 a bright square for a second melody.",
)
synth
