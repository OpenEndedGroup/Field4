# Field Document: teaching_a_pen

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 7 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. 1 - the corpora

<!-- field-box id="tp_corpora" index="0" -->

```edn [field-box id="tp_corpora" role="properties"]
{"id" "tp_corpora" "index" 0 "name" "1 - the corpora" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1 - the corpora" "_tombstone" false "frame" #afield/rect [60.0 60.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "tp_corpora_code_.kt" "lines" 20 "characters" 1014 "sha256" "f0309077a13d9a6e127143fd750f4d3b8f5270b588daf98ae827620ac3c96a07"}]}
```

```kotlin [field-property box="tp_corpora" name="code" path="tp_corpora_code_.kt"]
import afield.ai.learning.pen.*

// Three drawings from each generator, every one drawn in the order a hand or a plotter would.
// That order is all a pen learns from, and the thing a scanned drawing cannot give you.
val rows = [
    (0 until 3).map { PenDrawings.hershey(it.toLong(), HersheyFont.names[it * 3], words = 2) },
    (0 until 3).map { PenDrawings.harmonograph(it.toLong()) },
    (0 until 3).map { PenDrawings.plant(it.toLong()) },
    (0 until 3).map { PenDrawings.truchet(it.toLong(), columns = 5, rows = 3) },
    (0 until 3).map { PenDrawings.boids(it.toLong(), count = 6, steps = 100, size = 100.0) },
    (0 until 2).map { PenDrawings.twoHands(it.toLong(), words = 4) },
]
var y = 0.0
val all = mutableListOf<FLine>()
for (row in rows) {
    val laid = sideBySide(row.map { [it.apply { color = vec(0.85, 0.9, 1.0, 1.0) }] }, gap = 30.0, y = y)
    all += laid
    y = laid.maxOf { it.fast2DBounds().let { b -> (b.y + b.h).toDouble() } } + 30
}
lines(all, w = 520.0, h = 640.0, fitToSize = true)
```

## 2. 3 - one stroke, seven hands

<!-- field-box id="tp_hands" index="1" -->

```edn [field-box id="tp_hands" role="properties"]
{"id" "tp_hands" "index" 1 "name" "3 - one stroke, seven hands" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "3 - one stroke, seven hands" "_tombstone" false "frame" #afield/rect [60.0 1660.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "tp_hands_code_.kt" "lines" 29 "characters" 1030 "sha256" "c0328d319fda75d9469be4be4e8f7a7ba188300ce010be6dcc43d9586671f6fa"}]}
```

```kotlin [field-property box="tp_hands" name="code" path="tp_hands_code_.kt"]
import afield.ai.learning.pen.*

// The same yellow stroke, continued by the handwriting pen and by each of the six synthetic
// ones. continueWith takes any model: this is the whole difference between the rows.
val start = fline {
    moveTo(0, 0)
    cubicTo(4, -14, 12, -14, 12, 0)
    cubicTo(12, 8, 20, 8, 24, 0)
    color = vec(1.0, 0.85, 0.3, 1.0)
    thickness = 2.0
}
val models =
    [PenModel.distill] + ["hershey", "harmonograph", "plants", "truchet", "boids", "mixed"].map { PenModel.bundled(it) }
val rows = models.map { model ->
    [
        start.copy().apply {
            color = vec(1.0, 0.85, 0.3, 1.0)
            thickness = 2.0
        },
        start.continueWith(model, steps = 260, seed = 2).apply { color = vec(0.85, 0.9, 1.0, 1.0) },
    ]
}
// two columns: handwriting, Hershey, harmonograph and plants; then truchet, boids and two hands
lines(
    sideBySide([stacked(rows.take(4), gap = 24.0), stacked(rows.drop(4), gap = 24.0)], gap = 60.0),
    w = 520.0,
    h = 420.0,
    fitToSize = true,
)
```

## 3. 6 - a pen that learned a taste

<!-- field-box id="tp_rounder" index="2" -->

```edn [field-box id="tp_rounder" role="properties"]
{"id" "tp_rounder" "index" 2 "name" "6 - a pen that learned a taste" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "6 - a pen that learned a taste" "_tombstone" false "frame" #afield/rect [60.0 4060.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "tp_rounder_code_.kt" "lines" 23 "characters" 1117 "sha256" "9aed3953a4ebd268798f57f58b43c437d6919b135824654d125a7b67b088e826"}]}
```

