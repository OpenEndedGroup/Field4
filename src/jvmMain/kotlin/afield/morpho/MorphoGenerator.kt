package afield.morpho

import kotlin.random.Random

/**
 * Where MorphoHDL programs come from when nobody writes them.
 *
 * A sketch fixes everything about a cell that the search is *not* looking for — its name, its
 * ports, what it falls back into — and leaves the body open. That split is deliberate: "find a
 * circuit" with nothing fixed is a search over parse errors, and the interesting question is not
 * whether a random walk can discover the token `def`.
 *
 * ## Repair rather than reject
 *
 * Mutation operators here are allowed to produce nonsense, and [MorphoProgramGenerator.repair]
 * projects it back into the region the width model calls plausible: an argument that names a wire
 * out of scope is rewired, a `SPLIT` of a one-bit bus is deleted, a recursive call that would not
 * narrow has its arguments resampled. Rejection-and-resample would be simpler and much worse —
 * with a rejection rate near one the search stops being a search.
 *
 * The compiler still has the last word. Repair only makes candidates *likely* to compile; the
 * fraction that does not is reported as [MorphoSearchRun.failureRate] rather than hidden.
 */

/**
 * The fixed frame around one evolvable cell.
 *
 * @param designWidth the bus width the width model reasons at. Use the widest width in the spec:
 *   a recursion that narrows correctly at 8 bits bottoms out early at 2 rather than breaking, which
 *   is MorphoHDL working as designed.
 * @param allowSelf whether the cell may call itself. Off for a base case, on for a recursion.
 */
class MorphoCellSketch(
    val name: String,
    val inputs: List<String>,
    val inputRoles: List<MorphoPortRole>,
    val outputRoles: List<MorphoPortRole>,
    val palette: MorphoPalette,
    val fallback: String? = null,
    val designWidth: Int = 8,
    val allowSplit: Boolean = true,
    val allowCat: Boolean = true,
    val allowSelf: Boolean = true,
    val minStatements: Int = 2,
    val maxStatements: Int = 10,
    /**
     * Statements always present at the top of the body, which mutation cannot remove.
     *
     * The dial between "search" and "sketch". With an empty prologue the search has to discover
     * that a MorphoHDL cell divides its inputs before it can discover anything else; with
     * `SPLIT(a)` and `SPLIT(b)` given, the divide is handed over and only the conquer is searched.
     * Both are worth running, and which one a result came from is the difference between an
     * interesting claim and a misleading one — so it is a field on the sketch and not a flag.
     */
    val prologue: List<MorphoStatement> = emptyList(),
) {
    init {
        require(inputs.size == inputRoles.size) { "$name: ${inputs.size} input names for ${inputRoles.size} roles" }
        require(minStatements in 0..maxStatements) { "$name: minStatements must be in 0..maxStatements" }
        require(palette.luts.isNotEmpty()) { "$name: the palette is empty, so there is nothing to build with" }
    }

    val inputWidths: IntArray =
        IntArray(inputRoles.size) { if (inputRoles[it] == MorphoPortRole.BUS) designWidth else 1 }

    val outputWidths: IntArray =
        IntArray(outputRoles.size) { if (outputRoles[it] == MorphoPortRole.BUS) designWidth else 1 }

    private val busPort: Int = inputRoles.indexOf(MorphoPortRole.BUS)

    /**
     * What a recursive call returns, given what it is passed.
     *
     * The bus outputs take the width of the call's first bus argument, which is the invariant every
     * divide-and-conquer cell in morpho's own library satisfies — `ripple_adder` returns a sum as
     * wide as the `a` it was given (`tiny_morpho.py`, `# a: [N], b: [N], c: [1] -> sum: [N]`).
     */
    fun selfShape(argWidths: IntArray): IntArray {
        val bus = if (busPort >= 0) argWidths.getOrElse(busPort) { 1 } else 1
        return IntArray(outputRoles.size) { if (outputRoles[it] == MorphoPortRole.BUS) bus else 1 }
    }

    /** The generator's opcode set for this cell: the palette, plus whichever structural ops are on. */
    val opcodes: List<MorphoOpcode> = buildList {
        addAll(palette.ops)
        if (allowSplit) add(MorphoOpcode("SPLIT", 1, 2, MorphoOpKind.SPLIT))
        if (allowCat) add(MorphoOpcode("CAT", 2, 1, MorphoOpKind.CAT))
        if (allowSelf) add(MorphoOpcode(name, inputs.size, outputRoles.size, MorphoOpKind.SELF))
    }

    fun trace(cell: MorphoCellGenome): MorphoWidthModel.Trace =
        MorphoWidthModel.trace(cell, inputWidths, palette, ::selfShape)
}

