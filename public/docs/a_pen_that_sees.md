# A pen that sees

*The [learned pens](learned_pen.md) know only how they moved. They can carry a habit, but they
cannot see the line they drew a moment ago, so they cannot follow it, stop when they meet it, or
close a shape. A pen that senses the ink around its tip can do all three. The two on this page were
made with Henri Michaux's mescaline drawings in mind: dense fields of fine, trembling lines, each
one echoing the line beside it.*

The quickest way in is the starting point. Open **Starting points** on the welcome screen and search
for **a pen that sees**. Its sixth box is a drawing panel: draw a line and the pen hatches beside it.
[Browse the starting point ↗](offline.html?file=assets/agent_transcripts/a_pen_that_sees/metadata.edn)

## What it sees

At every point, a sensing pen is told how close the nearest ink is in each of sixteen directions,
out to 3 model units (24 pixels at the usual scale), and which way, and how far, the middle of all
the ink so far lies. It ignores its own last eight points, which are always just behind it. That is
all: eighteen numbers alongside the usual three. Here the seeing *Michaux* pen is stopped at four
moments while it hatches beside a wavering line. `seeing()` draws what it sees: the faint circle is
its reach, and each ray runs to the nearest ink in one direction, brighter the closer it is.

```diffstatic_runner {data-lang=kotlin screenshot="assets/a_pen_that_sees/sees.png" embed=true alt="Four stages of a pen hatching beside a yellow wavering line, one, two, three and then five passes; at the pen tip in each a faint orange circle, and short bright orange rays reaching sideways to the nearest line"}
:::static
import afield.ai.learning.pen.*
import kotlin.math.*

val yours = FLine().also { f -> for (i in 0..120) { val y = i * 2.0; val x = 14 * sin(y / 40) + 5 * sin(y / 13); if (i == 0) f.moveTo(x, y) else f.lineTo(x, y) } }
val writer = PenLine(yours, model = PenModel.bundled("michaux"), seed = 1)
val moments = [60, 180, 320, 520].map { until ->
    writer.grow(until - writer.history.size)
    listOf(yours.copy().apply { color = vec(1.0, 0.85, 0.3, 1.0); thickness = 1.5 },
        writer.drawn.copy().apply { color = vec(0.9, 0.92, 1.0, 1.0); thickness = 1.0 }) + writer.seeing()
}
lines(sideBySide(moments, gap = 40.0), w = 520.0, h = 300.0, fitToSize = true)
```

The rays reach sideways, to the line it is following, and that is what it steers by.

## Beside a line

Given a line to continue, the seeing pen turns at its end and comes back beside it, pass after
pass, each following the undulation of the one before. Its blind twin was trained on exactly the
same drawings, with the same settings, and was never shown what is around it. It knows the habit,
long trembling strokes, but has nothing to follow. The yellow line is the primer; the seeing pen is
white, the blind one red.

```diffstatic_runner {data-lang=kotlin screenshot="assets/a_pen_that_sees/hatch.png" embed=true alt="Five copies of a yellow wavering line. Beside the first three, white passes of the seeing pen lie close alongside it, following its waves in tight columns. Beside the last two, red lines of the blind pen wander and cross, running far below the line and tangling"}
:::static
import afield.ai.learning.pen.*
import kotlin.math.*

val yours = FLine().also { f -> for (i in 0..120) { val y = i * 2.0; val x = 14 * sin(y / 40) + 5 * sin(y / 13); if (i == 0) f.moveTo(x, y) else f.lineTo(x, y) } }
fun beside(model: String, seed: Long) = listOf(
    yours.copy().apply { color = vec(1.0, 0.85, 0.3, 1.0); thickness = 1.5 },
    yours.continueWith(PenModel.bundled(model), steps = 1500, seed = seed).apply {
        color = if (model.endsWith("blind")) vec(1.0, 0.6, 0.6, 1.0) else vec(0.9, 0.92, 1.0, 1.0); thickness = 1.0
    })
lines(sideBySide([beside("michaux", 1), beside("michaux", 2), beside("michaux", 3), beside("michaux-blind", 1), beside("michaux-blind", 2)], gap = 30.0),
    w = 520.0, h = 440.0, fitToSize = true)
```

