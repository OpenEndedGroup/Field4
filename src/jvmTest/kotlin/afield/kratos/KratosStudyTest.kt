package afield.kratos

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The study layer's own invariants, with no Kratos anywhere near them.
 *
 * `KratosStudy` needs a live session to solve, so the sweep logic — grid ordering, clamping,
 * memoisation, the sense flip, the figure — is tested here against an *analytic* objective, and the
 * real solver is exercised by the `kratos.study.parameter-sweep` catalog smoke. Splitting it that
 * way is what makes these tests run in a second on a machine with no container: the bugs that live
 * in an odometer or a cache key have nothing to do with physics, and pinning them behind a 447 MB
 * image would mean not pinning them.
 */
class KratosStudyTest {

    private data class Point(val x: Double = 0.0, val y: Double = 0.0)

    private fun space() = StudySpace(
        Point(),
        StudyVariable("x", 0.0..1.0, "m") { p, v -> p.copy(x = v) },
        StudyVariable("y", -2.0..2.0) { p, v -> p.copy(y = v) },
    )

    // ------------------------------------------------------------------------------------------
    // Variables and spaces
    // ------------------------------------------------------------------------------------------

    @Test fun sampleGridsIncludeBothEndsAndASingleStepIsTheMidpoint() {
        val v = StudyVariable<Point>("x", 2.0..6.0) { p, _ -> p }
        assertEquals(listOf(2.0, 3.0, 4.0, 5.0, 6.0), v.samples(5))
        assertEquals(listOf(4.0), v.samples(1))
        assertFails { v.samples(0) }
    }

    @Test fun aVariableRefusesTheRangesThatWouldMakeASweepMeaningless() {
        assertFails { StudyVariable<Point>("", 0.0..1.0) { p, _ -> p } }
        assertFails { StudyVariable<Point>("x", 1.0..1.0) { p, _ -> p } }
        assertFails { StudyVariable<Point>("x", 2.0..1.0) { p, _ -> p } }
        assertFails { StudyVariable<Point>("x", Double.NaN..1.0) { p, _ -> p } }
        // Two axes that share a name would make the landscape's labels a lie.
        assertFails {
            StudySpace(
                Point(),
                StudyVariable<Point>("x", 0.0..1.0) { p, _ -> p },
                StudyVariable<Point>("x", 0.0..1.0) { p, _ -> p },
            )
        }
    }

    @Test fun coordinatesAreClampedRatherThanRejected() {
        // A mutation that steps off the edge of the space is ordinary; the edge has to be somewhere
        // a search can sit, not somewhere it dies.
        val s = space()
        assertEquals(Point(1.0, -2.0), s.at(doubleArrayOf(9.0, -9.0)))
        assertEquals(Point(0.5, 0.0), s.at(s.centre()))
        assertEquals(Point(0.0, -2.0), s.at(doubleArrayOf(Double.NaN, Double.NaN)))
        assertFails { s.at(doubleArrayOf(0.5)) }
    }

    // ------------------------------------------------------------------------------------------
    // Landscapes
    // ------------------------------------------------------------------------------------------

    /** A landscape assembled directly, so the indexing can be checked without an evaluator. */
    private fun landscapeOf(rows: Int, cols: Int, value: (Int, Int) -> Double?): StudyLandscape {
        val axes = listOf(
            StudyAxis("x", "m", (0 until rows).map { it.toDouble() }),
            StudyAxis("y", "", (0 until cols).map { it.toDouble() }),
        )
        val samples = ArrayList<StudySample>()
        for (r in 0 until rows) for (c in 0 until cols) {
            val v = value(r, c)
            samples += StudySample(
                doubleArrayOf(r.toDouble(), c.toDouble()), v, emptyMap(), 1.0, false,
                if (v == null) "declined" else null)
        }
        return StudyLandscape("miss", StudyGoal.MINIMISE, axes, samples)
    }

    @Test fun landscapeIndexingIsRowMajorWithTheLastAxisFastest() {
        val landscape = landscapeOf(3, 4) { r, c -> (10 * r + c).toDouble() }
        assertEquals(0.0, landscape.at(0, 0).objective)
        assertEquals(3.0, landscape.at(0, 3).objective)
        assertEquals(20.0, landscape.at(2, 0).objective)
        assertEquals(23.0, landscape.samples.last().objective)
        assertFails { landscape.at(0) }
        assertFails { landscape.at(3, 0) }
        assertFails { StudyLandscape("m", StudyGoal.MINIMISE, landscape.axes, landscape.samples.drop(1)) }
    }

