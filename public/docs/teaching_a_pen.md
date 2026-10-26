# Teaching a pen

*The [learned pen](learned_pen.md) knows handwriting because it watched handwriting. Show it
something else and it learns that instead. Field ships six more pens, each trained on drawings
that Field generated itself: plotter lettering, harmonographs, branching plants, Truchet curves,
flocking trails, and a hand that switches between two styles. This page shows what each one
learned and missed, how to find a learned habit inside a pen's memory, how to teach a pen a taste,
and how to make a corpus of your own.*

The quickest way in is the starting point. Open **Starting points** on the welcome screen and search
for **teaching a pen**.
[Browse the starting point ↗](offline.html?file=assets/agent_transcripts/teaching_a_pen/metadata.edn)

## Drawings with an order

A pen learns from the *order* a drawing was made in: which way each stroke went, where the pen
lifted, where it went down again. A scan of a drawing has none of that. A drawing that Field
generates has all of it, because the code that drew it moved in an order. `PenDrawings` holds six
such generators and `HersheyFont` reads the Hershey fonts, single-stroke lettering from 1967
designed for pen plotters. Here are three drawings from each.

```diffstatic_runner {data-lang=kotlin screenshot="assets/teaching_a_pen/corpora.png" embed=true alt="Six rows of pale line drawings: three Hershey lettering samples in different fonts; three harmonograph figures of decaying loops; three branching plants; three panels of Truchet tiles whose quarter-circle arcs join into winding curves; three tangles of flocking trails; and two lines alternating words of small loops with words of sharp zigzags"}
:::static
import afield.ai.learning.pen.*

val rows = listOf(
    (0 until 3).map { PenDrawings.hershey(it.toLong(), HersheyFont.names[it * 3], words = 2) },
    (0 until 3).map { PenDrawings.harmonograph(it.toLong()) },
    (0 until 3).map { PenDrawings.plant(it.toLong()) },
    (0 until 3).map { PenDrawings.truchet(it.toLong(), columns = 5, rows = 3) },
    (0 until 3).map { PenDrawings.boids(it.toLong(), count = 6, steps = 100, size = 100.0) },
    (0 until 2).map { PenDrawings.twoHands(it.toLong(), words = 4) },
)
var y = 0.0
val all = mutableListOf<FLine>()
for (row in rows) {
    val laid = sideBySide(row.map { [it.apply { color = vec(0.85, 0.9, 1.0, 1.0) }] }, gap = 30.0, y = y)
    all += laid
    y = laid.maxOf { it.fast2DBounds().let { b -> (b.y + b.h).toDouble() } } + 30
}
lines(all, w = 520.0, h = 640.0, fitToSize = true)
```

Each generator takes a seed, so `PenDrawings.plant(7)` is the same plant every time. They draw
at a size that suits a pen's default scale of 8 drawing units per model unit.

## Training on a corpus

`PenCorpus.write(folder, drawings)` turns a list of drawings into a corpus that the optional
`fieldpen` Python package can train on: one JSON file per drawing, every tenth one held back for
validation. Each stroke is resampled the way a pen reads a line, so a pen trained on the corpus
sees the same spacing when it is later shown a line of yours.

!!! note "Installing"
    Training needs PyTorch, in the optional `pen` group:

    ```diffstatic {data-lang=bash}
    UV_PROJECT_ENVIRONMENT=.venv uv sync --inexact --group pen --group docs --group backdoor
    ```

    Using the six pens below needs nothing: they ship with Field.

The six pens on this page were made by two commands. The first writes every corpus, and the
second trains each one and exports it for Kotlin:

```diffstatic {data-lang=bash}
uv run --project . --group backdoor python tools/field_backdoor.py --json \
    --command "run-jvm-main afield.ai.learning.pen.PenCorpora --arg $PWD/build/pen-corpora --timeout 1500"
uv run --project . --group pen python tools/train_pen_corpora.py --device mps build/pen-corpora
```

`--device mps` uses an Apple GPU, where each from-scratch pen takes about five minutes, against
half an hour or so on a laptop CPU; on Linux or Windows with an NVIDIA card, `--device cuda`.
For a corpus of your own, the training run underneath is:

