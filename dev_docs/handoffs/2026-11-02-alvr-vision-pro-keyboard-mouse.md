# Mac handoff: ALVR Vision Pro keyboard and mouse for Field

Prepared 2 November 2026. Start with the **on-device input-capture experiment** below.
The desired result is the same usable IDE we have on Quest: Field's live browser panel in VR,
with a keyboard and mouse attached to the headset, no person operating a Linux desktop window,
and another browser free to edit the same document with independent focus.

## What is already working, and what is not

Quest 3 + WiVRn + Linux/NVIDIA has been tested in the headset: the high-resolution browser panel,
controller clicks, analog two-axis scrolling, one-grip positioning, two-grip uniform resizing,
and headset keyboard/mouse editing. The browser defaults to 2560 × 1600. Field owns the page
cursor and browser focus; it does not need desktop focus for WiVRn's dedicated input devices.

For Vision Pro, the planned rendering path is:

```text
Linux Field Vulkan/OpenXR → SteamVR OpenXR → ALVR streamer → visionOS ALVR client
visionOS keyboard/mouse → proposed paired input connection → Field's browser input
```

**No Vision Pro hardware test or visionOS build has been performed in this work.** The second
line above is a proposal, not an implemented endpoint. The Mac is the development/signing machine;
the existing Linux machine continues rendering Field. This is separate from the native Field
visionOS frontend under `native/field-visionos`.

## Linux preparation included with this handoff

Commit `6e0d042d5` introduces:

- `tools/field_alvr.py`: discovers SteamVR via the OpenVR registry or standard Steam locations;
  `--steamvr DIRECTORY` overrides discovery. `--check` prints a read-only launch plan.
- Per-process `XR_RUNTIME_JSON` selecting SteamVR; the global WiVRn runtime is preserved.
- `FIELD_VR_INPUT=desktop` selecting companion-window keyboard/mouse focus. `auto` preserves
  the existing Linux/OpenXR WiVRn default; `wivrn` explicitly requires Linux/OpenXR.
  An explicit `FieldVrWorkspace(headsetInput = ...)` overrides the environment default.
- Setup and limitations in `public/docs/field_vr_ide.md`, and the existing runnable starter
  `dev_docs/agents/transcripts/field_vr_ide.yaml` / gallery entry `field_vr_ide`.

Verified: 3 Python launcher tests, 3 Kotlin input-mode tests and 14 existing VR interaction tests
passed. The launch check found the real installed SteamVR manifest. These establish host
selection and existing interaction logic, not successful Vision Pro streaming.

On the Linux host only, a portable official ALVR v20.14.1 candidate is installed at
`~/.local/opt/alvr/v20.14.1/bin/alvr_dashboard`. It has **not** been registered or started.
`field-download-provenance.json` in that installation records the download. The release archive's
measured SHA-256 was `be82b4a7a3cb3607dd5c307cf2b0a1b524d1ecbba586d657a458117a8a63bc09`;
this is download provenance, not an independently authenticated checksum manifest.
The dashboard's linked libraries resolve; the driver also needs its bundled `libopenvr_api.so`
in the loader search path (plain standalone `ldd` reported it missing, with the bundled directory
in `LD_LIBRARY_PATH` it resolved). A dependency listing does not prove SteamVR driver loading.

## Sources inspected and version discipline

