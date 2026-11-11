# Global looks and keyframes

Box **23 · Global looks / keyframes** connects the reading room's global controls to
Field's native `KeyframeScope`. The controls remain in their usual boxes; this box
owns their declarations and the shared scope.

In **global looks**, enter a preset name and press **store**. Change some controls,
then press **recall** to return to that preset. The **preset morph** slider blends
through saved presets in name order. With names `A` and `B`, its ends are those two
looks. **saved global looks** is Field's keyframe browser: inspect values, apply a
look, or recover a previous version after storing over the same name.

The initial live look is kept as **starting-look**. Keys and version histories live
in the document's `files/global-keys/` directory. Store another named look to retain
new work; opening or rerunning the controls does not automatically recall a key.

## What a look contains

- The complete compositor colour settings, exposure, green selector, blur cascade,
  background, shadow/highlight tones, final contrast, AA and display dither.
- Text blur contrast and gain, frost gamma and black floor, and each frame's frost
  enable, opacity, radius, brightness and local black level. Frame margins, overstroke
  fraction/distance and each frustum’s fixed/automatic aspect are captured too.
- Layout forces, damping, text scale, stroke/marker size and dot visibility for each
  example. Aggregate sliders still edit every example; saved keys also retain any
  deliberately different values authored for individual examples or panels.
- Body interaction forces, reach, falloff, radii, hand/head inclusion and debug views.
- Reader-facing behaviour, its turning half-life, reading distance, minimum stroke
  and point sizes, walking enable/speed, and desktop preview choices.
- Staged text-cache quality. These choices step at the next preset and do not allocate
  GPU images during a morph. Press **Apply text blur quality** to rebuild caches.

A look does not rewind physics, select an example, seek the score, recenter the room
or change the headset pose. Those actions remain separate from the rendering look.

## From code

The shared root property is `readingKeyframes`. Connect a helper below box 23 so its
preamble includes that typed property. Capture and loading only read; invoking a key
applies it. Both the model and its visible controls are updated.

```kotlin
val before = root.readingKeyframes.capture()
before.save("global-keys/quiet".path, "A soft reading light")

val quiet = root.readingKeyframes.loadKey("global-keys/quiet".path)
quiet()

val other = root.readingKeyframes.loadKey("global-keys/bright".path)
root.readingKeyframes.interpolate(quiet, other, .4)()
```

Getters resolve inherited `drawing` and `poem` properties when used, rather than
retaining old renderer objects. After rebuilding a graph, launch Body, Navigation
and Global looks again; recall the desired key after the new models exist. The
`text_one_launch` macro already follows that order. Re-running just Global looks
refreshes registrations and panels from the running models without changing them.

When adding a global control, use `keyframeSlider`, `keyframeCheckbox` or
`keyframeColorPicker` in this box. Give it a stable, descriptive lane name and keep
`restoreValues = false`: the live model supplies its value, including after recall.
