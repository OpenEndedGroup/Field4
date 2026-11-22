# Field Document: nile_gezira_kernels

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 7 "persistentProperties" ["contents" "frame" "name" "code" "code_language" "box_kind" "textFrame" "textRunAnchor" "textRunSources" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "parentHint" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_panel" "ai4_continuation" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "ai4_garden" "fileManagerAccessHistory" "kitRole"]}
```

## 1. stroke kernel

<!-- field-box id="gezira_stroke" index="0" -->

```edn [field-box id="gezira_stroke" role="properties"]
{"id" "gezira_stroke" "index" 0 "name" "stroke kernel" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "stroke kernel" "_tombstone" false "frame" #afield/rect [40.0 1200.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "gezira_stroke_code_.kt" "lines" 20 "characters" 673 "sha256" "3f11422b933f787491d7c2c4a9426eb0be1bb6a6f53ac829c5cf8b62625665a8"}]}
```

```kotlin [field-property box="gezira_stroke" name="code" path="gezira_stroke_code_.kt"]
import afield.graphics.nile.*

// An open wave of two cubics, stroked 2 pixels wide, on a 48 x 24 grid.
val wave = listOf(
    Bezier(doubleArrayOf(3.0, 12.0, 10.0, 0.0, 17.0, 24.0, 24.0, 12.0)),
    Bezier(doubleArrayOf(24.0, 12.0, 31.0, 0.0, 38.0, 24.0, 45.0, 12.0)),
)
val t = (GeziraStroke.strokeBezierPath(2.0) then Gezira.rasterizer).trace(wave)
println(t)

val s = 16.0
val outline = t.get<Bezier>("StrokeBezierPath")
val spans = t.get<Span>("WindingSpans")
lines(
    listOf(Gezira.grid(48, 24, s)) +
        Gezira.pixels(Gezira.coverage(spans, 48, 24), 48, s) +
        Gezira.pieces(outline, s) +
        Gezira.cuts(outline, s),
    channel = "stroked wave",
)
```

## 2. texture and composite

<!-- field-box id="gezira_composite" index="1" -->

```edn [field-box id="gezira_composite" role="properties"]
{"id" "gezira_composite" "index" 1 "name" "texture and composite" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "texture and composite" "_tombstone" false "frame" #afield/rect [760.0 1200.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "gezira_composite_code_.kt" "lines" 18 "characters" 933 "sha256" "70fcf9ab683a2e09909567d4e9ec1a991e9bd73f9cf9877388772dfff33a588f"}]}
```

```kotlin [field-property box="gezira_composite" name="code" path="gezira_composite_code_.kt"]
import afield.graphics.nile.*

// A star filled with a gradient, then a translucent circle composited over it, then a
// stroked ring multiplied on top: three paints, two blends, one canvas.
val canvas = Canvas(64, 48)
val star = FLine().moveTo(28.0 + 20.0, 24.0)
for (k in 1 until 10) {
    val a = PI * k / 5
    val r = if (k % 2 == 0) 20.0 else 8.5
    star.lineTo(28.0 + r * cos(a), 24.0 + r * sin(a))
}
val gradient = GeziraComposite.linearGradient(8.0, 4.0, 48.0, 44.0, Rgba.of(1.0, 0.75, 0.1), Rgba.of(0.9, 0.1, 0.4))
canvas.draw(Gezira.beziers(star), GeziraComposite.texture(gradient))
canvas.draw(Gezira.circle(40.0, 28.0, 13.0), GeziraComposite.color(Rgba.of(0.1, 0.5, 1.0, 0.55)))
val ring = GeziraStroke.strokeBezierPath(3.0)(Gezira.circle(22.0, 30.0, 11.0))
val t = canvas.draw(ring, GeziraComposite.color(Rgba.of(0.3, 0.9, 0.5, 0.9)), GeziraComposite.MULTIPLY)
println(t)
lines(canvas.pixels(8.0), channel = "canvas")
```

## 3. text beside skia

<!-- field-box id="gezira_text" index="2" -->

```edn [field-box id="gezira_text" role="properties"]
{"id" "gezira_text" "index" 2 "name" "text beside skia" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "text beside skia" "_tombstone" false "frame" #afield/rect [1480.0 1200.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "gezira_text_code_.kt" "lines" 12 "characters" 694 "sha256" "fd5afb1e82db2f7f5c273abbbd000641e2bba928dbabd127e5c6cc85aa259a97"}]}
```

```kotlin [field-property box="gezira_text" name="code" path="gezira_text_code_.kt"]
import afield.graphics.nile.*

// A line of serif text: quadratic glyph outlines, the same kind of input as Gezira's tiger.
val text = Gezira.glyphs("Pixels on a screen", 6.0, 46.0, size = 40.0)
val t = Gezira.edgeSampleRasterizer.trace(text)
println("${text.size} segments, ${t.get<Bezier>("DecomposeBeziers").size} pieces, ${t.output.size} spans")
val ours = Gezira.coverage(t.get("CombineEdgeSamples"), 360, 64)
val skia = Gezira.skiaCoverage(text, 360, 64)
val (worst, mean) = Gezira.error(ours, skia)
println("kernels vs Skia: max |diff| %.3f, mean %.4f".format(worst, mean))
lines(Gezira.pixels(ours, 360, 2.0), channel = "kernels")
lines(Gezira.pixels(skia, 360, 2.0), channel = "skia")
```

## 4. a new kernel

<!-- field-box id="gezira_new_kernel" index="3" -->

```edn [field-box id="gezira_new_kernel" role="properties"]
{"id" "gezira_new_kernel" "index" 3 "name" "a new kernel" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "a new kernel" "_tombstone" false "frame" #afield/rect [1480.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "gezira_new_kernel_code_.kt" "lines" 28 "characters" 1023 "sha256" "e7db74051659f363febb15b8c5db88dbdfcf09606aaf93437d067a837cc3c91c"}]}
```

```kotlin [field-property box="gezira_new_kernel" name="code" path="gezira_new_kernel_code_.kt"]
import afield.graphics.nile.*

// Gezira's TransformBeziers: one kernel, in front of the rasteriser.
fun rotate(degrees: Double, cx: Double, cy: Double): Stage<Bezier, Bezier> {
    val c = cos(degrees * PI / 180)
    val s = sin(degrees * PI / 180)
    return mapKernel("TransformBeziers") { b ->
        Bezier(
            DoubleArray(b.p.size) { i ->
                val x = b.p[i - i % 2] - cx
                val y = b.p[i - i % 2 + 1] - cy
                if (i % 2 == 0) cx + c * x - s * y else cy + s * x + c * y
            },
        )
    }
}

val square = FLine().moveTo(4.0, 4.0).lineTo(12.0, 4.0).lineTo(12.0, 12.0).lineTo(4.0, 12.0).lineTo(4.0, 4.0)
val t = (rotate(20.0, 8.0, 8.0) then Gezira.edgeSampleRasterizer).trace(Gezira.beziers(square))
println(t)
val px = 24.0
val spans = t.get<Span>("CombineEdgeSamples")
lines(
    listOf(Gezira.grid(16, 16, px)) +
        Gezira.pixels(Gezira.coverage(spans, 16, 16), 16, px) +
        Gezira.pieces(t.get("DecomposeBeziers"), px),
    channel = "rotated",
)
```

## 5. gezira streams

<!-- field-box id="gezira_streams" index="4" -->

```edn [field-box id="gezira_streams" role="properties"]
{"id" "gezira_streams" "index" 4 "name" "gezira streams" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "gezira streams" "_tombstone" false "frame" #afield/rect [40.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "gezira_streams_code_.kt" "lines" 26 "characters" 929 "sha256" "6c1fb68b10a2b2170397b7561884690acc4beb4dc4e0f3ff7f2fdca9cb83ad9a"}]}
```

```kotlin [field-property box="gezira_streams" name="code" path="gezira_streams_code_.kt"]
import afield.graphics.nile.*

// A curved shape as an FLine, in pixel units on a 16 x 12 grid.
val shape =
    FLine()
        .moveTo(2.2, 10.6)
        .cubicTo(1.0, 3.0, 0.0, 7.0, -0.5, 0.0, 9.5, 1.4, 0.0)
        .cubicTo(12.0, 2.0, 0.0, 15.5, 6.0, 0.0, 13.8, 10.9, 0.0)
        .lineTo(2.2, 10.6)

val t = Gezira.edgeSampleRasterizer.trace(Gezira.beziers(shape))
println(t)

val s = 32.0
val pieces = t.get<Bezier>("DecomposeBeziers")
val samples = t.get<EdgeSample>("EdgeSamples")
val spans = t.get<Span>("CombineEdgeSamples")
lines(listOf(Gezira.grid(16, 12, s)) + Gezira.pieces(pieces, s) + Gezira.cuts(pieces, s), channel = "1 pieces")
lines(
    listOf(Gezira.grid(16, 12, s)) + Gezira.outline(shape, s) + Gezira.samples(samples, s),
    channel = "2 edge samples",
)
lines(
    listOf(Gezira.grid(16, 12, s)) + Gezira.pixels(Gezira.coverage(spans, 16, 12), 16, s) + Gezira.spans(spans, s),
    channel = "3 spans",
)
```

## 6. beside skia

<!-- field-box id="gezira_vs_skia" index="5" -->

```edn [field-box id="gezira_vs_skia" role="properties"]
{"id" "gezira_vs_skia" "index" 5 "name" "beside skia" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "beside skia" "_tombstone" false "frame" #afield/rect [760.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "gezira_vs_skia_code_.kt" "lines" 21 "characters" 851 "sha256" "933143c87311f82c3f97ef330db1ace4676158dd8ab7b842295a0d5fa998aae1"}]}
```

```kotlin [field-property box="gezira_vs_skia" name="code" path="gezira_vs_skia_code_.kt"]
import afield.graphics.nile.*

// A five-pointed star and a circle of four cubics, on a 48 x 40 grid.
val star = FLine().moveTo(24.0 + 17.0, 20.0)
for (k in 1 until 10) {
    val a = PI * k / 5
    val r = if (k % 2 == 0) 17.0 else 7.0
    star.lineTo(24.0 + r * cos(a), 20.0 + r * sin(a))
}
val beziers = Gezira.beziers(star) + Gezira.circle(30.3, 26.1, 9.4)

val ours = Gezira.rasterize(beziers, 48, 40)
val skia = Gezira.skiaCoverage(beziers, 48, 40)
val diff = DoubleArray(ours.size) { minOf(1.0, 10 * abs(ours[it] - skia[it])) }
val (worst, mean) = Gezira.error(ours, skia)
println("kernels vs Skia: max |diff| %.4f, mean %.4f".format(worst, mean))

val s = 8.0
lines(Gezira.pixels(ours, 48, s), channel = "kernels")
lines(Gezira.pixels(skia, 48, s), channel = "skia")
lines(Gezira.pixels(diff, 48, s, 1.0, 0.4, 0.3), channel = "difference x10")
```

## 7. fill rules

<!-- field-box id="gezira_fill_rules" index="6" -->

```edn [field-box id="gezira_fill_rules" role="properties"]
{"id" "gezira_fill_rules" "index" 6 "name" "fill rules" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "fill rules" "_tombstone" false "frame" #afield/rect [2200.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "gezira_fill_rules_code_.kt" "lines" 24 "characters" 1244 "sha256" "73afdf37c4e5e0d59d6ac1903899aba2cdabc5a35ab1c218061461e2014a65ae"}]}
```

```kotlin [field-property box="gezira_fill_rules" name="code" path="gezira_fill_rules_code_.kt"]
import afield.graphics.nile.*

// A pentagram drawn as one outline that crosses itself, and two overlapping circles, on 56 x 40.
val star = FLine()
for (k in 0..5) {
    val a = -PI / 2 + k * 4 * PI / 5
    val x = 16.0 + 14.0 * cos(a)
    val y = 21.0 + 14.0 * sin(a)
    if (k == 0) star.moveTo(x, y) else star.lineTo(x, y)
}
val shape = Gezira.beziers(star) + Gezira.circle(36.0, 17.0, 10.3) + Gezira.circle(43.0, 25.0, 10.3)

val nonZero = Gezira.rasterize(shape, 56, 40, FillRule.NON_ZERO)
val evenOdd = Gezira.rasterize(shape, 56, 40, FillRule.EVEN_ODD)
val edgeSamples = Gezira.coverage(Gezira.edgeSampleRasterizer(shape), 56, 40)
val reference = Gezira.supersampledCoverage(shape, 56, 40, n = 64)
val error = DoubleArray(edgeSamples.size) { minOf(1.0, 10 * abs(edgeSamples[it] - reference[it])) }
println("winding, non-zero, vs 64 x 64 supersample: max |diff| %.4f".format(Gezira.error(nonZero, reference).first))
println("edge samples vs the same: max |diff| %.4f".format(Gezira.error(edgeSamples, reference).first))

val s = 6.0
lines(Gezira.pixels(nonZero, 56, s), channel = "non-zero")
lines(Gezira.pixels(evenOdd, 56, s), channel = "even-odd")
lines(Gezira.pixels(error, 56, s, 1.0, 0.4, 0.3), channel = "edge samples, error x10")
```

