# Linux to Apple Vision Pro

Render an OpenXR scene on Linux and view it on Apple Vision Pro through NVIDIA CloudXR
and Apple's Foveated Streaming framework. Field uses its existing Vulkan renderer and
multiview eye targets; the Vision Pro client handles the stream and native tracking.

The working development setup keeps editing on a laptop: open the Linux Field document in
its browser and view the laptop through Mac Virtual Display. The headset receives the scene.
The same live document can be edited from other connected browsers.

## Starting point

The `cloudxr_one` starter draws a red 40 cm cube 1.5 m ahead, on black, with a desktop eye-image
preview. It **does not draw the IDE on a texture by default**. Its three boxes configure the
room, draw the cube and advance frames. The cube's vertex and fragment tabs are editable
and support the normal shader reload command.

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

Pause/disconnect, focus changes and scene activation did not recover typing. The exact
responsible component is not established. Use the laptop browser for editing; the native
browser and Vulkan panel remain optional interfaces, not keyboard-complete IDEs on this setup.
[Reproduction notes](https://github.com/OpenEndedGroup/Field4/tree/main/tools/cloudxr/diagnostics/keyboard-repro)
record the tests without publishing private device logs.

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
