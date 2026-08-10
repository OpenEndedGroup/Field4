@file:afield.CompletionCategory("Solvers")

package afield.brief

import afield.stdlib.ceres.AxisSet
import afield.stdlib.ceres.CeresExpr
import afield.stdlib.ceres.CeresExprVec
import afield.stdlib.ceres.CeresLinearSolver
import afield.stdlib.ceres.FfmCeresBackend
import afield.stdlib.ceres.Colinear
import afield.stdlib.ceres.ConstraintScope
import afield.stdlib.ceres.c
import afield.stdlib.ceres.GeometryModel
import afield.stdlib.ceres.GeometryModelScope
import afield.stdlib.ceres.LineRef
import afield.stdlib.ceres.PanelRef
import afield.stdlib.ceres.PlaneRef
import afield.stdlib.ceres.PointRef
import afield.stdlib.ceres.RobustLoss
import afield.stdlib.ceres.SolveOptions
import afield.stdlib.ceres.Vec3
import afield.stdlib.ceres.geometryModel
import afield.stdlib.linalg.Vector3f
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Resolution and lowering: a [BriefSyntax] becomes a [BriefProgram], and a [BriefProgram] becomes an
 * `afield.stdlib.ceres.GeometryModel` solved through the real Ceres backend.
 *
 * The two halves are separated because they fail differently. Resolution is where a *reader* is wrong
 * — a misspelling, a plane used where a line was wanted, two statements that cannot both hold — and
 * every failure there is a located [BriefDiagnostic]. Lowering is where the *implementation* would be
 * wrong, and by then every name has been checked, so it throws `error()` on anything that gets
 * through: an exception from this file is a bug in this file, never in the brief.
 */

// ---------------------------------------------------------------------------------------------
// The compiled program
// ---------------------------------------------------------------------------------------------

/**
 * A resolved brief: a checked syntax tree, a point order, and the list of points the notation invented
 * because a relation named them before anything declared them.
 *
 * [compileBrief] returns one of these or throws [BriefException]; a program that compiled may still
 * carry warnings in [diagnostics].
 */
class BriefProgram internal constructor(
    val source: String,
    val syntax: BriefSyntax,
    val diagnostics: List<BriefDiagnostic>,
    /** Every point, in the order they became parameters — declared first, then invented. */
    val pointNames: List<String>,
    /** Points a relation named that nothing declared, seeded on the golden-angle circle. */
    val inventedPoints: List<String>,
    internal val seeds: Map<String, Vec3>,
    internal val anchors: List<AxisAnchor>,
) {
    /** A hidden point that turns `p.z = 3` into a relation between two points, which the solver has. */
    internal class AxisAnchor(val name: String, val at: Vec3, val axes: AxisSet)

    val constraintCount: Int get() = syntax.constraints.size

    val warnings: List<BriefDiagnostic> get() = diagnostics.filter { it.severity == BriefSeverity.WARNING }

    /** The statement labels, in source order — the names a residual report uses. */
    val statementLabels: List<String> get() = syntax.constraints.map { it.label }

    override fun toString(): String =
        "BriefProgram(${pointNames.size} point(s), $constraintCount constraint(s)" +
            if (inventedPoints.isEmpty()) ")" else ", invented ${inventedPoints.joinToString(", ")})"
}

/**
 * Compile a brief. A pure function of text: no `Box`, no output, no server, no native library.
 *
 * A leading `#brief` line is optional and is skipped as a comment either way, so the same string works
 * as an embedded region and as a loose file. Throws [BriefException] carrying *every* error, because a
 * brief with four misspellings should report four.
 */
fun compileBrief(source: String): BriefProgram {
    val lexer = BriefLexer(source)
    val toks = lexer.lex()
    val syntax = BriefParser(toks).parse()
    val resolver = BriefResolver(source, syntax, lexer.diagnostics() + syntax.diagnostics)
    return resolver.resolve()
}

/** [compileBrief] without the throw: the diagnostics, whether or not the program is usable. */
fun checkBrief(source: String): List<BriefDiagnostic> =
    try {
        compileBrief(source).diagnostics
    } catch (e: BriefException) {
        e.diagnostics
    }

// ---------------------------------------------------------------------------------------------
// Resolution
// ---------------------------------------------------------------------------------------------

/**
 * Two passes and a check.
 *
 * Pass one registers every declaration, so a constraint on line 3 can name a panel declared on line
 * 30 — forward reference is not a feature bolted onto the grammar, it is the consequence of resolving
 * after parsing rather than during. Pass two walks the constraints, resolving each name against the
 * namespace its position demands and inventing points that nobody declared. The check is
 * [contradictions], which is the only place the notation second-guesses the author.
 */
