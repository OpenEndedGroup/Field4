package afield.solidsl

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The differentiable reading, checked four independent ways.
 *
 * `SolidGradient.kt` claims an *exact* gradient. That claim is worth exactly as much as the evidence
 * behind it, and "it compiles" and "the fit converged" are both consistent with a systematically
 * wrong derivative — a descent on a wrong gradient still goes downhill on *something*. So:
 *
 *  1. **Against a numerical oracle.** Central differences over a census of every construct in the
 *     algebra, in both directions that matter (position, and each named parameter), at a floored
 *     tolerance. The oracle reports its own trustworthiness ([SolidGradientCheck.usable]) by
 *     disagreeing with itself between step `h` and `h/2`, and the test **asserts a floor on how many
 *     samples were usable** — because a check that quietly skipped everything reads as a pass, which
 *     is the trap `RectSLFnBackendTest` documents guarding against.
 *  2. **Against a second implementation.** Lowering (c)'s `.value` against lowering (a)'s
 *     [toScalarField], which shares no line of code with it. [SolidAgreement]'s own argument: two
 *     implementations that agree to five figures is much better evidence than any assertion against a
 *     remembered constant.
 *  3. **Against itself.** [SolidExpr.numericSlots] and [SolidExpr.toADField] are two different `when`
 *     blocks over thirty node kinds and they have to agree about what every path is *called*. They are
 *     asserted set-equal via [SolidNumerics.pathsAsked], so a node added to one and not the other
 *     fails here rather than presenting as a parameter that silently never moves.
 *  4. **Against a known answer.** A parametric solid perturbed away from the truth, fitted, and the
 *     improvement floored — with the counterfactual measured live in the same run.
 *
 * # The census asserts its own premise
 *
 * `constructCensus` is checked against `SolidExpr::class.sealedSubclasses`, not against a remembered
 * list — so adding a node to the algebra fails this test until the census covers it. That only works
 * while reflection over the sealed hierarchy works, so the test **asserts that reflection returned a
 * plausible hierarchy before using it**. Without that line, an environment where
 * `sealedSubclasses` came back empty would turn `assertEquals(algebra, covered)` into
 * `assertEquals(emptySet, emptySet)` and the whole ratchet into a tautology that passes forever.
 */
class SolidSLGradientTest {

    // ==========================================================================================
    // The census
    // ==========================================================================================

    /**
     * One expression per construct, each carrying at least one bindable number.
     *
     * @param skipFields slot *fields* excluded from the finite-difference check, each with a reason.
     *   Kept deliberately tiny and printed on every run: an exclusion list is the easiest place for a
     *   coverage claim to quietly rot, so it is a visible number rather than a comment.
     */
    private data class Subject(
        val name: String,
        val expr: SolidExpr,
        val skipFields: Map<String, String> = emptyMap(),
    )

