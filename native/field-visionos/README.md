# Field4Vision

Field4Vision is the native toolbar for Field on Apple Vision Pro. Use Field's regular editor
over Mac Virtual Display or in Safari. The headset app runs the selection-following 3D FLine
viewer, windowed and immersive RTT video, Live Metal, and [Spatial Ink](SPATIAL_INK.md).

## Connect to Field

Start Field on a host the headset can reach. For LAN or tailnet access, launch it with
`AFIELD_BIND_HOST=0.0.0.0` on your trusted network. Its HTTPS port defaults to 9092.

Enter a host with an optional port, such as `field.local:9093`, or a full HTTP(S) URL and
choose **Connect**. The panel displays the active URL, Field port, and reachability. The clock
menu recalls the last eight hosts that answered. Your last selection returns when you reopen the
app. The optional `FIELD4_SERVER` launch environment variable takes precedence at startup and is
useful for scripted runs.

The 3D viewer reconnects when you change hosts. Exit Spatial Ink or Live Metal first to finish
their immersive session before changing the connection. RTT receives UDP independently. Send
video to the headset's address and the UDP port shown in the panel, which defaults to 9000 and can
be overridden with `FIELD4_RTT_PORT`.

For an existing desktop OpenXR scene on AVP over Wi-Fi, see the separate
[Linux CloudXR route](../../tools/cloudxr/README.md) and [route comparison](../../public/docs/vision.md).

## Companion code (the field4 backend + senders)

The Field backend, its Vulkan renderer, and the **RTT stream senders** live in this same repo,
one level up. Key cross-references:

- `../../dev_docs/vision_frontend.md` — full architecture + rationale (the "sender seam" section).
- `../field-encode/WIRE_FORMAT.md` — the UDP media + control contract this shell's receiver
  implements against (authoritative). Its "Linux sender addendum" documents the SCM_RIGHTS fd
  handoff `../field-encode-linux`/`../field-fdpass` use — receiver-transparent, same wire format.
- `../field-encode/` — the macOS VideoToolbox **sender** co-process (`field-encode`).
- `../field-encode-linux/` + `../field-fdpass/` — the Linux/NVENC **sender**, verified end-to-end
  against this receiver (real device, sustained streaming, zero dropped/malformed datagrams). Same
  control + wire protocol as the Mac sender; this receiver needed zero changes for it.
- `../../src/jvmMain/kotlin/afield/media/stream/FieldRTTStreamer.kt` — the **producer**, OS-branching
  internally (Mac: IOSurface ring imported from the helper; Linux: Vulkan ring allocated here and
  handed to the helper). `FieldEncodeProcess.kt` is the JSON stdin/stdout driver, shared by both
  backends.

This shell is the **receiver** end of that pipeline (see `RTT/` below).

## Files

