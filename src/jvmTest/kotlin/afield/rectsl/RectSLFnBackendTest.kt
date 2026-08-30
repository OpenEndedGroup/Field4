package afield.rectsl

import afield.stdlib.Functions
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Does the differentiable backend compute the same animation the CPU sampler does?
 *
 * The oracle here is the best one available anywhere in this project. `ddf732260` shipped
 * `src/jvmTest/resources/rectsl/track_characterization.txt` — every sampled rect of all 34 gallery
 * programs at 41 times each, to full double precision — and it was **generated from pre-refactor
 * code**. So it is 37k lines of ground truth with no code path in common with anything written
 * tonight, or indeed with the symbolic IR at all.
 *
 * It is 64,490 lines since 2026-08-30, and the two halves are not equally good evidence. The
 * original 34 programs' rows are byte-identical to `ddf732260`'s — regenerating for the four
 * `musicality-*` programs `0d875cc63` added changed no existing line — so for those the oracle is
 * still code that no longer exists. The 27,433 new rows came from today's [SceneProgram.sample],
 * which shares no code path with the symbolic IR either, but is not independent of a sampler bug
 * introduced after `ddf732260`. Worth knowing which row a disagreement is in before concluding
 * what it proves.
 *
 * The comparison is arranged so that only one thing is under test. [walkScene] is a transcription
 * of [SceneProgram.sample]'s group/rect composition, because that composition has to happen
 * somewhere and the Fn backend is scalar-only. A transcription is exactly the kind of evidence that
 * quietly rots, so [theSceneWalkerIsAFaithfulTranscription] runs [walkScene] with the *original*
 * tracks and demands the result match the golden **bit for bit**. Once that passes, a disagreement
 * in the differential test belongs to the lowering and to nothing else.
 *
 * Coverage is reported alongside the error, and asserted, because a differential test that refused
 * every track would otherwise be a green no-op.
 */
class RectSLFnBackendTest {

    private val galleryDir: Path = repoRoot().resolve("public/docs/assets/rectsl_gallery")

    private val channelNames = listOf(
        "center.x", "center.y", "size.x", "size.y", "rotation", "scale.x", "scale.y", "alpha", "z"
    )

    @Test
    fun theSceneWalkerIsAFaithfulTranscription() {
        val golden = readGolden()
        assertTrue(golden.rows.isNotEmpty(), "the golden should not be empty")
        var compared = 0
        for ((program, rows) in golden.rows) {
            val compiled = compile(program) ?: continue
            val mine = walkScene(compiled) { track, t -> track.sample(t) }
            for (row in rows) {
                val actual = assertNotNull(
                    mine[row.id to row.time],
                    "$program/${row.id}@${row.time} missing from the walker"
                )
                for (channel in channelNames.indices) {
                    val got = assertNotNull(
                        actual[channel],
                        "$program/${row.id}@${row.time} ${channelNames[channel]} was declined by " +
                            "the CPU sampler, which cannot happen"
                    )
                    assertEquals(
                        row.values[channel].toRawBits(),
                        got.toRawBits(),
                        "$program/${row.id}@${row.time} ${channelNames[channel]}: " +
                            "golden=${row.values[channel]} walker=$got"
                    )
                    compared++
                }
            }
        }
        println("scene-walker transcription: $compared channel values bit-identical to the golden")
        assertTrue(compared > 100_000, "expected the whole golden, compared only $compared values")
    }

