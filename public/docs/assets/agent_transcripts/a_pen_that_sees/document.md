# Field Document: a_pen_that_sees

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 9 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. 2 - beside a line, seeing and blind

<!-- field-box id="ps_hatch" index="0" -->

```edn [field-box id="ps_hatch" role="properties"]
{"id" "ps_hatch" "index" 0 "name" "2 - beside a line, seeing and blind" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2 - beside a line, seeing and blind" "_tombstone" false "frame" #afield/rect [60.0 560.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "ps_hatch_code_.kt" "lines" 13 "characters" 916 "sha256" "0d65e0dce940c6f23f066fe239aefe8160b454e4c38eb17d5924d6788af43844"}]}
```

```kotlin [field-property box="ps_hatch" name="code" path="ps_hatch_code_.kt"]
import afield.ai.learning.pen.*
import kotlin.math.*

// The same yellow line continued three times by the seeing Michaux pen (white) and twice by
// its blind twin (red), trained on the same drawings without sight.
val yours = FLine().also { f -> for (i in 0..120) { val y = i * 2.0; val x = 14 * sin(y / 40) + 5 * sin(y / 13); if (i == 0) f.moveTo(x, y) else f.lineTo(x, y) } }
fun beside(model: String, seed: Long) = listOf(
    yours.copy().apply { color = vec(1.0, 0.85, 0.3, 1.0); thickness = 1.5 },
    yours.continueWith(PenModel.bundled(model), steps = 1500, seed = seed).apply {
        color = if (model.endsWith("blind")) vec(1.0, 0.6, 0.6, 1.0) else vec(0.9, 0.92, 1.0, 1.0); thickness = 1.0
    })
lines(sideBySide(listOf(beside("michaux", 1), beside("michaux", 2), beside("michaux", 3), beside("michaux-blind", 1), beside("michaux-blind", 2)), gap = 30.0),
    w = 520.0, h = 440.0, fitToSize = true)
```

## 2. 6 - hatch beside your line (launch)

<!-- field-box id="ps_draw" index="1" -->

```edn [field-box id="ps_draw" role="properties"]
{"id" "ps_draw" "index" 1 "name" "6 - hatch beside your line (launch)" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "6 - hatch beside your line (launch)" "_tombstone" false "frame" #afield/rect [60.0 2560.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "ps_draw_code_.kt" "lines" 27 "characters" 1552 "sha256" "0232d42b2b6045e9da25a3c1e47956f9c28443b225977dde993046ba0abd2827"}]}
```

```kotlin [field-property box="ps_draw" name="code" path="ps_draw_code_.kt"]
import afield.ai.learning.pen.*

// Launch, and the seeing Michaux pen hatches beside the yellow line in "pen". Draw a line of
// your own in "draw here", top to bottom works best, and it starts again beside yours,
// seeing everything else you have drawn as well.
val border = fline { moveTo(0, 0); lineTo(500, 0); lineTo(500, 300); lineTo(0, 300); lineTo(0, 0); color = vec(1, 1, 1, 0.2) }
val starter = fline { moveTo(120, 30); cubicTo(150, 100, 100, 170, 130, 270); color = vec(1.0, 0.85, 0.3, 1.0); thickness = 2.0 }
val canvas = lines(border, starter, channel = "draw here", w = 520.0, h = 320.0, editable = true, drawing = true)!!
val michaux = PenModel.bundled("michaux")
var writer: PenLine? = null
var shown = listOf<FLine>()
var seen = 1
frames {
    while (frame()) {
        val drawn = canvas.currentLines()
        if (drawn.size > seen) {
            seen = drawn.size
            val yours = drawn.last().copy().apply { color = vec(1.0, 0.85, 0.3, 1.0); thickness = 2.0 }
            shown = drawn.drop(1).dropLast(1).map { it.copy().apply { color = vec(1.0, 0.85, 0.3, 0.6) } } + yours
            writer = PenLine(yours, model = michaux, seed = seen.toLong(), surroundings = drawn.drop(1).dropLast(1))
        }
        val w = writer ?: continue
        // six points a frame; it runs past the end of your line before it turns back
        if (w.history.size < 2400) w.grow(6)
        lines(listOf(border) + shown + w.drawn.apply { color = vec(0.9, 0.92, 1.0, 1.0); thickness = 1.0 }, channel = "pen", w = 520.0, h = 320.0)
    }
}
```

## 3. 7 - cracks in your shape (launch)

<!-- field-box id="ps_shape" index="2" -->

