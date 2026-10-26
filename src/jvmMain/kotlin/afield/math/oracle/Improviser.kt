package afield.math.oracle

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Playing a sequence memory back as a new performance: when to leave the passage being played,
 * how to join the next one, and how to tell whether what came out still resembles the recording.
 *
 * [Improviser] advances one sample at a time. It follows the recording, and once it has stayed on
 * a passage for at least a phrase it may jump to another that shares context, joining the two
 * with a correction that starts at the discontinuity and fades out. Every sample it emits is
 * taken from the memory, and [Performance] records where from: the memory position behind each
 * output sample, the size of the correction applied there, which channels the performer held, and
 * a [Segment] wherever the source changed, carrying the [Reason] it changed.
 *
 * [Performance] also carries the measurements, since whether a recombination still sounds like
 * the recording is not something the player can answer about itself. `reuse` and `coverage` count
 * memory positions; `outputRepetition` looks at the output alone, and so sees a loop assembled
 * from copies of one gesture taken from different places in memory, which `reuse` cannot;
 * `joinRoughness` and `accelerationDistance` compare the result against the original recording.
 *
 * [ContinuationJudge] is declared here and implemented elsewhere: a second opinion that rates
 * proposed continuations, which [Improviser] either folds into its own scores or, in veto mode,
 * uses only to drop candidates. `afield.math.temporal.ForecastJudge` is the one that ships. A
 * judge can change which passage is played next; it proposes no samples of its own.
 *
 * [Policy] selects between the oracle and two baselines (jumping to the nearest match in recent
 * history, with no oracle structure, and looping each episode unchanged), so a recombination can
 * be compared against doing less. Tests: `VariableMarkovOracleTest`, `SeveralTakesTest` and
 * `ContinuationJudgeTest`.
 */

/** How an [Improviser] chooses where to go next. */
enum class Policy {
    /** Play each episode straight through and start it again: the baseline that never varies. */
    Loop,

    /** Jump to whatever earlier passage best matches the recent output, with no oracle structure. */
    NearestHistory,

    /** Follow the recording and jump along the oracle's suffix links, which share context. */
    Oracle,
}

/**
 * Something that knows how a signal tends to move, asked to rate proposed futures — for an
 * [Improviser] that recombines memory, a second opinion on which continuation is plausible.
 * It sees every frame actually played ([observe]) and returns a loss for a proposed path
 * (`path[channel][t]`, starting at output sample [index]); lower is better, null for no opinion.
 * `afield.math.temporal.ForecastJudge` rates paths against a Timestral forecast.
 */
interface ContinuationJudge {
    fun observe(index: Long, frame: DoubleArray)
    fun loss(index: Long, path: Array<DoubleArray>): Double?
}

/** How an [Improviser] uses its [ContinuationJudge]. */
enum class JudgeMode {
    /** The judge's loss joins the score: it can favour one candidate, choose another, or keep the improviser where it is. */
    Prefer,

    /**
     * The judge only removes candidates: a jump whose loss is more than `vetoRatio` times that of
     * staying on the current passage is dropped, and the oracle chooses among the rest exactly as
     * it would alone. If every jump is vetoed, the improviser stays.
     */
    Veto,
}

/**
 * Why a [Segment] began. [Hold] repeats the last frame because no acceptable continuation exists;
 * after holding for a phrase, [Restart] takes the closest match there is, whatever its distance or
 * correction, rather than hold for ever.
 */
