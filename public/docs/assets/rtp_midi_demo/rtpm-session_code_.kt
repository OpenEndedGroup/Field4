import afield.rtpmidi.*

// Open an RTP-MIDI network session called "Field" on UDP 5004/5005 and announce it over Bonjour.
// In macOS Audio MIDI Setup -> MIDI Studio -> Network, "Field" now appears in the Directory:
// select it and press Connect. An iPad app with network MIDI, or rtpMIDI on Windows, lists it too.
val midi = rtpMidi("Field")

// Each note that arrives runs this, on Field's main loop.
midi.onNote { pitch, velocity, channel ->
    if (velocity > 0) markdown("last note", "note **$pitch** velocity $velocity on channel ${channel + 1}")
}
midi.onControl { cc, value, channel -> markdown("last control", "CC $cc = $value on channel ${channel + 1}") }

markdown("session", "Session **${midi.session.name}** on port ${midi.port}; ${midi.peers.size} peer(s).")