```edn [field-box id="ps_shape" role="properties"]
{"id" "ps_shape" "index" 2 "name" "7 - cracks in your shape (launch)" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "7 - cracks in your shape (launch)" "_tombstone" false "frame" #afield/rect [60.0 3760.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "ps_shape_code_.kt" "lines" 26 "characters" 1459 "sha256" "06a92873ee34fc23b34eaa60417eafdac6a0aabbcd5a4b4c3901f62e9a24a9dd"}]}
```

```kotlin [field-property box="ps_shape" name="code" path="ps_shape_code_.kt"]
import afield.ai.learning.pen.*
import kotlin.math.*

// Launch, and the seeing cracks pen fills the yellow shape in "pen" with cracks that stop
// where they meet a line. Draw a closed shape of your own in "draw here" and it cracks yours.
val border = fline { moveTo(0, 0); lineTo(500, 0); lineTo(500, 300); lineTo(0, 300); lineTo(0, 0); color = vec(1, 1, 1, 0.2) }
val starter = FLine().also { f -> for (i in 0..24) { val a = i * 2 * PI / 24; val r = 90 * (1 + 0.15 * sin(3 * a) + 0.08 * cos(5 * a)); if (i == 0) f.moveTo(250 + cos(a) * r, 150 + sin(a) * r) else f.lineTo(250 + cos(a) * r, 150 + sin(a) * r) } }
    .apply { color = vec(1.0, 0.85, 0.3, 1.0); thickness = 2.0 }
val canvas = lines(border, starter, channel = "draw here", w = 520.0, h = 320.0, editable = true, drawing = true)!!
val cracks = PenModel.bundled("cracks")
var writer: PenLine? = null
var shape = starter
var seen = 1
frames {
    while (frame()) {
        val drawn = canvas.currentLines()
        if (drawn.size > seen) {
            seen = drawn.size
            shape = drawn.last().copy().apply { color = vec(1.0, 0.85, 0.3, 1.0); thickness = 2.0 }
            writer = PenLine(shape, model = cracks, seed = seen.toLong(), liftAtEnd = true)
        }
        val w = writer ?: continue
        if (w.history.size < 1200) w.grow(4)
        lines(border, shape, w.drawn.apply { color = vec(0.9, 0.92, 1.0, 1.0); thickness = 1.0 }, channel = "pen", w = 520.0, h = 320.0)
    }
}
```

## 4. 4 - a field of lines

<!-- field-box id="ps_field" index="3" -->

```edn [field-box id="ps_field" role="properties"]
{"id" "ps_field" "index" 3 "name" "4 - a field of lines" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "4 - a field of lines" "_tombstone" false "frame" #afield/rect [60.0 1560.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "ps_field_code_.kt" "lines" 9 "characters" 565 "sha256" "aaf0f96fc217f93b9f566c93f783c72bdcb1a902ef59b564e8c354b27ae848d3"}]}
```

```kotlin [field-property box="ps_field" name="code" path="ps_field_code_.kt"]
import afield.ai.learning.pen.*
import kotlin.math.*

// One short wavering stroke, then five thousand points from the seeing Michaux pen: columns
// of trembling lines, each pass following the one before it.
val first = FLine().also { f -> for (i in 0..60) { val y = i * 2.5; val x = 6 * sin(y / 25); if (i == 0) f.moveTo(x, y) else f.lineTo(x, y) } }
val writer = PenLine(first, model = PenModel.bundled("michaux"), seed = 7)
writer.grow(5000)
lines(writer.line.apply { color = vec(0.9, 0.92, 1.0, 1.0); thickness = 0.8 }, w = 520.0, h = 320.0, fitToSize = true)
```

## 5. 9 - hatch your drawing (launch)

<!-- field-box id="ps_doodle" index="4" -->

```edn [field-box id="ps_doodle" role="properties"]
{"id" "ps_doodle" "index" 4 "name" "9 - hatch your drawing (launch)" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "9 - hatch your drawing (launch)" "_tombstone" false "frame" #afield/rect [60.0 5460.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "ps_doodle_code_.kt" "lines" 32 "characters" 1590 "sha256" "76903c3b0b0ca775ce189bd5383f3acdc4e97b755f0d394353993ad48d52f958"}]}
```

