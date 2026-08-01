package afield.morpho

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * What a MorphoHDL design is *supposed* to do, and how much it is allowed to cost — the half of a
 * search that morpho itself has no opinion about.
 *
 * Everything in this file evaluates candidate designs by running them: [MorphoRuntime] compiles the
 * candidate's source with upstream's parser and upstream's compiler, [MorphoSim] runs the resulting
 * netlist, and [MorphoTiming] — morpho's own `updateTimes()` — supplies the depth. Nothing here
 * models a circuit. A search whose fitness function was Field's idea of what a netlist would look
 * like would be searching Field's imagination, and the point of the bridge is that it does not have
 * to.
 */

/**
 * The role a port plays in a **width-parametric** design.
 *
 * MorphoHDL cells do not declare bus widths — a design is a claim quantified over all N — so a
 * specification has to say which ports scale with N and which are single wires. `ripple_adder`'s
 * `a` and `b` are [BUS]; its carry in and carry out are [BIT].
 */
enum class MorphoPortRole { BUS, BIT }

/**
 * A functional oracle plus the widths it must hold at.
 *
 * The width family is the part that matters and the part that is specific to this language. A
 * combinational-logic search normally checks one circuit against one truth table; a MorphoHDL
 * design is a *family*, so [widths] lists several and [MorphoEvaluator] compiles the candidate once
 * per width and scores the worst of them. An individual that adds correctly at four bits and not at
 * sixteen has not found an adder, and this is where that gets noticed rather than in the write-up.
 *
 * @param oracle `(inputs, busWidth) -> outputs`, both indexed by port, values as unsigned integers.
 * @param exhaustiveLimit compile-time input-space size below which every vector is tried. Above it
 *   the evaluator falls back to [sampleCount] seeded-random vectors, which is a weaker check and is
 *   reported as such by [MorphoEvaluator.exhaustiveAt].
 */
