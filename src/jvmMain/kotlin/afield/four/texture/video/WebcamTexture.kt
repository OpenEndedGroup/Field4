package afield.four.texture.video

import afield.four.Renderer
import afield.four.Target
import afield.four.buffers.VulkanBuffer
import afield.four.device.CommandBuffer
import afield.four.device.Fence
import afield.four.modules.fromContext
import afield.four.texture.HasImageView
import afield.four.texture.Image
import afield.four.texture.ImageView
import afield.logger.lprintln
import afield.server.Browsable
import afield.server.HasBrowsable
import org.bytedeco.javacv.Frame
import org.bytedeco.javacv.OpenCVFrameConverter
import org.bytedeco.javacv.OpenCVFrameGrabber
import org.bytedeco.opencv.global.opencv_core.CV_8UC4
import org.bytedeco.opencv.global.opencv_imgproc.COLOR_BGR2RGBA
import org.bytedeco.opencv.global.opencv_imgproc.COLOR_BGRA2RGBA
import org.bytedeco.opencv.global.opencv_imgproc.COLOR_GRAY2RGBA
import org.bytedeco.opencv.global.opencv_imgproc.cvtColor
import org.bytedeco.opencv.opencv_core.Mat
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import org.lwjgl.vulkan.VK10
import org.lwjgl.vulkan.VkBufferImageCopy
import org.lwjgl.vulkan.VkExtent3D
import org.lwjgl.vulkan.VkImageMemoryBarrier
import org.lwjgl.vulkan.VkImageSubresourceLayers
import org.lwjgl.vulkan.VkImageSubresourceRange
import org.lwjgl.vulkan.VkOffset3D

/**
 * Webcam-backed RGBA texture for Vulkan sampling.
 *
 * The camera is opened on first use, the first frame is uploaded immediately so [imageView] is valid,
 * and later frames are grabbed on a background thread and uploaded from the render thread.
 */
