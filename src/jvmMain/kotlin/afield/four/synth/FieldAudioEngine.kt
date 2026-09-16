@file:afield.CompletionCategory("Synthesis")
package afield.four.synth

import afield.graphquery.LiveGraphEdge
import afield.graphquery.LiveGraphInspectable
import afield.logger.lprintln
import org.lwjgl.openal.AL
import org.lwjgl.openal.AL10.*
import org.lwjgl.openal.ALC
import org.lwjgl.openal.ALC10.*
import org.lwjgl.openal.ALC11.*
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.collections.ArrayDeque
import kotlin.concurrent.thread
import kotlin.math.roundToInt

/**
 * The file that opens an audio device: the OpenAL output stream Field plays through, the optional
 * capture stream `audioInput()` reads from, and the mixer that sums every registered patch into it.
 *
 * [AudioPatchSpec] is one thing to hear, either a mono [Signal] with a gain and a pan or a
 * [StereoRenderable] with a gain. A box builds one by returning `audio { … }` from `synth.kt` rather
 * than by calling the constructors here. [FieldAudioEngine] is the device and the list of patches on
 * it; `shared()`, reached from a box as `audioEngine()`, is the single instance a process gets, and
 * its sample rate and buffer size are whatever the first caller in the session asked for. `register`
 * takes a spec and hands back an [AudioPatchHandle], and closing that handle is the only way a patch
 * leaves the mix.
 *
 * Rendering is pulled by the device rather than driven by a clock. One daemon thread keeps a queue of
 * OpenAL buffers filled, `bufferSize` frames each, and renders the next block only when the source
 * reports an earlier buffer as processed, so the mix advances as fast as the sound card consumes it
 * and no faster. A [SynthContext] is advanced once per block and handed to every patch. Patches
 * accumulate rather than replace: each renderer adds into the same pair of buffers, a mono one
 * through an equal-power pan law that puts a centred signal at 0.707 in both channels rather than at
 * half in each. The sum is clamped to [-1, 1] and written out as 16-bit stereo, and a sample that is
 * not finite becomes silence and is counted in `nonFiniteSamplesSanitized`, which reaches the
 * `warning,audio` log channel at most once every two seconds.
 *
 * Three properties of that loop account for most silence. `outputActive` means the device opened, not
 * that audio is flowing: it is set when the OpenAL source and its buffers exist and cleared only when
 * the thread exits, while rendering depends on that source continuing to report processed buffers. A
 * machine too loaded to service its own audio daemon therefore renders nothing with every flag still
 * reading true, and a patch that counts its own `generateStereo` calls is what separates that case
 * from a wiring mistake. If the device cannot be opened at all, the thread prints why and returns
 * without retrying, so `start()` does nothing from then on and patches register into a mix nobody
 * renders; `destroyShared()` forces a fresh attempt. And input is the newest block the capture device
 * has rather than a continuous stream: at most `bufferSize` frames are taken per block, and a block
 * with nothing ready reads as silence.
 *
 * `enableLinkOutput` sends the same mix on to Ableton Link through [LinkAudioInterface], and can mute
 * the local device while it does by zeroing the OpenAL source gain; the mix is rendered either way. A
 * Link interface opened anywhere else in the process is picked up per block without this file being
 * told about it, so a patch can reach Link without the call.
 */

/**
 * One thing to hear: a signal graph, the gain and pan it plays at, and how many blocks to render it
 * for before it joins the mix.
 *
 * A spec holds no device and makes no sound by itself. Boxes build one with `audio { … }` from
 * `synth.kt`; the two `from` overloads are the direct form, one for a mono [Signal] and one for a
 * [StereoRenderable]. Registering the same spec twice puts it in the mix twice.
 */
