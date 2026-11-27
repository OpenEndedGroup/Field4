package afield.graphics.render

import afield.document.properties.Delegation
import afield.geometry.path.FLine
import afield.geometry.path.PlanarTessellation
import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.buffers.FrameBufferGroup
import afield.graphics.vulkan.buffers.VulkanBuffer
import afield.graphics.vulkan.buffers.defaultBuffer
import afield.math.linalg.Vec3
import afield.math.linalg.Vec4
import org.lwjgl.system.MemoryUtil
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * A list of [FLine]s, drawn by the GPU as three vertex-pulled layers: strokes, fills and points.
 *
 * Every layer is one array of [FLineLayers.FLOATS_PER_VERTEX]-float vertices in a buffer the
 * shaders read by device address, so each layer is a single non-indexed draw however many lines it
 * holds. The GLSL view of a vertex, shared by all three layers, is:
 *
 * ```glsl
 * struct FLineVertex {
 *     vec4 position; // xyz: world position; w: per-layer scalar, see below
 *     vec4 color;    // straight-alpha RGBA in 0..1
 * };
 * ```
 *
 * - **strokes**: the polyline samples of every stroked line, in order. Vertex `i` and `i + 1` make
 *   a segment unless `i + 1` starts a new run; a run's first vertex carries a *negative* `w`.
 *   `|w|` is the line's `thickness` (1 when unset), which the default shaders read as pixels.
 * - **fills**: fans, restarted in the vertex data rather than by an index buffer. `w` says how
 *   many vertices back this vertex's fan centre is, and 0 marks a centre. Triangle `k` is
 *   `(v[k - w], v[k], v[k + 1])`, and there is none when `v[k].w` is 0 or `v[k + 1]` is a centre,
 *   so a layer of `n` vertices is drawn as `3n` (one triangle slot per vertex). A tessellated
 *   triangle is a three-vertex fan (`w` = 0, 1, 2); a [FillHint.STAR] subpath of `m` points is one
 *   fan of `m + 2` (centroid, the ring, the first point again). [FLine.fillHint] chooses.
 *   Fill colour is the line's colour; per-node colours do not reach the fill.
 * - **points**: one vertex per node that has a position (not `close`). `w` is the dot's radius,
 *   `pointSize * 3`, the radius the Skiko, SVG and three.js outputs draw.
 *
 * Call [upload] once a frame after changing [lines]. It is cheap for lines that have not changed,
 * even when they are new objects (as spatial rules make every frame), because it goes by content:
 *
 * 1. Each line's [FLineContentKey] is computed: a 64-bit hash of its nodes and drawing attributes.
 * 2. A line whose key was packed last frame or this one reuses that packing. Only new content is
 *    linearized and tessellated.
 * 3. The buffers are per swapchain image. Each remembers which key it holds at which offset, and a
 *    span that already holds this key at this offset is not copied again.
 *
 * So a scene whose lines all stay the same writes nothing, and one that changes at the end
 * rewrites only the end. A change near the front shifts every later line and rewrites them, but
 * does not re-tessellate them. [stats] says what each upload did.
 *
 * Only call [upload] and [close] from one thread, usually a box's `loop { }`. The renderer reads
 * the layers' buffers and counts at draw time.
 *
 * @param samplesPerCubic how many straight samples a `cubicTo` becomes, in strokes and fills.
 * @param initialVertices starting size of each layer's buffers, in vertices. They grow as needed.
 */
