@file:afield.CompletionCategory("Search")

package afield.stdlib

import afield.onnx.Lpips
import afield.stacksl.StackSLCompiler
import afield.stacksl.StackSLSeverity
import afield.stacksl.cpu.CpuRaster
import afield.stacksl.cpu.StackSLCpu
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Genetic programming over StackSL **token sequences**, scored by rendering the program and
 * comparing it to a target image.
 *
 * This is a third shape of consumer for `PopulationSearch.kt`. The two that were there before it
 * evolve *numbers or rule tables* — `FunctionEvolve.kt` mutates an `Fn` expression graph and
 * `alife_evolve.kt` mutates rule tables, growth windows and grammar bodies — and neither has a
 * phenotype you can look at. Here the genome is a program in a real language, the phenotype is a
 * picture, and the thing in between is the production emitter plus `afield.stacksl.cpu`'s
 * interpreter. Nothing about what a StackSL word *means* is restated in this file.
 *
 * ### What is new here and what is not
 *
 * Being precise about this, because three of the four ingredients already existed:
 *
 *  - **Not new: linear-genome GP with this operator set.** `LSystemGenome` in `alife_evolve.kt`
 *    already has `point`, `insert`, `delete`, `duplicate`, `swap`, single-point crossover and a
 *    `signature`, over a string of turtle symbols. [StackSLGenome]'s operators are that set,
 *    lifted to a token list, and the resemblance is deliberate.
 *  - **Not new: synthesising a program as text and letting a real compiler judge it.**
 *    `afield.morpho.MorphoGenome` does exactly that for MorphoHDL, and already reports its invalid
 *    fraction rather than hiding it. It predates `PopulationSearch` and runs on a private search
 *    core.
 *  - **New: the phenotype is an image**, produced by the same emitter a GPU reads, so fitness is a
 *    measurement of a rendered picture rather than of a scalar or a netlist.
 *  - **New: a program-text genome on the *generic* core**, so [PopulationSearch.random],
 *    [PopulationSearch.evolve], [PopulationSearch.novelty], [PopulationSearch.mapElites] and
 *    [Selection.LEXICASE] are all available to it without any of them being written again.
 *
 * ### Arity: there was nothing to repair and nothing to reject
 *
 * The plan this file was written against asked for a deliberate choice between *repairing* a token
 * sequence into a legal stack effect and *rejecting* it as invalid. Measured against the real
 * compiler, that is a false dichotomy for a straight-line program: **StackSL has no invalid
 * straight-line programs.** `StackSLCompileOptions.defaultValues` fills any operand the stack does
 * not have, and it does so *without* an ERROR diagnostic even at `tolerant = false` — `+` on its
 * own compiles, `ok`, and leaves the stack at depth 1. The silent-pruning hazard that
 * `finalStackDepth` exists to expose belongs to **definitions** (`: name … ;`), whose arity has to
 * work out for the emitted function to be called at all; this generator emits no definitions, so
 * it cannot hit it.
 *
 * So [StackSLGenome] does not repair for validity and does not reject. It carries a *model* of
 * each word's stack effect ([StackSLWord.consumes]) and uses it only to **steer** generation — the
 * same relationship `MorphoWidthModel` has to MorphoHDL's real width inference, where "the compiler
 * remains the judge; the model's accuracy shows up as `failureRate` and nowhere else". Here the
 * model's accuracy shows up as [StackSLEvolveRun.danglingValues], which counts values a program
 * pushed and never consumed, read off the compiler's own analysis.
 *
 * ### The gate that actually matters is on the image, not on the program
 *
 * A program that compiles perfectly and renders a flat grey rectangle is the single most likely way
 * a search like this produces a fake result: two blanks agree with each other everywhere, and a
 * plain error metric will happily rank one of them first. [StackSLImageObjective] therefore scores
 * a constant-colour render as invalid, inside the fitness function, and refuses a target that is
 * itself blank.
 *
 * ### Beating chance is not resembling the target
 *
 * The first version of this file scored `1 -` mean absolute RGB error, now [StackSLMetric.PIXEL].
 * Evolution beat the random control on four of five targets under it, and the pictures mostly did
 * not look like their targets: a yellow disc evolved into dark diagonal streaks, four quadrants
 * into a vertical gradient. An absolute error is satisfied first by matching the target's overall
 * colour balance, and the search found that rather than the layout. [StackSLMetric.LPIPS], the
 * default, scores with a learned perceptual distance instead ([afield.onnx.Lpips]).
 * [StackSLLayout.layoutAgreement] is the independent check on whether a result resembles its
 * target, and `StackSLEvolveGallery.figureTargets` runs both metrics against each other and asserts
 * on it.
 *
 * ### Cost, so the budget is chosen rather than discovered
 *
 * One 32x32 candidate is about 1,000 pixel-shader invocations through `afield.stacksl.cpu`'s
 * interpreter, which runs 1.3–1.6 x 10^4 a second per core (`dev_docs/STACKSL_CPU_BACKEND.md`):
 * 30 ms with `parallel = true` on ten cores, 70 ms to 130 ms on one, depending on how many `fbm`
 * octaves the program has and what else the machine is doing. Compiling and expanding the
 * template costs 0.35 ms and is noise by comparison. A 600-evaluation search is therefore about
 * twenty seconds, and the two-arm comparison [evolveStackSLTowards] runs about forty.
 *
 * [evolveStackSLTowards] is the one-call entry point a box wants; see `public/docs/stacksl_evolve.md`.
 */

// ==============================================================================================
// 1 — the genome: a StackSL program as a list of tokens
// ==============================================================================================

/** What a word does to the stack's *depth*, in the model this file steers generation with. */
enum class StackSLWordKind {
    /** Pushes a value and consumes none: coordinates, constants, colours, numeric literals. */
    PUSH,

    /** Consumes one and pushes one: `sin`, `fbm`, `length`, a swizzle, a `]`-driver suffix. */
    UNARY,

    /** Consumes two and pushes one: `+`, `mix`'s neighbours, `threshold`, `twist`. */
    BINARY,

    /** Consumes three and pushes one: `mix`, `smoothstep`, `select`, `pulse`. */
    TERNARY
}

/**
 * One word in the generator's vocabulary, with the stack effect this file believes it has.
 *
 * [consumes] is a *model*, and it is measured rather than read off the cheatsheet: every entry in
 * [StackSLPalette.DEFAULT] was checked by compiling `input input input input input <word>` and
 * reading `finalStackDepth` back off `StackSLAnalysis`. Words whose real arity depends on how deep
 * the stack happens to be — `palette` eats five values when five are there and one when one is,
 * `grade` eats four, `vec4` eats four — are modelled at the arity they are *used* at in the gallery,
 * which is the arity the generator should aim for. Being wrong here costs a dangling value, not a
 * broken program.
 */
class StackSLWord(val token: String, val kind: StackSLWordKind) {
    /** Values the word takes off the stack, in the model. */
    val consumes: Int get() = when (kind) {
        StackSLWordKind.PUSH -> 0
        StackSLWordKind.UNARY -> 1
        StackSLWordKind.BINARY -> 2
        StackSLWordKind.TERNARY -> 3
    }

    override fun toString(): String = token
}

/**
 * A StackSL program as a token list — the genome.
 *
 * A list rather than a string because every variation operator in [StackSLGenome] is positional,
 * and splitting a string on whitespace at the top of six operators is six chances to disagree about
 * what a token is. [text] is the program as StackSL reads it, and is also the [signature] the
 * search memoises on.
 */
class StackSLProgram(val tokens: List<String>) {

    /** The program as source text, one space between tokens. */
    val text: String get() = tokens.joinToString(" ")

    /** How many tokens; the parsimony axis and one of the MAP-Elites axes. */
    val size: Int get() = tokens.size

    override fun toString(): String = text

    companion object {
        /** Split source text into tokens the way the generator's operators expect. */
        fun of(text: String): StackSLProgram =
            StackSLProgram(text.split(Regex("""\s+""")).filter { it.isNotBlank() })
    }
}

/**
 * The vocabulary a search draws from, and the numeric-literal distribution beside it.
 *
 * Deliberately *not* every StackSL word. Three exclusions, each for a reason:
 *
 *  - **`sample`, `sample-abs`, `gather`, `blur` and the other texture readers** are out because
 *    they need a previous frame, and a still render has none — `GlslSamplers.REFUSE` would throw
 *    and the candidate would be scored as a crash rather than as a picture.
 *  - **`: … ;` definitions** are out, because a definition whose arity does not work out is pruned
 *    silently and this whole file's honesty rests on nothing being silent.
 *  - **`?name` host reads** are out because they name GLSL that only exists inside a live module.
 *
 * Everything else in the cheatsheet's arithmetic, swizzle, reducer, domain-transform, colour and
 * shaping tables is fair game, plus the `]`-driver suffix forms, which are where StackSL's
 * modulation vocabulary actually lives.
 */
