# Box snapping: code ownership and verification

The user workflow is documented in `public/docs/snapping.md`. The complete algorithmic contract —
Caps Lock state, candidate and apply thresholds, axis independence, resize behavior, guides, and
complexity — is colocated on `BoxSnappingSystem` in
`src/jsMain/kotlin/afield/BoxSnapping.kt`. Drag and resize integration lives in
`src/jsMain/kotlin/afield/Main.kt`.

There is no named snapping case in `tools/run_ui_regressions.py`; the public video is a checked-in
asset rather than a docs-runner capture. Treat both as verification gaps when changing this code.

Manual invariants:

1. With snapping off (Caps Lock off and the Toggle Box Snapping command not on), drag and resize coordinates are unmodified and guides are cleared.
2. With snapping on, drag chooses at most one closest target per axis; resize may show multiple
   in-range edge guides.
3. The snapped frame, not the unsnapped proposal, is sent through the ordinary server update path.
4. Hidden/tombstoned/non-renderable boxes are not useful snap references.
5. Even spacing pairs only neighbours in the dragged box's row (or column): a box in another
   row never comes between them.
6. Guides remain non-interactive, follow the overlay coordinate system, and disappear on gesture end.

If thresholds or SVG styling change, update the KDoc and rebuild `snapping.md` with
`FIELD_DOCS_RUNNER_MODE=always` (its `capture="ui"` fence drives the real UI); do not preserve numeric details in a second prose specification here.
