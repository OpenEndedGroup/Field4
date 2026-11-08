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

```diffstatic_runner {data-lang=kotlin screenshot="assets/ascii_shape/shapes.png" alt="two panels of white characters on dark gray. Left, brightness: the ring is a dotted outline of colons, semicolons and the letter c, the triangle a blur of C and 1, the disc a block of N. Right, shape: the ring's rim is drawn with underscores on top, quote marks and slashes on the curve, and the triangle's sloping sides with p, y and Q in a diagonal run" wait="3" timeout="240" capture="box" open-outputs="true" padding="20" name="ascii-shape-shapes" embed=true}
// Three outlines and a filled disc, drawn white on black.
fun ring(cx: Double, cy: Double, r: Double, sides: Int, turn: Double = 0.0) = fline {
    for (i in 0..sides) {
        val a = turn + 2 * PI * i / sides
        if (i == 0) moveTo(cx + r * cos(a), cy + r * sin(a)) else lineTo(cx + r * cos(a), cy + r * sin(a))
    }
    thickness = 6.0
}
val disc = ring(360.0, 100.0, 55.0, 64).apply { filled = true }
val drawing =
    Img.zeros(440, 200, 3)
        .drawFLines(
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
val panels =
    "TL/o@"
        .map { ch ->
            val i = ascii.glyphs.indexOf(ch)
            // pad the cell so the outer circles fit, then draw the circles over it
            val pad = ascii.cellWidth
            val cell = ascii.glyphImg(i).pad(pad, Border.ZERO)
            val inner =
                circles.take(6).map { (x, y, r) ->
                    circle(x + pad, y + pad, r).apply { color = Vec4(1f, 0.55f, 0.1f, 1f) }
                }
            val outer =
                circles.drop(6).map { (x, y, r) ->
                    circle(x + pad, y + pad, r).apply { color = Vec4(0.3f, 0.6f, 1f, 1f) }
                }
            val w = ascii.cellWidth.toDouble()
            val h = ascii.cellHeight.toDouble()
            val box = fline {
                moveTo(pad, pad)
                lineTo(pad + w, pad)
                lineTo(pad + w, pad + h)
                lineTo(pad, pad + h)
                lineTo(pad, pad)
            }
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
val scene =
    Img.generate(300, 240) { x, y ->
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

```diffstatic_runner {data-lang=kotlin screenshot="assets/ascii_shape/contrast.png" alt="the ring, triangle and disc three times, one above another. In the first, the triangle's right side is a diagonal of the letter Q; in the second, contrast 3, it is a clean run of backslashes and the ring's rim has fewer heavy letters; in the third, contrast 3 with directional 3, the disc's outline is trimmed and its top row starts with an underscore" wait="3" timeout="240" capture="box" open-outputs="true" padding="20" name="ascii-shape-contrast" embed=true}
// Three outlines and a filled disc, drawn white on black.
fun ring(cx: Double, cy: Double, r: Double, sides: Int, turn: Double = 0.0) = fline {
    for (i in 0..sides) {
        val a = turn + 2 * PI * i / sides
        if (i == 0) moveTo(cx + r * cos(a), cy + r * sin(a)) else lineTo(cx + r * cos(a), cy + r * sin(a))
    }
    thickness = 6.0
}
val disc = ring(360.0, 100.0, 55.0, 64).apply { filled = true }
val drawing =
    Img.zeros(440, 200, 3)
        .drawFLines(
            [ring(90.0, 100.0, 70.0, 64), ring(210.0, 105.0, 65.0, 3, -PI / 2), disc],
            color = Vec4(1f, 1f, 1f, 1f),
        )
