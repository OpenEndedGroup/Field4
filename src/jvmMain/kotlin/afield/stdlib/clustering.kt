@file:Suppress("MemberVisibilityCanBePrivate")

package afield.stdlib

import afield.graphics.FLine
import smile.clustering.Clustering
import smile.clustering.HierarchicalClustering
import smile.clustering.KMedoids
import smile.clustering.linkage.*
import smile.math.distance.Distance
import smile.math.distance.ManhattanDistance
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

enum class AgglomerativeLinkage {
    SINGLE,
    COMPLETE,
    AVERAGE,
    WEIGHTED_AVERAGE,
    CENTROID,
    MEDIAN,
    WARD
}

enum class MedoidDistance {
    EUCLIDEAN,
    MANHATTAN
}

data class KMedoidsOptions(
    val maxIter: Int = 3,
    val neighborRatio: Double = 0.0125,
    val runs: Int = 4,
    val distance: MedoidDistance = MedoidDistance.MANHATTAN
)

data class KMediansOptions(
    val maxIterations: Int = 100,
    val tolerance: Double = 1e-4,
    val runs: Int = 6,
    val randomSeed: Long? = null
)

class ClusterLayer(
    val name: String,
    labels: IntArray
) {
    val labels: IntArray = labels.copyOf()

    init {
        require(this.labels.isNotEmpty()) { "ClusterLayer.labels must not be empty" }
        this.labels.forEachIndexed { i, label ->
            require(label >= 0) { "ClusterLayer.labels[$i] must be >= 0, found $label" }
        }
    }

    val sampleCount: Int
        get() = labels.size

    val clusterCount: Int
        get() = (labels.maxOrNull() ?: -1) + 1

    fun indices(cluster: Int): IntArray {
        require(cluster >= 0) { "cluster must be >= 0, found $cluster" }
        val out = ArrayList<Int>()
        for (i in labels.indices) if (labels[i] == cluster) out.add(i)
        return out.toIntArray()
    }
}

class AgglomerativeClustering internal constructor(
    val linkage: AgglomerativeLinkage,
    rawModel: HierarchicalClustering
) {
    private val model = HierarchicalClustering(
        rawModel.tree().map { it.copyOf() }.toTypedArray(),
        rawModel.height().copyOf()
    )

    val sampleCount: Int
        get() = model.tree().size + 1

    val tree: Array<IntArray>
        get() = model.tree().map { it.copyOf() }.toTypedArray()

    val heights: DoubleArray
        get() = model.height().copyOf()

    fun partition(k: Int, name: String = "k=$k"): ClusterLayer {
        require(k in 1..sampleCount) {
            "AgglomerativeClustering.partition: k must be in [1, $sampleCount], found $k"
        }
        val labels = if (k == 1) {
            IntArray(sampleCount)
        } else {
            model.partition(k)
        }
        return ClusterLayer(name, canonicalizeLabels(labels))
    }

    fun partitionByHeight(height: Double, name: String = "h=$height"): ClusterLayer {
        require(height.isFinite()) {
            "AgglomerativeClustering.partitionByHeight: height must be finite, found $height"
        }
        val labels = model.partition(height)
        return ClusterLayer(name, canonicalizeLabels(labels))
    }

    fun layers(kValues: Iterable<Int>): List<ClusterLayer> {
        val used = LinkedHashSet<Int>()
        val out = ArrayList<ClusterLayer>()
        for (k in kValues) {
            if (k !in 1..sampleCount) continue
            if (used.add(k)) out += partition(k, "k=$k")
        }
        return out
    }

    fun defaultLayers(maxK: Int = 6): List<ClusterLayer> {
        val capped = max(2, min(maxK, sampleCount))
        return layers(2..capped)
    }
}

data class KMedoidsResult<T>(
    val labels: IntArray,
    val medoids: List<T>,
    val medoidIndices: IntArray,
    val distortion: Double,
    val clusterSizes: IntArray,
    val options: KMedoidsOptions
) {
    init {
        require(labels.isNotEmpty()) { "KMedoidsResult.labels must not be empty" }
        require(medoids.isNotEmpty()) { "KMedoidsResult.medoids must not be empty" }
        require(medoidIndices.size == medoids.size) {
            "KMedoidsResult: medoidIndices.size (${medoidIndices.size}) must match medoids.size (${medoids.size})"
        }
    }

    val k: Int
        get() = medoids.size

    fun asLayer(name: String = "kmedoids(k=$k)"): ClusterLayer =
        ClusterLayer(name, labels)
}

data class KMediansResult<T>(
    val labels: IntArray,
    val centers: List<T>,
    val objective: Double,
    val iterations: Int,
    val converged: Boolean,
    val clusterSizes: IntArray,
    val options: KMediansOptions
) {
    init {
        require(labels.isNotEmpty()) { "KMediansResult.labels must not be empty" }
        require(centers.isNotEmpty()) { "KMediansResult.centers must not be empty" }
    }

    val k: Int
        get() = centers.size

    fun asLayer(name: String = "kmedians(k=$k)"): ClusterLayer =
        ClusterLayer(name, labels)
}

