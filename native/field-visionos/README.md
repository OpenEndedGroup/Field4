# Field4Vision — visionOS shell (handoff)

A SwiftUI + WKWebView shell that hosts the existing Field4 web front end as spatial "docks"
on visionOS. It is a **thin third RPC client** alongside the browser and the Python backdoor —
it renders the *existing* Field server endpoints (`/welcome`, `/index.html?file=…`) in
`WKWebView`s that float in space. No Field4 changes are required to *run* it; the display-mode
features below are additive server-side niceties.

> This directory is vendored into the field4 repo (single source of truth, one git history) but
> still built as its own Xcode project — visionOS can't be built from the Field4/Gradle toolchain,
> so there's no cross-build integration, just a shared repo. Xcode 26.5 / visionOS 26.x SDK.

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
- `../../src/jvmMain/kotlin/afield/four/stream/FieldRTTStreamer.kt` — the **producer**, OS-branching
  internally (Mac: IOSurface ring imported from the helper; Linux: Vulkan ring allocated here and
  handed to the helper). `FieldEncodeProcess.kt` is the JSON stdin/stdout driver, shared by both
  backends.

This shell is the **receiver** end of that pipeline (see `RTT/` below).

## Files

- **Field4VisionApp.swift** — `@main` App. Two scenes: the main `WindowGroup { ContentView() }`
  and `WindowGroup(for: URL.self)` for floating windows (undocked docks + `window.open` +
  the floating editor). `UndockedWebDockWindow` wraps a `WebDock` for those.
- **ContentView.swift** — `DockManager`-backed `NavigationSplitView`: a collapsible rail of docks
  + one **bottom ornament** carrying every control (toggle · Welcome · Canvas · `</>` Floating
  Editor · Undock). There is **no top title bar** — visionOS won't shrink it via title modifiers,
  so we dropped it and reclaimed the height (the doc name shows in the rail).
- **DockManager.swift** — dock model + open/close/relabel. `openGlassCanvas` (the default view),
  `floatingEditorURL`, `openWelcome` (single-welcome invariant), `updateDock`/`classify`
  (relabel on in-place navigation), `selectionContext(for:)` = `field:<file>`.
- **WebDock.swift** — `UIViewRepresentable` `WKWebView`. Trust override for the self-signed cert
  (`mi2` / `*.ts.net` / loopback); `WKUIDelegate.createWebViewWith` routes `window.open(_blank)`
  to a new window; `didFinish` relabels docks; `isOpaque = false` so transparent pages show the
  window glass; pushes `--shell-corner-inset` (CSS var) so web toasts tuck clear of the window's
  rounded corner. `shellLog` (subsystem `com.field4.vision`) for diagnostics.
- **ServerConfig.swift** — `baseURL` (currently `https://10.0.0.4:9092`, the Mac's plain-LAN
  address — see Running), overridable at runtime with `FIELD4_SERVER` so you can point at a
  Tailscale name or another port without a rebuild.
  `documentURL(file:flags:selectionContext:)` builds `/index.html?…` URLs.
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

## What works (as of this handoff)

- **Multiple windows** — floating editor / undock / `window.open` all open real windows via
  `openWindow`. (If this ever silently no-ops again, see "Multiple scenes" below.)
- **Collapsible rail; all controls in a bottom ornament; no top title bar.**
- **Glass field** — translucent frosted backdrop with a translucent vignette + the pannable
  scroll-dots preserved; **opaque, black-bordered box "islands"** float on it; Monaco stays solid.
- **Shared selection** — an editor dock and a canvas dock that pass the same
  `?shaderSelectionContext` track one selection (server-mediated; works across separate WKWebView
  processes). Verified cross-client (a browser drove a selection; the sim followed).
- **`editorOnly`** — server skips the box-placeholder pre-render; an **idle editor is glassy**,
  going solid only when a box is selected (`:has(.box.selected)`).
- **Toasts** tuck clear of the window's rounded corner (state-aware `--shell-corner-inset`:
  0 when the rail covers the corner, ~56 when collapsed or in a standalone window).
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
- **Editor placement.** The floating editor's `WindowGroup(for: URL.self)` placement moved from
  `.utilityPanel` (tilted) to `.automatic` (upright, forward, in front of the active window). Relative
  `.leading/.trailing(mainWindow)` is still avoided — it resolves off-display and fails to open.
  It is **opt-in**: it no longer opens itself on launch, because a second window that takes keyboard
  focus costs more in focus-juggling than the immediate shared-selection pairing is worth. Open it
  with the `</>` ornament button, or set `FIELD4_AUTO_EDITOR=1` to restore launch auto-open (for
  headless/paired-client testing, where nothing can tap a button).