class StackSLPalette(
    val words: List<StackSLWord>,
    /** Literal values the `number` draw picks from before jitter widens them. */
    val numbers: List<Double> = DEFAULT_NUMBERS,
    /** Probability that a `PUSH` draw yields a fresh numeric literal rather than a named word. */
    val numberShare: Double = 0.45
) {

    init {
        require(words.isNotEmpty()) { "a palette needs at least one word" }
        require(numbers.isNotEmpty()) { "a palette needs at least one number" }
        require(numberShare in 0.0..1.0) { "numberShare must be in 0..1, got $numberShare" }
        StackSLWordKind.entries.forEach { kind ->
            require(words.any { it.kind == kind }) { "palette has no $kind word" }
        }
    }

    private val byKind: Map<StackSLWordKind, List<StackSLWord>> = words.groupBy { it.kind }
    private val kindOfToken: Map<String, StackSLWordKind> = words.associate { it.token to it.kind }

    /** The modelled kind of [token]; a token that parses as a number is a [StackSLWordKind.PUSH]. */
    fun kindOf(token: String): StackSLWordKind =
        kindOfToken[token] ?: if (isNumber(token)) StackSLWordKind.PUSH else StackSLWordKind.UNARY

    /** How many values [token] is modelled to consume. */
    fun consumes(token: String): Int = when (kindOf(token)) {
        StackSLWordKind.PUSH -> 0
        StackSLWordKind.UNARY -> 1
        StackSLWordKind.BINARY -> 2
        StackSLWordKind.TERNARY -> 3
    }

    /** A fresh token of [kind]; a `PUSH` is a numeric literal with probability [numberShare]. */
    fun draw(kind: StackSLWordKind, rng: Random): String {
        if (kind == StackSLWordKind.PUSH && rng.nextDouble() < numberShare) return drawNumber(rng)
        val pool = byKind.getValue(kind)
        return pool[rng.nextInt(pool.size)].token
    }

    /**
     * A token of the *same* modelled kind as [token].
     *
     * This is what makes `point` substitution structure-preserving: swapping `sin` for `fbm` leaves
     * every downstream token reading the depth it read before, so a point mutation explores meaning
     * without disturbing shape. An insertion or a deletion is the operator that changes shape.
     */
    fun substitute(token: String, rng: Random): String {
        val kind = kindOf(token)
        if (kind == StackSLWordKind.PUSH && isNumber(token)) return drawNumber(rng)
        val pool = byKind.getValue(kind).filter { it.token != token }
        if (pool.isEmpty()) return token
        return pool[rng.nextInt(pool.size)].token
    }

    /** A numeric literal, occasionally with a StackSL suffix that turns it into a modulator. */
    fun drawNumber(rng: Random): String {
        val base = numbers[rng.nextInt(numbers.size)]
        return when (rng.nextInt(8)) {
            0 -> format(base) + "t"
            1 -> format(base) + "s" + (1 + rng.nextInt(6))
            2 -> format(base) + "c" + (1 + rng.nextInt(6))
            else -> format(base)
        }
    }

    companion object {

        /** A round set of magnitudes shader constants actually take, spanning four decades. */
        val DEFAULT_NUMBERS: List<Double> = listOf(
            0.05, 0.1, 0.15, 0.2, 0.25, 0.33, 0.5, 0.62, 0.75, 0.9,
            1.0, 1.5, 2.0, 3.0, 4.0, 5.0, 6.0, 8.0, 12.0, 18.0
        )

        /** `true` when [token] is a plain decimal literal, with or without a StackSL suffix. */
        fun isNumber(token: String): Boolean = NUMBER.matches(token)

        /** The numeric part of a literal token, or `null` when there is none. */
        fun numberOf(token: String): Double? = NUMBER.matchEntire(token)?.groupValues?.get(1)?.toDoubleOrNull()

        /** Rewrite a literal token's numeric part, keeping any suffix. */
        fun withNumber(token: String, value: Double): String {
            val m = NUMBER.matchEntire(token) ?: return token
            return format(value) + m.groupValues[2]
        }

        /** Three decimals, no exponent, no trailing `.0` surprises — StackSL reads this back. */
        fun format(v: Double): String {
            val rounded = Math.round(v * 1000.0) / 1000.0
            return if (rounded == Math.rint(rounded) && abs(rounded) < 1e6) "%.1f".format(rounded)
            else "%.3f".format(rounded).trimEnd('0')
        }

        /** A literal, group 1 the number and group 2 the suffix (`t`, `s3`, `c2`, …). */
        private val NUMBER = Regex("""(\d+(?:\.\d+)?)([a-z]\d*)?""")

        /**
         * The default vocabulary: 96 words across the four kinds.
         *
         * Every arity here was measured, not read: `input input input input input <word>` compiled
         * and `finalStackDepth` compared against the 6 the five pushes alone leave.
         */
        val DEFAULT: StackSLPalette = StackSLPalette(
            listOf(
                // --- PUSH: coordinates, time, constants, colours -----------------------------
                "uv", "p", "input", "time", "hash", "pi", "tau",
                "white", "black", "red", "green", "blue", "cyan", "magenta", "yellow", "orange", "gray"
            ).map { StackSLWord(it, StackSLWordKind.PUSH) } + listOf(
                // --- UNARY: maths, swizzles, reducers, noise, colour spaces, domain flips -----
                "abs", "sin", "cos", "fract", "floor", "sqrt", "neg", "1-", "not", "normalize",
                "length", "luma", "average", "sum", "vmin", "vmax", "median",
                "x", "y", "xy", "rgb", "xyz", "yx", "xx",
                "fbm", "noise", "palette", "saturate", "tonemap",
                "polar", "cart", "mirror", "repeat",
                "rgb2oklab", "oklab2rgb", "ryb",
                "]wr8", "]wa6", "]wx12", "]wy12", "]sh10", "]fbm4", "]c3", "]s5", "]tri4", "]saw3"
            ).map { StackSLWord(it, StackSLWordKind.UNARY) } + listOf(
                // --- BINARY: arithmetic, comparison, shaping, domain transforms ---------------
                "+", "-", "*", "/", "min", "max", "mod", "pow", "atan2", "dot", "distance", "step",
                "threshold", "posterize", "contrast", "sigmoid", "bias", "bitcrush",
                "kaleido", "tile", "twist", "spiral", "rotate", ">x", ">y", "vec2"
            ).map { StackSLWord(it, StackSLWordKind.BINARY) } + listOf(
                // --- TERNARY -----------------------------------------------------------------
                "mix", "smoothstep", "select", "ramp", "band", "pulse", "vec3"
            ).map { StackSLWord(it, StackSLWordKind.TERNARY) }
        )
    }
}

/**
 * A StackSL program presented to `PopulationSearch` as a genome.
 *
 * All six variation operators are **named**, so `SearchRun.improvements` says which one produced
 * each improvement and the lineage can be drawn rather than asserted. [crossover] is genuine
 * one-point recombination on the token list, so `SearchRun.recombined` is a real count.
 *
 * [signature] is [StackSLProgram.text], and it is the most valuable line in the class. A `point`
 * mutation that lands on the only `sin` and puts a `sin` back, a `jitter` that rounds to the same
 * three decimals, a `delete` on a token past [maxTokens] that was going to be clipped anyway — all
 * of those are neutral, all of them are common, and the core charges no budget for a cache hit
 * because the evaluator was not called. At 30 ms a render that is not a micro-optimisation.
 *
 * @param minTokens shortest program the generator or `delete` will produce
 * @param maxTokens longest; every operator clamps to it, so bloat has a hard ceiling rather than a
 *   parsimony penalty that would have to be weighed against image error in units nobody has
 */
