package afield.stdlib

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * That [mixtureSource] buys what it is supposed to buy: a target with several disjoint components
 * becomes reachable.
 *
 * The claim being tested is not "the loss went down" — it does not, and that is the point. It is
 * that the *samples* land on the shape, which needs [chamferDistance] and needs it against a
 * scale. Every case here quotes two reference numbers alongside the measurement:
 *
 * - a **floor**, the chamfer of a fresh independent sampling of the same target against itself.
 *   No generator can beat this; it is the discretisation of the shape.
 * - a **ceiling**, the chamfer of an undisplaced Gaussian of roughly the right size. This is what
 *   "did nothing" scores.
 *
 * Without those two a chamfer is an uninterpretable number, and the first version of this work
 * shipped a demo on exactly that mistake — a field whose loss looked fine and whose picture was a
 * featureless haze.
 *
 * **Every `sample()` here passes a seed.** `FlowMatchField.sample` draws from its source
 * unseeded by default, which is right for a generator and wrong for a measurement: the chamfer
 * then moves by a unit or so between runs, and a figure quoted from one run — as the first
 * write-up of this work quoted 13.1 and 5.3 — is a point estimate of a random variable presented
 * as a constant. Seeded, the numbers below are reproducible and the thresholds can be tight.
 */
class MixtureSourceTest {

    /** Closed subpaths of [text], sampled in arc-length order — the components a word is made of. */
    private fun glyphComponents(text: String, total: Int = 3500): List<List<Vec3>> =
        contourComponents(layoutText(text, typeface("Helvetica", 260.0)).toFLines(), total = total, seed = 3)

    /** No generator can beat this: the target resampled against itself. */
    private fun floorFor(components: List<List<Vec3>>): Double {
        val target = components.flatten()
        val sparse = components.flatMap { it.filterIndexed { i, _ -> i % 3 == 0 } }
        return chamferDistance(sparse, target)
    }

    /** What "did nothing" scores: an undisplaced blob of roughly the target's size. */
    private fun ceilingFor(target: List<Vec3>): Double {
        var sx = 0.0
        var sy = 0.0
        for (p in target) { sx += p.x * p.x; sy += p.y * p.y }
        val spread = kotlin.math.sqrt((sx + sy) / (2 * target.size))
        val r = java.util.Random(1)
        return chamferDistance(
            (0 until 1200).map { vec(r.nextGaussian() * spread, r.nextGaussian() * spread, 0.0) },
            target,
        )
    }

    @Test
    fun splittingGlyphsIntoClosedSubpathsBeatsTreatingEachGlyphAsOneComponent() {
        // A "D" is an outer contour and a counter. Treated as one component it is an annulus
        // being asked to come from a circle -- the same topology error as the word itself, one
        // level down. Graded as a map, because that is where it shows: chamfer barely separates
        // these (5.2 against 5.9) while the landing error is a factor of two.
        val lines = layoutText("FIELD", typeface("Helvetica", 260.0)).toFLines()
        val perGlyph = lines.map { flowSamples(it, 700, seed = 3) }
        val perSubpath = contourComponents(lines, total = 3500, seed = 3)
        assertTrue(perSubpath.size > perGlyph.size, "FIELD should have more subpaths than glyphs")

        fun landing(components: List<List<Vec3>>): Double {
            val p = mixtureSource(components, seed = 5)
            val f = flowMatch(p.to, p.from, FlowMatchOptions(steps = 12000, coupling = FlowCoupling.PAIRED, seed = 4))
            val landed = f.transport(p.from, steps = 32)
            return landed.indices.map {
                kotlin.math.hypot((landed[it].x - p.to[it].x).toDouble(), (landed[it].y - p.to[it].y).toDouble())
            }.average()
        }
        val glyph = landing(perGlyph)
        val subpath = landing(perSubpath)
        println("landing error: per-glyph ${"%.1f".format(glyph)}, per-subpath ${"%.1f".format(subpath)}")
        assertTrue(subpath < glyph * 0.75, "subpaths should land closer: $subpath vs $glyph")
    }