    private val census: List<Subject> = listOf(
        Subject("sphere", sphere(0.8)),
        Subject("cuboid", cuboid(1.2, 0.9, 0.6)),
        Subject("cylinder", cylinder(1.4, 0.5)),
        Subject("frustum", cylinder(1.2, 0.62, 0.28)),
        Subject("extrude", Profile.rect(0.9, 0.7).extruded(1.1).at(0.0, 0.0, -0.55)),
        Subject(
            "twistedExtrude",
            Profile.star(5, 0.8, 0.42).extruded(1.0, twistDegrees = 55.0, scaleTop = 0.7).at(0.0, 0.0, -0.5),
        ),
        Subject("torus", torus(1.0, 0.32), skipFields = mapOf(
            "Revolve.degrees" to
                "a full revolve at exactly 360 branches to a different lowering than 359.99999, so a " +
                    "central difference straddles two implementations; the wedge subject parameterises " +
                    "it instead",
        )),
        Subject("revolveWedge", Profile.rect(0.5, 0.5).translated(1.0, 0.0).revolved(255.0)),
        Subject("gyroidSheet", gyroid(2.2, 0.28)),
        Subject("withEmpty", differenceOf(listOf(sphere(0.7), SolidExpr.Empty))),
        Subject("translate", cuboid(0.9, 0.7, 0.5).at(0.3, -0.2, 0.15)),
        Subject("rotate", cuboid(1.1, 0.6, 0.4).turned(21.0, 13.0, 34.0)),
        Subject("scale", sphere(0.6).sized(1.3, 0.85, 1.05)),
        Subject("mirror", cuboid(0.8, 0.5, 0.4).at(0.5, 0.2, 0.0).mirrored(1.0, 0.35, 0.0)),
        Subject("round", cuboid(0.9, 0.7, 0.5).rounded(0.14)),
        Subject("shell", sphere(0.75).shelled(0.11)),
        Subject("union", sphere(0.6) + cuboid(0.9, 0.5, 0.5).at(0.4, 0.0, 0.0)),
        Subject("intersect", intersectionOf(listOf(sphere(0.8), cuboid(1.1, 1.1, 0.9)))),
        Subject("difference", cuboid(1.2, 0.9, 0.6) - cylinder(1.6, 0.24).at(0.2, 0.1, 0.0)),
        Subject("smoothUnion", smoothUnion(sphere(0.55), cuboid(0.8, 0.5, 0.5).at(0.45, 0.0, 0.0), 0.22)),
        Subject("smoothDifference", smoothDifference(cuboid(1.1, 0.8, 0.6), sphere(0.5).at(0.4, 0.0, 0.3), 0.18)),
        // Profiles: everything the 2D half of the algebra can be.
        Subject("profileCircle", Profile.circle(0.55).extruded(0.9).at(0.0, 0.0, -0.45)),
        Subject("profileOffset", Profile.rect(0.7, 0.5).offset(0.16).extruded(0.8).at(0.0, 0.0, -0.4)),
        Subject("profileRotate", Profile.rect(0.9, 0.4).turned(27.0).extruded(0.7).at(0.0, 0.0, -0.35)),
        Subject("profileScale", Profile.circle(0.5).sized(1.4, 0.8).extruded(0.7).at(0.0, 0.0, -0.35)),
        Subject(
            "profileBooleans",
            Profile.difference(
                Profile.union(Profile.rect(1.0, 0.6), Profile.circle(0.36).translated(0.45, 0.0)),
                Profile.intersect(Profile.rect(0.5, 0.5), Profile.circle(0.3)),
            ).extruded(0.6).at(0.0, 0.0, -0.3),
        ),
        Subject(
            "profileEmpty",
            Profile.difference(Profile.rect(0.9, 0.6), Profile.Empty).extruded(0.6).at(0.0, 0.0, -0.3),
        ),
    )

    /** Where to sample a subject. A gyroid has no bounds of its own, so it gets a stated window. */
    private fun window(s: Subject) = s.expr.bounds()?.expandedBy(0.25)
        ?: afield.stdlib.AxisAlignedBoundingBox3D(
            afield.stdlib.Vec3(-1.6f, -1.6f, -1.6f),
            afield.stdlib.Vec3(1.6f, 1.6f, 1.6f),
        )

    // ==========================================================================================
    // 1. The coverage ratchet, premise first
    // ==========================================================================================

    @Test
    fun `the census covers every construct in the sealed algebra`() {
        // --- the premise, asserted before it is relied on -------------------------------------
        val algebra = SolidExpr::class.sealedSubclasses.mapNotNull { it.simpleName }.toSet()
        val profiles = Profile::class.sealedSubclasses.mapNotNull { it.simpleName }.toSet()
        assertTrue(
            algebra.size >= 18 && profiles.size >= 11,
            "PREMISE FAILED: reflection over the sealed hierarchies returned ${algebra.size} SolidExpr " +
                "and ${profiles.size} Profile subclasses, where the source declares at least 18 and 11. " +
                "Without a real hierarchy the coverage assertion below compares two empty sets and " +
                "passes forever, so this test refuses to run rather than becoming a tautology.",
        )

        val coveredSolids = census.flatMap { solidNodeNames(it.expr) }.toSet()
        val coveredProfiles = census.flatMap { profileNodeNames(it.expr) }.toSet()

        val solidFraction = coveredSolids.size.toDouble() / algebra.size
        val profileFraction = coveredProfiles.size.toDouble() / profiles.size
        println(
            "construct coverage: %d/%d SolidExpr (%.1f%%), %d/%d Profile (%.1f%%)".format(
                coveredSolids.size, algebra.size, 100 * solidFraction,
                coveredProfiles.size, profiles.size, 100 * profileFraction,
            ),
        )
        val skips = census.sumOf { it.skipFields.size }
        println("finite-difference exclusions: $skips")
        census.filter { it.skipFields.isNotEmpty() }.forEach { s ->
            s.skipFields.forEach { (f, why) -> println("  ${s.name}/$f — $why") }
        }

        assertEquals(
            algebra, coveredSolids,
            "the census does not exercise every SolidExpr node; missing ${algebra - coveredSolids}",
        )
        assertEquals(
            profiles, coveredProfiles,
            "the census does not exercise every Profile node; missing ${profiles - coveredProfiles}",
        )
        // The printed number, floored. 100% is the claim; 100% is what is asserted.
        assertTrue(solidFraction >= 1.0 && profileFraction >= 1.0, "coverage regressed below 100%")
        // And the exclusion list stays small enough to read. One entry today.
        assertTrue(skips <= 2, "the finite-difference exclusion list has grown to $skips entries")
    }

