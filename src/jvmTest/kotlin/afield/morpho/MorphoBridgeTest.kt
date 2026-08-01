package afield.morpho

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The bridge that runs MorphoHDL inside Field is only worth anything if it is *upstream's*
 * MorphoHDL. This is where that claim gets checked.
 *
 * Nothing here tests morpho. It tests the seam: morpho's parser and compiler running on GraalJS,
 * the netlist copied across into Kotlin, and Field's own simulator reading it. A bug anywhere in
 * that chain — a mis-strided metadata block, a transposed LUT index, a pin array read at the wrong
 * offset — shows up as arithmetic that does not add.
 *
 * ## Two oracles, deliberately
 *
 * **Arithmetic.** A ripple adder that does not compute `a + b + c` is broken regardless of what
 * any reference implementation says, so the primary check is integer arithmetic and the strongest
 * case is exhaustive. This catches the whole chain at once and needs nothing external.
 *
 * **`tiny_morpho.py`.** Arithmetic alone would pass a circuit that is correct but twice the size
 * it should be, so gate counts are pinned against morpho's own Python implementation. Those
 * numbers were produced by running upstream's reference directly:
 *
 * ```
 * uv run --project . --with numpy python src/jvmMain/resources/morpho/tiny_morpho.py
 * ```
 *
 * which prints `Gate Counts (Total: 872, Unoptimized: 1086)` for the 16×16 Wallace multiplier, and
 * by calling `compile(fn, sig).report()` for the rest. Two independent implementations of the same
 * language — one in Python with numpy, one in JavaScript with a struct-of-arrays compiler —
 * agreeing to the gate is a much sharper instrument than either agreeing with itself.
 *
 * ## What is *not* claimed
 *
 * Gate counts are compared; netlist *shape* is not. The Python reference emits a flat op list and
 * the JavaScript compiler emits a hierarchical cell graph, and there is no honest structural
 * comparison between them that would not amount to reimplementing one in terms of the other.
 */
class MorphoBridgeTest {

    private val stdlib get() = MorphoRuntime.standardLibrary

    private fun compile(
        design: String,
        vararg signature: Int,
        optimize: Boolean = true,
        method: String = "largest",
    ): MorphoNetlist = MorphoRuntime.compile(stdlib, design, signature, optimize = optimize).use {
        it.growFully(method)
        it.netlist()
    }

    // ---------------------------------------------------------------- arithmetic

    /**
     * Exhaustive. Every 8-bit `a`, every 8-bit `b`, both carries: 131 072 vectors, which the
     * bit-parallel simulator covers in 2 048 passes.
     *
     * Exhaustive matters more than it looks. A carry chain is exactly the thing that a plausible
     * off-by-one in pin indexing gets right for most inputs and wrong on the rare vector where a
     * carry propagates the whole width.
     */
    @Test
    fun `ripple adder is exhaustively correct at 8 bits`() {
        val net = compile("ripple_adder", 8, 8, 1)
        assertEquals(listOf(8, 8, 1), net.inputWidths.toList())
        assertEquals(listOf(8, 1), net.outputWidths.toList())

        val n = 256 * 256 * 2
        val a = LongArray(n); val b = LongArray(n); val c = LongArray(n)
        var i = 0
        for (x in 0 until 256) for (y in 0 until 256) for (k in 0 until 2) {
            a[i] = x.toLong(); b[i] = y.toLong(); c[i] = k.toLong(); i++
        }

        val out = MorphoSim.evaluate(net, arrayOf(a, b, c))
        var wrong = 0
        for (j in 0 until n) {
            val expected = a[j] + b[j] + c[j]
            if (out[0][j] != (expected and 0xFF) || out[1][j] != (expected ushr 8)) wrong++
        }
        assertEquals(0, wrong, "$wrong of $n exhaustive vectors disagreed with a + b + carry")
    }

    /** Random, because 2^65 vectors is not exhaustible. */
    @Test
    fun `brent kung adder is correct at 32 bits`() {
        val net = compile("brent_kung_adder", 32, 32, 1)
        val rng = Random(42)
        val n = 4096
        val a = LongArray(n) { rng.nextLong(1L shl 32) }
        val b = LongArray(n) { rng.nextLong(1L shl 32) }
        val c = LongArray(n) { rng.nextLong(2) }

        val out = MorphoSim.evaluate(net, arrayOf(a, b, c))
        for (j in 0 until n) {
            val expected = a[j] + b[j] + c[j]
            assertEquals(expected and 0xFFFFFFFFL, out[0][j], "sum for ${a[j]} + ${b[j]} + ${c[j]}")
            assertEquals(expected ushr 32, out[1][j], "carry for ${a[j]} + ${b[j]} + ${c[j]}")
        }
    }