    @Test
    fun jitteringTheSourceDestroysTheCorrespondenceItDependsOn() {
        // Counterintuitive enough to pin. Thickening each ellipse into an annulus looks like it
        // should only soften the result; it costs a factor of four in landing error, because the
        // radial offset has no consistent target and the field has to average it away.
        val components = glyphComponents("FIELD", total = 2000)
        fun landing(jitter: Double): Double {
            val p = mixtureSource(components, jitter = jitter, seed = 5)
            val f = flowMatch(p.to, p.from, FlowMatchOptions(steps = 12000, coupling = FlowCoupling.PAIRED, seed = 4))
            val landed = f.transport(p.from, steps = 32)
            return landed.indices.map {
                kotlin.math.hypot((landed[it].x - p.to[it].x).toDouble(), (landed[it].y - p.to[it].y).toDouble())
            }.average()
        }
        val clean = landing(0.0)
        val jittered = landing(0.12)
        println("landing error: no jitter ${"%.1f".format(clean)}, jitter 0.12 ${"%.1f".format(jittered)}")
        assertTrue(clean < jittered * 0.5, "a clean source should land far closer: $clean vs $jittered")
    }

    @Test
    fun aMixtureSourceMakesAFiveComponentTargetReachableWhenMoreTrainingDoesNot() {
        val components = glyphComponents("FIELD")
        val target = components.flatten()
        val floor = floorFor(components)
        val ceiling = ceilingFor(target)

        // The old way: one Gaussian, independent coupling, and a generous budget. This is the
        // configuration that produced a haze on screen, and it is here as the thing to beat.
        val single = flowMatch(target, options = FlowMatchOptions(steps = 20000, seed = 4))
        val singleChamfer = chamferDistance(single.sample(1200, steps = 32, seed = 31), target)

        // The new way: one blob per contour, paired, at a *smaller* budget.
        val pairing = mixtureSource(components, seed = 5)
        val mixture = flowMatch(
            pairing.to,
            pairing.from,
            FlowMatchOptions(steps = 12000, coupling = FlowCoupling.PAIRED, seed = 4),
        )
        val mixtureChamfer = chamferDistance(mixture.sample(1200, steps = 32, seed = 31), target)

        println(
            "FIELD: single-Gaussian ${"%.1f".format(singleChamfer)} (loss ${"%.3f".format(single.loss(200))}), " +
                "mixture ${"%.1f".format(mixtureChamfer)} (loss ${"%.3f".format(mixture.loss(200))}), " +
                "floor ${"%.1f".format(floor)}, did-nothing ${"%.1f".format(ceiling)}"
        )

        // The headline, stated as what was actually measured rather than what was hoped for. A
        // single Gaussian sits at 12.3-13.1 on this target at every budget tried; per-subpath
        // ellipses, phase-aligned, with no source jitter reach 5.2. That is *not* the floor (1.8) — the
        // remaining gap is the next piece of work, and this assertion is deliberately set where
        // it will fail if that gap is ever closed, so the number in the docs has to be revisited
        // rather than quietly drifting.
        assertTrue(
            mixtureChamfer < singleChamfer * 0.55,
            "mixture chamfer $mixtureChamfer should be well under the single-Gaussian $singleChamfer"
        )
        assertTrue(
            mixtureChamfer > floor,
            "mixture chamfer $mixtureChamfer is now at or below the floor $floor -- " +
                "better than expected; re-measure and update the quoted numbers"
        )
    }

