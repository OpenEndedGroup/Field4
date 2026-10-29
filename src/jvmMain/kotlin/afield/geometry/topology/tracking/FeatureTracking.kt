package afield.geometry.topology.tracking

import afield.geometry.network.Channel
import afield.geometry.network.GeometryCurve
import afield.geometry.network.GeometryNetwork
import afield.geometry.network.GeometryNodes
import afield.geometry.path.FLine
import afield.geometry.spatial.HungarianAssignment
import afield.geometry.topology.morsesmale.CriticalType
import afield.geometry.topology.morsesmale.MorseSmale
import afield.geometry.topology.morsesmale.MorseSmaleResult
import afield.math.linalg.Vec4
import afield.media.image.Img
import afield.media.image.gaussianBlur
import kotlin.math.hypot

/**
 * Following the topological features of a 2-D field from one moment to the next, so that a peak
 * keeps its identity while it moves, and comparing two fields by their topology alone.
 *
 * A Morse–Smale complex says where the peaks, pits and passes of one field are; it says nothing
 * about which peak of this frame is which peak of the last. That correspondence is an assignment
 * problem, and [FeatureMatching] solves it exactly for each moment, with a cost per match and a
 * fixed price for leaving a feature unmatched ([MatchingWeights]), so births and deaths are priced
 * in the same optimisation as the matches. [FeatureTracker] drives it frame by
 * frame and accumulates [Track]s; [ScaleSpace] runs the same matching over a blur series instead of
 * over time, where the scale a feature survives to is a measure of its size.
 *
 * The input is a list of [Feature]s, which [MorseSmaleResult.features] reads off a Morse–Smale
 * result and `VectorFieldTopology` supplies from a vector field's critical points, so the tracker
 * itself is indifferent to where the features came from.
 *
 * [persistenceDiagramDistance] answers the other question, about whole fields rather than single
 * features: how far apart two persistence diagrams are, exactly for diagrams of up to a few
 * thousand pairs and entropically for larger ones. Everything here is plain Kotlin and needs no
 * native library, though its usual source does. The worked account is
 * `public/docs/topology_tracking.md`.
 */

/**
 * A topological feature at one moment: a minimum, saddle or maximum with its position, value and
 * persistence (how much it stands out: the persistence of the persistence pair it belongs to).
 * [sourceId] is its id in the source it came from, e.g. a Morse–Smale cell.
 */
data class Feature(
    val type: CriticalType,
    val x: Double,
    val y: Double,
    val value: Double,
    val persistence: Double,
    val sourceId: Int = -1,
)

/**
 * The features of a Morse–Smale result whose persistence is at least [minPersistence]. Each critical
 * point gets the persistence of its pair in the persistence diagram; the global minimum, which has
 * no pair, gets infinity.
 */
fun MorseSmaleResult.features(minPersistence: Double = 0.0, types: Set<CriticalType> = setOf(CriticalType.MINIMUM, CriticalType.MAXIMUM)): List<Feature> {
    val persistenceOf = HashMap<Int, Double>()
    complex.persistencePairs.forEach { p ->
        persistenceOf[p.birth] = p.persistence
        p.death?.let { persistenceOf[it] = p.persistence }
    }
    return criticalPoints.filter { it.type in types }.mapNotNull { c ->
        val p = persistenceOf[c.id] ?: Double.POSITIVE_INFINITY
        if (p < minPersistence) null else Feature(c.type, c.x, c.y, c.value, p, c.id)
    }
}

/**
 * Weights for matching features between two moments.
 *
 * Matching two features costs `distanceWeight × distance² + valueWeight × (Δvalue² + Δpersistence²)`.
 * Leaving a feature unmatched, so that it dies or is born, costs `distanceWeight × birthDistance² +
 * valueWeight × persistence² / 2`: a fixed price in space, plus what it would cost to shrink the
 * feature to nothing (its distance to the diagonal of the persistence diagram). The persistence parts
 * are the lifted Wasserstein distance between persistence diagrams that Soler, Plainchault, Conche
 * and Tierny use for tracking (2018). So a feature dies rather than move more than about
 * `birthDistance × √2`, and a strong one is harder to kill than a faint one. Features further apart
 * than [maxDistance] are never matched, and features of different types never are.
 */
data class MatchingWeights(
    val distanceWeight: Double = 1.0,
    val valueWeight: Double = 1.0,
    val birthDistance: Double = 10.0,
    val maxDistance: Double = Double.POSITIVE_INFINITY,
)

