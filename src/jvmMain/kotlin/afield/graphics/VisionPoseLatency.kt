package afield.graphics

import afield.stdlib.prettySummary
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Where the time goes between a head moving and the resulting image reaching it.
 *
 * The plan asks for this before any optimisation, on the grounds that latency debugging becomes
 * archaeology otherwise. The point is not one number — it is **attribution**, because the plausible
 * explanations for "it feels laggy" are spread across five machines' worth of stages and at least two
 * of them are deliberate.
 *
 * ## Two clocks, and why the headline number needs neither reconciled
 *
 * The head's clock (`LayerRenderer.Clock`) and this process's [System.nanoTime] are unrelated. That
 * would normally make one-way timings unmeasurable. It does not here, because of a property of the
 * wire we already have: a pose's `predictedDisplayTime` is in the *head's* clock, and the media wire
 * carries it back to the head as `poseDisplayTimeNanos`. So the head can subtract two of its own
 * timestamps and get **motion-to-photon with no clock agreement at all**. That is why the headline
 * figure is measured on the device and reported here, rather than computed here.
 *
 * The offset is still wanted, for splitting the remainder into "our side" and "their side". It is
 * estimated NTP-style from [PING]/[PONG] round trips, keeping the sample with the **smallest** round
 * trip rather than an average: the minimum is the one least polluted by queuing in either direction,
 * and averaging RTTs on a contended Wi-Fi link estimates the contention, not the offset.
 *
 * ## What is deliberate, and must not be read as a fault
 *
 * The receiver runs a jitter buffer (`RTTStream.bufferFrames`) that holds frames on purpose so that
 * network jitter does not become dropped frames. It is reported as its own span. Without that split,
 * the buffer doing exactly its job looks like the network being slow, and the obvious "fix" —
 * shrinking it — trades smoothness for a better number.
 *
 * Likewise `HOST_SUBMIT_TO_ENCODED` includes the render fence: the producer only signals a surface
 * once its GPU work has completed, so scene complexity lands in that span rather than anywhere else.
 *
 * ## What this cannot see
 *
 * The device's last observable moment is the decoded frame reaching the display texture. Actual
 * photons are the compositor's business, up to one refresh later, and with the present path still on
 * RealityKit there is no hook for it. So the headline is a **lower bound**, short by up to one
 * compositor frame. That closes when the present path moves to Compositor Services, which is also
 * what makes reprojection possible; it is not worth faking before then.
 */
object VisionPoseLatency {

    const val PING = "visionpose/ping"
    const val PONG = "visionpose/pong"
    const val REPORT = "visionpose/latency"

    /** How many samples each span keeps. At 60 Hz this is about thirty seconds of history. */
    private const val WINDOW = 2048

    /** Spans this process can time on its own clock. */
    const val POSE_TRANSIT = "pose uplink transit"
    const val POSE_AGE_AT_LATCH = "pose age when latched"
    const val LATCH_TO_SUBMIT = "latch to frame submit"
    const val SUBMIT_TO_ENCODED = "submit to encoded (incl. render fence)"

    private val spans = ConcurrentHashMap<String, Span>()
    private val pings = ConcurrentHashMap<Long, Long>()
    private val pongCount = AtomicLong(0)

    /**
     * head clock ≈ this clock + [clockOffsetNanos]. Zero until a pong lands; [offsetKnown] says
     * which, because a zero offset is also a legitimate estimate and silently treating "unknown" as
     * "aligned" would report host-side spans as if the two machines shared a clock.
     */
    @Volatile
    var clockOffsetNanos: Long = 0L
        private set

    @Volatile
    var offsetKnown: Boolean = false
        private set

    /** Smallest round trip seen, the sample the offset is taken from. */
    @Volatile
    var bestRoundTripNanos: Long = Long.MAX_VALUE
        private set

    /** A bounded ring of samples with percentiles. Not a histogram: the tail is the interesting part. */
    private class Span(val name: String) {
        private val values = LongArray(WINDOW)
        private var count = 0
        private var next = 0

