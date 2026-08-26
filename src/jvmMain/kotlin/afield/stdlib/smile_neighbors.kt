@file:afield.CompletionCategory("Data")

package afield.stdlib

import smile.neighbor.KDTree
import smile.neighbor.LSH
import smile.neighbor.Neighbor
import kotlin.math.sqrt

/**
 * Nearest-neighbour indexes over `Vec2` / `Vec3` point lists, wrapping Smile's `KDTree` and `LSH`.
 * Build one with [vec2NN] or [vec3NN]: both copy the points, reject non-finite coordinates, and
 * return a [Vec2NNIndex] / [Vec3NNIndex] exposing `nearest`, `kNearest` and `withinRadius`. Results
 * come back as [VecNeighbor2] / [VecNeighbor3] carrying the index into the original list and the
 * true Euclidean distance, radius results sorted nearest-first.
 *
 * [VecNNMode.APPROXIMATE] (the default) builds an LSH alongside the KD-tree and silently degrades
 * to exact mode, with a message on stderr, if LSH construction fails. The brute-force fallbacks are
 * narrower than they sound and do **not** make approximate mode correct: `kNearest` tops up an answer
 * that came back *short*, and `withinRadius` only runs one when the structure returns *nothing at all*,
 * so a full-length or merely-incomplete LSH answer is passed straight through. Ask for
 * [VecNNMode.EXACT] when the answer has to be right. [VecNNBuildOptions] tunes the LSH parameters and
 * can forbid duplicate points.
 */

/**
 * Which structure a [Vec2NNIndex] / [Vec3NNIndex] actually queries. `EXACT` searches the Smile
 * KD-tree and returns the true nearest neighbours; `APPROXIMATE` (the default) searches a
 * locality-sensitive hash instead, which only looks at points sharing a hash bucket with the query
 * — so it can return the *wrong* neighbours, not merely take a different route to the right ones.
 *
 * This picks the query path, not the build: the KD-tree is always built, the LSH only in approximate
 * mode. Approximate is also not automatically the faster of the two here. A brute-force scan of every
 * point backs up a *short* `kNearest` answer and an *empty* `withinRadius` one — not a full-length answer
 * that is simply wrong — and the default `lshBucketWidth` of 1.0 makes short answers the common case on
 * coordinates much larger than 1; see [VecNNBuildOptions].
 */
enum class VecNNMode {
    APPROXIMATE,
    EXACT
}

/**
 * LSH build parameters and the duplicate-point policy for [vec2NN] / [vec3NN]. Only the approximate
 * path reads the three `lsh` values; a [VecNNMode.EXACT] index ignores them entirely, so tuning them
 * there changes nothing.
 *
 * The number of hash tables is not exposed — it is derived from the point count as
 * `max(8, sqrt(n))`. The rest go straight to Smile's `LSH` constructor.
 */
data class VecNNBuildOptions(
    /**
     * Width of the LSH random projections, in the same units as your point coordinates. Wider
     * buckets collect more points, so a query considers more candidates and is likelier to find the
     * true neighbours, at more work per query; narrower buckets are cheaper and miss more. Must be
     * finite and greater than zero.
     *
     * The default of 1.0 suits points near unit scale and is far too narrow for, say, screen
     * coordinates — there almost every query ends up in the brute-force fallback instead.
     */
    val lshBucketWidth: Double = 1.0,
    /**
     * How many random projections make up one LSH hash value. More projections mean more selective
     * buckets, which is the same trade as a narrower [lshBucketWidth]: fewer candidates examined,
     * more true neighbours missed. Must be greater than zero.
     */
    val lshProjectionCount: Int = 8,
    /**
     * Size of the universal hash table behind each LSH hash; the default 9973 is prime. Bigger costs
     * memory and reduces accidental collisions between unrelated buckets. Must be greater than zero.
     */
    val lshUniversalHashSize: Int = 9973,
    /**
     * Leave this true and duplicate points are indexed normally, simply showing up as several
     * neighbours at the same distance. Set it false and [vec2NN] / [vec3NN] reject any list holding
     * two bit-for-bit identical points, with an `IllegalArgumentException` naming the offending
     * index. Note that this compares raw coordinate bits, so `0.0` and `-0.0` count as different
     * points.
     */
    val allowDuplicatePoints: Boolean = true
)

/**
 * One 2D neighbour hit: where the point sits in the list you built the index from, a copy of the
 * point itself, and how far it is from the query. Handed back by [Vec2NNIndex]; there is no reason
 * to construct one by hand.
 */
data class VecNeighbor2(
    /**
     * Position of this point in the list originally passed to [vec2NN]. The index takes a copy but
     * preserves order, so this is safe to use as a key into your own parallel arrays.
     */
    val index: Int,
    /**
     * A fresh copy of the indexed point, not the instance you passed in. `Vec2` is Field's mutable
     * `Vector2f`, so writing to this cannot corrupt the index — and equally, mutating your original
     * point after the build does not change what the index holds.
     */
    val point: Vec2,
    /**
     * True Euclidean distance from the query to this point: not squared, and recomputed in `Double`
     * from the stored coordinates rather than trusted from whatever the KD-tree or LSH reported. Zero
     * when the query coincides with the point, which happens whenever you query with a point that is
     * itself in the index.
     */
    val distance: Double
)

