package afield.examples.four

import afield.Box
import afield.four.Renderer
import afield.four.TexturePreviewCache
import afield.four.fabricated.FabricateBuiltChain
import afield.four.fabricated.FabricatePassChain
import afield.four.fabricated.FabricatePassLibrary
import afield.four.fabricated.FabricatePassLibrary.SummaryStatistics
import afield.four.fabricated.FabricatePassSize
import afield.four.fabricated.FabricatePassSpec
import afield.four.fabricated.StandardRenderer
import afield.four.fabricated.addPassChain
import afield.four.fabricated.applyScenePlan
import afield.four.targets.RenderToTexture
import afield.four.util.Options
import afield.four.util.Window
import afield.stdlib.linalg.Vector3f
import org.lwjgl.vulkan.VK10.VK_FORMAT_R32G32B32A32_SFLOAT
import org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
import java.io.File
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.system.exitProcess

/*
FIELD_TEST
id: four.quadtree-chain.smoke
runner: jvm-main
entrypoint: afield.examples.four.FabricateQuadtreeChainDemo
tags: [smoke, four, fabricate, pass-chain, quadtree, morton, statistics, gpu, image]
timeoutSec: 420
expect:
  exitCode: 0
  stdoutContains:
    - "quadtree chain tests PASSED"
*/
/**
 * Holds the two summarised-quadtree templates to arithmetic: a 2×2 reduction pyramid over a raster,
 * and a Morton-code/sort/prefix-range quadtree over a point set.
 *
 * ### Why this one is mostly ground truth rather than A/Bs
 *
 * Every other GPU test in this family measures a *picture* — a blur that must be wider, a fluid that
 * must keep moving, a distance field whose bands must be evenly spaced. A summary pyramid produces no
 * picture at all. What it produces is eleven numbers per cell, and the whole failure mode of the thing
 * is that a wrong number is indistinguishable from a right one: a centroid half a texel off, a
 * variance that lost its between-children term, a node bracketing a range that starts three points
 * early. None of those is visible, and none of them is caught by the planner, which is why this file
 * compares against **analytic** answers wherever one exists.
 *
 * It exists because one does. A field that ramps linearly across the image with a uniform weight has a
 * closed form for every statistic in the summary, at every level: a cell of `s` texels starting at
 * column `c` has mean `base + amp(c + s/2)/R`, variance `amp²(s² − 1)/12R²`, a centroid at its own
 * centre and a spatial variance of `(s² − 1)/12R²` in each axis. So the pyramid is not compared
 * against a previous render or against a plausible-looking gradient; it is compared against a formula,
 * in the 8-bit codes the readback actually produces.
 *
 * ### What it asserts, and why each needs a GPU
 *
 * - **The raster pyramid agrees with the closed form** — weight, value mean, both first moments and
 *   both second moments, at two depths, in encoded space.
 * - **The pooled variance survives a spread that the raw form cannot**, measured in the same run on
 *   the same field. This is the one real A/B here, and it is the argument for
 *   [SummaryStatistics.POOLED] being the default: at an amplitude of 1/4096 on an offset of 0.5,
 *   `E[v²] − E[v]²` is a difference of two fp32 numbers that agree past the last bit they have.
 * - **The sort is a permutation.** Every slot of the gathered array has to be filled exactly once,
 *   which the gather pass records in its alpha channel — and alpha survives the readback *linearly*,
 *   so "255 everywhere" is an exact statement rather than a tolerance.
 * - **The sorted keys are non-decreasing and the unsorted ones are not.** The readback's sRGB curve is
 *   monotonic, so order is one of the very few things an 8-bit encoding preserves exactly.
 * - **The point producer and the raster producer agree**, on the same distribution, to within the
 *   readback's own quantisation. That is the assertion the unification claim lives or dies by, and it
 *   is also the strongest available check on the sort and on the range search: a node bracketing the
 *   wrong points produces a summary that is wrong in no visible way and disagrees here immediately.
 * - **A clustered set puts its centroids somewhere other than the cell centres.** The uniform case is
 *   the one where the entire spatial half of the summary is a tautology, so a test that only ever used
 *   it would pass with the moments unbound.
 *
 * ### Running it
 *
 * ```
 * tools/run_field_tests.py --id four.quadtree-chain.smoke          # writes PNGs to build/quadtree-chain/
 * tools/run_field_tests.py --id four.quadtree-chain.smoke --args=--keep
 * ```
 *
 * Needs a GPU and a window: `afield.four` has no headless mode, so offscreen targets still need a
 * `Window` for the device to exist.
 */
object FabricateQuadtreeChainDemo {

    /** Render size of the bundle the chains are appended to. */
    private const val DIM = 512

    /**
     * Leaf resolution of the ground-truth pyramid, and the side of its point set.
     *
     * Small on purpose, and the reason is the rank pass rather than the pyramid: sorting by gather is
     * O(N) taps per texel, so 16² points is 256 taps and 64² would be 4096 — a loop an optimiser may
     * decide to unroll. 16² is also enough to have five levels, which is enough for a reduction
     * ladder to be wrong in an interesting way.
     */
    private const val GRID = 16

    /** Leaf depth of the point quadtree over [GRID]² points — one cell per point. */
    private const val GRID_DEPTH = 4

    /** Points in the ground-truth set. */
    private const val GRID_POINTS = GRID * GRID

    /** The ramp the closed form is written for: `value = RAMP_BASE + RAMP_AMP * u`. */
    private const val RAMP_BASE = 0.25f
    private const val RAMP_AMP = 0.5f

    /**
     * The amplitude the two [SummaryStatistics] are separated at, on an offset of 0.5.
     *
     * Chosen to sit just past the point where fp32 gives up on the raw form rather than at some
     * theatrical extreme. The variance of a ramp of this amplitude over the whole image is about
     * 4.9e-9, and `E[v²]` and `E[v]²` are both about 0.25 — so their difference is 2e-8 of their
     * size, against an fp32 epsilon of 6e-8. The subtraction has nothing left to return.
     */
    private const val FLAT_BASE = 0.5f
    private const val FLAT_AMP = 1f / 4096f

    /** Lifts the root variance of the [FLAT_AMP] field into the middle of the readback's range. */
    private const val FLAT_VARIANCE_GAIN = 1e8f

    /** Lifts the root variance of the [RAMP_AMP] field, and the depth-2 one, into the same place. */
    private const val RAMP_ROOT_VARIANCE_GAIN = 16f
    private const val RAMP_MID_VARIANCE_GAIN = 256f

    /** The clustered set: the case where the spatial moments say something. */
    private const val CLOUD_GRID = 16
    private const val CLOUD_DEPTH = 4
    private const val CLOUD_POINTS = CLOUD_GRID * CLOUD_GRID