    @Test
    fun theFnBackendReproducesThePreRefactorGolden() {
        val golden = readGolden()
        var worstAbsolute = 0.0
        var worstRelative = 0.0
        var worstWhere = "(nothing compared)"
        var comparedValues = 0
        var skippedValues = 0
        var skippedRows = 0
        var comparedRows = 0
        val refusals = LinkedHashMap<String, Int>()
        val perProgram = LinkedHashMap<String, Coverage>()
        // The two directions of the classification, collected as the offending tracks rather than
        // as counts, so a failure says which track and not merely how many.
        val refusedWithoutAPattern = mutableListOf<String>()
        val patternsThatLowered = mutableListOf<String>()
        val reasonsOtherThanAPattern = LinkedHashMap<String, Int>()

        for ((program, rows) in golden.rows) {
            val compiled = compile(program) ?: continue
            val window = Functions.Domain(0.0, (golden.duration[program] ?: 1.0) * 1.25)
            val cache = HashMap<Track<Double>, RectSLFnLowering<Functions.Fn>>()
            fun lower(track: Track<Double>): RectSLFnLowering<Functions.Fn> =
                cache.getOrPut(track) { track.toFn(window) }

            var lowered = 0
            var refused = 0
            var patternTracks = 0
            for (track in scalarTracksOf(compiled)) {
                val isPattern = track.dependsOnPatternTrack()
                if (isPattern) patternTracks++
                when (val r = lower(track)) {
                    is RectSLFnLowering.Lowered -> {
                        lowered++
                        if (isPattern) {
                            patternsThatLowered += "$program: ${track.describe().take(110)}"
                        }
                    }
                    is RectSLFnLowering.Refused -> {
                        refused++
                        refusals.merge(r.why.take(70), 1, Int::plus)
                        if (r.why != RECTSL_PATTERN_REFUSAL) {
                            reasonsOtherThanAPattern.merge(r.why, 1, Int::plus)
                        }
                        if (!isPattern) {
                            refusedWithoutAPattern +=
                                "$program: ${track.describe().take(110)} — ${r.why.take(110)}"
                        }
                    }
                }
            }

            // Availability is per channel, not per rect: a `loop-time` on a rotation should not
            // cost us the comparison of that rect's size, alpha and z as well. The one place it is
            // still all-or-nothing is a parent group's transform, which mixes every channel of
            // every descendant.
            val mine = walkScene(compiled) { track, t ->
                (lower(track) as? RectSLFnLowering.Lowered)?.value?.eval(t)
            }

            var valuesHere = 0
            for (row in rows) {
                val actual = mine[row.id to row.time]
                var any = false
                for (channel in channelNames.indices) {
                    val expected = row.values[channel]
                    val got = actual?.get(channel)
                    if (got == null) {
                        skippedValues++
                        continue
                    }
                    any = true
                    if (!expected.isFinite() && !got.isFinite()) continue
                    comparedValues++
                    valuesHere++
                    val delta = abs(expected - got)
                    val relative = delta / (1.0 + abs(expected))
                    if (relative > worstRelative || relative.isNaN()) {
                        worstRelative = relative
                        worstAbsolute = delta
                        worstWhere = "$program/${row.id}@${row.time} ${channelNames[channel]}: " +
                            "golden=$expected fn=$got"
                    }
                }
                if (any) comparedRows++ else skippedRows++
            }
            perProgram[program] = Coverage(lowered, refused, valuesHere, patternTracks)
        }

        val totalLowered = perProgram.values.sumOf { it.lowered }
        val totalRefused = perProgram.values.sumOf { it.refused }
        val totalPatterns = perProgram.values.sumOf { it.patternTracks }
        println("=== RectSL Fn backend vs the pre-refactor golden ===")
        println("programs:        ${perProgram.size}")
        println(
            "tracks:          $totalLowered lowered, $totalRefused refused (" +
                "%.1f".format(100.0 * totalLowered / (totalLowered + totalRefused)) + "% lowered)"
        )
        println("rows:            $comparedRows partly compared, $skippedRows skipped entirely")
        println("channel values:  $comparedValues compared, $skippedValues declined")
        println("worst relative:  $worstRelative")
        println("worst absolute:  $worstAbsolute")
        println("worst at:        $worstWhere")
        println("pattern tracks:  $totalPatterns of ${totalLowered + totalRefused}")
        println("refusal reasons:")
        for ((why, count) in refusals.entries.sortedByDescending { it.value }) {
            println("  ${count.toString().padStart(5)}  $why")
        }
        println("per program (tracks lowered/total, pattern tracks, channel values compared):")
        for ((program, counts) in perProgram) {
            println(
                "  ${program.padEnd(28)} tracks ${counts.lowered}/${counts.lowered + counts.refused}" +
                    "   patterns ${counts.patternTracks}   values ${counts.values}"
            )
        }
        val silent = perProgram.filterValues { it.values == 0 }.keys
        println("programs contributing nothing: ${if (silent.isEmpty()) "none" else silent.joinToString()}")

        // Coverage first: a refusal-only run must not read as a pass.
        assertTrue(perProgram.size >= 30, "expected the whole gallery, got ${perProgram.size}")
        assertTrue(
            comparedRows > 20_000,
            "the differential test only reached $comparedRows rows — coverage collapsed"
        )
        assertTrue(
            perProgram.values.all { it.values > 0 },
            "every gallery program should contribute at least one comparable channel; these did " +
                "not: " + perProgram.filterValues { it.values == 0 }.keys
        )

        // ---- the ratchet -----------------------------------------------------------------------
        // The lowering rate above was printed and not asserted for as long as it was interesting,
        // which is the wrong way round: a printed figure nobody checks is a figure that can quietly
        // fall over. On 2026-08-19 the rate reached 100% — `HeadingTrack` was the last closable
        // refusal and it was 116 of the 2754 tracks the gallery then had — so the floor was set at
        // *zero refusals*, with a comment predicting the day a pattern track would arrive and
        // reach it.
        //
        // That day was `0d875cc63` (2026-08-22), which added the four `musicality-*` gallery
        // examples, and the decision the comment asked for was taken on 2026-08-30 with the run's
        // own refusal table in front of it: 4993 of 5020 tracks lowered, 27 refused, and **every
        // one of the 27** gave the pattern reason. So the shape of the honest floor is not a lower
        // number. It is the same guarantee, stated per class:
        //
        //  1. **A pattern is the only thing that may be refused.** Nothing else — an easing with no
        //     `Fn`, a degenerate keyed track, a `MOD` — is permitted at any count. The 2026-08-19
        //     result survives intact for everything that has a differentiable form; what changed is
        //     that the corpus now also contains things that do not.
        //  2. **Refused and pattern-built are the same set, both ways round.** Checked with
        //     [dependsOnPatternTrack], which decides it from the track, not from the sentence. One
        //     direction stops the refusal widening — a future `sine` filed under the pattern reason
        //     would be caught even though its reason matched. The other stops it narrowing into
        //     nothing: if the backend ever "lowered" a `pat`, it would be answering with a
        //     plausible number for an interpreter it cannot see inside, and that is worse than
        //     refusing. This is what keeps the class from being a skip list with extra steps.
        //  3. **The class has to be non-empty.** A predicate that matched nothing would satisfy 1
        //     and 2 vacuously, and would then be indistinguishable from having deleted the
        //     assertion — which is exactly the outcome this is written to avoid.
        //  4. **Coverage floors, so the concession cannot spread by attrition.** A pattern on a
        //     *group* transform costs every channel of every descendant, so the cheapest way to
        //     lose most of this differential without failing 1-3 is to write more of them. The
        //     absolute counts below are the fence.
        //
        // A pattern has no bound to be held to, unlike the GLSL/SDF emitter's bakes, which were
        // classified the same way on the same commit and *can* be measured against a sampled
        // reconstruction. Here there is no reconstruction: the refusal is total, so membership of
        // the class is the whole of what there is to check, and 2 is what makes checking it worth
        // anything.
        assertTrue(
            reasonsOtherThanAPattern.isEmpty(),
            "the Fn backend lowered $totalLowered of ${totalLowered + totalRefused} gallery tracks " +
                "and every refusal is supposed to be a pattern track; these are not: " +
                reasonsOtherThanAPattern.entries.joinToString("; ") { "${it.value}x ${it.key}" }
        )
        assertTrue(
            refusedWithoutAPattern.isEmpty(),
            "these tracks were refused but have no pattern track under them, so the permanent " +
                "refusal class has widened: " + refusedWithoutAPattern.take(5)
        )
        assertTrue(
            patternsThatLowered.isEmpty(),
            "these tracks are built over a pattern and the backend claimed to lower them anyway, " +
                "which would mean answering for an interpreter it cannot see inside: " +
                patternsThatLowered.take(5)
        )
        // Non-vacuity, and the counts. 27 of 5020 on 2026-08-30, and where they are is the useful
        // part, because it is not where "the rhythm scenes refuse" would predict:
        //
        //  - 16 in `musicality-signals`, whose whole subject is signals read as channel values —
        //    `sine`, `tri`, `saw`, `perlin`, `hold-pat`, `pat-range`, `->degrade`.
        //  - 7 in `musicality-four-to-the-floor`, all from one `->every`: the mutator wraps *every*
        //    scalar track of the rect it selects in a `CycleSwitchTrack`, so one word costs seven
        //    channels.
        //  - 4 in `musicality-surface`, which is a catalogue of forty-odd pattern operators and
        //    still refuses only its four signal-word rects — `cosine`, `isaw`, `square`, `rand`,
        //    one x channel each.
        //  - **None** in `musicality-variation`, which spends six `pat` verbs and lowers all 1096
        //    of its tracks.
        //
        // What that says: `pat` as *scene structure* is unrolled at compile time into one gated
        // rect per event, so no interpreter survives into the sampled IR at all. What survives is a
        // pattern still being asked a value of at sample time. So a refusal count here is not "how
        // much rhythm is in the gallery" — it is how much of it is still a function when the
        // sampler arrives, which is a much smaller thing and the only thing a differentiable
        // backend could ever have minded.
        assertTrue(totalPatterns > 0, "no gallery track depends on a pattern; assertions 1-2 are vacuous")
        // Implied by the three assertions above, and kept as a cross-check on this test's own
        // bookkeeping rather than on the backend: the two lists are built in different branches of
        // the same `when`, and a mistake there would make all three read as a pass.
        assertEquals(
            totalPatterns,
            totalRefused,
            "$totalPatterns pattern-built tracks but $totalRefused refusals, which the three " +
                "assertions above say is impossible — so this test's own counting is wrong"
        )
        assertTrue(
            totalLowered >= 4_990,
            "the Fn backend lowered only $totalLowered gallery tracks; it lowered 4993 on 2026-08-30"
        )
        assertTrue(
            comparedValues >= 578_000,
            "the differential comparison covered $comparedValues channel values; it covered " +
                "578059 on 2026-08-30 (333207 on 2026-08-19, before the gallery grew)"
        )

        // Then agreement. The lowering is exact arithmetic, not an approximation, so the only error
        // permitted is float rounding: a telescoping sum of key deltas, `pow(x, 0.5)` where the
        // sampler calls `sqrt`, and the odd re-association. A heading widens that budget on purpose:
        // it divides nothing, but it *differences* two positions a 240th of a second apart, so an
        // absolute error of an ulp in a position is a relative error ~1e-14 in the velocity and
        // therefore in the angle. Measured worst on 2026-08-19: 7.7e-14, in a rotation channel.
        assertTrue(worstRelative < 1e-12, "worst relative disagreement $worstRelative at $worstWhere")
    }

