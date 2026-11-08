package afield.audio.modal

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Raman's harmonic drum and inverse design: a loaded membrane against Ramakrishna & Sondhi's
 * composite-membrane values, the harmonicity sweep, the density gradient the designs use, and the
 * syahi and shape fits.
 */
class HarmonicDrumTest {
    private val disk08 = polarDrumMesh(0.08) { 1.0 }

    /** A uniform density of 4 everywhere divides every eigenvalue by 4 and leaves the ratios alone. */
    @Test
    fun uniformDensityScalesEigenvalues() {
        val a = disk08.modes(6).lambda
        val b = disk08.withDensity { _, _ -> 4.0 }.modes(6).lambda
        for (i in 0 until 6) assertTrue(abs(a[i] - 4 * b[i]) < 1e-8 * a[i], "mode $i: ${a[i]} vs 4 × ${b[i]}")
    }

    /**
     * The gradient the syahi design uses: making a patch 0.1% heavier changes ln λ by −(the mode's
     * energy share on the patch) × 0.001. Checked against a finite difference on six modes.
     */
    @Test
    fun energyShareIsTheDensityGradient() {
        val base = disk08.withDensity(syahi(0.4, 5.0))
        val modes = base.modes(6)
        val inPatch = BooleanArray(disk08.triangleCount) { t ->
            val a = disk08.tris[3 * t]; val b = disk08.tris[3 * t + 1]; val c = disk08.tris[3 * t + 2]
            hypot((disk08.x[a] + disk08.x[b] + disk08.x[c]) / 3, (disk08.y[a] + disk08.y[b] + disk08.y[c]) / 3) < 0.3
        }
        val eps = 1e-3
        val bumped = disk08.withTriangleDensity(DoubleArray(disk08.triangleCount) { base.density!![it] * (if (inPatch[it]) 1 + eps else 1.0) }).modes(6)
        for (i in 0 until 6) {
            val fd = (ln(bumped.lambda[i]) - ln(modes.lambda[i])) / eps
            val share = modes.energyShare(i) { inPatch[it] }
            println("mode $i: d ln λ / d ln ρ finite difference $fd, energy share ${-share}")
            assertTrue(abs(fd + share) < 1e-3, "mode $i: $fd vs ${-share}")
            assertTrue(abs(modes.energyShare(i) { true } - 1) < 1e-9, "shares sum to 1")
        }
    }

    /**
     * The bare disk: modes come out in the textbook order (0,1), (1,1)×2, (2,1)×2, (0,2), ..., and
     * its fifteen lowest eigenvalues sit about 190 cents rms from Raman's 1 : 2 : 3 : 4 : 5.
     */
    @Test
    fun bareDiskIsFarFromHarmonic() {
        val modes = disk08.modes(15)
        assertEquals(
            "[(0,1), (1,1), (1,1), (2,1), (2,1), (0,2), (3,1), (3,1), (1,2), (1,2), (4,1), (4,1), (2,2), (2,2), (0,3)]",
            modes.circularLabels().toString(),
        )
        val fit = modes.ramanHarmonicity()
        println("bare disk against Raman: $fit")
        assertTrue(fit.rms in 185.0..195.0, "bare disk rms ${fit.rms}")
        assertTrue(abs(modes.ratios()[1] - 1.5933) < 2e-3, "f₂/f₁ = j₁₁/j₀₁ ≈ 1.5933: ${modes.ratios()[1]}")
    }

