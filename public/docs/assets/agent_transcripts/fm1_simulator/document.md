# Field Document: fm1_simulator

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_panel" "ai4_continuation" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "ai4_garden" "fileManagerAccessHistory" "kitRole"]}
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
{"id" "fm1_offline" "index" 0 "name" "offline, four projects" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "offline, four projects" "_tombstone" false "frame" #afield/rect [480.0 80.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "fm1_offline_code_.kt" "lines" 25 "characters" 938 "sha256" "8e5171c99d389dcf97c6f54bf6cd4710bf5453bded0df4735dbe071d0738f39e"}]}
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
    val sim = FM1Sim.create()
    sim.preset(slot)
    repeat(5) { sim.render(l, r, 4800, 48000f) }
    sim.play()
    val levels = (0 until 30).map {
        sim.render(l, r, 4800, 48000f)
        kotlin.math.sqrt(l.indices.sumOf { i -> (l[i] * l[i] + r[i] * r[i]).toDouble() } / (2 * l.size))
    }
    val trace = levels.joinToString("") { bars[(it / 0.14 * 8).toInt().coerceIn(0, 8)].toString() }
    println("project $slot  playing=${sim.isPlaying}  $trace")
    sim.close()
}
```

## 2. FM-1, playing

<!-- field-box id="fm1_play" index="1" -->

### Narrative

From `code`

The M-VAVE FM-1's open-firmware simulator in Field's audio engine. Launch this box to hear
it; stop the box and it leaves the mix.

Change the project slot (0..3) and re-run: the simulator is shared, so the new project
loads while the transport keeps its state. PLAY/STOP is a toggle, hence the check.

```edn [field-box id="fm1_play" role="properties"]
{"id" "fm1_play" "index" 1 "name" "FM-1, playing" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "FM-1, playing" "_tombstone" false "frame" #afield/rect [80.0 80.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "fm1_play_code_.kt" "lines" 14 "characters" 403 "sha256" "9b365a54817dc1cf0550717b5a13f6e0b54c38bfeb199ada0ba009caab0ca700"}]}
```

```kotlin [field-property box="fm1_play" name="code" path="fm1_play_code_.kt"]
/*
The M-VAVE FM-1's open-firmware simulator in Field's audio engine. Launch this box to hear
it; stop the box and it leaves the mix.

Change the project slot (0..3) and re-run: the simulator is shared, so the new project
loads while the transport keeps its state. PLAY/STOP is a toggle, hence the check.
*/
val fm1 = FM1Sim.shared

fm1.preset(1)
if (!fm1.isPlaying) fm1.play()
fm1.level(0.8)

fm1.node
```

