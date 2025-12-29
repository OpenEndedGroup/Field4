package afield.four.texture.video

import org.lwjgl.openal.AL
import org.lwjgl.openal.AL10.*
import org.lwjgl.openal.AL11.AL_SAMPLE_OFFSET
import org.lwjgl.openal.ALC
import org.lwjgl.openal.ALC10.*
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * AudioPlayer manages OpenAL audio playback for synchronized video/audio.
 * Supports streaming audio via buffer queuing for low-latency playback.
 */
class AudioPlayer(
    private val sampleRate: Int = 44100,
    private val channels: Int = 2
) {
    private var device: Long = 0
    private var context: Long = 0
    private var source: Int = 0
    private var buffers: IntArray = IntArray(NUM_BUFFERS)

    private var isInitialized = false
    private var isPlaying = false

    // Queue for tracking buffer states
    private val freeBuffers = ConcurrentLinkedQueue<Int>()
    private val queuedBuffers = ConcurrentLinkedQueue<Int>()

    // Track total samples processed for accurate timing
    private var totalSamplesProcessed: Long = 0
    private var lastSampleOffset: Int = 0

    // Format constants
    private val format = when (channels) {
        1 -> AL_FORMAT_MONO16
        2 -> AL_FORMAT_STEREO16
        else -> throw IllegalArgumentException("Unsupported channel count: $channels")
    }

    // Volume control
    var volume: Float = 1.0f
        set(value) {
            field = value.coerceIn(0.0f, 1.0f)
            if (isInitialized) {
                alSourcef(source, AL_GAIN, field)
            }
        }

    /**
     * Initialize OpenAL context and resources.
     */
    fun initialize(): Boolean {
        if (isInitialized) return true

        try {
            // Open default audio device
            device = alcOpenDevice(null as ByteBuffer?)
            if (device == 0L) {
                println("Error: Failed to open OpenAL device")
                return false
            }

            // Create context
            context = alcCreateContext(device, null as IntArray?)
            if (context == 0L) {
                println("Error: Failed to create OpenAL context")
                alcCloseDevice(device)
                device = 0
                return false
            }

            // Make context current
            if (!alcMakeContextCurrent(context)) {
                println("Error: Failed to make OpenAL context current")
                alcDestroyContext(context)
                alcCloseDevice(device)
                context = 0
                device = 0
                return false
            }

            // Create capabilities for the context
            val alcCapabilities = ALC.createCapabilities(device)
            AL.createCapabilities(alcCapabilities)

            // Check for errors
            val error = alGetError()
            if (error != AL_NO_ERROR) {
                println("Error: OpenAL initialization error: $error")
                cleanup()
                return false
            }

            // Generate audio source
            source = alGenSources()
            if (alGetError() != AL_NO_ERROR) {
                println("Error: Failed to generate OpenAL source")
                cleanup()
                return false
            }

            // Generate buffers
            alGenBuffers(buffers)
            if (alGetError() != AL_NO_ERROR) {
                println("Error: Failed to generate OpenAL buffers")
                cleanup()
                return false
            }

            // Initialize all buffers as free
            buffers.forEach { freeBuffers.offer(it) }

            // Set source properties
            alSourcef(source, AL_PITCH, 1.0f)
            alSourcef(source, AL_GAIN, volume)
            alSource3f(source, AL_POSITION, 0.0f, 0.0f, 0.0f)
            alSource3f(source, AL_VELOCITY, 0.0f, 0.0f, 0.0f)
            alSourcei(source, AL_LOOPING, AL_FALSE)

            println("OpenAL initialized: ${sampleRate}Hz, $channels channels")
            isInitialized = true
            return true

        } catch (e: Exception) {
            println("Error initializing OpenAL: ${e.message}")
            e.printStackTrace()
            cleanup()
            return false
        }
    }

    /**
     * Queue an audio buffer for playback.
     * @param audioData PCM audio data (16-bit signed, interleaved)
     * @param numSamples Number of samples in the buffer (for timing tracking)
     * @return true if buffer was successfully queued
     */
    fun queueBuffer(audioData: ByteBuffer, numSamples: Int = audioData.remaining() / (channels * 2)): Boolean {
        if (!isInitialized) return false

        // Get a free buffer (non-blocking check)
        val buffer = freeBuffers.poll()
        if (buffer == null) {
            // No free buffers available - caller should process buffers first
            return false
        }

        // Upload data to buffer
        alBufferData(buffer, format, audioData, sampleRate)
        if (alGetError() != AL_NO_ERROR) {
            println("Error uploading audio data to buffer")
            freeBuffers.offer(buffer)  // Return buffer to free pool
            return false
        }

        // Queue buffer on source
        alSourceQueueBuffers(source, buffer)
        if (alGetError() != AL_NO_ERROR) {
            println("Error queuing audio buffer")
            freeBuffers.offer(buffer)
            return false
        }

        queuedBuffers.offer(buffer)
        return true
    }

    /**
     * Queue an AudioFrame for playback (convenience method).
     * @param audioFrame Decoded audio frame from AudioDecoder
     * @return true if buffer was successfully queued
     */
    fun queueAudioFrame(audioFrame: AudioFrame): Boolean {
        return queueBuffer(audioFrame.data, audioFrame.samples)
    }

    /**
     * Process finished buffers and return them to the free pool.
     * Also updates timing information.
     */
    fun processBuffers() {
        if (!isInitialized) return

        val processed = alGetSourcei(source, AL_BUFFERS_PROCESSED)

        for (i in 0 until processed) {
            val buffer = alSourceUnqueueBuffers(source)
            if (alGetError() == AL_NO_ERROR) {
                // Get buffer size to update total samples
                val bufferSize = alGetBufferi(buffer, AL_SIZE)
                val samplesInBuffer = bufferSize / (channels * 2)  // 2 bytes per sample
                totalSamplesProcessed += samplesInBuffer

                queuedBuffers.remove(buffer)
                freeBuffers.offer(buffer)
            }
        }
    }

    /**
     * Start audio playback.
     */
    fun play() {
        if (!isInitialized || isPlaying) return

        alSourcePlay(source)
        if (alGetError() != AL_NO_ERROR) {
            println("Error starting audio playback")
            return
        }

        isPlaying = true
    }

    /**
     * Pause audio playback.
     */
    fun pause() {
        if (!isInitialized || !isPlaying) return

        alSourcePause(source)
        if (alGetError() != AL_NO_ERROR) {
            println("Error pausing audio playback")
            return
        }

        isPlaying = false
    }

    /**
     * Stop audio playback and clear all queued buffers.
     */
    fun stop() {
        if (!isInitialized) return

        alSourceStop(source)
        if (alGetError() != AL_NO_ERROR) {
            println("Error stopping audio playback")
        }

        // Unqueue all buffers
        val queued = alGetSourcei(source, AL_BUFFERS_QUEUED)
        for (i in 0 until queued) {
            val buffer = alSourceUnqueueBuffers(source)
            if (alGetError() == AL_NO_ERROR) {
                queuedBuffers.remove(buffer)
                freeBuffers.offer(buffer)
            }
        }

        // Reset timing
        totalSamplesProcessed = 0
        lastSampleOffset = 0

        isPlaying = false
    }

    /**
     * Get current playback time in seconds.
     * Tracks total samples played across all buffers for accurate timing.
     * @return Current playback time, or 0.0 if not playing
     */
    fun getCurrentTime(): Double {
        if (!isInitialized) return 0.0

        // Get current sample offset within the current buffer
        val currentOffset = alGetSourcei(source, AL_SAMPLE_OFFSET)

        // Handle offset wrap-around when buffers are processed
        if (currentOffset < lastSampleOffset) {
            // Offset wrapped, a buffer was completed
            // (processBuffers() should have already updated totalSamplesProcessed)
        }
        lastSampleOffset = currentOffset

        // Total time = (all processed samples + current offset) / sample rate
        return (totalSamplesProcessed + currentOffset).toDouble() / sampleRate
    }

    /**
     * Get number of buffers currently queued.
     */
    fun getQueuedBufferCount(): Int {
        if (!isInitialized) return 0
        return alGetSourcei(source, AL_BUFFERS_QUEUED)
    }

    /**
     * Get number of buffers processed (ready to be unqueued).
     */
    fun getProcessedBufferCount(): Int {
        if (!isInitialized) return 0
        return alGetSourcei(source, AL_BUFFERS_PROCESSED)
    }

    /**
     * Check if audio is currently playing.
     */
    fun isCurrentlyPlaying(): Boolean {
        if (!isInitialized) return false
        val state = alGetSourcei(source, AL_SOURCE_STATE)
        return state == AL_PLAYING
    }

    /**
     * Get number of free buffers available for queuing.
     */
    fun getFreeBufferCount(): Int = freeBuffers.size

    /**
     * Clean up OpenAL resources.
     */
    fun cleanup() {
        if (!isInitialized) return

        // Stop playback
        if (isPlaying) {
            stop()
        }

        // Delete buffers
        if (buffers.isNotEmpty()) {
            alDeleteBuffers(buffers)
            buffers = IntArray(0)
        }

        // Delete source
        if (source != 0) {
            alDeleteSources(source)
            source = 0
        }

        // Destroy context
        if (context != 0L) {
            alcMakeContextCurrent(0L)
            alcDestroyContext(context)
            context = 0L
        }

        // Close device
        if (device != 0L) {
            alcCloseDevice(device)
            device = 0L
        }

        freeBuffers.clear()
        queuedBuffers.clear()

        // Reset timing
        totalSamplesProcessed = 0
        lastSampleOffset = 0

        isInitialized = false
        isPlaying = false

        println("OpenAL cleaned up")
    }

    companion object {
        private const val NUM_BUFFERS = 4  // Number of buffers for streaming
    }
}
