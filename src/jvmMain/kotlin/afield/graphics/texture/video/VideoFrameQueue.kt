package afield.graphics.texture.video

/** Bounded decoded pictures with presentation-time gating. The caller owns synchronization and taken frames. */
internal class VideoFrameQueue<T>(
    private val timestamp: (T) -> Double,
    private val release: (T) -> Unit,
    private val capacity: Int = 8,
) {
    private val frames = ArrayDeque<T>()
    var dropped = 0L
        private set
    var superseded = 0L
        private set

    init { require(capacity > 0) }

    fun isNotEmpty() = frames.isNotEmpty()

    fun add(frame: T) {
        if (frames.size >= capacity) {
            release(frames.removeFirst())
            dropped++
        }
        frames.addLast(frame)
    }

    /** A null deadline is an explicit seek: publish its answer without waiting for the running clock. */
    fun take(deadline: Double?, latest: Boolean): T? {
        fun due(frame: T) = deadline == null || timestamp(frame) <= deadline
        if (latest) {
            // Discard only pictures already due. Keep future pictures for the slower clock.
            while (frames.size > 1 && due(frames[1])) {
                release(frames.removeFirst())
                superseded++
            }
        }
        val first = frames.firstOrNull() ?: return null
        return if (due(first)) frames.removeFirst() else null
    }

    fun clear() {
        frames.forEach(release)
        frames.clear()
    }
}