data class ClusterLayerVizOptions(
    val layerSpacing: Float = 0.7f,
    val clusterOffset: Float = 0.0f,
    val pointSize: Double = 0.1,
    val pointHaloSize: Double = 0.3,
    val centerPointSize: Double = 0.14,
    val centerHaloSize: Double = 22.0,
    val linkThickness: Double = 0.85,
    val pointAlpha: Float = 0.92f,
    val haloAlpha: Float = 0.14f,
    val centerAlpha: Float = 0.98f,
    val linkAlpha: Float = 0.16f,
    val drawLinks: Boolean = true,
    val drawCenters: Boolean = true,
    val drawGuides: Boolean = true
)

fun floatAgglomerative(
    values: List<Float>,
    linkage: AgglomerativeLinkage = AgglomerativeLinkage.WARD
): AgglomerativeClustering {
    val rows = floatRows(values, "floatAgglomerative")
    return agglomerative(rows, linkage, "floatAgglomerative")
}

fun vec2Agglomerative(
    points: List<Vec2>,
    linkage: AgglomerativeLinkage = AgglomerativeLinkage.WARD
): AgglomerativeClustering {
    val rows = vec2Rows(points, "vec2Agglomerative")
    return agglomerative(rows, linkage, "vec2Agglomerative")
}

fun vec3Agglomerative(
    points: List<Vec3>,
    linkage: AgglomerativeLinkage = AgglomerativeLinkage.WARD
): AgglomerativeClustering {
    val rows = vec3Rows(points, "vec3Agglomerative")
    return agglomerative(rows, linkage, "vec3Agglomerative")
}

fun vec4Agglomerative(
    points: List<Vec4>,
    linkage: AgglomerativeLinkage = AgglomerativeLinkage.WARD
): AgglomerativeClustering {
    val rows = vec4Rows(points, "vec4Agglomerative")
    return agglomerative(rows, linkage, "vec4Agglomerative")
}

fun floatKMedoids(
    values: List<Float>,
    k: Int,
    options: KMedoidsOptions = KMedoidsOptions()
): KMedoidsResult<Float> {
    val rows = floatRows(values, "floatKMedoids")
    val core = kMedoids(rows, k, options, "floatKMedoids")
    val medoids = core.medoidIndices.map { values[it] }
    return KMedoidsResult(
        labels = core.labels,
        medoids = medoids,
        medoidIndices = core.medoidIndices,
        distortion = core.distortion,
        clusterSizes = core.clusterSizes,
        options = options
    )
}

fun vec2KMedoids(
    points: List<Vec2>,
    k: Int,
    options: KMedoidsOptions = KMedoidsOptions()
): KMedoidsResult<Vec2> {
    val rows = vec2Rows(points, "vec2KMedoids")
    val core = kMedoids(rows, k, options, "vec2KMedoids")
    val medoids = core.medoidIndices.map { Vec2(points[it]) }
    return KMedoidsResult(
        labels = core.labels,
        medoids = medoids,
        medoidIndices = core.medoidIndices,
        distortion = core.distortion,
        clusterSizes = core.clusterSizes,
        options = options
    )
}

fun vec3KMedoids(
    points: List<Vec3>,
    k: Int,
    options: KMedoidsOptions = KMedoidsOptions()
): KMedoidsResult<Vec3> {
    val rows = vec3Rows(points, "vec3KMedoids")
    val core = kMedoids(rows, k, options, "vec3KMedoids")
    val medoids = core.medoidIndices.map { Vec3(points[it]) }
    return KMedoidsResult(
        labels = core.labels,
        medoids = medoids,
        medoidIndices = core.medoidIndices,
        distortion = core.distortion,
        clusterSizes = core.clusterSizes,
        options = options
    )
}

fun vec4KMedoids(
    points: List<Vec4>,
    k: Int,
    options: KMedoidsOptions = KMedoidsOptions()
): KMedoidsResult<Vec4> {
    val rows = vec4Rows(points, "vec4KMedoids")
    val core = kMedoids(rows, k, options, "vec4KMedoids")
    val medoids = core.medoidIndices.map { Vec4(points[it]) }
    return KMedoidsResult(
        labels = core.labels,
        medoids = medoids,
        medoidIndices = core.medoidIndices,
        distortion = core.distortion,
        clusterSizes = core.clusterSizes,
        options = options
    )
}

fun floatKMedians(
    values: List<Float>,
    k: Int,
    options: KMediansOptions = KMediansOptions()
): KMediansResult<Float> {
    val rows = floatRows(values, "floatKMedians")
    val core = kMedians(rows, k, options, "floatKMedians")
    val centers = core.centers.map { it[0].toFloat() }
    return KMediansResult(
        labels = core.labels,
        centers = centers,
        objective = core.objective,
        iterations = core.iterations,
        converged = core.converged,
        clusterSizes = core.clusterSizes,
        options = options
    )
}

fun vec2KMedians(
    points: List<Vec2>,
    k: Int,
    options: KMediansOptions = KMediansOptions()
): KMediansResult<Vec2> {
    val rows = vec2Rows(points, "vec2KMedians")
    val core = kMedians(rows, k, options, "vec2KMedians")
    val centers = core.centers.map { v -> vec(v[0], v[1]) }
    return KMediansResult(
        labels = core.labels,
        centers = centers,
        objective = core.objective,
        iterations = core.iterations,
        converged = core.converged,
        clusterSizes = core.clusterSizes,
        options = options
    )
}

