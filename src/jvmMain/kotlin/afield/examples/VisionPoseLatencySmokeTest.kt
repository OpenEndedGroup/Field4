package afield.examples

import afield.graphics.VISION_POSE_CHANNEL
import afield.graphics.VISION_POSE_VERSION
import afield.graphics.VisionPoseLatency
import afield.graphics.VisionPoseStream
import afield.graphics.installVisionPoseHandler
import afield.graphics.visionPoseRawHandler
import kotlin.math.abs

/*
FIELD_TEST
id: visionpose.latency
runner: jvm-main
entrypoint: afield.examples.VisionPoseLatencySmokeTest
tags: [smoke, visionos, pose, latency]
timeoutSec: 90
expect:
  exitCode: 0
  stdoutContains:
    - "VisionPoseLatencySmokeTest ok"
*/

/**
 * Guards the clock-offset arithmetic, which is the part of the latency measurement that cannot be
 * checked by looking at it.
 *
 * A wrong offset does not fail — it produces a *plausible* latency, off by whatever the two machines'
 * clocks happen to differ by. The number would then be wrong in the one direction nobody
 * double-checks: believable. So the estimator is driven against a synthetic head whose true offset and
 * true one-way delays are known, and the recovered offset is compared with the truth.
 *
 * Also pinned: that the estimator prefers the least-contended sample rather than the most recent, and
 * that "no pong yet" stays distinguishable from "the offset is zero".
 */
object VisionPoseLatencySmokeTest {

    /** The truth the synthetic head is built from. Recovered, not assumed. */
    private const val TRUE_OFFSET_NANOS = 1_234_567_890_000L
    private const val MILLIS = 1_000_000L

    @JvmStatic
    fun main(args: Array<String>) {
        installVisionPoseHandler()
        val handler = visionPoseRawHandler() ?: error("no handler registered")

        VisionPoseLatency.reset()
        VisionPoseStream.reset()

        // ---- nothing measured yet ---------------------------------------------------------------
        require(!VisionPoseLatency.offsetKnown) { "the offset must start unknown" }
        require(VisionPoseLatency.clockOffsetNanos == 0L) { "unknown must not masquerade as an offset" }
        require(VisionPoseLatency.report().contains("UNKNOWN")) {
            "a report with no pong must say so rather than print a zero offset as fact"
        }

        // ---- a symmetric round trip recovers the offset ------------------------------------------
        // Several samples, not one, because that is how the estimator is meant to be used: the
        // *first* round trip through this path pays cold class-loading and JSON-parser init inside
        // the measured window, and lands ~200 ms late. That is not a flaw in the estimator, it is the
        // reason it keeps the minimum — but it does mean a single-sample assertion here would be
        // testing the JVM's warm-up, and would have been "fixed" by loosening the tolerance until it
        // no longer checked anything.
        repeat(6) { pong(handler, oneWayNanos = 5 * MILLIS, holdNanos = 2 * MILLIS) }
        require(VisionPoseLatency.offsetKnown) { "a pong must establish the offset" }
        val error = abs(VisionPoseLatency.clockOffsetNanos - TRUE_OFFSET_NANOS)
        // 20 ms is loose against the 5 ms path but tight against every structural error this can have:
        // a sign slip is out by twice the offset (~41 minutes), a missing halving by the offset itself,
        // and swapping the head's two instants by the hold. Host scheduling noise is what is left.
        require(error < 20 * MILLIS) {
            "recovered offset is off by ${error / 1e6} ms — expected ~0 on a symmetric path"
        }

        // The head's own hold must not be charged to the network.
        val rtt = VisionPoseLatency.bestRoundTripNanos
        require(abs(rtt - 10 * MILLIS) < 12 * MILLIS) {
            "round trip should be ~10 ms (two 5 ms crossings, hold excluded), got ${rtt / 1e6} ms"
        }

        // ---- a congested sample must not displace a clean one -----------------------------------
        val cleanOffset = VisionPoseLatency.clockOffsetNanos
        pong(handler, oneWayNanos = 150 * MILLIS, holdNanos = 1 * MILLIS)
        require(VisionPoseLatency.clockOffsetNanos == cleanOffset) {
            "a slower round trip replaced the offset: the estimator must keep the minimum-RTT sample, " +
                "or a contended link estimates the contention rather than the offset"
        }

        // ---- refusals ---------------------------------------------------------------------------
        val before = VisionPoseLatency.bestRoundTripNanos
        handler(pongJson(hostSend = 42L, headRecv = 1L, headSend = 2L))
        require(VisionPoseLatency.bestRoundTripNanos == before) {
            "an unsolicited pong must be ignored — otherwise anything on the bus can move the clock"
        }

        // ---- the device's half round-trips through the wire --------------------------------------
        handler(latencyJson())
        val spans = VisionPoseLatency.deviceSpans
        require(spans.size == 2) { "expected two device spans, got ${spans.keys}" }
        val jitter = spans["head: jitter buffer hold (deliberate)"]
            ?: error("the jitter-buffer span did not survive the wire: ${spans.keys}")
        require(abs(jitter.p50Millis - 33.4) < 0.01) { "p50 lost: ${jitter.p50Millis}" }
        require(jitter.n == 900) { "sample count lost: ${jitter.n}" }

        // ---- host spans are recorded and summarised ----------------------------------------------
        VisionPoseLatency.record(VisionPoseLatency.LATCH_TO_SUBMIT, 4 * MILLIS)
        VisionPoseLatency.record(VisionPoseLatency.LATCH_TO_SUBMIT, 8 * MILLIS)
        VisionPoseLatency.record(VisionPoseLatency.SUBMIT_TO_ENCODED, 11 * MILLIS)
        // A negative span is a mis-attribution, and must be dropped rather than counted as zero —
        // counting it would quietly pull every percentile down.
        VisionPoseLatency.record(VisionPoseLatency.SUBMIT_TO_ENCODED, -5 * MILLIS)

        // ---- the readout names every stage, including what it cannot see -------------------------
        val report = VisionPoseLatency.report()
        listOf(
            VisionPoseLatency.POSE_AGE_AT_LATCH,
            VisionPoseLatency.LATCH_TO_SUBMIT,
            VisionPoseLatency.SUBMIT_TO_ENCODED,
            "head: jitter buffer hold (deliberate)",
            "compositor frame",
        ).forEach { needle ->
            require(report.contains(needle)) { "the report omits '$needle':\n$report" }
        }
        require(!report.contains("UNKNOWN")) { "the offset is known by now" }
        require(Regex("""submit to encoded[^\n]*n=1""").containsMatchIn(report)) {
            "the negative span was counted; it must be dropped:\n$report"
        }

        println(report)
        println("VisionPoseLatencySmokeTest ok")
    }

