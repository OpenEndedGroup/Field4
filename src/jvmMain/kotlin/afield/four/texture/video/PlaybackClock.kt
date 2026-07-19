package afield.four.texture.video

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
class SystemClock : PlaybackClock {
    private var startTime: Long = 0
    private var pausedTime: Double = 0.0
    private var isActive: Boolean = false
    
    override fun getCurrentTime(): Double {
        if (!isActive) return pausedTime
        
        val elapsed = (System.nanoTime() - startTime) / 1_000_000_000.0
        return pausedTime + elapsed
    }
    
    override fun isPlaying(): Boolean = isActive
    
    fun play() {
        if (!isActive) {
            startTime = System.nanoTime()
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
            startTime = System.nanoTime()
        }
    }
    
    fun reset() {
        startTime = System.nanoTime()
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
