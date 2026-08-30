package afield.four.texture.video

import afield.four.Renderer
import afield.four.buffers.VulkanBuffer
import afield.four.device.CommandBuffer
import afield.four.modules.fromContext
import afield.four.texture.HasImageView
import afield.four.texture.HasImageViewArray
import afield.four.texture.Image
import afield.four.texture.ImageView
import afield.four.texture.vkFormatBytesPerPixel
import afield.logger.lprint
import afield.logger.lprintln
import afield.server.Browsable
import afield.server.HasBrowsable
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import org.lwjgl.vulkan.*
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import kotlin.concurrent.thread

/**
 * VideoTexture manages video playback with P010 format support.
 * Maintains configurable texture slot arrays for luma and chroma planes in semi-planar format.
 * Supports load, play, scrub, and unload operations while reusing Vulkan resources when dimensions match.
 *
 * @param renderer The Vulkan renderer
 * @param clock Optional external clock for synchronization (e.g., AudioClock for A/V sync).
 *              If null, uses internal SystemClock which may drift over long durations.
 * @param backgroundDecode Decode and RGB conversion on a worker, leaving only the Vulkan upload in
 *              the render callback. Useful for large data-bearing atlases; false preserves the
 *              original synchronous behavior.
 */
class VideoTexture(
    private val renderer: Renderer = fromContext(),
    private val clock: PlaybackClock? = null,
    private val textureSlots: Int = 2,
    /** P010 for display video; RGBA8 for movies whose RGB channels carry numeric data. */
    val pixelFormat: VideoDecodeFormat = VideoDecodeFormat.P010,
    val backgroundDecode: Boolean = false
) : HasImageViewArray, afield.four.Target, HasBrowsable {

    /**
     * Optional sampled-image indirection. A module may keep this VideoTexture as its stable descriptor
     * provider while a frame-set presenter atomically changes which already-loaded VideoTexture its
     * shaders see. Decoding and uploading still happen on the source texture itself.
     */
    @Volatile
    private var sampledSource: VideoTexture? = null

    fun sampleFrom(source: VideoTexture?) {
        val next = source?.takeUnless { it === this }
        if (next != null) {
            require(next.pixelFormat == pixelFormat) {
                "cannot sample ${next.pixelFormat} through a $pixelFormat VideoTexture"
            }
            require(next.width == width && next.height == height) {
                "sampled video dimensions ${next.width}x${next.height} do not match ${width}x${height}"
            }
        }
        sampledSource = next
    }

    val sampledFrom: VideoTexture get() = sampledSource ?: this

    // Inspector support: expose state + version tied to timestamp
    override fun browsable(): Browsable? = object : Browsable {
        override fun heading(): String = "VideoTexture"
        override fun dir(): Map<String, Any?>? = null
        override fun html(): String {
            fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            val video = esc(currentVideoPath ?: "(none)")
            val ts = "%.2fs".format(currentTimestamp)
            val size = if (videoWidth > 0) "${videoWidth}x${videoHeight}" else "(unloaded)"
            val slotVal = currentSlot.get().toString()
            val state = if (isPlaying) "playing" else "paused"
            val pending = if (pendingFrameRefresh) " · pending" else ""
            return """
                <div style="font-family:inherit;color:#ddd;padding:6px 8px;">
                  <div style="display:grid;grid-template-columns:120px 1fr;row-gap:4px;column-gap:10px;align-items:baseline;">
                    <div style="text-align:right;color:#8a8a8a;letter-spacing:0.6px;font-size:12px;">video</div><div style="color:#d0d0d0;white-space:pre-wrap;">$video</div>
                    <div style="text-align:right;color:#8a8a8a;letter-spacing:0.6px;font-size:12px;">state</div><div style="color:#d0d0d0;">$state$pending</div>
                    <div style="text-align:right;color:#8a8a8a;letter-spacing:0.6px;font-size:12px;">timestamp</div><div style="color:#d0d0d0;">$ts</div>
                    <div style="text-align:right;color:#8a8a8a;letter-spacing:0.6px;font-size:12px;">size</div><div style="color:#d0d0d0;">$size</div>
                    <div style="text-align:right;color:#8a8a8a;letter-spacing:0.6px;font-size:12px;">slot</div><div style="color:#d0d0d0;">$slotVal</div>
                  </div>
                </div>
            """.trimIndent()
        }
        override fun version(): Long =
            (currentTimestamp * 1000).toLong() +
                (if (isPlaying) 1 else 0) +
                (if (pendingFrameRefresh) 2 else 0) +
                (currentSlot.get().toLong() shl 2)
    }

    // Current video state
    private var decoder: VideoDecoder? = null
    private var currentVideoPath: String? = null
    private var isPlaying = false

    // Internal clock used when no external clock is provided
    private val internalClock = SystemClock()

    // Active clock (external if provided, internal otherwise)
    private val activeClock: PlaybackClock
        get() = clock ?: internalClock

    private var currentTimestamp = 0.0
    private var lastFrameTimestamp = 0.0
    @Volatile private var uploadedFrame = false

    // Video dimensions
    private var videoWidth = 0
    private var videoHeight = 0

    // Texture slot arrays for luma and chroma
    private var lumaTextures = arrayOfNulls<VideoPlaneTexture>(textureSlots)
    private var chromaTextures = arrayOfNulls<VideoPlaneTexture>(textureSlots)
    private var rgbaTextures = arrayOfNulls<VideoPlaneTexture>(textureSlots)

    // Current slot index - indicates most recent frame
    private val currentSlot = AtomicInteger(0)

    // When true, allow a one-off update while paused to reflect a scrubbed position
    private var pendingFrameRefresh = false

    private data class DecodeRequest(val generation: Long, val timestamp: Double, val exactFrame: Long? = null)
    private data class DecodedFrame(val serial: Long, val frame: VideoFrame)

    private val decodeGeneration = AtomicLong()
    private val decodeSerial = AtomicLong()
    private val requestedDecode = AtomicReference<DecodeRequest?>()
    private val pendingDecode = AtomicReference<DecodeRequest?>()
    private val decodedFrame = AtomicReference<DecodedFrame?>()
    private val decodedFrameLock = Any()
    private var decodeWorker: Thread? = null
    @Volatile private var decodeWorkerRunning = false
    @Volatile private var decodeFailure: Throwable? = null
    @Volatile var backgroundDecodeMicros: Long = 0
        private set
    private var lastRequestedFrame = Long.MIN_VALUE
    private var seenDecodedSerial = -1L

    // Format constants for P010
    private val lumaFormat = VK10.VK_FORMAT_R16_UNORM  // 16-bit luma
    private val chromaFormat = VK10.VK_FORMAT_R16G16_UNORM  // 16-bit interleaved UV

    init {
        require(textureSlots > 0) { "textureSlots must be at least 1" }
    }

    /**
     * Load a video file. If dimensions match the previously loaded video, reuses Vulkan resources.
     */
    fun load(videoPath: String): Boolean {
        // If loading the same video, just seek to start
        if (videoPath == currentVideoPath && decoder != null) {
            seek(0.0)
            return true
        }

        // Create new decoder
        val newDecoder = VideoDecoder(videoPath, pixelFormat)
        if (!newDecoder.open()) {
            lprintln("error,video", "Failed to open video: $videoPath")
            return false
        }

        val newWidth = newDecoder.width
        val newHeight = newDecoder.height

        // Check if we can reuse existing textures (same dimensions)
        val texturesAllocated = when (pixelFormat) {
            VideoDecodeFormat.P010 -> lumaTextures.all { it != null } && chromaTextures.all { it != null }
            VideoDecodeFormat.RGBA8 -> rgbaTextures.all { it != null }
        }
        val canReuseTextures = (newWidth == videoWidth && newHeight == videoHeight && texturesAllocated)

        // Close old decoder if exists. A background decoder owns FFmpeg exclusively while running.
        stopDecodeWorker()
        decoder?.close()
        decoder = newDecoder
        currentVideoPath = videoPath

        if (canReuseTextures) {
            lprintln("info,video", "Reusing existing textures for video: $videoPath (${newWidth}x${newHeight})")
        } else {
            lprintln("info,video", "Creating new textures for video: $videoPath (${newWidth}x${newHeight})")

            // Clean up old textures if they exist
            cleanupTextures()

            // Update dimensions
            videoWidth = newWidth
            videoHeight = newHeight

            // Create the upload textures for the selected decode contract.
            for (i in 0 until textureSlots) {
                when (pixelFormat) {
                    VideoDecodeFormat.P010 -> {
                        lumaTextures[i] = VideoPlaneTexture(
                            renderer = renderer,
                            width = videoWidth,
                            height = videoHeight,
                            format = lumaFormat,
                            name = "video_luma_$i"
                        )
                        chromaTextures[i] = VideoPlaneTexture(
                            renderer = renderer,
                            width = videoWidth / 2,
                            height = videoHeight / 2,
                            format = chromaFormat,
                            name = "video_chroma_$i"
                        )
                    }
                    VideoDecodeFormat.RGBA8 -> rgbaTextures[i] = VideoPlaneTexture(
                        renderer = renderer,
                        width = videoWidth,
                        height = videoHeight,
                        format = VK10.VK_FORMAT_R8G8B8A8_UNORM,
                        name = "video_rgba_$i"
                    )
                }
            }
        }

        currentTimestamp = 0.0
        lastFrameTimestamp = 0.0
        uploadedFrame = false
        uploadedFrameIndex = -1
        currentSlot.set(0)
        pendingFrameRefresh = true
        lastRequestedFrame = Long.MIN_VALUE
        seenDecodedSerial = -1L
        clearDecodedFrame()

        // Reset internal clock when loading a new video
        if (clock == null) {
            internalClock.reset()
        }

        if (backgroundDecode) startDecodeWorker(newDecoder)

        return true
    }

    /**
     * Start or resume playback.
     */
    fun play() {
        if (decoder != null) {
            isPlaying = true

            // If using internal clock, start it
            if (clock == null) {
                internalClock.play()
            }
        }
    }

    /**
     * Pause playback.
     */
    fun pause() {
        isPlaying = false

        // If using internal clock, pause it
        if (clock == null) {
            internalClock.pause()
        }
    }

    /**
     * Seek to a specific timestamp in seconds.
     */
    fun seek(timestampSeconds: Double): Boolean {
        lprint("info,video", "Seeking to $timestampSeconds")
        decoder?.let { dec ->
            if (backgroundDecode) {
                currentTimestamp = timestampSeconds
                lastFrameTimestamp = timestampSeconds
                lastRequestedFrame = Long.MIN_VALUE
                pendingFrameRefresh = true
                if (clock == null) internalClock.seek(timestampSeconds)
                requestBackgroundDecode(timestampSeconds)
                return true
            }
            if (dec.seek(timestampSeconds)) {
                currentTimestamp = timestampSeconds
                lastFrameTimestamp = timestampSeconds

                // If using internal clock, seek it
                if (clock == null) {
                    internalClock.seek(timestampSeconds)
                }

                if (!isPlaying) {
                    pendingFrameRefresh = true
                }
                return true
            }
        }
        return false
    }

    /**
     * Update the video texture. Call this every frame to decode and upload new frames when playing.
     * Returns true if a new frame was uploaded.
     *
     * When using an external clock (e.g., AudioClock), this method will sync video frames to the clock's time.
     * When using internal clock, it advances based on wall-clock time (may drift over long durations).
     */
    fun update(commandBuffer: CommandBuffer): Boolean {
        val activeDecoder = decoder ?: return false
        val allowWhilePaused = pendingFrameRefresh
        val shouldUpdate = isPlaying || allowWhilePaused

        if (!shouldUpdate) {
            return false
        }

        // Get target timestamp from the active clock
        val targetTimestamp = activeClock.getCurrentTime()

        if (backgroundDecode) {
            return updateFromBackground(commandBuffer, activeDecoder, targetTimestamp)
        }

        // Check if we need to decode a new frame
        // We decode if:
        // 1. We're significantly behind the clock (more than one frame)
        // 2. We're paused and need a refresh
        val frameDuration = if (activeDecoder.fps > 0) 1.0 / activeDecoder.fps else 0.033
        val delta = targetTimestamp - lastFrameTimestamp
        // An external timeline can scrub or loop. Decoding forward from the old cursor cannot reach a
        // time behind it, and walking hundreds of frames after a large jump is the wrong latency.
        val discontinuity = delta < -frameDuration * 0.5 || delta > frameDuration * 12.0
        if (discontinuity) {
            if (!activeDecoder.seek(targetTimestamp.coerceAtLeast(0.0))) return false
            lastFrameTimestamp = targetTimestamp - frameDuration
        }
        val needsNewFrame = discontinuity || (targetTimestamp - lastFrameTimestamp) >= frameDuration || allowWhilePaused

        if (!needsNewFrame) {
            return false
        }

        // Decode frames until we reach or pass the target timestamp
        var decodedFrame: VideoFrame? = null
        var attempts = 0
        val maxAttempts = 10  // Prevent infinite loops

        while (attempts < maxAttempts) {
            val frame = activeDecoder.decodeNextFrame() ?: break

            decodedFrame?.close()
            decodedFrame = frame
            attempts++

            // If this frame is at or past the target time, use it
            if (frame.timestamp >= targetTimestamp) {
                break
            }

            // If we're more than 2 frames ahead, we're seeking or lagging - use this frame
            if (targetTimestamp - frame.timestamp > frameDuration * 2) {
                // Skip frames when catching up
                continue
            }

            // Frame is close enough to target, use it
            break
        }

        lprint("info, video", "decoded frame in ${attempts} attempts")

        val frame = decodedFrame ?: return false
        try {
            // Check if we're past the video duration
            if (frame.timestamp >= activeDecoder.duration) {
                // Loop back to start
                activeDecoder.seek(0.0)
                lastFrameTimestamp = 0.0

                // Reset internal clock if we're using it
                if (clock == null) {
                    internalClock.seek(0.0)
                }

                return false
            }

            // Switch to next slot
            val nextSlot = (currentSlot.get() + 1) % textureSlots

            when (pixelFormat) {
                VideoDecodeFormat.P010 -> {
                    val lumaTexture = lumaTextures[nextSlot] ?: return false
                    val chromaTexture = chromaTextures[nextSlot] ?: return false
                    lumaTexture.upload(commandBuffer, frame.plane0)
                    chromaTexture.upload(commandBuffer, frame.plane1)
                }
                VideoDecodeFormat.RGBA8 -> {
                    val rgbaTexture = rgbaTextures[nextSlot] ?: return false
                    rgbaTexture.upload(commandBuffer, frame.plane0)
                }
            }

            // Update current slot
            currentSlot.set(nextSlot)

            // Track the timestamp of this frame
            lastFrameTimestamp = frame.timestamp
            currentTimestamp = frame.timestamp
            uploadedFrame = true
            uploadedFrameIndex = if (fps > 0) VideoFrameIndex.decoded(frame.timestamp, fps) else -1

            // If we were paused and refreshing after a scrub, stop the temporary refresh
            if (!isPlaying && pendingFrameRefresh) {
                pendingFrameRefresh = false
            }

            return true
        } finally {
            frame.close()
        }
    }

    /**
     * Get the current luma texture.
     */
    fun getLumaTexture(): HasImageView? {
        return lumaTextures[currentSlot.get()]
    }

    /**
     * Get the current chroma texture.
     */
    fun getChromaTexture(): HasImageView? {
        return chromaTextures[currentSlot.get()]
    }

    /**
     * Get both current textures as an array (luma at index 0, chroma at index 1).
     */
    override val imageViewArray: List<HasImageView>
        get() {
            sampledSource?.let { return it.imageViewArray }
            val slot = currentSlot.get()
            return when (pixelFormat) {
                VideoDecodeFormat.P010 -> listOfNotNull(lumaTextures[slot], chromaTextures[slot])
                VideoDecodeFormat.RGBA8 -> listOfNotNull(rgbaTextures[slot])
            }
        }

    /**
     * Get video properties.
     */
    val width: Int get() = videoWidth
    val height: Int get() = videoHeight
    val duration: Double get() = decoder?.duration ?: 0.0
    val fps: Double get() = decoder?.fps ?: 0.0
    val timestamp: Double get() = currentTimestamp
    val playing: Boolean get() = isPlaying
    /** True after at least one decoded frame has reached its Vulkan image. */
    val hasFrame: Boolean get() = uploadedFrame

    /** Source-frame identity of the image selected for the next draw, not the requested clock. */
    var uploadedFrameIndex: Long = -1
        private set

    /** Prepare a CFR source frame off-thread without uploading it. Do not also schedule [update]. */
    fun prepareFrame(frameIndex: Long) {
        require(backgroundDecode) { "prepareFrame requires backgroundDecode=true" }
        require(frameIndex >= 0 && fps > 0) { "load a video and request a non-negative frame" }
        decodeFailure?.let { throw IllegalStateException("background video decode failed", it) }
        if (requestedDecode.get()?.exactFrame == frameIndex) return
        requestBackgroundDecode(frameIndex / fps, frameIndex)
    }

    fun isFrameReady(frameIndex: Long): Boolean {
        decodeFailure?.let { throw IllegalStateException("background video decode failed", it) }
        return synchronized(decodedFrameLock) {
            val ready = decodedFrame.get() ?: return@synchronized false
            VideoFrameIndex.decoded(ready.frame.timestamp, fps) == frameIndex
        }
    }

    /** Record this exact image's upload before the same draw as its matching geometry. Never waits. */
    fun uploadPreparedFrame(commandBuffer: CommandBuffer, frameIndex: Long): Boolean {
        decodeFailure?.let { throw IllegalStateException("background video decode failed", it) }
        return synchronized(decodedFrameLock) {
            val ready = decodedFrame.get() ?: return@synchronized false
            if (VideoFrameIndex.decoded(ready.frame.timestamp, fps) != frameIndex) return@synchronized false
            if (seenDecodedSerial == ready.serial) return@synchronized uploadedFrameIndex == frameIndex
            uploadDecodedFrame(commandBuffer, ready)
        }
    }

    /**
     * Unload the current video and free resources.
     */
    fun unload() {
        pause()
        stopDecodeWorker()
        decoder?.close()
        decoder = null
        currentVideoPath = null
        currentTimestamp = 0.0
        uploadedFrame = false
        uploadedFrameIndex = -1
        // Note: We keep the Vulkan textures allocated for potential reuse
    }

    /**
     * Clean up all resources including Vulkan textures.
     */
    fun cleanup() {
        unload()
        cleanupTextures()
    }

    private fun cleanupTextures() {
        for (i in 0 until textureSlots) {
            lumaTextures[i]?.cleanup()
            lumaTextures[i] = null
            chromaTextures[i]?.cleanup()
            chromaTextures[i] = null
            rgbaTextures[i]?.cleanup()
            rgbaTextures[i] = null
        }
        videoWidth = 0
        videoHeight = 0
    }

    override fun render(command: CommandBuffer, index: Int) {
        update(command)
    }

    private fun updateFromBackground(
        commandBuffer: CommandBuffer,
        activeDecoder: VideoDecoder,
        targetTimestamp: Double
    ): Boolean {
        decodeFailure?.let { throw IllegalStateException("background video decode failed", it) }
        val frameDuration = if (activeDecoder.fps > 0) 1.0 / activeDecoder.fps else 0.033
        val targetFrame = VideoFrameIndex.requested(targetTimestamp, 1.0 / frameDuration)
        if (targetFrame != lastRequestedFrame) {
            lastRequestedFrame = targetFrame
            requestBackgroundDecode(targetFrame * frameDuration)
        }

        return synchronized(decodedFrameLock) {
            val ready = decodedFrame.get() ?: return@synchronized false
            if (ready.serial == seenDecodedSerial) return@synchronized false
            uploadDecodedFrame(commandBuffer, ready)
        }
    }

    private fun uploadDecodedFrame(commandBuffer: CommandBuffer, ready: DecodedFrame): Boolean {
        val frame = ready.frame
        val nextSlot = (currentSlot.get() + 1) % textureSlots
        when (pixelFormat) {
            VideoDecodeFormat.P010 -> {
                val lumaTexture = lumaTextures[nextSlot] ?: return false
                val chromaTexture = chromaTextures[nextSlot] ?: return false
                lumaTexture.upload(commandBuffer, frame.plane0)
                chromaTexture.upload(commandBuffer, frame.plane1)
            }
            VideoDecodeFormat.RGBA8 -> {
                val rgbaTexture = rgbaTextures[nextSlot] ?: return false
                rgbaTexture.upload(commandBuffer, frame.plane0)
            }
        }
        seenDecodedSerial = ready.serial
        currentSlot.set(nextSlot)
        lastFrameTimestamp = frame.timestamp
        currentTimestamp = frame.timestamp
        uploadedFrame = true
        uploadedFrameIndex = if (fps > 0) VideoFrameIndex.decoded(frame.timestamp, fps) else -1
        pendingFrameRefresh = false
        return true
    }

    private fun requestBackgroundDecode(timestamp: Double, exactFrame: Long? = null) {
        val request = DecodeRequest(decodeGeneration.incrementAndGet(), timestamp.coerceAtLeast(0.0), exactFrame)
        requestedDecode.set(request)
        pendingDecode.set(request)
        LockSupport.unpark(decodeWorker)
    }

    private fun startDecodeWorker(activeDecoder: VideoDecoder) {
        check(!decodeWorkerRunning)
        decodeFailure = null
        requestedDecode.set(null)
        pendingDecode.set(null)
        decodeWorkerRunning = true
        decodeWorker = thread(name = "video-decode-${pixelFormat.name.lowercase()}", isDaemon = true) {
            runDecodeWorker(activeDecoder)
        }
    }

    private fun runDecodeWorker(activeDecoder: VideoDecoder) {
        var decoderTimestamp = Double.NaN
        try {
            while (decodeWorkerRunning) {
                val request = pendingDecode.getAndSet(null)
                if (request == null) {
                    LockSupport.parkNanos(2_000_000L)
                    continue
                }
                val frameDuration = if (activeDecoder.fps > 0) 1.0 / activeDecoder.fps else 0.033
                val already = synchronized(decodedFrameLock) { decodedFrame.get() }
                if (request.exactFrame != null && already != null &&
                    VideoFrameIndex.decoded(already.frame.timestamp, activeDecoder.fps) == request.exactFrame) continue
                val delta = request.timestamp - decoderTimestamp
                // Forward playback is deliberately sequential. A long-GOP movie may seek back to
                // frame zero anyway, and repeatedly restarting that decode is both slower and prone
                // to starving. Backward motion (including loop wrap) still seeks immediately.
                if (!decoderTimestamp.isFinite() || delta < -frameDuration * 0.5 ||
                    (request.exactFrame != null && delta <= frameDuration * 0.5)) {
                    check(activeDecoder.seek(request.timestamp)) { "could not seek video to ${request.timestamp}s" }
                    decoderTimestamp = request.timestamp - frameDuration
                }

                var attempts = 0
                var reached = false
                while (decodeWorkerRunning && attempts < 48) {
                    if (request.exactFrame != null && requestedDecode.get()?.generation != request.generation) break
                    val latestTarget = requestedDecode.get()?.timestamp ?: request.timestamp
                    if (decoderTimestamp.isFinite() && latestTarget < decoderTimestamp - frameDuration * 0.5) break
                    val frameStarted = System.nanoTime()
                    val frame = activeDecoder.decodeNextFrame()
                    if (frame == null) {
                        check(request.exactFrame == null) { "video ended before requested source frame ${request.exactFrame}" }
                        break
                    }
                    decoderTimestamp = frame.timestamp
                    attempts++
                    backgroundDecodeMicros = (System.nanoTime() - frameStarted) / 1_000L
                    var published = false
                    try {
                        if (request.exactFrame != null) {
                            val frameIndex = VideoFrameIndex.decoded(frame.timestamp, activeDecoder.fps)
                            check(frameIndex <= request.exactFrame) { "video skipped requested frame ${request.exactFrame}, got $frameIndex" }
                            if (frameIndex == request.exactFrame) {
                                publishDecodedFrame(frame)
                                published = true
                                reached = true
                                break
                            }
                        } else {
                            publishDecodedFrame(frame)
                            published = true
                            if (frame.timestamp + frameDuration * 0.5 >= latestTarget) break
                        }
                    } finally {
                        if (!published) frame.close()
                    }
                }
                // Long-GOP seeks can need more than one bounded decode pass. Keep the exact request
                // alive, but never overwrite a newer seek/loop request.
                if (request.exactFrame != null && !reached && requestedDecode.get()?.generation == request.generation) {
                    pendingDecode.compareAndSet(null, request)
                }
            }
        } catch (t: Throwable) {
            if (decodeWorkerRunning) decodeFailure = t
        } finally {
            decodeWorkerRunning = false
        }
    }

    private fun stopDecodeWorker() {
        decodeWorkerRunning = false
        LockSupport.unpark(decodeWorker)
        decodeWorker?.interrupt()
        decodeWorker?.join(5_000L)
        check(decodeWorker?.isAlive != true) { "video decode worker did not stop" }
        decodeWorker = null
        requestedDecode.set(null)
        pendingDecode.set(null)
        clearDecodedFrame()
    }

    private fun publishDecodedFrame(frame: VideoFrame) {
        synchronized(decodedFrameLock) {
            decodedFrame.getAndSet(DecodedFrame(decodeSerial.incrementAndGet(), frame))?.frame?.close()
        }
    }

    private fun clearDecodedFrame() {
        synchronized(decodedFrameLock) {
            decodedFrame.getAndSet(null)?.frame?.close()
        }
    }
}