class WebcamTexture(
    private val renderer: Renderer = fromContext(),
    private val deviceIndex: Int = 0,
    private val requestedWidth: Int? = null,
    private val requestedHeight: Int? = null,
    private val requestedFps: Double? = null,
    private val startupTimeoutMs: Long = 5_000L
) : HasImageView, Target, HasBrowsable, AutoCloseable {

    private val stateLock = Any()
    private val converter = OpenCVFrameConverter.ToMat()

    private var grabber: OpenCVFrameGrabber? = null
    private var captureThread: Thread? = null
    private var texture: WebcamRgbaTexture? = null
    private var sharedFrameBuffer: java.nio.ByteBuffer? = null
    private var rgbaScratch: Mat? = null

    private var running = false
    private var frameBytes = 0
    private var cameraWidth = 0
    private var cameraHeight = 0
    private var latestFrameIdInternal = 0L
    private var uploadedFrameId = 0L
    private var capturedFrames = 0L
    private var uploadedFrames = 0L
    private var droppedFrames = 0L
    private var warnedAboutResize = false
    private var lastError: Throwable? = null

    val width: Int
        get() = synchronized(stateLock) { cameraWidth }

    val height: Int
        get() = synchronized(stateLock) { cameraHeight }

    val active: Boolean
        get() = synchronized(stateLock) { running }

    /**
     * Monotonic id of the most recently captured frame, or 0 before the first.
     *
     * Lets a CPU-side consumer skip work it has already done, the same way [update] skips a redundant
     * GPU upload.
     */
    val latestFrameId: Long
        get() = synchronized(stateLock) { latestFrameIdInternal }

    /**
     * Read the newest frame on the CPU, without a GPU round trip.
     *
     * The capture thread already has every frame in host memory as tightly packed RGBA8 on its way to
     * the staging buffer, so anything wanting pixels — a depth or segmentation model, say — should take
     * them from here rather than reading the texture back off the GPU.
     *
     * [action] receives the shared capture buffer, its dimensions and the frame id. It runs while the
     * capture lock is held, so it **must not block**: copy what it needs and return. Anything slower
     * than a memcpy stalls capture and shows up as dropped frames.
     *
     * @return false if no frame has been captured yet, in which case [action] is not called
     */
    fun withLatestFrame(action: (rgba: java.nio.ByteBuffer, width: Int, height: Int, frameId: Long) -> Unit): Boolean =
        synchronized(stateLock) {
            val buffer = sharedFrameBuffer ?: return false
            if (latestFrameIdInternal == 0L) return false
            action(buffer, cameraWidth, cameraHeight, latestFrameIdInternal)
            true
        }

    override val imageView: ImageView
        get() = ensureInitialized().imageView

    override fun render(command: CommandBuffer, index: Int) {
        update(command)
    }

    override fun close() {
        cleanup()
    }

    fun start(): WebcamTexture {
        ensureInitialized()
        return this
    }

    fun stop() {
        val threadToJoin: Thread?
        val grabberToRelease: OpenCVFrameGrabber?
        synchronized(stateLock) {
            running = false
            threadToJoin = captureThread
            captureThread = null
            grabberToRelease = grabber
            grabber = null
        }

        if (threadToJoin != null && threadToJoin !== Thread.currentThread()) {
            threadToJoin.interrupt()
        }

        runCatching { grabberToRelease?.stop() }
        runCatching { grabberToRelease?.release() }

        if (threadToJoin != null && threadToJoin !== Thread.currentThread()) {
            runCatching { threadToJoin.join(1_000L) }
        }
    }

    fun cleanup() {
        stop()
        synchronized(stateLock) {
            texture?.cleanup()
            texture = null

            sharedFrameBuffer?.let { MemoryUtil.memFree(it) }
            sharedFrameBuffer = null

            rgbaScratch?.release()
            rgbaScratch?.close()
            rgbaScratch = null

            frameBytes = 0
            cameraWidth = 0
            cameraHeight = 0
            latestFrameIdInternal = 0L
            uploadedFrameId = 0L
            capturedFrames = 0L
            uploadedFrames = 0L
            droppedFrames = 0L
            warnedAboutResize = false
        }
    }

    /**
     * Uploads the newest captured frame, if any. Returns true when a new frame reached the texture.
     */
    fun update(command: CommandBuffer): Boolean {
        ensureInitialized()

        synchronized(stateLock) {
            val activeTexture = texture ?: return false
            val buffer = sharedFrameBuffer ?: return false
            if (latestFrameIdInternal == uploadedFrameId) return false

            activeTexture.upload(command, buffer, frameBytes)
            uploadedFrameId = latestFrameIdInternal
            uploadedFrames++
            return true
        }
    }

    override fun browsable(): Browsable? = object : Browsable {
        override fun heading(): String = "WebcamTexture"
        override fun dir(): Map<String, Any?>? = null
        override fun html(): String {
            fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            val snapshot = synchronized(stateLock) {
                mapOf(
                    "device" to deviceIndex.toString(),
                    "size" to if (cameraWidth > 0) "${cameraWidth}x${cameraHeight}" else "(uninitialized)",
                    "state" to if (running) "running" else "stopped",
                    "captured" to capturedFrames.toString(),
                    "uploaded" to uploadedFrames.toString(),
                    "dropped" to droppedFrames.toString(),
                    "error" to (lastError?.message ?: "")
                )
            }
            return """
                <div style="font-family:inherit;color:#ddd;padding:6px 8px;">
                  <div style="display:grid;grid-template-columns:120px 1fr;row-gap:4px;column-gap:10px;align-items:baseline;">
                    <div style="text-align:right;color:#8a8a8a;font-size:12px;">device</div><div>${snapshot["device"]}</div>
                    <div style="text-align:right;color:#8a8a8a;font-size:12px;">size</div><div>${snapshot["size"]}</div>
                    <div style="text-align:right;color:#8a8a8a;font-size:12px;">state</div><div>${snapshot["state"]}</div>
                    <div style="text-align:right;color:#8a8a8a;font-size:12px;">captured</div><div>${snapshot["captured"]}</div>
                    <div style="text-align:right;color:#8a8a8a;font-size:12px;">uploaded</div><div>${snapshot["uploaded"]}</div>
                    <div style="text-align:right;color:#8a8a8a;font-size:12px;">dropped</div><div>${snapshot["dropped"]}</div>
                    <div style="text-align:right;color:#8a8a8a;font-size:12px;">error</div><div style="white-space:pre-wrap;">${esc(snapshot["error"] ?: "")}</div>
                  </div>
                </div>
            """.trimIndent()
        }

        override fun version(): Long = synchronized(stateLock) { latestFrameIdInternal + (uploadedFrameId shl 32) }
    }

    private fun ensureInitialized(): WebcamRgbaTexture {
        texture?.let { return it }

        synchronized(stateLock) {
            texture?.let { return it }

            val newGrabber = OpenCVFrameGrabber(deviceIndex).apply {
                requestedWidth?.let { imageWidth = it }
                requestedHeight?.let { imageHeight = it }
                requestedFps?.let { frameRate = it }
            }

            try {
                newGrabber.start()

                val firstFrame = awaitFirstFrame(newGrabber)
                val firstRgba = convertFrameToRgba(firstFrame)
                val width = firstRgba.cols()
                val height = firstRgba.rows()
                val bytes = width * height * 4

                val latestBuffer = MemoryUtil.memAlloc(bytes)
                copyMatToBuffer(firstRgba, latestBuffer, bytes)

                val newTexture = WebcamRgbaTexture(
                    renderer = renderer,
                    width = width,
                    height = height,
                    name = "webcam_$deviceIndex"
                )
                newTexture.uploadNow(latestBuffer, bytes)

                grabber = newGrabber
                texture = newTexture
                sharedFrameBuffer = latestBuffer
                cameraWidth = width
                cameraHeight = height
                frameBytes = bytes
                latestFrameIdInternal = 1L
                uploadedFrameId = 1L
                capturedFrames = 1L
                uploadedFrames = 1L
                droppedFrames = 0L
                warnedAboutResize = false
                lastError = null
                running = true

                startCaptureThreadLocked()
                return newTexture
            } catch (t: Throwable) {
                lastError = t
                runCatching { newGrabber.stop() }
                runCatching { newGrabber.release() }
                throw IllegalStateException("Failed to initialize webcam $deviceIndex", t)
            }
        }
    }

    private fun startCaptureThreadLocked() {
        if (captureThread?.isAlive == true) return

        val thread = Thread({
            captureLoop()
        }, "WebcamTexture-$deviceIndex").apply {
            isDaemon = true
        }
        captureThread = thread
        thread.start()
    }

    private fun captureLoop() {
        while (true) {
            val activeGrabber = synchronized(stateLock) {
                if (!running) return
                grabber
            } ?: return

            try {
                val frame = activeGrabber.grab() ?: continue
                val rgba = convertFrameToRgba(frame)
                val width = rgba.cols()
                val height = rgba.rows()

                synchronized(stateLock) {
                    if (!running) return

                    if (width != cameraWidth || height != cameraHeight) {
                        if (!warnedAboutResize) {
                            warnedAboutResize = true
                            lprintln(
                                "warning,webcam",
                                "Ignoring webcam resize ${width}x${height}; texture remains ${cameraWidth}x${cameraHeight}"
                            )
                        }
                        return@synchronized
                    }

                    val latestBuffer = sharedFrameBuffer ?: return@synchronized
                    copyMatToBuffer(rgba, latestBuffer, frameBytes)
                    if (latestFrameIdInternal != uploadedFrameId) {
                        droppedFrames++
                    }
                    latestFrameIdInternal++
                    capturedFrames++
                }
            } catch (_: InterruptedException) {
                return
            } catch (t: Throwable) {
                lastError = t
                lprintln("error,webcam", "Capture loop failed for webcam $deviceIndex: ${t.message}")
                synchronized(stateLock) {
                    running = false
                }
                return
            }
        }
    }

    private fun awaitFirstFrame(grabber: OpenCVFrameGrabber): Frame {
        val deadline = System.nanoTime() + startupTimeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            val frame = grabber.grab()
            if (frame != null) {
                return frame
            }
            Thread.sleep(10L)
        }
        throw IllegalStateException("Timed out waiting for webcam $deviceIndex to produce a frame")
    }

    private fun convertFrameToRgba(frame: Frame): Mat {
        val source = converter.convert(frame)
            ?: throw IllegalStateException("OpenCVFrameGrabber returned a frame that could not be converted to Mat")

        val width = source.cols()
        val height = source.rows()
        val target = ensureScratch(width, height)

        when (source.channels()) {
            4 -> cvtColor(source, target, COLOR_BGRA2RGBA)
            3 -> cvtColor(source, target, COLOR_BGR2RGBA)
            1 -> cvtColor(source, target, COLOR_GRAY2RGBA)
            else -> throw IllegalStateException("Unsupported webcam channel count: ${source.channels()}")
        }
        return target
    }

    private fun ensureScratch(width: Int, height: Int): Mat {
        val current = rgbaScratch
        if (current != null && current.cols() == width && current.rows() == height) {
            return current
        }

        current?.release()
        current?.close()

        return Mat(height, width, CV_8UC4).also {
            rgbaScratch = it
        }
    }

    private fun copyMatToBuffer(mat: Mat, destination: java.nio.ByteBuffer, bytes: Int) {
        val srcPointer = mat.data()
            ?: throw IllegalStateException("Converted webcam frame had no backing pixel buffer")
        val src = MemoryUtil.memByteBuffer(srcPointer.address(), bytes)
        val srcCopy = src.duplicate()
        srcCopy.position(0)
        srcCopy.limit(bytes)

        destination.clear()
        destination.put(srcCopy)
        destination.flip()
    }
}

