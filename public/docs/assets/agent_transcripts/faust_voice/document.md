# Field Document: faust_voice

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 5 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "fileManagerAccessHistory"]}
```

## 1. an error, on the line

<!-- field-box id="faust_voice_error" index="0" -->

```edn [field-box id="faust_voice_error" role="properties"]
{"id" "faust_voice_error" "index" 0 "name" "an error, on the line" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "an error, on the line" "_tombstone" false "frame" #afield/rect [80.0 780.0 200.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "faust_voice_error_code_.kt" "lines" 14 "characters" 600 "sha256" "ec2186875d8593d88d16adb6eed1452c85d1d8dab89f69031fa35c5ccb74bbf1"}]}
```

```kotlin [field-property box="faust_voice_error" name="code" path="faust_voice_error_code_.kt"]
import afield.four.synth.*

// Deliberately broken — `os.sawtoothX` does not exist. Run it and Faust's own message lands
// on the `process` line below rather than on this box's first line.
//
// That is less obvious than it looks. `#` is not a comment in Faust (unlike Ruby, where
// `#sonicpi` can stay in the program Sonic Pi is handed), so the marker is stripped before
// compiling and every line the compiler reports is short by however many lines went with it.
// FaustEngine adds them back before placing the marker.
faust("""
#faust
import("stdfaust.lib");
process = os.sawtoothX(440);
""")
```

## 2. waveform

<!-- field-box id="faust_voice_waveform" index="1" -->

```edn [field-box id="faust_voice_waveform" role="properties"]
{"id" "faust_voice_waveform" "index" 1 "name" "waveform" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "waveform" "_tombstone" false "frame" #afield/rect [680.0 620.0 200.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "faust_voice_waveform_code_.kt" "lines" 16 "characters" 637 "sha256" "53d6714a4ff305e9d12cac56c2fd2385fcf2fb1147a06f2549a752991dde76af"}]}
```

```kotlin [field-property box="faust_voice_waveform" name="code" path="faust_voice_waveform_code_.kt"]
import afield.four.synth.*

// The same program, rendered offline and plotted. Nothing here touches the audio engine —
// which is what makes an honest Faust figure possible in the documentation, and what makes
// this box safe to run on a machine with no sound card.
//
// The source is identical to the diagram box above, so this compile is a cache hit: compiles
// are memoised on a hash of the source.
val program = faustCompile("""
#faust
import("stdfaust.lib");
process = os.sawtooth(220) : fi.lowpass(3, 1200) * 0.2 <: _,_;
""")

faustWaveform(program, seconds = 0.02)
println("rendered 20ms of " + program.outputs + " channels")
```

## 3. the interface it declares

<!-- field-box id="faust_voice_interface" index="2" -->

```edn [field-box id="faust_voice_interface" role="properties"]
{"id" "faust_voice_interface" "index" 2 "name" "the interface it declares" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "the interface it declares" "_tombstone" false "frame" #afield/rect [80.0 420.0 200.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "faust_voice_interface_code_.kt" "lines" 30 "characters" 1309 "sha256" "7e85ffc76d6dd916d2b0b5752f14af37ddd75cc989bfe7b6ecdf61c5b558883b"}]}
```

```kotlin [field-property box="faust_voice_interface" name="code" path="faust_voice_interface_code_.kt"]
import afield.four.synth.*

// What the compiler says about a program's own interface. This is the whole reason the
// controls in the first box are generated rather than written.
//
// `index` is the byte offset of that parameter inside the DSP's WebAssembly memory, which is
// why setting a value is a float store and not a message. And note the order: Faust lists
// controls alphabetically, not in the order they were declared.
val program = faustCompile("""
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
""")

println(program.inputs.toString() + " in, " + program.outputs + " out")
program.ui.writable.forEach { c ->
    println(
        c.displayLabel + "  " + c.type +
            "  " + c.min + ".." + c.max + " step " + c.step +
            "  init " + c.init + "  " + c.scale + "  index " + c.index
    )
}
program.ui.meters.forEach { println(it.label + "  " + it.type + " (read-only, use launch.meter)") }
```

## 4. block diagram

<!-- field-box id="faust_voice_diagram" index="3" -->

```edn [field-box id="faust_voice_diagram" role="properties"]
{"id" "faust_voice_diagram" "index" 3 "name" "block diagram" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "block diagram" "_tombstone" false "frame" #afield/rect [680.0 80.0 200.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "faust_voice_diagram_code_.kt" "lines" 15 "characters" 622 "sha256" "8c9dad069cae25d2307514dce0a2d5370100157943d2796dfc525e0b2204029c"}]}
```

```kotlin [field-property box="faust_voice_diagram" name="code" path="faust_voice_diagram_code_.kt"]
import afield.four.synth.*

// Faust is a language about signal flow, and the block diagram *is* the signal flow. This
// comes out of the embedded compiler, so there is no `faust -svg` and no command line.
//
// A quirk worth knowing: generateAuxFiles throws *after* writing the SVG (it goes on to try
// a C++ backend that is not built), so FaustEngine reads the files regardless.
val program = faustCompile("""
#faust
import("stdfaust.lib");
process = os.sawtooth(220) : fi.lowpass(3, 1200) * 0.2 <: _,_;
""")

faustDiagram(program)
println("diagram for " + program.name + " — " + program.wasm.size + " bytes of wasm")
```

## 5. Faust voice

<!-- field-box id="faust_voice_play" index="4" -->

```edn [field-box id="faust_voice_play" role="properties"]
{"id" "faust_voice_play" "index" 4 "name" "Faust voice" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Faust voice" "_tombstone" false "frame" #afield/rect [80.0 80.0 200.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "faust_voice_play_code_.kt" "lines" 16 "characters" 753 "sha256" "ff904e4878c354c1f965e851444d5a670c7fa3a0286120bbb8f21272f9a79ad8"}]}
```

```kotlin [field-property box="faust_voice_play" name="code" path="faust_voice_play_code_.kt"]
import afield.four.synth.*

// A Faust program, playing. Click this box and the sliders below appear — they are the
// program's own hslider declarations, not something written here. Move `cutoff` while it
// sounds: the value is written straight into the running DSP's WebAssembly memory.
//
// The first compile of a session is slow (13-22s of GraalVM warm-up, then ~600ms). Run
// `FaustEngine.warmInBackground()` in another box first if you would rather wait now.
faust("""
#faust
import("stdfaust.lib");
freq   = hslider("freq [unit:Hz] [scale:log]", 110, 40, 880, 0.01);
cutoff = hslider("cutoff [unit:Hz]", 1200, 60, 6000, 1);
gain   = hslider("gain", 0.18, 0, 0.6, 0.001);
process = os.sawtooth(freq) : fi.lowpass(3, cutoff) * gain <: _,_;
""")
```

