package afield.brief

import afield.stdlib.ceres.AxisSet
import afield.stdlib.ceres.CeresExpressionResidual
import afield.stdlib.ceres.Colinear
import afield.stdlib.ceres.DistanceAtLeast
import afield.stdlib.ceres.DistanceAtMost
import afield.stdlib.ceres.DistanceEquals
import afield.stdlib.ceres.EqualDistance
import afield.stdlib.ceres.LineParallel
import afield.stdlib.ceres.LockPosition
import afield.stdlib.ceres.MirrorAcrossPlane
import afield.stdlib.ceres.PanelArea
import afield.stdlib.ceres.Planar
import afield.stdlib.ceres.PointInHalfspace
import afield.stdlib.ceres.PointOnPlane
import afield.stdlib.ceres.RobustLoss
import afield.stdlib.ceres.SameCoordinate
import afield.stdlib.linalg.Vector3f
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Brief without a solver: parse, resolve, diagnose, lower.
 *
 * Everything here runs in `./gradlew check` because none of it needs the native library — that is the
 * point of `compileBrief` being a pure function of text. What the *solved answers* mean is checked
 * separately and analytically by `afield.examples.brief.BriefSolveSmoke`, behind `smokeBrief`, because
 * a JUnit test that skips when an optional native library is missing is a test that is silently green.
 *
 * The lowering assertions here name the underlying `GeometryConstraint` types rather than re-deriving
 * geometry, because that is the actual claim of the package: a brief statement *is* a
 * `ConstraintScope` call, and the weight it carries is the weight the constraint gets.
 */
class BriefTest {

    private fun constraintsOf(source: String, bindings: Map<String, Vector3f> = emptyMap()) =
        compileBrief(source).toGeometryModel(bindings).program.constraints

    // -----------------------------------------------------------------------------------------
    // Weight
    // -----------------------------------------------------------------------------------------

    @Test
    fun theStrengthLadderIsFourNamesForFourNumbers() {
        assertEquals(
            listOf("whisper" to 1e-3, "prefer" to 1.0, "insist" to 1e3, "demand" to 1e6),
            BriefStrength.entries.map { it.word to it.weight },
            "the ladder is documented in dev_docs/brief_notation.md and in BriefStrength's KDoc; " +
                "changing a rung changes every brief ever written"
        )
    }

    @Test
    fun aLadderWordAndItsNumberProduceTheSameConstraint() {
        val body = """
            point a at (0, 0, 0)
            point b at (1, 0, 0)
            line spine from a to b
        """.trimIndent()
        val word = constraintsOf("$body\ninsist spine.length = 4")
            .filterIsInstance<DistanceEquals>().single()
        val number = constraintsOf("$body\n1000 spine.length = 4")
            .filterIsInstance<DistanceEquals>().single()
        assertEquals(word, number, "'insist' has to be exactly the number it claims to be")
        assertEquals(1e3, word.weight)
        assertEquals(4.0, word.distance)
    }

    @Test
    fun anUnqualifiedStatementIsPreferExceptWhereTheKotlinDslDisagrees() {
        val body = "point a at (0, 0, 0)\npoint b at (1, 0, 0)\nline spine from a to b\n"
        assertEquals(
            1.0,
            constraintsOf("$body\nspine.length = 4").filterIsInstance<DistanceEquals>().single().weight,
            "a bare statement is 'prefer'"
        )
        // `fix` is `lock`, whose own default is 1e6, and `hold` is `weak`, whose default is 1e-6. A bare
        // `fix` at weight 1 would not hold anything, which is the one place a uniform default is worse.
        assertEquals(
            1e6,
            constraintsOf("${body}fix a").filterIsInstance<LockPosition>().first().weight,
            "a bare 'fix' has to mean what ConstraintScope.lock means"
        )
        assertEquals(
            1e-6,
            constraintsOf("${body}hold a")
                .filterIsInstance<afield.stdlib.ceres.WeakPosition>()
                .first { it.points.none { p -> p.value < 0 } }.weight,
            "a bare 'hold' has to mean what ConstraintScope.weak means"
        )
    }