class StackSLGenome(
    val palette: StackSLPalette = StackSLPalette.DEFAULT,
    val minTokens: Int = 3,
    val maxTokens: Int = 24
) : GenomeOps<StackSLProgram> {

    init {
        require(minTokens >= 1) { "minTokens must be >= 1, got $minTokens" }
        require(maxTokens >= minTokens) { "need maxTokens >= minTokens, got $maxTokens < $minTokens" }
    }

    /**
     * An independent draw — and therefore exactly what [PopulationSearch.random] samples.
     *
     * The walk tracks the modelled stack depth and only offers words the depth can pay for, which
     * is the whole use the arity model gets. It starts at depth 1 because StackSL's stack starts
     * holding `stack_in`; that is why an *empty* program is not a blank frame but the input's own
     * `rgb`, and why this search has no cold-start problem.
     */
    override fun random(rng: Random): StackSLProgram {
        val n = minTokens + rng.nextInt(maxTokens - minTokens + 1)
        val out = ArrayList<String>(n)
        var depth = 1
        repeat(n) {
            val kind = drawKind(depth, rng)
            val token = palette.draw(kind, rng)
            out += token
            depth = depth - palette.consumes(token) + 1
        }
        return canonical(out)
    }

    override val mutations: List<Variation<StackSLProgram>> = listOf(
        Variation("point") { g, rng ->
            if (g.size == 0) g else {
                val i = rng.nextInt(g.size)
                canonical(g.tokens.toMutableList().also { it[i] = palette.substitute(it[i], rng) })
            }
        },
        Variation("insert") { g, rng ->
            if (g.size >= maxTokens) g else {
                val at = rng.nextInt(g.size + 1)
                val depth = modelledDepthAt(g.tokens, at)
                canonical(g.tokens.toMutableList().also { it.add(at, palette.draw(drawKind(depth, rng), rng)) })
            }
        },
        Variation("delete") { g, rng ->
            if (g.size <= minTokens) g else canonical(g.tokens.toMutableList().also { it.removeAt(rng.nextInt(it.size)) })
        },
        Variation("duplicate") { g, rng ->
            if (g.size == 0 || g.size >= maxTokens) g else {
                val from = rng.nextInt(g.size)
                val to = min(g.size, from + 1 + rng.nextInt(4))
                canonical(g.tokens.toMutableList().also { it.addAll(to, g.tokens.subList(from, to)) })
            }
        },
        Variation("jitter") { g, rng ->
            val sites = g.tokens.indices.filter { StackSLPalette.numberOf(g.tokens[it]) != null }
            if (sites.isEmpty()) g else {
                val i = sites[rng.nextInt(sites.size)]
                val base = StackSLPalette.numberOf(g.tokens[i])!!
                val scaled = base * (1.0 + (rng.nextDouble() * 2.0 - 1.0) * JITTER)
                canonical(g.tokens.toMutableList().also { it[i] = StackSLPalette.withNumber(it[i], scaled) })
            }
        },
        Variation("swap") { g, rng ->
            if (g.size < 2) g else {
                val i = rng.nextInt(g.size)
                val j = rng.nextInt(g.size)
                canonical(
                    g.tokens.toMutableList().also {
                        val t = it[i]; it[i] = it[j]; it[j] = t
                    }
                )
            }
        }
    )

    /**
     * One-point crossover: a prefix of [a] and a suffix of [b].
     *
     * The textbook recombination for a linear genome, and the same one `LSystemGenome` uses on a
     * grammar body. It cuts each parent independently rather than at a shared index, because the
     * two programs are not aligned and pretending they are would make crossover a length-preserving
     * shuffle.
     */
    override fun crossover(a: StackSLProgram, b: StackSLProgram, rng: Random): StackSLProgram {
        val cutA = rng.nextInt(a.size + 1)
        val cutB = rng.nextInt(b.size + 1)
        return canonical(a.tokens.take(cutA) + b.tokens.drop(cutB))
    }

    /** The program text. See the class KDoc for why this is worth having. */
    override fun signature(g: StackSLProgram): String = g.text

    /**
     * Clamp to `minTokens..maxTokens`. **This is not a validity repair** — see the file KDoc: a
     * straight-line StackSL program cannot be arity-invalid, because the compiler fills missing
     * operands from `defaultValues` and does not complain. It is a bloat ceiling and nothing else.
     */
    fun canonical(tokens: List<String>): StackSLProgram {
        var t = tokens
        if (t.size > maxTokens) t = t.take(maxTokens)
        while (t.size < minTokens) t = t + "uv"
        return StackSLProgram(t)
    }

    /**
     * Pick a kind the modelled stack depth can pay for, biased toward pushes when it is shallow.
     *
     * Not a uniform draw: a uniform draw over four kinds on a shallow stack spends most of its
     * tokens on words whose operands are all `defaultValues` fillers, and a program made of
     * fillers is a program that ignores its own coordinates.
     */
    private fun drawKind(depth: Int, rng: Random): StackSLWordKind {
        val r = rng.nextDouble()
        return when {
            depth <= 1 -> if (r < 0.55) StackSLWordKind.PUSH else StackSLWordKind.UNARY
            depth == 2 -> when {
                r < 0.30 -> StackSLWordKind.PUSH
                r < 0.62 -> StackSLWordKind.UNARY
                else -> StackSLWordKind.BINARY
            }
            else -> when {
                r < 0.24 -> StackSLWordKind.PUSH
                r < 0.54 -> StackSLWordKind.UNARY
                r < 0.86 -> StackSLWordKind.BINARY
                else -> StackSLWordKind.TERNARY
            }
        }
    }

    /** The modelled stack depth just before token [index], starting from StackSL's own depth of 1. */
    fun modelledDepthAt(tokens: List<String>, index: Int): Int {
        var depth = 1
        for (i in 0 until min(index, tokens.size)) depth = depth - palette.consumes(tokens[i]) + 1
        return max(1, depth)
    }

    private companion object {
        /** Multiplicative half-width of a constant jitter, matching `FnMutations.jitterNumbers`. */
        const val JITTER: Double = 0.35
    }
}

// ==============================================================================================
// 2 — what a render looks like, and the guard that a render is a picture at all
// ==============================================================================================

/**
 * The three numbers that say whether a raster is a picture or a rectangle of one colour.
 *
 * Reported rather than merely thresholded, because "this render was rejected as blank" is only
 * believable next to the numbers that rejected it.
 *
 * @property spread the largest per-channel standard deviation over the image, in `0..1` units. A
 *   flat fill is exactly 0; the StackSL gallery's stills sit between 0.08 and 0.35.
 * @property distinctColours how many distinct 8-bit RGB triples appear, capped at
 *   [DISTINCT_CAP] so a busy image does not pay for counting them all.
 * @property spatialFrequency mean absolute luma gradient, scaled into `0..1`. Independent of
 *   [spread] in the way that matters: a smooth left-to-right ramp has a large spread and a tiny
 *   spatial frequency, and a checkerboard has both.
 */
class RasterStructure(
    val spread: Double,
    val distinctColours: Int,
    val spatialFrequency: Double,
    /** Mean per-pixel `max(rgb) - min(rgb)` — how far the image is from grey. */
    val colourfulness: Double
) {
    override fun toString(): String =
        "spread %.4f, %d colours, freq %.4f, colour %.4f".format(spread, distinctColours, spatialFrequency, colourfulness)

    companion object {
        /** Counting past this tells you nothing more about whether the image is blank. */
        const val DISTINCT_CAP: Int = 4096

        /** Measure [raster]'s structure. Alpha is ignored: every template here writes a constant 1. */
        fun of(raster: CpuRaster): RasterStructure {
            val n = raster.width * raster.height
            require(n > 0) { "cannot measure an empty raster" }
            var maxVariance = 0.0
            for (ch in 0 until 3) {
                var sum = 0.0
                var sumSq = 0.0
                for (i in 0 until n) {
                    val v = raster.rgba[i * 4 + ch].toDouble()
                    sum += v
                    sumSq += v * v
                }
                val mean = sum / n
                maxVariance = max(maxVariance, max(0.0, sumSq / n - mean * mean))
            }
            val seen = HashSet<Int>()
            val argb = raster.toArgb()
            for (px in argb) {
                if (seen.size >= DISTINCT_CAP) break
                seen += px and 0xFFFFFF
            }
            var colour = 0.0
            for (i in 0 until n) {
                val r = raster.rgba[i * 4]
                val g = raster.rgba[i * 4 + 1]
                val b = raster.rgba[i * 4 + 2]
                colour += (max(r, max(g, b)) - min(r, min(g, b))).toDouble()
            }
            var gradient = 0.0
            var edges = 0
            for (y in 0 until raster.height) for (x in 0 until raster.width) {
                val here = luma(raster, x, y)
                if (x + 1 < raster.width) { gradient += abs(luma(raster, x + 1, y) - here); edges++ }
                if (y + 1 < raster.height) { gradient += abs(luma(raster, x, y + 1) - here); edges++ }
            }
            return RasterStructure(
                spread = sqrt(maxVariance),
                distinctColours = seen.size,
                // x4 so an ordinary shader lands mid-scale rather than in the bottom eighth: a
                // mean neighbour-to-neighbour luma step of 0.25 is already a very busy image.
                spatialFrequency = if (edges == 0) 0.0 else (gradient / edges * 4.0).coerceIn(0.0, 1.0),
                colourfulness = (colour / n).coerceIn(0.0, 1.0)
            )
        }

        private fun luma(r: CpuRaster, x: Int, y: Int): Double {
            val i = (y * r.width + x) * 4
            return 0.2126 * r.rgba[i] + 0.7152 * r.rgba[i + 1] + 0.0722 * r.rgba[i + 2]
        }
    }
}

/**
 * Does a picture have its target's layout? The check this file uses on a search's results, kept
 * apart from anything a search optimises.
 */
object StackSLLayout {

