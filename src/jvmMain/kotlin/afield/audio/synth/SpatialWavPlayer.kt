package afield.audio.synth

import afield.audio.engine.WavFloatArrayLoader
import afield.audio.spatial.steamaudio.*
import afield.math.linalg.Quat
import afield.math.linalg.Vec3
import java.io.File
import java.lang.foreign.Arena
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.*
import org.lwjgl.openal.ALC11.ALC_ALL_DEVICES_SPECIFIER
import org.lwjgl.openal.ALUtil

/**
 * A mono WAV emitter in OpenXR world metres, binauralized by Steam Audio once per audio block.
 * Preparation loads the WAV and HRTF off the audio thread. Controls publish whole immutable
 * snapshots; the audio thread alone advances the cursor and effect history. A missing head pose
 * keeps the last valid pose. No Runloop callback renders audio.
 *
 * Stereo WAVs use their left channel, matching [WavFloatArrayLoader]'s mono-source contract.
 * [openWiVRn] requires a named WiVRn device; opening never falls back to desktop speakers.
 */
class SpatialWavPlayer(
    file: File,
    private val head: HeadPoseSource,
    val sampleRate: Int = 48_000,
    val blockSize: Int = 256,
) : StereoRenderable, AutoCloseable {
    private val wav = WavFloatArrayLoader.loadWavToFloatArray(file.absolutePath)
    private val arena = Arena.ofShared()
    private val context = SteamAudioContext()
    private val settings = AudioSettings(sampleRate, blockSize)
    private val hrtf = Hrtf.default(context, settings)
    private val effect = BinauralEffect(context, settings, hrtf)
    private val params =
        BinauralEffectParams(arena, hrtf, interpolation = HrtfInterpolation.BILINEAR)
    private val mono = AudioBuffer(arena, 1, blockSize)
    private val stereo = AudioBuffer(arena, 2, blockSize)
    private val samples = FloatArray(blockSize)
    private val left = FloatArray(blockSize)
    private val right = FloatArray(blockSize)
    private val position = Vec3()
    private val orientation = Quat()
    private val direction = Vec3()
    private val inverseOrientation = Quat()
    private val nextPosition = Vec3()
    private val nextOrientation = Quat()
    private var cursor = 0.0
    private var envelope = 0f
    @Volatile private var closed = false
    private var output: FieldAudioEngine? = null
    private var patch: AudioPatchHandle? = null
    private val seekRequest = AtomicReference<Double?>(null)
    @Volatile private var source = Placement(0f, 1.5f, -1.5f)
    @Volatile
    var playing: Boolean = false
        private set

    @Volatile var looping: Boolean = true
    @Volatile var gain: Float = .25f
    @Volatile var distanceRolloff: Boolean = true
    @Volatile
    var positionSeconds: Double = 0.0
        private set

    @Volatile
    var renderedBlocks: Long = 0
        private set

    val durationSeconds: Double
        get() = wav.samples.size.toDouble() / wav.sampleRate

    var outputDevice: String? = null
        private set

    init {
        require(wav.samples.isNotEmpty() && sampleRate > 0 && blockSize > 0)
    }

    private data class Placement(val x: Float, val y: Float, val z: Float)

    /** Absolute source position, not an offset that rotates with the listener's head. */
    fun place(x: Number, y: Number, z: Number) {
        val p = Placement(x.toFloat(), y.toFloat(), z.toFloat())
        require(listOf(p.x, p.y, p.z).all { it.isFinite() })
        source = p
    }

    fun play() {
        check(!closed)
        playing = true
    }

    fun pause() {
        playing = false
    }

    fun seek(seconds: Double) {
        require(seconds.isFinite())
        seekRequest.set(seconds.coerceIn(0.0, durationSeconds))
    }

    fun restart() {
        seek(0.0)
        play()
    }

    /** Open a dedicated small-queue output; call after the headset's WiVRn sink appears. */
    @Synchronized
    fun openWiVRn() {
        check(!closed)
        if (output != null) return
        val devices = ALUtil.getStringList(0L, ALC_ALL_DEVICES_SPECIFIER).orEmpty()
        val device =
            devices.firstOrNull { it.contains("wivrn", ignoreCase = true) }
                ?: error("No WiVRn OpenAL output. Connect headset audio first. Devices: $devices")
        val engine = FieldAudioEngine.openBinauralOutput(device, sampleRate, blockSize)
        try {
            patch = engine.register(AudioPatchSpec.from(this))
            output = engine
            outputDevice = device
            // Register the whole player immediately, before a later box error can bypass loop.end.
            afield.runtime.lifecycle.RunScope.current()?.own(this)
        } catch (failure: Throwable) {
            engine.close()
            throw failure
        }
    }

    @Synchronized
    override fun render(outL: FloatArray, outR: FloatArray, numFrames: Int, ctx: SynthContext) {
        outL.fill(0f, 0, numFrames)
        outR.fill(0f, 0, numFrames)
        if (closed) return
        require(numFrames == blockSize && ctx.sampleRate.toInt() == sampleRate)
        seekRequest.getAndSet(null)?.let {
            cursor = it * wav.sampleRate
            effect.reset()
        }
        // Only commit a complete valid pose. Dropouts retain the last listener pose.
        if (head.headPose(nextPosition, nextOrientation)) {
            position.set(nextPosition)
            orientation.set(nextOrientation)
        }
        val p = source
        direction.set(p.x - position.x, p.y - position.y, p.z - position.z)
        val distance = direction.length()
        if (distance > 1e-6f) direction.mul(1f / distance) else direction.set(0f, 0f, -1f)
        // World direction -> listener-local direction; never mutate the published pose itself.
        inverseOrientation.set(orientation).conjugate().transform(direction, direction)
        params.setDirection(direction.x, direction.y, direction.z)
        val level = if (gain.isFinite()) gain.coerceIn(0f, 4f) else 0f
        val attenuation = if (distanceRolloff) 1f / max(1f, distance) else 1f
        val step = wav.sampleRate.toDouble() / sampleRate
        for (i in 0 until blockSize) {
            val active = playing && (looping || cursor < wav.samples.size)
            val target = if (active) level * attenuation else 0f
            // Five-ms gain envelope avoids hard clicks when playing, pausing or moving the source.
            envelope += (target - envelope) * (1f - exp(-1f / (.005f * sampleRate)))
            val at = cursor.coerceAtMost((wav.samples.size - 1).toDouble())
            val lo = at.toInt()
            val hi = if (lo + 1 < wav.samples.size) lo + 1 else if (looping) 0 else lo
            samples[i] =
                (wav.samples[lo] + (wav.samples[hi] - wav.samples[lo]) * (at - lo).toFloat()) *
                    envelope
            if (active) {
                cursor += step
                if (looping) cursor %= wav.samples.size
                else if (cursor >= wav.samples.size) playing = false
            }
        }
        mono.writeChannel(0, samples)
        effect.apply(params, mono, stereo)
        stereo.readChannel(0, left)
        stereo.readChannel(1, right)
        left.copyInto(outL)
        right.copyInto(outR)
        positionSeconds = cursor / wav.sampleRate
        renderedBlocks++
    }

    override fun close() {
        // Do not hold render's lock while joining the audio thread.
        playing = false
        patch?.close()
        output?.close()
        synchronized(this) {
            if (closed) return
            closed = true
            effect.close()
            hrtf.close()
            context.close()
            arena.close()
        }
    }
}
