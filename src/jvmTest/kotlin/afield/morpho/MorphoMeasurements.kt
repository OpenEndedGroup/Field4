package afield.morpho

import kotlin.test.Test

/**
 * Not an assertion — a measurement run, whose printed output is the source of every number on
 * `public/docs/morpho_examples.md`.
 *
 * It lives with the tests so the page's figures can be regenerated rather than remembered:
 *
 * ```
 * ./gradlew jvmTest --tests "afield.morpho.MorphoMeasurements" --rerun -i
 * ```
 */
class MorphoMeasurements {

    @Test
    fun measure() {
        val designs = listOf(
            Triple("ripple_adder", intArrayOf(8, 8, 1), true),
            Triple("ripple_adder", intArrayOf(32, 32, 1), true),
            Triple("brent_kung_adder", intArrayOf(32, 32, 1), true),
            Triple("wallace_multiplier", intArrayOf(16, 16), true),
            Triple("wallace_multiplier", intArrayOf(16, 16), false),
            Triple("right_shifter", intArrayOf(32, 5, 1), true),
            Triple("medusa", intArrayOf(36, 8), true),
            Triple("triangle", intArrayOf(32), true),
        )

        println("MEASURE | design | sig | opt | cells | gates | nets | depth | expansions | compile+grow ms | relax200 ms | flatness")
        designs.forEach { (design, sig, opt) ->
            val t0 = System.nanoTime()
            val circuit = MorphoCircuit.compile(design = design, signature = sig, optimize = opt)
            circuit.use {
                it.growFully(relaxSteps = 0)
                val growMs = (System.nanoTime() - t0) / 1e6
                val t1 = System.nanoTime()
                it.layout.relax(200)
                val relaxMs = (System.nanoTime() - t1) / 1e6
                println(
                    "MEASURE | %s | %s | %s | %d | %d | %d | %d | %d | %.0f | %.0f | %.2f".format(
                        design, sig.joinToString(","), opt,
                        it.netlist.activeCount, it.netlist.gateCount, it.netlist.nNets,
                        it.depth.toInt(), it.expansions, growMs, relaxMs, it.layout.flatness()
                    )
                )
            }
        }

        // Exhaustive-simulation throughput, the number that justifies the bit-parallel design.
        MorphoCircuit.compile(design = "ripple_adder", signature = intArrayOf(8, 8, 1)).use { c ->
            c.growFully(relaxSteps = 0)
            val n = 256 * 256 * 2
            val a = LongArray(n); val b = LongArray(n); val cin = LongArray(n)
            var i = 0
            for (x in 0 until 256) for (y in 0 until 256) for (k in 0 until 2) {
                a[i] = x.toLong(); b[i] = y.toLong(); cin[i] = k.toLong(); i++
            }
            MorphoSim.evaluate(c.netlist, arrayOf(a, b, cin)) // warm
            val t = System.nanoTime()
            MorphoSim.evaluate(c.netlist, arrayOf(a, b, cin))
            val ms = (System.nanoTime() - t) / 1e6
            println("MEASURE-SIM | %d vectors in %.1f ms | %d passes of %d lanes".format(
                n, ms, n / MorphoSim.LANES, MorphoSim.LANES))
        }

        // Depth scaling: the whole reason both adder families exist.
        print("MEASURE-DEPTH |")
        listOf(4, 8, 16, 32, 64).forEach { w ->
            val r = MorphoCircuit.compile(design = "ripple_adder", signature = intArrayOf(w, w, 1))
                .use { it.growFully(relaxSteps = 0); it.depth.toInt() }
            val bk = MorphoCircuit.compile(design = "brent_kung_adder", signature = intArrayOf(w, w, 1))
                .use { it.growFully(relaxSteps = 0); it.depth.toInt() }
            print(" %d:%d/%d".format(w, r, bk))
        }
        println()
    }
}
