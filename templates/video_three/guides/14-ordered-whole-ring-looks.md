# video_three — a carousel with whole-ring rule keyframes

The renderer and single-decoder history come from `video_two`. Video surfaces and the Field
pane add light, with depth testing and depth writes disabled. Opacity scales emitted light;
black contributes nothing and overlapping screens add their linear RGB values. One shared look now holds a rule
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
Recall crossfades outputs with optional per-screen staggering; Restore shared speed controls
the one decoder and starts enabled for the vanilla-2x baseline. Material-update cadence and
the complete exposure-meter configuration are saved too. Those settings switch as a block
when a look transition finishes. A held transition is a saveable look. The resolved table shows each
lane's eight actual values. Expressions can use inherited props; vanished local captures cannot
be restored. Rules run on Apply/recall, not continuously when idle.

The whole-ring box has one compact **ring controls** panel. Choose Exposure, Tone, Colour,
Cadence or Shutter in its Section menu. Sliders and toggles change all eight screens together;
their handles follow screen 1 when the current rule varies across the ring. Units sit beside the
values, and shutter durations advance in whole headset frames.

**Edit per-screen rule** opens one source editor. Choose a parameter there; switching parameters
retains each unfinished draft. **Apply this rule** applies the selected draft; **Apply edited rules**
validates all edited drafts together, preserving other current rules. **Discard drafts** returns to
the current authored sources. Unapplied drafts are not included when storing a look.
Invalid drafts retain the last working scene. For code, use `sourceBlock { screenNo: Int -> ... }`;
for a source text field, enter the lambda itself, including braces and the parameter type.

Choose a saved look from the menu, then Recall look; use Save as and Store look to keep a new
name or version. Transition duration, per-screen staggering and Restore shared speed are in the
separate **look transitions** panel. The version browser and resolved-value table start closed.
Opening or switching a panel only changes the interface; it never applies a parameter.

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

Geometry presets are separate from colour/exposure looks; see [Ring arrangements](15-ring-arrangements.md).

## Previewing a speed-first transition

In the whole-ring box, open **transition preview**. Select From and To looks and set Fade duration.
**Load start** cuts to the From look, including its speed, so you can watch the starting state.
**Preview transition** returns to From, immediately selects the To speed, then fades the numeric
scene lanes over the chosen duration. Repeat to audition the same change. Named keys are not
overwritten, and the movie playhead, accumulation history and geometry continue.

The initial pair is copper-1x → vanilla-2x-warm over 8 seconds. Playback changes from 1x to 2x
at the start, while accumulation mix falls from 1 to 0.15 and chroma transfer rises from 0 to 0.90.
Both endpoints use the same 24 fps material cadence and the same per-screen exposure tracker
settings. Meter configuration is a discrete block at the end for pairs whose tracker settings differ.
**Hold transition** in the main look controls freezes the current interpolated appearance;
playback continues at the destination speed. The ordinary recall duration and stagger settings
are retained; preview uses a simultaneous fade across the screens.
