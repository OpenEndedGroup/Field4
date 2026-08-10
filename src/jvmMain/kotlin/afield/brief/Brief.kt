@file:afield.CompletionCategory("Solvers")

package afield.brief

import kotlin.math.abs
import kotlin.math.min

/**
 * Brief: a weighted constraint notation.
 *
 * A brief is what an architect writes before there is a building — a statement of what has to be
 * true, in an order nobody promised, about things that do not exist yet. That is the shape of this
 * language and the reason for its name. `afield.stdlib.ceres.ConstraintScope` already holds a
 * complete weighted constraint vocabulary, and until this package the only way to reach a single verb
 * of it was to write Kotlin — a `GeometryModelScope.() -> Unit` receiver lambda that no other
 * language in Field can construct, not even through FieldML's `hostCall`. Brief is that vocabulary as
 * text.
 *
 * A program is a `#brief`-tagged region — a Kotlin triple-quoted string whose first line is `#brief`,
 * the same Route-A shape RectSL uses — or any string handed to [compileBrief]:
 *
 * ```
 * #brief
 * point west  at (0, 0)
 * point east  at (9, 0)
 * fix west east
 *
 * prefer spine at 1/3 along (west, east)
 * insist distance(west, spine) = 6
 * ```
 *
 * Four things about the design, because each is a decision rather than an accident.
 *
 * **Weight is a number, not a mood.** Every constraint statement starts with a *strength*, and the
 * slot takes either a word from a decade ladder ([BriefStrength]) or a bare number. `prefer` and
 * `insist` differ by a factor of a thousand, and the solved answer moves accordingly — that is the
 * whole point, and the fact that the ladder words are only names for numbers is the reason a bare
 * number is accepted in the same position.
 *
 * **Forward reference is the normal case.** Lines, planes and panels resolve in a second pass, so a
 * relation may cite geometry declared below it. Points go further and are *declared on use*: naming a
 * point that was never declared creates one, seeded on a deterministic golden-angle circle so a brief
 * of pure relations starts from a non-degenerate spread rather than a pile at the origin. Which points
 * were invented is reported ([BriefProgram.inventedPoints]) rather than left silent.
 *
 * **Two kinds of relation, one statement shape.** `spine.length = 4` is a relation over a *property*;
 * `spine parallel ridge` is a relation over *elements*. Both are `strength relation`, and a relation
 * is an equation when a top-level `=`, `>=` or `<=` appears and a predicate otherwise. Properties and
 * functions of elements are first-class *terms*, which is what lets `west.length = east.length` and
 * `angle(spine, ridge) = 30deg` sit in one grammar. It is not a total unification: `planar`, `mirrors`
 * and `fix` have no scalar left-hand side, and pretending otherwise would have meant inventing a
 * property nobody would look for. See `dev_docs/brief_notation.md`.
 *
 * **Diagnostics are located and named.** Every failure is a [BriefDiagnostic] with a line, a column, a
 * span and — where a name was misspelled — a did-you-mean drawn from the names actually declared.
 * [compileBrief] never throws a bare Kotlin exception at a program that merely reads wrong.
 *
 * [compileBrief] is a pure function of text: no `Box`, no output, no server. `BriefLowering.kt` turns
 * the result into a `GeometryModel` and solves it.
 */
object Brief {
    /** The first-line tag that marks a `#brief` embedded region. */
    const val TAG: String = "#brief"

    /** The language id, shared by the editor extension and the Monaco language. */
    const val ID: String = "brief"
}

// ---------------------------------------------------------------------------------------------
// Diagnostics
// ---------------------------------------------------------------------------------------------

enum class BriefSeverity { ERROR, WARNING }

/**
 * One located complaint about a program.
 *
 * [line] and [column] are 1-based and count the source *as handed to [compileBrief]*, including the
 * `#brief` tag line if there is one, so a column number can be trusted against the editor. [span] is
 * the length of the offending text, for underlining. [hint] is the actionable half and is separated
 * from [message] so a UI can show one and not the other.
 */
data class BriefDiagnostic(
    val severity: BriefSeverity,
    val line: Int,
    val column: Int,
    val span: Int,
    val message: String,
    val hint: String? = null,
) {
    /** `3:8 error: unknown line 'spien' — did you mean 'spine'?`, the form a box prints. */
    fun format(): String = buildString {
        append(line).append(':').append(column).append(' ')
        append(if (severity == BriefSeverity.ERROR) "error" else "warning").append(": ")
        append(message)
        hint?.let { append(" — ").append(it) }
    }

    override fun toString(): String = format()
}

