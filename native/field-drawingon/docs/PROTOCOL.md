# Drawing On capture protocol, version 1

The local journal is UTF-8 newline-delimited JSON. Each WebSocket text message carries one identical event, without the trailing newline. Units are **seconds**, **UIKit points**, and **radians**. Coordinates are relative to the canvas, with origin at top left and positive Y down. Values are not quantized, smoothed, or normalized. A constant-width renderer is a projection, not the source of capture truth.

```json
{"v":1,"session":"UUID","seq":42,"t":1.234,"type":"stroke.samples","data":{"stroke":"UUID","samples":[]}}
```

`session` identifies a persistent journal. `seq` increases in append order, starting at 1. `t` is event time on the session’s monotonic clock. **Sort transport/replay by `seq`, not `t`:** audio buffers arrive after their first sample and corrections refer back to earlier measurements. Sample timestamps and audio buffer start times determine alignment. Predictions may lie in the future.

## Clock

`session.clock` contains `wallTime` (UTC ISO 8601), `uptimeOrigin`, `timelineOffset`, device and system information. For that process segment:

```
t = UIKit touch.timestamp - uptimeOrigin + timelineOffset
```

Audio uses `AVAudioTime.seconds(forHostTime: bufferTime.hostTime)` in the same formula. `audio.buffer.t` is the time of the **first frame**, not the callback’s arrival time. Within a buffer, frame `i` occurs at `t + i / sampleRate`. `sampleTime` is the native audio sample counter within that recording; `-1` means unavailable. `hostTimeValid=false` explicitly marks a fallback to callback uptime. Audio input latency and I/O buffer duration are retained in `audio.start`; no heuristic latency correction is applied.

At relaunch, the journal resumes slightly beyond its last event / audio frame. `session.clock` and `session.resume` mark the discontinuity. Use wall anchors to recover time between app launches; do not infer continuous audio across that boundary. `session.active` and `session.suspend` mark foreground/background transitions within a process.

## Drawing events

| Type | Data |
| --- | --- |
| `canvas.viewport` | `width`, `height`, screen `scale`, UIKit interface `orientation` raw value, complete `transform`, `screen`, `drawingCorners`, `drawingBounds` |
| `canvas.transform` | Complete `transform`, gesture `phase`, `viewport` with screen/corners/bounds, navigation `contacts` |
| `canvas.appearance` | `light`, `opacity`, `width` |
| `layers.state` | Complete ordered `layers` (`id`, `name`, `visible`), selected `active`, nullable `solo`, and effective `visible` layer IDs |
| `canvas.tool` | Selected `tool`: `freehand`, `line`, or `circle` |
| `stroke.geometry` | `stroke`, derived `geometry` for a line or circle, updated with measured input / corrections |
| `stroke.begin` | `stroke`, `layer`, `tool` (defaults to freehand for older captures), effective `visibleLayers`, UIKit `input` raw value, viewport dimensions |
| `stroke.samples` | `stroke`, measured/coalesced `samples` in delivery order |
| `stroke.prediction` | `stroke`, replacement `samples`; replaces all earlier predictions for that stroke, including with an empty array |
| `stroke.correct` | `stroke`, corrected `samples`; replace measured samples by stable sample `id` |
| `stroke.end` | `stroke`, `cancelled`, optional `reason`; also invalidates predictions |
| `stroke.visibility` | `stroke`, `visible`, `reason` (`undo` or `redo`) |

Shape geometry is additional to raw input. A line uses its first and latest measured drawing-coordinate endpoints. A circle uses the first point as its center and the distance to the latest point as its radius. `stroke.geometry.data.geometry` contains `tool`, `start: {x,y}`, `end: {x,y}`, and (for circles) `center: {x,y}` and `radius`. Every measured or corrected batch updates this shape description; temporary predictions affect only the on-screen preview. Late endpoint corrections can update geometry after `stroke.end`. Undo, cancellation, layer visibility, and offline replay work identically for all three tools. The selected tool is recorded separately and each stroke retains the tool it began with.

Layer changes always append a complete visibility state. A solo layer is visible only if its own visibility flag is also true. Cancelling a stroke removes it from the scene and keeps its data. An interface action finishes a current stroke first. At process recovery, open strokes are explicitly ended with a recovery reason.

Each measured sample contains:

- `id`: stable within its stroke; `t`: original touch time, preserved through corrections.
- `x`, `y`, `previousX`, `previousY`: UIKit locations in points.
- `preciseX`, `preciseY`, `precisePreviousX`, `precisePreviousY`: UIKit’s more precise locations, also in points.
- `drawingX`, `drawingY`: precise location mapped through the inverse view transform into stable drawing coordinates. These drive geometry; raw screen positions and angles remain available. Samples in older journals without these fields use `preciseX/Y` as drawing coordinates.
- `force`, `maximumForce`: native force values. Derive a normalized value as `force / maximumForce` when the maximum is nonzero.
- `altitude`, `azimuth`, `azimuthX`, `azimuthY`, `roll`: Pencil angles and direction. Omitted for finger input. Unsupported Pencil roll is reported as zero by UIKit, so zero does not prove roll capability.
- `radius`, `radiusTolerance`: touch major radius and tolerance.
- `phase`, `tapCount`, `input`: raw UIKit touch values.
- `estimated`, `expectingUpdates`: raw `UITouch.Properties` bit masks; force=1, azimuth=2, altitude=4, location=8, roll=16.
- `estimationIndex`: optional native estimation update index. The app associates it with a stroke and stable sample ID. A correction may arrive after a stroke has ended.