    // ==========================================================================================
    // 2. The internal-consistency check: two `when` blocks, one path vocabulary
    // ==========================================================================================

    @Test
    fun `numericSlots and toADField agree about every path`() {
        var total = 0
        census.forEach { s ->
            val declared = s.expr.numericSlots().map { it.path }.toSet()
            val tape = afield.stdlib.ADTape()
            val numerics = SolidNumerics(tape)
            s.expr.toADField(numerics)
            assertEquals(
                declared, numerics.pathsAsked,
                "${s.name}: numericSlots() and toADField() disagree about the path vocabulary. " +
                    "Declared-but-never-read: ${declared - numerics.pathsAsked}. " +
                    "Read-but-never-declared: ${numerics.pathsAsked - declared}. " +
                    "A path in the second set is a parameter a caller cannot bind; a path in the " +
                    "first is a binding the lowering would silently ignore.",
            )
            total += declared.size
        }
        println("path vocabulary: $total slots across ${census.size} subjects, both walks agreeing")
        assertTrue(total >= 60, "only $total slots in the census — it has lost its teeth")
    }

    // ==========================================================================================
    // 3. Value agreement: lowering (c) against lowering (a)
    // ==========================================================================================

    @Test
    fun `the differentiable reading agrees with the sampled field reading`() {
        var worstRel = 0.0
        var worstName = ""
        census.forEach { s ->
            val a = s.expr.gradientAgreement(count = 1200, seed = 11, bounds = window(s), margin = 0.0)
            assertEquals(0, a.nonFinite, "${s.name}: ${a.nonFinite} non-finite samples — ${a.report()}")
            if (a.maxRelativeError > worstRel) { worstRel = a.maxRelativeError; worstName = s.name }
            assertTrue(
                a.maxRelativeError < 1e-6,
                "${s.name}: lowering (c) and lowering (a) disagree — ${a.report()}. These share no " +
                    "code; a disagreement above Float's own resolution is a real bug in one of them.",
            )
        }
        println(
            ("value agreement across ${census.size} subjects: worst relative error %.3e (%s) — " +
                "Float's own resolution is ~1.2e-7").format(worstRel, worstName),
        )
        // Float has ~7 digits and toScalarField returns one, so the residual cannot be zero. Asserting
        // that it is *not* absurdly small guards against the check having been short-circuited.
        assertTrue(worstRel > 0.0, "a perfectly zero residual means the two readings are not both running")
    }

    // ==========================================================================================
    // 4. The finite-difference census
    // ==========================================================================================

