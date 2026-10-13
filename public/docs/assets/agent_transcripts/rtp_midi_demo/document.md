# Field Document: rtp_midi_demo

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. 3. Play into it (run)

<!-- field-box id="rtpm-play" index="0" -->

```edn [field-box id="rtpm-play" role="properties"]
{"id" "rtpm-play" "index" 0 "name" "3. Play into it (run)" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "3. Play into it (run)" "_tombstone" false "frame" #afield/rect [980.0 60.0 380.0 70.0] "autorun" false} "blockProperties" [{"name" "code" "language" "kotlin" "path" "rtpm-play_code_.kt" "lines" 15 "characters" 641 "sha256" "2cf14cf6508e5c16cd0250fd3543709d7e7b62d4e9dd93949f1a261e66b18158"}]}
```

```kotlin [field-property box="rtpm-play" name="code" path="rtpm-play_code_.kt"]
import afield.io.midi.rtp.*

// Play an arpeggio from the peer session into the "Field" session; box 1 shows each note as it
// arrives. Pointed at a Mac's session instead, these notes reach any DAW listening to it.
val other = rtpMidi("Field peer", port = 5006, advertise = false)
for (pitch in listOf(60, 64, 67, 72)) {
    other.noteOn(pitch, 90)
    Thread.sleep(150)
    other.noteOff(pitch)
}
other.controlChange(74, 100)
Thread.sleep(100)   // let the last packets arrive before counting

val field = rtpMidi("Field")
markdown("received", "box 1's session has received ${field.messageCount} messages; held notes: ${field.held.size}")
```

## 2. 2. A peer joins (run)

<!-- field-box id="rtpm-peer" index="1" -->

```edn [field-box id="rtpm-peer" role="properties"]
{"id" "rtpm-peer" "index" 1 "name" "2. A peer joins (run)" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2. A peer joins (run)" "_tombstone" false "frame" #afield/rect [520.0 60.0 380.0 70.0] "autorun" false} "blockProperties" [{"name" "code" "language" "kotlin" "path" "rtpm-peer_code_.kt" "lines" 13 "characters" 702 "sha256" "0fc48c107bfee17dc28c5fb96644a750880e520f3d0cb02e4cd605c0e9c46c59"}]}
```

```kotlin [field-property box="rtpm-peer" name="code" path="rtpm-peer_code_.kt"]
import afield.io.midi.rtp.*

// A second session in the same Field, so the demo works with nothing else on the network.
// It invites the "Field" session from box 1 the way a Mac or an iPad would.
val other = rtpMidi("Field peer", port = 5006, advertise = false)
other.invite("127.0.0.1", 5004)

// The invitation and the first clock exchange take a few milliseconds.
Thread.sleep(500)
markdown("peers", other.peers.joinToString("\n") { p ->
    val stats = p.roundTripStatsMs?.let { (lo, mean, hi) -> "round trip %.2f / %.2f / %.2f ms (min/mean/max)".format(lo, mean, hi) } ?: "not synced yet"
    "- **${p.name}** at ${p.address.hostAddress}:${p.controlPort}, ${p.state.name.lowercase()}, $stats"
})
```

## 3. 1. Open the session (run first)

<!-- field-box id="rtpm-session" index="2" -->

```edn [field-box id="rtpm-session" role="properties"]
{"id" "rtpm-session" "index" 2 "name" "1. Open the session (run first)" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1. Open the session (run first)" "_tombstone" false "frame" #afield/rect [60.0 60.0 380.0 70.0] "autorun" false} "blockProperties" [{"name" "code" "language" "kotlin" "path" "rtpm-session_code_.kt" "lines" 14 "characters" 779 "sha256" "b24a11df74af3d6ff53675705dfb1b5b5cd7ef39d32385b9f7ea6e4451edab91"}]}
```

```kotlin [field-property box="rtpm-session" name="code" path="rtpm-session_code_.kt"]
import afield.io.midi.rtp.*

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
```

