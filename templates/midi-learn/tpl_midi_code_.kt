// Press MIDI learn, touch a control, then turn a knob: the two are bound, and the binding is
// saved with the box. Launch to listen to the first MIDI input; stop to close it.
var cutoff = 1000.0
var bright = false

controlGroup("synth") {
    slider("cutoff", 20.0, 20000.0, initialValue = cutoff, law = ControlLaw.LOGARITHMIC) { cutoff = it }
    checkbox("bright", bright) { bright = it }
    midiLearnButton()
}
val midi = midiLearn("synth")

frames {
    val device = MidiDevices.inputs().firstOrNull()
    val input = device?.let(MidiDevices::openInput)
    input?.let(midi::attach)
    println(device?.let { "listening to ${it.name}" } ?: "no MIDI input on this machine")
    try {
        while (frame()) {}
    } finally {
        input?.close()
    }
}