val ascii = ShapeAscii()
showAscii(
    "contrast 1" to ascii.convert(drawing, 44),
    "contrast 3" to ascii.convert(drawing, 44, contrast = 3.0),
    "contrast 3, directional 3" to ascii.convert(drawing, 44, contrast = 3.0, directional = 3.0),
    columns = 1,
    cellSide = 600,
)
```

Contrast does most of the work on lines: the triangle's right side turns into backslashes. Directional
contrast matters most along the boundary of a filled shape, where it trims the disc's rim; on thin
lines it can thin a stroke so far that a character drops out (the ring's left side), so treat both
as dials and look.

## Live, in a char grid

`drawAscii` writes a frame into a [`charGrid`](agent_transcripts.md#madp_chargrid) panel, so anything you can draw into an
`Img` each tick becomes live text. This box spins a wireframe cube, projects its edges into an
FLine drawing every tick, and matches it by shape. Pass the charset to `charGrid` with
`glyphs = ascii.glyphs` so every character the matcher picks exists in the grid:

```diffstatic_runner {data-lang=kotlin video="assets/ascii_shape/cube.mp4" alt="a char grid panel showing a wireframe cube turning, its edges drawn in white characters that follow each edge's direction, such as y, f, i, L, T, Q and underscores" execution="launch" wait-output="true" duration="6" fps="20" timeout="240" capture="box" open-outputs="true" padding="20" name="ascii-shape-cube" embed=true}
// A wireframe cube turning about two axes, drawn into an Img each tick and matched by shape.
val ascii = ShapeAscii()
// Corner i has x, y, z = ±1 from its three bits; an edge joins corners one bit apart.
val corners =
    (0 until 8).map { i ->
        Triple(if (i and 1 == 0) -1.0 else 1.0, if (i and 2 == 0) -1.0 else 1.0, if (i and 4 == 0) -1.0 else 1.0)
    }
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
ticks. Stop the box to stop the cube.

## On the GPU, over a live scene

The char grid above matches on the CPU, one drawn image per tick. `ShapeAsciiGpu` runs the same
matcher as a GPU pass, so it keeps up with a Vulkan scene. Draw any module into its `scene` target
and the characters come out in the window every frame, as in the real-time 3-D scenes of Harri's
essay. Here it is a lit torus knot, turning, with the scene itself in the corner:

```diffstatic_runner {data-lang=kotlin capture="native-window" capture-box="ascii_gpu_renderer" transcript="dev_docs/agents/transcripts/docs_capture/ascii_shape_gpu.yaml" video="assets/ascii_shape/gpu-knot.mp4" duration="8" wait="1" timeout="300" embed=true hidden=true name="ascii-shape-gpu-knot" alt="a native window of light gray characters on near-black: a torus knot turning about two axes, drawn in 128 columns and 48 rows of characters. The lit upper-left sides of the tube are runs of @, B and 8, the shaded sides Z, Q and S, and the rims are underscores, quotes, slashes and pipes that follow the curve. In the lower right corner a small inset shows the same knot as a smooth white shaded render"}
// The transcript builds a renderer whose window is the grid's size, and the knot box below beneath it.
```

The knot box draws into `ascii.scene` rather than into the window; otherwise it is an ordinary
module with a mesh. `installInto()` runs the scene and the matching pass every frame and draws the
characters over the window:

```diffstatic {data-lang=kotlin}
// The knot is drawn into the ASCII pass's scene target, not into the window. Every frame the pass
// matches the scene cell by cell and draws the characters over the window.
var ascii = ShapeAsciiGpu(cols = 128, rows = 48) {
    contrast = 3f
    directional = 3f
}
var module = moduleHere { +ascii.scene }
var knot = Mesh()
module += "knot" to knot
ascii.scene += "knot" to module

// The vertex shader turns the knot by `time` and projects it at the scene's shape.
var time = 0f
knot.time = {
    time += 1f / 60
    time
}
knot.aspect = { ascii.sceneWidth.toFloat() / ascii.sceneHeight }

// A (2, 3) torus knot: a tube of radius 0.16 around a centre line, 240 steps along it and 16 around.
fun centre(a: Double) =
    vec(0.42 * (2 + cos(3 * a)) * cos(2 * a), 0.42 * (2 + cos(3 * a)) * sin(2 * a), -0.42 * sin(3 * a))
knot.set(240 * 16, 240 * 16 * 2) {
    for (i in 0 until 240) for (j in 0 until 16) {
        val a = 2 * PI * i / 240
        val along = (centre(a + 1e-4) - centre(a - 1e-4)).normalize()
        val side = vec(along.y, -along.x, 0).normalize()
        val up = vec(side.y * along.z, -side.x * along.z, side.x * along.y - side.y * along.x)
        val w = 2 * PI * j / 16
        val out = side * cos(w).toFloat() + up * sin(w).toFloat()
        val p = centre(a) + out * 0.16f
        pos.add(p.x, p.y, p.z)
        normal.add(out.x, out.y, out.z)
        val next = (i + 1) % 240
        tri.add(i * 16 + j, next * 16 + j, next * 16 + (j + 1) % 16)
        tri.add(i * 16 + j, next * 16 + (j + 1) % 16, i * 16 + (j + 1) % 16)
    }
}

// Run the scene and the matching pass every frame and draw the characters over the window,
// with the scene itself, as the matcher sees it, in the lower right corner.
ascii.installInto()
ascii.drawSceneInto(
    viewport = Rect(window.width * 0.76, window.height * 0.76, window.width * 0.22, window.height * 0.22),
)
```

