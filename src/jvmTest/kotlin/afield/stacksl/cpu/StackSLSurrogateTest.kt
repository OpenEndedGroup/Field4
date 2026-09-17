package afield.stacksl.cpu

import afield.stacksl.stackSLDocumentableWords
import afield.stdlib.OptimizeMethod
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sign
import kotlin.math.tanh
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a smoothed `floor` actually buys a differentiable StackSL fit — measured in both directions.
 *
 * The brief for this work named `floor` as the wall blocking a noise fit. It is the opposite, and
 * the landed branch had already measured it: *"`floor` is what makes a noise frequency
 * differentiable"*. Inside a value-noise cell the four corner hashes **are** constants, so zero is
 * the *true* derivative there; and `_hash21` — `fract(p * vec2(123.34, 456.21))`, `p += dot(p, p +
 * 45.32)`, `fract(p.x * p.y)` — is not bit-twiddling at all. It is perfectly differentiable and
 * perfectly useless, a chaotic map whose chain rule `floor` is currently keeping out of the answer.
 *
 * So this file measures two things that point in opposite directions, and keeps both as fences:
 *
 *  - what a surrogate **widens**: which words gain a gradient, and which walled parameters a fit
 *    can then recover;
 *  - what it **costs**: `the staircase surrogate is a regression for a noise frequency` is the
 *    falsification, kept so that nobody switches [AdSurrogateFamily.STAIRCASE] on globally.
 *
 * Every test here asserts its own premise before its result, because each comparison has a
 * degenerate reading that would pass quietly: a surrogate that did nothing would preserve the primal
 * perfectly, a wall with no signal behind it would be "recovered" by standing still, and
 * zero-versus-zero agrees with every floor there is.
 *
 * #### The one result that is a caveat rather than a win
 *
 * A surrogate partial is **local**: it mollifies the jump in front of it and knows nothing about
 * what happens downstream. Two words in this vocabulary gain a gradient the true loss flatly denies
 * — `any` and `ceil` both render an image pinned at 1.0, because the still template's own
 * `clamp(c, 0.0, 1.0)` erases the jump the surrogate is standing on. The surrogate reports ~2e-1
 * where a central difference reports exactly 0.0 at three widths. That is pinned as its own ledger
 * ([FICTITIOUS_LEDGER]) rather than folded into the win, because an unblocked count that includes
 * them is an overstatement.
 */
class StackSLSurrogateTest {

    // -- the kernel ------------------------------------------------------------------------------

    @Test
    fun `the kernel is a mollified delta, by two independent routes to its mass`() {
        assertEquals(0.0, AdSurrogate.OFF.kernel(0.0, 1.0), "the off surrogate must carry no partial")
        assertTrue(!AdSurrogate.OFF.enabled, "OFF must not report itself enabled")
        assertEquals(0.0, AdSurrogate.OFF.unitPeriodMass, "OFF carries no mass either")

        TEMPERATURES.forEach { t ->
            val s = AdSurrogate.all(t)
            assertTrue(s.enabled, "$s should be enabled")
            assertTrue(s.kernel(0.0, 1.0) > 0.0, "$s carries nothing at the jump itself")
            assertEquals(
                0.0, s.kernel(1e6, 1.0),
                "$s still carries ${s.kernel(1e6, 1.0)} a million units away from the jump"
            )
            assertEquals(
                2.0 * s.kernel(0.0, 1.0), s.kernel(0.0, 2.0), 0.0,
                "the kernel must be linear in the jump height — `sign`'s height-2 jump depends on it"
            )

            // Route one: the closed form `tanh(1/4t)`. Route two: a midpoint rule over one period.
            // A closed form checked only against itself is an assertion about a comment.
            var quadrature = 0.0
            for (i in 0 until QUADRATURE_STEPS) {
                quadrature += s.kernel(-0.5 + (i + 0.5) / QUADRATURE_STEPS, 1.0) / QUADRATURE_STEPS
            }
            assertEquals(tanh(1.0 / (4.0 * t)), s.unitPeriodMass, 1e-12, "the closed form moved")
            assertTrue(
                abs(quadrature - s.unitPeriodMass) < 1e-6,
                "at t=$t the kernel integrates to $quadrature over one unit period but the closed " +
                    "form says ${s.unitPeriodMass} — one of the two is wrong"
            )
        }

        // Annealing has to mean something: the mass must rise towards 1 as the temperature falls,
        // which is the statement that the surrogate converges to the exact (zero) derivative.
        val masses = TEMPERATURES.sortedDescending().map { AdSurrogate.all(it).unitPeriodMass }
        assertTrue(
            masses.zipWithNext().all { (hot, cold) -> cold > hot },
            "unit-period mass is not monotone in temperature: $masses"
        )
        assertTrue(masses.last() > 0.99, "the coldest temperature carries only ${masses.last()}")
        assertTrue(masses.first() < 0.8, "the hottest temperature already carries ${masses.first()}")
    }

    @Test
    fun `the annealing schedule is geometric, monotone, and ends where it says`() {
        val a = AdAnnealing.stepOnly(iterations = 41, start = 0.25, end = 0.01)
        assertEquals(0.25, a.temperatureAt(0), 1e-12)
        assertEquals(0.01, a.temperatureAt(40), 1e-12)
        // Geometric means the midpoint is the geometric mean, which a linear schedule is not.
        assertEquals(Math.sqrt(0.25 * 0.01), a.temperatureAt(20), 1e-9)
        assertTrue(
            (0..40).map { a.temperatureAt(it) }.zipWithNext().all { (x, y) -> y < x },
            "the schedule is not monotone"
        )
        // Past the budget it holds at the end rather than running away.
        assertEquals(0.01, a.temperatureAt(400), 1e-12)

        assertEquals(0, a.used)
        val first = a.next()
        assertEquals(0.25, first.temperature, 1e-12)
        assertEquals(setOf(AdSurrogateFamily.STEP), first.families)
        assertEquals(1, a.used)
        assertEquals(
            0.01, AdAnnealing(0.25, 0.01, 1, setOf(AdSurrogateFamily.STEP)).next().temperature, 1e-12,
            "a one-evaluation budget should sit at the end of the schedule, not the start"
        )
    }

    // -- the primal does not move -----------------------------------------------------------------