## Cracks that stop at a line

The *cracks* pen was trained on webs of cracks inside wobbling outlines, every crack ending where
it meets a line. Given an outline of ours, the seeing pen cracks it from the inside and its cracks
stop on lines; the blind pen leaves the outline, because it cannot tell where it is.

```diffstatic_runner {data-lang=kotlin screenshot="assets/a_pen_that_sees/cracks.png" embed=true alt="Five copies of a yellow wobbling outline. Inside the first three, white cracks of the seeing pen cross the shape and gather towards its middle, one of them wandering out in a long loop. Around the last two, red strokes of the blind pen cross the outline and scatter away from it"}
:::static
import afield.ai.learning.pen.*
import kotlin.math.*

val outline = FLine().also { f -> for (i in 0..90) { val a = i * 2 * PI / 90; val r = 60 * (1 + 0.15 * sin(3 * a) + 0.08 * cos(5 * a)); if (i == 0) f.moveTo(cos(a) * r, sin(a) * r) else f.lineTo(cos(a) * r, sin(a) * r) } }
fun inside(model: String, seed: Long) = listOf(
    outline.copy().apply { color = vec(1.0, 0.85, 0.3, 1.0); thickness = 1.5 },
    outline.continueWith(PenModel.bundled(model), steps = 700, seed = seed, liftAtEnd = true).apply {
        color = if (model.endsWith("blind")) vec(1.0, 0.6, 0.6, 1.0) else vec(0.9, 0.92, 1.0, 1.0); thickness = 1.0
    })
lines(sideBySide([inside("cracks", 1), inside("cracks", 2), inside("cracks", 3), inside("cracks-blind", 1), inside("cracks-blind", 2)], gap = 30.0),
    w = 520.0, h = 300.0, fitToSize = true)
```

It is not perfect. The cracks crowd towards the middle of the shape, and now and then the pen
leaves the shape to draw an outline of its own, which is what its corpus begins with.
`liftAtEnd = true` hands it the outline as a finished stroke, which matters: in its corpus, a
finished outline is followed by a new stroke, not more outline.

## Seeing against blind, measured

Two numbers say what sight adds. *Following* is the share of a pen's points that have older ink
within 8 pixels, `following(line)`; *joins* is the share of its strokes that end on ink,
`joins(line)`. Each is averaged over twenty seeds, from the primers above.

```diffstatic_runner {data-lang=kotlin screenshot="assets/a_pen_that_sees/measures.png" embed=true alt="A table with columns seeing and blind. Michaux beside a line, following: 0.86 seeing, 0.59 blind. Cracks in an outline, joins: 0.85 seeing, 0.19 blind"}
:::static
import afield.ai.learning.pen.*
import kotlin.math.*

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

The seeing pens also predicted their validation drawings better than the blind ones, but by much
less than these numbers suggest: a pen being shown a drawing one point at a time has the drawing
to lean on either way. The difference is in what a pen does on its own.

## A field of lines

Left to run from one short stroke, the Michaux pen lays down columns of trembling lines, each pass
following the last, a little changed, a field that accumulates.

```diffstatic_runner {data-lang=kotlin screenshot="assets/a_pen_that_sees/field.png" embed=true alt="A field of fine pale lines on black: several dense columns of trembling vertical strokes laid side by side, each column a run of passes echoing one another, with single long wavering lines at the edges"}
:::static
import afield.ai.learning.pen.*
import kotlin.math.*

