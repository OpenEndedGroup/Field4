@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package afield.four.audio

import afield.four.targets.OpenXRHmdTarget
import afield.four.targets.SideBySideStereoTarget
import afield.four.texture.video.AudioDecoder
import afield.four.util.Camera
import afield.stdlib.Quat
import afield.stdlib.Sound2VecAudioSubclip
import afield.stdlib.Vec3
import afield.stdlib.vec
import afield.stdlib.linalg.Vector3f
import org.lwjgl.openal.AL
import org.lwjgl.openal.AL10.*
import org.lwjgl.openal.AL11.AL_SAMPLE_OFFSET
import org.lwjgl.openal.AL11.alSource3i
import org.lwjgl.openal.ALC
import org.lwjgl.openal.ALC10.*
import org.lwjgl.openal.EXTEfx
import org.lwjgl.openal.EXTEfx.*
import org.lwjgl.openal.SOFTHRTF
import org.lwjgl.openal.SOFTLoopback
import org.lwjgl.openal.EXTThreadLocalContext
import org.lwjgl.openal.ALCapabilities
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Simple spatial audio engine using LWJGL OpenAL.
 *
 * - Loads mono (or stereo-downmixed) WAV files.
 * - Supports 3D positioning with listener orientation.
 * - Optional HRTF enablement (OpenAL Soft).
 *
 * @param threadLocalContext isolate this engine on its initializing thread. All calls and destroy
 * must remain on that thread; requires ALC_EXT_thread_local_context and preserves other contexts.
 * @param loopbackSampleRate positive for deterministic stereo PCM16 rendering into [renderSamples],
 * zero for the normal output device. Loopback advances only when samples are requested.
 */
