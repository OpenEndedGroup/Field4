package afield.simulation.agents

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins Flow Lenia (`alife_flow_lenia.kt`): total mass conserved to rounding over hundreds of steps
 * under both mixing rules, per-species mass conserved under mass-weighted mixing, tracers that stay
 * fractions, and a species alone in a shared-kernel world behaving exactly as in its own world.
 */
class FlowLeniaTest {
    private fun threeSpecies(mixing: FlowLeniaMixing) = flowLeniaWorld(
        64, 64,
        listOf(FlowLeniaPresets.wanderers(), FlowLeniaPresets.rings(), FlowLeniaPresets.cell()),
        positions = listOf(20.0 to 20.0, 44.0 to 24.0, 32.0 to 46.0),
        seedRadius = 10.0,
        mixing = mixing,
    )

    @Test fun totalMassIsConservedUnderBothMixingRules() {
        for (mixing in FlowLeniaMixing.entries) {
            val w = threeSpecies(mixing)
            val m0 = w.totalMass()
            var worst = 0.0
            repeat(300) {
                w.step()
                worst = maxOf(worst, abs(w.totalMass() - m0) / m0)
            }
            println("FlowLenia $mixing: m0=$m0 worst relative drift $worst; $w")
            assertTrue(m0 > 100.0, "seeded something: $m0")
            assertTrue(worst < 1e-10, "$mixing: relative mass drift $worst")
            assertTrue(w.matter.all { it >= 0.0 }, "no negative matter")
        }
    }

    @Test fun meanMixingConservesEachSpeciesAndKeepsTracersFractions() {
        val w = threeSpecies(FlowLeniaMixing.MEAN)
        val s0 = w.speciesMasses()
        w.steps(300)
        val s1 = w.speciesMasses()
        for (i in s0.indices) assertTrue(abs(s1[i] - s0[i]) / s0[i] < 1e-10, "species $i: ${s0[i]} -> ${s1[i]}")
        val n = w.width * w.height
        for (c in 0 until n) if (w.matter[c] > 1e-9) {
            val sum = (0 until w.speciesCount).sumOf { w.species[it * n + c] }
            assertTrue(abs(sum - 1.0) < 1e-9, "tracer sum $sum at $c")
        }
    }

    @Test fun matterMovesAndClassicLeniaDoesNotConserve() {
        val w = flowLeniaWorld(64, 64, listOf(FlowLeniaPresets.wanderers()))
        val before = w.matter.copyOf()
        w.steps(100)
        val moved = before.indices.sumOf { abs(before[it] - w.matter[it]) }
        assertTrue(moved > 10.0, "the field changed: $moved")
        // Control: the clamped growth update of ordinary Lenia changes the total freely.
        val orbium = LeniaPresets.orbium()
        val a = orbium.seed(64, 64)
        val m0 = a.data.sum()
        val m1 = a.leniaTimes(orbium.rule, 20).data.sum()
        assertTrue(abs(m1 - m0) / m0 > 1e-3, "classic Lenia's mass moved: $m0 -> $m1")
    }

    @Test fun aSpeciesAloneIgnoresOtherSpeciesKernels() {
        val alone = flowLeniaWorld(48, 48, listOf(FlowLeniaPresets.rings()))
        val shared = FlowLeniaWorld(48, 48, FlowLeniaRule(FlowLeniaPresets.cell().kernels + FlowLeniaPresets.rings().kernels), 1)
        shared.seed(0, DoubleArray(4) + FlowLeniaPresets.rings().h, 24.0, 24.0)
        alone.steps(40); shared.steps(40)
        val worst = alone.matter.indices.maxOf { abs(alone.matter[it] - shared.matter[it]) }
        assertEquals(0.0, worst, 1e-9, "same rule with zero-weighted extra kernels")
    }

    @Test fun sparseAffinityMatchesADenseGather() {
        val w = threeSpecies(FlowLeniaMixing.MEAN).steps(20)
        val u = w.affinity()
        val n = w.width * w.height
        var worst = 0.0
        for (c in 0 until n) {
            val x = c % w.width; val y = c / w.width
            // only cells next to matter are evaluated
            val near = (-1..1).any { j -> (-1..1).any { i -> w.matter[Math.floorMod(y + j, w.height) * w.width + Math.floorMod(x + i, w.width)] > 0.0 } }
            if (!near) continue
            var dense = 0.0
            for ((k, kernel) in w.rule.kernels.withIndex()) {
                val t = kernel.taps
                var acc = 0.0
                for (i in 0 until t.size) acc += w.matter[Math.floorMod(y + t.dy[i], w.height) * w.width + Math.floorMod(x + t.dx[i], w.width)] * t.weight[i]
                dense += w.weights[k * n + c] * kernel.growth(acc)
            }
            worst = maxOf(worst, abs(dense - u[c]))
        }
        assertTrue(worst < 1e-9, "sparse vs dense affinity: $worst")
    }

