package afield.morpho

/**
 * A snapshot of a MorphoHDL circuit, as produced by morpho's own compiler and copied across the
 * GraalJS boundary by `morpho-field-driver.mjs`.
 *
 * Indices are morpho's, not ours: [nCells] counts every cell the compiler has ever allocated,
 * including ones a later expansion or dead-code pass deactivated. Ask [isActive] before believing
 * anything about a cell. Keeping upstream's numbering means a Kotlin bug and a JS bug can never
 * quietly disagree about which cell is "cell 40" — the cost is that callers must filter.
 *
 * Nets 0 and 1 are reserved by the compiler for the constants ZERO and ONE and have no driver.
 */
class MorphoNetlist(
    val nCells: Int,
    val nNets: Int,
    val nPins: Int,
    /** Expansions performed to reach this snapshot. */
    val steps: Int,
    private val meta: IntArray,
    private val cellPinCount: IntArray,
    /** Per input pin, the net it currently reads. Pins are contiguous per cell from [pinStart]. */
    val pinNet: IntArray,
    /** Per net, the cell driving it, or -1 for constants and dangling nets. */
    val netDriver: IntArray,
    /** Cell indices of the design's input ports, in declaration order. */
    val inputCells: IntArray,
    /** Cell indices of the design's output ports, in declaration order. */
    val outputCells: IntArray,
    val names: List<String>,
) {
    companion object {
        const val INPUT = 1
        const val OUTPUT = 2
        const val LUT = 3
        const val MORPHO = 4

        /** Stride of the packed per-cell metadata block written by the driver. */
        const val STRIDE = 8
    }

    fun isActive(cell: Int): Boolean = meta[cell * STRIDE] == 1
    fun type(cell: Int): Int = meta[cell * STRIDE + 1]
    fun lutValue(cell: Int): Int = meta[cell * STRIDE + 2]
    fun lutArity(cell: Int): Int = meta[cell * STRIDE + 3]
    fun parent(cell: Int): Int = meta[cell * STRIDE + 4]
    fun netStart(cell: Int): Int = meta[cell * STRIDE + 5]

    /**
     * Output wires driven by this cell, occupying nets `netStart until netStart + slots`.
     *
     * A LUT cell is *broadcast*: one truth table applied across [slots] independent bit slices, so
     * an 8-bit XOR is a single cell with eight slots, not eight cells. That is why active cell
     * counts are so much lower than gate counts, and why [lutCount] is not the gate count.
     */
    fun slots(cell: Int): Int = meta[cell * STRIDE + 6]
    fun pinStart(cell: Int): Int = meta[cell * STRIDE + 7]
    fun pinCount(cell: Int): Int = cellPinCount[cell]
    fun name(cell: Int): String = names.getOrElse(cell) { "" }

    fun isLut(cell: Int): Boolean = type(cell) == LUT

    /** Every cell index that survived expansion and optimisation. */
    val activeCells: IntArray by lazy {
        val out = IntArray(nCells)
        var n = 0
        for (i in 0 until nCells) if (isActive(i)) out[n++] = i
        out.copyOf(n)
    }

    val activeCount: Int get() = activeCells.size

    /** Active LUT cells. Not the gate count — see [slots]. */
    val lutCount: Int get() = activeCells.count { isLut(it) }

    /** Total gates, counting each broadcast LUT slot separately. */
    val gateCount: Int get() = activeCells.filter { isLut(it) }.sumOf { slots(it) }

    /**
     * The input port index of a LUT's pin. LUT input pins are stored slot-major
     * (`pinStart + slot * arity + port`) so that splitting a broadcast cell is a contiguous copy.
     */
    fun lutInputNet(cell: Int, slot: Int, port: Int): Int =
        pinNet[pinStart(cell) + slot * lutArity(cell) + port]

    /** Widths of the design's input ports, in declaration order. */
    val inputWidths: IntArray get() = IntArray(inputCells.size) { slots(inputCells[it]) }

    /** Widths of the design's output ports, in declaration order. */
    val outputWidths: IntArray get() = IntArray(outputCells.size) { pinCount(outputCells[it]) }

    /**
     * Driver-to-consumer edges between active cells, one per connected input pin.
     *
     * Returned flat as `[srcCell, dstCell, net, ...]` because the caller is usually about to build
     * FLines and would only take the triple apart again.
     */
    fun edges(): IntArray {
        val out = IntArray(nPins * 3)
        var n = 0
        for (cell in activeCells) {
            val start = pinStart(cell)
            for (p in 0 until pinCount(cell)) {
                val net = pinNet[start + p]
                if (net < 0 || net >= nNets) continue
                val src = netDriver[net]
                if (src < 0 || src >= nCells || !isActive(src)) continue
                out[n++] = src
                out[n++] = cell
                out[n++] = net
            }
        }
        return out.copyOf(n)
    }

    /**
     * Active cells in dependency order: every cell appears after the cells driving its inputs.
     *
     * Iterative rather than recursive — a 32-bit ripple adder is 32 levels deep and `medusa` is a
     * good deal worse, and this runs on Field's runloop thread.
     */
    fun topologicalOrder(): IntArray = order

    /** Memoised: an exhaustive simulation asks for this thousands of times per circuit. */
    private val order: IntArray by lazy { computeTopologicalOrder() }

    private fun computeTopologicalOrder(): IntArray {
        val state = ByteArray(nCells) // 0 unvisited, 1 on stack, 2 done
        val order = IntArray(activeCount)
        var n = 0
        val stack = IntArray(nCells + 1)
        val cursor = IntArray(nCells + 1)

        for (root in activeCells) {
            if (state[root].toInt() != 0) continue
            var sp = 0
            stack[0] = root
            cursor[0] = 0
            state[root] = 1
            while (sp >= 0) {
                val cell = stack[sp]
                val pins = pinCount(cell)
                var advanced = false
                while (cursor[sp] < pins) {
                    val p = cursor[sp]++
                    val net = pinNet[pinStart(cell) + p]
                    if (net < 0 || net >= nNets) continue
                    val src = netDriver[net]
                    if (src < 0 || src >= nCells || !isActive(src)) continue
                    if (state[src].toInt() == 1) continue // cycle: ignore the back edge
                    if (state[src].toInt() == 0) {
                        state[src] = 1
                        sp++
                        stack[sp] = src
                        cursor[sp] = 0
                        advanced = true
                        break
                    }
                }
                if (advanced) continue
                state[cell] = 2
                if (n < order.size) order[n++] = cell
                sp--
            }
        }
        return if (n == order.size) order else order.copyOf(n)
    }

    override fun toString(): String =
        "MorphoNetlist(${activeCount} cells, $lutCount LUTs / $gateCount gates, $nNets nets, $steps expansions)"
}
