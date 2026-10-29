/**
 * Holds back presentation of a frame set until every member is ready, so several textures switch
 * frames together.
 *
 * Ordinary clock ticks cannot replace an unfinished set; a new seek or reconfiguration revision
 * can. `FrameSetBarrierTest` covers it.
 */

package afield.graphics.texture.video

/**
 * Nonblocking two-phase presentation. Ordinary clock ticks cannot supersede an unfinished set
 * (which would starve a slow decoder); an explicit seek/reconfiguration revision can.
 * Call from one render thread. [K] must be an immutable source-frame identity.
 */
class FrameSetBarrier<K : Any> {
    /**
     * The frame identity [select] is holding back, or null when nothing is waiting. It survives
     * ordinary clock ticks, and is cleared by [presentIfReady], by [revise] with a new revision,
     * or by [reset].
     */
    var pending: K? = null
        private set

    /**
     * The frame identity the set is showing, or null before the first one. [presentIfReady] moves
     * [pending] here once its upload callback has returned, so this names images the caller has
     * committed to rather than ones it has asked for.
     */
    var presented: K? = null
        private set
    private var revision: Long? = null

    fun select(desired: K, revision: Long): K? {
        revise(revision)
        if (pending == null && desired != presented) pending = desired
        return pending
    }

    /** Cancel stale pending work even when a paused pacer has no replacement key to select. */
    fun revise(revision: Long) {
        if (this.revision == revision) return
        pending = null
        this.revision = revision
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