        @Synchronized
        fun add(nanos: Long) {
            values[next] = nanos
            next = (next + 1) % WINDOW
            if (count < WINDOW) count++
        }

        @Synchronized
        fun snapshot(): LongArray = values.copyOf(count).also { it.sort() }

        val n: Int @Synchronized get() = count
    }

    private fun span(name: String) = spans.computeIfAbsent(name) { Span(it) }

    fun record(name: String, nanos: Long) {
        // A negative span means the two ends disagree about order — a clock estimate that has moved,
        // or a frame attributed to the wrong pose. Counting it as zero would quietly flatter the
        // result, so drop it and let `n` fall short of the frame count instead.
        if (nanos >= 0) span(name).add(nanos)
    }

    // ---- clock offset ---------------------------------------------------------------------------

    /** Build a ping. Send it to a head; [acceptPong] closes the loop. */
    fun ping(): String {
        val sent = System.nanoTime()
        pings[sent] = sent
        // Trim rather than grow if a head never answers.
        if (pings.size > 64) pings.keys.sorted().take(pings.size - 64).forEach { pings.remove(it) }
        return """{"channel":"$VISION_POSE_CHANNEL","type":"$PING","version":$VISION_POSE_VERSION,""" +
            """"hostSendNanos":$sent}"""
    }

    /**
     * Close a round trip.
     *
     * `rtt = (hostRecv - hostSend) - (headSend - headRecv)`, so the head's own processing delay is
     * removed rather than being charged to the network. The offset is the midpoint of the two
     * crossings, which assumes the path is symmetric — the standard assumption, and the reason to
     * keep the minimum-RTT sample where it is least wrong.
     */
    fun acceptPong(hostSendNanos: Long, headRecvNanos: Long, headSendNanos: Long) {
        val hostRecv = System.nanoTime()
        pings.remove(hostSendNanos) ?: return   // not ours, or already retired
        val headHold = headSendNanos - headRecvNanos
        val roundTrip = (hostRecv - hostSendNanos) - headHold
        if (roundTrip < 0) return
        pongCount.incrementAndGet()
        if (roundTrip < bestRoundTripNanos) {
            bestRoundTripNanos = roundTrip
            clockOffsetNanos = ((headRecvNanos - hostSendNanos) + (headSendNanos - hostRecv)) / 2
            offsetKnown = true
        }
    }

    /** Convert a head-clock instant into this process's clock. Only meaningful once [offsetKnown]. */
    fun headToHostNanos(headNanos: Long): Long = headNanos - clockOffsetNanos

    // ---- per-frame host bookkeeping -------------------------------------------------------------

    private class InFlight(val poseArrivalNanos: Long, val poseSentHeadNanos: Long, val latchNanos: Long)

    private val inFlight = ConcurrentHashMap<Long, InFlight>()

    /**
     * A stereo target latched [pose] and is about to render stream frame [seq].
     *
     * Called from the render thread once per streamed frame.
     */
    fun latched(seq: Long, pose: VisionPoseFrame) {
        val now = System.nanoTime()
        record(POSE_AGE_AT_LATCH, now - pose.receivedAtNanos)
        if (offsetKnown) {
            // The head stamped `sentAt` in its clock; bring it here and the uplink's own transit
            // falls out. Without the offset this is unknowable, hence the guard.
            record(POSE_TRANSIT, pose.receivedAtNanos - headToHostNanos((pose.sentAt * 1e9).toLong()))
        }
        inFlight[seq] = InFlight(pose.receivedAtNanos, (pose.sentAt * 1e9).toLong(), now)
        if (inFlight.size > 256) inFlight.keys.sorted().take(inFlight.size - 256).forEach { inFlight.remove(it) }
    }

    /** The producer has signalled the surface for [seq] to the encoder. */
    fun submitted(seq: Long) {
        val f = inFlight[seq] ?: return
        record(LATCH_TO_SUBMIT, System.nanoTime() - f.latchNanos)
        submitNanos[seq] = System.nanoTime()
        if (submitNanos.size > 256) submitNanos.keys.sorted().take(submitNanos.size - 256).forEach { submitNanos.remove(it) }
    }

