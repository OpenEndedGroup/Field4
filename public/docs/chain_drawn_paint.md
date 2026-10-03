# Drawn Strokes Become Paint

A pen stroke on the drawing layer is a list of points and, from a pressure-sensitive pen, a pressure
at each point. `notation` reads both back, and `PaintCanvas` paints along the points with a brush
that presses as hard as the pen did. The result is an oil-paint version of what you drew, with the
brush swelling where you pressed and thinning where you lifted.

The strokes on this page are drawn by code, with a pressure written for each point, so that the
page can be rebuilt. Strokes you draw by hand with a tablet pen are read the same way.

## Three strokes, with pressure

`notation { stroke(...) }` writes a stroke into the drawing. Its `pressure` argument gives the pen
pressure, 0 to 1, as a function of how far along the stroke the pen is. This box draws three:
a wave named `S` that is pressed hardest in the middle, a `sweep` that starts hard and lifts off,
and a `loop` that starts light and bears down as it curls in to its end. It then reads them back with `notation.strokes`:

```diffstatic_runner {data-lang=kotlin screenshot="assets/chain_drawn_paint/strokes.png" wait="5" capture="box" open-outputs="true" padding="18" timeout="90" name="chain-drawn-paint-strokes" embed=true alt="Three curves of pen pressure against distance along the stroke. The S rises from light to a peak in the middle and falls back; the sweep falls in a straight line from hard to light; the loop rises slowly at first, then steeply, to full pressure at its end. A table below lists each stroke's point count and its lightest and heaviest pressure."}
notation {
    stroke(FLine().moveTo(80, 300).cubicTo(200, 60, 330, 420, 420, 140), name = "S",
        pressure = { 0.15 + 0.85 * sin(PI * it) })
    stroke(FLine().moveTo(470, 110).cubicTo(560, 300, 700, 330, 820, 180), name = "sweep",
        pressure = { 1.0 - 0.85 * it })
    stroke(FLine().moveTo(880, 330).cubicTo(1060, 300, 1020, 60, 900, 120).cubicTo(820, 160, 880, 260, 960, 250),
        name = "loop", pressure = { 0.15 + 0.85 * it * it })
}
val strokes = notation.strokes

:::fold plot each stroke's pressure, and the table
showSignals(*strokes.map { it.label!! to it.pressuresAlong(50) }.toTypedArray(),
    x = (0 until 50).map { it / 49.0 }, title = "pen pressure along each stroke",
    xTitle = "fraction of the way along", yTitle = "pressure", channel = "pressure")
markdown("table", "| stroke | points | lightest | heaviest |\n|---|---|---|---|\n" + strokes.joinToString("\n") {
    "| ${it.label} | ${it.points.size} | %.2f | %.2f |".format(it.pressures.min(), it.pressures.max())
}, width = 380)
notation.clear()   // take the strokes down again so they do not lie over the plot
:::endfold
```

`pressures` is the list Excalidraw keeps, one value per point. A pen records points closer together
where it moves slowly, so that list is not evenly spaced along the stroke. `pressuresAlong(n)` gives
the pressure at `n` points evenly spaced by distance, which is what the brush wants. A stroke drawn
with a mouse has no pressure: both lists are empty.

## Painted

`PaintCanvas(view = ...)` stretches a rectangle of drawing coordinates over the whole canvas, so the
strokes need no conversion: `toFLine()` gives each one as an FLine in drawing coordinates, and both
the drawing and the canvas have y growing downwards. A brush's `swell` is a list of pressure
multipliers along the stroke, so with the base `pressure` at 1, `swell = pressuresAlong(64)` makes
the brush press as the pen did.

The top row is painted that way. The bottom row is the same three strokes, shifted down, painted at
one even pressure:

