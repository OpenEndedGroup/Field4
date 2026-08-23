package afield.four.physics

import afield.stdlib.vec
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers [Wind], [Turbulence] and [ClothWind] — the air the world layer blows.
 *
 * The claim that needs the most care here is "the sheet flutters". A sheet leaning into a steady
 * wind and a sheet flapping in one both look like "the wind is working" from a single frame and
 * from a single number; what separates them is motion that *does not settle*. So flutter is
 * measured as the trailing edge's per-frame travel averaged over the last third of a long run,
 * after any transient is over, and both models are run through the same measurement.
 */
class SimdVerletWindTest {

    /** A flag: pinned along its left edge, hanging in the XY plane, free to move in z. */
    private fun flag(
        sim: SimdVerlet,
        width: Int = 20,
        height: Int = 12,
        stiffness: Float = 60f
    ): Cloth = sim.addCloth(
        width = width, height = height, spacing = 0.25f, stiffness = stiffness,
        curvatureStrength = 0f,
        position = { c, r -> vec(c * 0.25f, -r * 0.25f, 0f) },
        pinned = { c, _ -> c == 0 }
    )

    private fun newSim() = SimdVerlet(SimdVerlet.State3.empty())

    // ------------------------------------------------------------------------------------------
    // The gust field
    // ------------------------------------------------------------------------------------------

    @Test
    fun turbulenceIsBoundedSmoothAndExactlyReproducible() {
        val a = Turbulence(seed = 7, octaves = 4)
        val b = Turbulence(seed = 7, octaves = 4)
        val other = Turbulence(seed = 8, octaves = 4)

        var maxAbs = 0f
        var maxStep = 0f
        var differsFromOther = 0
        var previous = a.sample(0, 0f, 0f, 0f, 0f)
        for (n in 0 until 4000) {
            val t = n * 0.01f
            val x = n * 0.013f
            val v = a.sample(0, x, 0.3f, -0.7f, t)
            assertEquals(v, b.sample(0, x, 0.3f, -0.7f, t), 0f, "same seed must be bit-identical at n=$n")
            if (abs(v) > maxAbs) maxAbs = abs(v)
            if (abs(v - previous) > maxStep) maxStep = abs(v - previous)
            previous = v
            if (abs(v - other.sample(0, x, 0.3f, -0.7f, t)) > 1e-4f) differsFromOther++
        }
        println("turbulence: |max| %.4f, largest step between adjacent samples %.4f".format(maxAbs, maxStep))
        assertTrue(maxAbs <= 1f, "the field must stay in [-1, 1], saw $maxAbs")
        assertTrue(maxAbs > 0.4f, "premise: and must actually use the range, saw only $maxAbs")
        assertTrue(maxStep < 0.2f, "and be smooth at this sampling rate, largest step $maxStep")
        assertTrue(differsFromOther > 3900, "a different seed must be a different field, $differsFromOther/4000")

        // The three components must be independent, not one field scaled three ways.
        var maxCross = 0f
        for (n in 0 until 500) {
            val t = n * 0.02f
            val d = abs(a.sample(0, 1f, 2f, 3f, t) - a.sample(1, 1f, 2f, 3f, t))
            if (d > maxCross) maxCross = d
        }
        assertTrue(maxCross > 0.3f, "x and y gusts must be independent, largest difference $maxCross")
    }

