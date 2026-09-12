package afield.ml.core

import afield.ml.ast.BinaryOp
import afield.ml.ast.TypeDecl
import afield.ml.type.Type

/**
 * The tree FieldML actually evaluates: the typed tree with its source positions dropped and its
 * multi-parameter lambdas curried into one-parameter [CLam] nodes.
 *
 * [Lowerer] is the only thing that builds one and [Evaluator] the only thing that walks one. Every
 * case here corresponds to a case of the typed tree, and the lowering is a case-for-case mapping
 * apart from the lambda, so the two hierarchies are easy to read side by side.
 *
 * What the lowering leaves behind is as informative as what it keeps. Types stay on every node, so a
 * later pass can still ask what a subexpression is. Ranges do not, so an evaluation failure carries
 * no position of its own, and a box reports it against the fragment that was run rather than the
 * subexpression that failed. [CLet] keeps the `recursive` flag but has no parameter list, because by
 * this point a `let f x = ...`'s parameters are the lambda sitting in its `value`.
 * [CoreConstructor] keeps a constructor's name and arity and drops its type scheme, which has
 * already done its work in inference.
 */

/** A node of the lowered tree, carrying the type inferred for it. */
sealed interface CoreExpr {
    val type: Type
}

data class CInt(val value: Long, override val type: Type) : CoreExpr
data class CFloat(val value: Double, override val type: Type) : CoreExpr
data class CBool(val value: Boolean, override val type: Type) : CoreExpr
data class CString(val value: String, override val type: Type) : CoreExpr
data class CVar(val name: String, override val type: Type) : CoreExpr
data class CTuple(val elements: List<CoreExpr>, override val type: Type) : CoreExpr
data class CList(val elements: List<CoreExpr>, override val type: Type) : CoreExpr

data class CTypeDecl(
    val declaration: TypeDecl,
    val constructors: List<CoreConstructor>,
    val body: CoreExpr,
    override val type: Type,
) : CoreExpr

data class CoreConstructor(
    val name: String,
    val arity: Int,
)

data class CLet(
    val name: String,
    val value: CoreExpr,
    val body: CoreExpr,
    override val type: Type,
    /** See [afield.ml.type.TExprLet.recursive]. */
    val recursive: Boolean = false,
) : CoreExpr

data class CLam(
    val parameter: String,
    val parameterType: Type,
    val body: CoreExpr,
    override val type: Type,
) : CoreExpr

data class CApp(
    val function: CoreExpr,
    val argument: CoreExpr,
    override val type: Type,
) : CoreExpr

data class CIf(
    val condition: CoreExpr,
    val thenBranch: CoreExpr,
    val elseBranch: CoreExpr,
    override val type: Type,
) : CoreExpr

sealed interface CorePattern {
    val type: Type
}

data class CPWildcard(override val type: Type) : CorePattern
data class CPVar(val name: String, override val type: Type) : CorePattern
data class CPInt(val value: Long, override val type: Type) : CorePattern
data class CPBool(val value: Boolean, override val type: Type) : CorePattern
data class CPString(val value: String, override val type: Type) : CorePattern
data class CPTuple(val elements: List<CorePattern>, override val type: Type) : CorePattern
data class CPList(val elements: List<CorePattern>, override val type: Type) : CorePattern

data class CPConstructor(
    val name: String,
    val arguments: List<CorePattern>,
    override val type: Type,
) : CorePattern

data class CPCons(
    val head: CorePattern,
    val tail: CorePattern,
    override val type: Type,
) : CorePattern

data class CMatchCase(
    val pattern: CorePattern,
    val body: CoreExpr,
)

data class CMatch(
    val scrutinee: CoreExpr,
    val cases: List<CMatchCase>,
    override val type: Type,
) : CoreExpr

data class CRecord(
    val fields: List<Pair<String, CoreExpr>>,
    override val type: Type,
) : CoreExpr

data class CField(
    val target: CoreExpr,
    val field: String,
    override val type: Type,
) : CoreExpr

data class CBinary(
    val op: BinaryOp,
    val left: CoreExpr,
    val right: CoreExpr,
    override val type: Type,
) : CoreExpr

data class CNegate(
    val value: CoreExpr,
    override val type: Type,
) : CoreExpr
