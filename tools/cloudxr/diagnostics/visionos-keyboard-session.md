# Mac Virtual Display keyboard loss on entering the stream

Physical device observations, 2026-11-12: visionOS 27.3 beta, Xcode 27.2,
CloudXR 6.2.3, the development Field CloudXR client.

Native WKWebView renders Field and handles pointer, gaze/pinch, selection and dragging.
Mac keyboard typing works before streaming, then fails in both the WKWebView and a
separate SwiftUI TextEditor. Pause and Stop do not recover typing. Safari and Mac
Virtual Display remain usable. Explicit key-window/becomeFirstResponder activation
reported true/true without fixing typing. Mixed versus progressive immersion and
hand-only versus hand+accessory input permissions produced the same failure.

Device log reproduction (device local time):

- 21:31:43: UIKit reports hardwareKeyboardMode=1 and text insertion activity.
- 21:32:31: Linux host reports CONNECTED.
- 21:32:33: RealityKeyboard shouldResume changes from 1 to 0 as immersiveSceneType
  changes to 2; sidecarRunning remains 1.
- 21:32:41: RemoteTextInput reports `perform input operation requires a valid sessionID`
  with Keyboard modality.
- 21:32:43 and 21:32:45: RealityKeyboard explicitly reports hardware key events received
  from the Sidecar MacBook keyboard.

This supports an invalid text-input-session hypothesis; it does not prove which
component invalidates the session or establish an Apple/NVIDIA root cause.

Private raw/filtered capture and transition subset remain on mi2 under
/Users/marc/temp/cloudxrapp/live-keyboard-filtered.log and
keyboard-immersion-transition.ndjson. They are not committed here.

Next isolated experiment: explicitly resign and re-enter native text focus using the
Renew input session button, instead of requesting focus on an already focused field.
No success claimed until tested on device.

## Subsequent device results

Explicit resign/re-enter focus did not restore laptop typing. The user can insert
text by selecting a suggestion from the system helper palette. Therefore insertion
into the field works through at least one system input path; an invalid-session log
warning alone cannot establish the cause of missing hardware keystrokes.

Next control: a separate empty RealityView ImmersiveSpace, without connecting or
starting FoveatedStreaming/CloudXR. Test keyboard before and after opening it in a
fresh app process. This separates generic immersion/Sidecar behavior from the stream.

Local empty RealityView immersive space works with laptop typing (user-confirmed).
This rules out ordinary progressive immersion as sufficient to trigger the issue in
this app. The next build uses empty immersivePresentationBehaviors to connect before
presentation, with an explicit Show connected stream button, following the public
FoveatedStreaming API. This tests connection setup separately from stream-space entry.

Manual presentation result: typing fails after CloudXR connects, before pressing Show
connected stream. Presenting the streaming immersive space is not required. Around
connection startup, UIKit logs StreamReceiverDisplay requesting focus with
`stealKeyboard: YES`. This is a lead, not proof that focus remains captured. The next
workaround requests editor UIWindowScene activation through UIApplication's public
activateSceneSession API, rather than only local UIWindow.makeKey().

A directly paired Bluetooth keyboard also stops typing on connection before stream
presentation (user-confirmed). This rules out Mac forwarding as a necessary cause.
Explicit editor-scene activation did not restore typing. Next: explicitly enable a
GCKeyboard probe in the native test window and compare raw transition counts before
and after connection. No key content is recorded; an existing app handler is chained.

Final raw-input result: GCKeyboard transitions stop on connection along with ordinary
typing. The client cannot forward keys it no longer receives. The opaque data channel
is operational (preset Hello! round trip); this does not solve event capture. A separate
Mac input source could bypass the failing client input path, but is not implemented.