    @Test
    fun windSamplesTheMeanPlusTheGustAndScalesWithSpeed() {
        val sim = newSim()
        val out = FloatArray(3)

        val steady = sim.addWind(velocityX = 4f, turbulence = 0f, name = "steady")
        steady.sampleInto(1f, 2f, 3f, out)
        assertEquals(4f, out[0], 0f, "no turbulence is exactly the mean")
        assertEquals(0f, out[1], 0f)
        assertEquals(0f, out[2], 0f)

        val gusty = sim.addWind(velocityX = 4f, turbulence = 0.5f, seed = 3, name = "gusty")
        var maxDeviation = 0f
        for (n in 0 until 400) {
            gusty.sampleInto(n * 0.05f, 0f, 0f, out, t = n * 0.02f)
            val d = sqrt((out[0] - 4f) * (out[0] - 4f) + out[1] * out[1] + out[2] * out[2])
            if (d > maxDeviation) maxDeviation = d
        }
        // turbulence 0.5 on a speed-4 wind is gusts of up to 2 per component, so up to ~3.46 in 3D.
        println("gust deviation from the mean, peak over 400 samples: %.3f".format(maxDeviation))
        assertTrue(maxDeviation > 0.8f, "gusts must actually gust, peak deviation $maxDeviation")
        assertTrue(maxDeviation <= 0.5f * 4f * sqrt(3f) + 1e-3f, "and stay inside the amplitude, $maxDeviation")
    }

    @Test
    fun windLinesAreDrawableAndNonEmpty() {
        val sim = newSim()
        val wind = sim.addWind(velocityX = 3f, turbulence = 0.6f, seed = 11)
        val lines = wind.windLines(0f, -3f, 0f, 6f, 0f, 0f, columns = 8, rows = 6)
        assertTrue(lines.isNotEmpty(), "a non-zero wind must draw")
        val nodes = lines.sumOf { it.nodes.size }
        assertEquals(8 * 6 * 2, nodes, "one whisker per grid point")
        for (line in lines) for (node in line.nodes) {
            assertTrue(node.to.x.isFinite() && node.to.y.isFinite() && node.to.z.isFinite(), "finite geometry")
        }
        assertTrue(
            sim.addWind(velocityX = 0f, name = "still").windLines(0f, 0f, 0f, 1f, 1f, 0f).isEmpty(),
            "still air draws nothing"
        )
    }

    // ------------------------------------------------------------------------------------------
    // The two models
    // ------------------------------------------------------------------------------------------

    /**
     * Mean per-frame travel of the trailing edge over the last third of the run: how much the
     * unpinned end of the flag is *still moving* once any transient is over.
     */
    private fun trailingEdgeMotion(sim: SimdVerlet, cloth: Cloth, frames: Int, step: () -> Unit): Float {
        val trailing = (0 until cloth.height).map { cloth[cloth.width - 1, it] }
        var travel = 0.0
        var counted = 0
        val prev = FloatArray(trailing.size * 3)
        for (f in 0 until frames) {
            step()
            for ((n, i) in trailing.withIndex()) {
                if (f > frames * 2 / 3) {
                    val dx = sim.state.x[i] - prev[n * 3]
                    val dy = sim.state.y[i] - prev[n * 3 + 1]
                    val dz = sim.state.z[i] - prev[n * 3 + 2]
                    travel += sqrt((dx * dx + dy * dy + dz * dz).toDouble())
                    counted++
                }
                prev[n * 3] = sim.state.x[i]
                prev[n * 3 + 1] = sim.state.y[i]
                prev[n * 3 + 2] = sim.state.z[i]
            }
        }
        assertTrue(counted > 0, "premise: the measurement window must contain frames")
        return (travel / counted).toFloat()
    }

