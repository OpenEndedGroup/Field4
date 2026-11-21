# Field Document: fm1_simulator

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 4 "persistentProperties" ["contents" "frame" "name" "code" "code_language" "box_kind" "textFrame" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "parentHint" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_panel" "ai4_continuation" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "ai4_garden" "fileManagerAccessHistory" "kitRole"]}
```

## 1. offline, four projects

<!-- field-box id="fm1_offline" index="0" -->

### Narrative

From `code`

Each factory project rendered offline: load it, let it settle for half a second, press
PLAY, then render three seconds at 48 kHz and print the level of every 100 ms as a bar.
No audio device is involved, so this runs anywhere.

Each project gets its own simulator, closed at the end, so nothing here touches the shared
one the box to the left plays.

```edn [field-box id="fm1_offline" role="properties"]
{"id" "fm1_offline" "index" 0 "name" "offline, four projects" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "offline, four projects" "_tombstone" false "frame" #afield/rect [480.0 80.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "fm1_offline_code_.kt" "lines" 25 "characters" 968 "sha256" "0363ad4476bc0df7ed323deaff150361603b51061c30ed4e25f66c2d5bdcca9c"}]}
```

```kotlin [field-property box="fm1_offline" name="code" path="fm1_offline_code_.kt"]
/*
Each factory project rendered offline: load it, let it settle for half a second, press
PLAY, then render three seconds at 48 kHz and print the level of every 100 ms as a bar.
No audio device is involved, so this runs anywhere.

Each project gets its own simulator, closed at the end, so nothing here touches the shared
one the box to the left plays.
*/
val bars = " ▁▂▃▄▅▆▇█"
val l = FloatArray(4800)
val r = FloatArray(4800)

for (slot in 0 until 4) {
    FM1Sim.create().use { sim ->
        sim.preset(slot)
        repeat(5) { sim.render(l, r, 4800, 48000f) }
        sim.start()
        val levels = (0 until 30).map {
            sim.render(l, r, 4800, 48000f)
            kotlin.math.sqrt(l.indices.sumOf { i -> (l[i] * l[i] + r[i] * r[i]).toDouble() } / (2 * l.size))
        }
        val trace = levels.joinToString("") { bars[(it / 0.14 * 8).toInt().coerceIn(0, 8)].toString() }
        println("project $slot  playing=${sim.isPlaying}  $trace")
    }
}
```

## 2. FM-1, playing

<!-- field-box id="fm1_play" index="1" -->

### Narrative

From `code`

The M-VAVE FM-1's open-firmware simulator in Field's audio engine. Launch this box to hear
it and see its front panel, live; stop the box and it leaves the mix.

Change the project slot (0 to 15) and re-run: the simulator is shared, so the new project
loads into the same one. start() starts from the first step whether it was playing or not.

```edn [field-box id="fm1_play" role="properties"]
{"id" "fm1_play" "index" 1 "name" "FM-1, playing" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "FM-1, playing" "_tombstone" false "frame" #afield/rect [80.0 80.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "fm1_play_code_.kt" "lines" 14 "characters" 441 "sha256" "07ea96f0f04a79921b9e4e4e4f07368e73a073fdd1eeb8dafb22509c5bba986e"}]}
```

```kotlin [field-property box="fm1_play" name="code" path="fm1_play_code_.kt"]
/*
The M-VAVE FM-1's open-firmware simulator in Field's audio engine. Launch this box to hear
it and see its front panel, live; stop the box and it leaves the mix.

Change the project slot (0 to 15) and re-run: the simulator is shared, so the new project
loads into the same one. start() starts from the first step whether it was playing or not.
*/
val fm1 = FM1Sim.shared

fm1.preset(1)
fm1.start()
fm1.level(0.8)

fm1.node + fm1Panel(fm1)
```

## 3. the screen

<!-- field-box id="fm1_screen" index="2" -->

### Narrative

From `code`

The FM-1's LCD for factory project 4, from a private simulator rendered offline for half a
second, shown at three times its size.

```edn [field-box id="fm1_screen" role="properties"]
{"id" "fm1_screen" "index" 2 "name" "the screen" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "the screen" "_tombstone" false "frame" #afield/rect [480.0 260.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "fm1_screen_code_.kt" "lines" 12 "characters" 341 "sha256" "74ea9558472c7a99be57c051b270476bac18c8eaadcae438ae124e6d28bbc26b"}]}
```

```kotlin [field-property box="fm1_screen" name="code" path="fm1_screen_code_.kt"]
/*
The FM-1's LCD for factory project 4, from a private simulator rendered offline for half a
second, shown at three times its size.
*/
FM1Sim.create().use { fm1 ->
    fm1.preset(4)
    val l = FloatArray(4800)
    val r = FloatArray(4800)
    repeat(5) { fm1.render(l, r, 4800, 48000f) }

    display("screen", fm1.screenPng(scale = 3))
}
```

## 4. a pattern on track 2

<!-- field-box id="fm1_pattern" index="3" -->

### Narrative

From `code`

A Field pattern on ACID ALLEY's DX7 track (track 2), one cycle to the project's bar, while a
slow sine turns the 303's filter on track 0. Launch it to hear it; re-run with another
pattern and it replaces this one.

```edn [field-box id="fm1_pattern" role="properties"]
{"id" "fm1_pattern" "index" 3 "name" "a pattern on track 2" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "a pattern on track 2" "_tombstone" false "frame" #afield/rect [80.0 260.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "fm1_pattern_code_.kt" "lines" 12 "characters" 403 "sha256" "160c4f6141ff792f39483bfeb44491eb76c273b419224dc95fc6b14ed276de21"}]}
```

```kotlin [field-property box="fm1_pattern" name="code" path="fm1_pattern_code_.kt"]
/*
A Field pattern on ACID ALLEY's DX7 track (track 2), one cycle to the project's bar, while a
slow sine turns the 303's filter on track 0. Launch it to hear it; re-run with another
pattern and it replaces this one.
*/
val fm1 = FM1Sim.shared
fm1.preset(1)
fm1.start()
fm1.play(pat("<c5 ef5> ~ [g5 bf5] ~ c6 ~ g5 ~"), track = 2)
fm1.modulate(0, FM1Sim.CC.CUTOFF, SineOsc(0.25.p) * 0.5 + 0.5)

fm1.node
```

