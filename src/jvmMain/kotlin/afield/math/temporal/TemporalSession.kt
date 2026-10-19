@file:afield.runtime.completion.CompletionCategory("Math")

package afield.math.temporal

import afield.math.oracle.Improviser
import afield.math.oracle.Trajectory
import afield.math.oracle.VariableMarkovOracle
import java.util.concurrent.CompletableFuture
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Continuing a multichannel time series live, from any backend that can propose a future.
 *
 * A [TemporalSession] answers [ContinuationRequest]s (recent history in, proposed future out)
 * asynchronously. [RecedingHorizon] turns a session into something a frame loop can step
 * without ever waiting: it keeps a buffer of planned samples, commits only a short prefix of
 * each plan, replans in the background, drops replies made stale by a takeover, and reports
 * underruns instead of extrapolating. Two sessions ship: [OracleSession] (explicit sequence
 * memory, `afield.math.oracle`) and [TimestralSession] (Mistral's pretrained forecaster, in a
 * separate Python worker). Tests: `RecedingHorizonTest`.
 */

/** What a [TemporalSession] can do, declared rather than discovered. */
data class TemporalCapabilities(
    /** Returns a quantile envelope around the central proposal. */
    val quantileForecast: Boolean = false,
    /** Can return several distinct alternatives (not quantiles). */
    val sampledAlternatives: Boolean = false,
    /** Accepts covariates known over the future horizon. */
    val futureCovariates: Boolean = false,
    /** Accepts history with individual samples marked missing. */
    val maskedHistory: Boolean = false,
    /** Can add new demonstrations while running. */
    val onlineMemoryAppend: Boolean = false,
)

/**
 * A request for [horizon] samples continuing [history]. [revision] identifies the history the
 * request was made from; [owned] marks performer-driven channels (`owned[channel]`), and
 * [known] carries covariates over history + horizon for a session with `futureCovariates`.
 */
class ContinuationRequest(
    val revision: Long,
    val history: Trajectory,
    val horizon: Int,
    val owned: BooleanArray = BooleanArray(history.width),
    val known: Array<DoubleArray>? = null,
)

/**
 * A proposed future: [proposal]`[channel][t]` for `t` in `0 until horizon`, starting at the
 * sample after the request's history. [quantiles]`[channel][t][q]` at [quantileLevels] when the
 * session provides them — an uncertainty display per sample, not a set of joint performances.
 * [diagnostics] is backend-specific (latency, source positions, model identity).
 */
class Continuation(
    val revision: Long,
    val proposal: Array<DoubleArray>,
    val quantiles: Array<Array<DoubleArray>>? = null,
    val quantileLevels: DoubleArray? = null,
    val diagnostics: Map<String, Any?> = emptyMap(),
) {
    val horizon: Int get() = proposal[0].size
}

/** A backend that proposes continuations. Replies may arrive on any thread. */
interface TemporalSession : AutoCloseable {
    val capabilities: TemporalCapabilities
    fun propose(request: ContinuationRequest): CompletableFuture<Continuation>
    override fun close() {}
}

/**
 * The oracle as a [TemporalSession]: cue the memory with the request's history (only the
 * performer's channels, if any are owned), then let an [Improviser] play forward from the best
 * match for the horizon. Diagnostics carry `source` (memory sample per proposed sample) and
 * `cueDistance`. Proposals are computed on the caller's thread and complete immediately.
 */
class OracleSession(val oracle: VariableMarkovOracle, private var seed: Long = 1, val configure: (VariableMarkovOracle, Long, Int) -> Improviser = { o, s, start -> Improviser(o, seed = s, start = start) }) : TemporalSession {
    override val capabilities = TemporalCapabilities(sampledAlternatives = true)

    override fun propose(request: ContinuationRequest): CompletableFuture<Continuation> {
        val h = request.history
        val recent = (max(0, h.length - (h.sampleRate * 2).roundToInt()) until h.length).map { h.frame(it) }
        val mask = if (request.owned.any { it }) request.owned else BooleanArray(h.width) { true }
        val cue = oracle.cue(recent, mask = mask, count = 1).firstOrNull()
        val start = cue?.let { min(it.position + 1, oracle.trajectory.length - 1) } ?: 0
        val p = configure(oracle, seed++, start).take(request.horizon)
        return CompletableFuture.completedFuture(Continuation(request.revision, p.output.values, diagnostics = mapOf("source" to p.source, "cueDistance" to cue?.distance, "segments" to p.segments.size)))
    }
}

/** What [RecedingHorizon] does when the plan runs out before a new one arrives. */
enum class Underflow { Hold, Stop }

