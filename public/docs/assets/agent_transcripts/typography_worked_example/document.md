# Field Document: typography_worked_example

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 8 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. 3 — advances, kerning, tracking, alignment

<!-- field-box id="typo_layout" index="0" -->

### Narrative

From `code`

Layout is AWT's advances and kerning pairs, plus the things AWT does not do: \n, letter
spacing, alignment, and a mutable transform handle per glyph.

```edn [field-box id="typo_layout" role="properties"]
{"id" "typo_layout" "index" 0 "name" "3 — advances, kerning, tracking, alignment" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "3 — advances, kerning, tracking, alignment" "_tombstone" false "frame" #afield/rect [1000.0 60.0 390.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "typo_layout_code_.kt" "lines" 62 "characters" 2815 "sha256" "a356919522e2ef63ec422115cbd73e9360353f7646f8bc5a0f44fc31288e41b9"}]}
```

```kotlin [field-property box="typo_layout" name="code" path="typo_layout_code_.kt"]
// Layout is AWT's advances and kerning pairs, plus the things AWT does not do: \n, letter
// spacing, alignment, and a mutable transform handle per glyph.
val face = typeface("SansSerif", 120.0)

// Identity one: with kerning off and no tracking, the line is the sum of its advances. Two
// different AWT paths — accumulated positions versus per-glyph metrics — that must agree.
val plain = layoutText("minimum", face.withKerning(false))
println(
    "advance width ${plain.advanceWidth.f4} vs sum of advances " + "${plain.glyphs.sumOf { it.glyph.advance }.f4}",
)

// Identity two: tracking adds one gap per INTER-GLYPH space, not one per glyph.
val t = 0.16 * face.size
val tight = layoutText("TYPE", face)
val loose = layoutText("TYPE", face, tracking = t)
println(
    "tracking ${t.f1} over ${tight.glyphs.size - 1} gaps widened the line by " +
        "${(loose.advanceWidth - tight.advanceWidth).f4} (expected ${((tight.glyphs.size - 1) * t).f4})",
)

// Kerning adjusts spacing for specific pairs and may widen or narrow them.
// Report the measured total change; zero alone does not prove the absence of kern pairs.
val on = layoutText("AVATAR Wa To", face.withKerning(true)).advanceWidth
val off = layoutText("AVATAR Wa To", face.withKerning(false)).advanceWidth
println("kerning: $off -> $on (width change ${on - off} units)")

// The diagram of the decisions rather than the letters: baselines, ascent/descent guides and
// one advance box per glyph — including for the space, which has no glyph to draw.
showPlaneGrid(
    "tracking 0 — ${tight.advanceWidth.f0} units wide" to
        (tight.metricsFLines() + tight.advanceBoxFLines() + tight.toFLines(thickness = 1.0)),
    "tracking 0.16em — ${loose.advanceWidth.f0} units wide" to
        (loose.metricsFLines() + loose.advanceBoxFLines() + loose.toFLines(thickness = 1.0)),
    columns = 1,
    cellWidth = 820,
    cellHeight = 240,
    window = afield.app.protocol.Rect(-20.0, -face.ascent - 20, loose.advanceWidth + 40, face.lineHeight + 40),
)

// Multi-line and alignment, measured by the real layout so the wrap is right for the
// tracking and kerning you are going to use.
val prose = wrapText(
    "Type is geometry that happens to be legible, and legibility is the one test " +
        "a plausible looking picture cannot pass.",
    face.atSize(44.0),
    maxWidth = 620.0,
)
println(
    "wrapped to ${prose.lines().size} lines, widest ${prose.lines().maxOf { textWidth(it, face.atSize(44.0)) }.f1} of 620",
)
displayOpen("alignment")
showImgGrid(
    *TextAlign.entries
        .map { a ->
            a.name.lowercase() to
                rasterizeText(layoutText(prose, face.atSize(44.0), align = a, lineSpacing = 1.12), 700, 380)
        }
        .toTypedArray(),
    channel = "alignment",
    columns = 3,
    cellSide = 420,
)
```

## 2. 5 — the handles, animated (LAUNCH this one)

<!-- field-box id="typo_kinetic" index="1" -->

### Narrative

From `code`

Every PlacedGlyph carries mutable handles — offset, rotation, scale, pivot — and the
pivot defaults to the glyph's own visual centre, so a bare `rotation = 0.3` spins each
letter about itself. These handles are the API rather than an afterthought: this whole box
is one layout, computed once, and a function of elapsed seconds written into it.

