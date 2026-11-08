package afield.simulation.agents

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the motion classification of `alife_particle_lenia_motion.kt` on creatures whose behaviour is
 * known: the page's rotator (dev_docs/alife/PARTICLE_LENIA.md, n 100, muK 4, sigmaK 0.6, cRep 0.3), a
 * glider and a still creature from the article demo's zoo, and the demo's integrator against ours.
 */
class ParticleLeniaMotionTest {
    @Test fun pageRotatorClassifiesAsRotating() {
        val pl = ParticleLenia.random(100, side = 12.0 * Math.sqrt(0.5), seed = 1L, params = ParticleLeniaParams(sigmaK = 0.6, cRep = 0.3))
        pl.stepTimes(3000)
        val w = pl.measure(2000)
        println("PL rotator: $w")
        assertEquals(LeniaMotion.ROTATING, w.motion, "$w")
        assertTrue(w.angularVelocity in 0.002..0.0035, "$w")
        assertEquals(1, w.groups)
    }

    @Test fun zooCreaturesClassify() {
        for (c in ParticleLeniaZoo.creatures) {
            val pl = c.lenia()
            pl.stepTimes(2000)
            println("PL zoo ${c.name} (${c.count}): ${pl.measure(2000)}")
        }
        val glider = ParticleLeniaZoo["Tarbosaurolop"].lenia().stepTimes(2000).measure(2000)
        assertEquals(LeniaMotion.GLIDING, glider.motion, "$glider")
        assertTrue(abs(glider.speed - 0.124) < 0.005, "$glider")
        val still = ParticleLeniaZoo["Bellusauropel"].lenia().stepTimes(2000).measure(2000)
        assertEquals(LeniaMotion.STATIC, still.motion, "$still")
    }

    @Test fun demoIntegratorGlidesAtTheSameSpeed() {
        val euler = ParticleLeniaZoo["Tarbosaurolop"].lenia().stepTimes(2000).measure(2000)
        val demo = ParticleLeniaZoo["Tarbosaurolop"].lenia().also { it.inertia = 0.5 }.stepTimes(2000).measure(2000)
        println("PL euler $euler\nPL demo  $demo")
        assertTrue(abs(euler.speed - demo.speed) < 1e-3 * euler.speed, "euler ${euler.speed} demo ${demo.speed}")
    }

    @Test fun normalisedKernelWeightMatchesTheDemo() {
        // the demo's own w1 for Tarbosaurolop (muK 3.13, sigmaK 2.63)
        assertTrue(abs(ParticleLeniaParams.normalisedWK(3.13, 2.63) - 0.010787660998384171) < 1e-12)
    }
}
