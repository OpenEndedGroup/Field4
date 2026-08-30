package afield.four.texture.video

/**
 * Nonblocking two-phase presentation. Ordinary clock ticks cannot supersede an unfinished set
 * (which would starve a slow decoder); an explicit seek/reconfiguration revision can.
 * Call from one render thread. [K] must be an immutable source-frame identity.
 */
class FrameSetBarrier<K : Any> {
    var pending: K? = null
        private set
    var presented: K? = null
        private set
    private var revision: Long? = null

    fun select(desired: K, revision: Long): K? {
        if (this.revision != revision) {
            pending = null
            this.revision = revision
        }
        if (pending == null && desired != presented) pending = desired
        return pending
    }

    /** No upload callback runs until the whole set is ready. Commit only after uploads succeed. */
    fun presentIfReady(ready: (K) -> Boolean, upload: (K) -> Unit): Boolean {
        val key = pending ?: return false
        if (!ready(key)) return false
        upload(key)
        presented = key
        pending = null
        return true
    }

    fun reset() {
        pending = null
        presented = null
        revision = null
    }
}