    /**
     * Keeps the clustered set's crowded leaf cells inside the readback's range.
     *
     * Its one job is the *measurement*: a cell holding a quarter of the points has a density of
     * about 64 against a uniform spread, and both the weight and the moments would come back clamped
     * at 1.0 — from which a centroid is `1/1`, which is the middle of the image for every crowded
     * cell in the set. The ellipse overlay reads the fp32 plane and never noticed.
     */
    private const val CLOUD_DENSITY_SCALE = 1f / 128f

    /** Resolution the cloud is splatted to, and the leaf of the raster pyramid built over that splat. */
    private const val SPLAT_DIM = 256
    private const val CLOUD_FIELD_LEAF = 128

    /**
     * The splat's kernel width and its overall gain.
     *
     * The gain is a measurement parameter, not a look. The splatted field is what the *raster*
     * producer summarises, and a field driven over 1.0 comes back clamped — so a cluster's centre
     * would lose mass the moment it saturated, moving the raster's centroid outwards and inflating
     * its covariance, both of which would read as the two producers disagreeing. The ceiling is
     * asserted rather than assumed; see [SATURATION_CEILING].
     */
    private const val SPLAT_RADIUS = 0.02f
    private const val SPLAT_GAIN = 0.05f

    /**
     * Ceiling on the splatted field's peak, below the readback's clamp with room to spare.
     *
     * The same trap `four.simulation-chain.smoke` pays for with its dye, in the form it takes here:
     * a clipped density is a *different distribution*, and every statistic taken of it is correct
     * about the wrong thing.
     */
    private const val SATURATION_CEILING = 0.96

    /**
     * Lifts a depth-2 spatial covariance into the readback's range.
     *
     * A cell at depth 2 is a quarter of the image, so a cluster inside one has a variance of a few
     * thousandths — three orders under the middle of the range where an 8-bit code is worth least.
     * Too much gain is as bad as too little: the covariance pass biases `cxy` by 0.5 to carry its
     * sign, so anything past ±0.5/gain clamps, and a clamped covariance reads as a disagreement.
     */
    private const val COVARIANCE_GAIN = 200f

    /**
     * How far the two producers' covariances may differ, after [COVARIANCE_GAIN].
     *
     * Not a quantisation bound like [CHANNEL_CODE_TOLERANCE], because the two are not summarising
     * the same thing to the last digit: one has the points and the other has a Gaussian splat of
     * them, which adds the splat's own variance — about `r² = 4e-4`, or 0.08 at this gain — to every
     * cell. The floor sits above that and far below what disagreeing about a cell's *contents*
     * would cost.
     */
    private const val COVARIANCE_AGREEMENT_FLOOR = 0.15

    /** Frames to let the renderer settle before anything is measured. */
    private const val WARMUP_FRAMES = 6

    /**
     * How far a summary channel may sit from its closed form, in **8-bit codes of the readback**.
     *
     * The same unit and for the same reason as `four.simulation-chain.smoke`'s distance tolerance: the
     * comparison is made in encoded space, with the exact answer put through the transfer function the
     * measurement came out of, so the residual is the instrument's quantisation and not a percentage
     * of a linear value that sRGB has already stretched. Two codes out of 255 is a bound on the
     * readback; the arithmetic under it is exact.
     */
    private const val CHANNEL_CODE_TOLERANCE = 2.0

    /** Channel index whose readback is **linear** rather than sRGB-encoded — see [Plane.linear]. */
    private const val ALPHA = 3

    private val DATA_IMAGE_RE = Regex("base64,([^']+)")

    private val outputDir = File(System.getProperty("afield.quadtree.out") ?: "build/quadtree-chain")

    private var failures = 0

    @JvmStatic
    fun main(args: Array<String>) {
        val keepOpen = args.contains("--keep")
        Options.enableVR = false
        Options.doVulkanSDK()

        outputDir.mkdirs()
        val window = Window("Field · Fabricate summarised quadtrees")
        val renderer = Renderer(window)

        // Same reason as the other two chain demos: StandardRenderer.build allocates targets through
        // a bare `RenderToTexture { … }`, whose renderer defaults to `fromContext()`, so it needs a
        // box context or the failure is an NPE from inside the RTT constructor.
        val hostBox = Box("fabricate-quadtree-chain-demo").apply { name = "fabricate-quadtree-chain-demo" }
        hostBox.setPropertyRaw("renderer", renderer, doResync = false)
        hostBox.setPropertyRaw("window", window, doResync = false)

        try {
            Box.withBoxContextNow(hostBox) { run(renderer, window) }
            if (keepOpen) {
                println("-- --keep: leaving the window open, close it to exit --")
                while (!window.shouldClose()) pump(renderer, window, 1)
            }
        } catch (t: Throwable) {
            println("FAIL: ${t.message}")
            t.printStackTrace()
            failures++
        } finally {
            runCatching { renderer.cleanup() }
            runCatching { window.cleanup() }
        }

        if (failures == 0) {
            println("quadtree chain tests PASSED — figures in ${outputDir.absolutePath}")
        } else {
            println("quadtree chain tests FAILED ($failures)")
        }
        exitProcess(if (failures == 0) 0 else 1)
    }