    @Test
    fun everyLoweredEasingAgreesWithItsOwnClosure() {
        val grid = (0..1000).map { it / 1000.0 }
        var worst = 0.0
        var worstAt = "(none)"
        for ((name, lowering) in RECTSL_EASING_FNS) {
            val closure = assertNotNull(
                RECTSL_EASINGS[name],
                "'$name' has a lowering but is not a real RectSL easing"
            )
            val fn = lowering(with(Functions) { identity(Functions.Domain.ALL) })
            for (u in grid) {
                val delta = abs(closure(u) - fn.eval(u))
                if (delta > worst) {
                    worst = delta
                    worstAt = "$name at u=$u: closure=${closure(u)} fn=${fn.eval(u)}"
                }
            }
            // The clamped-ramp sum only telescopes when a segment shape is 0 at 0 and 1 at 1.
            assertTrue(abs(fn.eval(0.0)) < 1e-12, "$name should be 0 at u=0, was ${fn.eval(0.0)}")
            assertTrue(
                abs(fn.eval(1.0) - 1.0) < 1e-12,
                "$name should be 1 at u=1, was ${fn.eval(1.0)}"
            )
        }
        println("easings lowered: ${RECTSL_EASING_FNS.size} of ${RECTSL_EASINGS.size}")
        println("worst easing disagreement: $worst ($worstAt)")
        assertTrue(worst < 1e-12, "worst easing disagreement $worst at $worstAt")
    }

