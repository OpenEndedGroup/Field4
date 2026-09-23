package afield.stdlib

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The interval walker's contract, tested the way it is stated: every value `Fn.eval` returns on an
 * interval lies inside the enclosure — zero violations over a generated population — with the
 * population itself checked for covering every op and every [FnExpr] kind, a counterfactual that
 * measures what a sampled envelope misses, and a ratchet on how loose a one-shot bound is.
 */
class FnIntervalTest {

    private val unbounded = Functions.Domain.ALL
    private val win = Functions.Domain(-2.0, 2.0)

    // ---- a generator of formula-only graphs --------------------------------------------------

    /**
     * A random graph built only from ops with an [FnFormulas] entry. Parameters are sometimes
     * numbers and sometimes signals, so both point and non-point ranges reach every rule; `pow`,
     * `log` and `divide` are deliberately allowed to see non-positive or zero-crossing inputs, so the
     * NaN and infinity cases are exercised rather than avoided.
     */
    private fun gen(r: Random, depth: Int, valueOnly: Boolean = true): Functions.Fn = with(Functions) {
        fun num(lo: Double, hi: Double) = lo + (hi - lo) * r.nextDouble()
        fun sub() = gen(r, depth - 1, valueOnly)
        fun small() = if (depth <= 1 || r.nextInt(3) == 0) c(num(-2.0, 2.0), unbounded) else sub()
        if (depth <= 0) {
            return when (r.nextInt(4)) {
                0 -> c(num(-2.0, 2.0), unbounded)
                1 -> identity(unbounded)
                2 -> sin(num(0.1, 3.0), amplitude = num(0.2, 2.0), offset = num(-1.0, 1.0))
                else -> cos(num(0.1, 3.0), amplitude = num(0.2, 2.0), offset = num(-1.0, 1.0))
            }
        }
        when (r.nextInt(if (valueOnly) 35 else 33)) {
            0 -> sub() + sub()
            1 -> sub() - sub()
            2 -> sub() * sub()
            3 -> sub() / sub()
            4 -> sin(frequency = c(num(0.1, 2.0), unbounded), phase = small(), amplitude = small(), offset = c(num(-1.0, 1.0), unbounded))
            5 -> cos(frequency = small(), phase = c(num(-3.0, 3.0), unbounded), amplitude = c(num(0.2, 2.0), unbounded), offset = small())
            6 -> sub().scaleY(num(-2.0, 2.0))
            7 -> sub().offsetY(num(-2.0, 2.0))
            8 -> if (r.nextBoolean()) sub().pow(listOf(2.0, 3.0, -1.0, -2.0, 0.5, 1.5, 0.0)[r.nextInt(7)]) else sub().pow(small())
            9 -> sub().log(if (r.nextBoolean()) 2.0 else num(0.5, 4.0))
            10 -> sub().exp()
            11 -> sub().flip()
            12 -> sub().abs()
            13 -> sub().floor()
            14 -> sub().atan()
            15 -> sub().atan2(sub())
            16 -> sub().hypot(sub())
            17 -> { val a = num(-1.5, 1.0); sub().clamp(a, a + num(0.0, 2.0)) }
            18 -> sub().softClip(if (r.nextInt(5) == 0) c(0.0, unbounded) else small())
            19 -> sub().amplitudeMod(sub(), num(-1.0, 1.0), num(-1.0, 1.0))
            20 -> sub().ringMod(sub(), num(-1.0, 1.0))
            21 -> sub().shift(small())
            22 -> sub().warp(sub(), num(-0.5, 0.5))
            23 -> sub().scaleX(if (r.nextInt(5) == 0) 0.0 else num(-2.0, 2.0), num(-1.0, 1.0))
            24 -> gaussian(small(), if (r.nextInt(6) == 0) c(0.0, unbounded) else c(num(0.05, 1.5), unbounded), small(), unbounded)
            25 -> ramp(num(-2.0, 2.0), num(-2.0, 2.0), win)
            26 -> { val a = num(-2.0, 2.0); remap(a, if (r.nextInt(6) == 0) a else num(-2.0, 2.0), unbounded) }
            27 -> { val a = num(-2.0, 2.0); smoothStep(a, if (r.nextInt(5) == 0) a else num(-2.0, 2.0), unbounded) }
            28 -> sub() * hannWindow(win)
            29 -> sub() * hammingWindow(win)
            30 -> sub().withName("n")
            31 -> sub().withDomain(win)
            // The two value-only ops (FnFormulas.valueOnlyOps): a step at a signal or numeric edge,
            // and the sign of a subgraph that genuinely crosses zero.
            32 -> if (!valueOnly) identity(unbounded) * sub() // the pre-2026-09-23 draw, unchanged
                else step(if (r.nextBoolean()) c(num(-2.0, 2.0), unbounded) else small(), unbounded)
            33 -> sub().sign()
            else -> identity(unbounded) * sub()
        }
    }

