package afield.datalog

/**
 * The abstract syntax of Field's Datalog — see [Datalog] for the facade, [DatalogParser] for the
 * concrete syntax and [DatalogEngine] for what evaluates this.
 *
 * Everything here is a value type with structural equality, because the engine leans on that in two
 * places: rules are de-duplicated when the same text is executed twice (a live box re-runs its whole
 * program on every keystroke-triggered execute), and derived rows are stored in hash sets.
 *
 * Names are `Dl`-prefixed. Boxes get `import afield.datalog.*` for free (see
 * `afield.intro`), on top of `afield.*` and `afield.stdlib.*`, so an unprefixed `Var`, `Atom` or
 * `Rule` in this package would be a name collision waiting to happen in user code.
 */

/** A Datalog term: either a variable or a constant. */
sealed interface DlTerm

/**
 * A logic variable. `_` in source becomes a *fresh* [DlVar] per occurrence (named `_1`, `_2`, …),
 * which is what makes `emp(_, D, _)` mean "some row" rather than "a row whose first and third
 * columns are equal".
 */
data class DlVar(val name: String) : DlTerm {
    val isAnonymous: Boolean get() = name.startsWith("_")
    override fun toString() = name
}

/**
 * A constant. Values are normalised by [dlNormalize]: a bare lowercase identifier in source is a
 * [String], so `edge(a, b)` and `edge("a", "b")` are the same fact, and any integral number is a
 * `Long` however it was written or computed.
 */
data class DlConst(val value: Any?) : DlTerm {
    override fun toString() = dlFormat(value)
}

/** An arithmetic/string expression, usable on either side of a comparison or bind. */
sealed interface DlArith

data class DlAValue(val term: DlTerm) : DlArith
data class DlABinary(val op: String, val left: DlArith, val right: DlArith) : DlArith
data class DlAUnary(val op: String, val operand: DlArith) : DlArith
data class DlACall(val fn: String, val args: List<DlArith>) : DlArith

/** A body item. Heads are always a positive [DlAtom]. */
sealed interface DlLiteral

/** `pred(t1, …, tn)`, or `!pred(…)` / `not pred(…)` when [negated]. */
data class DlAtom(val pred: String, val terms: List<DlTerm>, val negated: Boolean = false) : DlLiteral {
    val arity: Int get() = terms.size
    override fun toString() =
        (if (negated) "!" else "") + pred + "(" + terms.joinToString(", ") { it.toString() } + ")"
}

/**
 * `a <op> b` for `< <= > >= != =`.
 *
 * `X = <expr>` is parsed as a comparison and *becomes* a binding at planning time when `X` is not
 * yet bound at that point in the body — the planner decides, not the parser, because the answer
 * depends on the order the planner picks for the other literals.
 */
data class DlCompare(val op: String, val left: DlArith, val right: DlArith) : DlLiteral

/**
 * `Target = <op> <over> : { body }` — an aggregate over the solutions of `body`, grouped by
 * whichever of `body`'s variables are already bound by the enclosing rule.
 *
 * `over` is null for `count`. Ops: `count sum min max mean collect`.
 */
data class DlAggregate(
    val target: DlVar,
    val op: String,
    val over: DlArith?,
    val body: List<DlLiteral>,
) : DlLiteral

/** `head :- body.`, or `head.` when [body] is empty (a fact). [source] is for error messages. */
data class DlRule(val head: DlAtom, val body: List<DlLiteral>, val source: String? = null) {
    val isFact: Boolean get() = body.isEmpty()
    override fun toString() =
        if (body.isEmpty()) "$head." else "$head :- " + body.joinToString(", ") { dlLiteralText(it) } + "."
}

/** A parsed `?- goal.` directive. */
data class DlQuery(val body: List<DlLiteral>, val source: String? = null)

/** A `#directive` line — the box language's REPL commands (`#reset`, `#relations`, `#boxes`, `#why …`). */
data class DlDirective(val name: String, val argument: String, val line: Int)