    @Test
    fun everyRefusalSaysWhyAndWhetherItIsPermanent() {
        // The op-level refusal that remains — one, since 2026-08-19. Everything else in the four
        // enums lowers.
        val refusals = listOf<RectSLFnLowering<*>>(TrackOp2.MOD.fn)
        for (refusal in refusals) {
            val r = refusal as RectSLFnLowering.Refused
            assertTrue(r.why.length > 40, "a refusal should explain itself: '${r.why}'")
            // The closable/permanent split is only worth having if the closable ones say what they
            // need — otherwise it is a label rather than a plan.
            if (r.closable) {
                assertTrue(r.missing.length > 30, "declared closable but vague: '${r.missing}'")
            } else {
                assertEquals("", r.missing, "declared permanent but names something missing")
            }
        }

        // Everything that is not `MOD` lowers. Stated as a whole-enum sweep rather than as a list,
        // so adding an op to any of them forces a decision here.
        for (op in TrackOp1.entries) {
            assertTrue(op.fn.valueOrNull() != null, "$op should lower")
        }
        for (op in TrackOp2.entries - setOf(TrackOp2.MOD)) {
            assertTrue(op.fn.valueOrNull() != null, "$op should lower")
        }
        for (op in TrackOp3.entries) {
            assertTrue(op.fn.valueOrNull() != null, "$op should lower")
        }
        for (warp in TimeWarpKind.entries) {
            assertTrue(warp.fn.valueOrNull() != null, "$warp should lower")
        }

        // A refused op still refuses the whole track, and the message still names the op.
        val refused = BinaryDoubleTrack(
            LinearScalarTrack(listOf(TcbKey(0.0, 0.0), TcbKey(1.0, 4.0))),
            LinearScalarTrack(listOf(TcbKey(0.0, 1.0), TcbKey(1.0, 2.0))),
            TrackOp2.MOD
        )
        val why = refused.toFn(Functions.Domain(0.0, 1.0)).refusalOrNull()
        assertTrue(why != null && "MOD" in why, "the refusal should name MOD: $why")
    }