fun vec3KMedians(
    points: List<Vec3>,
    k: Int,
    options: KMediansOptions = KMediansOptions()
): KMediansResult<Vec3> {
    val rows = vec3Rows(points, "vec3KMedians")
    val core = kMedians(rows, k, options, "vec3KMedians")
    val centers = core.centers.map { v -> vec(v[0], v[1], v[2]) }
    return KMediansResult(
        labels = core.labels,
        centers = centers,
        objective = core.objective,
        iterations = core.iterations,
        converged = core.converged,
        clusterSizes = core.clusterSizes,
        options = options
    )
}

fun vec4KMedians(
    points: List<Vec4>,
    k: Int,
    options: KMediansOptions = KMediansOptions()
): KMediansResult<Vec4> {
    val rows = vec4Rows(points, "vec4KMedians")
    val core = kMedians(rows, k, options, "vec4KMedians")
    val centers = core.centers.map { v -> vec(v[0], v[1], v[2], v[3]) }
    return KMediansResult(
        labels = core.labels,
        centers = centers,
        objective = core.objective,
        iterations = core.iterations,
        converged = core.converged,
        clusterSizes = core.clusterSizes,
        options = options
    )
}

fun visualizeVec3ClusterLayers3D(
    points: List<Vec3>,
    layers: List<ClusterLayer>,
    options: ClusterLayerVizOptions = ClusterLayerVizOptions()
): Map<String, List<FLine>> {
    require(points.isNotEmpty()) { "visualizeVec3ClusterLayers3D: points must not be empty" }
    val checkedLayers = validateLayers(points.size, layers, "visualizeVec3ClusterLayers3D")
    val center = points.center()
    val bounds = bounds3(points)

    val pointHalo = pointCloudLine(options.pointHaloSize)
    val pointCore = pointCloudLine(options.pointSize)
    val centerHalo = pointCloudLine(options.centerHaloSize)
    val centerCore = pointCloudLine(options.centerPointSize)
    val links = edgeLine(options.linkThickness)

    val layerCount = checkedLayers.size
    checkedLayers.forEachIndexed { li, layer ->
        val k = max(1, layer.clusterCount)
        val palette = okhslSet(k, saturation = 0.78, lightness = 0.60, alpha = 1f, startHue = li * 0.11f)
        val tLayer = if (layerCount <= 1) 1f else li.toFloat() / (layerCount - 1).toFloat()
        val positioned = ArrayList<Vec3>(points.size)

        for (i in points.indices) {
            val p = points[i]
            val radial = Vec3(p).sub(center)
            if (radial.lengthSquared() < 1e-9f) {
                radial.set(
                    ((i * 0.7548777f + li * 0.173f) % 1f) - 0.5f,
                    ((i * 0.5698403f + li * 0.113f) % 1f) - 0.5f,
                    ((i * 0.4389231f + li * 0.071f) % 1f) - 0.5f
                )
            }
            radial.normalize().mul(li * options.layerSpacing * 0.08f)
            positioned += Vec3(p).add(radial)
        }

        val centers = clusterCentroids(positioned, layer.labels)
        for (i in points.indices) {
            val cluster = layer.labels[i]
            val clusterColor = palette[cluster % palette.size]
            val coordColor = coordinateColor(points[i], bounds, alpha = 1f)
            val color = mixColor(coordColor, clusterColor, 0.35f + 0.55f * tLayer, options.pointAlpha)
            val glow = withAlpha(color, options.haloAlpha)
            pointHalo.addPoint(positioned[i], glow)
            pointCore.addPoint(positioned[i], color)

            if (options.drawLinks) {
                val c = centers[cluster]
                if (c != null) links.addSegment(positioned[i], c, withAlpha(clusterColor, options.linkAlpha))
            }
        }

        if (options.drawCenters) {
            for (cluster in centers.indices) {
                val c = centers[cluster] ?: continue
                val clusterColor = palette[cluster % palette.size]
                val bright = mixColor(clusterColor, vec(1f, 1f, 1f, 1f), 0.22f, options.centerAlpha)
                centerHalo.addPoint(c, withAlpha(clusterColor, options.haloAlpha * 1.25f))
                centerCore.addPoint(c, bright)
            }
        }
    }

    return clusteredLineMap(
        pointHalo = pointHalo,
        pointCore = pointCore,
        centerHalo = centerHalo,
        centerCore = centerCore,
        links = links,
        guides = null
    )
}

