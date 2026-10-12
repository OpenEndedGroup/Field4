import afield.dmx.*

// Field as an Art-Net node: listen on UDP 6454, merge what arrives (HTP), answer ArtPoll.
// Launch this first, then launch the sender. Stopping this box closes the socket.
val input = artnetIn()

// Redraw the two received universes as RGB cells whenever new data has arrived.
var seen = -1L
loop {
    val v = input.version(1) + input.version(2)
    if (v != seen && frameNumber % 4 == 0) {
        seen = v
        showUniverse(input, 1)
        showUniverse(input, 2)
    }
}