```diffstatic {data-lang=bash}
uv run --project . --group pen python -m fieldpen.prepare mine/train train.npz --scale 8
uv run --project . --group pen python -m fieldpen.prepare mine/valid valid.npz --scale 8
uv run --project . --group pen python -m fieldpen.train --train train.npz --valid valid.npz --out runs/mine \
    --hidden 256 --epochs 200 --lr 2e-3 --batch 64 --crop 256 --samples 2048 --input-noise 0.02 --device mps
```

!!! note "Two settings that matter"
    `--crop 256` trains on random 256-point windows instead of whole drawings, and
    `--input-noise 0.02` jitters what the pen is shown while it trains. Without them a pen fits its
    corpus beautifully and draws badly: it never had to recover from a small mistake, so its first
    one sends it drifting off across the page instead of closing a figure. A corpus of a few
    hundred drawings wants a couple of hundred epochs of windows.

To fine-tune the handwriting pen rather than start from nothing, pass
`--init src/jvmMain/resources/afield/pen/distill-b64.json` instead of `--hidden`. Then export the
best checkpoint, in half precision, and load it in Kotlin:

```diffstatic {data-lang=python}
import fieldpen
fieldpen.export_for_field(fieldpen.load_model("runs/mine/best.pt"), "mine.fieldpen.json", half=True)
```

```diffstatic {data-lang=kotlin}
val mine = PenModel.load("/path/to/mine.fieldpen.json")
```

The bundled pens load by name, with `PenModel.bundled("plants")` and so on.

## What each pen learned

Each row starts with a drawing from the pen's corpus in grey, then three drawings the pen made on
its own, with no primer.

```diffstatic_runner {data-lang=kotlin screenshot="assets/teaching_a_pen/pens.png" embed=true alt="Six rows. Each starts with a grey corpus drawing and continues with three pale blue drawings by the pen trained on it: angular plotter lettering; looping figures; dense branching tufts; scattered quarter-circle arcs and rings; tangles of short trails; and words alternating loops and zigzags"}
:::static
import afield.ai.learning.pen.*

val pens = listOf(
    "hershey" to { s: Long -> PenDrawings.hershey(s, words = 2) },
    "harmonograph" to { s: Long -> PenDrawings.harmonograph(s) },
    "plants" to { s: Long -> PenDrawings.plant(s) },
    "truchet" to { s: Long -> PenDrawings.truchet(s, columns = 5, rows = 3) },
    "boids" to { s: Long -> PenDrawings.boids(s, count = 6, steps = 100, size = 100.0) },
    "mixed" to { s: Long -> PenDrawings.twoHands(s, words = 3) },
)
val rows = pens.map { (name, corpus) ->
    val pen = PenModel.bundled(name)
    val own = (0 until 3).map { seed -> PenLine(model = pen, seed = seed.toLong()).also { it.grow(300) }.line.apply { color = vec(0.6, 0.85, 1.0, 1.0) } }
    sideBySide([[corpus(900).apply { color = vec(1, 1, 1, 0.5) }]] + own.map { [it] }, gap = 30.0)
}
lines(stacked(rows, gap = 30.0), w = 520.0, h = 700.0, fitToSize = true)
```

- **Hershey** writes plotter lettering: straight segments, sharp corners, serifs, letters that
  look like letters of no alphabet. It was fine-tuned from the handwriting pen, and the handwriting
  is gone.
- **Harmonograph** draws looping figures with the right curvature, but does not decay inwards the
  way a real harmonograph does, and wanders where the real thing stays centred.
- **Plants** branches: out along a twig and back down to the fork, again and again. The plants
  are denser and messier than its corpus, but they are plants.
- **Truchet** learned the arcs, quarter circles joined into S-curves and rings, but not the grid
  they sit on, so the arcs are scattered instead of tiled.
- **Boids** draws short trails that swerve and cluster, closer to a scribble than to a flock.
- **Two hands** writes words of loops and words of zigzags, switching between them as its corpus
  does. The section after next looks inside it.