    /**
     * Would someone looking at [candidate] see [target]'s layout? Cohen's kappa: `1` exact, `0` no
     * better than chance, negative for the target's negative.
     *
     * A check on a search, not part of its fitness, and a different kind of measurement from LPIPS:
     * it classifies rather than compares features. When the target is drawn in at most [PALETTE_LIMIT]
     * colours (every built-in target is), each candidate pixel is labelled with the target colour
     * it is **nearest** in RGB, and kappa compares those labels with the target's own. So a white
     * disc on black counts as the yellow disc on navy it imitates, while faint texture in the
     * background colour counts as background, however well it happens to correlate with the disc.
     * A rank-based version of this measure was tried first and credited exactly that: the
     * 2026-09-06 "dark streaks" answer for `spot` scored 0.65 because its streaks were very slightly
     * brighter in the middle.
     *
     * For a target with more colours than that (a photograph), pixels are instead placed along the
     * target's principal colour axis and cut into four equal-population bands, the target's and the
     * candidate's at the same cut points.
     */
    fun layoutAgreement(candidate: CpuRaster, target: CpuRaster): Double {
        require(candidate.width == target.width && candidate.height == target.height) {
            "cannot compare ${candidate.width}x${candidate.height} against ${target.width}x${target.height}"
        }
        val n = target.width * target.height
        val keyOf = { r: CpuRaster, i: Int ->
            (Math.round(r.rgba[i * 4] * 255f) shl 16) or (Math.round(r.rgba[i * 4 + 1] * 255f) shl 8) or
                Math.round(r.rgba[i * 4 + 2] * 255f)
        }
        val palette = (0 until n).map { keyOf(target, it) }.distinct()
        val targetLabel = IntArray(n)
        val candidateLabel = IntArray(n)
        val k: Int
        if (palette.size <= PALETTE_LIMIT) {
            k = palette.size
            val index = palette.withIndex().associate { it.value to it.index }
            val colours = palette.map { doubleArrayOf((it shr 16 and 255) / 255.0, (it shr 8 and 255) / 255.0, (it and 255) / 255.0) }
            for (i in 0 until n) {
                targetLabel[i] = index.getValue(keyOf(target, i))
                var best = 0
                var bestD = Double.MAX_VALUE
                for ((l, c) in colours.withIndex()) {
                    val dr = candidate.rgba[i * 4] - c[0]
                    val dg = candidate.rgba[i * 4 + 1] - c[1]
                    val db = candidate.rgba[i * 4 + 2] - c[2]
                    val d = dr * dr + dg * dg + db * db
                    if (d < bestD) { bestD = d; best = l }
                }
                candidateLabel[i] = best
            }
        } else {
            k = 4
            val axis = principalAxis(target)
            val t = project(target, axis)
            val c = project(candidate, axis)
            val cuts = t.sorted().let { sorted -> (1 until k).map { sorted[it * n / k] } }
            for (i in 0 until n) {
                targetLabel[i] = cuts.count { t[i] >= it }
                candidateLabel[i] = cuts.count { c[i] >= it }
            }
        }
        val targetCount = IntArray(k)
        val candidateCount = IntArray(k)
        var agree = 0
        for (i in 0 until n) {
            targetCount[targetLabel[i]]++
            candidateCount[candidateLabel[i]]++
            if (targetLabel[i] == candidateLabel[i]) agree++
        }
        val chance = (0 until k).sumOf { targetCount[it].toDouble() * candidateCount[it] } / (n.toDouble() * n)
        return if (chance >= 1.0) 0.0 else (agree.toDouble() / n - chance) / (1.0 - chance)
    }

    /** Most colours a target may have for [layoutAgreement] to label by nearest colour. */
    const val PALETTE_LIMIT: Int = 8

    /** The unit RGB direction of greatest variance over [raster], by power iteration. */
    fun principalAxis(raster: CpuRaster): DoubleArray {
        val n = raster.width * raster.height
        val mean = DoubleArray(3)
        for (i in 0 until n) for (ch in 0 until 3) mean[ch] += raster.rgba[i * 4 + ch]
        for (ch in 0 until 3) mean[ch] /= n
        val cov = Array(3) { DoubleArray(3) }
        for (i in 0 until n) for (a in 0 until 3) for (b in 0 until 3) {
            cov[a][b] += (raster.rgba[i * 4 + a] - mean[a]) * (raster.rgba[i * 4 + b] - mean[b])
        }
        // Start from luma's direction, so a grey target's axis is (1,1,1)/sqrt(3) with its sign.
        var v = doubleArrayOf(0.2126, 0.7152, 0.0722)
        repeat(64) {
            val w = DoubleArray(3) { a -> (0 until 3).sumOf { b -> cov[a][b] * v[b] } }
            val norm = sqrt(w.sumOf { it * it })
            if (norm < 1e-12) return doubleArrayOf(0.2126, 0.7152, 0.0722)
            v = DoubleArray(3) { w[it] / norm }
        }
        // Orient it so that "higher" still means "brighter".
        if (v[0] * 0.2126 + v[1] * 0.7152 + v[2] * 0.0722 < 0) v = DoubleArray(3) { -v[it] }
        return v
    }

    private fun project(raster: CpuRaster, axis: DoubleArray): DoubleArray =
        DoubleArray(raster.width * raster.height) { i ->
            axis[0] * raster.rgba[i * 4] + axis[1] * raster.rgba[i * 4 + 1] + axis[2] * raster.rgba[i * 4 + 2]
        }
}

/** A named target image a search is aimed at. */
class StackSLTarget(val name: String, val raster: CpuRaster) {
    /** The target's own structure — measured, so a blank target can be refused. */
    val structure: RasterStructure = RasterStructure.of(raster)

    /**
     * The target's mean RGB — the colour of the best possible *flat* answer.
     *
     * This is what [StackSLImageObjective.flatFloor] is built from, and it is the anchor every
     * number in this file is reported against. A raw `1 - error` is close to unreadable on its own:
     * almost every program that renders anything mid-toned scores between 0.66 and 0.78 against
     * these targets, so a margin of 0.03 could be either a rounding artefact or a large fraction of
     * the whole attainable range, and the reader cannot tell which. Against the flat floor it is
     * legible immediately.
     */
    val flatColour: FloatArray = run {
        val n = raster.width * raster.height
        val out = FloatArray(3)
        for (i in 0 until n) for (ch in 0 until 3) out[ch] += raster.rgba[i * 4 + ch]
        FloatArray(3) { out[it] / n }
    }

    /** A raster of nothing but [flatColour] — the trivial answer, at the same size. */
    fun flatRaster(): CpuRaster {
        val n = raster.width * raster.height
        val data = FloatArray(n * 4)
        for (i in 0 until n) {
            data[i * 4] = flatColour[0]
            data[i * 4 + 1] = flatColour[1]
            data[i * 4 + 2] = flatColour[2]
            data[i * 4 + 3] = 1f
        }
        return CpuRaster(raster.width, raster.height, data)
    }

    override fun toString(): String = "$name (${raster.width}x${raster.height}, $structure)"

    companion object {
        /**
         * A target from any picture: area-averaged down to [dim] square, grey expanded to RGB.
         *
         * Row 0 of [img] is its top row, as a PNG's is. A non-square picture is squashed, not
         * cropped; crop it first if the aspect matters.
         */
        fun of(name: String, img: Img, dim: Int = DEFAULT_FITNESS_DIM): StackSLTarget {
            val small = img.resize(dim, dim).toRGB()
            val data = FloatArray(dim * dim * 4)
            for (p in 0 until dim * dim) {
                for (c in 0 until 3) data[p * 4 + c] = small.data[p * 3 + c].coerceIn(0f, 1f)
                data[p * 4 + 3] = 1f
            }
            return StackSLTarget(name, CpuRaster(dim, dim, data))
        }
    }
}

/**
 * Five synthetic targets, generated in plain Kotlin.
 *
 * Generated rather than borrowed from the StackSL gallery on purpose. A gallery still is a picture
 * some StackSL program already makes, so aiming a StackSL search at one is a program-recovery task,
 * and a good score would be ambiguous between "the search works" and "the search rediscovered a
 * short program that was in its own vocabulary". These are made of arithmetic this language has no
 * single word for, so a match has to be *built*.
 *
 * ### Why they are all high-contrast, and why they were not at first
 *
 * The first version of this set had a smooth two-axis colour ramp in it, and `evolve` **lost** to
 * `random` on it at every budget tried. That is not a defect in the search; it is what an easy
 * problem looks like. Against a smooth mid-toned target, almost anything the generator produces is
 * within 0.25 mean absolute error, so the whole attainable range is a few hundredths wide and the
 * control's max-of-N sits at the top of it. `AlifeEvolveGallery` had already learned the same
 * lesson on a radius-1 rule table and wrote down the response: *"A control arm that nearly wins is
 * telling you the problem is easy, not that the search is good, and the honest response is a harder
 * problem rather than a softer floor."*
 *
 * So every target here is **saturated and piecewise or high-frequency**, which makes the flat-fill
 * answer bad (see [StackSLTarget.flatColour]) and opens up the range. And the set still spans
 * structures: [spot] is radial, [rings] is radial and periodic, [bands] is one-dimensional,
 * [checker] is two-dimensional and periodic, [quadrants] is aperiodic and piecewise. A search that
 * only handled one kind of structure would show up as four of the five failing.
 */
object StackSLTargets {

    /** A bright warm disc on near-black: radial, one hard edge. */
    fun spot(dim: Int): StackSLTarget = generate("spot", dim) { u, v ->
        val r = sqrt((u - 0.5) * (u - 0.5) + (v - 0.5) * (v - 0.5))
        if (r < 0.28) floatArrayOf(1.0f, 0.82f, 0.25f) else floatArrayOf(0.03f, 0.04f, 0.12f)
    }