    @Test fun fftAffinityMatchesSparse() {
        // 96 = 2^5 * 3 exercises the mixed-radix path, 64 the power-of-two one.
        for (size in listOf(64, 96)) {
            val w = flowLeniaWorld(size, size, listOf(FlowLeniaPresets.wanderers(), FlowLeniaPresets.rings())).steps(10)
            val sparse = w.copy(w.rule.withConvolution(FlowLeniaConvolution.SPARSE))
            val viaFft = w.copy(w.rule.withConvolution(FlowLeniaConvolution.FFT))
            assertTrue(!sparse.usesFft() && viaFft.usesFft())
            val a = sparse.affinity()
            val b = viaFft.affinity()
            val worst = a.indices.maxOf { abs(a[it] - b[it]) }
            println("FlowLenia FFT vs sparse affinity at $size: $worst")
            assertTrue(worst < 1e-9, "FFT vs sparse at $size: $worst")
        }
    }

    @Test fun fftStepConservesAndTracksSparse() {
        val a = flowLeniaWorld(64, 64, listOf(FlowLeniaPresets.wanderers()))
        val a2 = a.copy(a.rule.withConvolution(FlowLeniaConvolution.SPARSE))
        val b = a.copy(a.rule.withConvolution(FlowLeniaConvolution.FFT))
        val m0 = a.totalMass()
        a2.steps(60); b.steps(60)
        val worst = a2.matter.indices.maxOf { abs(a2.matter[it] - b.matter[it]) }
        assertTrue(abs(b.totalMass() - m0) / m0 < 1e-10, "FFT step conserves mass")
        assertTrue(worst < 1e-6, "60 FFT steps follow 60 sparse steps: $worst")
    }

    @Test fun bodiesJoinAcrossTheSeam() {
        val w = FlowLeniaWorld(64, 48, FlowLeniaRule(FlowLeniaPresets.rings().kernels), 1)
        w.seed(0, FlowLeniaPresets.rings().h, 1.0, 24.0, radius = 6.0)
        w.seed(0, FlowLeniaPresets.rings().h, 32.0, 10.0, radius = 4.0)
        val bs = w.bodies(threshold = 0.0)
        assertEquals(2, bs.size, "two discs, one of them across the x seam: $bs")
        val seam = bs.first { it.cells > 60 }
        val dx = minOf(abs(seam.x - 1.0), 64 - abs(seam.x - 1.0))
        assertTrue(dx < 0.6, "the seam disc's centroid is near x = 1, not mid-world: $seam")
        assertEquals(w.totalMass(), bs.sumOf { it.mass }, 1e-9)
        assertEquals(0, bs[0].lineage)
    }

    @Test fun scaledRuleKeepsTheCreature() {
        // Upscale a settled 48-cell world 2x and step both: the 2x world, averaged back down, stays close.
        val small = flowLeniaWorld(48, 48, listOf(FlowLeniaPresets.wanderers()), seedRadius = 10.0).steps(150)
        val big = small.scaledCopy(2)
        assertEquals(4.0 * small.totalMass(), big.totalMass(), 1e-6)
        assertEquals(4.0 * small.rule.dt, big.rule.dt, 1e-12)
        assertEquals(2.0 * small.rule.spread, big.rule.spread, 1e-12)
        assertEquals(2 * small.rule.kernels[0].radius, big.rule.kernels[0].radius)
        small.steps(20); big.steps(20)
        var dot = 0.0; var na = 0.0; var nb = 0.0
        for (y in 0 until 48) for (x in 0 until 48) {
            val a = small.matter[y * 48 + x]
            var b = 0.0
            for (j in 0..1) for (i in 0..1) b += big.matter[(2 * y + j) * 96 + 2 * x + i]
            b /= 4.0
            dot += a * b; na += a * a; nb += b * b
        }
        val cos = dot / kotlin.math.sqrt(na * nb)
        println("FlowLenia 2x scaled copy vs original after 20 steps: cosine $cos")
        // Measured 0.95; with the spread left at one cell it was 0.86. The control for chaos (dt 0.1 % off,
        // same grid) stays at 1.000 for this species, so the gap is the rescaling, not sensitivity.
        assertTrue(cos > 0.9, "the 2x copy keeps the same creature: $cos")
        assertTrue(big.copy().trackLargest(1).steps == 1)
    }

    @Test fun neutralMutationLeavesThePhysicsUnchanged() {
        val plain = threeSpecies(FlowLeniaMixing.MEAN)
        val neutral = threeSpecies(FlowLeniaMixing.MEAN)
        neutral.mutation = FlowLeniaMutation(rate = 0.2, radius = 5.0, scale = 0.0)
        plain.steps(120); neutral.steps(120)
        val worst = plain.matter.indices.maxOf { abs(plain.matter[it] - neutral.matter[it]) }
        assertEquals(0.0, worst, 0.0, "scale 0 mutations change no weight, so MEAN matter is bit-identical")
        assertTrue(neutral.lineages.size > 10, "but they cut lineages: ${neutral.lineages.size}")
        val lm = neutral.lineageMasses()
        assertEquals(neutral.totalMass(), lm.sum(), 1e-9 * neutral.totalMass())
        for (l in neutral.lineages.drop(3)) assertTrue(l.parent in 0 until l.id, "parent before child: $l")
    }