    /**
     * The multiplier is the test that would catch a transposed LUT index, which the adders would
     * not: `Xor3` and `Maj3` are symmetric in their inputs, so feeding them in the wrong order
     * still adds correctly. `Mux2` and the carry-save tree are not symmetric.
     */
    @Test
    fun `wallace multiplier is correct at 16 bits`() {
        val net = compile("wallace_multiplier", 16, 16)
        val rng = Random(7)
        val n = 2048
        val a = LongArray(n) { rng.nextLong(1L shl 16) }
        val b = LongArray(n) { rng.nextLong(1L shl 16) }

        val out = MorphoSim.evaluate(net, arrayOf(a, b))
        for (j in 0 until n) {
            assertEquals(a[j] * b[j], out[0][j], "product for ${a[j]} * ${b[j]}")
        }
    }

    /** Asymmetric in a second way: the shift amount is positional, so bit order cannot be lucky. */
    @Test
    fun `right shifter is correct at 32 bits`() {
        val net = compile("right_shifter", 32, 5, 1)
        val rng = Random(11)
        val n = 2048
        val x = LongArray(n) { rng.nextLong(1L shl 32) }
        val s = LongArray(n) { rng.nextLong(32) }
        val pad = LongArray(n) // 0 fill, i.e. a logical shift

        val out = MorphoSim.evaluate(net, arrayOf(x, s, pad))
        for (j in 0 until n) {
            assertEquals(x[j] ushr s[j].toInt(), out[0][j], "${x[j]} >>> ${s[j]}")
        }
    }

    // ---------------------------------------------------------------- against the Python reference

    /**
     * Gate counts, against `tiny_morpho.py`.
     *
     * A LUT cell in the expanded graph carries one gate per output slot, so the comparable
     * quantity is [MorphoNetlist.gateCount], not the cell count.
     */
    @Test
    fun `gate counts match morphos python reference`() {
        data class Case(val design: String, val sig: IntArray, val optimize: Boolean, val gates: Int)

        // Every expected value here was printed by upstream's own implementation; see the class
        // KDoc for the command.
        val cases = listOf(
            Case("ripple_adder", intArrayOf(8, 8, 1), true, 16),
            Case("ripple_adder", intArrayOf(32, 32, 1), true, 64),
            Case("brent_kung_adder", intArrayOf(32, 32, 1), true, 190),
            Case("wallace_multiplier", intArrayOf(16, 16), true, 872),
            Case("wallace_multiplier", intArrayOf(16, 16), false, 1086),
            Case("right_shifter", intArrayOf(32, 5, 1), true, 160),
        )

        cases.forEach { case ->
            val net = compile(case.design, *case.sig, optimize = case.optimize)
            assertEquals(
                case.gates, net.gateCount,
                "${case.design}(${case.sig.joinToString()}) optimize=${case.optimize}: " +
                    "GraalJS produced ${net.gateCount} gates, tiny_morpho.py reports ${case.gates}"
            )
        }
    }

    // ---------------------------------------------------------------- timing

    /**
     * The two adder families exist because their critical paths differ, so timing should show it:
     * a ripple adder's depth is linear in width and a Brent–Kung's is logarithmic. If Field ever
     * reads `updateTimes()` out at the wrong stride this collapses to noise.
     */
    @Test
    fun `adder depth separates ripple from brent kung`() {
        fun depth(design: String, width: Int): Float =
            MorphoRuntime.compile(stdlib, design, intArrayOf(width, width, 1)).use {
                it.growFully()
                it.timing().depth(it.netlist())
            }

        val ripple8 = depth("ripple_adder", 8)
        val ripple32 = depth("ripple_adder", 32)
        val bk32 = depth("brent_kung_adder", 32)

        assertEquals(8f, ripple8, "8-bit ripple carry should be 8 deep")
        assertEquals(32f, ripple32, "32-bit ripple carry should be 32 deep")
        assertTrue(
            bk32 < ripple32 / 2,
            "Brent-Kung at 32 bits should be far shallower than ripple's $ripple32, was $bk32"
        )
        assertTrue(bk32 in 8f..16f, "Brent-Kung at 32 bits should be ~log2 deep, was $bk32")
    }