/**
 * Plays a [TemporalSession] in a frame loop without blocking on it.
 *
 * Each [step] returns the next sample from the current plan. When [commit] seconds of a plan
 * have been played it asks for a new one from the actual played history (the last [history]
 * seconds) for [horizon] seconds ahead; one request is in flight at a time, and the newest
 * history is used when the next is sent. A reply whose revision is older than the current one
 * — a takeover, release or [reset] happened while it was computing — is discarded.
 *
 * Performer-owned channels ([perform]) play the performer's values and are part of the history
 * the session sees. [release] hands a channel back with a fade over [releaseTime] seconds; the
 * fade size is reported in [releaseCorrection]. If the plan runs out, [underflow] decides
 * between holding the last frame and stopping; either way [underruns] counts it.
 *
 * With [lockstep] the player waits for each reply: for offline renders and docs captures, where
 * results must not depend on machine speed. Live use leaves it false.
 */
class RecedingHorizon(
    val session: TemporalSession,
    seed: Trajectory,
    val history: Double = 10.0,
    val horizon: Double = 4.0,
    val commit: Double = 1.0,
    val releaseTime: Double = 0.3,
    val underflow: Underflow = Underflow.Hold,
    val lockstep: Boolean = false,
) : AutoCloseable {
    private val rate = seed.sampleRate
    private val channels = seed.channels
    private val width = seed.width
    private val historySteps = max(2, (history * rate).roundToInt())
    private val horizonSteps = max(1, (horizon * rate).roundToInt())
    private val commitSteps = max(1, min(horizonSteps, (commit * rate).roundToInt()))
    private val releaseSteps = max(1, (releaseTime * rate).roundToInt())

    private val past = ArrayDeque<DoubleArray>()
    private var index = 0L
    private var plan: Continuation? = null
    private var planStart = 0L
    private var inflight: CompletableFuture<Continuation>? = null
    private var inflightStart = 0L
    private var inflightSent = 0L
    private val owned = BooleanArray(width)
    private val performed = DoubleArray(width)
    private val fade = DoubleArray(width)
    private val fadeLeft = IntArray(width)

    /** Bumped by every takeover, release and reset; replies from older revisions are dropped. */
    @Volatile var revision = 0L; private set

    /** Steps on which no planned sample was available. */
    var underruns = 0; private set

    /** Replies discarded as stale. */
    var staleReplies = 0; private set

    /** Request-to-reply latency of every accepted plan, in seconds. */
    val latencies = ArrayList<Double>()

    /** The most recent accepted plan, for plotting its envelope. */
    val currentPlan: Continuation? get() = plan

    /** Output sample index at which [currentPlan] starts. */
    val currentPlanStart: Long get() = planStart

    /** Size of the fade applied on the last step (scaled by channel spread), 0 when none. */
    var releaseCorrection = 0.0; private set

    /** First failure reported by the session, if any; the player then follows [underflow]. */
    var failure: Throwable? = null; private set

    init {
        for (t in max(0, seed.length - historySteps) until seed.length) past.addLast(seed.frame(t))
        request()
    }

    fun perform(channel: Int, value: Double) {
        if (!owned[channel]) { owned[channel] = true; revision++ }
        performed[channel] = value
    }

    fun perform(name: String, value: Double) = perform(channels.indexOf(name), value)

    fun release(channel: Int) {
        if (!owned[channel]) return
        owned[channel] = false; revision++
        fade[channel] = Double.NaN; fadeLeft[channel] = releaseSteps
        request(force = true)
    }

    fun release(name: String) = release(channels.indexOf(name))

    /** Drops the plan and anything in flight; the next step replans from the current history. */
    fun reset() { revision++; plan = null; request(force = true) }

    private fun historyTrajectory(): Trajectory {
        val n = past.size
        return Trajectory(rate, channels, Array(width) { c -> DoubleArray(n) { past[it][c] } }, name = "played")
    }

    private fun request(force: Boolean = false) {
        if (inflight != null && !force) return
        val req = ContinuationRequest(revision, historyTrajectory(), horizonSteps, owned.copyOf())
        inflightStart = index
        inflightSent = System.nanoTime()
        inflight = try { session.propose(req) } catch (e: Throwable) { failure = e; null }
    }

    private fun collect() {
        val f = inflight ?: return
        if (lockstep && !f.isDone) runCatching { f.get() }
        if (!f.isDone) return
        inflight = null
        val c = try { f.get() } catch (e: Throwable) { failure = e.cause ?: e; return }
        if (c.revision != revision) { staleReplies++; request(); return }
        latencies.add((System.nanoTime() - inflightSent) / 1e9)
        plan = c; planStart = inflightStart
    }

    /** Advances one sample; never blocks unless [lockstep]. */
    fun step(): DoubleArray {
        collect()
        val last = past.lastOrNull() ?: DoubleArray(width)
        val p = plan
        val k = (index - planStart).toInt()
        val raw: DoubleArray? = if (p != null && k in 0 until p.horizon) DoubleArray(width) { p.proposal[it][k] } else null
        val out = DoubleArray(width)
        if (raw == null) {
            underruns++
            if (underflow == Underflow.Stop) { for (c in 0 until width) out[c] = if (owned[c]) performed[c] else Double.NaN }
            else for (c in 0 until width) out[c] = if (owned[c]) performed[c] else last[c]
        } else {
            var mag = 0.0
            for (c in 0 until width) {
                if (owned[c]) { out[c] = performed[c]; continue }
                if (fade[c].isNaN()) fade[c] = performed[c] - raw[c]
                val o = if (fadeLeft[c] > 0) fade[c] * fadeLeft[c]-- / releaseSteps else 0.0
                out[c] = raw[c] + o; mag += o * o
            }
            releaseCorrection = kotlin.math.sqrt(mag)
        }
        // a stopped channel is NaN on output, but the history keeps its last real value
        past.addLast(DoubleArray(width) { if (out[it].isNaN()) last[it] else out[it] })
        if (past.size > historySteps) past.removeFirst()
        index++
        if (inflight == null && (plan == null || index - planStart >= commitSteps)) request()
        return out
    }

    /** Runs [samples] steps (with an optional per-step [script]) and returns what was played. */
    fun take(samples: Int, script: (RecedingHorizon.(Int) -> Unit)? = null): Played {
        val out = Array(width) { DoubleArray(samples) }
        val own = Array(width) { BooleanArray(samples) }
        val bands = Array(width) { Array(2) { DoubleArray(samples) { Double.NaN } } }
        for (i in 0 until samples) {
            script?.invoke(this, i)
            val f = step()
            val p = plan
            val k = (index - 1 - planStart).toInt()
            for (c in 0 until width) {
                out[c][i] = f[c]; own[c][i] = owned[c]
                val q = p?.quantiles
                if (q != null && k in 0 until p.horizon) { bands[c][0][i] = q[c][k].first(); bands[c][1][i] = q[c][k].last() }
            }
        }
        return Played(Trajectory(rate, channels, out, name = "played"), own, bands, underruns, staleReplies, latencies.toList())
    }

    fun take(seconds: Double, script: (RecedingHorizon.(Int) -> Unit)? = null) = take((seconds * rate).roundToInt(), script)

    override fun close() { inflight?.cancel(true); session.close() }
}

