# A learned pen

*A recurrent network that has watched people write by hand, and draws the next point. Give it any
[FLine](fline_examples.md) and it carries on from where the line ends: one continuation, a dozen
alternatives, or a pen that keeps writing live while you change how it samples.*

The model is the one from Shan Carter, David Ha, Ian Johnson and Chris Olah's
[*Experiments in Handwriting with a Neural Network*](https://distill.pub/2016/handwriting/)
(Distill, 2016). It is a 500-cell LSTM, trained on the IAM On-Line Handwriting Database, whose
output at each step is a probability for every possible next pen movement. It ships with Field and
runs in Kotlin, so nothing needs installing. It learned handwriting, not text, so what it writes
looks like writing and says nothing.

The quickest way in is the starting point. Open **Starting points** on the welcome screen and search
for **learned pen**. Its third box is a drawing panel: draw a stroke and the pen keeps writing from
the end of it.
[Browse the starting point ↗](offline.html?file=assets/agent_transcripts/learned_pen/metadata.edn)

## Continue a line

`continueWith` shows the pen the whole line, then lets it draw. It returns only the pen's
part, as a new FLine that starts where yours ends. The yellow stroke here is ours; everything after
it is the pen's.

```diffstatic_runner {data-lang=kotlin screenshot="assets/learned_pen/continue.png" embed=true alt="A short yellow wave, and after it a line of white handwriting-like marks continuing from its end, letters that look like writing but spell nothing"}
:::static
import afield.ai.learning.pen.*

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

The same `seed` gives the same continuation every time; change it for another. `steps` is the
number of points the pen adds. A letter takes thirty or so.

The pen reads your line at its own size. One of its *model units* is about four times a typical
handwritten step, and `scale = 8.0` (the default) says that one model unit is 8 of your FLine's
units, which makes canvas-pixel writing about the size above. Draw larger or smaller and change
`scale` to match. A stroke that is much bigger than handwriting at that scale reads to the pen as
one long sweep, and it will try to continue a sweep.

## Many futures

`futuresWith` asks the pen what it might do next without committing to any of it. Each future
starts from the same reading of your line and differs only in the random choices after it. Here
are twelve, each drawn beside its own copy of the stroke.

```diffstatic_runner {data-lang=kotlin screenshot="assets/learned_pen/futures.png" embed=true alt="Twelve copies of the same small yellow stroke in a grid, each followed by a different short run of pale blue handwriting"}
:::static
import afield.ai.learning.pen.*

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

## Temperature and noise

The pen's prediction is a mixture of twenty overlapping guesses (Gaussians), each a likely
direction and distance for the next step. Every step it picks one guess, then a point near it.
`PenControls` sets how it does both:

- `temperature` is how the guess is picked. Low values keep the pen to its most likely habit; high
  ones let it change its mind. 0.65 is the default.
- `noise` scales how far from the chosen guess the point lands. 1 is the spread the model learned.
  At 0 the pen takes each guess's centre, which is a small, cautious step, and it barely leaves
  the end of your stroke; turned up, the strokes get longer and looser.
- `liftBias` lifts the pen more often (positive) or keeps it on the paper (negative), and
  `driftX` / `driftY` push every step a little in one direction.

Rows here are temperature 0.2, 0.65 and 1.2; columns are noise 0, 0.65 and 1.2. Every one has the
same seed.

```diffstatic_runner {data-lang=kotlin screenshot="assets/learned_pen/controls.png" embed=true alt="A three by three grid of continuations of one small yellow stroke. In the left column, with no noise, the pen adds only a dot or a tick; the middle column writes a few ordinary letters; the right column, with the most noise, writes longer, looser strokes that wander further"}
:::static
import afield.ai.learning.pen.*

val start = fline {
    moveTo(0, 0)
    cubicTo(4, -14, 12, -14, 12, 0)
    cubicTo(12, 8, 20, 8, 24, 0)
    color = vec(1.0, 0.85, 0.3, 1.0)
    thickness = 1.5
}
val grid = mutableListOf<FLine>()
listOf(0.2, 0.65, 1.2).forEachIndexed { row, temperature ->
    listOf(0.0, 0.65, 1.2).forEachIndexed { column, noise ->
        val at = vec(column * 200.0, row * 70.0, 0.0)
        val pen = PenControls(temperature = temperature, noise = noise)
        grid += start + at
        grid += (start.continueWith(steps = 110, seed = 5, controls = pen) + at).apply { color = vec(0.75, 0.85, 1.0, 1.0) }
    }
}
lines(grid, w = 520.0, h = 260.0, fitToSize = true)
```

## A pen that keeps writing

`continueWith` draws everything at once. A `PenLine` keeps the pen and lets you draw a few points
at a time: `grow(n)` adds `n` points, and `line` (your stroke and the pen's) and `drawn` (the pen's
alone) are FLines that grow in place. Its `controls` can change between calls, so a slider can
steer the pen while it writes.

This is the starting point's third box. It draws four points a frame, starting from a stroke in
the "draw here" panel. Draw a stroke of your own there and the pen starts again from yours.

```diffstatic_runner {data-lang=kotlin screenshot="assets/learned_pen/live.png" embed=true macro-file="../../dev_docs/agents/transcripts/learned_pen.yaml" run-box="lp_draw" capture-box="lp_draw" execution="launch" wait="10" clip="0,0,2203,2710" alt="A box's output: temperature and noise sliders, a drawing panel holding one small yellow stroke, and below it a panel where a line of handwriting has grown from that stroke across the page"}
:::static
import afield.ai.learning.pen.*

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

`PenLine.futures(count, steps)` is `futuresWith` from wherever the pen has got to, and `lift()`
makes the pen come up so its next point starts a new stroke.

## Inside the pen's memory

The Distill article's fourth experiment edits the network's memory while it writes. A `Pen` (the
state under a `PenLine`, its `pen` property) exposes its 500 memory cells: `cells()` reads them and
`setCell(cell, value)` overwrites one. Here the same pen, with the same seed, writes three times:
untouched, then with cell 136 held at +4, then at −4.

```diffstatic_runner {data-lang=kotlin screenshot="assets/learned_pen/cell.png" embed=true alt="Three rows of machine handwriting from the same seed. Top, white: ordinary. Middle, green, with cell 136 held high: taller, more slanted and more joined up. Bottom, red, with it held low: sparse, broken marks spread out along the line"}
:::static
import afield.ai.learning.pen.*

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

Which cell does what is not written down anywhere; the network found its own uses for them.
`hidden()` reads each cell's output, and `liftProbability` is the chance the pen lifts after its
next point. Plotting either against the line it is drawing is how you find a cell worth holding.

## Training your own pen

The pen knows handwriting because it watched handwriting. Show it something else and it will
learn that. The optional `fieldpen` Python package (in `python/fieldpen`, the same model in
PyTorch) trains a model from scratch or fine-tunes this one on your own strokes, and exports the
result for Kotlin.

!!! note "Installing"
    Training needs PyTorch. Install the optional `pen` group into the venv Field's Python uses:

    ```bash
    UV_PROJECT_ENVIRONMENT=.venv uv sync --inexact --group pen --group docs --group backdoor
    ```

    None of the Kotlin on this page needs it.

Record your drawings as JSON, one file per drawing, each a list of strokes and each stroke a list of
`[x, y]` points in the order they were drawn:

```json
{"strokes": [[[0, 0], [8, 2], [16, -1]], [[30, 10], [31, 18]]]}
```

An FLine is already ordered, so drawings made in Field are the easiest source. Keep training and
validation drawings in separate folders, holding out whole drawings rather than pieces of ones you
train on, then:

```bash
uv run --project . --group pen python -m fieldpen.prepare drawings/train train.npz --scale 8
uv run --project . --group pen python -m fieldpen.prepare drawings/valid valid.npz --scale 8
uv run --project . --group pen python -m fieldpen.train --train train.npz --valid valid.npz \
    --out runs/mine --init src/jvmMain/resources/afield/pen/distill-b64.json
```

`--scale` means what it means in Kotlin: drawing units per model unit. `--init` starts from the
handwriting model, which already knows how a hand moves smoothly. Leave it out, and pick `--hidden`,
`--layers` and `--mixtures`, to start from nothing. The model is small (about a million numbers), so
it trains on a laptop CPU; `--device cuda` uses a GPU if there is one.

Kotlin reads its own format rather than PyTorch's, so export the best checkpoint:

```python
import fieldpen
fieldpen.export_for_field(fieldpen.load_model("runs/mine/best.pt"), "mine.fieldpen.json")
```

and pass it to anything on this page:

```kotlin
val mine = PenModel.load("/path/to/mine.fieldpen.json")
lines(start, start.continueWith(mine))
```

The same package runs in a [Python box](python.md) too (`from fieldpen import load_model, Session`),
and `python -m fieldpen.lab` opens a page in the browser for drawing primers, watching futures and
editing cells without Field. `python/fieldpen/README.md` has both.

## Other models

`continueWith`, `futuresWith` and `PenLine` take any `StrokeModel`: something that makes a
`StrokePen`, which can be fed a displacement, asked for the next one, and forked. `PenModel` is the
one that ships. Implement the two interfaces to put a different model behind the same calls: a
Markov chain over recorded strokes, a model running in Python, or a rule you wrote.

## Source files

| file | what is in it |
|---|---|
| [`afield/ai/learning/pen/pen.kt`](https://github.com/OpenEndedGroup/Field4/blob/main/src/jvmMain/kotlin/afield/ai/learning/pen/pen.kt) | `PenModel`, `Pen`, `PenLine`, `PenControls`, `continueWith`, `futuresWith`, `StrokeModel` |
| [`python/fieldpen/`](https://github.com/OpenEndedGroup/Field4/tree/main/python/fieldpen) | the PyTorch model, training, data preparation, export and the browser lab |
| `src/jvmMain/resources/afield/pen/distill-b64.json` | the Distill article's published weights, shared by Kotlin and Python |

The Kotlin forward pass is checked against PyTorch's to four decimal places (`PenModelTest`), and
the PyTorch port against the original article's JavaScript.

??? aside "Where this came from"
    The PyTorch port, its training code and the lab were written in a ChatGPT conversation in
    October 2026, as a standalone package with a subprocess bridge to Field. Field's version keeps
    the Python for training and runs inference in Kotlin instead: one step of a 500-cell LSTM is
    about a million multiply-adds, which the JVM does fast enough to write live, so drawing needs
    no bridge. The weights are the article's own quantized release, decoded, not retrained.
    The port is Apache-2.0 (`python/fieldpen/LICENSE`, `NOTICE`); the Distill repository's
    licence is beside the weights.
