# Live Field frontend in OpenXR

The public walkthrough and runnable starter are `public/docs/field_vr_ide.md` and
`dev_docs/agents/transcripts/field_vr_ide.yaml`. The starter uses a desktop companion first;
`useHeadset = true` in its renderer box enables the existing OpenXR runtime on a fresh Field launch.

`FieldVrWorkspace` composes the existing browser texture, runtime-sized stereo target and browser
panel. The browser upload/input hook precedes stereo packing, and each eye's MVP/cursor uniforms
are set at the actual module draw boundary. Browser pixels are sampled without text antialiasing
postprocessing. Chromium's viewport is explicitly set after document readiness: on the tested
Wayland desktop a requested 2560 × 1600 hidden window initially produced a different extent,
causing the fixed-size resident texture to reject every frame. Setting metrics before initial
navigation stalled Chromium startup; the post-readiness configuration passed the browser suite
and produced actual full-size Vulkan pixels on a fresh producer.

`PuppetBrowserVrInput` arbitrates the browser's single mouse between hands, with capture through
off-panel motion, cancellation on lost tracking and a pinch hysteresis fallback. OpenXR aim rays
use local -Z. `PuppetBrowserVrGrab` stores `inverse(controllerPose) * panelModel` on grip-down;
each held frame applies `controllerPose * offset`. It preserves the offset, including rotation,
and leaves the last world transform on release or tracking loss. Grabbing suppresses browser
clicks and does not reinterpret a held trigger as a fresh click afterwards.

The native companion shows the same browser front-on. Its mouse UVs also drive the VR cursor.
Physical pointer activity takes ownership; moving or pressing a controller can resume the ray.
A physical mouse drag resists controller hover. Native keyboard focus follows the desktop's
focused window, while controller clicks choose the browser's focused control. Controller input
does not steal desktop focus; the toolbar's Real keyboard action explicitly requests it. Focus
loss releases held keys/buttons. The virtual keyboard prevents pointer-down focus changes and
uses Chromium's normal input path, including whole-box launch and line/selection execution.

Validation on Linux/NVIDIA, 2026-10-31:

- 29 focused JVM/browser tests passed: eight VR input/grab tests, three stereo panel orientation
  tests, five protocol tests and thirteen real Chromium control tests. The virtual-keyboard test
  uses dispatched mouse presses, checks retained text-field focus, insertion and Backspace.
- The three starter boxes compiled; materialization, gallery index and layout guards passed.
- A real Vulkan companion and both packed eye images were captured and inspected. The actual
  producer reported a 2560 × 1600 viewport with device scale factor 1.
- A virtual-keyboard pointer click typed into the real Monaco Playground model, and the edited
  source synchronized to the same Field document. Clicking Run box executed it and produced its
  ordinary output. These checks used the browser input bridge, not a model-value replacement.
- The docs runner regenerated the committed native companion capture from the canonical starter.
- Live headset grip/trigger/pinch and physical keyboard/mouse checks still require a connected
  headset and user feedback; desktop/stereo evidence alone does not establish those outcomes.

This checkout's Linux browser transport remains the explicit CPU bitmap fallback. Its default
browser rate is 30 Hz; `FIELD_BROWSER_TEXTURE_FRAME_RATE=60` can be set before launching Field to
try a faster UI on a sufficiently fast machine. Eye rendering and head tracking have their own
runtime rate. No experimental Electron binary or global input interception is installed here.


## Dedicated WiVRn input (2026-10-31)

`WiVRnDeviceReader` selects only USB bus 0003/vendor 4711 and the upstream names/products:
WiVRn Keyboard/0840 and WiVRn Mouse/0839. It verifies identity on the opened descriptor and
requires EVIOCGRAB. `LinuxEvdevSystem` uses read-only/nonblocking/CLOEXEC evdev descriptors through
JDK FFM. Overflow or removal closes the descriptor, cancels held input and re-queries initial key
state on reconnect. Unrelated PC input nodes are never opened. The udev helper excludes these
virtual devices from libinput even between Field sessions; it does not add input-group membership.
The rule was syntax checked with `udevadm verify` (success; systemd warns that non-system-user
node ownership is deprecated). No administrator rule has been installed from this agent session.