```edn [field-box id="typo_kinetic" role="properties"]
{"id" "typo_kinetic" "index" 1 "name" "5 — the handles, animated (LAUNCH this one)" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "5 — the handles, animated (LAUNCH this one)" "_tombstone" false "frame" #afield/rect [60.0 780.0 400.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "typo_kinetic_code_.kt" "lines" 52 "characters" 1854 "sha256" "0a557dfb51bf0e22ea334b8d5e4ba322d9d00d9ee0c0a2ddb1feb4c9c6d3e60a"}]}
```

```kotlin [field-property box="typo_kinetic" name="code" path="typo_kinetic_code_.kt"]
/*
Every PlacedGlyph carries mutable handles — offset, rotation, scale, pivot — and the
pivot defaults to the glyph's own visual centre, so a bare `rotation = 0.3` spins each
letter about itself. These handles are the API rather than an afterthought: this whole box
is one layout, computed once, and a function of elapsed seconds written into it.
*/
val face = typeface("SansSerif", 150.0)
val layout = layoutText("KINETIC", face, tracking = 16.0)

// The frame is fixed once from the widest the wave will ever get, so the letters move inside
// a still frame instead of the frame breathing around them.
val window = run {
    val probe = layoutText("KINETIC", face, tracking = 16.0)
    probe.eachGlyph { i ->
        rotation = 0.5
        scale = vec(1.3, 1.3)
        offset = vec(0.0, 34.0)
    }
    val a = probe.bounds
    probe.eachGlyph { i ->
        rotation = -0.5
        scale = vec(1.3, 1.3)
        offset = vec(0.0, -34.0)
    }
    a.union(probe.bounds)
}

val started = AnimationClock.nowSeconds()
val displayFps = 20.0
var nextDisplay = 0.0

// The line you would change: elapsed seconds in, a travelling wave out.
fun waveAt(elapsed: Double, i: Int): Double = 1.7 * elapsed + i * 0.7

animate {
    val elapsed = AnimationClock.nowSeconds() - started
    // Keep motion tied to elapsed time; cap the expensive raster and output work.
    if (elapsed < nextDisplay) return@animate
    nextDisplay = elapsed + 1.0 / displayFps
    layout.eachGlyph { i ->
        val w = waveAt(elapsed, i)
        rotation = 0.42 * kotlin.math.sin(w)
        val s = 1.0 + 0.22 * kotlin.math.cos(w)
        scale = vec(s, s)
        offset = vec(0.0, 30.0 * kotlin.math.sin(w * 1.3))
    }
    showImgGrid(
        "t = %.1fs".format(elapsed) to rasterizeText(layout, 1100, 420, window = window),
        columns = 1,
        cellSide = 820,
    )
}
```

## 3. 7 — letters in the SDF family

<!-- field-box id="typo_sdf" index="2" -->

### Narrative

From `code`

toSdf builds a 2D signed-distance grid over the layout, samples it bilinearly and extrudes
it in z with the exact prism formula. Negative inside the ink, per mesh_sdf.kt's
convention. From there letters are just another ScalarField3D, which is the point.

```edn [field-box id="typo_sdf" role="properties"]
{"id" "typo_sdf" "index" 2 "name" "7 — letters in the SDF family" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "7 — letters in the SDF family" "_tombstone" false "frame" #afield/rect [60.0 1140.0 310.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "typo_sdf_code_.kt" "lines" 40 "characters" 1622 "sha256" "6de993e8e3f8157dfff399311fd22af1dfcfcc81d81fb9413f670df9f4cca755"}]}
```

