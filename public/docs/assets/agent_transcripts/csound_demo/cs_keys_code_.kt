// Launch "pluck" first, then launch this: MIDI notes into its massign'ed instr Key.
val pluck = csoundOf("pluck").csound
val notes = listOf(62, 65, 69, 72, 69, 65)
var i = 0
loop {
    val note = notes[i++ % notes.size]
    pluck.noteOn(0, note, 90)
    delay(220)
    pluck.noteOff(0, note)
    delay(30)
}