    @Test
    fun `every gradient matches central differences`() {
        val rng = Random(20260827)
        var checkedParam = 0
        var usableParam = 0
        var checkedPos = 0
        var usablePos = 0
        var worstParam = 0.0
        var worstParamWhere = ""
        var worstPos = 0.0
        var worstPosWhere = ""

        println("%-18s %6s %13s %12s %11s %11s".format("subject", "params", "dParam usable", "dPos usable", "worst dP", "worst dX"))

        census.forEach { s ->
            val slots = s.expr.numericSlots().filter { "${it.node}.${it.field}" !in s.skipFields }
            if (slots.isEmpty()) return@forEach
            val template = s.expr.parameterize {
                slots.forEachIndexed { i, slot -> param("p$i", slot.path) }
            }.orThrow()

            val box = window(s)
            var subjectWorstParam = 0.0
            var subjectWorstPos = 0.0
            var subjectCheckedParam = 0
            var subjectUsableParam = 0
            var subjectCheckedPos = 0
            var subjectUsablePos = 0
            val samples = 24
            repeat(samples) {
                val x = (box.min.x + rng.nextDouble() * (box.max.x - box.min.x))
                val y = (box.min.y + rng.nextDouble() * (box.max.y - box.min.y))
                val z = (box.min.z + rng.nextDouble() * (box.max.z - box.min.z))

                val g = template.checkGradient(x, y, z)
                subjectCheckedParam += g.size
                subjectUsableParam += g.usableCount
                if (g.usable) {
                    if (g.maxError > subjectWorstParam) subjectWorstParam = g.maxError
                    assertTrue(
                        g.maxError < 2e-5,
                        "${s.name}: the parameter gradient disagrees with central differences at " +
                            "($x, $y, $z) — $g. Component ${g.worstComponent} is " +
                            "${slots.getOrNull(g.worstComponent)?.path}",
                    )
                }

                val p = template.checkPositionGradient(x, y, z)
                subjectCheckedPos += p.size
                subjectUsablePos += p.usableCount
                if (p.usable) {
                    if (p.maxError > subjectWorstPos) subjectWorstPos = p.maxError
                    assertTrue(
                        p.maxError < 2e-5,
                        "${s.name}: the position gradient disagrees with central differences at " +
                            "($x, $y, $z) — $p",
                    )
                }
            }
            checkedParam += subjectCheckedParam; usableParam += subjectUsableParam
            checkedPos += subjectCheckedPos; usablePos += subjectUsablePos
            if (subjectWorstParam > worstParam) { worstParam = subjectWorstParam; worstParamWhere = s.name }
            if (subjectWorstPos > worstPos) { worstPos = subjectWorstPos; worstPosWhere = s.name }
            println(
                "%-18s %6d %6d/%-6d %5d/%-6d %11.3e %11.3e".format(
                    s.name, template.size,
                    subjectUsableParam, subjectCheckedParam,
                    subjectUsablePos, subjectCheckedPos,
                    subjectWorstParam, subjectWorstPos,
                ),
            )
            // Per subject: the oracle has to have had *something* to say. A construct where every
            // component came back unusable contributed nothing and would vanish into the aggregate.
            assertTrue(
                subjectUsableParam >= subjectCheckedParam / 4,
                "${s.name}: only $subjectUsableParam of $subjectCheckedParam parameter-gradient " +
                    "components produced a trustworthy central difference, so this construct is " +
                    "effectively unchecked",
            )
            assertTrue(
                subjectUsablePos >= subjectCheckedPos / 4,
                "${s.name}: only $subjectUsablePos of $subjectCheckedPos position-gradient components " +
                    "produced a trustworthy central difference",
            )
        }

        val paramFraction = usableParam.toDouble() / checkedParam
        val posFraction = usablePos.toDouble() / checkedPos
        println(
            ("finite differences: %d/%d parameter-gradient components usable (%.1f%%), " +
                "%d/%d position (%.1f%%); worst floored relative error %.3e (%s) and %.3e (%s)").format(
                usableParam, checkedParam, 100 * paramFraction,
                usablePos, checkedPos, 100 * posFraction,
                worstParam, worstParamWhere, worstPos, worstPosWhere,
            ),
        )
        // The printed fractions, floored. Without these the whole test passes when the oracle
        // declares every single component untrustworthy, which is the failure mode that looks green.
        assertTrue(paramFraction > 0.70, "only ${"%.1f".format(100 * paramFraction)}% of parameter-gradient components were usable")
        assertTrue(posFraction > 0.70, "only ${"%.1f".format(100 * posFraction)}% of position-gradient components were usable")
        // And a worst error of exactly zero would mean the comparison never ran.
        assertTrue(worstParam > 0.0 && worstPos > 0.0, "a zero worst-case error means nothing was compared")
    }

    // ==========================================================================================
    // 5. The refusals
    // ==========================================================================================

