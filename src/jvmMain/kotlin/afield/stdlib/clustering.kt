@file:Suppress("MemberVisibilityCanBePrivate")
@file:afield.CompletionCategory("Data")

package afield.stdlib

import afield.graphics.FLine
import smile.clustering.HierarchicalClustering
import smile.clustering.linkage.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Clustering for lists of `Float`, `Vec2`, `Vec3` and `Vec4`.
 *
 * Every algorithm here returns either a *hierarchy* or a centre drawn from the data it summarises: a
 * medoid is one of your own samples, and a median is coordinate-wise robust against an outlier.
 * **There is no k-means**, whose centroid is neither.
 *
 * Hierarchical clustering — [floatAgglomerative], [vec2Agglomerative], [vec3Agglomerative],
 * [vec4Agglomerative], which wrap Smile's `HierarchicalClustering` over the seven
 * [AgglomerativeLinkage]s — returns an [AgglomerativeClustering] holding the merge tree, which you
 * cut into a [ClusterLayer] with [AgglomerativeClustering.partition],
 * [AgglomerativeClustering.partitionByHeight] or [AgglomerativeClustering.layers]. The `…KMedoids`
 * and `…KMedians` functions are both local implementations in this file, each with D²-weighted
 * seeding and [KMedoidsOptions.runs]/[KMediansOptions.runs] restarts keeping the best objective:
 * k-medoids alternates assignment with an exact per-cluster re-medoid, k-medians is Lloyd's algorithm
 * on the L1 metric with a coordinate-wise median. Cuts of the merge tree come back with labels
 * renumbered by first appearance; every result type can be turned into a [ClusterLayer].
 *
 * [visualizeVec3ClusterLayers3D], [visualizeVec2ClusterLayers3D] and
 * [visualizeFloatClusterLayers3D] — plus the [AgglomerativeClustering.visualizeVec3]-style
 * extensions that build the layers for you — stack a list of [ClusterLayer]s along one axis and
 * return `FLine`s in a map keyed `"cluster-points"`, `"cluster-points-halo"`,
 * `"cluster-centers"`, `"cluster-centers-halo"`, `"cluster-links"` and `"cluster-guides"`.
 * [ClusterLayerVizOptions] controls spacing, sizes and which of those layers are emitted.
 */

/**
 * How the distance between two *clusters* is defined, which is the whole content of a hierarchical
 * clustering — the point distances are the same either way.
 *
 * `WARD` is the default because it minimises the increase in within-cluster variance at each merge
 * and so tends to produce compact, comparably-sized clusters, which is what a reader of a
 * dendrogram usually means by "cluster". `SINGLE` merges on the nearest pair and famously chains
 * along dense filaments; `COMPLETE` merges on the farthest pair and is compact but sensitive to one
 * far-out sample; `AVERAGE` (UPGMA) and `WEIGHTED_AVERAGE` (WPGMA) average all pairs, unweighted and
 * weighted by cluster size. `CENTROID` (UPGMC) and `MEDIAN` (WPGMC) measure between cluster centres,
 * and are the two that can produce *inversions* — a merge lower than one it contains — which makes
 * `AgglomerativeClustering.partitionByHeight` hard to interpret with them; cut by `k` instead.
 */
enum class AgglomerativeLinkage {
    SINGLE,
    COMPLETE,
    AVERAGE,
    WEIGHTED_AVERAGE,
    CENTROID,
    MEDIAN,
    WARD
}

/**
 * The metric the `…KMedoids` family assigns and re-medoids with.
 *
 * `MANHATTAN` (L1) is the default: it is the metric a medoid is a natural centre for, and it is less
 * moved by one wild coordinate than L2. `EUCLIDEAN` is there for when the distances have to agree
 * with a geometric picture. A [KMedoidsResult.distortion] is not comparable across the two.
 */
enum class MedoidDistance {
    EUCLIDEAN,
    MANHATTAN
}

/**
 * How the `…KMedoids` functions run.
 *
 * The per-property text below is what the reference and the completion panel show; the defaults are
 * chosen so that a call with no options is reproducible in shape (six restarts) but not in outcome
 * (no fixed seed) — set [randomSeed] the moment a picture has to be the same twice.
 */