/**
 * One 3D neighbour hit: where the point sits in the list you built the index from, a copy of the
 * point itself, and how far it is from the query. Handed back by [Vec3NNIndex]; there is no reason
 * to construct one by hand.
 */
data class VecNeighbor3(
    /**
     * Position of this point in the list originally passed to [vec3NN]. The index takes a copy but
     * preserves order, so this is safe to use as a key into your own parallel arrays.
     */
    val index: Int,
    /**
     * A fresh copy of the indexed point, not the instance you passed in. `Vec3` is Field's mutable
     * `Vector3f`, so writing to this cannot corrupt the index — and equally, mutating your original
     * point after the build does not change what the index holds.
     */
    val point: Vec3,
    /**
     * True Euclidean distance from the query to this point: not squared, and recomputed in `Double`
     * from the stored coordinates rather than trusted from whatever the KD-tree or LSH reported. Zero
     * when the query coincides with the point, which happens whenever you query with a point that is
     * itself in the index.
     */
    val distance: Double
)

/**
 * A built 2D nearest-neighbour index — a private snapshot of the points plus the Smile structures
 * that search them. Get one from [vec2NN] (the constructor is internal) and ask it for [nearest],
 * [kNearest] or [withinRadius].
 *
 * It is a snapshot, not a live view: the points were deep-copied at build time, so appending to or
 * mutating the original list afterwards has no effect here, and there is no way to add a point to an
 * existing index. A point equal to the query is never excluded, so querying with a point that is in
 * the index returns that point at distance 0 — and in [kNearest] it uses up one of your k slots. A
 * query with a non-finite coordinate throws rather than returning nothing.
 */
class Vec2NNIndex internal constructor(
    private val points: List<Vec2>,
    private val mode: VecNNMode,
    private val exactIndex: KDTree<Int>?,
    private val approxIndex: LSH<Int>?
) {
    /**
     * How many points the index holds. This is the length of the snapshot taken at build time, so it
     * never changes, and it is at least 1 because [vec2NN] rejects an empty list.
     */
    val size: Int
        get() = points.size

    /**
     * The single closest indexed point to `query`, with its true Euclidean distance. Never null: the
     * index is non-empty by construction, and if the backing structure declines to answer this falls
     * through to a full scan.
     *
     * In [VecNNMode.APPROXIMATE] mode the LSH is asked first, then the KD-tree, then brute force, so
     * a hash miss costs time rather than correctness — but when the LSH does answer, the point it
     * names need not be the true nearest. Use [VecNNMode.EXACT] for the reference answer. Throws
     * `IllegalArgumentException` on a non-finite query.
     */
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

    /**
     * The `k` closest indexed points to `query`, with distinct indices and **no guaranteed order** —
     * unlike [withinRadius] this never sorts, it returns candidates in whatever order the backing
     * structure produced them, so sort by `distance` yourself if you need nearest-first.
     *
     * `k` is a maximum, not a promise: it is clamped to [size], so asking for more neighbours than the
     * index holds gives you every point rather than an error, while a `k` of zero or less
     * throws. When the backing structure returns fewer candidates than that, the shortfall is topped
     * up from a brute-force distance sort, which costs O(n log n). That top-up only rescues a *short*
     * answer: in approximate mode a full-length answer from the LSH is accepted as it stands, even
     * when it is not the true k nearest.
     */
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
            // A `for`, not a `forEach`: `return@forEach` is a *continue*, so the early exit this looks
            // like never happened and the whole index was inserted before `take(kEff)` threw it away.
            // The answer was right either way (a LinkedHashSet keeps the order the first kEff arrived
            // in); the walk was just O(n) inserts long.
            for (idx in bruteSortedIndices(query)) {
                selected.add(idx)
                if (selected.size >= kEff) break
            }
        }

        return selected.take(kEff).map { makeNeighbor(it, query) }
    }

    /**
     * Every indexed point within `radius` of `query` — a true Euclidean distance, not a squared one —
     * sorted nearest-first. Returns an empty list when nothing is in range; `radius` must be finite
     * and at least zero or this throws.
     *
     * A radius of exactly zero is special-cased into a brute-force hunt for coincident points (within
     * 1e-12), which is how you ask "what is sitting exactly here". Otherwise the backing structure
     * answers and the results are re-filtered against `radius` with a 1e-12 slack. The brute-force
     * fallback only runs when the structure returns *nothing at all*, so in approximate mode a short
     * but non-empty answer is passed straight through and can be missing points that really are in
     * range.
     */
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