class AudioPatchSpec internal constructor(
    internal var renderer: Renderer,
    /**
     * Blocks to render and throw away on the registering thread before the patch joins the mix.
     *
     * Zero by default. Enough of them allocates the graph's internal buffers and gets its hot path
     * compiled before the audio thread reaches it, which is the difference between a first block that
     * is late and one that is not.
     */
    val warmUpCycles: Int = 0
) : LiveGraphInspectable {
    internal val visualTrace = StereoTrace(capacity = 4096, stride = 1)
    internal var visualSampleRate: Int = 48_000
    internal var inlineSummaryTarget: InlineAudioSummaryTarget? = null

    internal interface Renderer {
        fun render(outL: FloatArray, outR: FloatArray, numFrames: Int, ctx: SynthContext)
    }

    companion object {
        /**
         * A mono [signal] scaled by [gain] and placed by [pan], both of which are [Param]s and so can
         * themselves be signals.
         *
         * [pan] runs -1 to 1 through an equal-power law, so the centre is 0.707 in each channel and
         * the two ends are one channel only.
         */
        fun from(
            signal: Signal,
            gain: Param = Param(1f),
            pan: Param = Param(0f),
            warmUpCycles: Int = 0
        ): AudioPatchSpec {
            val spec = AudioPatchSpec(renderer = PlaceholderRenderer, warmUpCycles = warmUpCycles)
            spec.renderer = MonoRenderer(signal, gain, pan, spec.visualTrace)
            return spec
        }

        /**
         * A stereo [signal] scaled by [gain], with no pan: a two-channel source is already placed.
         *
         * The source is given [StereoNode]'s per-block cache if it does not already have one, so a
         * stateful graph reached twice in one block still advances once.
         */
        fun from(
            signal: StereoRenderable,
            gain: Param = Param(1f),
            warmUpCycles: Int = 0
        ): AudioPatchSpec {
            val spec = AudioPatchSpec(renderer = PlaceholderRenderer, warmUpCycles = warmUpCycles)
            spec.renderer = StereoRenderer(signal.hold(), gain, spec.visualTrace)
            return spec
        }
    }

    internal fun clearVisualizers() {
        visualTrace.clear()
    }

    private object PlaceholderRenderer : Renderer {
        override fun render(outL: FloatArray, outR: FloatArray, numFrames: Int, ctx: SynthContext) = Unit
    }

    override fun liveGraphChildren(): Iterable<LiveGraphEdge> = listOf(
        LiveGraphEdge("renderer") { renderer },
        LiveGraphEdge("warmUpCycles") { warmUpCycles }
    )

    override fun toString(): String = synthPrettyObject(
        this,
        "renderer" to renderer,
        "warmUpCycles" to warmUpCycles
    )
}

internal data class InlineAudioSummaryTarget(
    val line: Int,
    val property: String = "code",
    val annotation: String? = null
)

/**
 * A registration rather than a patch: closing it takes the spec out of the mix and does nothing else.
 *
 * The [AudioPatchSpec] itself survives and can be registered again. When a box returns a patch, the
 * return-value handler in `AudioReturnValue.kt` holds the handle for as long as that run of the box
 * lasts and closes it in a `finally`, which is what stops a re-run box from playing its old sound
 * underneath its new one.
 */
class AudioPatchHandle internal constructor(
    private val engine: FieldAudioEngine,
    private val id: String
) : AutoCloseable {
    override fun close() {
        engine.unregister(id)
    }

    override fun toString(): String = "AudioPatchHandle(id=$id)"
}

private class MonoRenderer(
    private val source: Signal,
    private val gain: Param,
    private val pan: Param,
    private val trace: StereoTrace
) : AudioPatchSpec.Renderer, LiveGraphInspectable {
    private var srcBuf = FloatArray(0)
    private var gainBuf = FloatArray(0)
    private var panBuf = FloatArray(0)
    private var patchL = FloatArray(0)
    private var patchR = FloatArray(0)
    private val piOver4 = (Math.PI / 4.0).toFloat()

    override fun render(outL: FloatArray, outR: FloatArray, numFrames: Int, ctx: SynthContext) {
        if (srcBuf.size < numFrames) {
            srcBuf = FloatArray(numFrames)
            gainBuf = FloatArray(numFrames)
            panBuf = FloatArray(numFrames)
            patchL = FloatArray(numFrames)
            patchR = FloatArray(numFrames)
        }
        source.render(srcBuf, numFrames, ctx)
        gain.render(gainBuf, numFrames, ctx)
        pan.render(panBuf, numFrames, ctx)

        for (i in 0 until numFrames) {
            val sample = srcBuf[i] * gainBuf[i]
            val p = panBuf[i].coerceIn(-1f, 1f)
            val angle = p * piOver4
            val left = sample * kotlin.math.cos(angle + piOver4)
            val right = sample * kotlin.math.sin(angle + piOver4)
            patchL[i] = left
            patchR[i] = right
            outL[i] += left
            outR[i] += right
        }
        trace.recordStereo(patchL, patchR, numFrames)
    }

    override fun liveGraphChildren(): Iterable<LiveGraphEdge> = listOf(
        LiveGraphEdge("source") { source },
        LiveGraphEdge("gain") { gain },
        LiveGraphEdge("pan") { pan }
    )
}

