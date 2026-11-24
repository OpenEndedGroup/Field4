# Field Document: muse_sequencer

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 5 "persistentProperties" ["contents" "frame" "name" "code" "code_language" "box_kind" "textFrame" "textRunAnchor" "textRunSources" "textFrameReview" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "parentHint" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_panel" "ai4_continuation" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "ai4_garden" "fileManagerAccessHistory" "kitRole"]}
```

## 1. lock

<!-- field-box id="muse_lock" index="0" -->

```edn [field-box id="muse_lock" role="properties"]
{"id" "muse_lock" "index" 0 "name" "lock" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "lock" "_tombstone" false "frame" #afield/rect [20.0 1500.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "muse_lock_code_.kt" "lines" 10 "characters" 275 "sha256" "83a177f5c0d6a6d5f5e1c109ae449352405e07e620597a60a94b48d0b3a9196e"}]}
```

```kotlin [field-property box="muse_lock" name="code" path="muse_lock_code_.kt"]
val muse = PatMuse(seed = 3)
val free = muse.rightPattern()
muse.lock(8, atCycle = 4)
showPatRollOf(
    "free" to free,
    "locked" to muse.rightPattern(),
    title = "right hand: free, then the last 8 events before cycle 4, locked",
    cycles = 8,
    pitched = true,
)
```

## 2. live

<!-- field-box id="muse_live" index="1" -->

### Narrative

From `code`

On the audio engine: the left hand locked and plain, the right hand locked and improvising.

```edn [field-box id="muse_live" role="properties"]
{"id" "muse_live" "index" 1 "name" "live" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "live" "_tombstone" false "frame" #afield/rect [760.0 2100.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "muse_live_code_.kt" "lines" 6 "characters" 289 "sha256" "7f0abb408453d8053ca48eb081136359b3318497af28427938a499f450019851"}]}
```

```kotlin [field-property box="muse_live" name="code" path="muse_live_code_.kt"]
// On the audio engine: the left hand locked and plain, the right hand locked and improvising.
val muse = PatMuse(seed = 3).lock(8, atCycle = 4)
muse.rightLoop!!.improvise = 0.4
val piano = Piano()
piano.play(muse.pattern(), cycles = 32, cps = 0.5, velocity = 1.0)
audio(piano, gain = 1f)
```

## 3. edit

<!-- field-box id="muse_edit" index="2" -->

### Narrative

From `code`

A locked loop corrected by hand: the third event becomes a triad on the octave, the last a rest.

```edn [field-box id="muse_edit" role="properties"]
{"id" "muse_edit" "index" 2 "name" "edit" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "edit" "_tombstone" false "frame" #afield/rect [20.0 2100.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "muse_edit_code_.kt" "lines" 13 "characters" 424 "sha256" "6e9381d468f1bea2494ec759ff5a3056496b0fff402dcee1f00c87eb640a066a"}]}
```

```kotlin [field-property box="muse_edit" name="code" path="muse_edit_code_.kt"]
// A locked loop corrected by hand: the third event becomes a triad on the octave, the last a rest.
val loop = PatMuseChannel(seed = 3).lock(8, atCycle = 4)
val before = loop.pattern()
loop.set(2, degrees = [7, 9, 11], velocity = 0.8)
loop.set(7, degrees = [])
showPatRollOf(
    "as locked" to before,
    "edited" to loop.pattern(),
    title = "editing a locked loop",
    cycles = 4,
    pitched = true,
)
println(loop)
```

## 4. ornaments

<!-- field-box id="muse_ornaments" index="3" -->

```edn [field-box id="muse_ornaments" role="properties"]
{"id" "muse_ornaments" "index" 3 "name" "ornaments" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "ornaments" "_tombstone" false "frame" #afield/rect [760.0 1500.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "muse_ornaments_code_.kt" "lines" 11 "characters" 293 "sha256" "24234be121cc9f362cb4921a07c6da45c736cc6b8844b729875e4f2109bd762d"}]}
```

```kotlin [field-property box="muse_ornaments" name="code" path="muse_ornaments_code_.kt"]
val muse = PatMuse(seed = 3).lock(8, atCycle = 4)
val loop = muse.rightLoop!!
val plain = loop.pattern()
loop.improvise = 0.4
showPatRollOf(
    "locked" to plain,
    "improvise 0.4" to loop.pattern(),
    title = "the same loop, plain and improvising",
    cycles = 8,
    pitched = true,
)
```

## 5. muse

<!-- field-box id="muse_sliders" index="4" -->

### Narrative

From `code`

Two hands drawn from weights. Move a slider and the roll redraws; re-run the box to hear the
current settings through the piano (every slider keeps its value across runs).

```edn [field-box id="muse_sliders" role="properties"]
{"id" "muse_sliders" "index" 4 "name" "muse" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "muse" "_tombstone" false "frame" #afield/rect [20.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "muse_sliders_code_.kt" "lines" 88 "characters" 2538 "sha256" "7bfca898fcba7352769cf99b80d397cc1619b037084ac25416d2eef5cf3be238"}]}
```

```kotlin [field-property box="muse_sliders" name="code" path="muse_sliders_code_.kt"]
// Two hands drawn from weights. Move a slider and the roll redraws; re-run the box to hear the
// current settings through the piano (every slider keeps its value across runs).
val muse = PatMuse(seed = 3)
var seed = 3.0
var lockLength = 0.0
var lockAt = 4.0
var start = 0.0
var end = 64.0
var direction = 0.0
var improvise = 0.0

fun hands(): PatMuse {
    muse.left.seed = seed.toLong()
    muse.right.seed = seed.toLong()
    muse.release()
    if (lockLength >= 1) {
        muse.lock(lockLength.toInt(), atCycle = lockAt)
        for (loop in [muse.leftLoop!!, muse.rightLoop!!]) {
            loop.start = start.toInt()
            loop.end = end.toInt()
            loop.direction = PatLoopDirection.entries[direction.toInt()]
            loop.improvise = improvise
            loop.seed = seed.toLong()
        }
    }
    return muse
}

fun redraw() {
    val m = hands()
    val state = if (lockLength >= 1) "locked: ${m.rightLoop}" else "free"
    showPatRollOf(
        "left" to m.leftPattern(),
        "right" to m.rightPattern(),
        title = "PatMuse(seed = ${seed.toInt()})",
        subtitle = state,
        cycles = 8,
        pitched = true,
        channel = "roll",
    )
}

controlGroup("loop", columns = 4) {
    slider("seed", 1.0, 64.0, 1.0, seed) {
        seed = it
        redraw()
    }
    slider("lock length (0 = free)", 0.0, 64.0, 1.0, lockLength) {
        lockLength = it
        redraw()
    }
    slider("lock at cycle", 1.0, 16.0, 1.0, lockAt) {
        lockAt = it
        redraw()
    }
    slider("improvise", 0.0, 1.0, 0.05, improvise) {
        improvise = it
        redraw()
    }
    slider("start", 0.0, 63.0, 1.0, start) {
        start = it
        redraw()
    }
    slider("end", 1.0, 64.0, 1.0, end) {
        end = it
        redraw()
    }
    slider("direction", 0.0, 2.0, 1.0, direction) {
        direction = it
        redraw()
    }
}
controlGroup("right hand", columns = 4) {
    weights("degree", muse.right.degree) { redraw() }
    weights("chord", muse.right.chord) { redraw() }
    weights("length", muse.right.rhythm) { redraw() }
}
controlGroup("left hand", columns = 4) {
    weights("degree", muse.left.degree) { redraw() }
    weights("chord", muse.left.chord) { redraw() }
    weights("length", muse.left.rhythm) { redraw() }
}
redraw()
val r = pianoRender(17.0, Piano(sampleRate = 44_100)) {
    play(hands().pattern(), cycles = 8, cps = 0.5, velocity = 1.0, startFrame = 0)
}
showPianoAudio(r, "listen")
["loop", "roll", "listen"].forEach { displayOpen(it) }
```

