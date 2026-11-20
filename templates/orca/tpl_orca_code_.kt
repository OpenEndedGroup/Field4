// Launch, then click the grid to type into ORCA. Space plays and pauses; Cmd-G lists every
// operator. Ask AI4 (Cmd-4) to change the tune: it can read, write and command this ORCA.
val synth = PatSynthBank()
val o = orca(
    rows = 12,
    grid =
        """
    #.ORCA.IN.FIELD.#
    .
    .2C8
    D2.8TCDEGAGED
    .:04
    .
    D8
    .:12C
""",
)
o.onNote {
    synth.play(it)
    markdown("heard", "`${it.note}${it.octave}` on channel ${it.channel}, MIDI ${it.midi}")
}
displayOpen("heard")
o.exposeTools()

synth
