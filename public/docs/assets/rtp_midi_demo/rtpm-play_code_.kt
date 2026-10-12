import afield.rtpmidi.*

// Play an arpeggio from the peer session into the "Field" session; box 1 shows each note as it
// arrives. Pointed at a Mac's session instead, these notes reach any DAW listening to it.
val other = rtpMidi("Field peer", port = 5006, advertise = false)
for (pitch in listOf(60, 64, 67, 72)) {
    other.noteOn(pitch, 90)
    Thread.sleep(150)
    other.noteOff(pitch)
}
other.controlChange(74, 100)
Thread.sleep(100)   // let the last packets arrive before counting

val field = rtpMidi("Field")
markdown("received", "box 1's session has received ${field.messageCount} messages; held notes: ${field.held.size}")
