package afield.graphics.scene.video

import afield.graphics.render.*
import afield.graphics.shader.fromViews
import afield.graphics.texture.HasImageView
import afield.graphics.texture.LoadedTexture
import afield.graphics.texture.TextureSampler
import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.Target
import afield.graphics.vulkan.targets.MultiviewStereoTarget
import afield.math.linalg.Vector4f
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
    source: StereoVideoSource? = null,
    sharedAutoExposure: VideoAutoExposure? = null,
    private val autoExposureIndex: Int = 0,
) : AutoCloseable, ConfiguresModule {
    private val ownsStream = source == null
    val stream = source ?: StereoVideoSource(renderer, stereo, "$name-source")
    val autoExposure = sharedAutoExposure ?: VideoAutoExposure(renderer, 1)
    private val ownsAutoExposure = sharedAutoExposure == null
    private var meterRevision = 0L
    private val meterBegin = Target { command, _ -> autoExposure.beginFrame(command) }
    private val meterFinish = Target { command, _ -> autoExposure.record(command, listOf(accumulationMeterSource())) }

    /** Age in published pictures, including alternate-frame selection and material caps. */
    @Volatile
    var delayFrames = 0
        set(value) { require(value in 0..255); field = value }
    /** Linear-light gain after sharp/accumulated mixing; one preserves the original exposure. */
    @Volatile
    var brightness = 1f
        set(value) { require(value.isFinite() && value in 0f..16f); field = value }
    /** Straight-alpha coverage, independent of the headset-clock brightness envelope. */
    @Volatile
    var opacity = 1f
        set(value) { require(value.isFinite()); field = value }
    var speed: Double
        get() = stream.speed
        set(value) { stream.speed = value }
    var materialFramesPerSecond: Int
        get() = stream.materialFramesPerSecond
        set(value) { stream.materialFramesPerSecond = value }
    var selectiveDecode: Boolean
        get() = stream.selectiveDecode
        set(value) { stream.selectiveDecode = value }
    var skipNonReferenceFrames: Boolean
        get() = stream.skipNonReferenceFrames
        set(value) { stream.skipNonReferenceFrames = value }
    val materialUpdates get() = stream.materialUpdates
    val omittedMaterialFrames get() = stream.omittedMaterialFrames

    /** Luminance-preserving cool shadow tone after blending and brightness, immediately before output. */
    @Volatile
    var shadowCooling = 0f
        set(value) { require(value.isFinite() && value in 0f..1f); field = value }

    /** Sharp/accumulated image blend. Zero bypasses accumulation unless sharp colour transfer needs it. */
    @Volatile
    var accumulationMix = 0f
        set(value) { require(value.isFinite() && value in 0f..1f); field = value }
    /** Borrow processed accumulation chromaticity for sharp video while preserving its linear luminance. */
    @Volatile
    var accumulationColourTransfer = 0f
        set(value) { require(value.isFinite() && value in 0f..1f); field = value }
    @Volatile
    var accumulationHalfLife = .5
        set(value) { require(value.isFinite() && value in 0.0..60.0); field = value }
    @Volatile
    var accumulationInputWeight = 1.0
        set(value) { require(value.isFinite() && value in 0.0..100.0); field = value }
    /** Cool new decoded contributions before temporal accumulation; existing history is retained. */
    @Volatile
    var accumulationShadowCooling = 0f
        set(value) { require(value.isFinite() && value in 0f..1f); field = value }

    /** Presentation-only input black/white levels; the accumulated pixels remain untouched. */
    @Volatile
    var accumulationBlackLevel = 0f
        set(value) { require(value.isFinite()); field = value }
    @Volatile
    var accumulationWhiteLevel = 1f
        set(value) { require(value.isFinite()); field = value }
    /** Midtone gamma for accumulated-image presentation; values above one brighten midtones. */
    @Volatile
    var accumulationGamma = 1f
        set(value) { require(value.isFinite() && value > 0f); field = value }
    /** Per-screen auto/manual endpoint blend; the meter's global mode and mix remain independent. */
    @Volatile
    var autoExposureMix = 1f
        set(value) { require(value.isFinite() && value in 0f..1f); field = value }

    /** Hold a movie-local exposure per pair, alternating soft/dark then clear in real time. */
    @Volatile
    var exposureCadenceEnabled = false
    @Volatile
    var exposurePairsPerSecond = 12.0
        set(value) { require(value.isFinite() && value in 1.0..30.0); field = value }
    /** Gaussian sigma in original packed-video pixels; each eye is filtered independently. */
    @Volatile
    var exposureBlurSigma = 2f
        set(value) { require(value.isFinite() && value in 0f..16f); field = value }
    @Volatile
    var exposureBlurMix = 1f
        set(value) { require(value.isFinite() && value in 0f..1f); field = value }
    /** Fraction of linear-light brightness removed from the soft half of each pair. */
    @Volatile
    var exposureDarkening = .08f
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
    private var previousSerial = -1L
    private var previousEpoch = -1L
    private var previousFile = -1L
    private var previousDelay = -1
    private var currentFrame: PublishedVideoFrame? = null
    private val frameSource = object : VideoSourceFrame {
        override val width get() = stream.width
        override val height get() = stream.height
        override val colorSpace get() = currentFrame?.colorSpace ?: 1
        override val colorRange get() = currentFrame?.colorRange ?: 1
        override val imageViewArray get() = currentFrame?.imageViewArray.orEmpty()
    }
    private var accumulationWasEnabled = false

    val accumulationSamples get() = accumulator?.samples ?: 0L
    val decodeMicros get() = stream.decodeMicros
    val droppedFrames get() = stream.droppedFrames
    val selectedStride get() = stream.selectedStride

    fun clearAccumulation() { clearAccumulationRequest.set(true) }

    @Volatile
    var swapEyes = false
    @Volatile
    var flipVertically = false
    val error get() = stream.error
    val path get() = stream.path
    val duration get() = stream.duration
    /** Timestamp of this surface's selected (possibly delayed) picture. */
    val timestamp get() = currentFrame?.timestamp ?: 0.0
    val playing get() = stream.playing
    val hasFrame get() = currentFrame != null
    val uploadedFrameIndex get() = currentFrame?.sourceFrameIndex ?: -1L
    val width get() = stream.width
    val height get() = stream.height
    val nativeAspect get() = stream.nativeAspect

    private var module: ModulePointer? = null
    private var mesh: Mesh? = null
    private var closed = false
    private val sampler = TextureSampler(renderer, mipLevels = 1, anisotropyEnable = false)

    private val prepare = Target { command, index ->
        if (previousFile != stream.fileRevision) {
            accumulator?.close()
            accumulator = null
            exposureBlur?.close()
            exposureBlur = null
            exposureActive = false
            previousSerial = -1
            previousFile = stream.fileRevision
        }
        currentFrame = stream.frame(delayFrames)
        currentFrame?.let { selected ->
            val now = System.nanoTime()
            val uploaded = selected.serial != previousSerial
            val changed = previousEpoch != stream.epoch || previousDelay != delayFrames
            val materialTick = stream.materialTick
            val accumulating = accumulationMix > 0f || accumulationColourTransfer > 0f
            if (accumulating && accumulator == null) {
                accumulator = VideoFrameAccumulator(renderer, frameSource, accumulationModuleFactory)
            }
            if (changed || (accumulating && !accumulationWasEnabled)) clearAccumulationRequest.set(true)
            val reset = (materialTick || changed) && clearAccumulationRequest.getAndSet(false)
            if (reset) {
                accumulator?.clear()
                exposureClock.reset()
                exposureBlur?.clear()
            }
            if (accumulating && hasFrame && (uploaded || reset)) {
                accumulator?.render(command, index, accumulationHalfLife, accumulationInputWeight, accumulationShadowCooling)
                meterRevision++
            }
            accumulationWasEnabled = accumulating
            previousSerial = selected.serial
            previousEpoch = stream.epoch
            previousDelay = delayFrames

            // Only the material is held. Current eye poses still draw it on every headset frame.
            val wasActive = exposureActive
            exposureActive = exposureCadenceEnabled && accumulating && accumulator?.valid == true
            val phase = exposureClock.phase(now, playing, exposurePairsPerSecond)
            if (exposureActive) {
                val blur = exposureBlur ?: VideoExposureBlur(renderer, accumulator!!, width, height)
                    .also { exposureBlur = it }
                val refresh = !wasActive || !blur.valid || phase.pair != exposurePair || exposureRate != exposurePairsPerSecond
                if (refresh || exposureSigma != exposureBlurSigma) {
                    blur.render(command, index, exposureBlurSigma, refresh)
                    exposurePair = phase.pair
                    exposureSigma = exposureBlurSigma
                    exposureRate = exposurePairsPerSecond
                }
            }
            placement.validate()
            module?.current?.liveUniformStructures?.get("ScreenAppearance")?.set("levels",
                Vector4f(opacity, accumulationBlackLevel, accumulationWhiteLevel, accumulationGamma))
            module?.current?.liveUniformStructures?.get("ScreenAppearance")?.set("autoMeter", autoExposure.appearance(autoExposureIndex, autoExposureMix).apply { w = accumulationColourTransfer })
            module?.current?.liveUniformStructures?.get("ScreenAppearance")?.set("outputTone", Vector4f(shadowCooling, 0f, 0f, 0f))
            mesh?.apply {
                resize(if (hasFrame && opacity > 0f) strips * 6 else 0, 0)
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
                        width.toFloat(),
                        height.toFloat(),
                        if (swapEyes) 1f else 0f,
                        if (flipVertically) 1f else 0f,
                    )
                push["colorimetry"] = StereoVideoColorimetry.parameters(frameSource.colorSpace, frameSource.colorRange)
                    .apply { w = brightness - 1f } // spare component: exposure gain minus one
                push["accumulation"] = Vector4f(accumulationMix, if (accumulator?.valid == true) 1f else 0f,
                    if (exposureActive && phase.softened && exposureBlurSigma > 0f) exposureBlurMix else 0f,
                    if (exposureActive && phase.softened) exposureDarkening else 0f)
            }
        } ?: mesh?.resize(0, 0)
    }

    init {
        require(strips in 8..1024)
        check(name !in stereo.beforeScene && name !in stereo.layered.modules) {
            "Screen '$name' already exists"
        }
        if (ownsAutoExposure) stereo.beforeScene["$name-meter-begin"] = meterBegin
        stereo.beforeScene[name] = prepare
        if (ownsAutoExposure) stereo.beforeScene["$name-meter-finish"] = meterFinish
    }

    internal fun accumulationMeterSource(): VideoAutoExposure.Source? =
        accumulator?.takeIf { it.valid }?.let { VideoAutoExposure.Source(it, width, height, meterRevision) }

    /**
     * Configure a document's moduleHere block without assigning shader source inside it. Texture providers
     * follow the current movie and uploaded slot, so a new file does not replace the pipeline, its reload
     * actions, or the shader's generated Kotlin accessors.
     */
    override fun configure(parameters: Parameters) {
        stereo.configure(parameters)
        parameters.depthTest = false
        parameters.depthWrite = false
        parameters.blend = true
        parameters.srcColor = VK_BLEND_FACTOR_SRC_ALPHA
        parameters.dstColor = VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA
        parameters.srcAlpha = VK_BLEND_FACTOR_ONE
        parameters.dstAlpha = VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA
        val previousUboResolver = parameters.uboResolver
        parameters.uboResolver = { requested ->
            if (requested.name == "ScreenAppearance") requested.apply { uniform(); perFrame = true; hosted() }
            else previousUboResolver(requested)
        }
        val previousSsboResolver = parameters.ssboResolver
        parameters.ssboResolver = { requested ->
            if (requested.name == "VideoExposureStatistics") autoExposure.statisticsParameters else previousSsboResolver(requested)
        }
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
                                        else -> frameSource.imageViewArray.getOrNull(plane)?.imageView
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
        pointer.current.liveUniformStructures["ScreenAppearance"]?.set("levels",
            Vector4f(opacity, accumulationBlackLevel, accumulationWhiteLevel, accumulationGamma))
        pointer.current.liveUniformStructures["ScreenAppearance"]?.set("autoMeter", Vector4f(0f))
        pointer.current.liveUniformStructures["ScreenAppearance"]?.set("outputTone", Vector4f(shadowCooling, 0f, 0f, 0f))
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

    internal fun ensureDrawing() = renderer.runOnQueueThread {
        if (module == null && automaticDrawing) draw(defaultModule())
    }

    /** Open the shared movie once; every connected surface follows the replacement. */
    fun load(file: String): Boolean = renderer.runOnQueueThread {
        check(!closed)
        val loaded = stream.load(file)
        if (loaded) {
            if (module == null && automaticDrawing) draw(defaultModule())
            placement.aspect = nativeAspect
        }
        loaded
    }

    fun play() = stream.play()
    fun pause() = stream.pause()
    /** Latest seek wins for the shared source and resets every screen's delay/exposure history. */
    fun seek(seconds: Double) = stream.seek(seconds)

    override fun close() = renderer.runOnQueueThread {
        if (!closed) {
            closed = true
            stereo.beforeScene.remove(name, prepare)
            if (ownsAutoExposure) {
                stereo.beforeScene.remove("$name-meter-begin", meterBegin)
                stereo.beforeScene.remove("$name-meter-finish", meterFinish)
                autoExposure.close()
            }
            module?.let { stereo.layered.modules.remove(name, it) }
            vkDeviceWaitIdle(renderer.device.vkDevice)
            module?.current?.cleanup()
            accumulator?.close()
            accumulator = null
            exposureBlur?.close()
            exposureBlur = null
            if (ownsStream) stream.close()
            sampler.cleanup()
            currentFrame = null
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