    @Test
    fun aBriefIsPlainLeastSquaresUnlessItSaysOtherwise() {
        val body = "point a at (0, 0, 0)\npoint b at (1, 0, 0)\nline spine from a to b\nspine.length = 4\n"
        assertEquals(
            RobustLoss.NONE, compileBrief(body).toGeometryModel().program.loss,
            "Huber(1e-3) makes two weights decide a fight outright instead of splitting it, which " +
                "would make the strength ladder a lie"
        )
        assertEquals(
            RobustLoss(RobustLoss.Kind.HUBER, 0.5),
            compileBrief("${body}robust huber 0.5").toGeometryModel().program.loss
        )
        assertEquals(
            RobustLoss.Kind.SOFT_L_ONE,
            compileBrief("${body}robust soft-l1 2").toGeometryModel().program.loss.kind
        )
    }

    // -----------------------------------------------------------------------------------------
    // Forward reference
    // -----------------------------------------------------------------------------------------

    @Test
    fun aRelationMayCiteGeometryDeclaredBelowIt() {
        val program = compileBrief(
            """
            #brief
            insist spine.length = 4
            prefer roof planar

            point a at (0, 0, 0)
            point b at (4, 0, 0)
            point c at (4, 3, 0)
            point d at (0, 3, 1)
            line spine from a to b
            panel roof corners a b c d
            """.trimIndent()
        )
        assertTrue(program.inventedPoints.isEmpty(), "nothing needed inventing: ${program.inventedPoints}")
        val constraints = program.toGeometryModel().program.constraints
        assertEquals(4.0, constraints.filterIsInstance<DistanceEquals>().single().distance)
        assertEquals(4, constraints.filterIsInstance<Planar>().single().points.size)
    }

    @Test
    fun aPointNamedByARelationAndNothingElseIsInventedOnAGoldenAngleSpiral() {
        val program = compileBrief(
            """
            #brief
            line spine from hinge to tip
            insist spine.length = 4
            """.trimIndent()
        )
        assertEquals(listOf("hinge", "tip"), program.inventedPoints)
        val seeds = program.pointNames.map { program.seeds.getValue(it) }
        // Distinct, non-degenerate and not at the origin: a coincident pile is a rank-deficient
        // Jacobian, which is the whole reason the seeding is not "all at (0,0,0)".
        assertEquals(2, seeds.distinct().size)
        seeds.forEach { assertTrue(abs(it.x) + abs(it.y) > 0.1, "invented seed too near the origin: $it") }
        assertTrue(
            (seeds[0].x - seeds[1].x) * (seeds[0].x - seeds[1].x) +
                (seeds[0].y - seeds[1].y) * (seeds[0].y - seeds[1].y) > 0.5,
            "invented seeds must be well separated, got $seeds"
        )
    }

    @Test
    fun theSeedingIsDeterministic() {
        val source = "line spine from hinge to tip\ninsist spine.length = 4"
        assertEquals(
            compileBrief(source).seeds, compileBrief(source).seeds,
            "an invented seed decides which of several answers a non-convex brief lands on, so it " +
                "cannot depend on iteration order"
        )
    }

    // -----------------------------------------------------------------------------------------
    // The two kinds of relation
    // -----------------------------------------------------------------------------------------

    @Test
    fun aPropertyRelationAndAnElementRelationBothParse() {
        val program = compileBrief(
            """
            #brief
            point a at (0, 0, 0)
            point b at (4, 0, 0)
            point c at (0, 3, 0)
            point d at (4, 3, 0)
            line west from a to c
            line east from b to d
            insist west.length = east.length
            insist west parallel east
            """.trimIndent()
        )
        val kinds = program.toGeometryModel().program.constraints.map { it::class.simpleName }
        assertTrue("EqualDistance" in kinds, "the property relation lowered: $kinds")
        assertTrue("LineParallel" in kinds, "the element relation lowered: $kinds")
    }

    @Test
    fun comparisonsChooseTheBarrierConstraints() {
        val body = "point a at (0, 0, 0)\npoint b at (1, 0, 0)\nline spine from a to b\n"
        assertEquals(1, constraintsOf("${body}spine.length >= 2").filterIsInstance<DistanceAtLeast>().size)
        assertEquals(1, constraintsOf("${body}spine.length <= 9").filterIsInstance<DistanceAtMost>().size)
        // A literal on the left flips the comparison rather than being refused, because "2 <= x" and
        // "x >= 2" are the same sentence and a notation that accepts one should accept both.
        assertEquals(1, constraintsOf("${body}2 <= spine.length").filterIsInstance<DistanceAtLeast>().size)
    }

