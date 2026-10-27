package afield.graphics.vulkan.util

/**
 * Releases a renderer's deferred GPU resources at shutdown, when no later frame will come to age
 * them out.
 *
 * While a renderer is running, a resource retired by one frame waits in a queue until the frames
 * that might still be reading it have finished. Shutdown removes that guarantee, so [Renderer]
 * drains the queues by hand: once the present queue, the graph queue and the device are idle, and
 * then again after releasing the resources a document still held, because releasing those enqueues
 * more work of its own.
 *
 * Each batch is cleared before it is invoked, so a destructor may enqueue dependent work, and a
 * destructor that throws does not strand the resources queued behind it. Failures are collected and
 * handed back for the owner to report once the rest of its teardown is done, which is why a broken
 * release shows up as one exception with the others suppressed on it rather than as a half-finished
 * shutdown.
 */

/**
 * Drain a renderer's retirement queues after its device has become idle. There will be no
 * more frames to age the last batches. Clear each batch before invoking it so a destructor
 * can enqueue dependent work; a failed destructor must not strand the remaining resources.
 *
 * Call on the render thread, while command pools, fences and the device are still alive.
 * Return any failures for the owner to report after the rest of its teardown.
 */
internal fun drainDeferredCleanup(
    pending: MutableList<Runnable>,
    retired: MutableList<Pair<Long, MutableList<Runnable>>>,
): IllegalStateException? {
    var failure: IllegalStateException? = null
    while (pending.isNotEmpty() || retired.isNotEmpty()) {
        val batch = ArrayList<Runnable>()
        retired.forEach { (_, actions) ->
            batch.addAll(actions)
            actions.clear()
        }
        retired.clear()
        batch.addAll(pending)
        pending.clear()

        batch.forEach { action ->
            try {
                action.run()
            } catch (error: Throwable) {
                val report = failure ?: IllegalStateException("Deferred GPU cleanup failed").also {
                    failure = it
                }
                report.addSuppressed(error)
            }
        }
    }
    return failure
}
