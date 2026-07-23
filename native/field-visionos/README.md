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
- **ServerConfig.swift** — `baseURL` (currently `https://mi2:9093`; normally 9092 — see Running).
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
in the graphics or animation path. In a normal Field Kotlin box:

```kotlin
visionMetal(
    shader = visionMetalExampleShader,
    script = visionMetalExampleScript,
    uniforms = listOf("phase", "amount", "tint")
).onStatus { println("${it.phase}: ${it.message}") }
```

This displays the built-in indexed cube. `visionMetal(...)` can instead receive a
`Collection<VisionMetalVertex>` plus an `IntArray` of triangle indices. Rerunning the box replaces
the active program. MSL compilation happens off the render thread; a successful pipeline is swapped
at a frame boundary, while a failed edit leaves the last successful shader visible and reports its
compiler error to Field.

The native runtime prepends this stable ABI to user MSL:

```metal
struct FieldVertex {
    float4 position;
    float4 normal;
    float4 color;
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
};
```

User source defines `field_vertex` and `field_fragment`. Helpers are available as
`fieldTime(frame)`, `fieldDeltaTime(frame)`, `fieldPredictedDisplayTime(frame)`, and
`fieldUniform(frame, index)`. The vertex entry point receives vertices at buffer 0 and `FieldFrame`
at buffer 1; the fragment entry point receives the same frame at buffer 1. See
`visionMetalExampleShader` for complete signatures.

The accompanying JavaScript runs in JavaScriptCore on the render executor, not in WKWebView. It may
install one autonomous callback and update the declared uniform names:

```javascript
uniform("amount", 0.025)
uniforms.tint = [0.1, 0.8, 1.0, 0.35]

onFrame((time, dt, predictedDisplayTime) => {
    uniform("phase", time * 3.0)
})
```

Each name maps to one `float4` slot in declaration order. Scalar writes use `.x`; missing components
are zero. Uniform storage is triple-buffered and copied once per compositor frame. JavaScript source
and MSL source both receive `field://visionmetal/<uid>` source URLs for useful diagnostics.

The current first slice intentionally has a small surface: one active indexed triangle mesh, one
pipeline, alpha blending, depth testing, 16 named `float4` uniforms, and one JS `onFrame` callback.
Textures, compute functions, multiple draw items, interaction and FLine GPU tessellation belong on
top of this resident runtime; they do not require another architectural bridge.

### Kotlin/JS external surface

`src/jvmMain/kotlin/afield/graphics/VisionMetalKotlinJs.kt` is the typed Kotlin/JS description of
the JavaScriptCore globals above. It deliberately derives its API from this native host rather than
pretending that browser, THREE, or DOM globals exist. A Kotlin/JS program using it has this shape:

```kotlin
// {{ visionmetal uniforms=phase,amount,tint }}
uniform("amount", 0.025)
uniform("tint", 0.1, 0.8, 1.0, 0.35)
onFrame { frame ->
    uniform("phase", frame.time * 3.0)
    log("predicted display time = " + frame.predictedDisplayTime)
}
```

The first-line marker selects the `visionmetal` head profile. The compiler adds the Kotlin/JS
externals; completion receives the colocated JVM-analysis-safe mirror of that public surface, and
execution publishes the compiled plain module through the existing replayable
`visionmetal/program` channel. MSL comes from the box's `fragment` property, or from
`visionMetalExampleShader` when the property is empty. The
optional `uniforms=` list fixes shader slot order; without it, literal uniform writes are inferred
(the built-in shader retains `phase,amount,tint` first). `remotejs.kotlinjs-head-profiles` verifies
marker parsing plus compilation and global-name linking for both Live Metal and RemoteAR host
shims. Device execution remains part of native-head verification; the JVM smoke does not substitute
for JavaScriptCore, Metal compilation, or compositor presentation on visionOS.

## Field host-side dependencies (server must serve these)

These now live alongside the shell in this Field4 repository. To run the shell with all features,
the Field server build must include:

- `rpc.kt` — `Hello.shaderSelectionContext` + the ephemeral `SharedUiIntent` payload.
- `Server.kt` — `selectionContextSessions` registry + `controller-selection`/`shared-ui-intent` rebroadcast to
  context peers; join on `hello`, leave in the `/ws` finally; `injectInitialFramePreview` skips
  `editorOnly`; HTTPS connector binds `0.0.0.0`.
- `graphics/VisionMetal.kt` — the replayable `__visionmetal__` program/status channel and the
  box-facing `visionMetal(...)` API used by the native Compositor Services renderer.
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

1. On the Mac, start the Field server: `PORT=9093 ./gradlew runJvm` in the field4 repo.
   **Use 9093** — a CI loop bounces a backend on 9092, which yanks the shell's connection. The
   HTTPS connector binds `0.0.0.0`, reachable as `mi2` over Tailscale from the sim/device.
2. Build/run this project against a **visionOS ≥ 26** simulator or device (older runtimes reject
   the app — the sim list has 1.0/1.2/2.2 devices that will fail to install).
3. Cert: self-signed with `mi2` in its SAN; `WebDock` trusts `mi2`/`*.ts.net`/loopback.

## Gotchas (hard-won)

- **9092 is bounced by a CI loop** → dev on 9093.
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
