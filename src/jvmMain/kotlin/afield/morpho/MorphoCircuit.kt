package afield.morpho

import java.io.Closeable

/**
 * A MorphoHDL circuit that is being grown and laid out at the same time — the thing a morpho box
 * holds, and the thing a Kotlin box draws.
 *
 * Growth and layout are deliberately interleaved rather than sequenced. Compiling the whole design
 * and *then* laying it out gives you a finished object that snaps into place; expanding a few cells
 * per frame and relaxing the layout between expansions gives you a circuit that unfolds, which is
 * both the point of a language built on recursive division and the only version worth live-coding
 * against.
 *
 * ```kotlin
 * val c = MorphoCircuit.compile(design = "brent_kung_adder", signature = intArrayOf(32, 32, 1))
 * while (c.step()) { /* draw c.flines() each frame */ }
 * println(c.summary())
 * ```
 */
class MorphoCircuit private constructor(
    val design: String,
    val signature: IntArray,
    private val session: MorphoSession,
    val layout: MorphoLayout3D,
) : Closeable {

    companion object {
        /**
         * Compile [design] at [signature] out of [source], ready to grow.
         *
         * [source] defaults to morpho's own standard library, so `MorphoCircuit.compile(design =
         * "wallace_multiplier", signature = intArrayOf(16, 16))` works with no source at all.
         */
        fun compile(
            source: String = MorphoRuntime.standardLibrary,
            design: String,
            signature: IntArray,
            optimize: Boolean = true,
            seed: Int = 1337,
        ): MorphoCircuit {
            val session = MorphoRuntime.compile(source, design, signature, optimize)
            val circuit = MorphoCircuit(design, signature, session, MorphoLayout3D(session, seed))
            circuit.refresh()
            return circuit
        }
    }

    /** The circuit as it stands. Replaced, not mutated, on every [step]. */
    var netlist: MorphoNetlist = session.netlist()
        private set

    /** Signal arrival times for [netlist]. */
    var timing: MorphoTiming = session.timing()
        private set

    /** False once every cell has been expanded. */
    var growing: Boolean = true
        private set

    /** Expansions performed so far. */
    val expansions: Int get() = netlist.steps

    private fun refresh() {
        netlist = session.netlist()
        timing = session.timing()
        layout.sync()
    }

    /**
     * Advance one frame: expand up to [grow] cells, then relax the layout [relax] iterations.
     *
     * @return true while there is still growing to do, so a run loop can be `while (c.step()) { … }`.
     *   Layout keeps relaxing after growth finishes, so calling [step] on a fully grown circuit is
     *   still useful — it settles.
     */
    fun step(grow: Int = 16, relax: Int = 6, params: MorphoLayout3D.Params = MorphoLayout3D.Params()): Boolean {
        if (grow > 0 && growing) {
            val expanded = session.grow(grow)
            if (expanded == 0) growing = false
            refresh()
        }
        if (relax > 0) layout.relax(relax, params)
        return growing
    }

    /** Expand to completion, then settle the layout for [relaxSteps] iterations. */
    fun growFully(relaxSteps: Int = 200): MorphoCircuit {
        session.growFully()
        growing = false
        refresh()
        if (relaxSteps > 0) layout.relax(relaxSteps)
        return this
    }

    /** Run the circuit on a batch of integer test vectors. See [MorphoSim.evaluate]. */
    fun evaluate(vararg samples: LongArray): Array<LongArray> =
        MorphoSim.evaluate(netlist, arrayOf(*samples))

    /** Run the circuit on one test vector. */
    fun evaluateOne(vararg ports: Long): LongArray = MorphoSim.evaluateOne(netlist, *ports)

    /** Critical-path depth: the number that separates a ripple adder from a Brent–Kung. */
    val depth: Float get() = timing.depth(netlist)

    /** A one-line description with every number that matters. */
    fun summary(): String = buildString {
        append(design).append('(').append(signature.joinToString(", ")).append(") ")
        append(if (growing) "growing" else "grown")
        append(": ").append(netlist.activeCount).append(" cells, ")
        append(netlist.gateCount).append(" gates, ")
        append(netlist.nNets).append(" nets, depth ").append(depth.toInt())
        append(", ").append(expansions).append(" expansions")
        if (!growing) append(", z/xy ").append(String.format("%.2f", layout.flatness()))
    }

    override fun close() {
        layout.close()
        session.close()
    }
}
