package afield.morpho

import kotlin.random.Random

/**
 * The thing a search actually mutates: a MorphoHDL **program, as text**.
 *
 * That choice is the whole design. A generator could build netlists directly and skip the compiler,
 * and it would be faster and it would be a lie — the netlist would be Field's idea of what morpho
 * compiles a design into, and the resulting search would optimise against that idea rather than
 * against MorphoHDL. So a genome renders to source, upstream's `parse()` reads it, upstream's
 * `compileModule()` compiles it, and anything the search finds can be pasted into a morpho box and
 * will still be there tomorrow.
 *
 * ## The grammar this has to hit
 *
 * `parser.js` is 190 lines and its accepted language is small (`parser.js:23-188`):
 *
 * * top-level `Name = LUT(arity, value)`;
 * * `@morpho` or `@morpho(fallback=X)`, then `def name(a, b, c):`;
 * * SSA lines `t0, t1 = OP(x, y)`, one per line;
 * * `return x, y`.
 *
 * That is all [MorphoGenome.render] emits, and it is why the rendered text is legible: it is not a
 * serialisation format, it is the language.
 *
 * ## Where the widths come from
 *
 * MorphoHDL infers bus widths and never declares them, so a generator that ignores widths produces
 * mostly-invalid programs. [MorphoWidthModel] is Field's *model* of that inference — it mirrors
 * `primitives.js`'s `SPLIT`/`CAT` and `compiler.js`'s `materializeLut` broadcast rule — and it is
 * used only to steer generation. The compiler remains the judge; the model's accuracy shows up as
 * [MorphoSearchRun.failureRate] and nowhere else.
 */

// ---------------------------------------------------------------- vocabulary

/** A LUT standard cell: a truth table and the number of inputs it reads. */
data class MorphoLut(val name: String, val arity: Int, val value: Int) {
    /** As `parser.js:165` wants to read it back. */
    fun declaration(): String {
        val bits = 1 shl arity
        var binary = value.toUInt().toString(2).padStart(bits, '0').takeLast(bits)
        if (arity >= 3) binary = binary.chunked(4).joinToString("_")
        return "%-6s = LUT(%d, 0b%s)".format(name, arity, binary)
    }
}

/** What a generated SSA line can be. */
enum class MorphoOpKind {
    /** Apply a standard cell from the palette. */
    LUT,

    /** `SPLIT(x)` — halve a bus. Returns `null` below width 2, which is how recursion terminates. */
    SPLIT,

    /** `CAT(x, y)` — join two buses. */
    CAT,

    /** Call the cell being generated. Only legal when the arguments strictly narrow. */
    SELF,
}

/** One callable thing, with its shape. */
data class MorphoOpcode(val name: String, val arity: Int, val results: Int, val kind: MorphoOpKind)

/**
 * The standard cells a search is allowed to build out of.
 *
 * [standard] lifts every `LUT(...)` declaration straight out of morpho's own standard library
 * rather than copying the truth tables into Kotlin, because a copy drifts and this one cannot: it
 * is the same bytes `tiny_morpho.py` executes and `parser.js` parses.
 */
class MorphoPalette(val luts: List<MorphoLut>) {
    val ops: List<MorphoOpcode> = luts.map { MorphoOpcode(it.name, it.arity, 1, MorphoOpKind.LUT) }

    /** The palette's declarations, ready to head a rendered program. */
    fun declarations(): String = luts.joinToString("\n") { it.declaration() }

    /** Only the cells with the given arities — a 1-bit base case has no use for a 4-input mux. */
    fun withArities(arities: Set<Int>): MorphoPalette = MorphoPalette(luts.filter { it.arity in arities })

    /** Only the named cells, in the order named. */
    fun only(vararg names: String): MorphoPalette =
        MorphoPalette(names.mapNotNull { n -> luts.firstOrNull { it.name == n } })