    /** Concentric black-and-white rings: radial and periodic. */
    fun rings(dim: Int): StackSLTarget = generate("rings", dim) { u, v ->
        val r = sqrt((u - 0.5) * (u - 0.5) + (v - 0.5) * (v - 0.5))
        val s = if (kotlin.math.sin(r * 40.0) > 0.0) 1.0f else 0.0f
        floatArrayOf(s, s, s)
    }

    /** Six hard vertical stripes, yellow against deep blue: high frequency in x, none in y. */
    fun bands(dim: Int): StackSLTarget = generate("bands", dim) { u, _ ->
        if (kotlin.math.floor(u * 6.0).toInt() % 2 == 0) floatArrayOf(0.98f, 0.9f, 0.1f)
        else floatArrayOf(0.05f, 0.1f, 0.55f)
    }

    /** A 4x4 magenta-and-teal checkerboard: periodic in both axes. */
    fun checker(dim: Int): StackSLTarget = generate("checker", dim) { u, v ->
        val cell = kotlin.math.floor(u * 4.0).toInt() + kotlin.math.floor(v * 4.0).toInt()
        if (cell % 2 == 0) floatArrayOf(0.9f, 0.1f, 0.7f) else floatArrayOf(0.05f, 0.45f, 0.4f)
    }

    /** Four flat quadrants: piecewise constant, hard edges, four saturated colours. */
    fun quadrants(dim: Int): StackSLTarget = generate("quadrants", dim) { u, v ->
        when {
            u < 0.5 && v < 0.5 -> floatArrayOf(0.9f, 0.1f, 0.1f)
            u >= 0.5 && v < 0.5 -> floatArrayOf(0.1f, 0.5f, 0.95f)
            u < 0.5 -> floatArrayOf(0.98f, 0.9f, 0.1f)
            else -> floatArrayOf(0.05f, 0.06f, 0.15f)
        }
    }

    /** All five at [dim] square, in the order the contact sheet shows them. */
    fun all(dim: Int): List<StackSLTarget> =
        listOf(spot(dim), rings(dim), bands(dim), checker(dim), quadrants(dim))

    /**
     * Build a target from a `(u, v) -> rgb` function.
     *
     * `v` runs **downwards**, matching [CpuRaster]'s row 0 = top. That is deliberately *not*
     * StackSL's own convention — `StackSLCpu.FRAG_UV_ORIGIN_IS_TOP_LEFT` is `false`, so `fragUV.y`
     * grows upwards — and the mismatch is fine: both sides of the comparison are rasters, indexed
     * the same way, and a program that needs `1 - y` can find it.
     */
    fun generate(name: String, dim: Int, rgb: (u: Double, v: Double) -> FloatArray): StackSLTarget {
        require(dim >= 2) { "a target must be at least 2x2, got $dim" }
        val data = FloatArray(dim * dim * 4)
        for (y in 0 until dim) for (x in 0 until dim) {
            val c = rgb((x + 0.5) / dim, (y + 0.5) / dim)
            val i = (y * dim + x) * 4
            data[i] = c[0].coerceIn(0f, 1f)
            data[i + 1] = c[1].coerceIn(0f, 1f)
            data[i + 2] = c[2].coerceIn(0f, 1f)
            data[i + 3] = 1f
        }
        return StackSLTarget(name, CpuRaster(dim, dim, data))
    }
}

// ==============================================================================================
// 3 — the objective
// ==============================================================================================

/** What [StackSLImageObjective] counts as "close to the target". */
enum class StackSLMetric {
    /**
     * `1 -` mean absolute RGB error (optionally over a pyramid). Rewards getting each pixel's
     * colour right on average, which a search satisfies first by matching the target's overall
     * colour balance: against a disc that is mostly background, "mostly background colour, with
     * texture anywhere" scores well whether or not there is a disc.
     */
    PIXEL,

    /**
     * `1 -` the LPIPS perceptual distance ([afield.onnx.Lpips]): AlexNet features compared where
     * they are, weighted by a layer trained on human judgements of which images look alike. The
     * default.
     */
    LPIPS,

    /**
     * The mean of [LPIPS] and [PIXEL]. LPIPS compares features wherever they are and barely
     * notices a picture's negative; the pixel error pins colours and positions. Its lexicase cases
     * are both sets: the LPIPS quadrants (when [StackSLImageObjective.lpipsRegions] is on) and the
     * pixel-error grid.
     */
    LPIPS_PIXEL
}

/**
 * One render's scores under both metrics, so one render can serve several objectives.
 *
 * @property valid `false` for a render the blank guard refused or a program that threw; every
 *   score is then [StackSLImageObjective.INVALID_SCORE].
 * @property lpipsCases one LPIPS case per region, or empty when the objective was not asked for
 *   them ([StackSLImageObjective.lpipsRegions])
 */
class StackSLScores(
    val valid: Boolean,
    val pixelFitness: Double,
    val pixelCases: DoubleArray,
    val lpipsFitness: Double,
    val lpipsCases: DoubleArray,
    val behaviour: DoubleArray
) {
    /** The scalar fitness under [metric]. */
    fun fitness(metric: StackSLMetric): Double = when (metric) {
        StackSLMetric.PIXEL -> pixelFitness
        StackSLMetric.LPIPS -> lpipsFitness
        StackSLMetric.LPIPS_PIXEL -> 0.5 * (pixelFitness + lpipsFitness)
    }

    /** The search-facing [Evaluation] under [metric]. */
    fun evaluation(metric: StackSLMetric): Evaluation = when (metric) {
        StackSLMetric.PIXEL -> Evaluation(pixelFitness, pixelCases, behaviour)
        StackSLMetric.LPIPS -> Evaluation(lpipsFitness, lpipsCases, behaviour)
        StackSLMetric.LPIPS_PIXEL -> Evaluation(fitness(metric), lpipsCases + pixelCases, behaviour)
    }

    companion object {
        /** A render the blank guard refused; its behaviour is still reported for MAP-Elites. */
        fun blank(caseGrid: Int, lpipsCases: Int, behaviour: DoubleArray): StackSLScores {
            val floor = StackSLImageObjective.INVALID_SCORE
            return StackSLScores(
                false, floor, DoubleArray(caseGrid * caseGrid) { floor },
                floor, DoubleArray(lpipsCases) { floor }, behaviour
            )
        }
    }
}

/**
 * Program text to [StackSLScores], for objectives that share one target.
 *
 * Thread-safe, so targets can be searched concurrently; but one cache serves one target at one
 * set of objective settings (resolution, supersampling, render time, pyramid depth, case grid,
 * LPIPS size and regions), and the first objective to use it fixes them. A second objective that disagrees is refused rather than handed another target's
 * scores.
 */
class StackSLScoreCache {
    private val scores = java.util.concurrent.ConcurrentHashMap<String, StackSLScores>()
    private val hitCount = java.util.concurrent.atomic.AtomicInteger()
    private var owner: String? = null

    /** Evaluations served from the cache rather than the interpreter. */
    val hits: Int get() = hitCount.get()

    /** Distinct programs scored. */
    val size: Int get() = scores.size

    @Synchronized
    internal fun claim(
        target: StackSLTarget, dim: Int, time: Float, pyramidLevels: Int, caseGrid: Int, supersample: Int,
        lpipsSide: Int, lpipsRegions: Boolean
    ) {
        val key = "${target.name}#${System.identityHashCode(target)} @$dim x$supersample t=$time pyramid=$pyramidLevels " +
            "cases=$caseGrid lpips=$lpipsSide regions=$lpipsRegions"
        val current = owner
        require(current == null || current == key) { "this cache belongs to $current, not $key" }
        owner = key
    }

    internal fun get(text: String): StackSLScores? = scores[text]?.also { hitCount.incrementAndGet() }

    internal fun put(text: String, s: StackSLScores) {
        scores[text] = s
    }
}

/**
 * Score a StackSL program by rendering it and comparing it to [target].
 *
 * Fitness is in `0..1` for anything that renders a picture, higher is better, and 1.0 is a
 * pixel-exact match. What "close" means is [metric]; see [StackSLMetric]. `cases` are the same
 * measure over a [caseGrid] by [caseGrid] tiling of the image, which is what makes
 * [Selection.LEXICASE] mean something here: a program that nails one corner and misses everything
 * else is invisible to a mean and is selected by lexicase whenever that corner's case comes up
 * first.
 *
 * [skill] rescales a fitness so that 0 is a flat fill of the target's mean colour and 1 is exact,
 * which is the number to read: raw fitness for almost anything mid-toned sits in a band a few
 * hundredths wide.
 *
 * [pyramidLevels] > 1 averages the PIXEL error over a box-filtered pyramid. Measured on 2026-09-06
 * over four targets at budget 800, three levels against one gave a summed margin over chance of
 * +0.108 against +0.149: neutral to slightly worse, so the default is one level.
 *
 * `behaviour` is `[spatialFrequency, colourfulness]` of the render, both in `0..1`, for
 * [PopulationSearch.mapElites].
 *
 * ### The blank guard
 *
 * A render whose [RasterStructure.spread] is below [MIN_SPREAD], or which shows fewer than
 * [MIN_DISTINCT_COLOURS] distinct 8-bit colours, scores [INVALID_SCORE] — a *large negative*, not a
 * merely poor error — and every one of its cases scores the same. So:
 *
 *  - the search can never climb toward a blank, however well a blank happens to match;
 *  - a target that is itself blank is refused in `init`, because the guard is meaningless if both
 *    sides are flat;
 *  - and because the guard is in the fitness function rather than in the report, nothing downstream
 *    has to remember to apply it.
 *
 * A program that throws — a `sample` word with no texture bound, a NaN that becomes an exception in
 * the interpreter — also lands on [INVALID_SCORE] rather than propagating, which is the same
 * treatment and for the same reason. (`PopulationSearch`'s `Session.consider` would have caught the
 * throw anyway, at `Double.NEGATIVE_INFINITY`; catching it here keeps every rejection on one scale
 * and lets [invalidRate] count it.)
 */
