/**
 * A whole Gezira scene, many filled paths in paint order, rasterised and composited on the GPU in
 * one five-stage compute pipeline per frame.
 */

package afield.graphics.nile

import afield.geometry.path.FLine
import afield.graphics.render.filled
import afield.graphics.render.stroked
import afield.graphics.render.thickness
import afield.graphics.vulkan.buffers.FrameBufferGroup
import afield.graphics.vulkan.buffers.defaultBuffer
import afield.graphics.vulkan.util.GpuProbe
import kotlin.math.max

/**
 * One filled path of a [GeziraGpuScene]: its outline, its premultiplied [paint] and its fill [rule].
 * A stroke enters as the outline [GeziraStroke.strokeBezierPath] makes of it, filled non-zero.
 */
class GeziraPath(val beziers: List<Bezier>, val paint: Rgba, val rule: FillRule = FillRule.NON_ZERO)

/**
 * Gezira's rasteriser for a whole document on the GPU: every path uploaded once, then each frame
 * five compute stages from `gezira_scene.comp`, with no work on the CPU.
 *
 * 1. **Count**: each curve, put through the frame's affine [transform], gets the number of pieces
 *    Wang's formula says keep it within [tolerance] pixels of its chords (curves wholly above or below
 *    the image get none).
 * 2. **Scan**: one workgroup turns the counts into each curve's first segment.
 * 3. **Emit**: each curve writes its pieces as line segments. Curves are in paint order, so segments are.
 * 4. **Bin**: a workgroup per band of 16 rows keeps, in order, the segments that touch the band, and
 *    marks where each path's run of them starts.
 * 5. **Resolve**: a workgroup per pixel row takes the runs in paint order. For each it cuts the run's
 *    segments to the row, resolves them exactly as [Gezira.resolveRow] does (bands at edge ends and
 *    crossings, a winding walk, each inside trapezoid's area added to its columns) under the path's
 *    rule, and composites the path's colour source-over at that coverage into the row.
 *
 * Against the CPU's [Gezira.rasterizer] the differences are the flattening (uniform pieces from
 * Wang's bound at [tolerance], where the CPU halves curves to 1/10,000 of a pixel) and float32.
 *
 * Buffers and stages live on a [GpuProbe]; [image] is `width × height` premultiplied RGBA floats.
 * The dispatches run every frame until the probe closes, reading [transform] each time, so a box
 * that changes it animates the scene.
 *
 * @param segmentCapacity segments the buffer holds; pieces past it are dropped and counted in [overflow].
 * @param bandCapacity entries (and runs) each band of 16 rows can hold.
 * @param rowEdges edges one path can put in one pixel row in the fast resolve pass. A row past it is
 *   not dropped: the fast pass flags it and a second, wide pass at [WIDE_EDGES] redraws just that row
 *   ([wideRows] counts them); only edges past [WIDE_EDGES] are dropped, counted in [overflow].
 *   It sizes the resolve stage's shared memory (with room for `2 × rowEdges` crossings, and one
 *   accumulator per column of [width]), and that is what sets how many rows a GPU core resolves at
 *   once: on an M-series Mac, 256 here resolves the 900 × 900 tiger in about two thirds of the time
 *   512 does (`dev_docs/GEZIRA_GPU_TIMING.md`).
 * @param repeats dispatches of each stage per frame: 1 to draw, more only to time the pipeline as the
 *   difference between frames with several and with one (repeats run back to back and each stage is
 *   idempotent, so the picture is the same).
 * @param repeatStage with [repeats], repeat only this stage (0 count .. 4 resolve, 5 the wide pass); -1 repeats all.
 */