Predictions use negative IDs, are stored separately, and **must never be appended to measured geometry**. Their replacement lifetime is one input event. The renderer uses them temporarily to reduce apparent latency.

`pencil.hover` captures position, normalized hover `z`, altitude, azimuth angle/vector, roll, and gesture phase. Hover’s public recognizer exposes no sensor timestamp, so it uses receipt time and marks `timestampSource=callback`. `pencil.tap` and `pencil.squeeze` retain interaction timestamps and nullable hover poses; squeeze also includes interaction phase. Fields reflect what public UIKit APIs report on the particular Pencil/iPad, not private raw sensor packets. Default palm rejection means finger contacts are not archived as drawing strokes unless finger drawing is enabled.

The live hover indicator is a ten-point crosshair with a 0.65-second fading trail, drawn in screen coordinates above the scene. It creates no strokes and is independent of layer visibility and undo. Contact, view changes, capture changes, and app deactivation clear it; leaving hover range removes the crosshair while the trail fades. The offline contact sheet currently omits this transient overlay; the original `pencil.hover` events remain in the capture for annotation analysis.

Two-finger movement pans, rotates, and scales in one gesture. Stationary two-finger tap remains undo; three-finger tap remains redo. Navigation has a six-point movement threshold and scale limits of 0.05× to 32×. Gesture contact samples remain in `canvas.transform`; tentative finger strokes that become navigation are ended as cancelled. The view transform survives relaunch, and **Reset view** records an identity transform.

The affine transform maps drawing coordinates to screen coordinates:

```
screenX = a * drawingX + c * drawingY + tx
screenY = b * drawingX + d * drawingY + ty
```

`canvas.transform.phase` is `began`, `changed`, `ended`, `cancelled`, or `reset`. Each event stores the full matrix, so receivers need not integrate deltas. A cancelled gesture explicitly restores its starting transform. Estimated Pencil corrections use the matrix from the original sample, even if navigation has happened since.

Every viewport description contains `screen: {x,y,width,height}` in canvas display points, `drawingCorners: [[x,y],...]` clockwise from screen top-left, and `drawingBounds: {x,y,width,height}` enclosing those corners in drawing coordinates. Corners give the exact visible footprint under rotation; the axis-aligned bounding box supports spatial queries. View descriptions appear on resize, on every transform, and at stroke begin. Display rotation changes the viewport dimensions without rescaling the drawing. This version has no eraser or selection tool.

Each new capture and application launch records `canvas.appearance` with `light`, `opacity`, `width`, and `reason: "bootstrap"`. Subsequent appearance changes use the same fields. This initial anchor makes standalone replay independent of the app’s current preferences. Older captures can lack an initial appearance anchor; a replay tool must flag its fallback instead of silently treating a later theme change as the initial state.

## Audio events

`audio.start` includes recording UUID, native `sampleRate`, `channels`, `encoding=f32le-interleaved`, `inputLatency`, and `ioBufferDuration`. `audio.buffer` includes the same recording ID and format, `frames`, `sampleTime`, `hostTimeValid`, and base64 `pcm`. Decode `pcm` into little-endian Float32, reshaped as `[frames, channels]`. Channel samples for each frame are adjacent. No compression, resampling, speech processing, or silence removal is performed. `audio.stop` gives recording ID and reason; `audio.error` preserves start failures. Route changes and interruptions end a recording instead of silently changing its format.

At 48 kHz mono, Float32 plus base64 takes roughly 15 MB/minute before JSON overhead. Local storage is the full capture, including audio; no retention policy deletes it automatically.

## Reliable WebSocket delivery

Both workflows use the same append-only event format and receiver. Live mode reads the working journal; a later upload reads an immutable finished capture and has a fixed final sequence. `capture.finish` contains the capture title. The app stops the microphone and drains pending audio buffers before appending this boundary and freezing the capture. Each new capture receives a new session UUID and independent scene/clock.

On each connection the iPad sends a handshake (outside the capture journal):

```json
{"type":"capture.hello","session":"UUID","through":4200}
```

`through` is the final sequence for a saved upload, or null for a live stream. The receiver replies `{"ready":1234,"session":"UUID"}` with its durable head. The client skips already received events. Session UUID matching is case-insensitive. This handshake makes resume work after either process restarts; client-side cached receipts are UI status, not authority.

Live streams and saved uploads send up to 64 available events / roughly 512 KiB per batch. A single larger event is sent alone. Live streaming sends available events immediately without waiting for a full batch:

