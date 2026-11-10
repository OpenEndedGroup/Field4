package afield.graphics.scene.video

import afield.graphics.render.*
import afield.graphics.shader.fromViews
import afield.graphics.texture.HasImageView
import afield.graphics.texture.LoadedTexture
import afield.graphics.texture.TextureSampler
import afield.graphics.texture.video.VideoDecodeFormat
import afield.graphics.texture.video.VideoTexture
import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.Target
import afield.graphics.vulkan.targets.MultiviewStereoTarget
import afield.math.linalg.Vector4f
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import org.lwjgl.vulkan.VK10.*

/**
 * Full-resolution side-by-side video in a room-locked cylindrical screen, drawn once for both eyes. Decoding
 * runs on VideoTexture's worker. One pre-scene upload selects the same source frame for both eyes; the mirror
 * reads that very eye image. Optional video-local temporal accumulation, with no audio.
 */
class StereoVideoSurface(
    private val renderer: Renderer,
    private val stereo: MultiviewStereoTarget,
    val placement: StereoVideoPlacement = StereoVideoPlacement(),
    private val name: String = "stereo-video",
    private val strips: Int = 128,
    /** Library callers get a default pipeline; editable documents supply moduleHere via draw(). */
    private val automaticDrawing: Boolean = true,
) : AutoCloseable, ConfiguresModule {
    /** Clock multiplier; changes preserve the current media position. */
    @Volatile
    var speed = 1.0
        set(value) { require(value.isFinite() && value in .1..8.0); field = value }

    /** Zero is uncapped; 12/24 cap source publication and exposure updates, independently of speed. */
    @Volatile
    var materialFramesPerSecond = 0
        set(value) { require(value == 0 || value == 12 || value == 24); field = value }
    private val materialCadence = VideoMaterialCadence()
    var materialUpdates = 0L
        private set
    val omittedMaterialFrames get() = video?.supersededDecodedFrames ?: 0L

    /** Above 4x, retain alternate source frames before conversion/upload. */
    @Volatile
    var selectiveDecode = true
    @Volatile var skipNonReferenceFrames = false

    /** Sharp/accumulated image blend. Zero bypasses the accumulation pass entirely. */
    @Volatile
    var accumulationMix = 0f
        set(value) { require(value.isFinite() && value in 0f..1f); field = value }
    @Volatile var accumulationHalfLife = .5
        set(value) { require(value.isFinite() && value in 0.0..60.0); field = value }
    @Volatile var accumulationInputWeight = 1.0
        set(value) { require(value.isFinite() && value in 0.0..100.0); field = value }

    /** Hold a movie-local exposure per pair, alternating soft/dark then clear in real time. */
    @Volatile var exposureCadenceEnabled = false
    @Volatile var exposurePairsPerSecond = 12.0
        set(value) { require(value.isFinite() && value in 1.0..30.0); field = value }
    /** Gaussian sigma in original packed-video pixels; each eye is filtered independently. */
    @Volatile var exposureBlurSigma = 2f
        set(value) { require(value.isFinite() && value in 0f..16f); field = value }
    @Volatile var exposureBlurMix = 1f
        set(value) { require(value.isFinite() && value in 0f..1f); field = value }
    /** Fraction of linear-light brightness removed from the soft half of each pair. */
    @Volatile var exposureDarkening = .08f
        set(value) { require(value.isFinite() && value in 0f..1f); field = value }
    private val exposureClock = VideoExposureCadence()
    private var exposureBlur: VideoExposureBlur? = null
    private var exposureActive = false
    private var exposurePair = Long.MIN_VALUE
    private var exposureSigma = Float.NaN
    private var exposureRate = Double.NaN

    /** Editable documents install a moduleHere factory before loading a movie. */
    var accumulationModuleFactory: ((Parameters) -> ModulePointer)? = null
    private var accumulator: VideoFrameAccumulator? = null
    private val clearAccumulationRequest = java.util.concurrent.atomic.AtomicBoolean(true)
    private var previousSourceTime = Double.NaN
    private var accumulationWasEnabled = false

    val accumulationSamples get() = accumulator?.samples ?: 0L
    val decodeMicros get() = video?.backgroundDecodeMicros ?: 0L
    val droppedFrames get() = video?.droppedDecodedFrames ?: 0L
    val selectedStride get() = if (selectiveDecode && speed > 4.0) 2 else 1

    fun clearAccumulation() { clearAccumulationRequest.set(true) }

    @Volatile
    var swapEyes = false
    @Volatile
    var flipVertically = false
    @Volatile
    var error: String? = null
        private set

    @Volatile
    var path: String? = null
        private set

    @Volatile
    private var video: VideoTexture? = null
    private var module: ModulePointer? = null
    private var mesh: Mesh? = null
    private var closed = false
    private val sampler = TextureSampler(renderer, mipLevels = 1, anisotropyEnable = false)
    // Controls publish requests here; the queue thread consumes at most the newest value
    // before recording either eye. FFmpeg itself remains owned by VideoTexture's worker.
    private val seekRequest = AtomicReference<Double?>()
    private val playRequest = AtomicReference<Boolean?>()

    /** Loaded duration in seconds, or zero while no valid file is open. */
    val duration
        get() = video?.duration?.takeIf { it.isFinite() && it > 0 } ?: 0.0

    /** Timestamp of the image actually uploaded, rather than the requested seek position. */
    val timestamp
        get() = video?.timestamp ?: 0.0

    val playing
        get() = video?.playing == true

    val hasFrame
        get() = video?.hasFrame == true

    val uploadedFrameIndex
        get() = video?.uploadedFrameIndex ?: -1L

    val width
        get() = video?.width ?: 0

    val height
        get() = video?.height ?: 0

    /** Full-resolution SBS: half the packed width, with no implied squeeze correction. */
    val nativeAspect
        get() = if (height > 0) width.toFloat() / (2f * height) else 16f / 9f

    private val prepare = Target { command, index ->
        video?.let { source ->
            if (source.playbackRate != speed) source.playbackRate = speed
            source.conversionStride = selectedStride
            source.discardNonReference = skipNonReferenceFrames && speed > 1.0
            seekRequest.getAndSet(null)?.let {
                clearAccumulation()

                source.pause()
                source.seek(
                    it.coerceIn(0.0, (duration - 1.0 / source.fps.coerceAtLeast(1.0)).coerceAtLeast(0.0)),
                )
            }
            playRequest.getAndSet(null)?.let { if (it) source.play() else source.pause() }
            // Decode follows the media clock on every display frame. Only publication is held;
            // capped ticks select the newest decoded picture instead of draining an old backlog.
            val now = System.nanoTime()
            val materialTick = materialCadence.tick(now, materialFramesPerSecond)
            source.preferLatestDecodedFrame = materialFramesPerSecond != 0
            val uploaded = source.update(command, uploadFrame = materialTick)
            if (uploaded) materialUpdates++
            val accumulating = accumulationMix > 0f
            if (accumulating && accumulator == null) {
                accumulator = VideoFrameAccumulator(renderer, source, accumulationModuleFactory)
            }
            val wrapped = uploaded && previousSourceTime.isFinite() && source.timestamp < previousSourceTime
            if (wrapped || (accumulating && !accumulationWasEnabled)) clearAccumulationRequest.set(true)
            val reset = materialTick && clearAccumulationRequest.getAndSet(false)
            if (reset) {
                accumulator?.clear()
                exposureClock.reset()
                exposureBlur?.clear()
            }
            if (accumulating && source.hasFrame && (uploaded || reset)) {
                accumulator?.render(command, index, accumulationHalfLife, accumulationInputWeight)
            }
            accumulationWasEnabled = accumulating
            if (uploaded) previousSourceTime = source.timestamp

            // Only the material is held. Current eye poses still draw it on every headset frame.
            val wasActive = exposureActive
            exposureActive = exposureCadenceEnabled && accumulating && accumulator?.valid == true
            val phase = exposureClock.phase(now, source.playing, exposurePairsPerSecond)
            if (exposureActive) {
                val blur = exposureBlur ?: VideoExposureBlur(renderer, accumulator!!, source.width, source.height)
                    .also { exposureBlur = it }
                val refresh = !wasActive || !blur.valid || phase.pair != exposurePair || exposureRate != exposurePairsPerSecond
                if (refresh || exposureSigma != exposureBlurSigma) {
                    blur.render(command, index, exposureBlurSigma, refresh)
                    exposurePair = phase.pair
                    exposureSigma = exposureBlurSigma
                    exposureRate = exposurePairsPerSecond
                }
            }
            if (source.colorTransfer == 16 || source.colorTransfer == 18) {
                source.pause()
                module?.current?.enabled = false
                error = "PQ/HLG video needs an HDR display path"
                return@Target
            }
            placement.validate()
            mesh?.apply {
                resize(if (source.hasFrame) strips * 6 else 0, 0)
                push["model"] = placement.model()
                push["surface"] =
                    Vector4f(
                        placement.widthMeters,
                        placement.widthMeters / placement.aspect,
                        Math.toRadians(placement.bendDegrees.toDouble()).toFloat(),
                        strips.toFloat(),
                    )
                push["image"] =
                    Vector4f(
                        source.width.toFloat(),
                        source.height.toFloat(),
                        if (swapEyes) 1f else 0f,
                        if (flipVertically) 1f else 0f,
                    )
                push["colorimetry"] = StereoVideoColorimetry.parameters(source.colorSpace, source.colorRange)
                push["accumulation"] = Vector4f(accumulationMix, if (accumulator?.valid == true) 1f else 0f,
                    if (exposureActive && phase.softened && exposureBlurSigma > 0f) exposureBlurMix else 0f,
                    if (exposureActive && phase.softened) exposureDarkening else 0f)
            }
        }
    }

    init {
        require(strips in 8..1024)
        check(name !in stereo.beforeScene && name !in stereo.layered.modules) {
            "Screen '$name' already exists"
        }
        stereo.beforeScene[name] = prepare
    }

    /**
     * Configure a document's moduleHere block without assigning shader source inside it. Texture providers
     * follow the current movie and uploaded slot, so a new file does not replace the pipeline, its reload
     * actions, or the shader's generated Kotlin accessors.
     */
    override fun configure(parameters: Parameters) {
        stereo.configure(parameters)
        parameters.depthTest = false
        parameters.depthWrite = false
        parameters.blend = false
        parameters.textureResolver = { requested ->
            val plane =
                when (requested.name) {
                    "luma" -> 0
                    "chroma" -> 1
                    "accumulatedImage" -> 2
                    "softenedImage" -> 3
                    else -> -1
                }
            if (plane < 0) null
            else
                fromViews(
                    listOf(
                        object : HasImageView {
                            // Before a file is loaded the mesh has zero vertices, but descriptors still
                            // need valid images. This renderer-owned fallback is never drawn as video.
                            override val imageView
                                get() =
                                    (when (plane) {
                                        2 -> if (exposureActive) exposureBlur?.held?.imageView else accumulator?.imageView
                                        3 -> exposureBlur?.blurred?.imageView
                                        else -> video?.imageViewArray?.get(plane)?.imageView
                                    })
                                        ?: LoadedTexture.stubTexture(renderer).imageView
                        },
                    ),
                    requested.name,
                    sampler,
                )
        }
    }

    /** Attach a live document pipeline; draw before any optional Field browser overlay. */
    fun draw(pointer: ModulePointer) = renderer.runOnQueueThread {
        check(!closed && pointer.current.valid)
        val previous = module
        if (previous === pointer) return@runOnQueueThread

        // All pose and image data are initialized even while the empty screen is not drawing.
        // Module reload transfers this same mesh and its push suppliers to the new pipeline.
        val screen = Mesh(renderer, pointer).resize(0, 0)
        screen.push["model"] = placement.model() // screen-local metres -> room coordinates
        screen.push["surface"] = Vector4f(3f, 1.6875f, 0f, strips.toFloat()) // width, height, bend, strips
        screen.push["image"] = Vector4f(2f, 2f, 0f, 0f) // packed size, eye swap, vertical flip
        screen.push["colorimetry"] = StereoVideoColorimetry.parameters(1, 1) // BT.709, limited range
        screen.push["accumulation"] = Vector4f(0f) // processed mix, valid history, spatial blur mix, darkening
        pointer += "screen" to screen

        val overlays = stereo.layered.modules.filterKeys { it != name }
        stereo.layered.modules.clear()
        stereo.layered.modules[name] = pointer
        stereo.layered.modules.putAll(overlays)
        module = pointer
        mesh = screen
        previous?.current?.cleanup()
    }

    private fun defaultModule(): ModulePointer = Module(
        parameters(renderer) {
            name = this@StereoVideoSurface.name
            configure(this)
            vertex = source("vertex").replace("// VIEWS", MultiviewStereoTarget.VIEWS_GLSL)
            fragment = source("fragment")
        },
    ).pointer()
        .also { check(it.current.valid) { "Cannot build stereo video shaders" } }

    /** Load is an explicit file-open operation; later decoding and seeks are asynchronous. */
    fun load(file: String): Boolean = renderer.runOnQueueThread {
        check(!closed)
        val resolved =
            if (file.startsWith("~/")) File(System.getProperty("user.home"), file.substring(2))
            else File(file)
        if (!resolved.isFile) {
            error = "No video file: $resolved"
            return@runOnQueueThread false
        }
        val next = VideoTexture(renderer, pixelFormat = VideoDecodeFormat.P010, backgroundDecode = true)
        try {
            check(next.load(resolved.absolutePath)) { "Could not decode $resolved" }
            require(next.width % 2 == 0 && next.width >= 2 && next.height > 0) {
                "SBS needs an even source width"
            }
            require(next.colorTransfer != 16 && next.colorTransfer != 18) {
                "This player is SDR; PQ/HLG video needs an HDR display path"
            }
            // The document's moduleHere pipeline remains stable across file and size changes.
            // Only the library-only convenience path needs to create its pipeline on first load.
            val nextModule = module ?: if (automaticDrawing) defaultModule() else null
            // File replacement is infrequent. Drain GPU references before destroying old images.
            vkDeviceWaitIdle(renderer.device.vkDevice)
            val oldVideo = video
            accumulator?.close()
            accumulator = null
            exposureBlur?.close()
            exposureBlur = null
            exposureActive = false
            exposureClock.reset()
            previousSourceTime = Double.NaN
            materialCadence.reset()
            materialUpdates = 0
            clearAccumulation()
            next.queueDecodedFrames = true
            next.playbackRate = speed
            next.conversionStride = selectedStride
            next.discardNonReference = skipNonReferenceFrames && speed > 1.0
            video = next
            if (module == null && nextModule != null) draw(nextModule)
            oldVideo?.cleanup()
            placement.aspect = nativeAspect
            seekRequest.set(null)
            playRequest.set(null)
            path = resolved.absolutePath
            error = null
            next.play()
            true
        } catch (failure: Exception) {
            next.cleanup()
            error = failure.message ?: failure.javaClass.simpleName
            false // keep the previous movie and its descriptors when opening fails
        }
    }

    fun play() {
        playRequest.set(true)
    }

    fun pause() {
        playRequest.set(false)
    }

    /** Latest seek wins; scrubbing pauses. Call play() to resume at the chosen frame. */
    fun seek(seconds: Double) {
        require(seconds.isFinite())
        seekRequest.set(seconds)
    }

    override fun close() = renderer.runOnQueueThread {
        if (!closed) {
            closed = true
            stereo.beforeScene.remove(name, prepare)
            module?.let { stereo.layered.modules.remove(name, it) }
            vkDeviceWaitIdle(renderer.device.vkDevice)
            module?.current?.cleanup()
            accumulator?.close()
            accumulator = null
            exposureBlur?.close()
            exposureBlur = null
            video?.cleanup()
            sampler.cleanup()
            video = null
            module = null
            mesh = null
        }
    }

    companion object {
        private fun source(stage: String) =
            requireNotNull(StereoVideoSurface::class.java.getResource("/afield/stereovideo/$stage.glsl"))
                .readText()
    }
}