    private fun run(renderer: Renderer, window: Window) {
        val bundle = StandardRenderer.build(
            renderer,
            StandardRenderer.Config(
                renderWidth = DIM,
                renderHeight = DIM,
                backgroundColor = Vector3f(0.04f, 0.05f, 0.07f)
            )
        )
        bundle.applyScenePlan()
        bundle.present(renderer = renderer)
        bundle.attachTo(renderer)
        pump(renderer, window, WARMUP_FRAMES)

        val ramp = bundle.addPassChain(rampChain(), renderer = renderer, printReport = true)
        // The A/B: the same field, the same gain, the same readout pass, differing only in how the
        // value statistics merge on the way up.
        val pooled = bundle.addPassChain(flatChain("flat", SummaryStatistics.POOLED), renderer = renderer)
        val raw = bundle.addPassChain(flatChain("rawflat", SummaryStatistics.RAW_MOMENTS), renderer = renderer)
        val grid = bundle.addPassChain(gridPointChain(), renderer = renderer, printReport = true)
        val cloud = bundle.addPassChain(cloudChain(), renderer = renderer)

        // Every capture below names a pass by its prefix and depth rather than carrying a chain
        // around: the whole point of the naming scheme is that `ramp-value-2` says which producer,
        // which plane and which level, and a reader of an assertion should not have to also know
        // which `addPassChain` call it came out of.
        chainsByPrefix["ramp"] = ramp
        chainsByPrefix["flat"] = pooled
        chainsByPrefix["rawflat"] = raw
        chainsByPrefix["grid"] = grid
        chainsByPrefix["cloud"] = cloud
        chainsByPrefix["cloudfield"] = cloud

        bundle.present(renderer = renderer)
        bundle.attachTo(renderer)
        pump(renderer, window, WARMUP_FRAMES)

        guard("the pyramid planned one complete quadtree per plane, and the levels share targets") {
            val leaf = GRID_DEPTH
            FabricatePassLibrary.SUMMARY_PLANES.forEach { plane ->
                (leaf downTo 0).forEach { depth ->
                    val name = FabricatePassLibrary.summaryPlane("ramp", plane, depth)
                    val target = ramp.targetOf(name) ?: error("$name was not built")
                    require(target.width == 1 shl depth && target.height == 1 shl depth) {
                        "$name is ${target.width}x${target.height}, not ${1 shl depth}²"
                    }
                }
            }
            require(ramp.plan.targetCount < ramp.plan.passCount) {
                "the pyramid planned ${ramp.plan.targetCount} targets for ${ramp.plan.passCount} passes — " +
                        "nothing shared, which for a chain of dead intermediates means the lifetimes are wrong"
            }
            "${ramp.plan.passCount} passes on ${ramp.plan.targetCount} targets, " +
                    "${"%.2f".format(ramp.plan.naiveBytes.toDouble() / ramp.plan.plannedBytes)}x against " +
                    "one full-resolution target per pass"
        }

        // --- the two pyramids against the closed form ---

        val rampRoot = planes(renderer, "ramp", 0, "01-ramp-root")
        val rampMid = planes(renderer, "ramp", 2, "02-ramp-depth2")
        val gridRoot = planes(renderer, "grid", 0, "10-grid-root")
        val gridMid = planes(renderer, "grid", 2, "11-grid-depth2")

        guard("the root cell's weight, mean and moments are the closed form of the whole image") {
            checkCell(rampRoot, 0, 0, cell = 0, side = GRID, label = "raster root")
            checkCell(gridRoot, 0, 0, cell = 0, side = GRID, label = "point root")
            "raster root " + describe(rampRoot, 0, 0) + "; point root " + describe(gridRoot, 0, 0)
        }

        guard("every cell at depth 2 is the closed form of its own quarter, not of the image") {
            // The level that catches a reduction reading the wrong child, which the root cannot: at
            // the root every wrong tree of reductions still averages the same 256 texels.
            val side = GRID shr 2
            (0 until 4).forEach { cy ->
                (0 until 4).forEach { cx ->
                    checkCell(rampMid, cx, cy, cell = cx, side = side, label = "cell ($cx, $cy)", cellY = cy)
                }
            }
            "16 cells of $side² texels each agree with the closed form; " + describe(rampMid, 1, 2)
        }

        guard("the value variance is the variance of a ramp, at two depths") {
            val rootVariance = plane(renderer, ramp, "ramp-variance-0", "03-ramp-variance-root")
            val exactRoot = (RAMP_AMP * RAMP_AMP).toDouble() * spatialVariance(GRID, GRID)
            codeEquals("root variance", rootVariance.linear(0, 0, 0), exactRoot * RAMP_ROOT_VARIANCE_GAIN, 0)

            val midVariance = plane(renderer, ramp, "ramp-variance-2", "04-ramp-variance-depth2")
            val exactMid = (RAMP_AMP * RAMP_AMP).toDouble() * spatialVariance(GRID shr 2, GRID)
            (0 until 4).forEach { cx ->
                codeEquals("depth-2 variance at ($cx, 1)", midVariance.linear(cx, 1, 0), exactMid * RAMP_MID_VARIANCE_GAIN, 0)
            }
            "root ${"%.6f".format(exactRoot)} and depth-2 ${"%.6f".format(exactMid)}, both within " +
                    "$CHANNEL_CODE_TOLERANCE codes of the closed form"
        }

        guard("the pooled merge keeps a spread the raw moments cannot, on the same field in one run") {
            val exact = (FLAT_AMP * FLAT_AMP).toDouble() * spatialVariance(GRID, GRID)
            val expected = exact * FLAT_VARIANCE_GAIN
            val pooledRoot = plane(renderer, pooled, "flat-variance-0", "05-flat-variance-pooled")
            val rawRoot = plane(renderer, raw, "rawflat-variance-0", "06-flat-variance-raw")
            val measuredPooled = pooledRoot.linear(0, 0, 0)
            val measuredRaw = rawRoot.linear(0, 0, 0)
            codeEquals("pooled root variance", measuredPooled, expected, 0)
            // Not "the raw form is bad" as an opinion: at this amplitude the subtraction has no
            // digits left, so its answer has to be *outside* the readback's own resolution of the
            // right one. If this ever starts passing, fp32 got wider or the field got louder.
            val rawError = abs(measuredRaw - expected)
            require(rawError > 8.0 * codeStep(expected)) {
                "the raw-moment form returned ${"%.4f".format(measuredRaw)} against an exact " +
                        "${"%.4f".format(expected)}, which is inside the instrument's own noise — the A/B " +
                        "is not separating the two representations any more, so either the amplitude is " +
                        "too loud to cancel or the raw path is no longer raw"
            }
            "exact ${"%.4f".format(expected)} (gain ${FLAT_VARIANCE_GAIN.toLong()}): pooled " +
                    "${"%.4f".format(measuredPooled)}, raw moments ${"%.4f".format(measuredRaw)}"
        }

        // --- the point pipeline ---

        val codes = plane(renderer, grid, "grid-code", "07-grid-codes")
        val sorted = plane(renderer, grid, "grid-sorted", "08-grid-sorted")

        guard("the gather filled every slot exactly once — the sort is a permutation") {
            // The gather writes alpha 1 only where it found an element for its slot. Alpha comes back
            // *linearly* through the readback (an sRGB format encodes rgb and leaves a alone), so
            // this is an exact count and not a thresholded one.
            val holes = (0 until sorted.height).sumOf { y ->
                (0 until sorted.width).count { x -> sorted.code(x, y, ALPHA) < 255 }
            }
            require(holes == 0) {
                "$holes of ${GRID_POINTS} slots were never written — the rank is not a bijection, which " +
                        "is what a rank computed without the index tie-break does the moment two points " +
                        "share a leaf cell"
            }
            "all $GRID_POINTS slots written once"
        }

        guard("the keys come out of the sort in order, and went in out of order") {
            // Order is one of the few things an 8-bit sRGB encoding preserves exactly, because the
            // curve is monotonic. So this is an exact check on a quantised measurement.
            val after = keysInRasterOrder(sorted)
            val before = keysInRasterOrder(codes)
            val descents = (1 until after.size).count { after[it] < after[it - 1] }
            require(descents == 0) { "$descents of ${after.size - 1} adjacent sorted keys descend" }
            val scrambled = (1 until before.size).count { before[it] < before[it - 1] }
            require(scrambled > 0) {
                "the Morton codes were already in raster order before the sort, so this run proves " +
                        "nothing about the sort — the point set has become one the interleave does not reorder"
            }
            "sorted: 0 descents in ${after.size - 1} steps; unsorted: $scrambled"
        }

        val gridRange = plane(renderer, grid, FabricatePassLibrary.summaryPlane("grid", FabricatePassLibrary.SUMMARY_RANGE, 0), "09-grid-range-root")

        guard("the root node's range is the whole array, which is what makes every node's range contiguous") {
            // `(first, count)` reduced by (min, sum) up the tree. The root's count being exactly N is
            // the arithmetic statement that the leaf ranges tile the sorted array — four adjacent
            // Morton ranges concatenating into one is the property the whole scheme rests on, and the
            // root is where a gap or an overlap would show as a count that is not N.
            require(gridRange.code(0, 0, 0) == 0) {
                "the root range starts at code ${gridRange.code(0, 0, 0)}, not 0"
            }
            require(gridRange.code(0, 0, 1) == 255) {
                "the root range holds ${gridRange.code(0, 0, 1)}/255 of the array — the leaf ranges do " +
                        "not tile it, so some points are summarised twice and others not at all"
            }
            "root range = [0, $GRID_POINTS)"
        }

        // --- the two producers, on one distribution ---

        guard("the point quadtree agrees with the same closed form, to the same tolerance, unscaled") {
            // The *same* expectations as the raster, with no conversion factor anywhere: a node's
            // weight is its density against a uniform spread, and this set is one point per texel, so
            // a uniformly weighted cell has density exactly 1 — which is also the raster's mean
            // coverage. Two pipelines sharing nothing below the reduction ladder, landing on the same
            // numbers.
            val side = GRID shr 2
            (0 until 4).forEach { cy ->
                (0 until 4).forEach { cx ->
                    checkCell(gridMid, cx, cy, cell = cx, side = side, label = "node ($cx, $cy)", cellY = cy)
                }
            }
            "16 nodes of ${side * side} points each against the raster's own closed form, " +
                    describe(gridMid, 1, 2)
        }

        guard("the centroid and the covariance the two producers report are the same numbers") {
            // The statistic-level comparison rather than the channel-level one above, and the one the
            // unification claim is actually about: two pipelines with nothing in common below the
            // reduction ladder, asked for the same distribution's shape.
            var worstCentroid = 0.0
            var worstChannel = 0
            (0 until 4).forEach { cy ->
                (0 until 4).forEach { cx ->
                    val a = shapeOf(rampMid, cx, cy)
                    val b = shapeOf(gridMid, cx, cy)
                    worstCentroid = maxOf(worstCentroid, hypot(a.centroidU - b.centroidU, a.centroidV - b.centroidV))
                    (0..3).forEach { channel ->
                        listOf(rampMid.value to gridMid.value, rampMid.moment to gridMid.moment, rampMid.extent to gridMid.extent)
                            .forEach { (one, other) ->
                                worstChannel = maxOf(worstChannel, abs(one.code(cx, cy, channel) - other.code(cx, cy, channel)))
                            }
                    }
                }
            }
            require(worstCentroid < 0.01) {
                "the two producers' centroids differ by up to ${"%.5f".format(worstCentroid)} UV — more " +
                        "than the readback's quantisation, so they are not summarising the same points"
            }
            "worst centroid disagreement ${"%.5f".format(worstCentroid)} UV over 16 cells, and the " +
                    "twelve raw channels differ by at most $worstChannel code"
        }

        // --- the clustered set, where the spatial half of the summary says something ---

        val cloudLeaf = planes(renderer, "cloud", CLOUD_DEPTH, "12-cloud-leaf")
        val cloudRange = plane(renderer, cloud, FabricatePassLibrary.summaryPlane("cloud", FabricatePassLibrary.SUMMARY_RANGE, CLOUD_DEPTH), "13-cloud-leaf-ranges")

        guard("a clustered set fills its leaf cells unevenly, which the uniform case cannot show") {
            val counts = (0 until cloudRange.height).flatMap { y ->
                (0 until cloudRange.width).map { x -> cloudRange.code(x, y, 1) }
            }
            require(counts.max() > counts.min()) { "every leaf node holds the same number of points" }
            val empty = counts.count { it == 0 }
            require(empty > 0) {
                "every one of the ${counts.size} leaf cells is occupied — a clustered set of " +
                        "$CLOUD_POINTS points over ${counts.size} cells should leave some empty, and a " +
                        "summary that never sees an empty cell never exercises its zero-weight branch"
            }
            val occupancy = cloudLeaf.value.let { plane ->
                (0 until plane.height).sumOf { y -> (0 until plane.width).count { x -> plane.code(x, y, ALPHA) > 127 } }
            }
            require(occupancy == counts.size - empty) {
                "the occupancy channel says $occupancy cells are occupied and the ranges say " +
                        "${counts.size - empty}"
            }
            "$empty of ${counts.size} leaf cells empty, fullest holds ${counts.max()}/255 of the array"
        }

        guard("the centroids have walked away from their cells' centres") {
            // The assertion the ramp cannot make. With a uniform weight every centroid sits at its
            // cell's centre whatever the moments are doing, so a chain with `momentTex` bound to the
            // wrong plane still passes everything above. Here the mass is lumpy and the centroid has
            // to follow it.
            var worst = 0.0
            var moved = 0
            (0 until cloudLeaf.value.height).forEach { cy ->
                (0 until cloudLeaf.value.width).forEach { cx ->
                    val shape = shapeOf(cloudLeaf, cx, cy)
                    if (shape.weight <= 0.0) return@forEach
                    val cells = cloudLeaf.value.width
                    val offset = hypot(
                        shape.centroidU - (cx + 0.5) / cells,
                        shape.centroidV - (cy + 0.5) / cells
                    )
                    if (offset > 0.25 / cells) moved++
                    worst = maxOf(worst, offset * cells)
                }
            }
            require(moved >= 4) {
                "only $moved occupied cells hold a centroid off their own centre by more than a quarter " +
                        "of a cell — on a clustered set that is what a centroid computed from the cell " +
                        "index rather than from the moments looks like"
            }
            "$moved cells with a displaced centroid, worst ${"%.2f".format(worst)} of a cell width"
        }

        // --- the figures ---

        val splat = capture(renderer, cloud.targetOf("cloud-splat")!!, "14-cloud-splat")
        val cloudEllipses = capture(renderer, cloud.targetOf("cloud-ellipses")!!, "15-cloud-ellipses")
        val fieldEllipses = capture(renderer, cloud.targetOf("cloudfield-ellipses")!!, "16-cloudfield-ellipses")

        guard("the overlay draws something the image underneath does not have") {
            require(splat.distinctLumaBuckets >= 8) { "the splatted cloud has ${splat.distinctLumaBuckets} levels" }
            // Checked before the comparison below draws any conclusion from the raster producer: a
            // clipped density is a different distribution, and its moments are right about it.
            require(splat.maxLuma < SATURATION_CEILING) {
                "the splatted cloud peaks at ${"%.4f".format(splat.maxLuma)}, at or over the readback's " +
                        "clamp — the raster producer is then summarising a flat-topped version of the " +
                        "cloud, and its disagreement with the point producer would be the clipping. " +
                        "Lower SPLAT_GAIN."
            }
            require(cloudEllipses.differenceFrom(splat) > 0.02) {
                "the ellipse overlay is within ${"%.4f".format(cloudEllipses.differenceFrom(splat))} of the " +
                        "image it was drawn over — either the planes are unbound or every cell fell through " +
                        "the zero-weight branch, both of which render as the picture you started with"
            }
            "overlay differs from its source by ${"%.4f".format(cloudEllipses.differenceFrom(splat))} mean luma"
        }

        guard("both producers find the same shape in the same cloud, where a cell holds enough of it") {
            // Deliberately NOT a comparison of the two overlays as pictures. At the leaf level they
            // look different and should: a leaf cell holds one or two points, so the point producer
            // reports a singular covariance and no ellipse, while the raster producer — summarising
            // a *splat* of those points — sees a smooth little blob in every cell and draws a small
            // circle. That difference is information, not error; it is the sub-cell structure the
            // rasterisation already threw away.
            //
            // Two levels up, a cell holds a sixteenth of the set and both producers have a shape to
            // report. That is where they have to agree, and it is asserted on the covariance itself
            // rather than on a picture of it.
            val points = plane(renderer, cloud, "cloud-covariance-2", "17-cloud-covariance")
            val field = plane(renderer, cloud, "cloudfield-covariance-2", "18-cloudfield-covariance")
            var worst = 0.0
            var worstAt = ""
            var anisotropic = 0
            (0 until 4).forEach { cy ->
                (0 until 4).forEach { cx ->
                    if (points.code(cx, cy, ALPHA) < 128) return@forEach
                    val cxx = points.linear(cx, cy, 0)
                    val cyy = points.linear(cx, cy, 1)
                    if (cxx > 1e-4 && abs(cxx - cyy) > 0.25 * (cxx + cyy)) anisotropic++
                    listOf(0 to "cxx", 1 to "cyy", 2 to "cxy").forEach { (channel, label) ->
                        val a = points.linear(cx, cy, channel)
                        val b = field.linear(cx, cy, channel)
                        val error = abs(a - b)
                        if (error > worst) {
                            worst = error
                            worstAt = "$label at ($cx, $cy): points ${"%.4f".format(a)}, raster ${"%.4f".format(b)}"
                        }
                    }
                }
            }
            // A splat is a point convolved with a Gaussian, so the raster's variance is the point
            // set's plus the splat's own — about r² = 4e-4, which at this gain is 0.4. The floor is
            // set above that and well under a disagreement about *which* points a cell holds.
            require(worst < COVARIANCE_AGREEMENT_FLOOR) {
                "the two producers' covariances differ by up to ${"%.4f".format(worst)} at $worstAt — " +
                        "more than the splat's own width accounts for, so they are not summarising the " +
                        "same mass"
            }
            require(anisotropic >= 2) {
                "only $anisotropic cells came back measurably anisotropic — the clusters are stretched " +
                        "2:1 and 1:4, so a summary that reports them all round has lost the off-diagonal " +
                        "or is reading the wrong level"
            }
            require(fieldEllipses.distinctLumaBuckets >= 8) {
                "the raster overlay has ${fieldEllipses.distinctLumaBuckets} levels"
            }
            "worst covariance disagreement ${"%.4f".format(worst)} at gain " +
                    "${COVARIANCE_GAIN.toInt()} ($worstAt), $anisotropic cells anisotropic; the overlays " +
                    "differ as pictures by ${"%.3f".format(fieldEllipses.differenceFrom(cloudEllipses))} " +
                    "mean luma, which is the sub-cell structure the splat lost"
        }
    }