    @Test
    fun `the surrogate changes the gradient and not one bit of the primal`() {
        var comparisons = 0
        var changedGradient = 0
        var floats = 0
        var controlChanged = 0
        PRIMAL_POPULATION.forEach { (program, bearsAJump) ->
            val wired = StackSLWires.lift(program)
            val loss = StackSLImageLoss(wired, StackSLCpu.stillTemplate(), PRIMAL_DIM, rampTarget(PRIMAL_DIM))
            val values = wired.initial
            val reference = loss.render(values)
            val exact = loss.valueAndGradient(values)
            var changedHere = 0
            TEMPERATURES.forEach { t ->
                val s = AdSurrogate.all(t)
                val primal = loss.renderPrimal(values, s)
                val differing = reference.rgba.indices.count {
                    reference.rgba[it].toRawBits() != primal.rgba[it].toRawBits()
                }
                assertEquals(
                    0, differing,
                    "$s moved $differing of ${reference.rgba.size} primal floats on " +
                        "'${program.lines().first()}' — a straight-through surrogate touches partials only"
                )
                val soft = loss.valueAndGradient(values, s)
                assertEquals(
                    exact.value, soft.value, 0.0,
                    "$s changed the loss VALUE on '${program.lines().first()}': the value is the " +
                        "`Float` path's, exactly, at every temperature"
                )
                if (soft.gradient.indices.any { soft.gradient[it] != exact.gradient[it] }) changedHere++
                comparisons++
                floats += reference.rgba.size
            }
            println(
                "primal invariance: '${program.lines().first()}' ${"%5s".format(bearsAJump)} jump, " +
                    "$changedHere of ${TEMPERATURES.size} temperatures changed a gradient component"
            )
            changedGradient += changedHere
            if (!bearsAJump) controlChanged += changedHere
        }
        println(
            "primal invariance: ${PRIMAL_POPULATION.size} programs x ${TEMPERATURES.size} temperatures " +
                "= $comparisons comparisons, $floats floats, 0 differing primal bits, 0 changed loss " +
                "values; $changedGradient comparisons changed at least one gradient component"
        )

        // Premise one: a surrogate that did nothing would pass everything above trivially.
        assertTrue(
            changedGradient >= MIN_GRADIENT_CHANGED,
            "only $changedGradient of $comparisons surrogate settings changed any gradient component, " +
                "under $MIN_GRADIENT_CHANGED — the primal-invariance claim above is then close to a " +
                "tautology on this population"
        )
        // Premise two, the control: a program with no jump in it must be bit-identical in the
        // GRADIENT too, not just the primal. This is what says the surrogate is confined to its sites.
        assertEquals(
            0, controlChanged,
            "the jump-free control program had its gradient changed by the surrogate in " +
                "$controlChanged comparisons — a surrogate partial has escaped its own sites"
        )
    }

    // -- M1: which words gain a gradient, and which of those gains are real -----------------------

    @Test
    fun `the surrogate unblocks twelve words and two of the twelve are fictitious`() {
        val words = stackSLDocumentableWords.sorted()
        assertTrue(words.size > 300, "expected the whole vocabulary, got ${words.size}")

        var exactPasses = 0
        var refused = 0
        var notProbed = 0
        val exactBlocks = sortedSetOf<String>()
        val gradientOf = HashMap<String, (AdSurrogate) -> Double?>()
        val trueLossMoves = HashMap<String, Boolean>()

        words.forEach { word ->
            val program = "input 1.0 * $word"
            val wired = runCatching { StackSLWires.lift(program) }.getOrNull()
            if (wired == null) { notProbed++; return@forEach }
            val literal = wired.literalWires.firstOrNull { it.token == "1.0" }
            if (literal == null) { notProbed++; return@forEach }
            val loss = StackSLImageLoss(
                wired, StackSLCpu.stillTemplate(), WORD_DIM, rampTarget(WORD_DIM), parallel = false
            )
            val probe: (AdSurrogate) -> Double? = { s ->
                runCatching { loss.valueAndGradient(wired.initial, s).gradient[literal.index] }.getOrNull()
            }
            val exact = probe(AdSurrogate.OFF)
            if (exact == null) { refused++; return@forEach }
            if (exact != 0.0) { exactPasses++; return@forEach }
            exactBlocks += word
            gradientOf[word] = probe
            // Does the TRUE loss move with this wire at all? Two resolutions, three widths, on the
            // `Float` path. A jump the template's clamp has already flattened is invisible here, and
            // a surrogate standing on it is reporting a derivative of nothing.
            trueLossMoves[word] = REALITY_DIMS.any { dim ->
                val l = StackSLImageLoss(wired, StackSLCpu.stillTemplate(), dim, rampTarget(dim), parallel = false)
                REALITY_WIDTHS.any { h ->
                    l.value(bump(wired.initial, literal.index, h)) !=
                        l.value(bump(wired.initial, literal.index, -h))
                }
            }
        }

        assertTrue(
            exactBlocks.size >= MIN_EXACT_BLOCKS,
            "only ${exactBlocks.size} words block a derivative exactly, expected at least " +
                "$MIN_EXACT_BLOCKS — with nothing blocked this test has nothing to measure"
        )

        val byStep = sortedSetOf<String>()
        val byStaircase = sortedSetOf<String>()
        val bySign = sortedSetOf<String>()
        exactBlocks.forEach { word ->
            val probe = gradientOf.getValue(word)
            if ((probe(AdSurrogate.stepOnly(WORD_TEMPERATURE)) ?: 0.0) != 0.0) byStep += word
            if ((probe(AdSurrogate.staircaseOnly(WORD_TEMPERATURE)) ?: 0.0) != 0.0) byStaircase += word
            if ((probe(AdSurrogate.of(WORD_TEMPERATURE, setOf(AdSurrogateFamily.SIGN))) ?: 0.0) != 0.0) {
                bySign += word
            }
        }
        val unblocked = sortedSetOf<String>().apply { addAll(byStep); addAll(byStaircase); addAll(bySign) }
        val fictitious = unblocked.filter { trueLossMoves[it] == false }.toSortedSet()
        val real = (unblocked - fictitious).toSortedSet()

        println(
            "M1 word reach: ${words.size} documentable words, " +
                "${exactPasses + exactBlocks.size + refused} probed with a wire upstream — " +
                "$exactPasses pass a derivative exactly, ${exactBlocks.size} block it, $refused " +
                "refused outright; $notProbed do not compile as 'input 1.0 * word'"
        )
        println("  unblocked by STEP      (${byStep.size}): $byStep")
        println("  unblocked by STAIRCASE (${byStaircase.size}): $byStaircase")
        println("  unblocked by SIGN      (${bySign.size}): $bySign")
        println(
            "  unblocked at all: ${unblocked.size} of ${exactBlocks.size} blocked — ${real.size} where " +
                "the true loss really moves ($real), ${fictitious.size} fictitious ($fictitious)"
        )

        // The bidirectional ledger, twice over. `entries.size >= POPULATION` catches a shrink and
        // nothing catches an addition, which is the hole last night's polish commit had to fill; so
        // each set gets missing, stale and equality, each with its own way to re-fence.
        assertLedger("UNBLOCKED_LEDGER", UNBLOCKED_LEDGER, unblocked)
        assertLedger("FICTITIOUS_LEDGER", FICTITIOUS_LEDGER, fictitious)
        assertEquals(
            UNBLOCKED_LEDGER - FICTITIOUS_LEDGER, real.toSet(),
            "the real set is not the unblocked set minus the fictitious one, which it is by definition"
        )

        // And the stated reason `step` is the family that carries the practical win: three words
        // lower to a `step` call, and the surrogate is what lets a gradient reach all three.
        assertTrue(
            byStep.containsAll(setOf("threshold", "step", "select", "if")),
            "the step family is supposed to unblock threshold, step, select and if: $byStep"
        )
        assertTrue(
            byStaircase.contains("floor"),
            "the staircase family is supposed to unblock `floor` itself: $byStaircase"
        )
        // SIGN unblocks nothing at this population, and that is a measurement rather than a bug:
        // no documentable word routes a wire to the image through `sign`. Two-sided, so that a word
        // that starts doing so is noticed rather than silently absorbed.
        assertEquals(
            emptySet<String>(), bySign.toSet(),
            "the SIGN family now unblocks $bySign, where it unblocked nothing when measured — " +
                "record the new word rather than leaving the count unpinned"
        )
    }

