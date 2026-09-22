package afield.four.util

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
