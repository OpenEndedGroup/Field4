# Field Document: profiling_timeline

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 4 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "fileManagerAccessHistory"]}
```

## 1. 4. Record and explore

<!-- field-box id="profile_capture" index="0" -->

### Narrative

From `code`

Run the three workload boxes first, then run this box. The macro does that for you.
Drag the overview, Zoom to selection, choose Allocated bytes, and click a lane.
Open current box code returns you to the code behind a measured coroutine.
Hot code adds sampled classes, methods and box lines, including background threads.

```edn [field-box id="profile_capture" role="properties"]
{"id" "profile_capture" "index" 0 "name" "4. Record and explore" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "4. Record and explore" "_tombstone" false "frame" #afield/rect [520.0 250.0 350.0 105.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "profile_capture_code_.kt" "lines" 7 "characters" 567 "sha256" "dbe699abfb5eb962f26b41b4911d42cb4a1cedd163cbe133b20b1ed226f858df"}]}
```

```kotlin [field-property box="profile_capture" name="code" path="profile_capture_code_.kt"]
// Run the three workload boxes first, then run this box. The macro does that for you.
// Drag the overview, Zoom to selection, choose Allocated bytes, and click a lane.
// Open current box code returns you to the code behind a measured coroutine.
// Hot code adds sampled classes, methods and box lines, including background threads.
CoroutineProfiles.request(thisBox.inside.filename, CoroutineProfileRequest("start", 3.0, stacks = true))
thisBox.inBrowser("window.showProfilingTimeline()")
println("Recording three seconds. The timeline will latch automatically.")
```

## 2. 1. Steady work

<!-- field-box id="profile_steady" index="1" -->

### Narrative

From `code`

A small amount of CPU work at roughly 60 Hz. Increase iterations to widen the bars.

```edn [field-box id="profile_steady" role="properties"]
{"id" "profile_steady" "index" 1 "name" "1. Steady work" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1. Steady work" "_tombstone" false "frame" #afield/rect [80.0 80.0 320.0 105.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "profile_steady_code_.kt" "lines" 7 "characters" 274 "sha256" "c200dfbf7054e1a67240e3f96fc33578a2b288ac26a088db32e5e1d80ac236d6"}]}
```

```kotlin [field-property box="profile_steady" name="code" path="profile_steady_code_.kt"]
// A small amount of CPU work at roughly 60 Hz. Increase iterations to widen the bars.
val iterations = 18000
var checksum = 0.0
loop {
    repeat(iterations) { checksum += kotlin.math.sin(it.toDouble()) }
    delay(16) // suspension should be a gap, not charged CPU time
}
```

## 3. 2. Periodic bursts

<!-- field-box id="profile_bursts" index="2" -->

### Narrative

From `code`

Sparse, longer CPU bars. Try 800000 iterations, or shorten the quiet interval.

```edn [field-box id="profile_bursts" role="properties"]
{"id" "profile_bursts" "index" 2 "name" "2. Periodic bursts" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2. Periodic bursts" "_tombstone" false "frame" #afield/rect [80.0 250.0 320.0 105.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "profile_bursts_code_.kt" "lines" 10 "characters" 287 "sha256" "fbcf3e2cab0dd6a8ff8abff6838408249c138618fa9f053d395975bc73dccf32"}]}
```

```kotlin [field-property box="profile_bursts" name="code" path="profile_bursts_code_.kt"]
// Sparse, longer CPU bars. Try 800000 iterations, or shorten the quiet interval.
val iterations = 350000
val quietMillis = 240
var checksum = 0.0
frames {
    while (frame()) {
        repeat(iterations) { checksum += kotlin.math.cos(it.toDouble()) }
        delay(quietMillis)
    }
}
```

## 4. 3. Allocate or reuse

<!-- field-box id="profile_vectors" index="3" -->

### Narrative

From `code`

Flip reuse to true, relaunch, and record again. Both paths update the same vectors.
vec is Field's linalg constructor; set writes into an existing vector.

```edn [field-box id="profile_vectors" role="properties"]
{"id" "profile_vectors" "index" 3 "name" "3. Allocate or reuse" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "3. Allocate or reuse" "_tombstone" false "frame" #afield/rect [80.0 420.0 320.0 105.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "profile_vectors_code_.kt" "lines" 15 "characters" 482 "sha256" "71cbec7b4eacd445e7afc6616165adfdf344d4a3b2e32b87fecd66912b17e0f4"}]}
```

```kotlin [field-property box="profile_vectors" name="code" path="profile_vectors_code_.kt"]
// Flip reuse to true, relaunch, and record again. Both paths update the same vectors.
// vec is Field's linalg constructor; set writes into an existing vector.
val reuse = false
val count = 8192
var positions = Array(count) { vec(0f, 0f, 0f) }
var phase = 0f
loop {
    phase += 0.01f
    if (reuse) {
        for (i in positions.indices) positions[i].set(i.toFloat(), phase, 0f)
    } else {
        positions = Array(count) { vec(it.toFloat(), phase, 0f) }
    }
    delay(16)
}
```