/**
 * Thrown by [compileBrief] when a program has errors, carrying all of them rather than the first.
 *
 * The message is every diagnostic, one per line, because a brief with four misspellings should tell
 * you about four misspellings.
 */
class BriefException(val diagnostics: List<BriefDiagnostic>) :
    IllegalArgumentException(
        "brief has ${diagnostics.count { it.severity == BriefSeverity.ERROR }} error(s):\n" +
            diagnostics.joinToString("\n") { "  " + it.format() }
    )

// ---------------------------------------------------------------------------------------------
// Strength
// ---------------------------------------------------------------------------------------------

/**
 * The strength ladder: four words that are names for four numbers, three decades apart.
 *
 * The spacing is chosen, not arbitrary. [DEMAND] is `1e6` because that is
 * `afield.stdlib.ceres.ConstraintScope.lock`'s own default weight, so `demand` means exactly what the
 * Kotlin DSL already means by "hold this". [PREFER] is `1.0`, the DSL's default weight and the scale
 * a `length` residual is measured on. Three decades between rungs is far enough that a lower rung
 * loses a direct fight by a factor of a thousand — visible in a figure, not merely in a residual —
 * and close enough that a thousand `whisper`s can still outvote one `prefer`, which is the behaviour
 * a least-squares compromise should have.
 *
 * The warning that comes with it, inherited from the underlying vocabulary: **weights only compare
 * between residuals that share units.** An area residual is off every length residual by a factor of
 * length², so `insist roof.area = 12` against `prefer spine.length = 4` on a building measured in
 * millimetres is not a fight between 1e3 and 1e0. Where a brief mixes units, say the numbers.
 */
enum class BriefStrength(val word: String, val weight: Double) {
    /** `1e-3` — a tie-breaker. Loses to anything, shapes the answer where nothing else speaks. */
    WHISPER("whisper", 1e-3),

    /** `1e0` — the default, and the scale a length residual is measured on. */
    PREFER("prefer", 1.0),

    /** `1e3` — wins a direct fight against `prefer` by a factor of a thousand. */
    INSIST("insist", 1e3),

    /** `1e6` — `ConstraintScope.lock`'s own weight. Still soft; nothing here is a hard constraint. */
    DEMAND("demand", 1e6),
    ;

    companion object {
        val byWord: Map<String, BriefStrength> = entries.associateBy { it.word }

        /** What an unqualified statement means. */
        val DEFAULT: BriefStrength = PREFER
    }
}

// ---------------------------------------------------------------------------------------------
// Source positions and the AST
// ---------------------------------------------------------------------------------------------

/** Where something is, for a diagnostic. 1-based, counting the source as handed to the compiler. */
data class BriefSpan(val line: Int, val column: Int, val length: Int)

/** A name as written, with where it was written — so an unresolved name can point at itself. */
data class BriefName(val text: String, val at: BriefSpan) {
    override fun toString(): String = text
}

/** The kinds of thing a brief can declare. The resolver keys its namespaces on this. */
enum class BriefElementKind(val noun: String) {
    POINT("point"),
    LINE("line"),
    PLANE("plane"),
    PANEL("panel"),
}

/** A declaration statement. */
sealed interface BriefDeclaration {
    val name: BriefName
    val kind: BriefElementKind

    data class Point(override val name: BriefName, val x: Double, val y: Double, val z: Double) : BriefDeclaration {
        override val kind get() = BriefElementKind.POINT
    }

    data class Line(override val name: BriefName, val a: BriefName, val b: BriefName) : BriefDeclaration {
        override val kind get() = BriefElementKind.LINE
    }

    data class Plane(
        override val name: BriefName,
        val a: BriefName,
        val b: BriefName,
        val c: BriefName,
    ) : BriefDeclaration {
        override val kind get() = BriefElementKind.PLANE
    }

    data class Panel(
        override val name: BriefName,
        val a: BriefName,
        val b: BriefName,
        val c: BriefName,
        val d: BriefName,
    ) : BriefDeclaration {
        override val kind get() = BriefElementKind.PANEL
    }
}

/** The comparison in an equation. */
enum class BriefComparison(val symbol: String) { EQ("="), AT_LEAST(">="), AT_MOST("<=") }

/**
 * The left or right of an equation.
 *
 * [Literal] carries [radians] so `30deg` and `0.5236` are distinguishable *after* parsing: a cosine
 * target and an angle target are different constraints, and a program that writes `deg` where a
 * cosine was wanted deserves to be told.
 */
sealed interface BriefTerm {
    val at: BriefSpan

    data class Literal(val value: Double, val radians: Boolean, override val at: BriefSpan) : BriefTerm

