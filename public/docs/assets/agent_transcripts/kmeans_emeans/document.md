# Field Document: kmeans_emeans

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_panel" "ai4_continuation" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. Lloyd vs e-means

<!-- field-box id="kmeans_lloyd_vs_emeans" index="0" -->

### Narrative

From `code`

60 Gaussian blobs of uneven width, 800 points each: 48 000 points.

```edn [field-box id="kmeans_lloyd_vs_emeans" role="properties"]
{"id" "kmeans_lloyd_vs_emeans" "index" 0 "name" "Lloyd vs e-means" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Lloyd vs e-means" "_tombstone" false "frame" #afield/rect [80.0 80.0 320.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "kmeans_lloyd_vs_emeans_code_.kt" "lines" 57 "characters" 2421 "sha256" "be0d956d74d10c3663f835861b84c514dac31263d8fa230ffc0581c13a002177"}]}
```

```kotlin [field-property box="kmeans_lloyd_vs_emeans" name="code" path="kmeans_lloyd_vs_emeans_code_.kt"]
// 60 Gaussian blobs of uneven width, 800 points each: 48 000 points.
val rng = java.util.Random(4)
val centres = List(60) { vec(rng.nextDouble() * 10, rng.nextDouble() * 10) }
val widths = List(60) { 0.08 + rng.nextDouble() * 0.22 }
val points =
    List(48_000) { i ->
        val c = i % 60
        vec(centres[c].x + rng.nextGaussian() * widths[c], centres[c].y + rng.nextGaussian() * widths[c])
    }
val window = Rect(-0.6, -0.6, 11.2, 11.2)
// Same random seeds for both, so the only difference is the algorithm.
val seeds = KMeansOptions(seeding = KMeansSeeding.RANDOM, randomSeed = 3L)
val lloyd = vec2KMeans(points, 60, seeds.copy(algorithm = KMeansAlgorithm.LLOYD, epochs = 25))
val emeans = vec2KMeans(points, 60, seeds.copy(algorithm = KMeansAlgorithm.EMEANS, epochs = 3))

// Draw one point in thirteen (coprime with 60, so every blob shows): the clustering saw all 48 000.
val shown = points.indices.filter { it % 13 == 0 }
fun drawn(r: KMeansResult<Vec2>) =
    ClusterLayer("shown", IntArray(shown.size) { r.labels[shown[it]] })
        .toFLines(
            shown.map { points[it] },
            centers = r.centroids,
            drawSpokes = false,
            pointSize = 0.9,
            centerSize = 1.6,
        )

// MSE per epoch on a log axis, laid out inside the same window as the clouds.
val lo = ln(0.05)
val hi = ln(maxOf(lloyd.mseHistory.max(), emeans.mseHistory.max()))
fun curve(history: DoubleArray, color: Vec4) = polylineFrom(
    history.indices.map { e -> vec(0.4 + e / 25.0 * 9.2, 9.6 - (ln(history[e]) - lo) / (hi - lo) * 9.2) },
    0,
    closed = false,
).also {
    it.color = color
    it.thickness = 2.0
    it.pointed = true
    it.pointSize = 2.4
}
val axes = Rect(0.0, 0.0, 10.0, 10.0).toFLine(color = vec(0.35, 0.44, 0.56, 0.55), thickness = 1.0)

showPlaneGrid(
    "Lloyd, 25 epochs\nMSE %.4f, converged: ${lloyd.converged}".format(lloyd.mse) to drawn(lloyd),
    "e-means, 3 epochs\nMSE %.4f, batch ${emeans.batchSize}, alpha0 %.3f".format(emeans.mse, emeans.alpha0) to
        drawn(emeans),
    "MSE per epoch, log scale\norange Lloyd, blue e-means; e-means 1 epoch %.4f".format(emeans.mseHistory[1]) to
        listOf(
            axes,
            curve(lloyd.mseHistory, vec(1.0, 0.62, 0.30, 0.95)),
            curve(emeans.mseHistory, vec(0.45, 0.80, 1.0, 0.95)),
        ),
    columns = 3,
    cellWidth = 340,
    cellHeight = 340,
    window = window,
)
```

## 2. e-means revival

<!-- field-box id="kmeans_revival" index="1" -->

### Narrative

From `code`

40 blobs of 400 points, and 12 strays on a circle far outside them.

```edn [field-box id="kmeans_revival" role="properties"]
{"id" "kmeans_revival" "index" 1 "name" "e-means revival" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "e-means revival" "_tombstone" false "frame" #afield/rect [80.0 640.0 320.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "kmeans_revival_code_.kt" "lines" 40 "characters" 1589 "sha256" "7b471e5096c8147dd2d52c7ac8c3ec4a53b19e310160166d58fc7b1ac793f026"}]}
```

```kotlin [field-property box="kmeans_revival" name="code" path="kmeans_revival_code_.kt"]
// 40 blobs of 400 points, and 12 strays on a circle far outside them.
val rng = java.util.Random(8)
val centres = List(40) { vec(rng.nextDouble() * 10, rng.nextDouble() * 10) }
val blobs =
    List(16_000) { i ->
        val c = i % 40
        vec(centres[c].x + rng.nextGaussian() * 0.2, centres[c].y + rng.nextGaussian() * 0.2)
    }
val strays = List(12) { j -> vec(5.0 + 9.0 * cos(j * PI / 6), 5.0 + 9.0 * sin(j * PI / 6)) }
val points = buildList {
    addAll(blobs)
    addAll(strays)
}
val window = Rect(-4.6, -4.6, 19.2, 19.2)

// k-means++ seeds favour far points, so several land on strays. Revival is off by default in 2-D.
val options = KMeansOptions(epochs = 3, randomSeed = 5L)
val off = vec2KMeans(points, 40, options.copy(reviveThreshold = 0.0))
val on = vec2KMeans(points, 40, options.copy(reviveThreshold = 0.1))
fun dead(r: KMeansResult<Vec2>) = r.clusterSizes.count { it < 10 }

val shown = points.indices.filter { it % 7 == 0 || it >= blobs.size }
fun drawn(r: KMeansResult<Vec2>) =
    ClusterLayer("shown", IntArray(shown.size) { r.labels[shown[it]] })
        .toFLines(
            shown.map { points[it] },
            centers = r.centroids,
            drawSpokes = false,
            pointSize = 1.0,
            centerSize = 1.6,
        )

showPlaneGrid(
    "e-means, revival off\n${dead(off)} clusters under 10 members, MSE %.3f".format(off.mse) to drawn(off),
    "e-means, revival on\n${dead(on)} under 10, ${on.revivals} revivals, MSE %.3f".format(on.mse) to drawn(on),
    columns = 2,
    cellWidth = 400,
    cellHeight = 400,
    window = window,
)
```

