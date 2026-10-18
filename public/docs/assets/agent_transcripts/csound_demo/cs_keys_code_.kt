// Launch "pluck" first, then launch this: MIDI notes into its massign'ed instr Key.
val pluck = csoundOf("pluck").csound
val notes = [62, 65, 69, 72, 69, 65]
var i = 0
loop {
    val note = notes[i++ % notes.size]
    pluck.noteOn(0, note, 90)
    try {
        delay(220)
    } finally {
        // Stop can cancel the delay; release this run's note before leaving.
        pluck.noteOff(0, note)
    }
    delay(30)
}
