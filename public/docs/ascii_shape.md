# Shape-aware ASCII

Most ASCII art picks each character by brightness alone: a dark cell gets a space, a bright one
`@`, and everything between comes from a ramp of characters sorted by how much ink they hold. The
picture's tones come through but its edges do not, because a ramp cannot tell a cell lit along its
top from one lit along its bottom. `ShapeAscii` picks characters by *where* the light falls in
each cell as well, following Alex Harri's essay
[ASCII characters are not pixels](https://alexharri.com/blog/ascii-rendering), so a circle's rim
comes out as `_`, `/`, `'` and `[` running along the curve.

This box draws a ring, a triangle and a disc as FLines into an `Img`, then turns the same image
into 44 columns of characters twice, once by brightness and once by shape:

```diffstatic_runner {data-lang=kotlin screenshot="assets/ascii_shape/shapes.png" alt="two panels of white characters on dark gray. Left, brightness: the ring is a dotted outline of colons, semicolons and the letter c, the triangle a blur of C and 1, the disc a block of N. Right, shape: the ring's rim is drawn with underscores on top, quote marks and slashes on the curve, and the triangle's sloping sides with backslashes and the letter k" wait="3" timeout="240" capture="box" open-outputs="true" padding="20" name="ascii-shape-shapes" embed=true}
// Three outlines and a filled disc, drawn white on black.
fun ring(cx: Double, cy: Double, r: Double, sides: Int, turn: Double = 0.0) = fline {
    for (i in 0..sides) {
        val a = turn + 2 * PI * i / sides
        if (i == 0) moveTo(cx + r * cos(a), cy + r * sin(a)) else lineTo(cx + r * cos(a), cy + r * sin(a))
    }
    thickness = 6.0
}
val disc = ring(360.0, 100.0, 55.0, 64).apply { filled = true }
val drawing = Img.zeros(440, 200, 3).drawFLines(
    [ring(90.0, 100.0, 70.0, 64), ring(210.0, 105.0, 65.0, 3, -PI / 2), disc],
    color = Vec4(1f, 1f, 1f, 1f),
)
val ascii = ShapeAscii()
showAscii(
    "brightness" to ascii.convert(drawing, 44, shape = false),
    "shape" to ascii.convert(drawing, 44),
)
```

`ShapeAscii()` rasterises the printable ASCII characters in a monospaced font once; keep it in a
`val` and call `convert(img, cols)` as often as you like. `convert` reads bright pixels as ink,
the right way round for light characters on a dark panel; pass `invert = true` for a dark drawing
on white. `shape = false` gives the brightness ramp. `showAscii` draws each result in the font it
was matched against, side by side. The result is an `AsciiFrame`: `frame.text()` gives it as
lines of text, and inside a `charGrid` block `drawAscii(frame)` writes it into the grid (below).

## Six circles per cell

Each cell is summarised by six numbers: the mean brightness inside six circles, two columns of
three, with the right column set a little higher than the left so that together they cover the
cell's height. Every character is drawn into a cell of the same size and summarised the same way,
so choosing a character is finding the one whose six numbers are closest to the cell's. Here are
five characters as the matcher sees them, with their circles over them:

```diffstatic_runner {data-lang=kotlin screenshot="assets/ascii_shape/circles.png" alt="five tall black cells, each holding one white character enlarged ten times: T, L, a forward slash, o and @. Over each, a gray outline of the cell, six orange circles in two staggered columns inside it, and six blue circles further out, beyond the cell's corners and sides" wait="3" timeout="240" capture="box" open-outputs="true" padding="20" name="ascii-shape-circles" embed=true}
// Five glyphs as the matcher sees them, 10x up: the cell in gray, its six circles in orange,
// and the six just outside it in blue.
val ascii = ShapeAscii()
val k = 10.0
fun circle(cx: Double, cy: Double, r: Double) = fline {
    for (i in 0..48) {
        val a = 2 * PI * i / 48
        if (i == 0) moveTo(cx + r * cos(a), cy + r * sin(a)) else lineTo(cx + r * cos(a), cy + r * sin(a))
    }
}
val circles = ascii.samplingCircles()
val panels = "TL/o@".map { ch ->
    val i = ascii.glyphs.indexOf(ch)
    // pad the cell so the outer circles fit, then draw the circles over it
    val pad = ascii.cellWidth
    val cell = ascii.glyphImg(i).pad(pad, Border.ZERO)
    val inner = circles.take(6).map { (x, y, r) -> circle(x + pad, y + pad, r).apply { color = Vec4(1f, 0.55f, 0.1f, 1f) } }
    val outer = circles.drop(6).map { (x, y, r) -> circle(x + pad, y + pad, r).apply { color = Vec4(0.3f, 0.6f, 1f, 1f) } }
    val w = ascii.cellWidth.toDouble(); val h = ascii.cellHeight.toDouble()
    val box = fline { moveTo(pad, pad); lineTo(pad + w, pad); lineTo(pad + w, pad + h); lineTo(pad, pad + h); lineTo(pad, pad) }
    box.color = Vec4(0.5f, 0.5f, 0.5f, 1f)
    "'$ch'" to cell.drawFLines(inner + outer + box, scale = k, thickness = 2.0)
}
showImgGrid(*panels.toTypedArray(), columns = 5, cellSide = 200)
```

`T` fills its top two circles and leaves the bottom ones dark; `L` fills the left column and the
bottom right; `/` fills the circles along its diagonal; `o` the middle and bottom; `@` nearly
all six. Those differences are what a brightness ramp throws away. `glyphVector(i)` gives a
character's six numbers, scaled so the largest over the whole charset is 1, and
`samplingCircles()` the circles themselves. The blue circles belong to directional contrast,
further down.

Shading works too. A sphere lit from the upper left, shaded by hand into an `Img`:

```diffstatic_runner {data-lang=kotlin screenshot="assets/ascii_shape/sphere.png" alt="two panels of a sphere drawn in characters. Left, brightness: a disc of mixed letters and digits, densest at the upper left, with a ragged rim. Right, shape: the lit upper left is a block of @ and B, fading through 8, E, S, Z and I towards the lower right, and the rim is traced with underscores, g, i, d and quote marks" wait="3" timeout="240" capture="box" open-outputs="true" padding="20" name="ascii-shape-sphere" embed=true}
// A lit sphere on black, shaded by hand: brightness is the cosine between the surface normal and a light.
val scene = Img.generate(300, 240) { x, y ->
    val nx = (x - 150) / 100.0
    val ny = (y - 120) / 100.0
    val r2 = nx * nx + ny * ny
    if (r2 < 1) {
        val nz = sqrt(1 - r2)
        (-0.5 * nx - 0.6 * ny + 0.62 * nz).coerceAtLeast(0.0).toFloat()
    } else 0f
}
val ascii = ShapeAscii()
showAscii(
    "brightness" to ascii.convert(scene, 40, shape = false),
    "shape, contrast 2" to ascii.convert(scene, 40, contrast = 2.0),
    cellSide = 420,
)
```

The brightness version picks a different letter in almost every cell, because many characters hold
about the same amount of ink. The shape version settles on runs of the same character where the
shading is even, and its rim follows the outline.

!!! note
    The method suits light marks on a dark ground: lines, outlines and lit objects. A large even
    gray area matches whichever character is most even across its six circles, so a gray
    photograph or a screenshot of a window fills with rows of one letter (`S` or `Z`). Use the
    brightness ramp for those, or take edges first.

## Sharpening edges

A cell that an edge only grazes still has some light in every circle, and the nearest character
is then a blurry, all-over one. Two steps push the cell's numbers apart before the search.

**Contrast** (`contrast = 3.0`) divides each of the cell's six numbers by the largest of them,
raises it to that power, and multiplies back: the brightest circle stays where it is and the dim
ones fall towards zero, so the cell reads as "light in this corner" rather than "a little light
everywhere". **Directional contrast** (`directional = 3.0`) does the same per circle, against the
blue circle outside the cell in the same direction: where the neighbouring cell is brighter, the
inner circle is darkened, which stops a character from bleeding towards an edge that belongs to
the next cell. Both are exponents, and 1 turns a step off. The three rows below are the same
drawing with neither, contrast alone, and both:

```diffstatic_runner {data-lang=kotlin screenshot="assets/ascii_shape/contrast.png" alt="the ring, triangle and disc three times, one above another. In the first, the triangle's right side is a column of percent signs and letter Q; in the second, contrast 3, it is a clean run of backslashes and the ring's rim has fewer heavy letters; in the third, contrast 3 with directional 3, the disc's outline is trimmed and its top row starts with an underscore" wait="3" timeout="240" capture="box" open-outputs="true" padding="20" name="ascii-shape-contrast" embed=true}
// Three outlines and a filled disc, drawn white on black.
fun ring(cx: Double, cy: Double, r: Double, sides: Int, turn: Double = 0.0) = fline {
    for (i in 0..sides) {
        val a = turn + 2 * PI * i / sides
        if (i == 0) moveTo(cx + r * cos(a), cy + r * sin(a)) else lineTo(cx + r * cos(a), cy + r * sin(a))
    }
    thickness = 6.0
}
val disc = ring(360.0, 100.0, 55.0, 64).apply { filled = true }
val drawing = Img.zeros(440, 200, 3).drawFLines(
    [ring(90.0, 100.0, 70.0, 64), ring(210.0, 105.0, 65.0, 3, -PI / 2), disc],
    color = Vec4(1f, 1f, 1f, 1f),
)
val ascii = ShapeAscii()
showAscii(
    "contrast 1" to ascii.convert(drawing, 44),
    "contrast 3" to ascii.convert(drawing, 44, contrast = 3.0),
    "contrast 3, directional 3" to ascii.convert(drawing, 44, contrast = 3.0, directional = 3.0),
    columns = 1, cellSide = 600,
)
```

Contrast does most of the work on lines: the triangle's sides turn into slashes. Directional
contrast matters most along the boundary of a filled shape, where it trims the disc's rim; on thin
lines it can thin a stroke so far that a character drops out (the ring's left side), so treat both
as dials and look.

## Live, in a char grid

`drawAscii` writes a frame into a [`charGrid`](agent_transcripts.md#madp_chargrid) panel, so anything you can draw into an
`Img` each tick becomes live text. This box spins a wireframe cube, projects its edges into an
FLine drawing every tick, and matches it by shape. Pass the charset to `charGrid` with
`glyphs = ascii.glyphs` so every character the matcher picks exists in the grid:

```diffstatic_runner {data-lang=kotlin screenshot="assets/ascii_shape/cube.png" alt="a char grid panel showing a wireframe cube turned at an angle, its twelve edges drawn in white characters: slashes and backslashes on the slanted edges, underscores and dashes on the near-horizontal ones, vertical bars and brackets on the upright ones" wait="6" timeout="240" capture="box" open-outputs="true" padding="20" name="ascii-shape-cube" embed=true}
// A wireframe cube turning about two axes, drawn into an Img each tick and matched by shape.
val ascii = ShapeAscii()
// Corner i has x, y, z = ±1 from its three bits; an edge joins corners one bit apart.
val corners = (0 until 8).map { i -> Triple(if (i and 1 == 0) -1.0 else 1.0, if (i and 2 == 0) -1.0 else 1.0, if (i and 4 == 0) -1.0 else 1.0) }
val edges = (0 until 8).flatMap { i -> [1, 2, 4].filter { i and it == 0 }.map { i to (i or it) } }

charGrid(cols = 60, rows = 26, glyphs = ascii.glyphs) {
    val a = frame * 0.03
    fun project(p: Triple<Double, Double, Double>): Pair<Double, Double> {
        val (px, py, pz) = p
        val x1 = px * cos(a) - pz * sin(a)
        val z1 = px * sin(a) + pz * cos(a)
        val y1 = py * cos(a * 0.7) - z1 * sin(a * 0.7)
        val z2 = py * sin(a * 0.7) + z1 * cos(a * 0.7)
        val s = 150.0 / (z2 + 4.0)
        return (240 + x1 * s) to (130 + y1 * s)
    }
    val lines = edges.map { (i, j) ->
        fline {
            moveTo(project(corners[i]).first, project(corners[i]).second)
            lineTo(project(corners[j]).first, project(corners[j]).second)
            thickness = 5.0
        }
    }
    val img = Img.zeros(480, 260, 3).drawFLines(lines, color = Vec4(1f, 1f, 1f, 1f))
    drawAscii(ascii.convert(img, COLS, contrast = 3.0, rows = ROWS))
}
```

`convert` remembers the character it chose for each cell pattern it has met (the six numbers
rounded to 32 levels), so a moving scene costs a table lookup for most cells after the first few
ticks. A 60 × 26 grid keeps up with `charGrid`'s 24 frames a second. Stop the box to stop the cube.

## Reference

| Call | What it does |
| --- | --- |
| `ShapeAscii(glyphs, fontSize, fontName)` | Rasterise a charset (default printable ASCII, 16 pt monospaced). |
| `convert(img, cols, shape, contrast, directional, invert, rows)` | Turn an `Img` into an `AsciiFrame`; `rows` defaults to the image's aspect at this font's cell shape. |
| `frame.text()`, `frame.char(x, y)` | The result as lines, or one character. |
| `frame.toImg()` | Draw the result in the matched font. |
| `showAscii("label" to frame, ...)` | Show frames side by side, captioned. |
| `drawAscii(frame)` | Inside `charGrid { }`: write the frame into the grid. |
| `glyphVector(i)`, `glyphCoverage(i)`, `samplingCircles()`, `glyphImg(i)` | What the matcher compares. |

The method, the staggered circles and both contrast steps are Alex Harri's; read
[his essay](https://alexharri.com/blog/ascii-rendering) for interactive versions of every step and
for the GPU implementation he uses for real-time 3-D scenes.
