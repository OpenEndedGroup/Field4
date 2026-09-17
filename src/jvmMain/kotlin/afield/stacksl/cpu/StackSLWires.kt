package afield.stacksl.cpu

import afield.stacksl.StackSLCompileOptions
import afield.stacksl.StackSLCompiler
import afield.stacksl.StackSLDanglingWire
import afield.stacksl.StackSLSeverity
import java.util.Locale

/**
 * StackSL's dangling wires, made runnable on the CPU — the free-parameter vector a fit descends.
 *
 * `StackSLCompileOptions.externalizeConstants` already lifts every numeric literal out of a
 * program into an emitted `float <prefix>_wires[N]` array with a `<prefix>_init_wires`
 * initialiser, and records each one as a `StackSLDanglingWire` carrying its `index`, its
 * `initial`, and the source `token` it came from. So the parameter vector needs no new syntax.
 *
 * #### What did not work, and had to be bridged
 *
 * The externalised GLSL **cannot be parsed by this backend at all**, and no CPU path had ever
 * tried: `GlslParser` refuses arrays outright (`"GLSL arrays are not in the StackSL subset"`, twice)
 * and `StackSLCpu.stackAutoOptions` pins `externalizeConstants = false`. Two further things break
 * even if arrays are granted: every emitted function gains a trailing
 * `, float <prefix>_wires[<prefix>_wire_count]` parameter, and the entrypoint gains a **second,
 * overloaded** one-argument wrapper that declares the array, calls the initialiser and forwards —
 * and `GlslShader.functions` is keyed by name, so the wrapper simply replaces the real function.
 *
 * [lower] bridges that with one source-to-source rewrite of the **emitted** GLSL, which is still
 * the artifact the GPU consumes rather than a restatement of it:
 *
 *  1. `<prefix>_wires[i]` becomes a scalar global `<prefix>_wire_i` — a shape the subset does
 *     support, and one a caller can push a new value into per evaluation with `setGlobal`, with no
 *     recompile;
 *  2. the wire parameter and the wire argument disappear from every signature and call;
 *  3. `const int <prefix>_wire_count`, `<prefix>_init_wires` and the forwarding wrapper are
 *     dropped, the initial values having moved into the globals.
 *
 * The rewrite is not trusted on inspection. `StackSLWireLoweringTest` renders the lowered
 * program with every wire at its recorded initial and asserts the raster is **bit-identical** to
 * the ordinary non-externalised compile of the same program — which is the only evidence that
 * matters, since the two are supposed to be the same shader.
 *
 * #### Only some wires can be written back into the source
 *
 * A wire's `note` says where it came from, and this is the fact that shapes the whole round trip:
 * `"number"` wires stand for a literal the human typed, so a fitted value can be substituted at
 * the recorded `line`/`column` and the program still reads as the program. A wire noted
 * `"optional default palette a"` is a default the *emitter* supplied for a word that was given no
 * operand — there is no literal in the source to rewrite, and inventing one would mean pushing
 * four `vec4`s in front of `palette`. So [rewrite] fits and rewrites **literal-backed wires
 * only**, [literalBacked] is the population, and the rest are held at their initials. A program's
 * wire count is therefore an upper bound on its fittable parameter count, not the count itself,
 * and `StackSLFit` reports both.
 */
object StackSLWires {

    /** The compile options for a wired program: [StackSLCpu.stackAutoOptions] plus externalisation. */
    fun wiredOptions(slot: String, sampleTexture: String = "source"): StackSLCompileOptions =
        StackSLCpu.stackAutoOptions(slot, sampleTexture).copy(externalizeConstants = true)

    /** The global a lowered wire lives in. */
    fun globalName(prefix: String, index: Int): String = "${prefix}_wire_$index"

    /**
     * Compile [program] with its literals lifted, and lower the result to something the CPU
     * backend can parse.
     */
    fun lift(program: String, slot: String = "program", sampleTexture: String = "source"): WiredProgram {
        val result = StackSLCompiler.compile(program, wiredOptions(slot, sampleTexture))
        if (!result.ok) {
            val errors = result.diagnostics.filter { it.severity == StackSLSeverity.ERROR }
            throw IllegalArgumentException(
                "StackSL program does not compile:\n" + errors.joinToString("\n") { it.toString() }
            )
        }
        val wires = result.analysis.danglingWires
        return WiredProgram(
            program = program,
            slot = slot,
            prefix = result.analysis.prefix,
            glsl = lower(result.glsl, result.analysis.prefix, wires.size),
            wires = wires
        )
    }

    /**
     * Rewrite the wire array in [glsl] into scalar globals. See the file KDoc for the three steps
     * and for why this is a rewrite of the emitted artifact rather than a second emitter.
     */
    fun lower(glsl: String, prefix: String, wireCount: Int): String {
        val arrayName = "${prefix}_wires"
        val countName = "${prefix}_wire_count"
        val initName = "${prefix}_init_wires"

        val kept = ArrayList<String>()
        val lines = glsl.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            when {
                trimmed == "const int $countName = $wireCount;" -> i++

                trimmed.startsWith("void $initName(") -> i = skipBlock(lines, i)

                // The forwarding wrapper: the only other function whose body calls the initialiser.
                trimmed.startsWith("vec4 $prefix(") && blockCalls(lines, i, initName) -> i = skipBlock(lines, i)

                else -> { kept += line; i++ }
            }
        }

