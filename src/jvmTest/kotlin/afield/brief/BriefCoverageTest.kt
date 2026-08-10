package afield.brief

import afield.stdlib.ceres.ConstraintScope
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.full.extensionReceiverParameter
import kotlin.reflect.full.valueParameters
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The ratchet: what Kotlin can say about a constraint, against what Brief can say.
 *
 * The whole premise of `afield.brief` is that the notation reaches
 * `afield.stdlib.ceres.ConstraintScope`'s vocabulary. A premise like that decays the moment somebody
 * adds a verb to `ConstraintScope` and not to the notation, and it decays *silently*, because nothing
 * breaks — the Kotlin caller is fine and the brief simply cannot express the new thing. So the
 * classification below is compared to the class by reflection in **both** directions:
 *
 * - every member of `ConstraintScope` must appear in [REACH], so adding one fails this test until it
 *   has been *classified* — which is a decision, not a chore: either the notation reaches it, or there
 *   is a stated reason it does not;
 * - every row of [REACH] must still be a member, so a deleted verb cannot leave a row claiming
 *   coverage of something that no longer exists;
 * - every verb, function and property the notation defines ([BriefVerbs]) must appear in some row's
 *   syntax, so a construct that lowers to nothing is caught from the other side;
 * - and the covered *fraction* has a floor, close enough to the real figure to mean something.
 *
 * This is shape 1 of `dev_docs/small_language_anatomy.md`'s coverage-guard patterns — an exact set,
 * checked both ways — with a percentage floor bolted on because the set is partitioned rather than
 * total. A floor of "more than half" would prove nothing, so it sits two points under the truth.
 */
class BriefCoverageTest {

    /** Why a `ConstraintScope` member is or is not reachable from a brief. */
    private enum class Reach {
        /** The notation has syntax for it. */
        NOTATION,

        /** It only takes a Kotlin value, so a brief reaches it through `toGeometryModel(bindings)`. */
        HOST_ONLY,

        /** The notation deliberately lowers this relation a different way. The reason is the row. */
        SUPERSEDED,

        /** Not a relation: a declaration helper, an operator, or an escape hatch. */
        PLUMBING,
    }

    private data class Row(val member: String, val reach: Reach, val syntax: String)