class GeziraGpuScene(
    val probe: GpuProbe,
    val paths: List<GeziraPath>,
    val width: Int,
    val height: Int,
    /** Document to pixels: `x' = a x + c y + e`, `y' = b x + d y + f`. Read every frame. */
    @Volatile var transform: SvgTransform = SvgTransform.IDENTITY,
    @Volatile var tolerance: Double = 0.02,
    segmentCapacity: Int = 1 shl 20,
    bandCapacity: Int = 1 shl 15,
    val rowEdges: Int = 256,
    repeats: Int = 1,
    repeatStage: Int = -1,
) {
    init {
        require(width in 1..2048) { "GeziraGpuScene draws images 1 to 2048 pixels wide, not $width" }
        require(rowEdges in 16..WIDE_EDGES) { "rowEdges is 16 to $WIDE_EDGES, not $rowEdges" }
    }

    /** Curves uploaded, every one a cubic (lines and quadratics are raised exactly). */
    val curveCount: Int = paths.sumOf { it.beziers.size }
    val bands = (height + 15) / 16
    val segCap = segmentCapacity
    val bandCap = bandCapacity

    private fun local(name: String, floats: Int): FrameBufferGroup = defaultBuffer(name = "${probe.name}.$name") {
        size = max(1, floats).floats
        zeroInit()
    }.toBuffer(probe.renderer)

    private val curves = probe.fixture("curves", max(1, curveCount) * 12)
    private val shapes = probe.fixture("shapes", max(1, paths.size) * 8)
    private val offsets = local("offsets", 2 * curveCount + 1)
    private val segs = local("segs", segCap * 4)
    private val segShape = local("segShape", segCap)
    private val bins = local("bins", bands * 2 * bandCap)

    /**
     * Per band entries and runs, three overflow counters (segments, band entries, row edges), the
     * widest run, then a flag per row the resolve stage left to the wide pass.
     */
    val counts: FrameBufferGroup = probe.fixture("counts", bands * 2 + 4 + height)

    /** The composited picture, `width × height` premultiplied RGBA, row-major, y down. */
    val image: FrameBufferGroup = probe.fixture("image", width * height * 4)

    init {
        val c = FloatArray(max(1, curveCount) * 12)
        val s = FloatArray(max(1, paths.size) * 8)
        var k = 0
        for ((i, path) in paths.withIndex()) {
            s[i * 8] = path.paint.r; s[i * 8 + 1] = path.paint.g; s[i * 8 + 2] = path.paint.b; s[i * 8 + 3] = path.paint.a
            s[i * 8 + 4] = if (path.rule == FillRule.EVEN_ODD) 1f else 0f
            for (b in path.beziers) {
                val p = cubic(b)
                for (j in 0 until 8) c[k * 12 + j] = p[j].toFloat()
                c[k * 12 + 8] = Float.fromBits(i)
                k++
            }
        }
        probe.upload(curves, c)
        probe.upload(shapes, s)
        probe.fill(counts, 0f)
        val glsl = GeziraGpuScene::class.java.getResource("/afield/graphics/nile/gezira_scene.comp")!!.readText()
        val stageNames = listOf("count", "scan", "emit", "bin", "resolve") + if (rowEdges < WIDE_EDGES) listOf("resolve-wide") else emptyList()
        for ((n, name) in stageNames.withIndex()) {
            val edges = if (n == 5) WIDE_EDGES else rowEdges
            val defines = "#define STAGE ${minOf(n + 1, 5)}\n#define MAXW ${(width + 31) / 32 * 32}\n#define NE ${edges}u\n#define NY ${3 * edges}u\n" +
                if (n == 5) "#define WIDE 1\n" else ""
            val src = glsl.replaceFirst("#version 450\n", "#version 450\n$defines")
            val groups = when (n) {
                0, 2 -> (curveCount + 63) / 64
                1 -> 1
                3 -> bands
                else -> height
            }
            val stage = probe.stage("gezira-scene-$name", src)
            repeat(if (repeatStage < 0 || repeatStage == n) repeats else 1) { r -> stage.dispatch(groups = max(1, groups), label = "$name$r") { bindAll(this@GeziraGpuScene) } }
        }
    }

    private fun GpuProbe.Bindings.bindAll(scene: GeziraGpuScene) {
        bind("curves") { curves }
        bind("shapes") { shapes }
        bind("offsets") { offsets }
        bind("segs") { segs }
        bind("segShape") { segShape }
        bind("bins") { bins }
        bind("counts") { counts }
        bind("image") { image }
        bind("m0") { scene.transform.let { afield.math.linalg.Vector4f(it.a.toFloat(), it.b.toFloat(), it.c.toFloat(), it.d.toFloat()) } }
        bind("m1") { scene.transform.let { afield.math.linalg.Vector4f(it.e.toFloat(), it.f.toFloat(), 0f, 0f) } }
        bind("tol") { scene.tolerance.toFloat() }
        bind("curveCount") { curveCount }
        bind("width") { width }
        bind("height") { height }
        bind("segCap") { segCap }
        bind("bandCap") { bandCap }
        bind("debug") { scene.debug }
    }

    /** The picture read back. Call once the probe has settled on [image]. */
    fun read(): FloatArray = probe.read(image, width * height * 4)

    /**
     * While true the pipeline does nothing, so [image] holds still: a box whose renderer runs on its
     * own thread sets this, lets a couple of frames pass and then [read]s, or the next frame could
     * be rewriting rows under the readback.
     */
    var hold: Boolean
        get() = (debug and 16) != 0
        set(v) { debug = if (v) debug or 16 else debug and 16.inv() }

    /** Timing experiments only: bits that skip parts of the resolve stage (see `gezira_scene.comp`). 0 draws. */
    @Volatile var debug: Int = 0

    /** Pieces dropped for want of room: segments, band entries, row edges and crossings. Should all be 0. */
    fun overflow(): IntArray = probe.readInts(counts, bands * 2 + 3).copyOfRange(bands * 2, bands * 2 + 3)

    /** Zero the overflow counters (and [widestRun]); the per-band counts are rewritten every frame anyway. */
    fun resetOverflow() = probe.fill(counts, 0f)

    /** The most edges any path put in any one row, the resolve stage's inner size. */
    fun widestRun(): Int = probe.readInts(counts, bands * 2 + 4)[bands * 2 + 3]

    /**
     * Rows of the last frame that one path crowded past [rowEdges] edges, which the resolve stage
     * left for its wide pass (512 edges, the old speed) to redraw. 0 means the fast pass drew every row.
     */
    fun wideRows(): Int = probe.readInts(counts, bands * 2 + 4 + height).copyOfRange(bands * 2 + 4, bands * 2 + 4 + height).count { it != 0 }

    /** Band entries summed over bands, from the last frame: the binned segment count. */
    fun binned(): Int = probe.readInts(counts, bands * 2).filterIndexed { i, _ -> i % 2 == 0 }.sum()

    companion object {
        /** The resolve stage's largest size, used by the wide pass for rows past [rowEdges]. */
        const val WIDE_EDGES = 512

        /** [b] as a cubic's eight coordinates, raising a line or quadratic exactly. */
        fun cubic(b: Bezier): DoubleArray {
            val p = b.p
            return when (b.degree) {
                1 -> doubleArrayOf(
                    p[0], p[1], p[0] + (p[2] - p[0]) / 3, p[1] + (p[3] - p[1]) / 3,
                    p[0] + 2 * (p[2] - p[0]) / 3, p[1] + 2 * (p[3] - p[1]) / 3, p[2], p[3],
                )
                2 -> doubleArrayOf(
                    p[0], p[1], p[0] + 2 * (p[2] - p[0]) / 3, p[1] + 2 * (p[3] - p[1]) / 3,
                    p[4] + 2 * (p[2] - p[4]) / 3, p[5] + 2 * (p[3] - p[5]) / 3, p[4], p[5],
                )
                else -> p.copyOf()
            }
        }

        /**
         * [lines] as filled paths in paint order, the input [GeziraFLine.render] takes, painted from the
         * same attributes: a `filled` line is its outline under [fillRule] in [GeziraFLine.paint]'s
         * colour; a `stroked` one is [GeziraStroke]'s outline of it at `thickness` (1 when unset),
         * made here once on the CPU, in the lines' own units, and filled non-zero.
         */
        fun paths(lines: List<FLine>): List<GeziraPath> {
            val out = ArrayList<GeziraPath>()
            for (line in lines) {
                val c = GeziraFLine.paint(line)
                if (c.a <= 0f) continue
                if (line.filled) out += GeziraPath(Gezira.beziers(line), c, line.fillRule)
                val width = line.thickness ?: 1.0
                if (line.stroked && width > 0) {
                    out += GeziraPath(Gezira.subpaths(line).flatMap { GeziraStroke.strokeBezierPath(width)(it) }, c, FillRule.NON_ZERO)
                }
            }
            return out.filter { it.beziers.isNotEmpty() }
        }

        /** A scene of [lines] (see [paths]) on [probe], drawn through [transform] into `width × height`. */
        fun of(probe: GpuProbe, lines: List<FLine>, width: Int, height: Int, transform: SvgTransform = SvgTransform.IDENTITY) =
            GeziraGpuScene(probe, paths(lines), width, height, transform)
    }
}
