/**
 * Defines [PlaybackClock], the time source [VideoTexture] reads to choose a frame, with two
 * implementations: [SystemClock] (a monotonic clock with play, pause and seek) and [AudioClock]
 * (time taken from an [AudioPlayer]).
 */

package afield.graphics.texture.video

/** Time source consumed by [VideoTexture] when selecting the frame for the current update. */
interface PlaybackClock {
    /**
     * Get the current playback time in seconds.
     * @return Current time in seconds from the start of playback
     */
    fun getCurrentTime(): Double
    
    /**
     * Check if the clock is currently active/playing.
     * @return true if the clock is running
     */
    fun isPlaying(): Boolean
}

/**
 * Monotonic process clock with explicit play, pause, seek, and reset state.
 *
 * [VideoTexture] creates this implementation internally when no external [PlaybackClock] is given.
 */
class SystemClock(private val nanoTime: () -> Long = System::nanoTime) : PlaybackClock {
    private var startTime: Long = 0
    private var pausedTime: Double = 0.0
    private var isActive: Boolean = false
    
    private var currentRate = 1.0

    /** Continuous rate changes re-anchor the clock; they never jump the media position. */
    var rate: Double
        get() = currentRate
        set(value) {
            setRateAt(value, getCurrentTime())
        }

    /** Change speed from an observed media position, discarding time owed to a lagging decoder. */
    fun setRateAt(value: Double, timestamp: Double) {
        require(value.isFinite() && value > 0.0 && timestamp.isFinite())
        pausedTime = timestamp
        startTime = nanoTime()
        currentRate = value
    }

    override fun getCurrentTime(): Double {
        if (!isActive) return pausedTime
        
        val elapsed = (nanoTime() - startTime) / 1_000_000_000.0
        return pausedTime + elapsed * rate
    }
    
    override fun isPlaying(): Boolean = isActive
    
    fun play() {
        if (!isActive) {
            startTime = nanoTime()
            isActive = true
        }
    }
    
    fun pause() {
        if (isActive) {
            pausedTime = getCurrentTime()
            isActive = false
        }
    }
    
    fun seek(timestamp: Double) {
        pausedTime = timestamp
        if (isActive) {
            startTime = nanoTime()
        }
    }
    
    fun reset() {
        startTime = nanoTime()
        pausedTime = 0.0
        isActive = false
    }
}

/** Delegates time and playing state to [AudioPlayer]'s processed-sample and OpenAL-offset clock. */
class AudioClock(private val audioPlayer: AudioPlayer) : PlaybackClock {
    override fun getCurrentTime(): Double {
        return audioPlayer.getCurrentTime()
    }
    
    override fun isPlaying(): Boolean {
        return audioPlayer.isCurrentlyPlaying()
    }
}