private class WebcamRgbaTexture(
    private val renderer: Renderer,
    val width: Int,
    val height: Int,
    name: String
) : HasImageView {

    private val image: Image
    override val imageView: ImageView
    private val stagingBuffer: VulkanBuffer
    private var currentLayout = VK10.VK_IMAGE_LAYOUT_UNDEFINED

    init {
        val imageBytes = width * height * 4L
        stagingBuffer = VulkanBuffer(
            renderer,
            imageBytes,
            VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
            VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT or VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT
        )

        image = Image(
            renderer.device,
            Image.ImageData()
                .width(width)
                .height(height)
                .format(VK10.VK_FORMAT_R8G8B8A8_UNORM)
                .usage(VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT or VK10.VK_IMAGE_USAGE_SAMPLED_BIT)
                .mipLevels(1)
        )

        imageView = ImageView(
            renderer.device,
            image,
            ImageView.ImageViewData()
                .format(VK10.VK_FORMAT_R8G8B8A8_UNORM)
                .aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                .mipLevels(1)
        )
    }

    fun upload(cmd: CommandBuffer, source: java.nio.ByteBuffer, bytes: Int) {
        copyToStaging(source, bytes)
        recordUpload(cmd)
    }

    fun uploadNow(source: java.nio.ByteBuffer, bytes: Int) {
        val cmd = CommandBuffer(renderer.commandPool, true, true)
        cmd.beginRecording()
        upload(cmd, source, bytes)
        cmd.endRecording()

        val fence = Fence(renderer.device, true)
        fence.reset()
        MemoryStack.stackPush().use { stack ->
            renderer.graphQueue.submit(stack.pointers(cmd.vkCommandBuffer), null, null, null, fence)
        }
        fence.fenceWait()
        fence.cleanup()
        cmd.cleanup()
    }

    fun cleanup() {
        stagingBuffer.cleanup()
        imageView.cleanup()
        image.cleanup()
    }

    private fun copyToStaging(source: java.nio.ByteBuffer, bytes: Int) {
        val src = source.duplicate()
        src.position(0)
        src.limit(bytes)

        val mapped = stagingBuffer.map()
        val dst = MemoryUtil.memByteBuffer(mapped, bytes)
        dst.position(0)
        dst.put(src)
        stagingBuffer.unmap(0, bytes.toLong())
    }

    private fun recordUpload(cmd: CommandBuffer) {
        MemoryStack.stackPush().use { stack ->
            recordImageTransition(stack, cmd, currentLayout, VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
            recordCopyBuffer(stack, cmd)
            recordImageTransition(
                stack,
                cmd,
                VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
            )
        }
        currentLayout = VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
    }

    private fun recordCopyBuffer(stack: MemoryStack, cmd: CommandBuffer) {
        val region = VkBufferImageCopy.calloc(1, stack)
            .bufferOffset(0)
            .bufferRowLength(0)
            .bufferImageHeight(0)
            .imageSubresource { it: VkImageSubresourceLayers ->
                it.aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                    .mipLevel(0)
                    .baseArrayLayer(0)
                    .layerCount(1)
            }
            .imageOffset { it: VkOffset3D -> it.x(0).y(0).z(0) }
            .imageExtent { it: VkExtent3D ->
                it.width(width).height(height).depth(1)
            }

        VK10.vkCmdCopyBufferToImage(
            cmd.vkCommandBuffer,
            stagingBuffer.buffer,
            image.vkImage,
            VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
            region
        )
    }

    private fun recordImageTransition(stack: MemoryStack, cmd: CommandBuffer, oldLayout: Int, newLayout: Int) {
        val barrier = VkImageMemoryBarrier.calloc(1, stack)
            .sType(VK10.VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
            .oldLayout(oldLayout)
            .newLayout(newLayout)
            .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
            .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
            .image(image.vkImage)
            .subresourceRange { it: VkImageSubresourceRange ->
                it.aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0)
                    .levelCount(1)
                    .baseArrayLayer(0)
                    .layerCount(1)
            }

        val srcStage: Int
        val srcAccessMask: Int
        val dstAccessMask: Int
        val dstStage: Int

        when {
            oldLayout == VK10.VK_IMAGE_LAYOUT_UNDEFINED && newLayout == VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL -> {
                srcStage = VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT
                srcAccessMask = 0
                dstStage = VK10.VK_PIPELINE_STAGE_TRANSFER_BIT
                dstAccessMask = VK10.VK_ACCESS_TRANSFER_WRITE_BIT
            }

            oldLayout == VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL && newLayout == VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL -> {
                srcStage = VK10.VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT
                srcAccessMask = VK10.VK_ACCESS_SHADER_READ_BIT
                dstStage = VK10.VK_PIPELINE_STAGE_TRANSFER_BIT
                dstAccessMask = VK10.VK_ACCESS_TRANSFER_WRITE_BIT
            }

            oldLayout == VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL && newLayout == VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL -> {
                srcStage = VK10.VK_PIPELINE_STAGE_TRANSFER_BIT
                srcAccessMask = VK10.VK_ACCESS_TRANSFER_WRITE_BIT
                dstStage = VK10.VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT
                dstAccessMask = VK10.VK_ACCESS_SHADER_READ_BIT
            }

            else -> throw IllegalStateException("Unsupported image layout transition: $oldLayout -> $newLayout")
        }

        barrier.srcAccessMask(srcAccessMask)
        barrier.dstAccessMask(dstAccessMask)

        VK10.vkCmdPipelineBarrier(
            cmd.vkCommandBuffer,
            srcStage,
            dstStage,
            0,
            null,
            null,
            barrier
        )
    }
}