The pattern in what they missed is the same: a pen sees only how far it moved, never where it is.
Anything that depends on absolute position, a grid, a centre, the fork a branch started from, is
out of its reach unless the drawing walks back there. Plants work because the corpus draws each
branch out and back.

## One stroke, seven hands

`continueWith` takes any model, so the same stroke can be carried on in every hand. The left
column is the handwriting pen, Hershey, harmonograph and plants; the right is Truchet, boids and
two hands.

```diffstatic_runner {data-lang=kotlin screenshot="assets/teaching_a_pen/hands.png" embed=true alt="Seven copies of one small yellow wave, each continued in a different hand: handwriting, plotter lettering, loops, branching strokes, scattered arcs, a tangle of trails, and a run of zigzags followed by loops"}
:::static
import afield.ai.learning.pen.*

val start = fline { moveTo(0, 0); cubicTo(4, -14, 12, -14, 12, 0); cubicTo(12, 8, 20, 8, 24, 0); color = vec(1.0, 0.85, 0.3, 1.0); thickness = 2.0 }
val models = [PenModel.distill] + ["hershey", "harmonograph", "plants", "truchet", "boids", "mixed"].map { PenModel.bundled(it) }
val rows = models.map { model ->
    listOf(start.copy().apply { color = vec(1.0, 0.85, 0.3, 1.0); thickness = 2.0 },
        start.continueWith(model, steps = 260, seed = 2).apply { color = vec(0.85, 0.9, 1.0, 1.0) })
}
// two columns: handwriting, Hershey, harmonograph and plants; then truchet, boids and two hands
lines(sideBySide([stacked(rows.take(4), gap = 24.0), stacked(rows.drop(4), gap = 24.0)], gap = 60.0), w = 520.0, h = 420.0, fitToSize = true)
```

## Two hands, one switch

The *two hands* pen has to know, at every point, which hand it is writing in. That knowledge is
somewhere in its 256 cells. `probe` finds where: it shows the pen labelled drawings it has never
seen, records every cell's output at every point, and ranks the cells by how well each follows the
label. Here the top row is the pen's own writing, coloured by the best cell. Below it are pairs of
rows written from the same seed, with the top 1, 4 and 16 cells held towards loops (green) or
zigzags (red).

```diffstatic_runner {data-lang=kotlin screenshot="assets/teaching_a_pen/switch.png" embed=true alt="Seven rows of loops and zigzags. Top: words coloured by one cell, the zigzag words blue and the loop words orange. Then three pairs: with one cell held, both rows still switch hands; with four, green is mostly loops and red mostly zigzags; with sixteen, the green row is all loops and the red row all zigzags"}
:::static
import afield.ai.learning.pen.*

val pen = PenModel.bundled("mixed")
val hands = (0 until 40).map { mutableListOf<Int>() }
val drawings = (0 until 40).map { PenDrawings.twoHands(5000L + it, hands = hands[it]) }
val ranked = pen.probe(drawings, hands)
print("best cells: " + ranked.take(5).joinToString { (cell, r) -> "$cell (r = ${"%.2f".format(r)})" })

fun held(towards: Int, cells: Int) = PenLine(model = pen, seed = 3).also { writer ->
    repeat(700) {
        for ((cell, r) in ranked.take(cells)) (writer.pen as Pen).setCell(cell, if ((r > 0) == (towards == 1)) 3.0 else -3.0)
        writer.grow()
    }
}.line
val own = PenLine(model = pen, seed = 3, recordMemory = true).also { it.grow(700) }
val rows = [own.coloredByCell(ranked[0].first, thickness = 2.0, range = null)] +
    [1, 4, 16].flatMap { n ->
        [[held(0, n).apply { color = vec(0.5, 1.0, 0.6, 1.0) }], [held(1, n).apply { color = vec(1.0, 0.55, 0.6, 1.0) }]]
    }
lines(stacked(rows, gap = 14.0), w = 520.0, h = 520.0, fitToSize = true)
```