    @Test fun infeasiblePointsAreHolesRatherThanZeros() {
        val landscape = landscapeOf(3, 3) { r, c -> if (r == 1 && c == 1) null else (r + c).toDouble() }
        assertEquals(8, landscape.feasible.size)
        assertEquals(0.0, landscape.best?.objective)
        // NaN in the matrix, not 0.0 — a zero would be the smallest value on the map and would win.
        assertTrue(landscape.matrix()[1][1].isNaN())
        assertEquals(0.0 to 4.0, landscape.range())
        assertNull(landscape.at(1, 1).objective)
        assertEquals("declined", landscape.at(1, 1).failure)
    }

    @Test fun everyPointInfeasibleIsAnEmptyLandscapeRatherThanACrash() {
        val landscape = landscapeOf(2, 2) { _, _ -> null }
        assertNull(landscape.best)
        assertEquals(0.0 to 1.0, landscape.range())
        assertTrue(landscape.summary().contains("0/4 feasible"))
    }

    @Test fun goalDecidesWhichEndIsBest() {
        val axes = listOf(StudyAxis("x", "", listOf(0.0, 1.0, 2.0)))
        val samples = listOf(3.0, 1.0, 7.0).mapIndexed { i, v ->
            StudySample(doubleArrayOf(i.toDouble()), v, emptyMap(), 0.0, false, null)
        }
        assertEquals(1.0, StudyLandscape("o", StudyGoal.MINIMISE, axes, samples).best?.objective)
        assertEquals(7.0, StudyLandscape("o", StudyGoal.MAXIMISE, axes, samples).best?.objective)
        assertFails { StudyLandscape("o", StudyGoal.MINIMISE, axes, samples).matrix() }
    }

    // ------------------------------------------------------------------------------------------
    // Figures
    // ------------------------------------------------------------------------------------------

    @Test fun aLandscapeWithStructureProducesAFigureWithStructure() {
        // The failure this catches is the one a byte count cannot: a figure that renders, writes a
        // plausible number of bytes, and is a single flat rectangle.
        val landscape = landscapeOf(9, 9) { r, c -> abs(r - c).toDouble() }
        val raster = KratosStudyFigure.landscapeRaster(landscape, width = 640, height = 420)
        assertEquals(640 * 420, raster.pixels.size)
        val distinct = raster.pixels.toHashSet()
        assertTrue(distinct.size > 20, "only ${distinct.size} distinct colours")
        // And the colour map is actually spanned: the two ends of the ramp must both appear.
        val brightest = raster.pixels.maxOf { (it shr 8) and 0xFF }
        val darkest = raster.pixels.minOf { (it shr 8) and 0xFF }
        assertTrue(brightest - darkest > 100, "green channel spans only ${brightest - darkest}")
        assertTrue(raster.png().size > 2000)
    }

    @Test fun anAllInfeasibleLandscapeStillDrawsSomething() {
        val landscape = landscapeOf(4, 4) { _, _ -> null }
        val raster = KratosStudyFigure.landscapeRaster(landscape, width = 400, height = 320)
        assertTrue(raster.pixels.toHashSet().size >= 3, "hatched cells and text should still appear")
    }

    @Test fun aThreeAxisStudyIsRefusedByTheFigureRatherThanDrawnWrong() {
        val axes = (0 until 3).map { StudyAxis("a$it", "", listOf(0.0, 1.0)) }
        val samples = (0 until 8).map {
            StudySample(doubleArrayOf(0.0, 0.0, 0.0), it.toDouble(), emptyMap(), 0.0, false, null)
        }
        val landscape = StudyLandscape("o", StudyGoal.MINIMISE, axes, samples)
        assertFails { KratosStudyFigure.landscapeRaster(landscape) }
        assertFails { landscape.matrix() }
    }