    // ---------------------------------------------------------------- growth schedule

    /**
     * `morpho-field-driver.mjs` reimplements `grower.js`'s schedule because upstream's constructor
     * drags in the 2D layout Field is replacing. The schedule chooses *when* cells expand, never
     * *what* they expand into, so both orders must reach the same circuit.
     *
     * This is the test that would fail if the reimplementation drifted from upstream's.
     */
    @Test
    fun `expansion order does not change the circuit`() {
        val largest = compile("brent_kung_adder", 32, 32, 1, method = "largest")
        val bfs = compile("brent_kung_adder", 32, 32, 1, method = "bfs")

        assertEquals(largest.gateCount, bfs.gateCount, "gate count depends on expansion order")
        assertEquals(largest.activeCount, bfs.activeCount, "cell count depends on expansion order")

        val rng = Random(3)
        val n = 512
        val a = LongArray(n) { rng.nextLong(1L shl 32) }
        val b = LongArray(n) { rng.nextLong(1L shl 32) }
        val c = LongArray(n) { rng.nextLong(2) }
        val lhs = MorphoSim.evaluate(largest, arrayOf(a, b, c))
        val rhs = MorphoSim.evaluate(bfs, arrayOf(a, b, c))
        assertTrue(lhs[0].contentEquals(rhs[0]) && lhs[1].contentEquals(rhs[1]),
            "the two expansion orders compute different functions")
    }

    /** Growth is incremental, which is the whole reason it is watchable. */
    @Test
    fun `growth can be driven a budget at a time`() {
        MorphoRuntime.compile(stdlib, "brent_kung_adder", intArrayOf(32, 32, 1)).use { session ->
            var rounds = 0
            var previous = 0
            while (session.grow(16) > 0) {
                val now = session.netlist().activeCount
                assertTrue(now >= previous, "the circuit shrank between growth steps")
                previous = now
                rounds++
            }
            assertTrue(rounds > 4, "expected several growth rounds, got $rounds")
            assertTrue(session.isFullyGrown())
            assertEquals(190, session.netlist().gateCount)
        }
    }

    // ---------------------------------------------------------------- 3D layout

    /**
     * morpho's kernel is 3D but its own `force_layout.js` writes `pos[p*3+2] = 0` every frame, so
     * upstream only ever sees it flat. Field does not pin z, and this asserts the difference is
     * real rather than a claim in a comment: a laid-out adder should have genuine z extent.
     */
    @Test
    fun `wasm layout uses all three dimensions`() {
        MorphoRuntime.compile(stdlib, "brent_kung_adder", intArrayOf(32, 32, 1)).use { session ->
            session.growFully()
            MorphoLayout3D(session, seed = 1337).use { layout ->
                assertEquals(session.netlist().activeCount, layout.sync())
                layout.relax(120)

                val pos = layout.positions()
                assertEquals(layout.particleCount * 3, pos.size)
                assertTrue(pos.all { it.isFinite() }, "layout produced a non-finite coordinate")

                val meanZ = (0 until layout.particleCount).sumOf { abs(pos[it * 3 + 2]).toDouble() } /
                    layout.particleCount
                assertTrue(meanZ > 0.1, "layout collapsed to a plane: mean |z| = $meanZ")
                assertTrue(
                    layout.flatness() > 0.25,
                    "z extent is only ${layout.flatness()} of the circuit's width; expected a solid"
                )
            }
        }
    }

    // ---------------------------------------------------------------- language surface

    @Test
    fun `the standard library parses and declares what it should`() {
        val names = MorphoRuntime.cellNames(stdlib)
        listOf("full_adder", "ripple_adder", "brent_kung_adder", "wallace_multiplier",
            "right_shifter", "mux", "triangle", "medusa").forEach {
            assertTrue(it in names, "standard library is missing '$it'")
        }
    }

    @Test
    fun `a syntax error reports a line`() {
        val broken = "Xor = LUT(2, 0b0110)\n@morpho\ndef bad(a, b):\n    y = Xor(a\n    return y\n"
        val failure = runCatching { MorphoRuntime.compile(broken, "bad", intArrayOf(1, 1)) }
            .exceptionOrNull()
        assertTrue(failure is MorphoException, "expected a MorphoException, got $failure")
        val line = failure.line
        assertTrue(
            line != null && line > 0,
            "a syntax error should carry a line number, got $line"
        )
    }
}
