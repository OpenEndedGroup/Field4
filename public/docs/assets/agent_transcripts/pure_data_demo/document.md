# Field Document: pure_data_demo

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 8 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "fileManagerAccessHistory" "mainLanguage"]}
```

## 1. status — children of this box are Pure Data

<!-- field-box id="pd_root" index="0" -->

### Narrative

From `code`

This root stays Kotlin; its `mainLanguage` makes the three boxes under it Pd.

```edn [field-box id="pd_root" role="properties"]
{"id" "pd_root" "index" 0 "name" "status — children of this box are Pure Data" "parents" ["root"] "children" ["pd_drone" "pd_roundtrip" "pd_typo"] "properties" {"children" #{#afield/box "pd_drone" #afield/box "pd_roundtrip" #afield/box "pd_typo"} "parents" #{#afield/box "root"} "name" "status — children of this box are Pure Data" "_tombstone" false "frame" #afield/rect [40.0 40.0 420.0 80.0] "mainLanguage" "pd"} "blockProperties" [{"name" "code" "language" "kotlin" "path" "pd_root_code_.kt" "lines" 2 "characters" 121 "sha256" "225cb8d070cdaaecc976f8980c972eae66cae6eb7985e2de238b00eb77b9ded9"}]}
```

```kotlin [field-property box="pd_root" name="code" path="pd_root_code_.kt"]
// This root stays Kotlin; its `mainLanguage` makes the three boxes under it Pd.
println(afield.pd.PdLibrary.describe())
```

## 2. typo

<!-- field-box id="pd_typo" index="1" -->

### Narrative

From `code`

[osc 440] is not a Pd object ([osc~ 440] is). Launch: the error is on its line.

```edn [field-box id="pd_typo" role="properties"]
{"id" "pd_typo" "index" 1 "name" "typo" "parents" ["root" "pd_root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "pd_root"} "name" "typo" "_tombstone" false "frame" #afield/rect [420.0 180.0 160.0 80.0] "code_language" "Pd"} "blockProperties" [{"name" "code" "language" "pd" "path" "pd_typo_code_.kt" "lines" 5 "characters" 170 "sha256" "92bc02de15048258071b9ef8591791b641f286a08ab68d89495844270c958885"}]}
```

```pd [field-property box="pd_typo" name="code" path="pd_typo_code_.kt"]
// [osc 440] is not a Pd object ([osc~ 440] is). Launch: the error is on its line.
#N canvas 0 0 400 250 12;
#X obj 20 20 osc 440;
#X obj 20 60 dac~;
#X connect 0 0 1 0;
```

## 3. as an effect

<!-- field-box id="pd_effect" index="2" -->

### Narrative

From `code`

A Field sawtooth through a Pd lowpass: `input` feeds the patch's [adc~]. Launch it.

```edn [field-box id="pd_effect" role="properties"]
{"id" "pd_effect" "index" 2 "name" "as an effect" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "as an effect" "_tombstone" false "frame" #afield/rect [230.0 320.0 160.0 80.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "pd_effect_code_.kt" "lines" 20 "characters" 568 "sha256" "b850d27111e1f8c97cb74efdf8036e16ea8cac630e5d9f54e3d02a761e785679"}]}
```

```kotlin [field-property box="pd_effect" name="code" path="pd_effect_code_.kt"]
// A Field sawtooth through a Pd lowpass: `input` feeds the patch's [adc~]. Launch it.
val fx = pd("""
#X obj 20 20 adc~;
#X obj 20 60 lop~ 600;
#X obj 20 100 *~ 0.15;
#X obj 20 140 dac~;
#X obj 160 100 env~ 8192;
#X obj 160 140 s level;
#X connect 0 0 1 0;
#X connect 1 0 2 0;
#X connect 2 0 3 0;
#X connect 2 0 3 1;
#X connect 2 0 4 0;
#X connect 4 0 5 0;
""", input = SawOsc(Param(110f)).pan())
var n = 0
fx.receive("level") { m ->
    if (n++ % 10 == 0) display("level", "<div style='padding:8px'>after the lowpass: ${"%.1f".format(m.float ?: 0f)} dB</div>")
}
fx
```

## 4. from Kotlin

<!-- field-box id="pd_kotlin" index="3" -->

### Narrative

From `code`

A Pd patch as a Kotlin value. Launch it; move the Pitch slider.

```edn [field-box id="pd_kotlin" role="properties"]
{"id" "pd_kotlin" "index" 3 "name" "from Kotlin" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "from Kotlin" "_tombstone" false "frame" #afield/rect [40.0 320.0 160.0 80.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "pd_kotlin_code_.kt" "lines" 40 "characters" 1300 "sha256" "0d553236aebbeed65616b8f226c0e843e282a7fe9a634e6023e7d6c41b8a70c0"}]}
```

```kotlin [field-property box="pd_kotlin" name="code" path="pd_kotlin_code_.kt"]
// A Pd patch as a Kotlin value. Launch it; move the Pitch slider.
val patch = pd("""
#N canvas 0 0 520 300 12;
#X obj 20 20 hsl 128 15 55 440 0 0 empty pitch Pitch -2 -8 0 10 #fcfcfc #000000 #000000 0 1;
#X msg 20 50 \$1 40;
#X obj 20 80 line~;
#X obj 20 110 tabosc4~ wave;
#X obj 20 140 *~ 0.1;
#X obj 20 180 dac~;
#X obj 220 20 table wave 2051;
#X obj 220 140 env~ 8192;
#X obj 220 180 s level;
#X connect 0 0 1 0;
#X connect 1 0 2 0;
#X connect 2 0 3 0;
#X connect 3 0 4 0;
#X connect 4 0 5 0;
#X connect 4 0 5 1;
#X connect 4 0 7 0;
#X connect 7 0 8 0;
""")

// tabosc4~ wants 2^n + 3 points: one guard point before, two after.
val partials = listOf(1.0, 0.5, 0.33, 0.25, 0.2, 0.16)
val wave = FloatArray(2051) { i ->
    val phase = 2 * PI * (i - 1) / 2048
    (partials.withIndex().sumOf { (k, a) -> a * sin((k + 1) * phase) } * 0.4).toFloat()
}
patch.writeArray("wave", wave)
println("wrote ${patch.arraySize("wave")} points into [table wave]")

pdControls(patch)          // one slider, from the patch's own hsl
patch.send("pitch", 110)

var n = 0
patch.receive("level") { m ->
    // env~ reports in dB, 100 = full scale; this lambda runs on Field's main thread.
    if (n++ % 10 == 0) display("level", "<div style='padding:8px'>[s level] ${"%.1f".format(m.float ?: 0f)} dB</div>")
}
patch
```

## 5. offline

<!-- field-box id="pd_offline" index="4" -->

### Narrative

From `code`

No audio device: render one second, measure it, read back what Pd recorded.

```edn [field-box id="pd_offline" role="properties"]
{"id" "pd_offline" "index" 4 "name" "offline" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "offline" "_tombstone" false "frame" #afield/rect [610.0 320.0 160.0 80.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "pd_offline_code_.kt" "lines" 27 "characters" 1077 "sha256" "6927c2dd0731ca1d5c1f7c99074ee14698dd80229353f28052b858a44315eb69"}]}
```

```kotlin [field-property box="pd_offline" name="code" path="pd_offline_code_.kt"]
// No audio device: render one second, measure it, read back what Pd recorded.
val text = """
#N canvas 0 0 400 260 12;
#X obj 20 20 osc~ 440;
#X obj 20 60 *~ 0.25;
#X obj 20 100 dac~;
#X obj 160 100 tabwrite~ scope;
#X obj 160 20 loadbang;
#X obj 260 60 table scope 240;
#X connect 0 0 1 0;
#X connect 1 0 2 0;
#X connect 1 0 2 1;
#X connect 1 0 3 0;
#X connect 4 0 3 0;
"""
val pd = afield.pd.PdInstance(sampleRate = 48000)
pd.openPatchText(text)
// Not `val (left, _) = …`: a top-level `_` in a destructuring never completes in a box.
val (left, right) = pd.renderOffline(1.0)
val scope = pd.readArray("scope")
pd.close()

val ups = (1 until left.size).count { left[it - 1] < 0f && left[it] >= 0f }
val peak = left.maxOf { abs(it) }
println("$ups upward zero crossings in 1 s, peak ${"%.4f".format(peak)}, channels equal: ${left.contentEquals(right)}")
showSignals("tabwrite~ scope" to scope.toList(), channel = "scope", title = "[table scope], recorded by Pd")
lines(afield.pd.PdPatchGraph.parse(text).toFLines(), channel = "patch", w = 420.0, h = 200.0, fitToSize = true)
```

## 6. drone

<!-- field-box id="pd_drone" index="5" -->

### Narrative

From `code`

This text is the patch. Launch the box, then run one of these lines on its own:

```edn [field-box id="pd_drone" role="properties"]
{"id" "pd_drone" "index" 5 "name" "drone" "parents" ["root" "pd_root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "pd_root"} "name" "drone" "_tombstone" false "frame" #afield/rect [40.0 180.0 160.0 80.0] "code_language" "Pd"} "blockProperties" [{"name" "code" "language" "pd" "path" "pd_drone_code_.kt" "lines" 28 "characters" 620 "sha256" "00a5a949ac3068cb985831e0da1f77f84ac4d9ab0a55a4ead41e844d7084239d"}]}
```

```pd [field-property box="pd_drone" name="code" path="pd_drone_code_.kt"]
// This text is the patch. Launch the box, then run one of these lines on its own:
freq 110
freq 330
cutoff 400
cutoff 3000
#N canvas 0 0 500 320 12;
#X obj 20 20 r freq;
#X msg 20 50 \$1 200;
#X obj 20 80 line~;
#X obj 20 110 phasor~;
#X obj 20 140 lop~ 1200;
#X obj 140 110 r cutoff;
#X obj 20 170 *~ 0.08;
#X obj 20 210 dac~;
#X obj 240 20 loadbang;
#X msg 240 50 220;
#X obj 240 80 print drone;
#X connect 0 0 1 0;
#X connect 1 0 2 0;
#X connect 2 0 3 0;
#X connect 3 0 4 0;
#X connect 5 0 4 1;
#X connect 4 0 6 0;
#X connect 6 0 7 0;
#X connect 6 0 7 1;
#X connect 8 0 9 0;
#X connect 9 0 1 0;
#X connect 9 0 10 0;
```

## 7. round trip

<!-- field-box id="pd_roundtrip" index="6" -->

### Narrative

From `code`

Launch, then run "in 41": [+ 1] sends 42 to [s out] and [print] prints it.

```edn [field-box id="pd_roundtrip" role="properties"]
{"id" "pd_roundtrip" "index" 6 "name" "round trip" "parents" ["root" "pd_root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "pd_root"} "name" "round trip" "_tombstone" false "frame" #afield/rect [230.0 180.0 160.0 80.0] "code_language" "Pd"} "blockProperties" [{"name" "code" "language" "pd" "path" "pd_roundtrip_code_.kt" "lines" 11 "characters" 260 "sha256" "7cfc58f4cfa8093ca59f90a0eb17dccea3550d7c255ecfedb940e2654823ebc8"}]}
```

```pd [field-property box="pd_roundtrip" name="code" path="pd_roundtrip_code_.kt"]
// Launch, then run "in 41": [+ 1] sends 42 to [s out] and [print] prints it.
in 41
in 99
#N canvas 0 0 400 250 12;
#X obj 20 20 r in;
#X obj 20 60 + 1;
#X obj 20 100 s out;
#X obj 120 100 print out;
#X connect 0 0 1 0;
#X connect 1 0 2 0;
#X connect 1 0 3 0;
```

## 8. to drone, from Kotlin

<!-- field-box id="pd_send" index="7" -->

### Narrative

From `code`

Send numbers to a [r] in another Pd box, found by its name. Launch "drone" first.
Run one of these lines on its own:

```edn [field-box id="pd_send" role="properties"]
{"id" "pd_send" "index" 7 "name" "to drone, from Kotlin" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "to drone, from Kotlin" "_tombstone" false "frame" #afield/rect [420.0 320.0 160.0 80.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "pd_send_code_.kt" "lines" 14 "characters" 497 "sha256" "77768ef56e4451f8e47c283525a728a9d22e06e7ffc5eb3212924929b23f081b"}]}
```

```kotlin [field-property box="pd_send" name="code" path="pd_send_code_.kt"]
// Send numbers to a [r] in another Pd box, found by its name. Launch "drone" first.
// Run one of these lines on its own:
pdBox("drone").pdSend("freq", 330)
pdBox("drone").pdSend("freq", 110)
pdBox("drone").pdSend("cutoff", 3000)

// Or launch this box: an arpeggio into [r freq], one Int a quarter second, until you stop it.
val drone = pdBox("drone")
val notes = listOf(110, 165, 220, 330, 440, 330, 220, 165)
var i = 0
loop {
    drone.pdSend("freq", notes[i++ % notes.size])
    delay(250)
}
```