class FLineLayers(
    val renderer: Renderer,
    samplesPerCubic: Int = 16,
    initialVertices: Int = 4096,
) : AutoCloseable {

    /** The lines [upload] draws, in drawing order (a later line draws over an earlier one in the same layer). */
    val lines: MutableList<FLine> = ArrayList()

    /** How many straight samples a `cubicTo` becomes, in strokes and fills. Changing it repacks everything. */
    var samplesPerCubic: Int = samplesPerCubic
        set(value) {
            if (value != field) packer.clear()
            field = value
        }

    private val packer = FLinePacker()

    /** Polyline vertices of the stroked lines. */
    val strokes = FLineLayer(renderer, "fline strokes", initialVertices)

    /** Triangle-list vertices of the filled lines. */
    val fills = FLineLayer(renderer, "fline fills", initialVertices)

    /** One vertex per node of the pointed lines. */
    val points = FLineLayer(renderer, "fline points", initialVertices)

    /** What the last [upload] did: how many lines it packed or reused, and what it wrote or skipped. */
    var stats = FLineUploadStats()
        private set

    private var keys = LongArray(64)
    private var packed = arrayOfNulls<PackedFLine>(64)

    /** Replace [lines] with [next] and [upload]. */
    fun upload(next: Collection<FLine>): FLineUploadStats {
        if (next !== lines) {
            lines.clear()
            lines.addAll(next)
        }
        return upload()
    }

    /** Pack [lines] and write what changed into this frame's buffers. */
    fun upload(): FLineUploadStats {
        val t0 = System.nanoTime()
        val n = lines.size
        if (keys.size < n) {
            keys = LongArray(max(n, keys.size * 2))
            packed = arrayOfNulls(keys.size)
        }

        // 1. content keys
        for (i in 0 until n) keys[i] = FLineContentKey.of(lines[i])
        val t1 = System.nanoTime()

        // 2. packing, by content
        val packedBefore = packer.packedCount
        for (i in 0 until n) packed[i] = packer.pack(lines[i], keys[i], samplesPerCubic)
        packer.endFrame()
        val t2 = System.nanoTime()

        // 3. per-image buffer writes, skipping spans the buffer already holds
        val image = renderer.currentImage
        val s = strokes.write(image, n, keys, packed) { it.stroke }
        val f = fills.write(image, n, keys, packed) { it.fill }
        val p = points.write(image, n, keys, packed) { it.points }
        val t3 = System.nanoTime()

        for (i in 0 until n) packed[i] = null
        stats = FLineUploadStats(
            lines = n,
            packed = (packer.packedCount - packedBefore).toInt(),
            strokeVertices = strokes.count,
            fillVertices = fills.count,
            pointVertices = points.count,
            bytesWritten = s.written + f.written + p.written,
            bytesSkipped = s.skipped + f.skipped + p.skipped,
            reallocations = s.reallocations + f.reallocations + p.reallocations,
            keyNanos = t1 - t0,
            packNanos = t2 - t1,
            writeNanos = t3 - t2,
        )
        return stats
    }

    /** Release the buffers. Draws that still point at them stop finding them; remove those meshes first. */
    override fun close() {
        strokes.close()
        fills.close()
        points.close()
        packer.clear()
    }

    companion object {
        /** A vertex is two `vec4`s: 8 floats, 32 bytes. */
        const val FLOATS_PER_VERTEX = 8
    }
}

/** One upload's account, for profiling. Times are in nanoseconds; bytes count what was copied into mapped GPU memory. */
data class FLineUploadStats(
    val lines: Int = 0,
    /** Lines whose content was new, and so were linearized and tessellated. The rest reused a packing. */
    val packed: Int = 0,
    val strokeVertices: Int = 0,
    val fillVertices: Int = 0,
    val pointVertices: Int = 0,
    val bytesWritten: Long = 0,
    /** Bytes that were already in this image's buffer at the right offset, and were not copied. */
    val bytesSkipped: Long = 0,
    val reallocations: Int = 0,
    val keyNanos: Long = 0,
    val packNanos: Long = 0,
    val writeNanos: Long = 0,
) {
    val totalNanos: Long get() = keyNanos + packNanos + writeNanos

    override fun toString(): String =
        "FLineUploadStats(lines=$lines, packed=$packed, vertices s/f/p=$strokeVertices/$fillVertices/$pointVertices, " +
            "written=${bytesWritten / 1024}KiB, skipped=${bytesSkipped / 1024}KiB, realloc=$reallocations, " +
            "ms key/pack/write=%.3f/%.3f/%.3f)".format(keyNanos / 1e6, packNanos / 1e6, writeNanos / 1e6)
}

