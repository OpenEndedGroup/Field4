package afield.stdlib.polytope

enum class ConwaySeed(val symbol: Char) {
    TETRAHEDRON('T'),
    CUBE('C'),
    OCTAHEDRON('O'),
    ICOSAHEDRON('I'),
    DODECAHEDRON('D'),
    ;

    fun create(): PolyhedralObject = when (this) {
        TETRAHEDRON -> tetrahedron()
        CUBE -> cube()
        OCTAHEDRON -> octahedron()
        ICOSAHEDRON -> icosahedron()
        DODECAHEDRON -> dodecahedron()
    }
}

class ConwayParseException(val offset: Int, message: String) : IllegalArgumentException(
    "Conway parse error at offset $offset: $message",
)

/** Parsed standard prefix expression. [operators] are stored in execution order, seed outward. */
data class ConwayInvocation(
    val operator: ConwayOperator,
    val degree: Int? = null,
)

data class ConwayExpression(
    val seed: ConwaySeed,
    val invocations: List<ConwayInvocation>,
    val source: String,
) {
    val operators: List<ConwayOperator> get() = invocations.map { it.operator }

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

fun conway(
    expression: String,
    attributePolicy: AttributePropagationPolicy = PreserveCommonAttributes,
): PolytopeTransformResult = parseConwayExpression(expression).evaluate(attributePolicy)
