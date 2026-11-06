package afield.graphics.vulkan.targets

/** Queue-thread-owned, bounded cadence history. Runtime times are only subtracted from runtime times. */
internal class OpenXRFrameCadence(private val capacity: Int = 500) {
    init { require(capacity > 0) }

    private val submissions = LongArray(capacity)
    private val predictions = LongArray(capacity)
    private val periods = LongArray(capacity)
    private var next = 0
    private var count = 0
    private var previousSubmission = 0L
    private var previousPrediction = 0L
    private var lastReport = 0L

    data class Snapshot(
        val samples: Int,
        val submissionMs: Map<Long, Int>,
        val predictionMs: Map<Long, Int>,
        val periodMs: Map<Long, Int>,
    ) {
        fun summary(): String {
            fun bins(values: Map<Long, Int>) = values.entries
                .sortedWith(compareByDescending<Map.Entry<Long, Int>> { it.value }.thenBy { it.key })
                .take(6).joinToString(",") { "${it.key}..${it.key + 1}ms:${it.value}" }
            return "n=$samples submission={${bins(submissionMs)}} predicted_step={${bins(predictionMs)}} " +
                "runtime_period={${bins(periodMs)}} (top 6 bins; rolling image-bearing submissions)"
        }
    }

    /** Empty/failed submissions break continuity; sleeping time must not look like missed refreshes. */
    fun record(displayTime: Long, displayPeriod: Long, submittedAt: Long, hasLayer: Boolean) {
        if (!hasLayer || displayPeriod <= 0) {
            previousSubmission = 0L
            previousPrediction = 0L
            return
        }
        val wallStep = submittedAt - previousSubmission
        val predictionStep = displayTime - previousPrediction
        if (previousSubmission != 0L && wallStep > 0 && predictionStep > 0 && wallStep < 1_000_000_000L) {
            submissions[next] = wallStep
            predictions[next] = predictionStep
            periods[next] = displayPeriod
            next = (next + 1) % capacity
            count = minOf(count + 1, capacity)
        }
        previousSubmission = submittedAt
        previousPrediction = displayTime
    }

    fun snapshot(): Snapshot {
        fun bins(values: LongArray) = (0 until count).groupingBy { values[it] / 1_000_000L }.eachCount()
        return Snapshot(count, bins(submissions), bins(predictions), bins(periods))
    }

    /** Build/report histograms only every five seconds, rather than allocating on each frame. */
    fun reportIfDue(now: Long): Snapshot? {
        if (lastReport == 0L) { lastReport = now; return null }
        if (now - lastReport < 5_000_000_000L) return null
        lastReport = now
        return if (count > 0) snapshot() else null
    }
}
