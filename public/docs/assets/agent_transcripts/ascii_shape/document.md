# Field Document: ascii_shape

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 5 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. Six circles per cell

<!-- field-box id="ascii_shape_circles" index="0" -->

### Narrative

From `notes`

Six circles per cell

From `code`

Five glyphs as the matcher sees them, 10x up: the cell in gray, its six circles in orange,
and the six just outside it in blue.

```edn [field-box id="ascii_shape_circles" role="properties"]
{"id" "ascii_shape_circles" "index" 0 "name" "Six circles per cell" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Six circles per cell" "_tombstone" false "frame" #afield/rect [380.0 60.0 300.0 100.0]} "blockProperties" [{"name" "notes" "language" "markdown" "path" "ascii_shape_circles_notes_.md" "lines" 3 "characters" 188 "sha256" "c05e0d7d7d033ec6dffab6118b8f25c58b1b2894467bc80ac6494af441bace76"} {"name" "code" "language" "kotlin" "path" "ascii_shape_circles_code_.kt" "lines" 39 "characters" 1615 "sha256" "ce77af54d39461a57d7683260935dd63a5cf7b2726dbe82a1d8c5731f2669b93"}]}
```

```markdown [field-property box="ascii_shape_circles" name="notes" path="ascii_shape_circles_notes_.md"]
# Six circles per cell

Five characters as the matcher sees them: six inner sampling circles (orange) give each character its six numbers; the outer ones (blue) feed directional contrast.
```

```kotlin [field-property box="ascii_shape_circles" name="code" path="ascii_shape_circles_code_.kt"]
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

## 2. Lit sphere

<!-- field-box id="ascii_shape_sphere" index="1" -->

### Narrative

From `notes`

A lit sphere

From `code`

A lit sphere on black, shaded by hand: brightness is the cosine between the surface normal and a light.

```edn [field-box id="ascii_shape_sphere" role="properties"]
{"id" "ascii_shape_sphere" "index" 1 "name" "Lit sphere" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Lit sphere" "_tombstone" false "frame" #afield/rect [720.0 60.0 300.0 100.0]} "blockProperties" [{"name" "notes" "language" "markdown" "path" "ascii_shape_sphere_notes_.md" "lines" 3 "characters" 103 "sha256" "ef7878e358710a125a40a2e384f734ace27fd89e2b3bf75c8be67c230a5cde31"} {"name" "code" "language" "kotlin" "path" "ascii_shape_sphere_code_.kt" "lines" 17 "characters" 605 "sha256" "cc76eac49453ed8b47c4fe5e8ca9d91cedaa460eb93d1a926d9bd30588f1c495"}]}
```

```markdown [field-property box="ascii_shape_sphere" name="notes" path="ascii_shape_sphere_notes_.md"]
# A lit sphere

Shading by brightness ramp vs by shape. The method suits light marks on a dark ground.
```

```kotlin [field-property box="ascii_shape_sphere" name="code" path="ascii_shape_sphere_code_.kt"]
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

## 3. Brightness vs shape

<!-- field-box id="ascii_shape_shapes" index="2" -->

### Narrative

From `notes`

Shape-aware ASCII

From `code`

Three outlines and a filled disc, drawn white on black.

```edn [field-box id="ascii_shape_shapes" role="properties"]
{"id" "ascii_shape_shapes" "index" 2 "name" "Brightness vs shape" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Brightness vs shape" "_tombstone" false "frame" #afield/rect [40.0 60.0 300.0 100.0]} "blockProperties" [{"name" "notes" "language" "markdown" "path" "ascii_shape_shapes_notes_.md" "lines" 3 "characters" 295 "sha256" "585043a88bcdf7157ff0fc36201d366bf20c6fc2df00d7a3b6910e1813c35baa"} {"name" "code" "language" "kotlin" "path" "ascii_shape_shapes_code_.kt" "lines" 20 "characters" 757 "sha256" "da75b544190969f410ab2e57cf5a2a22630a79c6677bf83d4cbe33e06384c8d3"}]}
```

```markdown [field-property box="ascii_shape_shapes" name="notes" path="ascii_shape_shapes_notes_.md"]
# Shape-aware ASCII

The same FLine drawing as characters twice: picked by brightness alone (left) and by where the light falls in each cell (right), after Alex Harri's essay https://alexharri.com/blog/ascii-rendering. Run it; try `contrast = 3.0` in the second `convert`. Docs: ascii_shape.md.
```

```kotlin [field-property box="ascii_shape_shapes" name="code" path="ascii_shape_shapes_code_.kt"]
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

## 4. Live cube in a charGrid

<!-- field-box id="ascii_shape_cube" index="3" -->

### Narrative

From `notes`

Live, in a char grid

From `code`

A wireframe cube turning about two axes, drawn into an Img each tick and matched by shape.

```edn [field-box id="ascii_shape_cube" role="properties"]
{"id" "ascii_shape_cube" "index" 3 "name" "Live cube in a charGrid" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Live cube in a charGrid" "_tombstone" false "frame" #afield/rect [380.0 260.0 300.0 100.0]} "blockProperties" [{"name" "notes" "language" "markdown" "path" "ascii_shape_cube_notes_.md" "lines" 3 "characters" 155 "sha256" "8c4881254efea70ccece49ea7a577e8a8ccb16b8ab2c8216c5a3d48649b1f190"} {"name" "code" "language" "kotlin" "path" "ascii_shape_cube_code_.kt" "lines" 30 "characters" 1339 "sha256" "f73c961203f449839e1ea00d371793c09242698b761cbb48d701fc550fe808f2"}]}
```

```markdown [field-property box="ascii_shape_cube" name="notes" path="ascii_shape_cube_notes_.md"]
# Live, in a char grid

A wireframe cube drawn into an Img every tick and matched by shape into a `charGrid` with `drawAscii`. Run to start, stop to stop.
```

```kotlin [field-property box="ascii_shape_cube" name="code" path="ascii_shape_cube_code_.kt"]
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

## 5. Contrast and directional contrast

<!-- field-box id="ascii_shape_contrast" index="4" -->

### Narrative

From `notes`

Sharpening edges

From `code`

Three outlines and a filled disc, drawn white on black.

```edn [field-box id="ascii_shape_contrast" role="properties"]
{"id" "ascii_shape_contrast" "index" 4 "name" "Contrast and directional contrast" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Contrast and directional contrast" "_tombstone" false "frame" #afield/rect [40.0 260.0 300.0 100.0]} "blockProperties" [{"name" "notes" "language" "markdown" "path" "ascii_shape_contrast_notes_.md" "lines" 3 "characters" 108 "sha256" "cf81edf71d0b8947592b4e99a6cdea35dc7cc8dcc89f0a90d2e2db36fa4b4d25"} {"name" "code" "language" "kotlin" "path" "ascii_shape_contrast_code_.kt" "lines" 23 "characters" 898 "sha256" "d112be59d741af9a3cca07a0ad6a320363ef092cb8120c5702f67c32a3432fba"}]}
```

```markdown [field-property box="ascii_shape_contrast" name="notes" path="ascii_shape_contrast_notes_.md"]
# Sharpening edges

`contrast` and `directional` are exponents; 1 turns a step off. Compare the three rows.
```

```kotlin [field-property box="ascii_shape_contrast" name="code" path="ascii_shape_contrast_code_.kt"]
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

