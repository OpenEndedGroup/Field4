package afield.graphics.scene.video

/** Paired soft/dark then clear exposures in real time, independent of source speed and headset frames. */
internal class VideoExposureCadence {
    data class Phase(val pair: Long, val softened: Boolean)

    private var elapsed = 0L
    private var previous: Long? = null
    private var wasPlaying = false

    fun reset() {
        elapsed = 0L
        previous = null
        wasPlaying = false
    }

    /** Pausing holds the current phase; resuming never catches up the paused interval. */
    fun phase(nowNanos: Long, playing: Boolean, pairsPerSecond: Double): Phase {
        require(pairsPerSecond.isFinite() && pairsPerSecond in 1.0..30.0)
        previous?.let { if (wasPlaying) elapsed += (nowNanos - it).coerceAtLeast(0L) }
        previous = nowNanos
        wasPlaying = playing
        val halfStep = kotlin.math.floor(elapsed / 1e9 * pairsPerSecond * 2).toLong()
        return Phase(Math.floorDiv(halfStep, 2), halfStep % 2 == 0L)
    }
}