    private val submitNanos = ConcurrentHashMap<Long, Long>()

    /** The helper reported `{"ev":"encoded","seq":…}`. */
    fun encoded(seq: Long) {
        val submitted = submitNanos.remove(seq) ?: return
        record(SUBMIT_TO_ENCODED, System.nanoTime() - submitted)
        inFlight.remove(seq)
    }

    // ---- the device's half ----------------------------------------------------------------------

    /** Spans the head measured on its own clock, newest report wins. */
    @Volatile
    var deviceSpans: Map<String, DeviceSpan> = emptyMap()
        private set

    @Volatile
    var deviceReportCount: Long = 0L
        private set

    data class DeviceSpan(val p50Millis: Double, val p95Millis: Double, val maxMillis: Double, val n: Int)

    fun acceptDeviceReport(spansByName: Map<String, DeviceSpan>) {
        deviceSpans = spansByName
        deviceReportCount++
    }

    // ---- readout --------------------------------------------------------------------------------

    private fun percentileMillis(sorted: LongArray, fraction: Double): Double {
        if (sorted.isEmpty()) return Double.NaN
        val index = ((sorted.size - 1) * fraction).toInt().coerceIn(0, sorted.size - 1)
        return sorted[index] / 1.0e6
    }

    private fun row(label: String, p50: Double, p95: Double, max: Double, n: Int): String =
        "  %-38s p50 %7.2f  p95 %7.2f  max %7.2f  n=%d".format(label, p50, p95, max, n)

    /**
     * The whole chain, host spans then device spans, with the headline last.
     *
     * Ordered as the frame travels rather than by magnitude, so a reader can see *where* rather than
     * just *how much*. Milliseconds throughout.
     */
    fun report(): String = buildString {
        appendLine("vision pose/stream latency")
        val offset = if (offsetKnown)
            "head clock = host clock %+.3f ms (best RTT %.2f ms, %d pong%s)".format(
                clockOffsetNanos / 1.0e6, bestRoundTripNanos / 1.0e6,
                pongCount.get(), if (pongCount.get() == 1L) "" else "s")
        else "clock offset UNKNOWN — no pong yet; host/device spans cannot be compared"
        appendLine("  $offset")
        appendLine("  --- this machine ---")
        listOf(POSE_TRANSIT, POSE_AGE_AT_LATCH, LATCH_TO_SUBMIT, SUBMIT_TO_ENCODED).forEach { name ->
            val s = spans[name]
            if (s == null || s.n == 0) {
                appendLine("  %-38s (no samples)".format(name))
            } else {
                val sorted = s.snapshot()
                appendLine(row(name, percentileMillis(sorted, 0.5), percentileMillis(sorted, 0.95),
                               percentileMillis(sorted, 1.0), sorted.size))
            }
        }
        appendLine("  --- the head ---")
        if (deviceSpans.isEmpty()) {
            appendLine("  (no report from the head yet)")
        } else {
            deviceSpans.forEach { (name, d) ->
                appendLine(row(name, d.p50Millis, d.p95Millis, d.maxMillis, d.n))
            }
        }
        append("  note: the head's last observable moment is the texture upload, so any ")
        append("motion-to-photon figure above is short by up to one compositor frame.")
    }

    /** Forget everything. Test seam, and useful before a measurement run. */
    fun reset() {
        spans.clear()
        pings.clear()
        inFlight.clear()
        submitNanos.clear()
        deviceSpans = emptyMap()
        deviceReportCount = 0
        pongCount.set(0)
        clockOffsetNanos = 0
        offsetKnown = false
        bestRoundTripNanos = Long.MAX_VALUE
    }

    override fun toString(): String = prettySummary(
        "VisionPoseLatency",
        "offsetKnown" to offsetKnown,
        "bestRoundTripMs" to if (bestRoundTripNanos == Long.MAX_VALUE) null else bestRoundTripNanos / 1.0e6,
        "deviceReports" to deviceReportCount,
    )
}