private class StereoRenderer(
    private val source: StereoRenderable,
    private val gain: Param,
    private val trace: StereoTrace
) : AudioPatchSpec.Renderer, LiveGraphInspectable {
    private var leftBuf = FloatArray(0)
    private var rightBuf = FloatArray(0)
    private var gainBuf = FloatArray(0)

    override fun render(outL: FloatArray, outR: FloatArray, numFrames: Int, ctx: SynthContext) {
        if (leftBuf.size < numFrames) {
            leftBuf = FloatArray(numFrames)
            rightBuf = FloatArray(numFrames)
            gainBuf = FloatArray(numFrames)
        }
        source.render(leftBuf, rightBuf, numFrames, ctx)
        gain.render(gainBuf, numFrames, ctx)

        for (i in 0 until numFrames) {
            val g = gainBuf[i]
            leftBuf[i] *= g
            rightBuf[i] *= g
            outL[i] += leftBuf[i]
            outR[i] += rightBuf[i]
        }
        trace.recordStereo(leftBuf, rightBuf, numFrames)
    }

    override fun liveGraphChildren(): Iterable<LiveGraphEdge> = listOf(
        LiveGraphEdge("source") { source },
        LiveGraphEdge("gain") { gain }
    )
}

/**
 * The audio device and the patches playing through it.
 *
 * Reach it with `shared()`, or with `audioEngine()` from a box; the constructor is private and one
 * process gets one engine. `sampleRate` and `bufferSize` are fixed when it is created and every patch
 * is rendered against the same [SynthContext], advanced once per block on the engine's own daemon
 * thread. Nothing resamples, so a source that generates at a rate of its own has to be built at the
 * engine's `sampleRate`, which is why `parkDough` reads that off the engine before it builds a
 * [DoughEngine].
 */
