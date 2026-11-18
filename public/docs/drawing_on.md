# Drawing on iPad

Draw with an Apple Pencil, talk about what you are drawing, and keep both on one timeline.
**Drawing On** is Field's optional native iPad head: it records every measured Pencil sample and
microphone buffer, displays FLine geometry sent back by Field, and works entirely offline when
you want to record somewhere else. Later, upload the recording or turn its save file into a
contact sheet with aligned speech clips and transcripts.

Open the [Drawing On starting place](agent_transcripts.md#drawing_on) from Field's Welcome
screen and run **iPad drawing head**. Its plan appears in the box output, so you can edit and
try the Field side before connecting an iPad.

## Install and connect

On a Mac, run `./field.sh --setup` and select **Drawing On iPad app / offline reader**. The BUILD
step compiles the offline reader with your installed Apple tools and prints the Xcode steps.
The iPad app is signed and installed from Xcode; optional transcription models are supplied separately.
Field’s drawing-head server adapter already ships on every supported Field platform.

The app's [Xcode project and build guide](https://github.com/OpenEndedGroup/Field4/tree/main/native/field-drawingon)
ship in `native/field-drawingon/`, alongside Field's optional native headset clients. Open
`DrawingOn.xcodeproj`, choose your iPad and development team, and run the **DrawingOn** scheme.
The app requires iPadOS 18 or later. Building Field itself requires no Xcode or native app build.

For a physical iPad, start Field with `AFIELD_BIND_HOST=0.0.0.0` so it is reachable on your LAN.
Set the app's address to `wss://YOUR-FIELD-HOST:9092/drawingon/ws`, **Field target** to `ipad`, and
enable **Stream live**. Use the port your Field server is running on. For a simulator on the same
Mac, Field's default loopback mirror is `ws://127.0.0.1:19092/drawingon/ws`.

!!! note "A certificate for your Field host"
    The physical iPad must trust the HTTPS certificate, and the certificate must cover the hostname
    in the address. Field's default development certificate covers `localhost`. Supply your own
    keystore with `AFIELD_KEYSTORE_PATH`, `AFIELD_KEYSTORE_PASS`, and `AFIELD_KEYSTORE_ALIAS`.
    Drawing On uses normal system certificate validation. Keep Field's browser-origin policy enabled.

## Draw, move and talk

The canvas starts black with translucent white ink. **Light canvas** inverts it. Pressure, tilt,
roll, coalesced samples, timing, predictions and estimated-property corrections are recorded;
the initial brush draws a simple constant-width line.

Choose freehand, line or circle from the bottom-left tools. Drag a line between its endpoints,
or a circle from its centre to its edge. Two fingers pan, rotate and scale together. Two-finger
tap undoes; three-finger tap redoes. The menu adds layers and controls visibility and solo.
Portrait and landscape use the same drawing coordinates; resizing and view changes record the
screen rectangle, visible drawing-space corners and their bounding box.

**Record microphone** explicitly starts voice capture. Audio and drawing use a common monotonic
capture clock. Stopping the microphone, an interruption or backgrounding is recorded. Undo adds
a stroke-visibility event; it keeps the measured samples and their original times in the save file.
Field's returned geometry has its own reference layer, which you can hide or solo independently.

## A plan that draws back

This is the starting place's head box. `roomWidth` changes the room. `pad.publish(...)` replaces the
Field reference layer on target `ipad`; the same FLines are shown locally with `lines(...)`.
The folded callback receives committed input and adds a crosshair when a Pencil stroke ends.

```diffstatic_runner {data-lang=kotlin screenshot="assets/drawing_on/plan.png" capture="box" open-outputs="true" wait="3" padding="16" name="drawing-on-plan" embed=true alt="A translucent white rectangular room plan in Field’s dark output area"}
:::static
:::fold imports
import afield.geometry.path.FLine
import afield.graphics.render.color
import afield.io.drawingon.drawingOn
import afield.math.linalg.vec
import afield.ui.visualization.lines
import kotlinx.serialization.json.*
:::endfold

val pad = drawingOn("ipad")
val roomWidth = 400.0
val plan = FLine().moveTo(120, 160).lineTo(120 + roomWidth, 160)
    .lineTo(120 + roomWidth, 460).lineTo(120, 460).lineTo(120, 160)
plan.color = vec(1.0, 1.0, 1.0, 0.45)

fun showPlan(extra: List<FLine> = emptyList()) {
    val drawing = listOf(plan) + extra
    pad.publish(drawing, title = "Studio plan")
    lines(drawing, w = 640.0, h = 520.0)
}
showPlan()

:::fold receive Pencil endpoints and draw back
val endpoints = mutableMapOf<String, Pair<Double, Double>>()
pad.onEvent { event ->
    if (!event.upload) {
        val stroke = event.data["stroke"]?.jsonPrimitive?.contentOrNull
        if (event.type == "stroke.samples" && stroke != null) {
            val last = event.data["samples"]?.jsonArray?.lastOrNull()?.jsonObject
            val x = last?.get("drawingX")?.jsonPrimitive?.doubleOrNull
            val y = last?.get("drawingY")?.jsonPrimitive?.doubleOrNull
            if (x != null && y != null) endpoints[stroke] = x to y
        }
        if (event.type == "stroke.end" && stroke != null) {
            val point = endpoints.remove(stroke)
            if (point != null && event.data["cancelled"]?.jsonPrimitive?.booleanOrNull != true) {
                val (x, y) = point
                val marker = FLine().moveTo(x - 12, y).lineTo(x + 12, y)
                    .moveTo(x, y - 12).lineTo(x, y + 12)
                marker.color = vec(1.0, 1.0, 1.0, 0.8)
                showPlan(listOf(marker))
                println("Pencil endpoint ($x, $y) at ${event.time}s")
            }
        }
    }
}
:::endfold
```

The marker uses the last measured drawing-space point from `stroke.samples`. It is an example
response, rather than a replay of the human drawing. The complete original event stream,
including subsequent corrections, is retained in the archive. Change the callback to feed your
own geometry or interpretation process.

A target belongs to its owning box. Re-running that box replaces its callback; another box needs
a different target name. Desktop selection does not choose what appears on the iPad. Reconnecting
receives the latest published reference, and disconnecting leaves the last local reference visible.
Publish an empty collection to clear it.

## Coordinates and the event stream

The default `DrawingOnPlane()` maps Field X and Y directly to drawing points, with Y down. The
recorded iPad view transform then pans, rotates and scales the scene. For a Y-up model, pass a
plane with `yAxis = listOf(0.0, -1.0, 0.0)` and a suitable offset to `publish`. For a 3D model,
choose explicit independent `xAxis` and `yAxis` vectors and an origin. Projection is
`dot(position - origin, axis) * scale + offset`, recorded with each reference revision.

Returned lines, fills and points use Field's existing AVP Three geometry/buffer format, with a
simple two-dimensional renderer on the iPad. The native client verifies and saves the actual
geometry it displayed. Exported captures include these buffers; replay needs no running Field.

`pad.onEvent` receives `event.session`, `event.time`, `event.type`, `event.data`, and
`event.upload`. The last flag distinguishes a saved-capture upload from a live connection.
Callbacks run on Field's main Runloop after durable append. Filter the events you need and keep
expensive transcription or AI work in a separate job. Returned `field.*` records are ordinary
capture events too; publishing in response to every event would create a feedback loop.

`pad.sessions` lists recordings received for this target. In its owning box,
`pad.readCapture(session) { event -> ... }` streams the original JSON, including microphone
buffers and geometry assets. Field stores the append-only archive in `WORKSPACE/.drawingon/`
and deduplicates retransmitted session/sequence pairs. If your processing must recover from a
server restart, use the archive as its input; the live callback is not a persistent job queue.
The [wire format](https://github.com/OpenEndedGroup/Field4/blob/main/native/field-drawingon/docs/PROTOCOL.md)
describes sample fields, audio encoding, viewport transforms and geometry assets.

## Record elsewhere, upload later

Leave **Stream live** off to draw and record voice without a server or network. Give the capture
a name and choose **Finish capture** to save it and start a fresh canvas. Under **Saved captures**,
upload to Field when connected. Transfers resume from the receiver's durable position, and the
original stays on the iPad. A saved upload receives no new reference geometry.

**Share capture** exports a self-contained `.ndjson` save file, also accessible through Files or
Finder file sharing. It contains undone strokes as well as visible ones, audio, viewport changes,
and received Field references. This is the same input used by the offline reader below.

## A contact sheet from a save file

On a Mac, run the reader from the Field checkout. The first invocation compiles it with your
installed Xcode command-line tools. Rendering and segmentation use macOS frameworks, with no
server, network connection or external model:

```sh
native/field-drawingon/tools/offline-review/review capture.ndjson --output capture.review
open capture.review/index.html
```

The review contains PNG contact sheets, full-size viewport frames, speech-candidate WAVs and a
`manifest.json` for downstream agents. It reuses the app's replay model, including layer state,
undo, line/circle intent, navigation, appearance and Field reference replacements. It selects
moments around drawing activity and speech; silent drawing remains represented when the
microphone was off. Temporary predicted ink is omitted.

Each frame records its time and sequence cutoff, viewport, transform, visible layers and stroke
IDs. Speech clips retain capture-clock offsets and remain separate across recording gaps. The
HTML review links the full-size images and plays the audio locally. Its caption interval precedes
the snapshot; it does not assert that the drawing stayed unchanged throughout that interval.

Energy-based voice activity detection finds candidate clips before transcription. It can mistake
café noise for speech or miss quiet speech; keep the audio available to your interpretation
process. Tune `--vad-threshold`, `--silence`, `--interval` and `--max-frames` for your recordings.
The tool reports when its frame cap omits candidate states.

For local transcription, add an installed Whisper CLI and an existing model file. This example
uses OpenAI Whisper with a multilingual model and automatic language detection:

```sh
native/field-drawingon/tools/offline-review/review capture.ndjson --output spoken.review \
  --stt openai --stt-executable /path/to/whisper --stt-model /path/to/base.pt
```

Models are supplied by file path; the tool never installs or downloads them. Raw transcription
JSON is preserved, and manifest segments have both clip-local and capture-clock timestamps.
A compatible local `whisper.cpp` CLI is also supported. See the
[offline reader guide](https://github.com/OpenEndedGroup/Field4/blob/main/native/field-drawingon/tools/offline-review/README.md)
for options, output fields and limitations.

See also [Drawing with FLines](fline_examples.md), [Geometry review](geometry_review.md), and
[geometry in the headset](vision_geometry.md).