    private companion object {

        /**
         * The claim, member by member. `member` is the reflected key: receiver, name, parameter types.
         *
         * `syntax` is a real brief fragment for a `NOTATION` row and the reason otherwise. Keeping the
         * fragments here rather than in prose is what lets the reverse check work: a verb with no row
         * mentioning it is a verb nothing claims to lower.
         */
        val REACH: List<Row> = listOf(
            // --- relations the notation has syntax for -----------------------------------------
            Row("LineRef.length(Double, Double)", Reach.NOTATION, "insist spine.length = 4"),
            Row("LineRef.minLength(Double, Double)", Reach.NOTATION, "prefer spine.length >= 2"),
            Row("LineRef.maxLength(Double, Double)", Reach.NOTATION, "prefer spine.length <= 9"),
            Row("LineRef.sameLengthAs(LineRef, Double)", Reach.NOTATION, "prefer west.length = east.length"),
            Row("LineRef.sameVectorAs(LineRef, Double)", Reach.NOTATION, "prefer west same-vector east"),
            Row("LineRef.parallelTo(LineRef, Double)", Reach.NOTATION, "prefer spine parallel ridge"),
            Row("LineRef.perpendicularTo(LineRef, Double)", Reach.NOTATION, "prefer spine perpendicular ridge"),
            Row("LineRef.angleCosTo(LineRef, Double, Double)", Reach.NOTATION, "prefer cos-angle(spine, ridge) = 0.5"),
            Row("LineRef.angleRadiansTo(LineRef, Double, Double)", Reach.NOTATION, "prefer angle(spine, ridge) = 30deg"),
            Row("LineRef.direction(Vec3, Double)", Reach.NOTATION, "insist mast along (0, 0, 1)"),
            Row("LineRef.toward(PointRef, Double)", Reach.NOTATION, "prefer rafter toward apex"),
            Row("LineRef.vanishesAt(PointRef, Double)", Reach.NOTATION, "prefer rafter vanishes-at apex"),
            Row("LineRef.dot(LineRef)", Reach.NOTATION, "prefer dot(spine, ridge) = 0.5"),
            Row("DotExpr.raw()", Reach.NOTATION, "prefer raw-dot(spine, ridge) = 6"),
            Row("DotExpr.ceq(Number)", Reach.NOTATION, "prefer dot(spine, ridge) = 0.5"),
            Row("PanelRef.area(Double, Double)", Reach.NOTATION, "insist roof.area = 12"),
            Row("PanelRef.planar(Double)", Reach.NOTATION, "insist roof planar"),
            Row("Iterable.planar(Double)", Reach.NOTATION, "insist a b c d planar"),
            Row("PointRef.on(PlaneRef, Double, Double)", Reach.NOTATION, "insist apex on floor offset 3"),
            Row("PointRef.inside(PlaneRef, Double, Double)", Reach.NOTATION, "insist apex inside floor clearance 0.2"),
            Row("PointRef.lock(AxisSet, Double)", Reach.NOTATION, "demand fix west in xy"),
            Row("lock(Iterable, AxisSet, Double)", Reach.NOTATION, "demand fix west east"),
            Row("PointRef.weak(AxisSet, Double)", Reach.NOTATION, "whisper hold apex"),
            Row("PlaneRef.normal(Vec3, Double, Double)", Reach.NOTATION, "insist floor faces (0, 0, 1)"),
            Row("dihedral(LineRef, PointRef, PointRef, Double, Double)", Reach.NOTATION, "prefer dihedral(eaves, a, b) = -1"),
            Row("mirror(PointRef, PointRef, PlaneRef, Double)", Reach.NOTATION, "insist left mirrors right across spine"),
            Row("same(Iterable, AxisSet, Double)", Reach.NOTATION, "insist a b c level"),
            Row("sameXYZ(PointRef, PointRef, Double)", Reach.NOTATION, "insist a coincident b"),
            Row("PointRef.ceq(PointRef)", Reach.NOTATION, "insist a coincident b"),
            Row("weakAll(Double)", Reach.NOTATION, "whisper all loose"),
            Row("noNegativeZAll(Double)", Reach.NOTATION, "insist all above-ground"),

            // --- reachable only with a Kotlin value in hand -----------------------------------
            Row(
                "PointRef.ceq(Vector3fc)", Reach.HOST_ONLY,
                "a brief names points, not host vectors; bind one with toGeometryModel(bindings) and " +
                    "state the relation against its name",
            ),
            Row(
                "Vector3fc.ceq(PointRef)", Reach.HOST_ONLY,
                "the mirrored overload of the same thing",
            ),
            Row(
                "expression(List, Double, Function1)", Reach.HOST_ONLY,
                "an arbitrary Kotlin residual. Brief *uses* it — `at … along` and `area = area` are " +
                    "both expression residuals — but does not expose arbitrary arithmetic, because a " +
                    "notation that can say anything is a programming language and this one is a brief",
            ),

            // --- deliberately lowered another way ---------------------------------------------
            Row(
                "PointExpr.ceq(PointRef)", Reach.SUPERSEDED,
                "`prefer spine at 1/3 along (west, east)` lowers through `expression` instead. `ceq` on " +
                    "a PointExpr materialises a trace point recomputed between solves and held at 1e-6, " +
                    "so the minimiser can satisfy it by moving the trace point, and its weight is fixed " +
                    "at 1.0 — a notation whose premise is that strengths differ cannot use it",
            ),
            Row("PointExpr.ceq(Vector3fc)", Reach.SUPERSEDED, "as above, with a host value"),
            Row("PointRef.ceq(PointExpr)", Reach.SUPERSEDED, "as above, written the other way round"),

            // --- not relations ----------------------------------------------------------------
            Row("add(GeometryConstraint)", Reach.PLUMBING, "the raw escape hatch; `colinear` uses it"),
            Row("point(Vector3fc, String)", Reach.PLUMBING, "declaration, from a host vector"),
            Row("Vector3fc.getC()", Reach.PLUMBING, "declaration shorthand"),
            Row("Vector3f.getPoint()", Reach.PLUMBING, "declaration shorthand, bound"),
            Row("line(PointRef, PointRef, String)", Reach.PLUMBING, "declaration"),
            Row("line(Vector3fc, Vector3fc, String)", Reach.PLUMBING, "declaration"),
            Row("line(PointRef, Vector3fc, String)", Reach.PLUMBING, "declaration"),
            Row("line(Vector3fc, PointRef, String)", Reach.PLUMBING, "declaration"),
            Row("PointRef.lineTo(PointRef)", Reach.PLUMBING, "declaration shorthand"),
            Row("PointRef.lineTo(Vector3fc)", Reach.PLUMBING, "declaration shorthand"),
            Row("Vector3fc.lineTo(PointRef)", Reach.PLUMBING, "declaration shorthand"),
            Row("Vector3fc.lineTo(Vector3fc)", Reach.PLUMBING, "declaration shorthand"),
            Row("PointRef.plus(PointRef)", Reach.PLUMBING, "point arithmetic"),
            Row("PointRef.plus(PointExpr)", Reach.PLUMBING, "point arithmetic"),
            Row("PointRef.minus(PointRef)", Reach.PLUMBING, "point arithmetic"),
            Row("PointRef.minus(PointExpr)", Reach.PLUMBING, "point arithmetic"),
            Row("PointRef.times(Number)", Reach.PLUMBING, "point arithmetic"),
            Row("Number.times(PointRef)", Reach.PLUMBING, "point arithmetic"),
            Row("Number.times(PointExpr)", Reach.PLUMBING, "point arithmetic"),
        )

        /**
         * The floor on the covered fraction of *relations*.
         *
         * Today the figure is 31 of 37 = 83.8%. Two points under, so a real regression fails and a
         * rounding difference does not. Raise it when the gap closes; do not lower it.
         */
        const val COVERAGE_FLOOR = 0.82
    }

