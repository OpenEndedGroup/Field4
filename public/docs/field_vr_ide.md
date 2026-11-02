# Field inside VR

Field's frontend can run inside its own Vulkan scene: the real canvas, Monaco editor, outputs and
command palette, connected to the same document as the desktop app. Edits and code execution go to
the usual Field server. A world-locked browser panel lets you use it with the headset on. Controllers can position the
panel and operate the interface; a keyboard and mouse connected to the Quest can use a dedicated
WiVRn input path without desktop window focus. Another browser can edit the same document remotely.

```diffstatic_runner {data-lang=kotlin requires="electron-runtime" screenshot="assets/field_vr_ide/companion.png" capture="native-window" native-wait-frames="20" trim-native=true wait="8" embed=true hidden=true timeout="180" name="field-vr-ide-companion" alt="Field's live document and VR keyboard toolbar, sampled from Chromium into the Vulkan desktop companion" macro-file="../../dev_docs/agents/transcripts/field_vr_ide.yaml" capture-box="vr_ide_renderer"}
// Runs the complete starter in desktop mode and captures the actual Vulkan companion.
```

[Open the starting point →](offline.html?file=assets/agent_transcripts/field_vr_ide/metadata.edn)

## Start it

Launch **1 — VR IDE renderer**, then **2 — Live IDE in VR**. The starting point defaults to a
desktop companion. For the headset, connect your OpenXR runtime first, set `useHeadset = true`
in box 1, restart Field and launch both boxes again. Eye targets use the runtime's actual dimensions.

The browser opens this same document, including ephemeral session identity when needed. Select
**Playground**, open its editor, change the message or colour and press **Run box** in the
panel toolbar. The result appears as an ordinary Field output.

## Point, edit and run

Either controller can hover over the panel. Pull the trigger to click or drag; the hand that starts
a drag keeps control until release. The thumbstick or trackpad scrolls. Losing tracking releases a
held mouse button. When controller aim input is unavailable, tracked hands use a wrist-to-index-knuckle
ray and thumb/index pinch, with hysteresis to avoid repeated clicks near the pinch threshold.

Aim at any part of the panel and hold **grip** to pick it up. Moving and rotating that controller
moves and rotates the whole panel, preserving the initial attachment offset. Release grip to leave
it in place. Browser clicks are suspended while moving the panel; losing tracking drops it safely
at its last pose.

While holding the panel, aim the other controller at it and squeeze its grip too. Move your hands
apart to enlarge the panel or together to shrink it. Its aspect ratio stays fixed, and moving both
hands together moves its centre. Release either grip to continue moving it with the remaining
hand, or release both to leave it in place. Recenter preserves the resized dimensions. This changes
the plane's physical size; the browser's pixel resolution stays the same.

The toolbar's **Keyboard** button opens text, punctuation, arrows, selection, clipboard and undo
keys. It keeps the editor or palette search focused while you click its keys. **Run line** executes
the current line or selection (Alt+Enter); **Run box** launches the whole selected box (Alt+Up). **Commands** opens
Field's palette; **Esc** dismisses it. A physical keyboard also works while the native mirror window
has focus. The virtual keyboard is a basic ray keyboard; there is no dictation or predictive typing.

**Recenter** places the upright panel in front of your current head position. It stays in the room
as you move your head. Stop box 2 to close the offscreen browser and remove the panel's input hooks.
Restart Field to change OpenXR mode or eye dimensions.

## Choose your input

| Input | What receives it | Desktop window focus |
| --- | --- | --- |
| Controller trigger or hand pinch | The control under the ray on the VR panel | Not required |
| Keyboard and mouse attached to the PC | The front-on companion window | Required |
| Keyboard and mouse attached to the Quest | The VR browser through WiVRn HID forwarding | Not required after setup |

The headset mouse has a cursor confined to the browser page. It does not steer the Linux desktop
pointer or the other browser client's pointer. Clicking a text field or editor in the panel chooses
where the headset keyboard types. Switching between these inputs does not change which Field
document the panel displays.

## A real keyboard

For a keyboard attached to the PC while OpenXR is active, set `headsetInput = false` in the
`FieldVrWorkspace(...)` constructor in box 2, then stop and relaunch that box. This selects the
desktop focus behaviour below. The default Linux/OpenXR mode uses the dedicated headset input path.

A USB or Bluetooth keyboard attached to the desktop uses the native companion window's key and text
callbacks, forwarded into the focused Chromium editor. The companion displays the same page front-on,
with aspect-correct direct mouse input. **Real keyboard** explicitly requests desktop window focus.
Controller clicks choose the focused browser control without changing the desktop's focused app.
If the window manager refuses the explicit request, focus **Field · VR IDE** on the desktop once. This does not
capture keys globally from other applications. Ordinary modifiers, arrows, shortcuts and Unicode
text use the existing browser input path.

Alt-Tab to another app returns the physical keyboard and mouse to that app. Alt-Tab back reactivates
the companion's keyboard input; focus loss releases its held keys and buttons. The mouse cursor is
also visible on the VR panel. Moving a controller resumes its ray cursor; a held mouse drag keeps
ownership until release or an explicit controller press.

## Headset keyboard and mouse, without desktop focus

On Linux/OpenXR, `FieldVrWorkspace` automatically listens for WiVRn's virtual **WiVRn Keyboard**
and **WiVRn Mouse** devices. It captures those identified virtual devices and sends their events
directly to this Chromium panel. A headset mouse
moves a cursor inside the page; clicks, held drags and vertical/horizontal scrolling use that
cursor. Moving the panel leaves the cursor at the same page coordinates. An explicit controller
press can take over; controller hover cannot interrupt a mouse drag.

