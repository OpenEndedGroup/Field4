# Field Document: splat_tiles

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["contents" "frame" "name" "code" "code_language" "box_kind" "textFrame" "textRunAnchor" "textRunSources" "textFrameReview" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "parentHint" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "watchdog" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_panel" "ai4_continuation" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "ai4_garden" "fileManagerAccessHistory" "kitRole"]}
```

## 1. 3 - naive against balanced

<!-- field-box id="splat_tiles_timing" index="0" -->

```edn [field-box id="splat_tiles_timing" role="properties"]
{"id" "splat_tiles_timing" "index" 0 "name" "3 - naive against balanced" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "3 - naive against balanced" "_tombstone" false "frame" #afield/rect [740.0 60.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "splat_tiles_timing_code_.kt" "lines" 16 "characters" 613 "sha256" "6d48cb000e4c5f2cf99a446c48677cd337788ea938954ae7e0f2a0a9d12b61c4"}]}
```

```kotlin [field-property box="splat_tiles_timing" name="code" path="splat_tiles_timing_code_.kt"]
val count = 200_000
val cloud = SplatTiles.clusteredCloud(count)
// A small image is 36 tiles for 10 workers, and the core's tiles hold most of the work:
// the case where whoever gets a heavy tile last holds up the frame.
val view = SplatRaster.View.lookAt(
    eye = Vector3f(0f, 0.3f, 2.6f),
    target = Vector3f(0f, 0f, 0f),
    width = 96,
    height = 96,
)
val binning = SplatTiles.bin(cloud, count, view)
println(binning)
for (cutoff in [1e-4f, 0f]) {
    println("transmittance cutoff $cutoff")
    for (row in SplatTiles.compare(binning, repeats = 9, transmittanceCutoff = cutoff)) println("  $row")
}
```

## 2. 2 - splats per tile

<!-- field-box id="splat_tiles_heat" index="1" -->

```edn [field-box id="splat_tiles_heat" role="properties"]
{"id" "splat_tiles_heat" "index" 1 "name" "2 - splats per tile" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2 - splats per tile" "_tombstone" false "frame" #afield/rect [400.0 60.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "splat_tiles_heat_code_.kt" "lines" 16 "characters" 635 "sha256" "f9900b8632db6ed934a0684c6e4ccf980e7432c8276d8ce5d6ed10a00b88ce7d"}]}
```

```kotlin [field-property box="splat_tiles_heat" name="code" path="splat_tiles_heat_code_.kt"]
val count = 40_000
val cloud = SplatTiles.clusteredCloud(count)
val view = SplatRaster.View.lookAt(
    eye = Vector3f(0f, 0f, 4.2f),
    target = Vector3f(0f, 0f, 0f),
    width = 480,
    height = 320,
)
val binning = SplatTiles.bin(cloud, count, view)

// One cell per 16x16 tile, row 0 at the top like the image. Log scale: the core is
// hundreds of times heavier than the halo.
showHeatmap(binning.countRows(), colormap = "inferno", width = 360, logScale = true, smooth = false, flipY = false)
println(
    "heaviest tile ${binning.maxCount} splats, 99th percentile ${binning.quantile(0.99)}, median ${binning.quantile(0.5)}",
)
```

## 3. 1 - binned against the reference

<!-- field-box id="splat_tiles_image" index="2" -->

### Narrative

From `code`

A dense core inside a sparse halo, in the renderer's 16-float layout.

```edn [field-box id="splat_tiles_image" role="properties"]
{"id" "splat_tiles_image" "index" 2 "name" "1 - binned against the reference" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1 - binned against the reference" "_tombstone" false "frame" #afield/rect [60.0 60.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "splat_tiles_image_code_.kt" "lines" 21 "characters" 748 "sha256" "7ce9a6073826e4f939bc1a66974405098dcec794dc493513dd22f0941db398d5"}]}
```

```kotlin [field-property box="splat_tiles_image" name="code" path="splat_tiles_image_code_.kt"]
// A dense core inside a sparse halo, in the renderer's 16-float layout.
val count = 40_000
val cloud = SplatTiles.clusteredCloud(count)
val view = SplatRaster.View.lookAt(
    eye = Vector3f(0f, 0f, 4.2f),
    target = Vector3f(0f, 0f, 0f),
    width = 480,
    height = 320,
)

val reference = SplatRaster.rasterize(cloud, count, view).image.toArgb()
val binning = SplatTiles.bin(cloud, count, view)
val tiled = SplatTiles.render(binning).image.toArgb()

var worst = 0
for (i in reference.indices) for (shift in [16, 8, 0]) {
    worst = max(worst, abs(((reference[i] shr shift) and 255) - ((tiled[i] shr shift) and 255)))
}
println(binning)
println("largest difference from SplatRaster: $worst of 255")
showImage(tiled, view.width, view.height)
```

