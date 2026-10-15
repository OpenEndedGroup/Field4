# Verify Faust on mobile controllers, Linux and Windows

- **Handoff:** `ef0201ef-a245-4022-9084-1d699608c594` (project)
- **Filed from:** https://openendedgroup.com/field4/faust/
- **Filed by:** human, 2026-10-15 13:46
- **Close with:** `python tools/docs_notes.py handoff-close ef0201ef "what happened"`

Rule 25 sharp edges on public/docs/pat_spatial.md ("Two mistakes to avoid" and "Two things to know before using a real headset"):

> Call `awaitReady()` before handing a launch to an audio engine yourself. It returns once Steam Audio is up, the prescanned engines have booted and the ring has filled.

> With `OpenXRHmdTarget`, update the pose from the frame rather than a timer: construct the listener with `hz = 0.0` and call `pump()` inside the frame callback. Read on a timer, the pose can mix the position from one frame with the orientation from the next.

Fix in code: (1) have the launch's `render` wait for/handle readiness itself (or have every engine hand-off call `awaitReady()`), so forgetting it cannot cause underruns; (2) make `asHeadPoseSource()` on an `OpenXRHmdTarget` default to frame-pumped updates (or read position+orientation atomically), so the timer default cannot tear. Also consider warning once when a pattern carries `pan` under `patSpatial`, since it is silently ignored. Delete the corresponding bullets in the same change.