    /** `spine.length`, `roof.area`, `ridge.z`. */
    data class Property(val element: BriefName, val property: String, override val at: BriefSpan) : BriefTerm

    /** `angle(spine, ridge)`, `distance(a, b)`, `dihedral(edge, a, b)`. */
    data class Call(val function: String, val arguments: List<BriefName>, override val at: BriefSpan) : BriefTerm
}

/** The relation half of a constraint statement. */
sealed interface BriefRelation {
    val at: BriefSpan

    /** `left <cmp> right` — a relation over properties. */
    data class Equation(
        val left: BriefTerm,
        val comparison: BriefComparison,
        val right: BriefTerm,
        override val at: BriefSpan,
    ) : BriefRelation

    /**
     * `subjects verb objects…` — a relation over elements.
     *
     * [verb] is one of [BriefVerbs.all]; [modifier] is the trailing `offset 0.3` / `clearance 0.2` /
     * `in xy` qualifier, already parsed. [vector] is the `(x, y, z)` a direction verb takes.
     */
    data class Predicate(
        val verb: String,
        val subjects: List<BriefName>,
        val objects: List<BriefName>,
        val number: Double? = null,
        val vector: Triple<Double, Double, Double>? = null,
        val axes: String? = null,
        override val at: BriefSpan,
    ) : BriefRelation
}

/** One `strength relation` line. [strengthWritten] is null when the statement took the default. */
data class BriefConstraint(
    val strength: Double,
    val strengthWritten: String?,
    val relation: BriefRelation,
    val at: BriefSpan,
) {
    /** The source line, reconstructed enough for a residual report to name the statement. */
    val label: String
        get() = (strengthWritten ?: BriefStrength.byWord.values.firstOrNull { it.weight == strength }?.word
            ?: trimNumber(strength)) + " " + describeRelation(relation)
}

/**
 * What a statement means when it does not say.
 *
 * `prefer` for everything, with two exceptions that exist because the Kotlin DSL already has them.
 * `fix` is `ConstraintScope.lock`, whose own default weight is `1e6`, and `hold` / `all loose` are
 * `weak` / `weakAll`, whose default is `1e-6`. Making a bare `fix p` mean `prefer` would have been
 * consistent and wrong: `lock` at weight 1 does not hold anything, and a notation whose commonest
 * fixture silently does nothing is worse than one with two documented exceptions. An explicit strength
 * always wins.
 */
internal fun defaultStrength(relation: BriefRelation): Double = when {
    relation !is BriefRelation.Predicate -> BriefStrength.DEFAULT.weight
    relation.verb == "fix" -> BriefStrength.DEMAND.weight
    relation.verb == "hold" || relation.verb == "all-loose" -> 1e-6
    else -> BriefStrength.DEFAULT.weight
}

internal fun describeRelation(relation: BriefRelation): String = when (relation) {
    is BriefRelation.Equation ->
        "${describeTerm(relation.left)} ${relation.comparison.symbol} ${describeTerm(relation.right)}"
    is BriefRelation.Predicate -> buildString {
        // A prefix verb reads before its subjects and an infix one after, so a label reproduces the
        // statement rather than a normalised form nobody typed.
        val prefix = relation.verb in BriefVerbs.prefix || relation.verb.startsWith("all-")
        if (prefix) append(relation.verb.replace("all-", "all "))
        if (relation.subjects.isNotEmpty()) {
            if (prefix) append(' ')
            append(relation.subjects.joinToString(" "))
            if (!prefix) append(' ')
        }
        if (!prefix) append(relation.verb)
        if (relation.objects.isNotEmpty()) append(' ').append(relation.objects.joinToString(" "))
        relation.axes?.let { append(" in ").append(it) }
        relation.number?.let { append(' ').append(trimNumber(it)) }
        relation.vector?.let { append(" (").append(trimNumber(it.first)).append(", ")
            .append(trimNumber(it.second)).append(", ").append(trimNumber(it.third)).append(')') }
    }
}

internal fun describeTerm(term: BriefTerm): String = when (term) {
    is BriefTerm.Literal -> if (term.radians) "${trimNumber(Math.toDegrees(term.value))}deg" else trimNumber(term.value)
    is BriefTerm.Property -> "${term.element.text}.${term.property}"
    is BriefTerm.Call -> "${term.function}(${term.arguments.joinToString(", ") { it.text }})"
}

internal fun trimNumber(value: Double): String =
    if (value == value.toLong().toDouble() && abs(value) < 1e15) value.toLong().toString() else value.toString()

/** A `settle` directive: the whole-cloud prior weight, or null for "none". */
data class BriefSettle(val weight: Double?, val at: BriefSpan)

