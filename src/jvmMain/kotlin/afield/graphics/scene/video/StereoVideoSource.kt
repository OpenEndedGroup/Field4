package afield.graphics.scene.video

import afield.graphics.texture.video.VideoDecodeFormat
import afield.graphics.texture.video.VideoTexture
import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.Target
import afield.graphics.vulkan.targets.MultiviewStereoTarget
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import org.lwjgl.vulkan.VK10.vkDeviceWaitIdle

/** One playback clock, active decoder and upload stream, optionally retaining a P010 GPU history for many surfaces. */
class StereoVideoSource(
    private val renderer: Renderer,
    private val stereo: MultiviewStereoTarget,
    private val name: String = "stereo-video-source",
    historyFrames: Int = 1,
) : AutoCloseable {
    @Volatile
    var speed = 1.0
        set(value) { require(value.isFinite() && value in .1..8.0); field = value }
    @Volatile
    var materialFramesPerSecond = 0
        set(value) { require(value == 0 || value == 12 || value == 24); field = value }
    @Volatile
    var selectiveDecode = true
    @Volatile
    var skipNonReferenceFrames = false
    /** Number of published pictures retained, including the current one. Changing it refills the delay ring. */
    @Volatile
    var historyFrames = historyFrames
        set(value) { require(value in 1..256); field = value }
    private var history: VideoFrameHistory? = null
    private val cadence = VideoMaterialCadence()
    private val seekRequest = AtomicReference<Double?>()
    private val playRequest = AtomicReference<Boolean?>()
    private var video: VideoTexture? = null
    private var previousTimestamp = Double.NaN
    private var seekPending = false
    private var closed = false
    internal var current: PublishedVideoFrame? = null
        private set
    internal var epoch = 0L
        private set
    internal var fileRevision = 0L
        private set
    internal var materialTick = false
        private set
    var materialUpdates = 0L
        private set
    var error: String? = null
        private set
    var path: String? = null
        private set

    val duration get() = video?.duration?.takeIf { it.isFinite() && it > 0 } ?: 0.0
    val timestamp get() = current?.timestamp ?: 0.0
    val uploadedFrameIndex get() = current?.sourceFrameIndex ?: -1L
    val hasFrame get() = current != null
    val playing get() = video?.playing == true
    val width get() = video?.width ?: 0
    val height get() = video?.height ?: 0
    val nativeAspect get() = if (height > 0) width.toFloat() / (2 * height) else 16f / 9f
    val decodeMicros get() = video?.backgroundDecodeMicros ?: 0L
    val droppedFrames get() = video?.droppedDecodedFrames ?: 0L
    val omittedMaterialFrames get() = video?.supersededDecodedFrames ?: 0L
    val selectedStride get() = if (selectiveDecode && speed > 4) 2 else 1
    val retainedFrames get() = history?.count ?: if (hasFrame) 1 else 0
    val historyBytes get() = history?.bytes ?: 0L

    private val prepare = Target { command, _ ->
        materialTick = false
        video?.let { source ->
            if (source.playbackRate != speed) source.playbackRate = speed
            source.conversionStride = selectedStride
            source.discardNonReference = skipNonReferenceFrames && speed > 1
            seekRequest.getAndSet(null)?.let { position ->
                source.pause()
                source.seek(position.coerceIn(0.0, (duration - 1 / source.fps.coerceAtLeast(1.0)).coerceAtLeast(0.0)))
                seekPending = true
                history?.clear()
                epoch++
            }
            playRequest.getAndSet(null)?.let { if (it) source.play() else source.pause() }
            val requestedHistory = this@StereoVideoSource.historyFrames
            val activeHistory = history
            if (activeHistory != null && activeHistory.capacity != requestedHistory) {
                activeHistory.resize(requestedHistory)
                epoch++
            } else if (activeHistory == null && requestedHistory > 1) {
                history = VideoFrameHistory(renderer, requestedHistory)
                epoch++
            }
            materialTick = cadence.tick(System.nanoTime(), materialFramesPerSecond)
            source.preferLatestDecodedFrame = materialFramesPerSecond != 0
            if (source.update(command, uploadFrame = materialTick)) {
                val wrapped = previousTimestamp.isFinite() && source.timestamp < previousTimestamp
                if (wrapped || seekPending) { history?.clear(); epoch++; seekPending = false }
                materialUpdates++
                current = history?.capture(command, source, materialUpdates) ?: PublishedVideoFrame(materialUpdates,
                    source.timestamp, source.uploadedFrameIndex, source.width, source.height, source.colorSpace,
                    source.colorRange, source.imageViewArray.toList())
                previousTimestamp = source.timestamp
            }
        }
    }

    init {
        require(historyFrames in 1..256)
        check(name !in stereo.beforeScene)
        stereo.beforeScene[name] = prepare
    }

    internal fun frame(framesBehind: Int): PublishedVideoFrame? =
        history?.frame(framesBehind) ?: current

    fun play() { playRequest.set(true) }
    fun pause() { playRequest.set(false) }
    fun seek(seconds: Double) { require(seconds.isFinite()); seekRequest.set(seconds) }

    fun load(file: String): Boolean = renderer.runOnQueueThread {
        check(!closed)
        val resolved = if (file.startsWith("~/")) File(System.getProperty("user.home"), file.substring(2)) else File(file)
        if (!resolved.isFile) { error = "No video file: $resolved"; return@runOnQueueThread false }
        val next = VideoTexture(renderer, pixelFormat = VideoDecodeFormat.P010, backgroundDecode = true)
        try {
            check(next.load(resolved.absolutePath)) { "Could not decode $resolved" }
            require(next.width % 2 == 0 && next.width >= 2 && next.height > 0) { "SBS needs an even source width" }
            require(next.colorTransfer != 16 && next.colorTransfer != 18) { "This player is SDR; PQ/HLG video needs an HDR display path" }
            vkDeviceWaitIdle(renderer.device.vkDevice)
            video?.cleanup()
            history?.close()
            history = if (historyFrames > 1) VideoFrameHistory(renderer, historyFrames) else null
            current = null
            previousTimestamp = Double.NaN
            seekPending = false
            cadence.reset()
            materialUpdates = 0
            epoch++
            fileRevision++
            next.queueDecodedFrames = true
            next.playbackRate = speed
            next.conversionStride = selectedStride
            next.discardNonReference = skipNonReferenceFrames && speed > 1
            video = next
            path = resolved.absolutePath
            error = null
            seekRequest.set(null)
            playRequest.set(null)
            next.play()
            true
        } catch (failure: Exception) {
            next.cleanup()
            error = failure.message ?: failure.javaClass.simpleName
            false
        }
    }

    override fun close() = renderer.runOnQueueThread {
        if (!closed) {
            closed = true
            stereo.beforeScene.remove(name, prepare)
            vkDeviceWaitIdle(renderer.device.vkDevice)
            video?.cleanup()
            video = null
            history?.close()
            history = null
            current = null
        }
    }
}