    companion object {
        private val DECL = Regex("""^\s*([A-Za-z_]\w*)\s*=\s*LUT\(\s*(\d+)\s*,\s*(\S+?)\s*\)""", RegexOption.MULTILINE)

        /** Every LUT declared in [source]; defaults to morpho's own standard library. */
        fun from(source: String = MorphoRuntime.standardLibrary): MorphoPalette = MorphoPalette(
            DECL.findAll(source).map { m ->
                val raw = m.groupValues[3].replace("_", "")
                val value = when {
                    raw.startsWith("0b") -> raw.substring(2).toInt(2)
                    raw.startsWith("0x") -> raw.substring(2).toInt(16)
                    else -> raw.toInt()
                }
                MorphoLut(m.groupValues[1], m.groupValues[2].toInt(), value)
            }.toList()
        )

        /** morpho's own eight standard cells. */
        val standard: MorphoPalette by lazy { from() }
    }
}

// ---------------------------------------------------------------- the program

/** One SSA line: `targets = op(args)`. */
data class MorphoStatement(val targets: List<String>, val op: String, val args: List<String>) {
    fun render(): String = "    ${targets.joinToString(", ")} = $op(${args.joinToString(", ")})"
}

/** One `@morpho def` — a cell body plus the ports it presents. */
data class MorphoCellGenome(
    val name: String,
    val inputs: List<String>,
    val statements: List<MorphoStatement>,
    val outputs: List<String>,
    val fallback: String?,
) {
    fun render(): String = buildString {
        append(if (fallback == null) "@morpho\n" else "@morpho(fallback=$fallback)\n")
        append("def ").append(name).append('(').append(inputs.joinToString(", ")).append("):\n")
        statements.forEach { append(it.render()).append('\n') }
        append("    return ").append(outputs.joinToString(", ")).append('\n')
    }

    /**
     * Drop statements nothing reachable from [outputs] consumes.
     *
     * Not cosmetic. A dead `SPLIT` whose operand is one bit wide still returns `null`, and
     * `compiler.js:1053` throws the *whole* body away when any instruction does — so an unused
     * statement can silently turn a working design into its own fallback. Pruning before compiling
     * is the difference between a search that works and one that mostly evaluates base cases.
     */
    fun pruned(): MorphoCellGenome {
        val live = HashSet(outputs)
        val keep = BooleanArray(statements.size)
        for (i in statements.indices.reversed()) {
            val s = statements[i]
            if (s.targets.none { it in live }) continue
            keep[i] = true
            live.addAll(s.args)
        }
        return copy(statements = statements.filterIndexed { i, _ -> keep[i] })
    }

    /** Rename every target to `t0, t1, …` in order, so two structurally identical cells render identically. */
    fun normalized(): MorphoCellGenome {
        val map = HashMap<String, String>()
        var n = 0
        val out = statements.map { s ->
            val args = s.args.map { map[it] ?: it }
            val targets = s.targets.map { t -> "t${n++}".also { map[t] = it } }
            MorphoStatement(targets, s.op, args)
        }
        return copy(statements = out, outputs = outputs.map { map[it] ?: it })
    }
}

/**
 * A whole searchable program: one or more cells plus whatever fixed text they are compiled against.
 *
 * The first cell is the root — the one [MorphoEvaluator] builds — and later cells are helpers it
 * may fall back into. Two cells is the interesting case: a recursion and its base, evolved together.
 */