/**
 * A `robust` directive: the loss every residual is wrapped in.
 *
 * A brief's default is `robust none` — plain least squares — and that default is a decision, not an
 * inheritance. The underlying `CeresProgram` defaults to `Huber(1e-3)`, under which the cost of a
 * residual larger than `1e-3` is *linear*, so two statements fighting over one unknown settle at the
 * weighted **median** of their targets rather than the mean, and the heavier one wins outright at any
 * ratio. A notation whose whole premise is that `prefer` and `insist` name different numbers cannot
 * ship on a loss that makes them name the same outcome. `robust huber 1e-3` gets the old behaviour
 * back, and the two are visibly different figures.
 */
data class BriefRobust(val kind: String, val scale: Double, val at: BriefSpan)

// ---------------------------------------------------------------------------------------------
// The vocabulary tables — one place, so the editor extension and the parser cannot disagree
// ---------------------------------------------------------------------------------------------

/**
 * Every reserved word in the notation, grouped the way a diagnostic wants to talk about them.
 *
 * Two properties this table is responsible for. It is what makes `NAME+ VERB` unambiguous — a
 * declaration may not name an element with a reserved word, so a leading run of names ends exactly
 * where a verb begins. And it is the vocabulary the editor extension is checked against, in both
 * directions, by `BriefEditorExtensionTest`, which is the shape-4 guard the anatomy page recommends
 * for anything mirrored across the JVM/browser split.
 */
object BriefVerbs {
    /** Verbs that come *after* their subjects: `spine parallel ridge`. */
    val infix: Set<String> = setOf(
        "parallel", "perpendicular", "same-vector", "on", "inside", "mirrors", "toward",
        "vanishes-at", "along", "faces", "coincident", "colinear", "at",
        "planar", "level", "same-x", "same-y", "same-z", "same-xy", "same-xz", "same-yz",
    )

    /** Verbs that come *first*: `fix west east`. */
    val prefix: Set<String> = setOf("fix", "hold", "all")

    /** Words that introduce a declaration. */
    val declarations: Set<String> = setOf("point", "line", "plane", "panel")

    /** Words that introduce a directive. */
    val directives: Set<String> = setOf("settle", "robust")

    /** The robust-loss kinds `robust` accepts, mapped to `RobustLoss.Kind`. */
    val losses: Set<String> = setOf("none", "huber", "soft-l1", "cauchy")

    /** Function names usable as an equation term. */
    val functions: Set<String> = setOf("distance", "angle", "cos-angle", "dihedral", "dot", "raw-dot")

    /** Properties reachable with `element.property`. */
    val properties: Set<String> = setOf("length", "area", "x", "y", "z")

    /** Glue words that are reserved but carry no relation of their own. */
    val glue: Set<String> = setOf(
        "at", "from", "to", "through", "corners", "across", "offset", "clearance", "in",
        "loose", "above-ground", "none", "deg",
    )

    val strengths: Set<String> = BriefStrength.byWord.keys

    /** Everything a program may not use as a name. */
    val all: Set<String> =
        infix + prefix + declarations + directives + functions + properties + glue + strengths + losses
}

// ---------------------------------------------------------------------------------------------
// Lexer
// ---------------------------------------------------------------------------------------------

internal enum class TokKind { WORD, NUMBER, PUNCT, EOL, EOF }

internal data class Tok(
    val kind: TokKind,
    val text: String,
    val line: Int,
    val column: Int,
    /** Set for NUMBER: the parsed value, and whether a `deg` suffix was written. */
    val value: Double = 0.0,
    val degrees: Boolean = false,
) {
    val at: BriefSpan get() = BriefSpan(line, column, text.length.coerceAtLeast(1))
}

/**
 * Text to tokens. Line-oriented: an EOL token ends every statement, so a missing word is reported at
 * the end of the line it is missing from rather than at the start of the next one.
 *
 * `#` begins a comment. A word may contain `-` (so `same-vector` and `above-ground` are single
 * tokens), digits and `_`, but may not begin with a digit. A number is `-?digits[.digits][e±digits]`
 * with an optional `deg` suffix; `1/3` lexes as three tokens and the parser folds the division, which
 * is what makes fractions readable without a rational literal.
 */
internal class BriefLexer(private val source: String) {
    private val diagnostics = mutableListOf<BriefDiagnostic>()

    fun diagnostics(): List<BriefDiagnostic> = diagnostics