    /**
     * The two near-zero guards, on operands that are **not** statically constant — which is the case
     * that used to be refused, and the case whose obvious lowering is wrong.
     *
     * `[|b| > 1e-12] · (a/b)` still divides on the branch it means to suppress, so it is NaN rather
     * than 0. [fnGuardedQuotient] guards the divisor instead. Both fixtures are arranged so the
     * divisor (and the edge gap) passes *exactly through zero* at a sample on the grid, and the
     * numerator is negative there, so a lowering that got the sign of the suppressed zero wrong
     * would fail too — `RectSLSimplify`'s rule table is explicit that a stray `-0.0` here is a real
     * bug, not a curiosity.
     *
     * Asserted **bit for bit**, because both of these are transcriptions rather than
     * re-associations: there is nothing here that rounding is allowed to move.
     */
    @Test
    fun theNearZeroGuardsLowerExactlyOnNonConstantOperands() {
        val window = Functions.Domain(0.0, 2.0)
        val grid = (0..2000).map { it / 1000.0 }

        // b crosses zero exactly at t = 1.0, which is on the grid; a is negative throughout, so the
        // suppressed branch is the one that would produce -0.0 under a naive mask.
        val a = LinearScalarTrack(listOf(TcbKey(0.0, -1.0), TcbKey(2.0, -5.0)))
        val b = LinearScalarTrack(listOf(TcbKey(0.0, 1.0), TcbKey(2.0, -1.0)))
        val quotient = BinaryDoubleTrack(a, b, TrackOp2.DIV)
        val quotientFn = assertNotNull(
            quotient.toFn(window).valueOrNull(),
            "a non-constant divisor should lower now: ${quotient.toFn(window).refusalOrNull()}"
        )
        var suppressed = 0
        for (t in grid) {
            val expected = quotient.sample(t)
            if (expected == 0.0) suppressed++
            assertEquals(
                expected.toRawBits(),
                quotientFn.eval(t).toRawBits(),
                "guarded quotient at t=$t: sampler=$expected lowered=${quotientFn.eval(t)}"
            )
        }
        assertTrue(suppressed > 0, "the fixture never reached the guard — it proves nothing")
        // And the suppressed value really is a *positive* zero, in both.
        assertEquals(0.0.toRawBits(), quotient.sample(1.0).toRawBits(), "the sampler's guard is +0.0")
        assertEquals(0.0.toRawBits(), quotientFn.eval(1.0).toRawBits(), "so the lowering's must be")

        // The edges coincide exactly at t = 1.0, where the sampler's smoothstep returns 0.
        val low = LinearScalarTrack(listOf(TcbKey(0.0, 0.0), TcbKey(2.0, 2.0)))
        val high = LinearScalarTrack(listOf(TcbKey(0.0, 2.0), TcbKey(2.0, 0.0)))
        // `x` is constant on purpose: the keyed lowering is a telescoping sum, which is exact
        // arithmetic but not the sampler's bit-for-bit spelling at a key time, and this test is
        // about the guard rather than about that (already-measured, ~1e-16) difference.
        val x = constantTrack(1.7)
        val smooth = TernaryDoubleTrack(low, high, x, TrackOp3.SMOOTHSTEP)
        val smoothFn = assertNotNull(
            smooth.toFn(window).valueOrNull(),
            "non-constant edges should lower now: ${smooth.toFn(window).refusalOrNull()}"
        )
        var coincident = 0
        for (t in grid) {
            val expected = smooth.sample(t)
            if (kotlin.math.abs(high.sample(t) - low.sample(t)) <= 1e-12) coincident++
            assertEquals(
                expected.toRawBits(),
                smoothFn.eval(t).toRawBits(),
                "guarded smoothstep at t=$t: sampler=$expected lowered=${smoothFn.eval(t)}"
            )
        }
        assertTrue(coincident > 0, "the fixture never reached the coincident-edge guard")
        println(
            "guarded ops: quotient suppressed at $suppressed of ${grid.size} samples, " +
                "smoothstep edges coincident at $coincident — all bit-identical"
        )
    }

