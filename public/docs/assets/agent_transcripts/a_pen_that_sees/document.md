# Field Document: a_pen_that_sees

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 9 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. 2 - beside a line, seeing and blind

<!-- field-box id="ps_hatch" index="0" -->

```edn [field-box id="ps_hatch" role="properties"]
{"id" "ps_hatch" "index" 0 "name" "2 - beside a line, seeing and blind" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2 - beside a line, seeing and blind" "_tombstone" false "frame" #afield/rect [60.0 560.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "ps_hatch_code_.kt" "lines" 37 "characters" 1020 "sha256" "466345b334f4171b1fceea6b4cad59b2c4eead49f78103a3a61c29a5c2ec0f4c"}]}
```

```kotlin [field-property box="ps_hatch" name="code" path="ps_hatch_code_.kt"]
import afield.ai.learning.pen.*

// The same yellow line continued three times by the seeing Michaux pen (white) and twice by
// its blind twin (red), trained on the same drawings without sight.
val yours = fline {
    breakNext()
    for (i in 0..120) {
        val y = i * 2.0
        val x = 14 * sin(y / 40) + 5 * sin(y / 13)
        lineTo(x, y)
    }
}
fun beside(model: String, seed: Long) = [
    yours.copy().apply {
        color = vec(1.0, 0.85, 0.3, 1.0)
        thickness = 1.5
    },
    yours.continueWith(PenModel.bundled(model), steps = 1500, seed = seed).apply {
        color = if (model.endsWith("blind")) vec(1.0, 0.6, 0.6, 1.0) else vec(0.9, 0.92, 1.0, 1.0)
        thickness = 1.0
    },
]
lines(
    sideBySide(
        [
            beside("michaux", 1),
            beside("michaux", 2),
            beside("michaux", 3),
            beside("michaux-blind", 1),
            beside("michaux-blind", 2),
        ],
        gap = 30.0,
    ),
    w = 520.0,
    h = 440.0,
    fitToSize = true,
)
```

## 2. 6 - hatch beside your line (launch)

<!-- field-box id="ps_draw" index="1" -->

```edn [field-box id="ps_draw" role="properties"]
{"id" "ps_draw" "index" 1 "name" "6 - hatch beside your line (launch)" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "6 - hatch beside your line (launch)" "_tombstone" false "frame" #afield/rect [60.0 2560.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "ps_draw_code_.kt" "lines" 54 "characters" 1764 "sha256" "eededb7379f3b95c2d773374ca1aafdcaf6f8bb5c509bff2ff58f0b95fbf93ff"}]}
```

```kotlin [field-property box="ps_draw" name="code" path="ps_draw_code_.kt"]
import afield.ai.learning.pen.*

/*
Launch, and the seeing Michaux pen hatches beside the yellow line in "pen". Draw a line of
your own in "draw here", top to bottom works best, and it starts again beside yours,
seeing everything else you have drawn as well.
*/
val border = fline {
    moveTo(0, 0)
    lineTo(500, 0)
    lineTo(500, 300)
    lineTo(0, 300)
    lineTo(0, 0)
    color = vec(1, 1, 1, 0.2)
}
val starter = fline {
    moveTo(120, 30)
    cubicTo(150, 100, 100, 170, 130, 270)
    color = vec(1.0, 0.85, 0.3, 1.0)
    thickness = 2.0
}
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
            val yours = drawn.last().copy().apply {
                color = vec(1.0, 0.85, 0.3, 1.0)
                thickness = 2.0
            }
            shown = drawn.drop(1).dropLast(1).map { it.copy().apply { color = vec(1.0, 0.85, 0.3, 0.6) } } + yours
            writer = PenLine(yours, model = michaux, seed = seen.toLong(), surroundings = drawn.drop(1).dropLast(1))
        }
        val w = writer ?: continue
        // six points a frame; it runs past the end of your line before it turns back
        if (w.history.size < 2400) w.grow(6)
        lines(
            [border] +
                shown +
                w.drawn.apply {
                    color = vec(0.9, 0.92, 1.0, 1.0)
                    thickness = 1.0
                },
            channel = "pen",
            w = 520.0,
            h = 320.0,
        )
    }
}
```

## 3. 7 - cracks in your shape (launch)

<!-- field-box id="ps_shape" index="2" -->