class SimpleSpatialAudioEngine(
    private val distanceModel: Int = AL_INVERSE_DISTANCE_CLAMPED,
    private val threadLocalContext: Boolean = false,
    val loopbackSampleRate: Int = 0
) {
    companion object {
        @Volatile
        private var sharedInstance: SimpleSpatialAudioEngine? = null

        /**
         * Shared, lazily-created engine. Intended for live-coding workflows.
         *
         * - The first call constructs the engine (using distanceModel).
         * - Subsequent calls return the same instance and ignore distanceModel.
         * - By default, initializes the engine (no-op if already initialized).
         */
        @Synchronized
        fun shared(
            initialize: Boolean = true,
            enableHrtf: Boolean = true,
            deviceName: String? = null,
            distanceModel: Int = AL_INVERSE_DISTANCE_CLAMPED
        ): SimpleSpatialAudioEngine {
            val engine = sharedInstance ?: SimpleSpatialAudioEngine(distanceModel).also {
                sharedInstance = it
            }
            if (initialize) {
                engine.initialize(enableHrtf = enableHrtf, deviceName = deviceName)
            }
            return engine
        }

        @Synchronized
        fun destroyShared() {
            sharedInstance?.destroy()
            sharedInstance = null
        }
    }

    private var device: Long = 0
    private var context: Long = 0
    private var initialized: Boolean = false
    private var ownerThread: Thread? = null
    private var previousThreadContext = 0L
    private var previousCapabilities: ALCapabilities? = null
    private var contextBound = false
    val sourceCount: Int get() = sources.size
    val bufferCount: Int get() = buffers.size
    val outputDevice: String get() = if (device == 0L) "closed" else alcGetString(device, ALC_DEVICE_SPECIFIER) ?: "unknown"

    private val sources = mutableSetOf<Int>()
    private val buffers = mutableSetOf<Int>()
    private val bufferById = mutableMapOf<Int, SoundBuffer>()
    private val bufferByName = mutableMapOf<String, SoundBuffer>()
    private val bufferNameById = mutableMapOf<Int, String>()
    private val sourceByName = mutableMapOf<String, SoundSource>()
    private val sourceNameById = mutableMapOf<Int, String>()

    private var efxChecked: Boolean = false
    private var efxAvailable: Boolean = false
    private var efxInitialized: Boolean = false
    private var efxEffect: Int = 0
    private var efxSlot: Int = 0
    private var efxFilter: Int = 0

    var efxMaxAuxSends: Int = 0
        private set

    var reverbSlotGain: Float = 1.0f
        set(value) {
            field = value.coerceIn(0.0f, 1.0f)
            if (efxInitialized) {
                alAuxiliaryEffectSlotf(efxSlot, AL_EFFECTSLOT_GAIN, field)
            }
        }

    private var listenerOrientation: java.nio.FloatBuffer? = null

    var masterGain: Float = 1.0f
        set(value) {
            field = value.coerceIn(0.0f, 1.0f)
            if (initialized) {
                alListenerf(AL_GAIN, field)
            }
        }

    var hrtfStatus: Int? = null
        private set

    var hrtfEnabled: Boolean = false
        private set

    fun isInitialized(): Boolean = initialized

    fun isEfxAvailable(): Boolean {
        if (!initialized) return false
        if (!efxChecked) {
            efxAvailable = alcIsExtensionPresent(device, "ALC_EXT_EFX")
            if (efxAvailable) {
                MemoryStack.stackPush().use { stack ->
                    val buf = stack.mallocInt(1)
                    alcGetIntegerv(device, EXTEfx.ALC_MAX_AUXILIARY_SENDS, buf)
                    efxMaxAuxSends = buf[0]
                }
            } else {
                efxMaxAuxSends = 0
            }
            efxChecked = true
        }
        return efxAvailable
    }

    /**
     * Initialize OpenAL context and set up default listener state.
     */
    fun initialize(enableHrtf: Boolean = true, deviceName: String? = null): Boolean {
        if (initialized) { requireInitialized(); return true }
        require(loopbackSampleRate == 0 || loopbackSampleRate in 8000..192000)
        ownerThread = Thread.currentThread()
        try {
            MemoryStack.stackPush().use { stack ->
                val nameBuf = deviceName?.let { stack.UTF8(it) }
                device = if (loopbackSampleRate > 0) SOFTLoopback.alcLoopbackOpenDeviceSOFT(nameBuf) else alcOpenDevice(nameBuf)
            }
            if (device == 0L) {
                println("OpenAL: Failed to open device")
                return false
            }

            val alcCapabilities = ALC.createCapabilities(device)
            check(!threadLocalContext || alcCapabilities.ALC_EXT_thread_local_context) {
                "OpenAL: isolated context requires ALC_EXT_thread_local_context"
            }
            val hrtfSupported = alcIsExtensionPresent(device, "ALC_SOFT_HRTF")
            val attributes = ArrayList<Int>()
            if (loopbackSampleRate > 0) {
                attributes.addAll(listOf(ALC_FREQUENCY, loopbackSampleRate,
                    SOFTLoopback.ALC_FORMAT_CHANNELS_SOFT, SOFTLoopback.ALC_STEREO_SOFT,
                    SOFTLoopback.ALC_FORMAT_TYPE_SOFT, SOFTLoopback.ALC_SHORT_SOFT))
            }
            if (enableHrtf && hrtfSupported) attributes.addAll(listOf(SOFTHRTF.ALC_HRTF_SOFT, ALC_TRUE))
            attributes.add(0)
            val attrs = attributes.toIntArray()

            context = alcCreateContext(device, attrs)
            if (context == 0L) {
                println("OpenAL: Failed to create context")
                alcCloseDevice(device)
                device = 0
                return false
            }

            if (threadLocalContext) {
                previousThreadContext = EXTThreadLocalContext.alcGetThreadContext()
                previousCapabilities = if (previousThreadContext != 0L) AL.getCapabilities() else null
            }
            val bound = if (threadLocalContext) EXTThreadLocalContext.alcSetThreadContext(context) else alcMakeContextCurrent(context)
            if (!bound) {
                println("OpenAL: Failed to make context current")
                alcDestroyContext(context)
                alcCloseDevice(device)
                context = 0
                device = 0
                return false
            }

            contextBound = true
            AL.createCapabilities(alcCapabilities)

            alDistanceModel(distanceModel)
            alListenerf(AL_GAIN, masterGain)

            initialized = true

            // Default listener: origin, looking down -Z, up +Y.
            listenerOrientation = MemoryUtil.memAllocFloat(6)
            setListener(vec(0, 0, 0), vec(0, 0, -1), vec(0, 1, 0))

            if (enableHrtf && hrtfSupported) {
                MemoryStack.stackPush().use { stack ->
                    val status = stack.mallocInt(1)
                    alcGetIntegerv(device, SOFTHRTF.ALC_HRTF_STATUS_SOFT, status)
                    hrtfStatus = status[0]
                    hrtfEnabled = status[0] == SOFTHRTF.ALC_HRTF_ENABLED_SOFT ||
                        status[0] == SOFTHRTF.ALC_HRTF_HEADPHONES_DETECTED_SOFT
                }
            } else {
                hrtfStatus = null
                hrtfEnabled = false
            }

            return true
        } catch (e: Exception) {
            println("OpenAL: Error initializing - ${e.message}")
            e.printStackTrace()
            destroy()
            return false
        }
    }

    /**
     * Initialize EFX (effects, auxiliary effect slot) if available.
     */
    fun initializeEfx(): Boolean {
        return ensureEfx()
    }

    /**
     * Configure standard EFX reverb.
     */
    fun setReverb(settings: ReverbSettings = ReverbSettings()): Boolean {
        if (!ensureEfx()) return false
        alEffecti(efxEffect, AL_EFFECT_TYPE, AL_EFFECT_REVERB)
        alEffectf(efxEffect, AL_REVERB_DENSITY, settings.density)
        alEffectf(efxEffect, AL_REVERB_DIFFUSION, settings.diffusion)
        alEffectf(efxEffect, AL_REVERB_GAIN, settings.gain)
        alEffectf(efxEffect, AL_REVERB_GAINHF, settings.gainHF)
        alEffectf(efxEffect, AL_REVERB_DECAY_TIME, settings.decayTime)
        alEffectf(efxEffect, AL_REVERB_DECAY_HFRATIO, settings.decayHFRatio)
        alEffectf(efxEffect, AL_REVERB_REFLECTIONS_GAIN, settings.reflectionsGain)
        alEffectf(efxEffect, AL_REVERB_REFLECTIONS_DELAY, settings.reflectionsDelay)
        alEffectf(efxEffect, AL_REVERB_LATE_REVERB_GAIN, settings.lateReverbGain)
        alEffectf(efxEffect, AL_REVERB_LATE_REVERB_DELAY, settings.lateReverbDelay)
        alEffectf(efxEffect, AL_REVERB_AIR_ABSORPTION_GAINHF, settings.airAbsorptionGainHF)
        alEffectf(efxEffect, AL_REVERB_ROOM_ROLLOFF_FACTOR, settings.roomRolloffFactor)
        alEffecti(efxEffect, AL_REVERB_DECAY_HFLIMIT, if (settings.decayHFLimit) AL_TRUE else AL_FALSE)

        alAuxiliaryEffectSloti(efxSlot, AL_EFFECTSLOT_EFFECT, efxEffect)
        return alGetError() == AL_NO_ERROR
    }

    /**
     * Configure standard EFX reverb using a settings builder.
     */
    fun setReverb(block: ReverbSettings.() -> Unit): Boolean {
        return setReverb(ReverbSettings().apply(block))
    }

    /**
     * Configure high-quality EAX reverb (OpenAL Soft).
     */
    fun setEaxReverb(settings: EaxReverbSettings = EaxReverbSettings()): Boolean {
        if (!ensureEfx()) return false
        alEffecti(efxEffect, AL_EFFECT_TYPE, AL_EFFECT_EAXREVERB)
        alEffectf(efxEffect, AL_EAXREVERB_DENSITY, settings.density)
        alEffectf(efxEffect, AL_EAXREVERB_DIFFUSION, settings.diffusion)
        alEffectf(efxEffect, AL_EAXREVERB_GAIN, settings.gain)
        alEffectf(efxEffect, AL_EAXREVERB_GAINHF, settings.gainHF)
        alEffectf(efxEffect, AL_EAXREVERB_GAINLF, settings.gainLF)
        alEffectf(efxEffect, AL_EAXREVERB_DECAY_TIME, settings.decayTime)
        alEffectf(efxEffect, AL_EAXREVERB_DECAY_HFRATIO, settings.decayHFRatio)
        alEffectf(efxEffect, AL_EAXREVERB_DECAY_LFRATIO, settings.decayLFRatio)
        alEffectf(efxEffect, AL_EAXREVERB_REFLECTIONS_GAIN, settings.reflectionsGain)
        alEffectf(efxEffect, AL_EAXREVERB_REFLECTIONS_DELAY, settings.reflectionsDelay)
        alEffectfv(
            efxEffect,
            AL_EAXREVERB_REFLECTIONS_PAN,
            floatArrayOf(settings.reflectionsPanX, settings.reflectionsPanY, settings.reflectionsPanZ)
        )
        alEffectf(efxEffect, AL_EAXREVERB_LATE_REVERB_GAIN, settings.lateReverbGain)
        alEffectf(efxEffect, AL_EAXREVERB_LATE_REVERB_DELAY, settings.lateReverbDelay)
        alEffectfv(
            efxEffect,
            AL_EAXREVERB_LATE_REVERB_PAN,
            floatArrayOf(settings.lateReverbPanX, settings.lateReverbPanY, settings.lateReverbPanZ)
        )
        alEffectf(efxEffect, AL_EAXREVERB_ECHO_TIME, settings.echoTime)
        alEffectf(efxEffect, AL_EAXREVERB_ECHO_DEPTH, settings.echoDepth)
        alEffectf(efxEffect, AL_EAXREVERB_MODULATION_TIME, settings.modulationTime)
        alEffectf(efxEffect, AL_EAXREVERB_MODULATION_DEPTH, settings.modulationDepth)
        alEffectf(efxEffect, AL_EAXREVERB_AIR_ABSORPTION_GAINHF, settings.airAbsorptionGainHF)
        alEffectf(efxEffect, AL_EAXREVERB_HFREFERENCE, settings.hfReference)
        alEffectf(efxEffect, AL_EAXREVERB_LFREFERENCE, settings.lfReference)
        alEffectf(efxEffect, AL_EAXREVERB_ROOM_ROLLOFF_FACTOR, settings.roomRolloffFactor)
        alEffecti(efxEffect, AL_EAXREVERB_DECAY_HFLIMIT, if (settings.decayHFLimit) AL_TRUE else AL_FALSE)

        alAuxiliaryEffectSloti(efxSlot, AL_EFFECTSLOT_EFFECT, efxEffect)
        return alGetError() == AL_NO_ERROR
    }

    /**
     * Configure high-quality EAX reverb using a settings builder.
     */
    fun setEaxReverb(block: EaxReverbSettings.() -> Unit): Boolean {
        return setEaxReverb(EaxReverbSettings().apply(block))
    }

    /**
     * Attach the current reverb effect slot to a source.
     */
    fun attachReverb(source: SoundSource, send: Int = 0, filter: Int = AL_FILTER_NULL): Boolean {
        if (!ensureEfx()) return false
        if (efxMaxAuxSends > 0 && (send < 0 || send >= efxMaxAuxSends)) {
            error("EFX send index out of range: $send (max ${efxMaxAuxSends - 1})")
        }
        alSource3i(source.id, AL_AUXILIARY_SEND_FILTER, efxSlot, send, filter)
        return alGetError() == AL_NO_ERROR
    }

    /**
     * Detach any reverb effect from a source.
     */
    fun detachReverb(source: SoundSource, send: Int = 0) {
        if (!initialized) return
        alSource3i(source.id, AL_AUXILIARY_SEND_FILTER, AL_EFFECTSLOT_NULL, send, AL_FILTER_NULL)
    }

    /**
     * Disable the current reverb effect slot.
     */
    fun disableReverb() {
        if (efxInitialized) {
            alAuxiliaryEffectSloti(efxSlot, AL_EFFECTSLOT_EFFECT, AL_EFFECT_NULL)
        }
    }

    /**
     * Clean up OpenAL resources.
     */
    fun destroy() {
        check(!threadLocalContext || ownerThread == null || ownerThread === Thread.currentThread()) {
            "OpenAL: destroy the isolated engine on its owning thread"
        }
        if (initialized) {
            requireInitialized()
            sources.forEach { alDeleteSources(it) }
            buffers.forEach { alDeleteBuffers(it) }
            destroyEfx()
            sources.clear()
            buffers.clear()
            bufferById.clear()
            bufferByName.clear()
            bufferNameById.clear()
            sourceByName.clear()
            sourceNameById.clear()
        }

        if (context != 0L) {
            if (contextBound) {
                if (threadLocalContext) {
                    EXTThreadLocalContext.alcSetThreadContext(previousThreadContext)
                    AL.setCurrentThread(previousCapabilities)
                } else alcMakeContextCurrent(0)
            }
            contextBound = false
            alcDestroyContext(context)
            context = 0
        }
        if (device != 0L) {
            alcCloseDevice(device)
            device = 0
        }

        listenerOrientation?.let {
            MemoryUtil.memFree(it)
            listenerOrientation = null
        }
        efxChecked = false
        efxAvailable = false
        efxMaxAuxSends = 0
        initialized = false
        ownerThread = null
        previousCapabilities = null
        previousThreadContext = 0L
    }

    /** Advance a loopback engine by [frames] stereo frames into caller-owned direct PCM16 storage. */
    fun renderSamples(output: ShortBuffer, frames: Int) {
        requireInitialized()
        require(loopbackSampleRate > 0) { "OpenAL: renderSamples requires a loopback engine" }
        require(output.isDirect) { "OpenAL: renderSamples requires a direct buffer" }
        require(frames >= 0 && output.remaining().toLong() >= frames.toLong() * 2)
        SOFTLoopback.alcRenderSamplesSOFT(device, output, frames)
    }

    /**
     * Load a WAV file and create an OpenAL buffer.
     * Only PCM WAV is supported (8/16-bit, mono or stereo). Stereo is downmixed to mono by default.
     *
     * If name is provided (or defaults to path) and the buffer already exists, the existing buffer is returned.
     */
    fun loadWav(path: String, forceMono: Boolean = true, name: String? = null): SoundBuffer {
        requireInitialized()
        val key = name ?: path
        bufferByName[key]?.let { return it }

        val data = WavLoader.load(path, forceMono)

        val buffer = alGenBuffers()
        if (buffer == 0) {
            data.free()
            error("OpenAL: Failed to generate buffer for $path")
        }

        alBufferData(buffer, data.format, data.pcm, data.sampleRate)
        if (alGetError() != AL_NO_ERROR) {
            data.free()
            alDeleteBuffers(buffer)
            error("OpenAL: Failed to upload buffer data for $path")
        }

        data.free()
        val soundBuffer = SoundBuffer(
            id = buffer,
            sampleRate = data.sampleRate,
            channels = data.channels,
            sampleCount = data.sampleCount,
            samples16 = data.samples16,
            engine = this
        )
        buffers.add(buffer)
        bufferById[buffer] = soundBuffer
        bufferByName[key] = soundBuffer
        bufferNameById[buffer] = key
        return soundBuffer
    }

    /**
     * Create an OpenAL buffer from interleaved 16-bit PCM audio.
     */
    fun loadPcm16(
        pcm16: ShortArray,
        sampleRate: Int,
        channels: Int,
        name: String? = null,
    ): SoundBuffer {
        requireInitialized()
        require(channels == 1 || channels == 2) { "OpenAL: only mono or stereo PCM16 is supported" }

        val key = name
        if (key != null) {
            bufferByName[key]?.let { return it }
        }

        val bufferId = alGenBuffers()
        if (bufferId == 0) {
            error("OpenAL: Failed to generate PCM buffer")
        }

        val pcm = MemoryUtil.memAlloc(pcm16.size * Short.SIZE_BYTES)
        pcm.asShortBuffer().put(pcm16).flip()
        val format = if (channels == 1) AL_FORMAT_MONO16 else AL_FORMAT_STEREO16

        try {
            alBufferData(bufferId, format, pcm, sampleRate)
            if (alGetError() != AL_NO_ERROR) {
                alDeleteBuffers(bufferId)
                error("OpenAL: Failed to upload PCM16 buffer data")
            }
        } finally {
            MemoryUtil.memFree(pcm)
        }

        val soundBuffer = SoundBuffer(
            id = bufferId,
            sampleRate = sampleRate,
            channels = channels,
            sampleCount = pcm16.size / channels,
            samples16 = pcm16,
            engine = this,
        )
        buffers.add(bufferId)
        bufferById[bufferId] = soundBuffer
        if (key != null) {
            bufferByName[key] = soundBuffer
            bufferNameById[bufferId] = key
        }
        return soundBuffer
    }

    /**
     * Decode a subclip from an arbitrary audio file into an OpenAL buffer.
     * Uses FFmpeg-backed AudioDecoder, so compressed formats such as mp3 are supported.
     */
    fun loadAudioSubclip(
        subclip: Sound2VecAudioSubclip,
        name: String? = null,
        outputSampleRate: Int = subclip.sampleRate.takeIf { it > 0 } ?: 44100,
        outputChannels: Int = 2,
    ): SoundBuffer {
        requireInitialized()
        val key = name ?: "${subclip.filePath}#${subclip.startSec}-${subclip.endSec}"
        bufferByName[key]?.let { return it }

        val decoder = AudioDecoder(subclip.filePath)
        check(decoder.open(outputSampleRate = outputSampleRate, outputChannels = outputChannels)) {
            "OpenAL: Failed to open audio decoder for ${subclip.filePath}"
        }

        try {
            if (subclip.startSec > 0.0) {
                check(decoder.seek(subclip.startSec)) {
                    "OpenAL: Failed to seek to ${subclip.startSec} in ${subclip.filePath}"
                }
            }

            val pcm16 = decodeSubclipPcm16(decoder, subclip, outputChannels)
            return loadPcm16(
                pcm16 = pcm16,
                sampleRate = outputSampleRate,
                channels = outputChannels,
                name = key,
            )
        } finally {
            decoder.close()
        }
    }

    /**
     * Decode and play a subclip immediately as a non-looping source.
     */
    fun playAudioSubclip(
        subclip: Sound2VecAudioSubclip,
        gain: Float = 1.0f,
        position: Vec3 = vec(0, 0, 0),
        name: String? = null,
    ): SoundSource {
        val buffer = loadAudioSubclip(subclip, name = name)
        return createSource(buffer = buffer, looping = false, name = name).also {
            it.gain = gain
            it.setPosition(position)
            it.play()
        }
    }

    /**
     * Create an OpenAL source (optionally with a buffer attached).
     *
     * If name is provided (or derived from the buffer name), an existing source with that name is returned
     * and updated with the supplied parameters.
     */
    fun createSource(
        buffer: SoundBuffer? = null,
        looping: Boolean = false,
        referenceDistance: Float = 1.0f,
        maxDistance: Float = 1000.0f,
        rolloffFactor: Float = 1.0f,
        name: String? = null
    ): SoundSource {
        requireInitialized()

        val key = name ?: buffer?.let { bufferNameById[it.id] }
        if (key != null) {
            val existing = sourceByName[key]
            if (existing != null) {
                if (buffer != null) {
                    existing.attachBuffer(buffer)
                }
                existing.looping = looping
                existing.setDistanceModel(referenceDistance, maxDistance, rolloffFactor)
                return existing
            }
        }

        val source = alGenSources()
        if (source == 0) {
            error("OpenAL: Failed to generate source")
        }

        alSourcef(source, AL_PITCH, 1.0f)
        alSourcef(source, AL_GAIN, 1.0f)
        alSource3f(source, AL_POSITION, 0.0f, 0.0f, 0.0f)
        alSource3f(source, AL_VELOCITY, 0.0f, 0.0f, 0.0f)
        alSourcei(source, AL_LOOPING, if (looping) AL_TRUE else AL_FALSE)
        alSourcei(source, AL_SOURCE_RELATIVE, AL_FALSE)
        alSourcef(source, AL_REFERENCE_DISTANCE, referenceDistance)
        alSourcef(source, AL_MAX_DISTANCE, maxDistance)
        alSourcef(source, AL_ROLLOFF_FACTOR, rolloffFactor)

        if (buffer != null) {
            alSourcei(source, AL_BUFFER, buffer.id)
        }

        sources.add(source)
        val soundSource = SoundSource(this, source)
        if (key != null) {
            sourceByName[key] = soundSource
            sourceNameById[source] = key
        }
        return soundSource
    }

    fun destroySource(source: SoundSource) {
        requireInitialized()
        if (sources.remove(source.id)) {
            alDeleteSources(source.id)
            sourceNameById.remove(source.id)?.let { sourceByName.remove(it) }
        }
    }

    fun destroyBuffer(buffer: SoundBuffer) {
        requireInitialized()
        if (buffers.remove(buffer.id)) {
            alDeleteBuffers(buffer.id)
            bufferById.remove(buffer.id)
            bufferNameById.remove(buffer.id)?.let { bufferByName.remove(it) }
        }
    }

    /**
     * Update listener position/orientation in world space.
     */
    fun setListener(position: Vec3, forward: Vec3, up: Vec3, velocity: Vec3? = null) {
        requireInitialized()

        setListener(position.x, position.y, position.z, forward.x, forward.y, forward.z,
            up.x, up.y, up.z)
        if (velocity != null) alListener3f(AL_VELOCITY, velocity.x, velocity.y, velocity.z)
    }

    /** Allocation-free listener update. Forward/up must be finite, nonzero directions. */
    fun setListener(px: Float, py: Float, pz: Float, fx: Float, fy: Float, fz: Float,
                    ux: Float, uy: Float, uz: Float) {
        requireInitialized()
        val fn = sqrt(fx*fx+fy*fy+fz*fz)
        val un = sqrt(ux*ux+uy*uy+uz*uz)
        require(fn.isFinite() && un.isFinite() && fn > 0f && un > 0f)
        alListener3f(AL_POSITION, px, py, pz)
        val orientation = listenerOrientation ?: error("Listener orientation buffer not initialized")
        orientation.put(0, fx/fn).put(1, fy/fn).put(2, fz/fn)
        orientation.put(3, ux/un).put(4, uy/un).put(5, uz/un)
        alListenerfv(AL_ORIENTATION, orientation)
    }

    /**
     * Convenience: update listener from an OpenXR head pose.
     */
    fun setListenerFromOpenXR(target: OpenXRHmdTarget, positionScale: Float = 1.0f): Boolean {
        val pos = Vec3()
        val ori = Quat()
        if (!target.headPose(pos, ori)) return false

        pos.mul(positionScale)
        val forward = Vector3f(0f, 0f, -1f)
        val up = Vector3f(0f, 1f, 0f)
        ori.transform(forward)
        ori.transform(up)

        setListener(pos, forward, up)
        return true
    }

    /**
     * Convenience: update listener from a SideBySideStereoTarget.
     * Uses OpenXR head pose if attached, otherwise falls back to the base camera.
     */
    fun setListenerFromSideBySide(target: SideBySideStereoTarget, positionScale: Float = 1.0f): Boolean {
        val xr = target.openXR
        if (xr != null && setListenerFromOpenXR(xr, positionScale)) return true
        return setListenerFromCamera(target.stereoCamera.base, positionScale)
    }

    /**
     * Convenience: update listener from a Camera.
     */
    fun setListenerFromCamera(camera: Camera, positionScale: Float = 1.0f): Boolean {
        val pos = Vector3f(camera.at).mul(positionScale)
        val forward = Vector3f(camera.forward).normalize()
        val up = Vector3f(camera.up).normalize()
        setListener(pos, forward, up)
        return true
    }

    /**
     * List available HRTF specifiers (OpenAL Soft).
     */
    fun availableHrtfSpecifiers(): List<String> {
        if (device == 0L) return emptyList()
        if (!alcIsExtensionPresent(device, "ALC_SOFT_HRTF")) return emptyList()

        MemoryStack.stackPush().use { stack ->
            val countBuf = stack.mallocInt(1)
            alcGetIntegerv(device, SOFTHRTF.ALC_NUM_HRTF_SPECIFIERS_SOFT, countBuf)
            val count = countBuf[0]
            if (count <= 0) return emptyList()

            return (0 until count).mapNotNull {
                SOFTHRTF.alcGetStringiSOFT(device, SOFTHRTF.ALC_HRTF_SPECIFIER_SOFT, it)
            }
        }
    }

    /**
     * Estimate the RMS energy of the currently playing source.
     *
     * This uses the current OpenAL sample offset to window into the original
     * PCM samples and compute an RMS value in [0, 1].
     */
    fun estimateSourceEnergy(source: SoundSource, windowSeconds: Float = 0.02f): Float {
        if (!initialized) return 0f
        if (!source.isPlaying()) return 0f

        val bufferId = alGetSourcei(source.id, AL_BUFFER)
        if (bufferId == 0) return 0f

        val buffer = bufferById[bufferId] ?: return 0f
        val samples = buffer.samples16 ?: return 0f

        val sampleOffset = alGetSourcei(source.id, AL_SAMPLE_OFFSET)
        if (sampleOffset < 0) return 0f

        val windowFrames = max(1, (windowSeconds * buffer.sampleRate).toInt())
        val totalFrames = buffer.sampleCount
        if (totalFrames <= 0) return 0f

        val half = windowFrames / 2
        val start = (sampleOffset - half).coerceAtLeast(0)
        val end = (start + windowFrames).coerceAtMost(totalFrames)

        if (end <= start) return 0f

        var sumSquares = 0.0
        var count = 0

        if (buffer.channels == 1) {
            for (i in start until end) {
                val s = samples[i].toInt()
                val f = s / 32768.0
                sumSquares += f * f
                count++
            }
        } else {
            val ch = buffer.channels
            for (i in start until end) {
                val base = i * ch
                if (base + 1 >= samples.size) break
                val l = samples[base].toInt()
                val r = samples[base + 1].toInt()
                val mixed = (l + r) / 2
                val f = mixed / 32768.0
                sumSquares += f * f
                count++
            }
        }

        if (count == 0) return 0f
        return sqrt((sumSquares / count).toFloat())
    }

    private fun ensureEfx(): Boolean {
        requireInitialized()
        if (efxInitialized) return true
        if (!isEfxAvailable()) return false

        efxEffect = alGenEffects()
        if (efxEffect == 0 || alGetError() != AL_NO_ERROR) {
            destroyEfx()
            return false
        }

        efxSlot = alGenAuxiliaryEffectSlots()
        if (efxSlot == 0 || alGetError() != AL_NO_ERROR) {
            destroyEfx()
            return false
        }

        alAuxiliaryEffectSloti(efxSlot, AL_EFFECTSLOT_EFFECT, efxEffect)
        alAuxiliaryEffectSlotf(efxSlot, AL_EFFECTSLOT_GAIN, reverbSlotGain)
        if (alGetError() != AL_NO_ERROR) {
            destroyEfx()
            return false
        }

        efxInitialized = true
        return true
    }

    private fun destroyEfx() {
        if (efxFilter != 0) {
            alDeleteFilters(efxFilter)
            efxFilter = 0
        }
        if (efxSlot != 0) {
            alDeleteAuxiliaryEffectSlots(efxSlot)
            efxSlot = 0
        }
        if (efxEffect != 0) {
            alDeleteEffects(efxEffect)
            efxEffect = 0
        }
        efxInitialized = false
    }

    internal fun requireInitialized() {
        check(initialized) { "SimpleSpatialAudioEngine is not initialized" }
        check(!threadLocalContext || (ownerThread === Thread.currentThread() && EXTThreadLocalContext.alcGetThreadContext() == context)) {
            "OpenAL: use the isolated engine on its owning thread with its context current"
        }
    }

    private fun decodeSubclipPcm16(
        decoder: AudioDecoder,
        subclip: Sound2VecAudioSubclip,
        outputChannels: Int,
    ): ShortArray {
        val bytes = ByteArrayOutputStream()
        val targetStart = subclip.startSec
        val targetEnd = max(subclip.endSec, subclip.startSec)

        while (true) {
            val frame = decoder.decodeFrame() ?: break
            val frameStart = frame.timestamp
            val frameDuration = frame.samples.toDouble() / frame.sampleRate.toDouble()
            val frameEnd = frameStart + frameDuration

            if (frameEnd <= targetStart) continue
            if (frameStart >= targetEnd) break

            val startSample = if (targetStart <= frameStart) 0 else ((targetStart - frameStart) * frame.sampleRate).toInt()
            val endSampleExclusive = if (targetEnd >= frameEnd) frame.samples else ((targetEnd - frameStart) * frame.sampleRate).toInt()
            val clippedStart = startSample.coerceIn(0, frame.samples)
            val clippedEnd = endSampleExclusive.coerceIn(clippedStart, frame.samples)
            if (clippedEnd <= clippedStart) continue

            val frameShorts = frame.data.duplicate().order(ByteOrder.nativeOrder()).asShortBuffer()
            writeInterleavedSamples(bytes, frameShorts, clippedStart, clippedEnd, outputChannels)
        }

        val payload = bytes.toByteArray()
        val shortBuffer = ByteBuffer.wrap(payload).order(ByteOrder.nativeOrder()).asShortBuffer()
        val out = ShortArray(shortBuffer.remaining())
        shortBuffer.get(out)
        return out
    }

    private fun writeInterleavedSamples(
        out: ByteArrayOutputStream,
        frameShorts: ShortBuffer,
        startSample: Int,
        endSampleExclusive: Int,
        channels: Int,
    ) {
        for (sampleIndex in startSample until endSampleExclusive) {
            val base = sampleIndex * channels
            for (channel in 0 until channels) {
                val sample = frameShorts.get(base + channel).toInt()
                out.write(sample and 0xFF)
                out.write((sample ushr 8) and 0xFF)
            }
        }
    }
}