    // -- M2: parameter recovery, surrogate off versus on -------------------------------------------

    @Test
    fun `a wire behind a wall is recovered only with the surrogate on`() {
        var continuousWorstError = 0.0
        var continuousWorstImprovement = Double.MAX_VALUE
        var continuousWhere = "-"
        var countWorstError = 0.0
        var countWorstImprovement = Double.MAX_VALUE
        var countWhere = "-"
        var continuous = 0
        var counts = 0
        var lossesDrivenToZero = 0

        WALLED.forEach { case ->
            val wired = StackSLWires.lift(case.program)
            val wire = wired.literalWires.firstOrNull { it.token == case.token }
                ?: error("'${case.program}' has no literal-backed wire '${case.token}': ${wired.describe()}")

            // The target is generated by the SAME program at a different value for this ONE wire.
            // This is PARAMETER RECOVERY against known ground truth, and not fitting a picture no
            // StackSL program generated; the two are different claims and only this one is made here.
            val scratch = StackSLImageLoss(wired, StackSLCpu.stillTemplate(), FIT_DIM, rampTarget(FIT_DIM))
            val target = scratch.render(wired.initial.copyOf().also { it[wire.index] = case.star })
            val loss = StackSLImageLoss(wired, StackSLCpu.stillTemplate(), FIT_DIM, target)

            val seed = wired.initial
            val seedRelativeError = abs(seed[wire.index] - case.star) / abs(case.star)
            val seedLoss = loss.value(seed)

            // Premise one: the exact gradient for this wire really is zero, so an exact fit *cannot*
            // move it. If an emitter change makes it non-zero, this says so rather than quietly
            // measuring a surrogate against a gradient that already worked.
            val exactGradient = loss.valueAndGradient(seed).gradient[wire.index]
            assertEquals(
                0.0, exactGradient, 0.0,
                "'${case.program}' wire '${case.token}' has exact gradient $exactGradient, not zero — " +
                    "it is not behind a wall and does not belong in this corpus"
            )
            // Premise two: there IS something to recover. A wall with no signal behind it would be
            // "recovered" by standing still, and zero against zero passes every floor there is.
            assertTrue(
                seedRelativeError > MIN_SEED_RELATIVE_ERROR,
                "'${case.program}' seeds at ${seed[wire.index]} and targets ${case.star}, only " +
                    "${"%.4f".format(seedRelativeError)} apart relatively — nothing to recover"
            )
            assertTrue(
                seedLoss > MIN_SEED_LOSS,
                "'${case.program}' has seed loss ${"%.3e".format(seedLoss)}, under $MIN_SEED_LOSS — the " +
                    "wall has no signal behind it and the counterfactual would be zero against zero"
            )

            val off = fit(case, target, annealing = null)
            val on = fit(case, target, annealing = AdAnnealing(SURROGATE_START, SURROGATE_END, ITERATIONS, case.families))
            val offError = abs(off.fittedWires[wire.index] - case.star) / abs(case.star)
            val onError = abs(on.fittedWires[wire.index] - case.star) / abs(case.star)
            val improvement = if (onError == 0.0) Double.POSITIVE_INFINITY else offError / onError
            if (on.fittedLoss == 0.0) lossesDrivenToZero++

            println(
                ("M2 %-6s %-38s '%s' %.3f -> %.3f | off rel %.4f (cannot move) | on %.4f rel %.4f | " +
                    "%.1fx | loss %.3e -> %.3e | %s").format(
                    case.kind, case.program.lines().first(), case.token, seed[wire.index], case.star,
                    offError, on.fittedWires[wire.index], onError, improvement,
                    on.seedLoss, on.fittedLoss, case.families.joinToString("+") { it.name }
                )
            )

            // The exact fit literally cannot move: a zero gradient is a zero step in ADAM too.
            assertEquals(
                seed[wire.index], off.fittedWires[wire.index], 0.0,
                "'${case.program}' moved its walled wire with the surrogate OFF, which contradicts the " +
                    "zero gradient asserted above"
            )
            assertTrue(
                offError > MIN_SEED_RELATIVE_ERROR,
                "the surrogate-off arm ended ${"%.4f".format(offError)} from the truth, inside the " +
                    "non-degeneracy floor — then there is no counterfactual here"
            )

            if (case.kind == Kind.CONTINUOUS) {
                continuous++
                if (onError > continuousWorstError) { continuousWorstError = onError; continuousWhere = case.label }
                continuousWorstImprovement = minOf(continuousWorstImprovement, improvement)
            } else {
                counts++
                if (onError > countWorstError) { countWorstError = onError; countWhere = case.label }
                countWorstImprovement = minOf(countWorstImprovement, improvement)
            }
        }

        println(
            "M2 parameter recovery: ${WALLED.size} walled wires over ${WALLED.map { it.program }.distinct().size} " +
                "programs, ${FIT_DIM}x$FIT_DIM, $ITERATIONS Adam steps at lr=$LEARNING_RATE, annealed " +
                "$SURROGATE_START -> $SURROGATE_END. Continuous parameters: $continuous wires, worst " +
                "relative error ${"%.4f".format(continuousWorstError)} at $continuousWhere, worst " +
                "improvement ${"%.1f".format(continuousWorstImprovement)}x. Integer-valued (count) " +
                "parameters: $counts wires, worst relative error ${"%.4f".format(countWorstError)} at " +
                "$countWhere, worst improvement ${"%.1f".format(countWorstImprovement)}x. " +
                "$lossesDrivenToZero of ${WALLED.size} reached a loss of exactly zero."
        )

        assertEquals(CONTINUOUS_POPULATION, continuous, "the continuous class is not the size it was fenced at")
        assertEquals(COUNT_POPULATION, counts, "the count class is not the size it was fenced at")
        assertTrue(
            continuousWorstError <= CONTINUOUS_TOLERANCE,
            "worst continuous recovery error ${"%.4f".format(continuousWorstError)} at $continuousWhere, " +
                "above the floor $CONTINUOUS_TOLERANCE"
        )
        assertTrue(
            continuousWorstImprovement >= MIN_CONTINUOUS_IMPROVEMENT,
            "worst continuous off/on improvement ${"%.1f".format(continuousWorstImprovement)}x, under " +
                "${MIN_CONTINUOUS_IMPROVEMENT}x"
        )
        assertTrue(
            countWorstError <= COUNT_TOLERANCE,
            "worst count recovery error ${"%.4f".format(countWorstError)} at $countWhere, above the " +
                "floor $COUNT_TOLERANCE"
        )
        assertTrue(
            countWorstImprovement >= MIN_COUNT_IMPROVEMENT,
            "worst count off/on improvement ${"%.1f".format(countWorstImprovement)}x, under " +
                "${MIN_COUNT_IMPROVEMENT}x"
        )
        // Two-sided, and it is the finding rather than a safety margin: an INTEGER-valued parameter
        // recovers markedly worse, because the loss is itself a staircase in it, so the surrogate is
        // descending a function that really does have flat shelves. If the two classes ever become
        // comparable, that story is wrong and belongs rewritten rather than quietly relaxed.
        assertTrue(
            countWorstError > CONTINUOUS_TOLERANCE,
            "the count class recovered to ${"%.4f".format(countWorstError)}, inside the continuous " +
                "floor $CONTINUOUS_TOLERANCE — then the two classes are the same and this test's " +
                "structure is wrong"
        )
        assertTrue(
            lossesDrivenToZero >= MIN_LOSSES_TO_ZERO,
            "only $lossesDrivenToZero of ${WALLED.size} fits reached a loss of exactly zero, under " +
                "$MIN_LOSSES_TO_ZERO — an exact recovery is the strongest evidence here and it has gone"
        )
    }