```kotlin [field-property box="typo_sdf" name="code" path="typo_sdf_code_.kt"]
/*
toSdf builds a 2D signed-distance grid over the layout, samples it bilinearly and extrudes
it in z with the exact prism formula. Negative inside the ink, per mesh_sdf.kt's
convention. From there letters are just another ScalarField3D, which is the point.
*/
val layout = layoutText("SDF", typeface("SansSerif", 170.0))
val sdf = layout.toSdf(depth = 0.24, gridResolution = 340)
val grid = sdf.distanceField
println(
    "grid ${grid.nx}x${grid.ny} over ${grid.bounds.w.f0}x${grid.bounds.h.f0} units; " +
        "deepest inside the ink ${grid.deepestInside().f1} units",
)

val marched = sdf.march(resolution = 130)
println("marchingCubes: ${marched.triangleCount} triangles, genus ${marched.genus()}")

// And now the composition that having letters in this family buys you.
val melted = marchingCubes(sdfSmoothUnion(sdf.field, sdfSphere(0.6, vec(0.0, 0.0, 0.0)), 0.18), sdf.bounds, 130)
val hollow = marchingCubes(sdf.field.shell(0.02), sdf.bounds, 150)
val wrung = marchingCubes(sdf.field.twisted(0.5), sdf.bounds, 130)
println(
    "smooth union with a sphere: ${melted.triangleCount} triangles; " +
        "shelled: ${hollow.triangleCount}; twisted: ${wrung.triangleCount}",
)

displayOpen("sdf")
showImg(
    grid.toImg(),
    channel = "sdf",
    caption = "the 2D signed distance field — mid grey is the outline",
)
showMeshGrid(
    "marchingCubes on the letters" to marched,
    "sdfSmoothUnion with a sphere" to melted,
    "field.shell(0.02)" to hollow,
    "field.twisted(0.5)" to wrung,
    columns = 2,
    cellWidth = 400,
    view = MeshView(yawDegrees = 18.0, pitchDegrees = 12.0, distance = 2.6),
)
```

## 4. 8 — letters as point clouds

<!-- field-box id="typo_points" index="3" -->

### Narrative

From `code`

The last bridge is the plainest: even-by-arc-length samples along the outlines, which is
what every point-consuming family in the stdlib eats. Once a word is points, the
clusterers, the triangulators and the alife agents all work on letters.

```edn [field-box id="typo_points" role="properties"]
{"id" "typo_points" "index" 3 "name" "8 — letters as point clouds" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "8 — letters as point clouds" "_tombstone" false "frame" #afield/rect [1000.0 1140.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "typo_points_code_.kt" "lines" 39 "characters" 1835 "sha256" "1c3347f8d925ac77e5f5180528e16bc67bcc4760c3a4b176d0f40e9e8ee52609"}]}
```

```kotlin [field-property box="typo_points" name="code" path="typo_points_code_.kt"]
/*
The last bridge is the plainest: even-by-arc-length samples along the outlines, which is
what every point-consuming family in the stdlib eats. Once a word is points, the
clusterers, the triangulators and the alife agents all work on letters.
*/
val layout = layoutText("field", typeface("SansSerif", 200.0))
val coarse = layout.samplePoints(spacing = 18.0)
val fine = layout.samplePoints(spacing = 4.5)
println(
    "spacing 18 -> ${coarse.size} points, spacing 4.5 -> ${fine.size} points " +
        "(${(fine.size.toDouble() / coarse.size).f2}x for 4x finer)",
)

// Delaunay over the outline samples: the triangulation of a word.
val graph = vec2DelaunayGraph(coarse)
println("Delaunay over ${coarse.size} outline samples: ${graph.edges.size} edges")

// And k-medoids, which finds the strokes: six clusters over the same points, each medoid an
// actual sample rather than an average.
val km = vec2KMedoids(coarse, k = 6, options = KMedoidsOptions(randomSeed = 11L))
val layer = ClusterLayer("outline strokes", km.labels)
println(
    "k-medoids k=6: sizes ${km.labels.toList().groupingBy { it }.eachCount().values.sorted()}, " +
        "distortion ${km.distortion.f1}, converged ${km.converged}",
)

// Every panel shares one window, so the four views of the same word line up.
val window = layout.bounds
showPlaneGrid(
    "outline samples (${coarse.size} at spacing 18)" to [layout.toPointFLine(spacing = 18.0, pointSize = 1.1)],
    "Delaunay over those samples" to
        [graph.toFLine(coarse, thickness = 0.8), layout.toPointFLine(spacing = 18.0, pointSize = 0.9)],
    "k-medoids, k = 6" to layer.toFLines(coarse, centers = km.medoids, drawSpokes = false),
    "the outlines the samples came from" to layout.toFLines(thickness = 1.2),
    columns = 2,
    cellWidth = 400,
    cellHeight = 240,
    window = window,
)
```

## 5. 4 — text on a curve, by arc length

<!-- field-box id="typo_curve" index="4" -->

### Narrative

From `code`

onCurve does not distort a glyph. It looks its advance-box centre up at that distance
along the path with FLine.cursor()'s arc-length table — the same machinery
sampleByDistance and byDashing use — and then sets that glyph's rotation and pivot. So the
letters stay letters, and the spacing is even in DISTANCE rather than in curve parameter.