```diffstatic_runner {data-lang=kotlin screenshot="assets/chain_drawn_paint/painted.png" wait="15" capture="box" open-outputs="true" padding="18" timeout="120" requires="paint" name="chain-drawn-paint-painted" embed=true alt="Two rows of three oil-paint brushstrokes on white: a dark blue wave, a red-orange arc and a green spiral. In the top row the wave narrows at both ends and is broadest in the middle, the arc starts broad and narrows to a point, and the spiral starts as a narrow line on the outside and widens as it curls in to its end. In the bottom row the same three shapes are bands of nearly one width, narrowing only where the brush lands and lifts."}
:::static
import afield.paint.*

notation {
    stroke(FLine().moveTo(80, 300).cubicTo(200, 60, 330, 420, 420, 140), name = "S",
        pressure = { 0.15 + 0.85 * sin(PI * it) })
    stroke(FLine().moveTo(470, 110).cubicTo(560, 300, 700, 330, 820, 180), name = "sweep",
        pressure = { 1.0 - 0.85 * it })
    stroke(FLine().moveTo(880, 330).cubicTo(1060, 300, 1020, 60, 900, 120).cubicTo(820, 160, 880, 260, 960, 250),
        name = "loop", pressure = { 0.15 + 0.85 * it * it })
}
val colours = listOf(rgb(0.12, 0.2, 0.55), rgb(0.85, 0.3, 0.1), rgb(0.15, 0.5, 0.25))

val canvas = PaintCanvas(900, aspect = 1.6, view = Rect(40.0, 40.0, 1040.0, 650.0))
val brush = canvas.brush(BrushKind.Round, width = 40.0)
notation.strokes.forEachIndexed { i, s ->
    brush.load(colours[i], wipe = 1.0)
    canvas.stroke(s.toFLine(), brush, pressure = 1.0..1.0, swell = s.pressuresAlong(64))
}

:::fold the same strokes 320 units lower, at one pressure
notation.strokes.forEachIndexed { i, s ->
    val lower = FLine()
    s.points.forEachIndexed { k, p -> if (k == 0) lower.moveTo(p.x, p.y + 320) else lower.lineTo(p.x, p.y + 320) }
    brush.load(colours[i], wipe = 1.0)
    canvas.stroke(lower, brush, pressure = 0.6..0.6)
}
notation.clear()
:::endfold
display("main", canvas.png())
```

The difference is plainest in the loop, which opens as a narrow line where the pen was light and
widens as it curls in, and in the S, whose ends are narrower than its middle. Even at the lightest
pressure the brush lays a band about half its full width: pressure widens a round brush, but it does
not shrink it to a point unless you give it one with `point = 1.0`.

## In pencil

`canvas.draw(line, pencil, pressure = ...)` takes the same kind of list, a pressure at evenly spaced
stations along the line. Pressed lightly, a pencil leaves a pale grey line; pressed hard, a dark
one. The same three strokes in a 6B pencil, on a 20 cm sheet:

```diffstatic_runner {data-lang=kotlin screenshot="assets/chain_drawn_paint/pencil.png" wait="10" capture="box" open-outputs="true" padding="18" timeout="120" requires="paint" name="chain-drawn-paint-pencil" embed=true alt="The same three strokes drawn in grey pencil on white paper. The wave is pale at both ends and darkest in its middle trough; the arc starts dark and fades to a pale grey; the spiral is pale on its outer curve and darkens where it curls in to its end."}
:::static
import afield.paint.*

notation {
    stroke(FLine().moveTo(80, 300).cubicTo(200, 60, 330, 420, 420, 140), name = "S",
        pressure = { 0.15 + 0.85 * sin(PI * it) })
    stroke(FLine().moveTo(470, 110).cubicTo(560, 300, 700, 330, 820, 180), name = "sweep",
        pressure = { 1.0 - 0.85 * it })
    stroke(FLine().moveTo(880, 330).cubicTo(1060, 300, 1020, 60, 900, 120).cubicTo(820, 160, 880, 260, 960, 250),
        name = "loop", pressure = { 0.15 + 0.85 * it * it })
}

val canvas = PaintCanvas(900, aspect = 2.5, view = Rect(40.0, 40.0, 1040.0, 416.0), sizeMm = 200.0)
val pencil = canvas.pencil("6B")
for (s in notation.strokes) canvas.draw(s.toFLine(), pencil, pressure = s.pressuresAlong(32))
notation.clear()
display("main", canvas.png())
```

## Drawing your own

Open drawn_paint from the welcome screen's starting points and run `strokes`, then `paint` and
`pencil`. To paint your own, press `alt-d`, choose the pen (`p`) and draw. A tablet pen records
pressure; a mouse does not, and for a mouse stroke `pressuresAlong` is empty, so the brush paints at
its base pressure. Replace the `notation { ... }` block in `strokes` with nothing, and `paint`
paints every stroke in the drawing. Its `view` is the rectangle of drawing coordinates that becomes
the canvas: make it cover what you drew, or work it out from the strokes' own bounds:

```diffstatic {data-lang=kotlin}
val drawn = notation.strokes.filter { it.handDrawn }
val view = drawn.map { it.bounds }.reduce { a, b -> a.union(b) }.let { Rect(it.x - 40, it.y - 40, it.w + 80, it.h + 80) }
val canvas = PaintCanvas(900, aspect = view.w / view.h, view = view)
```

## Where each piece is documented

* [Drawing and Notation](drawing_and_notation.md) for the drawing layer and `notation`.
* [Paint and pencil from FLines](paint_canvas.md) for `PaintCanvas`, brushes, paint and pencils, and
  building the native library the paint figures need.
* [A Drawn Morph, Heard](chain_drawn_morph.md) reads strokes from the same drawing as shapes to morph
  between.