    // -- M3: the bias ------------------------------------------------------------------------------

    @Test
    fun `the surrogate direction tracks the loss at its own scale, not the derivative`() {
        var agreeing = 0
        var oracleVanished = 0
        var signFlipped = 0
        var total = 0
        var ratioLow = Double.MAX_VALUE
        var ratioHigh = 0.0
        val nestedRatios = ArrayList<Double>()

        WALLED.forEach { case ->
            val wired = StackSLWires.lift(case.program)
            val wire = wired.literalWires.first { it.token == case.token }
            val scratch = StackSLImageLoss(wired, StackSLCpu.stillTemplate(), BIAS_DIM, rampTarget(BIAS_DIM))
            val target = scratch.render(wired.initial.copyOf().also { it[wire.index] = case.star })
            val loss = StackSLImageLoss(wired, StackSLCpu.stillTemplate(), BIAS_DIM, target)
            val seed = wired.initial

            TEMPERATURES.forEach { t ->
                val g = loss.valueAndGradient(seed, AdSurrogate.of(t, case.families)).gradient[wire.index]
                // The oracle for a surrogate at temperature t is the t-SCALE central difference of
                // the true loss, not its derivative: a mollified delta of width t reports how the
                // loss changes over a window of width t. As h -> 0 the truth is zero and there is
                // nothing left to agree with, which is the whole problem being worked around.
                val fd = (loss.value(bump(seed, wire.index, t)) - loss.value(bump(seed, wire.index, -t))) /
                    (2.0 * t)
                total++
                when {
                    fd == 0.0 -> oracleVanished++
                    g == 0.0 || sign(g) != sign(fd) -> signFlipped++
                    else -> {
                        agreeing++
                        val ratio = abs(g / fd)
                        // The nested-`step` case is held out of the range, and fenced separately
                        // below: its collapse is structural rather than a scale drift.
                        if (case.program == NESTED_STEP_PROGRAM) {
                            nestedRatios += ratio
                        } else {
                            ratioLow = minOf(ratioLow, ratio)
                            ratioHigh = maxOf(ratioHigh, ratio)
                        }
                    }
                }
                println(
                    "M3 %-6s %-38s t=%.3f surrogate % .4e  fd(h=t) % .4e  ratio %s".format(
                        case.kind, case.program.lines().first(), t, g, fd,
                        if (fd == 0.0) "(oracle vanished)" else "%.3f".format(g / fd)
                    )
                )
            }
        }
        val fraction = agreeing.toDouble() / total
        println(
            "M3 bias: $agreeing of $total (wire, temperature) pairs — ${WALLED.size} walled wires x " +
                "${TEMPERATURES.size} temperatures — point the same way as the t-scale central " +
                "difference (${"%.1f".format(fraction * 100)}%). $signFlipped disagreed in sign, " +
                "$oracleVanished had no oracle because the true loss is flat over that very window. " +
                "Excluding the nested-step program, where both are non-zero the magnitude ratio spans " +
                "${"%.3f".format(ratioLow)}..${"%.3f".format(ratioHigh)}: the surrogate UNDER-reports " +
                "hot (the kernel carries only tanh(1/4t) of its jump) and OVER-reports cold."
        )
        println(
            "M3 nested-step collapse: '$NESTED_STEP_PROGRAM' ratios by temperature " +
                nestedRatios.joinToString(", ") { "%.3e".format(it) } +
                " — a fall of ${"%.2e".format(nestedRatios.first() / nestedRatios.last())}x across the " +
                "sweep. `select` lowers to mix(f, t, step(0.5, abs(cond))) and `abs(cond)` is exactly " +
                "0 or 1, so the OUTER step's argument sits at distance exactly 0.5 from its edge, " +
                "forever. K_t(0.5) decays like exp(-1/2t), so annealing switches `select` back off. " +
                "The practical rule: a nested predicate wants a temperature FLOOR near 0.1, not 0.01."
        )
        assertEquals(WALLED.size * TEMPERATURES.size, total, "not every (wire, temperature) pair was measured")
        assertTrue(
            fraction >= MIN_SIGN_AGREEMENT,
            "only ${"%.1f".format(fraction * 100)}% of surrogate directions agree in sign with the " +
                "t-scale finite difference, floor is ${MIN_SIGN_AGREEMENT * 100}% — a direction wrong " +
                "more often than that is not a descent direction"
        )
        assertTrue(
            ratioHigh <= MAX_MAGNITUDE_RATIO && ratioLow >= MIN_MAGNITUDE_RATIO,
            "the magnitude ratio spans ${"%.3f".format(ratioLow)}..${"%.3f".format(ratioHigh)}, outside " +
                "the recorded $MIN_MAGNITUDE_RATIO..$MAX_MAGNITUDE_RATIO — the surrogate's scale has " +
                "moved and the bias section of dev_docs/STACKSL_SURROGATE.md is stale"
        )
        // The nested-`step` collapse, fenced as its own claim. `select` lowers to
        // `mix(f, t, step(0.5, abs(cond)))`, and `abs(cond)` is exactly 0 or 1 because `cond` is
        // itself a `step` output — so the outer jump is *always* at distance exactly 0.5 and
        // `K_t(0.5) ~ exp(-1/2t)` drives the whole chain to nothing as the schedule cools. That is
        // not a bug to fix; it is the reason a nested predicate needs a temperature floor. Asserted
        // two-sided so that a `select` lowering which stops nesting is noticed rather than absorbed.
        assertEquals(
            TEMPERATURES.size, nestedRatios.size,
            "the nested-step case did not produce a ratio at every temperature: $nestedRatios"
        )
        assertTrue(
            nestedRatios.first() / nestedRatios.last() >= MIN_NESTED_COLLAPSE,
            "the nested-step surrogate ratio fell only " +
                "${"%.2e".format(nestedRatios.first() / nestedRatios.last())}x across the sweep, under " +
                "the recorded ${"%.0e".format(MIN_NESTED_COLLAPSE)} — then `select` no longer nests two " +
                "`step`s and the temperature-floor rule in dev_docs/STACKSL_SURROGATE.md is obsolete"
        )
        // Non-degeneracy: the EXACT gradient agrees with nothing at all, because it is zero. Without
        // this the comparison above could be measuring a wall that is not one.
        assertEquals(
            0, WALLED.count { case ->
                val wired = StackSLWires.lift(case.program)
                val wire = wired.literalWires.first { it.token == case.token }
                val scratch = StackSLImageLoss(wired, StackSLCpu.stillTemplate(), BIAS_DIM, rampTarget(BIAS_DIM))
                val target = scratch.render(wired.initial.copyOf().also { it[wire.index] = case.star })
                StackSLImageLoss(wired, StackSLCpu.stillTemplate(), BIAS_DIM, target)
                    .valueAndGradient(wired.initial).gradient[wire.index] != 0.0
            },
            "a walled wire has a non-zero exact gradient, so the comparison above is not against a wall"
        )
    }