fun visualizeVec2ClusterLayers3D(
    points: List<Vec2>,
    layers: List<ClusterLayer>,
    options: ClusterLayerVizOptions = ClusterLayerVizOptions()
): Map<String, List<FLine>> {
    require(points.isNotEmpty()) { "visualizeVec2ClusterLayers3D: points must not be empty" }
    val checkedLayers = validateLayers(points.size, layers, "visualizeVec2ClusterLayers3D")

    val pointHalo = pointCloudLine(options.pointHaloSize)
    val pointCore = pointCloudLine(options.pointSize)
    val centerHalo = pointCloudLine(options.centerHaloSize)
    val centerCore = pointCloudLine(options.centerPointSize)
    val links = edgeLine(options.linkThickness)
    val guides = if (options.drawGuides) guideLine() else null

    var minX = Float.POSITIVE_INFINITY
    var maxX = Float.NEGATIVE_INFINITY
    var minY = Float.POSITIVE_INFINITY
    var maxY = Float.NEGATIVE_INFINITY
    points.forEach { p ->
        if (p.x < minX) minX = p.x
        if (p.x > maxX) maxX = p.x
        if (p.y < minY) minY = p.y
        if (p.y > maxY) maxY = p.y
    }

    checkedLayers.forEachIndexed { li, layer ->
        val k = max(1, layer.clusterCount)
        val palette = okhslSet(k, saturation = 0.76, lightness = 0.63, alpha = 1f, startHue = li * 0.09f)
        val zBase = li * options.layerSpacing
        val positioned = ArrayList<Vec3>(points.size)

        for (i in points.indices) {
            val p = points[i]
            val cluster = layer.labels[i]
            val zOffset = if (k <= 1) 0f else (cluster - (k - 1) * 0.5f) * options.clusterOffset
            positioned += vec(p.x, p.y, zBase + zOffset)
        }

        val centers = clusterCentroids(positioned, layer.labels)
        for (i in points.indices) {
            val cluster = layer.labels[i]
            val clusterColor = palette[cluster % palette.size]
            val color = withAlpha(clusterColor, options.pointAlpha)
            val glow = withAlpha(clusterColor, options.haloAlpha)
            pointHalo.addPoint(positioned[i], glow)
            pointCore.addPoint(positioned[i], color)

            if (options.drawLinks) {
                val c = centers[cluster]
                if (c != null) links.addSegment(positioned[i], c, withAlpha(clusterColor, options.linkAlpha))
            }
        }

        if (options.drawCenters) {
            for (cluster in centers.indices) {
                val c = centers[cluster] ?: continue
                val clusterColor = palette[cluster % palette.size]
                val bright = mixColor(clusterColor, vec(1f, 1f, 1f, 1f), 0.2f, options.centerAlpha)
                centerHalo.addPoint(c, withAlpha(clusterColor, options.haloAlpha * 1.2f))
                centerCore.addPoint(c, bright)
            }
        }

        if (options.drawGuides) {
            val guideColor = withAlpha(okhsl(0.62f + li * 0.03f, 0.16f, 0.67f, 1f), 0.12f)
            guides?.addRect(minX, minY, maxX, maxY, zBase, guideColor)
        }
    }

    return clusteredLineMap(
        pointHalo = pointHalo,
        pointCore = pointCore,
        centerHalo = centerHalo,
        centerCore = centerCore,
        links = links,
        guides = guides
    )
}

fun visualizeFloatClusterLayers3D(
    values: List<Float>,
    layers: List<ClusterLayer>,
    options: ClusterLayerVizOptions = ClusterLayerVizOptions()
): Map<String, List<FLine>> {
    require(values.isNotEmpty()) { "visualizeFloatClusterLayers3D: values must not be empty" }
    values.forEachIndexed { i, v ->
        require(v.isFinite()) { "visualizeFloatClusterLayers3D: values[$i] must be finite, found $v" }
    }

    val checkedLayers = validateLayers(values.size, layers, "visualizeFloatClusterLayers3D")

    val pointHalo = pointCloudLine(options.pointHaloSize)
    val pointCore = pointCloudLine(options.pointSize)
    val centerHalo = pointCloudLine(options.centerHaloSize)
    val centerCore = pointCloudLine(options.centerPointSize)
    val links = edgeLine(options.linkThickness)
    val guides = if (options.drawGuides) guideLine() else null

    val sorted = values.indices.sortedBy { values[it] }
    val rank = IntArray(values.size)
    for (i in sorted.indices) rank[sorted[i]] = i

    val minX = values.minOrNull() ?: 0f
    val maxX = values.maxOrNull() ?: 1f
    val ySpread = if (checkedLayers.maxOf { it.clusterCount } <= 1) 0.18f else options.clusterOffset * 1.4f

    checkedLayers.forEachIndexed { li, layer ->
        val k = max(1, layer.clusterCount)
        val palette = okhslSet(k, saturation = 0.75, lightness = 0.62, alpha = 1f, startHue = li * 0.08f)
        val zBase = li * options.layerSpacing
        val positioned = ArrayList<Vec3>(values.size)

        for (i in values.indices) {
            val cluster = layer.labels[i]
            val clusterBand = if (k <= 1) 0f else (cluster - (k - 1) * 0.5f) * ySpread
            val rankJitter = if (values.size <= 1) 0f else ((rank[i] / (values.size - 1f)) - 0.5f) * 0.06f
            positioned += vec(values[i], clusterBand + rankJitter, zBase)
        }

        val centers = clusterCentroids(positioned, layer.labels)
        for (i in values.indices) {
            val cluster = layer.labels[i]
            val clusterColor = palette[cluster % palette.size]
            val color = withAlpha(clusterColor, options.pointAlpha)
            pointHalo.addPoint(positioned[i], withAlpha(clusterColor, options.haloAlpha))
            pointCore.addPoint(positioned[i], color)

            if (options.drawLinks) {
                val c = centers[cluster]
                if (c != null) links.addSegment(positioned[i], c, withAlpha(clusterColor, options.linkAlpha))
            }
        }

        if (options.drawCenters) {
            for (cluster in centers.indices) {
                val c = centers[cluster] ?: continue
                val clusterColor = palette[cluster % palette.size]
                centerHalo.addPoint(c, withAlpha(clusterColor, options.haloAlpha * 1.25f))
                centerCore.addPoint(c, mixColor(clusterColor, vec(1f, 1f, 1f, 1f), 0.24f, options.centerAlpha))
            }
        }

        if (options.drawGuides) {
            val guideColor = withAlpha(okhsl(0.58f + li * 0.02f, 0.14f, 0.68f, 1f), 0.12f)
            guides?.addRect(minX, -ySpread * (k + 1), maxX, ySpread * (k + 1), zBase, guideColor)
        }
    }

    return clusteredLineMap(
        pointHalo = pointHalo,
        pointCore = pointCore,
        centerHalo = centerHalo,
        centerCore = centerCore,
        links = links,
        guides = guides
    )
}

