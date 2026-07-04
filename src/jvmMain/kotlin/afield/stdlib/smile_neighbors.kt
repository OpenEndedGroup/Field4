@file:afield.CompletionCategory("Data")

package afield.stdlib

import smile.neighbor.KDTree
import smile.neighbor.LSH
import smile.neighbor.Neighbor
import kotlin.math.sqrt

enum class VecNNMode {
    APPROXIMATE,
    EXACT
}

data class VecNNBuildOptions(
    val lshBucketWidth: Double = 1.0,
    val lshProjectionCount: Int = 8,
    val lshUniversalHashSize: Int = 9973,
    val allowDuplicatePoints: Boolean = true
)

data class VecNeighbor2(
    val index: Int,
    val point: Vec2,
    val distance: Double
)

data class VecNeighbor3(
    val index: Int,
    val point: Vec3,
    val distance: Double
)

class Vec2NNIndex internal constructor(
    private val points: List<Vec2>,
    private val mode: VecNNMode,
    private val exactIndex: KDTree<Int>?,
    private val approxIndex: LSH<Int>?
) {
    val size: Int
        get() = points.size

    fun nearest(query: Vec2): VecNeighbor2 {
        validateQuery(query, "Vec2NNIndex.nearest")
        val queryKey = query.toDoubleKey()
        val candidate = when (mode) {
            VecNNMode.EXACT -> exactIndex?.nearest(queryKey)
            VecNNMode.APPROXIMATE -> approxIndex?.nearest(queryKey) ?: exactIndex?.nearest(queryKey)
        }
        val idx = candidate?.value ?: bruteSortedIndices(query).first()
        return makeNeighbor(idx, query)
    }

    fun kNearest(query: Vec2, k: Int): List<VecNeighbor2> {
        validateQuery(query, "Vec2NNIndex.kNearest")
        require(k > 0) { "Vec2NNIndex.kNearest: k must be > 0, found $k" }
        val kEff = minOf(k, size)
        val selected = LinkedHashSet<Int>(kEff)
        val queryKey = query.toDoubleKey()

        when (mode) {
            VecNNMode.EXACT -> {
                exactIndex?.search(queryKey, kEff)?.forEach { selected.add(it.value) }
            }

            VecNNMode.APPROXIMATE -> {
                approxIndex?.search(queryKey, kEff)?.forEach { selected.add(it.value) }
            }
        }

        if (selected.size < kEff) {
            bruteSortedIndices(query).forEach { idx ->
                selected.add(idx)
                if (selected.size >= kEff) return@forEach
            }
        }

        return selected.take(kEff).map { makeNeighbor(it, query) }
    }

    fun withinRadius(query: Vec2, radius: Double): List<VecNeighbor2> {
        validateQuery(query, "Vec2NNIndex.withinRadius")
        require(radius.isFinite() && radius >= 0.0) {
            "Vec2NNIndex.withinRadius: radius must be finite and >= 0, found $radius"
        }
        if (radius == 0.0) {
            return bruteSortedIndices(query)
                .asSequence()
                .map { makeNeighbor(it, query) }
                .filter { it.distance <= 1e-12 }
                .toList()
        }
        val ids = LinkedHashSet<Int>()
        val queryKey = query.toDoubleKey()

        when (mode) {
            VecNNMode.EXACT -> {
                val neighbors = ArrayList<Neighbor<DoubleArray, Int>>()
                exactIndex?.search(queryKey, radius, neighbors)
                neighbors.forEach { ids.add(it.value) }
            }

            VecNNMode.APPROXIMATE -> {
                val neighbors = ArrayList<Neighbor<DoubleArray, Int>>()
                approxIndex?.search(queryKey, radius, neighbors)
                neighbors.forEach { ids.add(it.value) }
            }
        }

        if (ids.isEmpty()) {
            bruteSortedIndices(query).forEach { idx ->
                if (distance(points[idx], query) <= radius) ids.add(idx)
            }
        }

        return ids
            .map { makeNeighbor(it, query) }
            .filter { it.distance <= radius + 1e-12 }
            .sortedBy { it.distance }
    }

    private fun makeNeighbor(index: Int, query: Vec2): VecNeighbor2 =
        VecNeighbor2(index = index, point = Vec2(points[index]), distance = distance(points[index], query))

    private fun bruteSortedIndices(query: Vec2): List<Int> =
        points.indices.sortedBy { idx -> squaredDistance(points[idx], query) }
}

