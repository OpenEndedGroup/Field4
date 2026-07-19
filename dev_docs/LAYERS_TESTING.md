# Layer-system verification

The user-facing layer palette and shortcuts are documented in `public/docs/funFeatures.md`. Runtime
semantics are colocated with `setVisibleLayers`, `setActiveLayer`, and `isBoxVisible` in
`src/jsMain/kotlin/afield/Main.kt`, the matching handlers in
`src/jvmMain/kotlin/afield/Server.kt`, and the `Box.layers` / `Box.layerFrames` properties in
`src/jvmMain/kotlin/afield/Boxes.kt`.

There is currently no named layer case in `tools/run_ui_regressions.py`; that is a coverage gap.
Until one is added, verify in a disposable document:

1. New boxes without an explicit layer are visible in `default`.
2. Visibility is the intersection of the current visible set and a box's comma-separated
   membership; empty frames, server-hidden boxes, and tombstones remain hidden regardless of layer.
3. Leaving an active layer snapshots frames only for boxes that belong to it. Entering another
   layer applies its saved overrides. Deactivating with `null` does not restore an original frame.
4. Animated and immediate changes converge to the same box, output-area, and connection visibility.
5. `layers` and `layerFrames` survive save/reload. The session's current visible and active choices
   are runtime state, not document properties.
6. `showLayers()` opens the implemented palette; **Show all layers**, Alt-], and Alt-[ recover and
   navigate visibility as described publicly.

Use the normal browser regression runner for surrounding selection/output/connection behavior, and
inspect the serialized `metadata.edn` when persistence fails.
