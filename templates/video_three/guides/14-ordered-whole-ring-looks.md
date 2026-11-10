# video_three — a carousel with whole-ring rule keyframes

The renderer and single-decoder history come from `video_two`. One shared look now holds a rule
for each exposure, tone, cooling, opacity and shutter parameter over the entire ordered ring.
A rule is a number or a typed source-backed `(screenNo: Int) -> Double` expression. Indices are
zero-based even though the controls label screens 1–8.

```kotlin
val cooling = sourceBlock { screenNo: Int -> 0.1 + 0.04 * screenNo }
val key = groupLooks.scope.capture()
key["input.shadowCooling"] = cooling
groupLooks.group.apply(key)
groupLooks.store("cooling wave")
```

Apply validates all indices before changing screens. Store saves rules, not their eight samples.
Recall crossfades outputs with optional per-screen staggering; Restore shared speed separately
controls the one decoder. A held transition is a saveable look. The resolved table shows each
lane's eight actual values. Expressions can use inherited props; vanished local captures cannot
be restored. Rules run on Apply/recall, not continuously when idle.

Each shared slider replaces its lane with a constant. Each multiline source field is a draft: use Apply all
rule drafts to compile and validate them together. Refresh drafts after a recall or a code edit.
Invalid drafts retain the last working scene. For code, use `sourceBlock { screenNo: Int -> ... }`;
for a source text field, enter the lambda itself, including braces and the parameter type.

Per-screen shader boxes retain manual inspector sliders, layout controls and editable GLSL.
Import manual screen edits replaces the authored rules with current renderer values: identical
values become constants and differences become lookup expressions. Import after manual sliders,
style buttons or scores before storing that edited appearance as a shared look.

The working rules are saved as a native key in `%document/keys/ordered-looks/working rules` after
a second without applied changes and on normal stop. Explicitly store a named look to keep a durable milestone.
Global exposure-meter configuration and geometry continue to use their existing document settings.
Keys retain parameters, not accumulation pixels, media frames or filter histories.

Build transcripts, then replay create and launch through Field:

```sh
python templates/video_three/build_transcript.py --output /tmp/video-three --headset \
  --video /absolute/path/to/stereo-sbs.mp4
```

Omit `--headset` for a desktop test. `--no-browser` suppresses the VR editor surface.
`--shader-document /path/to/video_two` uses that document's real stage sidecars. The generator
retains stable renderer box IDs and runs motion before the rule pool so the shutter clock exists.
It does not write the document metadata directly. Save through Field after creating the graph.

Core design in the Field repository: `dev_docs/animation/ORDERED_GROUP_KEYFRAMES.md`.
The numeric API also works for lights, instanced geometry, voices or any ordered collection with a
single validated commit callback.

Parameter and inspector panels use `controlGroup(..., restoreValues = false)`: their current model
supplies initial values, so cached widget values cannot flatten functions or replace a restored look
on rerun. The ordinary control-group cache policy remains available elsewhere.