- **Canvas-owned panel routing.** The context formerly used only for shared selection now also
  carries short-lived `shared-ui-intent` messages. Choosing **Show Document History** in the
  floating editor's command palette opens the history overlay in its paired canvas WKWebView,
  where box/history interaction belongs, rather than inside the editor panel. The `noeditor`
  projection keeps Monaco/docs hidden but now permits the full-width history overlay. The
  `visionos-shared-history-panel` browser regression drives two independent clients to protect the
  handoff.
- **First native viewer — `FLine/`.** A fully-3D RealityKit FLine viewer, the native equivalent of the
  three.js iframe. It speaks the SAME `__threejs__` protocol over `/ws` (`FLineViewerClient` sends one
  `subscribe-all` and receives every live view's `three/geometry` payload, keyed by uid). Strokes →
  swept **tubes** (real thickness, unlike three.js's flat lines), fills → triangles, points → spheres;
  per-FLine tinted `UnlitMaterial`. Orbit/scale/reset, tap → `fline-click`, and (editable views)
  node-drag → `fline-edit-translate` + Clear. Presented in a resizable volumetric window (scene
  `flineWindowID`); ornament button "FLine 3D". Files: `FLineWire` (Codable payload/edit mirrors),
  `FLineViewerClient` (WS + self-signed trust via the shared `isTrustedFieldDevHost`), `FLineMeshBuilder`
  (procedural geometry — no `.rkassets`, so no pbxproj edits), `FLineViewerView` (RealityView + gestures).
  - **Server side (field4 repo):** `ThreeJsViewer.kt` gained a `threeGlobalListeners` broadcast + a
    `subscribe-all` message; `Server.kt`'s `/ws` finally calls `detachThreeGlobalListener`. No protocol
    change — existing web viewers are untouched. Editing/thickness need a view created
    `threeLines(..., editable = true)` (only that path emits the `scene` block), same as the web viewer.
  - **Parity gaps (documented):** marquee / point-edge-face selection masks (need screen-space
    projection a volume doesn't expose) → replaced by tap-select; freehand begin/append/end-line has
    wire + client support but no gesture UI yet; cubic C1/C2 control handles are a stretch; fills in edit
    mode use fan triangulation (convex-correct); whole-FLine tap targets use an AABB (coarse). All
    RealityKit interaction is **device-verify** (the sim is non-interactive for spatial input).

## Live Metal — programmable native graphics

`Field4Vision/LiveMetal/` is a second native head, independent of the RealityKit FLine viewer. It
keeps the Field UI in its existing WKWebView windows and opens a mixed-immersion Compositor Services
scene with its own Metal render thread. Press **Live Metal** in the bottom ornament (or launch with
`FIELD4_LIVE_METAL_AUTOSTART=1`).

The renderer subscribes directly to Field's `/ws` bus on channel `__visionmetal__`; WKWebView is not
in the graphics or animation path.

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
which sweeps a polygon along a polyline, the same treatment the native FLine viewer gives a stroke, so
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

`src/jvmMain/kotlin/afield/graphics/VisionMetalKotlinJs.kt` is the typed Kotlin/JS description of the
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

## Field host-side dependencies (server must serve these)

These now live alongside the shell in this Field4 repository. To run the shell with all features,
the Field server build must include:

- `rpc.kt` — `Hello.shaderSelectionContext` + the ephemeral `SharedUiIntent` payload.
- `Server.kt` — `selectionContextSessions` registry + `controller-selection`/`shared-ui-intent` rebroadcast to
  context peers; join on `hello`, leave in the `/ws` finally; `injectInitialFramePreview` skips
  `editorOnly`; HTTPS connector binds `0.0.0.0`.
- `graphics/VisionMetal.kt` — the replayable, part-wise `__visionmetal__` program/status channel; the
  `Box.msl` property; the box-facing `visionMetal(...)` API; and the explicit reload commands.
- `graphics/VisionMetalKotlinJs.kt` — the Kotlin/JS surface, including the `geometry { … }` builder.
- `graphics/MslEditorLanguage.kt` + `msl.js` — the Monaco `msl` language, registered as
  `bithero/msl` in `bootstrapMonaco.js`.
- Property/language registrations for `msl`: `Boxes.ioSeparateProperties` (`msl` → `.metal`),
  `saving.kt`, `server/fileproxy.kt`, `KotlinCompletionSupport.kt`, `embeddedEditors.js`, and the
  three parallel arrays in `BootstrapMonacoInit.kt` (which must stay aligned).
- `BootstrapMonacoActions.kt` — `msl` → `visionMetal_reload`, which is what binds `alt r` in the MSL
  pane to a headset recompile.
- `Main.kt` — parse `editorOnly` / `noeditor` / `transparent` / `shaderSelectionContext`;
  `fieldDisplayMode` + body classes; inbound `controller-selection` handler → `applyRemoteSelection`
  (echo-guarded); canvas-side `shared-ui-intent` handling; `shaderSelectionContext` passed in `Hello`.
- `BootstrapMonacoRuntime.kt` — the Monaco selection monitor gated on display mode
  (no-editor never shows / steals focus; editor-only stays visible with no selection).
- `field.css` — `mode-editor-only` / `mode-no-editor` / `mode-transparent`; black box border +
  `11px` `::before` radius; `--shell-corner-inset` toast tuck; glassy empty editor-only.

### URL params understood by the front end

- `?editorOnly` — Monaco fills the window, canvas hidden.
- `?noeditor` — canvas only; editor/docs hidden, canvas-owned overlays such as History remain available.
- `?transparent` — glass field backdrop, opaque boxes/outputs (Monaco keeps its solid theme).
- `?shaderSelectionContext=NAME` — join a shared UI context (selection + ephemeral panel intents,
  bidirectional; the legacy parameter name is retained for compatibility).

All are additive and orthogonal (e.g. `?noeditor&transparent&shaderSelectionContext=field:default`).

## Running

1. On the Mac, start the Field server: `./gradlew runJvm` in the field4 repo (default port 9092).
   The HTTPS connector binds `0.0.0.0`, so it is reachable over the plain LAN — and over Tailscale
   as `mi2` if that is running. If a CI loop is bouncing 9092, use `PORT=9093 ./gradlew runJvm`
   and point the shell at it with `FIELD4_SERVER=https://10.0.0.4:9093` rather than editing source.
2. Build/run this project against a **visionOS ≥ 26** simulator or device (older runtimes reject
   the app — the sim list has 1.0/1.2/2.2 devices that will fail to install).
3. Cert: self-signed, issued for `localhost`. `isTrustedFieldDevHost` overrides trust for loopback,
   `mi2`/`*.ts.net`, and RFC1918/link-local IP literals — which covers the LAN address, whose SAN
   would never match anyway.
4. Over plain LAN the app needs **Local Network** permission (`NSLocalNetworkUsageDescription`,
   in the root `Info.plist`). Accept the prompt on first launch or nothing connects. Tailscale
   addresses are exempt — they go out a `utun` interface — which is why this only bit once we
   moved off Tailscale.

## Gotchas (hard-won)

- **9092 can be bounced by a CI loop** → if that bites, run on 9093 and select it with
  `FIELD4_SERVER=https://10.0.0.4:9093` instead of editing `ServerConfig`.
- **`field.css` / `index.html` are served from `build/…` dirs, not `src/`** — a webpack /
  `processResources` run (or a manual `cp` into `build/js/packages/afield/kotlin/` +
  `build/processedResources/{js,jvm}/main/`) is needed for edits to reach a running server.
- **WKWebView caches CSS** — `simctl uninstall` + `install` to clear when iterating on CSS.
- **Headless simulator limits:** you cannot tap ornament buttons from `simctl` (needs the
  interactive Simulator or a real device / XCUITest), the screenshot camera is locked forward so
  a second spatial window lands off-frame, and `defaultSize` / `defaultWindowPlacement` have
  interacted badly with `openWindow` in the sim. **Window placement + interaction is device work.**
- WKWebView content paints a second or two after launch (brief black pane) — wait before judging.

## Next / queued

- **Editor window placement** — land it as a utility panel floating *just in front* of the canvas,
  ~half width, right-inset. `.utilityPanel` is the right primitive; it's draggable by its bar for
  free. Verify on device (sim placement is finicky).
- **Command palette** — per-window is essentially free: each `WKWebView` has its own JS + keyboard
  focus, so ⌘P / ⌘⇧P / double-tap-shift render their palette in whichever window has focus.
  ~~Route the canvas-scoped History panel over the shared context~~ **done** via
  `shared-ui-intent`, including a two-client browser regression. Double-tap-⌘ to evict editor focus
  is native + minor → deferred.
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
dead Floating-Editor / Undock buttons). This is **fixed and in the repo**: the root **`Info.plist`**
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
