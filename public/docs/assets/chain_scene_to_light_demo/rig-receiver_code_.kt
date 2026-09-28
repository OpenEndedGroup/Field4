import afield.dmx.*

// Field as an sACN node on the sender's port. It knows where the fixtures are (the same layout),
// and nothing else: every colour drawn here was decoded from the universes that arrived.
val node = sacnIn(port = 35690)
val layout = SceneOnARig.Rig()

// Redrawn ten times a second: the rig lit by what arrived, then universe 1 in wire order
// (128 RGB pixels, then the unused slots).
loop {
    showReceived(layout.patch, node, channel = "received", w = 480.0, h = 300.0)
    showUniverse(node, 1, cols = 16, w = 480.0, h = 300.0)
    delay(100)
}