    /**
     * Drive one full round trip against a synthetic head with a known offset.
     *
     * The host's send instant is read back out of the ping it just built, so this exercises the real
     * `ping()`/`acceptPong()` pair rather than a re-implementation of the protocol. The delay is
     * actually slept, because the host stamps arrival from its own clock — fabricating that instant
     * would only test the arithmetic against itself.
     */
    private fun pong(handler: (String) -> Any?, oneWayNanos: Long, holdNanos: Long) {
        val ping = VisionPoseLatency.ping()
        val hostSend = Regex(""""hostSendNanos":(\d+)""").find(ping)?.groupValues?.get(1)?.toLong()
            ?: error("ping() did not carry a host send timestamp: $ping")
        val headRecv = hostSend + TRUE_OFFSET_NANOS + oneWayNanos
        val headSend = headRecv + holdNanos
        val total = oneWayNanos * 2 + holdNanos
        Thread.sleep(total / 1_000_000, (total % 1_000_000).toInt())
        handler(pongJson(hostSend, headRecv, headSend))
    }

    private fun pongJson(hostSend: Long, headRecv: Long, headSend: Long) =
        """{"channel":"$VISION_POSE_CHANNEL","type":"${VisionPoseLatency.PONG}",""" +
            """"version":$VISION_POSE_VERSION,"hostSendNanos":$hostSend,""" +
            """"headRecvNanos":$headRecv,"headSendNanos":$headSend}"""

    private fun latencyJson() = """
        {"channel":"$VISION_POSE_CHANNEL","type":"${VisionPoseLatency.REPORT}",
         "version":$VISION_POSE_VERSION,"device":"test-head",
         "spans":{"head: jitter buffer hold (deliberate)":{"p50Millis":33.4,"p95Millis":50.1,"maxMillis":66.8,"n":900},
                  "head: POSE to texture (motion-to-photon lower bound)":{"p50Millis":72.5,"p95Millis":98.2,"maxMillis":140.0,"n":900}}}
    """.trimIndent()
}
