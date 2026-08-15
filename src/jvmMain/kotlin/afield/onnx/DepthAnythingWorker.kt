@file:afield.CompletionCategory("Machine learning")

package afield.onnx

import afield.logger.lprintln
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * [DepthAnything] on its own thread, so a 100–400ms inference cannot stall the renderer.
 *
 * This is not an optimisation, it is the only workable arrangement. Depth Anything Small costs ≈101ms
 * at 266x266 and ≈388ms at 518x518 on a CPU execution provider; calling [DepthAnything.infer] from a
 * render loop turns 60fps into 2.6fps. So the renderer gets *staleness* instead of latency: the most
 * recent depth map that happens to be finished, which at 266x266 is six frames old at 60fps and looks
 * fine for displacement, parallax and depth-keyed compositing, and looks like lag for anything a hand
 * is steering directly.
 *
 * ```kotlin
 * // once
 * val depth = DepthAnythingWorker(width = 266, height = 266)
 * val tex = StreamingTexture(depth.depthFp16, depth.width, depth.height,
 *                            imageFormat = VK10.VK_FORMAT_R16_SFLOAT)
 * // per frame
 * depth.submit(webcam.frameBuffer, 640, 480)   // returns immediately, drops old frames
 * if (depth.consume()) tex.recordUpload(commandBuffer)
 * ```
 *
 * ## Frames are dropped, not queued
 *
 * [submit] overwrites any frame that has not been picked up yet. A queue would be wrong here: it
 * would grow without bound whenever inference is slower than capture — which is always — and every
 * frame it held would be more stale than the one behind it. Dropping means the model is always
 * working on the newest frame available when it became free.
 *
 * ## Why [consume] copies
 *
 * [depthFp16] has a fixed identity because `StreamingTexture` captures its source buffer once, at
 * construction. The worker therefore cannot write into it directly — the renderer could be reading it
 * mid-write and would show a torn map with a visible seam. Instead the worker publishes into an
 * internal buffer, and [consume] copies that into [depthFp16] under the lock, on the calling thread.
 * The copy is ~537KB at 518x518, tens of microseconds, and it is the only work the render thread does.
 *
 * @param width model input width, a multiple of [DepthAnything.PATCH]
 * @param height model input height, likewise
 * @param model which weights to use
 * @param provider execution provider to request
 * @param invert see [DepthAnything.invert]
 * @param adaptation see [DepthAnything.adaptation]
 */