    // -- M5: does the schedule earn its place? -----------------------------------------------------

    /**
     * The schedule sweep, whose result is a **negative** one and is kept for that reason.
     *
     * [AdAnnealing] was built on the assumption that cooling a surrogate is what makes it work: hot
     * early to find the jump, cold late to converge on the exact derivative. Measured over the
     * continuous corpus, that assumption is **wrong in its second half**. A *fixed hot* temperature
     * recovers marginally better than the geometric schedule and has a better mean, and a *fixed
     * cold* one fails outright on the nested-predicate case — which is the M3 collapse showing up in
     * a fit rather than only in a gradient.
     *
     * So what the measurement actually says is: **the temperature has to be hot enough, and the
     * shape of the schedule is second order.** Annealing is not harmful — it converges to the exact
     * gradient, which is a property worth having when the fit is nearly done — but it is not what
     * buys the recovery, and the documentation says so rather than implying otherwise.
     */
    @Test
    fun `a hot enough temperature is what recovers, and the schedule shape is second order`() {
        val continuous = WALLED.filter { it.kind == Kind.CONTINUOUS }
        val worst = LinkedHashMap<String, Double>()
        val zeroLosses = LinkedHashMap<String, Int>()
        val perCase = LinkedHashMap<String, MutableMap<String, Double>>()
        val seedError = HashMap<String, Double>()
        continuous.forEach { case ->
            val wired = StackSLWires.lift(case.program)
            val wire = wired.literalWires.first { it.token == case.token }
            seedError[case.label] = abs(wired.initial[wire.index] - case.star) / abs(case.star)
        }

        SCHEDULES.forEach { (label, ends) ->
            var w = 0.0
            var sum = 0.0
            var zeros = 0
            val here = LinkedHashMap<String, Double>()
            continuous.forEach { case ->
                val wired = StackSLWires.lift(case.program)
                val wire = wired.literalWires.first { it.token == case.token }
                val scratch = StackSLImageLoss(wired, StackSLCpu.stillTemplate(), FIT_DIM, rampTarget(FIT_DIM))
                val target = scratch.render(wired.initial.copyOf().also { it[wire.index] = case.star })
                val run = fit(case, target, AdAnnealing(ends.first, ends.second, ITERATIONS, case.families))
                val e = abs(run.fittedWires[wire.index] - case.star) / abs(case.star)
                if (run.fittedLoss == 0.0) zeros++
                here[case.label] = e
                w = maxOf(w, e)
                sum += e
            }
            worst[label] = w
            zeroLosses[label] = zeros
            perCase[label] = here
            println(
                "M5 %-24s worst relative error %.4f   mean %.4f   %d of %d fits reached loss exactly 0"
                    .format(label, w, sum / continuous.size, zeros, continuous.size)
            )
        }
        val nested = continuous.first { it.program == NESTED_STEP_PROGRAM }.label
        println(
            "M5 schedule sweep: ${continuous.size} continuous walled wires x ${SCHEDULES.size} " +
                "schedules, ${FIT_DIM}x$FIT_DIM, $ITERATIONS Adam steps at lr=$LEARNING_RATE. The " +
                "surrogate-off arm cannot move at all, so its error is the seed's. The result is " +
                "NEGATIVE for annealing: a fixed hot temperature " +
                "(${"%.4f".format(worst.getValue(HOT))}) is marginally BETTER than the geometric " +
                "schedule (${"%.4f".format(worst.getValue(GEOMETRIC))}), and a fixed cold one " +
                "(${"%.4f".format(worst.getValue(COLD))}) fails outright. What matters is that the " +
                "temperature is hot enough; the schedule's shape is second order."
        )
        println(
            "M5 and the reason cold fails is M3's collapse, in a fit: on $nested the cold arm ended " +
                "${"%.4f".format(perCase.getValue(COLD).getValue(nested))} from the truth against a " +
                "seed error of ${"%.4f".format(seedError.getValue(nested))} — it did not move at all, " +
                "because a nested `step`'s kernel is K_t(0.5) and that is ~1e-12 at t=0.016. Every " +
                "other continuous case recovers even when cold."
        )

        assertEquals(SCHEDULES.size, worst.size, "a schedule label is duplicated, so one overwrote another")

        // The three arms that work, held to M2's floor.
        listOf(HOT, WARM, GEOMETRIC).forEach { label ->
            assertTrue(
                worst.getValue(label) <= CONTINUOUS_TOLERANCE,
                "'$label' worst error ${"%.4f".format(worst.getValue(label))} is above the recovery " +
                    "floor $CONTINUOUS_TOLERANCE that M2 holds a continuous parameter to"
            )
            assertEquals(
                continuous.size, zeroLosses.getValue(label),
                "'$label' drove ${zeroLosses.getValue(label)} of ${continuous.size} continuous fits to " +
                    "a loss of exactly zero, not all of them"
            )
        }

        // The finding, asserted two-sided in both directions it can move.
        //
        // One: annealing does NOT beat a fixed hot temperature, so nothing here may claim it does.
        // Floored at a ratio, and if the schedule ever does become clearly better than hot then
        // dev_docs/STACKSL_SURROGATE.md's recommendation is out of date and should be rewritten
        // rather than left saying the opposite of the measurement.
        assertTrue(
            worst.getValue(GEOMETRIC) >= worst.getValue(HOT) / MAX_ANNEAL_ADVANTAGE,
            "the geometric schedule's worst error ${"%.4f".format(worst.getValue(GEOMETRIC))} is now " +
                "more than ${MAX_ANNEAL_ADVANTAGE}x better than a fixed hot temperature's " +
                "${"%.4f".format(worst.getValue(HOT))} — annealing has started to earn its place, and " +
                "the negative result recorded in dev_docs/STACKSL_SURROGATE.md needs rewriting"
        )
        assertTrue(
            worst.getValue(GEOMETRIC) <= worst.getValue(HOT) * MAX_ANNEAL_PENALTY,
            "the geometric schedule is ${"%.2f".format(worst.getValue(GEOMETRIC) / worst.getValue(HOT))}x " +
                "WORSE than a fixed hot temperature, past the recorded ${MAX_ANNEAL_PENALTY}x — " +
                "cooling has stopped being roughly free and the default should change"
        )

        // Two: a fixed COLD temperature really does fail, and it fails on exactly the case M3 says
        // it must. Asserted as "did not move" rather than as a loose bound, which is the sharpest
        // form the claim has.
        assertEquals(
            seedError.getValue(nested), perCase.getValue(COLD).getValue(nested), 1e-9,
            "the cold arm moved the nested-predicate wire, which contradicts M3: a nested `step`'s " +
                "surrogate kernel is K_t(0.5) ~ 1e-12 at t=0.016, so there is nothing to descend"
        )
        assertTrue(
            worst.getValue(COLD) >= MIN_COLD_FAILURE,
            "the cold arm's worst error ${"%.4f".format(worst.getValue(COLD))} is under the recorded " +
                "$MIN_COLD_FAILURE — if annealing to 0.016 is now safe then the temperature-floor rule " +
                "in dev_docs/STACKSL_SURROGATE.md is obsolete"
        )
        // Non-degeneracy for the cold arm: it must still recover everything *except* the nested one,
        // or "cold fails" would be a statement about the surrogate rather than about nesting.
        assertEquals(
            continuous.size - 1, zeroLosses.getValue(COLD),
            "the cold arm reached a loss of exactly zero on ${zeroLosses.getValue(COLD)} of " +
                "${continuous.size} cases, expected all but the nested one — cold failing *everywhere* " +
                "is a different finding from cold failing on a nested predicate"
        )
    }

