package afield.lang.fieldml.core

import afield.lang.fieldml.ast.BinaryOp
import afield.lang.fieldml.ast.TypeDecl
import afield.lang.fieldml.type.Type

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

/** An integer literal, from [TExprInt]. */
data class CInt(val value: Long, override val type: Type) : CoreExpr

/** A floating-point literal, from [TExprFloat]. */
data class CFloat(val value: Double, override val type: Type) : CoreExpr

/** A boolean literal, from [TExprBool]. */
data class CBool(val value: Boolean, override val type: Type) : CoreExpr

/** A string literal, from [TExprString]. */
data class CString(val value: String, override val type: Type) : CoreExpr

/** A name to look up in the environment, from [TExprVar]. */
data class CVar(val name: String, override val type: Type) : CoreExpr

/** A tuple, from [TExprTuple]. */
data class CTuple(val elements: List<CoreExpr>, override val type: Type) : CoreExpr

/** A list, from [TExprList]. */
data class CList(val elements: List<CoreExpr>, override val type: Type) : CoreExpr

/**
 * A data type declaration and the program that follows it, from [TExprTypeDecl].
 *
 * The declaration arrives here as the [TypeDecl] the parser built, unchanged by either later stage.
 */
data class CTypeDecl(
    val declaration: TypeDecl,
    val constructors: List<CoreConstructor>,
    val body: CoreExpr,
    override val type: Type,
) : CoreExpr

/**
 * A constructor as the evaluator needs it: its name, and how many arguments it takes before it is a
 * value.
 *
 * This is what is left of [TypedConstructor] once the lowering drops its type scheme, which has
 * already done its work in inference.
 */
data class CoreConstructor(
    val name: String,
    val arity: Int,
)

/**
 * A binding and the program that follows it, from [TExprLet].
 *
 * There is no parameter list here: by this point a `let f x = ...`'s parameters are the [CLam] nodes
 * sitting in its `value`.
 */
data class CLet(
    val name: String,
    val value: CoreExpr,
    val body: CoreExpr,
    override val type: Type,
    /** See [afield.lang.fieldml.type.TExprLet.recursive]. */
    val recursive: Boolean = false,
) : CoreExpr

/**
 * A lambda of exactly one parameter, so the evaluator only ever binds one at a time.
 *
 * This is the one node the lowering builds rather than renames: a [TExprLambda] of several
 * parameters becomes that many nested `CLam`s. Each carries its own `type`, and they differ — the
 * outer node's is the whole arrow, the inner node's is the arrow that starts at the next parameter.
 * `parameterType` is the type of the one parameter this node binds.
 */
data class CLam(
    val parameter: String,
    val parameterType: Type,
    val body: CoreExpr,
    override val type: Type,
) : CoreExpr

/** One argument applied to one function, from [TExprCall]. */
data class CApp(
    val function: CoreExpr,
    val argument: CoreExpr,
    override val type: Type,
) : CoreExpr

/** A conditional, from [TExprIf]. Both branches are present and have the same type. */
data class CIf(
    val condition: CoreExpr,
    val thenBranch: CoreExpr,
    val elseBranch: CoreExpr,
    override val type: Type,
) : CoreExpr

/**
 * A pattern in the lowered tree, carrying the type it matches against.
 *
 * Patterns are already checked by the time they get here, so matching one at run time is a question
 * about the value's shape rather than about its type.
 */
sealed interface CorePattern {
    val type: Type
}

/** Matches anything and binds nothing, from [TPWildcard]. */
data class CPWildcard(override val type: Type) : CorePattern

/** Matches anything and binds it under `name`, from [TPVar]. */
data class CPVar(val name: String, override val type: Type) : CorePattern

/** Matches one integer value, from [TPInt]. */
data class CPInt(val value: Long, override val type: Type) : CorePattern

/** Matches one boolean value, from [TPBool]. */
data class CPBool(val value: Boolean, override val type: Type) : CorePattern

/** Matches one string value, from [TPString]. */
data class CPString(val value: String, override val type: Type) : CorePattern

/** Matches a tuple element by element, from [TPTuple]. */
data class CPTuple(val elements: List<CorePattern>, override val type: Type) : CorePattern

/** Matches a list of exactly this length, from [TPList]. */
data class CPList(val elements: List<CorePattern>, override val type: Type) : CorePattern

/** Matches one constructor and its arguments, from [TPConstructor]. */
data class CPConstructor(
    val name: String,
    val arguments: List<CorePattern>,
    override val type: Type,
) : CorePattern

/** Matches a non-empty list, splitting it into `head` and `tail`, from [TPCons]. */
data class CPCons(
    val head: CorePattern,
    val tail: CorePattern,
    override val type: Type,
) : CorePattern

/** One arm of a [CMatch]: a pattern, and the expression to evaluate when it matches. */
data class CMatchCase(
    val pattern: CorePattern,
    val body: CoreExpr,
)

/**
 * A match over its arms, from [TExprMatch].
 *
 * The arms stay in the order they were written, which is the order the evaluator tries them in: the
 * first pattern that matches wins. Nothing checks that they cover every case, so a value that
 * matches no arm is a run-time failure rather than a compile error.
 */
data class CMatch(
    val scrutinee: CoreExpr,
    val cases: List<CMatchCase>,
    override val type: Type,
) : CoreExpr

/** A record literal, from [TExprRecord], as name-and-value pairs in their written order. */
data class CRecord(
    val fields: List<Pair<String, CoreExpr>>,
    override val type: Type,
) : CoreExpr

/** Reads one field off a record or a host value, from [TExprField]. */
data class CField(
    val target: CoreExpr,
    val field: String,
    override val type: Type,
) : CoreExpr

/**
 * An infix operator over two operands, from [TExprBinary].
 *
 * `AND` and `OR` short-circuit: the right operand is evaluated only when it can change the answer.
 */
data class CBinary(
    val op: BinaryOp,
    val left: CoreExpr,
    val right: CoreExpr,
    override val type: Type,
) : CoreExpr

/** Negation, from [TExprNegate], over an integer, a float, or each component of a vector. */
data class CNegate(
    val value: CoreExpr,
    override val type: Type,
) : CoreExpr
