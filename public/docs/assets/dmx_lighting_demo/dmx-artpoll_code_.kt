import afield.dmx.*

// Art-Net discovery: send one ArtPoll and list every node that answers within a second.
// With the receiver box running, Field itself answers. Try "255.255.255.255" on a real network.
artPoll("127.0.0.1").forEach { println(it) }