data class SoundBuffer(
    val id: Int,
    val sampleRate: Int,
    val channels: Int,
    val sampleCount: Int,
    val samples16: ShortArray?,
    val engine: SimpleSpatialAudioEngine? = null
)

fun SoundBuffer.createSource(
    looping: Boolean = false,
    referenceDistance: Float = 1.0f,
    maxDistance: Float = 1000.0f,
    rolloffFactor: Float = 1.0f,
    name: String? = null
): SoundSource {
    val owner = engine ?: error("SoundBuffer has no engine; create sources via SimpleSpatialAudioEngine")
    return owner.createSource(
        buffer = this,
        looping = looping,
        referenceDistance = referenceDistance,
        maxDistance = maxDistance,
        rolloffFactor = rolloffFactor,
        name = name
    )
}

class SoundSource internal constructor(
    private val engine: SimpleSpatialAudioEngine,
    val id: Int
) {
    var gain: Float = 1.0f
        set(value) {
            engine.requireInitialized()
            field = value.coerceIn(0.0f, 4.0f)
            alSourcef(id, AL_GAIN, field)
        }

    var pitch: Float = 1.0f
        set(value) {
            engine.requireInitialized()
            field = value.coerceIn(0.125f, 4.0f)
            alSourcef(id, AL_PITCH, field)
        }

    var looping: Boolean = false
        set(value) {
            engine.requireInitialized()
            field = value
            alSourcei(id, AL_LOOPING, if (value) AL_TRUE else AL_FALSE)
        }

    fun attachBuffer(buffer: SoundBuffer) {
        engine.requireInitialized()
        alSourcei(id, AL_BUFFER, buffer.id)
    }

    fun detachBuffer() {
        engine.requireInitialized()
        alSourcei(id, AL_BUFFER, 0)
    }

    fun setPosition(position: Vec3) {
        engine.requireInitialized()
        alSource3f(id, AL_POSITION, position.x, position.y, position.z)
    }

    fun setPosition(x: Float, y: Float, z: Float) {
        engine.requireInitialized()
        alSource3f(id, AL_POSITION, x, y, z)
    }

    fun setVelocity(velocity: Vec3) {
        engine.requireInitialized()
        alSource3f(id, AL_VELOCITY, velocity.x, velocity.y, velocity.z)
    }

    fun setVelocity(x: Float, y: Float, z: Float) {
        engine.requireInitialized()
        alSource3f(id, AL_VELOCITY, x, y, z)
    }

    fun setDistanceModel(referenceDistance: Float, maxDistance: Float, rolloffFactor: Float = 1.0f) {
        engine.requireInitialized()
        alSourcef(id, AL_REFERENCE_DISTANCE, max(0.0001f, referenceDistance))
        alSourcef(id, AL_MAX_DISTANCE, max(0.001f, maxDistance))
        alSourcef(id, AL_ROLLOFF_FACTOR, max(0.0f, rolloffFactor))
    }

    /**
     * Gets the current playback position in seconds.
     */
    val currentTime: Float
        get() { engine.requireInitialized(); return alGetSourcef(id, org.lwjgl.openal.AL11.AL_SEC_OFFSET) }

    /**
     * Sets the current playback position in seconds.
     */
    fun seekTo(timeSeconds: Float) {
        engine.requireInitialized()
        alSourcef(id, org.lwjgl.openal.AL11.AL_SEC_OFFSET, timeSeconds)
    }

    fun play() {
        engine.requireInitialized()
        alSourcePlay(id)
    }

    fun pause() {
        engine.requireInitialized()
        alSourcePause(id)
    }

    fun stop() {
        engine.requireInitialized()
        alSourceStop(id)
    }

    fun isPlaying(): Boolean { engine.requireInitialized(); return alGetSourcei(id, AL_SOURCE_STATE) == AL_PLAYING }

    fun estimateEnergy(windowSeconds: Float = 0.02f): Float {
        return engine.estimateSourceEnergy(this, windowSeconds)
    }

    fun attachReverb(send: Int = 0, filter: Int = AL_FILTER_NULL): Boolean {
        return engine.attachReverb(this, send, filter)
    }

    fun detachReverb(send: Int = 0) {
        engine.detachReverb(this, send)
    }

    fun destroy() {
        engine.destroySource(this)
    }
}