    /**
     * The gradient of a guarded op, against a central finite difference, on the live branch.
     *
     * The value being exact says nothing about the derivative: the guard is built out of a `floor`,
     * whose adjoint is zero, so the *whole* question is whether the surviving path is `d(a/b)` and
     * not `d(a/b · something)`. Sampled away from the crossing, where the function really is
     * differentiable.
     */
    @Test
    fun theGuardedQuotientsGradientAgreesWithACentralDifference() {
        val clock = LinearScalarTrack(listOf(TcbKey(0.0, 0.0), TcbKey(2.0, 2.0)))
        val numerator = BinaryDoubleTrack(clock, FreeParamTrack("gain", 1.7), TrackOp2.MUL)
        val divisor = BinaryDoubleTrack(clock, constantTrack(3.0), TrackOp2.ADD)
        val quotient = BinaryDoubleTrack(numerator, divisor, TrackOp2.DIV)
        val worst = quotient.gradientAgreement(
            (1..8).map { it * 0.2 },
            over = Functions.Domain(0.0, 2.0)
        )
        println("guarded quotient gradient vs central difference: worst relative $worst")
        assertTrue(worst < 1e-6, "the guarded quotient's gradient disagrees by $worst")
    }

    /**
     * `fnStepFrom` is the primitive three lowerings now rest on (a gate, a hold, a folded clock), and
     * the only thing that makes it correct rather than approximate is that its boundary falls on the
     * right side. Checked at the boundary itself and at every tread either side, not merely nearby.
     */
    @Test
    fun theExactStepIsExactIncludingAtItsOwnBoundary() = with(Functions) {
        val step = fnStepFrom(fnTime())
        for (x in listOf(-3.0, -2.5, -1.0, -0.5, -1e-15, 0.0, 1e-15, 0.5, 1.0, 2.5, 3.0, 1e9)) {
            val expected = if (x >= 0.0) 1.0 else 0.0
            assertEquals(expected, step.eval(x), 0.0, "step at x=$x")
        }
        // And -0.0, which is >= 0 and must therefore read as 1.
        assertEquals(1.0, step.eval(-0.0), 0.0, "step at -0.0")
    }

    @Test
    fun aWrappingWarpNowLowersAcrossTheWrap() {
        val source = LinearScalarTrack(listOf(TcbKey(0.0, 0.0), TcbKey(2.0, 10.0)))
        for (warp in listOf(TimeWarpKind.LOOP, TimeWarpKind.PINGPONG)) {
            val warped = TimeWarpTrack(source, warp, 2.0)
            val fn = assertNotNull(
                warped.toFn(Functions.Domain(0.0, 9.0)).valueOrNull(),
                "$warp should lower now that Fn has a floor"
            )
            var worst = 0.0
            // Nine seconds is four and a half periods, so every wrap and every fold is crossed.
            for (i in 0..900) {
                val t = i / 100.0
                worst = maxOf(worst, abs(warped.sample(t) - fn.eval(t)))
            }
            println("$warp across 4.5 periods: worst absolute disagreement $worst")
            assertTrue(worst < 1e-12, "$warp disagreed by $worst")
        }
    }