```edn [field-box id="ps_shape" role="properties"]
{"id" "ps_shape" "index" 2 "name" "7 - cracks in your shape (launch)" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "7 - cracks in your shape (launch)" "_tombstone" false "frame" #afield/rect [60.0 3760.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "ps_shape_code_.kt" "lines" 55 "characters" 1615 "sha256" "f7fef8c21450755f5bb41a2777b6711478fe812bb3c4d17bbc9d56ef6fffe3a5"}]}
```

```kotlin [field-property box="ps_shape" name="code" path="ps_shape_code_.kt"]
import afield.ai.learning.pen.*

// Launch, and the seeing cracks pen fills the yellow shape in "pen" with cracks that stop
// where they meet a line. Draw a closed shape of your own in "draw here" and it cracks yours.
val border = fline {
    moveTo(0, 0)
    lineTo(500, 0)
    lineTo(500, 300)
    lineTo(0, 300)
    lineTo(0, 0)
    color = vec(1, 1, 1, 0.2)
}
val starter = fline {
    breakNext()
    for (i in 0..24) {
        val a = i * 2 * PI / 24
        val r = 90 * (1 + 0.15 * sin(3 * a) + 0.08 * cos(5 * a))
        lineTo(250 + cos(a) * r, 150 + sin(a) * r)
    }
}
    .apply {
        color = vec(1.0, 0.85, 0.3, 1.0)
        thickness = 2.0
    }
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
            shape = drawn.last().copy().apply {
                color = vec(1.0, 0.85, 0.3, 1.0)
                thickness = 2.0
            }
            writer = PenLine(shape, model = cracks, seed = seen.toLong(), liftAtEnd = true)
        }
        val w = writer ?: continue
        if (w.history.size < 1200) w.grow(4)
        lines(
            border,
            shape,
            w.drawn.apply {
                color = vec(0.9, 0.92, 1.0, 1.0)
                thickness = 1.0
            },
            channel = "pen",
            w = 520.0,
            h = 320.0,
        )
    }
}
```

## 4. 4 - a field of lines

<!-- field-box id="ps_field" index="3" -->

```edn [field-box id="ps_field" role="properties"]
{"id" "ps_field" "index" 3 "name" "4 - a field of lines" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "4 - a field of lines" "_tombstone" false "frame" #afield/rect [60.0 1560.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "ps_field_code_.kt" "lines" 23 "characters" 582 "sha256" "adacf659146ddd0713c3f516f82c3f8f73a0295f7a778c8ff1b88792672b390a"}]}
```

```kotlin [field-property box="ps_field" name="code" path="ps_field_code_.kt"]
import afield.ai.learning.pen.*

// One short wavering stroke, then five thousand points from the seeing Michaux pen: columns
// of trembling lines, each pass following the one before it.
val first = fline {
    breakNext()
    for (i in 0..60) {
        val y = i * 2.5
        val x = 6 * sin(y / 25)
        lineTo(x, y)
    }
}
val writer = PenLine(first, model = PenModel.bundled("michaux"), seed = 7)
writer.grow(5000)
lines(
    writer.line.apply {
        color = vec(0.9, 0.92, 1.0, 1.0)
        thickness = 0.8
    },
    w = 520.0,
    h = 320.0,
    fitToSize = true,
)
```

## 5. 9 - hatch your drawing (launch)

<!-- field-box id="ps_doodle" index="4" -->

```edn [field-box id="ps_doodle" role="properties"]
{"id" "ps_doodle" "index" 4 "name" "9 - hatch your drawing (launch)" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "9 - hatch your drawing (launch)" "_tombstone" false "frame" #afield/rect [60.0 5460.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "ps_doodle_code_.kt" "lines" 64 "characters" 1895 "sha256" "0119cd4c2edef0643a2d5bff420c183f4d67bbbadfff1b008c61120ea253173c"}]}
```

