# Field Document: kmeans_emeans

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 4 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_panel" "ai4_continuation" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
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

## 2. k-means tree of centroid paths

<!-- field-box id="kmeans_tree" index="1" -->

### Narrative

From `code`

1 000 points scattered over a square, clustered into n/2, then those centroids into n/4, ... down to one.

```edn [field-box id="kmeans_tree" role="properties"]
{"id" "kmeans_tree" "index" 1 "name" "k-means tree of centroid paths" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "k-means tree of centroid paths" "_tombstone" false "frame" #afield/rect [80.0 2320.0 320.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "kmeans_tree_code_.kt" "lines" 15 "characters" 797 "sha256" "738a35c55fb9d655802459aaf2a479907fa4ff4437b5b6cb2052c37ed71b6642"}]}
```

```kotlin [field-property box="kmeans_tree" name="code" path="kmeans_tree_code_.kt"]
// 1 000 points scattered over a square, clustered into n/2, then those centroids into n/4, ... down to one.
val rng = java.util.Random(40)
val points = List(1_000) { vec(rng.nextDouble() * 10, rng.nextDouble() * 10) }
val options = KMeansOptions(epochs = 4, randomSeed = 7L, stepScale = 0.3, closeOnMean = false)
val halves = vec2KMeansTree(points, shrink = 2.0, options = options)
val thirds = vec2KMeansTree(points, shrink = 3.0, options = options)

showPlaneGrid(
    "shrink = 2\n${halves.levels.joinToString(" → ") { "${it.result.k}" }}" to halves.toFLines(leafSize = 0.8),
    "shrink = 3\n${thirds.levels.joinToString(" → ") { "${it.result.k}" }}" to thirds.toFLines(leafSize = 0.8),
    columns = 2,
    cellWidth = 420,
    cellHeight = 420,
    window = Rect(-0.4, -0.4, 10.8, 10.8),
)
```

## 3. Centroid trails on an epoch slider

<!-- field-box id="kmeans_epoch_slider" index="2" -->

### Narrative

From `code`

The centroid-trails cloud, on a slider: drag through the epochs and watch Lloyd crawl while e-means jumps.

```edn [field-box id="kmeans_epoch_slider" role="properties"]
{"id" "kmeans_epoch_slider" "index" 2 "name" "Centroid trails on an epoch slider" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Centroid trails on an epoch slider" "_tombstone" false "frame" #afield/rect [80.0 1200.0 320.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "kmeans_epoch_slider_code_.kt" "lines" 42 "characters" 1934 "sha256" "551be1684200d96ec9ccd4137db27f31b9b254a47f6cae6a60a13f18ccbbb1e7"}]}
```

```kotlin [field-property box="kmeans_epoch_slider" name="code" path="kmeans_epoch_slider_code_.kt"]
// The centroid-trails cloud, on a slider: drag through the epochs and watch Lloyd crawl while e-means jumps.
val rng = java.util.Random(12)
val centres = List(30) { vec(rng.nextDouble() * 10, rng.nextDouble() * 10) }
val points =
    List(12_000) { i ->
        val c = i % 30
        vec(centres[c].x + rng.nextGaussian() * 0.25, centres[c].y + rng.nextGaussian() * 0.25)
    }
val seeds = KMeansOptions(seeding = KMeansSeeding.RANDOM, randomSeed = 2L, recordCentroids = true)
val lloyd = vec2KMeans(points, 30, seeds.copy(algorithm = KMeansAlgorithm.LLOYD, epochs = 25))
val emeans = vec2KMeans(points, 30, seeds.copy(epochs = 3))
val cloud =
    pointCloudFLine(points.filterIndexed { i, _ -> i % 16 == 0 }, color = vec(0.55, 0.65, 0.78, 0.35), pointSize = 0.8)

// The trails up to epoch e, and the centroids at e; a run that has finished stays where it ended.
fun upTo(r: KMeansResult<Vec2>, e: Int): List<FLine> {
    val last = minOf(e, r.centroidHistory.size - 1)
    val trails =
        (0 until r.k).map { c ->
            polylineFrom(r.centroidHistory.take(last + 1).map { it[c] }).also {
                it.color = vec(1.0, 0.85, 0.45, 0.9)
                it.thickness = 1.2
            }
        }
    return listOf(cloud) +
        trails +
        pointCloudFLine(r.centroidHistory[last], color = vec(1.0, 1.0, 1.0, 0.95), pointSize = 2.2)
}

fun show(e: Int) = showPlaneGrid(
    "Lloyd, epoch ${minOf(e, lloyd.epochs)} of ${lloyd.epochs}\nMSE %.4f"
        .format(lloyd.mseHistory[minOf(e, lloyd.epochs)]) to upTo(lloyd, e),
    "e-means, epoch ${minOf(e, emeans.epochs)} of ${emeans.epochs}\nMSE %.4f"
        .format(emeans.mseHistory[minOf(e, emeans.epochs)]) to upTo(emeans, e),
    columns = 2,
    cellWidth = 420,
    cellHeight = 420,
    window = Rect(-0.8, -0.8, 11.6, 11.6),
)
slider("epoch", min = 0.0, max = 25.0, step = 1.0, initialValue = 1.0) { show(it.toInt()) }
displayOpen("controls")
show(1)
```

## 4. e-means revival

<!-- field-box id="kmeans_revival" index="3" -->

### Narrative

From `code`

40 blobs of 400 points, and 12 strays on a circle far outside them.

```edn [field-box id="kmeans_revival" role="properties"]
{"id" "kmeans_revival" "index" 3 "name" "e-means revival" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "e-means revival" "_tombstone" false "frame" #afield/rect [80.0 640.0 320.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "kmeans_revival_code_.kt" "lines" 40 "characters" 1630 "sha256" "8f08f8365c68aeba8e8b36e6922c7fed8f2bf1a54701bcb45ffdc1e36b37ddbe"}]}
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

// Plain k-means++ seeds favour far points, so several land on strays. Revival is off by default in 2-D.
val options = KMeansOptions(epochs = 3, seeding = KMeansSeeding.PLUS_PLUS, randomSeed = 5L)
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