data class KMedoidsOptions(
    /**
     * Most assign/re-medoid rounds per restart. The loop stops early the first time the medoid set
     * does not move, which is usually well inside ten rounds, so this is a safety bound rather than
     * a setting to tune.
     */
    val maxIterations: Int = 100,
    /**
     * Independent restarts from different D²-weighted seeds; the lowest-distortion one wins.
     *
     * Restarts are how this escapes a local optimum, because the exact re-medoid step never swaps a
     * medoid for a non-member. More restarts cost linearly and help most when `k` is large.
     */
    val runs: Int = 6,
    /** The metric, used both for assignment and for choosing each cluster's medoid. */
    val distance: MedoidDistance = MedoidDistance.MANHATTAN,
    /**
     * Fixes the seeding, so the whole thing is reproducible. `null` uses [Random.Default] and
     * successive calls may differ.
     */
    val randomSeed: Long? = null
)

/**
 * How the `…KMedians` functions run.
 *
 * Lloyd's algorithm on the L1 metric: assign, take each cluster's coordinate-wise median, repeat.
 * Unlike k-medoids the loop needs a numeric stopping rule, which is what [tolerance] is.
 */
data class KMediansOptions(
    /** Most assign/re-centre rounds per restart, as a safety bound. */
    val maxIterations: Int = 100,
    /**
     * Convergence threshold, applied to *both* the change in objective and the total L1 movement of
     * the centres — the round has to be quiet on both counts to stop. Must be finite and
     * non-negative.
     */
    val tolerance: Double = 1e-4,
    /** Independent restarts from different D²-weighted seeds; the lowest-objective one wins. */
    val runs: Int = 6,
    /** Fixes the seeding for reproducibility; `null` uses [Random.Default]. */
    val randomSeed: Long? = null
)

/**
 * One clustering of one dataset: a label per sample, and a name to draw it under.
 *
 * This is the common currency of the file — every result type converts to one, and the
 * `visualize…ClusterLayers3D` functions take a *list* of them so that several cuts of the same data
 * can be stacked and compared in one picture. Immutable: the labels are copied in.
 *
 * Labels must be non-empty and non-negative, and are conventionally dense and renumbered by first
 * appearance (which is what [AgglomerativeClustering.partition] hands you).
 */
class ClusterLayer(
    /** What this cut is called — `"k=4"`, `"h=0.3"`, `"kmedoids(k=3)"` by default. */
    val name: String,
    labels: IntArray
) {
    /** One cluster index per sample, in input order. A defensive copy: mutating it changes nothing. */
    val labels: IntArray = labels.copyOf()

    init {
        require(this.labels.isNotEmpty()) { "ClusterLayer.labels must not be empty" }
        this.labels.forEachIndexed { i, label ->
            require(label >= 0) { "ClusterLayer.labels[$i] must be >= 0, found $label" }
        }
    }

    /** How many samples were labelled. */
    val sampleCount: Int
        get() = labels.size

    /**
     * Highest label plus one — so an unused index in the middle still counts, and this is the length a
     * palette needs rather than the number of non-empty clusters.
     */
    val clusterCount: Int
        get() = (labels.maxOrNull() ?: -1) + 1

    /**
     * Which samples landed in [cluster], as indices into the original list. Empty for a cluster nothing
     * was assigned to.
     */
    fun indices(cluster: Int): IntArray {
        require(cluster >= 0) { "cluster must be >= 0, found $cluster" }
        val out = ArrayList<Int>()
        for (i in labels.indices) if (labels[i] == cluster) out.add(i)
        return out.toIntArray()
    }
}

/**
 * A whole merge hierarchy, not one clustering — the answer the `…Agglomerative` functions return.
 *
 * Hierarchical clustering does not need to be told `k`, which is its advantage: it merges until
 * everything is one cluster and records the order and the heights, and *then* you choose where to
 * cut. Cut by count with [partition], by dissimilarity with [partitionByHeight], or take several
 * cuts at once with [layers] / [defaultLayers] and stack them into one picture.
 *
 * The Smile model is copied on construction and every accessor copies again, so nothing a caller
 * does can invalidate the tree.
 */