enum class Reason { Start, Continue, SuffixLink, EpisodeEnd, Cue, Takeover, Hold, Restart }

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
    /** Join-correction size per output sample: RMS over channels, in channel spreads (0 = played as recorded). */
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

    /**
     * How much the output repeats *itself*, whatever memory it came from: the fraction of
     * [window]-second stretches of output that closely match some earlier stretch, at least one
     * window back. "Closely" is an RMS difference below [threshold] channel spreads, measured on
     * [channels] (all by default; pick a few for a wide trajectory), with the output resampled to
     * [rate] Hz to keep the comparison cheap.
     *
     * [reuse] counts memory positions, so a loop made of copies of the same gesture from different
     * places in memory does not show up there; it shows up here.
     */
    fun outputRepetition(window: Double = 2.0, threshold: Double = 0.25, channels: List<Int> = output.channels.indices.toList(), rate: Double = 10.0): Double {
        val step = max(1, (output.sampleRate / rate).toInt())
        val n = output.length / step
        val w = max(2, (window * output.sampleRate / step).toInt())
        if (n < 3 * w) return Double.NaN
        val sd = channels.map { c ->
            val v = output.values[c]; val m = v.average()
            kotlin.math.sqrt(v.sumOf { (it - m) * (it - m) } / v.size).coerceAtLeast(1e-9)
        }
        val x = Array(n) { t -> DoubleArray(channels.size) { k -> output.values[channels[k]][t * step] / sd[k] } }
        var repeated = 0; var total = 0
        for (a in 2 * w until n - w + 1) {
            total++
            var found = false
            var b = 0
            while (!found && b + w <= a - w) {
                var s = 0.0
                for (k in 0 until w) for (c in channels.indices) { val d = x[a + k][c] - x[b + k][c]; s += d * d }
                if (kotlin.math.sqrt(s / (w * channels.size)) < threshold) found = true
                b++
            }
            if (found) repeated++
        }
        return repeated.toDouble() / max(1, total)
    }

    private fun accel(t: Trajectory, c: Int): DoubleArray =
        DoubleArray(max(0, t.length - 2)) { i -> kotlin.math.abs(t.values[c][i + 2] - 2 * t.values[c][i + 1] + t.values[c][i]) }

    /**
     * How rough the joins are, against the recording: the mean |second difference| within three
     * samples of every jump, divided by the [memory]'s mean |second difference|, averaged over
     * channels. 1 means joins are as smooth as the recording is on average; above 1, the seams show.
     * NaN with no jumps.
     */
    fun joinRoughness(memory: Trajectory): Double {
        val jumps = segments.filter { it.reason != Reason.Start && it.reason != Reason.Continue && it.reason != Reason.Hold }.map { it.outputStart }
        if (jumps.isEmpty()) return Double.NaN
        var s = 0.0; var n = 0
        for (c in 0 until memory.width) {
            val ref = accel(memory, c).average().coerceAtLeast(1e-12)
            val a = accel(output, c)
            for (j in jumps) for (k in j - 3..j + 3) if (k in a.indices) { s += a[k] / ref; n++ }
        }
        return s / max(1, n)
    }

    /**
     * Kolmogorov–Smirnov distance between the output's and the [memory]'s distributions of
     * |second difference| (how sharply things change), worst channel: 0 = indistinguishable, 1 = disjoint.
     */
    fun accelerationDistance(memory: Trajectory): Double = (0 until memory.width).maxOf { c ->
        val a = accel(memory, c).sorted(); val b = accel(output, c).sorted()
        var i = 0; var j = 0; var d = 0.0
        while (i < a.size && j < b.size) {
            val v = minOf(a[i], b[j])
            while (i < a.size && a[i] == v) i++
            while (j < b.size && b[j] == v) j++
            d = maxOf(d, kotlin.math.abs(i.toDouble() / a.size - j.toDouble() / b.size))
        }
        d
    }

    override fun toString() = "Performance(${output.length} samples, ${segments.size} segments, reuse=${"%.2f".format(reuse())})"
}

