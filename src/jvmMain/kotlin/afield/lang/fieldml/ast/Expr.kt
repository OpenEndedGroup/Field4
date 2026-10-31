package afield.lang.fieldml.ast

import afield.lang.fieldml.diag.SourceRange

/**
 * FieldML's surface syntax tree: one node per piece of written syntax, each carrying the source
 * range it was parsed from.
 *
 * [Parser] produces this tree and [Inferencer] reads it. Nothing in it has a type yet. Every node
 * here has a counterpart in `afield.lang.fieldml.type` that carries an inferred type, and that typed tree is
 * lowered again into the `afield.lang.fieldml.core` form the evaluator runs.
 *
 * The tree is narrower than the syntax in two places. [ECall] takes a single argument, so
 * application is already curried when it is parsed: `add 20 22` is `ECall(ECall(add, 20), 22)`. The
 * pipe operator builds that same node with its left operand as the argument, so `10 |> add 5` and
 * `add 5 10` differ only in the ranges they record. And a constructor's argument types are bare
 * names: [TypeRef] has one case, [TypeRefName], so a run of names in `type Tree a = Node Tree Tree`
 * reads as a run of separate arguments. Inference resolves each name to a built-in type, to the type
 * being declared, to one of that type's own parameters, or to a data type with no arguments.
 *
 * It is wider than the syntax in one place: [ELet] and [ELambda] keep the parameter list as it was
 * written, so `let add a b = ...` is one node with two parameters. Folding those into nested arrow
 * types is inference's job, and turning them into nested one-parameter lambdas is the lowering's.
 *
 * [ELet] and [ETypeDecl] both carry a `body`, and for a top-level binding that body is the whole
 * rest of the program: a box's bindings stack up by nesting inside each other. A `let` with nothing
 * after it gets `EVar("Unit")` for a body.
 */

/** A node of the surface tree, positioned by the [SourceRange] it was parsed from. */
sealed interface Expr {
    val range: SourceRange
}

/** An integer literal. */
data class EInt(val value: Long, override val range: SourceRange) : Expr

/** A floating-point literal. */
data class EFloat(val value: Double, override val range: SourceRange) : Expr

/** `true` or `false`. The value comes from which keyword was read, never from the text. */
data class EBool(val value: Boolean, override val range: SourceRange) : Expr

/** A string literal, with its quotes removed and its backslash escapes already decoded. */
data class EString(val value: String, override val range: SourceRange) : Expr

/**
 * A name used as an expression.
 *
 * It is also how two things that are not written as names arrive: `()` parses to `EVar("Unit")`,
 * and so does the body of a `let` with nothing after it.
 */
data class EVar(val name: String, override val range: SourceRange) : Expr

/**
 * Two or more comma-separated expressions in parentheses.
 *
 * A single parenthesised expression is grouping and leaves no node of its own, so this node always
 * has at least two elements.
 */
data class ETuple(val elements: List<Expr>, override val range: SourceRange) : Expr

/** A bracketed list of elements, which may be empty. */
data class EList(val elements: List<Expr>, override val range: SourceRange) : Expr

/**
 * A `type` declaration as written: the name being declared, the parameters it takes, and its
 * constructors.
 *
 * This is the one surface node that survives both later stages unchanged. Inference and the
 * lowering each keep it as it was parsed and record their own findings beside it, in
 * [TypedConstructor] and [CoreConstructor] respectively.
 */
data class TypeDecl(
    val name: String,
    val parameters: List<String>,
    val constructors: List<ConstructorDecl>,
    val range: SourceRange,
)

/** One constructor of a [TypeDecl], with its argument types as they were written. */
data class ConstructorDecl(
    val name: String,
    val arguments: List<TypeRef>,
    val range: SourceRange,
)

/** A type as written inside a constructor's argument list. */
sealed interface TypeRef {
    val name: String
    val range: SourceRange
}

/**
 * [TypeRef]'s only case: a bare name.
 *
 * Because there is no case for an application, a run of names reads as a run of separate arguments,
 * and deciding what each name refers to is inference's job.
 */
data class TypeRefName(
    override val name: String,
    override val range: SourceRange,
) : TypeRef

/** A `type` declaration together with the program that follows it, which is its `body`. */
data class ETypeDecl(
    val declaration: TypeDecl,
    val body: Expr,
    override val range: SourceRange,
) : Expr

/**
 * A `let` binding together with the program that follows it.
 *
 * `parameters` holds the function form's parameters as they were written, so `let add a b = ...` is
 * one node with two of them rather than a binding whose value is a lambda. For a top-level binding
 * the `body` is the whole rest of the program.
 */
data class ELet(
    val name: String,
    val parameters: List<String>,
    val value: Expr,
    val body: Expr,
    override val range: SourceRange,
) : Expr

