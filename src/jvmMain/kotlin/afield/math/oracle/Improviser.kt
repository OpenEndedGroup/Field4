package afield.math.oracle

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** How an [Improviser] chooses where to go next. */
enum class Policy {
    /** Play each episode straight through and start it again: the baseline that never varies. */
    Loop,

    /** Jump to whatever earlier passage best matches the recent output, with no oracle structure. */
    NearestHistory,

    /** Follow the recording and jump along the oracle's suffix links, which share context. */
    Oracle,
}

/** Why a [Segment] began. */
enum class Reason { Start, Continue, SuffixLink, EpisodeEnd, Cue, Takeover, Hold }

/**
 * One stretch of output played from one place in memory. [outputStart] is the output sample it
 * began on; [source] the memory sample it began from; [episode] which demonstration that is.
 * [distance] is the join distance (feature units) that admitted the jump, [context] the shared
 * context length in samples, and [correction] the size of the join correction it needed.
 */
data class Segment(val outputStart: Int, val source: Int, val episode: Int, val reason: Reason, val distance: Double, val context: Int, val correction: Double)

/**
 * What an improviser played: the output itself, and for every output sample the memory sample
 * it came from, the size of the join correction applied, and which channels a performer owned.
 */
class Performance(
    val output: Trajectory,
    /** Memory sample per output sample (`-1` while holding). */
    val source: IntArray,
    /** Join-correction magnitude per output sample, in scaled units (0 = played as recorded). */
    val correction: DoubleArray,
    /** `owned[channel][t]`: true where the performer drove that channel. */
    val owned: Array<BooleanArray>,
    val segments: List<Segment>,
) {
    /** The source trace in seconds of memory, ready to plot: a staircase of straight runs and jumps. */
    fun sourceSeconds(): List<Double> = source.map { if (it < 0) Double.NaN else it / output.sampleRate }

    /** Fraction of output samples whose source passage was played at least once before within [window] samples. */
    fun reuse(window: Int = (output.sampleRate * 10).toInt()): Double {
        var r = 0
        for (t in source.indices) {
            val s = source[t]; if (s < 0) continue
            for (u in max(0, t - window) until t - 1) if (source[u] == s) { r++; break }
        }
        return r.toDouble() / max(1, source.size)
    }

    /** Fraction of memory samples that appear somewhere in the output. */
    fun coverage(memoryLength: Int): Double = source.filter { it >= 0 }.toSet().size.toDouble() / memoryLength

    override fun toString() = "Performance(${output.length} samples, ${segments.size} segments, reuse=${"%.2f".format(reuse())})"
}

/**
 * Plays an [oracle]'s memory forward one sample at a time, recombining it.
 *
 * By default it continues along the current source passage. Once it has stayed for at least
 * [minPhrase] seconds it may, with probability [jumpProbability] per sample, take an oracle
 * jump: to a state that shares a suffix with the current one. Candidates are scored by shared
 * context length, by the join distance (how far the candidate's preceding sample is from where
 * the output is now, value and velocity), and by how recently their passage was played; one is
 * sampled from a seeded softmax with [temperature]. A candidate whose join distance exceeds
 * [maxJoin] (a multiple of the oracle threshold), or whose join would need a correction larger
 * than [maxCorrection] (in channel standard deviations), is rejected rather than smoothed over. At the
 * end of an episode a jump is compulsory; if no candidate is acceptable it falls back to
 * matching recent history, and if that is worse than [declineDistance] it holds.
 *
 * Every jump is joined by a correction that starts at the discontinuity and fades over
 * [joinTime] seconds; its magnitude is recorded in [Performance.correction], not hidden.
 *
 * [perform] hands a channel to the performer: the improviser plays the performer's values on it
 * and, every [cueInterval] seconds, realigns the free channels by cueing the memory with the
 * performed channels. It only moves after [minPhrase] seconds on a passage, and only to a match
 * whose distance is below [takeoverMargin] times the current passage's, so an input unlike
 * anything in memory leaves the free channels playing on rather than jumping around.
 * [release] gives the channel back with the same fading correction.
 *
 * Stepping is cheap (no allocation beyond the recent-history buffer, a cue every few samples
 * while performing), so [step] can run directly in a frame loop.
 */