/** One line's vertices for the three layers, packed once per distinct content. Arrays are exactly sized. */
class PackedFLine(val key: Long, val stroke: FloatArray, val fill: FloatArray, val points: FloatArray)

/**
 * One layer of [FLineLayers]: a per-image, device-addressable buffer of `FLineVertex`, and the
 * bookkeeping that lets an upload skip what an image's buffer already holds.
 *
 * Bind [buffer] to a `buffer_reference` push constant and draw [count]-derived vertices.
 * [buffer]'s address is the last-written image's, so the count and the data a draw sees agree.
 */
class FLineLayer internal constructor(val renderer: Renderer, val name: String, initialVertices: Int) {

    /** One buffer per swapchain image, host visible and addressable. */
    val buffer: FrameBufferGroup = defaultBuffer(name = name) {
        perFrame = true
        size = max(1, initialVertices) * FLineLayers.FLOATS_PER_VERTEX * 4L
    }.toBuffer(renderer)

    /** Vertices in the most recently written image. */
    @Volatile
    var count: Int = 0
        private set

    /** What one image's buffer holds: the keys of the non-empty spans written there, and their float offsets, rising. */
    private class Held {
        var keys = LongArray(0)
        var offsets = IntArray(0)
        var size = 0
        var allocation: Long = 0 // the VkBuffer handle the record describes; a realloc changes it
    }

    private val held = Array(buffer.buffers.size) { Held() }

    internal class Written(val written: Long, val skipped: Long, val reallocations: Int)

    internal fun write(
        image: Int,
        n: Int,
        keys: LongArray,
        packed: Array<PackedFLine?>,
        floatsOf: (PackedFLine) -> FloatArray,
    ): Written {
        val slot = image % buffer.buffers.size
        val target: VulkanBuffer = buffer.buffers[slot]
        val record = held[slot]

        // How much room this frame needs.
        var total = 0
        for (i in 0 until n) total += floatsOf(packed[i]!!).size

        var reallocations = 0
        val neededBytes = total * 4L
        if (neededBytes > target.requestedSize) {
            target.realloc(max(neededBytes, (target.requestedSize * 3) / 2))
            reallocations++
        }
        if (record.allocation != target.buffer) {
            record.size = 0
            record.allocation = target.buffer
        }

        val floats = MemoryUtil.memFloatBuffer(target.map(), (target.requestedSize / 4).toInt())
        val nextKeys = LongArray(n)
        val nextOffsets = IntArray(n)
        var nextSize = 0
        var written = 0L
        var skipped = 0L
        var dirtyFrom = Int.MAX_VALUE
        var dirtyTo = 0
        var at = 0
        var j = 0 // walks the previous record, whose offsets rise
        for (i in 0 until n) {
            val data = floatsOf(packed[i]!!)
            if (data.isNotEmpty()) {
                nextKeys[nextSize] = keys[i]
                nextOffsets[nextSize] = at
                nextSize++
                // Equal keys pack to equal floats, so the same key at the same offset is the same bytes.
                while (j < record.size && record.offsets[j] < at) j++
                if (j < record.size && record.offsets[j] == at && record.keys[j] == keys[i]) {
                    skipped += data.size * 4L
                } else {
                    floats.put(at, data, 0, data.size)
                    written += data.size * 4L
                    dirtyFrom = min(dirtyFrom, at)
                    dirtyTo = max(dirtyTo, at + data.size)
                }
            }
            at += data.size
        }
        if (dirtyTo > dirtyFrom) target.unmap(dirtyFrom * 4L, (dirtyTo - dirtyFrom) * 4L)

        record.keys = nextKeys
        record.offsets = nextOffsets
        record.size = nextSize

        buffer.markAsWritten(image)
        count = total / FLineLayers.FLOATS_PER_VERTEX
        return Written(written, skipped, reallocations)
    }

