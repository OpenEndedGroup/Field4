# Control presets, morph, record and MIDI learn — dev note

User page: `public/docs/visualizers.md` (Controls section). Source: `src/jvmMain/kotlin/afield/ui/output/ControlPresets.kt`,
tests `src/jvmTest/kotlin/afield/ui/output/ControlPresetsTest.kt`, demo transcript
`dev_docs/agents/transcripts/controls_presets_morph_midi.yaml`.

- Addressing: a *preset key* is `type/label#ordinal`, derived from the control-group cache key
  (`controlGroupControlsWithKeys` in `outputs.kt`). Renaming a label orphans stored presets/bindings.
- Persistence: `_controlPresets` ({channel: {preset: {key: value-map}}}) and `_controlMidi`
  ({channel: {key: {channel, cc}}}), both in `Box.ioPersistentProperties`.
- Applying values (`applyControlValues`): recall goes through `updateControllerControlValue`
  (durable, notifies gesture listeners); morph/knob/playback go through the programmatic path
  (in-memory cache, no operation log). Both broadcast `control-value-update` and call the callback.
- Gesture listeners hang off `updateControllerControlValue`, which every browser and tablet
  change goes through; button and meter updates do not, by design.
- The record control is a *button*, not a checkbox: a checkbox's cached state is restored on rerun
  and re-armed the take.

Known gaps (2026-10-23):
- Values applied *during* the run that builds the group (e.g. `midi.feed` in the same box) reach an
  open page via broadcast, but a page that loads later replays the group's original HTML, so it
  shows the pre-feed values until the next change. The docs MIDI example is static for that reason.
- The preset strip is built from ordinary controls, so each button renders as a full-width row
  with a "HOLD" face; a compact dedicated strip in `sliders.js` would read better.
- No undo for control changes; click-to-learn has unit coverage but no browser-driven test.