class AgglomerativeClustering internal constructor(
    /** Which cluster-distance rule built this hierarchy. See [AgglomerativeLinkage]. */
    val linkage: AgglomerativeLinkage,
    rawModel: HierarchicalClustering
) {
    private val model = HierarchicalClustering(
        rawModel.tree().map { it.copyOf() }.toTypedArray(),
        rawModel.height().copyOf()
    )

    /**
     * How many samples were clustered — one more than the number of merges, since each merge removes a
     * cluster.
     */
    val sampleCount: Int
        get() = model.tree().size + 1

    /**
     * The merge tree, one `[left, right]` pair per merge in merge order.
     *
     * Indices below [sampleCount] are original samples; `sampleCount + i` is the cluster formed by merge
     * `i`. This is Smile's own encoding, handed back as a fresh copy.
     */
    val tree: Array<IntArray>
        get() = model.tree().map { it.copyOf() }.toTypedArray()

    /**
     * The dissimilarity at which each merge happened, parallel to [tree] — the y-axis of a dendrogram
     * and the scale [partitionByHeight] cuts on.
     */
    val heights: DoubleArray
        get() = model.height().copyOf()

    /**
     * Cut the hierarchy into exactly [k] clusters. [k] must be within `1..sampleCount`.
     *
     * Labels come back renumbered by first appearance, so cutting the same tree at different `k` gives
     * comparable colouring rather than Smile's arbitrary internal ids.
     */
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

    /**
     * Cut every branch taller than [height], letting the data decide how many clusters that is.
     *
     * The scale is [heights], which is in the linkage's own units and is *not* comparable across
     * linkages or across datasets. Use [partition] when you want a known number of clusters, and avoid
     * this with `CENTROID` or `MEDIAN` linkage, whose heights can be non-monotone.
     */
    fun partitionByHeight(height: Double, name: String = "h=$height"): ClusterLayer {
        require(height.isFinite()) {
            "AgglomerativeClustering.partitionByHeight: height must be finite, found $height"
        }
        val labels = model.partition(height)
        return ClusterLayer(name, canonicalizeLabels(labels))
    }

    /**
     * One [ClusterLayer] per requested `k`, ready to stack in a `visualize…` call.
     *
     * Forgiving: a `k` outside `1..sampleCount` is skipped rather than throwing, and repeats
     * are dropped, so a generous range like `2..20` is safe on a small point cloud.
     */
    fun layers(kValues: Iterable<Int>): List<ClusterLayer> {
        val used = LinkedHashSet<Int>()
        val out = ArrayList<ClusterLayer>()
        for (k in kValues) {
            if (k !in 1..sampleCount) continue
            if (used.add(k)) out += partition(k, "k=$k")
        }
        return out
    }

    /** Cuts at `2..maxK`, clamped to what the data can support — a sensible stack with no arguments. */
    fun defaultLayers(maxK: Int = 6): List<ClusterLayer> {
        val capped = max(2, min(maxK, sampleCount))
        return layers(2..capped)
    }
}

/**
 * What a `…KMedoids` call found: the labels, the medoids themselves, and enough to judge whether to
 * believe them.
 *
 * Read [converged] and [distortion] before reading anything into the clusters. `T` is the caller's
 * own point type, so the medoids come back as `Float`, `Vec2`, `Vec3` or `Vec4` rather than as rows.
 */
data class KMedoidsResult<T>(
    /** One cluster index per input sample, in input order, each in `0 until k`. */
    val labels: IntArray,
    /**
     * The chosen medoids themselves — always **actual input samples**, which is the whole point of
     * k-medoids over k-means: `medoids[c]` is `input[medoidIndices[c]]`.
     */
    val medoids: List<T>,
    /** Where each medoid sits in the input list, parallel to [medoids]. */
    val medoidIndices: IntArray,
    /**
     * The **sum** over samples of the distance to the assigned medoid, in
     * [KMedoidsOptions.distance]'s metric.
     *
     * Lower is better, and it is the quantity the restarts minimise. It is not comparable across
     * different `k` (more clusters always cost less) or across metrics, so it cannot be used to
     * choose `k`.
     */
    val distortion: Double,
    /** Assign/re-medoid rounds taken by the winning restart. */
    val iterations: Int,
    /**
     * `true` if the winning restart stopped because the medoid set repeated, rather than because it
     * hit [KMedoidsOptions.maxIterations]. A `false` here means the numbers below are a snapshot of
     * an unfinished run.
     */
    val converged: Boolean,
    /** How many samples each cluster holds. A zero is possible: an empty cluster keeps its medoid. */
    val clusterSizes: IntArray,
    /** The options this run used, carried along so a result is self-describing. */
    val options: KMedoidsOptions
) {
    init {
        require(labels.isNotEmpty()) { "KMedoidsResult.labels must not be empty" }
        require(medoids.isNotEmpty()) { "KMedoidsResult.medoids must not be empty" }
        require(medoidIndices.size == medoids.size) {
            "KMedoidsResult: medoidIndices.size (${medoidIndices.size}) must match medoids.size (${medoids.size})"
        }
    }

    /** How many clusters this result actually has. */
    val k: Int
        get() = medoids.size

    /** This result as a [ClusterLayer], so it can be stacked and drawn beside other cuts. */
    fun asLayer(name: String = "kmedoids(k=$k)"): ClusterLayer =
        ClusterLayer(name, labels)
}

