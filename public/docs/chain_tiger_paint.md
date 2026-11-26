# An SVG Tiger, Painted

The Ghostscript tiger is a standard test picture for 2D renderers: a few hundred filled and stroked
paths with flat colours, from a broad orange head down to whiskers a fraction of a pixel wide.
`GeziraSvg` reads it into Field FLines, each carrying its colour, whether it is filled or stroked,
and its stroke width. [`PaintCanvas`](paint_canvas.md) takes any FLine as a region to paint or a
path to drag a brush along. Put the two together and a vector drawing becomes an oil painting, one
path at a time, in the order the SVG paints them.

The SVG fixes what goes where; everything else is the painter's choice. The same few hundred
shapes come out as a heavy wet-into-wet oil sketch, slabs of knifed paint, a field of dots, a
nervous line drawing, a watercolour whose colours run into each other, or a painting blown apart
with a straw. Staying close to the original is one more choice among these, with its own costs, and
the page measures it near the end.

## The tiger as FLines

`GeziraSvg.parse` reads the SVG into an `SvgDocument`. Its `lines` are FLines in paint order and in
the document's own units: a 900 × 900 view box, y down. An element with both a fill and a stroke
becomes two FLines, the fill first. This box draws the document with the Gezira kernels, the
reference for everything below, and counts what the painter will be handed:

```diffstatic_runner {data-lang=kotlin screenshot="assets/chain_tiger_paint/census.png" wait="5" capture="box" open-outputs="true" padding="18" timeout="180" name="chain-tiger-paint-census" embed=true alt="The Ghostscript tiger drawn by the Gezira kernels at 300 pixels on white: an orange and black striped head with green eyes, an open pink mouth with cream fangs, white fur on the chin and whiskers fanning out to the left. Below it, a table: 239 elements read; 304 FLines, 226 fills and 78 strokes; 39 colours; fill areas from 22 to 257828 view-box units squared, median 303; five stroke widths from 0.18 to 3.53; and 0 of 226 fills that change under even-odd."}
:::static
import afield.graphics.nile.*

val doc = GeziraSvg.parse(GeziraSvg.tiger())
val fills = doc.lines.filter { it.filled }
val strokes = doc.lines.filter { it.stroked }
val areas = fills.map { abs(it.area2D().d) }.sorted()

// Paint regions are even-odd. Rasterise each fill both ways and count the ones that differ.
val fit = doc.fit(200, 200)
fun ink(f: FLine) =
    GeziraFLine.render([f], 200, 200, fit).canvas.rgba.let { px -> (3 until px.size step 4).map { px[it] } }
val differ = fills.count { f ->
    val nonZero = ink(f)
    f.fillRule = FillRule.EVEN_ODD
    val evenOdd = ink(f)
    f.fillRule = FillRule.NON_ZERO
    nonZero.indices.sumOf { abs(nonZero[it] - evenOdd[it]).d } > 1.0
}

display("main", GeziraSvg.png(GeziraSvg.render(doc, 300, 300).canvas.rgba, 300, 300))
markdown(
    "census",
    """
    | | |
    |---|---|
    | elements read | ${doc.elements} |
    | FLines | ${doc.lines.size}: ${fills.size} fills, ${strokes.size} strokes |
    | colours | ${doc.lines.map { it.color }.distinct().size} |
    | fill area, view-box units² | ${areas.first().f0} to ${areas.last().f0}, median ${areas[areas.size / 2].f0} |
    | stroke widths | ${strokes.mapNotNull { it.thickness }.distinct().sorted().joinToString(", ") { it.f2 }} |
    | fills that change under even-odd | $differ of ${fills.size} |
    """.trimIndent(),
    width = 520,
)
```

Most fills are small: half of them are under about 17 × 17 view-box units, while the largest, the
head itself, covers a third of the picture. That spread is what the first painting gets wrong.

