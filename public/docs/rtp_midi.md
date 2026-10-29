# Network MIDI: RTP-MIDI sessions

macOS, iOS, rtpMIDI on Windows, and a lot of MIDI hardware with an Ethernet port can carry MIDI
over a network as **RTP-MIDI**, also called AppleMIDI. On a Mac it lives in *Audio MIDI Setup →
MIDI Studio → Network*. A box can open a session of its own, so a DAW on another machine or an
iPad app on the same Wi-Fi can play into Field, and Field can play back out, with no cable and no
driver.

The demo is the [rtp_midi_demo](agent_transcripts.md#rtp_midi_demo) starting point. A second session in
the same Field joins the first, so it works with nothing else on the network.

## Receiving notes and controllers

This box opens a session called "Field" and shows the last note and controller that arrive:

```diffstatic {data-lang=kotlin}
import afield.io.midi.rtp.*

val midi = rtpMidi("Field")

midi.onNote { pitch, velocity, channel ->
    if (velocity > 0) markdown("last note", "note **$pitch** velocity $velocity on channel ${channel + 1}")
}
midi.onControl { cc, value, channel -> markdown("last control", "CC $cc = $value on channel ${channel + 1}") }
```

`rtpMidi(name)` opens the session on UDP ports 5004 and 5005, or reuses it if it is already open,
and announces it on the local network. On a Mac, "Field" then appears in the *Directory* list of
*MIDI Network Setup*. Select it and press **Connect**, and the Mac's network session becomes a MIDI
port that Logic, Ableton Live or any other program can send to.

Callbacks run on Field's main loop, like the rest of the box. Note-off arrives as velocity 0,
whichever way the sender wrote it. Re-running the box replaces its callbacks instead of adding
more, and deleting the box removes them. The session itself stays open when the box stops, so a
connected DAW does not drop while you edit.

A box that only needs the current value of a knob can read it without a callback:

```diffstatic {data-lang=kotlin}
val cutoff = 200.0 + 8000.0 * midi.knob(74) // 0..1, 0 until the knob first moves
val held = midi.held.keys.map { (_, pitch) -> pitch }
```

`onMessage { message, peer -> }` receives everything, as a `javax.sound.midi.MidiMessage`, along
with the peer that sent it. It is the same type `MidiDevices.openInput` gives you for a MIDI port
on this machine.

## Sending

Whatever a box sends goes to every connected peer. **Launch** this sequence; Stop or rerun
cancels its wait and releases the current note:

```diffstatic {data-lang=kotlin}
frames {
    for (pitch in [60, 64, 67, 72]) {
        midi.noteOn(pitch, 90)
        try {
            delay(150) // Milliseconds; yields to Field while the note sounds.
        } finally {
            midi.noteOff(pitch) // Stop or rerun also releases the current note.
        }
    }
    midi.controlChange(74, 100)
    midi.pitchBend(-2000)
    midi.sysex(byteArrayOf(0x7D, 1, 2, 3)) // The F0 ... F7 framing is added.
}
```

`programChange`, `send(midiMessage)` and `allNotesOff()` are there as well. Channels count from 0.

## Connecting to another session

Field can make the connection itself. `invite` takes the other session's address and its first
port, which is 5004 for a Mac's default session:

```diffstatic {data-lang=kotlin}
val peer = midi.invite("192.168.1.20", 5004)
```

The invitation is retried for about twelve seconds. `midi.peers` lists every peer with its state,
and once a peer is connected the two ends exchange clock readings, so each `Peer` reports the
network round trip:

```diffstatic {data-lang=kotlin}
markdown(
    midi.peers.joinToString("\n") { p ->
        "- **${p.name}** ${p.state}, round trip ${p.roundTripMs} ms, ${p.received} received"
    },
)
```

`roundTripStatsMs` gives the minimum, mean and maximum over the last 32 exchanges. On a wired
network the round trip is well under a millisecond; on Wi-Fi a few milliseconds is normal, and the
maximum shows how far it spikes.

To find sessions on the network rather than typing an address:

```diffstatic {data-lang=kotlin}
for (s in rtpMidiDiscover()) markdown("- **${s.instance}** at `${s.address}:${s.port}`")
```

## Ports and the network

`rtpMidi(name, port = 5004)` takes another port for a second session; the data port is always the
next one up. All boxes that ask for the same port share one session. `advertise = false` keeps a
session off the network's list; other machines can still invite it by address.

A session accepts every invitation it receives, from any machine on the network. Set
`midi.session.acceptInvitations = false` to refuse them and connect only with `invite`.
`RtpMidiSessions.closeAll()` ends every session, saying goodbye to each peer.

## Not supported

- No recovery journal is sent. A note lost on a bad link stays lost, and a journal sent by a peer
  is ignored. A wired connection reduces the chance of packet loss.
- Timestamps in incoming packets are reported but not used to schedule playback: messages are
  delivered as they arrive.
- A session does not notice a peer that disappears without saying goodbye; it stays in `peers`
  until it is invited again or the session closes.
- No IPv6.

## See also

- [OSC](osc.md) and [OSCQuery](oscquery.md), for tools that speak OSC instead.

??? aside "How this page was checked"
    The fences are static. The protocol is covered by
    `src/jvmTest/kotlin/afield/io/midi/rtp/RtpMidiLoopbackTest.kt`: two sessions over loopback UDP
    connect, exchange clocks, and pass notes, controllers, running status and SysEx both ways; a
    hand-built packet with a journal tail checks that journals are skipped. The demo document's
    three boxes were run in Field through the backdoor. Interoperation with macOS's own network
    session was not tested automatically. Notes are in `dev_docs/RTP_MIDI.md`.