    private fun population(count: Int = 600, seed: Int = 20260923, valueOnly: Boolean = true): List<Functions.Fn> {
        val r = Random(seed)
        return List(count) { gen(r, 1 + r.nextInt(4), valueOnly) }
    }

    private fun opsOf(fn: Functions.Fn, into: MutableSet<String> = mutableSetOf()): MutableSet<String> {
        val node = fn.graphNode()
        if (!node.numbers.containsKey("value")) into += node.op.lowercase()
        for (edge in node.children) opsOf(edge.fn, into)
        return into
    }

    private fun kindsOf(e: FnExpr, into: MutableSet<String>) {
        into += e::class.simpleName!!
        when (e) {
            is FnExpr.X, is FnExpr.Lit, is FnExpr.Child, is FnExpr.Alpha -> Unit
            is FnExpr.Add -> { kindsOf(e.a, into); kindsOf(e.b, into) }
            is FnExpr.Sub -> { kindsOf(e.a, into); kindsOf(e.b, into) }
            is FnExpr.Mul -> { kindsOf(e.a, into); kindsOf(e.b, into) }
            is FnExpr.Div -> { kindsOf(e.a, into); kindsOf(e.b, into) }
            is FnExpr.Neg -> kindsOf(e.a, into)
            is FnExpr.Abs -> kindsOf(e.a, into)
            is FnExpr.Floor -> kindsOf(e.a, into)
            is FnExpr.Sin -> kindsOf(e.a, into)
            is FnExpr.Cos -> kindsOf(e.a, into)
            is FnExpr.Exp -> kindsOf(e.a, into)
            is FnExpr.Ln -> kindsOf(e.a, into)
            is FnExpr.Tanh -> kindsOf(e.a, into)
            is FnExpr.Atan -> kindsOf(e.a, into)
            is FnExpr.Atan2 -> { kindsOf(e.y, into); kindsOf(e.x, into) }
            is FnExpr.Hypot -> { kindsOf(e.a, into); kindsOf(e.b, into) }
            is FnExpr.Pow -> { kindsOf(e.a, into); kindsOf(e.b, into) }
            is FnExpr.Min -> { kindsOf(e.a, into); kindsOf(e.b, into) }
            is FnExpr.Max -> { kindsOf(e.a, into); kindsOf(e.b, into) }
            is FnExpr.Clamp01 -> kindsOf(e.a, into)
            is FnExpr.CoerceIn -> { kindsOf(e.a, into); kindsOf(e.lo, into); kindsOf(e.hi, into) }
            is FnExpr.IfZero -> { kindsOf(e.test, into); kindsOf(e.then, into); kindsOf(e.otherwise, into) }
            is FnExpr.IfEq -> { kindsOf(e.a, into); kindsOf(e.b, into); kindsOf(e.then, into); kindsOf(e.otherwise, into) }
            is FnExpr.IfLt -> { kindsOf(e.a, into); kindsOf(e.b, into); kindsOf(e.then, into); kindsOf(e.otherwise, into) }
            is FnExpr.AtX -> { kindsOf(e.newX, into); kindsOf(e.body, into) }
        }
    }

    // ---- the premise: the population reaches everything it claims to test ----------------------

