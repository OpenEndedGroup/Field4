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
