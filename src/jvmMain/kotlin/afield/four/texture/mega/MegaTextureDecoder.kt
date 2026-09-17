package afield.four.texture.mega

import afield.logger.lprintln
import org.lwjgl.stb.STBImage.stbi_image_free
import org.lwjgl.stb.STBImage.stbi_load
import org.lwjgl.stb.STBImageResize.stbir_resize_uint8_linear
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * One decoded, downsampled page on its way to the GPU.
 *
 * [pixels] is native memory from [MegaTextureDecoder]'s per-level pool; whoever consumes the page
 * must hand it back with [MegaTextureDecoder.recycle] or the pool bleeds.
 *
 * [usedWidth] / [usedHeight] are the part of the page the picture actually occupies. The
 * rest is left at whatever the pool last held: never sampled, because the page table publishes
 * this rectangle and the shader scales its UVs by it.
 */
class MegaDecodedPage(
    val index: Int,
    val level: Int,
    val pageWidth: Int,
    val pageHeight: Int,
    val pixels: ByteBuffer,
    val usedWidth: Int,
    val usedHeight: Int,
    /** Mean colour of the picture, packed `0xAABBGGRR` — what a not-yet-resident image draws as. */
    val averageColor: Int,
    val sourceWidth: Int,
    val sourceHeight: Int,
    /** True if this page came off disk rather than out of a JPEG decode. */
    val fromCache: Boolean
)

/**
 * The parallel decode/downsample/cache queue behind a [MegaTexture].
 *
 * Three things make this different from a plain executor, and each of them is the whole reason
 * this class exists rather than a `submit { }` call:
 *
 * **Priority.** The camera moves, so what is worth decoding changes every few frames. Requests
 * carry a score (see [MegaTexture]) and are served best-first out of a [PriorityBlockingQueue].
 *
 * **Cancellation without bookkeeping.** A queued request is not removed when the camera turns
 * away; instead every worker re-asks [stillWanted] the moment it pops one. Cancelling a million
 * queued requests is then free, and the check is against live state rather than against a snapshot
 * that was already stale when it was taken. Requests also carry the generation they were made in,
 * so a worker can drop anything the requester has since superseded.
 *
 * **Free work.** A cache miss has to decode the full-size JPEG, which is nearly all of the cost.
 * Having paid it, this writes *every* cacheable level of that image, not just the one asked for.
 * The first pass over a directory therefore populates the whole small-page pyramid, and the wall's
 * base level comes up in one pass rather than one pass per level.
 */
