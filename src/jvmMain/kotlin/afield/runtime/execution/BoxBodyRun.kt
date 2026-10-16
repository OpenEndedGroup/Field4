package afield.runtime.execution

import afield.document.graph.Box

/**
 * Which boxes' bodies are executing right now: set by execution around a box's run or launch, and not by
 * its frame loop or callbacks, which run after the body has returned.
 *
 * This is what lets a saved `sourceBlock` refuse to hydrate on its own box's engine while that box's body
 * is still running. Evaluating there would run the body again (see `SourceBackedBlockSelfBoxReentry`).
 */
object BoxBodyRun {
    private val running = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** Run [block] as the body of [box]. */
    inline fun <T> during(box: Box, block: () -> T): T {
        enter(box.id)
        try { return block() } finally { exit(box.id) }
    }

    @PublishedApi internal fun enter(id: String) { running.merge(id, 1, Int::plus) }
    @PublishedApi internal fun exit(id: String) { running.computeIfPresent(id) { _, n -> (n - 1).takeIf { it > 0 } } }

    /** Whether the box with [id] is running its body (rather than a frame loop or a callback). */
    fun isRunning(id: String): Boolean = running.containsKey(id)
}