    @Test
    fun `parameterisation refuses, with a reason, in every way it can`() {
        val body = cuboid(1.0, 1.0, 0.4) - cylinder(1.2, 0.3).at(0.3, 0.0, 0.0)

        val refusals = linkedMapOf<String, SolidLowering<*>>(
            "an unknown path" to body.parameterize { param("r", "Difference/0/Cuboid.w") },
            "no path at all" to body.parameterize { param("r") },
            "a predicate that matches nothing" to body.parameterize { param("r") { it.node == "Torus" } },
            "the same name twice" to body.parameterize {
                param("r", "Difference/0/Cuboid.x"); param("r", "Difference/0/Cuboid.y")
            },
            "two names for one slot" to body.parameterize {
                param("a", "Difference/0/Cuboid.x"); param("b", "Difference/0/Cuboid.x")
            },
            "tied slots with inconsistent seeds" to body.parameterize {
                param("size") { it.node == "Cuboid" }
            },
            "a singular Scale seed" to sphere(0.5).sized(1.0, 1.0, 1.0).parameterize {
                param("s", "Scale.x", seed = 1e-9)
            },
            "a degenerate smooth-blend k" to smoothUnion(sphere(0.4), sphere(0.4).at(0.5, 0.0, 0.0), 0.2)
                .parameterize { param("k", "SmoothUnion.k", seed = 0.0) },
            "no parameters at all" to body.parameterize { },
        )

        refusals.forEach { (what, l) ->
            val why = l.refusalOrNull()
            assertTrue(why != null, "$what should have been refused, and was not: $l")
            assertTrue(why.isNotBlank() && why.length > 30, "$what refused with an unhelpful reason: '$why'")
            println("refused ($what): $why")
        }

        // `segments` is not a refusal — it is not a slot at all, which is the stronger statement.
        val slots = sphere(0.5, segments = 64).numericSlots()
        assertEquals(listOf("Sphere.radius"), slots.map { it.path })
        assertTrue(
            slots.none { it.field == "segments" },
            "segments appeared as a bindable slot; it is an integer facet count that toScalarField " +
                "ignores entirely, so there is no derivative to take and it must not be offerable",
        )

        // And a legal binding is not refused, so the above is not just "everything refuses".
        val ok = body.parameterize { param("hole") { it.node == "Cylinder" && it.field == "radiusLow" } }
        assertTrue(ok is SolidLowering.Lowered, "a legal binding was refused: ${ok.refusalOrNull()}")
    }

    // ==========================================================================================
    // 6. Recovery — the counterfactual measured live
    // ==========================================================================================

    private val truthValues = BAR_AND_BALL_TRUTH

    @Test
    fun `a perturbed parametric solid recovers its target`() {
        val template = barAndBallTemplate()
        val truth = template.withValues(truthValues)
        val target = truth.sampleField(count = 2400, seed = 91)

        // Every parameter measurably far from the truth, and far enough that the shapes are visibly
        // different objects — see SolidSLInverseRenderProofTest, which renders exactly these three.
        val start = BAR_AND_BALL_SEED
        val fit = template.fitParameters(target, start)

        println(fit.report())
        println("  target        $truth")
        println("  target rms    ${"%.6f".format(target.rms)} over ${target.size} points, ${target.insideCount} inside")

        // Every number below is measured in this run. There is no remembered before-value: the seed
        // loss, the fitted loss and the ratio all come out of the same `fit`.
        assertTrue(fit.deadParameters.isEmpty(), "parameters could not move: ${fit.deadParameters}")
        assertTrue(
            fit.initialLoss > 1e-3,
            "the seed is not measurably wrong (loss ${fit.initialLoss}), so there is nothing to recover " +
                "from and the improvement factor below would be meaningless",
        )
        // The printed improvement is ~1e20 — an exact recovery, to the limit of the tape. The floor
        // is eight orders below that, which is still ten orders above "it got a bit better".
        assertTrue(
            fit.improvementFactor > 1e12,
            "the fit improved the loss only ${"%.4g".format(fit.improvementFactor)}x " +
                "(${fit.initialLoss} -> ${fit.loss})",
        )
        truthValues.indices.forEach { i ->
            val err = abs(fit.values[i] - truthValues[i])
            assertTrue(
                err < 1e-7,
                "${template.names[i]} recovered as ${fit.values[i]}, wanted ${truthValues[i]} (off by $err)",
            )
            // And the seed really was far away, so `err` is a recovery rather than a lucky start.
            // Relative, because the three parameters live on different scales — 0.15 absolute is
            // most of a radius and a twentieth of a height.
            assertTrue(
                abs(start[i] - truthValues[i]) / truthValues[i] > 0.30,
                "${template.names[i]} started only " +
                    "${"%.0f".format(100 * abs(start[i] - truthValues[i]) / truthValues[i])}% away",
            )
        }
        // The fitted expression is an ordinary SolidExpr again, not a bag of numbers — which is what
        // lets the next line hand it back to the other two lowerings.
        assertEquals(truth.nodeCount(), fit.fitted.nodeCount())
        assertEquals(emptyList<String>(), fit.fitted.fieldNotes())
    }