    /**
     * Ramakrishna & Sondhi's composite membrane (loaded radius 0.4, density × 3.125²): the FEM
     * frequencies of Raman's nine mode families land within 1% of their exact values (as tabulated by
     * Sathej & Adhikari, Table I), the drum is within 40 cents rms of 1:2:3:4:5, and the fifteen
     * lowest modes fall into Raman's five groups exactly as he identified them.
     */
    @Test
    fun compositeMembraneMatchesRamakrishnaSondhi() {
        val modes = loadedDisk(Raman.compositeRadius, Raman.compositeMassRatio, h = 0.05).modes(15)
        val fam = modes.ramanFamilies()
        for (i in fam.indices) {
            val rel = abs(fam[i] - Raman.compositeExact[i]) / Raman.compositeExact[i]
            println("family ${Raman.families[i]}: FEM ${fam[i]} exact ${Raman.compositeExact[i]} rel $rel")
            assertTrue(rel < 0.01, "family ${Raman.families[i]}: ${fam[i]} vs ${Raman.compositeExact[i]}")
        }
        val fit = modes.ramanHarmonicity()
        println("composite membrane against Raman: $fit")
        assertTrue(fit.rms < 40, "composite rms ${fit.rms}")
        val labels = modes.circularLabels()
        val groups = listOf(0..0, 1..2, 3..5, 6..9, 10..14)
        for ((g, range) in groups.withIndex()) {
            val got = range.map { labels[it].diameters to labels[it].circles }.toSet()
            val raman = Raman.families.filter { it.third == g + 1 }.map { it.first to it.second }.toSet()
            assertEquals(raman, got, "harmonic ${g + 1}")
        }
    }

    /** A coarse sweep of loading radius × mass ratio finds its most harmonic cell at Ramakrishna & Sondhi's 0.4 and ≈10. */
    @Test
    fun harmonicityMapFindsTheTabla() {
        val map = harmonicityMap(doubleArrayOf(0.3, 0.4, 0.5, 0.6), doubleArrayOf(1.0, 3.0, 10.0, 30.0), h = 0.08)
        val (i, j) = map.best
        println("best cell radius ${map.radii[j]} mass ratio ${map.massRatios[i]}: ${map.cents[i][j]} cents; bare ${map.cents[0][0]}")
        assertEquals(0.4, map.radii[j])
        assertEquals(10.0, map.massRatios[i])
        assertTrue(map.cents[i][j] < 40 && map.cents[0].all { it > 185 })
    }

    /** One fitted syahi layer improves on the composite membrane it starts from, at about the same radius. */
    @Test
    fun oneLayerSyahiRefinesTheComposite() {
        val d = designSyahi(1, h = 0.08)
        println(d)
        assertTrue(d.fit.rms < d.startFit.rms && d.fit.rms < 29, "fitted ${d.fit} from ${d.startFit}")
        assertTrue(d.radii[0] in 0.38..0.46 && d.peak in 6.0..12.0, "layer ${d.radii[0]} peak ${d.peak}")
    }

    /** No uniform drum's second mode reaches the octave: f₂/f₁ ≤ j₁₁/j₀₁ (Ashbaugh–Benguria), over a sweep of shapes. */
    @Test
    fun noUniformShapeReachesTheOctave() {
        val rnd = java.util.Random(7)
        repeat(8) {
            val s = PolarShape.circle(5)
            for (k in 2..5) { s.cosine[k] = 0.12 * (rnd.nextDouble() - 0.5); s.sine[k] = 0.12 * (rnd.nextDouble() - 0.5) }
            val r = disk08.polarMorphed(s).modes(3).ratios()
            assertTrue(r[1] <= 1.5935, "shape $s has f₂/f₁ = ${r[1]}")
        }
    }

    /** A drawn heart, fitted with three harmonics, is bent into a drum whose three lowest modes sound a major triad, 4:5:6. */
    @Test
    fun shapeDesignTurnsADrawingIntoATriad() {
        val heart = polarPolygon(200) { t -> 1 + 0.35 * kotlin.math.sin(t) * abs(kotlin.math.cos(t)) + 0.15 * kotlin.math.cos(3 * t) }
        val d = designShape(doubleArrayOf(4.0, 5.0, 6.0), start = PolarShape.fromPolygon(heart, 3), h = 0.08)
        println(d)
        assertTrue(d.startFit.rms > 100 && d.fit.rms < 0.5, "triad fit ${d.fit} from ${d.startFit}")
    }

    /** [PolarShape.fromPolygon] recovers the coefficients of a polar outline centred on its centroid. */
    @Test
    fun fromPolygonRecoversPolarCoefficients() {
        val s = PolarShape.of(0.2, 0.0, 0.1)
        val back = PolarShape.fromPolygon(s.polygon(720), 4)
        for (k in 2..4) assertTrue(abs(back.cosine[k] - s.cosine[k]) < 1e-6, "cos $k: ${back.cosine[k]} vs ${s.cosine[k]}")
        assertTrue(abs(back.cosine[0] - 1) < 1e-12)
    }
}
