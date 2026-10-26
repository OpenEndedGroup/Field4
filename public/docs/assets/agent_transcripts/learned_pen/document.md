# Field Document: learned_pen

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 4 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. 1 - continue a line

<!-- field-box id="lp_continue" index="0" -->

```edn [field-box id="lp_continue" role="properties"]
{"id" "lp_continue" "index" 0 "name" "1 - continue a line" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1 - continue a line" "_tombstone" false "frame" #afield/rect [60.0 60.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "lp_continue_code_.kt" "lines" 14 "characters" 548 "sha256" "378983f72d6ba9e137de8515c4e96b0adca11144c09ff73ca2d84e931bd7ee79"}]}
```

```kotlin [field-property box="lp_continue" name="code" path="lp_continue_code_.kt"]
import afield.ai.learning.pen.*

// A few strokes of our own, in canvas pixels. The pen is shown all of them, then carries on
// from where the last one ends. Change the seed for a different continuation.
val start = fline {
    moveTo(0, 0)
    cubicTo(6, -26, 22, -26, 20, -4)
    cubicTo(18, 8, 30, 10, 38, -2)
    lineTo(46, -14)
    color = vec(1.0, 0.85, 0.3, 1.0)
    thickness = 2.0
}
val more = start.continueWith(steps = 500, seed = 3).apply { color = vec(0.85, 0.9, 1.0, 1.0) }
lines(start, more, w = 520.0, h = 260.0, fitToSize = true)
```

## 2. 4 - one memory cell

<!-- field-box id="lp_cell" index="1" -->

```edn [field-box id="lp_cell" role="properties"]
{"id" "lp_cell" "index" 1 "name" "4 - one memory cell" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "4 - one memory cell" "_tombstone" false "frame" #afield/rect [60.0 2560.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "lp_cell_code_.kt" "lines" 16 "characters" 707 "sha256" "58a4a56a63dddfb766f64f8df4d7802f2525d6e846a80455fea0cdc794a393fc"}]}
```

```kotlin [field-property box="lp_cell" name="code" path="lp_cell_code_.kt"]
import afield.ai.learning.pen.*

// The same pen, the same seed, written three times. The middle row holds one memory cell at
// +4 the whole way, the bottom row at -4. Which cell does what is something to find out:
// try other cells (0 until 500) and values.
val cell = 136
fun row(value: Double?, y: Double): FLine {
    val writer = PenLine(start = vec(0.0, y, 0.0), seed = 11)
    repeat(400) {
        if (value != null) (writer.pen as Pen).setCell(cell, value)
        writer.grow()
    }
    return writer.line
}
lines(row(null, 0.0), row(4.0, 140.0).apply { color = vec(0.5, 1.0, 0.6, 1.0) },
    row(-4.0, 280.0).apply { color = vec(1.0, 0.55, 0.6, 1.0) }, w = 520.0, h = 340.0, fitToSize = true)
```

## 3. 3 - draw, and it writes on (launch)

<!-- field-box id="lp_draw" index="2" -->

```edn [field-box id="lp_draw" role="properties"]
{"id" "lp_draw" "index" 2 "name" "3 - draw, and it writes on (launch)" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "3 - draw, and it writes on (launch)" "_tombstone" false "frame" #afield/rect [60.0 1060.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "lp_draw_code_.kt" "lines" 33 "characters" 1611 "sha256" "58748cbb1008809ee01999367d3a00f2c0d49a51b849a919d34951bab880b0af"}]}
```

```kotlin [field-property box="lp_draw" name="code" path="lp_draw_code_.kt"]
import afield.ai.learning.pen.*

// Launch, and the pen writes on from the yellow stroke in "pen". Draw a stroke of your own in
// "draw here" and it starts again from yours. The sliders change how it samples as it goes.
var temperature = 0.65
var noise = 0.65
controlGroup("pen controls") {
    slider("temperature", min = 0.05, max = 2.0, initialValue = temperature) { temperature = it }
    slider("noise", min = 0.0, max = 1.5, initialValue = noise) { noise = it }
}

val border = fline { moveTo(0, 0); lineTo(500, 0); lineTo(500, 240); lineTo(0, 240); lineTo(0, 0); color = vec(1, 1, 1, 0.2) }
val starter = fline { moveTo(30, 120); cubicTo(36, 96, 52, 96, 50, 116); cubicTo(48, 128, 60, 130, 68, 118); color = vec(1.0, 0.85, 0.3, 1.0); thickness = 2.0 }
val canvas = lines(border, starter, channel = "draw here", w = 520.0, h = 260.0, editable = true, drawing = true)!!
var primer = starter
var writer: PenLine? = null
var seen = 1
frames {
    while (frame()) {
        val drawn = canvas.currentLines()
        if (drawn.size > seen) {
            seen = drawn.size
            primer = drawn.last().copy().apply { color = vec(1.0, 0.85, 0.3, 1.0); thickness = 2.0 }
            writer = PenLine(primer)
        }
        val w = writer ?: continue
        w.controls = PenControls(temperature = temperature, noise = noise)
        // four points a frame, until it writes off the right-hand edge
        val x = w.drawn.currentPoint?.x ?: 0f
        if (x < 490f) w.grow(4)
        lines(border, primer, w.drawn.apply { color = vec(0.85, 0.9, 1.0, 1.0) }, channel = "pen", w = 520.0, h = 260.0)
    }
}
```

## 4. 2 - futures

<!-- field-box id="lp_futures" index="3" -->

```edn [field-box id="lp_futures" role="properties"]
{"id" "lp_futures" "index" 3 "name" "2 - futures" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2 - futures" "_tombstone" false "frame" #afield/rect [60.0 560.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "lp_futures_code_.kt" "lines" 20 "characters" 732 "sha256" "95b461b333911718143e93769b693875112b057ee96d3285d4eb5508bfe72044"}]}
```

```kotlin [field-property box="lp_futures" name="code" path="lp_futures_code_.kt"]
import afield.ai.learning.pen.*

// Twelve things the pen might do next, from the same line, each drawn beside its own copy
// of the line. They share the pen's reading of the yellow stroke and differ only in the
// random choices after it.
val start = fline {
    moveTo(0, 0)
    cubicTo(4, -14, 12, -14, 12, 0)
    cubicTo(12, 8, 20, 8, 24, 0)
    color = vec(1.0, 0.85, 0.3, 1.0)
    thickness = 1.5
}
val futures = start.futuresWith(count = 12, steps = 80)
val grid = mutableListOf<FLine>()
futures.forEachIndexed { k, future ->
    val at = vec(k % 3 * 170.0, k / 3 * 60.0, 0.0)
    grid += start + at
    grid += (future + at).apply { color = vec(0.75, 0.85, 1.0, 1.0) }
}
lines(grid, w = 520.0, h = 260.0, fitToSize = true)
```