    internal fun close() {
        buffer.buffers.forEach { it.cleanup() }
        held.forEach { it.size = 0 }
        count = 0
    }
}

/**
 * A 64-bit hash of everything about an [FLine] that changes how [FLineLayers] draws it: node kinds
 * and points, per-node `color` and `pointSize`, and the line's `color`, `thickness`, `filled`,
 * `stroked`, `pointed`, `pointSize` and `fillRule`.
 *
 * It reads the property maps directly, so hashing a line never writes a default into it (reading
 * `line.color` does). Two lines with equal keys pack to the same vertices.
 */
object FLineContentKey {
    private const val K1 = -0x61c8864680b583ebL // golden ratio, odd
    private const val K2 = -0x40a7b892e31b1a47L

    @Suppress("NOTHING_TO_INLINE")
    private inline fun mix(h: Long, v: Long): Long = java.lang.Long.rotateLeft((h xor v) * K1, 29) * K2

    @Suppress("NOTHING_TO_INLINE")
    private inline fun mix(h: Long, v: Float): Long = mix(h, java.lang.Float.floatToRawIntBits(v).toLong())

    private fun mixValue(h: Long, v: Any?): Long = when (v) {
        null -> mix(h, 0x5bd1e995L)
        is Vec4 -> mix(mix(mix(mix(h, v.x), v.y), v.z), v.w)
        is Number -> mix(h, java.lang.Double.doubleToRawLongBits(v.toDouble()))
        is Boolean -> mix(h, if (v) 3L else 5L)
        is Enum<*> -> mix(h, 7L + v.ordinal)
        else -> mix(h, v.hashCode().toLong())
    }

    fun of(line: FLine): Long {
        var h = 0x2545F4914F6CDD1DL
        val p = line.properties
        h = mixValue(h, p["color"])
        h = mixValue(h, p["thickness"])
        h = mixValue(h, p["filled"])
        h = mixValue(h, p["stroked"])
        h = mixValue(h, p["pointed"])
        h = mixValue(h, p["pointSize"])
        h = mixValue(h, p["fillRule"])
        h = mixValue(h, p["fillHint"])
        val nodes = line.nodes
        h = mix(h, nodes.size.toLong())
        for (k in nodes.indices) {
            val node = nodes[k]
            h = mix(h, when (node) {
                is FLine.MoveTo -> 1L
                is FLine.LineTo -> 2L
                is FLine.CubicTo -> 3L
                is FLine.Close -> 4L
            })
            val s = node.storage
            for (q in s.indices) {
                val v = s[q]
                h = mix(mix(mix(h, v.x), v.y), v.z)
            }
            if (node.properties.isNotEmpty()) {
                h = mixValue(h, node.properties["color"])
                h = mixValue(h, node.properties["pointSize"])
            }
        }
        // murmur3 finalizer
        h = (h xor (h ushr 33)) * -0xae502812aa7333L
        h = (h xor (h ushr 33)) * -0x3b314601e57a13adL
        return h xor (h ushr 33)
    }
}

/**
 * Turns an [FLine] into a [PackedFLine], remembering packings by content key for two frames, so a
 * line drawn again with the same content is not linearized or tessellated again.
 */
class FLinePacker {
    private var current = HashMap<Long, PackedFLine>()
    private var previous = HashMap<Long, PackedFLine>()

    /** How many packings this packer has made, ever. */
    var packedCount = 0L
        private set

    fun pack(line: FLine, key: Long, samplesPerCubic: Int): PackedFLine {
        current[key]?.let { return it }
        previous.remove(key)?.let { current[key] = it; return it }
        val packed = packNow(line, key, samplesPerCubic)
        packedCount++
        current[key] = packed
        return packed
    }

    /** Forget packings not used since the frame before this one. */
    fun endFrame() {
        val t = previous
        t.clear()
        previous = current
        current = t
    }

