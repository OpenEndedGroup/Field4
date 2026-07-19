# visionOS architecture and current seams

Status: the native SwiftUI/RealityKit shell and both RTT sender backends are in
the repository. A distinct Kotlin/JS presentation bundle remains deferred.

This page records the cross-component decisions. Exact receiver behavior belongs
in `native/field-visionos/README.md`; the media contract belongs in
`native/field-encode/WIRE_FORMAT.md`; JVM producer behavior belongs in KDoc on
`FieldRTTStreamer`, `FieldEncodeProcess`, `MetalIOSurface`,
`VulkanExternalMemory`, and `UnixFdPass`.

## Architecture

Field already separates a server-authoritative document/runtime from browser
presentation over the shared message protocol. The visionOS shell is another
client of that server:

- WKWebViews host existing `/welcome` and `/index.html` projections for Monaco,
  canvas, history, and HTML output.
- Native RealityKit surfaces present streamed RTT pixels and FLine geometry.
- `shaderSelectionContext` groups otherwise independent web clients for shared
  selection and ephemeral `shared-ui-intent` messages.

The design deliberately does not capture live DOM into textures or reimplement
Monaco. Web docks remain two-dimensional, system-hosted web content. Native
islands are separate spatial objects linked by document/box identity rather than
being registered behind a transparent web view.

## Existing web projection seams

The regular frontend accepts additive query flags implemented in `Main.kt` and
its resources:

- `editorOnly`: editor projection;
- `noeditor`: canvas projection, while allowing canvas-owned overlays;
- `transparent`: transparent shell styling;
- `shaderSelectionContext=<name>`: server-mediated selection and UI-intent peer
  group. The legacy name is retained for compatibility.

The `shared-ui-intent` route currently carries `show-document-history` from an
editor projection to a paired canvas projection. Server join, rebroadcast, and
detach logic is in `Server.kt`; frontend application is in `Main.kt` and
`commands.kt`; payloads are in `rpc.kt`.

## Deferred Kotlin/JS fork

`src/jsVision/resources/index.html` is a served static placeholder, so `/vision`
does not normally 404. `src/jsVision/kotlin/afield/Main.kt` is a tracked seed but
is not included in a Gradle compilation and no `afield-vision.mjs` is built.
Its `_send` implementation is therefore illustrative, not live transport.

The intended boundary is still useful: protocol changes belong in
`commonMain`; a future vision presentation may diverge from `jsMain` without
forking `Messages.kt` or `rpc.kt`. The mechanism is deliberately undecided:

- an associated custom JS compilation would be an in-module, soft boundary and
  would require proving browser bundling; or
- a contract module would require replacing the current module-local
  `expect`/`actual _send` seam with an injectable transport before moving shared
  protocol code.

Do not describe the seed as a working second frontend until the build actually
produces and serves that bundle.

## RTT stream

The stable producer API is `FieldRTTStreamer.start()/surfaces/stop()`. It
self-registers as a render target and signals the encoder only after the current
graphics frame completes; callers attach content to every returned ring surface
and must not add a parallel frame-trigger loop.

Both OS paths feed the same packet format and native receiver:

- macOS: the helper allocates IOSurfaces, Field imports them as RTT backing, and
  the Swift helper uses VideoToolbox;
- Linux/NVIDIA: Field creates dedicated, linear, exportable Vulkan images and
  transfers their real file descriptors with SCM_RIGHTS; the C++ helper imports
  them through CUDA and drives NVENC.

Pixels never travel over the JSON control stream. It carries allocation/open,
per-frame readiness, bitrate, self-test, and close commands. Encoded Annex-B
access units are fragmented over UDP according to `WIRE_FORMAT.md`. Side-by-side
stereo is a flag and content convention; producing both eye views remains the
caller's responsibility.

`POST /vision/stream?action=start|stop&doc=<name>[&boxes=...]` is a thin lifecycle
route for an already attached document. With no `boxes`, it targets every
non-root box; supplied ids are ordered. Start launches boxes in order and stop
reverses the order, stopping any attached `FieldRTTStreamer` before stopping its
box. It is not a document loader or stream configuration API.

## Native receiver and geometry

`native/field-visionos` is a separate Xcode project, not built by Gradle. Its
`RTT/` code parses the shared header, reassembles newest complete frames, decodes
HEVC/H.264 with VideoToolbox, converts NV12 for RealityKit, and presents mono or
SBS material surfaces. Its `FLine/` client subscribes to the existing three.js
geometry protocol and builds native mesh representations. Current verification
history and device-only gaps are maintained beside that code, not duplicated
here.

## Durable non-goals

- no native Monaco replacement;
- no on-device Field JVM;
- no protocol fork in a presentation-specific source tree;
- no claim that the unused `jsVision` seed is a compiled client;
- no promise of motion, latency, bitrate, browser, or device performance without
  a dated measurement artifact.

## Verification map

- JVM compilation: `./gradlew compileKotlinJvm compileKotlinJs` (the latter does
  not compile `src/jsVision`).
- Browser projection contracts: targeted UI regressions, including the shared
  history-panel scenario.
- Sender helpers: their local build/self-test instructions and the wire-format
  fixtures.
- Native shell: Xcode/simulator/device verification described in
  `native/field-visionos/README.md`.

Keep observed test history in the owning component's README or artifacts. This
architecture note should state current seams and decisions, not accumulate
machine-specific anecdotes or speculative platform research.