    /**
     * The second fitting problem, and the more interesting one: a seed at which one parameter is
     * **dead**, and comes back anyway.
     *
     * Start the bar fat and short and the ball small, and the ball is swallowed by more than the
     * blend radius `k`. [SolidExpr.SmoothUnion]'s `h` clamp saturates, the ball contributes nothing
     * to any sample, and `∂loss/∂sphereRadius` is exactly `0.0` — [SolidFit.deadParameters] says so
     * before the first step, which is the whole reason that field exists.
     *
     * A one-shot gradient method would stop there and report convergence. It does not, because the
     * *other* two parameters are alive: shrinking the bar towards its true size un-swallows the ball,
     * the blend comes off its clamp, and the dead parameter revives mid-descent. So the pathology is
     * real, it is detected, it is reported, and on this problem it is survivable — three separate
     * claims, all asserted here rather than described.
     */
    @Test
    fun `a parameter that starts dead inside a saturated blend still recovers`() {
        val template = barAndBallTemplate()
        val truth = template.withValues(truthValues)
        val target = truth.sampleField(count = 2400, seed = 91)

        val start = doubleArrayOf(0.28, 2.55, 0.62)
        val fit = template.fitParameters(target, start)
        println(fit.report())

        assertEquals(
            listOf("sphereRadius"), fit.deadParameters,
            "the swallowed ball was expected to have an exactly-zero gradient at this seed; if it does " +
                "not, either the blend no longer saturates or the dead-parameter detection is broken, " +
                "and both would make SolidInverse.kt's stated diagnosis wrong",
        )
        assertTrue(
            fit.improvementFactor > 1e12,
            "the descent did not revive the dead parameter: ${"%.4g".format(fit.improvementFactor)}x",
        )
        truthValues.indices.forEach { i ->
            assertTrue(
                abs(fit.values[i] - truthValues[i]) < 1e-7,
                "${template.names[i]} recovered as ${fit.values[i]}, wanted ${truthValues[i]}",
            )
        }
    }

    /**
     * A hard union really does starve a swallowed child, and a smooth one really does feed it.
     *
     * This is the claim `SolidInverse.kt` makes about why a fit stalls, asserted rather than
     * asserted-in-prose. The same geometry twice: a small sphere entirely inside a big one. Under
     * [SolidExpr.Union] the small sphere is nowhere nearest, so `min` routes every sample's derivative
     * to the big one and the small radius is dead. Under [smoothUnion] the blend gives it a share.
     */
    @Test
    fun `a swallowed child is dead under a hard union and alive under a smooth one`() {
        val inner = sphere(0.25)
        val outer = sphere(1.0)
        val target = outer.sampleField(count = 800, seed = 3)

        fun deadUnder(expr: SolidExpr, innerPath: String): List<String> {
            val t = expr.parameterize { param("inner", innerPath) }.orThrow()
            return t.fitParameters(target, doubleArrayOf(0.25), SolidFitOptions(iterations = 1)).deadParameters
        }

        val hard = deadUnder(unionOf(listOf(outer, inner)), "Union/1/Sphere.radius")
        val soft = deadUnder(smoothUnion(outer, inner, 0.9), "SmoothUnion/b/Sphere.radius")

        println("swallowed child: hard union -> dead=$hard, smooth union -> dead=$soft")
        assertEquals(
            listOf("inner"), hard,
            "the hard union did NOT starve its swallowed child, which contradicts SolidInverse.kt's " +
                "stated diagnosis of why a parameter refuses to move",
        )
        assertEquals(
            emptyList<String>(), soft,
            "the smooth union did not feed its swallowed child, which is the whole reason to prefer it " +
                "when a shape is going to be fitted",
        )
    }

