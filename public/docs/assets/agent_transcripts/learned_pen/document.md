# Field Document: learned_pen

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 6 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
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

## 2. 6 - which cell? (launch)

<!-- field-box id="lp_cells" index="1" -->

```edn [field-box id="lp_cells" role="properties"]
{"id" "lp_cells" "index" 1 "name" "6 - which cell? (launch)" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "6 - which cell? (launch)" "_tombstone" false "frame" #afield/rect [60.0 4760.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "lp_cells_code_.kt" "lines" 26 "characters" 945 "sha256" "b788df3f5cc4a1738e0b2e0edadfd1dd5740a4899d462b7f92a1a3c58c07fc04"}]}
```

```kotlin [field-property box="lp_cells" name="code" path="lp_cells_code_.kt"]
import afield.ai.learning.pen.*

/*
The pen writes 500 points once, remembering every cell's output at every point. Drag the
slider to colour the line by a cell: blue where it is low, orange where it is high. The
"liveliest" line lists the cells that changed most, the ones worth trying first.
*/
val start = fline {
    moveTo(0, 0)
    cubicTo(4, -14, 12, -14, 12, 0)
    cubicTo(12, 8, 20, 8, 24, 0)
}
val writer = PenLine(start, seed = 4, recordMemory = true)
writer.grow(500)
val lively = writer.liveliestCells(12)
markdown("liveliest", "liveliest cells: ${lively.joinToString(", ")}")
var cell = lively[0]
var shown = -1
slider("cell", min = 0.0, max = 499.0, step = 1.0, initialValue = cell.toDouble()) { cell = it.toInt() }
frames {
    while (frame()) {
        if (cell == shown) continue
        shown = cell
        lines(writer.coloredByCell(cell, thickness = 2.0), channel = "cell", w = 520.0, h = 200.0, fitToSize = true)
    }
}
```

## 3. 4 - one memory cell

<!-- field-box id="lp_cell" index="2" -->

```edn [field-box id="lp_cell" role="properties"]
{"id" "lp_cell" "index" 2 "name" "4 - one memory cell" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "4 - one memory cell" "_tombstone" false "frame" #afield/rect [60.0 2560.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "lp_cell_code_.kt" "lines" 24 "characters" 727 "sha256" "0d5e231e6d286c81de73393296d2dfb44cdba26b37082cedaf80c4051a62f5b9"}]}
```

```kotlin [field-property box="lp_cell" name="code" path="lp_cell_code_.kt"]
import afield.ai.learning.pen.*

/*
The same pen, the same seed, written three times. The middle row holds one memory cell at
+4 the whole way, the bottom row at -4. Which cell does what is something to find out:
try other cells (0 until 500) and values.
*/
val cell = 136
fun row(value: Double?, y: Double): FLine {
    val writer = PenLine(start = vec(0.0, y, 0.0), seed = 11)
    repeat(400) {
        if (value != null) (writer.pen as Pen).setCell(cell, value)
        writer.grow()
    }
    return writer.line
}
lines(
    row(null, 0.0),
    row(4.0, 140.0).apply { color = vec(0.5, 1.0, 0.6, 1.0) },
    row(-4.0, 280.0).apply { color = vec(1.0, 0.55, 0.6, 1.0) },
    w = 520.0,
    h = 340.0,
    fitToSize = true,
)
```

## 4. 3 - draw, and it writes on (launch)

<!-- field-box id="lp_draw" index="3" -->

```edn [field-box id="lp_draw" role="properties"]
{"id" "lp_draw" "index" 3 "name" "3 - draw, and it writes on (launch)" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "3 - draw, and it writes on (launch)" "_tombstone" false "frame" #afield/rect [60.0 1060.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "lp_draw_code_.kt" "lines" 49 "characters" 1689 "sha256" "d3f488463e98d238c67dfd07f36c0b098eadff78c743f142d26300054c1cb20e"}]}
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

val border = fline {
    moveTo(0, 0)
    lineTo(500, 0)
    lineTo(500, 240)
    lineTo(0, 240)
    lineTo(0, 0)
    color = vec(1, 1, 1, 0.2)
}
val starter = fline {
    moveTo(30, 120)
    cubicTo(36, 96, 52, 96, 50, 116)
    cubicTo(48, 128, 60, 130, 68, 118)
    color = vec(1.0, 0.85, 0.3, 1.0)
    thickness = 2.0
}
val canvas = lines(border, starter, channel = "draw here", w = 520.0, h = 260.0, editable = true, drawing = true)!!
var primer = starter
var writer: PenLine? = null
var seen = 1
frames {
    while (frame()) {
        val drawn = canvas.currentLines()
        if (drawn.size > seen) {
            seen = drawn.size
            primer = drawn.last().copy().apply {
                color = vec(1.0, 0.85, 0.3, 1.0)
                thickness = 2.0
            }
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

## 5. 2 - futures

<!-- field-box id="lp_futures" index="4" -->

```edn [field-box id="lp_futures" role="properties"]
{"id" "lp_futures" "index" 4 "name" "2 - futures" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2 - futures" "_tombstone" false "frame" #afield/rect [60.0 560.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "lp_futures_code_.kt" "lines" 22 "characters" 729 "sha256" "1056f4ee3f9a3a4cd6cf067a8629f8d55d6ca2002a54257dd1d3000090bad0ea"}]}
```

```kotlin [field-property box="lp_futures" name="code" path="lp_futures_code_.kt"]
import afield.ai.learning.pen.*

/*
Twelve things the pen might do next, from the same line, each drawn beside its own copy
of the line. They share the pen's reading of the yellow stroke and differ only in the
random choices after it.
*/
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

## 6. 5 - what it expects (launch)

<!-- field-box id="lp_expects" index="5" -->

```edn [field-box id="lp_expects" role="properties"]
{"id" "lp_expects" "index" 5 "name" "5 - what it expects (launch)" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "5 - what it expects (launch)" "_tombstone" false "frame" #afield/rect [60.0 3060.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "lp_expects_code_.kt" "lines" 26 "characters" 972 "sha256" "c8ef3f62f382219ec92eed12aeb23a8a12b000eb4ea559453c91d1e06d42febe"}]}
```

```kotlin [field-property box="lp_expects" name="code" path="lp_expects_code_.kt"]
import afield.ai.learning.pen.*

/*
Launch, and the pen writes two points a frame. "page" draws its line thick where it is sure
to stay on the paper and thin where it expects to lift. "tip" is a magnifier on the last
dozen points, with an ellipse for each of its guesses about where the next point will land.
*/
val start = fline {
    moveTo(0, 0)
    cubicTo(4, -14, 12, -14, 12, 0)
    cubicTo(12, 8, 20, 8, 24, 0)
    color = vec(1.0, 0.85, 0.3, 1.0)
    thickness = 2.0
}
var noise = 1.0
slider("noise", min = 0.1, max = 1.5, initialValue = noise) { noise = it }
val writer = PenLine(start, seed = 9)
frames {
    while (frame()) {
        if (writer.history.size > 600) continue
        writer.controls = PenControls(noise = noise)
        writer.grow(2)
        lines([start] + writer.widthByLift(), channel = "page", w = 520.0, h = 200.0, fitToSize = true)
        lines(writer.closeUp(12).lines(), channel = "tip", w = 520.0, h = 260.0, fitToSize = true)
    }
}
```