class StackSLImageObjective(
    val target: StackSLTarget,
    val dim: Int = target.raster.width,
    val time: Float = 1.75f,
    /** The [StackSLMetric.PIXEL] lexicase cases: one per cell of this grid. */
    val caseGrid: Int = 4,
    /** Scales the [StackSLMetric.PIXEL] error is averaged over; 1 is a plain full-resolution mean. */
    val pyramidLevels: Int = DEFAULT_PYRAMID_LEVELS,
    /** `false` renders each candidate on one thread — see [evolveStackSLTowards] for when to. */
    val parallel: Boolean = true,
    /** What "close to the target" means. See [StackSLMetric]. */
    val metric: StackSLMetric = StackSLMetric.LPIPS,
    /**
     * Scores already computed for this target, keyed by program text, shared between objectives.
     *
     * Every evaluation computes *both* metrics from its one render (the comparison costs a few
     * milliseconds, the render tens) and stores them here. So a random control scored under both
     * metrics, or two arms that happen to draw the same program, pay for the render once.
     */
    val cache: StackSLScoreCache? = null,
    /**
     * Render each candidate at this multiple of [dim] and average down: 1 (no supersampling) or a
     * power of two. Without it a program can score by aliasing: stripes finer than a fitness pixel,
     * sampled at pixel centres, can land on a pattern that resembles the target at 32 pixels and
     * nothing at 128. Costs `supersample^2` times the render.
     */
    val supersample: Int = 1,
    /**
     * The side LPIPS sees. A render smaller than this is enlarged pixel by pixel (and the target
     * with it) before comparison, because AlexNet's deeper stages are 1x1 at 32 pixels. Enlarging
     * adds no detail, it gives the network room to look at the detail the render has.
     */
    val lpipsSide: Int = DEFAULT_LPIPS_SIDE,
    /**
     * Also score LPIPS on each quadrant of the [lpipsSide] image, as four lexicase cases. Four
     * more (smaller) LPIPS evaluations per candidate, so off unless you select by lexicase.
     */
    val lpipsRegions: Boolean = false
) : Objective<StackSLProgram> {

    init {
        require(dim >= 2) { "fitness resolution must be at least 2, got $dim" }
        require(caseGrid >= 1) { "caseGrid must be >= 1, got $caseGrid" }
        require(pyramidLevels >= 1) { "pyramidLevels must be >= 1, got $pyramidLevels" }
        require(supersample >= 1 && supersample and (supersample - 1) == 0) {
            "supersample must be a power of two, got $supersample"
        }
        require(lpipsSide >= Lpips.MIN_SIDE && lpipsSide % dim == 0) {
            "lpipsSide must be at least ${Lpips.MIN_SIDE} and a multiple of dim $dim, got $lpipsSide"
        }
        require(!lpipsRegions || lpipsSide >= 2 * Lpips.MIN_SIDE) {
            "lpipsRegions needs lpipsSide >= ${2 * Lpips.MIN_SIDE}, got $lpipsSide"
        }
        require(target.raster.width == dim && target.raster.height == dim) {
            "target '${target.name}' is ${target.raster.width}x${target.raster.height} but fitness runs at ${dim}x$dim"
        }
        require(target.structure.spread >= MIN_SPREAD) {
            "target '${target.name}' has spread ${"%.5f".format(target.structure.spread)}, below the " +
                "$MIN_SPREAD blank floor — matching a blank target is not a measurement of anything"
        }
    }

    /** The target's pyramid and its LPIPS input, built once. */
    private val targetPyramid: List<CpuRaster> = pyramidOf(target.raster, pyramidLevels)
    private val targetPlanar: FloatArray = lpipsPlanar(target.raster, lpipsSide)
    private val lpipsCaseCount: Int = if (lpipsRegions) 4 else 0
    private val targetQuadrants: FloatArray? = if (lpipsRegions) quadrants(targetPlanar, lpipsSide) else null

    init {
        cache?.claim(target, dim, time, pyramidLevels, caseGrid, supersample, lpipsSide, lpipsRegions)
    }

    /**
     * What a flat fill of the target's own mean colour would score — the trivial answer's fitness.
     *
     * The anchor [skill] is measured from. Note that it is **not attainable** by any candidate this
     * objective will accept: a flat fill has zero spread and the blank guard rejects it. It is a
     * reference point, not a competitor, and that is exactly what makes it a good one — it is the
     * score a search gets for understanding nothing about where the target's structure is.
     */
    val flatFloor: Double =
        scoresAgainst(target.flatRaster(), target.raster, targetPyramid, targetPlanar, targetQuadrants, allowFlat = true)
            .fitness(metric)

    /**
     * A fitness rescaled so 0 is the flat answer and 1 is exact.
     *
     * `(fitness - flatFloor) / (1 - flatFloor)`. Monotone in fitness, so it never changes a ranking
     * or which arm wins — it only makes the margin readable. Negative for a candidate that did worse
     * than a flat fill, which is a thing that happens and should look bad.
     */
    fun skill(fitness: Double): Double =
        if (flatFloor >= 1.0) 0.0 else (fitness - flatFloor) / (1.0 - flatFloor)

    /** Candidates offered so far, including ones this objective rejected. */
    var considered: Int = 0
        private set

    /** Candidates rejected as blank or as a crash. The `failureRate` of `MorphoSearchRun`, here. */
    var invalid: Int = 0
        private set

    /** Fraction of candidates that were not pictures. Reported, never hidden. */
    val invalidRate: Double get() = if (considered == 0) 0.0 else invalid.toDouble() / considered

    override fun evaluate(genome: StackSLProgram): Evaluation {
        considered++
        val s = cache?.get(genome.text) ?: run {
            val raster = try {
                renderForFitness(genome)
            } catch (t: Throwable) {
                null
            }
            val computed = if (raster == null) StackSLScores.blank(caseGrid, lpipsCaseCount, doubleArrayOf(0.0, 0.0))
            else scores(raster)
            cache?.put(genome.text, computed)
            computed
        }
        if (!s.valid) invalid++
        return s.evaluation(metric)
    }

    /** Render [genome] at [dim] — the same call `evaluate` makes, for a caller that wants the pixels. */
    fun render(genome: StackSLProgram, dim: Int = this.dim): CpuRaster =
        StackSLCpu.renderStill(genome.text, dim = dim, time = time, parallel = parallel)

    /**
     * What [evaluate] scores: [genome] at [dim], rendered at `dim * supersample` and box-filtered
     * down, so detail finer than a fitness pixel averages out instead of aliasing.
     */
    fun renderForFitness(genome: StackSLProgram): CpuRaster {
        var r = StackSLCpu.renderStill(genome.text, dim = dim * supersample, time = time, parallel = parallel)
        while (r.width > dim) r = halve(r)
        return r
    }

    /**
     * Fitness of a program against a target at *that* target's resolution, under the same metric.
     *
     * The proxy question is asked with this: score the same programs against a large target and see
     * whether the small target's ranking survives. LPIPS then sees the large image at its own size.
     */
    fun scoreAt(genome: StackSLProgram, other: StackSLTarget): Double {
        val raster = render(genome, other.raster.width)
        val side = max(Lpips.MIN_SIDE, other.raster.width)
        val want = lpipsPlanar(other.raster, side)
        return scoresAgainst(raster, other.raster, pyramidOf(other.raster, pyramidLevels), want,
            if (lpipsRegions) quadrants(want, side) else null).fitness(metric)
    }

    /** Both metrics' scores for a raster already rendered at [dim]. */
    fun scores(raster: CpuRaster): StackSLScores =
        scoresAgainst(raster, target.raster, targetPyramid, targetPlanar, targetQuadrants)

    private fun scoresAgainst(
        raster: CpuRaster,
        want: CpuRaster,
        wantPyramid: List<CpuRaster>,
        wantPlanar: FloatArray,
        wantQuadrants: FloatArray?,
        allowFlat: Boolean = false
    ): StackSLScores {
        val structure = RasterStructure.of(raster)
        val behaviour = doubleArrayOf(structure.spatialFrequency, structure.colourfulness)
        val flat = structure.spread < MIN_SPREAD || structure.distinctColours < MIN_DISTINCT_COLOURS
        // allowFlat is for the flat-fill reference alone: the one flat raster that must get a
        // number rather than the blank guard's floor, because [skill] is measured from it.
        if (flat && !allowFlat) return StackSLScores.blank(caseGrid, lpipsCaseCount, behaviour)

        val size = raster.width
        val pixelFitness = 1.0 - pyramidError(raster, wantPyramid)
        val pixelCases = DoubleArray(caseGrid * caseGrid)
        for (ry in 0 until caseGrid) for (rx in 0 until caseGrid) {
            pixelCases[ry * caseGrid + rx] = 1.0 - meanAbsoluteError(
                raster, want, rx * size / caseGrid, ry * size / caseGrid, (rx + 1) * size / caseGrid, (ry + 1) * size / caseGrid
            )
        }

        val side = wantPlanar.size / 3
        val lpipsSideHere = kotlin.math.sqrt(side.toDouble()).toInt()
        val planar = lpipsPlanar(raster, lpipsSideHere)
        val lpipsFitness = 1.0 - Lpips.distancesPlanar(planar, 1, lpipsSideHere, lpipsSideHere, wantPlanar)[0]
        val lpipsCases = if (wantQuadrants == null) DoubleArray(0) else {
            val half = lpipsSideHere / 2
            val mine = quadrants(planar, lpipsSideHere)
            val plane = 3 * half * half
            DoubleArray(4) { q ->
                val d = Lpips.distancesPlanar(
                    mine.copyOfRange(q * plane, (q + 1) * plane), 1, half, half,
                    wantQuadrants.copyOfRange(q * plane, (q + 1) * plane)
                )[0]
                1.0 - d
            }
        }
        return StackSLScores(true, pixelFitness, pixelCases, lpipsFitness, lpipsCases, behaviour)
    }

    companion object {
        /**
         * What a program that did not render a picture scores.
         *
         * Below every attainable fitness (which lives in `0..1`) by a wide margin, and *finite*, so
         * `SearchRun.trace` stays plottable and a run whose first candidates were all blank does not
         * start at negative infinity.
         */
        const val INVALID_SCORE: Double = -1.0

        /**
         * Smallest per-channel standard deviation a render may have and still count as a picture.
         *
         * 0.02 in `0..1` units is about five 8-bit levels. Chosen from the other side: the 29
         * non-feedback stills in the StackSL gallery have spreads from 0.08 upward, so this floor is
         * a factor of four below the least structured thing the language is known to make on purpose.
         */
        const val MIN_SPREAD: Double = 0.02

        /**
         * Fewer distinct 8-bit colours than this is a flat fill.
         *
         * Two, not more. This was 4 until 2026-10-07, on the theory that two colours is "a dither,
         * not an image" — which refused every hard-edged two-colour picture, including four of the
         * five targets themselves and the shortest programs that draw them (`p length 0.28
         * threshold` renders exactly two colours). The search could approach a target only through
         * programs that were *not* its exact answer. [MIN_SPREAD] is what tells a picture from a
         * flat fill; this only stops a single colour slipping under it.
         */
        const val MIN_DISTINCT_COLOURS: Int = 2

        /**
         * Scales the default error averages over — **one**, i.e. a plain full-resolution mean.
         *
         * The simplest metric that could work, and the one that measured best. See the class KDoc
         * for the pyramid that was built as an alternative and did not earn the default.
         */
        const val DEFAULT_PYRAMID_LEVELS: Int = 1

        /**
         * The pyramid depth [PYRAMID_LEVELS_MEASURED] refers to: full, half, quarter.
         *
         * Three rather than "as many as fit". A fourth level at [DEFAULT_FITNESS_DIM] would be 4x4
         * pixels, at which point the level is measuring the image's average colour and would put a
         * quarter of the metric's weight on a single number.
         */
        const val PYRAMID_LEVELS_MEASURED: Int = 3

        /**
         * The side LPIPS sees by default: 64, twice [DEFAULT_FITNESS_DIM]. At 32 AlexNet's three
         * deepest stages are 1x1 and the distance scale is compressed (a disc against a ramp is
         * 0.42 at 32 pixels and 0.68 at 64); at 64 they are 3x3.
         */
        const val DEFAULT_LPIPS_SIDE: Int = 64

        /**
         * [raster] as LPIPS input: planar RGB, `3 x side x side`, enlarged pixel by pixel when
         * [side] is a multiple of its size. A raster already at [side] is copied as is.
         */
        fun lpipsPlanar(raster: CpuRaster, side: Int): FloatArray {
            require(side % raster.width == 0 && raster.width == raster.height) {
                "cannot enlarge a ${raster.width}x${raster.height} raster to $side"
            }
            val k = side / raster.width
            val plane = side * side
            val out = FloatArray(3 * plane)
            for (y in 0 until side) for (x in 0 until side) {
                val src = ((y / k) * raster.width + x / k) * 4
                val dst = y * side + x
                out[dst] = raster.rgba[src].coerceIn(0f, 1f)
                out[plane + dst] = raster.rgba[src + 1].coerceIn(0f, 1f)
                out[2 * plane + dst] = raster.rgba[src + 2].coerceIn(0f, 1f)
            }
            return out
        }

        /** The four quadrants of a planar `3 x side x side` image, each planar, top-left first. */
        fun quadrants(planar: FloatArray, side: Int): FloatArray {
            val half = side / 2
            val plane = side * side
            val qPlane = half * half
            val out = FloatArray(4 * 3 * qPlane)
            for (q in 0 until 4) {
                val ox = (q % 2) * half
                val oy = (q / 2) * half
                for (c in 0 until 3) for (y in 0 until half) for (x in 0 until half) {
                    out[q * 3 * qPlane + c * qPlane + y * half + x] = planar[c * plane + (oy + y) * side + ox + x]
                }
            }
            return out
        }

        /**
         * [levels] box-filtered halvings of [raster], level 0 being [raster] itself.
         *
         * Stops early rather than going below 2x2, so a small target silently gets fewer levels
         * instead of a degenerate one; [pyramidError] weights by the levels it is given.
         */
        fun pyramidOf(raster: CpuRaster, levels: Int): List<CpuRaster> {
            require(levels >= 1) { "a pyramid needs at least one level, got $levels" }
            val out = ArrayList<CpuRaster>(levels)
            var current = raster
            out += current
            while (out.size < levels && current.width >= 4 && current.height >= 4) {
                current = halve(current)
                out += current
            }
            return out
        }

        /** Mean over levels of the absolute RGB error, each level against its own target level. */
        fun pyramidError(raster: CpuRaster, targetPyramid: List<CpuRaster>): Double {
            var sum = 0.0
            var current = raster
            for ((level, want) in targetPyramid.withIndex()) {
                if (level > 0) current = halve(current)
                sum += meanAbsoluteError(current, want, 0, 0, want.width, want.height)
            }
            return sum / targetPyramid.size
        }

        /** A 2x2 box downsample. Odd sizes drop the last row/column; every raster here is a power of two. */
        fun halve(raster: CpuRaster): CpuRaster {
            val w = raster.width / 2
            val h = raster.height / 2
            require(w >= 1 && h >= 1) { "cannot halve a ${raster.width}x${raster.height} raster" }
            val out = FloatArray(w * h * 4)
            for (y in 0 until h) for (x in 0 until w) {
                for (ch in 0 until 4) {
                    val a = raster.rgba[((2 * y) * raster.width + 2 * x) * 4 + ch]
                    val b = raster.rgba[((2 * y) * raster.width + 2 * x + 1) * 4 + ch]
                    val c = raster.rgba[((2 * y + 1) * raster.width + 2 * x) * 4 + ch]
                    val d = raster.rgba[((2 * y + 1) * raster.width + 2 * x + 1) * 4 + ch]
                    out[(y * w + x) * 4 + ch] = (a + b + c + d) * 0.25f
                }
            }
            return CpuRaster(w, h, out)
        }

        /** Mean absolute RGB difference over a rectangle of two same-sized rasters, in `0..1`. */
        fun meanAbsoluteError(a: CpuRaster, b: CpuRaster, x0: Int, y0: Int, x1: Int, y1: Int): Double {
            require(a.width == b.width && a.height == b.height) {
                "cannot compare ${a.width}x${a.height} against ${b.width}x${b.height}"
            }
            var sum = 0.0
            var n = 0
            for (y in y0 until y1) for (x in x0 until x1) {
                val i = (y * a.width + x) * 4
                sum += abs(a.rgba[i] - b.rgba[i]).toDouble()
                sum += abs(a.rgba[i + 1] - b.rgba[i + 1]).toDouble()
                sum += abs(a.rgba[i + 2] - b.rgba[i + 2]).toDouble()
                n += 3
            }
            return if (n == 0) 0.0 else sum / n
        }
    }
}