    @Test fun mutationConservesMassUnderSampledMixing() {
        val w = threeSpecies(FlowLeniaMixing.SAMPLED)
        w.mutation = FlowLeniaMutation(rate = 0.1, radius = 6.0, scale = 0.2)
        val m0 = w.totalMass()
        w.steps(200)
        assertTrue(abs(w.totalMass() - m0) / m0 < 1e-10, "mass conserved with mutation")
        assertEquals(w.totalMass(), w.lineageMasses().sum(), 1e-9 * m0)
        assertTrue(w.lineageHistory.size == w.massHistory.size, "one lineage row per mass row")
        assertTrue(w.lineagePlot(64, 32).data.any { it > 0f })
    }

    @Test fun paintWeightsChangesOnlyTheRuleInTheDisc() {
        val w = flowLeniaWorld(64, 64, listOf(FlowLeniaPresets.rings())).steps(20)
        val before = w.matter.copyOf()
        val weightsBefore = w.weights.copyOf()
        val h = DoubleArray(w.rule.kernels.size) { 0.5 }
        val id = w.paintWeights(32.0, 32.0, 5.0, h)
        assertEquals(1, id)
        assertTrue(before.contentEquals(w.matter), "matter untouched")
        val n = 64 * 64
        var changed = 0
        for (c in 0 until n) if (w.weights[c] != weightsBefore[c]) {
            changed++
            val x = c % 64 + 0.5; val y = c / 64 + 0.5
            assertTrue(kotlin.math.hypot(x - 32.0, y - 32.0) < 5.0 && w.matter[c] > 0.0)
            assertEquals(1, w.lineage[c])
        }
        assertTrue(changed > 20, "painted $changed cells")
        // After a step trace matter reaches everywhere; a freshly seeded world's corner is truly empty.
        val fresh = flowLeniaWorld(64, 64, listOf(FlowLeniaPresets.rings()), seedRadius = 8.0)
        assertEquals(-1, fresh.paintWeights(2.0, 2.0, 1.5, h), "an empty disc paints nothing")
        assertEquals(1, fresh.lineages.size)
    }

    @Test fun genomeDrawsAndVariesValidSpecies() {
        val ops = FlowLeniaGenome()
        val rng = kotlin.random.Random(3)
        repeat(20) {
            val g = ops.random(rng)
            assertTrue(g.kernels.size in ops.kernelCounts && g.h.size == g.kernels.size)
            for (v in ops.mutations) {
                val m = v.apply(g, rng)
                assertTrue(m.kernels.size in ops.kernelCounts && m.h.size == m.kernels.size, "${v.name}")
                assertTrue(m.kernels.all { it.sigma > 0.0 })
            }
            val c = ops.crossover(g, ops.random(rng), rng)
            assertEquals(c.kernels.size, c.h.size)
        }
        assertEquals(ops.signature(FlowLeniaPresets.cell()), ops.signature(FlowLeniaPresets.cell()))
        assertTrue(FlowLeniaPresets.cell().code().startsWith("FlowLeniaSpecies(\"cell\", listOf("))
    }

    @Test fun dartGlidesAndTheCrescentKeepsItsSpeedWhenScaled() {
        val run = flowLeniaGliderRun(FlowLeniaPresets.dart(), 64, 200, 200, 6.0, 1L)
        println("FlowLenia dart: $run")
        assertTrue(run.track.speed > 0.05 && run.track.massKept > 0.95, "the dart glides: ${run.track}")
        // Control: the crescent from the same start is the still round body.
        val still = flowLeniaGliderRun(FlowLeniaPresets.crescent(), 64, 200, 200, 6.0, 1L)
        assertTrue(still.track.speed < 0.01, "crescent seed 1 sits still: ${still.track}")
        // Noise seed 2 folds into the crescent. At twice the resolution it still glides and keeps its mass;
        // it covers 1.44 times the cells per step rather than 2 (at three times, 3.0), so the speed is
        // close to scale-invariant without being exactly so.
        val small = flowLeniaWorld(64, 64, listOf(FlowLeniaPresets.crescent()), seedRadius = 6.0, randomSeed = 2L).steps(200)
        // The blocky copy needs some steps to settle before its speed means anything.
        val big = small.scaledCopy(2).steps(100)
        small.steps(100)
        val t1 = small.trackLargest(200)
        val t2 = big.trackLargest(200)
        val ratio = t2.speed / t1.speed
        println("FlowLenia crescent: $t1; at 2x: $t2, speed ratio $ratio")
        assertTrue(t1.speed > 0.01 && t1.massKept > 0.95, "the crescent glides: $t1")
        assertTrue(t2.massKept > 0.95 && ratio in 1.2..2.4, "scaled crescent glides: $t2, ratio $ratio")
    }
}