    // ── the chains the assertions are written against ────────────────────────

    /** The ramp the closed form is written for, plus its pyramid and two variance probes. */
    private fun rampChain(): List<FabricatePassSpec> =
        listOf(rampSource("ramp", RAMP_BASE, RAMP_AMP)) +
                FabricatePassLibrary.summaryPyramid(
                    prefix = "ramp",
                    resolution = GRID,
                    source = "ramp",
                    weightChannel = 3,
                    valueChannel = 0
                ) +
                FabricatePassLibrary.summaryVariance(
                    name = "ramp-variance-0",
                    from = FabricatePassLibrary.summaryPlane("ramp", FabricatePassLibrary.SUMMARY_VALUE, 0),
                    gain = RAMP_ROOT_VARIANCE_GAIN
                ) +
                FabricatePassLibrary.summaryVariance(
                    name = "ramp-variance-2",
                    from = FabricatePassLibrary.summaryPlane("ramp", FabricatePassLibrary.SUMMARY_VALUE, 2),
                    gain = RAMP_MID_VARIANCE_GAIN
                )

    /** The same shape of chain at an amplitude fp32 cannot subtract, in one of the two forms. */
    private fun flatChain(prefix: String, statistics: SummaryStatistics): List<FabricatePassSpec> =
        listOf(rampSource(prefix, FLAT_BASE, FLAT_AMP)) +
                FabricatePassLibrary.summaryPyramid(
                    prefix = prefix,
                    resolution = GRID,
                    source = prefix,
                    weightChannel = 3,
                    valueChannel = 0,
                    statistics = statistics
                ) +
                FabricatePassLibrary.summaryVariance(
                    name = "$prefix-variance-0",
                    from = FabricatePassLibrary.summaryPlane(prefix, FabricatePassLibrary.SUMMARY_VALUE, 0),
                    statistics = statistics,
                    gain = FLAT_VARIANCE_GAIN
                )