/** Optimal matching between two sets of features: pairs of indices, null for born or died. */
object FeatureMatching {
    /**
     * Matches [before] to [after] at least total cost (see [MatchingWeights]). Returns one entry per
     * feature: (i, j) for a match, (i, null) for a feature that dies, (null, j) for one born.
     */
    fun match(before: List<Feature>, after: List<Feature>, weights: MatchingWeights = MatchingWeights()): List<Pair<Int?, Int?>> {
        val out = ArrayList<Pair<Int?, Int?>>()
        for (type in CriticalType.entries) {
            val a = before.indices.filter { before[it].type == type }
            val b = after.indices.filter { after[it].type == type }
            if (a.isEmpty() && b.isEmpty()) continue
            // Square problem of size |a| + |b|: real rows/columns plus a dummy per feature of the
            // other side standing for "unmatched", as in the standard Wasserstein reduction.
            val n = a.size + b.size
            val inf = 1e30
            val cost = Array(n) { DoubleArray(n) }
            fun diag(f: Feature) = if (f.persistence.isInfinite()) inf / 4
                else weights.distanceWeight * weights.birthDistance * weights.birthDistance + weights.valueWeight * f.persistence * f.persistence / 2
            for (i in 0 until n) for (j in 0 until n) {
                cost[i][j] = when {
                    i < a.size && j < b.size -> {
                        val p = before[a[i]]; val q = after[b[j]]
                        val d = hypot(p.x - q.x, p.y - q.y)
                        if (d > weights.maxDistance) inf else {
                            val dp = if (p.persistence.isInfinite() && q.persistence.isInfinite()) 0.0
                            else if (p.persistence.isInfinite() || q.persistence.isInfinite()) inf / 8
                            else p.persistence - q.persistence
                            weights.distanceWeight * d * d + weights.valueWeight * ((p.value - q.value).let { it * it } + dp * dp)
                        }
                    }
                    i < a.size -> diag(before[a[i]])          // a[i] dies
                    j < b.size -> diag(after[b[j]])           // b[j] is born
                    else -> 0.0                               // dummy to dummy
                }
            }
            val assignment = HungarianAssignment.solve(cost)
            for (i in 0 until n) {
                val j = assignment[i]
                when {
                    i < a.size && j < b.size && cost[i][j] < inf / 16 -> out.add(a[i] to b[j])
                    i < a.size -> out.add(a[i] to null)
                    j < b.size -> out.add(null to b[j])
                }
            }
            // a real-real assignment at infinite cost means both are unmatched
            for (i in 0 until n) {
                val j = assignment[i]
                if (i < a.size && j < b.size && cost[i][j] >= inf / 16) out.add(null to b[j])
            }
        }
        return out
    }
}

/** One feature followed through time (or scale): its samples, in order, and when it was born and died. */
class Track internal constructor(val id: Int, val type: CriticalType) {
    /** (time, feature) for every moment the feature was seen. */
    val samples = ArrayList<Pair<Double, Feature>>()

    val born: Double get() = samples.first().first
    val last: Double get() = samples.last().first

    /** Set when the feature failed to match at a later moment; null while it is still alive. */
    var died: Double? = null
        internal set

    val isAlive: Boolean get() = died == null

    /** The track's path as an FLine in the feature's own coordinates. */
    fun toFLine(): FLine = FLine().also { f ->
        samples.forEachIndexed { i, (_, s) -> if (i == 0) f.moveTo(s.x, s.y) else f.lineTo(s.x, s.y) }
        f.properties["trackId"] = id
    }

    override fun toString() = "Track($id, $type, ${samples.size} samples, $born..${died ?: last})"
}

/**
 * Follows features from moment to moment, giving each a track that persists for as long as the
 * feature can be matched, by [FeatureMatching], to one at the next moment.
 *
 * ```
 * val tracker = FeatureTracker(MatchingWeights(maxDistance = 20.0))
 * loop {
 *     val ms = MorseSmale.compute(fieldAt(frameNumber), persistence = 0.05)
 *     tracker.add(frameNumber.toDouble(), ms.features(minPersistence = 0.05))
 *     display("tracks", tracker.toFLines())
 * }
 * ```
 *
 * The same works across scale instead of time: see [ScaleSpace].
 */
class FeatureTracker(val weights: MatchingWeights = MatchingWeights()) {
    private val all = ArrayList<Track>()
    private var current: List<Pair<Track, Feature>> = emptyList()
    private var nextId = 0