class Vec3NNIndex internal constructor(
    private val points: List<Vec3>,
    private val mode: VecNNMode,
    private val exactIndex: KDTree<Int>?,
    private val approxIndex: LSH<Int>?
) {
    val size: Int
        get() = points.size

    fun nearest(query: Vec3): VecNeighbor3 {
        validateQuery(query, "Vec3NNIndex.nearest")
        val queryKey = query.toDoubleKey()
        val candidate = when (mode) {
            VecNNMode.EXACT -> exactIndex?.nearest(queryKey)
            VecNNMode.APPROXIMATE -> approxIndex?.nearest(queryKey) ?: exactIndex?.nearest(queryKey)
        }
        val idx = candidate?.value ?: bruteSortedIndices(query).first()
        return makeNeighbor(idx, query)
    }

    fun kNearest(query: Vec3, k: Int): List<VecNeighbor3> {
        validateQuery(query, "Vec3NNIndex.kNearest")
        require(k > 0) { "Vec3NNIndex.kNearest: k must be > 0, found $k" }
        val kEff = minOf(k, size)
        val selected = LinkedHashSet<Int>(kEff)
        val queryKey = query.toDoubleKey()

        when (mode) {
            VecNNMode.EXACT -> {
                exactIndex?.search(queryKey, kEff)?.forEach { selected.add(it.value) }
            }

            VecNNMode.APPROXIMATE -> {
                approxIndex?.search(queryKey, kEff)?.forEach { selected.add(it.value) }
            }
        }

        if (selected.size < kEff) {
            bruteSortedIndices(query).forEach { idx ->
                selected.add(idx)
                if (selected.size >= kEff) return@forEach
            }
        }

        return selected.take(kEff).map { makeNeighbor(it, query) }
    }

    fun withinRadius(query: Vec3, radius: Double): List<VecNeighbor3> {
        validateQuery(query, "Vec3NNIndex.withinRadius")
        require(radius.isFinite() && radius >= 0.0) {
            "Vec3NNIndex.withinRadius: radius must be finite and >= 0, found $radius"
        }
        if (radius == 0.0) {
            return bruteSortedIndices(query)
                .asSequence()
                .map { makeNeighbor(it, query) }
                .filter { it.distance <= 1e-12 }
                .toList()
        }
        val ids = LinkedHashSet<Int>()
        val queryKey = query.toDoubleKey()

        when (mode) {
            VecNNMode.EXACT -> {
                val neighbors = ArrayList<Neighbor<DoubleArray, Int>>()
                exactIndex?.search(queryKey, radius, neighbors)
                neighbors.forEach { ids.add(it.value) }
            }

            VecNNMode.APPROXIMATE -> {
                val neighbors = ArrayList<Neighbor<DoubleArray, Int>>()
                approxIndex?.search(queryKey, radius, neighbors)
                neighbors.forEach { ids.add(it.value) }
            }
        }

        if (ids.isEmpty()) {
            bruteSortedIndices(query).forEach { idx ->
                if (distance(points[idx], query) <= radius) ids.add(idx)
            }
        }

        return ids
            .map { makeNeighbor(it, query) }
            .filter { it.distance <= radius + 1e-12 }
            .sortedBy { it.distance }
    }

    private fun makeNeighbor(index: Int, query: Vec3): VecNeighbor3 =
        VecNeighbor3(index = index, point = Vec3(points[index]), distance = distance(points[index], query))

    private fun bruteSortedIndices(query: Vec3): List<Int> =
        points.indices.sortedBy { idx -> squaredDistance(points[idx], query) }
}

fun vec2NN(
    points: List<Vec2>,
    mode: VecNNMode = VecNNMode.APPROXIMATE,
    options: VecNNBuildOptions = VecNNBuildOptions()
): Vec2NNIndex {
    require(points.isNotEmpty()) { "vec2NN: points must not be empty" }
    validateBuildOptions(options, "vec2NN")
    val copy = points.mapIndexed { i, p ->
        require(p.x.isFinite() && p.y.isFinite()) {
            "vec2NN: points[$i] must be finite, found (${"%.6f".format(p.x)}, ${"%.6f".format(p.y)})"
        }
        Vec2(p)
    }
    enforceDuplicatePolicy2(copy, options.allowDuplicatePoints, "vec2NN")

    val keys = Array(copy.size) { i -> copy[i].toDoubleKey() }
    val ids = Array(copy.size) { i -> i }
    val exact = KDTree(keys, ids)
    val approx = if (mode == VecNNMode.APPROXIMATE) buildLshOrNull(keys, ids, options, "vec2NN") else null
    val effectiveMode = if (mode == VecNNMode.APPROXIMATE && approx == null) VecNNMode.EXACT else mode
    return Vec2NNIndex(copy, effectiveMode, exact, approx)
}