    private fun simpleName(kClass: KClass<*>?): String = kClass?.simpleName ?: "?"

    /** `LineRef.length(Double, Double)` — receiver, name, value-parameter types. */
    private fun key(function: KFunction<*>): String {
        val receiver = function.extensionReceiverParameter?.type?.classifier as? KClass<*>
        val params = function.valueParameters.joinToString(", ") {
            simpleName(it.type.classifier as? KClass<*>)
        }
        val prefix = if (receiver == null) "" else simpleName(receiver) + "."
        return "$prefix${function.name}($params)"
    }

    /**
     * Every declared member, including the property getters, which are how `val Vector3fc.c` and
     * `val Vector3f.point` appear — they are part of the surface and would otherwise slip the net.
     */
    private fun members(): Set<String> {
        val functions = ConstraintScope::class.declaredFunctions.map { key(it) }
        val getters = ConstraintScope::class.members
            .filterIsInstance<kotlin.reflect.KProperty<*>>()
            .filter { it.extensionReceiverParameter != null }
            .map { "${simpleName(it.extensionReceiverParameter?.type?.classifier as? KClass<*>)}.get" +
                it.name.replaceFirstChar { c -> c.uppercase() } + "()" }
        return (functions + getters).toSortedSet()
    }

    @Test
    fun everyConstraintScopeMemberIsClassified() {
        val reflected = members()
        assertTrue(
            reflected.size >= 40,
            "the ConstraintScope reflection looks broken — only found ${reflected.size}: $reflected"
        )
        val classified = REACH.map { it.member }.toSet()
        val unclassified = (reflected - classified).sorted()
        assertTrue(
            unclassified.isEmpty(),
            "ConstraintScope has ${unclassified.size} member(s) that BriefCoverageTest does not " +
                "classify. Adding a constraint verb to the Kotlin DSL and not to the notation is the " +
                "exact drift this test exists to catch — either give it brief syntax and a NOTATION " +
                "row, or add a row saying why not:\n" + unclassified.joinToString("\n") { "  $it" }
        )
    }

