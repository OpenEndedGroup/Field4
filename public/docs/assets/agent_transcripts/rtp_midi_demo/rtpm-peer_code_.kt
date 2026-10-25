import afield.io.midi.rtp.*

// A second session in the same Field, so the demo works with nothing else on the network.
// It invites the "Field" session from box 1 the way a Mac or an iPad would.
val other = rtpMidi("Field peer", port = 5006, advertise = false)
other.invite("127.0.0.1", 5004)

// Launch: wait for a real connection and clock sample, with a bounded deadline.
frames {
    val deadline = System.nanoTime() + 12_000_000_000L
    while (other.peers.none { it.connected && it.roundTripStatsMs != null }) {
        check(System.nanoTime() < deadline) { "RTP-MIDI peer did not connect and synchronize within 12 seconds" }
        frame()
    }
    markdown(
        "peers",
        other.peers.joinToString("\n") { p ->
            val stats =
                p.roundTripStatsMs?.let { (lo, mean, hi) ->
                    "round trip %.2f / %.2f / %.2f ms (min/mean/max)".format(lo, mean, hi)
                } ?: "not synced yet"
            "- **${p.name}** at ${p.address.hostAddress}:${p.controlPort}, ${p.state.name.lowercase()}, $stats"
        },
    )
}