data class ReverbSettings(
    val density: Float = AL_REVERB_DEFAULT_DENSITY,
    val diffusion: Float = AL_REVERB_DEFAULT_DIFFUSION,
    val gain: Float = AL_REVERB_DEFAULT_GAIN,
    val gainHF: Float = AL_REVERB_DEFAULT_GAINHF,
    val decayTime: Float = AL_REVERB_DEFAULT_DECAY_TIME,
    val decayHFRatio: Float = AL_REVERB_DEFAULT_DECAY_HFRATIO,
    val reflectionsGain: Float = AL_REVERB_DEFAULT_REFLECTIONS_GAIN,
    val reflectionsDelay: Float = AL_REVERB_DEFAULT_REFLECTIONS_DELAY,
    val lateReverbGain: Float = AL_REVERB_DEFAULT_LATE_REVERB_GAIN,
    val lateReverbDelay: Float = AL_REVERB_DEFAULT_LATE_REVERB_DELAY,
    val airAbsorptionGainHF: Float = AL_REVERB_DEFAULT_AIR_ABSORPTION_GAINHF,
    val roomRolloffFactor: Float = AL_REVERB_DEFAULT_ROOM_ROLLOFF_FACTOR,
    val decayHFLimit: Boolean = AL_REVERB_DEFAULT_DECAY_HFLIMIT != 0
)