/**
 * What a `…KMedians` call found.
 *
 * The difference from [KMedoidsResult] that matters: [centers] are *computed* coordinate-wise
 * medians and need not be any of your samples, so there is no index to look them up by. They are
 * still robust — a median moves with the ranks, not with the outliers — which is the reason to reach
 * for this over a mean-based centroid.
 */
data class KMediansResult<T>(
    /** One cluster index per input sample, in input order, each in `0 until k`. */
    val labels: IntArray,
    /** The coordinate-wise median of each cluster. Not necessarily an input sample. */
    val centers: List<T>,
    /**
     * Sum of L1 distances from every sample to its assigned centre. Lower is better, and it is what
     * the restarts minimise; as with [KMedoidsResult.distortion] it cannot be used to choose `k`.
     */
    val objective: Double,
    /** Assign/re-centre rounds taken by the winning restart. */
    val iterations: Int,
    /**
     * `true` if the winning restart stopped because both the objective and the centres went quiet
     * within [KMediansOptions.tolerance], rather than on [KMediansOptions.maxIterations].
     */
    val converged: Boolean,
    /** How many samples each cluster holds. An emptied cluster is re-seeded from the worst-fit sample. */
    val clusterSizes: IntArray,
    /** The options this run used. */
    val options: KMediansOptions
) {
    init {
        require(labels.isNotEmpty()) { "KMediansResult.labels must not be empty" }
        require(centers.isNotEmpty()) { "KMediansResult.centers must not be empty" }
    }

    /** How many clusters this result actually has. */
    val k: Int
        get() = centers.size

    /** This result as a [ClusterLayer], so it can be stacked and drawn beside other cuts. */
    fun asLayer(name: String = "kmedians(k=$k)"): ClusterLayer =
        ClusterLayer(name, labels)
}

/**
 * How a stack of [ClusterLayer]s is laid out and drawn.
 *
 * Sizes are in the units the `FLine` renderer uses for point size and thickness, alphas are 0..1, and
 * the three `draw…` flags decide which keys appear in the returned map at all — turning one off is
 * cheaper than drawing it and hiding it.
 */
data class ClusterLayerVizOptions(
    /** Distance between consecutive layers along the stacking axis. Zero superimposes them. */
    val layerSpacing: Float = 0.7f,
    /**
     * Extra separation between clusters *within* a layer — a fan-out that makes membership visible when
     * colour alone is not enough. Zero keeps every point at its own coordinates.
     */
    val clusterOffset: Float = 0.0f,
    /** Size of a sample's core dot. */
    val pointSize: Double = 0.1,
    /** Size of the soft halo drawn behind each sample, which is what makes dense regions read as bright. */
    val pointHaloSize: Double = 0.3,
    /** Size of a cluster centre's core dot — slightly larger than a sample's, so centres read as centres. */
    val centerPointSize: Double = 0.14,
    /** Size of the glow behind a cluster centre. Much larger than the others. */
    val centerHaloSize: Double = 22.0,
    /** Stroke thickness of the sample-to-centre links. */
    val linkThickness: Double = 0.85,
    /** Opacity of a sample's core dot. */
    val pointAlpha: Float = 0.92f,
    /** Opacity of the halos. Low, because they accumulate: overlap is the signal. */
    val haloAlpha: Float = 0.14f,
    /** Opacity of a cluster centre's core dot. */
    val centerAlpha: Float = 0.98f,
    /** Opacity of the links, low enough that a thousand of them still show shape rather than ink. */
    val linkAlpha: Float = 0.16f,
    /** Emit `cluster-links`: one segment from every sample to its cluster centre. */
    val drawLinks: Boolean = true,
    /** Emit `cluster-centers` and `cluster-centers-halo`. */
    val drawCenters: Boolean = true,
    /**
     * Emit `cluster-guides`: a rectangle around each layer, so the stacking is legible. Ignored by the
     * `Vec3` visualization, which has no plane to draw one on.
     */
    val drawGuides: Boolean = true
)

