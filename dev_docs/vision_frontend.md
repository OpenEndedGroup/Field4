# Field4 visionOS front end — design note

Status: **design agreed; fork deferred** (worktree `vision-frontend`). This note is the
durable record of the architecture. The `/vision` route + `src/jsVision/` exist as a
reserved, documented placeholder; the Kotlin/JS fork itself is deliberately deferred — see
"Front-end strategy" for why (KMP constraints) and the decision.

## The one-line thesis

Field4's browser front end and its GPU renderer are *already* two decoupled surfaces
talking JSON-RPC. That accidental decoupling is an almost-ideal visionOS architecture.
So we do **not** try to solve "HTML → texture map ← events" — we delete the problem by
letting each surface land on its native visionOS equivalent.

## Why there is no html→texture problem to solve

Field4 is a client/server web app:

- **Front end**: Kotlin/JS browser DOM (Electron Chromium or a plain browser). HTML is
  live DOM (`innerHTML`); boxes are CSS-positioned rectangles on a pannable canvas.
- **Backend**: Ktor JVM server (`afield.Server`) that *separately* owns a native LWJGL
  **Vulkan** window (`afield.four.Renderer`).
- **Bridge**: JSON-RPC over `wss://…/ws`. The document is server-authoritative — the
  browser is a subscribing *view*, not the source of truth.

The device-native mapping falls straight out of this:

| Field4 surface | visionOS home | Mechanism |
|---|---|---|
| DOM UI (Monaco, boxes, `display()` HTML) | WKWebView in RealityKit **attachments** | System-composited. OS does hit-testing + events. We never touch a texture. |
| GPU scene (`afield.four`) | Metal / CompositorServices, or RealityKit | Only surface where we legitimately own pixels. |

The general "put a live WKWebView texture in my scene and feed it events" problem has **no
supported zero-copy answer on visionOS by design** (web content is rendered
out-of-process, foveated, and system-composited; `takeSnapshot`/`drawViewHierarchyInRect`
is the slow per-frame path — the html2canvas-equivalent, avoid). The blessed path *is*
system-composited attachments, which hand us hit-testing and events for free. Field boxes
are flat rectangles, so that constraint is nearly free.

## The projection model (the unifying idea)

Because the document lives in the JVM and the browser is just a view, a **dock** and an
**island** are the same thing — a *scoped projection of the shared server document* — with
different renderers:

- **Dock** — a WKWebView showing an HTML projection (a box's Monaco, a box's `display()`
  output, a pinned canvas region). Always 2D. Its own lightweight web page subscribing to
  the same session with per-view scoping.
- **Island** — a RealityKit entity showing a *geometric* projection (see FLines below).
  Draggable, spatial.
- **Tether** — an elegant line connecting projections that share provenance.

> Workspace = `(docks ∪ islands) + tethers`, loosely bound by backend identity.

Consequences we committed to:

- A "dock/island" is a **document-level concept** (region/filter + camera), not native-only
  cruft. It degrades gracefully: a panel in space on visionOS, a tab/split-pane in the
  browser front end. Define it in the backend so both clients understand it.