```kotlin [field-property box="tp_rounder" name="code" path="tp_rounder_code_.kt"]
import afield.ai.learning.pen.*

/*
The "rounder" pen is the handwriting pen fine-tuned on the curliest eighth of 2400 of its own
scribbles, chosen by curliness() instead of by hand. Same seeds, both pens: white is the
original, gold the one that learned the taste. The numbers are mean curliness over 40 each.
*/
val rounder = PenModel.bundled("rounder")
fun scribble(model: StrokeModel, seed: Long) = PenLine(model = model, seed = seed).also { it.grow(160) }.drawn
val before = (0 until 6).map { scribble(PenModel.distill, it.toLong()).apply { color = vec(0.85, 0.9, 1.0, 1.0) } }
val after = (0 until 6).map { scribble(rounder, it.toLong()).apply { color = vec(1.0, 0.8, 0.35, 1.0) } }
lines(
    stacked(
        [sideBySide(before.map { [it] }, gap = 25.0), sideBySide(after.map { [it] }, gap = 25.0)],
        gap = 30.0,
    ),
    w = 520.0,
    h = 260.0,
    fitToSize = true,
)
val c0 = (0 until 40).map { curliness(scribble(PenModel.distill, 100L + it)) }.average()
val c1 = (0 until 40).map { curliness(scribble(rounder, 100L + it)) }.average()
print("curliness: handwriting ${c0.f3}, rounder ${c1.f3}")
```

## 4. 4 - two hands, one switch

<!-- field-box id="tp_switch" index="3" -->

```edn [field-box id="tp_switch" role="properties"]
{"id" "tp_switch" "index" 3 "name" "4 - two hands, one switch" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "4 - two hands, one switch" "_tombstone" false "frame" #afield/rect [60.0 2460.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "tp_switch_code_.kt" "lines" 36 "characters" 1568 "sha256" "671dab0923fabda2dabf1fc9cb4d54e6542561915089fe8cfa6cc683191e4a6e"}]}
```

```kotlin [field-property box="tp_switch" name="code" path="tp_switch_code_.kt"]
import afield.ai.learning.pen.*

/*
The "mixed" pen learned words in two hands, loops (hand 0) and zigzags (hand 1). probe shows
it forty labelled drawings it has never seen and ranks its cells by how well each tracks the
hand. The top row is the pen's own writing coloured by the best of them. Below, pairs of rows
from the same seed with the top 1, 4 and 16 cells held towards loops (green) or zigzags (red).
*/
val pen = PenModel.bundled("mixed")
val hands = (0 until 40).map { mutableListOf<Int>() }
val drawings = (0 until 40).map { PenDrawings.twoHands(5000L + it, hands = hands[it]) }
val ranked = pen.probe(drawings, hands)
print("best cells: " + ranked.take(5).joinToString { (cell, r) -> "$cell (r = ${r.f2})" })

fun held(towards: Int, cells: Int) =
    PenLine(model = pen, seed = 3)
        .also { writer ->
            repeat(700) {
                for ((cell, r) in ranked.take(cells)) (writer.pen as Pen).setCell(
                    cell,
                    if ((r > 0) == (towards == 1)) 3.0 else -3.0,
                )
                writer.grow()
            }
        }
        .line
val own = PenLine(model = pen, seed = 3, recordMemory = true).also { it.grow(700) }
val rows =
    [own.coloredByCell(ranked[0].first, thickness = 2.0, range = null)] +
        [1, 4, 16].flatMap { n ->
            [
                [held(0, n).apply { color = vec(0.5, 1.0, 0.6, 1.0) }],
                [held(1, n).apply { color = vec(1.0, 0.55, 0.6, 1.0) }],
            ]
        }
lines(stacked(rows, gap = 14.0), w = 520.0, h = 520.0, fitToSize = true)
```

## 5. 7 - a corpus of your own

<!-- field-box id="tp_yours" index="4" -->

```edn [field-box id="tp_yours" role="properties"]
{"id" "tp_yours" "index" 4 "name" "7 - a corpus of your own" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "7 - a corpus of your own" "_tombstone" false "frame" #afield/rect [60.0 4560.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "tp_yours_code_.kt" "lines" 21 "characters" 912 "sha256" "136f6c019ee62fa3296e751461213394f48bc59d60b1ec14083208896b159216"}]}
```

```kotlin [field-property box="tp_yours" name="code" path="tp_yours_code_.kt"]
import afield.ai.learning.pen.*

// Any function that draws an FLine in order is a corpus. This one draws spirals that wobble;
// PenCorpus.write resamples each one the way a pen reads a line and saves it for fieldpen.
fun spiral(seed: Long): FLine {
    val r = kotlin.random.Random(seed)
    val turns = 3 + r.nextDouble() * 3
    val wobble = r.nextDouble() * 4
    return fline {
        breakNext()
        for (i in 0..(turns * 80).toInt()) {
            val t = i / 80.0 * 2 * PI
            val radius = 4 + t * 3 + sin(t * 7) * wobble
            lineTo(cos(t) * radius, sin(t) * radius)
        }
    }
}
val drawings = (0 until 300).map { spiral(it.toLong()) }
val points = PenCorpus.write("spirals".file.path, drawings)
print("$points points in ${drawings.size} drawings -> ${"spirals".file.path}")
lines(sideBySide(drawings.take(6).map { [it] }, gap = 20.0), w = 520.0, h = 140.0, fitToSize = true)
```

## 6. 5 - keep the ones you like (launch)

<!-- field-box id="tp_taste" index="5" -->

