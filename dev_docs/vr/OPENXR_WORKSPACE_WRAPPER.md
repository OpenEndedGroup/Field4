# A shared workspace wrapper across VR runtimes

Design proposal, following the Linux → CloudXR → Vision Pro device tests on 2026-11-12.
This is not an implemented API. The working starter remains `templates/cloudxr_one`.

## What is already shared

All native OpenXR routes use Renderer, OpenXRHmdTarget and MultiviewStereoTarget. The same
scene can render to a desktop eye mirror and a headset without a second scene pass.
FieldVrWorkspace already groups the Chromium texture, panel, controller input, grabbing and
desktop companion. CloudXR adds a host/client lifecycle and optional vendor message channels;
it does not need a separate scene renderer. Native WKWebView and remote laptop browsers are
independent clients of Field's existing document protocol.

A wrapper should remove repeated setup/lifetime code without pretending every transport
has the same input or editor capabilities.

## Separate the choices

| Choice | Responsibility |
| --- | --- |
| Runtime adapter / process launcher | Select loader, runtime manifest, libraries, validation and platform graphics interop before the first instance |
| Scene/session owner | Renderer, one stereo target, current predicted eye poses, scene root transform, frame cadence and ordered shutdown |
| Desktop presentation | None, one eye, or side-by-side; best-effort acquisition when the headset is primary |
| Editor frontend | Remote browser (default here), optional Vulkan browser panel, or native client window |
| Input transport | OpenXR actions/joints; explicitly selected WiVRn HID; desktop companion focus; native client input when it works |
| Optional resources | Data channels, body/eye tracking and other extensions discovered from runtime capabilities |

An eventual surface could look like this (proposed syntax only):

```kotlin
val room = openXRWorkspace {
    editor = Editor.RemoteBrowser
    desktopPreview = Preview.LeftEye(bestEffort = true)
    input = Input.OpenXRActions
}
room.scene += cubeModule
```

The process launcher, not this late scene builder, must select CloudXR/WiVRn/SteamVR.
XR_RUNTIME_JSON and Vulkan loader/interop choices cannot safely change after first instance
creation. Show effective capabilities and selected paths rather than guessing from the OS.

## Useful capability boundaries

- Linux/WiVRn: controller actions and virtual headset keyboard/mouse devices. Evdev forwarding
  belongs to a WiVRn input adapter, never to a generic `isLinux` choice.
- Linux/CloudXR: standard scene and hand actions work. The client needs the Apple management,
  certificate and session lifecycle. Native keyboard delivery fails on the tested beta,
  including before stream presentation; laptop-browser editing remains independent of it.
- macOS/WiVRn: the existing launcher/loader, Vulkan driver and Metal/IOSurface interop setup
  are adapter responsibilities. Do not imply parity merely because the scene API is shared.
- Linux/Windows SteamVR (optionally streamed by ALVR): the same OpenXR target can own the
  scene; runtime/controller profile and streaming service setup remain separate.
- Browser WebXR and the native Field RTT receiver are different presentation transports.
  They may share scene-level concepts, but are not OpenXR runtime adapters.

Browser texture transport also differs by platform. The current Linux FieldUiBrowserTexture
uses CPU bitmap readback/upload. macOS has an IOSurface path. Windows browser bridge support
must be checked explicitly; a generic wrapper must not promise a working zero-copy path.

## First implementation seam

1. Extract the renderer/session/desktop-preview setup used by the starter. Keep the existing
   OpenXR render-before-submit ordering and predicted pose authoritative for every layer.
2. Give the wrapper exactly one stereo target. FieldVrWorkspace currently constructs its own;
   either reuse `workspace.stereo` or allow it to accept the owner's target. Do not construct
   a second target just to enable an editor panel.
3. Add the optional browser panel as a layer/resource whose lifetime is owned by the room.
   Editor windows and remote browsers do not become required for scene rendering.
4. Expose capability records and narrow runtime input adapters. Native hand-derived actions
   can use the current controller UI path without enabling raw hand-joint pointing globally.
5. Close panels and channels, retire scene GPU work/session and finally stop the external
   runtime. Pause, doffing, full disconnect and external host termination remain distinct.

Tests should cover desktop scene-only, desktop panel+preview, WiVRn controller/HID behavior,
CloudXR native hand actions and full disconnect/reconnect. Shared code does not establish
untested runtime/platform parity. Do not route keyboard events through a message channel
unless a real input source receives those events; the channel itself is only byte transport.