    @Test
    fun aGateAndAHoldLowerExactly() {
        val gate = GateTrack(1.25, 3.75)
        val gateFn = assertNotNull(gate.toFn(Functions.Domain(0.0, 5.0)).valueOrNull())
        // Includes both edges exactly: a gate is `[lo, hi)`, closed below and open above.
        for (t in listOf(0.0, 1.24999, 1.25, 2.0, 3.74999, 3.75, 4.0)) {
            assertEquals(gate.sample(t), gateFn.eval(t), 0.0, "gate at t=$t")
        }
        val hold = HoldScalarTrack(
            listOf(TcbKey(0.0, 0.2), TcbKey(2.0, 1.0), TcbKey(4.0, 0.45))
        )
        val holdFn = assertNotNull(hold.toFn(Functions.Domain(0.0, 6.0)).valueOrNull())
        var worst = 0.0
        for (i in -100..600) {
            val t = i / 100.0
            worst = maxOf(worst, abs(hold.sample(t) - holdFn.eval(t)))
        }
        println("hold track: worst absolute disagreement $worst")
        assertTrue(worst < 1e-15, "hold disagreed by $worst")
    }

    @Test
    fun aTrackTreePrintsAsItsAlgebra() {
        val track = BinaryDoubleTrack(
            LinearScalarTrack(listOf(TcbKey(0.0, 0.0), TcbKey(2.0, 4.0))),
            MappedDoubleTrack(FreeParamTrack("wobble", 0.5), TrackOp1.SIN),
            TrackOp2.ADD
        )
        assertEquals("ADD(linear[0.0=0.0 2.0=4.0], SIN(?wobble=0.5))", track.describe())
        // A constant subtree folds rather than printing its own arithmetic.
        val folded = BinaryDoubleTrack(constantTrack(2.0), constantTrack(3.0), TrackOp2.MUL)
        assertEquals("k(6.0)", folded.describe())
    }

    // -----------------------------------------------------------------------------------------
    // the corpus
    // -----------------------------------------------------------------------------------------

    /** One program's share of the differential: how much lowered, how much of it is a pattern. */
    private data class Coverage(
        val lowered: Int,
        val refused: Int,
        val values: Int,
        val patternTracks: Int
    )

    private data class Row(val time: Double, val id: String, val values: DoubleArray)

    private data class Golden(
        val rows: Map<String, List<Row>>,
        val duration: Map<String, Double>
    )

    private fun readGolden(): Golden {
        val text = javaClass.getResource("/rectsl/track_characterization.txt")?.readText()
            ?: error("the characterization golden is not on the classpath")
        val rows = LinkedHashMap<String, MutableList<Row>>()
        val durations = LinkedHashMap<String, Double>()
        for (line in text.lineSequence()) {
            if (line.isBlank()) continue
            if (line.startsWith("#")) {
                // "# name  duration=6.0 fps=30.0 nodes=13", or a "does not compile" note.
                val name = line.removePrefix("#").trim().substringBefore(" ").removeSuffix(":")
                Regex("duration=([0-9.eE+-]+)").find(line)?.let {
                    durations[name] = it.groupValues[1].toDouble()
                }
                continue
            }
            val parts = line.split('\t')
            if (parts.size < 13) continue
            rows.getOrPut(parts[0]) { mutableListOf() } += Row(
                time = parts[1].toDouble(),
                id = parts[2],
                values = DoubleArray(9) { parts[3 + it].toDouble() }
            )
        }
        return Golden(rows, durations)
    }

    private val compiledPrograms = HashMap<String, SceneProgram?>()

    private fun compile(program: String): SceneProgram? = compiledPrograms.getOrPut(program) {
        val source = galleryDir.resolve("${program}_rectsl_.rectsl")
        if (!source.exists()) null else RectAnimationOracle.compile(source.readText()).programOrNull
    }

