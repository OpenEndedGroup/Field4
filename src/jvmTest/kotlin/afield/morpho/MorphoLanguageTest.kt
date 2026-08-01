package afield.morpho

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Field-facing half of the bridge: the `# main …` directive, the grow-and-draw loop, and the
 * FLine output.
 *
 * [MorphoBridgeTest] establishes that the circuits are morpho's. This establishes that Field can
 * hold one, watch it grow and draw it — without needing a browser, a box or a running server, so
 * it stays a unit test.
 */
class MorphoLanguageTest {

    // ---------------------------------------------------------------- the directive

    @Test
    fun `directive carries design, signature and flags`() {
        val d = MorphoExecution.parseDirective(
            """
            # main brent_kung_adder(32, 32, 1) optimize=false grow=4 relax=2
            Xor = LUT(2, 0b0110)
            """.trimIndent()
        )
        assertNotNull(d)
        assertEquals("brent_kung_adder", d.design)
        assertEquals(listOf(32, 32, 1), d.signature.toList())
        assertEquals(false, d.optimize)
        assertEquals(4, d.grow)
        assertEquals(2, d.relax)
        assertEquals(0, d.line)
    }

    @Test
    fun `directive defaults are sensible and its line is reported`() {
        val d = MorphoExecution.parseDirective("\n\n#   main   ripple_adder( 8 , 8 , 1 )\n")
        assertNotNull(d)
        assertEquals("ripple_adder", d.design)
        assertEquals(listOf(8, 8, 1), d.signature.toList())
        assertTrue(d.optimize)
        assertEquals(2, d.line, "the directive's line is where a compile error should be reported")
    }

    /**
     * No directive means no circuit, on purpose. MorphoHDL cells are size-agnostic, so there is no
     * width a box could default to that would not be a silent guess at what the user meant.
     */
    @Test
    fun `a source with no directive is refused rather than guessed at`() {
        assertNull(MorphoExecution.parseDirective("Xor = LUT(2, 0b0110)\n"))
        assertNull(MorphoExecution.parseDirective("# main ripple_adder()"))
        assertNull(MorphoExecution.parseDirective("# a comment about main things"))
    }

    /**
     * The directive lives in a `#` comment because morpho's tokenizer drops comments wholesale, so
     * a Field-annotated program is still a valid morpho program. If that ever stopped being true
     * the annotation would start changing the circuit.
     */
    @Test
    fun `the directive is invisible to morphos parser`() {
        val bare = "Xor = LUT(2, 0b0110)\n@morpho\ndef unit(a, b):\n    y = Xor(a, b)\n    return y\n"
        val annotated = "# main unit(4, 4) grow=3\n$bare"

        val withoutDirective = MorphoRuntime.compile(bare, "unit", intArrayOf(4, 4))
            .use { it.growFully(); it.netlist() }
        val withDirective = MorphoRuntime.compile(annotated, "unit", intArrayOf(4, 4))
            .use { it.growFully(); it.netlist() }

        assertEquals(withoutDirective.gateCount, withDirective.gateCount)
        assertEquals(withoutDirective.activeCount, withDirective.activeCount)
    }

    // ---------------------------------------------------------------- the circuit loop

    @Test
    fun `a circuit grows a frame at a time and then settles`() {
        MorphoCircuit.compile(design = "brent_kung_adder", signature = intArrayOf(32, 32, 1)).use { c ->
            assertTrue(c.growing)

            var frames = 0
            while (c.step(grow = 8, relax = 2)) {
                frames++
                assertTrue(frames < 500, "growth did not terminate")
            }

            assertTrue(frames > 8, "expected the circuit to take several frames to grow, took $frames")
            assertEquals(190, c.netlist.gateCount)
            assertTrue(c.depth in 8f..16f, "depth was ${c.depth}")

            // Still useful after growth: the layout keeps settling.
            assertEquals(false, c.step(grow = 8, relax = 4))

            val summary = c.summary()
            assertTrue("brent_kung_adder(32, 32, 1)" in summary, summary)
            assertTrue("190 gates" in summary, summary)
        }
    }

    @Test
    fun `a grown circuit still adds`() {
        MorphoCircuit.compile(design = "ripple_adder", signature = intArrayOf(8, 8, 1)).use { c ->
            c.growFully(relaxSteps = 10)
            val (sum, carry) = c.evaluateOne(200L, 100L, 1L).let { it[0] to it[1] }
            assertEquals(301L and 0xFF, sum)
            assertEquals(301L ushr 8, carry)
        }
    }

    // ---------------------------------------------------------------- drawing

    @Test
    fun `flines draw one line per wire plus the gate and port markers`() {
        MorphoCircuit.compile(design = "ripple_adder", signature = intArrayOf(8, 8, 1)).use { c ->
            c.growFully(relaxSteps = 40)

            val lines = c.flines()
            val wires = c.netlist.edges().size / 3

            // one FLine per wire, plus point-sets for gates, inputs and outputs
            assertEquals(wires + 3, lines.size, "expected $wires wires and 3 marker sets")
            assertTrue(lines.isNotEmpty())
        }
    }

    /**
     * The projection is the only place the third dimension can be silently discarded, so this
     * checks that an isometric view actually differs from dropping z. It is the drawing-side
     * counterpart to `wasm layout uses all three dimensions`.
     */
    @Test
    fun `isometric and xy projections differ because the layout is 3D`() {
        MorphoCircuit.compile(design = "brent_kung_adder", signature = intArrayOf(32, 32, 1)).use { c ->
            c.growFully(relaxSteps = 60)

            val iso = c.flines(projection = MorphoProjection.ISOMETRIC)
            val xy = c.flines(projection = MorphoProjection.XY)
            assertEquals(iso.size, xy.size)

            val a = MorphoProjection.ISOMETRIC.project(1f, 2f, 3f)
            val b = MorphoProjection.XY.project(1f, 2f, 3f)
            assertTrue(a != b, "isometric and XY produced the same point, so z is being dropped")

            val pos = c.layout.positions()
            val zs = (0 until c.layout.particleCount).map { pos[it * 3 + 2] }
            assertTrue(zs.any { kotlin.math.abs(it) > 1f }, "no particle left the z = 0 plane")
        }
    }

    @Test
    fun `drawing a circuit that has not grown yet does not throw`() {
        MorphoCircuit.compile(design = "wallace_multiplier", signature = intArrayOf(16, 16)).use { c ->
            val lines = c.flines()
            assertTrue(lines.size >= 3, "expected at least the marker sets, got ${lines.size}")
        }
    }
}
