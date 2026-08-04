@file:afield.CompletionCategory("Polytopes")

package afield.stdlib.polytope

/**
 * Conway prefix notation: the one-line string that names a polyhedron by how it is built.
 *
 * `conway("bkC")` reads right to left — start from the cube, kis it, bevel that — and hands back a
 * [PolytopeTransformResult] with the full lineage attached. That is the whole public surface most
 * callers need. [parseConwayExpression] exposes the parse on its own, for a UI that wants to report a
 * bad character's offset before running anything, and `canonicalConway` is the same evaluation
 * followed by a canonical embedding.
 *
 * ## The notation this accepts
 *
 * A final seed letter — `T C O I D` for the five Platonic solids — preceded by any number of the
 * operator letters in [ConwayOperator]. Whitespace is ignored anywhere. Two operators additionally
 * accept a **degree selector** written as digits after the letter: `k5D` kises only the pentagonal
 * faces, `t3C` truncates only the degree-3 vertices. That is a Field extension of the notation, built
 * on [ElementSelectors]; digits after any other operator are a parse error rather than a silent
 * ignore, and so is a digit with no operator to attach to.
 *
 * ## What it will not do
 *
 * There is no grouping, no repetition count and no seed beyond the five Platonics — no `(ka)3T`, no
 * prisms, no antiprisms. Coordinates come from each operator's default realization policy, so a long
 * expression evaluates to something combinatorially exact and geometrically drifted; reach for
 * `canonicalConway` when you want a solid you would recognise.
 */

/**
 * The five Platonic seeds a Conway expression can start from, each with the capital letter the
 * notation names it by.
 *
 * These are the only seeds the parser knows. Anything else — a prism, an imported mesh, a topology you
 * built by hand — has to enter through [PolyhedralObject] and the operator extension functions rather
 * than through a notation string.
 */
enum class ConwaySeed(val symbol: Char) {
    TETRAHEDRON('T'),
    CUBE('C'),
    OCTAHEDRON('O'),
    ICOSAHEDRON('I'),
    DODECAHEDRON('D'),
    ;

    /**
     * A fresh realized seed, from the constructors in `Solids.kt`. Called once per evaluation, so two
     * evaluations of the same expression share nothing.
     */
    fun create(): PolyhedralObject = when (this) {
        TETRAHEDRON -> tetrahedron()
        CUBE -> cube()
        OCTAHEDRON -> octahedron()
        ICOSAHEDRON -> icosahedron()
        DODECAHEDRON -> dodecahedron()
    }
}

/**
 * A Conway string the parser could not read, with [offset] pointing at the character in the *original*
 * string — whitespace included — so an editor can underline it.
 */
class ConwayParseException(val offset: Int, message: String) : IllegalArgumentException(
    "Conway parse error at offset $offset: $message",
)

/**
 * One operator as written, with the optional degree selector that followed it.
 *
 * `degree` is non-null only for `k` and `t`, the two operators the parser accepts a selector for; it
 * is the face degree (for kis) or vertex degree (for truncate) to restrict the operator to.
 */
data class ConwayInvocation(
    val operator: ConwayOperator,
    val degree: Int? = null,
)

/**
 * A parsed Conway expression, ready to evaluate more than once.
 *
 * [invocations] are stored in **execution order, seed outward** — the reverse of how they are
 * written — so `daC` parses to `[ambo, dual]`. [source] is kept verbatim so an error or a label can
 * quote what the user typed.
 */
