# Field Document: csound_demo

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 5 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "fileManagerAccessHistory"]}
```

## 1. offline sine

<!-- field-box id="cs_offline" index="0" -->

### Narrative

From `code`

One second of a 440 Hz sine, rendered without an audio device, then measured.

```edn [field-box id="cs_offline" role="properties"]
{"id" "cs_offline" "index" 0 "name" "offline sine" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "offline sine" "_tombstone" false "frame" #afield/rect [40.0 160.0 160.0 80.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "cs_offline_code_.kt" "lines" 11 "characters" 534 "sha256" "3458024000a85da2fd68beb2b020d2874f9d2ee07622231bf91ff87e3c929bc5"}]}
```

```kotlin [field-property box="cs_offline" name="code" path="cs_offline_code_.kt"]
// One second of a 440 Hz sine, rendered without an audio device, then measured.
val (left, right) = csoundOffline("""
instr 1
  aenv linen 0.25, 0.01, p3, 0.05
  a1 oscili aenv, 440
  outs a1, a1
endin
""", "i1 0 1", wav = java.io.File(System.getProperty("java.io.tmpdir"), "csound_sine.wav"))
val ups = (1 until 48000).count { left[it - 1] < 0f && left[it] >= 0f }
val peak = left.maxOf { abs(it) }
println("$ups upward zero crossings in the first second, peak ${"%.3f".format(peak)}, channels equal: ${left.contentEquals(right)}")
```

## 2. status

<!-- field-box id="cs_status" index="1" -->

```edn [field-box id="cs_status" role="properties"]
{"id" "cs_status" "index" 1 "name" "status" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "status" "_tombstone" false "frame" #afield/rect [40.0 40.0 420.0 80.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "cs_status_code_.kt" "lines" 1 "characters" 48 "sha256" "cdd1dd10e1ee6ef6c582dd95c06462b6a0378f5c04a10d23fa88b582c128f90a"}]}
```

```kotlin [field-property box="cs_status" name="code" path="cs_status_code_.kt"]
println(afield.csound.CsoundLibrary.describe())
```

## 3. an error

<!-- field-box id="cs_error" index="2" -->

### Narrative

From `code`

oscilx is not an opcode (oscili is). Run it: the error is marked on line 4.

```edn [field-box id="cs_error" role="properties"]
{"id" "cs_error" "index" 2 "name" "an error" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "an error" "_tombstone" false "frame" #afield/rect [610.0 160.0 160.0 80.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "cs_error_code_.kt" "lines" 7 "characters" 161 "sha256" "3efb53a568f91814f55e5520b7de22470f158ce0c5a2737e80309f6e05363644"}]}
```

```kotlin [field-property box="cs_error" name="code" path="cs_error_code_.kt"]
// oscilx is not an opcode (oscili is). Run it: the error is marked on line 4.
csoundOffline("""
instr 1
  a1 oscilx 0.2, 440
  outs a1, a1
endin
""", "i1 0 1")
```

## 4. drone

<!-- field-box id="cs_drone" index="3" -->

### Narrative

From `code`

Launch it, then move the sliders. chn_k declares each channel's default and range.

```edn [field-box id="cs_drone" role="properties"]
{"id" "cs_drone" "index" 3 "name" "drone" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "drone" "_tombstone" false "frame" #afield/rect [230.0 160.0 160.0 80.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "cs_drone_code_.kt" "lines" 17 "characters" 415 "sha256" "c704a750665d5115dd2abaf4b41703848a2b9f6c6002aa37240b00dbbab15aa5"}]}
```

```kotlin [field-property box="cs_drone" name="code" path="cs_drone_code_.kt"]
// Launch it, then move the sliders. chn_k declares each channel's default and range.
val drone = csound("""
chn_k "pitch", 1, 2, 110, 55, 440
chn_k "cutoff", 1, 2, 1200, 200, 6000
instr 1
  kf chnget "pitch"
  kc chnget "cutoff"
  kf port kf, 0.05
  kc port kc, 0.05
  a1 vco2 0.12, kf
  a1 moogladder a1, kc, 0.3
  outs a1, a1
  printks "level %.3f\n", 1, rms(a1)
endin
""", "i1 0 z")
csoundControls(drone)
drone
```

## 5. events

<!-- field-box id="cs_events" index="4" -->

### Narrative

From `code`

Launch it: Kotlin writes the score, one note every quarter second.

```edn [field-box id="cs_events" role="properties"]
{"id" "cs_events" "index" 4 "name" "events" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "events" "_tombstone" false "frame" #afield/rect [420.0 160.0 160.0 80.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "cs_events_code_.kt" "lines" 13 "characters" 353 "sha256" "2482767d116c13939c3f2fdd56706ff977198c3ff77d42bdee0f50e8f7ca959d"}]}
```

```kotlin [field-property box="cs_events" name="code" path="cs_events_code_.kt"]
// Launch it: Kotlin writes the score, one note every quarter second.
val cs = csound("""
instr 1
  aenv expseg 0.001, 0.005, 0.2, p3, 0.001
  a1 oscili aenv, cpsmidinn(p4)
  outs a1, a1
  prints "note %d\n", p4
endin
""")
val notes = listOf(57, 60, 64, 67, 69, 67, 64, 60)
var i = 0
cs.every(0.25) { event('i', 1, 0, 0.4, notes[i++ % notes.size]) }
cs
```

