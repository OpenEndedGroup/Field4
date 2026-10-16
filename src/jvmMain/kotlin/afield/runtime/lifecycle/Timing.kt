@file:afield.runtime.completion.CompletionCategory("Execution")

package afield.runtime.lifecycle

/**
 * Run [warmup] untimed calls and return the shortest of [repetitions] measured calls, in milliseconds.
 * This is a quick local comparison, sensitive to JVM warmup and system load. The block runs repeatedly;
 * exceptions propagate immediately and side effects are not undone.
 */
fun bestTimeMs(repetitions: Int = 5, warmup: Int = 2, block: () -> Unit): Double =
    bestTimeMsWithClock(repetitions, warmup, System::nanoTime, block)

internal fun bestTimeMsWithClock(
    repetitions: Int,
    warmup: Int,
    nanoTime: () -> Long,
    block: () -> Unit,
): Double {
    require(repetitions > 0) { "repetitions must be positive" }
    require(warmup >= 0) { "warmup must be non-negative" }
    repeat(warmup) { block() }
    var best = Long.MAX_VALUE
    repeat(repetitions) {
        val start = nanoTime()
        block()
        best = minOf(best, nanoTime() - start)
    }
    return best / 1_000_000.0
}