fun vec3NN(
    points: List<Vec3>,
    mode: VecNNMode = VecNNMode.APPROXIMATE,
    options: VecNNBuildOptions = VecNNBuildOptions()
): Vec3NNIndex {
    require(points.isNotEmpty()) { "vec3NN: points must not be empty" }
    validateBuildOptions(options, "vec3NN")
    val copy = points.mapIndexed { i, p ->
        require(p.x.isFinite() && p.y.isFinite() && p.z.isFinite()) {
            "vec3NN: points[$i] must be finite, found (${"%.6f".format(p.x)}, ${"%.6f".format(p.y)}, ${"%.6f".format(p.z)})"
        }
        Vec3(p)
    }
    enforceDuplicatePolicy3(copy, options.allowDuplicatePoints, "vec3NN")

    val keys = Array(copy.size) { i -> copy[i].toDoubleKey() }
    val ids = Array(copy.size) { i -> i }
    val exact = KDTree(keys, ids)
    val approx = if (mode == VecNNMode.APPROXIMATE) buildLshOrNull(keys, ids, options, "vec3NN") else null
    val effectiveMode = if (mode == VecNNMode.APPROXIMATE && approx == null) VecNNMode.EXACT else mode
    return Vec3NNIndex(copy, effectiveMode, exact, approx)
}

private fun buildLshOrNull(
    keys: Array<DoubleArray>,
    ids: Array<Int>,
    options: VecNNBuildOptions,
    fn: String
): LSH<Int>? {
    return try {
        LSH<Int>(
            keys[0].size,
            lshTableCount(keys.size),
            options.lshProjectionCount,
            options.lshBucketWidth,
            options.lshUniversalHashSize
        ).also { approx ->
            for (i in keys.indices) {
                approx.put(keys[i], ids[i])
            }
        }
    } catch (t: Throwable) {
        if (t is VirtualMachineError || t is ThreadDeath) throw t
        System.err.println("$fn: approximate LSH backend unavailable; falling back to exact nearest-neighbor search: ${t.message ?: t.javaClass.name}")
        null
    }
}

private fun validateBuildOptions(options: VecNNBuildOptions, fn: String) {
    require(options.lshBucketWidth.isFinite() && options.lshBucketWidth > 0.0) {
        "$fn: lshBucketWidth must be > 0, found ${options.lshBucketWidth}"
    }
    require(options.lshProjectionCount > 0) {
        "$fn: lshProjectionCount must be > 0, found ${options.lshProjectionCount}"
    }
    require(options.lshUniversalHashSize > 0) {
        "$fn: lshUniversalHashSize must be > 0, found ${options.lshUniversalHashSize}"
    }
}

private fun enforceDuplicatePolicy2(points: List<Vec2>, allowDuplicatePoints: Boolean, fn: String) {
    if (allowDuplicatePoints) return
    val seen = HashSet<Long>(points.size * 2)
    points.forEachIndexed { i, p ->
        val key = (p.x.toRawBits().toLong() shl 32) xor (p.y.toRawBits().toLong() and 0xFFFF_FFFFL)
        require(seen.add(key)) {
            "$fn: duplicate point found at index $i (${p.x}, ${p.y}); set allowDuplicatePoints=true to permit duplicates"
        }
    }
}

private fun enforceDuplicatePolicy3(points: List<Vec3>, allowDuplicatePoints: Boolean, fn: String) {
    if (allowDuplicatePoints) return
    val seen = HashSet<String>(points.size * 2)
    points.forEachIndexed { i, p ->
        val key = "${p.x.toRawBits()}_${p.y.toRawBits()}_${p.z.toRawBits()}"
        require(seen.add(key)) {
            "$fn: duplicate point found at index $i (${p.x}, ${p.y}, ${p.z}); set allowDuplicatePoints=true to permit duplicates"
        }
    }
}

private fun validateQuery(query: Vec2, fn: String) {
    require(query.x.isFinite() && query.y.isFinite()) {
        "$fn: query must be finite, found (${query.x}, ${query.y})"
    }
}

private fun validateQuery(query: Vec3, fn: String) {
    require(query.x.isFinite() && query.y.isFinite() && query.z.isFinite()) {
        "$fn: query must be finite, found (${query.x}, ${query.y}, ${query.z})"
    }
}

private fun Vec2.toDoubleKey(): DoubleArray = doubleArrayOf(x.toDouble(), y.toDouble())

private fun Vec3.toDoubleKey(): DoubleArray = doubleArrayOf(x.toDouble(), y.toDouble(), z.toDouble())

private fun squaredDistance(a: Vec2, b: Vec2): Double {
    val dx = a.x.toDouble() - b.x.toDouble()
    val dy = a.y.toDouble() - b.y.toDouble()
    return dx * dx + dy * dy
}

private fun squaredDistance(a: Vec3, b: Vec3): Double {
    val dx = a.x.toDouble() - b.x.toDouble()
    val dy = a.y.toDouble() - b.y.toDouble()
    val dz = a.z.toDouble() - b.z.toDouble()
    return dx * dx + dy * dy + dz * dz
}

private fun distance(a: Vec2, b: Vec2): Double = sqrt(squaredDistance(a, b))

private fun distance(a: Vec3, b: Vec3): Double = sqrt(squaredDistance(a, b))

private fun lshTableCount(pointCount: Int): Int =
    maxOf(8, kotlin.math.sqrt(pointCount.toDouble()).toInt())
