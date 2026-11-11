@file:afield.runtime.completion.CompletionCategory("ALife")

package afield.simulation.agents

import afield.audio.engine.WavFloatArrayWriter
import afield.media.image.Img
import afield.study.search.Evaluation
import afield.study.search.GenomeOps
import afield.study.search.Objective
import afield.study.search.PopulationSearch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Evolved grain sets: an L-system read as a score of grains, scored by trapezoids over its statistics,
 * and evolved by the same engine as every other genome in `alife_evolve.kt`.
 *
 * The method is Nathan Ho's EvoGrain (`nathanho.me/posts/evograin`). A derived L-system string is
 * walked like a turtle, except that the turtle's state is a **time** and a **pitch** instead of a
 * position and a heading: `G` emits a grain and moves forward in time, `+`/`-` step the pitch, `[`/`]`
 * save and restore the state. Because a branch restores the *time* it started from, brackets make
 * polyphony: every branch is a voice that starts where its parent was.
 *
 * Nothing here is a new search: the genome is [LSystemGenome] with a grain alphabet
 * ([grainLSystemGenome]), and the driver is [PopulationSearch]. What this file adds is the reading
 * ([decodeGrains]), the fitness ([GrainSetStats], [Trapezoid], [grainSetObjective]), the sound
 * ([renderGrains]) and the pictures ([grainScoreImg], [trapezoidImg], [fitnessTraceImg]). [GrainBreeder]
 * is the interactive half: you listen to children and choose the parents.
 */

// ==============================================================================================
// Decoding
// ==============================================================================================

/**
 * One grain of a decoded set.
 *
 * @param onset start time in seconds from the start of the set
 * @param duration length in seconds, envelope included
 * @param pitch MIDI note number (fractional allowed), 60 = middle C
 * @param amp linear amplitude, 1 at the trunk and smaller on deeper branches
 * @param depth bracket depth the grain was emitted at, 0 for the trunk
 */
data class Grain(val onset: Double, val duration: Double, val pitch: Double, val amp: Double, val depth: Int) {
    /** Where the grain ends: [onset] plus [duration]. */
    val end: Double get() = onset + duration
}

/**
 * How [decodeGrains] reads the symbols of a derivation.
 *
 * `G` emit a grain here and advance time; `f` advance time silently (a rest); `+`/`-` raise or lower the
 * pitch by [pitchStep]; `[` push the state (time, pitch, amplitude, grain length), entering a branch
 * whose amplitude is multiplied by [branchAmp] and whose grains by [branchDuration]; `]` pop it. Every
 * other character is ignored, so nonterminals such as `X` can steer the grammar without sounding.
 *
 * @param basePitch MIDI pitch the turtle starts at
 * @param pitchStep semitones per `+` or `-`
 * @param timeStep seconds advanced by a `G` or an `f` on the trunk
 * @param grainDuration length of a trunk grain in seconds
 * @param branchAmp amplitude multiplier on entering a branch
 * @param branchDuration multiplier on grain length *and* time step on entering a branch, so deeper
 *   branches are quicker and shorter
 * @param maxGrains decoding stops after this many grains; a cap, not a range
 */
data class GrainTurtle(
    val basePitch: Double = 60.0,
    val pitchStep: Double = 2.0,
    val timeStep: Double = 0.09,
    val grainDuration: Double = 0.12,
    val branchAmp: Double = 0.8,
    val branchDuration: Double = 0.75,
    val maxGrains: Int = 4000
)

/**
 * Walk [string] with [turtle] and return the grains it emits, in emission order.
 *
 * An unmatched `]` is ignored, as [turtleDraw] ignores it.
 */
fun decodeGrainString(string: String, turtle: GrainTurtle = GrainTurtle()): List<Grain> {
    class State(val t: Double, val p: Double, val amp: Double, val scale: Double, val depth: Int)
    val out = ArrayList<Grain>()
    val stack = ArrayDeque<State>()
    var t = 0.0; var p = turtle.basePitch; var amp = 1.0; var scale = 1.0; var depth = 0
    for (c in string) {
        when (c) {
            'G' -> {
                if (out.size >= turtle.maxGrains) return out
                out.add(Grain(t, turtle.grainDuration * scale, p, amp, depth))
                t += turtle.timeStep * scale
            }
            'f' -> t += turtle.timeStep * scale
            '+' -> p += turtle.pitchStep
            '-' -> p -= turtle.pitchStep
            '[' -> {
                stack.addLast(State(t, p, amp, scale, depth))
                amp *= turtle.branchAmp; scale *= turtle.branchDuration; depth++
            }
            ']' -> stack.removeLastOrNull()?.let { s -> t = s.t; p = s.p; amp = s.amp; scale = s.scale; depth = s.depth }
        }
    }
    return out
}