fun AgglomerativeClustering.visualizeVec3(
    points: List<Vec3>,
    kValues: IntProgression = 2..6,
    options: ClusterLayerVizOptions = ClusterLayerVizOptions()
): Map<String, List<FLine>> {
    val ks = kValues.filter { it in 1..sampleCount }
    return visualizeVec3ClusterLayers3D(points, layers(ks), options)
}

fun AgglomerativeClustering.visualizeVec2(
    points: List<Vec2>,
    kValues: IntProgression = 2..6,
    options: ClusterLayerVizOptions = ClusterLayerVizOptions()
): Map<String, List<FLine>> {
    val ks = kValues.filter { it in 1..sampleCount }
    return visualizeVec2ClusterLayers3D(points, layers(ks), options)
}

fun AgglomerativeClustering.visualizeFloat(
    values: List<Float>,
    kValues: IntProgression = 2..6,
    options: ClusterLayerVizOptions = ClusterLayerVizOptions()
): Map<String, List<FLine>> {
    val ks = kValues.filter { it in 1..sampleCount }
    return visualizeFloatClusterLayers3D(values, layers(ks), options)
}

private fun agglomerative(
    rows: Array<DoubleArray>,
    linkage: AgglomerativeLinkage,
    fn: String
): AgglomerativeClustering {
    validateRows(rows, fn, minSamples = 2)
    val linkageModel = when (linkage) {
        AgglomerativeLinkage.SINGLE -> SingleLinkage.of(rows)
        AgglomerativeLinkage.COMPLETE -> CompleteLinkage.of(rows)
        AgglomerativeLinkage.AVERAGE -> UPGMALinkage.of(rows)
        AgglomerativeLinkage.WEIGHTED_AVERAGE -> WPGMALinkage.of(rows)
        AgglomerativeLinkage.CENTROID -> UPGMCLinkage.of(rows)
        AgglomerativeLinkage.MEDIAN -> WPGMCLinkage.of(rows)
        AgglomerativeLinkage.WARD -> WardLinkage.of(rows)
    }
    val model = HierarchicalClustering.fit(linkageModel)
    return AgglomerativeClustering(linkage, model)
}

private data class IndexedSample(val index: Int, val values: DoubleArray)

private data class KMedoidsCoreResult(
    val labels: IntArray,
    val medoidIndices: IntArray,
    val distortion: Double,
    val clusterSizes: IntArray
)

private fun kMedoids(
    rows: Array<DoubleArray>,
    k: Int,
    options: KMedoidsOptions,
    fn: String
): KMedoidsCoreResult {
    validateRows(rows, fn, minSamples = 2)
    require(k in 1 until rows.size) {
        "$fn: k must be in [1, ${rows.size - 1}] for k-medoids, found $k"
    }
    validateKMedoidsOptions(options, fn)

    val samples = Array(rows.size) { i -> IndexedSample(i, rows[i]) }
    val distance = when (options.distance) {
        MedoidDistance.EUCLIDEAN -> object : Distance<IndexedSample> {
            override fun d(x: IndexedSample, y: IndexedSample): Double = l2(x.values, y.values)
        }

        MedoidDistance.MANHATTAN -> object : Distance<IndexedSample> {
            private val l1 = ManhattanDistance()
            override fun d(x: IndexedSample, y: IndexedSample): Double = l1.d(x.values, y.values)
        }
    }

    val smileOptions = Clustering.Options(k, options.maxIter, options.neighborRatio, null)
    var best = KMedoids.fit(samples, distance, smileOptions)
    for (run in 1 until options.runs) {
        val candidate = KMedoids.fit(samples, distance, smileOptions)
        if (candidate.distortion() < best.distortion()) best = candidate
    }

    val labels = best.group().copyOf()
    val medoidIndices = IntArray(best.k()) { i -> best.centers()[i].index }
    return KMedoidsCoreResult(
        labels = labels,
        medoidIndices = medoidIndices,
        distortion = best.distortion(),
        clusterSizes = clusterSizes(labels, k)
    )
}