Its vertex shader turns the knot by `time` and projects it, and its fragment shader lights it from
the upper left:

```diffstatic {data-lang=glsl}
#version 450
layout(location = 0) in vec3 pos;
layout(location = 1) in vec3 normal;
layout(location = 0) out vec3 fnormal;

layout(push_constant) uniform Push {
    float time;
    float aspect;
} pc;

void main() {
    // turn about y, then x
    float a = pc.time * 0.7;
    float b = pc.time * 0.45;
    mat3 ry = mat3(cos(a), 0.0, -sin(a), 0.0, 1.0, 0.0, sin(a), 0.0, cos(a));
    mat3 rx = mat3(1.0, 0.0, 0.0, 0.0, cos(b), sin(b), 0.0, -sin(b), cos(b));
    vec3 p = rx * ry * pos;
    fnormal = rx * ry * normal;
    // a camera 4 units back looking along +z; +y is up, since Field flips its viewports;
    // depth runs 0..1 from z = 1 to z = 8
    float z = p.z + 4.0;
    gl_Position = vec4(p.x * 2.6 / pc.aspect, p.y * 2.6, (z - 1.0) / 7.0 * z, z);
}
```

```diffstatic {data-lang=glsl}
#version 450
layout(location = 0) in vec3 fnormal;
layout(location = 0) out vec4 outColor;

void main() {
    // a light up and to the left of the camera
    vec3 l = normalize(vec3(-0.5, 0.6, -0.65));
    float d = max(dot(normalize(fnormal), l), 0.0);
    outColor = vec4(vec3(0.04 + 0.96 * d), 1.0);
}
```

`scene` is an offscreen target exactly `cols` by `rows` cells of the charset's font: 1280 x 912
pixels here. A full-screen pass over a second target of 128 x 48 pixels, one fragment per cell,
averages the same circle pixels `convert` does, applies both contrast steps, and compares the
result with all 95 glyph vectors, writing the index of the nearest. A last pass draws those indices
into the window through an atlas of the glyphs. The circle pixels and the glyph vectors are written
into the matching shader as constants, from the same `ShapeAscii` the CPU uses. `contrast`,
`directional` and `invert` are read every frame, so they can change while it runs, and
`drawSceneInto(viewport = ...)` draws the scene as the matcher sees it.

### The same characters as the CPU

`ascii.read()` reads back one frame's scene together with the characters the GPU chose for it, so
the CPU can convert the same pixels. This box, beneath the knot box, compares the two:

```diffstatic {data-lang=kotlin}
// The knot box's pass, found among its parent's variables.
val gpu = fromContext<ShapeAsciiGpu>()

// Twice a second: read back a frame's scene with the GPU's characters, convert the same pixels on
// the CPU, exhaustively and through the cache, and count the cells that match.
frames {
    while (true) {
        val readback = gpu.read()
        while (!readback.isDone) frame()
        val (scene, chars) = readback.get()
        val c = gpu.contrast.toDouble()
        val d = gpu.directional.toDouble()
        val exact = gpu.ascii.convert(scene, gpu.cols, contrast = c, directional = d, rows = gpu.rows, exact = true)
        val cached = gpu.ascii.convert(scene, gpu.cols, contrast = c, directional = d, rows = gpu.rows)
        display(
            "agreement",
            "GPU = CPU exact on ${(100 * chars.agreement(exact)).f2}% of cells, " +
                "= CPU cached on ${(100 * chars.agreement(cached)).f2}%",
        )
        repeat(30) { frame() }
    }
}
```

Over five frames of the turning knot, each at three settings (no contrast, `contrast = 3`, and
`contrast = 3, directional = 3`), the GPU picked the same character as
`convert(..., exact = true)` in every cell of every frame, 6,144 cells a frame: 100.00 %. The
default `convert` agrees with it on 98 to 99 % of cells; the share moves with the frames its cache
has already met. The difference is the cache, not the GPU. `convert` rounds each
cell's six numbers to 32 levels and answers every vector that rounds the same way with the glyph
nearest the first such vector it met, so about one cell in a hundred gets a near neighbour instead
of the nearest glyph. `exact = true` searches all 95 glyphs for every cell, as the GPU does.

