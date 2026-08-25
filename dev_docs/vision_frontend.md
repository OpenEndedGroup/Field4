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

## Pose uplink

`__visionpose__` is the one channel that runs device to host. The RTT stream
carries pixels down and `__visionmetal__` carries a program down; neither tells
this process where the wearer is looking, so a streamed image can only be a
world-locked surface. Head-locked rendering needs the pose to arrive first.

The wire is JSON on the existing `/ws` bus: `visionpose/hello`,
`visionpose/frame` per compositor frame, `visionpose/bye`. A frame carries the
head transform, per-eye transform and per-eye projection as 16-float
column-major arrays, plus the head's predicted display time and send time in its
own clock. `afield/graphics/VisionPose.kt` is authoritative;
`Field4Vision/Pose/PoseWire.swift` is its hand-written mirror.

The sender is `PoseUplink`, fed from the Compositor Services renderer at the one
point that already holds both the drawable and the device anchor, so the pose
that travels is the pose that drawable was rendered from. It is latest-wins and
never queues: a pose is only useful while it is newest, and a queue here would
convert network jitter into accumulating pose delay.

`VisionPoseStream` on the host keeps the newest frame, composes each eye's
world-to-eye matrix the same way the head does, and extracts the frustum
tangents from the compositor's projection so the Vulkan renderer can build one in
its own depth convention. It also counts arrivals, rejections, late arrivals and a
smoothed arrival interval, because the remaining work is a latency measurement.

Publication is monotonic under a lock, and that is load-bearing rather than
defensive: Field's `/ws` reader dispatches each raw message in its own coroutine,
so poses decode concurrently and finish out of order. Measured against the
simulator at **9-10%**, reproduced across runs (123/1382, 181/1962, 230/2400). Without the
guard, `latest` would walk backwards several times a second — which presents as
the world swimming, not as lag. A large jump backwards is still accepted, since
`frameIndex` restarts at zero when the renderer does.

Not addressed here: the timestamps are carried faithfully but unshifted, so
nothing yet predicts forward by the render-encode-network-decode interval; and
the transport is TCP, chosen for the bus's existing reconnect and certificate
handling, to be revisited against a measurement rather than an assumption.

## Rendering from an uplinked pose

`StereoPoseSource` is the third pose source on `SideBySideStereoTarget`, beside
an attached `OpenXRHmdTarget` and the internal `stereoCamera`; it wins over both
when set. `VisionPoseStereoSource` implements it over `VisionPoseStream`.

A remote pose differs from the other two in one way that dictates the interface:
it arrives asynchronously, so it is **latched** once per frame, before both eye
passes. Reading it per eye would let the head move between them, which presents
as the two eyes disagreeing rather than as latency, and is near-impossible to
recognise from inside a headset. The target records `renderedPoseFrameId` and
`renderedPoseDisplayTime` for whatever it latched.

The projection is rebuilt, not forwarded. The head's matrix carries its
compositor's near/far and depth range; Field wants `Options.zNear`/`zFar` over a
0..1 range. So the frustum tangents — the convention-free part — feed the same
`frustum(..., zZeroToOne = true)` construction the OpenXR path uses. The view
matrix is forwarded untouched, because the wire already carries world-to-eye.

Losing tracking holds the last good pose rather than latching an identity one: a
frozen view reads as lost tracking, where a snap to the origin reads as a broken
scene. A one-view head (the simulator) draws both eyes from view 0.

## Controllers

Tracked accessories ride the same channel as the pose, in the same message, so
input and the image it acts on describe one instant. `StereoPoseSource.controller`
is defaulted off, and reports into `OpenXRHmdTarget.ControllerState` — the type the
OpenXR path already fills — so Field's interaction code cannot tell which backend
it is on. `SideBySideStereoTarget.controllerState` uses the same precedence as the
eye matrices: latched pose source, then OpenXR, then the mouse mock.

On device, two frameworks joined at `Accessory(device:)`: ARKit's
`AccessoryTrackingProvider` gives pose, held chirality and tracking state;
GameController gives buttons and axes for the same object. No correlating of
parallel sources. Poses go through `predictAnchor(for:at:)` to the pose's own
predicted display time. Input names are looked up rather than assumed, so an
absent one degrades to "not pressed".

Two rules that are load-bearing rather than fussy:

