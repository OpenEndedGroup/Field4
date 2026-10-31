package afield.simulation.agents

import afield.study.search.PopulationSearch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the numbers behind the "where to take it" sections of `public/docs/braitenberg_vehicles.md`:
 * BUMP orbits, a speed cost in the reward, combined learning and homeostasis, moving lights, and
 * evolved wirings. Each compares against the same seed with the ingredient removed.
 */
class VehicleWhereNextTest {
    private val lights = listOf(Light(60.0, 60.0), Light(180.0, 70.0), Light(120.0, 180.0), Light(200.0, 200.0))

    private fun orbitRadii(p: VehicleParams): OrbitRadii {
        val w = VehicleWorld.random(60, lights, wiring = VehicleWorld.AGGRESSOR, seed = 3L)
        w.stepTimes(p, 6000)
        val o = OrbitRadii()
        repeat(2000) { w.step(p); o.observe(w) }
        return o
    }

    @Test fun bumpAggressorsCircleJustOutsideTheTunedRadius() {
        val orbit = orbitRadii(VehicleParams(neuron = NeuronParams(activation = Activation.BUMP, gain = 4.0, tauMembrane = 1.0, bias = orbitBias(12.0))))
        assertTrue(orbit.median() in 13.5..15.5, "median ${orbit.median()}")
        assertTrue(orbit.fractionWithin(7.0, 17.0) > 0.95, "within ${orbit.fractionWithin(7.0, 17.0)}")
        val control = orbitRadii(VehicleParams())
        assertTrue(control.median() > 30.0, "sigmoid aggressors drive through lights: median ${control.median()}")
    }

    @Test fun aSlowMembraneBreaksTheOrbit() {
        val slow = orbitRadii(VehicleParams(neuron = NeuronParams(activation = Activation.BUMP, gain = 4.0, tauMembrane = 4.0, bias = orbitBias(12.0))))
        assertTrue(slow.median() > 30.0, "median ${slow.median()}")
    }

    private fun learnedArm(p: VehicleParams, seed: Long = 5L): Triple<Double, Double, Double> {
        val w = VehicleWorld.random(80, lights, seed = seed)
        w.stepTimes(p, 15000)
        var light = 0.0
        var speed = 0.0
        repeat(2000) { w.step(p); light += w.meanLight(); speed += w.meanSpeed() }
        return Triple(light / 2000, speed / 2000, w.w.sum() / w.count)
    }

    @Test fun aSpeedCostBuysSlowerVehiclesForALittleLight() {
        val base = VehicleParams(neuron = NeuronParams(bias = -1.0))
        val (light0, speed0, _) = learnedArm(base.copy(plasticity = PlasticityParams(learningRate = 3.0)))
        val (light1, speed1, w1) = learnedArm(base.copy(plasticity = PlasticityParams(learningRate = 3.0, speedCost = 0.5)))
        assertTrue(speed0 - speed1 > 0.15, "speed $speed0 -> $speed1")
        assertTrue(light0 - light1 in 0.0..0.1, "light $light0 -> $light1")
        assertTrue(w1 < -4.0, "summed weight $w1")
    }

    @Test fun learningWithHomeostasisSitsBetweenTheTwo() {
        val base = VehicleParams(neuron = NeuronParams(bias = -1.0))
        val none = learnedArm(base).first
        val learn = learnedArm(base.copy(plasticity = PlasticityParams(learningRate = 3.0))).first
        val both = learnedArm(base.copy(neuron = base.neuron.copy(adaptationRate = 0.002), plasticity = PlasticityParams(learningRate = 3.0))).first
        assertTrue(none < both && both < learn, "none $none, both $both, learn $learn")
    }

    @Test fun movingLightsMoveAndWrap() {
        val w = VehicleWorld.random(1, listOf(Light(235.0, 10.0, vx = 1.0, vy = -0.5)), seed = 1L)
        w.stepTimes(VehicleParams(), 10)
        assertEquals(5.0, w.lights[0].x, 1e-9)
        assertEquals(5.0, w.lights[0].y, 1e-9)
    }

    @Test fun trailAlongIsNegativeBehindALight() {
        val l = Light(100.0, 100.0, vx = 0.2)
        val w = VehicleWorld(240.0, 240.0, listOf(l), doubleArrayOf(95.0), doubleArrayOf(100.0), doubleArrayOf(0.0),
            DoubleArray(4), DoubleArray(6), DoubleArray(4), DoubleArray(1))
        assertEquals(-5.0, w.trailAlong(), 1e-9)
    }

    @Test fun aLoverFollowsAMovingLightBetterThanTheLearnedWiring() {
        val base = VehicleParams(neuron = NeuronParams(bias = -1.0))
        val trained = VehicleWorld.random(80, lights, seed = 5L)
        trained.stepTimes(base.copy(plasticity = PlasticityParams(learningRate = 3.0)), 15000)
        val moving = lights.mapIndexed { k, l -> l.copy(vx = 0.1 * kotlin.math.cos(k * 1.7), vy = 0.1 * kotlin.math.sin(k * 1.7)) }
        fun meanLight(src: VehicleWorld): Double {
            val w = VehicleWorld(src.width, src.height, moving, src.x.copyOf(), src.y.copyOf(), src.heading.copyOf(),
                src.w.copyOf(), src.neurons.copyOf(), src.traces.copyOf(), src.baseline.copyOf(), 11L)
            w.stepTimes(base, 1000)
            var s = 0.0
            repeat(2000) { w.step(base); s += w.meanLight() }
            return s / 2000
        }
        val learned = meanLight(trained)
        val lover = meanLight(VehicleWorld.random(80, lights, wiring = VehicleWorld.LOVER, seed = 5L))
        assertTrue(lover > learned + 0.2, "lover $lover, learned $learned")
    }

    @Test fun searchedWiringsBeatTheBookWirings() {
        val objective = vehicleForageObjective(lights)
        val lover = objective.evaluate(VehicleWorld.LOVER).fitness
        val run = PopulationSearch.evolve(VehicleWiringGenome(), objective, 120, seed = 13L, mu = 6, lambda = 12, crossoverRate = 0.3)
        assertTrue(run.bestFitness > lover + 0.2, "evolved ${run.bestFitness}, lover $lover")
    }
}