/**
 * One statement of a program and the lines it spans ([firstLine]..[lastLine], 1-based): exactly one of
 * [rule] (a fact is a rule with no body), [query] or [directive]. See `DatalogParser.statements`.
 */
class DlStatement(
    val firstLine: Int,
    val lastLine: Int,
    val rule: DlRule? = null,
    val query: DlQuery? = null,
    val directive: DlDirective? = null,
)

/** Everything one chunk of source contributed. */
data class DlUnit(
    val rules: List<DlRule> = emptyList(),
    val queries: List<DlQuery> = emptyList(),
    val directives: List<DlDirective> = emptyList(),
)

/** A parse, safety or stratification failure. [line] is 1-based; -1 when not known. */
class DlException(message: String, val line: Int = -1, val column: Int = -1) : RuntimeException(message) {
    override fun toString() = if (line >= 0) "line $line: $message" else message ?: "datalog error"
}

// ── value normalisation and printing ────────────────────────────────────────────────────────────

/**
 * Coerce a host value into the small set of types the engine joins on: `Long`, `Double`, `String`,
 * `Boolean`, `null`, or an opaque object compared by `equals`.
 *
 * What matters is that **every integral number becomes a `Long`**, including a `Double` that
 * happens to be integral. Without it a computed `4.0 / 2.0` would not join with a fact containing
 * `2`, and a Datalog whose arithmetic results silently fail to match its facts is worse than
 * useless. The cost, accepted, is that `2.0` prints as `2`.
 */
fun dlNormalize(value: Any?): Any? = when (value) {
    null -> null
    is Long, is String, is Boolean -> value
    is Int -> value.toLong()
    is Short -> value.toLong()
    is Byte -> value.toLong()
    is Char -> value.toString()
    is Float -> dlNormalize(value.toDouble())
    is Double -> if (value.isFinite() && value == Math.rint(value) && Math.abs(value) < 9.007199254740992E15) {
        value.toLong()
    } else {
        value
    }
    is java.math.BigInteger -> value.toLong()
    is java.math.BigDecimal -> dlNormalize(value.toDouble())
    else -> value
}

/** How a value appears in output tables and in `toString` of an atom. Strings are unquoted here. */
fun dlFormat(value: Any?): String = when (value) {
    null -> "null"
    is String -> value
    is Double -> if (value.isNaN()) "nan" else value.toString()
    is List<*> -> value.joinToString(", ", "[", "]") { dlFormat(it) }
    else -> value.toString()
}

/** How a value appears when a fact is printed back as source — strings are quoted and escaped. */
fun dlSourceFormat(value: Any?): String = when (value) {
    is String ->
        if (value.isNotEmpty() && value[0].isLowerCase() && value.all { it.isLetterOrDigit() || it == '_' }) value
        else "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\t", "\\t") + "\""
    else -> dlFormat(value)
}

internal fun dlLiteralText(literal: DlLiteral): String = when (literal) {
    is DlAtom -> literal.toString()
    is DlCompare -> "${dlArithText(literal.left)} ${literal.op} ${dlArithText(literal.right)}"
    is DlAggregate -> buildString {
        append(literal.target.name).append(" = ").append(literal.op)
        literal.over?.let { append(' ').append(dlArithText(it)) }
        append(" : { ").append(literal.body.joinToString(", ") { dlLiteralText(it) }).append(" }")
    }
}

internal fun dlArithText(arith: DlArith): String = when (arith) {
    is DlAValue -> arith.term.toString()
    is DlABinary -> "(${dlArithText(arith.left)} ${arith.op} ${dlArithText(arith.right)})"
    is DlAUnary -> "${arith.op}${dlArithText(arith.operand)}"
    is DlACall -> "${arith.fn}(${arith.args.joinToString(", ") { dlArithText(it) }})"
}

