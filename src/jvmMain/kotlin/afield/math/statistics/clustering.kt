@file:Suppress("MemberVisibilityCanBePrivate")
@file:afield.runtime.completion.CompletionCategory("Data")

package afield.math.statistics

import afield.animation.keyframes.h
import afield.animation.keyframes.roulette
import afield.data.collections.alphas
import afield.data.collections.get
import afield.data.collections.invoke
import afield.data.collections.times
import afield.document.context.Why
import afield.document.graph.minusAssign
import afield.document.graph.plusAssign
import afield.document.properties.compareTo
import afield.document.workspace.file
import afield.geometry.mesh.centroid
import afield.geometry.mesh.contains
import afield.geometry.mesh.repeated
import afield.graphics.render.rangeTo
import afield.lang.stacksl.h
import afield.math.autodiff.compareTo
import afield.math.autodiff.div
import afield.math.autodiff.dot
import afield.math.autodiff.floor
import afield.math.autodiff.mean
import afield.math.autodiff.minus
import afield.math.autodiff.plus
import afield.math.autodiff.round
import afield.math.autodiff.times
import afield.math.linalg.invoke
import afield.math.linalg.median
import afield.math.linalg.remAssign
import afield.math.linalg.times
import afield.math.units.div
import afield.math.units.minus
import afield.math.units.plus
import afield.math.units.times
import afield.media.image.median
import afield.media.image.threshold
import afield.runtime.execution.end
import afield.runtime.execution.go
import afield.runtime.lifecycle.once
import afield.simulation.agents.step
import afield.simulation.automata.step
import afield.study.ui.numbers
import afield.ui.output.h

