# Field inside VR

Field's frontend can run inside its own Vulkan scene: the real canvas, Monaco editor, outputs and
command palette, connected to the same document as the desktop app. Edits and code execution go to
the usual Field server. A world-locked browser panel lets you use it with the headset on.

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
at its last pose. The other controller cannot steal an active grab.

The toolbar's **Keyboard** button opens text, punctuation, arrows, selection, clipboard and undo
keys. It keeps the editor or palette search focused while you click its keys. **Run line** executes
the current line or selection (Alt+Enter); **Run box** launches the whole selected box (Alt+Up). **Commands** opens
Field's palette; **Esc** dismisses it. A physical keyboard also works while the native mirror window
has focus. The virtual keyboard is a basic ray keyboard; there is no dictation or predictive typing.

**Recenter** places the upright panel in front of your current head position. It stays in the room
as you move your head. Stop box 2 to close the offscreen browser and remove the panel's input hooks.
Restart Field to change OpenXR mode or eye dimensions.

## A real keyboard

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

A Bluetooth keyboard paired to the Quest can also reach the PC through WiVRn's **HID forwarding**.
WiVRn 26.6.2 defaults that setting to false and requires a writable `/dev/uinput`; its native server
then presents the forwarded input to Linux. The same native-window focus requirement applies.
See [WiVRn's versioned configuration](https://github.com/WiVRn/WiVRn/blob/v26.6.2/docs/configuration.md#hid-forwarding).
Keyboard visibility or passthrough in the headset is a separate client feature from forwarding keys.

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
