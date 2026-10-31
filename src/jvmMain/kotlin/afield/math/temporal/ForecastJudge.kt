package afield.math.temporal

import afield.math.oracle.ContinuationJudge
import afield.math.oracle.Trajectory
import afield.math.oracle.VariableMarkovOracle
import java.util.concurrent.CompletableFuture
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A forecaster used as a second opinion on a sequence memory's choices, and the one place where
 * the two ways of continuing a gesture meet.
 *
 * [ForecastJudge] implements `afield.math.oracle.ContinuationJudge` over any [TemporalSession]. It
 * watches every frame the improviser actually plays, asks the session for a fresh forecast at a
 * fixed interval in the background, and scores a proposed passage by its mean pinball loss
 * against that forecast's quantiles, per channel and divided by the channel's spread. The score
 * never reaches the output: the improviser still plays its memory, and the judge only changes
 * which part of it.
 *
 * What the score can and cannot say is set by the forecast it reads. The quantiles are per-step
 * marginals, so the loss asks whether each step of a passage sits inside the forecast's fan at
 * that step, not how likely the passage is as a whole. Until the first forecast arrives, and
 * wherever a passage runs past the end of the latest one, the judge returns no opinion and the
 * improviser decides alone. The first failure the session reports is recorded and no further
 * forecasts are requested.
 *
 * Forecasts are collected without blocking the frame that asked for them, so an interactive
 * session's timing does not depend on the model's latency, which the judge also records. For an
 * offline render that has to come out the same on any machine, `lockstep` waits for each request
 * instead.
 */

/**
 * A forecaster as a [ContinuationJudge]: the hybrid where the oracle proposes and the
 * forecaster judges.
 *
 * It keeps the last [history] seconds of what was actually played and, every [refresh]
 * seconds, asks [session] for a forecast [horizon] seconds ahead, in the background. A proposed
 * path is rated by its mean pinball loss against that forecast's quantiles: for each step,
 * channel and quantile level `q`, `max(q·e, (q−1)·e)` with `e = value − quantile`, divided by
 * the channel's spread. Averaged over the levels this approximates the continuous ranked
 * probability score — how well the path sits inside the forecast's fan, step by step.
 *
 * This is a heuristic. The quantiles are per-step marginals, so the score checks each step
 * against the model's uncertainty at that step; it is not the probability of the path as a
 * whole. Until the first forecast arrives, or where a path runs past the forecast, the judge
 * has no opinion and the improviser decides alone.
 *
 * With [lockstep] each request is waited for, so an offline render is the same on any machine.
 */
class ForecastJudge(
    val session: TemporalSession,
    val sampleRate: Double,
    val channels: List<String>,
    private val scale: DoubleArray,
    val history: Double = 20.0,
    val horizon: Double = 4.0,
    val refresh: Double = 1.0,
    val lockstep: Boolean = false,
) : ContinuationJudge, AutoCloseable {
    /** A judge for [oracle]'s channels, using its scaling. */
    constructor(session: TemporalSession, oracle: VariableMarkovOracle, history: Double = 20.0, horizon: Double = 4.0, refresh: Double = 1.0, lockstep: Boolean = false) :
        this(session, oracle.trajectory.sampleRate, oracle.trajectory.channels, oracle.features.scale, history, horizon, refresh, lockstep)

    private val width = channels.size
    private val historySteps = max(2, (history * sampleRate).roundToInt())
    private val horizonSteps = max(1, (horizon * sampleRate).roundToInt())
    private val refreshSteps = max(1, (refresh * sampleRate).roundToInt())
    private val minHistory = max(2, (2 * sampleRate).roundToInt())
    private val past = ArrayDeque<DoubleArray>()
    private var latest: Continuation? = null
    private var latestStart = 0L
    private var inflight: CompletableFuture<Continuation>? = null
    private var inflightStart = 0L
    private var sent = 0L

    /** Forecasts received. */
    var forecasts = 0; private set

    /** Request-to-reply latency of every forecast, in seconds. */
    val latencies = ArrayList<Double>()

    /** First failure reported by the session; the judge then has no opinion. */
    var failure: Throwable? = null; private set

    override fun observe(index: Long, frame: DoubleArray) {
        past.addLast(frame.copyOf())
        if (past.size > historySteps) past.removeFirst()
        collect(wait = false)
        if (inflight == null && failure == null && past.size >= minHistory && (latest == null || index + 1 - latestStart >= refreshSteps)) {
            val n = past.size
            val h = Trajectory(sampleRate, channels, Array(width) { c -> DoubleArray(n) { past[it][c] } }, name = "played")
            inflightStart = index + 1
            sent = System.nanoTime()
            inflight = try { session.propose(ContinuationRequest(0, h, horizonSteps)) } catch (e: Throwable) { failure = e; null }
            if (lockstep) collect(wait = true)
        }
    }

    private fun collect(wait: Boolean) {
        val f = inflight ?: return
        if (wait) runCatching { f.get() }
        if (!f.isDone) return
        inflight = null
        try { latest = f.get(); latestStart = inflightStart; forecasts++; latencies.add((System.nanoTime() - sent) / 1e9) }
        catch (e: Throwable) { failure = e.cause ?: e }
    }

    override fun loss(index: Long, path: Array<DoubleArray>): Double? {
        collect(wait = lockstep)
        val c = latest ?: return null
        val q = c.quantiles ?: return null
        val levels = c.quantileLevels ?: return null
        val k0 = (index - latestStart).toInt()
        var total = 0.0
        var count = 0
        for (t in path[0].indices) {
            val k = k0 + t
            if (k < 0 || k >= c.horizon) continue
            for (ch in 0 until width) {
                val y = path[ch][t]
                for (l in levels.indices) {
                    val e = y - q[ch][k][l]
                    total += max(levels[l] * e, (levels[l] - 1) * e) / scale[ch]
                }
                count += levels.size
            }
        }
        return if (count == 0) null else total / count
    }

    override fun close() { inflight?.cancel(true) }
}