    /**
     * A point set on the ramp's own texel centres, so the two producers see one distribution.
     *
     * "On the texel centres" is what makes the comparison exact rather than approximate. Any other
     * point set is a different distribution from the raster, and the two producers would then be
     * expected to disagree by an amount nobody can compute, which is not a test.
     */
    private fun gridPointChain(): List<FabricatePassSpec> =
        listOf(
            FabricatePassSpec(
                name = "grid-points",
                fragment = GRID_POINTS_FRAGMENT,
                size = FabricatePassSize.Absolute(GRID, GRID),
                format = VK_FORMAT_R32G32B32A32_SFLOAT,
                source = FabricatePassChain.CHAIN_INPUT,
                push = mapOf("base" to RAMP_BASE, "amplitude" to RAMP_AMP)
            )
        ) + FabricatePassLibrary.mortonPointQuadtree(
            prefix = "grid",
            points = "grid-points",
            pointGrid = GRID,
            depth = GRID_DEPTH
        )

    /** The clustered set: a point quadtree, a splat of the same points, and a pyramid over the splat. */
    private fun cloudChain(): List<FabricatePassSpec> =
        listOf(
            FabricatePassSpec(
                name = "cloud-points",
                fragment = cloudPointsFragment(CLOUD_GRID),
                size = FabricatePassSize.Absolute(CLOUD_GRID, CLOUD_GRID),
                format = VK_FORMAT_R32G32B32A32_SFLOAT,
                source = FabricatePassChain.CHAIN_INPUT
            ),
            FabricatePassSpec(
                name = "cloud-splat",
                fragment = cloudSplatFragment(CLOUD_POINTS, CLOUD_GRID),
                size = FabricatePassSize.Absolute(SPLAT_DIM, SPLAT_DIM),
                format = VK_FORMAT_R32G32B32A32_SFLOAT,
                source = "cloud-points",
                push = mapOf("radius" to SPLAT_RADIUS, "gain" to SPLAT_GAIN)
            )
        ) + FabricatePassLibrary.mortonPointQuadtree(
            prefix = "cloud",
            points = "cloud-points",
            pointGrid = CLOUD_GRID,
            depth = CLOUD_DEPTH,
            // A quarter of the points land in one leaf cell, so its density against a uniform
            // spread is about 64 — correct, and four hundred times what an 8-bit readback can
            // show. This is the knob for looking at one, and it scales the weight and the moments
            // together so every ratio taken from them is unchanged.
            densityScale = CLOUD_DENSITY_SCALE
        ) + FabricatePassLibrary.summaryEllipses(
            name = "cloud-ellipses",
            over = "cloud-splat",
            prefix = "cloud",
            depth = CLOUD_DEPTH,
            weightGain = 60f
        ) + FabricatePassLibrary.summaryPyramid(
            prefix = "cloudfield",
            resolution = CLOUD_FIELD_LEAF,
            source = "cloud-splat",
            // The mass channel, explicitly, and not luminance. The splat's green and blue carry the
            // *value* field, which is a ratio — `Σwv / Σw` — and therefore stays at full strength
            // wherever the mass is merely small rather than zero. Summarising luminance gave every
            // cell a uniform pedestal of about 0.2 covering the whole cell, which is a perfectly
            // plausible density field whose covariance is the cell's own: it read 4.1e-3 against the
            // point producer's 7.4e-4, and the picture of it looked fine.
            weightChannel = 0,
            valueChannel = 2
        ) + FabricatePassLibrary.summaryEllipses(
            name = "cloudfield-ellipses",
            over = "cloud-splat",
            prefix = "cloudfield",
            depth = CLOUD_DEPTH,
            weightGain = 24f
        ) + FabricatePassLibrary.summaryCovariance(
            name = "cloud-covariance-2",
            prefix = "cloud",
            depth = 2,
            gain = COVARIANCE_GAIN
        ) + FabricatePassLibrary.summaryCovariance(
            name = "cloudfield-covariance-2",
            prefix = "cloudfield",
            depth = 2,
            gain = COVARIANCE_GAIN
        )

