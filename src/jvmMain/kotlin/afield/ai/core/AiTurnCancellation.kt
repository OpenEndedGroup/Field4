package afield.ai.core

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