class MorphoSpec(
    val name: String,
    val inputRoles: List<MorphoPortRole>,
    val outputRoles: List<MorphoPortRole>,
    val widths: List<Int>,
    val exhaustiveLimit: Int = 1 shl 13,
    val sampleCount: Int = 384,
    val seed: Int = 20260801,
    val oracle: (LongArray, Int) -> LongArray,
) {
    init {
        require(inputRoles.isNotEmpty()) { "a spec needs at least one input port" }
        require(outputRoles.isNotEmpty()) { "a spec needs at least one output port" }
        require(widths.isNotEmpty()) { "a spec needs at least one width to check at" }
        require(widths.all { it >= 1 }) { "widths must be >= 1, got $widths" }
    }

    /** The signature `create()` wants: [MorphoPortRole.BUS] ports at [width], [MorphoPortRole.BIT] at 1. */
    fun signature(width: Int): IntArray =
        IntArray(inputRoles.size) { if (inputRoles[it] == MorphoPortRole.BUS) width else 1 }

    /** The output widths a correct design must produce at [width]. */
    fun outputWidths(width: Int): IntArray =
        IntArray(outputRoles.size) { if (outputRoles[it] == MorphoPortRole.BUS) width else 1 }

    /** How many distinct input vectors exist at [width], saturating rather than overflowing. */
    fun inputSpace(width: Int): Long {
        var n = 1L
        signature(width).forEach {
            if (n > Long.MAX_VALUE / (1L shl it)) return Long.MAX_VALUE
            n *= (1L shl it)
        }
        return n
    }

    /** True when [samples] at [width] is the whole input space rather than a sample of it. */
    fun isExhaustiveAt(width: Int): Boolean = inputSpace(width) <= exhaustiveLimit

    /** `samples[port][i]`, cached: a search asks for the same vectors thousands of times. */
    fun samples(width: Int): Array<LongArray> = sampleCache.getOrPut(width) { buildSamples(width) }

    /** `expected[port][i]`, the oracle's answer for [samples]. */
    fun expected(width: Int): Array<LongArray> = expectedCache.getOrPut(width) {
        val ins = samples(width)
        val n = ins[0].size
        val out = Array(outputRoles.size) { LongArray(n) }
        val one = LongArray(ins.size)
        for (i in 0 until n) {
            for (p in ins.indices) one[p] = ins[p][i]
            val got = oracle(one, width)
            require(got.size == outputRoles.size) {
                "oracle for '$name' returned ${got.size} outputs, spec declares ${outputRoles.size}"
            }
            for (p in got.indices) out[p][i] = got[p]
        }
        out
    }

    /**
     * The same specification checked at a different width family.
     *
     * The one operation a curriculum needs: search at `at(listOf(2))`, then re-score what was found
     * at `at(listOf(2, 4, 8, 16))` and see whether it was an adder or an accident.
     */
    fun at(widths: List<Int>): MorphoSpec = MorphoSpec(
        name, inputRoles, outputRoles, widths, exhaustiveLimit, sampleCount, seed, oracle
    )

    /** Total oracle output bits per width — the denominator of the bit-error rate. */
    fun outputBits(width: Int): Int = outputWidths(width).sum() * samples(width)[0].size

    /**
     * One **case** per (width, output port, bit position) — the granularity lexicase selection needs.
     *
     * Aggregating an adder's error into one number throws away the only structure the problem has:
     * bit 0 needs no carry, bit 1 needs one, bit 7 needs seven. A per-bit case vector lets a
     * selection operator notice an individual that is uniquely good at the high bits, which is
     * exactly the individual that has started to use the recursion.
     */
    val caseCount: Int by lazy { widths.sumOf { outputWidths(it).sum() } }

    /** Human-readable name of case *i*, for reporting. */
    fun caseName(index: Int): String {
        var k = index
        for (w in widths) {
            val ow = outputWidths(w)
            for (port in ow.indices) for (bit in 0 until ow[port]) {
                if (k == 0) return "w$w:p$port:b$bit"
                k--
            }
        }
        return "case$index"
    }

    private val sampleCache = HashMap<Int, Array<LongArray>>()
    private val expectedCache = HashMap<Int, Array<LongArray>>()

    private fun buildSamples(width: Int): Array<LongArray> {
        val sig = signature(width)
        val space = inputSpace(width)
        if (space <= exhaustiveLimit) {
            val n = space.toInt()
            val out = Array(sig.size) { LongArray(n) }
            for (i in 0 until n) {
                var rest = i.toLong()
                for (p in sig.indices) {
                    val span = 1L shl sig[p]
                    out[p][i] = rest % span
                    rest /= span
                }
            }
            return out
        }
        // Seeded so two search runs are comparable, and edge-heavy on purpose: all-ones and
        // all-zeros are where a carry chain and an off-by-one disagree, and uniform random draws
        // hit them with probability 2^-N.
        val rng = Random(seed + width)
        val n = sampleCount
        val out = Array(sig.size) { LongArray(n) }
        for (i in 0 until n) {
            for (p in sig.indices) {
                // Masked draws rather than nextLong(span): at 63 bits `1L shl 63` is Long.MIN_VALUE
                // and the bounded overload throws "Random range is empty", which is a crash that
                // only appears when a design is retested at a width nothing was searched at.
                val mask = if (sig[p] >= 63) Long.MAX_VALUE else (1L shl sig[p]) - 1
                out[p][i] = when (i) {
                    0 -> 0L
                    1 -> mask
                    2 -> if (p == 0) mask else 1L
                    else -> rng.nextLong() and mask
                }
            }
        }
        return out
    }

    companion object {
        /**
         * `sum, c_out = a + b + c_in` — the specification `ripple_adder` and `brent_kung_adder` are
         * two different answers to.
         *
         * Two ports in, three, both roles present, and an oracle whose hard cases (a full-width
         * carry propagation) are exactly the ones a nearly-right circuit gets wrong. It is the
         * standard benchmark for this kind of search for good reasons.
         */
        fun adder(widths: List<Int> = listOf(2, 4, 8), name: String = "adder"): MorphoSpec =
            MorphoSpec(
                name = name,
                inputRoles = listOf(MorphoPortRole.BUS, MorphoPortRole.BUS, MorphoPortRole.BIT),
                outputRoles = listOf(MorphoPortRole.BUS, MorphoPortRole.BIT),
                widths = widths,
            ) { ins, w ->
                val total = ins[0] + ins[1] + ins[2]
                longArrayOf(total and ((1L shl w) - 1), total ushr w)
            }

        /** `y = a XOR b` bitwise — the easiest parametric target there is, and so the pipeline's canary. */
        fun bitwiseXor(widths: List<Int> = listOf(2, 4, 8), name: String = "bitxor"): MorphoSpec =
            MorphoSpec(
                name = name,
                inputRoles = listOf(MorphoPortRole.BUS, MorphoPortRole.BUS),
                outputRoles = listOf(MorphoPortRole.BUS),
                widths = widths,
            ) { ins, _ -> longArrayOf(ins[0] xor ins[1]) }

        /**
         * `y = (x != 0)` — an N-bit bus reduced to one bit.
         *
         * The target that separates "can this search find a recursion" from "can this search find
         * an adder", and the reason is the shape of the landscape rather than the size of the
         * program.
         *
         * A reduction **cannot** be done without recursion in this vocabulary. Morpho's LUTs are
         * broadcast — `compiler.js:964` sizes a LUT cell to its widest operand — so no amount of
         * `Or` applied to an eight-bit bus produces one bit. The only route from N wires down to
         * one is to keep `SPLIT`ting, which means every correct answer here is genuinely
         * divide-and-conquer rather than a broadcast that happened to fit.
         *
         * And unlike parity, it has a gradient. `Or`-reducing only the low half is wrong exactly
         * when the low half is zero and the high half is not — about 6% of eight-bit vectors — so a
         * half-built recursion scores 0.06 where a half-built parity scores the 0.5 of a coin toss.
         * Parity is the textbook deceptive function for exactly that reason.
         */
        fun orReduce(widths: List<Int> = listOf(2, 4, 8), name: String = "orreduce"): MorphoSpec =
            MorphoSpec(
                name = name,
                inputRoles = listOf(MorphoPortRole.BUS),
                outputRoles = listOf(MorphoPortRole.BIT),
                widths = widths,
            ) { ins, _ -> longArrayOf(if (ins[0] != 0L) 1L else 0L) }

        /** `y = (x == all ones)` — the same shape as [orReduce] with the dual gate. */
        fun andReduce(widths: List<Int> = listOf(2, 4, 8), name: String = "andreduce"): MorphoSpec =
            MorphoSpec(
                name = name,
                inputRoles = listOf(MorphoPortRole.BUS),
                outputRoles = listOf(MorphoPortRole.BIT),
                widths = widths,
            ) { ins, w -> longArrayOf(if (ins[0] == (1L shl w) - 1) 1L else 0L) }

        /**
         * `y = x + 1` — an increment, which is an adder with one operand nailed to a constant and
         * therefore a genuinely easier carry chain to find.
         */
        fun increment(widths: List<Int> = listOf(2, 4, 8), name: String = "increment"): MorphoSpec =
            MorphoSpec(
                name = name,
                inputRoles = listOf(MorphoPortRole.BUS, MorphoPortRole.BIT),
                outputRoles = listOf(MorphoPortRole.BUS, MorphoPortRole.BIT),
                widths = widths,
            ) { ins, w ->
                val total = ins[0] + ins[1]
                longArrayOf(total and ((1L shl w) - 1), total ushr w)
            }
    }
}

