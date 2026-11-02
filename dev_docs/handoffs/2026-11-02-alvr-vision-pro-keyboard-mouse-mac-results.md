# Mac results: ALVR Vision Pro keyboard and mouse (2 November 2026)

Answer to [the Mac handoff](2026-11-02-alvr-vision-pro-keyboard-mouse.md). **No Vision Pro hardware
test was run**: the headset was not reachable while this was done (`devicectl` listed it as
`unavailable`). This document separates what was built, what ran, and what still needs the device.

| Stage | Status |
| --- | --- |
| Input capture on device (the handoff's first milestone) | **Not tested.** Recorder built and ready |
| Stream success from the Linux ALVR host | Not tested |
| Input forwarding to Field | Built both sides; verified only from the visionOS **simulator** to a JVM receiver on the Mac |
| Field-side receiver | Implemented behind `FIELD_VR_INPUT=paired`, JVM tests pass on macOS |

## Versions

| | |
| --- | --- |
| Client | `alvr-org/alvr-visionos` at `583d33acabfa8bbbeabe802ecc79d963557a8b91` (also `origin/main` on the day) |
| ALVR submodule | `e3fd448029c795b1b2d5835c84c6588bf01bae0d` (`v20.14.1-4-ge3fd4480`) |
| Xcode / SDK | Xcode 26.5 (17F42), visionOS 26.5 SDK, macOS 26.5 |
| Rust | rustup stable 1.98.0, target `aarch64-apple-ios`, profile `distribution` |
| Simulator | Apple Vision Pro 4K, visionOS 26.5 |

## Where the client work is

A local checkout, **not pushed anywhere**: `~/temp/alvr-visionos-field`, branch
`field-keyboard-mouse`, four commits on the pinned revision:

1. `fix: build WorldTracker against the visionOS 26.5 SDK`. Upstream now builds with the
   Xcode 27 beta (`XCODE_BETA_27` in `AppStore.xcconfig`). The gitignored `Override.xcconfig`
   drops that flag for Xcode 26.5, and one `predictAnchor` optional-chaining site needed a
   26.x branch. The unmodified baseline then built for device and simulator.
2. `feat: keyboard and mouse delivery recorder (diagnostic)`: the experiment the handoff asks
   for first (below).
3. `feat: paired keyboard/mouse bridge to Field's VR browser`: the sender (below).
4. `chore: sign as org.openendedgroup.field.alvr (team 682NLW597Q)`: bundle IDs, team and app
   group only, kept apart so it can be dropped before any upstream PR.

Rebuild the Rust core without `build_and_repack.sh`'s `curl | sh` branch: with rustup's `cargo`
first on `PATH`, run its `cargo build … -p alvr_client_core --profile distribution`, `cbindgen`
and `sh repack_alvr_client.sh` steps (about a minute).

## The recorder (first Mac task)

`ALVRClient/Input/KeyboardMouseCapture.swift` registers `GCKeyboard`/`GCMouse` handlers once at
launch, for devices already attached and on connect notifications, on a private handler queue.
It records HID key usages with up/down and modifiers, relative mouse motion, every button and
both scroll axes, stamped with `CLOCK_UPTIME_RAW`. A focused UIKit responder in the recorder
window (`KeyCaptureUIView`) records the same keys through `pressesBegan`, so a device run can tell
"GameController stopped receiving" apart from "the app stopped receiving".

`KeyboardMouseRecorder.swift` writes every event to
`Documents/KeyboardMouseLogs/km-<time>.jsonl` (48 MB cap per launch; the last 8 launches kept),
with a `context` line whenever scene activation, key window, app state or ALVR streaming changes
(sampled at 4 Hz). Once a second it prints a `KMREC` summary to stdout, including the longest gap
between mouse reports. Advanced Settings → **Keyboard & Mouse Recorder** shows it live, adds
one-tap markers for the handoff's conditions, and can share the log.

Simulator run: the app launched, the recorder logged `capture started` and a context line
(`app=active idle scenes=[window:active+key]`), and the `KMREC` heartbeat ran. **The simulator
could not supply real keyboard input:** synthetic keystrokes need Accessibility permission this
session did not have, and XCUITest on visionOS has no `typeKey`. So no GameController delivery
claim is made, even for the simulator.

## The bridge (second Mac task)

`ALVRClient/Input/FieldInputBridge.swift` sends to Field over its own TCP connection; ALVR's
transport is unchanged. Pairing: Field shows a ten-character code in the VR toolbar's input-status
line; type it, the Linux host's address and port (9293) into the recorder window once (the code
is kept in the Keychain). Wire format and threat model are in the header of
`src/jvmMain/kotlin/afield/media/browser/PairedHeadsetInput.kt`: HMAC challenge-response on the
code, then AES-GCM frames with a strictly increasing sequence number.

