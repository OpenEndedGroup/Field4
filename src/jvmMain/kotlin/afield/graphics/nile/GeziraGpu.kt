/**
 * The last stage of [Gezira.rasterizer], WindingSpans, as a Vulkan compute shader: one invocation
 * workgroup per pixel row resolves that row's edges exactly into a coverage image.
 */

package afield.graphics.nile

import afield.graphics.vulkan.buffers.FrameBufferGroup
import afield.graphics.vulkan.buffers.defaultBuffer
import afield.graphics.vulkan.util.GpuProbe
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * A GPU backend for [Gezira.rasterize].
 *
 * The first three stages of the winding rasteriser, FlattenBeziers → RowEdges → sort, stay on the
 * CPU as the same kernels ([pack]); what runs on the GPU is WindingSpans, the stage that does the
 * arithmetic. Each pixel row is one workgroup of [THREADS_PER_ROW] invocations. Together they gather
 * the row's band boundaries (every edge end and every crossing of two edges) into a scratch slice
 * and rank-sort them; then each invocation takes a run of consecutive bands, walks each band's edges
 * left to right counting the winding number, and wherever the fill rule says the region between two
 * neighbours is inside adds that trapezoid's exact area to each pixel column it covers. The row is
 * summed in shared memory as fixed point (2^24 to a pixel), since integer atomics are everywhere and
 * float ones are not.
 *
 * Each invocation keeps the row's edges in a permutation ordered by x at the band's middle (ties by
 * index, as the CPU's stable sort does). The order changes only at crossings, which are band
 * boundaries, so each band re-sorts an almost sorted list by insertion: a band costs about O(n), as on
 * the CPU. Flattened curves put hundreds of edges in a row, so this matters; a first version that
 * found each edge's neighbour by counting, O(n²) a band, took seconds a frame on a 128 × 128 field of
 * circles. Each invocation's first band is sorted by heap sort, O(n log n): insertion from an
 * arbitrary order there cost O(n²) per invocation and made the shader five times slower.
 *
 * Coordinates go to the GPU relative to the row (y in 0..1) and to the row's leftmost column, so
 * float32 keeps about seven digits of the position within the shape, not within the image.
 *
 * The buffers and the stage come from a [GpuProbe], which owns their lifetime, the sentinel that
 * says the shader ran, and the readback. From `jvmMain`, `probe.settle(job.coverage)` waits; from a
 * box, `probe.arm(job.coverage)` and `while (probe.stillWaiting()) frame()` inside `frames { }`.
 */
object GeziraGpu {

    /** Invocations per row (one workgroup a row); matches `T` in [GLSL]. */
    const val THREADS_PER_ROW = 32

    /** Widest image the shader's shared-memory row accumulator holds; `MAX_WIDTH` in [GLSL]. */
    const val MAX_WIDTH = 4096

    /** The WindingSpans compute shader. */
    val GLSL: String by lazy {
        GeziraGpu::class.java.getResource("/afield/graphics/nile/gezira_winding.comp")!!.readText()
    }

    /** A shape flattened, cut into rows and laid out for [GLSL]. */
    class Packed(
        val width: Int,
        val height: Int,
        val rule: FillRule,
        /** Two vec4 per edge: `x0, y0, x1, y1` (x from the row's left, y from the row's top), then `dir, 0, 0, 0`. */
        val edges: FloatArray,
        /** Four uints per row, as float bits: first edge, edge count, scratch start, left column (signed); then one more row whose scratch start is the total. */
        val rows: FloatArray,
        /** Floats of scratch the rows need between them: two boundary lists and a permutation per invocation each. */
        val scratch: Int,
        /** Row edges in all. */
        val edgeCount: Int,
    )

    /**
     * Run FlattenBeziers → RowEdges → sort on the CPU, keep the rows inside `0 until h`, and lay the
     * result out for the shader.
     */
    fun pack(
        beziers: List<Bezier>, w: Int, h: Int,
        rule: FillRule = FillRule.NON_ZERO, tolerance: Double = Gezira.FLATNESS,
    ): Packed {
        require(w in 1..MAX_WIDTH) { "GeziraGpu rasterises images 1 to $MAX_WIDTH pixels wide, not $w" }
        val rowEdges = (Gezira.flattenBeziers(tolerance) then Gezira.rowEdges then Gezira.sortRows)(beziers)
            .filter { it.row in 0 until h }
        val byRow = arrayOfNulls<MutableList<RowEdge>>(h)
        for (e in rowEdges) (byRow[e.row] ?: ArrayList<RowEdge>().also { byRow[e.row] = it }).add(e)
        val edges = FloatArray(max(1, rowEdges.size) * 8)
        val rows = FloatArray((h + 1) * 4)
        var next = 0
        var scratch = 0
        for (r in 0 until h) {
            val list = byRow[r].orEmpty()
            val n = list.size
            var lo = Double.POSITIVE_INFINITY
            for (e in list) lo = min(lo, min(e.x0, e.x1))
            val left = if (n == 0) 0 else floor(lo).toInt()
            rows[r * 4] = Float.fromBits(next)
            rows[r * 4 + 1] = Float.fromBits(n)
            rows[r * 4 + 2] = Float.fromBits(scratch)
            rows[r * 4 + 3] = Float.fromBits(left)
            for (e in list) {
                val o = next * 8
                edges[o] = (e.x0 - left).toFloat()
                edges[o + 1] = (e.y0 - r).toFloat()
                edges[o + 2] = (e.x1 - left).toFloat()
                edges[o + 3] = (e.y1 - r).toFloat()
                edges[o + 4] = e.dir.toFloat()
                next++
            }
            // Two boundary lists (every end and every crossing, counted here as the CPU finds them,
            // with slack for the few the shader's float32 test may find besides), then a
            // permutation and its keys for each invocation.
            if (n >= 2) scratch += 2 * (2 * n + crossings(list) + 8 + n / 8) + 2 * THREADS_PER_ROW * n
        }
        rows[h * 4 + 2] = Float.fromBits(scratch)
        return Packed(w, h, rule, edges, rows, max(1, scratch), rowEdges.size)
    }

    /** Pairs of [edges] that cross strictly inside the band both span, as [Gezira.resolveRow] finds them. */
    private fun crossings(edges: List<RowEdge>): Int {
        var c = 0
        for (i in edges.indices) for (j in i + 1 until edges.size) {
            val a = edges[i]
            val b = edges[j]
            val y0 = max(a.y0, b.y0)
            val y1 = min(a.y1, b.y1)
            if (y1 <= y0) continue
            val d0 = a.xAt(y0) - b.xAt(y0)
            val d1 = a.xAt(y1) - b.xAt(y1)
            if ((d0 < 0 && d1 > 0) || (d0 > 0 && d1 < 0)) c++
        }
        return c
    }

    /**
     * One shape's buffers on a probe; [coverage] is `w × h` floats, row-major, y down. [status] holds
     * per row the band boundaries the shader dropped for want of scratch, which should be none.
     */
    class Job(val probe: GpuProbe, val packed: Packed, val coverage: FrameBufferGroup, val status: FrameBufferGroup) {
        /** Band boundaries dropped, over all rows; nonzero means the coverage is approximate. */
        fun dropped(): Int = probe.readInts(status, packed.height).sum()

        /** The coverage image, read back. Call after the probe has settled on [coverage]. */
        fun read(): DoubleArray {
            val f = probe.read(coverage, packed.width * packed.height)
            return DoubleArray(f.size) { f[it].toDouble() }
        }
    }

    /** The WindingSpans stage on [probe]; compile it once and pass it to every [dispatch]. */
    fun stage(probe: GpuProbe): GpuProbe.Stage = probe.stage("gezira-winding", GLSL)

    /**
     * Upload [packed] into fresh buffers on [probe] and add a dispatch of [stage] over its rows. The
     * dispatch then runs every frame until the probe is closed, rewriting the same image.
     */
    fun dispatch(probe: GpuProbe, stage: GpuProbe.Stage, packed: Packed, label: String = "shape"): Job {
        val edges = probe.fixture("$label.edges", packed.edges.size)
        val rows = probe.fixture("$label.rows", max(4, packed.rows.size))
        // Scratch is the shader's alone, so device-local rather than a host-readable fixture.
        val scratch = defaultBuffer(name = "${probe.name}.$label.scratch") {
            size = packed.scratch.floats
            zeroInit()
        }.toBuffer(probe.renderer)
        val coverage = probe.fixture("$label.coverage", packed.width * packed.height)
        val status = probe.fixture("$label.status", packed.height)
        probe.upload(edges, packed.edges)
        probe.upload(rows, packed.rows)
        probe.fill(status, 0f)
        stage.dispatch(groups = packed.height, label = label) {
            bind("edges") { edges }
            bind("rows") { rows }
            bind("scratch") { scratch }
            bind("coverage") { coverage }
            bind("status") { status }
            bind("width") { packed.width }
            bind("height") { packed.height }
            bind("evenOdd") { if (packed.rule == FillRule.EVEN_ODD) 1 else 0 }
        }
        return Job(probe, packed, coverage, status)
    }

    /** [pack] then [dispatch]. */
    fun dispatch(
        probe: GpuProbe, stage: GpuProbe.Stage, beziers: List<Bezier>, w: Int, h: Int,
        rule: FillRule = FillRule.NON_ZERO, label: String = "shape",
    ): Job = dispatch(probe, stage, pack(beziers, w, h, rule), label)

    /** A closed polygon `x0, y0, x1, y1, ...` as lines. */
    fun polygon(vararg xy: Double): List<Bezier> = (0 until xy.size / 2).map { i ->
        val j = (i + 1) % (xy.size / 2)
        Bezier(doubleArrayOf(xy[2 * i], xy[2 * i + 1], xy[2 * j], xy[2 * j + 1]))
    }

    /** A star of [points] points, outer radius [r] and inner radius [inner], as a simple polygon. */
    fun star(cx: Double, cy: Double, r: Double, inner: Double, points: Int = 5, rotation: Double = 0.0): List<Bezier> {
        val xy = DoubleArray(points * 4)
        for (i in 0 until 2 * points) {
            val a = rotation - Math.PI / 2 + i * Math.PI / points
            val rr = if (i % 2 == 0) r else inner
            xy[2 * i] = cx + rr * kotlin.math.cos(a)
            xy[2 * i + 1] = cy + rr * kotlin.math.sin(a)
        }
        return polygon(*xy)
    }

    /** A pentagram drawn as one self-crossing outline, the even-odd test case. */
    fun pentagram(cx: Double, cy: Double, r: Double): List<Bezier> {
        val xy = DoubleArray(10)
        for (i in 0 until 5) {
            val a = -Math.PI / 2 + i * 4 * Math.PI / 5
            xy[2 * i] = cx + r * kotlin.math.cos(a)
            xy[2 * i + 1] = cy + r * kotlin.math.sin(a)
        }
        return polygon(*xy)
    }

    /** A square with a square hole, the hole wound the same way when [sameWay], else opposite. */
    fun holedSquare(x: Double, y: Double, s: Double, hole: Double, sameWay: Boolean): List<Bezier> {
        val o = polygon(x, y, x + s, y, x + s, y + s, x, y + s)
        val a = x + (s - hole) / 2
        val b = y + (s - hole) / 2
        val inner = if (sameWay) polygon(a, b, a + hole, b, a + hole, b + hole, a, b + hole)
        else polygon(a, b, a, b + hole, a + hole, b + hole, a + hole, b)
        return o + inner
    }
}
