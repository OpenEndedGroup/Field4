package afield.morpho

/**
 * What a compiled MorphoHDL circuit computes, and how deep it is: a bit-parallel logic simulator,
 * plus the per-cell arrival times morpho's own compiler already hands over.
 *
 * [MorphoSim] evaluates a [MorphoNetlist] with 64 test vectors packed into the bits of a machine
 * word, which makes exhausting a small adder's whole input space cheap enough for a unit test.
 * Morpho's JavaScript has no logic simulator at all, so this side is Field's, written to the LUT
 * convention of the numpy evaluator in upstream's `tiny_morpho.py`. [MorphoTiming] is the other way
 * round: a thin reading of morpho's own `updateTimes()`, whose deepest cell is the circuit's
 * critical path. [MorphoEvaluator] reads both, the simulator for correctness and the arrival times
 * for depth.
 */

/**
 * Logic simulation for a compiled MorphoHDL circuit — does the adder actually add?
 *
 * Morpho's JavaScript has no logic simulator. What `viewer.js` animates is [MorphoTiming]: signal
 * *arrival*, not signal *value*. The only bit-level evaluator upstream ships is `CircuitRunner` in
 * `tiny_morpho.py`, which vectorises over samples with numpy. This is the same idea with the same
 * LUT convention, using a machine word as the sample axis instead of a numpy array.
 *
 * ## The sample axis is the bit axis
 *
 * Every net holds a [Long] whose bit *s* is that net's value for sample *s*, so one pass evaluates
 * 64 independent input vectors and a LUT evaluation is a handful of `and`/`or`/`inv`. For an
 * exhaustive 8+8+1-bit adder check that is 131072 vectors in 2048 passes rather than 131072.
 *
 * ## LUT convention
 *
 * Taken from `tiny_morpho.py:361` — `idx = Σ args[k] << k`, `out = (lut >> idx) & 1`. Port *k*
 * contributes bit *k* of the truth-table index. Getting this backwards still passes a symmetric
 * gate like XOR3 and fails only on asymmetric ones, so `MorphoBridgeTest` checks the shifter
 * and the multiplier, not just the adders.
 */
object MorphoSim {

    /** Samples evaluated per pass. */
    const val LANES = 64

    /**
     * Evaluate one pass of up to 64 samples held as bit planes.
     *
     * @param inputs `inputs[port][bit]` is a 64-sample plane for one wire of one input port.
     * @return `out[port][bit]`, same shape, for the design's output ports.
     */
    fun simulatePlanes(net: MorphoNetlist, inputs: Array<LongArray>): Array<LongArray> {
        require(inputs.size == net.inputCells.size) {
            "design has ${net.inputCells.size} input ports, got ${inputs.size}"
        }

        val values = LongArray(net.nNets)
        values[1] = -1L // net 1 is the constant ONE; net 0 stays all-zero

        net.inputCells.forEachIndexed { port, cell ->
            val width = net.slots(cell)
            val plane = inputs[port]
            require(plane.size == width) {
                "input port $port ('${net.name(cell)}') is $width bits wide, got ${plane.size}"
            }
            val base = net.netStart(cell)
            for (b in 0 until width) values[base + b] = plane[b]
        }

        for (cell in net.topologicalOrder()) {
            if (!net.isLut(cell)) continue
            val arity = net.lutArity(cell)
            val table = net.lutValue(cell)
            val base = net.netStart(cell)
            val entries = 1 shl arity

            for (slot in 0 until net.slots(cell)) {
                val outNet = base + slot
                // A LUT cell is broadcast across slots, and morpho's optimizer retires slots
                // individually: constant folding or buffer removal can bypass one slot of an
                // otherwise live cell, leaving its output net driverless and its input pins
                // disconnected (net == -1) while the cell stays active. So "active cell implies
                // connected pins" is false, and a slot has to be checked, not the cell.
                // Nothing reads a bypassed slot's net, so leaving it zero is safe.
                if (outNet < 0 || outNet >= values.size) continue
                if (net.netDriver[outNet] != cell) continue

                var acc = 0L
                for (m in 0 until entries) {
                    if ((table ushr m) and 1 == 0) continue
                    var mask = -1L
                    for (p in 0 until arity) {
                        val inNet = net.lutInputNet(cell, slot, p)
                        // A disconnected port reads as constant zero. That is not a guess: it is
                        // morpho's own convention, `if (w < 0) w = 0` in primitives.js's
                        // optimizeLUT, which is the function that disconnected the pin in the
                        // first place. Constant propagation prunes a port without rewriting the
                        // cell's truth table, so the arity and the table stay as they were and the
                        // pruned port is simply read as 0 by everyone downstream, upstream
                        // included.
                        val v = if (inNet < 0) 0L else values[inNet]
                        mask = mask and if ((m ushr p) and 1 == 1) v else v.inv()
                        if (mask == 0L) break
                    }
                    acc = acc or mask
                }
                values[outNet] = acc
            }
        }

        return Array(net.outputCells.size) { port ->
            val cell = net.outputCells[port]
            val start = net.pinStart(cell)
            LongArray(net.pinCount(cell)) { b ->
                val n = net.pinNet[start + b]
                // Unlike a bypassed LUT slot, a disconnected *output* bit is somebody reading a
                // wire that was never driven. Reading it as zero would quietly produce a plausible
                // wrong answer, and the arithmetic tests would then be pinning a bug.
                require(n >= 0) {
                    "output port $port bit $b of '${net.name(cell)}' is not connected to any net"
                }
                values[n]
            }
        }
    }