    /** Every track so far, alive or dead. */
    val tracks: List<Track> get() = all

    /** The tracks alive after the latest [add], in the order of the features passed to it. */
    var alive: List<Track> = emptyList()
        private set

    /** Births and deaths in the latest [add]. */
    var born: List<Track> = emptyList()
        private set
    var died: List<Track> = emptyList()
        private set

    /**
     * Adds the features seen at [time], continuing, starting or ending tracks. Returns the tracks
     * alive now.
     *
     * [motion], if given, predicts where a feature from the previous moment has moved to by now: it
     * receives the feature's previous position and returns the displacement (dx, dy). Features are
     * matched from their predicted positions. Use it when things move further between moments than
     * the matching distances allow, with any motion estimate to hand: optical flow, the particles of
     * a particle video, a simulation's velocity field.
     */
    fun add(time: Double, features: List<Feature>, motion: ((x: Double, y: Double) -> Pair<Double, Double>)? = null): List<Track> {
        val previous = current.map { it.second }.map { f ->
            if (motion == null) f else motion(f.x, f.y).let { (dx, dy) -> f.copy(x = f.x + dx, y = f.y + dy) }
        }
        val pairs = FeatureMatching.match(previous, features, weights)
        val trackOf = arrayOfNulls<Track>(features.size)
        val newBorn = ArrayList<Track>(); val newDied = ArrayList<Track>()
        pairs.forEach { (i, j) ->
            when {
                i != null && j != null -> trackOf[j] = current[i].first
                i != null -> { current[i].first.died = time; newDied.add(current[i].first) }
                j != null -> Track(nextId++, features[j].type).also { all.add(it); newBorn.add(it); trackOf[j] = it }
            }
        }
        features.forEachIndexed { j, f -> trackOf[j]!!.samples.add(time to f) }
        current = features.indices.map { trackOf[it]!! to features[it] }
        alive = current.map { it.first }
        born = newBorn; died = newDied
        return alive
    }

    /** Every track's path as an FLine, coloured by track id, the alive ones ending in a dot. */
    fun toFLines(thickness: Double = 1.5, includeDead: Boolean = true): List<FLine> =
        (if (includeDead) all else alive).filter { it.samples.size >= 2 }.map { t ->
            t.toFLine().also { it.properties["color"] = colourOf(t.id); it.properties["thickness"] = thickness }
        }

    /** The alive features as dots, coloured by track id. */
    fun aliveFLine(pointSize: Double = 7.0): FLine = FLine().also { f ->
        current.forEach { (t, s) -> f.moveTo(s.x, s.y); f.nodes.last().properties["color"] = colourOf(t.id) }
        f.properties["pointed"] = true; f.properties["stroked"] = false; f.properties["pointSize"] = pointSize
    }

    /**
     * Every track as a curve of a [GeometryNetwork], in (x, y, time) when [threeD], else (x, y).
     * Curve id = track id; samples `time`, `value`, `persistence`; attributes `born`, `died`
     * (infinity while alive) and `type` (0 minimum, 1 saddle, 2 maximum).
     */
    fun network(threeD: Boolean = false): GeometryNetwork {
        val d = if (threeD) 3 else 2
        val curves = all.filter { it.samples.size >= 2 }.map { t ->
            val n = t.samples.size
            GeometryCurve(
                t.id, d,
                DoubleArray(n * d) { k -> val (time, s) = t.samples[k / d]; when (k % d) { 0 -> s.x; 1 -> s.y; else -> time } },
                attributes = mapOf("born" to t.born, "died" to (t.died ?: Double.POSITIVE_INFINITY), "type" to t.type.ordinal.toDouble()),
                samples = mapOf(
                    "time" to Channel.scalar(DoubleArray(n) { t.samples[it].first }),
                    "value" to Channel.scalar(DoubleArray(n) { t.samples[it].second.value }),
                    "persistence" to Channel.scalar(DoubleArray(n) { t.samples[it].second.persistence.coerceAtMost(1e300) }),
                ),
            )
        }
        return GeometryNetwork(d, GeometryNodes.empty(d), curves, mapOf("source" to "featureTracking"))
    }

    companion object {
        /** A stable, distinct colour for a track id. */
        fun colourOf(id: Int): Vec4 {
            val h = (id * -0x61c88647) ushr 8
            return Vec4(0.35f + 0.65f * ((h and 0xff) / 255f), 0.35f + 0.65f * (((h shr 8) and 0xff) / 255f), 0.35f + 0.65f * (((h shr 16) and 0xff) / 255f), 1f)
        }
    }
}