class DepthAnythingWorker(
    val width: Int = 266,
    val height: Int = 266,
    model: OnnxModel = DepthAnything.FP32,
    provider: OnnxRuntime.OnnxProvider = OnnxRuntime.OnnxProvider.AUTO,
    invert: Boolean = false,
    adaptation: Float = 0.1f
) : AutoCloseable {

    private val estimator = DepthAnything(width, height, model, provider, adaptation, invert)

    /** The provider actually in force. */
    val activeProvider: String get() = estimator.activeProvider

    /**
     * The most recently consumed depth map, fp16 in `[0,1]`, `width*height*2` bytes.
     *
     * Only mutated by [consume], so it is stable for the whole of a frame's rendering. Zero-filled
     * until the first [consume] returns true.
     */
    val depthFp16: ByteBuffer =
        ByteBuffer.allocateDirect(width * height * 2).order(ByteOrder.LITTLE_ENDIAN)

    // Deliberately java.lang.Object rather than the Any() the compiler suggests: this lock is waited
    // on and notified, and wait/notifyAll are Java monitor methods that kotlin.Any does not expose.
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private val lock = Object()

    // Input staging. Sized on first submit; the webcam does not change size mid-stream.
    private var pendingInput: ByteBuffer? = null
    private var workInput: ByteBuffer? = null
    private var pendingW = 0
    private var pendingH = 0
    private var inputReady = false

    private val publishedOutput: ByteBuffer =
        ByteBuffer.allocateDirect(width * height * 2).order(ByteOrder.LITTLE_ENDIAN)
    private var outputReady = false

    @Volatile
    private var running = true

    /** Frames handed to [submit], including ones later dropped. */
    @Volatile
    var framesSubmitted: Long = 0L
        private set

    /** Frames actually inferred. The difference from [framesSubmitted] is the drop count. */
    @Volatile
    var framesInferred: Long = 0L
        private set

    /** Frames overwritten in staging because inference was still busy when a newer one arrived. */
    @Volatile
    var framesDropped: Long = 0L
        private set

    /** Wall time of the most recent inference, in milliseconds. */
    @Volatile
    var lastInferenceMs: Long = 0L
        private set

    /** Normalisation window of the most recent inference, or null before the first one completes. */
    @Volatile
    var lastStats: DepthStats? = null
        private set

    /** Whatever the worker thread last failed with, if anything. */
    @Volatile
    var lastError: Throwable? = null
        private set

    private val thread = Thread({ loop() }, "DepthAnythingWorker-${width}x$height").apply {
        isDaemon = true
        // Below the render and audio threads on purpose: a late depth map is a cosmetic problem, a
        // late frame is not.
        priority = Thread.NORM_PRIORITY - 1
        start()
    }

    /**
     * Offer a frame. Returns immediately.
     *
     * @param rgba tightly packed RGBA8, `srcW*srcH*4` bytes readable from index 0. Copied before this
     *   returns, so the caller may overwrite it — which is what a webcam capture thread will do.
     */
    fun submit(rgba: ByteBuffer, srcW: Int, srcH: Int) {
        require(srcW > 0 && srcH > 0) { "source must be non-empty, got ${srcW}x$srcH" }
        val needed = srcW * srcH * 4
        require(rgba.capacity() >= needed) {
            "RGBA buffer too small: need $needed bytes for ${srcW}x$srcH, have ${rgba.capacity()}"
        }

        synchronized(lock) {
            if (!running) return
            var target = pendingInput
            if (target == null || target.capacity() < needed) {
                target = ByteBuffer.allocateDirect(needed).order(ByteOrder.nativeOrder())
                pendingInput = target
            }
            // Absolute-indexed copy: leaves the caller's position/limit untouched, which matters
            // because the webcam's buffer is shared and its position is not ours to move.
            for (i in 0 until needed) target.put(i, rgba.get(i))
            pendingW = srcW
            pendingH = srcH
            if (inputReady) framesDropped++
            inputReady = true
            framesSubmitted++
            lock.notifyAll()
        }
    }

    /**
     * Publish a finished depth map into [depthFp16], if one is waiting.
     *
     * Call once per rendered frame, and upload only when it returns true — re-uploading an unchanged
     * texture is pure cost.
     *
     * @return true if [depthFp16] changed
     */
    fun consume(): Boolean = synchronized(lock) {
        if (!outputReady) return false
        depthFp16.clear()
        publishedOutput.clear()
        depthFp16.put(publishedOutput)
        depthFp16.flip()
        publishedOutput.clear()
        outputReady = false
        true
    }

    /** Stop the worker thread and release the model's buffers. The cached session is left alone. */
    override fun close() {
        synchronized(lock) {
            running = false
            lock.notifyAll()
        }
        runCatching { thread.join(2_000) }
    }

    private fun loop() {
        while (true) {
            var input: ByteBuffer? = null
            var w = 0
            var h = 0
            synchronized(lock) {
                while (running && !inputReady) lock.wait()
                if (!running) return
                val pending = pendingInput
                if (pending != null) {
                    val needed = pendingW * pendingH * 4
                    var work = workInput
                    if (work == null || work.capacity() < needed) {
                        work = ByteBuffer.allocateDirect(needed).order(ByteOrder.nativeOrder())
                        workInput = work
                    }
                    // Copy out of the staging buffer so submit() can keep accepting frames while
                    // this one is being inferred.
                    for (i in 0 until needed) work.put(i, pending.get(i))
                    w = pendingW
                    h = pendingH
                    input = work
                }
                inputReady = false
            }
            val frame = input ?: continue

            try {
                val stats = estimator.infer(frame, w, h)
                synchronized(lock) {
                    publishedOutput.clear()
                    estimator.depthFp16.clear()
                    publishedOutput.put(estimator.depthFp16)
                    publishedOutput.flip()
                    estimator.depthFp16.clear()
                    outputReady = true
                }
                lastStats = stats
                lastInferenceMs = estimator.lastInferenceMs
                framesInferred++
                lastError = null
            } catch (t: Throwable) {
                lastError = t
                lprintln("warning,onnx", "depth inference failed: ${t.message ?: t.javaClass.name}")
                // Do not spin on a persistent failure -- a broken model would otherwise burn a core.
                Thread.sleep(250)
            }
        }
    }
}