        var body = kept.joinToString("\n")
        // Order matters: the parameter form names the count, the reference form is numeric, and
        // the argument form is bare — so the most specific pattern goes first.
        body = body.replace(Regex(""",\s*float\s+${Regex.escape(arrayName)}\[${Regex.escape(countName)}]"""), "")
        body = Regex("""${Regex.escape(arrayName)}\[(\d+)]""").replace(body) { m ->
            globalName(prefix, m.groupValues[1].toInt())
        }
        body = body.replace(Regex(""",\s*${Regex.escape(arrayName)}(?=\s*[,)])"""), "")

        val declarations = (0 until wireCount).joinToString("\n") { "float ${globalName(prefix, it)};" }
        return if (wireCount == 0) body.trim() else declarations + "\n\n" + body.trim()
    }

    /** From a `{ … }` block's opening line, the index just past its closing brace. */
    private fun skipBlock(lines: List<String>, start: Int): Int {
        var depth = 0
        var i = start
        while (i < lines.size) {
            depth += lines[i].count { it == '{' } - lines[i].count { it == '}' }
            i++
            if (depth <= 0) break
        }
        return i
    }

    private fun blockCalls(lines: List<String>, start: Int, name: String): Boolean {
        val end = skipBlock(lines, start)
        return (start until end).any { lines[it].contains("$name(") }
    }

    /**
     * The wires a fitted value can be written back into the program source: the ones whose `note`
     * is `"number"`, i.e. the ones that stand for a literal the human typed.
     */
    fun literalBacked(wires: List<StackSLDanglingWire>): List<StackSLDanglingWire> =
        wires.filter { it.note == "number" && it.line != null && it.column != null && it.token != null }

    /**
     * Substitute [values] back into [program]'s source at the recorded token positions.
     *
     * [values] is indexed by wire index, and only the [literalBacked] entries are written. Each
     * line is rewritten right-to-left so that an earlier substitution cannot move a later token's
     * column.
     *
     * The literal is printed the way `StackSL.kt`'s own `formatFloat` prints one — `%.8f`, trimmed
     * — so a rewritten program is a program a human would have written. That printing is the one
     * lossy step in the round trip, and it is why
     * `StackSLFitRoundTripTest` asserts a tolerance rather than bit-equality.
     */
    fun rewrite(program: String, wires: List<StackSLDanglingWire>, values: DoubleArray): String {
        require(values.size == wires.size) { "expected ${wires.size} values, got ${values.size}" }
        val lines = program.lines().toMutableList()
        literalBacked(wires)
            .sortedWith(compareByDescending<StackSLDanglingWire> { it.line!! }.thenByDescending { it.column!! })
            .forEach { wire ->
                val row = wire.line!! - 1
                val col = wire.column!! - 1
                val token = wire.token!!
                require(row in lines.indices) { "wire ${wire.index} names line ${wire.line}, out of range" }
                val line = lines[row]
                require(col + token.length <= line.length && line.regionMatches(col, token, 0, token.length)) {
                    "wire ${wire.index} expected '$token' at line ${wire.line} column ${wire.column}, found " +
                        "'${line.drop(col).take(token.length)}'"
                }
                lines[row] = line.take(col) + formatLiteral(values[wire.index]) + line.drop(col + token.length)
            }
        return lines.joinToString("\n")
    }

    /** `%.8f`, trimmed, with a decimal point kept — the same shape `StackSL.kt` emits. */
    fun formatLiteral(v: Double): String {
        val text = String.format(Locale.US, "%.8f", v).trimEnd('0').trimEnd('.')
        return if (text.contains('.')) text else "$text.0"
    }
}

/**
 * A StackSL program whose numeric literals have become addressable free parameters.
 *
 * [glsl] is the lowered emitted shader: every wire is a scalar global named by
 * [StackSLWires.globalName], so one parse serves every point in a fit and a new parameter vector
 * costs a `setGlobal` rather than a recompile.
 */
class WiredProgram(
    /** The StackSL source this came from. */
    val program: String,
    /** The `{{ stack.auto.<slot> }}` slot the lowered GLSL is named for. */
    val slot: String,
    /** The emitted prefix, which is also [slot] unless the compiler renamed it. */
    val prefix: String,
    /** The lowered emitted GLSL: parseable by [parseGlslShader], wires as scalar globals. */
    val glsl: String,
    /** Every wire the emitter lifted, literal-backed or not, in index order. */
    val wires: List<StackSLDanglingWire>
) {
    /** The wires a fitted value can be written back into the source — see [StackSLWires.rewrite]. */
    val literalWires: List<StackSLDanglingWire> get() = StackSLWires.literalBacked(wires)

    /** Every wire's emitted initial value, indexed by wire index. */
    val initial: DoubleArray get() = DoubleArray(wires.size) { wires[it].initial.toDouble() }

    /** The globals map a render needs for a given parameter vector. */
    fun globals(values: DoubleArray): Map<String, GlslValue> {
        require(values.size == wires.size) { "expected ${wires.size} wire values, got ${values.size}" }
        return wires.indices.associate {
            StackSLWires.globalName(prefix, it) to GlslValue.float(values[it].toFloat())
        }
    }

    /** A one-line report of what this program's parameter vector actually is. */
    fun describe(): String =
        "$slot: ${wires.size} wire(s), ${literalWires.size} literal-backed " +
            "(${literalWires.joinToString(", ") { "#${it.index}='${it.token}'@${it.line}:${it.column}" }})"
}
