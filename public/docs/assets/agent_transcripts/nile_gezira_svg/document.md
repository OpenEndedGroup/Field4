# Field Document: nile_gezira_svg

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["contents" "frame" "name" "code" "code_language" "box_kind" "textFrame" "textRunAnchor" "textRunSources" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "parentHint" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_panel" "ai4_continuation" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "ai4_garden" "fileManagerAccessHistory" "kitRole"]}
```

## 1. tiger as flines

<!-- field-box id="gezira_svg_flines" index="0" -->

```edn [field-box id="gezira_svg_flines" role="properties"]
{"id" "gezira_svg_flines" "index" 0 "name" "tiger as flines" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "tiger as flines" "_tombstone" false "frame" #afield/rect [1900.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "gezira_svg_flines_code_.kt" "lines" 7 "characters" 311 "sha256" "b56bc044e0b0e85c80b169507e9d6b26b6ede9ca74e5fc8ff627ecff3cb3ccff"}]}
```

```kotlin [field-property box="gezira_svg_flines" name="code" path="gezira_svg_flines_code_.kt"]
import afield.graphics.nile.*

// The same document as FLines, for anything in Field that draws them.
val doc = GeziraSvg.parse(GeziraSvg.tiger())
val outlines = GeziraSvg.flines(doc, SvgTransform.scale(0.5))
println("${outlines.size} FLines from ${doc.shapes.size} shapes")
lines(outlines, channel = "flines")
```

## 2. tiger beside skia

<!-- field-box id="gezira_svg_tiger" index="1" -->

```edn [field-box id="gezira_svg_tiger" role="properties"]
{"id" "gezira_svg_tiger" "index" 1 "name" "tiger beside skia" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "tiger beside skia" "_tombstone" false "frame" #afield/rect [760.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "gezira_svg_tiger_code_.kt" "lines" 30 "characters" 1117 "sha256" "0934663db88218f071332b753ad69fa579cf0f07bdc041c9ec576903154d6341"}]}
```

```kotlin [field-property box="gezira_svg_tiger" name="code" path="gezira_svg_tiger_code_.kt"]
import afield.graphics.nile.*

// The Ghostscript tiger through Gezira's kernels, then through Skia's SVG renderer.
val n = 300
val text = GeziraSvg.tiger()
val doc = GeziraSvg.parse(text)
val r = GeziraSvg.render(doc, n, n)
val skia = GeziraSvg.skia(text, n, n)
val diff = GeziraSvg.difference(r.canvas.rgba, skia, 10.0)
val (worst, mean) = r.canvas.error(skia)
val readout = listOf(
    doc.toString(),
    r.toString(),
    "Gezira vs Skia per channel: max |diff| %.3f, mean %.4f".format(worst, mean),
).joinToString("\n")
println(readout)
display("readout", "<pre style='margin:0; white-space:pre-wrap'>$readout</pre>")

// Three panels side by side: Gezira, Skia, and the difference times ten (white where they agree).
val gap = 12
val w = 3 * n + 2 * gap
val sheet = FloatArray(4 * w * n)
for ((k, img) in listOf(r.canvas.rgba, skia, diff).withIndex()) {
    for (y in 0 until n) for (x in 0 until n) {
        val s = 4 * (y * n + x)
        val d = 4 * (y * w + k * (n + gap) + x)
        for (c in 0..3) sheet[d + c] = img[s + c]
    }
}
display("gezira | skia | difference x10", GeziraSvg.png(sheet, w, n))
```

## 3. svg reader

<!-- field-box id="gezira_svg_reader" index="2" -->

```edn [field-box id="gezira_svg_reader" role="properties"]
{"id" "gezira_svg_reader" "index" 2 "name" "svg reader" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "svg reader" "_tombstone" false "frame" #afield/rect [40.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "gezira_svg_reader_code_.kt" "lines" 21 "characters" 915 "sha256" "08cdc109b75ed5527c68f5196430e9a22b8b7138e1ccf136caf0a0f3ef3ea701"}]}
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
for (s in doc.shapes) {
    println(
        "${s.element}: ${s.subpaths.size} subpath(s), ${s.subpaths.sumOf { it.beziers.size }} segments, fill ${s.fill}, ${s.fillRule}, stroke ${s.stroke} ${s.strokeWidth}",
    )
}
val r = GeziraSvg.render(doc, 360, 180)
display("gezira", GeziraSvg.png(r.canvas.rgba, 360, 180))
```

