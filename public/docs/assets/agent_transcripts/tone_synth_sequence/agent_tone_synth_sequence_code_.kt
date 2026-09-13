// {{ tone }}
// A tiny chord arpeggio played through a reverb, scheduled on the Transport.
// Tone is installed as the global `Tone` in the RemoteAR head; every name below is
// bound by afield.graphics.ToneKotlinJs and grounded against the vendored Tone.js.
onStart {
    val synth = Tone.PolySynth()
    val reverb = Tone.Reverb(2.5)
    reverb.toDestination()
    synth.connect(reverb)
    synth.volume.value = -8.0

    val transport = Tone.getTransport()
    transport.bpm.value = 96.0

    val chord = arrayOf("C4", "E4", "G4", "B4")
    var step = 0
    val event = transport.scheduleRepeat({ time ->
        val note = chord[step % chord.size]
        synth.triggerAttackRelease(note, "8n", time)
        step += 1
    }, "8n")

    // Tone.start() resumes the AudioContext; this follows the click inside the head.
    Tone.start()
    transport.start()
    log("tone: arpeggio scheduled at " + transport.bpm.value + " bpm")

    onStop {
        transport.clear(event)
        transport.stop()
        synth.dispose()
        reverb.dispose()
    }
}
