package afield.stdlib

import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The function workbench's worked example, rewritten from formula-bearing ops, and its own claims
 * certified on the rewrite.
 *
 * `public/docs/assets/function_conversation/final.kt` (the same source as
 * `dev_docs/agents/demos/function_conversation_example/function_source_code_.kt`) builds each
 * channel as one `fn { seconds -> … }` lambda, which the interval walk must refuse. Only one
 * construct in it actually *needs* a lambda: the piecewise selection of an event and of its phase
 * (`events.firstOrNull { … }` and the `when`). Everything else — the phase-modulated carrier, the
 * integrated-frequency pulse, the quintic `smooth01`, `coerceIn`, the e3/y lag polynomial — is
 * arithmetic, `sin`/`cos` with a phase signal, and `clamp`. The selection is written here with
 * exact 0/1 indicators from `step` (a value-only formula since 2026-09-23), and every product and
 * sum is ordered as the lambda orders it.
 *
 * [workbenchLambda] is the worked example's code, verbatim but for being a function. The rewrite
 * is compared with it as raw bits.
 */
class FnIntervalWorkbenchTest {

    private val durationSeconds = 3600.0
    private val carrierPeriodSeconds = 40.0
    private val relativePhasePeriodSeconds = 100.0
    private val phaseExcursionRadians = 2.0 * PI / 3.0
    private val backgroundCenter = 0.45
    private val backgroundAmplitude = 0.20
    private val slowAmplitude = 0.04
    private val slowPeriodSeconds = 230.0
    private val firstStartSeconds = 185.0
    private val eventSpacingSeconds = 600.0
    private val e3OffsetSeconds = 40.0
    private val riseSeconds = 100.0
    private val e3RiseSeconds = 200.0
    private val holdSeconds = 30.0
    private val fallSeconds = 100.0
    private val lowPulseHz = 0.06
    private val highPulseHz = 0.80
    private val pulseFloor = 0.25
    private val mergeFraction = 0.20
    private val e3YPeakLagSeconds = 6.0
    private val e3YLagSpanSeconds = 180.0

    private val events = listOf(
        StudyEvent("e1", firstStartSeconds, riseSeconds, holdSeconds, fallSeconds),
        StudyEvent("e2", firstStartSeconds + eventSpacingSeconds, riseSeconds, holdSeconds, fallSeconds),
        StudyEvent("e3", firstStartSeconds + 2.0 * eventSpacingSeconds + e3OffsetSeconds, e3RiseSeconds, holdSeconds, fallSeconds),
        StudyEvent("e5", firstStartSeconds + 4.0 * eventSpacingSeconds, riseSeconds, holdSeconds, fallSeconds),
        StudyEvent("e6", firstStartSeconds + 5.0 * eventSpacingSeconds, riseSeconds, holdSeconds, fallSeconds)
    )
    private val names = listOf("x", "y", "z")

    // ---- the worked example, verbatim --------------------------------------------------------------

    private fun workbenchLambda(index: Int, name: String): Functions.Fn = with(Functions) {
        fun smooth01(value: Double): Double {
            val u = value.coerceIn(0.0, 1.0)
            return u * u * u * (10.0 + u * (-15.0 + 6.0 * u))
        }
        fun background(seconds: Double, channel: Int): Double {
            val drift = phaseExcursionRadians * kotlin.math.sin(2.0 * PI * seconds / relativePhasePeriodSeconds)
            val phase = 2.0 * PI * seconds / carrierPeriodSeconds + (channel - 1) * drift
            return backgroundCenter + backgroundAmplitude * kotlin.math.sin(phase) +
                slowAmplitude * kotlin.math.sin(2.0 * PI * seconds / slowPeriodSeconds)
        }
        fun shoulder(base: Double, age: Double, span: Double): Double {
            val u = (age / span).coerceIn(0.0, 1.0)
            val cycles = lowPulseHz * age +
                0.5 * (highPulseHz - lowPulseHz) * age * age / span
            val pulse = 0.5 - 0.5 * kotlin.math.cos(2.0 * PI * cycles)
            val strength = smooth01(u)
            val pulseTarget = pulseFloor + (1.0 - pulseFloor) * pulse
            val pulsed = (1.0 - strength) * base + strength * pulseTarget
            val merge = smooth01((u - (1.0 - mergeFraction)) / mergeFraction)
            return (1.0 - merge) * pulsed + merge
        }
        fn(0.0..durationSeconds, name) { seconds ->
            val base = background(seconds, index)
            val event = events.firstOrNull { seconds >= it.start && seconds <= it.end }
            when {
                event == null -> base
                seconds < event.plateauStart -> {
                    val age = seconds - event.start
                    val lag = if (event.id == "e3" && name == "y" && age < e3YLagSpanSeconds) {
                        val u = age / e3YLagSpanSeconds
                        val v = 1.0 - u
                        e3YPeakLagSeconds * 64.0 * u * u * u * v * v * v
                    } else 0.0
                    val localAge = age - lag
                    val result = shoulder(base, localAge, event.rise)
                    result
                }
                seconds <= event.plateauEnd -> 1.0
                else -> shoulder(base, event.end - seconds, event.fall)
            }
        }
    }

