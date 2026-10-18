import afield.io.midi.rtp.*

// Play an arpeggio from the peer session into the "Field" session; box 1 shows each note as it
// arrives. Pointed at a Mac's session instead, these notes reach any DAW listening to it.
val other = rtpMidi("Field peer", port = 5006, advertise = false)
val field = rtpMidi("Field")

// Launch: cancellation releases the current note before this sequence ends.
frames {
    for (pitch in [60, 64, 67, 72]) {
        other.noteOn(pitch, 90)
        try {
            delay(150) // Milliseconds; the receiver keeps processing while we wait.
        } finally {
            other.noteOff(pitch)
        }
    }
    other.controlChange(74, 100)
    delay(100) // Let the last packets arrive before reading the receiver's counters.
    markdown("received", "box 1's session has received ${field.messageCount} messages; held notes: ${field.held.size}")
}