/**
 * Derive [system] for [generations] rewrites and decode the result with [turtle].
 *
 * @param maxLength derivation cap passed to [LSystem.derive], which throws past it
 */
fun decodeGrains(
    system: LSystem,
    generations: Int = 3,
    turtle: GrainTurtle = GrainTurtle(),
    maxLength: Int = 200_000
): List<Grain> = decodeGrainString(system.derive(generations, maxLength = maxLength), turtle)

// ==============================================================================================
// Statistics and trapezoidal fitness
// ==============================================================================================

/** The descriptive statistics EvoGrain scores a set by. */
enum class GrainStat(
    /** The axis label the pictures use. */
    val label: String
) {
    /** Number of grains. */
    COUNT("grains"),
    /** Median MIDI pitch. */
    MEDIAN_PITCH("median pitch"),
    /** Standard deviation of pitch, in semitones. */
    PITCH_SPREAD("pitch spread (st)"),
    /** Seconds from the first onset to the last grain's end. */
    DURATION("duration (s)"),
    /** Grains per second over [DURATION]. */
    DENSITY("density (grains/s)"),
    /** Mean number of grains sounding at once, sampled over the set's duration. */
    POLYPHONY("mean polyphony"),
    /** Share of the grains on the single most common pitch: 1 for a one-pitch drone. */
    MODE_SHARE("share on one pitch")
}

/** The [GrainStat] values of one set. All zero for an empty set. */
class GrainSetStats(
    /** The grains measured. */
    val grains: List<Grain>
) {
    /** Number of grains. */
    val count: Int = grains.size
    /** Median MIDI pitch. */
    val medianPitch: Double
    /** Standard deviation of pitch, in semitones. */
    val pitchSpread: Double
    /** First onset to last end, seconds. */
    val duration: Double
    /** Grains per second. */
    val density: Double
    /** Mean grains sounding at once. */
    val polyphony: Double
    /** Fraction of the grains on the most common pitch. */
    val modeShare: Double

    init {
        if (grains.isEmpty()) {
            medianPitch = 0.0; pitchSpread = 0.0; duration = 0.0; density = 0.0; polyphony = 0.0; modeShare = 0.0
        } else {
            val ps = grains.map { it.pitch }.sorted()
            medianPitch = if (ps.size % 2 == 1) ps[ps.size / 2] else 0.5 * (ps[ps.size / 2 - 1] + ps[ps.size / 2])
            val mean = ps.average()
            pitchSpread = sqrt(ps.sumOf { (it - mean) * (it - mean) } / ps.size)
            val start = grains.minOf { it.onset }
            duration = grains.maxOf { it.end } - start
            density = if (duration > 0) count / duration else 0.0
            // Total sounding time over the span is exactly the mean number of grains sounding at once.
            polyphony = if (duration > 0) grains.sumOf { it.duration } / duration else 0.0
            modeShare = grains.groupingBy { Math.round(it.pitch * 1000) }.eachCount().values.max().toDouble() / count
        }
    }

    /** The value of [stat]. */
    operator fun get(stat: GrainStat): Double = when (stat) {
        GrainStat.COUNT -> count.toDouble()
        GrainStat.MEDIAN_PITCH -> medianPitch
        GrainStat.PITCH_SPREAD -> pitchSpread
        GrainStat.DURATION -> duration
        GrainStat.DENSITY -> density
        GrainStat.POLYPHONY -> polyphony
        GrainStat.MODE_SHARE -> modeShare
    }

    /** Every statistic, in [GrainStat] order: the behaviour descriptor a novelty search would use. */
    fun vector(): DoubleArray = GrainStat.entries.map { get(it) }.toDoubleArray()

    /** One line, every statistic. */
    override fun toString(): String =
        "%d grains, median %.1f, spread %.2f st, %.2f s, %.1f/s, polyphony %.2f, %.0f%% on one pitch"
            .format(count, medianPitch, pitchSpread, duration, density, polyphony, 100 * modeShare)
}

/**
 * A trapezoidal scoring function: 0 at or below [a], rising linearly to 1 at [b], 1 up to [c], falling
 * to 0 at [d] and 0 beyond. `a == b` or `c == d` makes a hard edge; `b == c` a triangle.
 *
 * @param a where the score starts rising
 * @param b where the plateau starts
 * @param c where the plateau ends
 * @param d where the score reaches 0 again
 */