A paint region is read even-odd: a point is inside when it is inside an odd number of the FLine's
rings. The SVG's fills are non-zero. For this tiger the two agree on every fill, as the last row
counts, so nothing is lost by handing its FLines to the canvas unchanged. For another drawing, run
the same count first.

## Six ways to interpret it

Each canvas below takes the tiger's FLines in order and hands them to a different part of the
paint system. All of them are slow enough that the box paints in
`runInterruptible(Dispatchers.Default)` inside `go { }`, off Field's main loop, and shows the
canvases when they are done.

The first three are oil:

- **A broad brush, wet into wet.** Every fill is worked with `Handling.Broad` and a brush 40 units
  wide on an oil ground, whatever the size of the shape, and nothing dries, so each layer drags the
  one under it along.
- **A painting knife.** Each shape's own edge becomes the path of a knife loaded with its colour.
  A long outline is cut into short passes, since a knife stroke takes at most 2000 points.
- **Stippled.** Every fill is covered with separate dabs of an upright brush, bigger in bigger
  shapes.

```diffstatic_runner {data-lang=kotlin screenshot="assets/chain_tiger_paint/gallery_oil.png" scale="1.5" wait="5" capture="box" open-outputs="true" padding="18" timeout="600" wait-output="true" wait-output-channel="styles" wait-output-contains="stippled" requires="paint" name="chain-tiger-paint-gallery-oil" embed=true alt="Three interpretations of the tiger side by side. Left, on a warm brown oil ground: broad smeared blocks of orange, black, white and pink brushstrokes, the stripes and mouth only roughly placed and blurred into each other. Middle, on the same ground: ragged slabs and rings of knifed paint following the edges of the shapes, black arcs over the brow, an orange and red ring round the muzzle, a pink loop for the mouth and grey flakes for the fur. Right, on white: the head built from thousands of small dots, golden yellow and orange on top with black dot clusters for the stripes, a pink patch of dots for the mouth and scattered grey and black dots for the fur below."}
:::static
import afield.graphics.nile.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

val doc = GeziraSvg.parse(GeziraSvg.tiger())
val v = doc.viewBox
val view = Rect(v[0], v[1], v[2], v[3])
fun area(l: FLine) = abs(l.area2D().d)

// One huge brush for everything, on an oil ground, all wet into wet.
fun broad() = PaintCanvas(420, ground = PaintGround.Oil, view = view, sizeMm = 400.0, seed = 7).apply {
    for (l in doc.lines) if (l.filled) work(l, l.color, Handling.Broad, width = 40.0, coverage = 0.8, stiff = 0.3)
}

// Every shape laid with a painting knife dragged round its own edge.
fun knifed() = PaintCanvas(420, ground = PaintGround.Oil, view = view, sizeMm = 400.0, seed = 8).apply {
    val k = knife(40.0)
    for (l in doc.lines) if (l.filled && area(l) > 300) {
        k.load(l.color, amount = 1.0)
        // a long outline is cut into knife passes of at most 40 points
        for (piece in l.pieces()) for (run in piece.sampleByDistance(6f).chunked(40)) {
            if (run.size < 2) continue
            val pass = FLine().moveTo(run[0].x.d, run[0].y.d)
            for (p in run.drop(1)) pass.lineTo(p.x.d, p.y.d)
            knife(pass, k, pressure = 0.4..0.7)
        }
    }
}

// Dabs of an upright brush, bigger in bigger shapes.
fun dotted() = PaintCanvas(420, view = view, seed = 9).apply {
    for (l in doc.lines) if (l.filled)
        stipple(l, l.color, size = (kotlin.math.sqrt(area(l)) / 12).coerceIn(3.0, 12.0), coverage = 0.8)
}

go {
    val canvases = runInterruptible(Dispatchers.Default) { [broad(), knifed(), dotted()] }
    display("main", pngRow(canvases, relief = 0.8))
    markdown("styles", "broad brush, wet into wet · painting knife · stippled", width = 520)
}
```

The next three leave oil paint behind or attack it afterwards:

- **Searching outlines.** No fills at all: `outline` draws each shape's edge in its own colour the
  way a hand would, lifting, overlapping and restarting, with a thin rigger.
- **Watercolour floods.** On paper, `wet(region, microns, paint)` floods each shape with water
  carrying pigments mixed to its colour. The shapes touch while they are wet, so the black stripes
  bleed into the orange and darken the whole head.
- **Blown.** The tiger is painted close to the original, then a straw is blown along every
  whisker and outline with `blow`, which pushes the wet paint aside into frayed tracks.

```diffstatic_runner {data-lang=kotlin screenshot="assets/chain_tiger_paint/gallery_mixed.png" scale="1.5" wait="5" capture="box" open-outputs="true" padding="18" timeout="600" wait-output="true" wait-output-channel="styles" wait-output-contains="whiskers" requires="paint" name="chain-tiger-paint-gallery-mixed" embed=true alt="Three more interpretations of the tiger. Left, on white: a line drawing in thin broken strokes, each shape's edge in its own colour, orange and red round the muzzle and brow, black dashes for the stripes, green eyes and a pink outlined mouth, with grey fur lines below. Middle, on cream paper: a watercolour where the head has run together into a dark umber and amber wash with black stripes bleeding through, a deep violet mouth, crisp black stripes on the white lower face, and pale grey fur strokes. Right, on white: an oil tiger close to the original but frayed and torn along every whisker and outline, the paint pushed into ragged tracks and speckled edges, the white fur grey and broken."}
:::static
import afield.graphics.nile.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

val doc = GeziraSvg.parse(GeziraSvg.tiger())
val v = doc.viewBox
val view = Rect(v[0], v[1], v[2], v[3])
fun area(l: FLine) = abs(l.area2D().d)

// No fills at all: each shape's edge drawn by a searching hand, in its own colour.
fun outlined() = PaintCanvas(420, view = view, seed = 10).apply {
    val pen = brush(BrushKind.Rigger, 5.0)
    for (l in doc.lines) if (l.filled && area(l) > 60) {
        pen.load(l.color)
        outline(l, pen, OutlineCharacter.Searching)
    }
}

// Each shape flooded with water carrying pigments mixed to its colour; neighbours bleed.
fun water(c: Vec4): WaterPaint {
    val r = c.x.d
    val g = c.y.d
    val b = c.z.d
    val dark = 1 - maxOf(r, g, b)
    return Watercolors.sumi * (6.0 * dark * dark) +
        Watercolors.burntSienna * (3.0 * (r - b).coerceAtLeast(0.0) * (1 - dark)) +
        Watercolors.cadmiumYellow * (2.0 * (g - b).coerceAtLeast(0.0)) +
        Watercolors.quinacridoneRose * (2.5 * (r - g - 0.3).coerceAtLeast(0.0)) +
        Watercolors.viridian * (2.0 * (g - r).coerceAtLeast(0.0))
}
fun washed() = PaintCanvas.paper(420, aspect = 1.0, sizeMm = 300.0, seed = 11, view = view).apply {
    for (l in doc.lines) if (l.filled && minOf(l.color.x, l.color.y, l.color.z) < 0.9f) {
        wet(l, microns = 120.0, paint = water(l.color))
    }
    dry()
}

// Painted quickly, then blown with a straw along every whisker and outline.
fun blown() = PaintCanvas(420, view = view, seed = 12).apply {
    for (l in doc.lines) if (l.filled) {
        val w = (kotlin.math.sqrt(area(l)) / 6).coerceIn(2.0, 24.0)
        work(
            l,
            l.color,
            if (area(l) < 2000) Handling.Detail else Handling.Body,
            width = w,
            clip = true,
            hiding = 0.95,
            stiff = 0.2,
        )
    }
    for (l in doc.lines) if (l.stroked) blow(l, radius = 10.0, pressure = 1.0, tendril = 12.0)
}

go {
    val canvases = runInterruptible(Dispatchers.Default) { [outlined(), washed(), blown()] }
    display("main", pngRow(canvases, relief = 0.8))
    markdown("styles", "searching outlines · watercolour floods · blown along the whiskers", width = 520)
}
```