/**
 * Hierarchical clustering of one-dimensional values: build the whole merge tree now, choose where to cut later.
 *
 * Needs at least two samples, and every coordinate must be finite. Returns an
 * [AgglomerativeClustering], not a labelling — call `partition`, `partitionByHeight` or `layers`
 * on it. See [AgglomerativeLinkage] for what the default `WARD` assumes.
 */
fun floatAgglomerative(
    values: List<Float>,
    linkage: AgglomerativeLinkage = AgglomerativeLinkage.WARD
): AgglomerativeClustering {
    val rows = floatRows(values, "floatAgglomerative")
    return agglomerative(rows, linkage, "floatAgglomerative")
}

/**
 * Hierarchical clustering of 2-D points: build the whole merge tree now, choose where to cut later.
 *
 * Needs at least two samples, and every coordinate must be finite. Returns an
 * [AgglomerativeClustering], not a labelling — call `partition`, `partitionByHeight` or `layers`
 * on it. See [AgglomerativeLinkage] for what the default `WARD` assumes.
 */
fun vec2Agglomerative(
    points: List<Vec2>,
    linkage: AgglomerativeLinkage = AgglomerativeLinkage.WARD
): AgglomerativeClustering {
    val rows = vec2Rows(points, "vec2Agglomerative")
    return agglomerative(rows, linkage, "vec2Agglomerative")
}

/**
 * Hierarchical clustering of 3-D points: build the whole merge tree now, choose where to cut later.
 *
 * Needs at least two samples, and every coordinate must be finite. Returns an
 * [AgglomerativeClustering], not a labelling — call `partition`, `partitionByHeight` or `layers`
 * on it. See [AgglomerativeLinkage] for what the default `WARD` assumes.
 */
fun vec3Agglomerative(
    points: List<Vec3>,
    linkage: AgglomerativeLinkage = AgglomerativeLinkage.WARD
): AgglomerativeClustering {
    val rows = vec3Rows(points, "vec3Agglomerative")
    return agglomerative(rows, linkage, "vec3Agglomerative")
}

/**
 * Hierarchical clustering of 4-D points: build the whole merge tree now, choose where to cut later.
 *
 * Needs at least two samples, and every coordinate must be finite. Returns an
 * [AgglomerativeClustering], not a labelling — call `partition`, `partitionByHeight` or `layers`
 * on it. See [AgglomerativeLinkage] for what the default `WARD` assumes.
 */
fun vec4Agglomerative(
    points: List<Vec4>,
    linkage: AgglomerativeLinkage = AgglomerativeLinkage.WARD
): AgglomerativeClustering {
    val rows = vec4Rows(points, "vec4Agglomerative")
    return agglomerative(rows, linkage, "vec4Agglomerative")
}

/**
 * Partition one-dimensional values into [k] clusters, each summarised by one of your own samples.
 *
 * Reach for this over `…KMedians` when the centre has to *be* a real datum — a representative
 * frame, an exemplar colour, a point you can go and look at. [k] must be strictly less than the
 * sample count (the algorithm needs a non-medoid to assign), which is one more restriction than
 * `…KMedians` has.
 *
 * Cost is quadratic in the sample count: it builds and holds one full distance matrix, shared
 * across the restarts. Fine for thousands of points, not for a million.
 */
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
        iterations = core.iterations,
        converged = core.converged,
        clusterSizes = core.clusterSizes,
        options = options
    )
}

/**
 * Partition 2-D points into [k] clusters, each summarised by one of your own samples.
 *
 * Reach for this over `…KMedians` when the centre has to *be* a real datum — a representative
 * frame, an exemplar colour, a point you can go and look at. [k] must be strictly less than the
 * sample count (the algorithm needs a non-medoid to assign), which is one more restriction than
 * `…KMedians` has.
 *
 * Cost is quadratic in the sample count: it builds and holds one full distance matrix, shared
 * across the restarts. Fine for thousands of points, not for a million.
 */
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
        iterations = core.iterations,
        converged = core.converged,
        clusterSizes = core.clusterSizes,
        options = options
    )
}

/**
 * Partition 3-D points into [k] clusters, each summarised by one of your own samples.
 *
 * Reach for this over `…KMedians` when the centre has to *be* a real datum — a representative
 * frame, an exemplar colour, a point you can go and look at. [k] must be strictly less than the
 * sample count (the algorithm needs a non-medoid to assign), which is one more restriction than
 * `…KMedians` has.
 *
 * Cost is quadratic in the sample count: it builds and holds one full distance matrix, shared
 * across the restarts. Fine for thousands of points, not for a million.
 */
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
        iterations = core.iterations,
        converged = core.converged,
        clusterSizes = core.clusterSizes,
        options = options
    )
}