    // ---- the same function, from formula-bearing ops ----------------------------------------------

    /** The background alone, for its own bound. */
    private fun background(index: Int): Functions.Fn = with(Functions) {
        val all = Functions.Domain.ALL
        fun k(v: Double) = c(v, all)
        val t = identity(all)
        // sin with frequency 0 is sin(phase): 0 + 1 * sin(0 * x + phase), each step exact.
        fun sinOf(phase: Functions.Fn) = sin(frequency = k(0.0), phase = phase)
        val tau = 2.0 * PI
        val drift = k(phaseExcursionRadians) * sinOf(k(tau) * t / k(relativePhasePeriodSeconds))
        val phase = k(tau) * t / k(carrierPeriodSeconds) + k((index - 1).toDouble()) * drift
        k(backgroundCenter) + k(backgroundAmplitude) * sinOf(phase) + k(slowAmplitude) * sinOf(k(tau) * t / k(slowPeriodSeconds))
    }

    private fun workbenchFormula(index: Int, name: String): Functions.Fn = with(Functions) {
        val all = Functions.Domain.ALL
        fun k(v: Double) = c(v, all)
        val t = identity(all)
        val one = k(1.0)
        val tau = 2.0 * PI
        fun cosOf(phase: Functions.Fn) = cos(frequency = k(0.0), phase = phase)
        fun smooth01(value: Functions.Fn): Functions.Fn {
            val u = value.clamp(0.0, 1.0)
            return u * u * u * (k(10.0) + u * (k(-15.0) + k(6.0) * u))
        }
        fun shoulder(base: Functions.Fn, age: Functions.Fn, span: Double): Functions.Fn {
            val u = (age / k(span)).clamp(0.0, 1.0)
            val cycles = k(lowPulseHz) * age + k(0.5 * (highPulseHz - lowPulseHz)) * age * age / k(span)
            val pulse = k(0.5) - k(0.5) * cosOf(k(tau) * cycles)
            val strength = smooth01(u)
            val pulseTarget = k(pulseFloor) + k(1.0 - pulseFloor) * pulse
            val pulsed = (one - strength) * base + strength * pulseTarget
            val merge = smooth01((u - k(1.0 - mergeFraction)) / k(mergeFraction))
            return (one - merge) * pulsed + merge
        }
        // [x >= a], exactly 0 or 1. [x <= b] is 1 - [x >= nextUp(b)], since no double lies between.
        fun from(a: Double) = step(a, all)
        val base = background(index)
        var selected: Functions.Fn? = null
        var inAnyEvent: Functions.Fn? = null
        for (event in events) {
            val age = t - k(event.start)
            val localAge = if (event.id == "e3" && name == "y") {
                val u = age / k(e3YLagSpanSeconds)
                val v = one - u
                val lag = k(e3YPeakLagSeconds * 64.0) * u * u * u * v * v * v
                // [age < 180] from the sign of 180 - age, which is exact; 0 * lag is ±0, and
                // age - (±0) is age, as the lambda's `else 0.0` gives.
                age - (k(e3YLagSpanSeconds) - age).sign().clamp(0.0, 1.0) * lag
            } else age
            val rising = from(event.start) - from(event.plateauStart)
            val holding = from(event.plateauStart) - from(Math.nextUp(event.plateauEnd))
            val falling = from(Math.nextUp(event.plateauEnd)) - from(Math.nextUp(event.end))
            val inEvent = from(event.start) - from(Math.nextUp(event.end))
            val term = rising * shoulder(base, localAge, event.rise) + holding * one +
                falling * shoulder(base, k(event.end) - t, event.fall)
            selected = selected?.let { it + term } ?: term
            inAnyEvent = inAnyEvent?.let { it + inEvent } ?: inEvent
        }
        // Exactly one addend is non-zero at any x; the rest are ±0 and leave it unchanged.
        (selected!! + (one - inAnyEvent!!) * base).withName(name)
    }