- `tracked` requires position *and* orientation. `AccessoryAnchor.isTracked` is
  true for an orientation-only anchor, and a ray cast from a position we do not
  have looks plausible and is wrong.
- Unspecified chirality is never guessed into a hand. Hardware can report inherent
  handedness without knowing who holds it; driving the wrong hand is worse than
  driving neither.

Adding this needed no `VISION_POSE_VERSION` bump: on a JSON channel with
`ignoreUnknownKeys`, an optional field is compatible in both directions. Bump only
for a change that would make an old message *misread*.

`MultiviewSideBySideStereoTarget` deliberately has no pose source: remote-head
rendering is the macOS path, which is where MoltenVK multiview is the awkward one.

Hands stay a separate concept and are not implemented. Controllers are device-only
to verify: `AccessoryTrackingProvider.isSupported` is false on the simulator.

## Carrying the pose identity to the head: media wire v2

For the head to reproject a received frame it must know which pose the frame was
drawn from. That needed a new header field, because the two apparent shortcuts are
both wrong: `ptsMicros` is deliberately stamped on the ideal grid (`seq *
interval`) rather than a clock, since a real timestamp bakes render-tick
quantisation into the stream and a receiver scheduling against it reproduces the
irregularity; and `seq` is also the ring-slot selector and the pts basis, so it
cannot carry someone else's numbering.

So the media header grew a 16-byte pose tail — `poseFrameId` plus
`poseDisplayTimeNanos` — gated by a `POSE_VALID` flag rather than a sentinel,
because compositor frame index 0 is legitimate. Full spec in
`native/field-encode/WIRE_FORMAT.md`.

`poseDisplayTimeNanos` is the head's own clock, because the head is the only
consumer: it hands the value back to `queryDeviceAnchor(atTimestamp:)`.

**Versions 1 and 2 are both permanent.** The macOS sender emits v2; the
Linux/NVENC sender emits v1 because its host tracks locally through OpenXR and has
no uplinked pose to report, so there is nothing to put in the tail. The receiver
parses both, keyed on the version byte. `field-encode-linux/src/Wire.hpp` carries
a note against "catching it up".

Producer side: `FieldEncodeProcess.frame(...)` takes both pose fields or neither,
and `FieldRTTStreamer.tagPosesFrom(target)` wires a stereo target's latched pose
in. The pose is read on the tick that rendered the surface, not in the deferred
submit block, where a newer pose would silently substitute itself.

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
- Rendering from an uplinked pose: `visionpose.stereo-source` (jvm-main). Checks
  the rebuilt frustum *geometrically* — a point on each frustum edge must land on
  the matching clip boundary, and the near plane at NDC z 0. Matching matrix
  entries against a literal would pass with the depth range inverted, which
  renders a plausible image with the wrong things in front.
- Media wire bytes: `tools/rtt_wire_check.py`. Drives the real helper and reads
  real datagrams, so it covers the control-channel decode and the threading of the
  pose through the async encoder, not just the header builder. Two phases because
  blank frames arrive as one fragment — the self-test's animated pattern is what
  exercises "the tail rides every fragment".
- Receiver parse: `RTTWireSelfCheck`, run at app launch and reported over
  `__visionpose__`. There is no XCTest target in the visionOS project, so without
  it the parser has no execution coverage at all. It must report over the socket:
  `os.Logger` goes to the unified log and `simctl launch --console-pty` captures
  nothing from this app.
- Pose uplink, end to end: `tools/vision_pose_simulator_smoke.sh`. Boots the
  simulator, opens the immersive space unattended and asserts that a real
  compositor pose reaches a Kotlin box. The simulator reports one view, so `eyes`
  is 1 there and 2 on hardware; real head motion and true per-eye separation stay
  device-only.
- Pose uplink arithmetic: `visionpose.uplink` (jvm-main). Pins the wire decode,
  the world-to-eye composition and the tangent extraction against hand-computed
  values, which is the half a headset cannot check: a transposed matrix or a
  swapped eye reads as "the world is wrong somehow" and looks correct in a
  screenshot.
- Browser projection contracts: targeted UI regressions, including the shared
  history-panel scenario.
- Sender helpers: their local build/self-test instructions and the wire-format
  fixtures.
- Native shell: Xcode/simulator/device verification described in
  `native/field-visionos/README.md`.

Keep observed test history in the owning component's README or artifacts. This
architecture note should state current seams and decisions, not accumulate
machine-specific anecdotes or speculative platform research.
