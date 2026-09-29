// Send numbers to a [r] in another Pd box, found by its name. Launch "drone" first.
// Run one of these lines on its own:
pdBox("drone").pdSend("freq", 330)
pdBox("drone").pdSend("freq", 110)
pdBox("drone").pdSend("cutoff", 3000)

// Or launch this box: an arpeggio into [r freq], one Int a quarter second, until you stop it.
val drone = pdBox("drone")
val notes = listOf(110, 165, 220, 330, 440, 330, 220, 165)
var i = 0
loop {
    drone.pdSend("freq", notes[i++ % notes.size])
    delay(250)
}
