# Linux to Apple Vision Pro

Render an OpenXR scene on Linux and view it on Apple Vision Pro through NVIDIA CloudXR
and Apple's Foveated Streaming framework. Field uses its existing Vulkan renderer and
multiview eye targets; the Vision Pro client handles the stream and native tracking.

You can work on the scene while you see it in the headset. In the tested setup, Field runs on
Linux and Chrome opens its document on a Mac. **Mac Virtual Display** brings that browser into
Vision Pro alongside the streamed scene. The Mac is needed for this editing setup; Linux still
renders the scene. Other connected browsers can edit the same live document.

To use [Mac Virtual Display](https://support.apple.com/en-ie/118521) inside the immersive app,
enable **Settings → Developer → Allow Mac Virtual Display** on the headset. A separate Vision Pro
remote-desktop viewer would normally be [hidden while the immersive space is open](https://developer.apple.com/documentation/swiftui/immersive-spaces),
so we have not established a Linux-only substitute for this editing setup. The CloudXR client also
offers optional IDE surfaces, described below.

## Starting point

Start with `cloudxr_one`: its three boxes configure the room, draw a red 40 cm cube 1.5 m ahead,
and advance frames. A desktop preview shows the eye image. You can edit the cube's vertex and
fragment tabs and reload the shaders as usual. The starter does not draw the IDE on a texture
unless you enable the panel.

- [Starter source and import instructions](https://github.com/OpenEndedGroup/Field4/tree/main/templates/cloudxr_one)
- [Linux host setup](https://github.com/OpenEndedGroup/Field4/tree/main/tools/cloudxr)
- [Optional visionOS client source](https://github.com/OpenEndedGroup/Field4/tree/main/native/field-cloudxr)

Install CloudXR Runtime separately under NVIDIA's license. Build the optional client on a
Mac with Xcode 27+, select your own signing team and bundle ID, and pair a physical Vision Pro.
Use the same bundle ID in the Linux host's Bonjour advertisement. The source includes Apple's
sample license and contains no signing identity or proprietary SDK binaries.

The runtime is selected in the Field process environment before Vulkan initializes. This
does not replace a WiVRn installation or change the computer's global OpenXR runtime.

## Hand interaction

With hand/accessory permissions allowed, Field receives 26 joints per hand and native OpenXR
aim, pinch and grasp actions. The optional Vulkan IDE panel supports pointing/pinch clicks,
one-fist movement and two-fist scaling. Two-hand rotation and gesture sensitivity need more
work. The client requests hidden upper limbs; final presentation remains under visionOS control.

The Linux launcher `--ide` enables the 3200 × 2000 Vulkan IDE panel. It is useful for viewing
and gestural interaction. The client's **Open Field IDE** button opens an optional native
WKWebView instead, with local text rendering and system pointer/gesture handling.

## Keyboard limitation

On the tested visionOS 27.3 beta, establishing the streaming connection stops ordinary
hardware keyboard input throughout the client app—even before displaying the stream. This
affects both Mac-forwarded keyboards and keyboards paired directly with Vision Pro. It
reproduces in a plain native TextEditor and Apple's message demo, independently of Field,
Monaco or WebKit. Raw keyboard transitions stop too, although accepting a system completion
suggestion can insert text. An ordinary local immersive space does not trigger it.

For now, use the laptop browser to edit. The native browser and Vulkan panel remain useful for
viewing and gestures, but neither provides a complete keyboard-driven IDE on this setup. Pausing or
disconnecting the stream, changing focus and reactivating the scene did not restore typing; we do
not yet know which component is responsible. The [reproduction notes](https://github.com/OpenEndedGroup/Field4/tree/main/tools/cloudxr/diagnostics/keyboard-repro)
record what was tested.

## What has been tested

Real headset tests used Linux/CachyOS with an RTX 4090, CloudXR Runtime 6.2.3, Xcode 27.2
and visionOS 27.3 beta. CachyOS is outside NVIDIA's stated Ubuntu support target. Cube streaming,
head movement, hand interactions, native-browser gestures and a byte-channel echo were confirmed.

Field reports 3800 × 3040 pixels per eye in float16 render targets. Runtime foveation must
be enabled so Field renders the full unwarped image rather than the small warped transport
image. Gaze-following behavior and end-to-end stream precision were not separately established.
Linux audio is unavailable in this SDK configuration. Full disconnects retire Field before the
runtime; native host shutdown has occasionally needed a service timeout.

See [Field inside VR](field_vr_ide.md) for the separate Quest/WiVRn workflow, including working
headset-attached keyboard and mouse input. [Field on Vision Pro](vision.md) describes the
other native frontend and RTT receiver; it is a separate transport.