import afield.audio.synth.*
import afield.data.collections.r
import afield.data.collections.s
import afield.geometry.mesh.bounds
import afield.geometry.spatial.center
import afield.graphics.color.okhsl
import afield.graphics.color.okhslSet
import afield.graphics.render.color
import afield.graphics.render.filled
import afield.graphics.render.fline
import afield.graphics.render.label
import afield.graphics.render.lineTo
import afield.graphics.render.moveTo
import afield.graphics.render.pointSize
import afield.graphics.render.pointed
import afield.graphics.render.stroked
import afield.graphics.render.thickness
import afield.graphics.vulkan.buffers.add
import afield.math.autodiff.maxOf
import afield.math.autodiff.sum
import afield.math.linalg.Vec2
import afield.math.linalg.Vec3
import afield.math.linalg.Vec4
import afield.math.linalg.d
import afield.math.linalg.i
import afield.math.linalg.set
import afield.math.linalg.vec
import afield.math.linalg.x
import afield.geometry.path.FLine
import afield.geometry.path.cubicSplineFrom
import afield.geometry.path.polylineFrom
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
 * Most algorithms here return either a *hierarchy* or a centre drawn from the data it summarises: a
 * medoid is one of your own samples, and a median is coordinate-wise robust against an outlier.
 * k-means ([floatKMeans], [vec2KMeans], [vec3KMeans], [vec4KMeans]) is the exception — its centroid
 * is a mean, which a single far sample pulls — and the one that scales: linear per epoch, with
 * [KMeansOptions.algorithm] switching between Lloyd's algorithm and e-means (mini-batch k-means with
 * scaling-law batch and step, ported from Faiss `contrib/e_means`; see [KMeansAlgorithm]).
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
 * Which k-means the `…KMeans` functions run. Both minimise the same thing — the sum of squared
 * distances from each sample to its cluster's mean — and differ in how they get there.
 *
 * `LLOYD` is the classical algorithm: assign every sample, move every centroid to its cluster's
 * exact mean, repeat. Each epoch is one full pass and one update.
 *
 * `EMEANS` is mini-batch k-means as described in "E-Means: Scaling Laws for Mini-Batch K-Means"
 * (Bessa et al., Meta FAIR, [arXiv:2611.09501](https://arxiv.org/abs/2611.09501),
 * <https://e-means.github.io>), ported from the MIT-licensed Faiss reference
 * `faiss/contrib/e_means`. Each mini-batch updates an exponential moving average of every
 * cluster's count and sum; the centroid is their ratio. The batch size and the initial step come
 * from fitted scaling laws in `n`, `k` and the epoch budget, the step anneals on a cosine from
 * `alpha0` to `0.1 * alpha0`, clusters whose mass falls below a threshold are revived by splitting a
 * large one, and the run closes on each cluster's exact mean over the last epoch. It makes many
 * updates per pass where Lloyd makes one, so it usually reaches a lower loss in fewer epochs.
 */
enum class KMeansAlgorithm {
    /** Full-batch assign / exact re-mean, one update per epoch. */
    LLOYD,
    /** Mini-batch EMA updates with scaling-law batch and step, cosine annealing and revival. */
    EMEANS
}

/** How the `…KMeans` functions pick their starting centroids. */
enum class KMeansSeeding {
    /** D²-weighted sampling (k-means++), the rule k-medoids and k-medians also seed with. */
    PLUS_PLUS,
    /** `k` distinct samples drawn uniformly — what the Faiss e-means reference does by default. */
    RANDOM,
    /**
     * Greedy k-means++, scikit-learn's default: each step draws `2 + ln k` D²-weighted candidates
     * and keeps the one that lowers the total squared distance most. Plain k-means++ with many
     * clusters in many dimensions often leaves a blob unseeded, because the spread inside the
     * blobs it has already covered outweighs the blob it missed; the greedy choice rarely does.
     * Costs `2 + ln k` times the seeding pass of [PLUS_PLUS].
     */
    GREEDY_PLUS_PLUS
}

/**
 * How the `…KMeans` functions run.
 *
 * Only [algorithm] and [epochs] usually matter. The e-means knobs default to `null`, which means
 * "use the published scaling law"; set them only to reproduce or ablate a run.
 *
 * @param algorithm Lloyd or e-means; see [KMeansAlgorithm].
 * @param epochs Full passes over the data. `null` is 25 for Lloyd and 5 for e-means (the Faiss
 * default). Lloyd may stop earlier when no label changes.
 * @param seeding How the starting centroids are chosen. The default, greedy k-means++, costs a
 * few seeding passes and avoids the commonest local minimum; see [KMeansSeeding].
 * @param randomSeed Fixes seeding, shuffling and revival for reproducibility; `null` uses
 * [Random.Default].
 * @param batchSize e-means only: rows per mini-batch. `null` uses the batch law.
 * @param alpha0 e-means only: the initial EMA step. `null` uses the step law at the chosen batch.
 * @param reviveThreshold e-means only: a cluster whose EMA mass is below this fraction of the mean
 * mass is revived by splitting a donor. `0.0` disables revival. `null` follows the Faiss default:
 * `0.1`, or `0.0` when the data has two or fewer dimensions.
 * @param recordHistory Compute the exact mean squared error after every epoch into
 * [KMeansResult.mseHistory]. Costs one extra assignment pass per e-means epoch.
 * @param spherical Cluster by direction (spherical k-means): every sample is scaled to unit length
 * before clustering and every centroid is put back on the unit sphere after each update, so
 * nearest-centroid is largest cosine similarity. Use it for embeddings and surface normals. A
 * zero-length sample is an error. [KMeansResult.inertia] is then measured on the unit vectors.
 * @param recordCentroids Keep a copy of the centroids at the seeds and after every epoch in
 * [KMeansResult.centroidHistory], to draw how they moved. Costs `k` copies per epoch.
 * @param runs Restarts from fresh seeds, keeping the run with the lowest inertia. k-means stops in
 * whichever local minimum its seeds lead to (two blobs sharing a centroid while another blob is
 * split in two); a few restarts usually escape it. Each run costs a whole clustering. Unlike
 * k-medoids and k-medians the default is 1, since k-means is the one used at scale.
 * @param recordPaths Keep every centroid's position after every update in
 * [KMeansResult.centroidPaths]: every mini-batch for e-means, every epoch for Lloyd. These are the
 * curves the centroids draw, for plotting rather than for measuring. Costs `k` points per update.
 * @param stepScale e-means only: multiplies the initial step, whether it came from the step law or
 * from [alpha0]; the result is capped at 1. Below 1 the centroids walk more slowly and more
 * smoothly, which with [recordPaths] and more [epochs] turns each path into a long drifting curve;
 * the clustering is then worse for the same number of epochs.
 * @param closeOnMean e-means only: end by moving each centroid to the exact mean of the samples it
 * collected in the last epoch (the reference algorithm's last step). Turn it off to keep a slowed
 * path smooth to its end instead of jumping there.
 */
data class KMeansOptions(
    val algorithm: KMeansAlgorithm = KMeansAlgorithm.EMEANS,
    val epochs: Int? = null,
    val seeding: KMeansSeeding = KMeansSeeding.GREEDY_PLUS_PLUS,
    val randomSeed: Long? = null,
    val batchSize: Int? = null,
    val alpha0: Double? = null,
    val reviveThreshold: Double? = null,
    val recordHistory: Boolean = true,
    val spherical: Boolean = false,
    val recordCentroids: Boolean = false,
    val runs: Int = 1,
    val recordPaths: Boolean = false,
    val stepScale: Double = 1.0,
    val closeOnMean: Boolean = true
)

/**
 * What a `…KMeans` call found.
 *
 * [centroids] are cluster *means*, so unlike a medoid they are generally not one of your samples, and
 * unlike a median a single far sample pulls them. [labels] are the exact nearest-centroid assignment
 * to the final [centroids], whichever algorithm ran.
 *
 * @param labels One cluster index per input sample, in input order, each in `0 until k`.
 * @param centroids The mean of each cluster.
 * @param inertia Sum of squared Euclidean distances from every sample to its centroid.
 * @param mseHistory Mean squared error (`inertia / n`) of the seeds at index 0 and after each epoch
 * after that, so its last entry equals `inertia / n`. Empty when
 * [KMeansOptions.recordHistory] is off.
 * @param epochs Epochs actually run.
 * @param converged Lloyd: `true` if it stopped because no label changed. e-means: always `false`,
 * since it runs its whole budget.
 * @param revivals e-means: how many dead clusters were revived by splitting. Lloyd: how many emptied
 * clusters were re-seeded from the worst-fit sample.
 * @param batchSize Rows per update: the mini-batch for e-means, the whole set for Lloyd.
 * @param alpha0 e-means: the initial EMA step used. Lloyd: `null`.
 * @param clusterSizes How many samples each cluster holds.
 * @param options The options this run used.
 * @param centroidHistory The centroids of the seeds at index 0 and after each epoch after that, so
 * entry `e` is what [mseHistory] entry `e` measured and the last entry equals [centroids]. Cluster
 * `c` is at index `c` in every entry, so a centroid's path is `centroidHistory.map { it[c] }`. Empty
 * unless [KMeansOptions.recordCentroids] is on.
 * @param centroidPaths One path per centroid, from its seed through its position after every update
 * to where it ended: `centroidPaths[c]` is the curve centroid `c` drew. Every path has the same
 * length, one point per update, so they are aligned in time; a centroid a batch did not touch
 * repeats its point. A revived centroid jumps. Empty unless [KMeansOptions.recordPaths] is on.
 */
data class KMeansResult<T>(
    val labels: IntArray,
    val centroids: List<T>,
    val inertia: Double,
    val mseHistory: DoubleArray,
    val epochs: Int,
    val converged: Boolean,
    val revivals: Int,
    val batchSize: Int,
    val alpha0: Double?,
    val clusterSizes: IntArray,
    val options: KMeansOptions,
    val centroidHistory: List<List<T>> = emptyList(),
    val centroidPaths: List<List<T>> = emptyList()
) {
    /** How many clusters this result has. */
    val k: Int
        get() = centroids.size

    /** `inertia` divided by the sample count. */
    val mse: Double
        get() = inertia / labels.size

    /** This result as a [ClusterLayer], so it can be stacked and drawn beside other cuts. */
    fun asLayer(name: String = "${options.algorithm.name.lowercase()}(k=$k)"): ClusterLayer =
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
 * Partition one-dimensional values into [k] clusters around their means (k-means).
 *
 * [KMeansOptions.algorithm] switches between Lloyd's algorithm and e-means; the default is e-means.
 * Linear in the sample count per epoch, so this is the clustering to reach for at hundreds of
 * thousands of samples.
 *
 * @param values The values to cluster.
 * @param k How many clusters.
 * @param options How to run; see [KMeansOptions].
 * @param seeds Where the centroids start, instead of [KMeansOptions.seeding]; `k` must equal
 * `seeds.size`. Every one of [KMeansOptions.runs] starts here.
 */
fun floatKMeans(
    values: List<Float>,
    k: Int,
    options: KMeansOptions = KMeansOptions(),
    seeds: List<Float>? = null
): KMeansResult<Float> {
    val core = kMeans(floatRows(values, "floatKMeans"), k, options, "floatKMeans", seeds?.let { floatRows(seeds, "floatKMeans seeds") })
    return core.toResult(options) { it[0].toFloat() }
}

/**
 * Partition 2-D points into [k] clusters around their means (k-means).
 *
 * [KMeansOptions.algorithm] switches between Lloyd's algorithm and e-means; the default is e-means.
 * Linear in the sample count per epoch, so this is the clustering to reach for at hundreds of
 * thousands of samples.
 *
 * @param points The points to cluster.
 * @param k How many clusters.
 * @param options How to run; see [KMeansOptions].
 * @param seeds Where the centroids start, instead of [KMeansOptions.seeding]; `k` must equal
 * `seeds.size`. Every one of [KMeansOptions.runs] starts here.
 */
fun vec2KMeans(
    points: List<Vec2>,
    k: Int,
    options: KMeansOptions = KMeansOptions(),
    seeds: List<Vec2>? = null
): KMeansResult<Vec2> {
    val core = kMeans(vec2Rows(points, "vec2KMeans"), k, options, "vec2KMeans", seeds?.let { vec2Rows(seeds, "vec2KMeans seeds") })
    return core.toResult(options) { v -> vec(v[0], v[1]) }
}

/**
 * Partition 3-D points into [k] clusters around their means (k-means).
 *
 * [KMeansOptions.algorithm] switches between Lloyd's algorithm and e-means; the default is e-means.
 * Linear in the sample count per epoch, so this is the clustering to reach for at hundreds of
 * thousands of samples.
 *
 * @param points The points to cluster.
 * @param k How many clusters.
 * @param options How to run; see [KMeansOptions].
 * @param seeds Where the centroids start, instead of [KMeansOptions.seeding]; `k` must equal
 * `seeds.size`. Every one of [KMeansOptions.runs] starts here.
 */
fun vec3KMeans(
    points: List<Vec3>,
    k: Int,
    options: KMeansOptions = KMeansOptions(),
    seeds: List<Vec3>? = null
): KMeansResult<Vec3> {
    val core = kMeans(vec3Rows(points, "vec3KMeans"), k, options, "vec3KMeans", seeds?.let { vec3Rows(seeds, "vec3KMeans seeds") })
    return core.toResult(options) { v -> vec(v[0], v[1], v[2]) }
}

/**
 * Partition 4-D points into [k] clusters around their means (k-means).
 *
 * [KMeansOptions.algorithm] switches between Lloyd's algorithm and e-means; the default is e-means.
 * Linear in the sample count per epoch, so this is the clustering to reach for at hundreds of
 * thousands of samples.
 *
 * @param points The points to cluster.
 * @param k How many clusters.
 * @param options How to run; see [KMeansOptions].
 * @param seeds Where the centroids start, instead of [KMeansOptions.seeding]; `k` must equal
 * `seeds.size`. Every one of [KMeansOptions.runs] starts here.
 */
fun vec4KMeans(
    points: List<Vec4>,
    k: Int,
    options: KMeansOptions = KMeansOptions(),
    seeds: List<Vec4>? = null
): KMeansResult<Vec4> {
    val core = kMeans(vec4Rows(points, "vec4KMeans"), k, options, "vec4KMeans", seeds?.let { vec4Rows(seeds, "vec4KMeans seeds") })
    return core.toResult(options) { v -> vec(v[0], v[1], v[2], v[3]) }
}

/**
 * Partition vectors of any length into [k] clusters around their means (k-means).
 *
 * The entry point for more than four dimensions: embeddings, feature vectors, colour histograms.
 * Every vector must have the same length. Above 16 dimensions e-means picks larger batches (the
 * "throughput" batch law; see [KMeansAlgorithm]). For embeddings compared by cosine similarity, set
 * [KMeansOptions.spherical]. The returned centroids are new arrays; [vectors] is not modified.
 *
 * @param vectors The vectors to cluster, all the same length.
 * @param k How many clusters.
 * @param options How to run; see [KMeansOptions].
 * @param seeds Where the centroids start, instead of [KMeansOptions.seeding]; `k` must equal
 * `seeds.size`. Every one of [KMeansOptions.runs] starts here.
 */
fun vectorKMeans(
    vectors: List<DoubleArray>,
    k: Int,
    options: KMeansOptions = KMeansOptions(),
    seeds: List<DoubleArray>? = null
): KMeansResult<DoubleArray> {
    require(vectors.isNotEmpty()) { "vectorKMeans: vectors must not be empty" }
    val start = seeds?.let { list -> Array(list.size) { list[it].copyOf() } }
    val core = kMeans(Array(vectors.size) { vectors[it].copyOf() }, k, options, "vectorKMeans", start)
    return core.toResult(options) { it.copyOf() }
}

/**
 * One level of a [KMeansTree]: the points that were clustered and what clustering them found.
 *
 * Level 0's [inputs] are your points. Every later level's [inputs] are the previous level's
 * centroids, so input `i` here is centroid `i` there, and its parent is centroid
 * `result.labels[i]` of this level.
 *
 * @param inputs The points this level clustered.
 * @param result The clustering, with [KMeansResult.centroidPaths] recorded.
 */
data class KMeansTreeLevel(val inputs: List<Vec2>, val result: KMeansResult<Vec2>)

/**
 * k-means applied to its own answer, level after level: cluster the points into `n / shrink`
 * centroids, cluster those centroids into `n / shrink²`, and so on down to one. Every centroid's
 * path from its seed to where it settled is kept, so the whole thing is a tree of curves: each
 * level's paths end at the points the next level starts from. Build it with [vec2KMeansTree] and
 * draw it with [toFLines].
 *
 * @param levels From the finest (your points) to the coarsest (a single centroid, unless the
 * level cap stopped it first).
 */
class KMeansTree(val levels: List<KMeansTreeLevel>) {
    /** How many clusterings were run. */
    val depth: Int
        get() = levels.size

    /**
     * The tree as `FLine`s: every centroid's path, plus thin connectors from each input to the
     * start of its parent's path (an input that seeded its parent connects to itself, so it draws
     * nothing). Paths are coloured by level, finest first in [palette], and thicken from
     * [thickness] by [thicknessGrowth] per level.
     *
     * @param smooth Draw each path as a Catmull-Rom spline through every [stride]-th point instead
     * of as a polyline through all of them.
     * @param stride Keep every `stride`-th point of a path (always keeping both ends).
     * @param connectors Draw the input-to-parent connectors.
     * @param connectorColor Colour of the connectors.
     * @param leaves Draw level 0's inputs (your points) as dots of [leafSize].
     * @param leafColor Colour of those dots.
     * @param palette One colour per level, reused cyclically.
     */
    fun toFLines(
        smooth: Boolean = true,
        stride: Int = 1,
        connectors: Boolean = true,
        connectorColor: Vec4 = vec(0.75, 0.8, 0.9, 0.35),
        leaves: Boolean = true,
        leafSize: Double = 1.0,
        leafColor: Vec4 = vec(0.75, 0.8, 0.9, 0.6),
        palette: List<Vec4> = okhslSet(maxOf(1, levels.size), saturation = 0.75, lightness = 0.66),
        thickness: Double = 0.8,
        thicknessGrowth: Double = 0.35,
    ): List<FLine> {
        require(stride >= 1) { "KMeansTree.toFLines: stride must be >= 1, found $stride" }
        val out = ArrayList<FLine>()
        if (leaves && levels.isNotEmpty()) {
            out += fline {
                levels[0].inputs.forEach { p -> moveTo(p.x.toDouble(), p.y.toDouble(), 0.0) }
                this.color = vec(leafColor.x, leafColor.y, leafColor.z, leafColor.w)
                this.pointed = true
                this.pointSize = leafSize
                this.stroked = false
                this.filled = false
            }
        }
        levels.forEachIndexed { level, (inputs, result) ->
            if (connectors) {
                out += fline {
                    inputs.forEachIndexed { i, p ->
                        val start = result.centroidPaths[result.labels[i]].first()
                        if (start.x != p.x || start.y != p.y) {
                            moveTo(p.x.toDouble(), p.y.toDouble(), 0.0)
                            lineTo(start.x.toDouble(), start.y.toDouble(), 0.0)
                        }
                    }
                    this.color = vec(connectorColor.x, connectorColor.y, connectorColor.z, connectorColor.w)
                    this.thickness = 0.5
                    this.stroked = true
                    this.filled = false
                }
            }
            val tint = palette[level % palette.size]
            for (path in result.centroidPaths) {
                val points = thinPath(path, stride)
                if (points.size < 2) continue
                out += (if (smooth && points.size > 2) cubicSplineFrom(points) else polylineFrom(points)).also {
                    it.color = vec(tint.x, tint.y, tint.z, tint.w)
                    it.thickness = thickness + thicknessGrowth * level
                    it.stroked = true
                    it.filled = false
                }
            }
        }
        return out
    }
}

/** [path] without repeated points, keeping every [stride]-th of the rest and always its last. */
private fun thinPath(path: List<Vec2>, stride: Int): List<Vec2> {
    val distinct = ArrayList<Vec2>(path.size)
    for (p in path) {
        val last = distinct.lastOrNull()
        if (last == null || last.x != p.x || last.y != p.y) distinct.add(p)
    }
    if (stride == 1 || distinct.size <= 2) return distinct
    val kept = distinct.filterIndexed { i, _ -> i % stride == 0 }.toMutableList()
    if (kept.last() !== distinct.last()) kept.add(distinct.last())
    return kept
}

/**
 * Cluster [points] into `n / shrink` clusters, then those centroids into `n / shrink²`, and so on
 * until one is left (or [maxLevels] clusterings have run), keeping every centroid's path. The
 * result is a [KMeansTree] of curves: draw it with [KMeansTree.toFLines].
 *
 * [options] apply at every level, with [KMeansOptions.recordPaths] forced on and the random seed
 * advanced by one per level. The defaults use e-means for 3 epochs. For slower, longer curves lower
 * [KMeansOptions.stepScale] and raise [KMeansOptions.epochs]; with [KMeansOptions.closeOnMean]
 * off each path ends smoothly instead of jumping to its cluster's mean. Every level weighs each
 * centroid below it equally, however many points it holds.
 *
 * @param shrink How many inputs per cluster at each level: 2 halves the count every level, 3 cuts
 * it to a third. Must be more than 1.
 * @param maxLevels At most this many clusterings.
 */
fun vec2KMeansTree(
    points: List<Vec2>,
    shrink: Double = 2.0,
    options: KMeansOptions = KMeansOptions(epochs = 3),
    maxLevels: Int = 64,
): KMeansTree {
    require(points.isNotEmpty()) { "vec2KMeansTree: points must not be empty" }
    require(shrink.isFinite() && shrink > 1.0) { "vec2KMeansTree: shrink must be finite and > 1, found $shrink" }
    require(maxLevels >= 1) { "vec2KMeansTree: maxLevels must be >= 1, found $maxLevels" }
    val levels = ArrayList<KMeansTreeLevel>()
    var inputs = points
    while (levels.size < maxLevels && inputs.size > 1) {
        val k = maxOf(1, (inputs.size / shrink).toInt())
        val seed = options.randomSeed?.plus(levels.size)
        val result = vec2KMeans(inputs, k, options.copy(recordPaths = true, randomSeed = seed))
        levels += KMeansTreeLevel(inputs, result)
        inputs = result.centroids
    }
    return KMeansTree(levels)
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
    var centers = initD2Centers(rows, k, rng, ::l1)
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

/**
 * D²-weighted seeding (the k-means++ rule) over [rows], with [distance] as the metric: each next
 * centre is drawn with probability proportional to the squared [distance] to its nearest chosen one.
 * k-medians seeds with `l1`, k-means with `l2`.
 */
private fun initD2Centers(
    rows: Array<DoubleArray>,
    k: Int,
    rng: Random,
    distance: (DoubleArray, DoubleArray) -> Double
): Array<DoubleArray> {
    if (k == rows.size) return Array(k) { i -> rows[i].copyOf() }

    val chosen = LinkedHashSet<Int>(k)
    val first = rng.nextInt(rows.size)
    chosen.add(first)

    val minDist = DoubleArray(rows.size) { i -> distance(rows[i], rows[first]) }
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
            val d = distance(rows[i], rows[next])
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

// ---- k-means: Lloyd and e-means ---------------------------------------------------------------
//
// The e-means constants below are those shipped in Faiss `contrib/e_means/base.py` (MIT, Meta
// Platforms), which its comments say differ from the paper's Table 2 because they were refitted.

/** Cosine floor: the step anneals from alpha0 to this fraction of it (paper Eq. 5). */
private const val EMEANS_ALPHA_MIN_FACTOR = 0.1

/** Batch updates between revival checks (Algorithm 1, line 9). */
private const val EMEANS_CHECK_UNUSED_EVERY = 5

/** Initial EMA mass N_j. Faiss uses 2^-16; the paper 1e-10. */
private const val EMEANS_INIT_USAGE = 1.0 / 65536.0

/** Relative coordinate perturbation of a split (Appendix J, Eq. 8). */
private const val EMEANS_SPLIT_EPSILON = 1e-4

/** Revival threshold tau: revive N_j < tau * mean(N). */
private const val EMEANS_UNUSED_THRESHOLD = 0.1

/** At or below this dimension the default threshold is 0: Faiss measured revival hurting 2-D data. */
private const val EMEANS_NO_REVIVAL_MAX_D = 2

/** At or below this dimension the "quality" batch law is used, above it "throughput". */
private const val EMEANS_AUTO_QUALITY_MAX_D = 16

/** Batch law B = C * k^bk * n^bn * T^bT, as (C, bk, bn, bT). */
private val EMEANS_THROUGHPUT_LAW = doubleArrayOf(0.035134, 0.134749, 0.876473, 0.813344)
private val EMEANS_QUALITY_LAW = doubleArrayOf(0.00439175, 0.134749, 0.876473, 0.813344)

/** Step law alpha0 = C * k^ak * B^aB * n^an * T^aT, as (C, ak, aB, an, aT), clamped to [1e-4, 1]. */
private val EMEANS_ALPHA_LAW = doubleArrayOf(0.8244, -0.4617, 0.9003, -0.4498, -0.1057)

private class KMeansCore(
    val labels: IntArray,
    val centroids: Array<DoubleArray>,
    val inertia: Double,
    val mseHistory: DoubleArray,
    val epochs: Int,
    val converged: Boolean,
    val revivals: Int,
    val batchSize: Int,
    val alpha0: Double?,
    val centroidHistory: List<Array<DoubleArray>>,
    val paths: List<List<DoubleArray>>
) {
    fun <T> toResult(options: KMeansOptions, convert: (DoubleArray) -> T): KMeansResult<T> =
        KMeansResult(
            labels = labels,
            centroids = centroids.map(convert),
            inertia = inertia,
            mseHistory = mseHistory,
            epochs = epochs,
            converged = converged,
            revivals = revivals,
            batchSize = batchSize,
            alpha0 = alpha0,
            clusterSizes = clusterSizes(labels, centroids.size),
            options = options,
            centroidHistory = centroidHistory.map { epoch -> epoch.map(convert) },
            centroidPaths = paths.map { path -> path.map(convert) }
        )
}

private fun kMeans(
    rows: Array<DoubleArray>,
    k: Int,
    options: KMeansOptions,
    fn: String,
    givenSeeds: Array<DoubleArray>? = null
): KMeansCore {
    validateRows(rows, fn, minSamples = 1)
    require(k in 1..rows.size) { "$fn: k must be in [1, ${rows.size}], found $k" }
    if (givenSeeds != null) {
        require(givenSeeds.size == k) { "$fn: k is $k but ${givenSeeds.size} seeds were given" }
        validateRows(givenSeeds, "$fn seeds", minSamples = 1)
        require(givenSeeds[0].size == rows[0].size) {
            "$fn: seeds have ${givenSeeds[0].size} dimensions, samples have ${rows[0].size}"
        }
        if (options.spherical) {
            givenSeeds.forEachIndexed { i, seed ->
                require(normalizeInPlace(seed)) { "$fn: spherical needs non-zero seeds, seed $i has length 0" }
            }
        }
    }
    options.epochs?.let { require(it > 0) { "$fn: epochs must be > 0, found $it" } }
    options.batchSize?.let { require(it > 0) { "$fn: batchSize must be > 0, found $it" } }
    options.alpha0?.let { require(it > 0.0 && it <= 1.0) { "$fn: alpha0 must be in (0, 1], found $it" } }
    require(options.stepScale.isFinite() && options.stepScale > 0.0) {
        "$fn: stepScale must be finite and > 0, found ${options.stepScale}"
    }
    options.reviveThreshold?.let {
        require(it.isFinite() && it >= 0.0) { "$fn: reviveThreshold must be finite and >= 0, found $it" }
    }
    if (options.spherical) {
        rows.forEachIndexed { i, row ->
            require(normalizeInPlace(row)) { "$fn: spherical needs non-zero samples, row $i has length 0" }
        }
    }
    require(options.runs > 0) { "$fn: runs must be > 0, found ${options.runs}" }
    val rng = options.randomSeed?.let { Random(it) } ?: Random.Default
    var best: KMeansCore? = null
    repeat(options.runs) {
        val seeds = if (givenSeeds != null) snapshot(givenSeeds) else when (options.seeding) {
            KMeansSeeding.PLUS_PLUS -> initD2Centers(rows, k, rng, ::l2)
            KMeansSeeding.RANDOM -> randomCenters(rows, k, rng)
            KMeansSeeding.GREEDY_PLUS_PLUS -> greedyPlusPlusCenters(rows, k, rng)
        }
        val run = when (options.algorithm) {
            KMeansAlgorithm.LLOYD -> runLloyd(rows, seeds, options.epochs ?: 25, options)
            KMeansAlgorithm.EMEANS -> runEmeans(rows, seeds, options.epochs ?: 5, options, rng)
        }
        if (best == null || run.inertia < best!!.inertia) best = run
    }
    return best!!
}

/**
 * Scale [v] to unit length; returns `false` (leaving it unchanged) when it has length 0. Spherical
 * k-means calls this on every sample once and on every centroid after each update.
 */
private fun normalizeInPlace(v: DoubleArray): Boolean {
    var s = 0.0
    for (x in v) s += x * x
    if (s <= 0.0 || !s.isFinite()) return false
    val inv = 1.0 / sqrt(s)
    for (j in v.indices) v[j] *= inv
    return true
}

private fun snapshot(centroids: Array<DoubleArray>): Array<DoubleArray> = Array(centroids.size) { centroids[it].copyOf() }

/** One growing path per centroid, for [KMeansOptions.recordPaths]; `null` when off. */
private fun newPaths(centroids: Array<DoubleArray>, on: Boolean): List<ArrayList<DoubleArray>>? =
    if (!on) null else centroids.map { arrayListOf(it.copyOf()) }

private fun extend(paths: List<ArrayList<DoubleArray>>?, centroids: Array<DoubleArray>) {
    if (paths == null) return
    for (c in centroids.indices) paths[c].add(centroids[c].copyOf())
}

/**
 * Greedy k-means++ (Arthur & Vassilvitskii's D² sampling with local trials, as in scikit-learn):
 * at each step sample `2 + floor(ln k)` candidates with probability proportional to the squared
 * distance to the nearest chosen centre, and keep the candidate whose addition leaves the smallest
 * total squared distance.
 */
private fun greedyPlusPlusCenters(rows: Array<DoubleArray>, k: Int, rng: Random): Array<DoubleArray> {
    val n = rows.size
    if (k == n) return Array(k) { rows[it].copyOf() }
    val trials = 2 + kotlin.math.ln(k.toDouble()).toInt()
    val chosen = ArrayList<Int>(k)
    val first = rng.nextInt(n)
    chosen.add(first)
    val minD2 = DoubleArray(n) { squaredL2(rows[it], rows[first]) }
    var potential = minD2.sum()
    val candidateD2 = DoubleArray(n)
    val bestD2 = DoubleArray(n)
    while (chosen.size < k) {
        var bestIndex = -1
        var bestPotential = Double.POSITIVE_INFINITY
        repeat(trials) {
            val candidate = if (potential <= 0.0) {
                rng.nextInt(n)
            } else {
                var r = rng.nextDouble() * potential
                var pick = n - 1
                for (i in 0 until n) {
                    r -= minD2[i]
                    if (r <= 0.0) {
                        pick = i
                        break
                    }
                }
                pick
            }
            var total = 0.0
            for (i in 0 until n) {
                val d = min(minD2[i], squaredL2(rows[i], rows[candidate]))
                candidateD2[i] = d
                total += d
            }
            if (total < bestPotential) {
                bestPotential = total
                bestIndex = candidate
                candidateD2.copyInto(bestD2)
            }
        }
        chosen.add(bestIndex)
        bestD2.copyInto(minD2)
        potential = bestPotential
    }
    // Duplicate picks are possible only when every remaining sample coincides with a centre already.
    return Array(k) { rows[chosen[it]].copyOf() }
}

private fun randomCenters(rows: Array<DoubleArray>, k: Int, rng: Random): Array<DoubleArray> {
    val order = IntArray(rows.size) { it }
    shuffleInPlace(order, rng)
    return Array(k) { rows[order[it]].copyOf() }
}

private fun shuffleInPlace(a: IntArray, rng: Random) {
    for (i in a.size - 1 downTo 1) {
        val j = rng.nextInt(i + 1)
        val t = a[i]
        a[i] = a[j]
        a[j] = t
    }
}

private fun squaredL2(a: DoubleArray, b: DoubleArray): Double {
    var s = 0.0
    for (i in a.indices) {
        val d = a[i] - b[i]
        s += d * d
    }
    return s
}

/** Nearest centroid of [row] by squared L2; writes the squared distance into [out] at [slot]. */
private fun nearestL2(row: DoubleArray, centroids: Array<DoubleArray>, out: DoubleArray, slot: Int): Int {
    var best = 0
    var bestD = Double.POSITIVE_INFINITY
    for (c in centroids.indices) {
        val d = squaredL2(row, centroids[c])
        if (d < bestD) {
            bestD = d
            best = c
        }
    }
    out[slot] = bestD
    return best
}

/** Exact assignment; returns the inertia. */
private fun assignL2(rows: Array<DoubleArray>, centroids: Array<DoubleArray>, labels: IntArray, d2: DoubleArray): Double {
    var total = 0.0
    for (i in rows.indices) {
        labels[i] = nearestL2(rows[i], centroids, d2, i)
        total += d2[i]
    }
    return total
}

private fun runLloyd(rows: Array<DoubleArray>, seeds: Array<DoubleArray>, epochs: Int, options: KMeansOptions): KMeansCore {
    val recordHistory = options.recordHistory
    val spherical = options.spherical
    val n = rows.size
    val d = rows[0].size
    val k = seeds.size
    val centroids = Array(k) { seeds[it].copyOf() }
    val labels = IntArray(n)
    val d2 = DoubleArray(n)
    var inertia = assignL2(rows, centroids, labels, d2)
    val history = ArrayList<Double>()
    if (recordHistory) history.add(inertia / n)
    val trail = ArrayList<Array<DoubleArray>>()
    if (options.recordCentroids) trail.add(snapshot(centroids))
    val paths = newPaths(centroids, options.recordPaths)
    var converged = false
    var revivals = 0
    var epoch = 0
    val previous = IntArray(n)
    while (epoch < epochs) {
        epoch++
        val counts = IntArray(k)
        val sums = Array(k) { DoubleArray(d) }
        for (i in 0 until n) {
            val c = labels[i]
            counts[c]++
            val row = rows[i]
            val s = sums[c]
            for (j in 0 until d) s[j] += row[j]
        }
        var replacements: IntArray? = null
        var cursor = 0
        for (c in 0 until k) {
            if (counts[c] > 0) {
                for (j in 0 until d) centroids[c][j] = sums[c][j] / counts[c]
                // A cluster of opposite directions can average to zero; keep it where it was then.
                if (spherical && !normalizeInPlace(centroids[c])) centroids[c] = rows[labelsOf(labels, c)].copyOf()
            } else {
                val order = replacements ?: farthestReplacementOrder(d2).also { replacements = it }
                centroids[c] = rows[order[cursor % order.size]].copyOf()
                cursor++
                revivals++
            }
        }
        labels.copyInto(previous)
        inertia = assignL2(rows, centroids, labels, d2)
        if (recordHistory) history.add(inertia / n)
        if (options.recordCentroids) trail.add(snapshot(centroids))
        extend(paths, centroids)
        if (previous.contentEquals(labels)) {
            converged = true
            break
        }
    }
    return KMeansCore(labels, centroids, inertia, history.toDoubleArray(), epoch, converged, revivals, n, null, trail, paths.orEmpty())
}

/** The first sample labelled [c]; only called for a cluster known to be non-empty. */
private fun labelsOf(labels: IntArray, c: Int): Int = labels.indexOfFirst { it == c }

private fun roundPowerOfTwo(value: Double): Int {
    if (value <= 1.0) return 1
    val low = 1 shl kotlin.math.floor(kotlin.math.log2(value)).toInt()
    val high = 2 * low
    return if (value / low < high / value) low else high
}

/** The Faiss `_resolve_recipe`: batch size from the batch law, then alpha0 from the step law. */
private fun emeansRecipe(n: Int, k: Int, d: Int, epochs: Int, options: KMeansOptions): Pair<Int, Double> {
    val batch = options.batchSize?.let { min(it, n) } ?: run {
        val quality = d <= EMEANS_AUTO_QUALITY_MAX_D
        val law = if (quality) EMEANS_QUALITY_LAW else EMEANS_THROUGHPUT_LAW
        val raw = law[0] * Math.pow(k.toDouble(), law[1]) * Math.pow(n.toDouble(), law[2]) *
            Math.pow(epochs.toDouble(), law[3])
        var b = roundPowerOfTwo(raw)
        b = if (quality) min(b, n / 2) else min(b, roundPowerOfTwo(n / 2.0))
        max(1, min(b, n))
    }
    val alpha0 = options.alpha0 ?: run {
        val law = EMEANS_ALPHA_LAW
        val a = law[0] * Math.pow(k.toDouble(), law[1]) * Math.pow(batch.toDouble(), law[2]) *
            Math.pow(n.toDouble(), law[3]) * Math.pow(epochs.toDouble(), law[4])
        min(max(a, 1e-4), 1.0)
    }
    return batch to alpha0
}

private fun scheduledAlpha(alpha0: Double, step: Int, totalSteps: Int): Double {
    val fraction = min(step, totalSteps).toDouble() / max(1, totalSteps)
    val cosine = 0.5 * (1.0 + kotlin.math.cos(Math.PI * fraction))
    return alpha0 * (EMEANS_ALPHA_MIN_FACTOR + (1.0 - EMEANS_ALPHA_MIN_FACTOR) * cosine)
}

private fun runEmeans(
    rows: Array<DoubleArray>,
    seeds: Array<DoubleArray>,
    epochs: Int,
    options: KMeansOptions,
    rng: Random
): KMeansCore {
    val n = rows.size
    val d = rows[0].size
    val k = seeds.size
    val (batch, lawAlpha0) = emeansRecipe(n, k, d, epochs, options)
    val alpha0 = min(1.0, lawAlpha0 * options.stepScale)
    val tau = options.reviveThreshold ?: if (d <= EMEANS_NO_REVIVAL_MAX_D) 0.0 else EMEANS_UNUSED_THRESHOLD
    val stepsPerEpoch = (n + batch - 1) / batch
    val totalSteps = stepsPerEpoch * epochs

    val centroids = Array(k) { seeds[it].copyOf() }
    val usage = DoubleArray(k) { EMEANS_INIT_USAGE }
    val labels = IntArray(n)
    val d2 = DoubleArray(n)
    val history = ArrayList<Double>()
    if (options.recordHistory) history.add(assignL2(rows, centroids, labels, d2) / n)
    val trail = ArrayList<Array<DoubleArray>>()
    if (options.recordCentroids) trail.add(snapshot(centroids))
    val paths = newPaths(centroids, options.recordPaths)
    val spherical = options.spherical

    val order = IntArray(n) { it }
    val batchCounts = IntArray(k)
    val batchSums = Array(k) { DoubleArray(d) }
    val scratch = DoubleArray(1)
    var step = 0
    var revivals = 0
    for (epoch in 0 until epochs) {
        shuffleInPlace(order, rng)
        val last = epoch == epochs - 1
        val passCounts = if (last) LongArray(k) else null
        val passSums = if (last) Array(k) { DoubleArray(d) } else null
        var start = 0
        while (start < n) {
            val end = min(start + batch, n)
            batchCounts.fill(0)
            for (s in batchSums) s.fill(0.0)
            // Algorithm 1, lines 6-7: assign the batch against one snapshot, collect n_j and s_j.
            for (p in start until end) {
                val row = rows[order[p]]
                val c = nearestL2(row, centroids, scratch, 0)
                batchCounts[c]++
                val s = batchSums[c]
                for (j in 0 until d) s[j] += row[j]
            }
            if (passCounts != null && passSums != null) {
                for (c in 0 until k) {
                    passCounts[c] += batchCounts[c].toLong()
                    val ps = passSums[c]
                    val bs = batchSums[c]
                    for (j in 0 until d) ps[j] += bs[j]
                }
            }
            // Line 8: S_j <- (1-a) S_j + a s_j, N_j <- (1-a) N_j + a n_j, c_j = S_j / N_j.
            val alpha = scheduledAlpha(alpha0, step, totalSteps)
            val decay = 1.0 - alpha
            for (c in 0 until k) {
                val nj = batchCounts[c]
                if (nj == 0) {
                    usage[c] *= decay
                    continue
                }
                val mass = if (alpha >= 1.0) nj.toDouble() else decay * usage[c] + alpha * nj
                val w = alpha * nj / mass
                val cc = centroids[c]
                val bs = batchSums[c]
                for (j in 0 until d) cc[j] += w * (bs[j] / nj - cc[j])
                if (spherical && !normalizeInPlace(cc)) {
                    // Exactly cancelling directions: fall back to the direction of the batch mean.
                    for (j in 0 until d) cc[j] = bs[j]
                    if (!normalizeInPlace(cc)) cc[0] = 1.0
                }
                usage[c] = mass
            }
            // Line 9: every few updates, revive clusters whose mass has collapsed.
            if (tau > 0.0 && (step + 1) % EMEANS_CHECK_UNUSED_EVERY == 0) {
                val revived = reviveUnused(centroids, usage, tau, rng)
                if (spherical && revived > 0) centroids.forEach { normalizeInPlace(it) }
                revivals += revived
            }
            extend(paths, centroids)
            step++
            start = end
        }
        if (options.closeOnMean && passCounts != null && passSums != null) {
            // Line 12: close on each cluster's exact mean over the last epoch.
            for (c in 0 until k) {
                if (passCounts[c] == 0L) continue
                val mean = DoubleArray(d) { j -> passSums[c][j] / passCounts[c] }
                if (spherical && !normalizeInPlace(mean)) continue
                centroids[c] = mean
            }
            extend(paths, centroids)
        }
        if (options.recordHistory && !last) history.add(assignL2(rows, centroids, labels, d2) / n)
        if (options.recordCentroids) trail.add(snapshot(centroids))
    }
    val inertia = assignL2(rows, centroids, labels, d2)
    if (options.recordHistory) history.add(inertia / n)
    return KMeansCore(labels, centroids, inertia, history.toDoubleArray(), epochs, false, revivals, batch, alpha0, trail, paths.orEmpty())
}

/**
 * Faiss `_choose_splits` + `_apply_splits`: donors are sampled without replacement in proportion
 * to their mass (Gumbel top-k), each split in two along an alternating-sign relative offset, and
 * the halves share the donor's mass. Returns the number of clusters revived.
 */
private fun reviveUnused(centroids: Array<DoubleArray>, usage: DoubleArray, tau: Double, rng: Random): Int {
    val k = usage.size
    val threshold = usage.sum() * tau / k
    val unused = (0 until k).filter { usage[it] < threshold }
    val donors = (0 until k).filter { usage[it] >= threshold && usage[it] > 0.0 }
    val count = min(unused.size, donors.size)
    if (count == 0) return 0
    val keys = donors.associateWith { j ->
        val u = max(rng.nextDouble(), Double.MIN_VALUE)
        kotlin.math.ln(usage[j]) - kotlin.math.ln(-kotlin.math.ln(u))
    }
    val sources = donors.sortedByDescending { keys.getValue(it) }.take(count)
    for (i in 0 until count) {
        val src = sources[i]
        val dst = unused[i]
        val c = centroids[src]
        val donor = DoubleArray(c.size)
        val child = DoubleArray(c.size)
        for (j in c.indices) {
            val offset = EMEANS_SPLIT_EPSILON * (if (j % 2 == 0) 1.0 else -1.0)
            donor[j] = c[j] * (1.0 - offset)
            child[j] = c[j] * (1.0 + offset)
        }
        if (donor.contentEquals(child)) child[0] = Math.nextUp(child[0])
        val half = usage[src] / 2.0
        centroids[src] = donor
        centroids[dst] = child
        usage[src] = half
        usage[dst] = half
    }
    return count
}