    @Test
    fun theGeneratorReachesEveryFormulaOpAndEveryExprKind() {
        val seen = mutableSetOf<String>()
        for (fn in population()) opsOf(fn, seen)
        val missingOps = (FnFormulas.ops + FnFormulas.valueOnlyOps).filterNot { it in seen }
        assertTrue(missingOps.isEmpty(), "formula ops the generator never produced: $missingOps")
        assertTrue(seen.all { FnFormulas.valueOf(it) != null }, "the generator produced a formula-less op: ${seen.filter { FnFormulas.valueOf(it) == null }}")

        val kinds = mutableSetOf<String>()
        for (op in seen) kindsOf(FnFormulas.valueOf(op)!!, kinds)
        val allKinds = FnExpr::class.sealedSubclasses.mapNotNull { it.simpleName }.toSet()
        assertTrue(allKinds.size >= 28, "sealedSubclasses came back with ${allKinds.size} kinds; the premise check would be vacuous")
        assertEquals(allKinds, kinds, "FnExpr kinds the generated population never reaches")

        // The coverage the interval walk has, derived the way FnOpCoverageTest derives its own.
        val canonical = OpRegistry.knownOps.map { OpRegistry.canonical(it)?.lowercase() ?: it.lowercase() }.toSortedSet()
        val enclosable = canonical.filter { FnFormulas.valueOf(it) != null || it == "const" || it == "param" }
        println("FN INTERVAL COVERAGE: ${enclosable.size} of ${canonical.size} canonical ops enclosable " +
            "(${FnFormulas.ops.size} formula ops + ${FnFormulas.valueOnlyOps.size} value-only + const/param)")
    }

    @Test
    fun everyExprKindHasADeclaredIntervalRuleAndNoRuleOutlivesItsKind() {
        val allKinds = FnExpr::class.sealedSubclasses.mapNotNull { it.simpleName }.toSet()
        assertTrue(allKinds.size >= 28, "sealedSubclasses came back with ${allKinds.size} kinds")
        val rules = FnIntervalRules.rules.keys
        assertEquals(emptySet(), allKinds - rules, "FnExpr kinds with no declared interval rule")
        assertEquals(emptySet(), rules - allKinds, "interval rules for FnExpr kinds that no longer exist")
        assertTrue(FnIntervalRules.rules.values.all { it.length > 15 }, "an interval rule too thin to be a rule")
    }

    /**
     * The value-only formulas are pinned the way `AutoDiffFnBridgeTest` pins the rest: raw bits,
     * against `Fn.eval`, at the inputs where a careless transcription differs — the edge itself,
     * signed zeros, NaN (`x >= NaN` is false, so a step at a NaN edge is 0, not 1) — and that the
     * gradient still refuses them.
     */
    @Test
    fun valueOnlyFormulasAreBitIdenticalAndStillRefusedByTheGradient() = with(Functions) {
        val xs = listOf(-2.0, -0.5, -0.0, 0.0, 0.25, 0.5, 1.0, 3.0, Double.NaN, Double.POSITIVE_INFINITY)
        val graphs = listOf(
            step(0.5, unbounded), step(0.0, unbounded), step(Double.NaN, unbounded),
            step(identity(unbounded) * c(0.5, unbounded), unbounded),
            identity(unbounded).sign(), (identity(unbounded) - c(0.5, unbounded)).sign(),
            (identity(unbounded) * c(-1.0, unbounded)).sign()
        )
        var compared = 0
        for (fn in graphs) for (x in xs) {
            assertEquals(fn.eval(x).toRawBits(), FnAutoDiff.evalByFormula(fn, x).toRawBits(), "${opsOf(fn)} at $x")
            compared++
        }
        assertEquals(70, compared)
        assertEquals(setOf("sign", "step"), FnFormulas.valueOnlyOps)
        assertTrue(FnFormulas.valueOnlyOps.none { it in FnAutoDiff.differentiableOps })
        assertTrue(FnFormulas.valueOnlyOps.all { FnNonDifferentiable.statusOf(it) != null })
        assertFalse(FnAutoDiff.supports(step(0.5, unbounded)))
        assertTrue(FnIntervals.supports(step(0.5, unbounded)))
    }

    // ---- containment ----------------------------------------------------------------------------