class Improviser(
    val oracle: VariableMarkovOracle,
    seed: Long = 1,
    val policy: Policy = Policy.Oracle,
    val minPhrase: Double = 1.0,
    val jumpProbability: Double = 0.2,
    val maxJoin: Double = 2.0,
    val temperature: Double = 1.0,
    val joinTime: Double = 0.15,
    val cueWindow: Double = 1.0,
    val cueInterval: Double = 0.25,
    val declineDistance: Double = 1.5,
    val maxCorrection: Double = 1.0,
    val takeoverMargin: Double = 0.7,
    start: Int = 0,
) {
    private val t = oracle.trajectory
    private val rate = t.sampleRate
    private val width = t.width
    private var rng = java.util.Random(seed)
    private val minPhraseSteps = max(1, (minPhrase * rate).roundToInt())
    private val joinSteps = max(1, (joinTime * rate).roundToInt())
    private val cueWindowSteps = max(2, (cueWindow * rate).roundToInt())
    private val cueIntervalSteps = max(1, (cueInterval * rate).roundToInt())
    private val recentCap = max(cueWindowSteps * 2, (rate * 10).toInt())

    private var pos = -1
    private var startAt = start
    private var phrase = 0
    private var outputIndex = 0
    private val offset = DoubleArray(width)
    private val offsetLeft = IntArray(width)
    private val owned = BooleanArray(width)
    private val performed = DoubleArray(width)
    private var sinceCue = 0
    private val recent = ArrayDeque<DoubleArray>()
    private val recentSource = ArrayDeque<Int>()
    private var pendingReason: Reason? = null

    /** Segments played so far (the most recent 10 000). */
    val segments = ArrayList<Segment>()

    /** Memory sample of the last output (or -1 when holding). */
    val position: Int get() = pos

    /** Correction magnitude applied to the last output. */
    var lastCorrection = 0.0; private set

    /** Hands [channel] to the performer at [value]; call every sample while performing. */
    fun perform(channel: Int, value: Double) { owned[channel] = true; performed[channel] = value }

    /** Hands the channel named [name] to the performer. */
    fun perform(name: String, value: Double) = perform(t.channels.indexOf(name), value)

    /** Gives [channel] back to the memory, fading from the performed value over [joinTime]. */
    fun release(channel: Int) {
        if (!owned[channel]) return
        owned[channel] = false
        // the fade starts from the performer's last value; the next step's raw value fills it in
        offset[channel] = Double.NaN
        offsetLeft[channel] = joinSteps
        pendingReason = Reason.Cue
    }

    fun release(name: String) = release(t.channels.indexOf(name))

    /** True while [channel] is performer-owned. */
    fun isPerformed(channel: Int) = owned[channel]

    /** Restart from memory sample [at] with a fresh random sequence. */
    fun reset(seed: Long, at: Int = 0) {
        rng = java.util.Random(seed); pos = -1; startAt = at; phrase = 0; outputIndex = 0
        offset.fill(0.0); offsetLeft.fill(0); owned.fill(false); recent.clear(); recentSource.clear(); segments.clear()
    }

    /** Advances one sample and returns the output frame. */
    fun step(): DoubleArray {
        val last = recent.lastOrNull()
        var reason = Reason.Continue
        var dist = 0.0
        var context = 0
        var next: Int

        if (pos < 0 && last == null) { next = startAt; reason = Reason.Start }
        else {
            val natural = if (pos >= 0 && !t.endsEpisode(pos)) pos + 1 else -1
            next = natural
            val performing = owned.any { it }
            if (performing && sinceCue >= cueIntervalSteps && recent.size >= 2 && (phrase >= minPhraseSteps || natural < 0)) {
                sinceCue = 0
                val hist = recent.toList()
                val c = oracle.cue(hist, cueWindowSteps, owned.copyOf(), count = 1).firstOrNull()
                // hysteresis: stay on the current passage unless the new match is clearly better
                val here = if (natural >= 0) oracle.matchDistance(hist, pos, cueWindowSteps, owned.copyOf()) else Double.POSITIVE_INFINITY
                if (c != null && !t.endsEpisode(c.position) && c.position != pos && c.distance < takeoverMargin * here && correctionFor(c.position + 1) <= maxCorrection) { next = c.position + 1; reason = Reason.Takeover; dist = c.distance; context = c.context }
            } else if (pendingReason == Reason.Cue && recent.size >= 2) {
                val c = oracle.cue(recent.toList(), cueWindowSteps, count = 1).firstOrNull()
                if (c != null && !t.endsEpisode(c.position) && c.distance < declineDistance) { next = c.position + 1; reason = Reason.Cue; dist = c.distance; context = c.context }
            } else if (natural < 0 || (phrase >= minPhraseSteps && rng.nextDouble() < jumpProbability && policy != Policy.Loop)) {
                val j = jump()
                if (j != null) { next = j.first; reason = j.second; dist = j.third.first; context = j.third.second }
                else if (natural < 0) {
                    when (policy) {
                        Policy.Loop -> { next = t.episodeStarts[t.episodeOf(pos)]; reason = Reason.EpisodeEnd }
                        else -> {
                            val c = if (recent.size >= 2) cueAway() else null
                            if (c != null) { next = c.position + 1; reason = Reason.EpisodeEnd; dist = c.distance; context = c.context }
                            else { next = -1; reason = Reason.Hold }
                        }
                    }
                }
            }
            pendingReason = null
        }

        val out: DoubleArray
        if (next < 0) {
            out = last!!.copyOf()
            for (c in 0 until width) if (owned[c]) out[c] = performed[c]
            lastCorrection = 0.0
        } else {
            val raw = t.frame(next)
            val jumped = reason != Reason.Continue && reason != Reason.Start
            if (jumped && last != null) {
                val prev = if (recent.size >= 2) recent[recent.size - 2] else last
                for (c in 0 until width) { offset[c] = (2 * last[c] - prev[c]) - raw[c]; offsetLeft[c] = joinSteps }
            }
            out = DoubleArray(width)
            var mag = 0.0
            for (c in 0 until width) {
                if (owned[c]) { out[c] = performed[c]; continue }
                if (offset[c].isNaN()) offset[c] = performed[c] - raw[c]
                val o = if (offsetLeft[c] > 0) offset[c] * offsetLeft[c] / joinSteps else 0.0
                if (offsetLeft[c] > 0) offsetLeft[c]--
                out[c] = raw[c] + o
                val s = o / oracle.features.scale[c]; mag += s * s
            }
            lastCorrection = sqrt(mag)
        }

        if (reason != Reason.Continue || segments.isEmpty()) {
            segments.add(Segment(outputIndex, next, if (next >= 0) t.episodeOf(next) else -1, reason, dist, context, if (next >= 0) offsetMagnitude() else 0.0))
            if (segments.size > 10_000) segments.removeAt(0)
            phrase = 0
        }
        phrase++; sinceCue++; outputIndex++
        pos = next
        recent.addLast(out); recentSource.addLast(next)
        if (recent.size > recentCap) { recent.removeFirst(); recentSource.removeFirst() }
        return out
    }

    /** Size (in channel spreads) of the correction a jump to [next] would start with. */
    private fun correctionFor(next: Int): Double {
        val last = recent.lastOrNull() ?: return 0.0
        val prev = if (recent.size >= 2) recent[recent.size - 2] else last
        var m = 0.0
        for (c in 0 until width) if (!owned[c]) { val s = ((2 * last[c] - prev[c]) - t.values[c][next]) / oracle.features.scale[c]; m += s * s }
        return sqrt(m)
    }

    private fun offsetMagnitude(): Double {
        var m = 0.0
        for (c in 0 until width) if (!owned[c] && !offset[c].isNaN()) { val s = offset[c] / oracle.features.scale[c]; m += s * s }
        return sqrt(m)
    }

    /** Oracle (or nearest-history) jump: next sample, reason, (join distance, context). */
    private fun jump(): Triple<Int, Reason, Pair<Double, Int>>? {
        if (pos < 0) return null
        if (policy == Policy.NearestHistory) {
            val c = cueAway() ?: return null
            return Triple(c.position + 1, Reason.Cue, c.distance to c.context)
        }
        if (policy != Policy.Oracle) return null
        val s = pos + 1
        val cands = LinkedHashMap<Int, Int>()
        val link = oracle.sfx[s]
        if (link > 0) cands[link] = oracle.lrs[s]
        for (r in oracle.reverseSuffixes(s)) cands[r] = oracle.lrs[r]
        if (link > 0) for (r in oracle.reverseSuffixes(link)) if (r != s) cands.putIfAbsent(r, oracle.lrs[r])
        val th = oracle.threshold
        val opts = ArrayList<Triple<Int, Double, Int>>()
        for ((c, ctx) in cands) {
            // state c holds sample c-1; continuing from it plays sample c
            if (c - 1 == pos || c >= t.length || t.endsEpisode(c - 1)) continue
            val d = distance(oracle.frames[c - 1], oracle.frames[pos])
            if (d > maxJoin * th || correctionFor(c) > maxCorrection) continue
            opts.add(Triple(c, d, ctx))
        }
        if (opts.isEmpty()) return null
        val scores = opts.map { (c, d, ctx) ->
            val reuse = recentSource.count { it >= 0 && kotlin.math.abs(it - c) < minPhraseSteps }.toDouble() / minPhraseSteps
            (kotlin.math.ln(1.0 + ctx) - d / th - reuse) / temperature
        }
        val m = scores.max()
        val w = scores.map { exp(it - m) }
        var u = rng.nextDouble() * w.sum()
        for (i in opts.indices) { u -= w[i]; if (u <= 0) return Triple(opts[i].first, Reason.SuffixLink, opts[i].second to opts[i].third) }
        val o = opts.last()
        return Triple(o.first, Reason.SuffixLink, o.second to o.third)
    }

    /** Best history match that is not where we already are. */
    private fun cueAway(): Cue? = oracle.cue(recent.toList(), cueWindowSteps, count = 6, separation = minPhraseSteps)
        .firstOrNull { kotlin.math.abs(it.position - pos) >= minPhraseSteps && !t.endsEpisode(it.position) && it.distance < declineDistance && correctionFor(it.position + 1) <= maxCorrection }

    /**
     * Runs [samples] steps and returns the [Performance]. [script] runs before each step with the
     * step index, for scripted takeovers: `{ i -> if (i in 300..450) perform("x", 0.0) else if (i == 451) release("x") }`.
     */
    fun take(samples: Int, script: (Improviser.(Int) -> Unit)? = null): Performance {
        val out = Array(width) { DoubleArray(samples) }
        val src = IntArray(samples)
        val cor = DoubleArray(samples)
        val own = Array(width) { BooleanArray(samples) }
        val segStart = segments.size
        for (i in 0 until samples) {
            script?.invoke(this, i)
            val f = step()
            for (c in 0 until width) { out[c][i] = f[c]; own[c][i] = owned[c] }
            src[i] = pos; cor[i] = lastCorrection
        }
        return Performance(Trajectory(rate, t.channels, out, name = "${policy.name.lowercase()} from ${t.name}"), src, cor, own, segments.subList(segStart, segments.size).toList())
    }

    /** Runs for [seconds] of output. */
    fun take(seconds: Double, script: (Improviser.(Int) -> Unit)? = null): Performance = take((seconds * rate).roundToInt(), script)
}