data class Trapezoid(val a: Double, val b: Double, val c: Double, val d: Double) {
    init { require(a <= b && b <= c && c <= d) { "trapezoid corners must satisfy a <= b <= c <= d, got $a $b $c $d" } }

    /** The score of [x], in `0..1`. */
    operator fun invoke(x: Double): Double = when {
        x.isNaN() -> 0.0
        x < b -> if (x <= a) 0.0 else (x - a) / (b - a)
        x <= c -> 1.0
        x < d -> (d - x) / (d - c)
        else -> 0.0
    }

    companion object {
        /** A trapezoid from four corners in any order, so four independent sliders can never make it invalid. */
        fun sorted(vararg corners: Double): Trapezoid {
            require(corners.size == 4) { "a trapezoid has four corners, got ${corners.size}" }
            val s = corners.sorted()
            return Trapezoid(s[0], s[1], s[2], s[3])
        }
    }
}

/**
 * One weighted term of a grain-set fitness: [shape] applied to [stat], times [weight].
 *
 * @param stat the statistic scored
 * @param shape its scoring function
 * @param weight its share of the weighted mean, >= 0
 */
data class GrainFitnessTerm(val stat: GrainStat, val shape: Trapezoid, val weight: Double = 1.0) {
    init { require(weight >= 0.0) { "weight must be >= 0, got $weight" } }
}

/**
 * The weighted mean of [terms] over [stats]: `Σ wᵢ·shapeᵢ(statᵢ) / Σ wᵢ`, so 1 means every term is on
 * its plateau. An empty set scores 0.
 */
fun grainSetFitness(stats: GrainSetStats, terms: List<GrainFitnessTerm>): Double {
    if (stats.count == 0) return 0.0
    val total = terms.sumOf { it.weight }
    if (total <= 0.0) return 0.0
    return terms.sumOf { it.weight * it.shape(stats[it.stat]) } / total
}

/**
 * A default target: about 40–120 grains over 2–5 s, a median near middle C, a few semitones of spread
 * and a little polyphony, with at most about a third of the grains on any one pitch (without that
 * term the search settles on a one-pitch drone of overlapping trunk grains, which counts as
 * polyphony). Every term is a dial; this is a starting profile, not a rule.
 */
fun defaultGrainTerms(): List<GrainFitnessTerm> = listOf(
    GrainFitnessTerm(GrainStat.COUNT, Trapezoid(10.0, 40.0, 120.0, 400.0)),
    GrainFitnessTerm(GrainStat.MEDIAN_PITCH, Trapezoid(48.0, 58.0, 66.0, 78.0)),
    GrainFitnessTerm(GrainStat.PITCH_SPREAD, Trapezoid(1.0, 4.0, 8.0, 16.0)),
    GrainFitnessTerm(GrainStat.DURATION, Trapezoid(0.5, 2.0, 5.0, 10.0)),
    GrainFitnessTerm(GrainStat.POLYPHONY, Trapezoid(1.0, 1.8, 3.5, 8.0)),
    GrainFitnessTerm(GrainStat.MODE_SHARE, Trapezoid(0.0, 0.0, 0.3, 0.6))
)

/**
 * An [Objective] over grain-alphabet L-systems: decode, measure, score with [terms]. A grammar that
 * blows past the derivation cap or emits nothing scores 0. The behaviour descriptor is
 * [GrainSetStats.vector].
 */
fun grainSetObjective(
    terms: List<GrainFitnessTerm> = defaultGrainTerms(),
    generations: Int = 3,
    turtle: GrainTurtle = GrainTurtle()
): Objective<LSystem> = Objective { system ->
    val grains = runCatching { decodeGrains(system, generations, turtle) }.getOrDefault(emptyList())
    val stats = GrainSetStats(grains)
    Evaluation(grainSetFitness(stats, terms), behaviour = stats.vector())
}

/**
 * [LSystemGenome] over the grain alphabet: one production for `G`, built from `G`, `f`, `+`, `-`, `[`
 * and `]`. `G` appears twice in the alphabet so random bodies sound more often than they rest.
 */
fun grainLSystemGenome(minLength: Int = 3, maxLength: Int = 10): LSystemGenome =
    LSystemGenome(axiom = "G", symbols = "G", alphabet = "GG+-[]f", minLength = minLength, maxLength = maxLength)