/**
 * Replace the variables [bindings] knows about with the values they took, leaving the rest as
 * variables.
 *
 * This exists so a proof can print the *instantiated* form of a literal — `child(a, C)` rather than
 * `child(P, C)` — beside the group it produced. A reader shown `N = count : { child(P, C) }` above a
 * list of tuples has to work out which `P` this was; shown `N = count : { child(a, C) }` they do
 * not. Rendering only; never fed back to the engine, which plans over the original.
 */
internal fun dlSubstitute(literal: DlLiteral, bindings: Map<String, Any?>): DlLiteral = when (literal) {
    is DlAtom -> literal.copy(terms = literal.terms.map { dlSubstituteTerm(it, bindings) })
    is DlCompare -> literal.copy(
        left = dlSubstituteArith(literal.left, bindings),
        right = dlSubstituteArith(literal.right, bindings),
    )
    is DlAggregate -> literal.copy(
        over = literal.over?.let { dlSubstituteArith(it, bindings) },
        body = literal.body.map { dlSubstitute(it, bindings) },
    )
}

private fun dlSubstituteTerm(term: DlTerm, bindings: Map<String, Any?>): DlTerm = when (term) {
    is DlConst -> term
    is DlVar -> if (bindings.containsKey(term.name)) DlConst(bindings[term.name]) else term
}

internal fun dlSubstituteArith(arith: DlArith, bindings: Map<String, Any?>): DlArith = when (arith) {
    is DlAValue -> DlAValue(dlSubstituteTerm(arith.term, bindings))
    is DlABinary -> arith.copy(
        left = dlSubstituteArith(arith.left, bindings),
        right = dlSubstituteArith(arith.right, bindings),
    )
    is DlAUnary -> arith.copy(operand = dlSubstituteArith(arith.operand, bindings))
    is DlACall -> arith.copy(args = arith.args.map { dlSubstituteArith(it, bindings) })
}

/** Every variable mentioned, in first-appearance order. */
internal fun dlVarsOf(literals: List<DlLiteral>): List<DlVar> {
    val out = LinkedHashSet<DlVar>()
    fun arith(a: DlArith) {
        when (a) {
            is DlAValue -> (a.term as? DlVar)?.let { out.add(it) }
            is DlABinary -> { arith(a.left); arith(a.right) }
            is DlAUnary -> arith(a.operand)
            is DlACall -> a.args.forEach { arith(it) }
        }
    }
    fun walk(list: List<DlLiteral>) {
        list.forEach { literal ->
            when (literal) {
                is DlAtom -> literal.terms.forEach { t -> (t as? DlVar)?.let { out.add(it) } }
                is DlCompare -> { arith(literal.left); arith(literal.right) }
                is DlAggregate -> { out.add(literal.target); literal.over?.let { arith(it) }; walk(literal.body) }
            }
        }
    }
    walk(literals)
    return out.toList()
}

/**
 * The variables a body *answers with*: everything at the top level, plus aggregate targets, but not
 * the variables local to an aggregate's subgoal.
 *
 * [dlVarsOf] cannot be used for this. `S = sum P : { emp(_, D, P) }` mentions `P`, but `P` is bound
 * only inside the aggregate, so putting it in a query's column list makes the synthesised query rule
 * unsafe — which is exactly the error the first version of `Datalog.query` produced.
 */
internal fun dlOutputVarsOf(literals: List<DlLiteral>): List<DlVar> {
    val out = LinkedHashSet<DlVar>()
    fun arith(a: DlArith) {
        when (a) {
            is DlAValue -> (a.term as? DlVar)?.let { out.add(it) }
            is DlABinary -> { arith(a.left); arith(a.right) }
            is DlAUnary -> arith(a.operand)
            is DlACall -> a.args.forEach { arith(it) }
        }
    }
    literals.forEach { literal ->
        when (literal) {
            is DlAtom -> literal.terms.forEach { t -> (t as? DlVar)?.let { out.add(it) } }
            is DlCompare -> { arith(literal.left); arith(literal.right) }
            is DlAggregate -> out.add(literal.target)
        }
    }
    return out.toList()
}
