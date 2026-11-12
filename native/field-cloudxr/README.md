# Field CloudXR client for Apple Vision Pro

Optional native visionOS client for Linux Field's Vulkan/OpenXR renderer, adapted from
Apple's Foveated Streaming sample. Apple's MIT license is retained in `LICENSE.txt`.
No NVIDIA runtime binaries, signing certificates, provisioning profiles or developer team
are included.

## Build

Open `FoveatedStreaming-Sample/FoveatedStreaming-Sample.xcodeproj`, select the
`FoveatedStreaming-Sample` scheme and a physical Vision Pro. Use Xcode 27+ for hand/accessory
authorization. Select **your own development team** and change the neutral bundle ID
`org.field4.CloudXRClient` to one registered to your team. Enable Foveated Streaming
(`com.apple.developer.foveated-streaming-session`), Developer Mode and device pairing.
The Linux host's `--bundle-id` must match. No project settings reference a developer account.

Unsigned compile check:

```sh
xcodebuild -project FoveatedStreaming-Sample/FoveatedStreaming-Sample.xcodeproj \
  -scheme FoveatedStreaming-Sample -configuration Debug \
  -destination 'generic/platform=visionOS' CODE_SIGNING_ALLOWED=NO build
```

A simulator cannot establish a real headset stream. The packaged source passes an unsigned physical-device-target build with
Xcode 27.2; the development client was signed, installed and tested on visionOS 27.3 beta.
Sign with your own account for device use.

## Connect and use

Start the [Linux host](../../tools/cloudxr/README.md), then choose Automatic/Search or enter
its LAN address and management port 55000. Scan the QR when requested. Allow hand and
accessory tracking. The client automatically presents streaming on connection/resume and
dismisses it on pause/disconnect.

The starter explicitly opts into OpenXR hand actions and receives 26 joints per hand. In the optional Vulkan
IDE panel, pointing/pinch clicks, one fist moves the panel and two fists resize it.
Two-fist rotation and threshold tuning remain future work. The app requests hidden upper
limbs; visionOS retains control of that presentation.

With a Mac, the tested editor is Chrome connected to Linux Field, viewed through Mac
Virtual Display. The starter defaults to scene-only rendering. Linux launcher `--ide` adds
the optional Vulkan panel. **Open Field IDE** opens a native WKWebView in this app; enter
an HTTPS Field URL, saved locally. With a development certificate, expand **Development
certificate** and enter its SHA-256 fingerprint. The pin applies only to that URL's host;
other certificates use system trust. No host or certificate is baked in. **Stream controls**
restores the main panel if it was closed.

## Keyboard limitation

On the tested visionOS 27.3 beta, connection stops hardware keyboard input throughout the
app, even before presenting the stream. Mac-forwarded and directly paired Bluetooth
keyboards both fail. A native TextEditor and Apple's message TextField reproduce it without
WebKit, Field or Monaco. Raw GCKeyboard events also stop. Pause/disconnect, focus and scene
activation did not restore typing; completion suggestions still insert text. Local immersion
without streaming works. Ownership of the underlying bug is not established.

Use the laptop-browser workflow for editing. The native browser remains an optional viewing
and gesture interface. [Reproduction notes](../../tools/cloudxr/diagnostics/keyboard-repro/README.md)
record the isolation results. Private device logs are not distributed.

The stock settings panel tests messages when the server announces a channel. The starter's
Channel 1 greets and echoes; its preset Hello! round trip was verified. Messaging does not
recover missing keyboard events.
