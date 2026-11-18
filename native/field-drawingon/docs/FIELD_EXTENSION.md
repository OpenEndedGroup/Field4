# Drawing On ↔ Field

Field receives complete drawing and microphone timelines and sends FLine geometry back to a named iPad target. A Field reference is a separate layer: hide or solo it, draw over it, and keep drawing when disconnected. Human undo never deletes the reference or its history.

The adapter adds `/drawingon/ws` to Field’s ordinary server; no Python receiver is needed. Its implementation is `afield/io/drawingon/DrawingOn.kt` and `afield/app/server/drawingon.kt` in the Field checkout.

## Connect

Run a Field build containing the adapter with `AFIELD_BIND_HOST=0.0.0.0` for LAN reachability (the default is loopback). Keep Field’s browser-origin policy enabled. In the iPad menu, set **WebSocket address** to `wss://YOUR-FIELD-HOST:9092/drawingon/ws`, **Field target** to `ipad`, and turn on **Stream live**. The same destination accepts saved-capture uploads.

The HTTPS certificate must match the host and be trusted by iPadOS. Field's default development certificate is for `localhost`; it needs replacing for a physical iPad. Field supports `AFIELD_KEYSTORE_PATH`, `AFIELD_KEYSTORE_PASS`, and `AFIELD_KEYSTORE_ALIAS`. There is no certificate-validation bypass in Drawing On. For a simulator on the same Mac, Field's existing loopback HTTP mirror works as `ws://127.0.0.1:19092/drawingon/ws` (default ports). The mirror is deliberately loopback-only.

## A box that draws back

Use Field's normal FLine construction and rendering properties:

```kotlin
import afield.geometry.path.FLine
import afield.graphics.render.color
import afield.io.drawingon.drawingOn
import afield.math.linalg.vec

val pad = drawingOn("ipad")

val plan = FLine().moveTo(120, 160).lineTo(520, 160).lineTo(520, 460).lineTo(120, 460).lineTo(120, 160)
plan.color = vec(1.0, 1.0, 1.0, 0.35)
pad.publish(listOf(plan), title = "Studio plan")

pad.onEvent { event ->
    if (event.type == "stroke.end" && !event.upload) {
        // Replace with a computation or your AI interpretation of committed input.
        val response = FLine().moveTo(160, 200).lineTo(480, 420)
        response.color = vec(1.0, 1.0, 1.0, 0.6)
        pad.publish(listOf(plan, response), title = "Studio plan + response")
    }
}
```

The target belongs to this box until its parent resource scope is retired. Re-running the box replaces its event callback; a second box cannot silently take over the same name. Desktop selection has no effect on the iPad target. Publish an empty collection to clear the reference. A reconnect receives the latest published revision.

Callbacks receive each newly committed event once per server process, after durable append, on Field's main Runloop. `event.session`, `event.type`, `event.time`, `event.data`, and `event.upload` expose the original data. Keep callbacks short; send expensive AI work to your own job. Filter the event types you use: audio buffers, viewport/layer changes, predictions, corrections, and returned-reference records also pass through this stream. Saved uploads set `upload = true`; replaying a capture does not itself publish geometry. A crash between durable acknowledgement and callback delivery can leave an event only in the archive: use the archive to recover derived processing.

`pad.sessions` lists received session UUIDs for this target. `pad.readCapture(session) { event -> ... }` streams the original JSON events, including audio and reference assets, without loading a complete recording into memory. Captures are append-only files under `WORKSPACE/.drawingon/`; acknowledgements follow an `fsync`, and retransmissions are deduplicated by session and sequence.

## Coordinates and rendering

The default `DrawingOnPlane()` maps Field X and Y to iPad drawing points, with Y down. The current iPad view transform then pans, rotates, and scales the entire scene. `canvas.viewport`, `canvas.transform`, and each stroke's viewport describe the visible drawing footprint.

For another plane, pass `DrawingOnPlane(origin=..., xAxis=..., yAxis=..., scale=..., offsetX=..., offsetY=...)` to `publish`. Projection is `dot(position-origin, axis)*scale+offset`; use independent unit axes for an orthographic basis. A conventional Y-up model can use `yAxis=listOf(0.0,-1.0,0.0)`. The plane is recorded with each reference revision.

FLine lines, triangle fills, and points reuse the AVP client's `three/geometry` description and typed little-endian buffer tables. This first renderer uses simple constant-width strokes and flat per-primitive colour/alpha. It has no 3D camera, material shading, or texture support. Light canvas inverts the reference colours. One reference layer is maintained per target; layer visibility and local renames survive subsequent replacements.

The iPad verifies each complete buffer asset by SHA-256, retains it in `Field assets/`, and writes `field.asset` chunks followed by `field.reference` into its journal. Exports and uploads are self-contained; the old Field buffer URL is unnecessary for replay. A reference is limited to 32 MiB of numeric buffers and 500,000 vertices per geometry. Existing references remain when streaming stops.

## Verify the real connection

From the Field checkout:

```sh
DRAWINGON_NATIVE_PATH="$PWD/native/field-drawingon" ./gradlew fastTest --tests 'afield.io.drawingon.DrawingOnTest'
```

This starts an isolated Ktor server, receives native drawing/audio/undo, publishes real FLine buffers in response, has the Swift URLSession client fetch and project them, reconstructs the response from a fresh journal/asset store, and exercises deduplicated reconnects and resumable uploads. It does not require a running desktop Field or change its workspace. Run `swift test` in Drawing On for the remaining reducer, projection, buffer-validation, and asset tests.

Drawing On is an optional native client with its own Xcode project in `native/field-drawingon/`; Field’s JVM build does not require Xcode. The app and offline reader are maintained in this repository. See the [native build guide](../README.md).
