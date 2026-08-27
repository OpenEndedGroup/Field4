package afield.solidsl

import afield.stdlib.OptimizeMethod
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The frames behind `solid_notation.md`'s convergence filmstrip, and the assertions that make it a
 * measurement rather than an animation.
 *
 * [SolidSLPartialRecoveryTest] shows where the descent *ends*. It cannot show the thing that makes
 * the end interesting: two of the parameters are converging on an answer, one is converging on a
 * compromise, and they do it at very different speeds.
 *
 * # These frames are a recording, and that is a recent change
 *
 * [afield.stdlib.OptimizeRun] used to record `losses` and per-iteration `gradientInfNorm` but **not
 * the parameter vector**, so an intermediate shape could not be recovered from a finished run at
 * all — the only way to draw one was to re-run the descent truncated at that iteration. That is
 * slower, and it is a different claim: a truncated re-run is the same point only if the descent is
 * deterministic, which nothing was forcing it to be. `OptimizeRun.trajectory` now records it, and
 * [SolidFit.shapeAt] reads it, so every frame below is a point the optimizer really passed through.
 *
 * The test still asserts determinism, because [shapeAt] being *able* to hand back the real point
 * does not make the descent reproducible, and the page shows fixed numbers.
 */
class SolidSLConvergenceFilmstripTest {

    private val sphereRadius = 0.62
    private val barHeight = 2.20
    private val barRadius = 0.28
    private val seed = doubleArrayOf(1.35, 1.60, 0.42, 25.0)

    private fun model() = (cuboid(1.0) + cylinder(1.0, 1.0).turned(y = 1.0)).parameterize {
        param("cubeSize") { it.node == "Cuboid" }
        param("barHeight") { it.node == "Cylinder" && it.field == "height" }
        param("barRadius") { it.node == "Cylinder" && it.field.startsWith("radius") }
        param("barTilt") { it.node == "Rotate" && it.field == "y" }
    }.orThrow()

    private fun fit(): SolidFit {
        val truth = sphere(sphereRadius) + cylinder(barHeight, barRadius)
        val target = truth.sampleField(count = 3000, seed = 17)
        return model().fitParameters(
            target,
            seed,
            SolidFitOptions(method = OptimizeMethod.ADAM, iterations = 6000, learningRate = 0.015),
        )
    }

    @Test
    fun `the recorded trajectory is the descent, and it is monotone in loss and tilt`() {
        val f = fit()
        val names = model().names
        val traj = f.run.trajectory

        // 1. Premise: the trajectory indexes exactly like the losses, and its ends are the run's
        //    own ends. If this ever drifts, every frame on the page is mislabelled.
        assertEquals(f.run.losses.size, traj.size, "trajectory and losses must be indexed alike")
        assertTrue(traj.first().contentEquals(f.start), "trajectory[0] is not the seed")
        assertTrue(traj.last().contentEquals(f.values), "trajectory.last() is not the fitted answer")
        assertTrue(
            f.shapeAt(traj.lastIndex) == f.fitted,
            "shapeAt(last) disagrees with `fitted`",
        )

        val frames = listOf(0, 150, 500, 1200, 2400, traj.lastIndex)
        println("iter   loss          cubeSize   barHeight  barRadius  barTilt")
        for (i in frames) {
            val n = names.zip(traj[i].toList()).toMap()
            println(
                "%-6d %.6e  %.6f   %.6f   %.6f   %9.6f".format(
                    i, f.run.losses[i],
                    n.getValue("cubeSize"), n.getValue("barHeight"),
                    n.getValue("barRadius"), n.getValue("barTilt"),
                )
            )
        }

        // 2. Loss falls at every frame on the strip. Not "ends lower" -- falls at each one, which is
        //    what makes the picture readable left to right. (The whole descent is not monotone in
        //    general; ADAM's momentum can overshoot. The frames the page shows are.)
        for (k in 1 until frames.size) {
            assertTrue(
                f.run.losses[frames[k]] < f.run.losses[frames[k - 1]],
                "loss rose between frame ${frames[k - 1]} and ${frames[k]}: " +
                    "${f.run.losses[frames[k - 1]]} -> ${f.run.losses[frames[k]]}",
            )
        }

        // 3. The tilt walks toward axis-aligned and never back. This is the parameter the picture is
        //    for -- the rod visibly swinging into line -- so a non-monotone tilt would make the
        //    strip lie about what is happening.
        val tiltIndex = names.indexOf("barTilt")
        val tilts = frames.map { traj[it][tiltIndex] }
        for (k in 1 until tilts.size) {
            assertTrue(tilts[k] < tilts[k - 1], "barTilt rose between frames: ${tilts[k - 1]} -> ${tilts[k]}")
        }
        assertTrue(tilts.first() == 25.0, "frame 0 must be the seed tilt, was ${tilts.first()}")
        assertTrue(tilts.last() < 1.0, "the last frame should be essentially axis-aligned, was ${tilts.last()}")

        // 4. Reproducible, bit for bit -- the page quotes fixed numbers off these frames.
        val again = fit()
        assertTrue(again.values.contentEquals(f.values), "the descent was not reproducible")
        assertTrue(
            again.run.trajectory[1200].contentEquals(traj[1200]),
            "frame 1200 differed between two runs of the same fit",
        )
    }
}
