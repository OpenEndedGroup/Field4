# Field Document: faust_voice

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 5 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. an error, on the line

<!-- field-box id="faust_voice_error" index="0" -->

### Narrative

From `code` tags: `#sonicpi`

Deliberately broken — `os.sawtoothX` does not exist. Run it and Faust's own message lands
on the `process` line below rather than on this box's first line.

That is less obvious than it looks. `#` is not a comment in Faust (unlike Ruby, where
`#sonicpi` can stay in the program Sonic Pi is handed), so the marker is stripped before
compiling and every line the compiler reports is short by however many lines went with it.
FaustEngine adds them back before placing the marker.

```edn [field-box id="faust_voice_error" role="properties"]
{"id" "faust_voice_error" "index" 0 "name" "an error, on the line" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "an error, on the line" "_tombstone" false "frame" #afield/rect [80.0 780.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "faust_voice_error_code_.kt" "lines" 16 "characters" 565 "sha256" "55f7542701fe6f9662c4bc46671e068a1c16d4c09c9aba7e6e47447cb0d1d970"}]}
```

```kotlin [field-property box="faust_voice_error" name="code" path="faust_voice_error_code_.kt"]
/*
Deliberately broken — `os.sawtoothX` does not exist. Run it and Faust's own message lands
on the `process` line below rather than on this box's first line.

That is less obvious than it looks. `#` is not a comment in Faust (unlike Ruby, where
`#sonicpi` can stay in the program Sonic Pi is handed), so the marker is stripped before
compiling and every line the compiler reports is short by however many lines went with it.
FaustEngine adds them back before placing the marker.
*/
faust(
    """
#faust
import("stdfaust.lib");
process = os.sawtoothX(440);
""",
)
```

## 2. waveform

<!-- field-box id="faust_voice_waveform" index="1" -->

### Narrative

From `code`

The same program, rendered offline and plotted. Nothing here touches the audio engine,
so this box runs on a machine with no sound card.

The source is identical to the diagram box above, so this compile is a cache hit: compiles
are memoised on a hash of the source.

```edn [field-box id="faust_voice_waveform" role="properties"]
{"id" "faust_voice_waveform" "index" 1 "name" "waveform" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "waveform" "_tombstone" false "frame" #afield/rect [680.0 620.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "faust_voice_waveform_code_.kt" "lines" 18 "characters" 523 "sha256" "acef40d9bcb6fe0924422ab543306a1c0adb4aa20e5e24330a3b0a4a3df80928"}]}
```

```kotlin [field-property box="faust_voice_waveform" name="code" path="faust_voice_waveform_code_.kt"]
/*
The same program, rendered offline and plotted. Nothing here touches the audio engine,
so this box runs on a machine with no sound card.

The source is identical to the diagram box above, so this compile is a cache hit: compiles
are memoised on a hash of the source.
*/
val program =
    faustCompile(
        """
#faust
import("stdfaust.lib");
process = os.sawtooth(220) : fi.lowpass(3, 1200) * 0.2 <: _,_;
""",
    )

faustWaveform(program, seconds = 0.02)
println("rendered 20ms of " + program.outputs + " channels")
```

## 3. the interface it declares

<!-- field-box id="faust_voice_interface" index="2" -->

### Narrative

From `code`

What the compiler says about a program's own interface. This is the whole reason the
controls in the first box are generated rather than written.

`index` is the byte offset of that parameter inside the DSP's WebAssembly memory, which is
why setting a value is a float store and not a message. And note the order: Faust lists
controls alphabetically, not in the order they were declared.

```edn [field-box id="faust_voice_interface" role="properties"]
{"id" "faust_voice_interface" "index" 2 "name" "the interface it declares" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "the interface it declares" "_tombstone" false "frame" #afield/rect [80.0 420.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "faust_voice_interface_code_.kt" "lines" 45 "characters" 1434 "sha256" "333ff1fa48ee7593fe08f7325c5d3407a46961e4f369e75be6b64307f48f12b3"}]}
```

```kotlin [field-property box="faust_voice_interface" name="code" path="faust_voice_interface_code_.kt"]
/*
What the compiler says about a program's own interface. This is the whole reason the
controls in the first box are generated rather than written.

`index` is the byte offset of that parameter inside the DSP's WebAssembly memory, which is
why setting a value is a float store and not a message. And note the order: Faust lists
controls alphabetically, not in the order they were declared.
*/
val program =
    faustCompile(
        """
#faust
import("stdfaust.lib");
freq   = hslider("freq [unit:Hz] [scale:log]", 220, 50, 2000, 0.01);
cutoff = hslider("cutoff [unit:Hz]", 1400, 50, 8000, 1);
gate   = button("gate");
bypass = checkbox("bypass");
level  = hbargraph("level", 0, 1);
env = en.adsr(0.01, 0.2, 0.6, 0.4, gate);
dry = os.sawtooth(freq) : fi.lowpass(2, cutoff) * env * 0.2;
process = (dry : (_ <: attach(_, abs : max ~ *(0.999) : level))) * (1 - bypass) <: _,_;
""",
    )

println(program.inputs.toString() + " in, " + program.outputs + " out")
program.ui.writable.forEach { c ->
    println(
        c.displayLabel +
            "  " +
            c.type +
            "  " +
            c.min +
            ".." +
            c.max +
            " step " +
            c.step +
            "  init " +
            c.init +
            "  " +
            c.scale +
            "  index " +
            c.index,
    )
}
program.ui.meters.forEach { println(it.label + "  " + it.type + " (read-only, use launch.meter)") }
```

## 4. block diagram

<!-- field-box id="faust_voice_diagram" index="3" -->

### Narrative

From `code`

Faust is a language about signal flow, and the block diagram *is* the signal flow. This
comes out of the embedded compiler, so there is no `faust -svg` and no command line.

generateAuxFiles throws *after* writing the SVG (it goes on to try a C++ backend that
is not built), so FaustEngine reads the files regardless of the exception.

```edn [field-box id="faust_voice_diagram" role="properties"]
{"id" "faust_voice_diagram" "index" 3 "name" "block diagram" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "block diagram" "_tombstone" false "frame" #afield/rect [680.0 80.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "faust_voice_diagram_code_.kt" "lines" 18 "characters" 599 "sha256" "4ec781e538aea67be302b97940b93b069afc0932863a3756d972b0cd68cd172f"}]}
```

```kotlin [field-property box="faust_voice_diagram" name="code" path="faust_voice_diagram_code_.kt"]
/*
Faust is a language about signal flow, and the block diagram *is* the signal flow. This
comes out of the embedded compiler, so there is no `faust -svg` and no command line.

generateAuxFiles throws *after* writing the SVG (it goes on to try a C++ backend that
is not built), so FaustEngine reads the files regardless of the exception.
*/
val program =
    faustCompile(
        """
#faust
import("stdfaust.lib");
process = os.sawtooth(220) : fi.lowpass(3, 1200) * 0.2 <: _,_;
""",
    )

faustDiagram(program)
println("diagram for " + program.name + " — " + program.wasm.size + " bytes of wasm")
```

## 5. Faust voice

<!-- field-box id="faust_voice_play" index="4" -->

### Narrative

From `code`

A Faust program, playing. Click this box and the sliders below appear — they are the
program's own hslider declarations, not something written here. Move `cutoff` while it
sounds: the value is written straight into the running DSP's WebAssembly memory.

The first compile of a session is slow (13-22s of GraalVM warm-up, then ~600ms). Run
`FaustEngine.warmInBackground()` in another box first if you would rather wait now.

```edn [field-box id="faust_voice_play" role="properties"]
{"id" "faust_voice_play" "index" 4 "name" "Faust voice" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Faust voice" "_tombstone" false "frame" #afield/rect [80.0 80.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "faust_voice_play_code_.kt" "lines" 18 "characters" 721 "sha256" "bddc0df9f08f52a6e4a70044df990cb91093fbf1485d2888467109efce2c789c"}]}
```

```kotlin [field-property box="faust_voice_play" name="code" path="faust_voice_play_code_.kt"]
/*
A Faust program, playing. Click this box and the sliders below appear — they are the
program's own hslider declarations, not something written here. Move `cutoff` while it
sounds: the value is written straight into the running DSP's WebAssembly memory.

The first compile of a session is slow (13-22s of GraalVM warm-up, then ~600ms). Run
`FaustEngine.warmInBackground()` in another box first if you would rather wait now.
*/
faust(
    """
#faust
import("stdfaust.lib");
freq   = hslider("freq [unit:Hz] [scale:log]", 110, 40, 880, 0.01);
cutoff = hslider("cutoff [unit:Hz]", 1200, 60, 6000, 1);
gain   = hslider("gain", 0.18, 0, 0.6, 0.001);
process = os.sawtooth(freq) : fi.lowpass(3, cutoff) * gain <: _,_;
""",
)
```