data class MorphoGenome(
    val cells: List<MorphoCellGenome>,
    /** LUT declarations and any fixed helper cells, emitted verbatim above the evolved ones. */
    val preamble: String,
) {
    init {
        require(cells.isNotEmpty()) { "a genome needs at least one cell" }
    }

    val rootName: String get() = cells[0].name

    /**
     * The program **as the compiler sees it**: dead statements dropped, wires renumbered.
     *
     * Genotype and phenotype are deliberately not the same object here. Statements nothing reads
     * are kept in [cells] and removed only on the way out, which sounds like bookkeeping and is
     * actually the difference between a search that works and one that stops.
     *
     * The first version pruned the genome itself, and hill climbing reliably walked into a cell
     * with no statements at all — `return a, ZERO` — and then could not leave. Every operator
     * became a no-op: there was nothing to rewire, nothing to delete, and an inserted statement was
     * dead the instant it appeared, so pruning deleted it again and the mutation was identity.
     * Keeping introns means an inserted statement waits, silently, until some later mutation points
     * an output at it. That is what neutral drift is *for*.
     */
    fun render(): String = phenotype().let { p ->
        buildString {
            if (preamble.isNotBlank()) append(preamble.trimEnd()).append("\n\n")
            p.cells.forEach { append(it.render()).append('\n') }
        }.trimEnd() + "\n"
    }

    /** The live part of the program, which is what [render] emits and what gets compiled. */
    fun phenotype(): MorphoGenome = copy(cells = cells.map { it.pruned().normalized() })

    fun pruned(): MorphoGenome = copy(cells = cells.map { it.pruned() })

    fun normalized(): MorphoGenome = copy(cells = cells.map { it.normalized() })

    /** Statements across every cell, introns included — what the *genome* costs. */
    val statementCount: Int get() = cells.sumOf { it.statements.size }

    /** Statements that survive into the compiled circuit. */
    val liveStatementCount: Int get() = cells.sumOf { it.pruned().statements.size }
}

// ---------------------------------------------------------------- the width model

/**
 * Field's model of MorphoHDL's width inference, used to *steer* generation and never to judge it.
 *
 * Bus widths in morpho are lists of net indices, so every rule here is a length rule taken straight
 * from upstream:
 *
 * | construct | rule | upstream |
 * | --- | --- | --- |
 * | `SPLIT(x)` | `floor(w/2)`, `w - floor(w/2)`; invalid below `w = 2` | `primitives.js:39-44` |
 * | `CAT(a, b)` | `w(a) + w(b)` | `primitives.js:45-47` |
 * | LUT | `max(w)`, with width-1 operands broadcast | `compiler.js:962-973` |
 * | self-call | the spec's own output shape at the argument widths | — |
 *
 * The last row is the one that is a model rather than a transcription, because the compiler works a
 * recursive call's output widths out by compiling it. Getting it wrong costs a failed candidate,
 * which is counted.
 */
object MorphoWidthModel {

    /** Widths morpho gives its two built-in constants — one net each (`compiler.js:949`). */
    val CONSTANTS = mapOf("ZERO" to 1, "ONE" to 1)

    /**
     * Walk [cell] with [inputWidths] and report the width of every wire it defines.
     *
     * @param selfShape output widths of a recursive call, given its argument widths.
     * @return wire widths, and the reasons (if any) the body would not survive the compiler.
     */
    fun trace(
        cell: MorphoCellGenome,
        inputWidths: IntArray,
        palette: MorphoPalette,
        selfShape: (IntArray) -> IntArray,
    ): Trace {
        val widths = HashMap<String, Int>(CONSTANTS)
        cell.inputs.forEachIndexed { i, n -> widths[n] = inputWidths.getOrElse(i) { 1 } }
        val problems = ArrayList<String>()
        val lut = palette.luts.associateBy { it.name }
        val inputTotal = inputWidths.sum()
        var boundary = false

        for (s in cell.statements) {
            val args = s.args.map { widths[it] }
            if (args.any { it == null }) {
                problems += "${s.op} reads an undefined wire"
                s.targets.forEach { widths[it] = 1 }
                continue
            }
            @Suppress("UNCHECKED_CAST")
            val w = (args as List<Int>)
            when {
                s.op == "SPLIT" -> {
                    if (w.size != 1) problems += "SPLIT takes one argument, got ${w.size}"
                    val x = w.firstOrNull() ?: 1
                    if (x < 2) {
                        problems += "SPLIT of a $x-bit bus discards the body"
                        boundary = true
                    }
                    val half = x / 2
                    s.targets.getOrNull(0)?.let { widths[it] = half }
                    s.targets.getOrNull(1)?.let { widths[it] = x - half }
                }

                s.op == "CAT" -> s.targets.getOrNull(0)?.let { widths[it] = w.sum() }

                s.op == cell.name -> {
                    if (w.size != cell.inputs.size) problems += "recursive call takes ${cell.inputs.size} arguments, got ${w.size}"
                    val total = w.sum()
                    if (total >= inputTotal) {
                        problems += "recursive call does not narrow ($total bits in, $inputTotal available): it would not terminate"
                    }
                    val shape = selfShape(w.toIntArray())
                    s.targets.forEachIndexed { i, t -> widths[t] = shape.getOrElse(i) { 1 } }
                }

                lut.containsKey(s.op) -> {
                    val def = lut.getValue(s.op)
                    if (w.size != def.arity) problems += "${s.op} takes ${def.arity} inputs, got ${w.size}"
                    val wide = w.maxOrNull() ?: 1
                    if (w.any { it != 1 && it != wide }) {
                        problems += "${s.op} mixes widths ${w.joinToString("/")}; morpho only broadcasts width 1"
                    }
                    s.targets.getOrNull(0)?.let { widths[it] = wide }
                }

                else -> {
                    problems += "unknown operation ${s.op}"
                    s.targets.forEach { widths[it] = 1 }
                }
            }
        }

        cell.outputs.forEach { if (it !in widths) problems += "returns an undefined wire '$it'" }
        return Trace(widths, problems, boundary)
    }