```kotlin [field-property box="ps_doodle" name="code" path="ps_doodle_code_.kt"]
import afield.ai.learning.pen.*

// Launch, and the pen hatches around the drawing in "draw here", stroke by stroke, as you
// watch. Draw more and it starts again around everything. The checkbox switches the pen's hand.
val border = fline { moveTo(0, 0); lineTo(500, 0); lineTo(500, 300); lineTo(0, 300); lineTo(0, 0); color = vec(1, 1, 1, 0.2) }
val starter = listOf(
    fline { moveTo(90, 60); cubicTo(200, 20, 260, 220, 400, 160) },
    fline { moveTo(150, 250); cubicTo(180, 150, 300, 300, 330, 230) },
).onEach { it.color = vec(1.0, 0.85, 0.3, 1.0); it.thickness = 2.0 }
val canvas = lines(listOf(border) + starter, channel = "draw here", w = 520.0, h = 320.0, editable = true, drawing = true)!!
val hands = listOf("echoes", "michaux")
var hand = 0
var choice = 0
checkbox("Michaux's hand", channel = "hand") { choice = if (it) 1 else 0 }
var hatcher: Hatcher? = null
var drawing = listOf<FLine>()
var seen = -1
frames {
    while (frame()) {
        val drawn = canvas.currentLines().drop(1)
        if (drawn.size != seen || choice != hand) {
            seen = drawn.size
            hand = choice
            drawing = drawn.map { it.copy().apply { color = vec(1.0, 0.85, 0.3, 1.0); thickness = 2.0 } }
            hatcher = Hatcher(drawing, PenModel.bundled(hands[hand]), passes = 8, gap = 4.0, seed = seen.toLong())
        }
        val h = hatcher ?: continue
        if (!h.done) h.grow(24)
        lines(listOf(border) + drawing + h.hatching.map { it.apply { color = vec(0.9, 0.92, 1.0, 1.0); thickness = 0.8 } },
            channel = "pen", w = 520.0, h = 320.0)
    }
}
```

## 6. 1 - what it sees

<!-- field-box id="ps_sees" index="5" -->

```edn [field-box id="ps_sees" role="properties"]
{"id" "ps_sees" "index" 5 "name" "1 - what it sees" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1 - what it sees" "_tombstone" false "frame" #afield/rect [60.0 60.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "ps_sees_code_.kt" "lines" 14 "characters" 911 "sha256" "7f816869d57bcaf18d7c8e98c498fe5f2ad581cd124adabb0595d0daaf886989"}]}
```

```kotlin [field-property box="ps_sees" name="code" path="ps_sees_code_.kt"]
import afield.ai.learning.pen.*
import kotlin.math.*

// The seeing Michaux pen hatching beside a wavering line, stopped at four moments. At each,
// the faint circle is as far as it can see and each orange ray runs to the nearest ink in one
// of its sixteen directions, brighter the closer it is.
val yours = FLine().also { f -> for (i in 0..120) { val y = i * 2.0; val x = 14 * sin(y / 40) + 5 * sin(y / 13); if (i == 0) f.moveTo(x, y) else f.lineTo(x, y) } }
val writer = PenLine(yours, model = PenModel.bundled("michaux"), seed = 1)
val moments = listOf(60, 180, 320, 520).map { until ->
    writer.grow(until - writer.history.size)
    listOf(yours.copy().apply { color = vec(1.0, 0.85, 0.3, 1.0); thickness = 1.5 },
        writer.drawn.copy().apply { color = vec(0.9, 0.92, 1.0, 1.0); thickness = 1.0 }) + writer.seeing()
}
lines(sideBySide(moments, gap = 40.0), w = 520.0, h = 300.0, fitToSize = true)
```

## 7. 3 - cracks that stop at a line

<!-- field-box id="ps_cracks" index="6" -->

```edn [field-box id="ps_cracks" role="properties"]
{"id" "ps_cracks" "index" 6 "name" "3 - cracks that stop at a line" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "3 - cracks that stop at a line" "_tombstone" false "frame" #afield/rect [60.0 1060.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "ps_cracks_code_.kt" "lines" 13 "characters" 1014 "sha256" "7e88b7ab0806eb1347105caa09825097e24068dd24ed2cf0bcf6bef0573daa7d"}]}
```