// ==============================================================================================
// Sound
// ==============================================================================================

/** A rendered grain set: two channels at [sampleRate]. */
class GrainAudio(
    /** Left channel. */
    val left: FloatArray,
    /** Right channel. */
    val right: FloatArray,
    /** Samples per second. */
    val sampleRate: Int
) {
    /** Length in seconds. */
    val seconds: Double get() = left.size.toDouble() / sampleRate

    /** Stereo 16-bit `.wav` bytes, peak-normalised to [normalizePeak]; `display(channel, bytes)` plays them. */
    fun wav(normalizePeak: Float? = 0.9f): ByteArray =
        WavFloatArrayWriter.encodeStereo16(left, right, sampleRate, normalizePeak)

    /** Write the `.wav` to [path]. */
    fun writeWav(path: String, normalizePeak: Float? = 0.9f) =
        WavFloatArrayWriter.writeStereo16(path, left, right, sampleRate, normalizePeak)
}

/**
 * Render [grains] offline, sample-accurately: every grain starts on the sample nearest its onset.
 *
 * Each grain is a Hann-windowed tone at its pitch (fundamental plus a soft second and third harmonic),
 * or, when [source] is given, a Hann-windowed read of [source] transposed by `pitch - 60` semitones
 * from a position that walks through the source as the set goes on, the way [afield.audio.synth.GrainCloud]
 * reads a sample. Branch depth pans the grain: the trunk is centred, alternate depths lean left and right.
 *
 * This is offline rather than a `GrainCloud` because a cloud spawns grains stochastically from
 * density and position parameters; a decoded set is a fixed list of grains at fixed onsets.
 *
 * @param tail seconds of silence kept after the last grain ends
 */
fun renderGrains(
    grains: List<Grain>,
    sampleRate: Int = 44_100,
    source: FloatArray? = null,
    sourceRate: Int = sampleRate,
    tail: Double = 0.2
): GrainAudio {
    val end = (grains.maxOfOrNull { it.end } ?: 0.0) + tail
    val n = max(1, (end * sampleRate).roundToInt())
    val left = FloatArray(n); val right = FloatArray(n)
    val span = max(1e-9, grains.maxOfOrNull { it.onset } ?: 1.0)
    for (g in grains) {
        val start = (g.onset * sampleRate).roundToInt()
        val len = max(4, (g.duration * sampleRate).roundToInt())
        val hz = 440.0 * 2.0.pow((g.pitch - 69.0) / 12.0)
        val pan = if (g.depth == 0) 0.0 else (if (g.depth % 2 == 1) -1.0 else 1.0) * min(0.8, 0.3 * g.depth)
        val gl = (g.amp * sqrt(0.5 * (1 - pan))).toFloat()
        val gr = (g.amp * sqrt(0.5 * (1 + pan))).toFloat()
        val rate = 2.0.pow((g.pitch - 60.0) / 12.0) * sourceRate / sampleRate
        val readStart = if (source != null) (g.onset / span) * max(0, source.size - 1) * 0.9 else 0.0
        for (i in 0 until len) {
            val j = start + i
            if (j >= n) break
            val w = 0.5 - 0.5 * cos(2 * PI * i / (len - 1))
            val v = if (source == null) {
                val ph = 2 * PI * hz * i / sampleRate
                sin(ph) + 0.3 * sin(2 * ph) + 0.12 * sin(3 * ph)
            } else {
                val pos = readStart + i * rate
                val k = pos.toInt()
                if (k + 1 >= source.size) 0.0 else source[k] + (pos - k) * (source[k + 1] - source[k])
            }
            val s = (w * v * 0.25).toFloat()
            left[j] += s * gl; right[j] += s * gr
        }
    }
    return GrainAudio(left, right, sampleRate)
}

// ==============================================================================================
// Pictures
// ==============================================================================================

private val DEPTH_COLOURS = arrayOf(
    floatArrayOf(0.95f, 0.85f, 0.55f), floatArrayOf(0.45f, 0.75f, 0.95f),
    floatArrayOf(0.95f, 0.5f, 0.45f), floatArrayOf(0.55f, 0.9f, 0.55f), floatArrayOf(0.8f, 0.6f, 0.95f)
)

private fun Img.fillRect(x0: Int, y0: Int, x1: Int, y1: Int, c: FloatArray, alpha: Float = 1f) {
    for (y in max(0, y0)..min(height - 1, y1)) for (x in max(0, x0)..min(width - 1, x1)) {
        for (k in 0 until 3) { val i = index(x, y, k); data[i] = data[i] * (1 - alpha) + c[k] * alpha }
    }
}