That search is cheap on the GPU, and its cost grows slowly with the grid. GPU time is the matching
pass alone, from timestamp queries, over 240 frames of the knot; CPU time is one `convert` of the
same frame with `contrast = 3, directional = 3`, the median of five:

| Grid | Scene pixels | GPU, median | GPU, 10th percentile | CPU, `exact = true` | CPU, cached |
| --- | --- | --- | --- | --- | --- |
| 64 x 24 | 640 x 456 | 1.8 ms | 1.2 ms | 2.1 ms | 2.0 ms |
| 128 x 48 | 1280 x 912 | 2.5 ms | 1.7 ms | 8.0 ms | 7.9 ms |
| 256 x 96 | 2560 x 1824 | 2.6 ms | 1.8 ms | 32 ms | 27 ms |
| 512 x 192 | 5120 x 3648 | 4.3 ms | 3.2 ms | 121 ms | 107 ms |

On the GPU about 1.2 ms is fixed cost, and 98,304 cells add only about 2 ms to it. The CPU's time
grows with the number of pixels it averages, so a 128-column grid fits in a 60 Hz frame on either,
but only the GPU keeps up at 256 columns and beyond. The cache saves the CPU little here because a
turning scene keeps meeting new cell patterns.

??? aside "Where the GPU numbers come from"
    The smoke test `ascii.shape-gpu.smoke` (`ShapeAsciiGpuSmoke.kt`) draws this knot on a real
    renderer, reads back five frames per setting with `read()`, and converts each on the CPU both
    ways. It fails if any frame's exact agreement falls below 99.5 %; every run so far has given
    100.00 %. It also checks that an empty scene reads back as spaces, and that a scene lit in its
    top half has ink in the top rows. That check exists because Field flips its viewports so that
    clip-space +y is up, so a full-screen pass sees the window's bottom row at `uv.y = 0`. The first
    cut drew every row and glyph upside down. The timings are from an Apple-silicon Mac with other
    Field sessions sharing the GPU, so the medians wander by about a millisecond between runs.
    The 10th percentile is closer to an idle machine.

## Reference

| Call | What it does |
| --- | --- |
| `ShapeAscii(glyphs, fontSize, fontName)` | Rasterise a charset (default printable ASCII, 16 pt monospaced). |
| `convert(img, cols, shape, contrast, directional, invert, rows, exact)` | Turn an `Img` into an `AsciiFrame`; `rows` defaults to the image's aspect at this font's cell shape; `exact = true` searches every glyph instead of the cache. |
| `frame.text()`, `frame.char(x, y)` | The result as lines, or one character. |
| `frame.toImg()` | Draw the result in the matched font. |
| `showAscii("label" to frame, ...)` | Show frames side by side, captioned. |
| `drawAscii(frame)` | Inside `charGrid { }`: write the frame into the grid. |
| `frame.agreement(other)` | The share of cells holding the same character in two frames of one size. |
| `glyphVector(i)`, `glyphCoverage(i)`, `samplingCircles()`, `circlePixels(k)`, `glyphImg(i)` | What the matcher compares. |
| `ShapeAsciiGpu(cols, rows, ascii) { contrast = 3f }` | The matcher as a GPU pass beneath a renderer: `cols` x `rows` cells of `ascii`'s font. |
| `gpu.scene` | The offscreen target to draw a scene into (`moduleHere { +gpu.scene }`, then `gpu.scene += "name" to module`). |
| `gpu.contrast`, `gpu.directional`, `gpu.invert`, `gpu.ink`, `gpu.background` | Read every frame. |
| `gpu.installInto()` | Run the scene and matching passes every frame and draw the characters over the window. |
| `gpu.drawInto(target, viewport)`, `gpu.drawSceneInto(target, viewport)` | Draw the characters, or the scene, into part of a target. |
| `gpu.read()` | A future of the next frame's `scene` and the characters chosen for it, read back together. |
| `gpu.profile`, `gpu.matchMilliseconds` | GPU timestamps on the scene and matching passes. |

The method, the staggered circles and both contrast steps are Alex Harri's; read
[his essay](https://alexharri.com/blog/ascii-rendering) for interactive versions of every step and
for the GPU implementation he uses for real-time 3-D scenes. Field's own is in
[the GPU section](#on-the-gpu-over-a-live-scene) above.
