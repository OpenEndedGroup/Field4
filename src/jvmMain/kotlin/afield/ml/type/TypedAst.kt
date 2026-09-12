package afield.ml.type

import afield.ml.ast.BinaryOp
import afield.ml.ast.TypeDecl
import afield.ml.diag.SourceRange

/**
 * FieldML's syntax tree after inference: the same shape as the surface tree in `afield.ml.ast`, with
 * a [Type] on every expression and on every pattern.
 *
 * [Inferencer] produces it and [Lowerer] consumes it. Source ranges survive inference, which is what
 * lets a type error point at the text that caused it, and this is the last stage to have them: the
 * `afield.ml.core` tree the evaluator runs keeps the types and drops the positions.
 *
 * Three things here are inference's findings rather than a restatement of the syntax:
 *
 *   - `TExprLet.recursive` is true for the `let f x = ...` function form, which is the form whose
 *     own name is in scope inside its body.
 *   - `TExprLet.parameters` repeats the parameters of the lambda that the binding's value became,
 *     and is empty when the value is not a lambda.
 *   - [TypedConstructor] gives each constructor of a declared data type its arity and the [Scheme]
 *     its name was bound under, so `Some` is an ordinary curried function from there on.
 *
 * `walkTyped` is the one walker over the hierarchy, and completion is its caller: it compiles a
 * probe program and looks for the [TExprLambda] whose parameter names match the lambda under the
 * caret, which is how a callback's parameters have types before a reader has written any.
 */

/** A node of the typed tree: a surface node, its source range, and the type inferred for it. */
sealed interface TExpr {
    val range: SourceRange
    val type: Type
}

data class TExprInt(val value: Long, override val range: SourceRange, override val type: Type) : TExpr
data class TExprFloat(val value: Double, override val range: SourceRange, override val type: Type) : TExpr
data class TExprBool(val value: Boolean, override val range: SourceRange, override val type: Type) : TExpr
data class TExprString(val value: String, override val range: SourceRange, override val type: Type) : TExpr
data class TExprVar(val name: String, override val range: SourceRange, override val type: Type) : TExpr
data class TExprTuple(val elements: List<TExpr>, override val range: SourceRange, override val type: Type) : TExpr
data class TExprList(val elements: List<TExpr>, override val range: SourceRange, override val type: Type) : TExpr

data class TExprTypeDecl(
    val declaration: TypeDecl,
    val constructors: List<TypedConstructor>,
    val body: TExpr,
    override val range: SourceRange,
    override val type: Type,
) : TExpr

data class TypedConstructor(
    val name: String,
    val arity: Int,
    val scheme: Scheme,
)

data class TExprLet(
    val name: String,
    val parameters: List<Pair<String, Type>>,
    val value: TExpr,
    val body: TExpr,
    override val range: SourceRange,
    override val type: Type,
    /**
     * True for the `let f x = ...` function form, whose own name is in scope inside its body.
     * Plain `let x = ...` bindings stay non-recursive so that `let x = x + 1` still shadows an
     * outer `x` rather than referring to itself.
     */
    val recursive: Boolean = false,
) : TExpr

data class TExprLambda(
    val parameters: List<Pair<String, Type>>,
    val body: TExpr,
    override val range: SourceRange,
    override val type: Type,
) : TExpr

data class TExprCall(
    val function: TExpr,
    val argument: TExpr,
    override val range: SourceRange,
    override val type: Type,
) : TExpr

data class TExprIf(
    val condition: TExpr,
    val thenBranch: TExpr,
    val elseBranch: TExpr,
    override val range: SourceRange,
    override val type: Type,
) : TExpr

sealed interface TPattern {
    val range: SourceRange
    val type: Type
}

data class TPWildcard(override val range: SourceRange, override val type: Type) : TPattern
data class TPVar(val name: String, override val range: SourceRange, override val type: Type) : TPattern
data class TPInt(val value: Long, override val range: SourceRange, override val type: Type) : TPattern
data class TPBool(val value: Boolean, override val range: SourceRange, override val type: Type) : TPattern
data class TPString(val value: String, override val range: SourceRange, override val type: Type) : TPattern
data class TPTuple(val elements: List<TPattern>, override val range: SourceRange, override val type: Type) : TPattern
data class TPList(val elements: List<TPattern>, override val range: SourceRange, override val type: Type) : TPattern

data class TPConstructor(
    val name: String,
    val arguments: List<TPattern>,
    override val range: SourceRange,
    override val type: Type,
) : TPattern

data class TPCons(
    val head: TPattern,
    val tail: TPattern,
    override val range: SourceRange,
    override val type: Type,
) : TPattern

data class TMatchCase(
    val pattern: TPattern,
    val body: TExpr,
)

data class TExprMatch(
    val scrutinee: TExpr,
    val cases: List<TMatchCase>,
    override val range: SourceRange,
    override val type: Type,
) : TExpr

data class TExprRecord(
    val fields: List<Pair<String, TExpr>>,
    override val range: SourceRange,
    override val type: Type,
) : TExpr

data class TExprField(
    val target: TExpr,
    val field: String,
    override val range: SourceRange,
    override val type: Type,
) : TExpr

data class TExprBinary(
    val op: BinaryOp,
    val left: TExpr,
    val right: TExpr,
    override val range: SourceRange,
    override val type: Type,
) : TExpr

data class TExprNegate(
    val value: TExpr,
    override val range: SourceRange,
    override val type: Type,
) : TExpr

/**
 * Every node of a typed tree, parents before children.
 *
 * A `when` over [TExpr] belongs in one place: the hierarchy has seventeen cases, and a walker
 * written out again at each call site is one that silently stops descending the day another arrives.
 */
fun TExpr.walkTyped(visit: (TExpr) -> Unit) {
    visit(this)
    when (this) {
        is TExprCall -> { function.walkTyped(visit); argument.walkTyped(visit) }
        is TExprLambda -> body.walkTyped(visit)
        is TExprLet -> { value.walkTyped(visit); body.walkTyped(visit) }
        is TExprIf -> { condition.walkTyped(visit); thenBranch.walkTyped(visit); elseBranch.walkTyped(visit) }
        is TExprTuple -> elements.forEach { it.walkTyped(visit) }
        is TExprList -> elements.forEach { it.walkTyped(visit) }
        is TExprRecord -> fields.forEach { it.second.walkTyped(visit) }
        is TExprField -> target.walkTyped(visit)
        is TExprBinary -> { left.walkTyped(visit); right.walkTyped(visit) }
        is TExprNegate -> value.walkTyped(visit)
        is TExprMatch -> { scrutinee.walkTyped(visit); cases.forEach { it.body.walkTyped(visit) } }
        is TExprTypeDecl -> body.walkTyped(visit)
        is TExprInt, is TExprFloat, is TExprBool, is TExprString, is TExprVar -> Unit
    }
}
