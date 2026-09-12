package afield.ml.ast

import afield.ml.diag.SourceRange

/**
 * FieldML's surface syntax tree: one node per piece of written syntax, each carrying the source
 * range it was parsed from.
 *
 * [Parser] produces this tree and [Inferencer] reads it. Nothing in it has a type yet. Every node
 * here has a counterpart in `afield.ml.type` that carries an inferred type, and that typed tree is
 * lowered again into the `afield.ml.core` form the evaluator runs.
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

data class EInt(val value: Long, override val range: SourceRange) : Expr
data class EFloat(val value: Double, override val range: SourceRange) : Expr
data class EBool(val value: Boolean, override val range: SourceRange) : Expr
data class EString(val value: String, override val range: SourceRange) : Expr
data class EVar(val name: String, override val range: SourceRange) : Expr
data class ETuple(val elements: List<Expr>, override val range: SourceRange) : Expr
data class EList(val elements: List<Expr>, override val range: SourceRange) : Expr

data class TypeDecl(
    val name: String,
    val parameters: List<String>,
    val constructors: List<ConstructorDecl>,
    val range: SourceRange,
)

data class ConstructorDecl(
    val name: String,
    val arguments: List<TypeRef>,
    val range: SourceRange,
)

sealed interface TypeRef {
    val name: String
    val range: SourceRange
}

data class TypeRefName(
    override val name: String,
    override val range: SourceRange,
) : TypeRef

data class ETypeDecl(
    val declaration: TypeDecl,
    val body: Expr,
    override val range: SourceRange,
) : Expr

data class ELet(
    val name: String,
    val parameters: List<String>,
    val value: Expr,
    val body: Expr,
    override val range: SourceRange,
) : Expr

data class ELambda(
    val parameters: List<String>,
    val body: Expr,
    override val range: SourceRange,
) : Expr

data class ECall(
    val function: Expr,
    val argument: Expr,
    override val range: SourceRange,
) : Expr

data class EIf(
    val condition: Expr,
    val thenBranch: Expr,
    val elseBranch: Expr,
    override val range: SourceRange,
) : Expr

sealed interface Pattern {
    val range: SourceRange
}

data class PWildcard(override val range: SourceRange) : Pattern
data class PVar(val name: String, override val range: SourceRange) : Pattern
data class PInt(val value: Long, override val range: SourceRange) : Pattern
data class PBool(val value: Boolean, override val range: SourceRange) : Pattern
data class PString(val value: String, override val range: SourceRange) : Pattern
data class PTuple(val elements: List<Pattern>, override val range: SourceRange) : Pattern
data class PList(val elements: List<Pattern>, override val range: SourceRange) : Pattern

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

data class MatchCase(
    val pattern: Pattern,
    val body: Expr,
    val range: SourceRange,
)

data class EMatch(
    val scrutinee: Expr,
    val cases: List<MatchCase>,
    override val range: SourceRange,
) : Expr

data class RecordField(
    val name: String,
    val value: Expr,
    val range: SourceRange,
)

data class ERecord(
    val fields: List<RecordField>,
    override val range: SourceRange,
) : Expr

data class EField(
    val target: Expr,
    val field: String,
    override val range: SourceRange,
) : Expr

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