    // -- M4: what the staircase family costs -------------------------------------------------------

    @Test
    fun `the staircase surrogate is a regression for a noise frequency`() {
        val wired = StackSLWires.lift(FBM_PROGRAM)
        val wire = wired.literalWires.firstOrNull { it.token == "3" }
            ?: error("the fbm program has no '3' frequency wire: ${wired.describe()}")
        val loss = StackSLImageLoss(wired, StackSLCpu.stillTemplate(), FBM_DIM, rampTarget(FBM_DIM))
        val values = wired.initial

        val exact = loss.valueAndGradient(values).gradient[wire.index]
        val h = 1e-3 * max(1.0, abs(values[wire.index]))
        val fd = (loss.value(bump(values, wire.index, h)) - loss.value(bump(values, wire.index, -h))) / (2.0 * h)
        val exactRelative = abs(exact - fd) / max(abs(fd), 1e-12)

        // The premise, and it is the landed measurement: `uv 3 * fbm`'s frequency is ALREADY
        // differentiable, because floor() keeps `_hash21`'s chain rule out of the answer. If that
        // stops being true, the conclusion below is about something else entirely.
        assertTrue(
            exactRelative <= EXACT_FBM_TOLERANCE,
            "the exact fbm frequency gradient ${"%.6e".format(exact)} disagrees with a central " +
                "difference ${"%.6e".format(fd)} by ${"%.3e".format(exactRelative)}, above " +
                "$EXACT_FBM_TOLERANCE — the premise this test reasons from, that the wire already " +
                "works, is gone"
        )

        val relatives = STAIRCASE_TEMPERATURES.map { t ->
            val g = loss.valueAndGradient(values, AdSurrogate.staircaseOnly(t)).gradient[wire.index]
            val relative = abs(g - fd) / max(abs(fd), 1e-12)
            println(
                "M4 fbm frequency at t=%.3f: staircase % .6e vs central difference % .6e — relative %.3e"
                    .format(t, g, fd, relative)
            )
            relative
        }
        val worst = relatives.max()
        println(
            "M4 the cost of a soft floor: on the fbm gallery program's frequency wire the EXACT " +
                "gradient ${"%.6e".format(exact)} agrees with a central difference to " +
                "${"%.3e".format(exactRelative)}; with STAIRCASE on it is wrong by up to " +
                "${"%.3e".format(worst)} relative over ${STAIRCASE_TEMPERATURES.size} temperatures " +
                "(${STAIRCASE_TEMPERATURES.joinToString()}), and the sign flips. A soft floor does not " +
                "help this wire — it is exactly what the wire was being protected from. Population: " +
                "1 program, 1 frequency wire, ${FBM_DIM}x$FBM_DIM."
        )
        assertTrue(
            worst >= MIN_FBM_DEGRADATION,
            "the staircase surrogate degraded the fbm frequency gradient by only ${"%.3e".format(worst)}, " +
                "under the recorded $MIN_FBM_DEGRADATION — if a soft floor is now HARMLESS on a noise " +
                "lattice then dev_docs/STACKSL_SURROGATE.md's central claim is wrong and belongs " +
                "rewritten, not quietly relaxed"
        )
        assertTrue(
            worst > exactRelative * 1e4,
            "the staircase surrogate is only ${"%.1f".format(worst / max(exactRelative, 1e-12))}x worse " +
                "than exact here; the claim is a regression of orders of magnitude, not a rounding " +
                "difference"
        )
    }

