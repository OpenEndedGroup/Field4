package afield.geometry.path

import afield.audio.modal.ModalDrum
import afield.audio.modal.PenroseSpectraDocs
import afield.audio.modal.springModes
import afield.audio.synth.SynthContext
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the tight-binding spectrum of de Bruijn tilings' vertex graphs: the Penrose confined-state
 * fraction 81 − 50τ (Arai, Tokihiro, Fujiwara & Kohmoto, Phys. Rev. B 38, 1621, 1988), its
 * controls, the confined states themselves, and the spring-network voice built on them. The
 * numbers are the ones `public/docs/debruijn_tilings.md` prints in The tiling as a graph.
 */
class TilingSpectrumTest {
    private val tau = (1 + sqrt(5.0)) / 2
    private val target = 81 - 50 * tau

    private fun penrose(radius: Double) = deBruijnTiling(symmetry = 5, radius = radius, scale = 1.0).vertexGraph()

    /**
     * Exact E = 0 counts (rank over two primes) on Penrose patches of growing radius, and the
     * bulk fraction from a `bulk·N + rim·√N` fit over the three largest: 0.0983, against
     * 81 − 50τ = 0.09830. The smallest patches read high or low by up to 0.002 from their rims.
     */
    @Test
    fun penroseZeroFractionConvergesTo81Minus50Tau() {
        val radii = listOf(6.0, 14.0, 20.0, 28.0, 36.0)
        val graphs = radii.map { penrose(it) }
        val counts = graphs.map { it.vertexCount to it.zeroModeCount() }
        println("Penrose (vertices, E=0 states): $counts")
        assertEquals(listOf(934 to 92, 4874 to 478, 9853 to 965, 19225 to 1879, 31662 to 3104), counts)
        for ((n, z) in counts) assertEquals(target, z.toDouble() / n, 0.002, "raw fraction at N=$n")
        val fit = ZeroModeFit.ofCounts(counts.takeLast(3))
        println("bulk fit over the three largest: $fit (81 - 50 tau = $target)")
        assertEquals(target, fit.bulk, 0.0005)
        // Every zero state on the small patches is forced by a sublattice mismatch: as many as a
        // maximum matching leaves unmatched.
        assertEquals(92, graphs[0].matchingDeficit())
        assertEquals(478, graphs[1].matchingDeficit())
        for (g in graphs) assertTrue(g.matchingDeficit() <= g.zeroModeCount())
    }

    /**
     * Controls: the square lattice's zero states all live on its rim (exactly 2R + 1, bulk 0); the
     * 3-fold de Bruijn tiling (the periodic dice lattice) has its flat band of 1/3; jittering the
     * Penrose grid by 0.1 of a spacing removes more than half the confined states.
     */
    @Test
    fun controlsSeparateBulkFromRim() {
        val square = listOf(10.0, 20.0, 30.0, 45.0).map { TilingGraph.squareLattice(it) }
        assertEquals(listOf(21, 41, 61, 91), square.map { it.zeroModeCount() })
        val sq = ZeroModeFit.of(square)
        println("square lattice: $sq")
        assertEquals(0.0, sq.bulk, 0.001)
        val dice = ZeroModeFit.of(listOf(13.3, 20.0, 29.3).map { deBruijnTiling(symmetry = 3, radius = it, scale = 1.0).vertexGraph() })
        println("3-fold (dice lattice): $dice")
        assertEquals(1.0 / 3, dice.bulk, 0.01)
        val jittered = ZeroModeFit.of(listOf(10.0, 16.0, 24.0).map { deBruijnTiling(symmetry = 5, radius = it, scale = 1.0, disorder = 0.1).vertexGraph() })
        println("Penrose, disorder 0.1: $jittered")
        assertTrue(jittered.bulk < 0.05, "disorder 0.1 should leave under half the confined states, got $jittered")
    }

    /**
     * The dense spectrum agrees with the exact count, the E = 0 level is isolated from the bulk
     * states by a gap of at least 0.16, the confined states sit on vertices of degree 3 to 5 only,
     * and `confinedState` returns states that are exactly zero off a cluster of 10 to 60 vertices.
     */
    @Test
    fun confinedStatesAreStrictlyLocalAndGapped() {
        val g = penrose(6.0)
        val s = g.spectrum(true)
        assertEquals(92, s.zeroCount())
        val inner = g.interior(0.6 * g.extent)
        val bulkGap = s.gaps(2, weights = s.weightOn(inner), minWeight = 0.3 * inner.size / g.vertexCount)
        println("bulk gaps around E = 0: $bulkGap")
        assertTrue(bulkGap.all { abs(it.first) < 1e-9 || abs(it.second) < 1e-9 } && bulkGap.all { it.second - it.first > 0.16 })
        val w = s.zeroWeights()
        assertEquals(92.0, w.sum(), 1e-6)
        for (v in 0 until g.vertexCount) if (g.degree(v) >= 6) assertTrue(w[v] < 1e-12, "zero weight at a degree-${g.degree(v)} vertex")
        val sizes = inner.filter { w[it] > 1e-6 }.take(20).map { c ->
            val psi = g.confinedState(c) ?: error("no confined state at $c")
            for (v in 0 until g.vertexCount) assertEquals(0.0, g.neighbours[v].sumOf { psi[it] }, 1e-10, "A·psi at $v")
            psi.count { abs(it) > 1e-9 }
        }
        println("confined supports: ${sizes.sorted()}")
        assertTrue(sizes.all { it in 10..60 })
        assertNull(g.confinedState(inner.first { g.degree(it) == 7 }, maxHops = 5))
    }

    /**
     * The spring network: a strike heard at the vertex with the most confined weight puts 31.5% of
     * its energy at the E = 0 pitch, one on a degree-7 vertex none, and the live `ModalDrum` voice
     * of all 934 modes rings after a strike.
     */
    @Test
    fun springNetworkRingsAtTheConfinedPitch() {
        val g = PenroseSpectraDocs.penrose()
        val modes = g.springModes(PenroseSpectraDocs.STIFFNESS)
        val confined = PenroseSpectraDocs.strikeVertex(g, modes, true)
        val open = PenroseSpectraDocs.strikeVertex(g, modes, false)
        println("confined share ${modes.zeroShare(confined)} at degree ${g.degree(confined)}; open share ${modes.zeroShare(open)} at degree ${g.degree(open)}")
        assertEquals(0.3151, modes.zeroShare(confined), 1e-3)
        assertEquals(7, g.degree(open))
        assertEquals(0.0, modes.zeroShare(open), 1e-12)
        assertEquals(sqrt(PenroseSpectraDocs.STIFFNESS / modes.lambda[0]), modes.zeroRatio, 1e-12)
        assertEquals(220.0, modes.frequencies(220.0)[modes.energies.indices.first { abs(modes.energies[it]) < 1e-8 }], 1e-6)

        val drum = ModalDrum(modes, fundamental = modes.fundamentalFor(220.0))
        val ctx = SynthContext(44_100f, 512)
        val block = FloatArray(512)
        fun peakOf(blocks: Int): Float { var p = 0f; repeat(blocks) { drum.render(block, 512, ctx); ctx.cycleId++; p = maxOf(p, block.maxOf { abs(it) }) }; return p }
        assertEquals(0f, peakOf(2))
        drum.strike(g.x[confined], g.y[confined], 0.1)
        assertTrue(peakOf(8) > 0.01f)
    }
}