/**
 * Features followed across scale: the image blurred at each σ in turn, its Morse–Smale complex
 * computed and simplified at [persistence], and its extrema tracked from each σ to the next. A
 * feature that survives to large σ is large; one that dies early is fine detail.
 */
object ScaleSpace {
    class Result(
        val sigmas: List<Double>,
        val tracker: FeatureTracker,
        val complexes: List<MorseSmaleResult>,
    ) {
        /** The largest σ each track reached: its scale. */
        fun scaleOf(track: Track): Double = track.died?.let { d -> sigmas.last { it < d } } ?: track.last
    }

    /**
     * Tracks extrema of channel [channel] of [image] across [sigmas] (increasing). Features are kept
     * if their persistence is at least [persistence], and matched within [maxDistance] pixels.
     */
    fun track(
        image: Img,
        sigmas: List<Double>,
        persistence: Double = 0.02,
        maxDistance: Double = 12.0,
        channel: Int = 0,
        types: Set<CriticalType> = setOf(CriticalType.MINIMUM, CriticalType.MAXIMUM),
    ): Result {
        require(sigmas.zipWithNext().all { (a, b) -> b > a }) { "sigmas must increase" }
        val single = if (image.channels == 1) image else Img(image.width, image.height, 1, FloatArray(image.width * image.height) { image.data[it * image.channels + channel] })
        val tracker = FeatureTracker(MatchingWeights(maxDistance = maxDistance))
        val results = sigmas.map { s ->
            val r = MorseSmale.compute(if (s > 0) single.gaussianBlur(s) else single, persistence)
            tracker.add(s, r.features(persistence, types))
            r
        }
        return Result(sigmas, tracker, results)
    }
}

/**
 * The p-Wasserstein distance between two persistence diagrams, each a list of (birth, death) pairs:
 * the least total cost, over all matchings, of moving each pair of one onto a pair of the other or
 * onto the diagonal, with `|Δbirth|^p + |Δdeath|^p` per move and the result's p-th root. Pairs with an
 * infinite death are left out. Small distances mean two fields with similar topology.
 *
 * With no [epsilon] it is solved exactly with [HungarianAssignment], `O(n³)` in the total number of
 * pairs, so for diagrams up to a few thousand pairs. With an [epsilon] it uses the entropic
 * [afield.geometry.spatial.sinkhorn] solver on the same problem, `O(n²)` per sweep, which handles the
 * much larger diagrams of noisy images; as [epsilon] shrinks the answer approaches the exact one (a
 * large [epsilon] overestimates it). [epsilon] is in the units of the per-move cost (value^p).
 */
fun persistenceDiagramDistance(a: List<Pair<Double, Double>>, b: List<Pair<Double, Double>>, p: Double = 2.0, epsilon: Double? = null): Double {
    val x = a.filter { it.second.isFinite() }; val y = b.filter { it.second.isFinite() }
    val n = x.size + y.size
    if (n == 0) return 0.0
    fun pw(v: Double) = Math.pow(kotlin.math.abs(v), p)
    fun toDiagonal(q: Pair<Double, Double>) = 2 * pw((q.second - q.first) / 2)
    val cost = Array(n) { i -> DoubleArray(n) { j ->
        when {
            i < x.size && j < y.size -> pw(x[i].first - y[j].first) + pw(x[i].second - y[j].second)
            i < x.size -> toDiagonal(x[i])
            j < y.size -> toDiagonal(y[j])
            else -> 0.0
        }
    } }
    if (epsilon != null) {
        val plan = afield.geometry.spatial.sinkhorn(cost, epsilon)
        // the plan carries total mass 1 over n units, so its cost is the mean per pair
        return Math.pow(plan.cost * n, 1 / p)
    }
    val m = HungarianAssignment.solve(cost)
    return Math.pow((0 until n).sumOf { cost[it][m[it]] }, 1 / p)
}

/** The persistence diagram of a Morse–Smale result as (birth value, death value) pairs, in [dimension] 0 or 1. */
fun MorseSmaleResult.persistenceDiagram(dimension: Int): List<Pair<Double, Double>> =
    complex.persistencePairs.filter { it.dimension == dimension && it.death != null }.map { pp ->
        val b = complex.critical(pp.birth)!!.value; val d = complex.critical(pp.death!!)!!.value
        minOf(b, d) to maxOf(b, d)
    }
