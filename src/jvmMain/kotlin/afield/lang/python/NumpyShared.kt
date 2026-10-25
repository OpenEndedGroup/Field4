/**
 * Shared memory between Python boxes and Kotlin boxes: images and line sets that numpy writes in
 * place and Kotlin reads without copying through a serializer.
 *
 * [NumpyShared] hands out named [SharedImage] and [SharedLines] buffers; the `fieldcv` package in
 * `python/` is the Python side. `NumpySharedTest` covers the publish/consume handoff.
 */

package afield.lang.python

import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.device.CommandBuffer
import afield.document.context.fromContext
import afield.graphics.texture.StreamingTexture
import afield.geometry.path.FLine
import afield.ui.visualization.lines
import jep.DirectNDArray
import org.lwjgl.vulkan.VK10.VK_FORMAT_B8G8R8A8_SRGB
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * Named buffers that a Python box writes with numpy and a Kotlin box reads, without serializing.
 *
 * Python asks for a buffer by name and gets back a numpy array whose memory is a JVM direct buffer;
 * OpenCV writes into it in place (`cv2.cvtColor(frame, cv2.COLOR_BGR2BGRA, dst=image.array)`).
 * Kotlin asks for the same name and gets the object that owns that memory. Neither side has to run
 * first: whichever asks first with a size creates the buffer, and the other attaches to it.
 *
 * ```kotlin
 * // a graphics box under the renderer
 * prop<LoadedTexture>(camera) = NumpyShared.image("camera").texture()
 * ```
 * ```python
 * # a Python box
 * from fieldcv import field
 * cam = field.image("camera", 720, 1280)
 * ```
 *
 * ## Publish and consume
 *
 * The writer is usually a Python worker thread and the reader the render thread, so each buffer is
 * three copies of the data, as in [afield.ai.inference.onnx.DepthAnythingWorker]: Python writes the *back* copy
 * whenever it likes, [SharedImage.publish] copies back to *published* under a lock, and
 * [SharedImage.consume], on the reader's thread, copies published to *front* under the same lock. The
 * front copy only changes inside `consume`, so it is stable for the rest of the reader's frame and a
 * texture upload never shows half of one frame and half of the next. The copies are a few
 * milliseconds at 1080p; dropping the back copy would save one and bring the tearing back.
 *
 * A writer that publishes faster than the reader consumes loses frames, which is what a live view
 * wants. Nothing queues.
 */
object NumpyShared {
    private val images = ConcurrentHashMap<String, SharedImage>()
    private val lines = ConcurrentHashMap<String, SharedLines>()

    /**
     * The shared image called [name], creating it at [height] x [width] if it does not exist or has a
     * different size. A size of `0` attaches to whatever exists and fails if nothing does.
     *
     * Replacing an image with one of another size leaves earlier holders with the old one, which
     * stops updating: re-run the box that made a texture from it.
     */
    @JvmStatic
    @JvmOverloads
    fun image(name: String, height: Int = 0, width: Int = 0): SharedImage {
        if (height <= 0 || width <= 0) {
            return images[name] ?: throw IllegalStateException(
                "no shared image called '$name' yet; give its size, or run the box that creates it first (known: ${images.keys.sorted()})"
            )
        }
        return images.compute(name) { _, old ->
            if (old != null && old.height == height && old.width == width) old else SharedImage(name, height, width)
        }!!
    }

    /** The shared polylines called [name], created empty on first use. */
    @JvmStatic
    fun lines(name: String): SharedLines = lines.computeIfAbsent(name) { SharedLines(it) }

    /**
     * Draw [lines] into [box]'s output on [channel] as SVG, scaled from normalized coordinates to
     * [width] x [height] pixels. This is a preview in the box itself; the renderer path is
     * [SharedLines.lines].
     */
    @JvmStatic
    @JvmOverloads
    fun draw(box: afield.document.graph.Box, lines: SharedLines, channel: String = "lines", width: Double = 480.0, height: Double = 270.0) {
        box.lines(lines.lines(width, height), channel = channel, w = width, h = height)
    }

    /** Every shared buffer by name, for the status panel and for debugging. */
    @JvmStatic
    fun names(): List<String> = (images.keys.map { "image:$it" } + lines.keys.map { "lines:$it" }).sorted()
}

private fun direct(bytes: Int): ByteBuffer = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())

/**
 * A `height x width` BGRA8 image: OpenCV's channel order with an alpha byte, so a BGR frame becomes
 * one `cvtColor` into [array] and the GPU reads it as `B8G8R8A8_SRGB` with no swizzle.
 */
class SharedImage internal constructor(val name: String, val height: Int, val width: Int) {
    val bytes: Int = height * width * 4

    private val back: ByteBuffer = direct(bytes)
    private val published: ByteBuffer = direct(bytes)

    /** The copy a reader sees: only changes inside [consume]. */
    val front: ByteBuffer = direct(bytes)

    private val lock = Any()

    @Volatile
    var version: Long = 0
        private set
    private var consumed: Long = 0

    /** The back copy as a writable `(height, width, 4)` `uint8` numpy array (JEP converts it on return). */
    fun array(): DirectNDArray<ByteBuffer> = DirectNDArray(back, true, height, width, 4)