/**
 * Internal class representing a single video plane texture (luma or chroma).
 */
private class VideoPlaneTexture(
    private val renderer: Renderer,
    val width: Int,
    val height: Int,
    val format: Int,
    val name: String
) : HasImageView {

    private val image: Image
    override val imageView: ImageView
    private val stagingBuffer: VulkanBuffer
    private var currentLayout = VK10.VK_IMAGE_LAYOUT_UNDEFINED

    init {
        val bytesPerPixel = vkFormatBytesPerPixel(format, "$name plane")
        val imageSize = width * height * bytesPerPixel

        // Create staging buffer for uploads
        stagingBuffer = VulkanBuffer(
            renderer,
            imageSize.toLong(),
            VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
            VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT or VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT
        )

        // Create image
        val imageData = Image.ImageData()
            .width(width)
            .height(height)
            .format(format)
            .usage(VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT or VK10.VK_IMAGE_USAGE_SAMPLED_BIT)
            .mipLevels(1)

        image = Image(renderer.device, imageData)

        // Create image view
        val imageViewData = ImageView.ImageViewData()
            .format(format)
            .aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
            .mipLevels(1)

        imageView = ImageView(renderer.device, image, imageViewData)
    }

    /**
     * Upload data to this texture plane.
     */
    fun upload(cmd: CommandBuffer, data: ByteBuffer) {
        // Create a duplicate to avoid modifying the caller's buffer position
        val dataCopy = data.duplicate()

        // Copy data to staging buffer
        val mappedMemory = stagingBuffer.map()
        val buffer = MemoryUtil.memByteBuffer(mappedMemory, dataCopy.remaining())
        buffer.put(dataCopy)
        stagingBuffer.unmap()

        MemoryStack.stackPush().use { stack ->
            // Transition to transfer destination
            recordImageTransition(stack, cmd, currentLayout, VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)

            // Copy buffer to image
            recordCopyBuffer(stack, cmd)

            // Transition to shader read
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

    private fun recordImageTransition(
        stack: MemoryStack,
        cmd: CommandBuffer,
        oldLayout: Int,
        newLayout: Int
    ) {
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
                // Video can drive vertex projection and compute forces as well as fragment colour.
                srcStage = VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT
                srcAccessMask = VK10.VK_ACCESS_SHADER_READ_BIT
                dstStage = VK10.VK_PIPELINE_STAGE_TRANSFER_BIT
                dstAccessMask = VK10.VK_ACCESS_TRANSFER_WRITE_BIT
            }
            oldLayout == VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL && newLayout == VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL -> {
                srcStage = VK10.VK_PIPELINE_STAGE_TRANSFER_BIT
                srcAccessMask = VK10.VK_ACCESS_TRANSFER_WRITE_BIT
                dstStage = VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT
                dstAccessMask = VK10.VK_ACCESS_SHADER_READ_BIT
            }
            else -> throw RuntimeException("Unsupported layout transition: $oldLayout -> $newLayout")
        }

        barrier.srcAccessMask(srcAccessMask)
        barrier.dstAccessMask(dstAccessMask)

        VK10.vkCmdPipelineBarrier(cmd.vkCommandBuffer, srcStage, dstStage, 0, null, null, barrier)
    }

    fun cleanup() {
        imageView.cleanup()
        image.cleanup()
        stagingBuffer.cleanup()
    }



}
