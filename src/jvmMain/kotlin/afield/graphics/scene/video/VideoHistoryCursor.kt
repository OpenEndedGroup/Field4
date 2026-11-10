package afield.graphics.scene.video

/** Published-frame age, independent of render frames; warm-up holds the oldest available picture. */
internal class VideoHistoryCursor(val capacity: Int) {
    init { require(capacity in 1..256) }
    var count = 0
        private set
    private var newest = -1
    fun clear() { count = 0; newest = -1 }
    fun advance(): Int {
        newest = (newest + 1) % capacity
        count = (count + 1).coerceAtMost(capacity)
        return newest
    }
    fun slot(framesBehind: Int): Int? {
        require(framesBehind in 0 until capacity) { "Video history age $framesBehind outside active capacity $capacity (filled $count)" }
        if (count == 0) return null
        return Math.floorMod(newest - framesBehind.coerceAtMost(count - 1), capacity)
    }
}