- [visionOS client](https://github.com/alvr-org/alvr-visionos), inspected commit
  `583d33acabfa8bbbeabe802ecc79d963557a8b91`.
- Its ALVR submodule was `e3fd448029c795b1b2d5835c84c6588bf01bae0d`, whose Cargo version is
  20.14.1. That explains the candidate host version; it does not prove compatibility with an
  arbitrary App Store/TestFlight client or a later source build.
- `ALVRClient/WorldTracker.swift`, around lines 1998–2009 in that snapshot, contains a
  commented-out `GCKeyboard.coalesced?.keyboardInput` / `keyChangedHandler` TODO. It does not
  forward keys. No active general mouse forwarder was found in the inspected input sources.
- `ALVRClient/Input/InputSink.swift` wraps controller button/scalar FFI. Do not assume sending
  a new controller path automatically creates a keyboard or mouse transport.
- ALVR main snapshot `a2103d12f0716639abb36672dd23478e88c681ff`,
  `alvr/packets/src/lib.rs`: `ClientControlPacket` has controller button/profile messages and
  reserved payloads, but no dedicated keyboard/mouse events. Check the actual pinned submodule
  before changing any wire format; this protocol observation was from main, not a built client.

Record the client commit, submodule commit, streamer version, Xcode version, visionOS version
and accessory models in every device result. Preserve a working unmodified client as a baseline.

## First Mac task: prove input delivery during ALVR immersion

Apple provides [GCKeyboard](https://developer.apple.com/documentation/gamecontroller/gckeyboard)
and [GCMouse](https://developer.apple.com/documentation/gamecontroller/gcmouse), including relative
movement through `GCMouseInput.mouseMovedHandler`. However, Apple's
[Game Controller documentation](https://developer.apple.com/documentation/gamecontroller)
describes visionOS input as dependent on attention to the app window. Availability of these
APIs is not evidence that callbacks arrive in ALVR's immersive scene.

1. Pair a physical keyboard and mouse directly to Vision Pro using a supported connection.
   Do not assume Mac Virtual Display/Universal Control behaves like direct pairing, or that
   the Quest USB-C hub arrangement transfers to Vision Pro.
2. Build and run the unmodified client first. Establish streaming from the Linux ALVR host.
3. On a topic branch, add a small diagnostic input recorder. Register keyboard/mouse connection
   and disconnection handlers once, retain them for the session, and handle already-connected
   devices. Record monotonic timestamps, key codes/up/down/modifiers, mouse deltas, buttons and
   both scroll axes. Keep callbacks short and logs bounded. Do not register them on every frame
   inside `sendGamepadInputs()` just because the existing TODO lives there.
4. Test the same inputs in the launch window, during active streaming/immersion, while looking
   at the streamed panel, while looking away, and across system UI, app focus and scene changes.
   Test a long mouse drag and Shift selection, release, disconnect and reconnect.
5. Save the event log and observed focus conditions. If immersive callbacks stop, investigate
   a legitimate app-owned input surface/window and its focus requirements. Treat that as an
   experiment: do not claim an invisible window or pointer-lock API solves it without a device
   result. System-reserved shortcuts may remain reserved.

**Milestone:** ordinary typing and continuous relative mouse movement, buttons and scrolling
reach the app while the user is looking at the streamed IDE. If this fails, resolve the visionOS
input ownership problem before building a network bridge. A simulator result is insufficient.

## Mac build starting point

Use a separate checkout of ALVR, not the native Field visionOS app:

```bash
git clone --recursive https://github.com/alvr-org/alvr-visionos.git
cd alvr-visionos
git switch -c field-keyboard-mouse 583d33acabfa8bbbeabe802ecc79d963557a8b91
git submodule update --init --recursive
git rev-parse HEAD
git submodule status
xcodebuild -version
xcodebuild -showsdks
```

The pinned revision is the inspected baseline, not a requirement to ignore newer compatibility
fixes. Read its README, build scripts and project settings before choosing a different revision.
The upstream [building guide](https://github.com/alvr-org/alvr-visionos/wiki/Building) describes
building the Rust client framework with `zsh build_and_repack.sh`, opening `ALVRClient.xcodeproj`,
selecting a signing team, pairing a physical headset and enabling Developer Mode. Install the
visionOS SDK and choose the physical headset as the destination. Follow current Xcode signing
requirements; record any project or bundle-ID changes separately from the input patch.

The wiki was last edited in February 2024 and still mentions a v21 nightly. Resolve that advice
against the actual pinned code and compatible streamer, rather than blindly replacing the host
with whatever nightly happens to be current. A build must compile the visionOS target; an empty
or simulator-only success does not validate installation or physical input capture.

## Second task: a small paired Field input bridge

Recommended first implementation: add a separate input connection from the patched visionOS
ALVR app to Field. Keep ALVR's video/tracking transport unchanged initially. A future upstream
ALVR keyboard/mouse protocol would be a separate integration.

The receiver **does not exist yet**. Choose its API after the capture experiment. Use a paired,
authenticated session scoped to one VR browser, respect Field's existing browser-origin policy,
and keep credentials out of logs and committed documents. Do not expose a general remote input
injection endpoint or inject into the Linux desktop. End the input session when its panel closes.

Send physical key identity/up/down and modifier state, relative mouse deltas, button transitions,
two-axis wheel deltas, and explicit reset/disconnect. Include sequence/session identity to reject
stale traffic after reconnect. Keep buttons/keys ordered; coalesce motion and wheel updates while
preserving displacement and press/release barriers. Bound the queue and release all held input
on focus loss, timeout, overflow or disconnect. Reconnect must not replay already-held keys.

Reuse these Field seams (all under `src/jvmMain/kotlin/afield/media/browser/`):

- `WiVRnBrowserInput.kt` and `WiVRnEvdev.kt`: current dedicated-device ownership, translation,
  modifier and reset behaviour. Reuse the browser-facing logic, not WiVRn device identities.
- `FieldVrWorkspace.kt`: browser lifecycle, physical/controller cursor ownership and modifier
  merging. The new source needs an explicit mode; merely setting `FIELD_VR_INPUT=wivrn` will
  wait for WiVRn devices and cannot consume ALVR input.
- `PuppetBrowserVrInput.kt`: existing ray click/scroll and grip interactions.
- `FieldVrKeyboard.kt` and the browser input implementation: focus and DOM/editor event delivery.

Check physical-key layout handling against the existing XKB path. Raw key codes are not Unicode
text: international layouts, dead keys, Compose, and especially native IME composition need an
explicit policy. Start with the actual developer's layout and test shortcuts plus ordinary text;
do not claim full text-input parity from a US letter-key demo. Avoid duplicate key-to-text paths.

## Bring up Field on Linux when the headset is ready

The existing WiVRn setup and user workspace must be preserved. At preparation time, the user's
Field was on port 9092, workspace `/home/marc/Field4-VR-workspace`, document `Field-VR-IDE`.
These are a snapshot: recheck listener, process command line and cwd before stopping anything.
Use SIGINT on the verified Field JVM so pending edits save. Coordinate with the user before
replacing an active VR session; do not terminate unrelated GPU work or change the global runtime.

Start the compatible ALVR streamer, register its SteamVR driver and trust/connect Vision Pro.
Then, from the Field checkout containing the launcher:

```bash
uv run python tools/field_alvr.py --check -- /home/marc/Field4-VR-workspace -d Field-VR-IDE
uv run python tools/field_alvr.py -- /home/marc/Field4-VR-workspace -d Field-VR-IDE
```

Use the existing **Field inside VR** starter. Set `useHeadset = true` in box 1; restart Field
when switching runtime or eye size; launch box 1, then box 2. Until the proposed input receiver
is implemented, the launcher selects desktop companion focus for real keyboard/mouse input.
Controller/hand mappings depend on what SteamVR/ALVR expose and require separate device tests.

## Acceptance and handback

Demonstrate typing/editing Kotlin in Monaco, modifiers/shortcuts, selection, held mouse drag,
two-axis scroll without a delayed tail, running a box and inspecting its real output. Verify
the page cursor never moves the Linux desktop pointer and no desktop window needs focus.
Then test controller/mouse handoff, recenter, panel movement/resizing, focus loss, reconnect,
and an independent remote browser editing the same document without stealing focus.

Hand back the client patch/commit, host receiver patch/commit, exact build/version information,
real device logs and a short capture if available. Distinguish capture success, stream success,
input-forwarding success and remaining failures. Update the public verification section only
after hardware results exist. Publish Field changes through its lander; follow the ALVR fork's
own repository instructions for the client. No Mac-side code or client fork has been published
as part of this handoff.