```edn [field-box id="tp_taste" role="properties"]
{"id" "tp_taste" "index" 5 "name" "5 - keep the ones you like (launch)" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "5 - keep the ones you like (launch)" "_tombstone" false "frame" #afield/rect [60.0 3260.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "tp_taste_code_.kt" "lines" 68 "characters" 2709 "sha256" "60336e31805867222b3551d88c7eb88f5c89b576694b9901df3ada1c063d4cb3"}]}
```

```kotlin [field-property box="tp_taste" name="code" path="tp_taste_code_.kt"]
import afield.ai.learning.pen.*

/*
Twelve scribbles from the handwriting pen. Click the ones you like: they turn green and are
added to a corpus in this document's files/taste folder. "next twelve" draws twelve more. With a few
hundred kept, fine-tune on them (the command is printed below) and load the result with
PenModel.load: a pen that has learned your taste from its own work.
*/
val folder = "taste".file
val kept = mutableListOf<FLine>()
var round = 0
var redraw = true
fun scribbles() = (0 until 12).map { k -> PenLine(seed = round * 12L + k).also { it.grow(120) }.drawn }
var current = scribbles()
controlGroup("taste") {
    // buttons report press and release; act on the press
    button("next twelve") { down ->
        if (down) {
            round++
            current = scribbles()
            redraw = true
        }
    }
    button("save corpus") { down ->
        if (down) {
            PenCorpus.write(folder.path, kept)
            print(
                "${kept.size} kept -> ${folder.path}\n" +
                    "uv run --project . --group pen python -m fieldpen.prepare ${folder.path}/train taste_train.npz --scale 8 (and valid), then\n" +
                    "uv run --project . --group pen python -m fieldpen.train --init src/jvmMain/resources/afield/pen/distill-b64.json --crop 256 --input-noise 0.02 ...",
            )
        }
    }
}
frames {
    while (frame()) {
        if (!redraw) continue
        redraw = false
        val groups = current.map { s ->
            val chosen = kept.any { it === s }
            // a faint frame around each scribble, so you can see which marks are one choice
            val b = s.fast2DBounds()
            val frame = fline {
                moveTo(b.x - 6, b.y - 6)
                lineTo(b.x + b.w + 6, b.y - 6)
                lineTo(b.x + b.w + 6, b.y + b.h + 6)
                lineTo(b.x - 6, b.y + b.h + 6)
                lineTo(b.x - 6, b.y - 6)
                color = vec(1, 1, 1, 0.15)
            }
            [
                frame,
                s.apply {
                    color = if (chosen) vec(0.5, 1.0, 0.6, 1.0) else vec(0.85, 0.9, 1.0, 1.0)
                    thickness = 1.5
                    hoverColor = vec(1.0, 0.85, 0.3, 1.0)
                    onClick = {
                        if (kept.none { it === s }) kept += s else kept.removeAll { it === s }
                        redraw = true
                    }
                },
            ]
        }
        val rows = groups.chunked(4).map { sideBySide(it, gap = 30.0) }
        lines(stacked(rows, gap = 30.0), channel = "scribbles", w = 520.0, h = 300.0, fitToSize = true)
        markdown("kept", "**${kept.size}** kept")
    }
}
```

## 7. 2 - six pens, drawing alone

<!-- field-box id="tp_pens" index="6" -->

```edn [field-box id="tp_pens" role="properties"]
{"id" "tp_pens" "index" 6 "name" "2 - six pens, drawing alone" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2 - six pens, drawing alone" "_tombstone" false "frame" #afield/rect [60.0 860.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "tp_pens_code_.kt" "lines" 27 "characters" 1166 "sha256" "186ed4fee814cb9042f40cc8b580c8db563787d80311ba26f2bf4fba851c34e7"}]}
```

```kotlin [field-property box="tp_pens" name="code" path="tp_pens_code_.kt"]
import afield.ai.learning.pen.*

/*
Each pen that ships with Field, given nothing and left to draw: a grey drawing from its
corpus on the left, then three of its own. What each one learned, and what it missed,
is easiest to see side by side.
*/
val pens = [
    "hershey" to { s: Long -> PenDrawings.hershey(s, words = 2) },
    "harmonograph" to { s: Long -> PenDrawings.harmonograph(s) },
    "plants" to { s: Long -> PenDrawings.plant(s) },
    "truchet" to { s: Long -> PenDrawings.truchet(s, columns = 5, rows = 3) },
    "boids" to { s: Long -> PenDrawings.boids(s, count = 6, steps = 100, size = 100.0) },
    "mixed" to { s: Long -> PenDrawings.twoHands(s, words = 3) },
]
val rows = pens.map { (name, corpus) ->
    val pen = PenModel.bundled(name)
    val own =
        (0 until 3).map { seed ->
            PenLine(model = pen, seed = seed.toLong())
                .also { it.grow(300) }
                .line
                .apply { color = vec(0.6, 0.85, 1.0, 1.0) }
        }
    sideBySide([[corpus(900).apply { color = vec(1, 1, 1, 0.5) }]] + own.map { [it] }, gap = 30.0)
}
lines(stacked(rows, gap = 30.0), w = 520.0, h = 700.0, fitToSize = true)
```