/**
 * What a compiled candidate costs, entirely in numbers the real netlist and morpho's own timing
 * produced.
 *
 * [gates] rather than [cells] is the size to optimise: a LUT cell is *broadcast* across output
 * slots (see [MorphoNetlist.slots]), so an 8-bit XOR is one cell and eight gates, and cell count
 * would reward a design for being wide rather than for being small.
 */
data class MorphoCost(
    val cells: Int,
    val luts: Int,
    val gates: Int,
    val depth: Int,
    val maxFanout: Int,
) {
    override fun toString(): String = "$gates gates, depth $depth, fanout $maxFanout"

    companion object {
        fun of(net: MorphoNetlist, timing: MorphoTiming): MorphoCost = MorphoCost(
            cells = net.activeCount,
            luts = net.lutCount,
            gates = net.gateCount,
            depth = timing.depth(net).toInt(),
            maxFanout = maxFanoutOf(net),
        )

        /**
         * The largest number of active input pins any one net feeds.
         *
         * Not a cost morpho charges — its compiler only inserts buffer trees when a `maxFanout` is
         * asked for — but a design that drives forty gates off one wire is not free in silicon, and
         * a search that is only told about gates and depth will happily find one.
         */
        fun maxFanoutOf(net: MorphoNetlist): Int {
            val counts = IntArray(net.nNets)
            for (cell in net.activeCells) {
                val start = net.pinStart(cell)
                for (p in 0 until net.pinCount(cell)) {
                    val n = net.pinNet[start + p]
                    if (n in 0 until net.nNets) counts[n]++
                }
            }
            return counts.maxOrNull() ?: 0
        }
    }
}