    private fun rampSource(name: String, base: Float, amplitude: Float) = FabricatePassSpec(
        name = name,
        fragment = RAMP_FRAGMENT,
        size = FabricatePassSize.Absolute(GRID, GRID),
        // fp32 and not the chain input's format, and it is load-bearing: at an amplitude of 1/4096
        // an 8-bit source is a constant, and the variance under test would be exactly zero in both
        // representations — an A/B that agrees perfectly because there is nothing to measure.
        format = VK_FORMAT_R32G32B32A32_SFLOAT,
        source = FabricatePassChain.CHAIN_INPUT,
        push = mapOf("base" to base, "amplitude" to amplitude)
    )

    /** `value = base + amplitude * u` in red, a uniform weight in alpha. */
    private const val RAMP_FRAGMENT = """
        #version 450
        layout(location = 0) in vec2 vUv;
        layout(push_constant) uniform RampPush {
            float base;
            float amplitude;
        } push;
        layout(location = 0) out vec4 outColor;

        void main() {
            float v = push.base + push.amplitude * vUv.x;
            outColor = vec4(v, v, v, 1.0);
        }
    """

    /** One point per texel, at the texel's own centre, carrying the ramp's value. */
    private const val GRID_POINTS_FRAGMENT = """
        #version 450
        layout(location = 0) in vec2 vUv;
        layout(push_constant) uniform PointPush {
            float base;
            float amplitude;
        } push;
        layout(location = 0) out vec4 outPoint;

        void main() {
            // vUv is the texel centre, which is exactly where the raster producer takes its moments
            // about — so the two see the same positions and not positions half a texel apart.
            outPoint = vec4(vUv, 1.0, push.base + push.amplitude * vUv.x);
        }
    """

    /**
     * Three anisotropic clusters from a hash: lumpy weights, and two of the three deliberately
     * stretched along different axes so the covariance has an orientation to report.
     */
    private fun cloudPointsFragment(width: Int): String = """
        #version 450
        layout(location = 0) in vec2 vUv;
        layout(location = 0) out vec4 outPoint;

        const int WIDTH = $width;

        float hash1(float n) { return fract(sin(n) * 43758.5453123); }

        void main() {
            ivec2 p = ivec2(gl_FragCoord.xy);
            float i = float(p.y * WIDTH + p.x);
            int which = int(mod(i, 3.0));

            // Each cluster sits inside one depth-2 cell, and that placement is a decision the
            // measurement depends on rather than an aesthetic one. The raster producer summarises a
            // *splat* of these points, and a splat spreads mass across whatever boundary it sits
            // on — so a cluster straddling a cell edge makes the two producers genuinely disagree
            // about which cell its mass is in, by an amount that has nothing to do with whether
            // either of them works. The first version of this file put a cluster centre exactly on
            // u = 0.5 and measured a covariance disagreement of 0.89 that was entirely that.
            vec2 centre = which == 0 ? vec2(0.375, 0.375) : which == 1 ? vec2(0.625, 0.375) : vec2(0.625, 0.625);
            // One round, two stretched — and the stretched pair rotated off the axes, so the
            // off-diagonal of the covariance is not zero by symmetry. Nothing else in this file
            // reads the cross moment, and a plane nobody reads is a plane that can be wrong.
            vec2 skew = which == 0 ? vec2(1.0, 1.0) : which == 1 ? vec2(2.0, 0.45) : vec2(0.45, 1.8);
            float lean = which == 0 ? 0.0 : which == 1 ? 0.5 : -0.7;
            float radius = which == 0 ? 0.10 : 0.05;

            float angle = hash1(i * 1.7 + 3.0) * 6.2831853;
            vec2 offset = vec2(cos(angle), sin(angle)) * (radius * pow(hash1(i * 2.3 + 11.0), 0.7)) * skew;
            vec2 turned = vec2(
                offset.x * cos(lean) - offset.y * sin(lean),
                offset.x * sin(lean) + offset.y * cos(lean)
            );

            outPoint = vec4(
                clamp(centre + turned, vec2(0.01), vec2(0.99)),
                0.35 + 0.65 * hash1(i * 3.1 + 7.0),
                0.2 + 0.7 * hash1(i * 5.9 + 19.0)
            );
        }
    """