    /**
     * **The contract.** For 600 generated graphs, several intervals each, from 10 µ-wide to 6 wide:
     * every sampled `Fn.eval` lies in the enclosure, NaN only where NaN was declared possible. Zero
     * violations, over a counted population.
     */
    @Test
    fun everySampledValueLiesInsideItsEnclosure() {
        val r = Random(7)
        var graphs = 0
        var intervals = 0
        var samples = 0
        var refused = 0
        var violations = 0
        var firstViolation: String? = null
        var nanSamples = 0
        for ((index, fn) in population().withIndex()) {
            graphs++
            repeat(4) { k ->
                val width = listOf(6.0, 1.0, 1e-2, 1e-5)[k]
                val a = -3.0 + (6.0 - width) * r.nextDouble()
                val b = a + width
                val e = fn.enclose(a, b)
                intervals++
                if (!e.certified) { refused++; return@repeat }
                for (i in 0..120) {
                    val x = when {
                        i == 0 -> a
                        i == 1 -> b
                        i < 60 -> a + (b - a) * (i - 2) / 57.0
                        else -> a + (b - a) * r.nextDouble()
                    }.coerceIn(a, b)
                    val v = runCatching { fn.eval(x) }.getOrNull() ?: continue
                    samples++
                    if (v.isNaN()) nanSamples++
                    if (v !in e) {
                        violations++
                        if (firstViolation == null) firstViolation = "graph #$index ${opsOf(fn)} on [$a, $b]: f($x) = $v outside $e"
                    }
                }
            }
        }
        println("FN INTERVAL CONTAINMENT: $graphs graphs, $intervals intervals, $samples samples ($nanSamples NaN), $refused refused, $violations violations")
        assertEquals(0, violations, "enclosure violated: $firstViolation")
        assertEquals(0, refused, "formula-only graphs should never be refused")
        assertTrue(samples > 250_000, "the containment population shrank to $samples samples")
        assertTrue(nanSamples > 0, "no sample was NaN, so the NaN rules went untested")
    }

    /**
     * The same contract an hour away from the origin, where the viewer actually works: `sin`'s
     * crest test and every phase are computed at x in the thousands, not near 0.
     */
    @Test
    fun containmentHoldsFarFromTheOrigin() {
        val r = Random(99)
        var samples = 0
        var violations = 0
        var first: String? = null
        for ((index, fn) in population(300, 17).withIndex()) {
            for (width in listOf(40.0, 0.5, 1e-3)) {
                val a = 500.0 + 3000.0 * r.nextDouble()
                val b = a + width
                val e = fn.enclose(a, b)
                assertTrue(e.certified, "graph #$index refused: ${e.refusal}")
                for (i in 0..80) {
                    val x = (a + (b - a) * i / 80.0).coerceIn(a, b)
                    val v = runCatching { fn.eval(x) }.getOrNull() ?: continue
                    samples++
                    if (v !in e) { violations++; if (first == null) first = "graph #$index ${opsOf(fn)}: f($x) = $v outside $e" }
                }
            }
        }
        println("FN INTERVAL FAR CONTAINMENT: $samples samples on x in [500, 3540], $violations violations")
        assertEquals(0, violations, first)
        assertTrue(samples > 70_000, "far population shrank to $samples")
    }

    /** The per-bin envelope obeys the same contract, bin by bin, on the same population. */
    @Test
    fun everySampledValueLiesInsideItsCertifiedEnvelopeBin() {
        var violations = 0
        var checked = 0
        var first: String? = null
        for ((index, fn) in population(200, 11).withIndex()) {
            val env = fn.certifiedEnvelope(-2.0, 2.0, 16, tolerance = 1e-2, maxDepth = 3)
            assertTrue(env.certified, "graph #$index refused: ${env.refusal}")
            for (bin in 0 until env.bins) {
                val lo = env.edge(bin)
                val hi = env.edge(bin + 1)
                for (i in 0..40) {
                    val x = lo + (hi - lo) * i / 40.0
                    val v = runCatching { fn.eval(x) }.getOrNull() ?: continue
                    if (v.isNaN()) continue
                    checked++
                    val inside = !env.low[bin].isNaN() && v >= env.low[bin] && v <= env.high[bin]
                    if (!inside) {
                        violations++
                        if (first == null) first = "graph #$index bin $bin: f($x) = $v outside [${env.low[bin]}, ${env.high[bin]}]"
                    }
                }
            }
        }
        println("FN INTERVAL ENVELOPE CONTAINMENT: $checked samples, $violations violations")
        assertEquals(0, violations, first)
        assertTrue(checked > 100_000, "envelope population shrank to $checked samples")
    }

    // ---- refusal ---------------------------------------------------------------------------------