/**
 * How a candidate scored: how wrong it is, what it costs, and — when it did not compile at all —
 * why.
 *
 * [error] is the *fraction of output bits that disagreed with the oracle*, not a pass/fail. A
 * pass/fail landscape is flat everywhere except at the answer, which makes hill climbing exactly
 * as good as random sampling; a bit-error rate at least tells a search that getting the low bits of
 * a sum right is progress.
 *
 * ## Mean to search by, worst to believe
 *
 * [error] is the **mean** across the spec's widths and [worstError] is the maximum. The search
 * climbs the mean and [correct] demands that every width is exact, which are two different jobs.
 * An earlier version scored on the maximum alone and searched much worse: a candidate that has
 * genuinely learnt to add two bits, and has not yet learnt to carry across eight, is progress, and
 * a max-only score cannot see it. Nothing is claimed on the strength of the mean — a design is
 * correct here only if it is correct at every width in the family.
 */
class MorphoFitness(
    val error: Double,
    /** Bit-error rate at each of [MorphoSpec.widths], in order. */
    val perWidthError: DoubleArray,
    val cost: MorphoCost?,
    /** Non-null when the candidate did not compile, did not simulate, or was rejected as too big. */
    val failure: String?,
    val millis: Double = 0.0,
    private val caseErrors: DoubleArray = DoubleArray(0),
) {
    val valid: Boolean get() = failure == null

    /** Error rate per (width, port, bit) case — see [MorphoSpec.caseCount]. */
    val cases: DoubleArray get() = caseErrors

    /** The width that went worst — the number a claim of correctness has to answer to. */
    val worstError: Double get() = perWidthError.maxOrNull() ?: 1.0

    val correct: Boolean get() = failure == null && perWidthError.all { it == 0.0 }

    override fun toString(): String = when {
        failure != null -> "invalid ($failure)"
        correct -> "correct, ${cost}"
        else -> "err %.4f (worst %.4f), %s".format(error, worstError, cost ?: "no cost")
    }

    companion object {
        /** The score a candidate gets for not compiling: maximally wrong, and no cost to trade against. */
        fun failed(reason: String, widths: Int, cases: Int, millis: Double = 0.0): MorphoFitness =
            MorphoFitness(1.0, DoubleArray(widths) { 1.0 }, null, reason, millis, DoubleArray(cases) { 1.0 })
    }
}

/**
 * Compiles a candidate at every width in the spec and scores it.
 *
 * ## Why there is a watchdog
 *
 * MorphoHDL recursion terminates when a `SPLIT` runs out of bits — `primitives.js:41` returns
 * `null`, `compiler.js:1053` discards the graph, and the fallback takes over. A *generated* cell
 * body has no such guarantee: a self-call whose arguments are no narrower than its inputs recurses
 * forever, and one whose arguments are *wider* does so while doubling the bus each level, which
 * exhausts memory long before it exhausts the JS stack. [MorphoProgramGenerator] will not emit
 * either, but a mutation operator is not a proof, so every evaluation runs under a timeout that
 * calls [MorphoRuntime.interruptGuest]. A candidate that trips it is scored as a failure and the
 * search carries on.
 */
