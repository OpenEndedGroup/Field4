package afield.ai.conversation

/**
 * Telling a stopped AI4 turn apart from one that failed.
 *
 * Stopping a turn arrives as a `CancellationException` or an `InterruptedException`, but rarely at
 * the top of the throwable: a future, a coroutine or a reflective tool call wraps it, sometimes
 * several layers down, so the type that was thrown says nothing by itself. The one function here
 * walks the cause chain for either of those two, guarding against a cycle on the way.
 *
 * It is asked wherever the agent loop would otherwise swallow an exception and carry on. Saving and
 * restoring a conversation's branch state both do that, and both rethrow instead when this answers
 * yes, so a turn the person stopped is not written into the transcript as a step that failed.
 */

/** Futures and reflective tool dispatch can wrap interruption/cancellation. Never retry those as failures. */
internal fun isAiTurnCancellation(failure: Throwable): Boolean {
    val seen = mutableSetOf<Throwable>()
    var cause: Throwable? = failure
    while (cause != null && seen.add(cause)) {
        if (cause is java.util.concurrent.CancellationException || cause is InterruptedException) return true
        cause = cause.cause
    }
    return false
}