/**
 * Plays an [oracle]'s memory forward one sample at a time, recombining it.
 *
 * By default it continues along the current source passage. Once it has stayed for at least
 * [minPhrase] seconds it may, with probability [jumpProbability] per sample, take an oracle
 * jump: to a state that shares a suffix with the current one. Candidates are scored by shared
 * context length, by the join distance (how far the candidate's preceding sample is from where
 * the output is now, value and velocity), and by how much of their next phrase was played within
 * the last [reuseMemory] seconds (scaled by [reuseWeight]; raise either to push towards material
 * not heard for a while); one is
 * sampled from a seeded softmax with [temperature]. A candidate whose join distance exceeds
 * [maxJoin] (a multiple of the oracle threshold), or whose join would need a correction larger
 * than [maxCorrection] (RMS over channels, in channel standard deviations), or that leaves less
 * than [minPhrase] before the end of its episode, is rejected rather than smoothed over. At the
 * end of an episode a jump is compulsory; if no candidate is acceptable it falls back to
 * matching recent history, and if that is worse than [declineDistance] it holds.
 *
 * With several episodes and [bridges] on, the candidates also include [VariableMarkovOracle.bridges]:
 * the nearest moments in other takes, which carry no shared context, so the oracle prefers its own
 * links unless [reuseWeight] pushes it towards material it has not played recently.
 *
 * With [escapeReplays] (on by default), playing material already played in the last [reuseMemory]
 * seconds is itself a reason to leave: after a phrase, a jump is considered on every step of a
 * replay rather than with [jumpProbability], so the improviser does not coast through a repeat.
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
 * With a [judge], every jump decision also rates each candidate's next [judgeAhead] seconds —
 * and staying on the current passage — by the judge's loss. The loss enters the score relative
 * to the candidates' mean (`judgeWeight × loss / mean loss`), so the judge can prefer a jump,
 * choose another, or veto jumping. With [judgeMode] = [JudgeMode.Veto] it only removes jumps
 * whose loss exceeds [vetoRatio] times that of staying, leaving the choice among the rest to the
 * oracle. Every sample still comes from memory. [judgedDecisions],
 * [judgeChanged] and [judgeStayed] count how often it had a say and how often it changed the outcome.
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
    val judge: ContinuationJudge? = null,
    val judgeWeight: Double = 2.0,
    val judgeAhead: Double = 2.0,
    val judgeMode: JudgeMode = JudgeMode.Prefer,
    val reuseWeight: Double = 1.0,
    val reuseMemory: Double = 10.0,
    val bridges: Boolean = true,
    val restartTolerance: Double = 2.0,
    val escapeReplays: Boolean = true,
    val vetoRatio: Double = 1.5,
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
    private var holding = 0
    private val reuseMemorySteps = max(1, (reuseMemory * rate).roundToInt())
    private val lastPlayed = IntArray(t.length) { -1 }
    private val recent = ArrayDeque<DoubleArray>()
    private val recentSource = ArrayDeque<Int>()
    private var pendingReason: Reason? = null

    /** Segments played so far (the most recent 10 000). */
    val segments = ArrayList<Segment>()

    /** Jump decisions the judge scored. */
    var judgedDecisions = 0; private set

    /**
     * Judged decisions the judge altered: in [JudgeMode.Prefer], its most likely choice differed
     * from the unjudged one; in [JudgeMode.Veto], it removed at least one candidate.
     */
    var judgeChanged = 0; private set

    /** Candidate jumps the judge vetoed ([JudgeMode.Veto]). */
    var judgeVetoed = 0; private set

    /** Judged decisions that ended with staying on the current passage. */
    var judgeStayed = 0; private set

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
        offset.fill(0.0); offsetLeft.fill(0); owned.fill(false); recent.clear(); recentSource.clear(); segments.clear(); lastPlayed.fill(-1)
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
            } else if (natural < 0 || (phrase >= minPhraseSteps && policy != Policy.Loop && (replaying(natural) || rng.nextDouble() < jumpProbability))) {
                val j = jump()
                if (j != null && j.second == Reason.Continue) { /* the judge chose to stay */ }
                else if (j != null) { next = j.first; reason = j.second; dist = j.third.first; context = j.third.second }
                else if (natural < 0) {
                    when (policy) {
                        Policy.Loop -> { next = t.episodeStarts[t.episodeOf(pos)]; reason = Reason.EpisodeEnd }
                        else -> {
                            val st = if (policy == Policy.Oracle) freshStart() else -1
                            val c = if (st >= 0) Cue(st - 1, 0.0, 0) else if (recent.size >= 2) cueAway() else null
                            if (c != null) { next = c.position + 1; reason = Reason.EpisodeEnd; dist = c.distance; context = c.context }
                            else if (holding >= minPhraseSteps && recent.size >= 2) {
                                // held a whole phrase: take the closest place there is rather than freeze
                                val best = oracle.cue(recent.toList(), cueWindowSteps, count = 6, separation = minPhraseSteps).firstOrNull { !t.endsEpisode(it.position) && runway(it.position + 1) >= minPhraseSteps }
                                if (best != null) { next = best.position + 1; reason = Reason.Restart; dist = best.distance; context = best.context }
                                else { next = -1; reason = Reason.Hold }
                            }
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
            lastCorrection = sqrt(mag / width)
        }

        if (reason != Reason.Continue || segments.isEmpty()) {
            segments.add(Segment(outputIndex, next, if (next >= 0) t.episodeOf(next) else -1, reason, dist, context, if (next >= 0) offsetMagnitude() else 0.0))
            if (segments.size > 10_000) segments.removeAt(0)
            phrase = 0
        }
        holding = if (next < 0) holding + 1 else 0
        if (next >= 0) lastPlayed[next] = outputIndex
        phrase++; sinceCue++; outputIndex++
        pos = next
        judge?.observe(outputIndex - 1L, out)
        recent.addLast(out); recentSource.addLast(next)
        if (recent.size > recentCap) { recent.removeFirst(); recentSource.removeFirst() }
        return out
    }

    /** True when [next] was already played within the last [reuseMemory] seconds and [escapeReplays] is on. */
    private fun replaying(next: Int): Boolean =
        escapeReplays && next >= 0 && lastPlayed[next] >= 0 && outputIndex - lastPlayed[next] <= reuseMemorySteps

    /** Samples left in the episode from sample [next], inclusive. */
    private fun runway(next: Int): Int {
        val e = t.episodeOf(next)
        val end = if (e + 1 < t.episodeStarts.size) t.episodeStarts[e + 1] else t.length
        return end - next
    }

    /** Size (in channel spreads) of the correction a jump to [next] would start with. */
    private fun correctionFor(next: Int): Double {
        val last = recent.lastOrNull() ?: return 0.0
        val prev = if (recent.size >= 2) recent[recent.size - 2] else last
        var m = 0.0; var n = 0
        for (c in 0 until width) if (!owned[c]) { val s = ((2 * last[c] - prev[c]) - t.values[c][next]) / oracle.features.scale[c]; m += s * s; n++ }
        return sqrt(m / max(1, n))
    }

    private fun offsetMagnitude(): Double {
        var m = 0.0; var n = 0
        for (c in 0 until width) if (!owned[c] && !offset[c].isNaN()) { val s = offset[c] / oracle.features.scale[c]; m += s * s; n++ }
        return sqrt(m / max(1, n))
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
        // cross-take bridges: continue from the sample after a near match in another take (no shared context)
        if (bridges) for (j in oracle.bridges(pos)) cands.putIfAbsent(j + 1, 0)
        val th = oracle.threshold
        val opts = ArrayList<Triple<Int, Double, Int>>()
        for ((c, ctx) in cands) {
            // state c holds sample c-1; continuing from it plays sample c
            if (c - 1 == pos || c >= t.length || t.endsEpisode(c - 1) || runway(c) < minPhraseSteps) continue
            val d = distance(oracle.frames[c - 1], oracle.frames[pos])
            if (d > maxJoin * th || correctionFor(c) > maxCorrection) continue
            opts.add(Triple(c, d, ctx))
        }
        if (opts.isEmpty()) return null
        val base = opts.map { (c, d, ctx) ->
            // the share of the next phrase from c that was played within the last reuseMemory seconds
            var played = 0
            for (k in c until min(t.length, c + minPhraseSteps)) if (lastPlayed[k] >= 0 && outputIndex - lastPlayed[k] <= reuseMemorySteps) played++
            val reuse = played.toDouble() / minPhraseSteps
            kotlin.math.ln(1.0 + ctx) - d / th - reuseWeight * reuse
        }.toMutableList()
        val natural = if (!t.endsEpisode(pos)) pos + 1 else -1
        val scores = ArrayList(base)
        val j = judge
        if (j != null) {
            val ahead = max(1, (judgeAhead * rate).roundToInt())
            fun path(from: Int): Array<DoubleArray> {
                var len = 0
                while (len < ahead && from + len < t.length && (len == 0 || !t.startsEpisode(from + len))) len++
                return Array(width) { c -> DoubleArray(len) { t.values[c][from + it] } }
            }
            // staying is an option the judge can prefer: scored as a zero-join, full-context candidate
            if (natural >= 0) {
                opts.add(Triple(natural, 0.0, oracle.lrs[natural]))
                base.add(kotlin.math.ln(1.0 + oracle.lrs[natural]))
                scores.add(base.last())
            }
            val losses = opts.map { (c, _, _) -> j.loss(outputIndex.toLong(), path(c)) }
            if (judgeMode == JudgeMode.Veto) {
                // staying is the reference, not a candidate: drop it, and any jump much worse than it
                val stay = if (natural >= 0) losses.last() else null
                if (natural >= 0) { opts.removeAt(opts.size - 1); base.removeAt(base.size - 1); scores.removeAt(scores.size - 1) }
                if (stay != null && losses.all { it != null }) {
                    judgedDecisions++
                    val keep = opts.indices.filter { losses[it]!! <= vetoRatio * stay }
                    val vetoed = opts.size - keep.size
                    judgeVetoed += vetoed
                    if (keep.isEmpty()) { judgeStayed++; judgeChanged++; return Triple(natural, Reason.Continue, 0.0 to oracle.lrs[natural]) }
                    if (vetoed > 0) {
                        judgeChanged++
                        val ko = keep.map { opts[it] }; val kb = keep.map { base[it] }
                        opts.clear(); opts.addAll(ko); base.clear(); base.addAll(kb); scores.clear(); scores.addAll(kb)
                    }
                }
            } else if (losses.all { it != null }) {
                val mean = losses.sumOf { it!! } / losses.size
                if (mean > 0) {
                    judgedDecisions++
                    val before = base.indices.filter { natural < 0 || opts[it].first != natural }.maxBy { base[it] }
                    for (i in scores.indices) scores[i] = base[i] - judgeWeight * losses[i]!! / mean
                    val after = scores.indices.maxBy { scores[it] }
                    if (after != before) judgeChanged++
                }
            } else if (natural >= 0) {
                // no opinion yet: decide as without a judge
                opts.removeAt(opts.size - 1); base.removeAt(base.size - 1); scores.removeAt(scores.size - 1)
            }
        }
        val m = scores.max()
        val w = scores.map { exp((it - m) / temperature) }
        var u = rng.nextDouble() * w.sum()
        var pick = opts.size - 1
        for (i in opts.indices) { u -= w[i]; if (u <= 0) { pick = i; break } }
        val o = opts[pick]
        if (o.first == natural && j != null) { judgeStayed++; return Triple(natural, Reason.Continue, 0.0 to o.third) }
        return Triple(o.first, Reason.SuffixLink, o.second to o.third)
    }

    /**
     * At the end of an episode: the start of another episode whose first sample is within
     * [restartTolerance] of where the output is, the one played least recently; -1 if none.
     */
    private fun freshStart(): Int {
        if (t.episodeStarts.size < 2 || pos < 0) return -1
        val here = t.episodeOf(pos)
        return t.episodeStarts.withIndex()
            .filter { (e, st) -> e != here && runway(st) >= minPhraseSteps && correctionFor(st) <= restartTolerance }
            .minByOrNull { (_, st) -> lastPlayed[st] }?.value ?: -1
    }

    /** Best history match that is not where we already are. */
    private fun cueAway(): Cue? {
        // at an episode end, the best matches are often other endings: insist on room to play
        val need = if (pos >= 0 && t.endsEpisode(pos)) 3 * minPhraseSteps else minPhraseSteps
        return oracle.cue(recent.toList(), cueWindowSteps, count = 12, separation = minPhraseSteps)
            .firstOrNull { kotlin.math.abs(it.position - pos) >= minPhraseSteps && !t.endsEpisode(it.position) && runway(it.position + 1) >= need && it.distance < declineDistance && correctionFor(it.position + 1) <= maxCorrection }
    }

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