```kotlin [field-property box="ps_doodle" name="code" path="ps_doodle_code_.kt"]
import afield.ai.learning.pen.*

// Launch, and the pen hatches around the drawing in "draw here", stroke by stroke, as you
// watch. Draw more and it starts again around everything. The checkbox switches the pen's hand.
val border = fline {
    moveTo(0, 0)
    lineTo(500, 0)
    lineTo(500, 300)
    lineTo(0, 300)
    lineTo(0, 0)
    color = vec(1, 1, 1, 0.2)
}
val starter = [
    fline {
        moveTo(90, 60)
        cubicTo(200, 20, 260, 220, 400, 160)
    },
    fline {
        moveTo(150, 250)
        cubicTo(180, 150, 300, 300, 330, 230)
    },
].onEach {
    it.color = vec(1.0, 0.85, 0.3, 1.0)
    it.thickness = 2.0
}
val canvas = lines([border] + starter, channel = "draw here", w = 520.0, h = 320.0, editable = true, drawing = true)!!
val hands = ["echoes", "michaux"]
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
            drawing = drawn.map {
                it.copy().apply {
                    color = vec(1.0, 0.85, 0.3, 1.0)
                    thickness = 2.0
                }
            }
            hatcher = Hatcher(drawing, PenModel.bundled(hands[hand]), passes = 8, gap = 4.0, seed = seen.toLong())
        }
        val h = hatcher ?: continue
        if (!h.done) h.grow(24)
        lines(
            [border] +
                drawing +
                h.hatching.map {
                    it.apply {
                        color = vec(0.9, 0.92, 1.0, 1.0)
                        thickness = 0.8
                    }
                },
            channel = "pen",
            w = 520.0,
            h = 320.0,
        )
    }
}
```

## 6. 1 - what it sees

<!-- field-box id="ps_sees" index="5" -->

```edn [field-box id="ps_sees" role="properties"]
{"id" "ps_sees" "index" 5 "name" "1 - what it sees" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1 - what it sees" "_tombstone" false "frame" #afield/rect [60.0 60.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "ps_sees_code_.kt" "lines" 31 "characters" 1004 "sha256" "0af17d399438935b70c434d7f65b99d05346ec54d287c845f2d400ea5d287de9"}]}
```

```kotlin [field-property box="ps_sees" name="code" path="ps_sees_code_.kt"]
import afield.ai.learning.pen.*

/*
The seeing Michaux pen hatching beside a wavering line, stopped at four moments. At each,
the faint circle is as far as it can see and each orange ray runs to the nearest ink in one
of its sixteen directions, brighter the closer it is.
*/
val yours = fline {
    breakNext()
    for (i in 0..120) {
        val y = i * 2.0
        val x = 14 * sin(y / 40) + 5 * sin(y / 13)
        lineTo(x, y)
    }
}
val writer = PenLine(yours, model = PenModel.bundled("michaux"), seed = 1)
val moments =
    [60, 180, 320, 520].map { until ->
        writer.grow(until - writer.history.size)
        [
            yours.copy().apply {
                color = vec(1.0, 0.85, 0.3, 1.0)
                thickness = 1.5
            },
            writer.drawn.copy().apply {
                color = vec(0.9, 0.92, 1.0, 1.0)
                thickness = 1.0
            },
        ] + writer.seeing()
    }
lines(sideBySide(moments, gap = 40.0), w = 520.0, h = 300.0, fitToSize = true)
```

## 7. 3 - cracks that stop at a line

<!-- field-box id="ps_cracks" index="6" -->

```edn [field-box id="ps_cracks" role="properties"]
{"id" "ps_cracks" "index" 6 "name" "3 - cracks that stop at a line" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "3 - cracks that stop at a line" "_tombstone" false "frame" #afield/rect [60.0 1060.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "ps_cracks_code_.kt" "lines" 37 "characters" 1100 "sha256" "8a84cd965c466560b7060315292ec1ac917196710830c8edd6c8260e89e1d6bb"}]}
```