/**
 * Partition 4-D points into [k] clusters, each summarised by one of your own samples.
 *
 * Reach for this over `…KMedians` when the centre has to *be* a real datum — a representative
 * frame, an exemplar colour, a point you can go and look at. [k] must be strictly less than the
 * sample count (the algorithm needs a non-medoid to assign), which is one more restriction than
 * `…KMedians` has.
 *
 * Cost is quadratic in the sample count: it builds and holds one full distance matrix, shared
 * across the restarts. Fine for thousands of points, not for a million.
 */
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
        iterations = core.iterations,
        converged = core.converged,
        clusterSizes = core.clusterSizes,
        options = options
    )
}

/**
 * Partition one-dimensional values into [k] clusters around coordinate-wise medians (Lloyd's algorithm on L1).
 *
 * The robust alternative to a mean-based centroid, and the cheaper alternative to `…KMedoids`:
 * linear in the sample count per round rather than quadratic, and [k] may go all the way up to
 * the sample count. The price is that a centre is a computed point rather than one of your
 * samples. An emptied cluster is re-seeded from the worst-fit sample, so `k` clusters are always
 * reported.
 */
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

/**
 * Partition 2-D points into [k] clusters around coordinate-wise medians (Lloyd's algorithm on L1).
 *
 * The robust alternative to a mean-based centroid, and the cheaper alternative to `…KMedoids`:
 * linear in the sample count per round rather than quadratic, and [k] may go all the way up to
 * the sample count. The price is that a centre is a computed point rather than one of your
 * samples. An emptied cluster is re-seeded from the worst-fit sample, so `k` clusters are always
 * reported.
 */
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

/**
 * Partition 3-D points into [k] clusters around coordinate-wise medians (Lloyd's algorithm on L1).
 *
 * The robust alternative to a mean-based centroid, and the cheaper alternative to `…KMedoids`:
 * linear in the sample count per round rather than quadratic, and [k] may go all the way up to
 * the sample count. The price is that a centre is a computed point rather than one of your
 * samples. An emptied cluster is re-seeded from the worst-fit sample, so `k` clusters are always
 * reported.
 */
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

/**
 * Partition 4-D points into [k] clusters around coordinate-wise medians (Lloyd's algorithm on L1).
 *
 * The robust alternative to a mean-based centroid, and the cheaper alternative to `…KMedoids`:
 * linear in the sample count per round rather than quadratic, and [k] may go all the way up to
 * the sample count. The price is that a centre is a computed point rather than one of your
 * samples. An emptied cluster is re-seeded from the worst-fit sample, so `k` clusters are always
 * reported.
 */
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

/**
 * Draw a stack of clusterings of the same 3-D point cloud as `FLine`s.
 *
 * Layers are separated *radially* — each layer pushes its points a little further out from the
 * cloud's own centre — rather than along an axis, because a 3-D cloud has no free axis left. Point
 * colour blends the sample's position in the bounding box with its cluster's colour, and the blend
 * moves towards cluster colour in later layers, so structure emerging as `k` grows is visible as a
 * shift from rainbow to blocks.
 *
 * Returns a map keyed `"cluster-points"`, `"cluster-points-halo"`, `"cluster-centers"`,
 * `"cluster-centers-halo"` and `"cluster-links"` — one entry per non-empty layer, ready to assign
 * into a box's `lines`. No guides here; see [ClusterLayerVizOptions.drawGuides].
 *
 * Every layer must label exactly as many samples as there are points.
 */
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

/**
 * Draw a stack of clusterings of the same 2-D point cloud, one layer per z-plane.
 *
 * This is the arrangement to reach for when comparing cuts: the data keeps its own x and y, and z
 * carries "which clustering", so a point's movement between layers is only ever its cluster
 * changing. [ClusterLayerVizOptions.clusterOffset] additionally spreads clusters in z within a
 * layer, and a faint rectangle per layer (`"cluster-guides"`) marks the shared plane.
 *
 * Same map keys as [visualizeVec3ClusterLayers3D], plus `"cluster-guides"`.
 */
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