```edn [field-box id="typo_curve" role="properties"]
{"id" "typo_curve" "index" 4 "name" "4 — text on a curve, by arc length" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "4 — text on a curve, by arc length" "_tombstone" false "frame" #afield/rect [1000.0 420.0 340.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "typo_curve_code_.kt" "lines" 47 "characters" 1866 "sha256" "8874de2cde568ef35e4aea84485b5c4d83da64e563d78a8187f25aa3376213bb"}]}
```

```kotlin [field-property box="typo_curve" name="code" path="typo_curve_code_.kt"]
/*
onCurve does not distort a glyph. It looks its advance-box centre up at that distance
along the path with FLine.cursor()'s arc-length table — the same machinery
sampleByDistance and byDashing use — and then sets that glyph's rotation and pivot. So the
letters stay letters, and the spacing is even in DISTANCE rather than in curve parameter.
*/
val face = typeface("SansSerif", 78.0)
val ring = layoutText("AROUND AND AROUND", face, tracking = 7.0)

// Size the circle to the text rather than the text to the circle: a circumference equal to
// the layout's width means the string closes exactly.
val circle = textCircle(radius = ring.advanceWidth / (2 * Math.PI))
val onCircle = ring.onCurve(circle, align = TextAlign.CENTER)
println("circle circumference ${circle.arcLength().f1} vs layout width ${ring.advanceWidth.f1}")
println(
    "${onCircle.glyphs.count { kotlin.math.abs(it.rotation) > 0.05 }} of ${onCircle.glyphs.size} glyphs turned with the curve",
)

val wave = fline {
    moveTo(0.0, 0.0)
    cubicTo(300.0, -240.0, 720.0, 240.0, 1020.0, 0.0)
}
val onWave = layoutText("a rolling line of type", face, tracking = 2.0).onCurve(wave, align = TextAlign.CENTER)

// onCurve returns a COPY, so the straight layout is still straight and can be bent again.
println("the original layout is untouched: ${ring.glyphs.all { it.isUntransformed }}")

showImgGrid(
    "on a circle" to rasterizeText(onCircle, 620, 620),
    "on a cubic" to rasterizeText(onWave, 900, 420),
    columns = 2,
    cellSide = 430,
)
displayOpen("paths")
showPlaneGrid(
    "the paths, and the letters as outlines" to
        ([
            circle.copy {
                color = vec(0.4, 0.6, 0.8, 0.5)
                thickness = 1.0
            },
        ] + onCircle.toFLines(thickness = 1.0)),
    channel = "paths",
    columns = 1,
    cellWidth = 420,
    cellHeight = 420,
)
```

## 6. 6 — extrusion, counters and all

<!-- field-box id="typo_mesh" index="5" -->

### Narrative

From `code`

extrudeMesh and triangulatePolygon in mesh_generate.kt say outright that they cannot do
holes, and a letter is a ring SYSTEM. So toMesh triangulates the caps with FloatEarcut and
hole indices, and builds the walls from the same flattened vertices the caps used — which
is what makes the result watertight, and what makes genus a meaningful question about a
letter.

```edn [field-box id="typo_mesh" role="properties"]
{"id" "typo_mesh" "index" 5 "name" "6 — extrusion, counters and all" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "6 — extrusion, counters and all" "_tombstone" false "frame" #afield/rect [1000.0 780.0 320.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "typo_mesh_code_.kt" "lines" 59 "characters" 2763 "sha256" "66de641e11486e5b22648a495f6ece8dc423cb885edff77ee77ef7118471c31b"}]}
```