`WiVRnBrowserInput` routes mouse deltas in CSS page coordinates with controller/mouse ownership,
buttons, drag capture and wheel events. Keyboard translation uses libxkbcommon state and Compose
with layout/variant/options from FIELD_VR_KEYBOARD_*. Text travels with Chromium keydown so an
editor's preventDefault still works; Ctrl/Alt/Meta shortcuts omit printable text. This is a
separate input source from native desktop callbacks and needs no desktop focus request.

The live collaboration probe exposed Monaco focusing its textarea while a remote model update
restored selection. `updateEditorPreservingOtherControlFocus` keeps the receiving client's
previously focused control focused, while retaining normal text-editor focus when it was active.

`tools/setup_wivrn_input.py` defaults to a dry run. Its administrator install scopes the device
rule to the intended user, updates only WiVRn's HID-forwarding setting, and can add an unused private
Tailscale Serve port. The running agent cannot sudo noninteractively or change Serve configuration.
Existing public Funnel 443 (proxy to localhost:8792) was preserved. The intended private port is
8443; it is not installed yet. The input rule and HID-forwarding config are also pending that
one-time administrator step. Physical Quest/USB/Bluetooth behavior remains unverified until the
headset is available; a connected runtime, packet replay or local browser cannot establish it.

Primary implementation references:
- https://github.com/WiVRn/WiVRn/blob/v26.6.2/server/driver/wivrn_uinput.cpp
- https://github.com/WiVRn/WiVRn/blob/v26.6.2/client/android/hid.cpp
- https://wayland.freedesktop.org/libinput/doc/latest/device-configuration-via-udev.html
- https://www.kernel.org/doc/html/latest/input/event-codes.html

Software verification:
- 41 focused input/protocol/real-Chromium tests passed, including real clicks, scrolling,
  Ctrl+A, Shift text, multiline entry, AltGr/dead keys, device identity/grab rejection and
  overflow/disconnect reset. The native libc bindings also opened/closed /dev/null and rejected
  its non-input ioctls; this is not positive hardware input proof.
- The opt-in WiVRnFieldCollaborationTest passed against an isolated document/box on Field 9092.
  Two real Chromium clients connected at localhost:19092 and the node's Tailscale address
  100.110.104.26:9443. A temporary user-owned TCP relay carried the latter connection to Field's
  TLS socket. Replayed evdev keys edited real Monaco; a fresh marker synchronized to the second
  client while its independent input retained focus and received no text. A real pointer click
  and text insertion in the remote editor synchronized back. No model-value replacement was used.
  This is same-node/two-origin coverage, not a second physical remote machine or installed Serve
  endpoint. Test-only CDP certificate-error acceptance handled Field's self-signed certificate.
- Four Python setup-helper tests passed, covering exact device selection and preservation of an
  existing public Tailscale listener while adding/refusing private-port configuration.

To repeat the live proof, create a dedicated document and box through the backdoor, then set
FIELD_VR_COLLAB_LOCAL_URL, FIELD_VR_COLLAB_REMOTE_URL and FIELD_VR_COLLAB_BOX before running
`./gradlew jvmTest --tests afield.media.browser.WiVRnFieldCollaborationTest -x personalJar`.
With those variables absent the explicitly opt-in live-server test skips; the other input and
Chromium fixture tests remain independent of any live Field server or physical headset.

The documentation build completed successfully, retaining the earlier real native companion
capture and refreshing this starter's web publication. Live proof JSON is retained locally in
`build/vr-input-validation/collaboration.json`; the temporary 9443 relay was stopped afterwards.
The updated application was restarted on 9092 and its actual Vulkan companion was inspected
at `/tmp/field-vr-input-ready.png`, including the new input-status footer. The desktop preview
is running; no headset was connected. Existing Playground source edits were preserved.