/**
 * Draw a stack of clusterings of the same one-dimensional data.
 *
 * The value itself is x, cluster membership becomes a band in y, and the layer is z — so a
 * one-dimensional dataset gets a picture at all, and the bands make it obvious when a cut splits a
 * run of near-equal values. Points are jittered in y by their rank so coincident values do not hide
 * each other. Every value must be finite.
 *
 * Same map keys as [visualizeVec2ClusterLayers3D].
 */
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

/**
 * Cut this hierarchy at each of [kValues] and draw the stack in one call — the two-step
 * [layers] + [visualizeVec3ClusterLayers3D] with the range filtered to what the data supports.
 */
fun AgglomerativeClustering.visualizeVec3(
    points: List<Vec3>,
    kValues: IntProgression = 2..6,
    options: ClusterLayerVizOptions = ClusterLayerVizOptions()
): Map<String, List<FLine>> {
    val ks = kValues.filter { it in 1..sampleCount }
    return visualizeVec3ClusterLayers3D(points, layers(ks), options)
}

/** Cut this hierarchy at each of [kValues] and draw the stack of 2-D layers in one call. */
fun AgglomerativeClustering.visualizeVec2(
    points: List<Vec2>,
    kValues: IntProgression = 2..6,
    options: ClusterLayerVizOptions = ClusterLayerVizOptions()
): Map<String, List<FLine>> {
    val ks = kValues.filter { it in 1..sampleCount }
    return visualizeVec2ClusterLayers3D(points, layers(ks), options)
}

/** Cut this hierarchy at each of [kValues] and draw the stack of 1-D layers in one call. */
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

private data class KMedoidsCoreResult(
    val labels: IntArray,
    val medoidIndices: IntArray,
    val distortion: Double,
    val iterations: Int,
    val converged: Boolean,
    val clusterSizes: IntArray
)

/**
 * k-medoids by alternating assignment and exact per-cluster re-medoiding, restarted from
 * D²-weighted seeds.
 *
 * # Why this is not Smile's `KMedoids`
 *
 * It used to be. Smile 5's `KMedoids.fit` is CLARANS, and it derives a `maxNeighbor` from the
 * `Clustering.Options` tolerance like this:
 *
 * ```
 * maxNeighbor = round(tol * k * (n - k))
 * maxNeighbor = max(min(100, k * (n - k)), maxNeighbor)
 * if (maxNeighbor > n) throw IllegalArgumentException("Too large maxNeighbor: " + maxNeighbor)
 * ```
 *
 * That `min(100, …)` floor is not reachable through any option, so for `k >= 2` and `k * (n - k) > n`
 * — which is every interesting case — the call throws unless `n >= 100`. Measured: `vec2KMedoids(90
 * points, k = 3)` with the default options threw `Too large maxNeighbor: 100`. The whole
 * `…KMedoids` family was therefore unusable on any point cloud smaller than a hundred samples, which
 * is most of the point clouds anyone hands it. Two of the old options were also inert: Smile clamps
 * `maxIter` to `min(3, maxIter)`, and `neighborRatio` only ever mattered when it pushed `maxNeighbor`
 * *above* the floor of 100 — never, at the default 0.0125.
 *
 * The replacement is the plain alternating algorithm ("Voronoi iteration"): assign every sample to
 * its nearest medoid, then replace each cluster's medoid by the member of that cluster with the
 * smallest total distance to the rest of it, and repeat until the medoid set stops moving. That step
 * is *exact* — it enumerates the cluster — so the distortion is monotonically non-increasing and the
 * loop terminates. It is not PAM: it will not escape a local optimum by swapping a medoid with a
 * non-member, which is what [KMedoidsOptions.runs] restarts are for.
 *
 * Cost: one `n × n` distance matrix, so O(n²) time and memory in the sample count, then O(n·k) per
 * assignment and O(n²/k) per re-medoid round. The matrix is shared across restarts.
 */
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

    val n = rows.size
    val metric: (DoubleArray, DoubleArray) -> Double = when (options.distance) {
        MedoidDistance.EUCLIDEAN -> ::l2
        MedoidDistance.MANHATTAN -> ::l1
    }
    val distances = Array(n) { DoubleArray(n) }
    for (i in 0 until n) {
        for (j in i + 1 until n) {
            val d = metric(rows[i], rows[j])
            distances[i][j] = d
            distances[j][i] = d
        }
    }

    var best: KMedoidsCoreResult? = null
    for (run in 0 until options.runs) {
        val rng = when (val seed = options.randomSeed) {
            null -> Random.Default
            else -> Random(seed + run)
        }
        val candidate = runKMedoids(distances, k, options.maxIterations, rng)
        if (best == null || candidate.distortion < best.distortion) best = candidate
    }
    return best!!
}

