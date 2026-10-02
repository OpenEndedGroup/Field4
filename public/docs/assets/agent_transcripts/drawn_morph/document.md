# Field Document: drawn_morph

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 5 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "fileManagerAccessHistory"]}
```

## 1. spread

<!-- field-box id="dm_spread" index="0" -->

### Narrative

From `code`

How big the in-between cloud is (RMS distance from its centre), for both pairings.

```edn [field-box id="dm_spread" role="properties"]
{"id" "dm_spread" "index" 0 "name" "spread" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "spread" "_tombstone" false "frame" #afield/rect [20.0 1480.0 190.0 60.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "dm_spread_code_.kt" "lines" 10 "characters" 599 "sha256" "fb6778a95307cbc72c80d6675fecb3be0d0da7f65a66ac2265c913bac61491e5"}]}
```

```kotlin [field-property box="dm_spread" name="code" path="dm_spread_code_.kt"]
// How big the in-between cloud is (RMS distance from its centre), for both pairings.
val from = notation.named("from")!!.points.resampleByLength(240, closed = true)
val to = notation.named("to")!!.points.resampleByLength(240, closed = true)
val partner = matchPoints(from, to).reorder(to)
val ts = (0..50).map { it / 50.0 }
showSignals(
    "matched" to ts.map { spreadOf(morphBetween(from, partner, it)) },
    "by list index" to ts.map { spreadOf(morphBetween(from, to, it)) },
    x = ts, title = "how big the cloud is, through the morph", xTitle = "t", yTitle = "spread", channel = "spread",
)
```

## 2. play

<!-- field-box id="dm_play" index="1" -->

### Narrative

From `code`

The morph out to the heart and back as 32 notes: MIDI 72 at the circle's size, an
octave lower for every halving. Edit the loop to change what you hear.

```edn [field-box id="dm_play" role="properties"]
{"id" "dm_play" "index" 1 "name" "play" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "play" "_tombstone" false "frame" #afield/rect [20.0 1960.0 190.0 60.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "dm_play_code_.kt" "lines" 16 "characters" 1021 "sha256" "faeb0f5ea0763f766850faf456f941c313c46e0ce637c0cdee347cbbb61bf7f5"}]}
```

```kotlin [field-property box="dm_play" name="code" path="dm_play_code_.kt"]
// The morph out to the heart and back as 32 notes: MIDI 72 at the circle's size, an
// octave lower for every halving. Edit the loop to change what you hear.
val from = notation.named("from")!!.points.resampleByLength(240, closed = true)
val to = notation.named("to")!!.points.resampleByLength(240, closed = true)
val voice = patVoice(wave = PatWave.TRIANGLE, decay = 0.004, sustain = 0.6)
for ((label, partner) in listOf("matched" to matchPoints(from, to).reorder(to), "by list index" to to)) {
    val notes = mutableListOf<TimedNote>()
    val spread0 = spreadOf(from)
    for (k in 0 until 32) {
        val t = if (k < 16) k / 15.0 else (31 - k) / 15.0   // out to the heart, then back
        val midi = 72 + 12 * log2(spreadOf(morphBetween(from, partner, t)) / spread0)
        notes += TimedNote(k * 0.25, k * 0.25 + 0.25, midi)
    }
    println("$label: lowest note %.2f".format(notes.minOf { it.note }))
    showPatAudio(patTimed(notes, beats = 8.0), cycles = 2, cps = 0.25, voice = voice, channel = label)
}
```

## 3. shapes

<!-- field-box id="dm_shapes" index="2" -->

### Narrative

From `code`

Two strokes, drawn from code into the document's drawing: a circle named "from" and a
heart named "to". Running it again replaces them.

```edn [field-box id="dm_shapes" role="properties"]
{"id" "dm_shapes" "index" 2 "name" "shapes" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "shapes" "_tombstone" false "frame" #afield/rect [20.0 40.0 190.0 60.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "dm_shapes_code_.kt" "lines" 8 "characters" 487 "sha256" "7db27ac842b3c26823a65c4e8a22f79b98ad9aada717ffdc27242865ef3518ce"}]}
```

```kotlin [field-property box="dm_shapes" name="code" path="dm_shapes_code_.kt"]
// Two strokes, drawn from code into the document's drawing: a circle named "from" and a
// heart named "to". Running it again replaces them.
notation { morphShapes() }
// To morph two strokes you drew yourself (Alt+D, P), delete the line above and use:
//   val mine = notation.strokes.filter { it.handDrawn }.takeLast(2)
//   notation.edit(mine[0]) { name = "from" }
//   notation.edit(mine[1]) { name = "to" }
println(notation.strokes.map { "${it.label}: ${it.points.size} points" })
```

## 4. paired

<!-- field-box id="dm_paired" index="3" -->

### Narrative

From `code`

Read the strokes back, give each 240 evenly spaced points, and pair them.

```edn [field-box id="dm_paired" role="properties"]
{"id" "dm_paired" "index" 3 "name" "paired" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "paired" "_tombstone" false "frame" #afield/rect [20.0 520.0 190.0 60.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "dm_paired_code_.kt" "lines" 15 "characters" 1069 "sha256" "247c72c45923279b32834824b316a8946dfb590d5d6fd7df5e7dff5d5254b036"}]}
```

```kotlin [field-property box="dm_paired" name="code" path="dm_paired_code_.kt"]
// Read the strokes back, give each 240 evenly spaced points, and pair them.
val from = notation.named("from")!!.points.resampleByLength(240, closed = true)
val to = notation.named("to")!!.points.resampleByLength(240, closed = true)
val match = matchPoints(from, to)
val partner = match.reorder(to)
val cx = from.sumOf { it.x.toDouble() } / from.size; val cy = from.sumOf { it.y.toDouble() } / from.size
fun chord(a: Vec2, b: Vec2, dx: Double) = FLine().moveTo(a.x + dx, a.y).lineTo(b.x + dx, b.y).also {
    it.thickness = 0.8
    it.color = okhsl(atan2(a.y - cy, a.x - cx) * 180 / PI + 360, 0.85, 0.6, 0.9)
}
// Left: the matching. Right: point i of one stroke to point i of the other.
lines(from.indices.map { chord(from[it], partner[it], -220.0) } + from.indices.map { chord(from[it], to[it], 220.0) },
    w = 760.0, h = 380.0, fitToSize = true)
val byIndex = from.indices.sumOf { (from[it].x - to[it].x).toDouble().pow(2) + (from[it].y - to[it].y).toDouble().pow(2) }
println("squared travel: matched %.0f, by list index %.0f".format(match.squaredCost, byIndex))
```

## 5. morph

<!-- field-box id="dm_morph" index="4" -->

### Narrative

From `code`

Five steps of the morph: matched on top, by list index below.

```edn [field-box id="dm_morph" role="properties"]
{"id" "dm_morph" "index" 4 "name" "morph" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "morph" "_tombstone" false "frame" #afield/rect [20.0 1000.0 190.0 60.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "dm_morph_code_.kt" "lines" 15 "characters" 1015 "sha256" "4341cc717cdd868fae428f28738889cfd4f76db7b29e3476a2df05e32d637abe"}]}
```

```kotlin [field-property box="dm_morph" name="code" path="dm_morph_code_.kt"]
// Five steps of the morph: matched on top, by list index below.
val from = notation.named("from")!!.points.resampleByLength(240, closed = true)
val to = notation.named("to")!!.points.resampleByLength(240, closed = true)
val partner = matchPoints(from, to).reorder(to)
val cx = from.sumOf { it.x.toDouble() } / from.size; val cy = from.sumOf { it.y.toDouble() } / from.size
fun dot(p: Vec2, dx: Double, dy: Double, colour: Vec4) = FLine().also { f ->
    val x = (p.x - cx) * 0.5 + dx; val y = (p.y - cy) * 0.5 + dy
    f.moveTo(x - 2, y - 2).lineTo(x + 2, y - 2).lineTo(x + 2, y + 2).lineTo(x - 2, y + 2).lineTo(x - 2, y - 2)
    f.color = colour; f.filled = true; f.stroked = false
}
val steps = listOf(0.0, 0.25, 0.5, 0.75, 1.0)
lines(steps.indices.flatMap { s ->
    morphBetween(from, partner, steps[s]).map { dot(it, s * 200.0, 0.0, vec(0.1, 0.45, 0.85, 1.0)) } +
        morphBetween(from, to, steps[s]).map { dot(it, s * 200.0, 200.0, vec(0.88, 0.2, 0.2, 1.0)) }
}, w = 1000.0, h = 400.0, fitToSize = true)
```

