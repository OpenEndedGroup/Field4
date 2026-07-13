# Field4Vision — visionOS shell (handoff)

A SwiftUI + WKWebView shell that hosts the existing Field4 web front end as spatial "docks"
on visionOS. It is a **thin third RPC client** alongside the browser and the Python backdoor —
it renders the *existing* Field server endpoints (`/welcome`, `/index.html?file=…`) in
`WKWebView`s that float in space. No Field4 changes are required to *run* it; the display-mode
features below are additive server-side niceties.

> This project is a **separate Xcode project, deliberately outside the field4 repo** (visionOS
> can't be built from the Field4 toolchain). Xcode 26.5 / visionOS 26.x SDK.

## Companion repo (the field4 backend + sender)

The Field backend, its Vulkan renderer, and the **RTT stream sender** live in the field4 repo at
**`~/field4`** on this machine (note: *not* `~/temp/field4`, which is an older copy without the
sender). Key cross-references there:

- `dev_docs/vision_frontend.md` — full architecture + rationale (the "sender seam" section).
- `native/field-encode/WIRE_FORMAT.md` — the UDP media + control contract this shell's receiver
  implements against (authoritative).
- `native/field-encode/` — the macOS VideoToolbox **sender** co-process (`field-encode`).
- `src/jvmMain/kotlin/afield/four/stream/FieldRTTStreamer.kt` — the **producer**: allocates an
  IOSurface ring in the helper, imports it as an RTT backing, renders into it, and ticks the
  encoder. `FieldEncodeProcess.kt` is the JSON stdin/stdout driver.

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
  - **ImmersiveStreamView.swift** — the present layer: a world-locked quad in a dimmed
    (`preferredSurroundingsEffect(.systemDark)`) `ImmersiveSpace`, sampling `displayTexture`. Mono/flat
    today; stereo (SBS) via a Camera Index Switch `ShaderGraphMaterial` is the marked next step.

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
- **RTT streaming — full spine, end-to-end.** The "Immersive" ornament button starts the receiver
  and opens a dimmed theatre showing the decoded stream on a world-locked screen. **Verified with
  the real Field sender** (`FieldRTTStreamer` in `~/field4`, see Companion repo): Field renders into
  an imported IOSurface-backed RTT → VideoToolbox HEVC → UDP → this receiver reassembles →
  VideoToolbox decode → `LowLevelTexture` → material, **with correct colour**. Also verified against
  `field-encode --selftest` (HEVC + H.264, correct header parse, inline VPS/SPS/PPS, `420v` NV12).
  - **Sender restarts are handled** — relaunch Field (its `seq` resets to 0) without reinstalling
    this app; the reassembler detects the restart (keyframe near seq 0 after a high-water mark) and
    re-locks on the new IDR. (Before the fix, a restarted stream was dropped as stragglers forever.)
  - **Rate** — the sender emits at its configured `fps`, not the render rate; keep it low (~15) for
    the *simulator*'s software decoder (a 60/90fps flood there causes reference-loss flicker). On
    device, hardware decode is real-time.
  - **Present smoothness is device work** — the visionOS *simulator* paints the immersive scene at
    ~1fps and is non-interactive, so the decode fps (status label) far exceeds what you see there.

## field4-repo dependencies (server must serve these)

These live in the **field4** working tree and are **currently UNCOMMITTED** there — they were
edited alongside pre-existing WIP in the same files, so they were intentionally *not* committed
from this shell repo. To run the shell with all features you need a Field server built from a
tree containing:

- `rpc.kt` — `Hello.shaderSelectionContext`.
- `Server.kt` — `selectionContextSessions` registry + `controller-selection` rebroadcast to
  context peers; join on `hello`, leave in the `/ws` finally; `injectInitialFramePreview` skips
  `editorOnly`; HTTPS connector binds `0.0.0.0`.
- `Main.kt` — parse `editorOnly` / `noeditor` / `transparent` / `shaderSelectionContext`;
  `fieldDisplayMode` + body classes; inbound `controller-selection` handler → `applyRemoteSelection`
  (echo-guarded); `shaderSelectionContext` passed in `Hello`.
- `BootstrapMonacoRuntime.kt` — the Monaco selection monitor gated on display mode
  (no-editor never shows / steals focus; editor-only stays visible with no selection).
- `field.css` — `mode-editor-only` / `mode-no-editor` / `mode-transparent`; black box border +
  `11px` `::before` radius; `--shell-corner-inset` toast tuck; glassy empty editor-only.

### URL params understood by the front end

- `?editorOnly` — Monaco fills the window, canvas hidden.
- `?noeditor` — canvas only, editor/docs/history hidden.
- `?transparent` — glass field backdrop, opaque boxes/outputs (Monaco keeps its solid theme).
- `?shaderSelectionContext=NAME` — join a shared-selection channel (bidirectional).

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
  focus, so ⌘P / ⌘⇧P / double-tap-shift render their palette in whichever window has focus. The
  **history panel is canvas-scoped** → broadcast a "show-history" **intent** over the shared context
  (generalize `shaderSelectionContext` into a shared-UI-context carrying selection *and* intents).
  Double-tap-⌘ to evict editor focus is native + minor → deferred.
- **RTT streaming — spine done, verified end-to-end** (receiver in `RTT/` here; sender
  `FieldRTTStreamer` + `field-encode` in `~/field4`). Field renders into an imported IOSurface RTT →
  HEVC → UDP → this receiver decodes and presents, correct colour, and survives sender restarts.
  Hardening sweep **done** (all committed + verified): **ring>1** render-ahead
  (`FieldRTTStreamer(ringSize=N)`, streamer draws the active slot round-robin — verified streaming at
  ringSize=3), **backpressure** (dedicated decode queue + drop-to-keyframe), **diagnostic gating**
  (`FIELD_ENCODE_VERBOSE`), and the **`Image.kt` import/memory** question (answered: `memReqs.size` is
  non-zero, so MoltenVK wants the bound memory — keep the allocate+bind). Remaining:
  - **Stereo dock** — swap the mono `UnlitMaterial` in `ImmersiveStreamView` for a
    `ShaderGraphMaterial` with a **Camera Index Switch** node sampling the SBS left/right halves per
    eye (authored in Reality Composer Pro; the converter already produces the full SBS frame, so only
    the material changes). Refs: `~/temp/openimmersive`, Apple's SBS→MV-HEVC sample.
  - **Device verification of present** — the sim paints the immersive scene at ~1fps and is
    non-interactive; confirm real smoothness + interaction on hardware.
  - **`/vision/stream` route** — start/stop a stream for the active document from a server route in
    `~/field4` `Server.kt` (alongside the `/vision` placeholder), instead of a Field box.
  - **Linux / NVENC sender** — a second sender binary (in `~/field4`, alongside `native/field-encode`)
    behind the **same** `WIRE_FORMAT.md` contract, so **this receiver needs zero changes** — it never
    learns which backend produced the stream. Capture forks on OS: instead of the Mac's IOSurface +
    `VK_EXT_metal_objects`, export the `VkImage` via `VK_KHR_external_memory_fd` → import as CUDA
    external memory → register as an NVENC input surface (Sunshine/ALVR's path), and hand a
    dma-buf/fd instead of an `IOSurfaceID`. Same control + UDP wire format out. Stays on SBS
    single-stream (no MV-HEVC on Linux). See `~/field4/dev_docs/vision_frontend.md` → "Backends" and
    "The sender seam".

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
*The RTT sender is now committed in `~/field4` (commits: "Add field-encode co-process helper" and
"Wire Field's RTT into the field-encode streamer (import path)"). The receiver + its restart fix
and sim-testing affordances are committed here. Present smoothness/interaction remains device work.*