// ==============================================================================================
// 4 — the box-facing entry point
// ==============================================================================================

/** Fitness resolution that costs about 30 ms a candidate on ten cores. See the file KDoc. */
const val DEFAULT_FITNESS_DIM: Int = 32

/**
 * One search, its control arm, and everything needed to say whether the result is real.
 *
 * [control] is not optional and not a courtesy. It is [PopulationSearch.random] over the same
 * [StackSLGenome] at the same budget with the same seed, which is the only thing that turns
 * "evolution found this" from a sentence into a claim: a generator good enough to stumble onto a
 * decent shader occasionally looks exactly like a successful search.
 *
 * @property danglingValues values the best program pushed and never consumed, off the compiler's
 *   own `StackSLAnalysis`. The arity model's error bar, in the only units it has.
 */
class StackSLEvolveRun(
    val target: StackSLTarget,
    val evolved: SearchRun<StackSLProgram>,
    val control: SearchRun<StackSLProgram>,
    val invalidRate: Double,
    val danglingValues: Int,
    /** The objective the evolved arm ran against — kept so [skill] and re-rendering are available. */
    val objective: StackSLImageObjective
) {
    /** The evolved program, or `null` if every candidate was rejected. */
    val best: StackSLProgram? get() = evolved.best?.genome

    /** Evolved fitness minus control fitness at equal budget. The number a claim is made of. */
    val margin: Double get() = evolved.bestFitness - control.bestFitness

    /** Evolved fitness in [StackSLImageObjective.skill] units: 0 is a flat fill, 1 is exact. */
    val evolvedSkill: Double get() = objective.skill(evolved.bestFitness)

    /** The control's fitness in the same units. */
    val controlSkill: Double get() = objective.skill(control.bestFitness)

    /**
     * How many times further above the trivial flat answer `evolve` got than `random` did.
     *
     * The headline number, and the one that is legible: a raw margin of 0.03 on a scale whose whole
     * useful range is 0.1 wide reads as nothing and is in fact a third of everything there was to
     * win. `NaN` when the control did not beat a flat fill at all, which would make the ratio
     * meaningless rather than infinite.
     */
    val skillRatio: Double get() = if (controlSkill <= 0.0) Double.NaN else evolvedSkill / controlSkill

    /**
     * [StackSLLayout.layoutAgreement] of the evolved program against the target, at the
     * search's own resolution: does it put light and dark where the target does? Not what either
     * arm optimised, which is why it is worth reporting beside their fitness.
     */
    val evolvedLayout: Double by lazy { layoutOf(evolved) }

    /** The control's [evolvedLayout]. */
    val controlLayout: Double by lazy { layoutOf(control) }

    private fun layoutOf(run: SearchRun<StackSLProgram>): Double {
        val g = run.best?.genome ?: return 0.0
        if (run.bestFitness <= StackSLImageObjective.INVALID_SCORE) return 0.0
        return StackSLLayout.layoutAgreement(objective.render(g), objective.target.raster)
    }

    /**
     * Target, evolved best and the control's best side by side at [side] pixels, for `showImg`.
     *
     * The target is enlarged pixel by pixel; both programs are re-rendered at [side], so
     * what you see is the program, not an enlarged thumbnail of what the search scored.
     */
    fun contactSheet(side: Int = 160): Img {
        val panels = ArrayList<Pair<String, Img>>()
        panels += "target" to target.raster.toImg().upscaleNearest(max(1, side / target.raster.width))
        evolved.best?.genome?.let { panels += "evolved %.2f".format(evolvedSkill) to objective.render(it, side).toImg() }
        control.best?.genome?.let { panels += "random %.2f".format(controlSkill) to objective.render(it, side).toImg() }
        return montage(panels, columns = panels.size, cellSide = side + 10)
    }

    fun summary(): String = buildString {
        append("%-10s evolved %.4f (skill %+.3f)  random %.4f (skill %+.3f)".format(
            target.name, evolved.bestFitness, evolvedSkill, control.bestFitness, controlSkill
        ))
        append("  margin %+.4f".format(margin))
        if (!skillRatio.isNaN()) append("  ratio %.2fx".format(skillRatio))
        append("  layout %.2f vs %.2f".format(evolvedLayout, controlLayout))
        append("  invalid %.1f%%".format(invalidRate * 100))
        append("  dangling %d".format(danglingValues))
        append("  %.1fs".format((evolved.millis + control.millis) / 1000.0))
    }

    override fun toString(): String = summary()
}