internal class BriefResolver(
    private val source: String,
    private val syntax: BriefSyntax,
    private val inherited: List<BriefDiagnostic>,
) {
    private val diagnostics = inherited.toMutableList()

    private val points = linkedMapOf<String, Vec3>()
    private val lines = linkedMapOf<String, BriefDeclaration.Line>()
    private val planes = linkedMapOf<String, BriefDeclaration.Plane>()
    private val panels = linkedMapOf<String, BriefDeclaration.Panel>()
    private val invented = mutableListOf<String>()
    private val anchors = mutableListOf<BriefProgram.AxisAnchor>()

    fun resolve(): BriefProgram {
        declarations()
        constraints()
        contradictions()

        if (diagnostics.any { it.severity == BriefSeverity.ERROR }) throw BriefException(diagnostics)

        return BriefProgram(
            source = source,
            syntax = syntax,
            diagnostics = diagnostics.toList(),
            pointNames = points.keys.toList() + anchors.map { it.name },
            inventedPoints = invented.toList(),
            seeds = points.toMap() + anchors.associate { it.name to it.at },
            anchors = anchors.toList(),
        )
    }

    private fun err(at: BriefSpan, message: String, hint: String? = null) {
        diagnostics += BriefDiagnostic(BriefSeverity.ERROR, at.line, at.column, at.length, message, hint)
    }

    private fun warn(at: BriefSpan, message: String, hint: String? = null) {
        diagnostics += BriefDiagnostic(BriefSeverity.WARNING, at.line, at.column, at.length, message, hint)
    }

    private fun declaredNames(): Set<String> = points.keys + lines.keys + planes.keys + panels.keys

    private fun kindOf(name: String): BriefElementKind? = when (name) {
        in points -> BriefElementKind.POINT
        in lines -> BriefElementKind.LINE
        in planes -> BriefElementKind.PLANE
        in panels -> BriefElementKind.PANEL
        else -> null
    }

    private fun declarations() {
        syntax.declarations.forEach { declaration ->
            val existing = kindOf(declaration.name.text)
            if (existing != null) {
                err(
                    declaration.name.at,
                    "'${declaration.name.text}' is already declared as a ${existing.noun}",
                    "every element name is used once, across all four kinds",
                )
                return@forEach
            }
            when (declaration) {
                is BriefDeclaration.Point -> points[declaration.name.text] =
                    Vec3(declaration.x, declaration.y, declaration.z)
                is BriefDeclaration.Line -> lines[declaration.name.text] = declaration
                is BriefDeclaration.Plane -> planes[declaration.name.text] = declaration
                is BriefDeclaration.Panel -> panels[declaration.name.text] = declaration
            }
        }
        // Second pass over the compound declarations' *members*, so `line spine from a to b` may
        // precede `point a at …` and may also invent both of its endpoints.
        lines.values.forEach { point(it.a); point(it.b) }
        planes.values.forEach { point(it.a); point(it.b); point(it.c) }
        panels.values.forEach { point(it.a); point(it.b); point(it.c); point(it.d) }
        lines.values.forEach {
            if (it.a.text == it.b.text) err(it.b.at, "a line's endpoints must be two different points")
        }
        planes.values.forEach { plane ->
            val names = listOf(plane.a, plane.b, plane.c)
            if (names.map { it.text }.distinct().size != 3) {
                err(plane.name.at, "a plane needs three different points")
            }
        }
        panels.values.forEach { panel ->
            val names = listOf(panel.a, panel.b, panel.c, panel.d)
            if (names.map { it.text }.distinct().size != 4) {
                err(panel.name.at, "a panel needs four different corners")
            }
        }
    }

    /**
     * Resolve a name in point position, declaring it on use if nothing did.
     *
     * The seed is a golden-angle point on a widening spiral, which is the whole reason this is safe:
     * every invented point is distinct from every other, so a brief made only of relations starts from
     * a spread the solver can move rather than a coincident pile whose Jacobian is rank-deficient. The
     * radius grows as `sqrt(k+1)` so the spread stays roughly uniform in area.
     */
    private fun point(name: BriefName): String {
        when (val kind = kindOf(name.text)) {
            BriefElementKind.POINT -> return name.text
            null -> Unit
            else -> {
                err(
                    name.at, "'${name.text}' is a ${kind.noun}, and a point was wanted here",
                    "the points are ${points.keys.sorted().joinToString(", ").ifEmpty { "none yet" }}",
                )
                return name.text
            }
        }
        val k = invented.size
        val theta = k * GOLDEN_ANGLE
        val radius = sqrt((k + 1).toDouble())
        points[name.text] = Vec3(radius * cos(theta), radius * kotlin.math.sin(theta), 0.0)
        invented += name.text
        return name.text
    }

    private fun expect(name: BriefName, kind: BriefElementKind): Boolean {
        if (kind == BriefElementKind.POINT) {
            point(name)
            return kindOf(name.text) == BriefElementKind.POINT
        }
        val actual = kindOf(name.text)
        if (actual == kind) return true
        if (actual == null) {
            err(
                name.at, "no ${kind.noun} called '${name.text}'",
                didYouMean(name.text, namesOf(kind))
                    ?: "declare it: ${declarationHint(kind, name.text)}",
            )
        } else {
            err(name.at, "'${name.text}' is a ${actual.noun}, and a ${kind.noun} was wanted here")
        }
        return false
    }

    private fun namesOf(kind: BriefElementKind): Set<String> = when (kind) {
        BriefElementKind.POINT -> points.keys
        BriefElementKind.LINE -> lines.keys
        BriefElementKind.PLANE -> planes.keys
        BriefElementKind.PANEL -> panels.keys
    }

    private fun declarationHint(kind: BriefElementKind, name: String): String = when (kind) {
        BriefElementKind.POINT -> "point $name at (0, 0)"
        BriefElementKind.LINE -> "line $name from a to b"
        BriefElementKind.PLANE -> "plane $name through a b c"
        BriefElementKind.PANEL -> "panel $name corners a b c d"
    }

    private fun constraints() {
        syntax.constraints.forEach { constraint ->
            when (val relation = constraint.relation) {
                is BriefRelation.Equation -> equation(constraint, relation)
                is BriefRelation.Predicate -> predicate(constraint, relation)
            }
        }
    }

    private fun termKind(term: BriefTerm): String = when (term) {
        is BriefTerm.Literal -> "a number"
        is BriefTerm.Property -> term.property
        is BriefTerm.Call -> term.function
    }

    private fun equation(constraint: BriefConstraint, equation: BriefRelation.Equation) {
        val left = equation.left
        val right = equation.right
        checkTerm(left)
        checkTerm(right)
        if (left is BriefTerm.Literal && right is BriefTerm.Literal) {
            err(equation.at, "an equation between two numbers constrains nothing")
            return
        }
        // Normalise so the element side is on the left, which is what the lowering assumes.
        val (element, other) = if (left is BriefTerm.Literal) right to left else left to right
        if (other is BriefTerm.Literal) {
            targetSanity(constraint, element, other)
            return
        }
        // element = element: only a matching pair of the same property is a relation the solver has.
        val a = termKind(element)
        val b = termKind(other)
        if (a != b) {
            err(
                equation.at, "brief cannot equate a '$a' to a '$b'",
                "the solver has no residual for it — state a number on one side",
            )
            return
        }
        when (a) {
            "length", "x", "y", "z", "distance" -> Unit
            // No opcode equates two areas, so this one is a runtime residual, and Ceres will not accept
            // a residual block that names one point twice. Two panels sharing a corner is therefore a
            // real refusal rather than a missing feature, and it is worth saying which corner.
            "area" -> {
                val left = (element as BriefTerm.Property).element.text
                val right = (other as BriefTerm.Property).element.text
                val a1 = panels[left]
                val b1 = panels[right]
                if (a1 != null && b1 != null) {
                    val shared = listOf(a1.a, a1.b, a1.c, a1.d).map { it.text }
                        .intersect(listOf(b1.a, b1.b, b1.c, b1.d).map { it.text }.toSet())
                    if (shared.isNotEmpty()) {
                        err(
                            equation.at,
                            "'$left' and '$right' share the corner(s) ${shared.sorted().joinToString(", ")}",
                            "an equal-area residual reads all eight corners at once and Ceres will not " +
                                "let one residual name a point twice — give each panel a number instead",
                        )
                    }
                }
            }
            else -> err(
                equation.at, "brief cannot equate two '$a' terms",
                "state a number on one side",
            )
        }
        if (equation.comparison != BriefComparison.EQ && a != "length" && a != "distance") {
            err(equation.at, "'${equation.comparison.symbol}' is only available for a length")
        }
    }

    private fun checkTerm(term: BriefTerm) {
        when (term) {
            is BriefTerm.Literal -> Unit
            is BriefTerm.Property -> when (term.property) {
                "length" -> expect(term.element, BriefElementKind.LINE)
                "area" -> expect(term.element, BriefElementKind.PANEL)
                "x", "y", "z" -> expect(term.element, BriefElementKind.POINT)
            }
            is BriefTerm.Call -> {
                val arity = CALL_ARITY.getValue(term.function)
                if (term.arguments.size != arity) {
                    err(
                        term.at,
                        "'${term.function}' takes $arity argument(s), not ${term.arguments.size}",
                    )
                    return
                }
                when (term.function) {
                    "distance" -> term.arguments.forEach { expect(it, BriefElementKind.POINT) }
                    "angle", "cos-angle", "dot", "raw-dot" ->
                        term.arguments.forEach { expect(it, BriefElementKind.LINE) }
                    "dihedral" -> {
                        expect(term.arguments[0], BriefElementKind.LINE)
                        expect(term.arguments[1], BriefElementKind.POINT)
                        expect(term.arguments[2], BriefElementKind.POINT)
                    }
                }
            }
        }
    }

    /** Complain about a number that cannot be what it says: a negative length, a cosine past one. */
    private fun targetSanity(constraint: BriefConstraint, element: BriefTerm, literal: BriefTerm.Literal) {
        val kind = termKind(element)
        when (kind) {
            "length", "distance", "area" ->
                if (literal.value < 0.0) {
                    err(literal.at, "a $kind cannot be negative")
                } else if (literal.value == 0.0 && kind != "area") {
                    warn(
                        literal.at, "a $kind of zero welds the two points together",
                        "write 'coincident' if that is what you mean",
                    )
                }
            "cos-angle", "dot", "dihedral" ->
                if (abs(literal.value) > 1.0) {
                    err(literal.at, "a cosine has to be between -1 and 1, not ${trimNumber(literal.value)}")
                }
            "angle" -> {
                if (!literal.radians && abs(literal.value) > PI * 2) {
                    warn(
                        literal.at, "'${trimNumber(literal.value)}' is being read as radians",
                        "write '${trimNumber(literal.value)}deg' for degrees",
                    )
                }
                if (literal.value < 0.0 || literal.value > PI) {
                    warn(
                        literal.at, "an angle target is a cosine underneath, so it is unsigned and " +
                            "folds into 0..180deg",
                    )
                }
            }
        }
        if (kind in setOf("x", "y", "z")) {
            val axis = when (kind) {
                "x" -> AxisSet.X
                "y" -> AxisSet.Y
                else -> AxisSet.Z
            }
            val subject = (element as BriefTerm.Property).element
            val seed = points[subject.text] ?: Vec3(0.0, 0.0, 0.0)
            val at = when (axis) {
                AxisSet.X -> Vec3(literal.value, seed.y, seed.z)
                AxisSet.Y -> Vec3(seed.x, literal.value, seed.z)
                else -> Vec3(seed.x, seed.y, literal.value)
            }
            anchors += BriefProgram.AxisAnchor("_at:${subject.text}.$kind:${anchors.size}", at, axis)
        }
        if (constraint.strength == 0.0) {
            warn(constraint.at, "a strength of 0 makes this statement free, so it says nothing")
        }
    }

    private fun predicate(constraint: BriefConstraint, predicate: BriefRelation.Predicate) {
        val subjects = predicate.subjects
        val objects = predicate.objects
        when (predicate.verb) {
            "parallel", "perpendicular", "same-vector" -> {
                requireSubjects(predicate, 1, 1, BriefElementKind.LINE)
                expect(objects[0], BriefElementKind.LINE)
                if (subjects.firstOrNull()?.text == objects[0].text) {
                    err(predicate.at, "'${predicate.verb}' between a line and itself says nothing")
                }
            }
            "along" -> {
                requireSubjects(predicate, 1, 0, BriefElementKind.LINE)
                val v = predicate.vector ?: return
                if (v.first == 0.0 && v.second == 0.0 && v.third == 0.0) {
                    err(predicate.at, "'along' needs a direction, and (0, 0, 0) is not one")
                }
            }
            "toward", "vanishes-at" -> {
                requireSubjects(predicate, 1, 1, BriefElementKind.LINE)
                expect(objects[0], BriefElementKind.POINT)
            }
            "faces" -> {
                requireSubjects(predicate, 1, 0, BriefElementKind.PLANE)
                val v = predicate.vector ?: return
                if (v.first == 0.0 && v.second == 0.0 && v.third == 0.0) {
                    err(predicate.at, "'faces' needs a direction, and (0, 0, 0) is not one")
                }
                predicate.number?.let {
                    if (abs(it) > 1.0) err(predicate.at, "a target cosine has to be between -1 and 1")
                }
            }
            "planar" -> {
                if (subjects.size == 1 && kindOf(subjects[0].text) == BriefElementKind.PANEL) return
                if (subjects.size < 3) {
                    err(
                        predicate.at, "'planar' needs at least three points, or one panel",
                        "it is only a constraint past three",
                    )
                    return
                }
                subjects.forEach { expect(it, BriefElementKind.POINT) }
                if (subjects.size == 3) {
                    warn(predicate.at, "three points are always coplanar, so this costs nothing")
                }
            }
            "colinear" -> {
                if (subjects.size < 3) {
                    err(predicate.at, "'colinear' needs at least three points")
                    return
                }
                subjects.forEach { expect(it, BriefElementKind.POINT) }
            }
            "level", "same-x", "same-y", "same-z", "same-xy", "same-xz", "same-yz" -> {
                if (subjects.size < 2) {
                    err(predicate.at, "'${predicate.verb}' needs at least two points")
                    return
                }
                subjects.forEach { expect(it, BriefElementKind.POINT) }
            }
            "on", "inside" -> {
                requireSubjects(predicate, 1, 1, BriefElementKind.POINT)
                expect(objects[0], BriefElementKind.PLANE)
            }
            "coincident" -> {
                requireSubjects(predicate, 1, 1, BriefElementKind.POINT)
                expect(objects[0], BriefElementKind.POINT)
                if (subjects.firstOrNull()?.text == objects[0].text) {
                    err(predicate.at, "a point is already coincident with itself")
                }
            }
            "mirrors" -> {
                requireSubjects(predicate, 1, 2, BriefElementKind.POINT)
                expect(objects[0], BriefElementKind.POINT)
                expect(objects[1], BriefElementKind.PLANE)
            }
            "at" -> {
                requireSubjects(predicate, 1, 2, BriefElementKind.POINT)
                objects.forEach { expect(it, BriefElementKind.POINT) }
                val all = (subjects + objects).map { it.text }
                if (all.distinct().size != all.size) {
                    err(
                        predicate.at, "'at … along' needs three different points",
                        "one residual block may not name the same point twice",
                    )
                }
                val f = predicate.number
                if (f != null && (f < 0.0 || f > 1.0)) {
                    warn(
                        predicate.at, "a fraction outside 0..1 puts the point beyond the span",
                        "which is legal, and rarely meant",
                    )
                }
            }
            "fix", "hold" -> {
                if (subjects.isEmpty()) {
                    err(predicate.at, "'${predicate.verb}' needs at least one point")
                    return
                }
                subjects.forEach { expect(it, BriefElementKind.POINT) }
            }
            "all-loose", "all-above-ground" -> Unit
            else -> err(predicate.at, "'${predicate.verb}' is not lowered yet")
        }
        if (constraint.strength == 0.0) {
            warn(constraint.at, "a strength of 0 makes this statement free, so it says nothing")
        }
    }

    private fun requireSubjects(
        predicate: BriefRelation.Predicate,
        subjects: Int,
        objects: Int,
        subjectKind: BriefElementKind,
    ) {
        if (predicate.subjects.size != subjects) {
            err(
                predicate.at,
                "'${predicate.verb}' takes $subjects ${subjectKind.noun}(s) before it, " +
                    "not ${predicate.subjects.size}",
            )
        }
        if (predicate.objects.size != objects) {
            err(predicate.at, "'${predicate.verb}' takes $objects thing(s) after it, not ${predicate.objects.size}")
        }
        predicate.subjects.forEach { expect(it, subjectKind) }
    }

    /**
     * The only place the notation argues with the author: two statements that cannot both hold.
     *
     * Deliberately narrow. A least-squares system is *supposed* to absorb disagreement — that is what
     * the weights are for — so flagging every pair that pulls in different directions would be noise.
     * What is flagged is a pair that agrees on the *statement* and disagrees on the *number*, where
     * both are at `insist` or above: at that strength the author has said "this one wins" twice, and
     * only one of them can.
     */
    private fun contradictions() {
        val hard = syntax.constraints.filter { it.strength >= BriefStrength.INSIST.weight }
        val byTarget = mutableMapOf<String, MutableList<Pair<BriefConstraint, Double>>>()
        hard.forEach { constraint ->
            val relation = constraint.relation as? BriefRelation.Equation ?: return@forEach
            if (relation.comparison != BriefComparison.EQ) return@forEach
            val literal = (relation.right as? BriefTerm.Literal) ?: (relation.left as? BriefTerm.Literal)
                ?: return@forEach
            val element = if (relation.left is BriefTerm.Literal) relation.right else relation.left
            byTarget.getOrPut(describeTerm(element)) { mutableListOf() } += constraint to literal.value
        }
        byTarget.forEach { (target, statements) ->
            val values = statements.map { it.second }.distinct()
            if (values.size > 1) {
                val where = statements.first().first
                err(
                    where.at,
                    "'$target' is insisted to be both ${values.sorted().joinToString(" and ") { trimNumber(it) }}",
                    "lines ${statements.map { it.first.at.line }.sorted().joinToString(", ")} — " +
                        "lower one of them, or the solver will split the difference and satisfy neither",
                )
            }
        }
    }

    private companion object {
        /** 2.399963… radians — the angle that makes successive points maximally unaligned. */
        const val GOLDEN_ANGLE = 2.399963229728653

        val CALL_ARITY = mapOf(
            "distance" to 2, "angle" to 2, "cos-angle" to 2, "dot" to 2, "raw-dot" to 2, "dihedral" to 3,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Lowering to the Kotlin DSL
// ---------------------------------------------------------------------------------------------

/** The resolved handles a lowering walks against. */
internal class BriefEnv(
    val points: Map<String, PointRef>,
    val lines: Map<String, LineRef>,
    val planes: Map<String, PlaneRef>,
    val panels: Map<String, PanelRef>,
) {
    fun point(name: BriefName): PointRef = points[name.text] ?: error("unresolved point ${name.text}")
    fun line(name: BriefName): LineRef = lines[name.text] ?: error("unresolved line ${name.text}")
    fun plane(name: BriefName): PlaneRef = planes[name.text] ?: error("unresolved plane ${name.text}")
    fun panel(name: BriefName): PanelRef = panels[name.text] ?: error("unresolved panel ${name.text}")
}

/**
 * Build the `GeometryModel` this brief describes.
 *
 * [bindings] is the host seam and the answer to "a Route-A notation cannot see Kotlin values": a name
 * in this map becomes a `GeometryModelScope.bindPoint` rather than a literal point, so the solver
 * reads the `Vector3f` on the way in and writes the answer back into it on the way out. That is how a
 * brief constrains geometry that lives outside it, and it is the reason the notation is embedded in
 * Kotlin rather than promoted to a box language.
 */
fun BriefProgram.toGeometryModel(bindings: Map<String, Vector3f> = emptyMap()): GeometryModel {
    val unknown = bindings.keys - pointNames.toSet()
    require(unknown.isEmpty()) {
        "brief has no point(s) named ${unknown.sorted().joinToString(", ")}; it declares " +
            pointNames.filterNot { it.startsWith("_at:") }.joinToString(", ")
    }
    return geometryModel {
        val env = declare(this, this@toGeometryModel, bindings)
        syntax.settle?.let { settle ->
            if (settle.weight == null) noDefaultInteractionPriors() else defaultInteractionPriors(settle.weight)
        }
        // Plain least squares unless the brief asks otherwise. See BriefRobust: the underlying
        // CeresProgram defaults to Huber(1e-3), under which a weight ratio decides a fight outright
        // instead of splitting it, and a notation built on a strength ladder cannot mean that.
        robustLoss(
            syntax.robust?.let { RobustLoss(lossKind(it.kind), it.scale) } ?: RobustLoss.NONE
        )
        constraints {
            anchors.forEach { anchor ->
                // Hold the hidden anchor overwhelmingly harder than any statement can be written, so
                // `p.z = 3` behaves as a relation to the number rather than a negotiation with it. All
                // three axes, not just the constrained one: the other two are read by nothing, and a
                // parameter with an identically-zero Jacobian column is exactly the free-wandering
                // direction `settle none` exists to forbid.
                lock(listOf(env.point(BriefName(anchor.name, BriefSpan(0, 0, 0)))), AxisSet.XYZ, ANCHOR_WEIGHT)
            }
            syntax.constraints.forEach { emit(this, env, it) }
        }
    }
}

/** `1e9` — three decades above `demand`, so an axis anchor is never the thing that loses. */
private const val ANCHOR_WEIGHT = 1e9

private fun lossKind(name: String): RobustLoss.Kind = when (name) {
    "none" -> RobustLoss.Kind.NONE
    "huber" -> RobustLoss.Kind.HUBER
    "soft-l1" -> RobustLoss.Kind.SOFT_L_ONE
    "cauchy" -> RobustLoss.Kind.CAUCHY
    else -> error("unhandled loss $name")
}

private fun declare(
    scope: GeometryModelScope,
    program: BriefProgram,
    bindings: Map<String, Vector3f>,
): BriefEnv {
    val points = linkedMapOf<String, PointRef>()
    program.pointNames.forEach { name ->
        val seed = program.seeds.getValue(name)
        val bound = bindings[name]
        points[name] = if (bound == null) {
            scope.point(seed.x, seed.y, seed.z, name)
        } else {
            scope.bindPoint(name = name, value = bound)
        }
    }
    val lines = linkedMapOf<String, LineRef>()
    val planes = linkedMapOf<String, PlaneRef>()
    val panels = linkedMapOf<String, PanelRef>()
    program.syntax.declarations.forEach { declaration ->
        when (declaration) {
            is BriefDeclaration.Point -> Unit
            is BriefDeclaration.Line -> lines[declaration.name.text] = scope.line(
                points.getValue(declaration.a.text), points.getValue(declaration.b.text), declaration.name.text,
            )
            is BriefDeclaration.Plane -> planes[declaration.name.text] = scope.plane(
                points.getValue(declaration.a.text),
                points.getValue(declaration.b.text),
                points.getValue(declaration.c.text),
                declaration.name.text,
            )
            is BriefDeclaration.Panel -> panels[declaration.name.text] = scope.panel(
                points.getValue(declaration.a.text),
                points.getValue(declaration.b.text),
                points.getValue(declaration.c.text),
                points.getValue(declaration.d.text),
                declaration.name.text,
            )
        }
    }
    return BriefEnv(points, lines, planes, panels)
}

private fun axisSet(name: String): AxisSet = when (name.lowercase()) {
    "x" -> AxisSet.X
    "y" -> AxisSet.Y
    "z" -> AxisSet.Z
    "xy" -> AxisSet.XY
    "xz" -> AxisSet.XZ
    "yz" -> AxisSet.YZ
    else -> AxisSet.XYZ
}

/**
 * One statement, lowered.
 *
 * Every branch is a call into `ConstraintScope`, which is the whole claim of this package: the
 * notation adds no residual of its own, so a brief cannot mean anything the Kotlin DSL could not
 * already say. `BriefCoverageTest` is what keeps that true in the other direction.
 */
private fun emit(scope: ConstraintScope, env: BriefEnv, constraint: BriefConstraint) {
    val w = constraint.strength
    when (val relation = constraint.relation) {
        is BriefRelation.Equation -> emitEquation(scope, env, relation, w)
        is BriefRelation.Predicate -> emitPredicate(scope, env, relation, w)
    }
}

private fun emitEquation(scope: ConstraintScope, env: BriefEnv, equation: BriefRelation.Equation, w: Double) {
    val flipped = equation.left is BriefTerm.Literal
    val element = if (flipped) equation.right else equation.left
    val other = if (flipped) equation.left else equation.right
    val comparison = if (!flipped) equation.comparison else when (equation.comparison) {
        BriefComparison.EQ -> BriefComparison.EQ
        BriefComparison.AT_LEAST -> BriefComparison.AT_MOST
        BriefComparison.AT_MOST -> BriefComparison.AT_LEAST
    }
    with(scope) {
        if (other is BriefTerm.Literal) {
            val target = other.value
            when (element) {
                is BriefTerm.Property -> when (element.property) {
                    "length" -> {
                        val line = env.line(element.element)
                        when (comparison) {
                            BriefComparison.EQ -> line.length(target, w)
                            BriefComparison.AT_LEAST -> line.minLength(target, w)
                            BriefComparison.AT_MOST -> line.maxLength(target, w)
                        }
                    }
                    "area" -> env.panel(element.element).area(target, w)
                    "x", "y", "z" -> {
                        val axis = axisSet(element.property)
                        val subject = env.point(element.element)
                        val anchor = env.points.keys.first { it.startsWith("_at:${element.element.text}.${element.property}:") }
                        same(listOf(subject, env.points.getValue(anchor)), axis, w)
                    }
                    else -> error("unhandled property ${element.property}")
                }
                is BriefTerm.Call -> when (element.function) {
                    "distance" -> {
                        val line = line(env.point(element.arguments[0]), env.point(element.arguments[1]))
                        when (comparison) {
                            BriefComparison.EQ -> line.length(target, w)
                            BriefComparison.AT_LEAST -> line.minLength(target, w)
                            BriefComparison.AT_MOST -> line.maxLength(target, w)
                        }
                    }
                    "angle" -> env.line(element.arguments[0])
                        .angleRadiansTo(env.line(element.arguments[1]), target, w)
                    "cos-angle" -> env.line(element.arguments[0])
                        .angleCosTo(env.line(element.arguments[1]), target, w)
                    "dot" -> (env.line(element.arguments[0]) dot env.line(element.arguments[1])) ceq target
                    "raw-dot" -> (env.line(element.arguments[0]) dot env.line(element.arguments[1])).raw() ceq target
                    "dihedral" -> dihedral(
                        env.line(element.arguments[0]),
                        env.point(element.arguments[1]),
                        env.point(element.arguments[2]),
                        target,
                        w,
                    )
                    else -> error("unhandled function ${element.function}")
                }
                is BriefTerm.Literal -> error("two literals should have been refused")
            }
            return
        }
        // element = element
        when {
            element is BriefTerm.Property && other is BriefTerm.Property && element.property == "length" ->
                env.line(element.element).sameLengthAs(env.line(other.element), w)
            element is BriefTerm.Property && other is BriefTerm.Property && element.property == "area" -> {
                val p = env.panel(element.element)
                val q = env.panel(other.element)
                expression(p.points + q.points, w) { corners ->
                    listOf(quadAreaExpr(corners.take(4)) - quadAreaExpr(corners.drop(4)))
                }
            }
            element is BriefTerm.Property && other is BriefTerm.Property ->
                same(listOf(env.point(element.element), env.point(other.element)), axisSet(element.property), w)
            element is BriefTerm.Call && other is BriefTerm.Call && element.function == "distance" ->
                line(env.point(element.arguments[0]), env.point(element.arguments[1]))
                    .sameLengthAs(line(env.point(other.arguments[0]), env.point(other.arguments[1])), w)
            element is BriefTerm.Call && other is BriefTerm.Property ->
                line(env.point(element.arguments[0]), env.point(element.arguments[1]))
                    .sameLengthAs(env.line(other.element), w)
            element is BriefTerm.Property && other is BriefTerm.Call ->
                env.line(element.element)
                    .sameLengthAs(line(env.point(other.arguments[0]), env.point(other.arguments[1])), w)
            else -> error("unhandled equation between ${describeTerm(element)} and ${describeTerm(other)}")
        }
    }
}

/**
 * A quadrilateral's area as a residual expression, triangulated on the `a`-`c` diagonal so it agrees
 * with `PanelArea` — the constraint a brief reaches when it states an area as a *number*. The two
 * spellings of "area" have to be the same function or a brief could satisfy one and not the other.
 */
private fun quadAreaExpr(corners: List<CeresExprVec>): CeresExpr {
    val (a, b, c, d) = corners
    return ((b - a) cross (c - a)).length() * 0.5.c + ((c - a) cross (d - a)).length() * 0.5.c
}

private fun emitPredicate(scope: ConstraintScope, env: BriefEnv, predicate: BriefRelation.Predicate, w: Double) {
    with(scope) {
        when (predicate.verb) {
            "parallel" -> env.line(predicate.subjects[0]).parallelTo(env.line(predicate.objects[0]), w)
            "perpendicular" -> env.line(predicate.subjects[0]).perpendicularTo(env.line(predicate.objects[0]), w)
            "same-vector" -> env.line(predicate.subjects[0]).sameVectorAs(env.line(predicate.objects[0]), w)
            "along" -> {
                val v = predicate.vector!!
                env.line(predicate.subjects[0]).direction(Vec3(v.first, v.second, v.third), w)
            }
            "toward" -> env.line(predicate.subjects[0]).toward(env.point(predicate.objects[0]), w)
            "vanishes-at" -> env.line(predicate.subjects[0]).vanishesAt(env.point(predicate.objects[0]), w)
            "faces" -> {
                val v = predicate.vector!!
                env.plane(predicate.subjects[0])
                    .normal(Vec3(v.first, v.second, v.third), predicate.number ?: 1.0, w)
            }
            "planar" ->
                if (predicate.subjects.size == 1 && predicate.subjects[0].text in env.panels) {
                    env.panel(predicate.subjects[0]).planar(w)
                } else {
                    predicate.subjects.map { env.point(it) }.planar(w)
                }
            "colinear" -> {
                val all = predicate.subjects.map { env.point(it) }
                add(Colinear(all[0].id, all[1].id, all.drop(2).map { it.id }, w))
            }
            "level", "same-z" -> same(predicate.subjects.map { env.point(it) }, AxisSet.Z, w)
            "same-x" -> same(predicate.subjects.map { env.point(it) }, AxisSet.X, w)
            "same-y" -> same(predicate.subjects.map { env.point(it) }, AxisSet.Y, w)
            "same-xy" -> same(predicate.subjects.map { env.point(it) }, AxisSet.XY, w)
            "same-xz" -> same(predicate.subjects.map { env.point(it) }, AxisSet.XZ, w)
            "same-yz" -> same(predicate.subjects.map { env.point(it) }, AxisSet.YZ, w)
            "on" -> env.point(predicate.subjects[0])
                .on(env.plane(predicate.objects[0]), predicate.number ?: 0.0, w)
            "inside" -> env.point(predicate.subjects[0])
                .inside(env.plane(predicate.objects[0]), predicate.number ?: 0.0, w)
            "coincident" -> sameXYZ(env.point(predicate.subjects[0]), env.point(predicate.objects[0]), w)
            "mirrors" -> mirror(
                env.point(predicate.subjects[0]),
                env.point(predicate.objects[0]),
                env.plane(predicate.objects[1]),
                w,
            )
            // Three residuals through `ConstraintScope.expression`, not `PointExpr.ceq`. `ceq`
            // materialises a trace point the minimiser can move instead of the geometry, and fixes the
            // weight at 1.0, so a `prefer`/`insist` distinction would have been silently lost — which
            // is exactly the thing this notation exists to make audible.
            "at" -> {
                val f = predicate.number!!
                val p = env.point(predicate.subjects[0])
                val u = env.point(predicate.objects[0])
                val v = env.point(predicate.objects[1])
                expression(listOf(p, u, v), w) { (pe, ue, ve) ->
                    val want = ue * (1.0 - f) + ve * f
                    listOf(pe.x - want.x, pe.y - want.y, pe.z - want.z)
                }
            }
            "fix" -> lock(predicate.subjects.map { env.point(it) }, axisSet(predicate.axes ?: "xyz"), w)
            "hold" -> predicate.subjects.forEach { env.point(it).weak(axisSet(predicate.axes ?: "xyz"), w) }
            "all-loose" -> weakAll(w)
            "all-above-ground" -> noNegativeZAll(w)
            else -> error("unhandled predicate ${predicate.verb}")
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Solving, and the per-statement residual report
// ---------------------------------------------------------------------------------------------

/** One statement's own residual, in its own units, measured from the solved geometry. */
data class BriefResidual(
    val label: String,
    val line: Int,
    val strength: Double,
    /** The statement's residual in its natural units, or NaN where there is no independent one. */
    val residual: Double,
) {
    /** What the statement contributes to the objective, `strength * residual²`. */
    val cost: Double get() = if (residual.isNaN()) Double.NaN else strength * residual * residual

    override fun toString(): String =
        "line $line  ${"%.6g".format(residual)}  (${label})"
}

/**
 * A solved brief: the geometry, and every statement's residual measured back off it.
 *
 * The residuals are computed *here*, in Kotlin, from the solved point positions — not read out of
 * Ceres. That is the point of them. `MutableSolveReport.groupCosts` is indexed by opcode, so a model
 * with six length constraints reports one number for all six, and the question a brief's author has —
 * "which of *my* statements is still fighting?" — cannot be answered from it. Measuring independently
 * also means the report is a *check* on the solve rather than a restatement of it.
 */
class BriefSolution internal constructor(
    val ok: Boolean,
    /** Ceres's own final cost, or NaN when metrics were not requested. */
    val totalCost: Double,
    val points: Map<String, Vec3>,
    val residuals: List<BriefResidual>,
) {
    operator fun get(name: String): Vec3 =
        points[name] ?: error("no point '$name' in this solution; it has ${points.keys.sorted()}")

    /** The statement with the largest weighted cost — "what is still fighting". */
    val worst: BriefResidual? get() = residuals.filterNot { it.cost.isNaN() }.maxByOrNull { it.cost }

    /** The sum of `strength * residual²` over the statements the author wrote. */
    val statedCost: Double get() = residuals.filterNot { it.cost.isNaN() }.sumOf { it.cost }

    /** A readable dump: the points, then the statements worst-first. */
    fun report(limit: Int = 8): String = buildString {
        append(if (ok) "solved" else "NOT SOLVED").append(", stated cost ")
        append("%.6g".format(statedCost)).append('\n')
        points.filterKeys { !it.startsWith("_") }.forEach { (name, at) ->
            append("  ").append(name).append(" = (")
            append("%.4f, %.4f, %.4f".format(at.x, at.y, at.z)).append(")\n")
        }
        residuals.filterNot { it.cost.isNaN() }.sortedByDescending { it.cost }.take(limit).forEach {
            append("  residual ").append("%- 12.6g".format(it.residual)).append("  ").append(it.label)
            append("  [line ").append(it.line).append("]\n")
        }
    }
}

/**
 * What a brief means by "solve it": run it out, not to the interactive default.
 *
 * `SolveOptions`' own defaults are for a drag frame — eight iterations and `functionTolerance = 1e-6`,
 * a *relative* decrease, so on a problem whose cost is a few thousand the solver stops while the answer
 * is still `1e-4` from the minimum. That is right for something redrawn sixty times a second and wrong
 * for a statement of intent, where the question is "what does this brief actually mean" and the answer
 * should not depend on how many iterations were affordable. Hence tolerances near machine epsilon and
 * `DENSE_QR`, which is the well-conditioned choice on the small dense systems a brief produces and
 * matters because a `demand` next to a `whisper` is a condition number of `1e9`.
 */
fun settleOptions(iterations: Int = 400): SolveOptions = SolveOptions(
    maxIterations = iterations,
    metrics = true,
    linearSolver = CeresLinearSolver.DENSE_QR,
    functionTolerance = 1e-14,
    gradientTolerance = 1e-16,
    parameterTolerance = 1e-14,
)

/**
 * Solve the brief through the real Ceres backend and measure every statement.
 *
 * Needs the native library — `./gradlew buildCeresRedistributable` once, per
 * `afield.stdlib.ceres.CeresNativeLibrary.HOW_TO_BUILD`. [bindings] are read on the way in and written
 * back on the way out, so a brief can move `Vector3f`s the rest of a box is drawing.
 */
fun BriefProgram.solve(
    iterations: Int = 400,
    bindings: Map<String, Vector3f> = emptyMap(),
    options: SolveOptions = settleOptions(iterations),
): BriefSolution {
    val model = toGeometryModel(bindings)
    // The backend directly rather than `GeometryModel.openSession`, because the session's fast path is
    // the allocation-free per-frame solve and it refuses a `SolveOptions` that needs the configurable
    // entry point (`FfmCeresBackend.kt:304`) — which tightened tolerances do. A brief has no traces and
    // no pins, so the session's other services are not wanted here; `InteractiveSolving` is still the
    // route for a drag.
    FfmCeresBackend().compile(model.initialPointCloud(), model.program).use { compiled ->
        val result = compiled.solve(model.initialPointCloud(), null, options)
        val solved = pointNames.mapIndexed { index, name -> name to result.points.get(index) }.toMap()
        bindings.forEach { (name, target) ->
            val at = solved.getValue(name)
            target.set(at.x.toFloat(), at.y.toFloat(), at.z.toFloat())
        }
        return BriefSolution(
            ok = result.ok,
            totalCost = result.metrics?.totalCost ?: Double.NaN,
            points = solved,
            residuals = measure(this, solved),
        )
    }
}

// --- residual measurement -------------------------------------------------------------------

private fun measure(program: BriefProgram, at: Map<String, Vec3>): List<BriefResidual> =
    program.syntax.constraints.map { constraint ->
        BriefResidual(
            label = constraint.label,
            line = constraint.at.line,
            strength = constraint.strength,
            residual = residualOf(program, constraint, at),
        )
    }

private fun residualOf(program: BriefProgram, constraint: BriefConstraint, at: Map<String, Vec3>): Double {
    val lines = program.syntax.declarations.filterIsInstance<BriefDeclaration.Line>().associateBy { it.name.text }
    val planes = program.syntax.declarations.filterIsInstance<BriefDeclaration.Plane>().associateBy { it.name.text }
    val panels = program.syntax.declarations.filterIsInstance<BriefDeclaration.Panel>().associateBy { it.name.text }
    fun p(name: BriefName): Vec3 = at.getValue(name.text)
    fun edge(name: BriefName): Pair<Vec3, Vec3> = lines.getValue(name.text).let { p(it.a) to p(it.b) }
    fun len(name: BriefName): Double = edge(name).let { (a, b) -> (b - a).norm() }

    return when (val relation = constraint.relation) {
        is BriefRelation.Equation -> {
            val flipped = relation.left is BriefTerm.Literal
            val element = if (flipped) relation.right else relation.left
            val other = if (flipped) relation.left else relation.right
            val actual = evaluateTerm(element, ::p, ::len, ::edge, planes, panels, at)
            val target = evaluateTerm(other, ::p, ::len, ::edge, planes, panels, at)
            if (actual.isNaN() || target.isNaN()) Double.NaN else when (relation.comparison) {
                BriefComparison.EQ -> actual - target
                BriefComparison.AT_LEAST -> max(0.0, target - actual)
                BriefComparison.AT_MOST -> max(0.0, actual - target)
            }
        }
        is BriefRelation.Predicate -> when (relation.verb) {
            "parallel" -> {
                val (a, b) = edge(relation.subjects[0])
                val (c, d) = edge(relation.objects[0])
                sinBetween(b - a, d - c)
            }
            "perpendicular" -> {
                val (a, b) = edge(relation.subjects[0])
                val (c, d) = edge(relation.objects[0])
                cosBetween(b - a, d - c)
            }
            "same-vector" -> {
                val (a, b) = edge(relation.subjects[0])
                val (c, d) = edge(relation.objects[0])
                ((b - a) - (d - c)).norm()
            }
            "along" -> {
                val (a, b) = edge(relation.subjects[0])
                val v = relation.vector!!
                sinBetween(b - a, Vec3(v.first, v.second, v.third))
            }
            "toward", "vanishes-at" -> {
                val (a, b) = edge(relation.subjects[0])
                distancePointToLine(p(relation.objects[0]), a, b)
            }
            "faces" -> {
                val plane = planes.getValue(relation.subjects[0].text)
                val n = planeNormal(p(plane.a), p(plane.b), p(plane.c))
                val v = relation.vector!!
                cosBetween(n, Vec3(v.first, v.second, v.third)) - (relation.number ?: 1.0)
            }
            "planar" -> {
                val pts = if (relation.subjects.size == 1 && relation.subjects[0].text in panels) {
                    panels.getValue(relation.subjects[0].text).let { listOf(p(it.a), p(it.b), p(it.c), p(it.d)) }
                } else {
                    relation.subjects.map { p(it) }
                }
                planarityDeviation(pts)
            }
            "colinear" -> {
                val pts = relation.subjects.map { p(it) }
                pts.drop(2).maxOfOrNull { distancePointToLine(it, pts[0], pts[1]) } ?: 0.0
            }
            "level", "same-z" -> spread(relation.subjects.map { p(it).z })
            "same-x" -> spread(relation.subjects.map { p(it).x })
            "same-y" -> spread(relation.subjects.map { p(it).y })
            "same-xy" -> max(spread(relation.subjects.map { p(it).x }), spread(relation.subjects.map { p(it).y }))
            "same-xz" -> max(spread(relation.subjects.map { p(it).x }), spread(relation.subjects.map { p(it).z }))
            "same-yz" -> max(spread(relation.subjects.map { p(it).y }), spread(relation.subjects.map { p(it).z }))
            "on" -> {
                val plane = planes.getValue(relation.objects[0].text)
                signedDistanceToPlane(p(relation.subjects[0]), p(plane.a), p(plane.b), p(plane.c)) -
                    (relation.number ?: 0.0)
            }
            "inside" -> {
                val plane = planes.getValue(relation.objects[0].text)
                val signed = signedDistanceToPlane(p(relation.subjects[0]), p(plane.a), p(plane.b), p(plane.c))
                max(0.0, (relation.number ?: 0.0) - signed)
            }
            "coincident" -> (p(relation.subjects[0]) - p(relation.objects[0])).norm()
            "mirrors" -> {
                val plane = planes.getValue(relation.objects[1].text)
                val reflected = reflect(p(relation.subjects[0]), p(plane.a), p(plane.b), p(plane.c))
                (reflected - p(relation.objects[0])).norm()
            }
            "at" -> {
                val f = relation.number!!
                val u = p(relation.objects[0])
                val v = p(relation.objects[1])
                (p(relation.subjects[0]) - (u * (1.0 - f) + v * f)).norm()
            }
            // Only the axes the statement named. Measuring all three would report the drift of a
            // coordinate that `fix … in xy` deliberately left free, and at a lock's weight that number
            // dominates every real residual and makes the "what is still fighting" readout useless.
            "fix" -> {
                val axes = relation.axes ?: "xyz"
                relation.subjects.maxOf { subject ->
                    val now = p(subject)
                    val seed = program.seeds.getValue(subject.text)
                    var worst = 0.0
                    if ('x' in axes) worst = max(worst, abs(now.x - seed.x))
                    if ('y' in axes) worst = max(worst, abs(now.y - seed.y))
                    if ('z' in axes) worst = max(worst, abs(now.z - seed.z))
                    worst
                }
            }
            // A weak prior's target follows the point, so its residual is identically zero and saying
            // so is more honest than reporting a number that means nothing.
            "hold", "all-loose" -> Double.NaN
            "all-above-ground" -> at.values.maxOfOrNull { max(0.0, -it.z) } ?: 0.0
            else -> Double.NaN
        }
    }
}

private fun evaluateTerm(
    term: BriefTerm,
    p: (BriefName) -> Vec3,
    len: (BriefName) -> Double,
    edge: (BriefName) -> Pair<Vec3, Vec3>,
    planes: Map<String, BriefDeclaration.Plane>,
    panels: Map<String, BriefDeclaration.Panel>,
    at: Map<String, Vec3>,
): Double = when (term) {
    is BriefTerm.Literal -> term.value
    is BriefTerm.Property -> when (term.property) {
        "length" -> len(term.element)
        "area" -> panels.getValue(term.element.text).let { quadArea(p(it.a), p(it.b), p(it.c), p(it.d)) }
        "x" -> p(term.element).x
        "y" -> p(term.element).y
        else -> p(term.element).z
    }
    is BriefTerm.Call -> when (term.function) {
        "distance" -> (p(term.arguments[1]) - p(term.arguments[0])).norm()
        "angle" -> {
            val (a, b) = edge(term.arguments[0])
            val (c, d) = edge(term.arguments[1])
            acos(cosBetween(b - a, d - c).coerceIn(-1.0, 1.0))
        }
        "cos-angle", "dot" -> {
            val (a, b) = edge(term.arguments[0])
            val (c, d) = edge(term.arguments[1])
            cosBetween(b - a, d - c)
        }
        "raw-dot" -> {
            val (a, b) = edge(term.arguments[0])
            val (c, d) = edge(term.arguments[1])
            (b - a).dot(d - c)
        }
        "dihedral" -> {
            val (e0, e1) = edge(term.arguments[0])
            val n1 = (e1 - e0).cross(p(term.arguments[1]) - e0)
            val n2 = (e1 - e0).cross(p(term.arguments[2]) - e0)
            cosBetween(n1, n2)
        }
        else -> Double.NaN
    }
}

// --- small vector helpers, local so this file has no dependency beyond Vec3 -------------------

private operator fun Vec3.minus(other: Vec3) = Vec3(x - other.x, y - other.y, z - other.z)
private operator fun Vec3.plus(other: Vec3) = Vec3(x + other.x, y + other.y, z + other.z)
private operator fun Vec3.times(s: Double) = Vec3(x * s, y * s, z * s)
private fun Vec3.dot(other: Vec3) = x * other.x + y * other.y + z * other.z
private fun Vec3.cross(other: Vec3) =
    Vec3(y * other.z - z * other.y, z * other.x - x * other.z, x * other.y - y * other.x)

private fun Vec3.norm() = sqrt(dot(this))

private fun Vec3.unit(): Vec3 {
    val n = norm()
    return if (n < 1e-12) Vec3(0.0, 0.0, 0.0) else this * (1.0 / n)
}

private fun cosBetween(a: Vec3, b: Vec3): Double {
    val na = a.norm()
    val nb = b.norm()
    return if (na < 1e-12 || nb < 1e-12) Double.NaN else a.dot(b) / (na * nb)
}

private fun sinBetween(a: Vec3, b: Vec3): Double {
    val na = a.norm()
    val nb = b.norm()
    return if (na < 1e-12 || nb < 1e-12) Double.NaN else a.cross(b).norm() / (na * nb)
}

private fun planeNormal(a: Vec3, b: Vec3, c: Vec3): Vec3 = (b - a).cross(c - a)

private fun signedDistanceToPlane(p: Vec3, a: Vec3, b: Vec3, c: Vec3): Double =
    planeNormal(a, b, c).unit().dot(p - a)

private fun reflect(p: Vec3, a: Vec3, b: Vec3, c: Vec3): Vec3 {
    val n = planeNormal(a, b, c).unit()
    return p - n * (2.0 * n.dot(p - a))
}

private fun distancePointToLine(p: Vec3, a: Vec3, b: Vec3): Double {
    val d = b - a
    val n = d.norm()
    return if (n < 1e-12) (p - a).norm() else (p - a).cross(d).norm() / n
}

private fun quadArea(a: Vec3, b: Vec3, c: Vec3, d: Vec3): Double =
    0.5 * (b - a).cross(c - a).norm() + 0.5 * (c - a).cross(d - a).norm()

/** Deviation from the best-fit plane, as the largest signed distance — the geometric residual. */
private fun planarityDeviation(points: List<Vec3>): Double {
    if (points.size < 4) return 0.0
    val centroid = points.fold(Vec3(0.0, 0.0, 0.0)) { acc, p -> acc + p } * (1.0 / points.size)
    // The normal that minimises squared deviation is the smallest eigenvector of the scatter matrix.
    // For the sizes a brief produces, a few power iterations on the inverse are overkill; take the
    // best of the candidate normals through the centroid, which is exact for a quad and tight above.
    var best = Double.MAX_VALUE
    for (i in points.indices) {
        for (j in points.indices) {
            for (k in points.indices) {
                if (i >= j || j >= k) continue
                val n = planeNormal(points[i], points[j], points[k])
                if (n.norm() < 1e-12) continue
                val u = n.unit()
                val worst = points.maxOf { abs(u.dot(it - centroid)) }
                if (worst < best) best = worst
            }
        }
    }
    return if (best == Double.MAX_VALUE) 0.0 else best
}

private fun spread(values: List<Double>): Double =
    if (values.isEmpty()) 0.0 else (values.max() - values.min())