class MegaTextureDecoder(
    private val catalog: MegaTextureCatalog,
    private val cache: MegaTexturePyramidCache,
    /** Where page sizes come from, so the decoder resamples to exactly what the atlas will hold. */
    private val layout: MegaTextureLayout,
    threads: Int,
    /** Answers "is this still worth doing?" from the requester's live state. Called off-thread. */
    private val stillWanted: (index: Int, level: Int, generation: Long) -> Boolean
) : AutoCloseable {

    private class Request(
        val index: Int,
        val level: Int,
        val score: Float,
        val generation: Long,
        val sequence: Long
    ) : Comparable<Request> {
        override fun compareTo(other: Request): Int {
            val byScore = other.score.compareTo(score)
            if (byScore != 0) return byScore
            return other.sequence.compareTo(sequence)
        }
    }

    private val queue = PriorityBlockingQueue<Request>()
    private val done = ConcurrentLinkedQueue<MegaDecodedPage>()

    /**
     * Requests that left the system without producing a page, packed as `index shl 4 or level`.
     *
     * The requester marks an (image, level) as asked-for so it does not ask twice, and that mark
     * has to come off again on *every* path out — cancelled as stale, trimmed off an over-deep
     * queue, or failed to decode. Reporting them is what lets the mark be exact instead of a
     * timeout, and a timeout is what it was: too short and a slow cold decode re-requested
     * everything every few seconds (twenty thousand queued requests for 1639 images), too long
     * and a dropped request stranded its image.
     */
    private val abandoned = ConcurrentLinkedQueue<Int>()
    private val levels: Int get() = layout.levels
    private val pools = Array(layout.levels) { ConcurrentLinkedQueue<ByteBuffer>() }
    private val stopping = AtomicBoolean(false)
    private val sequence = AtomicLong()
    private val workers: List<Thread>

    val decodedFromCache = AtomicLong()
    val decodedFromSource = AtomicLong()
    val decodeFailures = AtomicLong()
    val cancelled = AtomicLong()
    val inFlight = AtomicInteger()

    init {
        workers = (0 until max(1, threads)).map { n ->
            Thread({ workerLoop() }, "megatexture-decode-$n").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY - 1
                start()
            }
        }
    }

    val queueDepth: Int get() = queue.size
    val readyCount: Int get() = done.size

    /** Ask for (image, level). Higher [score] is served first. */
    fun request(index: Int, level: Int, score: Float, generation: Long) {
        if (stopping.get()) return
        queue.add(Request(index, level, score, generation, sequence.incrementAndGet()))
    }

    /** Take a finished page, or null. The caller owns [MegaDecodedPage.pixels] until it recycles it. */
    fun poll(): MegaDecodedPage? = done.poll()

    /** Return a page's native memory to the pool it came from. */
    fun recycle(page: MegaDecodedPage) {
        if (page.level in 0 until levels) {
            page.pixels.clear()
            pools[page.level].add(page.pixels)
        } else {
            MemoryUtil.memFree(page.pixels)
        }
    }

    /**
     * Drop queued requests beyond [keep], cheapest-scoring first.
     *
     * The queue is unbounded on purpose — a feedback frame can legitimately name a hundred
     * thousand images at once, and refusing the request is worse than serving it late. This keeps
     * that from turning into unbounded memory when the camera sweeps.
     */
    fun trimTo(keep: Int) {
        if (queue.size <= keep) return
        val held = ArrayList<Request>(keep)
        var taken = 0
        while (taken < keep) {
            val next = queue.poll() ?: break
            held.add(next)
            taken++
        }
        val dropped = ArrayList<Request>()
        queue.drainTo(dropped)
        queue.addAll(held)
        dropped.forEach { abandon(it.index, it.level) }
        cancelled.addAndGet(dropped.size.toLong())
    }

    private fun abandon(index: Int, level: Int) {
        abandoned.add((index shl 4) or (level and 0xf))
    }

    /** Hand every abandoned request since the last call to [sink], as `(index, level)`. */
    fun drainAbandoned(sink: (index: Int, level: Int) -> Unit) {
        while (true) {
            val packed = abandoned.poll() ?: break
            sink(packed shr 4, packed and 0xf)
        }
    }

    private fun borrow(level: Int): ByteBuffer {
        val bytes = layout.pageBytes(level)
        val pooled = pools[level].poll()
        if (pooled != null && pooled.capacity() >= bytes) {
            pooled.clear()
            return pooled
        }
        if (pooled != null) MemoryUtil.memFree(pooled)
        return MemoryUtil.memAlloc(bytes)
    }

    private fun workerLoop() {
        while (!stopping.get()) {
            val request = try {
                queue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                null
            } ?: continue

            if (!stillWanted(request.index, request.level, request.generation)) {
                abandon(request.index, request.level)
                cancelled.incrementAndGet()
                continue
            }

            inFlight.incrementAndGet()
            try {
                val page = produce(request.index, request.level)
                if (page != null) done.add(page) else {
                    abandon(request.index, request.level)
                    decodeFailures.incrementAndGet()
                }
            } catch (t: Throwable) {
                abandon(request.index, request.level)
                decodeFailures.incrementAndGet()
                lprintln(
                    "warning,megatexture",
                    "megatexture decode failed for ${catalog.path(request.index)} at level ${request.level}: ${t.message}"
                )
            } finally {
                inFlight.decrementAndGet()
            }
        }
    }

    private fun produce(index: Int, level: Int): MegaDecodedPage? {
        val pageW = layout.pageWidth(level)
        val pageH = layout.pageHeight(level)
        val sourceSize = cache.sourceSize(index)

        // Cache hit needs the source dimensions too: the page's used rectangle is derived from
        // them, and a page with no known aspect would be published as the page's own shape.
        if (sourceSize != null && cache.has(index, level)) {
            val pixels = borrow(level)
            pixels.clear()
            if (cache.read(index, level, pixels)) {
                pixels.position(0).limit(layout.pageBytes(level))
                val (usedW, usedH) = fitInto(sourceSize.first, sourceSize.second, pageW, pageH)
                decodedFromCache.incrementAndGet()
                return MegaDecodedPage(
                    index = index,
                    level = level,
                    pageWidth = pageW,
                    pageHeight = pageH,
                    pixels = pixels,
                    usedWidth = usedW,
                    usedHeight = usedH,
                    averageColor = averageOf(pixels, pageW, usedW, usedH),
                    sourceWidth = sourceSize.first,
                    sourceHeight = sourceSize.second,
                    fromCache = true
                )
            }
            pixels.clear()
            pools[level].add(pixels)
        }

        val path = catalog.path(index) ?: return null
        return MemoryStack.stackPush().use { stack ->
            val widthOut = stack.mallocInt(1)
            val heightOut = stack.mallocInt(1)
            val channelsOut = stack.mallocInt(1)
            val decoded = stbi_load(path.toString(), widthOut, heightOut, channelsOut, 4) ?: return@use null
            val sourceWidth = widthOut[0]
            val sourceHeight = heightOut[0]
            try {
                cache.setSourceSize(index, sourceWidth, sourceHeight)
                decodedFromSource.incrementAndGet()

                // The full-size decode is the expensive part; spend it on every cacheable level.
                for (other in cache.cachedLevels()) {
                    if (other == level || cache.has(index, other)) continue
                    val scratch = MemoryUtil.memAlloc(layout.pageBytes(other))
                    try {
                        val ok = fitIntoPage(
                            decoded, sourceWidth, sourceHeight, scratch,
                            layout.pageWidth(other), layout.pageHeight(other)
                        )
                        if (ok) cache.write(index, other, scratch)
                    } finally {
                        MemoryUtil.memFree(scratch)
                    }
                }

                val pixels = borrow(level)
                pixels.clear()
                if (!fitIntoPage(decoded, sourceWidth, sourceHeight, pixels, pageW, pageH)) {
                    pixels.clear()
                    pools[level].add(pixels)
                    return@use null
                }
                cache.write(index, level, pixels)
                pixels.position(0).limit(layout.pageBytes(level))
                val (usedW, usedH) = fitInto(sourceWidth, sourceHeight, pageW, pageH)
                MegaDecodedPage(
                    index = index,
                    level = level,
                    pageWidth = pageW,
                    pageHeight = pageH,
                    pixels = pixels,
                    usedWidth = usedW,
                    usedHeight = usedH,
                    averageColor = averageOf(pixels, pageW, usedW, usedH),
                    sourceWidth = sourceWidth,
                    sourceHeight = sourceHeight,
                    fromCache = false
                )
            } finally {
                stbi_image_free(decoded)
            }
        }
    }

    override fun close() {
        stopping.set(true)
        queue.clear()
        workers.forEach { it.interrupt() }
        workers.forEach { runCatching { it.join(500) } }
        while (true) {
            val page = done.poll() ?: break
            MemoryUtil.memFree(page.pixels)
        }
        pools.forEach { pool ->
            while (true) MemoryUtil.memFree(pool.poll() ?: break)
        }
    }

    companion object {
        /**
         * The picture's size inside a [pageWidth]×[pageHeight] page — contained, aspect kept.
         *
         * "Contained" is the whole content of this function, and it is why the page being
         * rectangular does not complicate anything downstream: whichever of the two ratios is
         * tighter decides, so a picture the same shape as its page fills it exactly, a wider one
         * is limited by width, a taller one by height. A 1920×798 frame in a 1920×798 page comes
         * back 1920×798 and wastes not one texel.
         *
         * Both results are at least 1: a 4000×1 panorama in an 8-pixel page is an 8×1 strip, not a
         * zero-height one, and a zero would make the page table publish an empty rectangle.
         */
        fun fitInto(sourceWidth: Int, sourceHeight: Int, pageWidth: Int, pageHeight: Int): Pair<Int, Int> {
            if (sourceWidth <= 0 || sourceHeight <= 0) return pageWidth to pageHeight
            val scale = minOf(
                pageWidth.toDouble() / sourceWidth,
                pageHeight.toDouble() / sourceHeight
            )
            val w = max(1, (sourceWidth * scale).roundToInt()).coerceAtMost(pageWidth)
            val h = max(1, (sourceHeight * scale).roundToInt()).coerceAtMost(pageHeight)
            return w to h
        }

        /** [fitInto] for a square page — the shorthand the square page ladder uses. */
        fun fitInto(sourceWidth: Int, sourceHeight: Int, pageSize: Int): Pair<Int, Int> =
            fitInto(sourceWidth, sourceHeight, pageSize, pageSize)

        /**
         * Resample RGBA [source] into the top-left `fitInto(...)` rectangle of the page.
         *
         * `stb_image_resize` takes the destination's row stride, which is what lets the picture go
         * into a corner of a larger page without a second copy.
         */
        private fun fitIntoPage(
            source: ByteBuffer,
            sourceWidth: Int,
            sourceHeight: Int,
            page: ByteBuffer,
            pageWidth: Int,
            pageHeight: Int
        ): Boolean {
            val (usedW, usedH) = fitInto(sourceWidth, sourceHeight, pageWidth, pageHeight)
            source.position(0)
            page.position(0)
            val result = stbir_resize_uint8_linear(
                source, sourceWidth, sourceHeight, 0,
                page, usedW, usedH, pageWidth * 4,
                4
            )
            source.position(0)
            page.position(0)
            page.limit(page.capacity())
            return result != null
        }

        /**
         * Mean colour over the page's used rectangle, packed `0xAABBGGRR` to match the shader's
         * unpack order.
         *
         * Sampled rather than summed: an every-nth-pixel walk over a full-size page is within a
         * percent of the true mean and this runs on the decode threads, where the full-size JPEG
         * decode is already the thing to beat.
         */
        internal fun averageOf(page: ByteBuffer, pageWidth: Int, usedWidth: Int, usedHeight: Int): Int {
            var r = 0L
            var g = 0L
            var b = 0L
            var n = 0L
            val step = max(1, usedWidth / 16)
            var y = 0
            while (y < usedHeight) {
                var x = 0
                val row = y * pageWidth * 4
                while (x < usedWidth) {
                    val at = row + x * 4
                    r += page.get(at).toInt() and 0xff
                    g += page.get(at + 1).toInt() and 0xff
                    b += page.get(at + 2).toInt() and 0xff
                    n++
                    x += step
                }
                y += step
            }
            if (n == 0L) return 0xff808080.toInt()
            return (0xff shl 24) or
                    (((b / n).toInt() and 0xff) shl 16) or
                    (((g / n).toInt() and 0xff) shl 8) or
                    ((r / n).toInt() and 0xff)
        }
    }
}