/**
 * A built 3D nearest-neighbour index — a private snapshot of the points plus the Smile structures
 * that search them. Get one from [vec3NN] (the constructor is internal) and ask it for [nearest],
 * [kNearest] or [withinRadius].
 *
 * It is a snapshot, not a live view: the points were deep-copied at build time, so appending to or
 * mutating the original list afterwards has no effect here, and there is no way to add a point to an
 * existing index. A point equal to the query is never excluded, so querying with a point that is in
 * the index returns that point at distance 0 — and in [kNearest] it uses up one of your k slots. A
 * query with a non-finite coordinate throws rather than returning nothing.
 */
class Vec3NNIndex internal constructor(
    private val points: List<Vec3>,
    private val mode: VecNNMode,
    private val exactIndex: KDTree<Int>?,
    private val approxIndex: LSH<Int>?
) {
    /**
     * How many points the index holds. This is the length of the snapshot taken at build time, so it
     * never changes, and it is at least 1 because [vec3NN] rejects an empty list.
     */
    val size: Int
        get() = points.size

    /**
     * The single closest indexed point to `query`, with its true Euclidean distance. Never null: the
     * index is non-empty by construction, and if the backing structure declines to answer this falls
     * through to a full scan.
     *
     * In [VecNNMode.APPROXIMATE] mode the LSH is asked first, then the KD-tree, then brute force, so
     * a hash miss costs time rather than correctness — but when the LSH does answer, the point it
     * names need not be the true nearest. Use [VecNNMode.EXACT] for the reference answer. Throws
     * `IllegalArgumentException` on a non-finite query.
     */
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

    /**
     * The `k` closest indexed points to `query`, with distinct indices and **no guaranteed order** —
     * unlike [withinRadius] this never sorts, it returns candidates in whatever order the backing
     * structure produced them, so sort by `distance` yourself if you need nearest-first.
     *
     * `k` is a maximum, not a promise: it is clamped to [size], so asking for more neighbours than the
     * index holds gives you every point rather than an error, while a `k` of zero or less
     * throws. When the backing structure returns fewer candidates than that, the shortfall is topped
     * up from a brute-force distance sort, which costs O(n log n). That top-up only rescues a *short*
     * answer: in approximate mode a full-length answer from the LSH is accepted as it stands, even
     * when it is not the true k nearest.
     */
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
            // A `for`, not a `forEach`: `return@forEach` is a *continue*, so the early exit this looks
            // like never happened and the whole index was inserted before `take(kEff)` threw it away.
            // The answer was right either way (a LinkedHashSet keeps the order the first kEff arrived
            // in); the walk was just O(n) inserts long.
            for (idx in bruteSortedIndices(query)) {
                selected.add(idx)
                if (selected.size >= kEff) break
            }
        }

        return selected.take(kEff).map { makeNeighbor(it, query) }
    }

    /**
     * Every indexed point within `radius` of `query` — a true Euclidean distance, not a squared one —
     * sorted nearest-first. Returns an empty list when nothing is in range; `radius` must be finite
     * and at least zero or this throws.
     *
     * A radius of exactly zero is special-cased into a brute-force hunt for coincident points (within
     * 1e-12), which is how you ask "what is sitting exactly here". Otherwise the backing structure
     * answers and the results are re-filtered against `radius` with a 1e-12 slack. The brute-force
     * fallback only runs when the structure returns *nothing at all*, so in approximate mode a short
     * but non-empty answer is passed straight through and can be missing points that really are in
     * range.
     */
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

/**
 * Builds a 2D nearest-neighbor index from a non-empty list of finite points.
 *
 * The points are copied before indexing. [mode] chooses the exposed lookup strategy;
 * [options] controls validation and LSH/KD-tree build settings, including duplicate handling.
 *
 * Every coordinate must be finite and the list must not be empty, or this throws
 * `IllegalArgumentException` naming the offending position. Because the copy is deep, the returned
 * [Vec2NNIndex] is a snapshot: later edits to your list, or to the mutable `Vec2` instances in it,
 * are invisible to it.
 *
 * The KD-tree is built either way; [VecNNMode.APPROXIMATE] additionally builds an LSH, and if that
 * construction throws, the failure is *swallowed* — a line goes to stderr and you get a silently
 * exact index instead. So an approximate index is never less accurate than an exact one by accident,
 * but you cannot tell from the returned object which one you got.
 */
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

/**
 * Builds a 3D nearest-neighbor index from a non-empty list of finite points.
 *
 * The 3D twin of [vec2NN], with the same conventions: the points are deep-copied, so the returned
 * [Vec3NNIndex] is a snapshot rather than a view; a non-finite coordinate or an empty list throws
 * `IllegalArgumentException` naming the offending position; [mode] selects the query path and
 * [options] tunes the LSH and the duplicate policy.
 *
 * As in 2D, the KD-tree is always built and a failed LSH construction is swallowed — stderr gets a
 * line and you are handed an exact index instead.
 */
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
        if (t is VirtualMachineError) throw t
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