/** One restart of [kMedoids]: seed, then alternate until the medoid set repeats. */
private fun runKMedoids(
    distances: Array<DoubleArray>,
    k: Int,
    maxIterations: Int,
    rng: Random
): KMedoidsCoreResult {
    val n = distances.size
    var medoids = initMedoids(distances, k, rng)
    val labels = IntArray(n)
    val assigned = DoubleArray(n)
    var iterations = 0
    var converged = false

    while (iterations < maxIterations) {
        iterations++
        assignToMedoids(distances, medoids, labels, assigned)

        val next = IntArray(k)
        for (cluster in 0 until k) {
            var bestMember = -1
            var bestCost = Double.POSITIVE_INFINITY
            for (candidate in 0 until n) {
                if (labels[candidate] != cluster) continue
                var cost = 0.0
                for (other in 0 until n) {
                    if (labels[other] == cluster) cost += distances[candidate][other]
                }
                if (cost < bestCost) {
                    bestCost = cost
                    bestMember = candidate
                }
            }
            // An empty cluster keeps its medoid rather than vanishing, so `k` clusters are always
            // reported — the caller asked for k and gets k, even if one of them has no members.
            next[cluster] = if (bestMember >= 0) bestMember else medoids[cluster]
        }

        if (next.contentEquals(medoids)) {
            converged = true
            break
        }
        medoids = next
    }

    assignToMedoids(distances, medoids, labels, assigned)
    return KMedoidsCoreResult(
        labels = labels.copyOf(),
        medoidIndices = medoids.copyOf(),
        distortion = assigned.sum(),
        iterations = iterations,
        converged = converged,
        clusterSizes = clusterSizes(labels, k)
    )
}

/**
 * D²-weighted seeding over the precomputed [distances] — the k-means++ rule, on whichever metric
 * built the matrix. Picking the first medoid uniformly and then favouring far-away samples spreads
 * the seeds instead of clumping them, which is what makes a handful of restarts enough.
 */
private fun initMedoids(distances: Array<DoubleArray>, k: Int, rng: Random): IntArray {
    val n = distances.size
    if (k >= n) return IntArray(k) { it % n }

    val chosen = LinkedHashSet<Int>(k)
    val first = rng.nextInt(n)
    chosen.add(first)
    val nearest = DoubleArray(n) { distances[it][first] }

    while (chosen.size < k) {
        var total = 0.0
        for (i in 0 until n) {
            if (i in chosen) continue
            val w = nearest[i] * nearest[i]
            if (w.isFinite() && w > 0.0) total += w
        }

        var picked = -1
        if (total > 1e-12) {
            var r = rng.nextDouble() * total
            for (i in 0 until n) {
                if (i in chosen) continue
                val w = nearest[i] * nearest[i]
                if (w <= 0.0 || !w.isFinite()) continue
                r -= w
                if (r <= 0.0) {
                    picked = i
                    break
                }
            }
        }
        if (picked < 0) {
            // Either every remaining sample coincides with a chosen medoid, or the roulette ran off
            // the end of the weights through rounding. Any unchosen index will do.
            var pick = rng.nextInt(n)
            while (pick in chosen) pick = (pick + 1) % n
            picked = pick
        }
        val next: Int = picked

        chosen.add(next)
        for (i in 0 until n) {
            if (distances[i][next] < nearest[i]) nearest[i] = distances[i][next]
        }
    }

    return chosen.toIntArray()
}

private fun assignToMedoids(
    distances: Array<DoubleArray>,
    medoids: IntArray,
    labels: IntArray,
    assigned: DoubleArray
) {
    for (i in distances.indices) {
        var bestCluster = 0
        var bestDistance = Double.POSITIVE_INFINITY
        for (cluster in medoids.indices) {
            val d = distances[i][medoids[cluster]]
            if (d < bestDistance) {
                bestDistance = d
                bestCluster = cluster
            }
        }
        labels[i] = bestCluster
        assigned[i] = bestDistance
    }
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
        if (best == null || candidate.objective < best.objective) best = candidate
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
    require(options.maxIterations > 0) {
        "$fn: maxIterations must be > 0, found ${options.maxIterations}"
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
