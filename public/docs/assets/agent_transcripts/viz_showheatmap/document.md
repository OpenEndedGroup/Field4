# Field Document: viz_showheatmap

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 4 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. spectrogram (magma)

<!-- field-box id="heatmap_spectrogram" index="0" -->

### Narrative

From `code`

A synthetic [frame][bin] magnitude spectrogram with a rising ridge. transpose=true puts
time on x and frequency on y; flipY keeps low frequency at the bottom; logScale gives a
dB-like view.

```edn [field-box id="heatmap_spectrogram" role="properties"]
{"id" "heatmap_spectrogram" "index" 0 "name" "spectrogram (magma)" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "spectrogram (magma)" "_tombstone" false "frame" #afield/rect [820.0 80.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "heatmap_spectrogram_code_.kt" "lines" 16 "characters" 596 "sha256" "558855dcb3ec5b9279153780d913b884e91e8072cd5d0a9772528e93681a2435"}]}
```

```kotlin [field-property box="heatmap_spectrogram" name="code" path="heatmap_spectrogram_code_.kt"]
/*
A synthetic [frame][bin] magnitude spectrogram with a rising ridge. transpose=true puts
time on x and frequency on y; flipY keeps low frequency at the bottom; logScale gives a
dB-like view.
*/
val frames = 120
val bins = 80
val spec =
    Array(frames) { f ->
        DoubleArray(bins) { bin ->
            val ridge = (bins * (0.15 + 0.7 * f / frames)).toInt()
            val d = (bin - ridge).toDouble()
            (0.001 + exp(-d * d / 12.0)) * (1.0 + 0.3 * sin(f * 0.3))
        }
    }
showHeatmap(spec, colormap = "magma", width = 900, transpose = true, flipY = true, logScale = true)
```

## 2. diverging (coolwarm)

<!-- field-box id="heatmap_diverging" index="1" -->

### Narrative

From `code`

A sin*sin field in [-1,1]. A fixed vmin/vmax with normalize=false anchors the coolwarm
white midpoint at zero, so blue = negative, red = positive.

```edn [field-box id="heatmap_diverging" role="properties"]
{"id" "heatmap_diverging" "index" 1 "name" "diverging (coolwarm)" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "diverging (coolwarm)" "_tombstone" false "frame" #afield/rect [80.0 500.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "heatmap_diverging_code_.kt" "lines" 7 "characters" 362 "sha256" "e565129367bcb90b9622ca5627c6bbd82cc775bb31159ffd26fdf537616cafe9"}]}
```

```kotlin [field-property box="heatmap_diverging" name="code" path="heatmap_diverging_code_.kt"]
// A sin*sin field in [-1,1]. A fixed vmin/vmax with normalize=false anchors the coolwarm
// white midpoint at zero, so blue = negative, red = positive.
val field =
    Array(48) { row ->
        DoubleArray(64) { col -> sin(col * 0.18) * sin(row * 0.22) }
    }
showHeatmap(field, colormap = "coolwarm", width = 640, vmin = -1.0, vmax = 1.0, normalize = false)
```

## 3. gaussian (viridis)

<!-- field-box id="heatmap_gaussian" index="2" -->

### Narrative

From `code`

A smooth 2-D Gaussian bump -> viridis heatmap. showHeatmap min-max normalizes,
bilinear-upsamples the coarse grid, and displays the PNG inline in one call.

```edn [field-box id="heatmap_gaussian" role="properties"]
{"id" "heatmap_gaussian" "index" 2 "name" "gaussian (viridis)" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "gaussian (viridis)" "_tombstone" false "frame" #afield/rect [80.0 80.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "heatmap_gaussian_code_.kt" "lines" 11 "characters" 414 "sha256" "33a8708263b45d97b25c9faa6981aaa7799b246abe8dbe02e7a6b8d4fe91094d"}]}
```

```kotlin [field-property box="heatmap_gaussian" name="code" path="heatmap_gaussian_code_.kt"]
// A smooth 2-D Gaussian bump -> viridis heatmap. showHeatmap min-max normalizes,
// bilinear-upsamples the coarse grid, and displays the PNG inline in one call.
val bump =
    Array(48) { row ->
        DoubleArray(64) { col ->
            val dx = (col - 32) / 16.0
            val dy = (row - 24) / 12.0
            exp(-(dx * dx + dy * dy))
        }
    }
showHeatmap(bump, colormap = "viridis", width = 640)
```

## 4. image (showImage)

<!-- field-box id="heatmap_image" index="3" -->

### Narrative

From `code`

showImage displays a ready-made ARGB IntArray directly (encode + display), for when you
already have a pixel buffer rather than a scalar field.

```edn [field-box id="heatmap_image" role="properties"]
{"id" "heatmap_image" "index" 3 "name" "image (showImage)" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "image (showImage)" "_tombstone" false "frame" #afield/rect [820.0 540.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "heatmap_image_code_.kt" "lines" 11 "characters" 394 "sha256" "2ae6e7f9138a07db2cc3a4dbc92f9139fe600aca4485d7eda96dabd0107f9607"}]}
```

```kotlin [field-property box="heatmap_image" name="code" path="heatmap_image_code_.kt"]
// showImage displays a ready-made ARGB IntArray directly (encode + display), for when you
// already have a pixel buffer rather than a scalar field.
val w = 512
val h = 256
val px = IntArray(w * h)
for (y in 0 until h) for (x in 0 until w) {
    val r = x * 255 / (w - 1)
    val b = y * 255 / (h - 1)
    px[x + y * w] = (0xFF shl 24) or (r shl 16) or (0x40 shl 8) or b
}
showImage(px, w, h)
```