private fun Img.line(x0d: Double, y0d: Double, x1d: Double, y1d: Double, c: FloatArray) {
    val steps = max(1, max(abs(x1d - x0d), abs(y1d - y0d)).roundToInt())
    for (s in 0..steps) {
        val x = (x0d + (x1d - x0d) * s / steps).roundToInt(); val y = (y0d + (y1d - y0d) * s / steps).roundToInt()
        fillRect(x, y, x + 1, y + 1, c)
    }
}

private fun blankPlot(width: Int, height: Int): Img {
    val img = Img.zeros(width, height, 3)
    img.data.fill(0.08f)
    return img
}

/**
 * The set as a piano-roll score: time across, pitch up, one bar per grain, brightness by amplitude and
 * colour by branch depth (trunk gold, then blue, red, green, violet). Faint lines mark every octave C.
 */
fun grainScoreImg(grains: List<Grain>, width: Int = 640, height: Int = 240): Img {
    val img = blankPlot(width, height)
    if (grains.isEmpty()) return img
    val t1 = grains.maxOf { it.end }
    val lo = grains.minOf { it.pitch } - 2; val hi = grains.maxOf { it.pitch } + 2
    val pad = 6.0
    fun x(t: Double) = pad + t / t1 * (width - 2 * pad)
    fun y(p: Double) = height - pad - (p - lo) / (hi - lo) * (height - 2 * pad)
    var c = (Math.ceil(lo / 12.0) * 12.0)
    while (c <= hi) { val yy = y(c).roundToInt(); img.fillRect(0, yy, width - 1, yy, floatArrayOf(0.25f, 0.25f, 0.28f)); c += 12.0 }
    val bar = max(2, ((height - 2 * pad) / (hi - lo) * 0.6).roundToInt())
    for (g in grains) {
        val col = DEPTH_COLOURS[g.depth % DEPTH_COLOURS.size]
        val yy = y(g.pitch).roundToInt()
        img.fillRect(x(g.onset).roundToInt(), yy - bar / 2, x(g.end).roundToInt(), yy + bar / 2, col, (0.35 + 0.65 * g.amp).toFloat().coerceIn(0f, 1f))
    }
    return img
}

/**
 * A trapezoid drawn over [lo]..[hi], with a vertical marker at [value] when given (white on the
 * plateau, fading to red as the score falls).
 */
fun trapezoidImg(shape: Trapezoid, lo: Double, hi: Double, value: Double? = null, width: Int = 300, height: Int = 120): Img {
    require(hi > lo) { "need hi > lo" }
    val img = blankPlot(width, height)
    val pad = 8.0
    fun x(v: Double) = pad + (v - lo) / (hi - lo) * (width - 2 * pad)
    fun y(s: Double) = height - pad - s * (height - 2 * pad)
    val col = floatArrayOf(0.95f, 0.85f, 0.55f)
    img.line(x(lo), y(0.0), x(hi), y(0.0), floatArrayOf(0.3f, 0.3f, 0.3f))
    var prevX = x(lo); var prevY = y(shape(lo))
    for (i in 1..width) {
        val v = lo + (hi - lo) * i / width
        val nx = x(v); val ny = y(shape(v)); img.line(prevX, prevY, nx, ny, col); prevX = nx; prevY = ny
    }
    if (value != null) {
        val s = shape(value).toFloat()
        val xv = x(value.coerceIn(lo, hi))
        img.line(xv, y(0.0), xv, y(1.0), floatArrayOf(1f, s, s))
    }
    return img
}

/** A best-so-far trace (e.g. `SearchRun.trace`) as a line, y from [lo] to [hi]. */
fun fitnessTraceImg(trace: DoubleArray, lo: Double = 0.0, hi: Double = 1.0, width: Int = 480, height: Int = 160): Img {
    val img = blankPlot(width, height)
    if (trace.isEmpty()) return img
    val pad = 8.0
    fun x(i: Int) = pad + i.toDouble() / max(1, trace.size - 1) * (width - 2 * pad)
    fun y(v: Double) = height - pad - ((v.coerceIn(lo, hi) - lo) / (hi - lo)) * (height - 2 * pad)
    for (g in 0..4) { val yy = y(lo + (hi - lo) * g / 4).roundToInt(); img.fillRect(0, yy, width - 1, yy, floatArrayOf(0.2f, 0.2f, 0.22f)) }
    for (i in 1 until trace.size) {
        val a = if (trace[i - 1].isFinite()) trace[i - 1] else lo
        img.line(x(i - 1), y(a), x(i), y(trace[i]), floatArrayOf(0.45f, 0.75f, 0.95f))
    }
    return img
}