One cell follows the hand closely (a correlation of about 0.7), but holding it barely changes what
the pen does. Holding sixteen does: one row is nothing but loops, the other nothing but zigzags.
The pen keeps its knowledge of which hand it is in spread across many cells, and `probe` ranks
them, which is what makes holding them possible. The baseline tilts as more cells are held, a sign
of how much else those cells were doing.

## Learning a taste

A corpus does not have to come from a generator. It can be the pen's own work, kept where you like
it. The starting point's fifth box draws twelve scribbles at a time; click the ones you like and
**save corpus** writes them out for fine-tuning.

```diffstatic_runner {data-lang=kotlin screenshot="assets/teaching_a_pen/taste.png" embed=true macro-file="../../dev_docs/agents/transcripts/teaching_a_pen.yaml" run-box="tp_taste" capture-box="tp_taste" execution="launch" wait="6" alt="A box's output: next and save buttons, then twelve framed scribbles of machine handwriting in a grid, waiting to be clicked, and a counter reading 0 kept"}
:::static
import afield.ai.learning.pen.*

val folder = "taste".file
val kept = mutableListOf<FLine>()
var round = 0
var redraw = true
fun scribbles() = (0 until 12).map { k -> PenLine(seed = round * 12L + k).also { it.grow(120) }.drawn }
var current = scribbles()
controlGroup("taste") {
    // buttons report press and release; act on the press
    button("next twelve") { down -> if (down) { round++; current = scribbles(); redraw = true } }
    button("save corpus") { down ->
        if (down) {
            PenCorpus.write(folder.path, kept)
            print("${kept.size} kept -> ${folder.path}\n" +
                "uv run --project . --group pen python -m fieldpen.prepare ${folder.path}/train taste_train.npz --scale 8 (and valid), then\n" +
                "uv run --project . --group pen python -m fieldpen.train --init src/jvmMain/resources/afield/pen/distill-b64.json --crop 256 --input-noise 0.02 ...")
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
            val frame = fline { moveTo(b.x - 6, b.y - 6); lineTo(b.x + b.w + 6, b.y - 6); lineTo(b.x + b.w + 6, b.y + b.h + 6); lineTo(b.x - 6, b.y + b.h + 6); lineTo(b.x - 6, b.y - 6); color = vec(1, 1, 1, 0.15) }
            listOf(frame, s.apply {
                color = if (chosen) vec(0.5, 1.0, 0.6, 1.0) else vec(0.85, 0.9, 1.0, 1.0)
                thickness = 1.5
                hoverColor = vec(1.0, 0.85, 0.3, 1.0)
                onClick = { if (kept.none { it === s }) kept += s else kept.removeAll { it === s }; redraw = true }
            })
        }
        val rows = groups.chunked(4).map { sideBySide(it, gap = 30.0) }
        lines(stacked(rows, gap = 30.0), channel = "scribbles", w = 520.0, h = 300.0, fitToSize = true)
        markdown("kept", "**${kept.size}** kept")
    }
}
```

The bundled `rounder` pen was made the same way, with `curliness` choosing instead of a person:
2400 scribbles from the handwriting pen, the curliest eighth kept, and the handwriting pen
fine-tuned on those. Below, the same six seeds from each, the original in white and `rounder` in
gold.

```diffstatic_runner {data-lang=kotlin screenshot="assets/teaching_a_pen/rounder.png" embed=true alt="Two lines of machine handwriting from the same seeds: the white original, and below it the gold fine-tuned pen, whose letters are rounder, more a, n and r than straight strokes"}
:::static
import afield.ai.learning.pen.*

val rounder = PenModel.bundled("rounder")
fun scribble(model: StrokeModel, seed: Long) = PenLine(model = model, seed = seed).also { it.grow(160) }.drawn
val before = (0 until 6).map { scribble(PenModel.distill, it.toLong()).apply { color = vec(0.85, 0.9, 1.0, 1.0) } }
val after = (0 until 6).map { scribble(rounder, it.toLong()).apply { color = vec(1.0, 0.8, 0.35, 1.0) } }
lines(stacked([sideBySide(before.map { [it] }, gap = 25.0), sideBySide(after.map { [it] }, gap = 25.0)], gap = 30.0),
    w = 520.0, h = 260.0, fitToSize = true)
val c0 = (0 until 40).map { curliness(scribble(PenModel.distill, 100L + it)) }.average()
val c1 = (0 until 40).map { curliness(scribble(rounder, 100L + it)) }.average()
print("curliness: handwriting ${"%.3f".format(c0)}, rounder ${"%.3f".format(c1)}")
```