    /** Make what has been written to [array] visible to the next [consume]. Safe from any thread. */
    fun publish() {
        synchronized(lock) {
            published.clear()
            published.put(back.duplicate().clear())
            published.clear()
            version++
        }
    }

    /** Bring [front] up to date; true if it changed since the last call. Call it on the reading thread. */
    fun consume(): Boolean = synchronized(lock) {
        if (version == consumed) return false
        front.clear()
        front.put(published.duplicate().clear())
        front.clear()
        consumed = version
        true
    }

    /**
     * A texture showing this image, uploaded on frames where something new was published.
     *
     * The upload is a transfer, which cannot be recorded inside a render pass, so the texture puts
     * itself on the renderer's compute list (owned by the calling box, and replaced when it re-runs)
     * rather than relying on whatever samples it.
     */
    @JvmOverloads
    fun texture(renderer: Renderer = fromContext()): StreamingTexture {
        val t = SharedImageTexture(this, renderer)
        consume()
        t.uploadNow()
        renderer.computeList += "numpy_$name" to (t as afield.graphics.vulkan.Target)
        return t
    }

    override fun toString() = "SharedImage($name, ${width}x$height, version $version)"
}

private class SharedImageTexture(val shared: SharedImage, renderer: Renderer) :
    StreamingTexture(shared.front, shared.width, shared.height, renderer, VK_FORMAT_B8G8R8A8_SRGB, "numpy_${shared.name}") {
    override fun render(command: CommandBuffer, index: Int) {
        if (shared.consume()) recordUpload(command)
    }
}

/**
 * Polylines in normalized image coordinates — tracks, contours, skeleton edges — written by
 * Python as one flat `(points, 2)` `float32` array plus a count per line.
 *
 * The Python side (`fieldcv.field.SharedLines.publish`) packs a list of `(n, 2)` arrays into
 * [points] and [counts] and calls [publish] with the totals; this copies the used prefix into a
 * snapshot under the lock, so the back arrays can be refilled straight away. [lines] turns the
 * newest snapshot into [FLine]s, rebuilt only when a new one has arrived.
 */
class SharedLines internal constructor(val name: String) {
    private val lock = Any()

    private var backPoints: FloatBuffer = direct(4 * 2 * 1024).asFloatBuffer()
    private var backCounts: IntBuffer = direct(4 * 256).asIntBuffer()

    private var snapshotPoints = FloatArray(0)
    private var snapshotCounts = IntArray(0)

    @Volatile
    var version: Long = 0
        private set
    private var builtVersion = -1L
    private var built: List<FLine> = emptyList()
    private var builtScale = Pair(Double.NaN, Double.NaN)

    val pointCapacity: Int get() = backPoints.capacity() / 2
    val lineCapacity: Int get() = backCounts.capacity()

    /** Grow the back arrays to hold at least [points] points in [lines] lines. Re-fetch the arrays after. */
    fun reserve(points: Int, lines: Int) {
        synchronized(lock) {
            if (points > pointCapacity) backPoints = direct(4 * 2 * Integer.highestOneBit(points) * 2).asFloatBuffer()
            if (lines > lineCapacity) backCounts = direct(4 * Integer.highestOneBit(lines) * 2).asIntBuffer()
        }
    }

    /** The back point array, `(pointCapacity, 2)` `float32`. */
    fun points(): DirectNDArray<FloatBuffer> = DirectNDArray(backPoints, pointCapacity, 2)

    /** The back count array, `(lineCapacity,)` `int32`. */
    fun counts(): DirectNDArray<IntBuffer> = DirectNDArray(backCounts, lineCapacity)

    /** Publish the first [lineCount] lines, [pointCount] points in all. */
    fun publish(pointCount: Int, lineCount: Int) {
        synchronized(lock) {
            require(pointCount in 0..pointCapacity && lineCount in 0..lineCapacity) {
                "publish($pointCount, $lineCount) exceeds capacity ($pointCapacity points, $lineCapacity lines)"
            }
            snapshotPoints = FloatArray(pointCount * 2).also { backPoints.duplicate().clear().get(it) }
            snapshotCounts = IntArray(lineCount).also { backCounts.duplicate().clear().get(it) }
            version++
        }
    }

    /**
     * The newest polylines as [FLine]s, scaled from normalized coordinates by [width] and [height].
     * The list is shared until something new is published, so don't mutate it.
     */
    @JvmOverloads
    fun lines(width: Double = 1.0, height: Double = 1.0): List<FLine> = synchronized(lock) {
        if (builtVersion == version && builtScale == Pair(width, height)) return built
        val out = ArrayList<FLine>(snapshotCounts.size)
        var p = 0
        for (n in snapshotCounts) {
            val f = FLine()
            for (i in 0 until n) {
                val x = snapshotPoints[2 * (p + i)] * width
                val y = snapshotPoints[2 * (p + i) + 1] * height
                if (i == 0) f.moveTo(x, y) else f.lineTo(x, y)
            }
            p += n
            out += f
        }
        built = out
        builtVersion = version
        builtScale = Pair(width, height)
        out
    }

    override fun toString() = "SharedLines($name, ${snapshotCounts.size} lines, version $version)"
}