private data class KMediansCoreResult(
    val labels: IntArray,
    val centers: Array<DoubleArray>,
    val objective: Double,
    val iterations: Int,
    val converged: Boolean,
    val clusterSizes: IntArray
)

private fun kMedians(
    rows: Array<DoubleArray>,
    k: Int,
    options: KMediansOptions,
    fn: String
): KMediansCoreResult {
    validateRows(rows, fn, minSamples = 1)
    require(k in 1..rows.size) { "$fn: k must be in [1, ${rows.size}], found $k" }
    validateKMediansOptions(options, fn)

    var best: KMediansCoreResult? = null
    val seedBase = options.randomSeed
    for (run in 0 until options.runs) {
        val rng = when (seedBase) {
            null -> Random.Default
            else -> Random(seedBase + run)
        }
        val candidate = runKMedians(rows, k, options, rng)
        if (best == null || candidate.objective < best!!.objective) best = candidate
    }
    return best!!
}

private fun runKMedians(
    rows: Array<DoubleArray>,
    k: Int,
    options: KMediansOptions,
    rng: Random
): KMediansCoreResult {
    val n = rows.size
    val d = rows[0].size
    var centers = initKMediansCenters(rows, k, rng)
    val labels = IntArray(n)
    val distances = DoubleArray(n)
    var prevObjective = Double.POSITIVE_INFINITY
    var converged = false
    var iter = 0

    while (iter < options.maxIterations) {
        iter++
        assignL1(rows, centers, labels, distances)
        val objective = distances.sum()
        val counts = clusterSizes(labels, k)
        val replacements = farthestReplacementOrder(distances)
        var replacementCursor = 0
        val newCenters = Array(k) { DoubleArray(d) }

        for (cluster in 0 until k) {
            if (counts[cluster] == 0) {
                val replacementIndex = replacements[replacementCursor % replacements.size]
                replacementCursor++
                newCenters[cluster] = rows[replacementIndex].copyOf()
            } else {
                for (dim in 0 until d) {
                    newCenters[cluster][dim] = coordinateMedian(rows, labels, cluster, dim, counts[cluster])
                }
            }
        }

        val shift = centersL1Shift(centers, newCenters)
        val delta = abs(prevObjective - objective)
        centers = newCenters
        if (delta <= options.tolerance && shift <= options.tolerance) {
            converged = true
            break
        }
        prevObjective = objective
    }

    assignL1(rows, centers, labels, distances)
    val objective = distances.sum()
    return KMediansCoreResult(
        labels = labels.copyOf(),
        centers = centers.map { it.copyOf() }.toTypedArray(),
        objective = objective,
        iterations = iter,
        converged = converged,
        clusterSizes = clusterSizes(labels, k)
    )
}

private fun initKMediansCenters(
    rows: Array<DoubleArray>,
    k: Int,
    rng: Random
): Array<DoubleArray> {
    if (k == rows.size) return Array(k) { i -> rows[i].copyOf() }

    val chosen = LinkedHashSet<Int>(k)
    val first = rng.nextInt(rows.size)
    chosen.add(first)

    val minDist = DoubleArray(rows.size) { i -> l1(rows[i], rows[first]) }
    while (chosen.size < k) {
        var total = 0.0
        for (i in rows.indices) {
            if (i in chosen) continue
            val w = minDist[i] * minDist[i]
            if (w.isFinite() && w > 0.0) total += w
        }

        val next = if (total <= 1e-12) {
            var pick = rng.nextInt(rows.size)
            while (pick in chosen) pick = (pick + 1) % rows.size
            pick
        } else {
            var r = rng.nextDouble() * total
            var chosenIndex = -1
            for (i in rows.indices) {
                if (i in chosen) continue
                val w = minDist[i] * minDist[i]
                if (w <= 0.0 || !w.isFinite()) continue
                r -= w
                if (r <= 0.0) {
                    chosenIndex = i
                    break
                }
            }
            if (chosenIndex >= 0) {
                chosenIndex
            } else {
                var fallback = 0
                while (fallback < rows.size && fallback in chosen) fallback++
                if (fallback < rows.size) fallback else 0
            }
        }

        chosen.add(next)
        for (i in rows.indices) {
            val d = l1(rows[i], rows[next])
            if (d < minDist[i]) minDist[i] = d
        }
    }

    return chosen.map { rows[it].copyOf() }.toTypedArray()
}

private fun assignL1(
    rows: Array<DoubleArray>,
    centers: Array<DoubleArray>,
    labels: IntArray,
    distances: DoubleArray
) {
    for (i in rows.indices) {
        var bestCluster = 0
        var bestDistance = Double.POSITIVE_INFINITY
        for (cluster in centers.indices) {
            val d = l1(rows[i], centers[cluster])
            if (d < bestDistance) {
                bestDistance = d
                bestCluster = cluster
            }
        }
        labels[i] = bestCluster
        distances[i] = bestDistance
    }
}

private fun coordinateMedian(
    rows: Array<DoubleArray>,
    labels: IntArray,
    cluster: Int,
    dim: Int,
    count: Int
): Double {
    val values = DoubleArray(count)
    var p = 0
    for (i in rows.indices) {
        if (labels[i] == cluster) {
            values[p] = rows[i][dim]
            p++
        }
    }
    values.sort()
    val mid = values.size / 2
    return if (values.size % 2 == 0) (values[mid - 1] + values[mid]) * 0.5 else values[mid]
}

