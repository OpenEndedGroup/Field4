# History view: This Box (2026-10-30)

`HistoryManager.boxStory(doc, uid, property)` → `history-box-story` RPC → the sidebar's **This Box**
list in `src/commonMain/resources/history.html`. Restore from a row sends the existing
`cherry-pick-history-version` with one `{uid, [property]}` selection and `includeTopology=false`.

Lessons from the first live drive:
- Wire types with defaults need `@EncodeDefault`: Field's JSON omits defaults, and the panel read the
  missing `exists` as false, listing every version as absent. `HistoryBoxStoryTest.defaultsAreOnTheWire` pins it.
- A transcript `edit` without `from` inserts `to` rather than replacing; give `from` the full old text.
- Backdoor `screenshot-window` clears the canvas selection (and so empties This Box) unless
  `--preserve-selection`; pass `viewport 1440x900` first or the window is 2200x2800.
- A box created by a transcript is saved once with empty code before its properties land; that
  version shows as `empty` and has no Restore button (restoring it left the sidecar stale).
- Docs captures refuse to run on battery below 80% (`build/docs-environment.json`).