    // -- helpers -----------------------------------------------------------------------------------

    private fun fit(case: Walled, target: CpuRaster, annealing: AdAnnealing?): StackSLFitResult {
        val wired = StackSLWires.lift(case.program)
        val wire = wired.literalWires.first { it.token == case.token }
        return StackSLFit.fit(
            case.program, target, dim = FIT_DIM, iterations = ITERATIONS, learningRate = LEARNING_RATE,
            method = OptimizeMethod.ADAM, indices = listOf(wire.index), annealing = annealing
        )
    }

    /** Missing, stale, and set equality — each with its own message and its own way to re-fence. */
    private fun assertLedger(name: String, ledger: Set<String>, measured: Set<String>) {
        val missing = ledger - measured
        val added = measured - ledger
        assertTrue(
            missing.isEmpty(),
            "${missing.size} word(s) $name says the surrogate reaches are no longer reached: $missing — " +
                "either the emitter stopped lowering them to a jump, or a surrogate site regressed"
        )
        assertTrue(
            added.isEmpty(),
            "${added.size} word(s) are newly in the '$name' category and pinned by nothing: $added — " +
                "add them to $name, and say in dev_docs/STACKSL_SURROGATE.md why they moved"
        )
        assertEquals(ledger, measured, "$name is not the measured set; the two checks above should have said which way")
    }

    private fun bump(values: DoubleArray, index: Int, delta: Double): DoubleArray =
        values.copyOf().also { it[index] += delta }

    /** A fixed, program-independent target for the probes that only need the loss to be non-zero. */
    private fun rampTarget(dim: Int): CpuRaster = CpuRaster(
        dim, dim,
        FloatArray(dim * dim * 4) { i ->
            val pixel = i / 4
            val x = (pixel % dim).toFloat() / dim
            val y = (pixel / dim).toFloat() / dim
            when (i % 4) {
                0 -> 0.5f * x + 0.25f
                1 -> 0.5f * y + 0.2f
                2 -> 0.4f * (1f - x) + 0.3f
                else -> 1f
            }
        }
    )

    /**
     * Which kind of parameter sits behind the wall, and it is the axis the result splits on.
     *
     * [CONTINUOUS] is a position: an edge, an offset, a phase. The loss is genuinely smooth in it
     * away from the jumps, so a mollified jump points somewhere useful. [COUNT] is integer-valued —
     * a posterize level count, a bit depth, a band count — and the loss is a **staircase in the
     * parameter itself**, with shelves a mollified delta of width `t` cannot see across.
     */
    private enum class Kind { CONTINUOUS, COUNT }

    /** One parameter-recovery case: a program, the literal behind the wall, and the ground truth. */
    private class Walled(
        val program: String,
        val token: String,
        val star: Double,
        val kind: Kind,
        val families: Set<AdSurrogateFamily>
    ) {
        val label: String get() = "'${program.lines().first()}' wire '$token'"
    }