```kotlin [field-property box="ps_cracks" name="code" path="ps_cracks_code_.kt"]
import afield.ai.learning.pen.*

// A wobbling outline handed to the seeing cracks pen three times and its blind twin twice.
// liftAtEnd = true: after a finished outline, the next thing in its corpus is a new stroke.
val outline = fline {
    breakNext()
    for (i in 0..90) {
        val a = i * 2 * PI / 90
        val r = 60 * (1 + 0.15 * sin(3 * a) + 0.08 * cos(5 * a))
        lineTo(cos(a) * r, sin(a) * r)
    }
}
fun inside(model: String, seed: Long) = [
    outline.copy().apply {
        color = vec(1.0, 0.85, 0.3, 1.0)
        thickness = 1.5
    },
    outline.continueWith(PenModel.bundled(model), steps = 700, seed = seed, liftAtEnd = true).apply {
        color = if (model.endsWith("blind")) vec(1.0, 0.6, 0.6, 1.0) else vec(0.9, 0.92, 1.0, 1.0)
        thickness = 1.0
    },
]
lines(
    sideBySide(
        [
            inside("cracks", 1),
            inside("cracks", 2),
            inside("cracks", 3),
            inside("cracks-blind", 1),
            inside("cracks-blind", 2),
        ],
        gap = 30.0,
    ),
    w = 520.0,
    h = 300.0,
    fitToSize = true,
)
```

## 8. 5 - seeing against blind, measured

<!-- field-box id="ps_measure" index="7" -->

```edn [field-box id="ps_measure" role="properties"]
{"id" "ps_measure" "index" 7 "name" "5 - seeing against blind, measured" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "5 - seeing against blind, measured" "_tombstone" false "frame" #afield/rect [60.0 2060.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "ps_measure_code_.kt" "lines" 35 "characters" 1205 "sha256" "02eb0b5f48dedf7c4b786405145ece061d94175ef5cca074976fb5b2a40d292a"}]}
```

```kotlin [field-property box="ps_measure" name="code" path="ps_measure_code_.kt"]
import afield.ai.learning.pen.*

// following: the share of the pen's points with older ink within 8 pixels. joins: the share
// of its strokes that end on ink. Twenty seeds each, the same primers as boxes 2 and 3.
val yours = fline {
    breakNext()
    for (i in 0..120) {
        val y = i * 2.0
        val x = 14 * sin(y / 40) + 5 * sin(y / 13)
        lineTo(x, y)
    }
}
val outline = fline {
    breakNext()
    for (i in 0..90) {
        val a = i * 2 * PI / 90
        val r = 60 * (1 + 0.15 * sin(3 * a) + 0.08 * cos(5 * a))
        lineTo(cos(a) * r, sin(a) * r)
    }
}
fun mean(f: (Long) -> Double) = ((0 until 20).map { f(it.toLong()) }.average()).f2
val hatching = { m: String -> mean { s -> following(yours.continueWith(PenModel.bundled(m), steps = 1500, seed = s)) } }
val cracking = { m: String ->
    mean { s -> joins(outline.continueWith(PenModel.bundled(m), steps = 700, seed = s, liftAtEnd = true)) }
}
markdown(
    "measures",
    """
| | seeing | blind |
|---|---|---|
| Michaux beside a line: following | ${hatching("michaux")} | ${hatching("michaux-blind")} |
| cracks in an outline: joins | ${cracking("cracks")} | ${cracking("cracks-blind")} |
"""
        .trimIndent(),
)
```

## 9. 8 - hatching around a word

<!-- field-box id="ps_word" index="8" -->

```edn [field-box id="ps_word" role="properties"]
{"id" "ps_word" "index" 8 "name" "8 - hatching around a word" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "8 - hatching around a word" "_tombstone" false "frame" #afield/rect [60.0 4960.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "ps_word_code_.kt" "lines" 17 "characters" 680 "sha256" "b4373c5036744ce9fcce2b759a175345dc96ee42755537a9d30d8452a49beab1"}]}
```

```kotlin [field-property box="ps_word" name="code" path="ps_word_code_.kt"]
import afield.ai.learning.pen.*

/*
Every stroke of the word gets ten passes of the echoes pen's trembling line, each kept
3.5 pixels from the ink beside it. The pen proposes 32 next points at a time and a
FollowGuide picks the one that keeps the gap without crossing anything. Try "michaux".
*/
val word = HersheyFont.load("futural").text("FIELD", size = 90.0).apply {
    color = vec(1.0, 0.85, 0.3, 1.0)
    thickness = 2.0
}
val hatching = Hatcher([word], PenModel.bundled("echoes"), passes = 10, gap = 3.5, seed = 1).finish()
hatching.forEach {
    it.color = vec(0.9, 0.92, 1.0, 1.0)
    it.thickness = 0.7
}
lines([word] + hatching, w = 520.0, h = 220.0, fitToSize = true)
```