    @Test
    fun aLambdaIsRefusedByNameAndNeverBounded() = with(Functions) {
        // The shape of every channel in the function-conversation worked example.
        val channel = fn(0.0..3600.0, "x") { seconds -> 0.45 + 0.2 * kotlin.math.sin(seconds) }
        val e = channel.enclose(0.0, 3600.0)
        assertFalse(e.certified)
        assertTrue(e.lo.isNaN() && e.hi.isNaN(), "a refused enclosure must not carry a bound: $e")
        assertEquals(listOf("lambda"), e.unsupportedOps)
        assertTrue(e.refusal!!.contains("'lambda'"), e.refusal)
        assertFalse(0.45 in e)

        // Mixed in with formula ops, the refusal still names only what is missing.
        val mixed = sin(0.1) + channel.abs()
        val missing = FnIntervals.unsupportedOps(mixed)
        assertTrue("lambda" in missing && "sin" !in missing, "unsupported: $missing")
        assertFalse(mixed.certifiedRange(0.0, 10.0).certified)
        assertEquals(FnVerdictKind.UNDECIDED, mixed.certifiedNeverExceeds(10.0, 0.0, 10.0).kind)
    }

    @Test
    fun anUnboundedShapeDomainIsRefusedRatherThanGuessed() = with(Functions) {
        val e = ramp(0.0, 1.0, unbounded).enclose(0.0, 1.0)
        assertFalse(e.certified)
        assertTrue(e.refusal!!.contains("not finite"), e.refusal)
    }

    // ---- the counterfactual -----------------------------------------------------------------------

    /**
     * A 10 ms spike in an hour: the viewer's 1000-bin sampled envelope misses it by at least 0.45 of
     * its 0.5 height, and the certified envelope's bound for the same bin covers it.
     */
    @Test
    fun aNarrowSpikeTheSampledEnvelopeMissesIsCaughtByTheCertifiedOne() = with(Functions) {
        val spikeAt = 1234.57 // exactly between two 0.02 s samples
        val f = sin(1.0 / 40.0, amplitude = 0.2, offset = 0.45) + gaussian(spikeAt, 0.002, 0.5, unbounded)
        val viewer = SignalViewer(listOf(SignalChannel("x", f::eval)), 0.0, 3600.0, sampleStep = 0.02)
        val sampled = viewer.window(0.0, 3600.0, 1000).lanes[0].current
        viewer.close()
        val certified = f.certifiedEnvelope(0.0, 3600.0, 1000)
        assertTrue(certified.certified, certified.refusal)
        val bin = (spikeAt / 3.6).toInt()
        val truePeak = f.eval(spikeAt)
        val sampledMiss = truePeak - sampled.high[bin]
        val certifiedMargin = certified.high[bin] - truePeak
        println("FN INTERVAL SPIKE: true peak $truePeak, sampled bin max ${sampled.high[bin]} (miss $sampledMiss), certified bin max ${certified.high[bin]}, ${certified.enclosures} enclosures, ${certified.unconverged} unconverged")
        assertTrue(sampledMiss >= 0.45, "the sampled envelope was expected to miss the spike, missed by only $sampledMiss")
        assertTrue(certifiedMargin >= 0.0, "the certified envelope must cover the peak: ${certified.high[bin]} < $truePeak")
        assertTrue(certifiedMargin < 0.01, "the certified bound should be tight at the spike, was $certifiedMargin above it")

        val range = f.certifiedRange(0.0, 3600.0, tolerance = 1e-6)
        assertTrue(range.certified, "$range")
        assertTrue(kotlin.math.abs(range.max!!.witness - spikeAt) < 1e-3, "max witness ${range.max.witness}")
        assertTrue(range.max.value >= truePeak - 1e-6 && range.max.bound - range.max.value <= 1e-6, "$range")
        assertEquals(FnVerdictKind.REFUTED, f.certifiedNeverExceeds(0.7, 0.0, 3600.0).kind)
        assertEquals(FnVerdictKind.PROVEN, f.certifiedNeverExceeds(truePeak + 1e-3, 0.0, 3600.0).kind)
        assertEquals(FnVerdictKind.PROVEN, f.certifiedReaches(0.75, 0.0, 3600.0).kind)
        assertEquals(FnVerdictKind.REFUTED, f.certifiedReaches(truePeak + 1e-3, 0.0, 3600.0).kind)
    }