    class Trace(val widths: Map<String, Int>, val problems: List<String>, val hitsBoundary: Boolean = false) {
        val ok: Boolean get() = problems.isEmpty()
        fun width(wire: String): Int = widths[wire] ?: 1
    }

    /**
     * Does this cell's recursion stop?
     *
     * The check that mattered most in practice. MorphoHDL terminates by *running out of bits*: a
     * `SPLIT` below width 2 returns `null` (`primitives.js:41`), the compiler throws the body away
     * (`compiler.js:1053`) and the fallback answers instead. A generated body has no such courtesy,
     * and the first version of this file learned it the slow way — `evolved(ONE, t0, ONE)`, where
     * `t0` is as wide as `b`, calls itself at the same signature forever.
     *
     * A local rule ("the arguments are narrower than the inputs") is not enough, because it is only
     * checked at the top and the body computes *different* widths at every level. So this walks the
     * signature graph instead: each distinct signature is traced, the self-calls in it give the
     * next signatures, and a signature revisited **on the current path** is a cycle and therefore a
     * hang. That is exact with respect to the width model, and the model is all the generator has.
     *
     * @param widthCap a signature wider than this is treated as divergent. A recursion whose bus
     *   grows is technically well-founded only in the sense that it eventually exhausts memory.
     */
    fun terminates(
        cell: MorphoCellGenome,
        inputWidths: IntArray,
        palette: MorphoPalette,
        selfShape: (IntArray) -> IntArray,
        widthCap: Int = 64,
        signatureBudget: Int = 256,
    ): Boolean {
        if (cell.statements.none { it.op == cell.name }) return true
        val onPath = HashSet<List<Int>>()
        val settled = HashSet<List<Int>>()
        var visits = 0

        fun visit(sig: IntArray): Boolean {
            if (++visits > signatureBudget) return false
            if (sig.any { it > widthCap || it < 0 }) return false
            val key = sig.toList()
            if (key in settled) return true
            if (!onPath.add(key)) return false

            val t = trace(cell, sig, palette, selfShape)
            var ok = true
            // A body that hits a boundary never runs; its fallback does, and a fallback cannot
            // recurse into this cell. So this signature is a leaf and the walk stops here.
            if (!t.hitsBoundary) {
                for (s in cell.statements) {
                    if (s.op != cell.name) continue
                    if (!visit(s.args.map { t.width(it) }.toIntArray())) {
                        ok = false
                        break
                    }
                }
            }
            onPath.remove(key)
            if (ok) settled += key
            return ok
        }

        return visit(inputWidths)
    }
}