Keys, buttons and resets keep their order; motion and scroll between them are summed into one
record, flushed every 4 ms; a heartbeat goes out after 0.5 s of silence. The sender's queue is
bounded (overflow becomes a single reset). It sends a reset at session start, on
`willResignActive`/background, and when a device disconnects. Reconnects replay nothing.

Field side, `PairedInputSource` turns records into the evdev events `WiVRnBrowserInput` already
consumes (HID usage → Linux key code as the kernel's hid-input maps it; motion → `REL_X/REL_Y`;
buttons → `BTN_*`; scroll → high-resolution wheel), so the XKB layout path, modifiers,
cursor ownership and release-on-reset are the existing, Quest-tested ones. It also synthesizes
evdev-style autorepeat (500 ms, then every 33 ms) for a held non-modifier key, because a
remote key-down does not repeat. A new session supersedes the old one; 2.5 s of silence,
a stale or tampered frame, or queue overflow ends the session and releases everything. The
listener exists only while the `FieldVrWorkspace` is open.

Select it on the Linux host with `uv run python tools/field_alvr.py --paired-input -- …`
(sets `FIELD_VR_INPUT=paired`). Options: `FIELD_VR_PAIRED_INPUT_PORT`,
`FIELD_VR_PAIRED_COMMAND_AS_CONTROL=1` (Command types Linux Control shortcuts),
`FIELD_VR_PAIRED_MOUSE_SCALE`, `FIELD_VR_PAIRED_WHEEL_SCALE` (negative flips). The code lives in
`~/.afield/vr-paired-input-code` (0600); delete it to rotate.

### Simulator-to-Field interop

The simulator build (launched with simulator-only `-FieldInputHost/-FieldInputPort/-FieldInputCode`
arguments and `-FieldInputSelfTest`) paired with a real `PairedInputSource` on the Mac's loopback
and sent the labelled self-test sequence through the real capture fan-out, bridge, crypto and
receiver. This proves the two implementations agree on the protocol, not that a device delivers
input. Field received, as evdev `(type code value)`:

- `hello`, Shift+A, Enter: keys 35 18 38 38 24, then 42↓ 30↓ 30↑ 42↑, then 28↓↑;
- ten GameController moves of (10, −5) summed into one record: `REL_X 100`, `REL_Y 50`, SYN
  (+y up negated to +y down);
- left button down, a 20 px move, button up, each in its own order with SYNs;
- scroll −3 as `REL_WHEEL_HI_RES −360`;
- a reset of both devices at session start, and again when the app was terminated, with the
  status falling back to "headset disconnected".

The temporary harness that printed this was not committed. The protocol, the ordering, and the
failure paths (wrong code, replayed or tampered frames, a superseding session, silence, a reset
record mid-batch, autorepeat, overflow, close) are pinned in
`src/jvmTest/kotlin/afield/media/browser/PairedHeadsetInputTest.kt`.

## Signing

A capability-carrying device build failed: Xcode's stored session for `marc@openendedgroup.com`
was rejected, so automatic provisioning could not register the app group
(`group.org.openendedgroup.field.alvr`) or the low-latency-streaming entitlement. **Sign in again
in Xcode → Settings → Accounts**, then build with `-allowProvisioningUpdates`. Meanwhile an
experiment-only build signed with the team's existing wildcard profile and *no entitlements*
built successfully (`CODE_SIGN_ENTITLEMENTS` overridden on the command line, nothing committed).
That build lacks low-latency streaming and the eye-broadcast app group, so judge input with it,
not stream quality.

## What the device run must settle

1. Do `GCKeyboard`/`GCMouse` callbacks arrive during ALVR immersion, looking at the stream and
   looking away, and across system UI? Compare the `gc` and `uikit` counts in the log.
2. Mouse Y sign. GameController is assumed to report +y up (the bridge negates it, toggle
   **Invert mouse Y**). Scroll Y sign is a guess too (**Invert scroll Y**,
   `FIELD_VR_PAIRED_WHEEL_SCALE`).
3. Whether `willResignActive` fires on gaze/focus changes during immersion (the bridge's reset
   relies on it; the context lines show what the system reported).
4. The handoff's acceptance list (Monaco editing, shortcuts, selection, held drag, two-axis
   scroll, focus loss, reconnect, an independent remote browser), on the Linux host with
   `--paired-input`.

Device recipe: wake the headset and wait for `xcrun devicectl list devices` to show it
`connected`, install `ALVRClient.app` from the build products, launch it with
`xcrun devicectl device process launch --device <id> --console org.openendedgroup.field.alvr`
to watch `KMREC` lines, and pull logs with
`xcrun devicectl device copy from --device <id> --domain-type appDataContainer
--domain-identifier org.openendedgroup.field.alvr --source Documents/KeyboardMouseLogs --destination <dir>`.
