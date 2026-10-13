package afield.geometry.spatial

import afield.data.collections.get
import afield.data.collections.invoke
import afield.data.collections.times
import afield.document.graph.minusAssign
import afield.document.graph.plusAssign
import afield.document.properties.compareTo
import afield.graphics.render.rangeTo
import afield.math.autodiff.compareTo
import afield.math.autodiff.div
import afield.math.autodiff.dot
import afield.math.autodiff.minus
import afield.math.autodiff.plus
import afield.math.autodiff.times
import afield.math.linalg.invoke
import afield.math.linalg.remAssign
import afield.math.linalg.set
import afield.math.linalg.times
import afield.math.statistics.noise
import afield.math.units.div
import afield.math.units.minus
import afield.math.units.plus
import afield.math.units.times
import afield.media.video.image
import afield.runtime.execution.go
import afield.runtime.execution.start

import afield.animation.keyframes.h
import afield.data.collections.s
import afield.graphics.render.pointed
import afield.lang.stacksl.h
import afield.math.autodiff.mean
import afield.math.autodiff.minOf
import afield.math.linalg.Vec2
import afield.math.linalg.f
import afield.math.linalg.i
import afield.math.linalg.x
import afield.math.statistics.random
import afield.media.image.Img
import afield.runtime.execution.cell
import afield.simulation.agents.step
import afield.simulation.automata.step
import afield.ui.output.h
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Invariants of `stippling.kt`: exact counts, point density as `brightness^densityPower`, blue-noise
 * spacing against a uniform random sample, and black staying empty.
 */
class StipplingTest {

    private fun flat(w: Int, h: Int, v: Float = 1f) = Img.generate(w, h) { _, _ -> v }

    /** Nearest-neighbour distance of every point, brute force; the tests stay small enough for it. */
    private fun nearest(points: List<Vec2>): DoubleArray = DoubleArray(points.size) { i ->
        var best = Double.POSITIVE_INFINITY
        for (j in points.indices) if (j != i) {
            val dx = (points[i].x - points[j].x).toDouble()
            val dy = (points[i].y - points[j].y).toDouble()
            best = minOf(best, dx * dx + dy * dy)
        }
        sqrt(best)
    }

    private fun coefficientOfVariation(values: DoubleArray): Double {
        val mean = values.average()
        val sd = sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
        return sd / mean
    }

    @Test
    fun returnsExactlyCountPointsInsideTheImage() {
        val points = flat(120, 80).stipple(500, iterations = 10)
        assertEquals(500, points.size)
        assertTrue(points.all { it.x in 0f..120f && it.y in 0f..80f })
    }

    private val halfAndHalf = Img.generate(200, 100) { x, _ -> if (x < 100) 1f else 0.5f }

    private fun brightToDimRatio(points: List<Vec2>): Double {
        val left = points.count { it.x < 100f }
        return left.toDouble() / (points.size - left)
    }

    @Test
    fun pointDensityFollowsBrightnessAtTheDefaultPower() {
        // Left half twice as bright as the right half, so twice the points.
        val ratio = brightToDimRatio(halfAndHalf.stipple(1200, iterations = 40))
        assertTrue(ratio in 1.7..2.3, "bright/dim point ratio $ratio, expected ~2")
    }

    @Test
    fun densityPowerTwoSquaresTheContrast() {
        val ratio = brightToDimRatio(halfAndHalf.stipple(1200, iterations = 40, densityPower = 2f))
        assertTrue(ratio in 3.4..4.6, "bright/dim point ratio $ratio, expected ~4")
    }

    @Test
    fun relaxationDoesNotDriftTowardsTheCvtOptimum() {
        // The optimal CVT of this field has ratio sqrt(2). Lloyd from a weighted start does not go
        // there: after the first few steps a point or two crosses the edge and no more. Pinned so a
        // change to the iteration that starts moving points between regions shows up here.
        val s = Stippler(halfAndHalf, 1200)
        s.relax(10)
        val early = brightToDimRatio(s.points())
        s.relax(300)
        val late = brightToDimRatio(s.points())
        assertTrue(abs(late - early) / early < 0.03, "ratio drifted $early -> $late")
        assertTrue(late - sqrt(2.0) > 0.4, "ratio $late has moved towards sqrt(2)")
        assertEquals(0.0, s.step(), "the iteration should be at a fixed point")
    }

    @Test
    fun relaxedPointsAreFarMoreEvenlySpacedThanAUniformRandomSample() {
        val img = flat(160, 160)
        val relaxed = coefficientOfVariation(nearest(img.stipple(800, iterations = 30)))
        val rng = Random(7)
        val random = List(800) { Vec2(rng.nextFloat() * 160f, rng.nextFloat() * 160f) }
        val unrelaxed = coefficientOfVariation(nearest(random))
        // A Poisson process has a nearest-neighbour CV of about 0.52; a settled CVT is near-hexagonal.
        assertTrue(unrelaxed > 0.4, "random sample CV $unrelaxed")
        assertTrue(relaxed < 0.2, "relaxed CV $relaxed, random $unrelaxed")
    }

