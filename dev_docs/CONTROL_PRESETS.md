# Control presets, morph, record and MIDI learn — dev note

User page: `public/docs/visualizers.md` (Controls section). Source: `src/jvmMain/kotlin/afield/ui/output/ControlPresets.kt`,
tests `src/jvmTest/kotlin/afield/ui/output/ControlPresetsTest.kt`, demo transcript
`dev_docs/agents/transcripts/controls_presets_morph_midi.yaml`.

- Presets are keyframer keys. `presetBar(scope, directory)` is UI over a `KeyframeScope`
  (`afield/animation/keyframes/keyframer.kt`): store = `scope.capture().save(dir/name)` (version
  history, readable by `KeyframeCollection` / `keyframeBrowser`), recall = `scope.loadKey(...)()`,
  morph = `scope.interpolateAlong(keys, t)`. Controls take part by being declared as delegated
  locals (`var size by keys.slider(...)`, `KeyframeControls.kt`; `keys.controls()` builds them) or
  with the explicit `keyframe*` helpers the delegates call, whose targets move the visible control
  (`ControlValueHandle.set`). A rerun restores each delegate when it is declared, from the same
  control-group cache `controlGroup` restores from (`lastControlGroupValue` in outputs.kt: the
  running box from `Box.context`, the channel found by matching type + label + ordinal across the
  box's cached channels; ambiguous across two channels falls back to restoring in `controls()`).
  No second store. A rerun restore does not call `onChange`: `fromControl` ignores a value equal to
  the local's, which is what `controls()` hands back after the declaration restored it.
  Default directory: `Box.presetDirectory(channel)` = `%document/keys/<box id>/<channel>`.
  keyframer.kt additions for this: `savedKeyNames(dir)`, `interpolateAlong(keys, t)`.
- The bar lays its name field and buttons in wrapping rows (`ControlGroupBuilder.inlineRow`,
  `.field-control-row` in `field.css`; buttons show their label on the hit face).
- Recording and MIDI address a control by its *control key* `type/label#ordinal`, derived from
  the control-group cache key (`controlGroupControlsWithKeys` in `outputs.kt`). Renaming a label
  orphans its MIDI binding. `_controlMidi` ({channel: {key: {channel, cc}}}) is in
  `Box.ioPersistentProperties`.
- Knob and playback changes go through `applyControlChanges` (programmatic path: in-memory cache,
  no operation log, broadcast, callback). Gesture listeners hang off `updateControllerControlValue`,
  which every browser and tablet change goes through; button and meter updates do not.
- The record control is a *button*, not a checkbox: a checkbox's cached state is restored on rerun
  and re-armed the take.

Known gaps (2026-10-23):
- Values applied *during* the run that builds the group (e.g. `midi.feed` in the same box) reach an
  open page via broadcast, but a page that loads later replays the group's original HTML, so it
  shows the pre-feed values until the next change. The docs MIDI example is static for that reason.
- The bar's key buttons are built when the group is built; a key stored since shows as a button
  after the next run (it joins the morph at once).
- No undo for control changes; click-to-learn has unit coverage but no browser-driven test.
