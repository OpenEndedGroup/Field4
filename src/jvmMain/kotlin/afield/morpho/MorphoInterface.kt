package afield.morpho

import afield.*
import afield.logger.lprintln
import afield.runtime.MainLanguageRuntime
import afield.runtime.TopologyWatcher
import afield.runtime.applyRuntimeToggle
import afield.runtime.isMainLanguage
import afield.runtime.registerMainLanguageRuntime
import afield.stdlib.Delegation
import io.ktor.websocket.WebSocketSession

/**
 * Field4 language adapter for MorphoHDL, a Python-syntax hardware-description language.
 *
 * MorphoHDL programs describe recursive circuit structures that Field compiles via morpho's
 * own compiler on GraalJS and lays out with its Barnes–Hut kernel on GraalWasm. The result
 * lands on [Box.morphoCircuit]; a neighbouring Kotlin box reads it and draws with
 * `circuit.flines()`.
 *
 * [MorphoExecution] is the [BoxExecution] implementation. Setting a parent box's `isMorphoRoot`
 * to `true` turns its children into MorphoHDL boxes. Execute mode grows the circuit to
 * completion; launch mode expands a few cells per frame while relaxing the layout between
 * expansions, so the recursive division is visible in motion. [installMorpho] registers the
 * runtime; [installMorphoPlugin] starts the topology watcher.
 */

/**
 * MorphoHDL as a first-class Field language.
 *
 * The runtime underneath is not Field's — it is morpho's own compiler on GraalJS and morpho's own
 * Barnes–Hut kernel on GraalWasm (see [MorphoRuntime]). This file is only the adapter: it teaches
 * Field how to run a box whose text is MorphoHDL, and it is deliberately the same shape as
 * [afield.forth.ForthExecution] and [afield.nano.NanoExecution], because "add a language" should
 * be one recognisable move rather than six bespoke ones.
 *
 * ## What a morpho box contains
 *
 * MorphoHDL source, plus one Field directive saying which design to build and how wide:
 *
 * ```python
 * # main ripple_adder(8, 8, 1)
 *
 * Xor3 = LUT(3, 0b1001_0110)
 * Maj3 = LUT(3, 0b1110_1000)
 *
 * @morpho
 * def full_adder(a, b, c_in):
 *     sum = Xor3(a, b, c_in)
 *     c_out = Maj3(a, b, c_in)
 *     return sum, c_out
 *
 * @morpho(fallback=full_adder)
 * def ripple_adder(a, b, c):
 *     a0, a1 = SPLIT(a)
 *     b0, b1 = SPLIT(b)
 *     s0, c_mid = ripple_adder(a0, b0, c)
 *     s1, c_out = ripple_adder(a1, b1, c_mid)
 *     sum = CAT(s0, s1)
 *     return sum, c_out
 * ```
 *
 * The directive rides in a `#` comment because morpho's tokenizer discards comments entirely, so
 * Field can annotate a program without the language ever seeing it — and the same file still
 * parses in upstream's demo. An empty box inherits morpho's standard library, so
 * `# main brent_kung_adder(32, 32, 1)` on its own is a complete program.
 *
 * ## Execute versus launch
 *
 * **Execute** grows the circuit to completion and reports it. **Launch** grows it a few cells per
 * frame and relaxes the layout between expansions, which is the mode worth having: a language
 * built on recursive division should be watched dividing. Either way the result lands on
 * [Box.morphoCircuit], so a neighbouring Kotlin box can draw it with [flines] and everything
 * Field can already do to an `FLine` applies.
 */
class MorphoExecution(val inside: Box) : BoxExecution {

    private var strandJobs = mutableMapOf<String, kotlinx.coroutines.Job>()

    override val languageName: String get() = "morpho"

    // ---------------------------------------------------------------- the Field directive

    /**
     * What a morpho box is asking Field to build.
     *
     * @param grow cells expanded per frame in launch mode. Larger is faster and less watchable.
     * @param relax layout iterations per frame.
     */
    data class Directive(
        val design: String,
        val signature: IntArray,
        val optimize: Boolean = true,
        val grow: Int = 12,
        val relax: Int = 6,
        val line: Int = 0,
    )