    /**
     * The opt-in viewer band: in both of the viewer's modes, every sampled bin lies inside its
     * certified bin, the band catches the spike the samples miss, and an opaque lane is refused with
     * its reason rather than given a band.
     */
    @Test
    fun theViewerCarriesACertifiedBandThatContainsItsOwnSamples() = with(Functions) {
        val spikeAt = 1234.57
        val f = sin(1.0 / 40.0, amplitude = 0.2, offset = 0.45) + gaussian(spikeAt, 0.002, 0.5, unbounded)
        val opaque = fn(0.0..3600.0) { 0.5 }
        val viewer = SignalViewer(
            listOf(certifiedSignalChannel("x", f), SignalChannel("plain", f::eval), SignalChannel("y", opaque::eval, certified = opaque)),
            0.0, 3600.0, sampleStep = 0.02
        )
        for ((from, to) in listOf(0.0 to 3600.0, 1234.0 to 1235.0)) {
            val w = viewer.window(from, to, 1000)
            val lane = w.lanes[0]
            val band = lane.certified!!
            assertEquals(lane.current.low.size - if (w.mode == "waveform") 1 else 0, band.low.size, "mode ${w.mode}")
            for (i in band.low.indices) {
                // In waveform mode bin i spans samples i and i+1.
                val samples = if (w.mode == "waveform") listOf(lane.current.low[i], lane.current.low[i + 1])
                    else listOf(lane.current.low[i], lane.current.high[i])
                for (v in samples) assertTrue(v >= band.low[i] && v <= band.high[i], "${w.mode} bin $i: $v outside [${band.low[i]}, ${band.high[i]}]")
            }
            assertTrue(band.high.max() >= f.eval(spikeAt), "${w.mode}: the band misses the spike")
            assertEquals(null, w.lanes[1].certified)
            assertEquals(null, w.lanes[2].certified)
            assertTrue(w.lanes[2].certifiedRefusal!!.contains("'lambda'"), w.lanes[2].certifiedRefusal)
        }
        viewer.close()
    }

    /**
     * What the band costs, against what the viewer already spends sampling. Reported, not a
     * contract — except the one ceiling that is: the viewer's route times a request out at 12 s, so a
     * certified hour-long window must come in well under that on this machine class.
     */
    @Test
    fun theCertifiedBandCostsAReportedMultipleOfSampling() = with(Functions) {
        val f = sin(1.0 / 40.0, amplitude = 0.2, offset = 0.45) + gaussian(1234.57, 0.002, 0.5, unbounded)
        val sampledViewer = SignalViewer(listOf(SignalChannel("x", f::eval)), 0.0, 3600.0, sampleStep = 0.02)
        val certifiedViewer = SignalViewer(listOf(certifiedSignalChannel("x", f)), 0.0, 3600.0, sampleStep = 0.02)
        repeat(3) { sampledViewer.window(0.0, 3600.0, 1000); certifiedViewer.window(0.0, 3600.0, 1000) } // warm
        fun best(block: () -> Unit): Double = (1..5).minOf { val t = System.nanoTime(); block(); (System.nanoTime() - t) / 1e6 }
        val sampledMs = best { sampledViewer.window(0.0, 3600.0, 1000) }
        val certifiedMs = best { certifiedViewer.window(0.0, 3600.0, 1000) }
        sampledViewer.close(); certifiedViewer.close()
        println("FN INTERVAL TIMING: 1000-bin hour window, 180000 samples: sampled ${"%.1f".format(sampledMs)} ms, " +
            "sampled + certified ${"%.1f".format(certifiedMs)} ms (${"%.2f".format(certifiedMs / sampledMs)}x)")
        assertTrue(certifiedMs < 4000.0, "a certified window took $certifiedMs ms; the viewer route times out at 12 s")
    }

    // ---- exactness: a held plateau certifies as the point itself ------------------------------------