data class ConwayExpression(
    val seed: ConwaySeed,
    val invocations: List<ConwayInvocation>,
    val source: String,
) {
    /**
     * Just the operators, execution order, degree selectors dropped. Enough to print the expression's
     * shape; not enough to re-evaluate it.
     */
    val operators: List<ConwayOperator> get() = invocations.map { it.operator }

    /**
     * Run the expression, seed outward, and return the chain with lineage and history attached.
     *
     * A degree selector that matches nothing is not an error: `k7C` finds no heptagonal faces on a cube
     * and kises none of them, so the result is the cube's combinatorics rebuilt with fresh element ids.
     * Evaluating twice gives two independent results.
     */
    fun evaluate(
        attributePolicy: AttributePropagationPolicy = PreserveCommonAttributes,
    ): PolytopeTransformResult {
        var result = PolytopeTransformResult.seed(seed.create())
        for (invocation in invocations) {
            result = when {
                invocation.degree == null -> result.transform(invocation.operator, attributePolicy = attributePolicy)
                invocation.operator == ConwayOperator.KIS -> result.kis(
                    ElementSelectors.faceDegree(invocation.degree),
                    attributePolicy = attributePolicy,
                )
                invocation.operator == ConwayOperator.TRUNCATE -> result.truncate(
                    ElementSelectors.vertexDegree(invocation.degree),
                    attributePolicy = attributePolicy,
                )
                else -> error("indexed ${invocation.operator.displayName} reached evaluation")
            }
        }
        return result
    }
}

/**
 * Parse a Platonic seed (`T`, `C`, `O`, `I`, `D`) with the supported Conway prefix operators.
 *
 * Conway prefix notation is evaluated right-to-left: `daC` is `d(a(C))`. Whitespace is ignored.
 */
fun parseConwayExpression(expression: String): ConwayExpression {
    val tokens = expression.mapIndexedNotNull { index, symbol ->
        if (symbol.isWhitespace()) null else index to symbol
    }
    if (tokens.isEmpty()) throw ConwayParseException(0, "expected an expression")
    val (seedOffset, seedSymbol) = tokens.last()
    val seed = ConwaySeed.entries.firstOrNull { it.symbol == seedSymbol }
        ?: throw ConwayParseException(seedOffset, "expected final seed T, C, O, I, or D")
    val operatorTokens = tokens.dropLast(1)
    val writtenInvocations = mutableListOf<ConwayInvocation>()
    var at = 0
    while (at < operatorTokens.size) {
        val (offset, symbol) = operatorTokens[at]
        val operator = ConwayOperator.entries.firstOrNull { it.symbol == symbol }
            ?: throw ConwayParseException(
                offset,
                if (symbol.isDigit()) "degree '$symbol' is not attached to an operator"
                else "unknown operator '$symbol'; expected ${ConwayOperator.entries.joinToString(", ") { it.symbol.toString() }}",
            )
        at++
        val digits = StringBuilder()
        val degreeOffset = operatorTokens.getOrNull(at)?.first ?: offset
        while (at < operatorTokens.size && operatorTokens[at].second.isDigit()) {
            digits.append(operatorTokens[at].second)
            at++
        }
        val degree = digits.takeIf { it.isNotEmpty() }?.toString()?.toIntOrNull()
            ?: if (digits.isEmpty()) null else throw ConwayParseException(degreeOffset, "degree is too large")
        if (degree != null) {
            if (operator !in setOf(ConwayOperator.KIS, ConwayOperator.TRUNCATE)) {
                throw ConwayParseException(offset, "only kis and truncate currently accept a degree selector")
            }
            val minimum = if (operator == ConwayOperator.KIS) 3 else 2
            if (degree < minimum) {
                throw ConwayParseException(degreeOffset, "${operator.displayName} degree must be at least $minimum")
            }
        }
        writtenInvocations += ConwayInvocation(operator, degree)
    }
    return ConwayExpression(seed, writtenInvocations.reversed(), expression)
}

/**
 * Parse and evaluate a Conway expression in one call — the everyday entry point.
 *
 * Throws [ConwayParseException] for a malformed string, before any geometry is built. `canonicalConway`
 * is this followed by [PolyhedralObject.canonicalize], which is usually what you want if the result is
 * going to be looked at rather than counted.
 */
fun conway(
    expression: String,
    attributePolicy: AttributePropagationPolicy = PreserveCommonAttributes,
): PolytopeTransformResult = parseConwayExpression(expression).evaluate(attributePolicy)