```kotlin [field-property box="typo_mesh" name="code" path="typo_mesh_code_.kt"]
/*
extrudeMesh and triangulatePolygon in mesh_generate.kt say outright that they cannot do
holes, and a letter is a ring SYSTEM. So toMesh triangulates the caps with FloatEarcut and
hole indices, and builds the walls from the same flattened vertices the caps used — which
is what makes the result watertight, and what makes genus a meaningful question about a
letter.
*/
val face = typeface("SansSerif", 170.0)
val o = layoutText("o", face).toMesh(depth = 46.0, centre = true)
val l = layoutText("l", face).toMesh(depth = 46.0, centre = true)
val word = layoutText("mesh", typeface("SansSerif", 130.0)).toMesh(depth = 36.0, centre = true)

// A 2D count against a 3D one. The hole count comes from ring nesting in the plane; the
// genus comes from counting the mesh's vertices, edges and faces.
for ((name, mesh, holes) in
    [
        Triple("o", o, layoutText("o", face).holeCount),
        Triple("l", l, layoutText("l", face).holeCount),
        Triple("mesh", word, layoutText("mesh", typeface("SansSerif", 130.0)).holeCount),
    ]) {
    println(
        "'$name': ${mesh.triangleCount} triangles, watertight ${mesh.isWatertight()}, " +
            "manifold ${mesh.isManifold()}, chi ${mesh.eulerCharacteristic()}, genus ${mesh.genus()} " +
            "(2D hole count $holes)",
    )
}

// A 2D area against a 3D volume: a shoelace sum times the depth, against the divergence
// theorem over the triangles. A dropped cap triangle or a wall wound the wrong way breaks it.
val flat = layoutText("o", face)
val expected = flat.glyphs.sumOf { it.glyph.inkArea } * 46.0
val measured = layoutText("o", face).toMesh(depth = 46.0, yUp = false).volume()
println(
    "'o': ink area x depth = ${expected.f0}, mesh volume ${measured.f0}, " +
        "relative difference ${"%.2e".format(kotlin.math.abs(expected - measured) / expected)}",
)

/*
Back-face culling is on in MeshView, so a wall wound inside out would be a MISSING wall
here rather than merely odd shading. No flatShaded() either: showMeshGrid computes
topology() on what you hand it and prints it in the caption, and flatShaded() un-welds
every triangle — so a flat-shaded copy would caption these as open, directly under a
title stating their genus. It is also unnecessary: the render path applies
shadedNormals to an unshaded mesh for you (mesh_field.kt:246).
*/
showMeshGrid(
    "o — genus ${o.genus()}" to o,
    "l — genus ${l.genus()}" to l,
    columns = 2,
    cellWidth = 400,
    view = MeshView(yawDegrees = 26.0, pitchDegrees = 16.0, distance = 3.2),
)
displayOpen("word")
showMesh(
    word,
    channel = "word",
    view = MeshView(yawDegrees = 22.0, pitchDegrees = 14.0, distance = 2.6, width = 820, height = 340),
    title = "extruded from the same contours the raster filled",
)
```

## 7. 2 — the winding convention, and what happens without it

<!-- field-box id="typo_winding" index="6" -->

### Narrative

From `code`

Both cells below go through the identical filler at the identical fill rule. The only
difference is whether hole rings run the opposite way to their bowls. NON_ZERO fill —
which is what Skia and Java2D both do — cancels a +1 outer against a -1 hole and leaves a
counter; give it two rings wound the same way and it fills them solid.

```edn [field-box id="typo_winding" role="properties"]
{"id" "typo_winding" "index" 6 "name" "2 — the winding convention, and what happens without it" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2 — the winding convention, and what happens without it" "_tombstone" false "frame" #afield/rect [60.0 420.0 470.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "typo_winding_code_.kt" "lines" 51 "characters" 1940 "sha256" "04602ab4aca96bbee29fdb4f0db0f49fdf9b895db30ac93d9f60a93cf840f112"}]}
```

```kotlin [field-property box="typo_winding" name="code" path="typo_winding_code_.kt"]
/*
Both cells below go through the identical filler at the identical fill rule. The only
difference is whether hole rings run the opposite way to their bowls. NON_ZERO fill —
which is what Skia and Java2D both do — cancels a +1 outer against a -1 hole and leaves a
counter; give it two rings wound the same way and it fills them solid.
*/
val face = typeface("SansSerif", 220.0)
val layout = layoutText("open", face)
val window = layout.bounds

val withConvention = rasterizeText(
    layout,
    900,
    460,
    window = window,
    fillRule = TextFillRule.NON_ZERO,
    rings = layout.toContours(honourHoles = true),
)
val withoutConvention = rasterizeText(
    layout,
    900,
    460,
    window = window,
    fillRule = TextFillRule.NON_ZERO,
    rings = layout.toContours(honourHoles = false),
)

/*
EVEN_ODD counts crossings and ignores their direction, so it depends on nesting parity
instead and fills the counters correctly either way. Two rules that agree on correct input
and disagree on broken input is a cross-check, which is why both are in the API.
*/
val evenOdd = rasterizeText(layout, 900, 460, window = window, fillRule = TextFillRule.EVEN_ODD)

var worst = 0.0
for (y in 0 until withConvention.height) for (x in 0 until withConvention.width) {
    worst = maxOf(worst, kotlin.math.abs(withConvention.at(x, y, 0) - evenOdd.at(x, y, 0)).toDouble())
}

showImgGrid(
    "NON_ZERO, convention on: counters are holes" to withConvention,
    "NON_ZERO, convention off: counters fill solid" to withoutConvention,
    "EVEN_ODD over the correct rings" to evenOdd,
    columns = 3,
    cellSide = 420,
)
println("ink with the convention  : ${(100 * withConvention.inkFraction()).f2}%")
println(
    "ink without it           : ${(100 * withoutConvention.inkFraction()).f2}%  <- the counters closed up",
)
println("worst NON_ZERO vs EVEN_ODD difference over the correct rings: $worst (0.0 means the two rules agree exactly)")
```