class FieldAudioEngine private constructor(
    val sampleRate: Int = 48_000,
    val bufferSize: Int = 512,
    private val queuedBufferCount: Int = 12,
    private val inputChannels: Int = 2
) : AutoCloseable {
    companion object {
        private const val NON_FINITE_LOG_INTERVAL_NS = 2_000_000_000L

        @Volatile
        private var sharedInstance: FieldAudioEngine? = null

        /**
         * The process-wide engine, created and started if it is not already running.
         *
         * The arguments are used only when it has to be created, so the first caller in a session
         * fixes the sample rate, the buffer size and the queue depth, and every later call gets that
         * engine whatever it asks for. [inputChannels] is clamped to 1 or 2.
         */
        @Synchronized
        fun shared(
            sampleRate: Int = 48_000,
            bufferSize: Int = 512,
            queuedBufferCount: Int = 12,
            inputChannels: Int = 2
        ): FieldAudioEngine {
            val engine = sharedInstance ?: FieldAudioEngine(
                sampleRate = sampleRate,
                bufferSize = bufferSize,
                queuedBufferCount = queuedBufferCount,
                inputChannels = inputChannels.coerceIn(1, 2)
            ).also {
                sharedInstance = it
            }
            engine.start()
            return engine
        }

        /**
         * Close the process-wide engine and forget it, so the next `shared()` opens the device again.
         *
         * This is the way back from a failed device open, which does not retry on its own, and the
         * only way to change the sample rate or buffer size a session is running at.
         */
        @Synchronized
        fun destroyShared() {
            sharedInstance?.close()
            sharedInstance = null
        }
    }

    private val started = AtomicBoolean(false)
    private val shouldStop = AtomicBoolean(false)
    private val patches = ConcurrentHashMap<String, AudioPatchSpec>()
    private val startLatch = CountDownLatch(1)

    private val synthContext = SynthContext(sampleRate.toFloat(), bufferSize)
    private val mixL = FloatArray(bufferSize)
    private val mixR = FloatArray(bufferSize)
    private val pcmBuffer: ByteBuffer =
        ByteBuffer.allocateDirect(bufferSize * 2 * 2).order(ByteOrder.nativeOrder())
    private val linkPcm = ShortArray(bufferSize * 2)

    private val captureState = CaptureState(bufferSize)
    private val monoInputSignal = CaptureSignal(captureState, -1)
    private val leftInputSignal = CaptureSignal(captureState, 0)
    private val rightInputSignal = CaptureSignal(captureState, 1)

    private var audioThread: Thread? = null
    private var device: Long = 0L
    private var context: Long = 0L
    private var source: Int = 0
    private var buffers: IntArray = IntArray(0)
    private val freeBuffers = ArrayDeque<Int>()

    private var captureDevice: Long = 0L
    private var captureScratch: ShortBuffer? = null

    @Volatile
    private var linkOutput: LinkAudioInterface? = null

    /**
     * Whether the local OpenAL source is audible.
     *
     * Only `enableLinkOutput` changes it. False mutes the device by setting its gain to zero and does
     * not stop the mix being rendered.
     */
    @Volatile
    var monitorLocalOutput: Boolean = true
        private set

    /**
     * Whether a capture device is open.
     *
     * False leaves `monoInput()` and `stereoInput()` producing silence rather than failing, so a
     * graph built on input still renders on a machine with no microphone.
     */
    @Volatile
    var inputActive: Boolean = false
        private set

    /**
     * Whether the output device opened, which is not the same as audio flowing.
     *
     * It is set once the OpenAL source and its buffers exist and cleared only when the audio thread
     * exits, so it stays true while a source that has stopped reporting processed buffers renders
     * nothing at all.
     */
    @Volatile
    var outputActive: Boolean = false
        private set

    /**
     * How many NaN or infinite samples have been replaced with silence since the engine started,
     * counted once per sample and per channel.
     *
     * A graph that has gone non-finite goes quiet rather than loud, so this counter is usually how it
     * is noticed. It is also logged to the `warning,audio` channel, at most once every two seconds.
     */
    @Volatile
    var nonFiniteSamplesSanitized: Long = 0L
        private set

    private var nonFiniteSamplesSinceLog: Long = 0L
    private var nextNonFiniteLogAtNs: Long = System.nanoTime() + NON_FINITE_LOG_INTERVAL_NS

    /**
     * The capture device's newest block as a mono [Signal], starting the engine if it is not running.
     *
     * A stereo capture device is averaged to one channel. Whenever capture is unavailable, or has
     * nothing ready at the top of a block, the signal reads zero.
     */
    fun monoInput(): Signal {
        start()
        return monoInputSignal
    }

    /**
     * The capture device's newest block as a [StereoSignal], starting the engine if it is not running.
     *
     * A mono capture device is copied to both channels, so the pair is always two full channels.
     */
    fun stereoInput(): StereoSignal {
        start()
        return StereoSignal(leftInputSignal, rightInputSignal)
    }

    /**
     * Publish the mix to Ableton Link as 16-bit stereo, and return this engine so the call chains.
     *
     * With [monitorLocal] false the local device is muted while Link carries the sound, which stops it
     * leaving the machine twice; the mix is rendered either way. [maxFrames] is raised to `bufferSize`
     * if it is smaller, since that is the size of the blocks Link will be handed.
     */
    fun enableLinkOutput(
        link: LinkAudioInterface = LinkAudioInterface.shared(),
        name: String = "Field4",
        maxFrames: Int = bufferSize,
        monitorLocal: Boolean = true
    ): FieldAudioEngine {
        link.enableAudioOutput(name = name, maxFrames = maxFrames.coerceAtLeast(bufferSize), channels = 2)
        linkOutput = link
        monitorLocalOutput = monitorLocal
        applyLocalOutputGain()
        start()
        return this
    }

    /**
     * Drop the engine's own reference to Link and unmute the local device.
     *
     * This clears a reference rather than a connection: the block loop falls back to whatever
     * [LinkAudioInterface] is still current in the process, so a shared interface opened elsewhere
     * keeps receiving the mix. `LinkAudioInterface.destroyShared()` is what ends that.
     */
    fun disableLinkOutput(): FieldAudioEngine {
        linkOutput = null
        monitorLocalOutput = true
        applyLocalOutputGain()
        return this
    }

    override fun toString(): String = synthPrettyObject(
        this,
        "sampleRate" to sampleRate,
        "bufferSize" to bufferSize,
        "queuedBufferCount" to queuedBufferCount,
        "inputChannels" to inputChannels,
        "patchCount" to patches.size,
        "inputActive" to inputActive,
        "outputActive" to outputActive,
        "monitorLocalOutput" to monitorLocalOutput,
        "linkOutput" to (linkOutput?.snapshot())
    )

    /**
     * Put [spec] in the mix and return the handle that takes it out again.
     *
     * Starts the engine if it is not running, then renders the spec's `warmUpCycles` blocks on the
     * calling thread and throws the audio away: that allocates the graph's internal buffers and gives
     * HotSpot passes over the hot path before the audio thread ever reaches it. The default is zero
     * cycles. The next block the device asks for is the first one the patch is heard in.
     */
    fun register(spec: AudioPatchSpec): AudioPatchHandle {
        start()
        warmUp(spec)
        spec.clearVisualizers()
        spec.visualSampleRate = sampleRate
        val id = UUID.randomUUID().toString()
        patches[id] = spec
        return AudioPatchHandle(this, id)
    }

    internal fun unregister(id: String) {
        patches.remove(id)
    }

    /**
     * Open the devices and begin rendering, or return at once if that has already been attempted.
     *
     * Blocks for up to five seconds while the audio thread tries to open its output and capture
     * devices, so `outputActive` and `inputActive` can be read immediately afterwards. Every entry
     * point that needs the engine running calls this, so a box rarely does. A second call after a
     * failed open does not try again: `destroyShared()` is what does.
     */
    fun start() {
        if (started.compareAndSet(false, true)) {
            shouldStop.set(false)
            audioThread = thread(start = true, isDaemon = true, name = "FieldAudioEngine") {
                try {
                    runLoop()
                } catch (t: Throwable) {
                    lprintln(
                        "error,audio",
                        "FieldAudioEngine crashed: ${t.message ?: t.javaClass.simpleName} " +
                            "(sampleRate=$sampleRate, bufferSize=$bufferSize, queuedBufferCount=$queuedBufferCount, patchCount=${patches.size})"
                    )
                    lprintln("error,audio", t.stackTraceToString())
                    throw t
                } finally {
                    outputActive = false
                    inputActive = false
                    startLatch.countDown()
                }
            }
        }
        startLatch.await(5, TimeUnit.SECONDS)
    }

    /**
     * Ask the audio thread to stop and wait up to two seconds for it, releasing the OpenAL device,
     * source, buffers and capture stream on the way out.
     *
     * The engine does not come back afterwards: `start()` sees that it has already started and does
     * nothing. Use `destroyShared()` to close the process-wide engine and get a new one.
     */
    override fun close() {
        shouldStop.set(true)
        audioThread?.join(2_000)
    }

    private fun warmUp(spec: AudioPatchSpec) {
        val cycles = spec.warmUpCycles
        if (cycles <= 0) return
        val tempCtx = SynthContext(sampleRate.toFloat(), bufferSize)
        val outL = FloatArray(bufferSize)
        val outR = FloatArray(bufferSize)
        repeat(cycles) {
            tempCtx.advanceCycle()
            Buf.clear(outL, 0, bufferSize)
            Buf.clear(outR, 0, bufferSize)
            spec.renderer.render(outL, outR, bufferSize, tempCtx)
        }
    }

    private fun runLoop() {
        try {
            initializeOutput()
            initializeCapture()
            startLatch.countDown()

            if (!outputActive) return

            repeat(queuedBufferCount) {
                queueNextBuffer()
            }
            ensurePlaying()

            val sleepMillis = ((bufferSize * 1_000.0) / sampleRate / 3.0).roundToInt().coerceAtLeast(1)

            while (!shouldStop.get()) {
                reclaimProcessedBuffers()
                while (freeBuffers.isNotEmpty()) {
                    queueNextBuffer()
                }
                ensurePlaying()
                Thread.sleep(sleepMillis.toLong())
            }
        } finally {
            runCatching { shutdownCapture() }.onFailure {
                lprintln("error,audio", "FieldAudioEngine capture shutdown failed: ${it.message ?: it.javaClass.simpleName}")
                lprintln("error,audio", it.stackTraceToString())
            }
            runCatching { shutdownOutput() }.onFailure {
                lprintln("error,audio", "FieldAudioEngine output shutdown failed: ${it.message ?: it.javaClass.simpleName}")
                lprintln("error,audio", it.stackTraceToString())
            }
        }
    }

    private fun initializeOutput() {
        try {
            device = alcOpenDevice(null as ByteBuffer?)
            if (device == 0L) {
                println("FieldAudioEngine: failed to open OpenAL output device")
                return
            }

            context = alcCreateContext(device, null as IntArray?)
            if (context == 0L || !alcMakeContextCurrent(context)) {
                println("FieldAudioEngine: failed to create OpenAL context")
                return
            }

            val alcCapabilities = ALC.createCapabilities(device)
            AL.createCapabilities(alcCapabilities)

            source = alGenSources()
            buffers = IntArray(queuedBufferCount)
            alGenBuffers(buffers)

            if (source == 0 || alGetError() != AL_NO_ERROR) {
                println("FieldAudioEngine: failed to create OpenAL source/buffers")
                return
            }

            freeBuffers.addAll(buffers.toList())
            applyLocalOutputGain()
            alSourcei(source, AL_LOOPING, AL_FALSE)
            outputActive = true
        } catch (t: Throwable) {
            println("FieldAudioEngine: output init failed: ${t.message}")
            t.printStackTrace()
        }
    }

    private fun initializeCapture() {
        try {
            val format = if (inputChannels == 1) org.lwjgl.openal.AL10.AL_FORMAT_MONO16 else org.lwjgl.openal.AL10.AL_FORMAT_STEREO16
            captureDevice = alcCaptureOpenDevice(null as ByteBuffer?, sampleRate, format, sampleRate)
            if (captureDevice == 0L) {
                return
            }
            captureScratch = MemoryUtil.memAllocShort(bufferSize * inputChannels)
            alcCaptureStart(captureDevice)
            inputActive = true
        } catch (t: Throwable) {
            println("FieldAudioEngine: capture init failed: ${t.message}")
            t.printStackTrace()
            shutdownCapture()
        }
    }

    private fun ensurePlaying() {
        if (!outputActive) return
        if (alGetSourcei(source, AL_BUFFERS_QUEUED) <= 0) return
        if (alGetSourcei(source, AL_SOURCE_STATE) != AL_PLAYING) {
            alSourcePlay(source)
        }
    }

    private fun applyLocalOutputGain() {
        if (source != 0) {
            alSourcef(source, AL_GAIN, if (monitorLocalOutput) 1f else 0f)
        }
    }

    private fun reclaimProcessedBuffers() {
        if (!outputActive) return
        val processed = alGetSourcei(source, AL_BUFFERS_PROCESSED)
        repeat(processed.coerceAtLeast(0)) {
            freeBuffers += alSourceUnqueueBuffers(source)
        }
    }

    private fun queueNextBuffer() {
        if (!outputActive || freeBuffers.isEmpty()) return
        val bufferId = freeBuffers.removeFirst()

        synthContext.advanceCycle()
        val activeLink = linkOutput ?: LinkAudioInterface.currentOrNull()
        val linkBlock = activeLink?.beginAudioBlock()
        synthContext.linkTiming = linkBlock?.snapshot
        pollCapture()
        Buf.clear(mixL, 0, bufferSize)
        Buf.clear(mixR, 0, bufferSize)

        val snapshot = patches.values.toList()
        snapshot.forEach { patch ->
            patch.renderer.render(mixL, mixR, bufferSize, synthContext)
        }

        pcmBuffer.clear()
        for (i in 0 until bufferSize) {
            val left = (sanitizePcmSample(mixL[i]) * Short.MAX_VALUE).roundToInt().toShort()
            val right = (sanitizePcmSample(mixR[i]) * Short.MAX_VALUE).roundToInt().toShort()
            val base = i * 2
            linkPcm[base] = left
            linkPcm[base + 1] = right
            pcmBuffer.putShort(left)
            pcmBuffer.putShort(right)
        }
        pcmBuffer.flip()
        linkOutput?.commitStereo(linkBlock, linkPcm, bufferSize, sampleRate)

        alBufferData(bufferId, AL_FORMAT_STEREO16, pcmBuffer, sampleRate)
        alSourceQueueBuffers(source, bufferId)
        if (alGetError() != AL_NO_ERROR) {
            freeBuffers += bufferId
        }
    }

    private fun sanitizePcmSample(sample: Float): Float {
        if (!sample.isFinite()) {
            recordNonFiniteSample()
            return 0f
        }
        return sample.coerceIn(-1f, 1f)
    }

    private fun recordNonFiniteSample() {
        nonFiniteSamplesSanitized += 1
        nonFiniteSamplesSinceLog += 1
        val now = System.nanoTime()
        if (now < nextNonFiniteLogAtNs) return

        lprintln(
            "warning,audio",
            "FieldAudioEngine sanitized non-finite samples: +$nonFiniteSamplesSinceLog (total=$nonFiniteSamplesSanitized)"
        )
        nonFiniteSamplesSinceLog = 0L
        nextNonFiniteLogAtNs = now + NON_FINITE_LOG_INTERVAL_NS
    }

    private fun pollCapture() {
        if (!inputActive || captureDevice == 0L) {
            captureState.clear()
            return
        }

        val scratch = captureScratch ?: run {
            captureState.clear()
            return
        }

        val available = MemoryStack.stackPush().use { stack ->
            val tmp = stack.mallocInt(1)
            alcGetIntegerv(captureDevice, ALC_CAPTURE_SAMPLES, tmp)
            tmp[0]
        }

        if (available <= 0) {
            captureState.clear()
            return
        }

        val framesToRead = minOf(available, bufferSize)
        scratch.clear()
        scratch.limit(framesToRead * inputChannels)
        alcCaptureSamples(captureDevice, scratch, framesToRead)
        captureState.update(scratch, framesToRead, inputChannels)
    }

    private fun shutdownCapture() {
        if (captureDevice != 0L) {
            alcCaptureStop(captureDevice)
            alcCaptureCloseDevice(captureDevice)
            captureDevice = 0L
        }
        captureScratch?.let {
            MemoryUtil.memFree(it)
            captureScratch = null
        }
        inputActive = false
    }

    private fun shutdownOutput() {
        if (source != 0) {
            runCatching { alSourceStop(source) }
        }

        val queued = if (source != 0) alGetSourcei(source, AL_BUFFERS_QUEUED) else 0
        repeat(queued.coerceAtLeast(0)) {
            runCatching { alSourceUnqueueBuffers(source) }
        }

        if (buffers.isNotEmpty()) {
            alDeleteBuffers(buffers)
            buffers = IntArray(0)
        }
        if (source != 0) {
            alDeleteSources(source)
            source = 0
        }
        if (context != 0L) {
            alcMakeContextCurrent(0L)
            alcDestroyContext(context)
            context = 0L
        }
        if (device != 0L) {
            alcCloseDevice(device)
            device = 0L
        }
        outputActive = false
    }
}