    /**
     * [SceneProgram.sample]'s composition, transcribed so a channel can be evaluated by something
     * other than [Track.sample], and so an individual channel can *decline* to answer.
     *
     * Keyed by rect id and time, which is how the golden addresses a value; the nine entries are in
     * [channelNames] order and a null one means "no differentiable form for this channel". Held
     * honest by [theSceneWalkerIsAFaithfulTranscription], which runs it with [Track.sample] — where
     * nothing declines — and requires bit-equality with the golden.
     */
    private fun walkScene(
        program: SceneProgram,
        channel: (Track<Double>, Double) -> Double?
    ): Map<Pair<String, Double>, Array<Double?>> {
        val nodeMap = program.nodes.associateBy { it.id }
        val childIds = program.nodes.filterIsInstance<GroupNode>().flatMap { it.children }.toSet()
        val roots = program.nodes.filter { it.id !in childIds }
        val out = HashMap<Pair<String, Double>, Array<Double?>>()

        /**
         * A group's own transform, all six channels or nothing.
         *
         * A group is the one place granularity has to be given up: `then` rotates and scales its
         * child's translation, so a declined group rotation genuinely makes every descendant
         * channel unknown.
         */
        fun groupTransform(node: GroupNode, t: Double): Transform2d? {
            val tx = channel(node.x, t) ?: return null
            val ty = channel(node.y, t) ?: return null
            val r = channel(node.rotation, t) ?: return null
            val sx = channel(node.scaleX, t) ?: return null
            val sy = channel(node.scaleY, t) ?: return null
            val a = channel(node.alpha, t) ?: return null
            return Transform2d(Vec2d(tx, ty), r, Vec2d(sx, sy), a)
        }

        fun visit(node: Node, t: Double, parent: Transform2d) {
            when (node) {
                is RectNode -> {
                    // Each output channel is composed from exactly the parts it needs, using
                    // `then` itself for the position so the arithmetic is not re-derived: a
                    // neutral child transform carrying only the translation composes to the same
                    // world position, bit for bit, as the full one would.
                    val lx = channel(node.x, t)
                    val ly = channel(node.y, t)
                    val position = if (lx == null || ly == null) {
                        null
                    } else {
                        (parent then Transform2d(Vec2d(lx, ly), 0.0, Vec2d(1.0, 1.0), 1.0)).translation
                    }
                    val rotation = channel(node.rotation, t)?.let { parent.rotation + it }
                    val scaleX = channel(node.scaleX, t)?.let { parent.scale.x * it }
                    val scaleY = channel(node.scaleY, t)?.let { parent.scale.y * it }
                    val alpha = channel(node.alpha, t)?.let { (parent.alpha * it).coerceIn(0.0, 1.0) }
                    out[node.id to t] = arrayOf(
                        position?.x, position?.y,
                        channel(node.w, t), channel(node.h, t),
                        rotation,
                        scaleX, scaleY,
                        alpha,
                        channel(node.z, t)
                    )
                }
                is GroupNode -> {
                    val local = groupTransform(node, t) ?: return
                    val world = parent then local
                    node.children.mapNotNull { nodeMap[it] }.forEach { visit(it, t, world) }
                }
            }
        }

        val span = (if (program.duration > 0.0) program.duration else 1.0) * 1.25
        for (i in 0..40) {
            val t = i * span / 40.0
            roots.forEach { visit(it, t, Transform2d.IDENTITY) }
        }
        return out
    }

    /** Every distinct scalar track a program's nodes feed the sampler, group transforms included. */
    private fun scalarTracksOf(program: SceneProgram): Set<Track<Double>> {
        val out = LinkedHashSet<Track<Double>>()
        for (node in program.nodes) {
            out += when (node) {
                is RectNode -> listOf(
                    node.x, node.y, node.w, node.h, node.rotation,
                    node.scaleX, node.scaleY, node.alpha, node.z
                )
                is GroupNode -> listOf(
                    node.x, node.y, node.rotation, node.scaleX, node.scaleY, node.alpha
                )
            }
        }
        return out
    }

    private fun repoRoot(): Path {
        var dir = Path.of("").toAbsolutePath()
        while (!dir.resolve("settings.gradle.kts").exists()) {
            dir = dir.parent ?: error("could not locate repo root")
        }
        return dir
    }
}