data class EaxReverbSettings(
    val density: Float = AL_EAXREVERB_DEFAULT_DENSITY,
    val diffusion: Float = AL_EAXREVERB_DEFAULT_DIFFUSION,
    val gain: Float = AL_EAXREVERB_DEFAULT_GAIN,
    val gainHF: Float = AL_EAXREVERB_DEFAULT_GAINHF,
    val gainLF: Float = AL_EAXREVERB_DEFAULT_GAINLF,
    val decayTime: Float = AL_EAXREVERB_DEFAULT_DECAY_TIME,
    val decayHFRatio: Float = AL_EAXREVERB_DEFAULT_DECAY_HFRATIO,
    val decayLFRatio: Float = AL_EAXREVERB_DEFAULT_DECAY_LFRATIO,
    val reflectionsGain: Float = AL_EAXREVERB_DEFAULT_REFLECTIONS_GAIN,
    val reflectionsDelay: Float = AL_EAXREVERB_DEFAULT_REFLECTIONS_DELAY,
    val reflectionsPanX: Float = 0.0f,
    val reflectionsPanY: Float = 0.0f,
    val reflectionsPanZ: Float = 0.0f,
    val lateReverbGain: Float = AL_EAXREVERB_DEFAULT_LATE_REVERB_GAIN,
    val lateReverbDelay: Float = AL_EAXREVERB_DEFAULT_LATE_REVERB_DELAY,
    val lateReverbPanX: Float = 0.0f,
    val lateReverbPanY: Float = 0.0f,
    val lateReverbPanZ: Float = 0.0f,
    val echoTime: Float = AL_EAXREVERB_DEFAULT_ECHO_TIME,
    val echoDepth: Float = AL_EAXREVERB_DEFAULT_ECHO_DEPTH,
    val modulationTime: Float = AL_EAXREVERB_DEFAULT_MODULATION_TIME,
    val modulationDepth: Float = AL_EAXREVERB_DEFAULT_MODULATION_DEPTH,
    val airAbsorptionGainHF: Float = AL_EAXREVERB_DEFAULT_AIR_ABSORPTION_GAINHF,
    val hfReference: Float = AL_EAXREVERB_DEFAULT_HFREFERENCE,
    val lfReference: Float = AL_EAXREVERB_DEFAULT_LFREFERENCE,
    val roomRolloffFactor: Float = AL_EAXREVERB_DEFAULT_ROOM_ROLLOFF_FACTOR,
    val decayHFLimit: Boolean = AL_EAXREVERB_DEFAULT_DECAY_HFLIMIT != 0
)

