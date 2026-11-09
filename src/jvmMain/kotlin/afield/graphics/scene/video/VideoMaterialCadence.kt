package afield.graphics.scene.video

/** One material update per wall-time bucket; missed buckets are skipped rather than queued. */
class VideoMaterialCadence {
    private var previousRate = -1
    private var previousBucket = Long.MIN_VALUE

    fun reset() {
        previousRate = -1
        previousBucket = Long.MIN_VALUE
    }

    /** Rate zero is uncapped. Display frames and eye poses do not use this clock. */
    fun tick(nowNanos: Long, framesPerSecond: Int): Boolean {
        require(framesPerSecond == 0 || framesPerSecond == 12 || framesPerSecond == 24)
        if (framesPerSecond == 0) {
            reset()
            return true
        }
        // Integer seconds/remainder avoids losing nanoseconds to a large floating-point timestamp.
        val bucket = Math.floorDiv(nowNanos, 1_000_000_000L) * framesPerSecond +
            Math.floorMod(nowNanos, 1_000_000_000L) * framesPerSecond / 1_000_000_000L
        val due = framesPerSecond != previousRate || bucket != previousBucket
        previousRate = framesPerSecond
        previousBucket = bucket
        return due
    }
}