    @Test
    fun anExactHoldCertifiesAsExactlyOne() = with(Functions) {
        // One event of the workbench's shape, built from formula ops: rise 185..285, hold to 315,
        // fall to 415, crossfaded over a moving background.
        val background = sin(1.0 / 40.0, amplitude = 0.2, offset = 0.45)
        val rise = smoothStep(185.0, 285.0, unbounded)
        val fall = c(1.0, unbounded) - smoothStep(315.0, 415.0, unbounded)
        val envelope = rise * fall
        // Written as `1 - (1 - background)(1 - envelope)` rather than the equivalent crossfade
        // `background(1 - envelope) + envelope`: both hold exactly 1, but only this form isolates the
        // bound, so `<= 1` can be proven right up to the plateau edge. See `aCrossfadeThatTouchesItsBound…`.
        val one = c(1.0, unbounded)
        val channel = one - (one - background) * (one - envelope)
        assertEquals(FnVerdictKind.PROVEN, channel.certifiedEquals(1.0, 285.0, 315.0).kind)
        val early = channel.certifiedEquals(1.0, 284.5, 315.0)
        assertEquals(FnVerdictKind.REFUTED, early.kind)
        assertTrue(early.witness!! < 285.0)
        // `<= 1` itself is attained, tangentially, at both plateau edges: see the next test.
        assertEquals(FnVerdictKind.PROVEN, channel.certifiedNeverExceeds(1.0, 0.0, 3600.0, slack = 1e-12).kind)
        assertEquals(FnVerdictKind.PROVEN, channel.certifiedNeverBelow(0.0, 0.0, 3600.0).kind)
    }

    /**
     * The limit of the method, pinned: the crossfade `b(1 - e) + e` equals 1 on the plateau and is
     * below 1 everywhere else, but interval arithmetic treats the two `e`s as independent, so every
     * piece touching the plateau edge bounds above 1 by about `b·Δe`. `<= 1` stays UNDECIDED however
     * far it bisects; `<= 1 + 1e-9` is proven, because the overshoot shrinks with the piece.
     */
    @Test
    fun aCrossfadeThatTouchesItsBoundCannotBeProvenAtTheBoundButCanJustAboveIt() = with(Functions) {
        val background = sin(1.0 / 40.0, amplitude = 0.2, offset = 0.45)
        val envelope = smoothStep(185.0, 285.0, unbounded) * (c(1.0, unbounded) - smoothStep(315.0, 415.0, unbounded))
        val crossfade = background * (c(1.0, unbounded) - envelope) + envelope
        assertEquals(FnVerdictKind.PROVEN, crossfade.certifiedEquals(1.0, 285.0, 315.0).kind)
        val atBound = crossfade.certifiedNeverExceeds(1.0, 0.0, 600.0, maxEnclosures = 20_000)
        assertEquals(FnVerdictKind.UNDECIDED, atBound.kind, "$atBound")
        val withSlack = crossfade.certifiedNeverExceeds(1.0, 0.0, 600.0, slack = 1e-9)
        assertEquals(FnVerdictKind.PROVEN, withSlack.kind, "$withSlack")
        println("FN INTERVAL CROSSFADE: <= 1 + 1e-9 proven in ${withSlack.enclosures} enclosures")
    }

    // ---- individual rules ------------------------------------------------------------------------

    @Test
    fun trigFindsInteriorExtremaExactlyAndStaysTightOnANarrowInterval() {
        val narrow = FnRangeMath.sin(FnRange(0.1, 0.2))
        assertTrue(narrow.hi < 0.2 && narrow.lo > 0.09, "narrow sin should not cop out to [-1, 1]: $narrow")
        val crest = FnRangeMath.sin(FnRange(1.5, 1.6))
        assertTrue(crest.hi >= 1.0 && crest.lo > 0.99, "$crest")
        val trough = FnRangeMath.cos(FnRange(3.0, 3.3))
        assertTrue(trough.lo <= -1.0 && trough.hi < -0.98, "$trough")
        assertEquals(FnRange(0.0, 0.0), FnRangeMath.floor(FnRange(0.0, 0.999)))
        assertEquals(FnRange(0.0, 4.0), FnRangeMath.square(FnRange(-1.0, 2.0)))
        assertEquals(FnRange(-2.0, 4.0), FnRangeMath.mul(FnRange(-1.0, 2.0), FnRange(-1.0, 2.0)))
        assertTrue(FnRangeMath.ln(FnRange(-1.0, 1.0)).maybeNaN)
        assertTrue(FnRangeMath.div(FnRange(1.0, 2.0), FnRange(-1.0, 1.0)).lo == Double.NEGATIVE_INFINITY)
    }

