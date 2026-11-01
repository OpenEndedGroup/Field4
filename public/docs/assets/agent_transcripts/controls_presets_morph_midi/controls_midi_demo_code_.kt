var cutoff = 1000.0
var bright = false
controlGroup("synth") {
    slider("cutoff", 20.0, 20000.0, initialValue = cutoff, law = ControlLaw.LOGARITHMIC) { cutoff = it }
    checkbox("bright", bright) { bright = it }
    midiLearnButton()
}
val midi = midiLearn("synth")
midi.bind("cutoff", cc = 74)
midi.bind("bright", cc = 64)
// What a knob sends; with hardware, midi.attach(MidiDevices.openInput(MidiDevices.inputs().first())).
for (v in [0, 32, 64, 96, 127]) {
    midi.feed(0, 74, v)
    println("CC74 = $v  ->  cutoff ${cutoff.f1}")
}
midi.feed(0, 64, 127)
midi.feed(0, 74, 80)
println("bright = $bright, bindings = ${midi.bindings}")