    fun clear() {
        current.clear()
        previous.clear()
    }

    private class Floats(capacity: Int = 64) {
        var a = FloatArray(capacity)
        var n = 0

        fun vertex(x: Float, y: Float, z: Float, w: Float, r: Float, g: Float, b: Float, alpha: Float) {
            if (n + 8 > a.size) a = a.copyOf(max(a.size * 2, n + 8))
            val q = a
            q[n] = x; q[n + 1] = y; q[n + 2] = z; q[n + 3] = w
            q[n + 4] = r; q[n + 5] = g; q[n + 6] = b; q[n + 7] = alpha
            n += 8
        }

        fun toArray(): FloatArray = if (n == 0) EMPTY else a.copyOf(n)
    }

    private val scratch = Floats(1024)

    private fun packNow(line: FLine, key: Long, samplesPerCubic: Int): PackedFLine {
        val p = line.properties
        val lineColor = p["color"] as? Vec4 ?: WHITE
        val stroked = p["stroked"] as? Boolean ?: true
        val filled = p["filled"] as? Boolean ?: false
        val pointed = p["pointed"] as? Boolean ?: false
        return PackedFLine(
            key,
            stroke = if (stroked) packStroke(line, lineColor, samplesPerCubic) else EMPTY,
            fill = if (filled) packFill(line, lineColor, samplesPerCubic) else EMPTY,
            points = if (pointed) packPoints(line, lineColor) else EMPTY,
        )
    }

    private fun packStroke(line: FLine, lineColor: Vec4, samplesPerCubic: Int): FloatArray {
        val width = ((line.properties["thickness"] as? Number)?.toFloat() ?: 1f).coerceAtLeast(0f)
        val colors = nodeColors(line, lineColor)
        val out = scratch
        out.n = 0
        line.uniform_linearize(samplesPerCubic) { at, instr ->
            // A negative instruction starts a run, at the node before |instr|; otherwise instr is
            // the (fractional, along a cubic) node index this sample belongs to.
            val start = instr < 0
            val u = if (start) max(0f, -instr - 1f) else instr
            val i0 = floor(u).toInt().coerceIn(0, colors.lastIndex)
            val i1 = ceil(u).toInt().coerceIn(0, colors.lastIndex)
            val t = u - floor(u)
            val c0 = colors[i0]
            val c1 = colors[i1]
            out.vertex(
                at.x, at.y, at.z, if (start) -max(width, 1e-6f) else width,
                c0.x + (c1.x - c0.x) * t, c0.y + (c1.y - c0.y) * t,
                c0.z + (c1.z - c0.z) * t, c0.w + (c1.w - c0.w) * t,
            )
        }
        return out.toArray()
    }

    private fun packFill(line: FLine, lineColor: Vec4, samplesPerCubic: Int): FloatArray {
        val out = scratch
        out.n = 0
        val r = lineColor.x
        val g = lineColor.y
        val b = lineColor.z
        val a = lineColor.w
        fun triangle(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, x2: Float, y2: Float, z2: Float) {
            out.vertex(x0, y0, z0, 0f, r, g, b, a)
            out.vertex(x1, y1, z1, 1f, r, g, b, a)
            out.vertex(x2, y2, z2, 2f, r, g, b, a)
        }
        when (line.properties["fillHint"] as? FillHint ?: FillHint.HOLES) {
            FillHint.HOLES -> line.tessellate(samplesPerCubic) { verts, index ->
                var t = 0
                while (t + 2 < index.size) {
                    val i = 3 * index[t]
                    val j = 3 * index[t + 1]
                    val k = 3 * index[t + 2]
                    triangle(verts[i], verts[i + 1], verts[i + 2], verts[j], verts[j + 1], verts[j + 2], verts[k], verts[k + 1], verts[k + 2])
                    t += 3
                }
            }

            FillHint.NO_HOLES -> for (ring in rings(line, samplesPerCubic)) {
                for (polygon in PlanarTessellation.tessellate(listOf(ring))) {
                    val tri = polygon.triangles
                    var t = 0
                    while (t + 2 < tri.size) {
                        val p0 = ring[tri[t].vertex]
                        val p1 = ring[tri[t + 1].vertex]
                        val p2 = ring[tri[t + 2].vertex]
                        triangle(p0.x, p0.y, p0.z, p1.x, p1.y, p1.z, p2.x, p2.y, p2.z)
                        t += 3
                    }
                }
            }

            FillHint.STAR -> for (ring in rings(line, samplesPerCubic)) {
                var cx = 0f
                var cy = 0f
                var cz = 0f
                for (q in ring) { cx += q.x; cy += q.y; cz += q.z }
                val n = ring.size
                out.vertex(cx / n, cy / n, cz / n, 0f, r, g, b, a)
                for (i in 0..n) {
                    val q = ring[i % n]
                    out.vertex(q.x, q.y, q.z, (i + 1).toFloat(), r, g, b, a)
                }
            }
        }
        return out.toArray()
    }