private data class WavData(
    val pcm: ByteBuffer,
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
    val samples16: ShortArray
) {
    val format: Int = when (channels) {
        1 -> AL_FORMAT_MONO16
        2 -> AL_FORMAT_STEREO16
        else -> error("Unsupported channel count: $channels")
    }

    val sampleCount: Int = pcm.remaining() / 2 / channels

    fun free() {
        MemoryUtil.memFree(pcm)
    }
}

private object WavLoader {
    fun load(path: String, forceMono: Boolean): WavData {
        val file = File(path)
        require(file.exists()) { "WAV file not found: $path" }
        val bytes = file.readBytes()
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        fun readFourCC(): String {
            val id = ByteArray(4)
            buf.get(id)
            return String(id, Charsets.US_ASCII)
        }

        fun skipChunkPadding(size: Int) {
            if (size % 2 == 1 && buf.remaining() > 0) {
                buf.position(buf.position() + 1)
            }
        }

        val riff = readFourCC()
        require(riff == "RIFF") { "Invalid WAV (missing RIFF header): $path" }
        buf.int // file size
        val wave = readFourCC()
        require(wave == "WAVE") { "Invalid WAV (missing WAVE header): $path" }

        var fmtFound = false
        var dataFound = false

        var audioFormat = 0
        var channels = 0
        var sampleRate = 0
        var bitsPerSample = 0

        var dataOffset = 0
        var dataSize = 0

        while (buf.remaining() >= 8) {
            val chunkId = readFourCC()
            val chunkSize = buf.int
            val chunkStart = buf.position()

            when (chunkId) {
                "fmt " -> {
                    audioFormat = buf.short.toInt() and 0xFFFF
                    channels = buf.short.toInt() and 0xFFFF
                    sampleRate = buf.int
                    buf.int // byteRate
                    buf.short // blockAlign
                    bitsPerSample = buf.short.toInt() and 0xFFFF
                    fmtFound = true
                }
                "data" -> {
                    dataOffset = chunkStart
                    dataSize = chunkSize
                    dataFound = true
                }
            }

            buf.position(chunkStart + chunkSize)
            skipChunkPadding(chunkSize)
            if (fmtFound && dataFound) break
        }

        require(fmtFound) { "Invalid WAV (missing fmt chunk): $path" }
        require(dataFound) { "Invalid WAV (missing data chunk): $path" }
        require(audioFormat == 1 || audioFormat == 3) { "Unsupported WAV format (only PCM and IEEE Float supported): $path" }
        require(bitsPerSample == 8 || bitsPerSample == 16 || bitsPerSample == 32 || bitsPerSample == 64) { "Unsupported WAV bit depth: $bitsPerSample" }
        require(channels == 1 || channels == 2) { "Unsupported WAV channels: $channels" }

        require(dataOffset >= 0 && dataSize >= 0 && dataOffset + dataSize <= bytes.size) {
            "Invalid WAV data chunk bounds: offset=$dataOffset size=$dataSize total=${bytes.size}"
        }
        val raw = bytes.copyOfRange(dataOffset, dataOffset + dataSize)
        val pcm = if (forceMono || channels == 1) {
            toMono16(raw, channels, bitsPerSample, audioFormat)
        } else {
            toStereo16(raw, channels, bitsPerSample, audioFormat)
        }

        val outChannels = if (forceMono) 1 else channels
        val samples16 = ShortArray(pcm.remaining() / 2)
        pcm.asShortBuffer().get(samples16)
        return WavData(
            pcm = pcm,
            sampleRate = sampleRate,
            channels = outChannels,
            bitsPerSample = 16,
            samples16 = samples16
        )
    }