- **Field4VisionApp.swift** — native launcher, volumetric windows and immersive scenes.
- **ContentView.swift** — connection panel, recent hosts and native head controls.
- **FieldConnection.swift** — reachability and host history.
- **ServerConfig.swift** — host normalization and WebSocket addressing.
- **RTT/** — the GPU/immersive half: the receiver for the `field-encode` UDP stream (see
  `native/field-encode/WIRE_FORMAT.md` in the field4 repo). One-way, lossy, no jitter buffer.
  - **RTTWireFormat.swift** — the 20-byte big-endian header parser (`RTTPacket`); a direct mirror
    of the `Wire` enum in the sender. Rejects unknown `version`/`codec` rather than misparse.
  - **FrameReassembler.swift** — accumulates fragments by `frameSeq` → a whole Annex-B access unit;
    abandons a frame once a newer one completes (spec's "present newest, drop stragglers"). Pure
    value logic, the unit-testable core.
  - **RTTReceiver.swift** — one BSD `recvfrom` loop on a serial queue, bound to udp/9000 (all
    interfaces), feeding the reassembler. Close-the-socket cancellation.
  - **VideoDecoder.swift** — Annex-B → VideoToolbox. Builds a `CMVideoFormatDescription` from the
    inline VPS/SPS/PPS on the first keyframe (HEVC *and* H.264), rewrites Annex-B to length-prefixed
    NAL units, decodes to NV12 (`420v`) `CVPixelBuffer`s.
  - **PixelBufferTextureConverter.swift** — NV12 → RGBA via a runtime-compiled Metal compute kernel
    (BT.709, video-range) into a RealityKit `LowLevelTexture` wrapped as a `TextureResource`. Runtime
    `makeLibrary(source:)` so no offline Metal toolchain is needed to build.
  - **RTTStream.swift** — `@Observable @MainActor` orchestrator: owns receiver + decode pipeline +
    converter, publishes `displayTexture` + status/fps. Receive+decode stay on the recv queue (in
    order); only the texture upload and stats hop to the main actor.
  - **RTTScreenContent.swift / ImmersiveStreamView.swift / RTTStreamWindow.swift** — the present
    layer: a shared textured quad (`RTTScreenContent`) sampling `displayTexture`, hosted in both a
    dimmed world-locked `ImmersiveSpace` (`ImmersiveStreamView`) and a movable/resizable volumetric
    window (`RTTStreamWindow`); receiver lifecycle is ref-counted (`RTTStream.acquire/release`).
    Stereo (SBS) uses the hand-authored Camera Index Switch material `RTT/SBSMaterial.usda` (present +
    verified loading), with a mono `UnlitMaterial` fallback; per-eye separation is device-only to
    confirm (the sim renders one eye).

- **Pose/** — the **uplink**, the only channel that runs device → Mac. Everything else on the wire
  pushes *to* the head (a program on `__visionmetal__`, pixels on the RTT stream); nothing told the
  Mac where the wearer is looking, so a streamed image could only ever be a world-locked surface.
  This is what makes it a view. Kotlin end (authoritative): `afield/graphics/VisionPose.kt`.
  - **PoseWire.swift** — `Encodable` mirrors of the `__visionpose__` messages
    (`visionpose/hello` · `/frame` · `/bye`) and the column-major `simd_float4x4` → `[Float]`
    flattening. Column-major is load-bearing: `simd_float4x4` and the Kotlin `Matrix4f` agree, so the
    array crosses untransposed — and a transposed rigid transform still looks like a plausible pose,
    which is the worst way for this to be wrong.
  - **PoseUplink.swift** — the sender. Deliberately **not** `@MainActor`: it is driven from the
    Compositor Services render thread, and hopping to the main actor would put tracking behind
    SwiftUI. **Latest-wins, never queued** — when the socket is still busy the new pose is dropped,
    because a queue here turns network jitter into accumulating pose delay (the failure that reads as
    "tracking feels laggy" and cannot be found in the renderer). Counts sent/dropped;
    `FIELD4_POSE_DIVISOR` thins the rate, `FIELD4_POSE_NAME` labels the head.
  - Fed from `LiveMetal/LiveMetalRenderer.swift` — `beginFrame`/`endFrame` bracket the drawable loop
    and `record` sits at the one line that already holds both the drawable and the device anchor, so
    the pose that travels is the pose that drawable was rendered from. That association is the point;
    re-querying ARKit in the uplink would make it a guess that happens to be close.

## Native heads

- **RTT streaming — full spine, end-to-end, on both sender backends.** The "Immersive" ornament
  button starts the receiver and opens a dimmed theatre showing the decoded stream on a
  world-locked screen. **Verified with the real Field sender** (`FieldRTTStreamer`, see Companion
  code): Field renders into an RTT → HEVC → UDP → this receiver reassembles → VideoToolbox decode →
  `LowLevelTexture` → material, **with correct colour** — on macOS/MoltenVK (IOSurface-backed RTT)
  and, since the Linux/NVENC sender landed, on Linux/NVIDIA too (Vulkan external-memory-backed RTT,
  same wire format, zero receiver changes). Also verified against `field-encode --selftest` (HEVC +
  H.264, correct header parse, inline VPS/SPS/PPS, `420v` NV12).
  - **Sender restarts are handled** — relaunch Field (its `seq` resets to 0) without reinstalling
    this app; the reassembler detects the restart (keyframe near seq 0 after a high-water mark) and
    re-locks on the new IDR. (Before the fix, a restarted stream was dropped as stragglers forever.)
  - **Rate** — the sender emits at its configured `fps`, not the render rate; keep it low (~15) for
    the *simulator*'s software decoder (a 60/90fps flood there causes reference-loss flicker). On
    device, hardware decode is real-time.
  - **Present smoothness is device work** — the visionOS *simulator* paints the immersive scene at
    ~1fps and is non-interactive, so the decode fps (status label) far exceeds what you see there.

## QoL additions (visionos-head-qol branch)

Four improvements on top of the PoC:

- **Windowed RTT stream.** The stream screen was factored out of `ImmersiveStreamView` into a shared
  `RTT/RTTScreenContent.swift`, now hosted in BOTH the dimmed immersive theatre AND a new
  movable/resizable **volumetric window** (`RTT/RTTStreamWindow.swift`, scene `rttWindowID` in
  `Field4VisionApp`). The receiver is ref-counted (`RTTStream.acquire()/release()`): it starts on the
  first presentation opened and stops only when the last closes, so the window and theatre are
  independent. Ornament button: "Stream Window".
- **Native three.js viewer — `Three/`.** A RealityKit volume that shows the 3D views of the box
  selected in Field: select a box on the Mac (in Mac Virtual Display) and its `three { }` /
  `threeLines(...)` views appear in the volume. Ornament button "3D View" (scene `threeWindowID`).
  - **Wire:** the same descriptions and buffers the browser viewer gets. `ThreeClient` sends `follow` on
    the `__threejs__` channel (optionally `document`, or `FIELD4_THREE_DOCUMENT`). Field answers with
    `three/follow`, listing the selected boxes and the views they own, and sends it again whenever that
    changes. The client sends an ordinary hello for each view and `detach` for views it no longer needs.
    For each `three/geometry` description it fetches the typed arrays from `/three-buffers` and binds
    them (`ThreeWire.swift`, the Swift twin of `bindWireBuffers`).
  - **Drawing:** `ThreeMeshBuilder` makes one entity per drawable, named by its id, with its transform
    and `hidden` applied. The FLine half (`lines`/`fills`/`points`) is handled as three more drawables.
    Surfaces get `PhysicallyBasedMaterial` from the drawable's material (or `UnlitMaterial` when
    `unlit`), lines become swept tubes and points small octahedra. RealityKit has no per-vertex colour,
    so colours are bucketed: each triangle, segment or point takes its first vertex's colour (quantised,
    at most 48 per drawable), and each bucket is one mesh part.
  - **Holding:** when the selection moves to a box with no 3D view (the plan beside the model), the
    volume keeps what it shows and marks it with a pin. Selecting a box that has views replaces them.
  - **Explode and layers come from Field.** The browser viewer of the same view, in the Field you are
    coding in, sends `three-view-state` (its explode amount and the drawable ids hidden in its Parts
    list) whenever either changes. Field keeps the latest per view, relays it to the view's other
    clients as `three/view-state`, and replays it to a client that attaches later. The headset moves
    each drawable by its assembly group's offset (`ThreeAssembly.swift`, the same radial/axis
    arithmetic as `explodeOffsets`) and hides what the browser hides. The controls stay on the Mac;
    the headset has none of its own yet. It works the way the controller page's sliders do.
  - **Server side:** `follow`, `detach`, `three/follow` and the view-state relay live in `ThreeJsViewer.kt`.
    `recordControllerSelection` (`controllers.kt`) feeds it the selection, and publishing a view
    re-announces it to followers of its owner. The old `subscribe-all` and the separate native-JSON
    encoding are gone.
  - **Mirrored, not recomputed:** the browser viewer sends its state after every frame it draws
    (`three/view-state`): each drawable's final matrix (`placements`, with explode, motion sliders
    and assembly steps already applied), each assembly group's explode offset (`groupOffsets`, which
    moves the FLine half, split by group into one entity per group), hidden parts, the selected part,
    x-ray (`xray`), and while a shot plays its look (`shot`: the per-vertex `explode` amount, rebuilt
    in thirtieths and cached, and the solid-to-authored opacity mix).
  - **Styles:** the scene's `partStyles` (colour, opacity, highlight, muted) and the stored part
    appearance (`partAppearance`, `three/appearance`, which wins) are applied as the browser applies them.
  - **Picking:** tap a part to select it in Field; the volume sends `three-view-command`
    `{select: id}`, Field relays it to the browser viewer, which selects it as a click would, and its
    next state draws the yellow selection box here. Tap it again to clear.
  - **Updates:** arrays are reused by content hash and only the rest fetched
    (`/three-buffers?only=`); a drawable whose arrays and style are unchanged reuses its built mesh.
  - **Lights:** one key light plus the volume's environment lighting; the browser's studio and
    path-trace modes are not mirrored.
  - **Drawn here:** lines are 1 px line-topology meshes (as sharp as the bounding cage), labels
    (`label(...)`) are SwiftUI attachments that face the viewer, and an x/y/z marker in red, green and
    blue sits at the cage's most negative corner. The fit sizes the bounds' sphere, not the longest
    axis, so orbiting never pushes a corner out of the volume. Entities are keyed per drawable, not
    per part id: `mesh(..., edges = true)` sends a surface and its edges under one id.
  - **Handles:** `three { handle(...) }` markers are 1 cm spheres, a constant size in the room. Pinch one
    and move it: the drag is in 3D, held to the handle's `axis` or `normal`, and goes to Field as
    `three-handle` (start, moves at most every 33 ms, end), the message the browser sends. Field runs
    the box's callback and relays the drag to the view's other viewers as `three/handle`, so a drag in
    the browser moves the marker here live, and the box's re-run then places it for good.
  - **Not yet:** the interactive FLine objects of `threeLines(editable = true)`, overlays, section cuts, multi-panel scenes (every panel is drawn superimposed). Surfaces are
    de-indexed, so a vertex is stored once per triangle. Spatial input can only be checked on the device.

## Live Metal — programmable native graphics

`Field4Vision/LiveMetal/` is a second native head, independent of the RealityKit FLine viewer. It
opens a mixed-immersion Compositor Services scene with its own Metal render thread. Press **Live Metal** in the bottom ornament (or launch with
`FIELD4_LIVE_METAL_AUTOSTART=1`).

The renderer subscribes directly to Field's `/ws` bus on channel `__visionmetal__`.

A program has three explicit parts, each authored somewhere different and each independently
reloadable:

| Part         | Where you write it                                   | Reloading it |
|--------------|------------------------------------------------------|--------------|
| **shader**   | the box's `msl` property                             | recompiles the pipeline; script, geometry and clock keep running |
| **geometry** | `geometry { … }` in the box's Kotlin/JS              | swaps the vertex buffers at a frame boundary |
| **uniforms** | the `uniforms=` option on the `// {{ visionmetal }}` marker | regenerates the shader's `u_NAME` defines, so it recompiles too |

Nothing is implicit. There is no empty-geometry fallback to a cube and no borrowing of the GLSL
`fragment` property for Metal source — both were proof-of-concept shortcuts.

### Wire protocol — version 2

Version 1 sent one monolithic payload, so *any* edit restarted the JavaScriptCore context, reallocated
the vertex buffers and reset the frame clock. Version 2 sends only the parts that changed, each with
its own revision; absent parts are `null` and the head leaves them resident. A shader reload is
therefore a payload carrying `shader` and nothing else.

The head's mailbox merges parts rather than replacing the payload — otherwise a geometry-only message
would discard the resident shader. Within a part, a newer revision still supersedes an older one, so a
burst of shader edits collapses to the newest rather than queueing obsolete compilations.

**A version-1 head cannot read a version-2 payload.** Server and shell ship together from this
repository; rebuild the app when `LiveMetalWire.version` changes.

### Explicit reload commands

Any box that has published a program carries these (installed by `visionMetal(...)`):

- **Reload MSL** (`alt r`) — republishes the `msl` property as a shader-only part. `alt r` inside the
  MSL editor pane routes here, because `BootstrapMonacoActions.kt` maps the `msl` property to this
  action id the same way it maps `fragment` to `moduleHere_reload`.
- **Reload Vision Uniforms** — republishes the declared slot order.
- **Reload Vision Program** — republishes all four parts together, re-reading `msl`.
- **Vision Geometry: Built-in Cube** — hands geometry back to the head's own cube. A known-good mesh
  for answering "is it my shader or my geometry?".

A failed Metal compile leaves the last good pipeline on screen and reports the error to Field. Because
the head emits `#line 1` after its preamble, the diagnostic carries the author's own line number, and
Field decorates the `msl` property at that line.

### The shader — MSL in the `msl` property

`msl` holds **both** entry points, because Metal compiles them from one translation unit. It has its
own Monaco language (`src/commonMain/resources/msl.js`, AMD module `bithero/msl`): a Monarch grammar
for MSL including `[[attribute]]` syntax and address-space qualifiers, plus completion for the Field
ABI, the `u_NAME` uniforms this particular box declares, and snippets for both entry points. There are
deliberately no lexical diagnostics — the real ones come from the device's Metal compiler.
`.metal`/`.msl` files opened in the editor get the same language, and `msl` saves beside the box as
`<name>.metal`.

The native runtime prepends this stable ABI:

```metal
struct FieldVertex {
    float4 position;
    float4 normal;
    float4 color;
    // .xy = texture coordinate, .zw = the geometry builder's two free per-vertex channels
    float4 uv;
};

struct FieldFrame {
    float4x4 viewProjection[2];
    float4x4 modelMatrix;
    // x=time, y=deltaTime, z=predictedDisplayTime, w=frameIndex
    float4 timing;
    float4 user[16];
};

struct FieldVarying {
    float4 position [[position]];
    float3 worldPosition;
    float3 normal;
    float4 color;
    float2 uv;
};
```

`FieldVertex` is four `float4`s, and the wire layout is identical, so geometry reaches the vertex
buffer as a copy rather than a repack.

Then, generated from the declared uniform list:

```metal
#define u_phase  frame.user[0]
#define u_amount frame.user[1]
#define u_tint   frame.user[2]
```

So shader source names its uniforms instead of counting slots. Writing a uniform's *value* never
recompiles; redeclaring the *names* does, since the defines change.

User source defines `field_vertex` and `field_fragment`. Helpers are `fieldTime(frame)`,
`fieldDeltaTime(frame)`, `fieldPredictedDisplayTime(frame)`, `fieldFrameIndex(frame)`, and
`fieldUniform(frame, index)` for slot-index access. The vertex entry point receives vertices at
buffer 0 and `FieldFrame` at buffer 1; the fragment entry point receives the same frame at buffer 1.
See `visionMetalExampleShader` for complete signatures.

### The geometry — Kotlin/JS in the head

Geometry is built in JavaScriptCore, on the render executor, and handed to Metal in one call. Nothing
per-vertex crosses the network or the JS bridge at frame rate:

```kotlin
// {{ visionmetal uniforms=phase,amount,tint }}
onStart {
    geometry {
        color(1.0, 0.35, 0.15)
        cube(0.18, cx = 0.45, cz = -1.2, tintFaces = true)
    }
    uniform("amount", 0.025)
    onFrame { frame -> uniform("phase", frame.time * 3.0) }
}
```

`MeshBuilder` appends an indexed triangle mesh. `vertex(x, y, z)` returns its index and picks up the
current `normal()`, `color()`, `uv()` and `extra()` state; `triangle()` and `quad()` supply indices,
so a vertex can be shared between faces or duplicated per face for facet normals. Winding is
counter-clockwise; the head culls back faces. On top of that: `cube`, `plane`, `sphere`, and `tube` —
which sweeps a polygon along a polyline, the same treatment the native three.js viewer gives a stroke, so
a line drawn in Kotlin has real thickness in the headset.

Call `geometry { … }` again at any time, including from `onFrame`, to replace the mesh. The swap
happens at a frame boundary, so a rebuild never tears. An empty builder is a legitimate way to draw
nothing.

Because there is no cube fallback any more, a box that owns its geometry and never builds any would
draw nothing with no diagnostic anywhere. The head reports that omission once, naming both fixes
(build a mesh, or run the built-in-cube command), rather than leaving you staring at an empty room.

`vertices`/`indices` on the JVM-side `visionMetal(...)` still work for publishing a mesh built in
JVM Kotlin, and `geometry = VisionMetalGeometryOrigin.BUILTIN` asks for the head's cube:

```kotlin
visionMetal(
    shader = visionMetalExampleShader,
    script = visionMetalExampleScript,
    uniforms = listOf("phase", "amount", "tint"),
    geometry = VisionMetalGeometryOrigin.BUILTIN,
).onStatus { println("${it.phase}: ${it.message}") }
```

### The uniforms

Each declared name maps to one `float4` slot in declaration order. Scalar writes use `.x`; missing
components are zero. Uniform storage is triple-buffered and copied once per compositor frame.

From Kotlin/JS: `uniform("phase", 1.0)` through `uniform("tint", r, g, b, a)`. From raw JavaScript
(the JVM `script =` path): `uniform("amount", 0.025)`, `uniforms.tint = [0.1, 0.8, 1.0, 0.35]`, or
`setGeometry(vertices, indices)` for a hand-packed mesh.

Declare slot order on the marker. Without it the server infers names from literal `uniform("…")`
writes and says so in a notification, because inference and shader source can disagree about order.
Writing an undeclared name reports the declared list back to the box rather than failing silently.

### Kotlin/JS external surface

`src/jvmMain/kotlin/afield/graphics/visionos/VisionMetalKotlinJs.kt` is the typed Kotlin/JS description of the
JavaScriptCore globals. It derives its API from this native host rather than pretending browser,
THREE, or DOM globals exist. The marker's first line selects the profile; the compiler appends the
externals; completion receives a colocated JVM-analysis-safe mirror. The `MeshBuilder` source itself
is *shared* between the two rather than mirrored by hand — only the bridge declarations above it
differ — so the compiled surface and the completion surface cannot drift.

`onStart`, `onFrame`, `onStop`, and `keepAlive` have the same Launch-only contract as the browser
heads; during Execute they are inert. Relaunch and Stop run cleanup once before replacing the
JavaScriptCore context.

### What is verified where

- `visionmetal.kotlinjs-externals` — compiles the surface, then links it under Node against a host
  shim that captures `__fieldSetGeometry`. Asserts the *real* packed output: 24 vertices / 36 indices
  for a cube, its exact bounding box and six facet normals, `position.w == 1`, `normal.w == 0`, and
  the arithmetic of `plane`/`sphere`/`tube` (every sphere vertex exactly `r` from the centre, every
  tube vertex exactly `radius` from its axis). Also asserts a shader-only payload really does carry
  `"script":null,"uniforms":null,"geometry":null`.
- `visionmetal.msl-language` — drives the shipped `msl.js` under Node with a mocked Monaco API:
  member completion resolving `frame.`/`v.` against the ABI from the surrounding declaration, prefix
  filtering, `u_NAME` slot numbering from the marker, inference order without one, and safety with no
  box selected.
- `remotejs.kotlinjs-head-profiles` — marker parsing, `msl` > `fragment` > resident precedence, the
  four reload commands and their part-wise independence, and geometry reaching the bridge.

### Pre-smoking on the simulator

`tools/vision_metal_simulator_smoke.sh [port]` drives the whole path against a **visionOS simulator**
before you reach for the headset. Most of what this slice touches is orthogonal to what the simulator
can't do, so it turns out to cover nearly all of it:

```bash
tools/vision_metal_simulator_smoke.sh
```

It builds and installs the app, starts a Field server, launches the head with
`FIELD4_LIVE_METAL_AUTOSTART=1` pointed at that server, publishes the `vision_geometry_shapes`
starting point, and then asserts on the head's own `visionmetal/status` messages — which the server
prints as `[vision-metal/ok|error] <uid> r<n> <phase>: <message>`. The assertion channel is that log,
not pixels, so it needs nobody watching. It checks:

- wire v2 decodes, and the uniforms part declares its names in order;
- **Apple's Metal compiler accepts the generated preamble**, including the `#define u_NAME` block —
  the thing no amount of JVM testing can tell you;
- Kotlin/JS geometry crosses the JavaScriptCore typed-array bridge into a real `MTLBuffer`, with the
  mesh the builder's arithmetic predicts (1337 vertices / 2380 triangles for that scene);
- a **shader-only reload** recompiles and nothing else reappears — script, lifecycle and geometry
  really do stay resident;
- a **broken shader** reports a diagnostic carrying the author's own line number, so Field can
  decorate the `msl` property at the right line.

This is not in the FIELD_TEST catalog: it needs Xcode, a visionOS 26+ simulator runtime, and a couple
of minutes. Run it by hand before a device session.

What the simulator still cannot tell you: per-eye stereo separation (it renders one mono view),
spatial input, and real display timing. Those remain device-verify.

### Still to build on this

Textures, compute functions, multiple draw items, and interaction sit on top of this resident
runtime; none of them needs another architectural bridge.

## RTT follow-up

- **RTT streaming — spine done, verified end-to-end, on both sender backends** (receiver in `RTT/`
  here; senders `FieldRTTStreamer` + `field-encode`/`field-encode-linux` one level up). Field
  renders into an RTT → HEVC → UDP → this receiver decodes and presents, correct colour, and
  survives sender restarts. Hardening sweep **done** (all committed + verified): **ring>1**
  render-ahead (`FieldRTTStreamer(ringSize=N)`, streamer draws the active slot round-robin —
  verified streaming at ringSize=3), **backpressure** (dedicated decode queue + drop-to-keyframe),
  **diagnostic gating** (`FIELD_ENCODE_VERBOSE`), and the **`Image.kt` import/memory** question
  (answered: `memReqs.size` is non-zero, so MoltenVK wants the bound memory — keep the
  allocate+bind). Remaining:
  - ~~**Stereo dock**~~ — **material authored + loads**: `RTT/SBSMaterial.usda` is a hand-written
    Camera Index Switch `ShaderGraphMaterial` (samples the SBS left/right halves per eye, mono→left),
    bundled via the synchronized group (no pbxproj edits). Verified loading in the sim
    (`sbsMaterial present`); `RTTScreenContent` binds `sbsTexture` and selects it when
    `stream.isStereo`. Remaining: confirm true per-eye separation on device (the sim renders one eye).
    Refs: `~/temp/openimmersive`, Apple's SBS→MV-HEVC sample.
  - **Device verification of present** — the sim paints the immersive scene at ~1fps and is
    non-interactive; confirm real smoothness + interaction on hardware. (App compiles for device;
    needs your signing team + a connected Vision Pro. Sender `dest` = the headset's IP:9001.)
  - ~~**`/vision/stream` route**~~ — **done**: `POST /vision/stream?action=start|stop&doc=<name>` in
    `Server.kt` (one level up) toggles a document's boxes over HTTP (thin lifecycle; content stays
    in the boxes; `stop` closes the `field-encode` co-process cleanly). Demo doc: `rtt_stream_demo`
    (window + sender, ringSize=3) in the default workspace. Verified start→decode, stop→halt via curl.
  - ~~**Linux / NVENC sender**~~ — **done**: `../field-encode-linux` (+ the SCM_RIGHTS fd-handoff
    shim `../field-fdpass`) behind the **same** `WIRE_FORMAT.md` contract — this receiver needed
    **zero changes**; it never learned which backend produced the stream. Verified against a real
    device: sustained streaming over the real network, "0 dropped, 0 malformed" across 3300+
    datagrams, correct colour (a known test color round-tripped through YUV encode/decode to the
    exact right RGB values). Capture forks on OS as anticipated: instead of the Mac's IOSurface +
    `VK_EXT_metal_objects`, the Vulkan `VkImage`'s memory is exported via `VK_KHR_external_memory_fd`
    (as a *dedicated* allocation — required, not optional) and handed to the helper via SCM_RIGHTS
    over a Unix domain socket (a `/proc/<pid>/fd/<fd>` duplication approach was tried first and
    doesn't work for NVIDIA's opaque-fd export — it's backed by a real device file, not an
    anonymous inode), which then imports it into CUDA and registers it as an NVENC
    `CUDADEVICEPTR` input surface. Stays on SBS single-stream (no MV-HEVC on Linux). See
    `../../dev_docs/vision_frontend.md` → "Backends" and "The sender seam", and
    `../field-encode-linux/README.md` for the full status including two real NVENC config bugs
    found via a live byte-level bitstream diff against this receiver (profile/tier/level and
    reference-count settings) and a duplicate-`frameSeq` bug that was actually in ad hoc sender-side
    test scaffolding, not the shipped code.

## Multiple scenes

`openWindow` requires the app to support multiple scenes, else it silently no-ops (the symptom
was a `"Unable to open a window when the app does not support multiple scenes"` runtime issue and
unopened native heads). This is **fixed and in the repo**: the root **`Info.plist`**
sets `UIApplicationSceneManifest → UIApplicationSupportsMultipleScenes = true`. It lives at the
project root (not in `Field4Vision/`) on purpose — inside the Xcode-16 synchronized file group it
would also be copied as a bundle resource and trigger "Multiple commands produce Info.plist".
The project keeps `GENERATE_INFOPLIST_FILE = YES` and Xcode merges the generated keys on top of
this base. If `openWindow` ever silently no-ops again, this is the first thing to check.

---
*Both RTT senders (Mac `field-encode`/VideoToolbox and Linux `field-encode-linux`/NVENC) are
committed one level up in this same repo, verified end-to-end against this receiver. The receiver
+ its restart fix and sim-testing affordances are committed here. Present smoothness/interaction
on a real Vision Pro remains the next thing to verify.*