val first = FLine().also { f -> for (i in 0..60) { val y = i * 2.5; val x = 6 * sin(y / 25); if (i == 0) f.moveTo(x, y) else f.lineTo(x, y) } }
val writer = PenLine(first, model = PenModel.bundled("michaux"), seed = 7)
writer.grow(5000)
lines(writer.line.apply { color = vec(0.9, 0.92, 1.0, 1.0); thickness = 0.8 }, w = 520.0, h = 320.0, fitToSize = true)
```

## Drawing with it

A sensing pen can see ink it did not draw. `PenLine(..., surroundings = lines)` shows it lines
before it starts, as though they were already on the page, and `sense(lines)` adds more later. In
the starting point's sixth box, your newest stroke is the primer and everything else you have drawn
is surroundings, so the pen hatches beside your line and around the rest.

```diffstatic_runner {data-lang=kotlin screenshot="assets/a_pen_that_sees/draw.png" embed=true macro-file="../../dev_docs/agents/transcripts/a_pen_that_sees.yaml" run-box="ps_draw" capture-box="ps_draw" execution="launch" wait="12" alt="A box's output: a drawing panel holding one yellow curved line, and below it a panel where the pen has laid a dense column of white trembling passes beside the yellow line"}
:::static
import afield.ai.learning.pen.*

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
        lines([border] + shown + w.drawn.apply { color = vec(0.9, 0.92, 1.0, 1.0); thickness = 1.0 }, channel = "pen", w = 520.0, h = 320.0)
    }
}
```

The seventh box does the same with the cracks pen: draw a closed shape and it cracks it.

## Training a pen that sees

What a pen sees is computed the same way in Python, where it is trained, and in Kotlin, where it
draws (`fieldpen/sense.py` and `PenSense`, held together by a test). For training, `prepare` adds
it to every point of a corpus:

```diffstatic {data-lang=bash}
uv run --project . --group pen python -m fieldpen.prepare mine/train train.npz --scale 8 --sense 16
uv run --project . --group pen python -m fieldpen.prepare mine/valid valid.npz --scale 8 --sense 16
uv run --project . --group pen python -m fieldpen.train --train train.npz --valid valid.npz --out runs/mine \
    --hidden 256 --epochs 200 --lr 2e-3 --batch 64 --crop 256 --samples 2048 --input-noise 0.02 --device mps
```

`train` reads the sense settings from the corpus and makes a pen with eighteen extra inputs.
`--init` with the handwriting pen widens it with those inputs set to zero, so fine-tuning starts
from exactly the pen it was. Export and load as for any pen ([Teaching a pen](teaching_a_pen.md));
Kotlin works out what the pen sees as it draws, from the points it has drawn and any surroundings.

A corpus for a seeing pen has to need sight. The Michaux corpus draws each column as one unbroken
stroke, down and back up beside itself, so at every point the line beside it says where to go;
`PenDrawings.michaux` and `PenDrawings.cracks` are the two generators.

## Source files

| file | what is in it |
|---|---|
| [`afield/ai/learning/pen/sense.kt`](https://github.com/OpenEndedGroup/Field4/blob/main/src/jvmMain/kotlin/afield/ai/learning/pen/sense.kt) | `PenSense`: what a pen sees, in Kotlin |
| [`python/fieldpen/sense.py`](https://github.com/OpenEndedGroup/Field4/blob/main/python/fieldpen/sense.py) | the same, in Python, and the canonical definition |
| [`afield/ai/learning/pen/corpora.kt`](https://github.com/OpenEndedGroup/Field4/blob/main/src/jvmMain/kotlin/afield/ai/learning/pen/corpora.kt) | `PenDrawings.michaux`, `PenDrawings.cracks`, `following`, `joins` |
| [`afield/ai/learning/pen/penviz.kt`](https://github.com/OpenEndedGroup/Field4/blob/main/src/jvmMain/kotlin/afield/ai/learning/pen/penviz.kt) | `seeing()` |
| `src/jvmMain/resources/afield/pen/models/` | `michaux`, `cracks` and their blind twins `michaux-blind`, `cracks-blind` |

??? aside "What did not work first"
    The first Michaux corpus drew every pass as its own stroke, lifting and landing beside the last,
    and its pen, primed with a line, wandered off: nothing in a primed continuation told it to lift
    and land. One unbroken stroke per column fixed it. The first sense was only the sixteen
    directions, which reach 24 pixels; on the rim of a 110-pixel outline that cannot tell inside
    from outside, and the cracks pen drew new outlines instead of cracking the one it was given. The
    two numbers pointing at the middle of the ink are what fixed that. Python first rounded the
    displacements to float32 before summing them into positions, and a point lying exactly on the
    boundary between two directions fell in a different one than Kotlin put it; positions are now
    summed in float64 on both sides.
