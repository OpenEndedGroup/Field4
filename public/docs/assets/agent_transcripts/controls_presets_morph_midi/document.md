# Field Document: controls_presets_morph_midi

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. Presets · morph · record · run me

<!-- field-box id="controls_presets_demo" index="0" -->

### Narrative

From `code`

Each control is a local delegated to `keys`, so a preset is a key of that scope.

```edn [field-box id="controls_presets_demo" role="properties"]
{"id" "controls_presets_demo" "index" 0 "name" "Presets · morph · record · run me" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Presets · morph · record · run me" "_tombstone" false "frame" #afield/rect [80.0 80.0 300.0 60.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "controls_presets_demo_code_.kt" "lines" 38 "characters" 1479 "sha256" "9b5793019f4f536102c4d0cc2a81fac0f64750210fa5bc4db560a4bf90dea611"}]}
```

```kotlin [field-property box="controls_presets_demo" name="code" path="controls_presets_demo_code_.kt"]
// Each control is a local delegated to `keys`, so a preset is a key of that scope.
val keys = KeyframeScope()
var size by keys.slider(0.4, 0.1..1.0)
var offset by keys.vec2(Vector2f(0f, 0f))
var tint by keys.color(Vector4f(0.2f, 0.6f, 1f, 1f))
var ring by keys.checkbox(false)

fun draw() {
    val r = (20 + size * 60).toInt()
    val cx = 110 + (offset.x * 60).toInt()
    val cy = 90 - (offset.y * 50).toInt()
    val fill = "rgba(${(tint.x * 255).toInt()},${(tint.y * 255).toInt()},${(tint.z * 255).toInt()},${tint.w})"
    val stroke = if (ring) "stroke='white' stroke-width='6'" else ""
    display("main", "<svg width='220' height='180' style='background:#222'><circle cx='$cx' cy='$cy' r='$r' fill='$fill' $stroke/></svg>")
}

keys.onChange { draw() }

val dir = presetDirectory("look")

// Two keys to start from, written once; after that the stored ones are used.
if (keys.savedKeyNames(dir).isEmpty()) {
    keys.Key("size" to 0.25, "offset" to Vector2f(-0.5f, 0.3f), "tint" to Vector4f(0.2f, 0.6f, 1f, 1f), "ring" to 0f)
        .save(dir.resolve("calm"), "seeded by the demo")
    keys.Key("size" to 0.95, "offset" to Vector2f(0.6f, -0.4f), "tint" to Vector4f(1f, 0.25f, 0.1f, 1f), "ring" to 1f)
        .save(dir.resolve("storm"), "seeded by the demo")
}

var presets: PresetBar? = null
controlGroup("look") {
    keys.controls()
    presets = presetBar(keys)
    recordToggle("size")
    midiLearnButton()
}

draw()
println("presets: ${presets!!.names} in $dir")
```

## 2. MIDI learn · run me

<!-- field-box id="controls_midi_demo" index="1" -->

```edn [field-box id="controls_midi_demo" role="properties"]
{"id" "controls_midi_demo" "index" 1 "name" "MIDI learn · run me" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "MIDI learn · run me" "_tombstone" false "frame" #afield/rect [80.0 200.0 300.0 60.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "controls_midi_demo_code_.kt" "lines" 18 "characters" 652 "sha256" "65bfda042d988ffbdd22aa7978b42a5532db065db401bb942a35a6ae8cab8057"}]}
```

```kotlin [field-property box="controls_midi_demo" name="code" path="controls_midi_demo_code_.kt"]
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
for (v in [0, 32, 64, 96, 127]) {
    midi.feed(0, 74, v)
    println("CC74 = $v  ->  cutoff ${"%.1f".format(cutoff)}")
}
midi.feed(0, 64, 127)
midi.feed(0, 74, 80)
println("bright = $bright, bindings = ${midi.bindings}")
```