    companion object {
        private val MAIN = Regex(
            """#\s*main\s+([A-Za-z_]\w*)\s*\(([^)]*)\)(.*)""",
            RegexOption.IGNORE_CASE
        )
        private val FLAG = Regex("""([A-Za-z_]\w*)\s*=\s*(\S+)""")

        /**
         * Find the `# main …` directive.
         *
         * Returns null rather than guessing. A morpho cell is size-agnostic by design — that is the
         * whole conceit of the language — so there is no defensible default width, and inventing
         * one would silently build a different circuit from the one intended.
         */
        fun parseDirective(code: String): Directive? {
            code.lineSequence().forEachIndexed { index, raw ->
                val m = MAIN.find(raw) ?: return@forEachIndexed
                val design = m.groupValues[1]
                val signature = m.groupValues[2]
                    .split(',')
                    .mapNotNull { it.trim().takeIf(String::isNotEmpty)?.toIntOrNull() }
                    .toIntArray()
                if (signature.isEmpty()) return@forEachIndexed

                var optimize = true
                var grow = 12
                var relax = 6
                FLAG.findAll(m.groupValues[3]).forEach { f ->
                    val value = f.groupValues[2]
                    when (f.groupValues[1].lowercase()) {
                        "optimize" -> optimize = value.toBooleanStrictOrNull() ?: true
                        "grow" -> grow = value.toIntOrNull()?.coerceAtLeast(1) ?: 12
                        "relax" -> relax = value.toIntOrNull()?.coerceAtLeast(0) ?: 6
                    }
                }
                return Directive(design, signature, optimize, grow, relax, index)
            }
            return null
        }
    }

    // ---------------------------------------------------------------- output plumbing

    private fun report(execute: Execute, reply: (Message) -> Unit, session: WebSocketSession?, text: String) {
        dispatchExecutionOutput(
            inside, reply,
            Output(
                uid = inside.id,
                line = execute.line + execute.code.split("\n").size,
                kind = "ok",
                html = text,
                frame = Runloop.runLoop.frame.toInt()
            ),
            originSession = session
        )
    }

    private fun clearErrors(execute: Execute, reply: (Message) -> Unit, session: WebSocketSession?) {
        dispatchExecutionOutput(
            inside, reply,
            Output(uid = inside.id, line = -1, kind = "err", property = "code", html = "",
                frame = Runloop.runLoop.frame.toInt()),
            originSession = session
        )
    }

    /**
     * Report a failure on the line that caused it.
     *
     * morpho's parser attaches a line to syntax errors, and those are relative to the box's text,
     * so they need offsetting by where the fragment started. Compiler failures usually carry no
     * line at all, and are pinned to the directive — which is nearly always the real culprit,
     * since the commonest compile failure is asking for a width a cell cannot divide.
     */
    private fun fail(execute: Execute, e: Throwable, directiveLine: Int) {
        val reported = (e as? MorphoException)?.line
        val line = if (reported != null) execute.line + reported - 1 else execute.line + directiveLine
        inside.error(e.message ?: e.javaClass.simpleName, line = line, property = "code")
    }

    private fun describe(circuit: MorphoCircuit): String {
        val n = circuit.netlist
        val ports = n.inputWidths.joinToString(", ") + " -> " + n.outputWidths.joinToString(", ")
        return """
            <div style="font-family:Iosevka Web, monospace; font-size:12px; line-height:1.5">
              <b>${circuit.design}</b>(${circuit.signature.joinToString(", ")})
              &nbsp;<span style="opacity:.6">$ports</span><br>
              ${n.gateCount} gates in ${n.activeCount} cells &middot;
              ${n.nNets} nets &middot; depth ${circuit.depth.toInt()} &middot;
              ${circuit.expansions} expansions
            </div>
        """.trimIndent()
    }

    // ---------------------------------------------------------------- execute

    override fun executeFragment(inside: Box, execute: Execute, reply: (Message) -> Unit) =
        executeAll(inside, execute, reply)

