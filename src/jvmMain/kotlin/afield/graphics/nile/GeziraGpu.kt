/**
 * The last stage of [Gezira.rasterizer], WindingSpans, as a Vulkan compute shader: one invocation
 * per pixel row resolves that row's edges exactly into a coverage image.
 */

package afield.graphics.nile

import afield.graphics.vulkan.buffers.FrameBufferGroup
import afield.graphics.vulkan.util.GpuProbe
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * A GPU backend for [Gezira.rasterize].
 *
 * The first three stages of the winding rasteriser, FlattenBeziers → RowEdges → sort, stay on the
 * CPU as the same kernels ([pack]); what runs on the GPU is WindingSpans, the stage that does the
 * arithmetic. Each invocation owns one pixel row. It gathers the row's band boundaries (every edge
 * end and every crossing of two edges) into a scratch slice and sorts them; in each band it finds,
 * for every active edge, the winding number just to its right and its right-hand neighbour, and
 * wherever the fill rule says that region is inside it adds the trapezoid's exact area to each
 * pixel column it covers. No two invocations touch the same pixels, so there are no atomics.
 *
 * The row's edges are kept in a permutation ordered by x at the band's middle (ties by index, as the
 * CPU's stable sort does). The order changes only at crossings, which are band boundaries, so each
 * band re-sorts an almost sorted list by insertion: a band costs about O(n), as on the CPU. Flattened
 * curves put hundreds of edges in a row, so this matters; an earlier version that found each edge's
 * neighbour by counting, O(n²) a band, took seconds a frame on a 128 × 128 field of circles.
 *
 * Coordinates go to the GPU relative to the row (y in 0..1) and to the row's leftmost column, so
 * float32 keeps about seven digits of the position within the shape, not within the image.
 *
 * The buffers and the stage come from a [GpuProbe], which owns their lifetime, the sentinel that
 * says the shader ran, and the readback. From `jvmMain`, `probe.settle(job.coverage)` waits; from a
 * box, `probe.arm(job.coverage)` and `while (probe.stillWaiting()) frame()` inside `frames { }`.
 */
object GeziraGpu {

    /** Rows per workgroup; matches `local_size_x` in [GLSL]. */
    const val ROWS_PER_GROUP = 64

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
        /** Four uints per row, as float bits: first edge, edge count, scratch start, left column (signed). */
        val rows: FloatArray,
        /** Floats of band-boundary scratch the rows need between them. */
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
        val rowEdges = (Gezira.flattenBeziers(tolerance) then Gezira.rowEdges then Gezira.sortRows)(beziers)
            .filter { it.row in 0 until h }
        val byRow = arrayOfNulls<MutableList<RowEdge>>(h)
        for (e in rowEdges) (byRow[e.row] ?: ArrayList<RowEdge>().also { byRow[e.row] = it }).add(e)
        val edges = FloatArray(max(1, rowEdges.size) * 8)
        val rows = FloatArray(h * 4)
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
            // every end, at most one crossing per pair, then a permutation and its keys
            if (n >= 2) scratch += 4 * n + n * (n - 1) / 2
        }
        return Packed(w, h, rule, edges, rows, max(1, scratch), rowEdges.size)
    }

    /** One shape's buffers on a probe; [coverage] is `w × h` floats, row-major, y down. */
    class Job(val probe: GpuProbe, val packed: Packed, val coverage: FrameBufferGroup) {
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
        val scratch = probe.fixture("$label.scratch", packed.scratch)
        val coverage = probe.fixture("$label.coverage", packed.width * packed.height)
        probe.upload(edges, packed.edges)
        probe.upload(rows, packed.rows)
        val groups = (packed.height + ROWS_PER_GROUP - 1) / ROWS_PER_GROUP
        stage.dispatch(groups = max(1, groups), label = label) {
            bind("edges") { edges }
            bind("rows") { rows }
            bind("scratch") { scratch }
            bind("coverage") { coverage }
            bind("width") { packed.width }
            bind("height") { packed.height }
            bind("evenOdd") { if (packed.rule == FillRule.EVEN_ODD) 1 else 0 }
        }
        return Job(probe, packed, coverage)
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
