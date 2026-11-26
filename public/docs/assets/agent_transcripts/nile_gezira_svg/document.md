# Field Document: nile_gezira_svg

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 4 "persistentProperties" ["contents" "frame" "name" "code" "code_language" "box_kind" "textFrame" "textRunAnchor" "textRunSources" "textFrameReview" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "parentHint" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "watchdog" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_panel" "ai4_continuation" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "ai4_garden" "fileManagerAccessHistory" "kitRole"]}
```

## 1. tiger as flines

<!-- field-box id="gezira_svg_flines" index="0" -->

```edn [field-box id="gezira_svg_flines" role="properties"]
{"id" "gezira_svg_flines" "index" 0 "name" "tiger as flines" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "tiger as flines" "_tombstone" false "frame" #afield/rect [1900.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "gezira_svg_flines_code_.kt" "lines" 6 "characters" 219 "sha256" "1b05167ff4040ed02de28932aa8d4d224e599e54c8acaeba0a2844fe5d33bafa"}]}
```

```kotlin [field-property box="gezira_svg_flines" name="code" path="gezira_svg_flines_code_.kt"]
import afield.graphics.nile.*

// The tiger as Field FLines, drawn by Field's own line renderer.
val outlines = GeziraSvg.flines(GeziraSvg.tiger())
println("${outlines.size} FLines")
lines(outlines, channel = "flines")
```

## 2. tiger beside skia

<!-- field-box id="gezira_svg_tiger" index="1" -->

```edn [field-box id="gezira_svg_tiger" role="properties"]
{"id" "gezira_svg_tiger" "index" 1 "name" "tiger beside skia" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "tiger beside skia" "_tombstone" false "frame" #afield/rect [760.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "gezira_svg_tiger_code_.kt" "lines" 30 "characters" 1158 "sha256" "a9b2b87d78cdce00e98d2c9902471c92f5c86c688a8a364f3e1bd9a25c916cde"}]}
```

````kotlin [field-property box="gezira_svg_tiger" name="code" path="gezira_svg_tiger_code_.kt"]
import afield.graphics.nile.*

// The Ghostscript tiger through Gezira's kernels, then through Skia's SVG renderer.
val n = 300
val text = GeziraSvg.tiger()
val doc = GeziraSvg.parse(text)
val r = GeziraSvg.render(doc, n, n)
val skia = GeziraSvg.skia(text, n, n)
val diff = GeziraSvg.difference(r.canvas.rgba, skia, 10.0)
val (worst, mean) = r.canvas.error(skia)
val readout = [
    "%d SVG elements, %d segments, read into %d FLines".format(doc.elements, doc.segments, doc.lines.size),
    r.toString(),
    "Gezira vs Skia per channel: max |diff| %.3f, mean %.4f".format(worst, mean),
].joinToString("\n")
println(readout)
markdown("readout", "```text\n$readout\n```")

// Three panels side by side: Gezira, Skia, and the difference times ten (white where they agree).
val gap = 12
val w = 3 * n + 2 * gap
val sheet = FloatArray(4 * w * n)
for ((k, img) in [r.canvas.rgba, skia, diff].withIndex()) {
    for (y in 0 until n) for (x in 0 until n) {
        val s = 4 * (y * n + x)
        val d = 4 * (y * w + k * (n + gap) + x)
        for (c in 0..3) sheet[d + c] = img[s + c]
    }
}
display("gezira | skia | difference x10", GeziraSvg.png(sheet, w, n))
````

## 3. flines through gezira

<!-- field-box id="gezira_fline_paint" index="2" -->

```edn [field-box id="gezira_fline_paint" role="properties"]
{"id" "gezira_fline_paint" "index" 2 "name" "flines through gezira" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "flines through gezira" "_tombstone" false "frame" #afield/rect [40.0 1200.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "gezira_fline_paint_code_.kt" "lines" 36 "characters" 1453 "sha256" "f9162f44dde0450735143ee6c5633b6df1cb327db078f0044ebe362ff8aa31ea"}]}
```

```kotlin [field-property box="gezira_fline_paint" name="code" path="gezira_fline_paint_code_.kt"]
import afield.graphics.nile.*

// Ordinary FLines, painted by their own color, filled, stroked, thickness and fillRule.
fun circle(cx: Double, cy: Double, r: Double): FLine {
    val k = 0.5522847498 * r
    return FLine()
        .moveTo(cx + r, cy)
        .cubicTo(cx + r, cy + k, 0.0, cx + k, cy + r, 0.0, cx, cy + r, 0.0)
        .cubicTo(cx - k, cy + r, 0.0, cx - r, cy + k, 0.0, cx - r, cy, 0.0)
        .cubicTo(cx - r, cy - k, 0.0, cx - k, cy - r, 0.0, cx, cy - r, 0.0)
        .cubicTo(cx + k, cy - r, 0.0, cx + r, cy - k, 0.0, cx + r, cy, 0.0)
        .close()
}
fun FLine.square(x: Double, y: Double, s: Double) =
    moveTo(x, y).lineTo(x + s, y).lineTo(x + s, y + s).lineTo(x, y + s).close()

val disc = circle(70.0, 60.0, 40.0)
disc.filled = true
disc.stroked = false
disc.color = vec(0.95, 0.55, 0.15, 1.0)
val ring = circle(70.0, 60.0, 40.0)
ring.thickness = 6.0
ring.color = vec(0.2, 0.15, 0.1, 1.0)
val frame = FLine().square(120.0, 20.0, 80.0).square(140.0, 40.0, 40.0)
frame.filled = true
frame.stroked = false
frame.fillRule = FillRule.EVEN_ODD
frame.color = vec(0.25, 0.5, 0.85, 0.8)
val wave = FLine().moveTo(20.0, 110.0).cubicTo(60.0, 70.0, 0.0, 100.0, 150.0, 0.0, 200.0, 110.0, 0.0)
wave.thickness = 3.0
wave.color = vec(0.8, 0.1, 0.3, 1.0)
val shapes = [disc, ring, frame, wave]
val r = GeziraFLine.render(shapes, 220, 130)
println(r)
display("gezira", GeziraSvg.png(r.canvas.rgba, 220, 130))
lines(shapes, channel = "field")
```

## 4. svg reader

<!-- field-box id="gezira_svg_reader" index="3" -->

```edn [field-box id="gezira_svg_reader" role="properties"]
{"id" "gezira_svg_reader" "index" 3 "name" "svg reader" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "svg reader" "_tombstone" false "frame" #afield/rect [40.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "gezira_svg_reader_code_.kt" "lines" 20 "characters" 899 "sha256" "239942722bafcaf859c66bccd7aca855daec88ed0e328883064e1d9a3420a985"}]}
```

```kotlin [field-property box="gezira_svg_reader" name="code" path="gezira_svg_reader_code_.kt"]
import afield.graphics.nile.*

// A group with a transform and inherited paint, an arc, and an even-odd path with a hole.
val svg =
    """
    <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 120 60">
      <g fill="#e8a33c" stroke="#3a2a1a" stroke-width="2" transform="translate(10 10)">
        <path d="M0 40 A20 20 0 0 1 40 40 Z"/>
        <path fill-rule="evenodd" fill="#4a7fd0" d="M50 0h40v40h-40z M60 10h20v20h-20z"/>
        <circle cx="20" cy="30" r="6" style="fill:white; stroke:none" opacity="0.8"/>
      </g>
    </svg>""".trimIndent()
val doc = GeziraSvg.parse(svg)
println(doc)
for (l in doc.lines) {
    val what = if (l.filled) "fill ${l.fillRule}" else "stroke ${l.thickness}"
    println("${l.svgId ?: "-"}: ${Gezira.subpaths(l).size} subpath(s), $what, colour ${l.color}")
}
val r = GeziraSvg.render(doc, 360, 180)
display("gezira", GeziraSvg.png(r.canvas.rgba, 360, 180))
```

