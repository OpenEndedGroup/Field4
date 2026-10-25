# RTP-MIDI / AppleMIDI in Field

Source: `src/jvmMain/kotlin/afield/rtpmidi/` — `RtpMidiCodec.kt` (wire formats),
`RtpMidiSession.kt` (sockets, session state machine), `RtpMidiBox.kt` (`Box.rtpMidi`, per-port
registry, per-box keyed callbacks dispatched on the Runloop). User page: `public/docs/rtp_midi.md`.
Demo: `public/docs/assets/rtp_midi_demo/`.

## What is implemented

- AppleMIDI session packets: `IN`/`OK`/`NO`/`BY` on the control port then the data port, both as
  initiator (`invite`, retried every second up to 12 times) and responder (`acceptInvitations`).
- `CK` three-way clock sync, 100 µs units. The initiator runs it on connect, twice more in the
  first few seconds, then every `syncIntervalMs`. Offset = remote clock − local, from the midpoint
  of ts1/ts3; RTT = ts3 − ts1. Both ends record it; the last 32 RTTs give min/mean/max.
- `RS` receiver feedback: sent on the control port at most every 500 ms after new data; incoming RS
  is recorded as `Peer.acknowledged` (no journal to trim).
- RFC 6295 command section: short/long header, Z (first delta), P (phantom status: commands are
  dropped until the next status byte, since running status is not carried across packets), J
  (journal skipped by LEN), 1–4-byte deltas, running status on encode and decode, real-time bytes
  that leave running status alone, system common that cancels it, whole SysEx, and segmented SysEx
  (F0..F0, F7..F0, F7..F7, F7..F4 cancel) reassembled per peer.
- Bonjour via the existing `afield.io.osc.query.Mdns` (`_apple-midi._udp`, control port).

## Not implemented

- Recovery journal (send or apply). Packets go out with J = 0, which RFC 6295 permits.
- Playback scheduling by RTP timestamp; peer liveness timeout; IPv6.
- `Box.rtpMidi(port = 0)` opens a fresh session per call (the registry keys on the bound port).

## Verification (2026-10-09)

- `./gradlew jvmTest --tests 'afield.io.midi.rtp.*'`: 12 tests, 0 failures (`RtpMidiCodecTest` 6,
  `RtpMidiLoopbackTest` 6). The loopback CK test asserts |offset| ≤ 2 ms and RTT ≤ 20 ms; both
  sessions share the process clock origin, so the true offset is 0.
- Demo document loaded fresh and its three boxes run through the backdoor: box 2 reported the peer
  connected with RTT 0.10 ms on loopback; after box 3, box 1's callbacks showed note 72 and CC 74 =
  100, and 9 messages received (4 note-on, 4 note-off, 1 CC).
- Not tested: interop with macOS's own network session. Driving *Audio MIDI Setup* headlessly is
  not possible from the backdoor; the packet layouts follow Apple's published session protocol and
  the RFC, but a real Mac/iPad connection is the outstanding check.