## A looser hand

`outline` takes an `amount` that scales every irregularity of its character, from 0, a clean
tracing, upwards. This video sweeps it from 0 to 6 in tenths over the same shapes, one drawing a frame:

```diffstatic_runner {data-lang=kotlin video="assets/chain_tiger_paint/looser.mp4" capture="box" open-outputs="true" wait-output="true" wait-output-kind="display" execution="launch" duration="6.5" fps="10" timeout="600" requires="paint" embed=true name="chain-tiger-paint-looser" alt="The tiger as a coloured line drawing, redrawn several times. At first short broken strokes follow each stripe, the eyes, the muzzle and the mouth closely. Frame by frame the strokes overshoot their corners, double back and wander off the shapes, until the last drawing is a loose scribble of coloured strokes, orange flaring off the back of the head, in which the tiger is still recognisable."}
:::static
import afield.graphics.nile.*

val doc = GeziraSvg.parse(GeziraSvg.tiger())
val v = doc.viewBox
val shapes = doc.lines.filter { it.filled && abs(it.area2D().d) > 60 }

// The same searching hand, from a clean tracing to a loose one, one canvas per frame.
fun outlined(amount: Double) = PaintCanvas(420, view = Rect(v[0], v[1], v[2], v[3]), seed = 10).apply {
    val pen = brush(BrushKind.Rigger, 5.0)
    for (l in shapes) {
        pen.load(l.color)
        outline(l, pen, OutlineCharacter.Searching, amount = amount)
    }
}

// 0 to 6 in steps of a tenth, one drawing a frame
val amounts = (0..60).map { it * 0.1 }
var at = 0
loop {
    if (at < amounts.size) {
        display("main", outlined(amounts[at]).png())
        display("amount", "amount = ${amounts[at].f1}")
        at++
    } else end()
}
```

## Staying close to the original

To paint the tiger as drawn, with every shape in its place and its own colour, takes two choices
the gallery mostly ignores: a brush sized to each shape, and dry paint under each layer. Here they
are one at a time, with the kernels' picture above as the yardstick. Every FLine is painted in document order. A fill goes to `work(region, color, handling)`, which
plans and paints strokes that cover the region the way a painter blocks in an area. A stroke gets a
round brush as wide as its `thickness`, converted from view-box units to the canvas's 1000-unit
width, and is dragged along the line with `stroke`.

The three canvases differ in two things:

- **One brush for every fill.** `work` with `Handling.Body` and its own brush, whatever the size of
  the region.
- **A brush sized to each fill.** The brush width follows the square root of the region's area,
  between 2 and 24 units; regions under 2000 units² get `Handling.Detail`, and `clip = true` keeps
  the bristles inside the region.
- **Sized, and dried after each FLine.** The same, with `dry()` after every path, so each layer
  goes on over dry paint instead of into wet paint.

As in the gallery, the painting runs off the main loop. The table compares each painting with the kernels' picture above, as the
mean difference per colour channel:

```diffstatic_runner {data-lang=kotlin screenshot="assets/chain_tiger_paint/strategies.png" scale="1.5" wait="5" capture="box" open-outputs="true" padding="18" timeout="420" wait-output="true" wait-output-channel="table" wait-output-contains="strategy" requires="paint" name="chain-tiger-paint-strategies" embed=true alt="Three oil paintings of the tiger side by side on white. Left: the head is a mess of broad horizontal brushstrokes, the black stripes smeared into wide dark patches, the white fur turned grey, and the eyes and mouth blurred, while the thin whiskers are crisp. Middle: the shapes are now in the right places, the stripes, eyes, fangs and mouth recognisable, but the black stripes on the lower face are streaked grey where the white fur paint was worked into them. Right: a clean painting with solid black stripes, white fur, green eyes, cream fangs and a pink mouth, close to the vector original, with brush texture inside each shape. Below, a table of the three strategies with the seconds each took and its mean difference from the kernels' picture, which falls from left to right."}
:::static
import afield.graphics.nile.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

val doc = GeziraSvg.parse(GeziraSvg.tiger())
val v = doc.viewBox
val view = Rect(v[0], v[1], v[2], v[3])
val N = 420

// The tiger by the Gezira kernels, the reference each painting is compared against.
val ref = GeziraSvg.render(doc, N, N).canvas.rgba
fun difference(c: PaintCanvas): Double {
    val px = c.render()
    var d = 0.0
    for (i in px.indices) {
        val a = ref[4 * i + 3].coerceIn(0f, 1f)
        for (k in 0..2) {
            val r = (ref[4 * i + k] + (1 - a)).coerceIn(0f, 1f) * 255
            d += abs(r - (px[i] shr (16 - 8 * k) and 255))
        }
    }
    return d / (3 * px.size)
}

// Paint every FLine in document order: a fill is worked as a region, a stroke is dragged.
fun paint(sized: Boolean, dryEach: Boolean): Pair<PaintCanvas, Double> {
    val c = PaintCanvas(N, aspect = 1.0, view = view, seed = 3)
    val t0 = System.nanoTime()
    for (l in doc.lines) {
        if (l.filled) {
            val area = abs(l.area2D().d)
            if (sized) {
                val w = (sqrt(area) / 6).coerceIn(2.0, 24.0)
                c.work(l, l.color, if (area < 2000) Handling.Detail else Handling.Body, width = w, clip = true)
            } else {
                c.work(l, l.color, Handling.Body)
            }
        } else {
            val b = c.brush(BrushKind.Round, width = ((l.thickness ?: 1f).d * 1000 / v[2]).coerceAtLeast(1.5))
            b.load(l.color)
            c.stroke(l, b)
        }
        if (dryEach) c.dry()
    }
    return c to (System.nanoTime() - t0) / 1e9
}

go {
    val runs = runInterruptible(Dispatchers.Default) {
        [
            paint(sized = false, dryEach = false),
            paint(sized = true, dryEach = false),
            paint(sized = true, dryEach = true),
        ]
    }
    display("main", pngRow(runs.map { it.first }))
    val names = ["one brush for every fill", "brush sized to each fill", "sized, and dried after each FLine"]
    markdown(
        "table",
        "| strategy | seconds | mean difference from the kernels (0–255) |\n|---|---|---|\n" +
            runs.indices.joinToString("\n") {
                "| ${names[it]} | %.1f | %.1f |".format(runs[it].second, difference(runs[it].first))
            },
        width = 560,
    )
}
```

With one brush, a region narrower than the brush gets strokes as wide as the brush, and a stripe
20 units wide is painted with a brush meant for the whole head. Sizing the brush puts each shape
back where it belongs, at about twice the cost: many more, smaller strokes.

The middle painting shows the other half of the problem. Oil paint stays wet, and `work` mixes into
whatever wet paint the brush meets, which is what makes the meadow on
[Drawn Strokes Become Paint](chain_drawn_paint.md) look painted. Here the SVG paints white fur over
black stripes and black over orange, and wet into wet those mix to grey and brown. Drying after
each FLine gives each layer a dry surface, as an SVG renderer's compositing does, and costs some
more time again. The table is one measurement among the styles above: the closest copy is also the
least like paint.

## In pencil

The same FLines make a drawing. This box hatches each black fill with a soft 6B pencil and draws
every stroke, the whiskers and outlines, with a hard 2H. Everything else is left as paper. The
canvas is 220 mm wide, so a pencil point is about a pixel.

A pencil wears down as it draws, and its line widens with it. Over a few hundred regions that
matters: on the left the 6B is never sharpened, and by the lower stripes its hatching has become
rows of fat dots. On the right it is sharpened before every region with `pencil.sharpen()`:

```diffstatic_runner {data-lang=kotlin screenshot="assets/chain_tiger_paint/pencil.png" wait="5" capture="box" open-outputs="true" padding="18" timeout="240" wait-output="true" wait-output-channel="worn" wait-output-contains="longest" requires="paint" name="chain-tiger-paint-pencil" embed=true alt="Two pencil drawings of the tiger side by side on white paper. Both have the whiskers, fur and outlines in thin light grey lines and the black stripes hatched in diagonal grey strokes. On the left the hatching in most stripes has thickened into rows of short fat dashes and dots. On the right the hatching is finer, with separate thin diagonal lines in the upper stripes; only the large mouth and the last stripes thicken. Below, a line giving the longest run of the 6B between sharpenings for each drawing, much longer on the left."}
:::static
import afield.graphics.nile.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

val doc = GeziraSvg.parse(GeziraSvg.tiger())
val v = doc.viewBox

// How dark a colour is, 0 white to 1 black.
fun dark(c: Vec4) = 1 - (0.3 * c.x + 0.59 * c.y + 0.11 * c.z)

// Hatch the black fills with a soft pencil and draw every stroke with a hard one.
fun draw(sharpenEach: Boolean): Pair<PaintCanvas, Double> {
    val c = PaintCanvas(420, aspect = 1.0, view = Rect(v[0], v[1], v[2], v[3]), sizeMm = 220.0, seed = 5)
    val soft = c.pencil("6B")
    val hard = c.pencil("2H")
    var worn = 0.0
    for (l in doc.lines) {
        if (l.filled && dark(l.color) > 0.8) {
            if (sharpenEach) soft.sharpen()
            c.hatch(l, soft, angle = 0.9, spacing = 7.0, length = 25.0, pressure = 0.8)
            worn = maxOf(worn, soft.worn)
        } else if (l.stroked) {
            c.draw(l, hard, pressure = 0.5)
        }
    }
    return c to worn
}

go {
    val runs = runInterruptible(Dispatchers.Default) { [draw(sharpenEach = false), draw(sharpenEach = true)] }
    display("main", pngRow(runs.map { it.first }))
    markdown(
        "worn",
        "longest run of the 6B between sharpenings: %.0f mm (left), %.0f mm (right)"
            .format(runs[0].second, runs[1].second),
        width = 560,
    )
}
```

Even sharpened each time, one large region wears the point down within that region: the mouth is
hatched with one pencil from start to finish. Cut a large region into smaller ones, or sharpen
between bands of its hatching, to keep it fine.

## Where each piece is documented

* [The SVG tiger](afield_four_graphics.md#the-svg-tiger) for `GeziraSvg` and `GeziraFLine`: what
  the reader reads and skips, and the tiger beside Skia's own SVG renderer.
* [The tiger on the GPU](afield_four_graphics.md#gezira-gpu-tiger) draws the same FLines every
  frame in compute shaders.
* [Paint and pencil from FLines](paint_canvas.md) for `PaintCanvas`, `work`, brushes, pencils and
  building the native library the paint figures need.

## Where to go next

* [Drawn Strokes Become Paint](chain_drawn_paint.md) paints strokes you draw yourself, with pen
  pressure, and shows wet paint mixing on purpose.
* [Paint settings, swept](paint_sweeps.md) varies the brush and paint controls one at a time.
* [Watercolour and ink](watercolor.md) paints on the same canvas with water instead of oil.

## API reference

- Reading SVG and painting its paths: [`GeziraSvg`](reference/graphics.md#gezirasvg-kt-gezirasvg), [`GeziraSvg.parse`](reference/graphics.md#gezirasvg-kt--gezirasvg--parse), [`GeziraSvg.render`](reference/graphics.md#gezirasvg-kt--gezirasvg--render), [`PaintCanvas`](reference/graphics.md#paintcanvas-kt-paintcanvas), [`PaintCanvas.work`](reference/graphics.md#paintcanvas-kt--paintcanvas--work).