    fun lex(): List<Tok> {
        val out = mutableListOf<Tok>()
        source.lines().forEachIndexed { index, rawLine ->
            val lineNumber = index + 1
            var i = 0
            // The `#brief` tag line, and any comment line, contribute nothing but their EOL.
            while (i < rawLine.length) {
                val ch = rawLine[i]
                when {
                    ch == '#' -> i = rawLine.length
                    ch.isWhitespace() -> i++
                    ch.isDigit() || (ch == '-' && i + 1 < rawLine.length && rawLine[i + 1].isDigit() &&
                        out.lastOrNull()?.let { it.kind == TokKind.EOL || it.text == "(" || it.text == "," } != false) -> {
                        val start = i
                        if (rawLine[i] == '-') i++
                        while (i < rawLine.length && (rawLine[i].isDigit() || rawLine[i] == '.')) i++
                        if (i < rawLine.length && (rawLine[i] == 'e' || rawLine[i] == 'E') &&
                            i + 1 < rawLine.length && (rawLine[i + 1].isDigit() || rawLine[i + 1] == '-' || rawLine[i + 1] == '+')
                        ) {
                            i += 2
                            while (i < rawLine.length && rawLine[i].isDigit()) i++
                        }
                        var degrees = false
                        if (rawLine.startsWith("deg", i)) {
                            degrees = true
                            i += 3
                        }
                        val text = rawLine.substring(start, i)
                        val numeric = if (degrees) text.dropLast(3) else text
                        val parsed = numeric.toDoubleOrNull()
                        if (parsed == null || !parsed.isFinite()) {
                            diagnostics += BriefDiagnostic(
                                BriefSeverity.ERROR, lineNumber, start + 1, text.length,
                                "'$text' is not a finite number",
                            )
                        } else {
                            out += Tok(TokKind.NUMBER, text, lineNumber, start + 1, parsed, degrees)
                        }
                    }
                    ch.isLetter() || ch == '_' -> {
                        val start = i
                        while (i < rawLine.length && (rawLine[i].isLetterOrDigit() || rawLine[i] == '_' || rawLine[i] == '-')) i++
                        // A trailing '-' is punctuation, not part of the word.
                        while (i > start + 1 && rawLine[i - 1] == '-') i--
                        out += Tok(TokKind.WORD, rawLine.substring(start, i), lineNumber, start + 1)
                    }
                    else -> {
                        val two = if (i + 1 < rawLine.length) rawLine.substring(i, i + 2) else ""
                        val text = if (two == ">=" || two == "<=") two else ch.toString()
                        if (text !in PUNCTUATION) {
                            diagnostics += BriefDiagnostic(
                                BriefSeverity.ERROR, lineNumber, i + 1, text.length,
                                "'$text' means nothing here",
                                "brief punctuation is ${PUNCTUATION.sorted().joinToString(" ")}",
                            )
                        } else {
                            out += Tok(TokKind.PUNCT, text, lineNumber, i + 1)
                        }
                        i += text.length
                    }
                }
            }
            out += Tok(TokKind.EOL, "end of line", lineNumber, rawLine.length + 1)
        }
        out += Tok(TokKind.EOF, "end of brief", source.lines().size, 1)
        return out
    }

    private companion object {
        val PUNCTUATION = setOf("(", ")", ",", ".", "=", ">=", "<=", "/", "-")
    }
}

// ---------------------------------------------------------------------------------------------
// Parser
// ---------------------------------------------------------------------------------------------

/**
 * The parsed, *unresolved* program: declarations, constraints and directives in source order.
 *
 * Unresolved on purpose. Nothing here has been checked against anything, which is what makes forward
 * reference a property of the *resolver* rather than a special case in the grammar.
 */
data class BriefSyntax(
    val declarations: List<BriefDeclaration>,
    val constraints: List<BriefConstraint>,
    val settle: BriefSettle?,
    val robust: BriefRobust?,
    val diagnostics: List<BriefDiagnostic>,
)

internal class BriefParser(private val toks: List<Tok>) {
    private var i = 0
    private val declarations = mutableListOf<BriefDeclaration>()
    private val constraints = mutableListOf<BriefConstraint>()
    private var settle: BriefSettle? = null
    private var robust: BriefRobust? = null
    private val diagnostics = mutableListOf<BriefDiagnostic>()

    fun parse(): BriefSyntax {
        while (peek().kind != TokKind.EOF) {
            if (peek().kind == TokKind.EOL) {
                i++
                continue
            }
            val lineNumber = peek().line
            try {
                statement()
            } catch (stop: Bail) {
                // Reported already; drop the rest of the line and carry on, so one bad line does not
                // hide the four good ones after it.
            }
            while (peek().kind != TokKind.EOL && peek().kind != TokKind.EOF && peek().line == lineNumber) i++
        }
        return BriefSyntax(declarations.toList(), constraints.toList(), settle, robust, diagnostics.toList())
    }

