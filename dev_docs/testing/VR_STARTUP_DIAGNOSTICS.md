# Diagnose a black headset before restarting

Use a fresh JVM after changing renderer/device initialization. Confirm the actual checkout,
workspace, selected Electron tab, WiVRn connection and GPU owners first.

## Evidence of rendering

`OpenXRHmdTarget.xrFrameCounter` counts XR frame cycles. It does **not** prove that the scene
was submitted. `Renderer.submittedSceneFrames` counts successful primary graphics submissions;
`Renderer.lastRenderFailure` retains the recording/submission exception until a scene submits
successfully. Compare the scene count across time and inspect an actual headset-mirror capture.
A nonzero historic counter, a successful box setup, or a running job alone is insufficient.

Native capture stall errors include these counters, desktop presentation state and the last
render failure. Diagnose the first exception before trying another capture or restarting.

- XR ticks advancing, scene count static: check render-loop ownership, desktop acquisition,
  presentation flags and the last exception.
- Both static: inspect the owning render thread; distinguish XR wait, queue dispatch and fence wait.
- Scene submissions advancing, image black: inspect target contents, visibility, bindings and the
  mirror's target. Bring the actual preview window onto the current desktop before judging it.
- A missing shader reference: bind it once during setup, before validation; a callback that refreshes
  it later does not necessarily initialize the first draw.
- Private runtime missing a type/include: inspect its build-source manifest rather than debugging
  the downstream shader error.

Use a small known-working document such as `video_one` early to separate the common OpenXR path
from a large document's resource setup. A driver reboot is not the first remedy for a local error.

## Recovery invariants

Recording must not reset a submission fence. Both compute and graphics recording must succeed
before either submission begins. Reset each fence immediately before its corresponding queue
submission, and check the Vulkan return code.

Retain a successfully acquired desktop image across recording failure: its acquisition semaphore
must be consumed before another acquire. If presentation is toggled while an image is acquired,
finish that frame in its original presentation mode and apply the toggle to the following frame.

`four.renderer.recording-recovery` injects graphics and compute recording failures in desktop and
offscreen modes, recovers, and repeatedly toggles presentation under Vulkan validation.
SPIR-V module creation is shared across every shader stage; shader-sized bytecode never belongs
on LWJGL's scratch stack (`ShaderModuleCodeTest`). Increasing the stack is not the repair.
