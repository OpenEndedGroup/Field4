# Make KeyframeScope.interpolate keep a lane's numeric type

- **Handoff:** `e5a3bf7d-a725-49c0-8c63-cfedb324a3ff` (project)
- **Filed from:** https://openendedgroup.com/field4/keyframe_autosweep/
- **Filed by:** human, 2026-10-15 13:44
- **Close with:** `python tools/docs_notes.py handoff-close e5a3bf7d "what happened"`

`public/docs/keyframe_autosweep.md` (aside "Picking the three lane policies in `SweepConfig`") says:

> **Numeric type.** `interpolate` returns `Double` unless *both* sides were `Float`, so an `Int` lane comes back as a `Double` an `Int` uniform will refuse. `preserveNumericKind` rounds each lane back to the anchor's type ...

`KeyframeScope.interpolate` silently changes a lane's numeric kind, and `KeyframeSweep` carries a policy flag to undo it. That is a sharp edge in `interpolate` (`src/jvmMain/kotlin/afield/animation/keyframes/keyframer.kt`).

Fix: make `interpolate` return the lane's own numeric kind (round Int/Long, keep Float when either side is Float), so `preserveNumericKind` becomes the default or goes away; then delete the **Numeric type** paragraph from the aside in the same change. Decide first whether any caller relies on the Double result.