    private class Bail : RuntimeException(null, null, false, false)

    private fun peek(offset: Int = 0): Tok = toks[min(i + offset, toks.size - 1)]

    private fun error(at: BriefSpan, message: String, hint: String? = null): Nothing {
        diagnostics += BriefDiagnostic(BriefSeverity.ERROR, at.line, at.column, at.length, message, hint)
        throw Bail()
    }

    private fun expectWord(word: String): Tok {
        val t = peek()
        if (t.kind != TokKind.WORD || t.text != word) {
            error(t.at, "expected '$word', found '${t.text}'")
        }
        i++
        return t
    }

    private fun expectPunct(text: String): Tok {
        val t = peek()
        if (t.kind != TokKind.PUNCT || t.text != text) error(t.at, "expected '$text', found '${t.text}'")
        i++
        return t
    }

    /** A user name: any WORD that is not reserved. */
    private fun name(role: String): BriefName {
        val t = peek()
        if (t.kind != TokKind.WORD) error(t.at, "expected a $role name, found '${t.text}'")
        if (t.text in BriefVerbs.all) {
            error(
                t.at, "'${t.text}' is a brief keyword and cannot be a $role name",
                "rename it, or reorder the statement",
            )
        }
        i++
        return BriefName(t.text, t.at)
    }

    /** A number, folding `a / b` into a single value so `1/3` reads as a third. */
    private fun number(role: String): Pair<Double, Boolean> {
        val t = peek()
        if (t.kind != TokKind.NUMBER) error(t.at, "expected $role, found '${t.text}'")
        i++
        var value = if (t.degrees) Math.toRadians(t.value) else t.value
        var radians = t.degrees
        while (peek().kind == TokKind.PUNCT && peek().text == "/") {
            i++
            val d = peek()
            if (d.kind != TokKind.NUMBER) error(d.at, "expected a divisor after '/', found '${d.text}'")
            i++
            if (d.value == 0.0) error(d.at, "division by zero")
            value /= if (d.degrees) Math.toRadians(d.value) else d.value
            radians = radians || d.degrees
        }
        return value to radians
    }

    private fun vector(): Triple<Double, Double, Double> {
        expectPunct("(")
        val x = number("a coordinate").first
        expectPunct(",")
        val y = number("a coordinate").first
        var z = 0.0
        if (peek().kind == TokKind.PUNCT && peek().text == ",") {
            i++
            z = number("a coordinate").first
        }
        expectPunct(")")
        return Triple(x, y, z)
    }

    private fun statement() {
        val head = peek()
        if (head.kind == TokKind.WORD) {
            when (head.text) {
                "point", "line", "plane", "panel" -> return declaration(head.text)
                "settle" -> return settleDirective()
                "robust" -> return robustDirective()
            }
        }
        constraint()
    }

    private fun declaration(keyword: String) {
        i++
        val declared = name(keyword)
        when (keyword) {
            "point" -> {
                expectWord("at")
                val (x, y, z) = vector()
                declarations += BriefDeclaration.Point(declared, x, y, z)
            }
            "line" -> {
                expectWord("from")
                val a = name("point")
                expectWord("to")
                val b = name("point")
                declarations += BriefDeclaration.Line(declared, a, b)
            }
            "plane" -> {
                expectWord("through")
                declarations += BriefDeclaration.Plane(declared, name("point"), name("point"), name("point"))
            }
            "panel" -> {
                expectWord("corners")
                declarations += BriefDeclaration.Panel(
                    declared, name("point"), name("point"), name("point"), name("point"),
                )
            }
        }
    }

    private fun settleDirective() {
        val at = peek().at
        i++
        if (peek().kind == TokKind.WORD && peek().text == "none") {
            i++
            settle = BriefSettle(null, at)
            return
        }
        val (weight, _) = number("a prior weight, or 'none'")
        if (weight < 0.0) error(at, "a settle weight must not be negative")
        settle = BriefSettle(weight, at)
    }

    /**
     * `robust none` | `robust huber 1e-3` | `robust soft-l1 2` | `robust cauchy 0.5`.
     *
     * Reachable, and off by default, because the loss changes what a weight *means* — see [BriefRobust].
     */
    private fun robustDirective() {
        val at = peek().at
        i++
        val kindTok = peek()
        if (kindTok.kind != TokKind.WORD || kindTok.text !in BriefVerbs.losses) {
            error(
                kindTok.at, "'${kindTok.text}' is not a robust loss",
                "the losses are ${BriefVerbs.losses.sorted().joinToString(", ")}",
            )
        }
        i++
        var scale = 1e-3
        if (peek().kind == TokKind.NUMBER) {
            scale = number("a loss scale").first
            if (scale <= 0.0) error(at, "a loss scale must be positive")
        }
        robust = BriefRobust(kindTok.text, scale, at)
    }

