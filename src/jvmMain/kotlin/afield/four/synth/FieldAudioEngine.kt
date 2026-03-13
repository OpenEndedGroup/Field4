package afield.four.synth

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

class AudioPatchSpec internal constructor(
    internal var renderer: Renderer,
    val warmUpCycles: Int = 0
) {
    internal val visualTrace = StereoTrace(capacity = 4096, stride = 1)
    internal var visualSampleRate: Int = 48_000

    internal interface Renderer {
        fun render(outL: FloatArray, outR: FloatArray, numFrames: Int, ctx: SynthContext)
    }

    companion object {
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
}

class AudioPatchHandle internal constructor(
    private val engine: FieldAudioEngine,
    private val id: String
) : AutoCloseable {
    override fun close() {
        engine.unregister(id)
    }
}

private class MonoRenderer(
    private val source: Signal,
    private val gain: Param,
    private val pan: Param,
    private val trace: StereoTrace
) : AudioPatchSpec.Renderer {
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
}

private class StereoRenderer(
    private val source: StereoRenderable,
    private val gain: Param,
    private val trace: StereoTrace
) : AudioPatchSpec.Renderer {
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
}

class FieldAudioEngine private constructor(
    val sampleRate: Int = 48_000,
    val bufferSize: Int = 512,
    private val queuedBufferCount: Int = 4,
    private val inputChannels: Int = 2
) : AutoCloseable {
    companion object {
        @Volatile
        private var sharedInstance: FieldAudioEngine? = null

        @Synchronized
        fun shared(
            sampleRate: Int = 48_000,
            bufferSize: Int = 512,
            queuedBufferCount: Int = 4,
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
    var inputActive: Boolean = false
        private set

    @Volatile
    var outputActive: Boolean = false
        private set

    fun monoInput(): Signal {
        start()
        return monoInputSignal
    }

    fun stereoInput(): StereoSignal {
        start()
        return StereoSignal(leftInputSignal, rightInputSignal)
    }

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

    fun start() {
        if (started.compareAndSet(false, true)) {
            shouldStop.set(false)
            audioThread = thread(start = true, isDaemon = true, name = "FieldAudioEngine") {
                try {
                    runLoop()
                } finally {
                    outputActive = false
                    inputActive = false
                    startLatch.countDown()
                }
            }
        }
        startLatch.await(5, TimeUnit.SECONDS)
    }

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

        shutdownCapture()
        shutdownOutput()
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
            alSourcef(source, AL_GAIN, 1f)
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
        pollCapture()
        Buf.clear(mixL, 0, bufferSize)
        Buf.clear(mixR, 0, bufferSize)

        val snapshot = patches.values.toList()
        snapshot.forEach { patch ->
            patch.renderer.render(mixL, mixR, bufferSize, synthContext)
        }

        pcmBuffer.clear()
        for (i in 0 until bufferSize) {
            val left = (mixL[i].coerceIn(-1f, 1f) * Short.MAX_VALUE).roundToInt().toShort()
            val right = (mixR[i].coerceIn(-1f, 1f) * Short.MAX_VALUE).roundToInt().toShort()
            pcmBuffer.putShort(left)
            pcmBuffer.putShort(right)
        }
        pcmBuffer.flip()

        alBufferData(bufferId, AL_FORMAT_STEREO16, pcmBuffer, sampleRate)
        alSourceQueueBuffers(source, bufferId)
        if (alGetError() != AL_NO_ERROR) {
            freeBuffers += bufferId
        }
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