```kotlin [field-property box="ps_cracks" name="code" path="ps_cracks_code_.kt"]
import afield.ai.learning.pen.*
import kotlin.math.*

// A wobbling outline handed to the seeing cracks pen three times and its blind twin twice.
// liftAtEnd = true: after a finished outline, the next thing in its corpus is a new stroke.
val outline = FLine().also { f -> for (i in 0..90) { val a = i * 2 * PI / 90; val r = 60 * (1 + 0.15 * sin(3 * a) + 0.08 * cos(5 * a)); if (i == 0) f.moveTo(cos(a) * r, sin(a) * r) else f.lineTo(cos(a) * r, sin(a) * r) } }
fun inside(model: String, seed: Long) = listOf(
    outline.copy().apply { color = vec(1.0, 0.85, 0.3, 1.0); thickness = 1.5 },
    outline.continueWith(PenModel.bundled(model), steps = 700, seed = seed, liftAtEnd = true).apply {
        color = if (model.endsWith("blind")) vec(1.0, 0.6, 0.6, 1.0) else vec(0.9, 0.92, 1.0, 1.0); thickness = 1.0
    })
lines(sideBySide(listOf(inside("cracks", 1), inside("cracks", 2), inside("cracks", 3), inside("cracks-blind", 1), inside("cracks-blind", 2)), gap = 30.0),
    w = 520.0, h = 300.0, fitToSize = true)
```

## 8. 5 - seeing against blind, measured

<!-- field-box id="ps_measure" index="7" -->

```edn [field-box id="ps_measure" role="properties"]
{"id" "ps_measure" "index" 7 "name" "5 - seeing against blind, measured" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "5 - seeing against blind, measured" "_tombstone" false "frame" #afield/rect [60.0 2060.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "ps_measure_code_.kt" "lines" 16 "characters" 1230 "sha256" "32656ae3c2ace83a48ec085655379f1b5b11b443441c44d8b95d4546ee3c9ac0"}]}
```

```kotlin [field-property box="ps_measure" name="code" path="ps_measure_code_.kt"]
import afield.ai.learning.pen.*
import kotlin.math.*

// following: the share of the pen's points with older ink within 8 pixels. joins: the share
// of its strokes that end on ink. Twenty seeds each, the same primers as boxes 2 and 3.
val yours = FLine().also { f -> for (i in 0..120) { val y = i * 2.0; val x = 14 * sin(y / 40) + 5 * sin(y / 13); if (i == 0) f.moveTo(x, y) else f.lineTo(x, y) } }
val outline = FLine().also { f -> for (i in 0..90) { val a = i * 2 * PI / 90; val r = 60 * (1 + 0.15 * sin(3 * a) + 0.08 * cos(5 * a)); if (i == 0) f.moveTo(cos(a) * r, sin(a) * r) else f.lineTo(cos(a) * r, sin(a) * r) } }
fun mean(f: (Long) -> Double) = "%.2f".format((0 until 20).map { f(it.toLong()) }.average())
val hatching = { m: String -> mean { s -> following(yours.continueWith(PenModel.bundled(m), steps = 1500, seed = s)) } }
val cracking = { m: String -> mean { s -> joins(outline.continueWith(PenModel.bundled(m), steps = 700, seed = s, liftAtEnd = true)) } }
markdown("measures", """
| | seeing | blind |
|---|---|---|
| Michaux beside a line: following | ${hatching("michaux")} | ${hatching("michaux-blind")} |
| cracks in an outline: joins | ${cracking("cracks")} | ${cracking("cracks-blind")} |
""".trimIndent())
```

## 9. 8 - hatching around a word

<!-- field-box id="ps_word" index="8" -->

```edn [field-box id="ps_word" role="properties"]
{"id" "ps_word" "index" 8 "name" "8 - hatching around a word" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "8 - hatching around a word" "_tombstone" false "frame" #afield/rect [60.0 4960.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "ps_word_code_.kt" "lines" 9 "characters" 681 "sha256" "e82b1762f52f984a72cc85889fdf24b6387cc6a74f56b254a806d989e35c81c6"}]}
```

```kotlin [field-property box="ps_word" name="code" path="ps_word_code_.kt"]
import afield.ai.learning.pen.*

// Every stroke of the word gets ten passes of the echoes pen's trembling line, each kept
// 3.5 pixels from the ink beside it. The pen proposes 32 next points at a time and a
// FollowGuide picks the one that keeps the gap without crossing anything. Try "michaux".
val word = HersheyFont.load("futural").text("FIELD", size = 90.0).apply { color = vec(1.0, 0.85, 0.3, 1.0); thickness = 2.0 }
val hatching = Hatcher(listOf(word), PenModel.bundled("echoes"), passes = 10, gap = 3.5, seed = 1).finish()
hatching.forEach { it.color = vec(0.9, 0.92, 1.0, 1.0); it.thickness = 0.7 }
lines(listOf(word) + hatching, w = 520.0, h = 220.0, fitToSize = true)
```