private fun centersL1Shift(
    a: Array<DoubleArray>,
    b: Array<DoubleArray>
): Double {
    var shift = 0.0
    for (i in a.indices) {
        shift += l1(a[i], b[i])
    }
    return shift
}

private fun farthestReplacementOrder(distances: DoubleArray): IntArray =
    distances.indices.sortedByDescending { distances[it] }.toIntArray()

private fun validateKMedoidsOptions(options: KMedoidsOptions, fn: String) {
    require(options.maxIter > 0) { "$fn: maxIter must be > 0, found ${options.maxIter}" }
    require(options.neighborRatio.isFinite() && options.neighborRatio > 0.0) {
        "$fn: neighborRatio must be finite and > 0, found ${options.neighborRatio}"
    }
    require(options.runs > 0) { "$fn: runs must be > 0, found ${options.runs}" }
}

private fun validateKMediansOptions(options: KMediansOptions, fn: String) {
    require(options.maxIterations > 0) {
        "$fn: maxIterations must be > 0, found ${options.maxIterations}"
    }
    require(options.tolerance.isFinite() && options.tolerance >= 0.0) {
        "$fn: tolerance must be finite and >= 0, found ${options.tolerance}"
    }
    require(options.runs > 0) { "$fn: runs must be > 0, found ${options.runs}" }
}

private fun validateRows(rows: Array<DoubleArray>, fn: String, minSamples: Int) {
    require(rows.size >= minSamples) {
        "$fn: at least $minSamples samples required, found ${rows.size}"
    }
    require(rows[0].isNotEmpty()) { "$fn: sample dimension must be > 0" }
    val d = rows[0].size
    rows.forEachIndexed { i, row ->
        require(row.size == d) {
            "$fn: inconsistent dimensions, row 0 has $d and row $i has ${row.size}"
        }
        row.forEachIndexed { j, v ->
            require(v.isFinite()) { "$fn: row[$i][$j] must be finite, found $v" }
        }
    }
}

private fun floatRows(values: List<Float>, fn: String): Array<DoubleArray> {
    require(values.isNotEmpty()) { "$fn: values must not be empty" }
    return Array(values.size) { i ->
        val v = values[i]
        require(v.isFinite()) { "$fn: values[$i] must be finite, found $v" }
        doubleArrayOf(v.toDouble())
    }
}

private fun vec2Rows(points: List<Vec2>, fn: String): Array<DoubleArray> {
    require(points.isNotEmpty()) { "$fn: points must not be empty" }
    return Array(points.size) { i ->
        val p = points[i]
        require(p.x.isFinite() && p.y.isFinite()) {
            "$fn: points[$i] must be finite, found (${p.x}, ${p.y})"
        }
        doubleArrayOf(p.x.toDouble(), p.y.toDouble())
    }
}

private fun vec3Rows(points: List<Vec3>, fn: String): Array<DoubleArray> {
    require(points.isNotEmpty()) { "$fn: points must not be empty" }
    return Array(points.size) { i ->
        val p = points[i]
        require(p.x.isFinite() && p.y.isFinite() && p.z.isFinite()) {
            "$fn: points[$i] must be finite, found (${p.x}, ${p.y}, ${p.z})"
        }
        doubleArrayOf(p.x.toDouble(), p.y.toDouble(), p.z.toDouble())
    }
}

private fun vec4Rows(points: List<Vec4>, fn: String): Array<DoubleArray> {
    require(points.isNotEmpty()) { "$fn: points must not be empty" }
    return Array(points.size) { i ->
        val p = points[i]
        require(p.x.isFinite() && p.y.isFinite() && p.z.isFinite() && p.w.isFinite()) {
            "$fn: points[$i] must be finite, found (${p.x}, ${p.y}, ${p.z}, ${p.w})"
        }
        doubleArrayOf(p.x.toDouble(), p.y.toDouble(), p.z.toDouble(), p.w.toDouble())
    }
}

private fun l1(a: DoubleArray, b: DoubleArray): Double {
    var s = 0.0
    for (i in a.indices) s += abs(a[i] - b[i])
    return s
}

private fun l2(a: DoubleArray, b: DoubleArray): Double {
    var s = 0.0
    for (i in a.indices) {
        val d = a[i] - b[i]
        s += d * d
    }
    return sqrt(s)
}

private fun clusterSizes(labels: IntArray, k: Int): IntArray {
    val sizes = IntArray(k)
    labels.forEach { label ->
        if (label in 0 until k) sizes[label]++
    }
    return sizes
}

private fun canonicalizeLabels(raw: IntArray): IntArray {
    val remap = LinkedHashMap<Int, Int>()
    return IntArray(raw.size) { i ->
        remap.getOrPut(raw[i]) { remap.size }
    }
}

private data class Bounds3(val min: Vec3, val max: Vec3, val size: Vec3)