    /**
     * The defining property, measured directly rather than inferred from a picture: a per-quad
     * force depends on which way the fabric is facing, and a per-particle drag force cannot.
     *
     * Two identical sheets at rest in the same wind, one face-on and one edge-on. Measured, the
     * per-quad pass gives the face-on sheet 209 against the edge-on sheet's 8.2, and every bit of
     * that 8.2 is the skin-friction term — switch [ClothWind.tangentialCoefficient] off and the
     * edge-on sheet gets nothing at all. The drag pass gives the two the same 345.6 to the last
     * bit, because a particle has no orientation to have.
     */
    @Test
    fun onlyThePerQuadModelKnowsWhichWayTheFabricIsFacing() {
        fun sheet(faceOn: Boolean): Triple<SimdVerlet, Cloth, ClothWind> {
            val sim = newSim()
            val cloth = sim.addCloth(
                width = 12, height = 12, spacing = 0.25f, stiffness = 60f, curvatureStrength = 0f,
                // Face-on: the sheet lies in xy, normals along z, and the wind blows along z.
                // Edge-on: the same sheet rotated into yz, normals along x, wind along its surface.
                position = { c, r ->
                    if (faceOn) vec(c * 0.25f, -r * 0.25f, 0f) else vec(0f, -r * 0.25f, c * 0.25f)
                },
                pinned = { _, _ -> false }
            )
            val wind = sim.addWind(velocityZ = 6f, drag = 0.4f)
            return Triple(sim, cloth, cloth.addWind(wind))
        }

        fun forceMagnitude(sim: SimdVerlet, cloth: Cloth, pass: (SimdVerlet.State3, Float) -> Unit): Float {
            SimdVerlet.zero3(sim.state)
            pass(sim.state, 1f / 60f)
            var sum = 0f
            for (i in cloth.base until cloth.end) {
                sum += sqrt(
                    sim.state.ax[i] * sim.state.ax[i] +
                        sim.state.ay[i] * sim.state.ay[i] +
                        sim.state.az[i] * sim.state.az[i]
                )
            }
            return sum
        }

        val (faceSim, faceCloth, faceAero) = sheet(faceOn = true)
        val (edgeSim, edgeCloth, edgeAero) = sheet(faceOn = false)

        val faceAeroForce = forceMagnitude(faceSim, faceCloth) { st, dt -> faceAero.accumulate(st, dt) }
        val edgeAeroForce = forceMagnitude(edgeSim, edgeCloth) { st, dt -> edgeAero.accumulate(st, dt) }
        val faceDragForce = forceMagnitude(faceSim, faceCloth) { st, dt -> faceAero.wind.accumulate(st, dt) }
        val edgeDragForce = forceMagnitude(edgeSim, edgeCloth) { st, dt -> edgeAero.wind.accumulate(st, dt) }

        println("summed force on a 12 x 12 sheet at rest in a +z wind of 6:")
        println("  per-quad   face-on %10.3f   edge-on %10.5f".format(faceAeroForce, edgeAeroForce))
        println("  drag       face-on %10.3f   edge-on %10.5f".format(faceDragForce, edgeDragForce))

        assertTrue(faceAeroForce > 100f, "premise: the per-quad pass must be doing something, $faceAeroForce")
        assertTrue(faceDragForce > 100f, "premise: and so must the drag pass, $faceDragForce")
        assertTrue(
            faceAeroForce > 20f * edgeAeroForce,
            "the per-quad model must barely touch an edge-on sheet: $faceAeroForce vs $edgeAeroForce"
        )
        // What is left on the edge-on sheet is exactly the skin-friction term and nothing else --
        // which is checkable rather than assertable-by-eye: turn the coefficient off and it goes.
        edgeAero.tangentialCoefficient = 0f
        val edgeNormalOnly = forceMagnitude(edgeSim, edgeCloth) { st, dt -> edgeAero.accumulate(st, dt) }
        println("  per-quad   edge-on, tangentialCoefficient = 0: %.8f".format(edgeNormalOnly))
        assertTrue(
            edgeNormalOnly < faceAeroForce * 1e-6f,
            "an edge-on sheet must get no normal force at all: $edgeNormalOnly against $faceAeroForce"
        )
        assertEquals(
            faceDragForce, edgeDragForce, faceDragForce * 1e-5f,
            "drag cannot tell the two apart -- a particle has no orientation"
        )
    }

