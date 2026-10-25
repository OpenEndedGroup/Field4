# Field Document: rtp_midi_demo

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. 3. Play into it (launch)

<!-- field-box id="rtpm-play" index="0" -->

```edn [field-box id="rtpm-play" role="properties"]
{"id" "rtpm-play" "index" 0 "name" "3. Play into it (launch)" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "3. Play into it (launch)" "_tombstone" false "frame" #afield/rect [980.0 60.0 380.0 70.0] "autorun" false} "blockProperties" [{"name" "code" "language" "kotlin" "path" "rtpm-play_code_.kt" "lines" 21 "characters" 877 "sha256" "4932e1320adf5eca984a1a04666358b8e3146aa10f71a6a3cd5cd55902c47d9e"}]}
```

```kotlin [field-property box="rtpm-play" name="code" path="rtpm-play_code_.kt"]
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
```

## 2. 2. A peer joins (launch)

<!-- field-box id="rtpm-peer" index="1" -->

```edn [field-box id="rtpm-peer" role="properties"]
{"id" "rtpm-peer" "index" 1 "name" "2. A peer joins (launch)" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2. A peer joins (launch)" "_tombstone" false "frame" #afield/rect [520.0 60.0 380.0 70.0] "autorun" false} "blockProperties" [{"name" "code" "language" "kotlin" "path" "rtpm-peer_code_.kt" "lines" 25 "characters" 1077 "sha256" "03702f63635f3908704f417a283c54f49a9f5948f6d8e1c57e616aea0ab43d72"}]}
```

```kotlin [field-property box="rtpm-peer" name="code" path="rtpm-peer_code_.kt"]
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
```

## 3. 1. Open the session (run first)

<!-- field-box id="rtpm-session" index="2" -->

```edn [field-box id="rtpm-session" role="properties"]
{"id" "rtpm-session" "index" 2 "name" "1. Open the session (run first)" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1. Open the session (run first)" "_tombstone" false "frame" #afield/rect [60.0 60.0 380.0 70.0] "autorun" false} "blockProperties" [{"name" "code" "language" "kotlin" "path" "rtpm-session_code_.kt" "lines" 16 "characters" 776 "sha256" "1c56dc373262ae31c615e0ed91277855bc5f5d966d5d48735d06d10fe697502a"}]}
```

```kotlin [field-property box="rtpm-session" name="code" path="rtpm-session_code_.kt"]
import afield.io.midi.rtp.*

/*
Open an RTP-MIDI network session called "Field" on UDP 5004/5005 and announce it over Bonjour.
In macOS Audio MIDI Setup -> MIDI Studio -> Network, "Field" now appears in the Directory:
select it and press Connect. An iPad app with network MIDI, or rtpMIDI on Windows, lists it too.
*/
val midi = rtpMidi("Field")

// Each note that arrives runs this, on Field's main loop.
midi.onNote { pitch, velocity, channel ->
    if (velocity > 0) markdown("last note", "note **$pitch** velocity $velocity on channel ${channel + 1}")
}
midi.onControl { cc, value, channel -> markdown("last control", "CC $cc = $value on channel ${channel + 1}") }

markdown("session", "Session **${midi.session.name}** on port ${midi.port}; ${midi.peers.size} peer(s).")
```

