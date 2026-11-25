# A learned pen

*A recurrent network that has watched people write by hand, and draws the next point. Give it any
[FLine](fline_examples.md) and it carries on from where the line ends: one continuation, a dozen
alternatives, or a pen that keeps writing live while you change how it samples.*

The model is the one from Shan Carter, David Ha, Ian Johnson and Chris Olah's
[*Experiments in Handwriting with a Neural Network*](https://distill.pub/2016/handwriting/)
(Distill, 2016). It is a 500-cell LSTM, trained on the IAM On-Line Handwriting Database, whose
output at each step is a probability for every possible next pen movement. It ships with Field and
runs in Kotlin, so nothing needs installing. It learned handwriting, not text, so what it writes
looks like writing and says nothing. [PenGPT](#writing-words-pengpt), further down, writes the text
you give it, in any of a thousand hands, and draws what a phrase describes.

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
[0.2, 0.65, 1.2].forEachIndexed { row, temperature ->
    [0.0, 0.65, 1.2].forEachIndexed { column, noise ->
        val at = vec(column * 200.0, row * 70.0, 0.0)
        val pen = PenControls(temperature = temperature, noise = noise)
        grid += start + at
        grid +=
            (start.continueWith(steps = 110, seed = 5, controls = pen) + at).apply { color = vec(0.75, 0.85, 1.0, 1.0) }
    }
}
lines(grid, w = 520.0, h = 260.0, fitToSize = true)
```

## What the pen expects next

Before every point the pen has a picture of where the next one will land: twenty guesses, each a
small oval of likely places, some more likely than others. `distribution()` on a `PenLine` returns
that picture in drawing coordinates, and `ellipses()` draws it, each guess as an ellipse two
standard deviations wide, fainter the less likely it is. `closeUp()` keeps the pen's last few
points with it, so a moment can be looked at on its own.

Here the pen writes two hundred points at its learned spread (`noise = 1.0`), keeping a close-up
of every moment. The top row is the four moments mid-stroke where it was least sure; the bottom row
the four least sure with the pen up, about to start somewhere new. Both rows are at the same
scale. `sideBySide` lays groups of lines out left to right without overlapping.

```diffstatic_runner {data-lang=kotlin screenshot="assets/learned_pen/expects.png" embed=true alt="Two rows of close-ups at the pen tip. Top row: four short white strokes, each ending in a cluster of tiny blue ellipses barely bigger than the line is wide. Bottom row: four strokes ending with the pen lifted, each followed by large overlapping ellipses, a small one for a short hop and a broad faint one for a jump to the next letter"}
:::static
import afield.ai.learning.pen.*

val start = fline {
    moveTo(0, 0)
    cubicTo(4, -14, 12, -14, 12, 0)
    cubicTo(12, 8, 20, 8, 24, 0)
    color = vec(1.0, 0.85, 0.3, 1.0)
    thickness = 2.0
}

val writer = PenLine(start, seed = 4, controls = PenControls(noise = 1.0))
val moments = (0 until 200).map {
    writer.grow()
    writer.closeUp()
}

val down = moments.filter { !it.penUp }.sortedByDescending { it.distribution.uncertainty }.take(4)
val up = moments.filter { it.penUp }.sortedByDescending { it.distribution.uncertainty }.take(4)
lines(
    sideBySide(down.map { it.lines() }) + sideBySide(up.map { it.lines() }, y = 60.0),
    w = 520.0,
    h = 260.0,
    fitToSize = true,
)
```

Mid-stroke, even at its least certain, the pen knows within a pixel or two where it is going.
With the pen up it is choosing between a short hop (the dot of an i, the cross of a t) and a jump
to the next letter, and the two guesses are far apart. `uncertainty` is the spread of the whole
mixture, the number used to pick these moments.

The same question asked hundreds of times gives a map of where the pen might go.
`futuresDensity()` runs that many futures from the end of a line and piles them into an image,
brightest where most of them went, on a log scale so a single stray future still shows. It returns
PNG bytes, which `display` shows as a picture.

```diffstatic_runner {data-lang=kotlin screenshot="assets/learned_pen/density.png" embed=true alt="A yellow wave on black, and from its end a fan of blue light: bright and narrow just past the tip where the futures agree, spreading into a broad haze to the right, with faint single strokes wandering out at the edges"}
:::static
import afield.ai.learning.pen.*

val start = fline {
    moveTo(0, 0)
    cubicTo(4, -14, 12, -14, 12, 0)
    cubicTo(12, 8, 20, 8, 24, 0)
}
display("density", PenLine(start, seed = 4).futuresDensity(count = 600, steps = 40))
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
lines(
    row(null, 0.0),
    row(4.0, 140.0).apply { color = vec(0.5, 1.0, 0.6, 1.0) },
    row(-4.0, 280.0).apply { color = vec(1.0, 0.55, 0.6, 1.0) },
    w = 520.0,
    h = 340.0,
    fitToSize = true,
)
```

Which cell does what is not written down anywhere; the network found its own uses for them. To
look for one, colour the line by it. A `PenLine` made with `recordMemory = true` keeps every
cell's output for every point it draws, `coloredByCell(cell)` draws the line blue where that cell
is low and orange where it is high, and `liveliestCells(n)` names the cells that changed most
while it wrote, which is where to start looking.

```diffstatic_runner {data-lang=kotlin screenshot="assets/learned_pen/cells.png" embed=true alt="The same line of machine handwriting written four times, one above the other, each coloured from blue to orange by a different memory cell; the colours change in different places in each row, one bright orange at the start of the line, another along the final loop"}
:::static
import afield.ai.learning.pen.*

val start = fline {
    moveTo(0, 0)
    cubicTo(4, -14, 12, -14, 12, 0)
    cubicTo(12, 8, 20, 8, 24, 0)
}
val writer = PenLine(start, seed = 4, recordMemory = true)
writer.grow(400)

val rows =
    writer.liveliestCells(4).mapIndexed { i, cell ->
        writer.coloredByCell(cell, thickness = 2.0).map { it + vec(0.0, i * 45.0, 0.0) }
    }
lines(rows.flatten(), w = 520.0, h = 300.0, fitToSize = true)
```

## Lift as pressure

The pen was never shown pressure, but it does know, at every point, how likely it is to lift.
`widthByLift()` draws the line thick where the pen is sure to stay on the paper and thin where it
expects to lift, which looks a lot like a hand pressing and easing off: strokes swell in the middle
and taper to their ends. It is the model's confidence, drawn as width.

```diffstatic_runner {data-lang=kotlin screenshot="assets/learned_pen/pressure.png" embed=true alt="A line of machine handwriting in white whose strokes swell and thin: heavy through the middle of each letter and tapering to hairlines where the pen is about to lift"}
:::static
import afield.ai.learning.pen.*

val start = fline {
    moveTo(0, 0)
    cubicTo(4, -14, 12, -14, 12, 0)
    cubicTo(12, 8, 20, 8, 24, 0)
}
val writer = PenLine(start, seed = 4)
writer.grow(400)
lines(writer.widthByLift(), w = 520.0, h = 160.0, fitToSize = true)
```

## Writing words: PenGPT

The pen above has learned handwriting but not text. PenGPT, from Sam Greydanus's
[*PenGPT*](https://greydanus.github.io/2026/11/12/pengpt/) (2026), writes the words you give it.
It is a small transformer, a GPT, that treats a pen like a language model treats text. Ink is snapped to a
grid and becomes a walk in eight directions, plus pen-down and pen-up. Common runs of moves are
merged into single tokens, and the model writes one token at a time, the way a language model
writes words. While it writes, it reads what it has been told: the text and a writer, for the
handwriting model, or a phrase, for the drawing model.

There are two models. `PenGpt.hand` has 6 million parameters and knows 1,192 hands. `PenGpt.sketch`
has 54 million and draws what a phrase describes. Both run in Kotlin. Both also continue what they
are shown, as the pen above does: a few words in one hand, or a drawing you started.

!!! note "The weights download the first time you use them"
    PenGPT's weights are not part of Field. The first call to `PenGpt.hand` downloads 6 MB from
    the [PenGPT release](https://github.com/greydanus/pengpt/releases), `PenGpt.sketch` 50 MB, and
    the first typed prompt 375 MB more, for the SigLIP 2 text encoder that turns a phrase into
    what the drawing model is told. They are kept in `~/.afield/onnx/models/pengpt/`.

`write` takes the text and a writer, and returns one FLine per word, laid out in lines no wider
than `width`. Here it writes the classic test sentence in writer 40's hand:

```diffstatic_runner {data-lang=kotlin screenshot="assets/learned_pen/pengpt_write.png" embed=true alt="Two lines of joined-up white handwriting reading: The quick brown fox jumps over, the lazy dog"}
:::static
import afield.ai.learning.pen.*

val hand = PenGpt.hand
val words = hand.write("The quick brown fox jumps over the lazy dog", writer = hand.writers[40], width = 500.0)
lines(words, w = 520.0, h = 130.0, fitToSize = true)
```

`scale` is drawing units per model unit, 100 by default, which gives letters about 12 units
high. A word takes about a tenth of a second.

### A thousand hands

`writers` lists the hands the model was trained on, as anonymous names. Here are six of them
writing the same three words:

```diffstatic_runner {data-lang=kotlin screenshot="assets/learned_pen/pengpt_hands.png" embed=true alt="Six rows of the phrase a learned hand, each in a different hand: two slanted joined-up scripts, a small print with capital-letter Ns and Ds, an upright print, a rounded print and a light italic"}
:::static
import afield.ai.learning.pen.*

val hand = PenGpt.hand
val rows = [1, 150, 300, 640, 900, 1100].flatMapIndexed { row, k ->
    hand.write("a learned hand", writer = hand.writers[k], at = vec(0.0, row * 55.0, 0.0))
}
lines(rows, w = 520.0, h = 340.0, fitToSize = true)
```

Each hand is a vector the model learned for that writer, and `writer = ""` is the model's
anonymous writer. `temperature = 0`, the default, takes the likeliest token every time, which is
each writer's tidiest hand. Raising it lets the letters loosen. The same writer and seed, at 0,
0.5 and 0.9:

```diffstatic_runner {data-lang=kotlin screenshot="assets/learned_pen/pengpt_temperature.png" embed=true alt="The words loosening up written three times by one hand, one above the other: tidy at the top, then with taller loops, then with letters that wobble and spread apart"}
:::static
import afield.ai.learning.pen.*

val hand = PenGpt.hand
val rows = [0.0, 0.5, 0.9].flatMapIndexed { row, temperature ->
    hand.write("loosening up", writer = hand.writers[40], temperature = temperature, seed = 2, at = vec(0.0, row * 55.0, 0.0))
}
lines(rows, w = 520.0, h = 180.0, fitToSize = true)
```

### In the hand of a few words

`writeLike` shows the model words someone wrote, with what each says, and lets it carry on in that
hand. The examples are read as though the same writer had written them a moment earlier, and the
model keeps going. Here the examples, in yellow, are two words in three of Field's plotter fonts
(`HersheyFont`). The white words are new text in the hand each pair suggests:

```diffstatic_runner {data-lang=kotlin screenshot="assets/learned_pen/pengpt_like.png" embed=true alt="Three rows. On the left, in yellow, the words garden river in a joined cursive, a plain sans-serif and a slanted italic; on the right, in white, the words a quiet morning handwritten in a matching style: slanted and joined, upright print, and slanted print"}
:::static
import afield.ai.learning.pen.*

val hand = PenGpt.hand
val page = mutableListOf<FLine>()
["cursive", "futural", "timesi"].forEachIndexed { row, font ->
    val y = row * 70.0
    val garden = HersheyFont.load(font).text("garden", at = vec(0.0, y, 0.0))
    val river = HersheyFont.load(font).text("river", at = vec(150.0, y, 0.0))
    page += [garden, river].map { it.apply { color = vec(1.0, 0.85, 0.3, 1.0) } }
    page += hand.writeLike(["garden" to garden, "river" to river], "a quiet morning", at = vec(285.0, y, 0.0))
}
lines(page, w = 520.0, h = 220.0, fitToSize = true)
```

The examples can be any FLines: words drawn on a drawing panel, traced from a scan, or written
by the pen above. `writeLike` reads their height to set its scale, and the line that 95% of the ink
sits above as their baseline. Pass `scale` and `baseline` if your writing has long descenders.

### Drawing what it is told

`PenGpt.sketch.draw(phrase)` draws a phrase, `size` drawing units on its longer side, centred on
`at`. The phrase goes through SigLIP 2's text encoder (`SiglipText`), which takes a few seconds
to load and about a second for each new phrase, and the drawing model is told the vector that
comes out. Each drawing takes one to six seconds.

```diffstatic_runner {data-lang=kotlin screenshot="assets/learned_pen/pengpt_draw.png" embed=true alt="Three white line drawings side by side: a seated cat with pointed ears, a lighthouse with light rays coming from its lamp, and a bicycle with two round wheels"}
:::static
import afield.ai.learning.pen.*

val sketch = PenGpt.sketch
val drawings = ["a cat", "a lighthouse", "a bicycle"].mapIndexed { k, phrase ->
    sketch.draw(phrase, seed = 1, size = 150.0, at = vec(k * 175.0, 0.0, 0.0))
}
lines(drawings, w = 520.0, h = 180.0, fitToSize = true)
```

`source` asks for the manner of one of the ten collections the model learned from (`writers`
lists them: `quickdraw`, `tuberlin`, `icons` and the rest), and `guidance` sets how hard each
drawing is pushed towards its phrase, 2 by default and 1 for not at all. `ink` and `strokes` ask
for an amount of drawing. Here is the same house, asked for in 3, 8 and 20 strokes:

```diffstatic_runner {data-lang=kotlin screenshot="assets/learned_pen/pengpt_strokes.png" embed=true alt="Three line drawings of a house, growing in detail from left to right: a triangle roof on a box with a door; a box with a door, a window and a small annex at the side; and a wide roof with two windows in it over a box with a double door"}
:::static
import afield.ai.learning.pen.*

val sketch = PenGpt.sketch
val houses = [3, 8, 20].mapIndexed { k, strokes ->
    sketch.draw("a house", seed = 1, strokes = strokes, size = 150.0, at = vec(k * 175.0, 0.0, 0.0))
}
lines(houses, w = 520.0, h = 180.0, fitToSize = true)
```

### Finishing a drawing

`continueDrawing` shows the model a drawing you started as the beginning of one of its own, and
returns the strokes it adds. The model was trained on whole drawings, so it needs to know where
the finished one will sit. By default that is your strokes' bounds, grown by half again. Here
three yellow circles are told `"a face"` with three seeds:

```diffstatic_runner {data-lang=kotlin screenshot="assets/learned_pen/pengpt_faces.png" embed=true alt="Three yellow circles, each finished in white as a different face: one with round eyes, ears and a frowning mouth; one with a nose and a wide smile; and one with hair framing it, oval eyes and an open mouth"}
:::static
import afield.ai.learning.pen.*

val page = mutableListOf<FLine>()
for (k in 0 until 3) {
    val circle = fline {
        circle(k * 175.0, 0.0, 60.0)
        color = vec(1.0, 0.85, 0.3, 1.0)
        thickness = 2.0
    }
    page += circle
    page += PenGpt.sketch.continueDrawing(circle, "a face", seed = k.toLong())
}
lines(page, w = 520.0, h = 200.0, fitToSize = true)
```

### PenGPT behind PenLine

`strokes(...)` turns either model into a `StrokeModel`, so `continueWith`, `futuresWith` and
`PenLine` drive it the same way they drive the pen above. Each token becomes a few small steps,
smoothed as PenGPT smooths its own ink, and the handwriting model sets each word beside the one
before it. A `PenLine` growing a few steps a frame watches it write. Here the yellow stroke is
continued as `"a snake"`:

```diffstatic_runner {data-lang=kotlin screenshot="assets/learned_pen/pengpt_snake.png" embed=true alt="A short yellow arc, and continuing from its end a long white snake: a doubled outline that curves down the page in an S, with a head and a forked tongue at the top left"}
:::static
import afield.ai.learning.pen.*

val start = fline {
    moveTo(0, 0)
    cubicTo(20, -40, 50, -40, 60, -10)
    color = vec(1.0, 0.85, 0.3, 1.0)
    thickness = 2.0
}
val more = start.continueWith(PenGpt.sketch.strokes("a snake"), steps = 600, scale = 220.0)
lines(start, more, w = 520.0, h = 260.0, fitToSize = true)
```

A step is one grid cell, 0.02 model units for handwriting and 0.0125 for drawings, so `scale`
should be about 100 for writing at canvas size, and about the size of the drawing for `sketch`.

### Teaching it a manner

The drawing model knows the ten collections of drawings it was trained on, its `writers`
(QuickDraw doodles, TU Berlin sketches, icons and others). None of them looks like the
[Michaux fields](a_pen_that_sees.md#a-field-of-lines) of the pen that sees, and no phrase draws
one. The model can learn one, though. Field generates the drawings, PenGPT's own training code
fine-tunes the model on them on a GPU, and Field loads the result.

`writeCorpus` writes drawings the way the model reads them, one JSON line each, holding the
points in the model's frame and the tokens Field makes of them. Here it writes 7,000 drawings from
the Michaux generator for training and 150 for checking, keeping those short enough for the
model's 768-token context:

```diffstatic {data-lang=kotlin}
import afield.ai.learning.pen.*

watchdog = 120 // 7,000 drawings take about a minute
val sketch = PenGpt.sketch
val dir = File(PenGpt.folder, "michaux").apply { mkdirs() }
fun fits(d: FLine) = sketch.encode(sketch.inFrame(d)).size <= sketch.contextLength - 2
val train = (2_000L until 9_000L).toList().parallelStream().map { PenDrawings.michaux(it) }.toList().filter(::fits)
val valid = (100_000L until 100_150L).map { PenDrawings.michaux(it) }.filter(::fits)
sketch.writeCorpus(File(dir, "michaux_train.jsonl"), train, withPoints = false)
sketch.writeCorpus(File(dir, "michaux_valid.jsonl"), valid)
```

5,730 of the 7,000 fit, 3.5 million tokens. Field's tokens are PenGPT's: given the same points,
PenGPT's tokenizer makes the same tokens for every drawing. `tools/pengpt_fit_style.py` then
fits the model to the corpus. It needs a checkout of
[greydanus/pengpt](https://github.com/greydanus/pengpt), PyTorch and the release's `sketch.pt`:

```diffstatic {data-lang=bash}
python tools/pengpt_fit_style.py --repo ~/pengpt --checkpoint ~/pengpt/weights/sketch.pt \
    --train michaux_train.jsonl --valid michaux_valid.jsonl --mode full --steps 2000 \
    --npz ~/pengpt/weights/sketch.npz,sketch-michaux.npz --out michaux.fit.json
```

The fit has three modes. They differ in how much of the model is allowed to move, measured on
the 125 held-out drawings in nats per token (lower is better):

<div class="field-data-table" markdown>

| `--mode` | what moves | held out |
| --- | --- | --- |
| (the released model, best source) | nothing | 3.04 |
| `vector` | the source vector the model is told | 2.58 |
| `context` | every row it cross-attends to | 2.44 |
| `full` | all 54 million weights | 2.15 |

</div>

Only `full` draws Michaux. The other two lower the loss with statistics of vertical lines, but
what they draw is still sketches: boxes, faces and stray strokes. On one RTX 4090, `full` takes
three minutes for 2,000 steps of 16 drawings, and the held-out loss levels off with the training
loss, so the model learns the manner rather than memorizing the drawings. It writes the model to
`--npz`, 100 MB in float16, with the fitted vector as the anonymous source, so an empty prompt
draws in the new manner. Copied into `PenGpt.folder` (`~/.afield/onnx/models/pengpt/`),
`PenGpt.named` loads it by name. Below, the generator draws the top row and the fine-tuned model,
told nothing, the bottom one:

```diffstatic_runner {data-lang=kotlin screenshot="assets/learned_pen/pengpt_michaux.png" capture-profile="local-weights" timeout="240" embed=true alt="Two rows of four drawings, each two columns of parallel wavering vertical lines: the top row in grey from Field's Michaux generator, with a fine tremor along every line, the bottom row in white from the fine-tuned drawing model, smoother and more widely waved"}
:::static
import afield.ai.learning.pen.*

watchdog = 120 // a drawing of 600 tokens takes about five seconds
val michaux = PenGpt.named("sketch-michaux")
val generated = (0 until 4).map { k ->
    [PenDrawings.michaux(200_000L + k).apply { color = vec(0.6, 0.6, 0.65, 1.0) }]
}
val learned = (0 until 4).map { k ->
    [michaux.draw(PenGptPrompt(), temperature = 0.8, seed = k.toLong())]
}
lines(stacked([sideBySide(generated, gap = 30.0), sideBySide(learned, gap = 30.0)], gap = 40.0), w = 520.0, h = 330.0, fitToSize = true)
```

The two Michaux pens are different instruments. The [pen that sees](a_pen_that_sees.md) grows one
field a step at a time, for as long as you let it, and answers the ink around it. The fine-tuned
PenGPT composes a whole page of columns at once, more evenly and closer to the generator, but it
loses the fine tremor to its grid and its smoothing, and a page is as much as fits in its context.

## Training your own pen

The pen knows handwriting because it watched handwriting. Show it something else and it will
learn that. [Teaching a pen](teaching_a_pen.md) trains six new pens on drawings Field generates
(plotter lettering, harmonographs, plants, Truchet curves, flocking trails and a two-handed
script), finds a learned habit inside one of them, and fine-tunes this pen on a taste. They all
ship with Field: `PenModel.bundled("hershey")` passed to `continueWith` writes in plotter
lettering. [A pen that sees](a_pen_that_sees.md) trains pens that also sense the ink around their
tip, so they can follow a line, stop when they meet one, or hatch around a drawing.

Training uses the optional `fieldpen` Python package (in `python/fieldpen`, the same model in
PyTorch). The package also runs in a [Python box](python.md)
(`from fieldpen import load_model, Session`), and `python -m fieldpen.lab` opens a page in the
browser for drawing primers, watching futures and editing cells without Field.
`python/fieldpen/README.md` has both.

## Other models

`continueWith`, `futuresWith` and `PenLine` take any `StrokeModel`: something that makes a
`StrokePen`, which can be fed a displacement, asked for the next one, and forked. `PenModel` is the
one that ships, and `PenGpt.strokes(...)` [puts PenGPT behind the same calls](#pengpt-behind-penline).
Implement the two interfaces to put another model there: a Markov chain over recorded strokes, a
model running in Python, or a rule you wrote.

## Source files

| file | what is in it |
|---|---|
| [`afield/ai/learning/pen/pen.kt`](https://github.com/OpenEndedGroup/Field4/blob/main/src/jvmMain/kotlin/afield/ai/learning/pen/pen.kt) | `PenModel`, `Pen`, `PenLine`, `PenControls`, `PenDistribution`, `continueWith`, `futuresWith`, `StrokeModel` |
| [`afield/ai/learning/pen/penviz.kt`](https://github.com/OpenEndedGroup/Field4/blob/main/src/jvmMain/kotlin/afield/ai/learning/pen/penviz.kt) | `ellipses`, `closeUp`, `coloredByCell`, `liveliestCells`, `widthByLift`, `futuresDensity`, `sideBySide`, `stacked` |
| [`python/fieldpen/`](https://github.com/OpenEndedGroup/Field4/tree/main/python/fieldpen) | the PyTorch model, training, data preparation, export and the browser lab |
| `src/jvmMain/resources/afield/pen/distill-b64.json` | the Distill article's published weights, shared by Kotlin and Python |
| [`afield/ai/learning/pen/pengpt.kt`](https://github.com/OpenEndedGroup/Field4/blob/main/src/jvmMain/kotlin/afield/ai/learning/pen/pengpt.kt) | `PenGpt` (`hand`, `sketch`, `named`, `write`, `writeLike`, `draw`, `continueDrawing`, `strokes`, `encode`, `decode`, `generate`, `writeCorpus`), `PenGptPrompt`, `PenGptSampling` |
| [`tools/pengpt_fit_style.py`](https://github.com/OpenEndedGroup/Field4/blob/main/tools/pengpt_fit_style.py) | fits PenGPT to a corpus from `writeCorpus`: a source vector, the context, or every weight |
| [`afield/ai/learning/pen/siglip.kt`](https://github.com/OpenEndedGroup/Field4/blob/main/src/jvmMain/kotlin/afield/ai/learning/pen/siglip.kt) | `SiglipText`, the SigLIP 2 text encoder that turns a phrase into what `PenGpt.sketch` is told |

The Kotlin forward pass is checked against PyTorch's to four decimal places (`PenModelTest`), and
the PyTorch port against the original article's JavaScript. `PenGptTest` checks PenGPT's
tokenizer, its decoder and the `StrokeModel` adapter on the small random model from PenGPT's own
tests, and that the released handwriting model writes words of a sensible size.

??? aside "Where this came from"
    The PyTorch port, its training code and the lab were written in a ChatGPT conversation in
    October 2026, as a standalone package with a subprocess bridge to Field. Field's version keeps
    the Python for training and runs inference in Kotlin instead: one step of a 500-cell LSTM is
    about a million multiply-adds, which the JVM does fast enough to write live, so drawing needs
    no bridge. The weights are the article's own quantized release, decoded, not retrained.
    The port is Apache-2.0 (`python/fieldpen/LICENSE`, `NOTICE`); the Distill repository's
    licence is beside the weights.

??? aside "Where PenGPT came from"
    `pengpt.kt` is a port of `inference/pen.py` from
    [greydanus/pengpt](https://github.com/greydanus/pengpt) (Apache-2.0): the tokenizer, the
    decoder with its rotary and pen-position features, classifier-free guidance and the hints for
    ink and strokes. `siglip.kt` does in Kotlin what that repository's `inference/words.py` does
    with `transformers`, from the same packed weights. The weights are the release's own, read
    from its `.npz` files with Field's NumPy reader. PenGPT's paper, in the repository's `paper/`,
    has the training recipe and the ablations: data, augmentation and training on several words by
    one writer matter most; model size, pen-position features and the merges matter little.

## Where to go next

- [Teaching a pen](teaching_a_pen.md) and [A pen that sees](a_pen_that_sees.md) train new LSTM
  pens on drawings Field makes. PenGPT's training code is in its repository (`pengpt.py train`),
  and the same corpora could train a PenGPT.
- Open the [starting point](agent_transcripts.md#learned_pen) to draw a primer and watch the pen
  write.
- Source: [`pen.kt`](https://github.com/OpenEndedGroup/Field4/blob/main/src/jvmMain/kotlin/afield/ai/learning/pen/pen.kt),
  [`pengpt.kt`](https://github.com/OpenEndedGroup/Field4/blob/main/src/jvmMain/kotlin/afield/ai/learning/pen/pengpt.kt),
  [`siglip.kt`](https://github.com/OpenEndedGroup/Field4/blob/main/src/jvmMain/kotlin/afield/ai/learning/pen/siglip.kt).

Next builds and open questions:

- **Your own hand, live.** A drawing panel where you write three words and type what they say,
  and `writeLike` carries on in your hand as you type: the starting point's third box, with
  PenGPT in it.
- **A writer vector from your ink.** PenGPT's `hand.py` fits one new writer vector to a few traced
  words with the rest of the model frozen. That needs gradients, so it is Python's job, but the
  vector it saves (`me.npy`) is what `write(..., style = ...)` takes.
- **Guided PenGPT.** `PenLine.guide` forks the pen for every proposal, which for PenGPT copies its
  attention cache each time. A guide that asks the model for its next-token distribution once,
  and scores tokens rather than forks, would steer it as cheaply as the LSTM.
- **Mixing the two pens.** The LSTM is sure mid-stroke and PenGPT is sure what the next letter is.
  Handing the stroke to the LSTM and the letter to PenGPT, or averaging their next steps, is
  untried.