    /** Compile and grow the whole circuit, once. */
    override fun executeAll(inside: Box, execute: Execute, reply: (Message) -> Unit) {
        val originSession = thisSession.get()
        inMain {
            val generation = ++inside.fragmentGeneration
            send(Message("is-running").payload(IsRunning(execute.uid, true, generation, "__fragment__")))
            configureOutputProxies(inside, reply, originSession)
            try {
                val directive = parseDirective(execute.code)
                if (directive == null) {
                    inside.error(missingDirectiveMessage(execute.code), line = execute.line, property = "code")
                    return@inMain
                }
                try {
                    inside.morphoCircuit?.close()
                    val circuit = MorphoCircuit.compile(
                        source = sourceFor(execute.code),
                        design = directive.design,
                        signature = directive.signature,
                        optimize = directive.optimize,
                    ).growFully()
                    inside.morphoCircuit = circuit
                    clearErrors(execute, reply, originSession)
                    report(execute, reply, originSession, describe(circuit))
                } catch (e: Throwable) {
                    if (e is InterruptedException) throw e
                    fail(execute, e, directive.line)
                }
            } finally {
                send(Message("is-running").payload(IsRunning(execute.uid, false, generation, "__fragment__")))
            }
        }
    }

    // ---------------------------------------------------------------- launch

    /**
     * Grow the circuit a few cells per frame, relaxing the 3D layout between expansions.
     *
     * The strand keeps running after growth completes so the layout can settle — and so a drawing
     * box downstream keeps seeing a live circuit rather than one that stopped moving mid-flight.
     */
    override fun launchAll(
        inside: Box, execute: Execute, code: String, strand: String, reply: (Message) -> Unit
    ) {
        val originSession = thisSession.get()
        inMain {
            configureOutputProxies(inside, reply, originSession)

            val directive = parseDirective(code)
            if (directive == null) {
                inside.error(missingDirectiveMessage(code), line = execute.line, property = "code")
                return@inMain
            }

            val circuit = try {
                inside.morphoCircuit?.close()
                MorphoCircuit.compile(
                    source = sourceFor(code),
                    design = directive.design,
                    signature = directive.signature,
                    optimize = directive.optimize,
                )
            } catch (e: Throwable) {
                if (e is InterruptedException) throw e
                fail(execute, e, directive.line)
                return@inMain
            }

            inside.morphoCircuit = circuit
            clearErrors(execute, reply, originSession)

            strandJobs[strand]?.cancel()
            val generation = inside.runGeneration.getOrPut(strand) { -1 } + 1
            inside.runGeneration[strand] = generation
            send(Message("is-running").payload(IsRunning(execute.uid, true, generation, strand)))

            strandJobs[strand] = inMain {
                try {
                    var lastReported = -1
                    while (inside.runGeneration[strand] == generation) {
                        try {
                            circuit.step(grow = directive.grow, relax = directive.relax)
                            if (circuit.expansions != lastReported) {
                                lastReported = circuit.expansions
                                inside.output.display("morpho", describe(circuit))
                            }
                        } catch (e: Throwable) {
                            if (e is InterruptedException) throw e
                            lprintln("error,morpho", e.stackTraceToString())
                            fail(execute, e, directive.line)
                            break
                        }
                        kotlinx.coroutines.yield()
                    }
                } finally {
                    send(Message("is-running").payload(IsRunning(execute.uid, false, generation, strand)))
                }
            }
        }
    }

    override fun stop(inside: Box) {
        strandJobs.values.forEach { it.cancel() }
        strandJobs.clear()
        inside.runGeneration.keys.toList().forEach { strand ->
            inside.runGeneration[strand] = (inside.runGeneration[strand] ?: 0) + 1
        }
    }

    override fun executeSymbol(inside: Box, executeSymbol: ExecuteSymbol, reply: (Message) -> Unit) {
        executeAll(
            inside,
            Execute(uid = executeSymbol.uid, type = "fragment", line = executeSymbol.line,
                code = executeSymbol.symbol, singleLine = true),
            reply
        )
    }

    // ---------------------------------------------------------------- completion