    // ---- tests --------------------------------------------------------------------------------------

    @Test
    fun theRewriteIsFormulaOnlyAndBitIdenticalToTheWorkedExample() {
        var compared = 0
        var mismatches = 0
        var first: String? = null
        for ((index, name) in names.withIndex()) {
            val lambda = workbenchLambda(index, name)
            val formula = workbenchFormula(index, name)
            assertEquals(listOf("lambda"), FnIntervals.unsupportedOps(lambda))
            assertEquals(emptyList(), FnIntervals.unsupportedOps(formula), "the rewrite must be formula-only")
            // Every 0.01 s over the hour, plus every event landmark and the doubles either side of it.
            val xs = (0..360_000).map { it / 100.0 }.toMutableList()
            for (e in events) for (landmark in listOf(e.start, e.plateauStart, e.plateauEnd, e.end, e.start + e3YLagSpanSeconds)) {
                xs += listOf(Math.nextDown(landmark), landmark, Math.nextUp(landmark))
            }
            for (x in xs) {
                val a = lambda.eval(x)
                val b = formula.eval(x)
                compared++
                if (a.toRawBits() != b.toRawBits()) {
                    mismatches++
                    if (first == null) first = "$name at x=$x: lambda $a, formula $b"
                }
            }
        }
        println("FN INTERVAL WORKBENCH: $compared samples over 3 channels, $mismatches raw-bit mismatches")
        assertEquals(0, mismatches, first)
        assertTrue(compared > 1_080_000)
    }

    /**
     * A narrated measurement, not a contract: what the certified band costs on a realistic graph —
     * the worked example's rewrite, ~40 formula nodes per shoulder — against sampling the original
     * lambda, at the viewer's own bin counts. Narrated only — nothing about the timing is asserted:
     * the measured 4.7 s overview is within 2.5x of the route's 12 s timeout, and this machine has
     * shown 3.5x slowdowns under load, so any ceiling tight enough to mean something would flake.
     */
    @Test
    fun theCertifiedBandOnTheWorkedExampleIsTimedAtTheViewersBinCounts() {
        val lambda = workbenchLambda(0, "x")
        val formula = workbenchFormula(0, "x")
        val sampled = SignalViewer(listOf(SignalChannel("x", lambda::eval)), 0.0, durationSeconds)
        val certified = SignalViewer(listOf(SignalChannel("x", lambda::eval, certified = formula)), 0.0, durationSeconds)
        fun best(block: () -> Unit): Double = (1..3).minOf { val t = System.nanoTime(); block(); (System.nanoTime() - t) / 1e6 }
        for ((from, to, points) in listOf(Triple(0.0, durationSeconds, 1000), Triple(185.0, 415.0, 1000), Triple(280.0, 290.0, 1000))) {
            sampled.window(from, to, points); certified.window(from, to, points)
            val s = best { sampled.window(from, to, points) }
            val c = best { certified.window(from, to, points) }
            val w = certified.window(from, to, points)
            println("FN INTERVAL WORKBENCH TIMING [$from, $to] ${w.mode} ${w.lanes[0].certified!!.low.size} bins: " +
                "sampled ${"%.1f".format(s)} ms, sampled + certified ${"%.1f".format(c)} ms (${"%.1f".format(c / s)}x)")
            assertTrue(w.lanes[0].certified!!.low.isNotEmpty(), "the certified band was not computed")
        }
        sampled.close(); certified.close()
    }