    @Test
    fun everyClassifiedMemberStillExists() {
        val reflected = members()
        val stale = REACH.map { it.member }.filterNot { it in reflected }.sorted()
        assertTrue(
            stale.isEmpty(),
            "these rows claim to classify a ConstraintScope member that is no longer there — the " +
                "signature moved, or the verb was deleted:\n" + stale.joinToString("\n") { "  $it" }
        )
        assertEquals(
            REACH.size, REACH.map { it.member }.distinct().size,
            "the table has duplicate rows, which would inflate the coverage figure"
        )
    }

    @Test
    fun theNotationCoversMostOfTheVocabulary() {
        val relations = REACH.filterNot { it.reach == Reach.PLUMBING }
        val covered = relations.count { it.reach == Reach.NOTATION }
        val fraction = covered.toDouble() / relations.size
        println(
            "brief covers $covered of ${relations.size} ConstraintScope relations " +
                "(${"%.1f".format(fraction * 100)}%); " +
                REACH.count { it.reach == Reach.HOST_ONLY } + " host-only, " +
                REACH.count { it.reach == Reach.SUPERSEDED } + " superseded"
        )
        assertTrue(
            fraction >= COVERAGE_FLOOR,
            "brief now reaches only ${"%.1f".format(fraction * 100)}% of ConstraintScope's relations, " +
                "under the ${"%.0f".format(COVERAGE_FLOOR * 100)}% floor. The uncovered ones are:\n" +
                relations.filterNot { it.reach == Reach.NOTATION }
                    .joinToString("\n") { "  ${it.member} — ${it.reach}: ${it.syntax}" }
        )
    }

    /**
     * The other direction. A verb in [BriefVerbs] that no row mentions is a construct nothing claims to
     * lower — which is how a notation grows a word that parses and does nothing.
     */
    @Test
    fun everyNotationConstructIsClaimedByARow() {
        val claimed = REACH.joinToString(" ") { it.syntax }
        val constructs = BriefVerbs.infix + BriefVerbs.prefix + BriefVerbs.functions + BriefVerbs.properties
        // Words handled by a covering row under a different spelling, each with the row that covers it.
        val spelledDifferently = mapOf(
            "at" to "prefer spine at 1/3 along (west, east) — expression, see the HOST_ONLY row",
            "colinear" to "add(GeometryConstraint) — Colinear has no ConstraintScope verb",
            "same-x" to "same(Iterable, AxisSet, Double), as `level`",
            "same-y" to "same(Iterable, AxisSet, Double), as `level`",
            "same-z" to "same(Iterable, AxisSet, Double), as `level`",
            "same-xy" to "same(Iterable, AxisSet, Double), as `level`",
            "same-xz" to "same(Iterable, AxisSet, Double), as `level`",
            "same-yz" to "same(Iterable, AxisSet, Double), as `level`",
            "all" to "weakAll / noNegativeZAll",
            "x" to "an axis anchor plus same(Iterable, AxisSet, Double)",
            "y" to "an axis anchor plus same(Iterable, AxisSet, Double)",
            "z" to "an axis anchor plus same(Iterable, AxisSet, Double)",
            "distance" to "LineRef.length, on a line the lowering makes for you",
            "area" to "PanelRef.area, or an expression residual for area = area",
            "length" to "LineRef.length",
        )
        val orphans = constructs.filterNot { it in claimed || it in spelledDifferently }.sorted()
        assertTrue(
            orphans.isEmpty(),
            "these brief constructs appear in no row of the coverage table, so nothing states what " +
                "they lower to: $orphans"
        )
        // And the alias map may not name a construct the notation no longer has.
        val ghosts = spelledDifferently.keys.filterNot { it in constructs }.sorted()
        assertTrue(ghosts.isEmpty(), "the alias map names constructs BriefVerbs no longer has: $ghosts")
    }
}