private class CaptureState(private val bufferSize: Int) {
    private val lock = Any()
    private var left = FloatArray(bufferSize)
    private var right = FloatArray(bufferSize)

    fun update(samples: ShortBuffer, frames: Int, channels: Int) {
        synchronized(lock) {
            val count = frames.coerceAtMost(bufferSize)
            for (i in 0 until count) {
                val base = i * channels
                val l = samples.get(base).toFloat() / Short.MAX_VALUE
                val r = if (channels > 1) samples.get(base + 1).toFloat() / Short.MAX_VALUE else l
                left[i] = l
                right[i] = r
            }
            for (i in count until bufferSize) {
                left[i] = 0f
                right[i] = 0f
            }
        }
    }

    fun clear() {
        synchronized(lock) {
            Buf.clear(left, 0, left.size)
            Buf.clear(right, 0, right.size)
        }
    }

    fun copy(channel: Int, output: FloatArray, numFrames: Int) {
        synchronized(lock) {
            val count = minOf(numFrames, bufferSize)
            when (channel) {
                0 -> System.arraycopy(left, 0, output, 0, count)
                1 -> System.arraycopy(right, 0, output, 0, count)
                else -> {
                    for (i in 0 until count) {
                        output[i] = (left[i] + right[i]) * 0.5f
                    }
                }
            }
            for (i in count until numFrames) {
                output[i] = 0f
            }
        }
    }
}

private class CaptureSignal(
    private val state: CaptureState,
    private val channel: Int
) : Signal() {
    override fun generate(output: FloatArray, numFrames: Int, ctx: SynthContext) {
        state.copy(channel, output, numFrames)
    }
}