    /**
     * The ablation for sustained motion, and its caveat.
     *
     * Both runs get the *same* [Wind] with the same drag, the same gusts and the same seed; the
     * only difference is whether [Cloth.addWind] is also registered. So the ratio is what the
     * sheet's own orientation adds, and nothing else.
     *
     * The caveat is asserted rather than mentioned: the advantage *shrinks* as [Wind.drag] rises,
     * because past a point the drag pass is doing the forcing and the sheet is being carried
     * rather than flying. Turn drag up far enough and the per-quad model stops mattering, and a
     * reader deserves to know where that is.
     */
    @Test
    fun perQuadAerodynamicsAddsSustainedMotionThatDragDoesNot() {
        val frames = 900

        fun motion(dragK: Float, aeroOn: Boolean): Float {
            val sim = newSim()
            val cloth = flag(sim)
            val wind = sim.addWind(velocityX = 8f, drag = dragK, turbulence = 0.6f, seed = 4)
            if (aeroOn) cloth.addWind(wind)
            return trailingEdgeMotion(sim, cloth, frames) {
                cloth.step(dt = 1f / 60f, damping = 0.98f, gravity = -2f, iterations = 16)
            }
        }

        val stillSim = newSim()
        val stillCloth = flag(stillSim)
        val still = trailingEdgeMotion(stillSim, stillCloth, frames) {
            stillCloth.step(dt = 1f / 60f, damping = 0.98f, gravity = -2f, iterations = 16)
        }
        assertTrue(still < 4e-4f, "premise: a sheet in still air must have settled, still moving $still")

        val drags = listOf(0.05f, 0.15f, 0.50f)
        val without = drags.map { motion(it, aeroOn = false) }
        val with = drags.map { motion(it, aeroOn = true) }

        println(
            ("20 x 12 flag, wind +x at 8 with 60 %% gusts, damping 0.98, %d frames; " +
                "trailing-edge travel per frame over the last %d").format(frames, frames / 3)
        )
        println("  Wind.drag   drag only   drag + per-quad   ratio")
        for (n in drags.indices) {
            println("  %9.2f   %9.5f   %15.5f   %5.2fx".format(drags[n], without[n], with[n], with[n] / without[n]))
        }
        println("  still air   %9.5f".format(still))

        for (n in drags.indices) {
            assertTrue(without[n].isFinite() && with[n].isFinite(), "nothing may diverge at drag ${drags[n]}")
            assertTrue(with[n] > without[n], "the per-quad pass must add motion at drag ${drags[n]}")
        }
        val ratios = drags.indices.map { with[it] / without[it] }
        assertTrue(ratios[0] > 2.5f, "at low drag the per-quad model must dominate, ratio ${ratios[0]}")
        assertTrue(with[0] > 0.004f, "and the motion must be visible at all, ${with[0]} per frame")
        for (n in 1 until ratios.size) {
            assertTrue(
                ratios[n] < ratios[n - 1],
                "and the advantage must fall away as drag takes over the forcing: $ratios"
            )
        }
        assertTrue(ratios.last() < 1.5f, "far enough, and it hardly matters: ${ratios.last()}")
    }

    @Test
    fun aSteadyWindPushesTheSheetDownwind() {
        val sim = newSim()
        val cloth = flag(sim)
        val wind = sim.addWind(velocityZ = 6f, drag = 0.1f)
        cloth.addWind(wind)
        repeat(600) { cloth.step(dt = 1f / 60f, damping = 0.99f, gravity = -2f, iterations = 16) }
        var meanZ = 0f
        for (i in cloth.base until cloth.end) meanZ += sim.state.z[i]
        meanZ /= cloth.count
        val tip = sim.state.z[cloth[cloth.width - 1, cloth.height / 2]]
        println("steady wind +z: mean sheet z %.3f, trailing tip z %.3f".format(meanZ, tip))
        assertTrue(meanZ > 0.15f, "a +z wind must carry the sheet downwind, mean z $meanZ")
        assertTrue(tip > meanZ, "and the free end further than the mean: $tip vs $meanZ")
    }