    @Test
    fun theLossDoesNotSeeTheDifferenceThatTheSamplesDo() {
        // The trap this whole exercise is built around, pinned as a test so it cannot be
        // rediscovered. Both configurations sit near the same loss; only chamfer separates them.
        val components = glyphComponents("FIELD", total = 2000)
        val target = components.flatten()

        val single = flowMatch(target, options = FlowMatchOptions(steps = 6000, seed = 4))
        val pairing = mixtureSource(components, seed = 5)
        val mixture = flowMatch(
            pairing.to,
            pairing.from,
            FlowMatchOptions(steps = 6000, coupling = FlowCoupling.PAIRED, seed = 4),
        )

        val singleChamfer = chamferDistance(single.sample(800, steps = 32, seed = 31), target)
        val mixtureChamfer = chamferDistance(mixture.sample(800, steps = 32, seed = 31), target)
        println(
            "loss ${"%.3f".format(single.loss(200))} vs ${"%.3f".format(mixture.loss(200))}; " +
                "chamfer ${"%.1f".format(singleChamfer)} vs ${"%.1f".format(mixtureChamfer)}"
        )

        assertTrue(
            mixtureChamfer < singleChamfer * 0.7,
            "the samples should separate these: $singleChamfer vs $mixtureChamfer"
        )
    }

    @Test
    fun phaseAlignmentIsWhatMakesTheConstructionWork() {
        // The single most load-bearing line in mixtureSource, pinned. A contour's arc-length
        // origin is unrelated to angle zero on its fitted ellipse, so the identity pairing
        // carries an arbitrary rotation; measured, that is the difference between a loss of
        // 0.0047 and 0.047.
        val components = glyphComponents("FIELD", total = 2000)
        fun lossFor(align: Boolean): Double {
            val p = mixtureSource(components, alignPhase = align, seed = 5)
            return flowMatch(
                p.to, p.from,
                FlowMatchOptions(steps = 6000, coupling = FlowCoupling.PAIRED, seed = 4),
            ).loss(200)
        }
        val aligned = lossFor(true)
        val unaligned = lossFor(false)
        println("phase alignment: loss $aligned aligned vs $unaligned unaligned")
        assertTrue(aligned < unaligned * 0.5, "alignment should more than halve the loss: $aligned vs $unaligned")
    }

    @Test
    fun theSourceIsInPointForPointCorrespondenceWithTheTarget() {
        // The contract callers depend on: `to` is the components in the order given, and `from`
        // is the same length, so FlowCoupling.PAIRED means what it says.
        val components = glyphComponents("FI", total = 400)
        val pairing = mixtureSource(components, seed = 2)
        assertEquals(components.sumOf { it.size }, pairing.size)
        assertEquals(components.flatten().map { it.x }, pairing.to.map { it.x })
    }

    @Test
    fun aDegenerateComponentIsStillSamplable() {
        // A perfectly straight contour has zero variance across its width, so the covariance is
        // singular and an unguarded Cholesky returns NaN — which propagates into the training data
        // and produces a field of NaNs rather than an error anyone can read.
        val line = (0 until 200).map { vec(it * 0.5, 3.0, 0.0) }
        val pairing = mixtureSource(listOf(line), seed = 1)
        assertTrue(pairing.from.all { it.x.isFinite() && it.y.isFinite() }, "degenerate component produced NaN")
    }

    @Test
    fun aSingleComponentIsNoWorseThanTheGaussianItReplaces() {
        // The mixture must not cost anything in the case it is not needed for, or it cannot be the
        // default. One ring, one blob.
        val ring = (0 until 700).map {
            val a = it * 2.0 * Math.PI / 700.0
            vec(140.0 * kotlin.math.cos(a), 140.0 * kotlin.math.sin(a), 0.0)
        }
        val pairing = mixtureSource(listOf(ring), seed = 5)
        val field = flowMatch(
            pairing.to,
            pairing.from,
            FlowMatchOptions(steps = 6000, coupling = FlowCoupling.PAIRED, seed = 4),
        )
        val radii = field.sample(600, steps = 32, seed = 31).map { kotlin.math.hypot(it.x.toDouble(), it.y.toDouble()) }
        val mean = radii.average()
        println("ring via mixture: mean radius ${"%.1f".format(mean)} of 140")
        assertTrue(abs(mean - 140.0) < 14.0, "ring landed at $mean")
    }
}