    @Test
    fun everyRelationInTheVocabularyLowersToItsOwnConstraint() {
        val source = """
            #brief
            point a at (0, 0, 0)
            point b at (4, 0, 0)
            point c at (4, 3, 0)
            point d at (0, 3, 0)
            point e at (2, 1, 2)
            point f at (2, 5, 2)
            point g at (8, 0, 0)
            line spine  from a to b
            line ridge  from c to d
            line mast   from e to f
            plane floor through a b c
            panel roof  corners a b c d
            insist spine.length = 4
            insist spine.length = ridge.length
            insist roof.area = 12
            insist roof planar
            insist a b c d planar
            insist a b g colinear
            insist spine parallel ridge
            insist spine perpendicular mast
            insist spine same-vector ridge
            insist angle(spine, mast) = 30deg
            insist cos-angle(spine, mast) = 0.5
            insist dot(spine, mast) = 0.25
            insist raw-dot(spine, mast) = 6
            insist dihedral(spine, c, e) = -1
            insist mast along (0, 0, 1)
            insist mast toward g
            insist ridge vanishes-at g
            insist floor faces (0, 0, 1)
            insist e on floor offset 2
            insist e inside floor clearance 0.5
            insist e mirrors f across floor
            insist a coincident e
            insist a b c level
            insist a b same-x
            insist e at 1/3 along (a, b)
            insist distance(a, g) = 8
            demand fix a in xy
            whisper hold e
            whisper all loose
            insist all above-ground
        """.trimIndent()
        val program = compileBrief(source)
        val constraints = program.toGeometryModel().program.constraints
        val present = constraints.map { it::class.simpleName!! }.toSet()
        // One representative per family, so a lowering that silently dropped a statement fails here
        // rather than in a demo.
        listOf(
            "DistanceEquals", "EqualDistance", "PanelArea", "Planar", "Colinear", "LineParallel",
            "LineAngle", "EqualEdgeVector", "VectorDot", "DihedralAngle", "LineDirection",
            "LineTowardPoint", "PlaneNormalDirection", "PointOnPlane", "PointInHalfspace",
            "MirrorAcrossPlane", "SameCoordinate", "CeresExpressionResidual", "LockPosition",
            "WeakPosition", "NoNegativeZ",
        ).forEach {
            assertTrue(it in present, "no $it in the lowered program; got ${present.sorted()}")
        }
        assertTrue(
            program.warnings.none { it.severity == BriefSeverity.ERROR },
            "the vocabulary sample must compile clean: ${program.diagnostics}"
        )
    }

    @Test
    fun degreesAreConvertedAndRawRadiansAreNot() {
        val body = """
            point a at (0, 0, 0)
            point b at (4, 0, 0)
            point c at (0, 3, 0)
            point d at (3, 3, 0)
            line spine from a to b
            line ridge from c to d
        """.trimIndent()
        val degrees = constraintsOf("$body\ninsist angle(spine, ridge) = 60deg")
            .filterIsInstance<afield.stdlib.ceres.LineAngle>().single()
        assertEquals(0.5, degrees.targetCos, 1e-12, "60 degrees is a cosine of a half")
        val radians = constraintsOf("$body\ninsist angle(spine, ridge) = 1.0471975511965976")
            .filterIsInstance<afield.stdlib.ceres.LineAngle>().single()
        assertEquals(degrees.targetCos, radians.targetCos, 1e-12, "pi/3 is the same angle")
        assertEquals(PI / 3.0, 1.0471975511965976, 1e-15)
    }

    @Test
    fun aFractionReadsAsADivision() {
        val program = compileBrief(
            """
            point a at (0, 0, 0)
            point b at (9, 0, 0)
            point s at (1, 0, 0)
            insist s at 1/3 along (a, b)
            """.trimIndent()
        )
        val residual = program.toGeometryModel().program.constraints
            .filterIsInstance<CeresExpressionResidual>().single()
        assertEquals(3, residual.expressions.size, "an affine point relation is three residuals")
        assertEquals(1e3, residual.weight, "and it carries the strength it was written with")
    }

    @Test
    fun anAxisTargetBecomesAnAnchoredSameCoordinate() {
        val program = compileBrief("point a at (0, 0, 5)\ninsist a.z = 2")
        // The mechanism, stated because it is visible in the point list: no opcode ties a coordinate to
        // a number, so a hidden point is locked at the number and the subject is tied to it.
        assertEquals(2, program.pointNames.size, "one hidden anchor: ${program.pointNames}")
        assertTrue(program.pointNames[1].startsWith("_at:a.z:"))
        val constraints = program.toGeometryModel().program.constraints
        val same = constraints.filterIsInstance<SameCoordinate>().single()
        assertEquals(AxisSet.Z, same.axes)
        assertEquals(1e3, same.weight, "the statement's strength lands on the relation, not the anchor")
        assertTrue(
            constraints.filterIsInstance<LockPosition>().all { it.weight == 1e9 },
            "the anchor is held three decades above 'demand' so it is never the thing that loses"
        )
    }

