# Field Document: stippling

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. stipple — a sphere in 6000 dots

<!-- field-box id="stipple_hello" index="0" -->

### Narrative

From `code`

A shaded sphere on a white floor, as an Img: lit from up and to the left (y grows down).

```edn [field-box id="stipple_hello" role="properties"]
{"id" "stipple_hello" "index" 0 "name" "stipple — a sphere in 6000 dots" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "stipple — a sphere in 6000 dots" "_tombstone" false "frame" #afield/rect [80.0 80.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "stipple_hello_code_.kt" "lines" 20 "characters" 969 "sha256" "5930a0d0e9b1fc51ad50747c9e6b1512dc54c2958c2ef7828f68bfec03cd8d30"}]}
```

```kotlin [field-property box="stipple_hello" name="code" path="stipple_hello_code_.kt"]
// A shaded sphere on a white floor, as an Img: lit from up and to the left (y grows down).
val sphere =
    Img.generate(480, 360) { x, y ->
        val dx = (x - 220) / 140.0
        val dy = (y - 170) / 140.0
        val r2 = dx * dx + dy * dy
        val tone =
            if (r2 < 1.0) 0.1 + 0.9 * max(0.0, -0.45 * dx - 0.55 * dy + 0.7 * sqrt(1.0 - r2))
            else 1.0 - 0.6 * exp(-2.0 * ((x - 280) / 150.0).pow(2) - 2.0 * ((y - 318) / 30.0).pow(2))
        tone.toFloat()
    }

// 6000 dots wherever it is dark (`invert`, as ink on paper), evenly spaced rather than clumped.
val dots = sphere.stipple(6000, invert = true)
showImg(stipplesToImg(dots, sphere.width, sphere.height))

// The same dots as geometry, for a plotter or an SVG: lines(stipplesToFLine(dots, size = 0.4))

// Try this: 2000 or 20000 dots; `densityPower = 2f` for harder contrast (point density goes
// as tone^densityPower); drop `invert` to stipple the light instead of the shadow.
```

## 2. Stippler — watching Lloyd relaxation settle

<!-- field-box id="stipple_relaxing" index="1" -->

### Narrative

From `code`

Launch it (Alt-R). The same sphere as `stipple_hello`.

```edn [field-box id="stipple_relaxing" role="properties"]
{"id" "stipple_relaxing" "index" 1 "name" "Stippler — watching Lloyd relaxation settle" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Stippler — watching Lloyd relaxation settle" "_tombstone" false "frame" #afield/rect [80.0 640.0 370.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "stipple_relaxing_code_.kt" "lines" 28 "characters" 1124 "sha256" "16dd6e5760dd9c67d8255734a1e912f4f5d1796a39298788459b99a870ce69f5"}]}
```

```kotlin [field-property box="stipple_relaxing" name="code" path="stipple_relaxing_code_.kt"]
// Launch it (Alt-R). The same sphere as `stipple_hello`.
val sphere =
    Img.generate(480, 360) { x, y ->
        val dx = (x - 220) / 140.0
        val dy = (y - 170) / 140.0
        val r2 = dx * dx + dy * dy
        val tone =
            if (r2 < 1.0) 0.1 + 0.9 * max(0.0, -0.45 * dx - 0.55 * dy + 0.7 * sqrt(1.0 - r2))
            else 1.0 - 0.6 * exp(-2.0 * ((x - 280) / 150.0).pow(2) - 2.0 * ((y - 318) / 30.0).pow(2))
        tone.toFloat()
    }

/*
Where the stippler starts: one dot per equal share of the darkness along a Hilbert curve,
so every region already has the right number of dots. Their spacing is rough, and evening
it out is what relaxation is for.
*/
val s = Stippler(sphere, 6000, invert = true)
showImg(stipplesToImg(s.points(), 480, 360), channel = "start")

// One Lloyd step per frame, until no dot moves.
val moved = mutableListOf<Double>()
loop {
    if (moved.lastOrNull() == 0.0) return@loop
    moved += s.step()
    showImg(stipplesToImg(s.points(), 480, 360), channel = "relaxed")
    showSignals("mean movement (px)" to moved, title = "Lloyd step ${s.iterations}", xTitle = "step")
}
```

