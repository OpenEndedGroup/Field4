# Make ComputeMesh unassigned-binding error say what happened

- **Handoff:** `4e884d7b-40d2-493e-8032-46f1a15d05e4` (project)
- **Filed from:** https://openendedgroup.com/field4/gpu_state/
- **Filed by:** human, 2026-10-15 13:48
- **Close with:** `python tools/docs_notes.py handoff-close 4e884d7b "what happened"`

Rule 25 sharp edge on `public/docs/gpu_state.md`, "Bindings are lambdas":

> A binding set inside the frame loop lands a frame late, and the diagnostic for the gap is easy to misread: a dispatch in that window throws `MissingRefException("risk of GPU null ref …")` naming the member, which reads like a missing buffer and is really an assignment that has not happened yet.

Fix in `afield/graphics/render/ComputeMesh.kt`: either apply a binding assigned inside `loop {}` before the dispatch of that frame, or make the `MissingRefException` message say "member X has not been assigned yet (assigned inside a loop? set bindings once at top level)". Delete the misreading half of the paragraph in the same change.