    private fun toMono16(raw: ByteArray, channels: Int, bitsPerSample: Int, audioFormat: Int): ByteBuffer {
        val bytesPerSample = bitsPerSample / 8
        val frameSize = channels * bytesPerSample
        val frameCount = raw.size / frameSize
        val out = MemoryUtil.memAlloc(frameCount * 2).order(ByteOrder.LITTLE_ENDIAN)

        if (audioFormat == 3 && bitsPerSample == 32) {
            val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until frameCount) {
                val l = bb.float
                val r = if (channels == 2) bb.float else l
                val mixed = if (channels == 2) (l + r) / 2.0f else l
                out.putShort((mixed.coerceIn(-1.0f, 1.0f) * 32767).toInt().toShort())
            }
            out.flip()
            return out
        }

        if (audioFormat == 3 && bitsPerSample == 64) {
            val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until frameCount) {
                val l = bb.double.toFloat()
                val r = if (channels == 2) bb.double.toFloat() else l
                val mixed = if (channels == 2) (l + r) / 2.0f else l
                out.putShort((mixed.coerceIn(-1.0f, 1.0f) * 32767).toInt().toShort())
            }
            out.flip()
            return out
        }

        if (channels == 1 && bitsPerSample == 16) {
            out.put(raw, 0, frameCount * 2)
            out.flip()
            return out
        }

        if (channels == 1 && bitsPerSample == 8) {
            for (i in 0 until frameCount) {
                val b = raw[i].toInt() and 0xFF
                val sample = ((b - 128) shl 8).toShort()
                out.putShort(sample)
            }
            out.flip()
            return out
        }

        if (channels == 2 && bitsPerSample == 16) {
            val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until frameCount) {
                val l = bb.short.toInt()
                val r = bb.short.toInt()
                val mixed = ((l + r) / 2).toShort()
                out.putShort(mixed)
            }
            out.flip()
            return out
        }

        if (channels == 2 && bitsPerSample == 8) {
            var idx = 0
            for (i in 0 until frameCount) {
                val l = (raw[idx].toInt() and 0xFF) - 128
                val r = (raw[idx + 1].toInt() and 0xFF) - 128
                val mixed = ((l + r) / 2)
                out.putShort((mixed shl 8).toShort())
                idx += 2
            }
            out.flip()
            return out
        }

        MemoryUtil.memFree(out)
        error("Unsupported WAV format for mono conversion (channels=$channels, bits=$bitsPerSample, format=$audioFormat)")
    }

    private fun toStereo16(raw: ByteArray, channels: Int, bitsPerSample: Int, audioFormat: Int): ByteBuffer {
        require(channels == 2) { "Stereo output requires 2-channel input" }
        val frameCount = raw.size / (channels * (bitsPerSample / 8))
        val out = MemoryUtil.memAlloc(frameCount * 2 * channels).order(ByteOrder.LITTLE_ENDIAN)

        if (audioFormat == 3 && bitsPerSample == 32) {
            val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until frameCount * 2) {
                val sample = bb.float
                out.putShort((sample.coerceIn(-1.0f, 1.0f) * 32767).toInt().toShort())
            }
            out.flip()
            return out
        }

        if (audioFormat == 3 && bitsPerSample == 64) {
            val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until frameCount * 2) {
                val sample = bb.double.toFloat()
                out.putShort((sample.coerceIn(-1.0f, 1.0f) * 32767).toInt().toShort())
            }
            out.flip()
            return out
        }

        if (bitsPerSample == 16) {
            out.put(raw, 0, frameCount * 2 * channels)
            out.flip()
            return out
        }

        if (bitsPerSample == 8) {
            var idx = 0
            for (i in 0 until frameCount * 2) {
                val b = raw[idx].toInt() and 0xFF
                val sample = ((b - 128) shl 8).toShort()
                out.putShort(sample)
                idx += 1
            }
            out.flip()
            return out
        }

        MemoryUtil.memFree(out)
        error("Unsupported WAV format for stereo conversion (bits=$bitsPerSample)")
    }
}