/**
 * Random programs, and small changes to programs.
 *
 * One generator serves every driver in [MorphoSearch], which is what makes the control arm honest:
 * random search and evolution draw from *the same* distribution, so a difference between them is a
 * difference in selection and not in the proposal.
 */
class MorphoProgramGenerator(
    val sketches: List<MorphoCellSketch>,
    val preamble: String,
) {
    init {
        require(sketches.isNotEmpty()) { "a generator needs at least one cell sketch" }
    }

    /** A fresh program drawn from the sketch's distribution. */
    fun random(rng: Random): MorphoGenome =
        MorphoGenome(sketches.map { randomCell(it, rng) }, preamble).normalized()

    /** One point change, repaired and pruned. */
    fun mutate(genome: MorphoGenome, rng: Random): MorphoGenome {
        val which = rng.nextInt(genome.cells.size)
        val sketch = sketches[which]
        val mutated = mutateCell(genome.cells[which], sketch, rng)
        val cells = genome.cells.toMutableList()
        cells[which] = settle(mutated, sketch, rng)
        return MorphoGenome(cells, genome.preamble).normalized()
    }

    /** [n] independent point changes — a bigger jump, for restarting a stuck climb. */
    fun mutate(genome: MorphoGenome, rng: Random, n: Int): MorphoGenome {
        var g = genome
        repeat(n.coerceAtLeast(1)) { g = mutate(g, rng) }
        return g
    }

    /**
     * Take whole cells from either parent.
     *
     * Splicing statements between two cells would mostly produce wire salad — the names mean
     * nothing across programs — whereas a genome of a recursion plus its base case has an obvious
     * seam, and recombining across it is the classic reason to have a population at all.
     */
    fun crossover(a: MorphoGenome, b: MorphoGenome, rng: Random): MorphoGenome =
        MorphoGenome(a.cells.indices.map { if (rng.nextBoolean()) a.cells[it] else b.cells[it] }, a.preamble)
            .normalized()

    // ---------------------------------------------------------------- construction

    private fun randomCell(sketch: MorphoCellSketch, rng: Random): MorphoCellGenome {
        val env = Env(sketch)
        val statements = ArrayList<MorphoStatement>()
        sketch.prologue.forEach { statements += it; env.define(it, sketch) }
        val n = sketch.minStatements + rng.nextInt(sketch.maxStatements - sketch.minStatements + 1)
        repeat(n) {
            propose(sketch, env, rng)?.let { s ->
                statements += s
                env.define(s, sketch)
            }
        }
        val cell = MorphoCellGenome(
            sketch.name, sketch.inputs, statements, chooseOutputs(sketch, env, rng), sketch.fallback
        )
        return settle(cell, sketch, rng)
    }

    /**
     * One statement whose operands the width model believes in, or null when nothing fits.
     *
     * Opcode choice is uniform over what is *legal here*, not over the vocabulary: with three wires
     * in scope and none of them two bits wide, `SPLIT` is not a coin flip that wastes a statement,
     * it is simply not offered.
     */
    private fun propose(sketch: MorphoCellSketch, env: Env, rng: Random): MorphoStatement? {
        val legal = sketch.opcodes.filter { op ->
            when (op.kind) {
                MorphoOpKind.SPLIT -> env.widest() >= 2
                MorphoOpKind.CAT -> env.size >= 1
                MorphoOpKind.SELF -> env.canNarrow()
                MorphoOpKind.LUT -> true
            }
        }
        if (legal.isEmpty()) return null
        val op = legal[rng.nextInt(legal.size)]
        val args = when (op.kind) {
            MorphoOpKind.SPLIT -> listOf(env.pickAtLeast(2, rng) ?: return null)
            MorphoOpKind.CAT -> {
                val a = env.pickAny(rng)
                val b = env.pickAny(rng)
                if (env.width(a) + env.width(b) > 4 * sketch.designWidth) return null
                listOf(a, b)
            }
            MorphoOpKind.SELF -> env.pickNarrowing(rng) ?: return null
            MorphoOpKind.LUT -> env.pickBroadcastable(op.arity, rng)
        }
        return MorphoStatement(List(op.results) { env.fresh() }, op.name, args)
    }

    private fun chooseOutputs(sketch: MorphoCellSketch, env: Env, rng: Random): List<String> =
        sketch.outputWidths.map { want -> env.pickWidth(want, rng) }

    // ---------------------------------------------------------------- mutation operators

    private fun mutateCell(cell: MorphoCellGenome, sketch: MorphoCellSketch, rng: Random): MorphoCellGenome {
        val operators = buildList<() -> MorphoCellGenome> {
            add { rewireArgument(cell, sketch, rng) }
            add { swapOpcode(cell, sketch, rng) }
            add { insertStatement(cell, sketch, rng) }
            add { graftStatement(cell, sketch, rng) }
            add { rewireOutput(cell, sketch, rng) }
            if (cell.statements.size > sketch.minStatements) add { deleteStatement(cell, rng) }
            if (cell.statements.isNotEmpty() && cell.statements.size < sketch.maxStatements) {
                add { duplicateStatement(cell, rng) }
            }
        }
        return operators[rng.nextInt(operators.size)]()
    }

    /** Point the *k*-th argument of one statement at a different wire that is already in scope. */
    private fun rewireArgument(cell: MorphoCellGenome, sketch: MorphoCellSketch, rng: Random): MorphoCellGenome {
        val candidates = cell.statements.indices.filter { cell.statements[it].args.isNotEmpty() }
        if (candidates.isEmpty()) return cell
        val at = candidates[rng.nextInt(candidates.size)]
        val env = envUpTo(cell, sketch, at)
        val s = cell.statements[at]
        val k = rng.nextInt(s.args.size)
        val replacement = env.pickAny(rng)
        val args = s.args.toMutableList().also { it[k] = replacement }
        return cell.copy(statements = cell.statements.toMutableList().also { it[at] = s.copy(args = args) })
    }

    /**
     * Swap one standard cell for another — *including* one of a different arity, whose extra
     * operand [repair] then supplies.
     *
     * Restricting this to equal arities was the single most expensive mistake in the first working
     * version. The full adder that hill climbing kept getting to was `sum = Xor3(a, b, c)`,
     * `c_out = And(a, b)` — two of sixteen bits wrong, and one `And` → `Maj3` away from exact. With
     * arity frozen, that swap did not exist, and the only route to it was insert-then-rewire, two
     * coordinated mutations with a combined probability around 10^-4.
     */
    private fun swapOpcode(cell: MorphoCellGenome, sketch: MorphoCellSketch, rng: Random): MorphoCellGenome {
        val at = cell.statements.indices.filter { i ->
            sketch.palette.luts.any { it.name == cell.statements[i].op }
        }.randomOrNull(rng) ?: return cell
        val s = cell.statements[at]
        val others = sketch.palette.luts.filter { it.name != s.op }
        if (others.isEmpty()) return cell
        val op = others[rng.nextInt(others.size)]
        return cell.copy(statements = cell.statements.toMutableList().also { it[at] = s.copy(op = op.name) })
    }

    /**
     * Add a statement *and* wire an output to it in one move.
     *
     * A plain insert is neutral by construction — nothing reads the new wire, so the phenotype does
     * not change — and it only pays off if a later, independent mutation happens to point something
     * at it. This operator does both halves at once, which is how a search gets to try a new idea
     * rather than merely to store one.
     */
    private fun graftStatement(cell: MorphoCellGenome, sketch: MorphoCellSketch, rng: Random): MorphoCellGenome {
        if (cell.statements.size >= sketch.maxStatements || cell.outputs.isEmpty()) return cell
        val at = rng.nextInt(cell.statements.size + 1)
        val env = envUpTo(cell, sketch, at)
        val s = propose(sketch, env, rng) ?: return cell
        val port = rng.nextInt(cell.outputs.size)
        val target = s.targets.getOrNull(rng.nextInt(s.targets.size)) ?: return cell
        return cell.copy(
            statements = cell.statements.toMutableList().also { it.add(at, s) },
            outputs = cell.outputs.toMutableList().also { it[port] = target },
        )
    }

    private fun insertStatement(cell: MorphoCellGenome, sketch: MorphoCellSketch, rng: Random): MorphoCellGenome {
        if (cell.statements.size >= sketch.maxStatements) return cell
        val at = rng.nextInt(cell.statements.size + 1)
        val env = envUpTo(cell, sketch, at)
        val s = propose(sketch, env, rng) ?: return cell
        return cell.copy(statements = cell.statements.toMutableList().also { it.add(at, s) })
    }

    private fun deleteStatement(cell: MorphoCellGenome, rng: Random): MorphoCellGenome {
        if (cell.statements.isEmpty()) return cell
        val at = rng.nextInt(cell.statements.size)
        return cell.copy(statements = cell.statements.toMutableList().also { it.removeAt(at) })
    }

    private fun duplicateStatement(cell: MorphoCellGenome, rng: Random): MorphoCellGenome {
        if (cell.statements.isEmpty()) return cell
        val at = rng.nextInt(cell.statements.size)
        val s = cell.statements[at]
        val copy = s.copy(targets = s.targets.map { it + "d" + rng.nextInt(1 shl 20) })
        return cell.copy(statements = cell.statements.toMutableList().also { it.add(at + 1, copy) })
    }

    private fun rewireOutput(cell: MorphoCellGenome, sketch: MorphoCellSketch, rng: Random): MorphoCellGenome {
        if (cell.outputs.isEmpty()) return cell
        val env = envUpTo(cell, sketch, cell.statements.size)
        val port = rng.nextInt(cell.outputs.size)
        val outs = cell.outputs.toMutableList()
        outs[port] = env.pickWidth(sketch.outputWidths[port], rng)
        return cell.copy(outputs = outs)
    }

    // ---------------------------------------------------------------- repair

    /**
     * Rebuild a mutated cell so the width model has no complaints left, dropping what cannot be
     * saved.
     *
     * The order matters: statements are walked front to back with a live scope, so a repair to an
     * early statement is visible to a later one, and a statement whose operands have all been
     * deleted goes with them.
     */
    fun repair(cell: MorphoCellGenome, sketch: MorphoCellSketch, rng: Random): MorphoCellGenome {
        val env = Env(sketch)
        val kept = ArrayList<MorphoStatement>(cell.statements.size + sketch.prologue.size)
        // The prologue is re-established here rather than protected in every operator: an operator
        // that deletes or overwrites one of its statements simply has that edit undone.
        sketch.prologue.forEach { kept += it; env.define(it, sketch) }
        val reserved = sketch.prologue.flatMap { it.targets }.toSet()

        for (s in cell.statements) {
            if (s.targets.any { it in reserved }) continue
            if (kept.size >= sketch.maxStatements) break
            val args = s.args.map { if (env.has(it)) it else env.pickAny(rng) }
            val fixed: MorphoStatement? = when {
                s.op == "SPLIT" -> {
                    val a = args.firstOrNull()?.takeIf { env.width(it) >= 2 } ?: env.pickAtLeast(2, rng)
                    if (a == null) null else MorphoStatement(targets(s, 2, env), "SPLIT", listOf(a))
                }

                s.op == "CAT" -> {
                    val two = padTo(args, 2, env, rng)
                    if (two.sumOf { env.width(it) } > 4 * sketch.designWidth) null
                    else MorphoStatement(targets(s, 1, env), "CAT", two)
                }

                s.op == sketch.name -> {
                    val a = repairSelf(args, sketch, env, rng)
                    if (a == null) null
                    else MorphoStatement(targets(s, sketch.outputRoles.size, env), sketch.name, a)
                }

                else -> {
                    val def = sketch.palette.luts.firstOrNull { it.name == s.op }
                    if (def == null) null else {
                        val a = repairLut(padTo(args, def.arity, env, rng), env, rng)
                        MorphoStatement(targets(s, 1, env), def.name, a)
                    }
                }
            }
            if (fixed != null) {
                kept += fixed
                env.define(fixed, sketch)
            }
        }

        val outputs = cell.outputs.mapIndexed { port, wire ->
            val want = sketch.outputWidths[port]
            if (env.has(wire) && env.width(wire) == want) wire else env.pickWidth(want, rng)
        }
        return cell.copy(statements = kept, outputs = outputs)
    }

    /**
     * Repair, then guarantee the recursion stops — dropping self-calls, latest first, until
     * [MorphoWidthModel.terminates] agrees.
     *
     * Latest first because an early self-call is usually the one the rest of the body was built
     * around, and removing it cascades into every wire downstream. This is the last thing that
     * happens to a candidate before it is rendered, so nothing can reintroduce a cycle afterwards.
     */
    fun settle(cell: MorphoCellGenome, sketch: MorphoCellSketch, rng: Random): MorphoCellGenome {
        var c = repair(cell, sketch, rng)
        repeat(sketch.maxStatements + 1) {
            // On the pruned cell: a self-call nothing reads is never compiled and so cannot hang.
            if (MorphoWidthModel.terminates(
                    c.pruned(), sketch.inputWidths, sketch.palette, sketch::selfShape, 4 * sketch.designWidth
                )
            ) return c
            val at = c.statements.indexOfLast { it.op == sketch.name }
            if (at < 0) return c
            c = repair(c.copy(statements = c.statements.filterIndexed { i, _ -> i != at }), sketch, rng)
        }
        return c.copy(statements = c.statements.filter { it.op != sketch.name })
            .let { repair(it, sketch, rng) }
    }

    /** Widen or trim an argument list to the arity the opcode actually has. */
    private fun padTo(args: List<String>, arity: Int, env: Env, rng: Random): List<String> = when {
        args.size == arity -> args
        args.size > arity -> args.take(arity)
        else -> args + List(arity - args.size) { env.pickAny(rng) }
    }

    /** Force every operand to a width morpho would broadcast: all equal, or width 1. */
    private fun repairLut(args: List<String>, env: Env, rng: Random): List<String> {
        val wide = args.maxOfOrNull { env.width(it) } ?: 1
        return args.map { a ->
            val w = env.width(a)
            if (w == 1 || w == wide) a else env.pickWidth(wide, rng)
        }
    }

    /** Make a recursive call narrow, or give up on it. */
    private fun repairSelf(args: List<String>, sketch: MorphoCellSketch, env: Env, rng: Random): List<String>? {
        var best = padTo(args, sketch.inputs.size, env, rng)
        repeat(8) {
            if (best.withIndex().all { (i, a) -> env.width(a) <= env.narrowCap(i) }) return best
            best = env.pickNarrowing(rng) ?: return null
        }
        return null
    }

    private fun targets(s: MorphoStatement, n: Int, env: Env): List<String> =
        List(n) { s.targets.getOrNull(it)?.takeIf { t -> !env.has(t) } ?: env.fresh() }

    private fun envUpTo(cell: MorphoCellGenome, sketch: MorphoCellSketch, at: Int): Env {
        val env = Env(sketch)
        for (i in 0 until at.coerceAtMost(cell.statements.size)) env.define(cell.statements[i], sketch)
        return env
    }

    /**
     * The wires in scope at a point in a cell body, with the width model's opinion of each.
     *
     * Seeded with morpho's two constants as well as the cell's ports: `ZERO` and `ONE` are ordinary
     * identifiers to `compiler.js:949`, and a search that cannot say "and this bit is zero" cannot
     * build an alignment shift or a carry seed.
     */
    private class Env(sketch: MorphoCellSketch) {
        private val order = ArrayList<String>()
        private val widths = HashMap<String, Int>()
        private var counter = 1000
        private val inputTotal = sketch.inputWidths.sum()
        private val inputWidths = sketch.inputWidths

        init {
            MorphoWidthModel.CONSTANTS.forEach { (n, w) -> put(n, w) }
            sketch.inputs.forEachIndexed { i, n -> put(n, sketch.inputWidths[i]) }
        }

        val size: Int get() = order.size

        fun has(wire: String): Boolean = wire in widths
        fun width(wire: String): Int = widths[wire] ?: 1
        fun widest(): Int = widths.values.maxOrNull() ?: 1
        fun fresh(): String = "v${counter++}"

        fun define(s: MorphoStatement, sketch: MorphoCellSketch) {
            val w = s.args.map { width(it) }
            when {
                s.op == "SPLIT" -> {
                    val x = w.firstOrNull() ?: 1
                    s.targets.getOrNull(0)?.let { put(it, x / 2) }
                    s.targets.getOrNull(1)?.let { put(it, x - x / 2) }
                }
                s.op == "CAT" -> s.targets.getOrNull(0)?.let { put(it, w.sum()) }
                s.op == sketch.name -> {
                    val shape = sketch.selfShape(w.toIntArray())
                    s.targets.forEachIndexed { i, t -> put(t, shape.getOrElse(i) { 1 }) }
                }
                else -> s.targets.getOrNull(0)?.let { put(it, w.maxOrNull() ?: 1) }
            }
        }

        fun pickAny(rng: Random): String = order[rng.nextInt(order.size)]

        fun pickAtLeast(w: Int, rng: Random): String? =
            order.filter { width(it) >= w }.randomOrNull(rng)

        /** Nearest available width, so a search is never handed a wire it will be scored down for. */
        fun pickWidth(want: Int, rng: Random): String {
            val exact = order.filter { width(it) == want }
            if (exact.isNotEmpty()) return exact[rng.nextInt(exact.size)]
            return order.minByOrNull { kotlin.math.abs(width(it) - want) } ?: "ZERO"
        }

        /** One wire of width [want] or 1, which is what morpho's LUT broadcast accepts. */
        fun pickBroadcastable(arity: Int, rng: Random): List<String> {
            val carrier = pickAny(rng)
            val w = width(carrier)
            val usable = order.filter { width(it) == w || width(it) == 1 }
            return List(arity) { i -> if (i == 0) carrier else usable[rng.nextInt(usable.size)] }
        }

        /** The widest a self-call may pass to port [i] and still be walking downhill. */
        fun narrowCap(i: Int): Int = if (inputWidths[i] >= 2) inputWidths[i] / 2 else inputWidths[i]

        fun canNarrow(): Boolean =
            inputTotal > inputWidths.size && inputWidths.indices.all { i -> order.any { width(it) <= narrowCap(i) } }

        /** An argument list for a self-call that strictly reduces total bus width. */
        fun pickNarrowing(rng: Random): List<String>? {
            repeat(12) {
                val args = inputWidths.indices.map { i ->
                    val cap = narrowCap(i)
                    val fits = order.filter { width(it) <= cap }
                    if (fits.isEmpty()) return@repeat
                    // Exactly-half first: a recursion that narrows by one bit is technically
                    // terminating and practically a 32-level unrolling, which is not what anyone
                    // means by divide and conquer.
                    val halves = fits.filter { width(it) == cap }
                    if (halves.isNotEmpty() && rng.nextInt(4) > 0) halves[rng.nextInt(halves.size)]
                    else fits[rng.nextInt(fits.size)]
                }
                if (args.sumOf { width(it) } < inputTotal) return args
            }
            return null
        }

        private fun put(name: String, w: Int) {
            if (name !in widths) order += name
            widths[name] = w.coerceAtLeast(0)
        }
    }
}

private fun <T> List<T>.randomOrNull(rng: Random): T? = if (isEmpty()) null else this[rng.nextInt(size)]
