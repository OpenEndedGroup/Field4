# Field's cross-platform headset routes

Status: 2026-11-13. This began as a runtime experiment plan. Both runtime routes below now
work with Field; use this page as a route map, and the linked guides for setup.

| Host and headset | Rendering and transport | Device evidence |
| --- | --- | --- |
| Linux → Quest | Field Vulkan/OpenXR → WiVRn → Quest WiVRn app | Interactive Field workspace, controllers, forwarded headset keyboard/mouse and stereo video |
| Apple silicon macOS → Quest | Field Vulkan/OpenXR → macOS WiVRn runtime → Quest WiVRn app | Interactive workspace and `video_four`, including the movie ring and Field pane on a fresh M4 Mac |
| Linux/NVIDIA → Vision Pro | Field Vulkan/OpenXR → CloudXR runtime → Apple Foveated Streaming client | Stereo scene, head tracking, hand interaction, workspace panel and native browser window |

The runtime routes preserve the desktop OpenXR scene. Their runtime, loader and Vulkan-driver
selection belongs to startup; it does not require a different particle, video or text renderer.
Each launch selects its runtime without replacing the system's default runtime.

## Linux and macOS through WiVRn

Use [the workspace guide](../../public/docs/field_vr_ide.md), including its macOS section.
On macOS, `./field.sh --setup` offers **WiVRn headset streaming**: it installs the pinned app
if absent, prepares the local OpenXR loader and selects VideoToolbox HEVC. The launcher
selects MoltenVK for WiVRn's Metal image sharing. The ordinary Vulkan SDK is not required
for the tested path: Field's bundled MoltenVK also ran the M4 video test.

[`video_four`](../../templates/video_four/README.md) is a portable room of stereo video screens.
Its movie lives in the document's `files/` directory and is resolved through FileManager.
Widget caches must not replace the resolved movie path or a recalled transport look: the
transport panels use `restoreValues = false`.

A remote browser needs both a listener and a recognised browser authority. For example:

```sh
AFIELD_BIND_HOST=0.0.0.0 AFIELD_BROWSER_HOSTS=my-mac:9092 python3 tools/field_video_four.py
```

Alternatively register the exact `hostname:port` in `~/.afield/browser-hosts.json` before
starting Field. Verify the scripts, styles, fonts and WebSocket connection in a real browser;
a successful HTML navigation alone does not exercise the same-origin resource checks.
See [the server surface guide](../../public/docs/server_surface.md).

## Linux to Vision Pro

Use [the CloudXR guide](../../public/docs/cloudxr_vision_pro.md),
[the optional native client](../../native/field-cloudxr/README.md) and
[host tooling](../../tools/cloudxr/README.md).

The client can include a native WKWebView, and the streamed scene can include Field's browser
texture. Both are optional. Hardware keyboard input into native app fields stopped after a
CloudXR connection in the tested visionOS/Xcode beta setup, including a directly paired
Bluetooth keyboard. Message transport worked; keyboard event delivery did not. The practical
editing path was Chrome on a Mac connected to the Linux Field server, viewed through Mac
Virtual Display alongside the immersive stream. Hand interaction remained usable in the stream.
The [keyboard investigation](../../tools/cloudxr/diagnostics/visionos-keyboard-session.md)
records the narrower evidence and remaining limitation.

## Independent native and RTT routes

[Field4Vision](../../native/field-visionos/README.md) remains a separate route for native spatial
UI, Kotlin-to-Metal work and stereo RTT surfaces. Those capabilities are useful in their own
right. A stereo video surface does not establish the pose-associated compositor behaviour of
an OpenXR runtime; see [the route status](../../public/docs/vision.md).

The proposed common lifecycle boundary is in
[OPENXR_WORKSPACE_WRAPPER.md](OPENXR_WORKSPACE_WRAPPER.md). Keep scene ownership, runtime
selection, presentation surfaces and input policy explicit when evolving that wrapper.