## 8. 1 — a string becomes contours

<!-- field-box id="typo_values" index="7" -->

### Narrative

From `code`

A Typeface is a font at a size. Logical families (SansSerif, Serif, Monospaced) always
resolve; Typefaces.fromFile("...ttf") loads one off disk through Font.createFont, and
Typefaces.firstAvailable("Futura", "Avenir") picks the first face you actually have.

```edn [field-box id="typo_values" role="properties"]
{"id" "typo_values" "index" 7 "name" "1 — a string becomes contours" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1 — a string becomes contours" "_tombstone" false "frame" #afield/rect [60.0 60.0 310.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "typo_values_code_.kt" "lines" 66 "characters" 2685 "sha256" "3492cc0eb4b4acf6fedbb6d3d3c058f2c0505258c90ac6869556483a765a21ab"}]}
```

```kotlin [field-property box="typo_values" name="code" path="typo_values_code_.kt"]
/*
A Typeface is a font at a size. Logical families (SansSerif, Serif, Monospaced) always
resolve; Typefaces.fromFile("...ttf") loads one off disk through Font.createFont, and
Typefaces.firstAvailable("Futura", "Avenir") picks the first face you actually have.
*/
val face = typeface("SansSerif", 140.0)
println(
    "$face — ascent ${face.ascent.f1}, descent ${face.descent.f1}, " + "line height ${face.lineHeight.f1}",
)

/*
A Glyph carries its rings twice over, and the two are guaranteed to agree: `contours` are
flattened polygons classified by nesting depth, `outline` is the same rings as an FLine of
the font's real cubics.
*/
for (c in ['o', 'l', 'B']) {
    val g = face.glyph(c)
    println(
        "'$c' glyph #${g.glyphCode}: ${g.contourCount} contours (${g.holeCount} holes), " +
            "advance ${g.advance.f1}, ink area ${g.inkArea.f0}",
    )
    g.contours.forEachIndexed { i, ring ->
        println(
            "    ring $i: depth ${ring.depth}, ${if (ring.isHole) "HOLE " else "outer"}, " +
                "signed area ${"%+.0f".format(ring.signedArea)}, ${ring.points.size} points",
        )
    }
}

/*
The convention this family enforces, stated once: outer rings positive, holes negative,
with nesting depth (containment, not direction) as the authority. That is why the counter
of an `o` is a hole under either fill rule instead of a filled blob.
*/

// And the headline: a word, filled from those contours by the family's own scanline filler.
// Not Graphics2D.drawString — this raster is evidence about the extraction.
val word = layoutText("Handgloves", face)
showImgGrid(
    "Handgloves — filled from its own contours" to rasterizeText(word, 1600, 380),
    columns = 1,
    cellSide = 860,
)
println(
    "laid out: ${word.contourCount} contours, ${word.holeCount} holes, " +
        "${word.advanceWidth.f1} units wide, ink ${(100 * rasterizeText(word, 400, 120).inkFraction()).f1}%",
)

/*
The same word again, but as VECTORS this time: showText fills the FLines through
drawToSVG, so the counters come out holes there too — Skia fills non-zero, and that is the
convention doing the work rather than the rasteriser being clever.

Its own channel, and that is not decoration: `display(channel, …)` REPLACES a channel
rather than appending to it, so a second figure on "main" silently erases the first. Two
figures in one box means two channels. displayOpen marks the card open before the content
lands, so it arrives expanded instead of as a collapsed header.
*/
displayOpen("vectors")
showText(
    word,
    channel = "vectors",
    title = "the same layout as filled FLines, through drawToSVG",
    showBaseline = true,
    height = 220,
)
```