    /**
     * Evaluate a batch of integer test vectors.
     *
     * @param samples `samples[port][i]` is the integer value of that input port for sample *i*.
     *   Every port must supply the same number of samples.
     * @return `out[port][i]`, the design's outputs for the same samples.
     *
     * Ports wider than 63 bits are rejected rather than silently truncated; nothing in morpho's
     * shipped library is that wide, and a wrong answer would be worse than a refusal.
     */
    fun evaluate(net: MorphoNetlist, samples: Array<LongArray>): Array<LongArray> {
        require(samples.size == net.inputCells.size) {
            "design has ${net.inputCells.size} input ports, got ${samples.size}"
        }
        if (samples.isEmpty()) return arrayOf()
        val count = samples[0].size
        require(samples.all { it.size == count }) { "every port must supply the same sample count" }

        val inWidths = net.inputWidths
        val outWidths = net.outputWidths
        inWidths.forEachIndexed { p, w ->
            require(w < LANES) { "input port $p is $w bits wide; batch packing supports < $LANES" }
        }
        outWidths.forEachIndexed { p, w ->
            require(w < LANES) { "output port $p is $w bits wide; batch packing supports < $LANES" }
        }

        val out = Array(outWidths.size) { LongArray(count) }

        var offset = 0
        while (offset < count) {
            val n = minOf(LANES, count - offset)

            val planes = Array(inWidths.size) { port ->
                val w = inWidths[port]
                val plane = LongArray(w)
                for (s in 0 until n) {
                    val value = samples[port][offset + s]
                    for (b in 0 until w) {
                        if ((value ushr b) and 1L == 1L) plane[b] = plane[b] or (1L shl s)
                    }
                }
                plane
            }

            val res = simulatePlanes(net, planes)
            for (port in res.indices) {
                val w = outWidths[port]
                for (s in 0 until n) {
                    var v = 0L
                    for (b in 0 until w) {
                        if ((res[port][b] ushr s) and 1L == 1L) v = v or (1L shl b)
                    }
                    out[port][offset + s] = v
                }
            }
            offset += n
        }
        return out
    }

    /** Single-sample convenience wrapper. */
    fun evaluateOne(net: MorphoNetlist, vararg ports: Long): LongArray =
        evaluate(net, Array(ports.size) { longArrayOf(ports[it]) }).map { it[0] }.toLongArray()
}

/**
 * Per-cell signal arrival, straight out of morpho's `CompiledGraph.updateTimes()`.
 *
 * [last] for the deepest cell is the circuit's critical path, which is the number that separates
 * the adder families: a ripple adder is linear in width, a Brent–Kung is logarithmic.
 */
class MorphoTiming(private val times: FloatArray, val nCells: Int) {
    fun first(cell: Int): Float = times[cell * 2]
    fun last(cell: Int): Float = times[cell * 2 + 1]

    /** Critical-path depth over the active cells of [net]. */
    fun depth(net: MorphoNetlist): Float {
        var d = 0f
        for (c in net.activeCells) if (last(c) > d) d = last(c)
        return d
    }
}
