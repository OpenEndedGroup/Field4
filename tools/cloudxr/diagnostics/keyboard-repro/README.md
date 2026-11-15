# FoveatedStreaming / Mac Virtual Display keyboard reproduction

`MinimalApp.swift` is a proposed reduced reproduction, not a separately built or
verified app. It passes `swiftc -typecheck` against the visionOS 27.2 SDK. The equivalent native TextEditor and manual-connection cases have
been reproduced in the signed Field CloudXR client. Use a separate visionOS 27
app target with the foveated-streaming entitlement, hand tracking privacy string,
local-network privacy string and your signing identity. Pair it with a CloudXR
server using Apple's management protocol. This source does not implement a QR host.

Tested environment in the full diagnostic client:

- Apple Vision Pro: visionOS 27.3 beta, build 24N5286r.
- Mac: macOS 27.2.1, Xcode 27.2 (27B52).
- CloudXR Linux runtime 6.2.3, RTX 4090.

Observed:

1. Connect Mac Virtual Display. The Mac keyboard types in a native TextEditor.
2. Connect FoveatedStreaming without opening its immersive space.
3. Ordinary typing stops in the native TextEditor and native address TextField.
4. The system helper's completion suggestions can still insert text.
5. Pause/disconnect does not restore normal typing; a fresh app process does.
6. An empty local RealityView immersive space does not trigger this failure.
7. Mixed/progressive immersion, removing accessory input authorization, explicit
   key-window/first-responder requests, resign/re-enter focus, and explicit scene
   activation did not restore typing.

Logs recognize incoming Sidecar MacBook keyboard events. Around connection,
StreamReceiverDisplay requests keyboard focus (`stealKeyboard: YES`). RTI also
emits invalid-session warnings; those alone are not conclusive, because accepting
suggestions still inserts text. Component ownership/root cause remains unproven.

A Bluetooth keyboard paired directly to Vision Pro was tested and fails identically,
including after connection but before stream presentation. The failure is therefore
not specific to Sidecar/Mac forwarding. Explicit scene activation also did not help.

Private logs are retained on mi2 in /Users/marc/temp/cloudxrapp:
`live-keyboard-filtered.log`, `keyboard-immersion-transition.ndjson`,
`keyboard-session-renewal.log`, and `keyboard-manual-stream.log`.
Do not publish raw device logs without reviewing their contents.

## Raw keyboard control

The app's GCKeyboard probe receives transitions before connection and stops receiving
transitions when normal typing stops after connection (user-confirmed). This happens
with a directly paired Bluetooth keyboard as well as Mac-forwarded input. A custom
message channel cannot relay events the client app does not receive. Channel 1 itself
was discovered and its preset Hello! message was successfully sent and echoed.

The logs show incoming Sidecar hardware key events at the system level; this does not
mean those events reach the client application. UI text delivery and GameController
raw keyboard delivery both fail. The exact responsible component remains unproven.

No public keyboard-capture-release switch was found in the inspected visionOS 27.2
FoveatedStreamingSession interface. This is a negative inspection result, not a claim
that no such API or workaround could exist elsewhere.