- **Loose coupling, deliberately.** Islands are *not* spatially pinned behind their web
  panel — that path (transparent WKWebView + native-content-behind + 90 Hz rect tracking)
  was the one real hazard and we dropped it. An island is a separate draggable object; its
  only link to its source box is **identity** (it renders box X's channel), not a live
  spatial registration. This is better UX (grab/orbit/shelf), not a compromise.
- The **tether is nearly free and self-consistent**: both endpoints are entities RealityKit
  already tracks (the island; the WKWebView attachment), so the line reflows for free when
  either end is dragged. The JS→native bridge is **never on the position path** — it carries
  only content/identity events. The tether can itself be drawn by Field's FLine vocabulary,
  giving a free place to encode liveness/staleness/error in the connector.
- **Kill-and-revive dock lifecycle.** WKWebView is process-per-instance on a
  memory-constrained device; many Monaco instances hit a ceiling. Because state is
  server-authoritative, suspended/offscreen docks can be torn down and rehydrated from the
  backend with no data loss. Design docks to be cheap to kill and revive from day one.
- **Monaco stays on the web side forever.** We never reimplement the editor natively.

## The three doors to "depth"

1. **Geometry (FLines) → native `LowLevelMesh` islands.** FLines are vector geometry
   (lines/points/meshes with per-vertex attributes), not pixels — so bridging them to a
   RealityKit `LowLevelMesh`/`MeshResource` is small and needs no Vulkan port. This is now
   the *sole* mechanism for head-tracked spatial depth (see non-goals re: WebXR), so it's
   worth doing early. Analogous to today's three.js `<iframe>` output area, but native.

2. **Stereo pixels → the stereo dock (day-one banner feature).** Render two eye views in
   Field on the Mac, RTT them, stream to the device, present one per eye. No head tracking,
   no reprojection — parallax baked into the content, which is exactly stereoscopic film.
   Two tiers, neither needs the Vulkan→Metal port (the Mac keeps rendering; the device just
   routes eyes):
   - **Tier 1 — live/interactive (lowest dependency):** stream a side-by-side (or
     over-under) frame; a quad in a Volume with a RealityKit `ShaderGraphMaterial` using a
     **Camera Index Switch** node routes left/right halves to the two eyes. Almost no new
     native code. This is the first stereo target because live-coding must work *before*
     there's anything finished to encode.
   - **Tier 2 — finished film (deferred):** encode **MV-HEVC** (Apple "spatial video",
     via VideoToolbox on macOS) and play through `VideoMaterial`/AVPlayer — native stereo
     for free. Deferred until there's rendered-out work to encode; the only new dependency
     is the MV-HEVC encoder on the JVM backend (VideoToolbox bridge or external tool),
     confined to the export path.
   - Field-side requirement (both tiers): a **stereo camera rig** (two off-axis eye
     cameras) → two RTTs or one split target. Small addition given `RenderToTexture` exists.
   - Present in a dimmed **ImmersiveSpace** for the "cinema without the projector" feel.

3. **Mono pixels/compute (RectSL/StackSL as-is) → flat mono dock.** Stream the RTT output
   (not a literal desktop screenshare — reuse the per-box RTT/video pipeline we already
   trust) into a flat video surface. Stays flat until it earns a native GPU player.

## Transport & encode — streaming the RTT off the backend GPU

Doors 2 and 3 both say "stream the RTT to the device" and leave the *how* open. This is the
mechanism, and it's the one piece of the plan that must work **Linux backend → Apple client**
(where Mac screensharing can't reach) and stereo (which screensharing can't do at all).

### The framing that sets the latency budget

The stereo/flat docks are **world-locked by the visionOS compositor**. The quad is nailed in
space; head motion is resolved locally at 90 Hz. So **stream latency never becomes
motion-to-photon latency** — this is *not* ALVR/CloudXR territory where <20 ms is a
sickness threshold. Stale pixels just mean "the shader you're editing updates ~2 frames
later." **Glass-to-glass of 40–90 ms is fine.** Optimize for robustness and simplicity
first; chase the last 20 ms only if it measurably feels bad.

**The network is Tailscale, not a bare LAN.** The shell already addresses the Mac by MagicDNS
name (`mi2`) so the same build works from the simulator and a real headset with no IP
juggling (see `Field4Vision/ServerConfig.swift`). For the media UDP that means: (a) the
sender resolves `dest` by name (done — `getaddrinfo`, dual-stack); (b) we want Tailscale's
**direct** WireGuard path (peer-to-peer, ≈LAN latency), *not* a DERP relay fallback — on the
same great WiFi it will be direct, but it's the one thing to confirm with `tailscale ping`
before blaming the encoder for latency.

### The pipeline

`capture (zero-copy) → HW encode → transport → HW decode → present`. Only the first two
stages fork on backend OS.

**Capture — render into a surface an encoder can take, not the swapchain:**

| Backend | Zero-copy capture | Encode |
|---|---|---|
| **macOS (MoltenVK)** | Allocate the eye RTT as an **IOSurface-backed `MTLTexture`** and render into it; MoltenVK backs `VkImage` with `MTLTexture` (`VK_EXT_metal_objects` exports it), and an IOSurface wraps trivially into a `CVPixelBuffer`. | **VideoToolbox** HEVC |
| **Linux / NVIDIA** | Export `VkImage` via `VK_KHR_external_memory_fd` → import as a CUDA external-memory image → register as an NVENC input surface (Sunshine/ALVR's path). `VK_KHR_video_encode_queue` can skip the CUDA hop but is less battle-tested. | **NVENC** HEVC |

HEVC over H.264 for the bitrate win at these resolutions; H.264 only as a negotiation
fallback. AV1's encode-latency/decoder-maturity story isn't worth it yet.

### Transport — one path: a thin custom UDP stream (decided 2026-07-12)

**Decision: a single transport for every streamed pixel — a thin custom UDP/RTP path with
our own HW decode on the device. No WebRTC anywhere.** On a controlled WiFi-6 LAN, WebRTC's
congestion-control/NACK/FEC machinery buys little, and its integration cost is real (notably
extracting GPU-resident `CVPixelBuffer`s from libwebrtc, and WebRTC-in-WebView can only ever
present flat). One path also collapses the flat/stereo distinction to a one-line material
difference.

**Consequence — flat and stereo are the same pipeline.** Both flat-mono and stereo pixel
streams are **native RealityKit surfaces**, never WebView `<video>`. Identical
capture→encode→transport→decode; they differ *only* in the material:

- **flat mono** → plain unlit material sampling the whole frame;
- **stereo** → `ShaderGraphMaterial` with a Camera Index Switch sampling the L/R halves.

(WKWebView docks stay for **DOM** content — Monaco, HTML `display()` — which was never a
pixel stream and needs no video transport at all. The WebRTC-in-WebView idea was only ever a
shortcut for flat *pixels*, and it's gone.)

Full path, one line: `capture → VideoToolbox/NVENC HEVC → RTP-ish over UDP → VideoToolbox
decode (always — it's an Apple device) → CVPixelBuffer → LowLevelTexture → material`
(`LowLevelTexture` is the current RealityKit primitive; `TextureResource.DrawableQueue` the
older one). Only the capture+encode end forks on backend OS (table above); device decode is
VideoToolbox regardless.

**What "thin" must still handle: loss recovery without NACK.** The one thing a bare UDP path
can't hand-wave is a dropped packet. Minimum viable framing: MTU-sized fragments of each NAL
unit, tagged with a frame sequence number + fragment index; and instead of retransmit,
**periodic IDR or encoder intra-refresh** so a lost packet costs at most a fraction of a
second of that eye, not a permanent smear. On a great LAN loss is rare, so this is cheap
insurance, not a jitter-buffer project.

### The SBS single-stream trick (the mechanism behind Tier 1)

Send **one** double-wide (or over-under) frame per eye pair — not two streams. One encode,
one decode, one texture; the Camera Index Switch samples the left half for eye 0 and the
right half for eye 1. This is why Tier 1 is "almost no new native code," and being
codec-agnostic is exactly what lets it cross Linux → Apple. Two gotchas that will bite:

- **Color range.** "Washed out / crushed" is a full-range vs video-range (BT.709 limited)
  mismatch between encoder and sampler. Pin it end to end; test with a known ramp.
- **Chroma seam.** With 4:2:0, the L/R (or over/under) split shares chroma samples across the
  boundary → color bleed at the seam. A few guard pixels, or 4:2:2 for the stereo stream,
  avoids it.

### MV-HEVC is effectively mac-only (reinforces "Tier 2 deferred")

VideoToolbox is the only sane MV-HEVC encoder; there is no good Linux one. So a **Linux
backend stays on SBS single-stream (Tier 1) for both live and export** — MV-HEVC isn't
"deferred" there, it's "not that path." Native spatial-video playback from a Linux backend
would mean transcoding SBS → MV-HEVC on a Mac in the export pipeline. (Don't spike
MV-HEVC-on-Linux.)

### Prior art to crib from, by relevance

- **ALVR** — closest analog (PC Vulkan/DX → HW encode → WiFi → per-eye surface); active
  visionOS-client effort. Read its encoder + pacing before writing our own.
- **Sunshine (host) / Moonlight (client)** — cleanest reference for NVENC-on-Linux and
  VideoToolbox-decode-on-Apple respectively.
- **NVIDIA CloudXR** — proprietary/overkill, but the canonical "stereo remote render to XR"
  architecture to sanity-check against.

### Backends: Mac first, Linux second (decode side identical)

The transport, packetization, and the entire device-side receiver are **backend-agnostic** —
only capture+encode differ. So build the **Mac backend (VideoToolbox encode) first**: it's
the zero-copy IOSurface path, it's the machine Field already runs on, and it exercises the
whole spine. The **Linux/NVENC backend is a second encoder implementation behind the same
packet format** — no receiver changes. Getting the wire format right on Mac is what makes the
Linux port a drop-in.

### First spike

A **mono** RTT of one eye → VideoToolbox HEVC → thin UDP → VideoToolbox decode →
`LowLevelTexture` → plain material on a flat quad (no Camera Index Switch yet). Proves the
whole capture→encode→transport→present spine and yields a real glass-to-glass number on the
actual WiFi. *Then* widen the RTT to SBS and add the Camera Index Switch — stereo is a shader
change, not a pipeline change.

## Front-end strategy: fork at `commonMain`, never below it

We keep an experimental visionOS front end **out of the main front end** to avoid polluting
it with spatial-specific HTML/JS. It's a fork of the *compiled Kotlin/JS app* (not just a
static resource tree — Field has no extension registry; `extensions/remote_ar/` is served
only incidentally by a catch-all static root).

**The guardrail that makes the promised merge/de-dup real:**

> `commonMain` is frozen-as-contract. The vision front end may fork only `jsMain`
> (presentation). If spatial work needs a protocol change, make it in `commonMain` so both
> front ends get it — never fork the protocol into the vision tree.

This is already achievable because the split is already clean:
- `commonMain/kotlin/` = **the contract**: `Messages.kt`, `rpc.kt`. Shared by jvm + all js.
- `jsMain/kotlin/` = **presentation** (20 files: Main.kt, outputareas, gestures, the Monaco
  bootstrap, its own `Message.kt` handlers). This is what the vision front end forks.

The classic way "we'll merge later" becomes a lie is divergence in the *foundation*. Pin the
foundation shared; fork only the surface. Then de-dup later is reconciling two presentation
trees over one protocol, which is tractable.

### KMP reality check — the clean fork isn't free (and is deferred)

Attempting the "second Kotlin/JS bundle that shares only `commonMain`" surfaced three KMP
constraints that make the *enforced* guardrail above harder than it first looked:

1. **No second `js()` target** — KMP forbids two targets of the same platform
   ("Declaring multiple Kotlin Targets of the same type is not supported").
2. **A custom compilation's source set can't `dependsOn(commonMain)`** — "different Source
   Set Trees." The only supported reuse is `associateWith(main)`, which pulls in *all* of
   `jsMain` — i.e. "extend jsMain," a **soft** boundary, not a clean fork. (Also: a custom
   compilation didn't get a browser-webpack task by default — it produced node-style compile
   tasks — so a browser bundle from it needs a spike to confirm.)
3. **The contract can't simply move to a shared `:contract` module** while `Messages.kt`
   holds `expect fun _send`: its `actual` is frontend-specific (the JS websocket client), and
   expect/actual can't cross module boundaries. A module split first requires refactoring
   `_send` off expect/actual into an injected transport — a change to the messaging core that
   the *main* front end also depends on.

**Decision (2026-07-12): defer the fork.** The native shell targets `/welcome` +
`/index.html` first, so a second bundle isn't needed yet. We claim the `/vision` prefix as a
documented placeholder now and decide the mechanism at the first spatial-specific divergence,
with requirements in hand. The two live options at that point:

- **Associate compilation** (in-module, fast, *soft* boundary): custom `vision` compilation
  `associateWith(jsMain)`. Real second bundle, trivial merge-back, but compiles on top of
  `jsMain` so the guardrail becomes a discipline, not a wall. Spike browser-webpack first.
- **Contract module** (clean, more upfront): refactor `_send` → injected transport, hoist
  `Messages.kt` + `rpc.kt` into a shared `:contract` module, add a `:frontend-vision` js
  module. Hard fork, crisp guardrail — this is the "better architecture" the merge/de-dup
  pass was always going to include.

Until then the guardrail is a **discipline** ("don't diverge the protocol"), not something a
module boundary enforces.

## The server seam (`/vision`) — placeholder

The precedent for an alternate front end at its own prefix is the hand-wired
`route("/welcome")` / `route("/code-use")` handlers in `Server.kt`. We mirror that, but as a
placeholder pending the deferred fork:

- `route("/vision")` (in `Server.kt`) — explicit handler serving
  `src/jsVision/resources/index.html`. Explicit (not the incidental static root) so we
  **dodge `IndexHtmlInterceptor`**'s `filter`/`injectInitialFramePreview` transforms, which we
  don't want on a bespoke page. Today it serves a static "reserved fork point" page.
- `src/jsVision/kotlin/afield/Main.kt` — a **deferred seed** (not wired into any compilation):
  a worked example of the two things the vision bundle must do (construct the shared `Message`,
  provide `actual fun _send`). Grows into the `jsMain` fork once the mechanism is chosen.
- No `afield-vision` bundle or static roots exist yet — added when the fork is un-deferred.

## Sequencing

1. **Native shell against existing endpoints first.** Build the SwiftUI/RealityKit shell —
   dockable/undockable tab manager, a dedicated Monaco dock, a welcome-page dock — with its
   WKWebViews pointed at the *existing* `/welcome` and `/index.html?file=…` on the Mac's
   Field server. Proves docking + the unknowable ("does Monaco/the whole app even run
   acceptably in a WKWebView on visionOS") with **zero Field4 changes**.
2. **Stand up `/vision` at the first forced divergence.** The spatial work (boxes in 3D,
   gestures, command-palette placement) triggers it soon, but it's step two, not tangled
   into proving the shell.
3. **First native island: FLine → `LowLevelMesh`.** Highest payoff-per-effort; sole path to
   head-tracked depth.
4. **Stereo dock (Tier 1).** Day-one banner feature for stereoscopic film work.

## Non-goals (explicit)

- **No pinned spatial registration** of native content behind transparent WKWebViews.
  Islands are loosely-coupled draggable objects joined by identity + a tether.
- **No immersive WebXR.** visionOS does not grant `immersive-ar` WebXR sessions; the web
  layer is flat by definition. All depth crosses into the native layer explicitly.
- **No on-device JVM.** The backend stays on a Mac/remote; the native client is just a third
  RPC client alongside the browser and the Python backdoor.
- **MV-HEVC deferred** until there's finished rendered work to encode. Live stereo (Tier 1)
  comes first.
- **We do not reimplement Monaco natively.**

## Division of labor

- **Swift/visionOS app** (tab manager, docks, RealityKit islands, stereo quad): a separate
  Xcode project, driven outside this repo (visionOS can't be built/run from the Field4
  toolchain).
- **Field4 side** (this repo): the `/vision` route, the `afield-vision.mjs` binary sharing
  `commonMain`, the skeleton vision front end, the encode co-process, and later FLine mesh
  export.
- **Stereo rendering: already ours, out of scope for the streaming work.** Field already
  renders SBS for its OpenXR path; the stereo camera rig is not new work. The streaming seam
  starts *after* the RTT is filled — see below.
- **Encode co-process** (per-OS, this repo, native): the `field-encode` helper — Swift +
  VideoToolbox on Mac, C++/CUDA + NVENC on Linux — behind one socket protocol + one UDP wire
  format. See the sender seam below.

## The sender seam — JVM (Vulkan) → encoder → UDP

The receiver (visionOS) is settled: VideoToolbox decode → `LowLevelTexture` → material. The
half that lives in *this* repo is the **sender**, and its one real design question is how the
JVM (which owns the LWJGL/MoltenVK Vulkan context) reaches a hardware encoder (VideoToolbox
is a native macOS framework; NVENC is native too). Two shapes:

- **In-process JNI/JNA** — JVM links a small native lib and calls the encoder directly.
  Lowest latency, but welds OS-specific native encode into the JVM process and re-does the
  Metal/Vulkan object-sharing dance inside the JVM's address space.
- **Co-process helper (chosen).** A tiny native binary owns the encoder + UDP socket; the JVM
  hands it a GPU surface handle over a local socket. This matches how Field4 already factors
  native/foreign capability (the Python backdoor, the MCP bridge): a thin, replaceable,
  restartable bridge — not native code fused into the JVM. It also *is* the Linux fork story:
  same JVM side, same wire format, swap the helper binary.

**Decision: co-process `field-encode`, one socket protocol, one UDP wire format, two helper
binaries (Mac/VideoToolbox first, Linux/NVENC second).**

Zero-copy chain on Mac (the part that must not accidentally introduce a blit):

1. JVM **allocates the `IOSurface`** for each eye-pair RTT (a ring of 2–3 for
   render-ahead), creates an `MTLTexture` from it, and **imports that texture into Vulkan as
   the RTT's backing** via `VK_EXT_metal_objects` (`VkImportMetalTextureInfoEXT`). The stereo
   existing SBS render (the OpenXR rig) targets it — no copy. **This import + the ring are the
   seam's responsibility; filling the RTT is the caller's.**
2. `IOSurface` is **cross-process by design**: pass its ID to the helper once at handshake
   (`IOSurfaceCreateMachPort` / `IOSurfaceLookupFromMachPort`), not per frame.
3. Helper wraps the `IOSurface` as a `CVPixelBuffer`, feeds `VTCompressionSession` (HEVC),
   packetizes NAL units (§ transport), sends UDP.

The socket protocol carries only **control + sync**, never pixels: the surface handle(s) at
handshake; per-frame "surface index *i* ready" + sequence number; encoder controls (target
bitrate, **force-IDR**). Correctness gate: the encoder must not read a half-written frame —
minimally, the JVM waits the render fence before signalling *i* ready (costs a frame of
latency); better, export a Vulkan↔Metal shared timeline semaphore (`VK_EXT_metal_objects` can
bridge `MTLSharedEvent` ↔ `VkSemaphore`) so the encoder GPU-waits without a CPU stall — note
as the optimization, ship the fence first.

Linux fork slots in cleanly: replace step 1's `IOSurface`/`VK_EXT_metal_objects` with
`VK_KHR_external_memory_fd` → CUDA external memory → NVENC input, and pass a dma-buf/fd handle
instead of an `IOSurfaceID`. Steps' *shape* — allocate-and-import, handshake the handle, signal
per-frame readiness, emit the same UDP wire format — is identical, which is what keeps the
receiver untouched.

### Implementation status

The Mac sender exists and boots: **`native/field-encode/`** (SwiftPM) driven by
**`afield.four.stream.FieldEncodeProcess`** (JVM `ProcessBuilder`, stdin JSON commands
`open`/`frame`/`bitrate`/`selftest`/`close`, JSON events back), streaming fragmented Annex-B
HEVC/H.264 over UDP. The **wire + control contract** the Linux sender and the visionOS
receiver both implement is `native/field-encode/WIRE_FORMAT.md`.

*Verified:* builds (Swift 6.3); the full encode→packetize→UDP path via `--selftest` (a
synthetic IOSurface pattern) — a loopback receiver confirmed correct fragmentation, every
frame delivered, periodic-IDR keyframes, on-target bitrate; and a JVM `ProcessBuilder`
launching + driving the helper end-to-end over the stdio pipe. `field-encode --selftest
--dest <host:port>` is the "packets into the void" boot. *Not yet exercised:* encode from
Field's *real* RTT IOSurface (vs. the self-test surface), and UDP reassembly/decode on
device. Seam: **producer hands over a filled IOSurface(-ID) + a per-frame ready tick; the
helper owns encode + wire.** IOSurface-backing the RTT (MoltenVK + `VK_EXT_metal_objects`)
is the producer's side, as is stereo rendering (Field already does SBS for OpenXR).

**The Linux/NVENC backend now exists too, and is verified end-to-end against the real visionOS
receiver**: `native/field-encode-linux/` (CMake/C++, dlopen'd against
`libcuda.so.1`/`libnvidia-encode.so.1` via vendored `ffnvcodec` headers — no CUDA toolkit or Video
Codec SDK install needed). Same control protocol and UDP wire format as Mac (see
`native/field-encode/WIRE_FORMAT.md`'s "Linux sender addendum"), with one structural difference
forced by the platform: surface ownership is reversed. A helper process can't allocate a *Vulkan*
image, so there's no Linux `alloc` — the JVM allocates the RTT ring itself
(`VK_KHR_external_memory_fd`, opaque-fd handle type, `VK_IMAGE_TILING_LINEAR`, and a *dedicated*
allocation via `VkMemoryDedicatedAllocateInfo` — required, not optional, see below) and hands the
real fds to the helper via **SCM_RIGHTS over a Unix domain socket** (a small JNI shim,
`native/field-fdpass/`), then imports them into CUDA (`cuImportExternalMemory` →
`cuExternalMemoryGetMappedBuffer` → registered with NVENC as
`NV_ENC_INPUT_RESOURCE_TYPE_CUDADEVICEPTR`). JVM side: `afield.four.texture.VulkanExternalMemory`
+ `Image.exportOpaqueFd()` (export) and `FieldRTTStreamer.startLinux()` (ring allocation +
handoff) — `FieldRTTStreamer`'s public API (`start()`/`surfaces`/`stop()`) is unchanged and now
branches on OS internally, so the same box code that drives the Mac backend runs unmodified on
Linux.

Two real bugs surfaced and got fixed getting the fd handoff working, both worth knowing about if
touching this code: (1) a first design duplicated the fd out of the producer's
`/proc/<pid>/fd/<fd>` (no native glue needed, works for anonymous-inode files like pipes) — but
NVIDIA's opaque-fd export turned out to be backed by `/dev/nvidiactl` (a real device file), and
reopening that path performs a *fresh* driver `open()`, numerically valid but semantically
unrelated to the original memory, so `cuImportExternalMemory` failed with `CUDA_ERROR_UNKNOWN`
(999) — replaced with real SCM_RIGHTS fd passing. (2) The Vulkan memory allocation must be
declared *dedicated* to match a flag CUDA's import requires, or the same error occurs.

*Verified end-to-end against the real device*: a real Vulkan-rendered RTT → export → SCM_RIGHTS →
CUDA import → NVENC encode → UDP → the actual Field4Vision app, sustained over the real network,
receiver-reported "0 dropped, 0 malformed" across 3300+ datagrams. Also verified in isolation:
builds against an RTX 4090 (driver 610.43.02); the full encode→packetize→UDP path via `--selftest`
for both HEVC and H.264 (zero dropped/out-of-order fragments, correct IDR cadence, on-target
bitrate, clean `ffprobe`/`ffmpeg -f null` decode).

**The "grey/white flicker" seen through most of live testing was a duplicate-`frameSeq` bug in ad
hoc test scaffolding, not the encoder, wire format, or receiver** — found via a byte-level
bitstream diff against the known-good Mac encoder (done receiver-side). `FieldRTTStreamer`'s
automatic per-frame trigger (`Renderer.afterCurrentGraphicsFrameComplete`) works correctly on its
own (confirmed by inspecting `Renderer`'s completion-callback queue directly — small and steadily
draining, never backlogged). An early test wrongly concluded it "never fires" and added a manual
polling thread as a workaround; that thread then ran *concurrently* with the real mechanism for
the rest of the session, both independently triggering `encoder.frame()` for overlapping frame
numbers against different ring-buffer content each time — producing two different encoded frames
sharing one `frameSeq` on the wire. The receiver correctly drops the second as a stale duplicate
per WIRE_FORMAT.md's rules, which breaks the P-frame reference chain (only self-contained IDRs
survive) — exactly the observed "occasional correct color, grey otherwise." **Never add a
manual/parallel frame-trigger loop alongside `FieldRTTStreamer.start()`** — its own registration
in `renderer.renderList` is sufficient. Real (and worth keeping) NVENC config fixes landed
alongside this misdiagnosis — see `native/field-encode-linux/README.md`'s Status section for the
profile/tier/level/reference-count details — but they were not the actual cause of the symptom.

*Open item, likely receiver-side, not yet re-checked against the fixed sender*: a ~70 second delay
was observed once between first packet arrival and first successful decode despite zero packet
loss reported the whole time — may or may not be related to the duplicate-`frameSeq` bug above.