private fun bounds3(points: List<Vec3>): Bounds3 {
    var minX = Float.POSITIVE_INFINITY
    var minY = Float.POSITIVE_INFINITY
    var minZ = Float.POSITIVE_INFINITY
    var maxX = Float.NEGATIVE_INFINITY
    var maxY = Float.NEGATIVE_INFINITY
    var maxZ = Float.NEGATIVE_INFINITY
    points.forEach { p ->
        if (p.x < minX) minX = p.x
        if (p.y < minY) minY = p.y
        if (p.z < minZ) minZ = p.z
        if (p.x > maxX) maxX = p.x
        if (p.y > maxY) maxY = p.y
        if (p.z > maxZ) maxZ = p.z
    }
    return Bounds3(
        min = vec(minX, minY, minZ),
        max = vec(maxX, maxY, maxZ),
        size = vec(
            max(1e-6f, maxX - minX),
            max(1e-6f, maxY - minY),
            max(1e-6f, maxZ - minZ)
        )
    )
}

private fun coordinateColor(point: Vec3, bounds: Bounds3, alpha: Float): Vec4 {
    val nx = ((point.x - bounds.min.x) / bounds.size.x).coerceIn(0f, 1f)
    val ny = ((point.y - bounds.min.y) / bounds.size.y).coerceIn(0f, 1f)
    val nz = ((point.z - bounds.min.z) / bounds.size.z).coerceIn(0f, 1f)
    val soften = { v: Float -> sqrt(v.toDouble()).toFloat().coerceIn(0f, 1f) }
    return vec(soften(nx), soften(ny), soften(nz), alpha)
}

private fun mixColor(a: Vec4, b: Vec4, t: Float, alpha: Float): Vec4 {
    val u = t.coerceIn(0f, 1f)
    return vec(
        a.x + (b.x - a.x) * u,
        a.y + (b.y - a.y) * u,
        a.z + (b.z - a.z) * u,
        alpha
    )
}

private fun withAlpha(color: Vec4, alpha: Float): Vec4 =
    vec(color.x, color.y, color.z, alpha)

private fun clusterCentroids(points: List<Vec3>, labels: IntArray): Array<Vec3?> {
    val k = (labels.maxOrNull() ?: -1) + 1
    val sums = Array(k) { vec(0f, 0f, 0f) }
    val counts = IntArray(k)
    for (i in points.indices) {
        val c = labels[i]
        if (c !in 0 until k) continue
        sums[c].add(points[i])
        counts[c]++
    }
    return Array(k) { i ->
        if (counts[i] == 0) null else sums[i].mul(1f / counts[i].toFloat(), vec(0f, 0f, 0f))
    }
}

private fun validateLayers(
    sampleCount: Int,
    layers: List<ClusterLayer>,
    fn: String
): List<ClusterLayer> {
    require(layers.isNotEmpty()) { "$fn: layers must not be empty" }
    layers.forEachIndexed { i, layer ->
        require(layer.sampleCount == sampleCount) {
            "$fn: layers[$i] sample count mismatch, expected $sampleCount found ${layer.sampleCount}"
        }
    }
    return layers
}

private fun pointCloudLine(pointSize: Double): FLine = fline {
    pointed = true
    stroked = false
    filled = false
    this.pointSize = pointSize
}

private fun edgeLine(thickness: Double): FLine = fline {
    pointed = false
    stroked = true
    filled = false
    this.thickness = thickness
}

private fun guideLine(): FLine = fline {
    pointed = false
    stroked = true
    filled = false
    this.thickness = 0.55
}

private fun FLine.addPoint(position: Vec3, color: Vec4) {
    moveTo(position)
    val node = nodes.last()
    node.properties["color"] = Vec4(color)
}

private fun FLine.addSegment(a: Vec3, b: Vec3, color: Vec4) {
    moveTo(a)
    nodes.last().properties["color"] = Vec4(color)
    lineTo(b)
    nodes.last().properties["color"] = Vec4(color)
}

private fun FLine.addRect(
    minX: Float,
    minY: Float,
    maxX: Float,
    maxY: Float,
    z: Float,
    color: Vec4
) {
    addSegment(vec(minX, minY, z), vec(maxX, minY, z), color)
    addSegment(vec(maxX, minY, z), vec(maxX, maxY, z), color)
    addSegment(vec(maxX, maxY, z), vec(minX, maxY, z), color)
    addSegment(vec(minX, maxY, z), vec(minX, minY, z), color)
}

private fun clusteredLineMap(
    pointHalo: FLine,
    pointCore: FLine,
    centerHalo: FLine,
    centerCore: FLine,
    links: FLine,
    guides: FLine?
): Map<String, List<FLine>> {
    val out = linkedMapOf<String, List<FLine>>()
    if (pointHalo.nodes.isNotEmpty()) out["cluster-points-halo"] = listOf(pointHalo)
    if (pointCore.nodes.isNotEmpty()) out["cluster-points"] = listOf(pointCore)
    if (centerHalo.nodes.isNotEmpty()) out["cluster-centers-halo"] = listOf(centerHalo)
    if (centerCore.nodes.isNotEmpty()) out["cluster-centers"] = listOf(centerCore)
    if (links.nodes.isNotEmpty()) out["cluster-links"] = listOf(links)
    if (guides != null && guides.nodes.isNotEmpty()) out["cluster-guides"] = listOf(guides)
    return out
}