// ==============================================================================================
// Interactive evolution
// ==============================================================================================

/**
 * Interactive evolution: you are the fitness function. Hold a population of grammars, listen, choose
 * parents by index, and [breed] the next generation from them by the genome's own crossover and
 * mutations. [scores] reports what [objective] thinks of each, so the ear and the trapezoids can be
 * compared, but nothing here selects on it.
 *
 * @param ops the genome; [grainLSystemGenome] by default
 * @param objective used only to report scores
 * @param size children per generation
 * @param seed seeds the first population and every breeding step, so a sequence of choices replays
 */
class GrainBreeder(
    val ops: GenomeOps<LSystem> = grainLSystemGenome(),
    val objective: Objective<LSystem> = grainSetObjective(),
    val size: Int = 6,
    seed: Long = 1L
) {
    private val rng = Random(seed)

    /** The current population. */
    var population: List<LSystem> = List(size) { ops.random(rng) }
        private set

    /** How many times [breed] has run. */
    var generation: Int = 0
        private set

    /** Every generation so far, oldest first, with the parents chosen from it. */
    val history: MutableList<Pair<List<LSystem>, List<Int>>> = mutableListOf()

    /** [objective]'s fitness for each member of [population]. */
    fun scores(): List<Double> = population.map { runCatching { objective.evaluate(it).fitness }.getOrDefault(0.0) }

    /**
     * Replace [population] with [size] children of the members at [parents]: the parents themselves
     * survive (elitism, so a choice is never lost), and the rest are crossovers of two random parents
     * when there are two or more, each then mutated once.
     */
    fun breed(parents: List<Int>): List<LSystem> {
        require(parents.isNotEmpty()) { "choose at least one parent" }
        require(parents.all { it in population.indices }) { "parents must be indices into 0..${population.size - 1}, got $parents" }
        val chosen = parents.distinct().map { population[it] }
        history.add(population to parents)
        val next = ArrayList<LSystem>(size)
        next.addAll(chosen.take(size))
        while (next.size < size) {
            val a = chosen[rng.nextInt(chosen.size)]
            val b = chosen[rng.nextInt(chosen.size)]
            val child = if (chosen.size > 1) ops.crossover(a, b, rng) ?: a else a
            val m = ops.mutations[rng.nextInt(ops.mutations.size)]
            next.add(runCatching { m.apply(child, rng) }.getOrDefault(child))
        }
        population = next
        generation++
        return population
    }
}

/** The single production body of a grain grammar, for a caption. */
fun grainRule(system: LSystem): String = system.productions.joinToString("  ") { "${it.symbol} → ${it.replacement}" }

/**
 * The docs page's audio scenes, rendered offline for the docs audio fence
 * (`audio-renderer="afield.simulation.agents.GrainSetSoundDocs"`).
 *
 * `example` is the hand-written grammar `G → G[+G-G]fG` at three generations; `evolved` is the best
 * grammar of the seeded run `PopulationSearch.evolve(grainLSystemGenome(), grainSetObjective(),
 * budget = 400, seed = 7L, crossoverRate = 0.3, immigrantRate = 0.1)`.
 */
object GrainSetSoundDocs {
    /** The hand-written example grammar. */
    val EXAMPLE: LSystem = lSystem("G", 'G' to "G[+G-G]fG")

    /** The seeded evolution run the page shows. */
    fun evolvedRun() = PopulationSearch.evolve(
        grainLSystemGenome(), grainSetObjective(), budget = 400, seed = 7L,
        crossoverRate = 0.3, immigrantRate = 0.1, label = "grains"
    )

    /** Render scene [name] (`example` or `evolved`) to a stereo `.wav` at [outPath]. */
    fun renderWav(name: String, outPath: String) {
        val system = when (name) {
            "example" -> EXAMPLE
            "evolved" -> evolvedRun().best!!.genome
            else -> error("unknown GrainSetSoundDocs scene '$name'; known: [example, evolved]")
        }
        val audio = renderGrains(decodeGrains(system, 3))
        require(audio.left.any { it != 0f }) { "scene '$name' rendered silence" }
        audio.writeWav(outPath)
    }
}