/**
 * What a [RecedingHorizon] played. [band]`[channel][0|1][t]` is the outer quantile pair of the
 * plan each sample came from (NaN without quantiles) — the forecaster's uncertainty at that
 * sample, not a bound on the output.
 */
class Played(
    val output: Trajectory,
    val owned: Array<BooleanArray>,
    val band: Array<Array<DoubleArray>>,
    val underruns: Int,
    val staleReplies: Int,
    val latencies: List<Double>,
) {
    /** The [p]th percentile of [latencies], in seconds. */
    fun latency(p: Double): Double = if (latencies.isEmpty()) Double.NaN else latencies.sorted()[min(latencies.size - 1, (p / 100 * latencies.size).toInt())]
}

/**
 * Index of the quantile level nearest [q] (`0.5` is the median). Throws without quantiles.
 */
fun Continuation.quantileIndex(q: Double): Int {
    val levels = quantileLevels ?: error("this continuation has no quantiles")
    return levels.indices.minBy { kotlin.math.abs(levels[it] - q) }
}

/** The proposal on [channel] drawn as y(x), with [xs] (one x per horizon step) — the thick line of a forecast plot. */
fun Continuation.medianFLine(channel: Int, xs: DoubleArray): afield.geometry.path.FLine =
    afield.geometry.path.FLine().also { out -> for (t in 0 until horizon) if (t == 0) out.moveTo(xs[t], proposal[channel][t]) else out.lineTo(xs[t], proposal[channel][t]) }

/** The [q] quantile on [channel] drawn as y(x). */
fun Continuation.quantileFLine(channel: Int, q: Double, xs: DoubleArray): afield.geometry.path.FLine {
    val k = quantileIndex(q)
    val qs = quantiles!![channel]
    return afield.geometry.path.FLine().also { out -> for (t in 0 until horizon) if (t == 0) out.moveTo(xs[t], qs[t][k]) else out.lineTo(xs[t], qs[t][k]) }
}

/**
 * The region between the [lower] and [upper] quantiles on [channel], as one closed FLine ready to
 * fill. Nest several (`0.01..0.99`, `0.1..0.9`, `0.25..0.75`) for a fan chart. Like a Gaussian
 * process's interval, each band is pointwise: it describes one step at a time, and no single
 * performance is expected to stay inside it.
 */
fun Continuation.bandFLine(channel: Int, lower: Double, upper: Double, xs: DoubleArray): afield.geometry.path.FLine {
    val lo = quantileIndex(lower); val hi = quantileIndex(upper)
    val qs = quantiles!![channel]
    return afield.geometry.path.FLine().also { out ->
        for (t in 0 until horizon) if (t == 0) out.moveTo(xs[t], qs[t][hi]) else out.lineTo(xs[t], qs[t][hi])
        for (t in horizon - 1 downTo 0) out.lineTo(xs[t], qs[t][lo])
        out.lineTo(xs[0], qs[0][hi])
    }
}