    /**
     * `strength? relation`.
     *
     * The one ambiguity worth naming: a leading NUMBER is the *strength* unless the token after it
     * begins a comparison, a fraction or a property access — `250 spine.length = 4` weights a
     * statement, `4 = spine.length` would be an equation with a literal on the left. The rule is
     * lexical and stated in the docs rather than resolved by backtracking.
     */
    private fun constraint() {
        val start = peek().at
        var strength = BriefStrength.DEFAULT.weight
        var written: String? = null
        val head = peek()
        if (head.kind == TokKind.WORD && head.text in BriefStrength.byWord) {
            strength = BriefStrength.byWord.getValue(head.text).weight
            written = head.text
            i++
        } else if (head.kind == TokKind.NUMBER && peek(1).let {
                !(it.kind == TokKind.PUNCT && it.text in setOf("=", ">=", "<=", "/", "."))
            }
        ) {
            if (head.value < 0.0 || !head.value.isFinite()) {
                error(head.at, "a strength must be finite and non-negative, not ${trimNumber(head.value)}")
            }
            strength = head.value
            written = trimNumber(head.value)
            i++
        }

        val relation = relation()
        if (written == null) strength = defaultStrength(relation)
        constraints += BriefConstraint(strength, written, relation, start)
    }

    private fun relation(): BriefRelation {
        // An equation is recognised by a top-level comparison on this line. Scanning first is what
        // keeps the two relation kinds in one grammar without a backtracking parser.
        val line = peek().line
        var scan = i
        var comparisonAt = -1
        while (toks[scan].line == line && toks[scan].kind != TokKind.EOL && toks[scan].kind != TokKind.EOF) {
            if (toks[scan].kind == TokKind.PUNCT && toks[scan].text in setOf("=", ">=", "<=")) {
                comparisonAt = scan
                break
            }
            scan++
        }
        return if (comparisonAt >= 0) equation() else predicate()
    }

    private fun equation(): BriefRelation.Equation {
        val start = peek().at
        val left = term()
        val op = peek()
        val comparison = when {
            op.kind == TokKind.PUNCT && op.text == "=" -> BriefComparison.EQ
            op.kind == TokKind.PUNCT && op.text == ">=" -> BriefComparison.AT_LEAST
            op.kind == TokKind.PUNCT && op.text == "<=" -> BriefComparison.AT_MOST
            else -> error(op.at, "expected '=', '>=' or '<=', found '${op.text}'")
        }
        i++
        val right = term()
        return BriefRelation.Equation(left, comparison, right, start)
    }

    private fun term(): BriefTerm {
        val t = peek()
        if (t.kind == TokKind.NUMBER) {
            val at = t.at
            val (value, radians) = number("a value")
            return BriefTerm.Literal(value, radians, at)
        }
        if (t.kind != TokKind.WORD) error(t.at, "expected a value, a property or a function, found '${t.text}'")
        if (t.text in BriefVerbs.functions) {
            i++
            expectPunct("(")
            val args = mutableListOf<BriefName>()
            args += name("element")
            while (peek().kind == TokKind.PUNCT && peek().text == ",") {
                i++
                args += name("element")
            }
            expectPunct(")")
            return BriefTerm.Call(t.text, args, t.at)
        }
        val element = name("element")
        expectPunct(".")
        val prop = peek()
        if (prop.kind != TokKind.WORD || prop.text !in BriefVerbs.properties) {
            error(
                prop.at, "'${prop.text}' is not a brief property",
                "the properties are ${BriefVerbs.properties.sorted().joinToString(", ")}",
            )
        }
        i++
        return BriefTerm.Property(element, prop.text, BriefSpan(element.at.line, element.at.column,
            element.at.length + 1 + prop.text.length))
    }