/**
 * Evolve a StackSL program toward [target], with the random control arm run beside it.
 *
 * The one call a box needs:
 *
 * ```kotlin
 * val run = evolveStackSLTowards(StackSLTargets.spot(32), budget = 600)
 * print(run.summary())
 * showImg(run.contactSheet())
 * ```
 *
 * `parallel = true` (the default) splits each render's rows across cores, which composes with
 * `PopulationSearch`'s sequential loop and is worth about 2.5x at 32 rows on ten cores. Turn it off
 * when you run several searches on their own threads.
 *
 * @param metric what counts as close; [StackSLMetric.LPIPS] unless you are reproducing the
 *   pixel-error version
 * @param cache shared by both arms, so a program they both draw is rendered once; pass `null` to
 *   render every evaluation
 * @param supersample see [StackSLImageObjective.supersample]
 * @param lpipsSide see [StackSLImageObjective.lpipsSide]
 *
 * @param budget evaluations *per arm*; the evolved and control arms each get this many, so the
 *   comparison is at equal budget by construction rather than by the caller remembering to
 * @param crossoverRate probability a child is recombined before mutation. Non-zero by default
 *   because [StackSLGenome] declares a real [GenomeOps.crossover] and `SearchRun.recombined` should
 *   have something in it
 */
fun evolveStackSLTowards(
    target: StackSLTarget,
    budget: Int = 600,
    seed: Long = 1L,
    genome: StackSLGenome = StackSLGenome(),
    dim: Int = target.raster.width,
    mu: Int = 6,
    lambda: Int = 12,
    selection: Selection = Selection.TOURNAMENT,
    tournament: Int = 3,
    crossoverRate: Double = 0.25,
    immigrantRate: Double = 0.05,
    pyramidLevels: Int = StackSLImageObjective.DEFAULT_PYRAMID_LEVELS,
    parallel: Boolean = true,
    metric: StackSLMetric = StackSLMetric.LPIPS,
    cache: StackSLScoreCache? = StackSLScoreCache(),
    supersample: Int = 1,
    lpipsSide: Int = StackSLImageObjective.DEFAULT_LPIPS_SIDE,
    label: String = "stacksl ${target.name}"
): StackSLEvolveRun {
    require(budget >= 1) { "budget must be >= 1, got $budget" }
    // Lexicase under LPIPS selects on the four quadrant distances; nothing else needs them.
    val regions = selection == Selection.LEXICASE && metric != StackSLMetric.PIXEL
    fun objective() = StackSLImageObjective(
        target, dim = dim, pyramidLevels = pyramidLevels, parallel = parallel, metric = metric, cache = cache,
        supersample = supersample, lpipsSide = lpipsSide, lpipsRegions = regions
    )
    val evolvedObjective = objective()
    val controlObjective = objective()
    val evolved = PopulationSearch.evolve(
        ops = genome,
        objective = evolvedObjective,
        budget = budget,
        seed = seed,
        mu = mu,
        lambda = lambda,
        tournament = tournament,
        selection = selection,
        crossoverRate = crossoverRate,
        immigrantRate = immigrantRate,
        label = label
    )
    val control = PopulationSearch.random(genome, controlObjective, budget, seed = seed, label = "$label random")
    return StackSLEvolveRun(
        target = target,
        evolved = evolved,
        control = control,
        invalidRate = evolvedObjective.invalidRate,
        danglingValues = evolved.best?.genome?.let { danglingValuesOf(it) } ?: 0,
        objective = evolvedObjective
    )
}

/**
 * How many values [program] pushes and never consumes, read off the compiler's own analysis.
 *
 * The one place `StackSLAnalysis` is consulted for its own sake, and the honest error bar on
 * [StackSLWord.consumes]: a dangling value is a token the model thought would be used and that was
 * not. Returns `-1` if the program does not compile at all, which for a straight-line program
 * without definitions should never happen — see the file KDoc.
 */
fun danglingValuesOf(program: StackSLProgram): Int {
    val result = StackSLCompiler.compile(program.text, StackSLCpu.stackAutoOptions("program"))
    if (result.diagnostics.any { it.severity == StackSLSeverity.ERROR }) return -1
    return result.analysis.danglingWires.size
}

/** A [CpuRaster] as an RGB [Img], for `montage` and `savePng`. Alpha is dropped, not premultiplied. */
fun CpuRaster.toImg(): Img {
    val out = FloatArray(width * height * 3)
    for (p in 0 until width * height) {
        out[p * 3] = rgba[p * 4].coerceIn(0f, 1f)
        out[p * 3 + 1] = rgba[p * 4 + 1].coerceIn(0f, 1f)
        out[p * 3 + 2] = rgba[p * 4 + 2].coerceIn(0f, 1f)
    }
    return Img(width, height, 3, out)
}