    @Test
    fun blackRegionsReceiveNoPoints() {
        // A black disc in a white field.
        val img = Img.generate(160, 160) { x, y ->
            val dx = x + 0.5 - 80.0
            val dy = y + 0.5 - 80.0
            if (dx * dx + dy * dy < 40.0 * 40.0) 0f else 1f
        }
        val points = img.stipple(600, iterations = 25)
        val inside = points.count {
            val dx = it.x - 80.0
            val dy = it.y - 80.0
            dx * dx + dy * dy < 36.0 * 36.0
        }
        assertEquals(0, inside, "points well inside the black disc")
        // invert stipples the disc instead
        val inverted = img.stipple(300, iterations = 25, invert = true)
        assertTrue(inverted.all { val dx = it.x - 80.0; val dy = it.y - 80.0; dx * dx + dy * dy < 41.0 * 41.0 })
    }

    @Test
    fun stepMovementSettlesAndIsDeterministicPerSeed() {
        val img = Img.generate(128, 128) { x, y -> (x + y) / 254f }
        val a = Stippler(img, 400, seed = 3, threads = 4)
        val first = a.step()
        repeat(20) { a.step() }
        val late = a.step()
        assertTrue(late < first * 0.25, "movement went $first -> $late")
        assertEquals(22, a.iterations)

        val b = Stippler(img, 400, seed = 3, threads = 4).relax(22)
        assertEquals(a.points(), b.points())
    }

    @Test
    fun aCountBeyondOnePointPerPixelIsRefused() {
        assertFailsWith<IllegalArgumentException> { Stippler(flat(10, 10), 101) }
        assertFailsWith<IllegalArgumentException> { Stippler(flat(10, 10, 0f), 10) }
    }

    @Test
    fun aPointPerPixelStillRasterisesEveryPoint() {
        // Every pixel holds a seed, so the collision search must find each one a free pixel.
        val points = flat(12, 9).stipple(108, iterations = 3)
        assertEquals(108, points.size)
    }

    @Test
    fun stipplesBecomeOnePointedLine() {
        val f = stipplesToFLine(listOf(Vec2(1f, 2f), Vec2(3f, 4f)), size = 2.0)
        assertTrue(f.pointed)
        assertEquals(2, f.nodes.size)
    }

    @Test
    fun stipplesRasteriseAsInkOnPaper() {
        val img = stipplesToImg(listOf(Vec2(10.5f, 10.5f)), 21, 21, radius = 2.0)
        assertEquals(0f, img.at(10, 10), "the dot's centre is solid ink")
        assertEquals(1f, img.at(0, 0), "paper away from the dot")
        assertTrue(img.at(13, 10) in 0f..1f && img.at(13, 10) > 0f, "the rim is anti-aliased")
        assertEquals(1f, stipplesToImg(listOf(Vec2(10.5f, 10.5f)), 21, 21, ink = false).at(10, 10))
    }

    @Test
    fun theTessellationIsSupersampledUntilAnAverageCellHasRoom() {
        // 6000 points over 480 x 360 is 29 image pixels a cell: 3x gives 259 raster pixels a cell.
        assertEquals(3, Stippler(flat(480, 360), 6000).supersample)
        assertEquals(1, Stippler(flat(480, 360), 100).supersample)
        assertEquals(4, Stippler(flat(100, 100), 9000).supersample, "capped at 4")
        // reported in image pixels whatever the raster
        val s = Stippler(flat(64, 48), 600, supersample = 3).relax(5)
        assertTrue(s.points().all { it.x in 0f..64f && it.y in 0f..48f })
    }

    @Test
    fun aFlatToneComesOutWithoutLumps() {
        // Count variance over 48 px windows, relative to a random scatter's (which is 1). An
        // independent random start keeps about 0.6 of it through relaxation; the Hilbert start ~0.03.
        val s = Stippler(flat(480, 360, 0.35f), 6000, invert = true).relax(100)
        val cell = 48
        val counts = IntArray((480 / cell) * (360 / cell))
        for (p in s.points()) {
            val ix = (p.x / cell).toInt()
            val iy = (p.y / cell).toInt()
            if (ix < 480 / cell && iy < 360 / cell) counts[iy * (480 / cell) + ix]++
        }
        val mean = counts.average()
        val dispersion = counts.sumOf { (it - mean) * (it - mean) } / counts.size / mean
        assertTrue(dispersion < 0.1, "window count variance / mean = $dispersion")
    }
}
