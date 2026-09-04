import afield.four.synth.*

// 3 - What MIDI hardware can this machine see, and can Field drive it?
//
// `MidiDevices.list()` reports whatever the JVM sees. The JDK always contributes
// Gervill (a software synthesizer) and the Real Time Sequencer, so the list is never
// empty - which means "the list is non-empty" is not evidence of hardware. `hardware()`
// is the filter that answers that question.
//
// On macOS the IAC driver appears as two entries with the SAME name, one output and one
// input, so a name alone does not identify a device: check the direction.
val all = MidiDevices.list()
val hardware = MidiDevices.hardware()

val rows = all.joinToString("\n") { d ->
    val direction = listOfNotNull(
        if (d.canReceive) "Field can send" else null,
        if (d.canTransmit) "Field can listen" else null
    ).joinToString(" + ")
    "| `${d.name}` | ${d.vendor} | ${d.implementation} | $direction |"
}

markdown(
    "main",
    """
    ### ${all.size} MIDI devices

    | name | vendor | implementation | direction |
    |---|---|---|---|
    $rows

    **Hardware ports:** ${if (hardware.isEmpty()) "none - nothing is plugged in, and only the JDK's software synth and sequencer are present." else hardware.joinToString { it.name } + "."}

    To drive a device, `MidiDevices.openOutput(handle)` gives a `MidiOutput` with
    `noteOn`/`noteOff`/`controlChange`/`programChange` and a blocking `play(clip)`.
    `MidiDevices.openInput(handle)` gives a `MidiInput` whose `knob(cc)` reads a
    controller normalised to 0..1 - that is the shape for driving a box property from a
    knob on a controller.

    One caveat worth knowing before you open an input: doing so starts a non-daemon
    `Java Sound MidiInDevice Thread` that outlives `close()`, so a JVM that has opened a
    MIDI input will not exit on its own.
    """.trimIndent()
)