    @Test fun theFontCoversTheLabelsTheDemoStudyUses() {
        // The dot-matrix font has no lower case and no comma; a label outside its alphabet draws as
        // blanks, so the demo's own axis names are checked here rather than discovered in a figure.
        for (variable in KratosStudyDemo.space().variables) {
            assertTrue(KratosStudyFigure.drawable(variable.label), "not drawable: ${variable.label}")
        }
        assertTrue(KratosStudyFigure.drawable(KratosStudyDemo.objectiveName()))
        assertTrue(KratosStudyFigure.drawable(StudyGoal.MINIMISE.name))
    }

    // ------------------------------------------------------------------------------------------
    // The demo study's arithmetic, without a solver
    // ------------------------------------------------------------------------------------------

    @Test fun theClosedFormValleyInvertsTheSampleItWasDerivedFrom() {
        // phi = (T - Tb) k / q, and k* = q phi / (target - Tb). Feed it a sample that already sits
        // exactly on the target and the prediction must be that sample's own conductivity.
        val target = 318.0
        val k = 2.5
        val sample = StudySample(
            doubleArrayOf(0.6, k), 0.0, mapOf("probe" to target), 1.0, false, null)
        assertEquals(k, KratosStudyDemo.predictedConductivity(sample, target)!!, 1e-9)
        // Twice the conductivity at the same geometry halves the rise, so the prediction is unmoved.
        val half = KratosStudyDemo.BOUNDARY + (target - KratosStudyDemo.BOUNDARY) / 2
        val colder = StudySample(
            doubleArrayOf(0.6, 2 * k), abs(half - target), mapOf("probe" to half), 1.0, false, null)
        assertEquals(k, KratosStudyDemo.predictedConductivity(colder, target)!!, 1e-9)
        assertNull(KratosStudyDemo.predictedConductivity(
            StudySample(doubleArrayOf(0.6, k), null, emptyMap(), 0.0, false, "no")))
    }

    @Test fun theDemoPlansThermalGeometryOnEveryPoint() {
        val space = KratosStudyDemo.space()
        val case = KratosStudyDemo.plan(space.at(doubleArrayOf(0.5, 3.0)))
        assertEquals(ThermalParameters.FORMULATION, case.parameters.formulation)
        assertEquals(SimulationEdit.GEOMETRY, case.edit)
        assertNotNull(case.geometry)
        // The geometric axis really does move the body.
        val short = KratosStudyDemo.plan(space.at(doubleArrayOf(KratosStudyDemo.ARM.start, 3.0)))
        val long = KratosStudyDemo.plan(space.at(doubleArrayOf(KratosStudyDemo.ARM.endInclusive, 3.0)))
        assertTrue((short.geometry as PrimitiveGeometry).expression() !=
            (long.geometry as PrimitiveGeometry).expression())
        // And the material axis moves only the coefficients.
        val soft = KratosStudyDemo.plan(space.at(doubleArrayOf(0.5, 1.0))).parameters.wire()
        val stiff = KratosStudyDemo.plan(space.at(doubleArrayOf(0.5, 5.0))).parameters.wire()
        assertEquals(1.0, soft["conductivity"])
        assertEquals(5.0, stiff["conductivity"])
        assertEquals(soft["source"], stiff["source"])
    }

    @Test fun demoRangesPutTheProbeInsideTheArmForEveryArmLength() {
        // If the probe ever fell outside the body the objective would go infeasible and the study
        // would report a hole where the physics is fine. Both ends of the axis, checked by hand.
        val probeX = KratosStudyDemo.PROBE.x
        assertTrue(probeX < KratosStudyDemo.ARM.start, "probe at $probeX is past the shortest arm")
        // ...and beyond the base, so the geometric axis actually reaches it.
        val base = KratosDemo.body().a as SimulationBox
        assertTrue(probeX > base.bounds.maxX, "probe at $probeX is inside the base and would not move")
        // The arm is the second operand and is what the axis lengthens.
        val arm = KratosDemo.body(0.5).b as SimulationBox
        assertTrue(probeX > arm.bounds.minX && probeX < arm.bounds.maxX)
        assertTrue(KratosStudyDemo.PROBE.y > arm.bounds.minY && KratosStudyDemo.PROBE.y < arm.bounds.maxY)
        assertTrue(KratosStudyDemo.PROBE.z > arm.bounds.minZ && KratosStudyDemo.PROBE.z < arm.bounds.maxZ)
    }
}
