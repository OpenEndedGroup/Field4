package afield.audio.modal

import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/** Checks the finite-element drum spectra against analytic values and the Kac isospectral pair. */
class EigendrumTest {
    private fun rel(a: Double, b: Double) = abs(a - b) / b

    /**
     * Unit square: λ = π²(m² + n²). The first six (with multiplicity) must land within 1.5%.
     */
    @Test
    fun unitSquareMatchesAnalytic() {
        val modes = drumMesh(doubleArrayOf(0.0, 0.0, 1.0, 0.0, 1.0, 1.0, 0.0, 1.0), 0.04).modes(6)
        val exact = listOf(2, 5, 5, 8, 10, 10).map { PI * PI * it }
        for (i in 0 until 6) {
            println("square λ$i = ${modes.lambda[i]} exact ${exact[i]} rel ${rel(modes.lambda[i], exact[i])}")
            assertTrue(rel(modes.lambda[i], exact[i]) < 0.015, "square mode $i: ${modes.lambda[i]} vs ${exact[i]}")
        }
    }

    /**
     * Unit disk: λ₁ = j₀,₁² ≈ 5.7832, λ₂,₃ = j₁,₁² ≈ 14.682 (a degenerate pair).
     */
    @Test
    fun unitDiskMatchesBesselZeros() {
        val modes = polarDrumMesh(0.05, 256) { 1.0 }.modes(4)
        val exact = listOf(5.783186, 14.681971, 14.681971, 26.374616)
        for (i in 0 until 4) {
            println("disk λ$i = ${modes.lambda[i]} exact ${exact[i]} rel ${rel(modes.lambda[i], exact[i])}")
            assertTrue(rel(modes.lambda[i], exact[i]) < 0.015, "disk mode $i: ${modes.lambda[i]} vs ${exact[i]}")
        }
    }

    /**
     * The Gordon–Webb–Wolpert pair: two different shapes whose first ten eigenvalues agree to 1%.
     * Driscoll (1997) gives λ₁ = 2.53794 for drums of half this size, so 4 × 2.53794 here.
     */
    @Test
    fun kacPairIsIsospectral() {
        val a = drumMesh(KacPair.drumA, 0.06)
        val b = drumMesh(KacPair.drumB, 0.06)
        assertTrue(rel(a.area, 3.5) < 1e-9 && rel(b.area, 3.5) < 1e-9, "both drums have area 3.5: ${a.area} ${b.area}")
        val la = a.modes(10).lambda
        val lb = b.modes(10).lambda
        for (i in 0 until 10) {
            println("kac λ$i A=${la[i]} B=${lb[i]} rel ${rel(la[i], lb[i])}")
            assertTrue(rel(la[i], lb[i]) < 0.01, "kac mode $i: ${la[i]} vs ${lb[i]}")
        }
        assertTrue(rel(la[0], 4 * 2.53794) < 0.01, "λ₁ near 4 × Driscoll 2.53794 (his drums have half our side): ${la[0]}")
    }

    /** A strike on the square's centre line silences the (2,1) mode, which has a nodal line there. */
    @Test
    fun strikeOnNodalLineSilencesMode() {
        val modes = drumMesh(doubleArrayOf(0.0, 0.0, 1.0, 0.0, 1.0, 1.0, 0.0, 1.0), 0.05).modes(4)
        val onAxis = modes.strike(0.5, 0.5, 0.03)
        println("centre strike amplitudes ${onAxis.toList()}")
        assertTrue(abs(onAxis[1]) < 0.02 * abs(onAxis[0]) && abs(onAxis[2]) < 0.02 * abs(onAxis[0]))
        val sound = modes.render(modes.strike(0.3, 0.2), seconds = 0.5, sampleRate = 8000)
        assertTrue(sound.any { abs(it) > 0.5f })
    }
}