    // ==========================================================================================
    // 7. Notes
    // ==========================================================================================

    @Test
    fun `gradient notes are stated for the constructs that have caveats, and withheld for Round`() {
        // Round is `f - r`: exact in value and in both derivatives. Listing it would dilute the list,
        // and this asserts that the omission is deliberate rather than forgotten.
        assertEquals(emptyList<String>(), cuboid(1.0, 1.0, 1.0).rounded(0.1).gradientNotes().filter { it.startsWith("Round") })
        assertTrue(cuboid(1.0, 1.0, 1.0).rounded(0.1).gradientNotes().any { it.startsWith("Cuboid") })

        assertTrue(sphere(1.0).gradientNotes().single().contains("singular"))
        assertTrue(smoothUnion(sphere(1.0), sphere(1.0), 0.2).gradientNotes().any { it.contains("C¹") })
        assertTrue(SolidExpr.Empty.gradientNotes().single().contains("exactly 0"))

        // Every construct in the census says *something* about itself, or is Round.
        census.forEach { s ->
            assertTrue(
                s.expr.gradientNotes().isNotEmpty(),
                "${s.name} produced no gradient notes at all, which for anything but a bare Round is " +
                    "an omission rather than a clean bill of health",
            )
        }
    }

    // ==========================================================================================
    // Walkers, exhaustive on purpose: adding a node breaks compilation here
    // ==========================================================================================

    private fun solidNodeNames(e: SolidExpr): List<String> =
        listOf(e.nodeName()) + e.children().flatMap { solidNodeNames(it) }

    private fun profileNodeNames(e: SolidExpr): List<String> = when (e) {
        is SolidExpr.Extrude -> names(e.profile)
        is SolidExpr.Revolve -> names(e.profile)
        else -> emptyList()
    } + e.children().flatMap { profileNodeNames(it) }

    private fun names(p: Profile): List<String> = listOf(p.nodeName()) + when (p) {
        is Profile.Circle, is Profile.Rect, is Profile.Polygon, Profile.Empty -> emptyList()
        is Profile.Offset -> names(p.child)
        is Profile.Translate -> names(p.child)
        is Profile.Rotate -> names(p.child)
        is Profile.Scale -> names(p.child)
        is Profile.Union -> p.children.flatMap { names(it) }
        is Profile.Intersect -> p.children.flatMap { names(it) }
        is Profile.Difference -> p.children.flatMap { names(it) }
    }
}

// ==============================================================================================
// The fixture the fitting tests and the render proof share
// ==============================================================================================

/**
 * A ball blended onto a rod: three named numbers, one of them driving two slots.
 *
 * Shared between `SolidSLGradientTest` and `SolidSLInverseRenderProofTest` rather than written twice,
 * because the render proof's whole job is to show the pictures of *the fit the other file measures*.
 * Two copies that drifted apart would leave a contact sheet illustrating a different experiment from
 * the one whose numbers are asserted, which is the quiet version of a faked capture.
 */
internal fun barAndBallTemplate(): SolidTemplate =
    smoothUnion(sphere(0.45), cylinder(1.90, 0.16).at(0.0, 0.0, 0.0), 0.18).parameterize {
        param("sphereRadius", "SmoothUnion/a/Sphere.radius")
        param("barHeight", "SmoothUnion/b/Translate/Cylinder.height")
        // One name, both radii — the symmetry constraint that says "the bar is a cylinder, not a
        // frustum", written into the parameterisation rather than hoped for.
        param("barRadius") { it.node == "Cylinder" && it.field.startsWith("radius") }
    }.orThrow()

/** What [barAndBallTemplate] actually is: a thin rod through a ball. */
internal val BAR_AND_BALL_TRUTH = doubleArrayOf(0.45, 1.90, 0.16)

/**
 * Where the fits start: a fat short capsule, wrong in every parameter by 42%, 45% and 112%, and
 * contained inside the target's bounding box on every axis — which is what lets the render proof pin
 * its camera to the target's own corners.
 */
internal val BAR_AND_BALL_SEED = doubleArrayOf(0.26, 1.05, 0.34)