```json
{"type":"capture.batch","events":[{"v":1,"session":"UUID","seq":1235,"t":1.2,"type":"stroke.end","data":{"stroke":"UUID","cancelled":false}}]}
```

The receiver validates the batch before appending anything, writes all new events and synchronizes them to disk, then replies `{"ack":1235,"session":"UUID"}` for the batch’s final sequence. The iPad keeps one batch awaiting confirmation and uses the journal as a bounded-memory spool. Deduplicate by `(session, seq)`. A receiver missing earlier events replies `{"replayFrom":1}` (or the next required sequence); the iPad seeks through the journal and resumes there. Connection loss or a ten-second handshake/acknowledgement timeout triggers a retry. Upload completes only when the receiver confirms the saved capture’s final sequence, then closes its connection. The local original is retained.

Saved captures have immutable `.ndjson` files and separate JSON metadata containing names, creation times, sizes, final sequences, and confirmations per receiver address. Metadata writes are atomic. Startup recovers a saved capture even if the process ended before its metadata was written. The working-journal pointer prevents a completed capture from reopening as an editable draft after finalization. Saved captures and the current live journal have separate connections, so transferring an earlier recording never joins their timelines.

The standalone Python receiver’s `/watch` is a second, receive-only socket for **newly committed** events. It has no replay or acknowledgement mechanism. A subscriber must connect before capture/replay, or reconstruct past state from the archive. A subscriber falling more than 512 events behind is disconnected with code 1013 and must recover from disk. The authoritative capture remains the receiver archive and iPad journal.

## Durability and limits

Appends run on a serial I/O queue. They are synchronized to disk once a second, when suspending, when stopping audio, and before exporting. An abrupt process/device failure may lose the last unsynchronized tail. Startup removes only an incomplete final line; a malformed complete event produces an error without rewriting the saved data. Undo, redo, layer state, and corrections are all additional events.

The current working journal is restored at launch; finished captures remain in the saved-capture library. Scene replay retains measured drawing samples in memory; audio and predictions are processed incrementally rather than retained in scene memory. Very long drawings will eventually need chunked scene indexing and a GPU renderer. Export creates a stable journal copy; there is no audio playback or timeline scrubber in this first version.

Both the standalone receiver and Field’s native `/drawingon/ws` route are exercised by native URLSession integration tests. See [Field integration](FIELD_EXTENSION.md) for setup and the box API.

Implementation references: [Apple Pencil input](https://developer.apple.com/documentation/uikit/handling-input-from-apple-pencil), [estimated-property corrections](https://developer.apple.com/documentation/uikit/uitouch/estimatedpropertiesexpectingupdates), [AVAudioTime](https://developer.apple.com/documentation/avfaudio/avaudiotime).

## Field geometry return channel

Field uses `/drawingon/ws?target=ipad` for the capture protocol above. Only a live handshake (`through: null`) subscribes to reference output. Saved uploads receive ordinary ready/ack messages. The return envelope is:

```json
{"type":"drawingon/reference","target":"ipad","title":"Plan","plane":{"origin":[0,0,0],"xAxis":[1,0,0],"yAxis":[0,1,0],"scale":1,"offsetX":0,"offsetY":0},"geometry":{"type":"three/geometry","uid":"drawingon:ipad","revision":"UUID","sourceBox":"box-id","buffers":{"generation":"UUID","bytes":0,"entries":[]}},"sha256":"sha256-of-complete-buffer-body"}
```

The geometry descriptor is the same FLine description as the AVP Three viewer, with `lines`, `fills`, `points`, optional drawables, and typed attributes/indices. A `buffer` attribute references an entry `{type,offset,count,hash}` in the buffer table. Offsets are bytes; counts are scalar elements; integers and floats are little-endian. The iPad fetches `/drawingon/buffers?target=ipad&generation=UUID` from the same host, changing `ws` to `http` or `wss` to `https`. Superseded generations return 404. Generation checks prevent an older fetch replacing a newer revision. No buffer URL is accepted from a message.

A fully fetched, verified, decoded response becomes a reference revision. The iPad first journals `field.asset` chunks `{sha256,part,parts,bytes,data}` (zero-based parts, base64 data, at most 128 KiB per chunk), once per asset per capture. Then `field.reference` contains `{reference:{layer,target,title,plane,geometry,asset}}`, where `asset` is the complete buffer SHA-256 or null for inline geometry. These are normal sequenced/time-stamped capture events; arrival/application time is on the iPad's capture clock. `sourceBox` and `revision` stay inside `geometry`. Layer IDs use `field:TARGET`, and `layers.state` entries for these layers have `source:"field"`. Replacements preserve that layer's visibility/solo state and do not become Pencil undo candidates.

A fresh replay reassembles and verifies asset chunks before applying the corresponding reference record. Asset data also lives in the app's `Field assets/` cache, but this cache is unnecessary to exchange a capture. References received in an earlier capture are written into the next capture again if Field re-sends them. Disconnecting freezes the last locally applied reference. Field never automatically republishes received `field.*` records; a box decides which human/input events should cause a response.