    @Test
    fun turbulenceIsReproducibleThroughAWholeSimulation() {
        fun run(seed: Int): FloatArray {
            val sim = newSim()
            val cloth = flag(sim, width = 12, height = 8)
            val wind = sim.addWind(velocityZ = 6f, drag = 0.2f, turbulence = 0.7f, seed = seed)
            cloth.addWind(wind)
            repeat(400) { cloth.step(dt = 1f / 60f, damping = 0.99f, gravity = -2f, iterations = 16) }
            return FloatArray(cloth.count * 3) { n ->
                val i = cloth.base + n / 3
                when (n % 3) {
                    0 -> sim.state.x[i]
                    1 -> sim.state.y[i]
                    else -> sim.state.z[i]
                }
            }
        }

        val a = run(5)
        val b = run(5)
        val c = run(6)
        assertTrue(a.all { it.isFinite() }, "premise: the run must not diverge")
        for (n in a.indices) assertEquals(a[n], b[n], 0f, "seed 5 twice must be bit-identical at $n")
        var differing = 0
        for (n in a.indices) if (abs(a[n] - c[n]) > 1e-3f) differing++
        println("seed 5 vs seed 6: $differing of ${a.size} coordinates differ by more than 1e-3")
        assertTrue(differing > a.size / 2, "a different seed must be a visibly different sheet, only $differing differ")
    }

    @Test
    fun theWindDoesNotMoveASheetItIsNotPointedAt() {
        // Range-limited wind: two flags, one in the wind and one out of it.
        val sim = newSim()
        val inWind = flag(sim, width = 10, height = 6)
        val sheltered = sim.addCloth(
            width = 10, height = 6, spacing = 0.25f, stiffness = 60f, curvatureStrength = 0f,
            position = { c, r -> vec(20f + c * 0.25f, -r * 0.25f, 0f) },
            pinned = { c, _ -> c == 0 }
        )
        sim.addWind(velocityZ = 8f, drag = 1.2f, range = inWind.base until inWind.end)
        repeat(300) { inWind.step(dt = 1f / 60f, damping = 0.99f, gravity = 0f, iterations = 16) }

        var movedZ = 0f
        for (i in inWind.base until inWind.end) movedZ += abs(sim.state.z[i])
        var shelteredZ = 0f
        for (i in sheltered.base until sheltered.end) shelteredZ += abs(sim.state.z[i])
        println("summed |z|: in the wind %.3f, sheltered %.6f".format(movedZ, shelteredZ))
        assertTrue(movedZ > 2f, "premise: the wind must move the flag it is pointed at, $movedZ")
        assertTrue(shelteredZ < 1e-4f, "and leave the other one exactly alone, $shelteredZ")
    }

    @Test
    fun detachingStopsBothPasses() {
        val sim = newSim()
        val cloth = flag(sim, width = 10, height = 6)
        val wind = sim.addWind(velocityZ = 8f, drag = 0.2f)
        val aero = cloth.addWind(wind)
        repeat(120) { cloth.step(dt = 1f / 60f, damping = 0.99f, gravity = 0f, iterations = 16) }
        assertTrue(aero.lastImpulse > 0f, "premise: the aero pass is doing something, ${aero.lastImpulse}")

        aero.detach()
        wind.detach()
        val before = FloatArray(cloth.count) { sim.state.z[cloth.base + it] }
        repeat(120) { cloth.step(dt = 1f / 60f, damping = 0.5f, gravity = 0f, iterations = 16) }
        assertEquals(0f, aero.lastImpulse, 0f, "a detached aero pass must not run, and zeroes its readout")
        // With no wind and heavy damping the sheet must relax back rather than being blown further.
        var furtherOut = 0
        for (n in 0 until cloth.count) if (sim.state.z[cloth.base + n] > before[n] + 1e-4f) furtherOut++
        println("after detaching: $furtherOut of ${cloth.count} nodes still moving downwind")
        assertTrue(furtherOut < cloth.count / 4, "the wind must be off, $furtherOut nodes still travelling")
        assertTrue("wind" !in sim.bulkForces, "and unregistered")
    }
}