class MorphoEvaluator(
    val spec: MorphoSpec,
    val optimize: Boolean = true,
    /** Candidates whose netlist exceeds this many active cells are rejected unsimulated. */
    val cellBudget: Int = 40_000,
    val timeoutMillis: Long = 5_000,
) {
    var evaluations: Int = 0
        private set
    var failures: Int = 0
        private set
    var totalMillis: Double = 0.0
        private set

    /** True when the width's check was the whole input space rather than a sample of it. */
    fun exhaustiveAt(width: Int): Boolean = spec.isExhaustiveAt(width)

    /**
     * Compile [source] at every spec width, simulate it, and score it.
     *
     * @param design the cell name to build; defaults to the spec's name, which is what
     *   [MorphoGenome] names the cell it renders.
     */
    fun evaluate(source: String, design: String = spec.name): MorphoFitness {
        val t0 = System.nanoTime()
        evaluations++
        val perWidth = DoubleArray(spec.widths.size)
        val cases = ArrayList<Double>(spec.caseCount)
        var cost: MorphoCost? = null

        for ((i, width) in spec.widths.withIndex()) {
            val outcome = runCatching { compileAndScore(source, design, width) }
            val value = outcome.getOrElse { e ->
                failures++
                val ms = (System.nanoTime() - t0) / 1e6
                totalMillis += ms
                return MorphoFitness.failed(describe(e), spec.widths.size, spec.caseCount, ms)
            }
            perWidth[i] = value.perBit.average()
            cases.addAll(value.perBit.toList())
            cost = value.cost
        }

        val ms = (System.nanoTime() - t0) / 1e6
        totalMillis += ms
        return MorphoFitness(perWidth.average(), perWidth, cost, null, ms, cases.toDoubleArray())
    }

    /** Per-bit error rates at one width, and the cost of the circuit that produced them. */
    private class WidthResult(val perBit: DoubleArray, val cost: MorphoCost)

    private fun compileAndScore(source: String, design: String, width: Int): WidthResult {
        val guard = watchdog.schedule({ MorphoRuntime.interruptGuest() }, timeoutMillis, TimeUnit.MILLISECONDS)
        val net: MorphoNetlist
        val cost: MorphoCost
        try {
            MorphoRuntime.compile(source, design, spec.signature(width), optimize).use { session ->
                session.growFully()
                net = session.netlist()
                if (net.activeCount > cellBudget) {
                    throw MorphoException("netlist of ${net.activeCount} cells exceeds the $cellBudget budget", null)
                }
                cost = MorphoCost.of(net, session.timing())
            }
        } finally {
            guard.cancel(false)
        }

        if (net.inputCells.size != spec.inputRoles.size) {
            throw MorphoException(
                "design has ${net.inputCells.size} input ports, spec declares ${spec.inputRoles.size}", null
            )
        }
        if (net.outputCells.size != spec.outputRoles.size) {
            throw MorphoException(
                "design has ${net.outputCells.size} output ports, spec declares ${spec.outputRoles.size}", null
            )
        }

        val got = MorphoSim.evaluate(net, spec.samples(width))
        return WidthResult(bitErrors(got, spec.expected(width), spec.outputWidths(width)), cost)
    }

    /**
     * Fraction of expected output bits the circuit got wrong.
     *
     * A port the design made too narrow contributes its missing bits as errors rather than being
     * quietly dropped — otherwise a one-bit `sum` would score better than an eight-bit one that is
     * merely wrong somewhere, and the search would evolve stumps.
     */
    private fun bitErrors(got: Array<LongArray>, want: Array<LongArray>, widths: IntArray): DoubleArray {
        val out = DoubleArray(widths.sum())
        var at = 0
        for (port in widths.indices) {
            val g = got[port]
            val e = want[port]
            for (bit in 0 until widths[port]) {
                var wrong = 0
                val mask = 1L shl bit
                for (i in e.indices) if (((g[i] xor e[i]) and mask) != 0L) wrong++
                out[at++] = if (e.isEmpty()) 1.0 else wrong.toDouble() / e.size
            }
        }
        return out
    }

    private fun describe(e: Throwable): String = when (e) {
        is MorphoException -> e.message?.substringBefore('\n')?.take(160) ?: "morpho error"
        is StackOverflowError -> "stack overflow"
        else -> (e.message?.substringBefore('\n')?.take(160) ?: e::class.simpleName ?: "error")
    }

    companion object {
        private val watchdog: ScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "morpho-search-watchdog").apply { isDaemon = true }
            }
    }
}