    // -----------------------------------------------------------------------------------------
    // Diagnostics
    // -----------------------------------------------------------------------------------------

    private fun errors(source: String): List<BriefDiagnostic> =
        checkBrief(source).filter { it.severity == BriefSeverity.ERROR }

    @Test
    fun aMisspelledNameIsLocatedAndGuessedAt() {
        val diagnostics = errors(
            """
            #brief
            point a at (0, 0, 0)
            point b at (1, 0, 0)
            line spine from a to b
            insist spien.length = 4
            """.trimIndent()
        )
        val first = diagnostics.singleOrNull() ?: fail("expected one error, got $diagnostics")
        assertEquals(5, first.line)
        assertEquals(8, first.column, "the column has to point at the name, not at the line")
        assertEquals(5, first.span)
        assertTrue("spien" in first.message)
        assertEquals("did you mean 'spine'?", first.hint)
    }

    @Test
    fun aDidYouMeanIsNotOfferedForSomethingUnrelated() {
        val diagnostics = errors("point a at (0, 0, 0)\ninsist wombat.length = 4")
        val first = diagnostics.first()
        assertTrue(
            first.hint?.startsWith("did you mean") != true,
            "an edit distance that suggests anything for anything is worse than silence: ${first.hint}"
        )
        assertTrue(first.hint?.contains("declare it") == true, "it should say how to declare one")
    }

    @Test
    fun everyErrorIsReportedNotJustTheFirst() {
        val diagnostics = errors(
            """
            point a at (0, 0, 0)
            insist alpha.length = 4
            insist beta.length = 4
            insist gamma.length = 4
            """.trimIndent()
        )
        assertEquals(3, diagnostics.size, "a brief with three misspellings should report three: $diagnostics")
        assertEquals(listOf(2, 3, 4), diagnostics.map { it.line })
    }

    @Test
    fun usingTheWrongKindOfElementSaysWhichKindItIs() {
        val diagnostics = errors(
            """
            point a at (0, 0, 0)
            point b at (1, 0, 0)
            point c at (0, 1, 0)
            plane floor through a b c
            insist floor.length = 4
            """.trimIndent()
        )
        val first = diagnostics.first()
        assertTrue("plane" in first.message && "line" in first.message, first.format())
    }

    @Test
    fun twoInsistedNumbersForOneThingIsAnError() {
        val diagnostics = errors(
            """
            point a at (0, 0, 0)
            point b at (1, 0, 0)
            line spine from a to b
            insist spine.length = 4
            demand spine.length = 6
            """.trimIndent()
        )
        val first = diagnostics.singleOrNull() ?: fail("expected one error, got $diagnostics")
        assertTrue("4" in first.message && "6" in first.message, first.format())
        assertTrue(first.hint?.contains("4, 5") == true, "it should name both lines: ${first.hint}")
    }

    @Test
    fun aWeakDisagreementIsNotAnError() {
        // The check is narrow on purpose: a least-squares system is *supposed* to absorb disagreement,
        // and flagging every opposed pair would make the notation unusable.
        val diagnostics = errors(
            """
            point a at (0, 0, 0)
            point b at (1, 0, 0)
            line spine from a to b
            prefer spine.length = 4
            insist spine.length = 6
            """.trimIndent()
        )
        assertTrue(diagnostics.isEmpty(), "a prefer against an insist is a compromise, not a contradiction")
    }

    @Test
    fun aKeywordCannotBeAName() {
        val diagnostics = errors("point length at (0, 0, 0)")
        assertTrue("keyword" in diagnostics.first().message, diagnostics.first().format())
    }