/**
 * `fun x y -> ...`, keeping its parameter list as written.
 *
 * Turning several parameters into nested one-parameter lambdas is the lowering's job, not the
 * parser's.
 */
data class ELambda(
    val parameters: List<String>,
    val body: Expr,
    override val range: SourceRange,
) : Expr

/**
 * One argument applied to one function.
 *
 * Application is curried as it is parsed, so `add 20 22` is `ECall(ECall(add, 20), 22)`. The pipe
 * operator builds the same node with its left operand as the `argument`, which is why `10 |> add 5`
 * and `add 5 10` differ only in the ranges they record.
 */
data class ECall(
    val function: Expr,
    val argument: Expr,
    override val range: SourceRange,
) : Expr

/** `if ... then ... else ...`. There is no one-armed form, so both branches are always present. */
data class EIf(
    val condition: Expr,
    val thenBranch: Expr,
    val elseBranch: Expr,
    override val range: SourceRange,
) : Expr

/** The left-hand side of a `match` arm, positioned by the [SourceRange] it was parsed from. */
sealed interface Pattern {
    val range: SourceRange
}

/** `_`: matches anything and binds nothing. */
data class PWildcard(override val range: SourceRange) : Pattern

/** A lower-case name: matches anything and binds it for the arm's body. */
data class PVar(val name: String, override val range: SourceRange) : Pattern

/** An integer literal, matching that value. */
data class PInt(val value: Long, override val range: SourceRange) : Pattern

/** `true` or `false`, matching that value. */
data class PBool(val value: Boolean, override val range: SourceRange) : Pattern

/** A string literal, escapes already decoded, matching that text. */
data class PString(val value: String, override val range: SourceRange) : Pattern

/** Two or more comma-separated patterns in parentheses. A single one is grouping. */
data class PTuple(val elements: List<Pattern>, override val range: SourceRange) : Pattern

/** A bracketed pattern, matching a list of exactly that length. `[]` matches the empty list. */
data class PList(val elements: List<Pattern>, override val range: SourceRange) : Pattern

/**
 * A constructor name and the patterns for its arguments.
 *
 * An upper-case name on its own is this node with no arguments, which is how `None` is told apart
 * from a [PVar] that would match anything.
 */
data class PConstructor(
    val name: String,
    val arguments: List<Pattern>,
    override val range: SourceRange,
) : Pattern

/** `head :: tail` — matches any non-empty list, binding its first element and the rest. */
data class PCons(
    val head: Pattern,
    val tail: Pattern,
    override val range: SourceRange,
) : Pattern

/** One `| pattern -> body` arm of an [EMatch]. */
data class MatchCase(
    val pattern: Pattern,
    val body: Expr,
    val range: SourceRange,
)

/**
 * `match scrutinee with` followed by its arms.
 *
 * The arms are kept in the order they were written, and nothing here checks that they cover the
 * scrutinee's type or that a later one is reachable.
 */
data class EMatch(
    val scrutinee: Expr,
    val cases: List<MatchCase>,
    override val range: SourceRange,
) : Expr

/** One `name = value` field of an [ERecord]. */
data class RecordField(
    val name: String,
    val value: Expr,
    val range: SourceRange,
)

/**
 * A braced record literal.
 *
 * Fields keep their written order. A repeated field name is rejected while parsing, so the list a
 * reader gets here has distinct names.
 */
data class ERecord(
    val fields: List<RecordField>,
    override val range: SourceRange,
) : Expr

/** `target.field`, reading one field out of a record. */
data class EField(
    val target: Expr,
    val field: String,
    override val range: SourceRange,
) : Expr

/**
 * The infix operators, each carrying the text it is written as.
 *
 * `CONS` is `::` in expression position; the pattern that takes the same shape apart again is
 * [PCons]. Both are right associative, so `a :: b :: rest` builds and matches alike. `CONCAT` is
 * `++`, which joins two strings and only strings: a list is built with `CONS`.
 */
enum class BinaryOp(val text: String) {
    ADD("+"),
    SUB("-"),
    MUL("*"),
    DIV("/"),
    CONCAT("++"),
    LT("<"),
    LTE("<="),
    GT(">"),
    GTE(">="),
    EQ("=="),
    NEQ("!="),
    AND("&&"),
    OR("||"),
    CONS("::"),
}

/** An infix operator applied to two operands, with precedence already resolved into the shape. */
data class EBinary(
    val op: BinaryOp,
    val left: Expr,
    val right: Expr,
    override val range: SourceRange,
) : Expr

/** Prefix `-` over numbers and vectors. */
data class ENegate(
    val value: Expr,
    override val range: SourceRange,
) : Expr