The desktop window does not need focus. In this mode **Real keyboard** focuses the browser
control path without requesting desktop window focus. Keys go to the panel's focused editor,
palette or input. The toolbar reports waiting, capture and permission status. Disconnects and
input overflow release held keys/buttons; reconnecting does not replay already-held presses.
The desktop-connected devices still use the ordinary companion-window focus described above.

Install the narrowly scoped Linux device rule once, as the user who runs Field. The command is a
dry run unless `--install` is present. From the Field repository, inspect the plan, then install:

```diffstatic {data-lang=bash}
python3 tools/setup_wivrn_input.py --user "$USER" --serve-port 8443
sudo python3 tools/setup_wivrn_input.py --install --user "$USER" --serve-port 8443
```

This grants your user access only to WiVRn's two identified virtual devices, excludes them from desktop
libinput, enables WiVRn HID forwarding, and adds a private Tailscale HTTPS listener on the unused
port 8443. It registers that exact hostname and port with Field's browser-origin guard; restart
Field after installation so it loads the registration. Existing listeners are preserved; an occupied or public target port is refused. Omit
`--serve-port 8443` when only input setup is wanted. Reconnect WiVRn afterwards so the desktop
sees newly created devices with the rule applied. Stop the VR panel to release Field's grabs;
the device rule continues excluding those forwarded devices from the desktop.

Attach the keyboard and mouse to the Quest by USB-C hub or Bluetooth. WiVRn must capture and
forward the devices; its Android client forwards relative mouse events, rather than an absolute
Android system pointer. The physical Quest USB/Bluetooth path still needs an on-headset check;
software replay establishes the Field routing, not Quest firmware/device compatibility.
See [WiVRn's HID configuration](https://github.com/WiVRn/WiVRn/blob/v26.6.2/docs/configuration.md#hid-forwarding)
and [its Android input handler](https://github.com/WiVRn/WiVRn/blob/v26.6.2/client/android/hid.cpp).

The default keyboard layout is US. Set `FIELD_VR_KEYBOARD_LAYOUT`,
`FIELD_VR_KEYBOARD_VARIANT` and `FIELD_VR_KEYBOARD_OPTIONS` before launching Field to select XKB
layout, variant and Compose options. Shift, Ctrl, Alt, Meta, AltGr and dead-key composition are
translated without relying on a desktop keyboard seat. For example, use layout `us`, variant
`intl` for international dead keys, or layout `fr` for a French keyboard.

## A remote browser on the same document

If you installed the helper before hostname registration was added, register the existing private
listener as your ordinary user, then restart Field. This does not change the listener:

```diffstatic {data-lang=bash}
python3 tools/setup_wivrn_input.py --register-serve-host 8443
```


The setup helper prints a **Private Field URL** after installing the listener. Open that address
from a browser with access to the same tailnet. Set its `file` query parameter to the document
shown in your VR panel; for example, `?file=Field-VR-IDE`. If Field runs on a different port, pass
`--field-port` with that port to the helper when configuring the listener.

Use the same document name in both clients. Each browser retains its own
selection, caret, scroll and keyboard/mouse focus while edits flow through the shared Field
server. Remote text updates preserve focus in another input or palette; they must not pull focus
back into Monaco. This is shared document editing, not a streamed desktop or remote mouse.

Nobody needs to operate the PC display. Field still requires a working graphics/display session
for Vulkan and Chromium, and WiVRn must be running and paired. This workflow does not remove the
Linux display-session requirement. Start Field, connect WiVRn and launch the two starter boxes
before working from the headset. These can be managed remotely; no person needs to operate a
window on the PC. The helper adds a private listener while preserving existing listeners.

## What has been verified

As of **1 November 2026**, the reference Linux/NVIDIA setup has the device rule installed, WiVRn
HID forwarding enabled and a responding private Tailscale HTTPS endpoint. Automated input,
browser, hostname-policy and setup-helper checks pass. Two real Chromium clients exchanged edits
through the installed private Tailscale Serve listener and a live Field server; the receiving
client kept its independent input focus. That collaboration check used two clients on one host.
The updated Vulkan companion was also captured and inspected.

The input tests replay Linux device events through Field's real keyboard translator and Chromium
input path. Physical Quest USB/Bluetooth keyboard and mouse capture, controller/hand interaction
with this IDE panel, and use from a second remote machine remain to be checked. The starting point
is ready for those hardware checks; software tests and the desktop capture do not establish them.

## Readable text

The browser's default is **2560 × 1600**, on a two-metre-wide panel 1.35 metres away. Browser
resolution controls the source text raster; WiVRn render resolution independently controls the
headset's eye targets. Increasing only one can leave the other as the limiting factor. Change the
constructor's `width`/`height`, `widthMeters` and `distanceMeters` to suit your setup.

The browser viewport is set explicitly after page readiness so desktop scaling or a native window
size limit cannot silently change the texture extent. The panel samples Chromium directly without FXAA or temporal filtering of the text. On Linux this
checkout uses the explicit bitmap browser fallback, normally at 30 browser frames per second;
head tracking and stereo rendering continue at the headset's rate. On macOS the existing IOSurface
transport stays on the GPU. See [the browser transport](puppet_browser.md).

```diffstatic {data-lang=kotlin}
val vr = FieldVrWorkspace(
    renderer = renderer,
    url = FieldUiBrowserTexture.fieldDocumentUrl(thisBox),
    width = 2560,
    height = 1600,
)

// Construct after the initial renderer frame, then run the renderer normally.
// Close vr when its owning box stops.
```