The kept scribbles were about twice as curly as the pen's average, and the fine-tuned pen's are
about a third curlier than the original's. It moved towards the taste, not all the way: three
hundred examples are a nudge, not a new hand.

## A corpus of your own

Any function that draws an FLine in order makes a corpus. This one draws wobbling spirals, writes
three hundred of them for training, and shows the first six.

```diffstatic_runner {data-lang=kotlin screenshot="assets/teaching_a_pen/yours.png" embed=true alt="Six pale spirals of different sizes, each with a small regular wobble along its arms"}
:::static
import afield.ai.learning.pen.*
import kotlin.math.*

fun spiral(seed: Long): FLine {
    val r = kotlin.random.Random(seed)
    val turns = 3 + r.nextDouble() * 3
    val wobble = r.nextDouble() * 4
    return FLine().also { f ->
        for (i in 0..(turns * 80).toInt()) {
            val t = i / 80.0 * 2 * PI
            val radius = 4 + t * 3 + sin(t * 7) * wobble
            if (i == 0) f.moveTo(cos(t) * radius, sin(t) * radius) else f.lineTo(cos(t) * radius, sin(t) * radius)
        }
    }
}
val drawings = (0 until 300).map { spiral(it.toLong()) }
val points = PenCorpus.write("spirals".file.path, drawings)
print("$points points in ${drawings.size} drawings -> ${"spirals".file.path}")
lines(sideBySide(drawings.take(6).map { [it] }, gap = 20.0), w = 520.0, h = 140.0, fitToSize = true)
```

`"spirals".file` is a folder inside the document, so the corpus travels with it. Then train it
with the commands under [Training on a corpus](#training-on-a-corpus).

## Source files

| file | what is in it |
|---|---|
| [`afield/ai/learning/pen/corpora.kt`](https://github.com/OpenEndedGroup/Field4/blob/main/src/jvmMain/kotlin/afield/ai/learning/pen/corpora.kt) | `HersheyFont`, `PenDrawings`, `PenCorpus`, `PenCorpora`, `probe`, `curliness` |
| [`afield/ai/learning/pen/penviz.kt`](https://github.com/OpenEndedGroup/Field4/blob/main/src/jvmMain/kotlin/afield/ai/learning/pen/penviz.kt) | `coloredByCell`, `sideBySide`, `stacked` and the other pictures of a pen |
| [`python/fieldpen/`](https://github.com/OpenEndedGroup/Field4/tree/main/python/fieldpen) | training, with `--crop` and `--input-noise`, and `export_for_field` |
| [`tools/train_pen_corpora.py`](https://github.com/OpenEndedGroup/Field4/blob/main/tools/train_pen_corpora.py) | the recipes the bundled pens were trained with |
| `src/jvmMain/resources/afield/pen/models/` | the six pens and `rounder`, with their training settings and losses in each file's metadata |
| `src/jvmMain/resources/afield/pen/hershey/` | nine Hershey fonts and their licence |

??? aside "What did not work first"
    Plants were first drawn the obvious way, lifting the pen at each twig's tip and jumping back
    to its fork. The pen trained on them climbed for ever and never branched: the jump means
    remembering exactly where the fork was, and a pen that sees only displacements cannot. Drawing
    each twig out and back fixed it. The first training runs used whole drawings and no input
    noise, about seven hundred optimizer steps in all, and every pen drifted off sideways; the
    windowed, noisy recipe above is what made the harmonograph close its loops. The pens were
    first exported at eight bits a weight, which is plenty for the handwriting model and not for
    these: rounding a sharp synthetic pen's weights to 256 levels made it drift by a hundred units
    in eight hundred points, so they ship in half precision. And `curliness` first measured the
    raw points, where it mostly counted jitter; `rounder` trained on that came out *less* curly
    than the pen it started from.