    /**
     * The cloud as a density field: every texel gathers every point.
     *
     * O(N) per texel and unapologetic — it is the figure's background, not the measurement. It is also
     * the *raster* the second producer summarises, which is why it is a splat rather than a scatter: a
     * fragment shader cannot scatter, and a point drawn by gathering is a point every texel agrees
     * about.
     */
    private fun cloudSplatFragment(count: Int, width: Int): String = """
        #version 450
        layout(location = 0) in vec2 vUv;
        layout(set = 0, binding = 0) uniform sampler2D sourceTex;
        layout(push_constant) uniform SplatPush {
            float radius;
            float gain;
        } push;
        layout(location = 0) out vec4 outColor;

        const int COUNT = $count;
        const int WIDTH = $width;

        void main() {
            float total = 0.0;
            float value = 0.0;
            for (int i = 0; i < COUNT; ++i) {
                vec4 point = texelFetch(sourceTex, ivec2(i % WIDTH, i / WIDTH), 0);
                float d = length(vUv - point.xy) / max(push.radius, 1e-5);
                float falloff = exp(-0.5 * d * d) * point.z;
                total += falloff;
                value += falloff * point.w;
            }
            float mass = clamp(total * push.gain, 0.0, 1.0);
            float mean = total > 0.0 ? value / total : 0.0;
            outColor = vec4(mass, mass * 0.6 + mean * 0.4, mean, mass);
        }
    """

    // ── reading a summary back ───────────────────────────────────────────────

    /** The three planes of one level, captured together — everything a cell's shape is computed from. */
    private class Level(val value: Plane, val moment: Plane, val extent: Plane)

    private fun planes(renderer: Renderer, prefix: String, depth: Int, name: String): Level {
        val chain = chainsByPrefix.getValue(prefix)
        return Level(
            plane(renderer, chain, FabricatePassLibrary.summaryPlane(prefix, FabricatePassLibrary.SUMMARY_VALUE, depth), "$name-value"),
            plane(renderer, chain, FabricatePassLibrary.summaryPlane(prefix, FabricatePassLibrary.SUMMARY_MOMENT, depth), "$name-moment"),
            plane(renderer, chain, FabricatePassLibrary.summaryPlane(prefix, FabricatePassLibrary.SUMMARY_EXTENT, depth), "$name-extent")
        )
    }

    private val chainsByPrefix = LinkedHashMap<String, FabricateBuiltChain>()

    private fun plane(renderer: Renderer, chain: FabricateBuiltChain, pass: String, name: String): Plane {
        val target = chain.targetOf(pass) ?: error("'$pass' is not a pass of this chain: ${chain.names()}")
        return Plane(pass, capture(renderer, target, name).file)
    }

    /**
     * One captured plane, read as codes.
     *
     * **The four channels do not come back the same way**, and that is the trap this class exists to
     * hold in one place. `TexturePreviewCache` blits an fp32 target to `VK_FORMAT_R8G8B8A8_SRGB`,
     * which is display-encoded in *rgb* and linear in *a* — that is what the format means. So a
     * statistic in the alpha channel decodes by dividing by 255 and one in red decodes through the
     * sRGB curve, and using one rule for all four reads the alpha channel about 2.2 times too small
     * in the dark. Both rules are here, keyed on the channel index, so no assertion picks.
     */
    private class Plane(val name: String, file: File) {
        private val image = ImageIO.read(file) ?: error("could not re-read $name from ${file.absolutePath}")
        val width = image.width
        val height = image.height

        private val argb = IntArray(width * height) { image.getRGB(it % width, it / width) }

        /** The raw 0..255 code of a channel — 0=r, 1=g, 2=b, 3=a. */
        fun code(x: Int, y: Int, channel: Int): Int {
            val c = argb[y * width + x]
            return when (channel) {
                0 -> c shr 16 and 0xff
                1 -> c shr 8 and 0xff
                2 -> c and 0xff
                else -> c shr 24 and 0xff
            }
        }

        /** The channel's value, decoded by whichever rule that channel came back under. */
        fun linear(x: Int, y: Int, channel: Int): Double {
            val encoded = code(x, y, channel) / 255.0
            return if (channel == ALPHA) encoded else srgbToLinear(encoded)
        }
    }

    /**
     * A cell's weight and centroid, as a consumer of the summary would compute them.
     *
     * First order only, and that is a statement about the *instrument* rather than about the
     * summary. A centroid is `Σwx / w`, a ratio of two channels of similar size, and survives an
     * 8-bit readback with a percent or so to spare. A covariance is `Σwx²/w − (Σwx/w)²`, a
     * difference of two channels that agree to three digits at any fine level — so subtracting them
     * *after* the readback returns the readback's noise whatever the GPU computed. That is what
     * `summaryCovariance` exists for: the subtraction happens on the GPU, at full precision, and
     * only the answer is quantised.
     */
    private class Shape(val weight: Double, val centroidU: Double, val centroidV: Double)

    private fun shapeOf(level: Level, x: Int, y: Int): Shape {
        val weight = level.value.linear(x, y, 0)
        if (weight <= 1e-6) return Shape(0.0, 0.0, 0.0)
        return Shape(weight, level.moment.linear(x, y, 0) / weight, level.moment.linear(x, y, 1) / weight)
    }