    private companion object {
        const val PRIMAL_DIM = 24
        const val WORD_DIM = 6
        const val FIT_DIM = 32
        const val BIAS_DIM = 32
        const val FBM_DIM = 32
        const val QUADRATURE_STEPS = 20_000

        const val ITERATIONS = 60
        const val LEARNING_RATE = 0.05
        const val SURROGATE_START = 0.25
        const val SURROGATE_END = 0.01
        const val WORD_TEMPERATURE = 0.25

        /** The sweep: a quarter of a unit cell down to under two percent of one, geometric. */
        val TEMPERATURES = listOf(0.25, 0.1, 0.04, 0.016)

        const val HOT = "fixed hot t=0.25"
        const val WARM = "fixed warm t=0.10"
        const val COLD = "fixed cold t=0.016"
        const val GEOMETRIC = "geometric 0.25 -> 0.01"

        /**
         * M5's arms: three fixed temperatures spanning the schedule, and the schedule itself.
         *
         * A fixed temperature is expressible as an [AdAnnealing] whose ends are equal, so all four
         * arms run through exactly the same code path and the comparison is between schedules
         * rather than between a schedule and a special case.
         */
        val SCHEDULES: List<Pair<String, Pair<Double, Double>>> = listOf(
            HOT to (0.25 to 0.25),
            WARM to (0.10 to 0.10),
            COLD to (0.016 to 0.016),
            GEOMETRIC to (SURROGATE_START to SURROGATE_END)
        )
        val STAIRCASE_TEMPERATURES = listOf(0.25, 0.1, 0.04, 0.016)

        /** Widths and resolutions the `true loss really moves` question is asked at. */
        val REALITY_WIDTHS = listOf(0.05, 0.2, 0.5)
        val REALITY_DIMS = listOf(6, 24)

        val STEP = setOf(AdSurrogateFamily.STEP)
        val STAIRCASE = setOf(AdSurrogateFamily.STAIRCASE)

        const val FBM_PROGRAM =
            "uv 3 * fbm\n0.5 0.5 0.5 1 vec4\n0.5 0.5 0.5 1 vec4\n1.0 1.0 1.0 1 vec4\n0.0 0.33 0.67 1 vec4\npalette"

        /**
         * Programs whose primal must not move, with whether each contains a jump at all. The last
         * one is the **control**: it has no `floor`, `step` or `sign` anywhere, so the surrogate must
         * leave its *gradient* alone too, not merely its primal.
         *
         * Two of the three jump-bearing entries change a gradient at every temperature and the
         * third changes none, which is worth knowing rather than puzzling over: the
         * `… threshold orange 0.8 * saturate` program ends in its own `saturate`, so every pixel is
         * pinned by an *active clamp* downstream of the surrogated `step` and the whole gradient is
         * zero with the surrogate on or off. It is the same fact the landed
         * `StackSLGradientTest` records — the clamp that matters is whichever one is active — and it
         * is the program-level form of the fictitious-gradient caveat in this file's KDoc.
         */
        val PRIMAL_POPULATION = listOf(
            "uv x 0.45 threshold" to true,
            "uv 2.0 * sin 0.45 threshold orange 0.8 * saturate" to true,
            FBM_PROGRAM to true,
            "uv 3.0 * sin 0.5 + palette" to false
        )

        /**
         * The recovery corpus: nine walled wires over seven programs, six continuous and three
         * integer-valued, spanning `threshold`, `select`, `bit-crush`, `posterize` and a bare
         * `floor` quantiser — four routed through the STEP family and five through STAIRCASE.
         *
         * Each `star` is the ground truth the target was rendered at; each seed is the literal as
         * written. Chosen so the seed is 27–43% away relatively, which is far enough that standing
         * still is not a pass.
         */
        val WALLED = listOf(
            Walled("uv x 0.35 threshold", "0.35", 0.57, Kind.CONTINUOUS, STEP),
            Walled("uv y 0.60 threshold", "0.60", 0.82, Kind.CONTINUOUS, STEP),
            Walled("red blue uv x 0.40 threshold select", "0.40", 0.62, Kind.CONTINUOUS, STEP),
            Walled("uv x 2.0 * 0.30 + fract 0.5 threshold", "0.5", 0.72, Kind.CONTINUOUS, STEP),
            Walled("uv x 0.30 + 3.0 bit-crush", "0.30", 0.52, Kind.CONTINUOUS, STAIRCASE),
            Walled("uv x 0.30 + 6.0 posterize palette", "0.30", 0.52, Kind.CONTINUOUS, STAIRCASE),
            Walled("uv x 4.0 * floor 4.0 /", "4.0", 7.0, Kind.COUNT, STAIRCASE),
            Walled("uv x 0.30 + 3.0 bit-crush", "3.0", 5.0, Kind.COUNT, STAIRCASE),
            Walled("uv x 6.0 posterize palette", "6.0", 10.0, Kind.COUNT, STAIRCASE)
        )

        // -- floors, every one of them measured on this branch at $BASE = d6d164822 ---------------

        /** Measured: 325 documentable words, 295 probed, 154 pass exactly, 74 block, 67 refuse. */
        const val MIN_EXACT_BLOCKS = 70

        /**
         * Measured: the twelve words the surrogate reaches — ten by STEP, two by STAIRCASE, none by
         * SIGN. `all/and/any/not/or/band/if/select` reach it because they lower to a `step` call.
         */
        val UNBLOCKED_LEDGER = setOf(
            "all", "and", "any", "band", "ceil", "floor", "if", "not", "or", "select", "step", "threshold"
        )

        /**
         * Of those twelve, the two where the true loss does **not** move at any width or resolution:
         * both render an image pinned at 1.0, so the still template's `clamp(c, 0.0, 1.0)` has
         * already erased the jump the surrogate is standing on. Pinned separately because an
         * unblocked count that includes them overstates the result.
         */
        val FICTITIOUS_LEDGER = setOf("any", "ceil")

        /** Measured: 8 of 16 (program, temperature) pairs change a gradient component. */
        const val MIN_GRADIENT_CHANGED = 6

        const val CONTINUOUS_POPULATION = 6
        const val COUNT_POPULATION = 3

        /** Measured: seeds sit 0.268..0.429 away relatively. */
        const val MIN_SEED_RELATIVE_ERROR = 0.2
        const val MIN_SEED_LOSS = 1e-4

        /** Measured: worst continuous relative error 0.0215, worst improvement 14.8x. */
        const val CONTINUOUS_TOLERANCE = 0.05
        const val MIN_CONTINUOUS_IMPROVEMENT = 8.0

        /** Measured: worst count relative error 0.2197, worst improvement 1.8x — ten times worse. */
        const val COUNT_TOLERANCE = 0.30
        const val MIN_COUNT_IMPROVEMENT = 1.5

        /** Measured: 6 of 9 fits reach a loss of exactly zero. */
        const val MIN_LOSSES_TO_ZERO = 4

        /** Measured: 31 of 36 pairs agree in sign; 3 of the 5 misses are a vanished oracle. */
        const val MIN_SIGN_AGREEMENT = 0.75

        /**
         * Measured, excluding [NESTED_STEP_PROGRAM]: the magnitude ratio spans 0.077..2.177 where
         * both the surrogate and the oracle are non-zero.
         */
        const val MIN_MAGNITUDE_RATIO = 0.03
        const val MAX_MAGNITUDE_RATIO = 5.0

        /** The one program in the corpus whose `select` nests a `step` inside a `step`. */
        const val NESTED_STEP_PROGRAM = "red blue uv x 0.40 threshold select"

        /** Measured: the nested-step ratio falls 1.45e+11x from t=0.25 to t=0.016. */
        const val MIN_NESTED_COLLAPSE = 1e6

        /** Measured: the exact fbm frequency gradient is within 9.35e-3 of a central difference. */
        const val EXACT_FBM_TOLERANCE = 5e-2

        /** Measured: with STAIRCASE on, 2.49e+06 to 1.33e+08 relative — and the sign flips. */
        const val MIN_FBM_DEGRADATION = 1e5

        /**
         * M5, measured: worst continuous relative error by schedule — hot **0.0204**, warm 0.0264,
         * geometric **0.0215**, cold **0.3548**. Means 0.0110, 0.0110, 0.0146, 0.0683. So the
         * annealed schedule is 1.05x *worse* than fixed hot rather than better, and cold fails.
         *
         * [MAX_ANNEAL_ADVANTAGE] is the two-sided half: if annealing ever becomes clearly better
         * than a fixed hot temperature, the negative result on record is wrong and should be
         * rewritten rather than left standing.
         */
        const val MAX_ANNEAL_ADVANTAGE = 1.3
        const val MAX_ANNEAL_PENALTY = 2.0

        /** Measured: a fixed t=0.016 leaves the nested-predicate wire exactly where it started. */
        const val MIN_COLD_FAILURE = 0.2
    }
}