    // ---- tightness ratchet ------------------------------------------------------------------------

    /**
     * How loose is one enclosure over a whole interval, and how loose after branch-and-bound?
     * Measured over the generated population on [-2, 2] against a 4001-point sampled range. The
     * one-shot number is a property of interval arithmetic (the dependency problem), not a bug; the
     * point of the ratchet is that nobody can quietly make it worse.
     */
    @Test
    fun tightnessIsMeasuredAndRatcheted() {
        val oneShot = ArrayList<Double>()
        val binned = ArrayList<Double>()
        // The population the ratchets were measured on (2026-09-23): the generator *without* the two
        // value-only ops, whose steps make the one-shot numbers a different population, not a worse walker.
        for (fn in population(300, 5, valueOnly = false)) {
            val xs = DoubleArray(4001) { -2.0 + 4.0 * it / 4000.0 }
            val vs = xs.map { runCatching { fn.eval(it) }.getOrDefault(Double.NaN) }
            if (vs.any { !it.isFinite() }) continue
            val observed = vs.max() - vs.min()
            if (observed < 1e-6) continue
            val e = fn.enclose(-2.0, 2.0)
            if (!e.width.isFinite()) { oneShot += Double.POSITIVE_INFINITY; continue }
            oneShot += e.width / observed
            val env = fn.certifiedEnvelope(-2.0, 2.0, 64, tolerance = 1e-3 * observed, maxDepth = 6)
            val lo = env.low.min()
            val hi = env.high.max()
            binned += (hi - lo) / observed
        }
        oneShot.sort(); binned.sort()
        fun q(list: List<Double>, p: Double) = list[((list.size - 1) * p).toInt()]
        val medianOneShot = q(oneShot, 0.5)
        val p75OneShot = q(oneShot, 0.75)
        val medianBinned = q(binned, 0.5)
        val p90Binned = q(binned, 0.9)
        println("FN INTERVAL TIGHTNESS: ${oneShot.size} graphs; one-shot width/observed p50 $medianOneShot p75 $p75OneShot " +
            "p90 ${q(oneShot, 0.9)}, infinite ${oneShot.count { it.isInfinite() }}; 64-bin refined p50 $medianBinned " +
            "p75 ${q(binned, 0.75)} p90 $p90Binned max ${binned.last()}")
        assertTrue(oneShot.size >= 150, "tightness population shrank to ${oneShot.size}")
        assertTrue(medianBinned >= 1.0, "a certified envelope narrower than the sampled range is impossible: $medianBinned")
        assertTrue(p90Binned <= RATCHET_BINNED_P90, "refined envelopes got looser: p90 $p90Binned > $RATCHET_BINNED_P90")
        assertTrue(p75OneShot <= RATCHET_ONE_SHOT_P75, "one-shot enclosures got looser: p75 $p75OneShot > $RATCHET_ONE_SHOT_P75")
    }

    /**
     * The containment check has teeth: the obvious wrong bound — the range of the two endpoint values,
     * which is what "sample the ends" would give — is caught on the same population, many times.
     */
    @Test
    fun theContainmentCheckCatchesAnEndpointOnlyBound() {
        var violations = 0
        for (fn in population(200, 3)) {
            val fa = runCatching { fn.eval(-1.0) }.getOrDefault(Double.NaN)
            val fb = runCatching { fn.eval(1.0) }.getOrDefault(Double.NaN)
            if (!fa.isFinite() || !fb.isFinite()) continue
            val fake = FnRange(minOf(fa, fb), maxOf(fa, fb))
            if ((0..200).any { i -> runCatching { fn.eval(-1.0 + i / 100.0) }.getOrDefault(fa) !in fake }) violations++
        }
        println("FN INTERVAL TEETH: the endpoint-only bound is violated on $violations of 200 graphs")
        assertTrue(violations >= 50, "the endpoint-only bound was violated on only $violations graphs")
    }

    companion object {
        // Measured 2026-09-23 on this population: one-shot p75 1.1286, 64-bin refined p90 1.00133.
        // Set just above; a change that loosens the walker should fail here, and one that tightens
        // it should lower these.
        const val RATCHET_ONE_SHOT_P75 = 1.15
        const val RATCHET_BINNED_P90 = 1.0015
    }
}