    private fun predicate(): BriefRelation.Predicate {
        val start = peek().at
        val head = peek()
        if (head.kind == TokKind.WORD && head.text in BriefVerbs.prefix) return prefixPredicate()

        val subjects = mutableListOf<BriefName>()
        while (peek().kind == TokKind.WORD && peek().text !in BriefVerbs.all) subjects += name("element")
        if (subjects.isEmpty()) {
            val t = peek()
            error(
                t.at, "expected a statement, found '${t.text}'",
                "a brief line is a declaration, a 'settle', or '[strength] relation'",
            )
        }
        val verbTok = peek()
        if (verbTok.kind != TokKind.WORD || verbTok.text !in BriefVerbs.infix) {
            error(
                verbTok.at, "'${verbTok.text}' is not a brief relation",
                didYouMean(verbTok.text, BriefVerbs.infix + BriefVerbs.prefix)
                    ?: "the relations are ${BriefVerbs.infix.sorted().joinToString(", ")}",
            )
        }
        i++
        val verb = verbTok.text
        var objects = emptyList<BriefName>()
        var number: Double? = null
        var vec: Triple<Double, Double, Double>? = null

        when (verb) {
            "planar", "colinear", "level", "same-x", "same-y", "same-z", "same-xy", "same-xz", "same-yz" -> Unit
            "parallel", "perpendicular", "same-vector", "coincident", "toward", "vanishes-at" ->
                objects = listOf(name("element"))
            "on", "inside" -> {
                objects = listOf(name("plane"))
                if (peek().kind == TokKind.WORD && (peek().text == "offset" || peek().text == "clearance")) {
                    i++
                    number = number("a distance").first
                }
            }
            "mirrors" -> {
                objects = listOf(name("point"))
                expectWord("across")
                objects = objects + name("plane")
            }
            "along" -> vec = vector()
            "faces" -> {
                vec = vector()
                if (peek().kind == TokKind.NUMBER) number = number("a target cosine").first
            }
            "at" -> {
                number = number("a fraction").first
                expectWord("along")
                expectPunct("(")
                val u = name("point")
                expectPunct(",")
                val v = name("point")
                expectPunct(")")
                objects = listOf(u, v)
            }
        }
        return BriefRelation.Predicate(verb, subjects, objects, number, vec, null, start)
    }

    private fun prefixPredicate(): BriefRelation.Predicate {
        val start = peek().at
        val verb = peek().text
        i++
        if (verb == "all") {
            val what = peek()
            if (what.kind != TokKind.WORD || (what.text != "loose" && what.text != "above-ground")) {
                error(what.at, "expected 'loose' or 'above-ground' after 'all', found '${what.text}'")
            }
            i++
            return BriefRelation.Predicate("all-${what.text}", emptyList(), emptyList(), at = start)
        }
        val subjects = mutableListOf<BriefName>()
        while (peek().kind == TokKind.WORD && peek().text !in BriefVerbs.all) subjects += name("point")
        if (subjects.isEmpty()) error(peek().at, "'$verb' needs at least one point")
        var axes: String? = null
        if (peek().kind == TokKind.WORD && peek().text == "in") {
            i++
            val a = peek()
            if (a.kind != TokKind.WORD || a.text.lowercase() !in AXES) {
                error(
                    a.at, "'${a.text}' is not an axis set",
                    "the axis sets are ${AXES.sorted().joinToString(", ")}",
                )
            }
            i++
            axes = a.text.lowercase()
        }
        return BriefRelation.Predicate(verb, subjects, emptyList(), axes = axes, at = start)
    }

    private companion object {
        val AXES = setOf("x", "y", "z", "xy", "xz", "yz", "xyz")
    }
}

// ---------------------------------------------------------------------------------------------
// did-you-mean
// ---------------------------------------------------------------------------------------------

/**
 * The closest candidate within an edit distance that scales with the length of what was written.
 *
 * The threshold matters more than the metric: a fixed distance of 2 suggests `x` for `z` on a
 * one-letter name, which is worse than silence. So it is `1 + length/4`, capped, and a candidate must
 * also share a first letter or a length — enough to make `spien` → `spine` land and `foo` → `bar` not.
 */
internal fun didYouMean(written: String, candidates: Collection<String>): String? {
    if (written.isEmpty() || candidates.isEmpty()) return null
    val limit = 1 + written.length / 4
    val best = candidates
        .filter { it != written }
        .map { it to editDistance(written.lowercase(), it.lowercase()) }
        .filter { (candidate, distance) ->
            distance <= limit && (candidate.firstOrNull() == written.firstOrNull() || candidate.length == written.length)
        }
        .minByOrNull { it.second }
        ?: return null
    return "did you mean '${best.first}'?"
}

internal fun editDistance(a: String, b: String): Int {
    if (a.isEmpty()) return b.length
    if (b.isEmpty()) return a.length
    var previous = IntArray(b.length + 1) { it }
    var current = IntArray(b.length + 1)
    for (x in 1..a.length) {
        current[0] = x
        for (y in 1..b.length) {
            val substitution = previous[y - 1] + if (a[x - 1] == b[y - 1]) 0 else 1
            current[y] = minOf(current[y - 1] + 1, previous[y] + 1, substitution)
        }
        val swap = previous
        previous = current
        current = swap
    }
    return previous[b.length]
}