    /** The closed rings of [line]'s subpaths, flattened, without repeated points; rings under three points are dropped. */
    private fun rings(line: FLine, samplesPerCubic: Int): List<List<Vec3>> {
        val rings = ArrayList<List<Vec3>>()
        var current = ArrayList<Vec3>()
        fun same(p: Vec3, q: Vec3) = p.x == q.x && p.y == q.y && p.z == q.z
        fun flush() {
            if (current.size >= 2 && same(current[0], current[current.lastIndex])) current.removeAt(current.lastIndex)
            if (current.size >= 3) rings += current
            current = ArrayList()
        }
        line.uniform_linearize(samplesPerCubic) { at, instr ->
            if (instr < 0) flush()
            if (current.isEmpty() || !same(current[current.lastIndex], at)) current += Vec3(at)
        }
        flush()
        return rings
    }

    private fun packPoints(line: FLine, lineColor: Vec4): FloatArray {
        val size = (line.properties["pointSize"] as? Number)?.toFloat() ?: 1f
        val out = scratch
        out.n = 0
        for (node in line.nodes) {
            if (node is FLine.Close) continue
            val at = node.to
            val c = node.properties["color"] as? Vec4 ?: lineColor
            val s = (node.properties["pointSize"] as? Number)?.toFloat() ?: size
            out.vertex(at.x, at.y, at.z, 3f * s, c.x, c.y, c.z, c.w)
        }
        return out.toArray()
    }

    /** One colour per node: the node's own `color`, else the previous node's, else the line's. */
    private fun nodeColors(line: FLine, lineColor: Vec4): Array<Vec4> {
        var current = lineColor
        return Array(max(1, line.nodes.size)) { i ->
            (line.nodes.getOrNull(i)?.properties?.get("color") as? Vec4)?.let { current = it }
            current
        }
    }

    companion object {
        private val EMPTY = FloatArray(0)
        private val WHITE = Vec4(1f, 1f, 1f, 1f)
    }
}

/** How [FLineLayers] fills a filled [FLine]: as much tessellation as the shape needs, and no more. */
enum class FillHint {
    /** Rings may nest and cut holes, under the line's `fillRule`. The full tessellator, and the default. */
    HOLES,

    /** Every subpath is filled on its own: no ring is a hole in another. Skips fill-rule resolution and hole assignment. */
    NO_HOLES,

    /**
     * Every subpath is ordered and star-convex about its centroid (circles, dots, convex
     * polygons, most blobs): each becomes one fan, with no tessellator at all. A subpath that is
     * not star-convex draws folded-over triangles.
     */
    STAR,
}

/**
 * How [FLineLayers] tessellates this line's fill; see [FillHint]. [FillHint.HOLES] by default.
 * Only the GPU layers read it.
 */
var FLine.fillHint by Delegation<FillHint> { FillHint.HOLES }