    /**
     * Complete cell and LUT names out of the box's own source, plus morpho's primitives.
     *
     * The names come from actually parsing the buffer with morpho's parser, so completion tracks
     * whatever the user has just written rather than a hard-coded list — and it silently offers
     * nothing while the buffer is mid-edit and unparseable, which is the right failure.
     */
    override fun complete(inside: Box, completionRequest: CompletionRequest, reply: (Message) -> Unit) {
        val code = completionRequest.allCode
        val cursor = completionRequest.position.coerceIn(0, code.length)

        var start = cursor
        while (start > 0 && (code[start - 1].isLetterOrDigit() || code[start - 1] == '_')) start--
        val prefix = code.substring(start, cursor)

        val fromSource = runCatching { MorphoRuntime.cellNames(sourceFor(code)) }.getOrDefault(emptyList())
        val documentation = PRIMITIVES + fromSource.associateWith { "cell defined in this box" }
        val matches = (PRIMITIVES.keys + fromSource)
            .distinct()
            .filter { it.startsWith(prefix, ignoreCase = true) }
            .sorted()
        if (matches.isEmpty()) return

        reply(
            Message("complete").payload(
                Completion(
                    boundsStart = start,
                    boundsEnd = cursor,
                    metadata = matches.map { name ->
                        Metadata(
                            icon = if (name in PRIMITIVES) "primitive" else "cell",
                            tail = if (name in PRIMITIVES) "primitive" else "cell",
                            documentation = documentation[name] ?: "",
                            text = name,
                            displayText = name
                        )
                    }
                )
            )
        )
    }

    /**
     * A box with no cells of its own still gets morpho's standard library, so the shortest useful
     * morpho program in Field is a single directive line.
     */
    private fun sourceFor(code: String): String =
        if (MorphoRuntime.cellNames(code).isEmpty()) {
            MorphoRuntime.standardLibrary + "\n" + code
        } else code

    private fun missingDirectiveMessage(code: String): String {
        val available = runCatching { MorphoRuntime.cellNames(sourceFor(code)) }.getOrDefault(emptyList())
        val example = available.firstOrNull { it == "ripple_adder" } ?: available.firstOrNull() ?: "ripple_adder"
        return "no '# main …' directive. Add one, e.g.  # main $example(8, 8, 1)" +
            if (available.isEmpty()) "" else
                "  — cells available: ${available.take(12).joinToString(", ")}" +
                    if (available.size > 12) ", …" else ""
    }
}

/** MorphoHDL's built-in operators, with the documentation completion shows. */
private val PRIMITIVES: Map<String, String> = mapOf(
    "LUT" to "LUT(arity, table) — define a gate from a truth table, e.g. LUT(3, 0b1001_0110)",
    "SPLIT" to "SPLIT(x) — halve a bus; raises the boundary that ends a recursion when x is 1 bit",
    "CAT" to "CAT(a, b, …) — concatenate buses",
    "REPEAT" to "REPEAT(x, ref) — tile x to the width of ref",
    "LSLICE" to "LSLICE(x, ref) — split off the low len(ref) bits of x",
    "HSLICE" to "HSLICE(x, ref) — split off the high len(ref) bits of x",
    "ZERO" to "ZERO — the constant 0 wire",
    "ONE" to "ONE — the constant 1 wire",
    "VOID" to "VOID — the zero-width bus",
)

// ── Box properties ───────────────────────────────────────────────────────────

var Box.isMorphoRoot by Delegation<Boolean?>()

/**
 * The circuit this box most recently built.
 *
 * This is the handoff between the two halves of the bridge. A morpho box writes it; a Kotlin box
 * beside it reads it and draws:
 *
 * ```kotlin
 * val circuit = parent.children.firstNotNullOf { it.morphoCircuit }
 * showPlaneGrid(circuit.flines())
 * ```
 */
var Box.morphoCircuit by Delegation<MorphoCircuit?>()

// ── Registration ─────────────────────────────────────────────────────────────

@RunsAt(early)
fun installMorpho() {
    Box.ioPersistentProperties.add("isMorphoRoot")
    registerMainLanguageRuntime(
        MainLanguageRuntime(
            id = "morpho",
            label = "MorphoHDL",
            runtimeClass = MorphoExecution::class,
            setLegacyRoot = { box, enabled -> box.isMorphoRoot = enabled },
            applyTopology = ::morphoTopologyNeedsUpdating,
        ),
    )
}

@RunsAt(plugins)
fun installMorphoPlugin() {
    TopologyWatcher("morpho") { morphoTopologyNeedsUpdating(it) }.start()
}

fun morphoTopologyNeedsUpdating(values: Collection<Box>) {
    applyRuntimeToggle(
        boxes = values,
        isRoot = { it.isMorphoRoot == true || it.isMainLanguage("morpho") },
        runtimeClass = MorphoExecution::class,
        newExecution = { child -> MorphoExecution(child) },
        language = "MorphoHDL",
        customization = "",
        notifyCategory = "morpho"
    )
}
