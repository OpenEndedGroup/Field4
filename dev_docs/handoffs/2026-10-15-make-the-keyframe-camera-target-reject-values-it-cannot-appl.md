# Make the keyframe camera target reject values it cannot apply

- **Handoff:** `9de9a0d2-5b88-4102-bfca-8697c38949d3` (project)
- **Filed from:** https://openendedgroup.com/field4/keyframer_examples/
- **Filed by:** human, 2026-10-15 13:44
- **Close with:** `python tools/docs_notes.py handoff-close 9de9a0d2 "what happened"`
- **Code:** `src/jvmMain/kotlin/afield/animation/keyframes/keyframer.kt:994#camera`

Rule 25 sharp edge on keyframer_examples.md, aside "The camera target itself needs a renderer":

> the target accepts a `CameraPose` or a bare `DualQuaternionf` and **silently ignores any other type** (`keyframer.kt` `fun camera(...)` dispatches on a `when` with no `else`). A `Vector3f` "camera position" key will appear to apply and do nothing.

Fix in `afield/animation/keyframes/keyframer.kt` `KeyframeScope.camera`: add an `else` branch that throws or logs on `error,keyframer`. Delete that sentence in the same change.