    @Test
    fun nonsenseIsRefusedWithSomethingReadable() {
        listOf(
            "point a at (0, 0, 0)\ninsist a wobbles b" to "not a brief relation",
            "point a at (0, 0, 0)\ninsist a.height = 4" to "not a brief property",
            "point a at (0, 0, 0)\nline spine from a to a" to "two different points",
            "point a at (0, 0, 0)\npoint a at (1, 0, 0)" to "already declared",
            "point a at (0, 0, 0)\npoint b at (1, 0, 0)\nline s from a to b\ninsist s.length = -4"
                to "cannot be negative",
            "point a at (0, 0, 0)\npoint b at (1, 0, 0)\nline s from a to b\n" +
                "line t from b to a\ninsist cos-angle(s, t) = 4" to "between -1 and 1",
            "point a at (0, 0, 0)\ninsist a at 1/3 along (a, a)" to "three different points",
            "point a at (0, 0, 0)\ninsist a b planar" to "at least three points",
            "point a at (0, 0, 0)\nsettle wobble" to "expected a prior weight",
            "point a at (0, 0, 0)\nrobust wobble" to "not a robust loss",
        ).forEach { (source, expected) ->
            val diagnostics = errors(source)
            assertTrue(
                diagnostics.any { expected in it.message || expected in (it.hint ?: "") },
                "'$source' should have complained about '$expected', said: $diagnostics"
            )
        }
    }

    @Test
    fun aWarningIsNotAnError() {
        val program = compileBrief(
            """
            point a at (0, 0, 0)
            point b at (1, 0, 0)
            point c at (0, 1, 0)
            insist a b c planar
            """.trimIndent()
        )
        val warning = program.warnings.singleOrNull() ?: fail("expected one warning: ${program.diagnostics}")
        assertTrue("always coplanar" in warning.message, warning.format())
    }

    @Test
    fun equatingTwoAreasWithASharedCornerIsRefusedWithTheReason() {
        val diagnostics = errors(
            """
            point a at (0, 0, 0)
            point b at (2, 0, 0)
            point c at (2, 2, 0)
            point d at (0, 2, 0)
            point e at (4, 0, 0)
            point f at (4, 2, 0)
            panel one corners a b c d
            panel two corners b e f c
            prefer one.area = two.area
            """.trimIndent()
        )
        val first = diagnostics.firstOrNull() ?: fail("expected an error")
        assertTrue("share the corner" in first.message, first.format())
        assertTrue("b" in first.message && "c" in first.message, first.format())
        assertTrue("name a point twice" in (first.hint ?: ""), "it should say why: ${first.hint}")
    }

    // -----------------------------------------------------------------------------------------
    // The host seam
    // -----------------------------------------------------------------------------------------

    @Test
    fun aBoundNameBecomesABoundPoint() {
        val hinge = Vector3f(1f, 2f, 3f)
        val model = compileBrief("point hinge at (0, 0, 0)\npoint tip at (4, 0, 0)\ninsist distance(hinge, tip) = 2")
            .toGeometryModel(mapOf("hinge" to hinge))
        assertEquals(1, model.bindings.size)
        // A bound point is seeded from the vector, not from the `at` in the brief: the host value is the
        // live one, and the literal is only what the brief says when nothing is bound.
        assertEquals(1.0, model.initialValue(model.bindings.single().point).x, 1e-6)
    }

    @Test
    fun bindingANameTheBriefDoesNotHaveIsRefusedByName() {
        val e = runCatching {
            compileBrief("point a at (0, 0, 0)").toGeometryModel(mapOf("b" to Vector3f()))
        }.exceptionOrNull()
        assertNotNull(e)
        assertTrue("no point(s) named b" in (e.message ?: ""), e.message ?: "")
        assertTrue("it declares a" in (e.message ?: ""), "it should list what there is: ${e.message}")
    }

    // -----------------------------------------------------------------------------------------
    // Shape
    // -----------------------------------------------------------------------------------------

    @Test
    fun theTagLineAndCommentsAreIgnoredButStillCounted() {
        val program = compileBrief(
            """
            #brief
            # a two-bay wall
            point a at (0, 0, 0)   # the west end
            point b at (4, 0, 0)
            line spine from a to b
            insist spine.length = 4
            """.trimIndent()
        )
        assertEquals(1, program.constraintCount)
        // The line number is the line in the source as handed over, tag included, so a column can be
        // trusted against an editor that is showing the same string.
        assertEquals(6, program.syntax.constraints.single().at.line)
    }

    @Test
    fun aStatementLabelReadsBackAsTheStatement() {
        val program = compileBrief(
            """
            point a at (0, 0, 0)
            point b at (4, 0, 0)
            point c at (0, 3, 0)
            line spine from a to b
            insist spine.length = 4
            demand fix a in xy
            whisper all loose
            prefer a b c level
            """.trimIndent()
        )
        assertEquals(
            listOf("insist spine.length = 4", "demand fix a in xy", "whisper all loose", "prefer a b c level"),
            program.statementLabels,
            "a residual report names statements by these, so they have to read like what was written"
        )
    }
}