    /**
     * The closed form of a cell of a ramp, channel by channel.
     *
     * Every expectation here is arithmetic over the cell's extent, and the comparison is in codes with
     * the exact answer put through the same transfer function the measurement came out of — so the
     * residual is the readback's quantisation and nothing else.
     */
    private fun checkCell(
        level: Level,
        x: Int,
        y: Int,
        cell: Int,
        side: Int,
        label: String,
        cellY: Int = 0
    ) {
        val meanU = cellMean(cell, side)
        val meanV = cellMean(cellY, side)
        val spread = spatialVariance(side, GRID)
        val meanValue = RAMP_BASE + RAMP_AMP * meanU

        codeEquals("$label weight", level.value.linear(x, y, 0), 1.0, 0)
        codeEquals("$label value mean", level.value.linear(x, y, 1), meanValue, 1)
        codeEquals("$label Σwx", level.moment.linear(x, y, 0), meanU, 0)
        codeEquals("$label Σwy", level.moment.linear(x, y, 1), meanV, 1)
        codeEquals("$label Σwx²", level.moment.linear(x, y, 2), spread + meanU * meanU, 2)
        codeEquals("$label Σwy²", level.moment.linear(x, y, ALPHA), spread + meanV * meanV, ALPHA)
        codeEquals("$label Σwxy", level.extent.linear(x, y, 0), meanU * meanV, 0)
        // The extremes are the first and last column of the cell, not the cell's bounds: a texel's
        // value is taken at its centre.
        codeEquals("$label min", level.extent.linear(x, y, 1), RAMP_BASE + RAMP_AMP * texelMean(cell * side), 1)
        codeEquals("$label max", level.extent.linear(x, y, 2), RAMP_BASE + RAMP_AMP * texelMean(cell * side + side - 1), 2)
    }

    /** Mean of `u` over a cell of [side] texels starting at cell index [cell]. */
    private fun cellMean(cell: Int, side: Int): Double = (cell * side + side / 2.0) / GRID

    /** The `u` of one texel's centre. */
    private fun texelMean(texel: Int): Double = (texel + 0.5) / GRID

    /** Variance of `u` over [side] consecutive texels of an [resolution]-wide image: `(s² − 1)/12R²`. */
    private fun spatialVariance(side: Int, resolution: Int): Double =
        (side.toDouble() * side - 1.0) / (12.0 * resolution * resolution)

    /** How much one 8-bit code is worth, in linear units, at [linear] — the instrument's resolution. */
    private fun codeStep(linear: Double): Double {
        val encoded = linearToSrgb(linear)
        return abs(srgbToLinear((encoded + 1.0 / 255.0).coerceAtMost(1.0)) - srgbToLinear(encoded))
            .coerceAtLeast(1.0 / 255.0 / 12.92)
    }

    private fun codeEquals(what: String, measured: Double, exact: Double, channel: Int) {
        val measuredCode = if (channel == ALPHA) measured * 255.0 else linearToSrgb(measured) * 255.0
        val exactCode = if (channel == ALPHA) exact.coerceIn(0.0, 1.0) * 255.0 else linearToSrgb(exact) * 255.0
        require(abs(measuredCode - exactCode) <= CHANNEL_CODE_TOLERANCE) {
            "$what read ${"%.1f".format(measuredCode)} codes against an exact " +
                    "${"%.1f".format(exactCode)} (${"%.6f".format(exact)}), over a tolerance of " +
                    "$CHANNEL_CODE_TOLERANCE — the arithmetic is exact, so anything past the readback's " +
                    "own quantisation is a wrong statistic and not a wrong instrument"
        }
    }

    private fun describe(level: Level, x: Int, y: Int): String {
        val shape = shapeOf(level, x, y)
        return "weight ${"%.4f".format(shape.weight)}, centroid " +
                "(${"%.4f".format(shape.centroidU)}, ${"%.4f".format(shape.centroidV)})"
    }

    /** The key channel of a point array, in the order the texels are laid out. */
    private fun keysInRasterOrder(plane: Plane): List<Int> =
        (0 until plane.height).flatMap { y -> (0 until plane.width).map { x -> plane.code(x, y, 0) } }

    // ── plumbing ─────────────────────────────────────────────────────────────

    private fun pump(renderer: Renderer, window: Window, frames: Int) {
        repeat(frames) {
            window.pollEvents()
            renderer.render()
        }
    }

    private fun capture(renderer: Renderer, rtt: RenderToTexture, name: String): Shot {
        val html = TexturePreviewCache.capture(
            renderer = renderer,
            vkImage = rtt.colorImageHandles[0],
            format = rtt.colorFormats[0],
            srcWidth = rtt.width,
            srcHeight = rtt.height,
            currentLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
            // The target's own size: a thumbnailed summary is a *resampled* summary, and every cell
            // of it would be a blend of statistics from neighbouring cells.
            maxDim = maxOf(rtt.width, rtt.height)
        ) ?: error("could not read back '$name' (${rtt.width}x${rtt.height})")
        val encoded = DATA_IMAGE_RE.find(html)?.groupValues?.get(1)
            ?: error("could not parse the readback of '$name'")
        val file = File(outputDir, "$name.png")
        file.delete()
        file.writeBytes(Base64.getDecoder().decode(encoded))
        require(file.length() > 0L) { "readback of '$name' wrote an empty PNG" }
        val shot = Shot(name, file)
        println(" -- $name: ${shot.size}, ${file.length() / 1024}KB, mean luma ${"%.4f".format(shot.meanLuma)}")
        return shot
    }

    /** One captured PNG and the handful of statistics the figure assertions are written in. */
    private class Shot(val name: String, val file: File) {
        private val image = ImageIO.read(file) ?: error("could not re-read $name from ${file.absolutePath}")
        val width = image.width
        val height = image.height
        val size get() = "${width}x$height"

        private val luma = DoubleArray(width * height) { index ->
            val c = image.getRGB(index % width, index / width)
            0.2126 * (c shr 16 and 0xff) / 255.0 + 0.7152 * (c shr 8 and 0xff) / 255.0 + 0.0722 * (c and 0xff) / 255.0
        }

        val meanLuma = luma.average()
        val maxLuma = luma.max()
        val distinctLumaBuckets = luma.map { (it * 64).toInt() }.distinct().size

        /** Mean absolute luma difference against another capture of the same size. */
        fun differenceFrom(other: Shot): Double {
            require(other.width == width && other.height == height) {
                "$name is $size and ${other.name} is ${other.size}"
            }
            return luma.indices.sumOf { abs(luma[it] - other.luma[it]) } / luma.size
        }
    }

    /** The sRGB electro-optical transfer function, which the readback applied to rgb and nothing undid. */
    private fun srgbToLinear(encoded: Double): Double =
        if (encoded <= 0.04045) encoded / 12.92 else Math.pow((encoded + 0.055) / 1.055, 2.4)

    /** Its inverse, for putting an exact answer through the same instrument the measurement came from. */
    private fun linearToSrgb(linear: Double): Double {
        val l = linear.coerceIn(0.0, 1.0)
        return if (l <= 0.0031308) l * 12.92 else 1.055 * Math.pow(l, 1.0 / 2.4) - 0.055
    }

    private fun guard(what: String, body: () -> String) {
        try {
            println("OK  $what — ${body()}")
        } catch (t: Throwable) {
            println("FAIL: $what — ${t.message}")
            failures++
        }
    }
}