    /**
     * The page's claims, checked on the rewrite: every event holds exactly (1, 1, 1) for its
     * 30-second plateau (PROVEN), the background stays inside the band its own constants imply
     * (PROVEN), and every channel stays >= 0 (PROVEN). The recorded assumption "all channels stay in
     * [0, 1]" is **REFUTED**: each channel exceeds 1 by 4 ulps (1.0000000000000009, in the worked
     * example's own lambda) at the rise-to-hold hand-over; `<= 1 + 9e-10` is PROVEN instead.
     */
    @Test
    fun theWorkedExamplesClaimsAreCertified() {
        val channels = names.mapIndexed { i, n -> workbenchFormula(i, n) }
        for (e in events) {
            for ((i, ch) in channels.withIndex()) {
                val hold = ch.certifiedEquals(1.0, e.plateauStart, e.plateauEnd)
                assertEquals(FnVerdictKind.PROVEN, hold.kind, "${e.id}/${names[i]}: $hold")
                assertEquals(1.0, ch.enclose(e.plateauStart, e.plateauEnd).lo)
            }
            println("FN INTERVAL WORKBENCH HOLD: ${e.id} == (1,1,1) on [${e.plateauStart}, ${e.plateauEnd}] PROVEN for x, y, z")
        }
        // The background: 0.45 ± 0.2 ± 0.04, so inside [0.21, 0.69] — and certifiedRange says how
        // much of that band it actually uses.
        for (i in names.indices) {
            val bg = background(i)
            assertEquals(FnVerdictKind.PROVEN, bg.certifiedNeverExceeds(0.69, 0.0, durationSeconds).kind)
            assertEquals(FnVerdictKind.PROVEN, bg.certifiedNeverBelow(0.21, 0.0, durationSeconds).kind)
            val r = bg.certifiedRange(0.0, durationSeconds, tolerance = 1e-6)
            assertTrue(r.certified, "$r")
            println("FN INTERVAL WORKBENCH BACKGROUND ${names[i]}: min ${r.min!!.value} at ${r.min.witness} (≥ ${r.min.bound}), " +
                "max ${r.max!!.value} at ${r.max.witness} (≤ ${r.max.bound}); ${r.min.enclosures + r.max.enclosures} enclosures")
        }
        // "All channels stay in [0,1]" (one of the page's recorded agent assumptions). Below 0 is
        // proven outright. Above 1 is not: the exact claim is REFUTED — each channel crosses 1 by a
        // few ulps in the last instant of each rise, where the lambda's shoulder hands over to the
        // hold — and `<= 1 + 1e-9` is PROVEN, by certifiedRange's proven upper bound. The witness is checked against the worked example's own
        // lambda, not just the rewrite.
        for ((i, ch) in channels.withIndex()) {
            val started = System.nanoTime()
            val below = ch.certifiedNeverBelow(0.0, 0.0, durationSeconds)
            val exact = ch.certifiedNeverExceeds(1.0, 0.0, durationSeconds)
            val max = ch.certifiedRange(0.0, durationSeconds, tolerance = 9e-10).max!!
            val seconds = (System.nanoTime() - started) / 1e9
            val lambdaAtWitness = workbenchLambda(i, names[i]).eval(exact.witness!!)
            println("FN INTERVAL WORKBENCH [0,1] ${names[i]}: >= 0 ${below.kind} (${below.enclosures} enclosures); " +
                "<= 1 ${exact.kind} at x=${exact.witness} (lambda gives $lambdaAtWitness); " +
                "max ${max.value} at ${max.witness}, proven ≤ ${max.bound} (${max.enclosures} enclosures); ${"%.1f".format(seconds)} s")
            assertEquals(FnVerdictKind.PROVEN, below.kind, "$below")
            assertEquals(FnVerdictKind.REFUTED, exact.kind, "$exact")
            assertTrue(lambdaAtWitness > 1.0, "the worked example itself should exceed 1 at the witness")
            assertEquals(exact.witnessValue!!.toRawBits(), lambdaAtWitness.toRawBits())
            // The proven bound of certifiedRange *is* the certificate for `<= 1 + 1e-9`.
            assertTrue(max.value > 1.0 && max.bound < 1.0 + 1e-9, "$max")
        }
    }
}
