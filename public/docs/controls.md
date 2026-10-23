# Controls: presets, morphing, recording and MIDI

A `controlGroup` is the panel of sliders, pads, colour pickers, checkboxes and text fields a box shows in its output. This page is about what you can do with such a panel once it exists: store its settings under a name and bring them back, blend between stored settings with one slider, record a slider's movement into a keyframe curve, and turn a hardware knob to move a control.

None of it needs the controls declared differently. Any slider, `vec2Control`, `colorPicker`, `checkbox` or `text` in a group can be stored, morphed, recorded and bound to a knob.

## Presets and morphing

Add `presetBar()` as the last line of a group. It gives the group a name field with **store preset** and **recall preset** buttons, a button for every preset already saved, and a **preset morph** slider. Here two presets, `calm` and `storm`, are written in code the first time the box runs, before the group is built, so the bar has a button for each and something to morph between straight away:

```diffstatic_runner {data-lang=kotlin screenshot="assets/controls/preset_bar.png" capture="box" open-outputs="true" wait="2.0" w="700" embed=true name="controls-preset-bar" alt="A control group: a size slider at 0.40, an offset pad, a blue tint colour picker, a ring checkbox, a preset name field reading storm, store preset and recall preset buttons, buttons named calm and storm, a preset morph slider at 0, a rec size button and a MIDI learn button. Below it a dark square holding a blue circle"}
:::static
var size = 0.4
var tint = Vector4f(0.2f, 0.6f, 1f, 1f)
var ring = false
var offset = Vector2f(0f, 0f)

fun draw() {
    val r = (20 + size * 60).toInt()
    val cx = 110 + (offset.x * 60).toInt()
    val cy = 90 - (offset.y * 50).toInt()
    val fill = "rgba(${(tint.x * 255).toInt()},${(tint.y * 255).toInt()},${(tint.z * 255).toInt()},${tint.w})"
    val stroke = if (ring) "stroke='white' stroke-width='6'" else ""
    display("main", "<svg width='220' height='180' style='background:#222'><circle cx='$cx' cy='$cy' r='$r' fill='$fill' $stroke/></svg>")
}

// Two presets to start from, written once; after that the stored ones are used.
val presets = controlPresets("look")
if (presets.names.isEmpty()) {
    presets.put("calm", mapOf(
        "slider/size#0" to ControlValue.Num(0.25),
        "vec2/offset#0" to ControlValue.Vec(-0.5, 0.3),
        "color/tint#0" to ControlValue.Rgba(0.2, 0.6, 1.0, 1.0),
        "checkbox/ring#0" to ControlValue.Flag(false)))
    presets.put("storm", mapOf(
        "slider/size#0" to ControlValue.Num(0.95),
        "vec2/offset#0" to ControlValue.Vec(0.6, -0.4),
        "color/tint#0" to ControlValue.Rgba(1.0, 0.25, 0.1, 1.0),
        "checkbox/ring#0" to ControlValue.Flag(true)))
}

controlGroup("look") {
    slider("size", 0.0, 1.0, initialValue = size) { size = it; draw() }
    vec2Control("offset", offset) { offset = it; draw() }
    colorPicker("tint", tint) { tint = it; draw() }
    checkbox("ring", ring) { ring = it; draw() }
    presetBar()
    recordToggle("size")
    midiLearnButton()
}

draw()
println("presets: ${presets.names}")
```

Type a name into **preset** and press **store preset** to save the panel as it is now; press a preset's own button, or type its name and press **recall preset**, to bring it back. Recalling moves the controls on screen and runs their callbacks, so `size`, `tint` and the rest change exactly as they would if you had dragged each control there yourself.

**preset morph** blends across every saved preset, in the order they were first stored: at 0 the panel is the first preset, at 1 the last, and in between it moves through the others in turn. Numbers, pads and colours move smoothly; a checkbox or a text field has no in-between, so it switches at the halfway point between two presets. With `calm` and `storm` above, the morph at 0.5 gives a half-size, purple circle, with the ring already on.

Presets are saved with the box, so they survive a rerun and closing Field. A preset only stores the controls in its own group (`"look"` here), and never the morph slider itself.

### From code

`controlPresets(channel)` gives the same presets to code:

```diffstatic {data-lang=kotlin}
val presets = controlPresets("look")
presets.save("wide")              // the panel as it is now
presets.recall("calm")            // move every control, run every callback
presets.morph(0.3, "calm", "wide")
println(presets.names)
```

`morph(t, ...)` takes the presets to blend in order; with no names it uses all of them, as the slider does. A preset is a map from a control's key to its value, so you can also write one without touching the panel, as the example does with `put`. A key is the control's type, its label and which of that label it is: `slider/size#0`, `vec2/offset#0`, `color/tint#0`, `checkbox/ring#0`, `string/title#0`. Renaming a control's label therefore leaves it out of the presets stored before.

## Recording a control into keyframes

`recordToggle("size")` adds a **rec size** button. Press it, move the size slider, and press it again: what you played becomes a [keyframe curve](keyframe_editor.md) named `size`, opened under the panel. The take is thinned to the keys needed to stay within half a percent of the slider's range, so a slow sweep becomes a handful of keys you can edit, not a key per mouse event.

To play the curve back into the slider, read it in a `loop` and hand it to `playControl`:

```diffstatic {data-lang=kotlin}
val take = keyframes(name = "size")
val start = System.nanoTime()
loop {
    val t = ((System.nanoTime() - start) / 1e9) % 2.0
    take.setTime(t)
    playControl("size", take, t, channel = "look")
}
```

`playControl` moves the slider and runs its callback, so the playback drives whatever the slider drives. `recordControl(label, channel)` is the same recording without the button: it returns a recording whose `stop()` writes the curve.

## MIDI learn

`midiLearnButton()` adds a **MIDI learn** button. Press it, touch the control you want, and turn a knob on your controller (or turn the knob first, then touch the control). From then on that knob moves that control. A knob follows the control's own response, so on a logarithmic frequency slider the middle of the knob is the middle of the slider, not 10 kHz. A knob bound to a checkbox switches it on above halfway. Learning a knob that is already bound moves the binding, and the bindings are saved with the box.

`midiLearn(channel)` does the same from code. `bind` attaches a knob by its controller number, `attach` listens to a MIDI input, and `feed` hands it a controller value from anywhere else — a test, an OSC message, or the loop below, which stands in for a knob:

```diffstatic {data-lang=kotlin}
var cutoff = 1000.0
var bright = false
controlGroup("synth") {
    slider("cutoff", 20.0, 20000.0, initialValue = cutoff, law = ControlLaw.LOGARITHMIC) { cutoff = it }
    checkbox("bright", bright) { bright = it }
    midiLearnButton()
}
val midi = midiLearn("synth")
midi.bind("cutoff", cc = 74)
midi.bind("bright", cc = 64)
// What a knob sends; with hardware, midi.attach(MidiDevices.openInput(MidiDevices.inputs().first())).
for (v in listOf(0, 32, 64, 96, 127)) {
    midi.feed(0, 74, v)
    println("CC74 = $v  ->  cutoff ${"%.1f".format(cutoff)}")
}
midi.feed(0, 64, 127)
midi.feed(0, 74, 80)
println("bright = $bright, bindings = ${midi.bindings}")
```

It prints the cutoff at 20, 114, 650, 3705 and 20000 as the stand-in knob goes from 0 to 127: the middle of the knob lands near the middle of the log scale.

To hear hardware, open an input with `MidiDevices` and attach it: `midi.attach(MidiDevices.openInput(MidiDevices.inputs().first()))`, or pass the input to `midiLearnButton(input)`.
