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
        // it also drifts, but its rim turns faster than its centre travels
        assertTrue(w.rimSpeed > w.speed, "$w")
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
        val rotor = ParticleLeniaZoo["Chungasto"].lenia().stepTimes(2000).measure(2000)
        assertEquals(LeniaMotion.ROTATING, rotor.motion, "$rotor")
        val still = ParticleLeniaZoo["Bellusauropel"].lenia().stepTimes(2000).measure(2000)
        assertEquals(LeniaMotion.STATIC, still.motion, "$still")
    }

    @Test fun demoIntegratorGlidesAtTheSameSpeed() {
        val euler = ParticleLeniaZoo["Tarbosaurolop"].lenia().stepTimes(2000).measure(2000)
        val demo = ParticleLeniaZoo["Tarbosaurolop"].lenia().also { it.inertia = 0.5 }.stepTimes(2000).measure(2000)
        println("PL euler $euler\nPL demo  $demo")
        assertTrue(abs(euler.speed - demo.speed) < 1e-3 * euler.speed, "euler ${euler.speed} demo ${demo.speed}")
    }

    @Test fun parallelStepMatchesSerialStep() {
        // 100 particles take the parallel path; the same positions stepped one particle at a time must agree exactly
        val a = ParticleLenia.random(100, side = 8.5, seed = 2L, params = ParticleLeniaParams(sigmaK = 0.6, cRep = 0.3)).stepTimes(50)
        val b = ParticleLenia(ParticleLenia.random(100, side = 8.5, seed = 2L).positions, ParticleLeniaParams(sigmaK = 0.6, cRep = 0.3))
        repeat(50) {
            val v = DoubleArray(b.positions.size)
            for (i in 0 until b.count) {
                val s = b.sample(b.positions[2 * i], b.positions[2 * i + 1])
                v[2 * i] = -s.gx; v[2 * i + 1] = -s.gy
            }
            for (k in v.indices) b.positions[k] += b.params.dt * v[k]
        }
        assertTrue(a.positions.contentEquals(b.positions), "parallel and serial steps differ")
    }

    @Test fun normalisedKernelWeightMatchesTheDemo() {
        // the demo's own w1 for Tarbosaurolop (muK 3.13, sigmaK 2.63)
        assertTrue(abs(ParticleLeniaParams.normalisedWK(3.13, 2.63) - 0.010787660998384171) < 1e-12)
    }
}
