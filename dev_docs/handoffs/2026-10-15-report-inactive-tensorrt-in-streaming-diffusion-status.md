# Report inactive TensorRT in streaming diffusion status

- **Handoff:** `4ab45937-13e5-41c0-9d0f-2a1a4d4de582` (project)
- **Filed from:** https://openendedgroup.com/field4/streaming_diffusion/
- **Filed by:** human, 2026-10-15 13:49
- **Close with:** `python tools/docs_notes.py handoff-close 4ab45937 "what happened"`

Rule 25 sharp edge on `public/docs/streaming_diffusion.md`, "Install the backend":

> `useTensorRT = true` is the default; ... Check `view.session.capabilities.optBoolean("tensorrt_active")` to see whether an engine actually executed; requesting TensorRT does not establish that it is active.

Fix in code (src/jvmMain/kotlin/afield/media/video/diffusion/StreamDiffusionSession.kt / StreamDiffusionView.kt and the worker under optional/streamdiffusion/): when TensorRT is requested but not active, report it in `session.status` (a visible warning in the box's availability/status output), and expose a typed property (e.g. `session.tensorRtActive`) instead of a JSON capability lookup. Then delete the "requesting TensorRT does not establish..." sentence in the same change.
