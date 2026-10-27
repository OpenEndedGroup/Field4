package afield.lang.fieldml.type

import afield.lang.fieldml.ast.BinaryOp
import afield.lang.fieldml.ast.TypeDecl
import afield.lang.fieldml.diag.SourceRange

/**
 * FieldML's syntax tree after inference: the same shape as the surface tree in `afield.lang.fieldml.ast`, with
 * a [Type] on every expression and on every pattern.
 *
 * [Inferencer] produces it and [Lowerer] consumes it. Source ranges survive inference, which is what
 * lets a type error point at the text that caused it, and this is the last stage to have them: the
 * `afield.lang.fieldml.core` tree the evaluator runs keeps the types and drops the positions.
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

/** [EInt] typed, which is always `Int`. */
data class TExprInt(val value: Long, override val range: SourceRange, override val type: Type) : TExpr

/** [EFloat] typed, which is always `Float`. */
data class TExprFloat(val value: Double, override val range: SourceRange, override val type: Type) : TExpr

/** [EBool] typed, which is always `Bool`. */
data class TExprBool(val value: Boolean, override val range: SourceRange, override val type: Type) : TExpr

/** [EString] typed, which is always `String`. */
data class TExprString(val value: String, override val range: SourceRange, override val type: Type) : TExpr

/**
 * [EVar] typed: a name, and the type the environment had for it.
 *
 * A name bound to a [Scheme] is instantiated with fresh variables here, so two uses of one
 * polymorphic binding can carry different types.
 */
data class TExprVar(val name: String, override val range: SourceRange, override val type: Type) : TExpr

/** [ETuple] typed, carrying a [TTuple] over its elements' types. */
data class TExprTuple(val elements: List<TExpr>, override val range: SourceRange, override val type: Type) : TExpr

/**
 * [EList] typed, carrying a [TList] of one element type.
 *
 * Every element unifies with that one type, so a list whose elements disagree is where the error is
 * reported rather than something the tree can represent.
 */
data class TExprList(val elements: List<TExpr>, override val range: SourceRange, override val type: Type) : TExpr

/**
 * [ETypeDecl] typed: the declaration as written, its constructors as inference bound them, and the
 * program that follows.
 *
 * The node's own type is the type of that `body`, since a declaration contributes names rather than
 * a value.
 */
data class TExprTypeDecl(
    val declaration: TypeDecl,
    val constructors: List<TypedConstructor>,
    val body: TExpr,
    override val range: SourceRange,
    override val type: Type,
) : TExpr

/**
 * What inference worked out about one constructor of a declared data type.
 *
 * The [Scheme] is what its name was bound under, which makes a constructor an ordinary curried
 * function from that point on: `Some` is applied like anything else rather than being special-cased
 * by the evaluator. `arity` is how many arguments it takes before it is a value.
 */
data class TypedConstructor(
    val name: String,
    val arity: Int,
    val scheme: Scheme,
)

/**
 * [ELet] typed, with a type beside each parameter name.
 *
 * `parameters` repeats the parameters of the lambda the binding's value became, and is empty when
 * the value is not a lambda.
 */
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

/**
 * [ELambda] typed, still holding every parameter it was written with.
 *
 * This is the node completion looks for: it compiles a probe program and finds the lambda whose
 * parameter names match the one under the caret, which is how a callback's parameters have types
 * before a reader has written any.
 */
data class TExprLambda(
    val parameters: List<Pair<String, Type>>,
    val body: TExpr,
    override val range: SourceRange,
    override val type: Type,
) : TExpr

/** [ECall] typed: one argument applied to one function, carrying the result type. */
data class TExprCall(
    val function: TExpr,
    val argument: TExpr,
    override val range: SourceRange,
    override val type: Type,
) : TExpr

/**
 * [EIf] typed.
 *
 * The condition unifies with `Bool` and the two branches with each other, so the node's type is the
 * type both branches arrived at.
 */
data class TExprIf(
    val condition: TExpr,
    val thenBranch: TExpr,
    val elseBranch: TExpr,
    override val range: SourceRange,
    override val type: Type,
) : TExpr

/**
 * A pattern after inference: a surface pattern, its range, and the type it matches against.
 *
 * A pattern's type is the scrutinee's, not the arm's result, so every pattern in one `match` carries
 * the same type.
 */
sealed interface TPattern {
    val range: SourceRange
    val type: Type
}

/** [PWildcard] typed. It matches anything, so its type is whatever the scrutinee's is. */
data class TPWildcard(override val range: SourceRange, override val type: Type) : TPattern

/** [PVar] typed. The type here is what the name is bound to inside the arm's body. */
data class TPVar(val name: String, override val range: SourceRange, override val type: Type) : TPattern

/** [PInt] typed, which forces the scrutinee to `Int`. */
data class TPInt(val value: Long, override val range: SourceRange, override val type: Type) : TPattern

/** [PBool] typed, which forces the scrutinee to `Bool`. */
data class TPBool(val value: Boolean, override val range: SourceRange, override val type: Type) : TPattern

/** [PString] typed, which forces the scrutinee to `String`. */
data class TPString(val value: String, override val range: SourceRange, override val type: Type) : TPattern

/** [PTuple] typed, element by element against a [TTuple] of the same width. */
data class TPTuple(val elements: List<TPattern>, override val range: SourceRange, override val type: Type) : TPattern

/** [PList] typed: every element against the one element type of a [TList]. */
data class TPList(val elements: List<TPattern>, override val range: SourceRange, override val type: Type) : TPattern

/**
 * [PConstructor] typed.
 *
 * Inference looks the name up among the declared constructors and matches its arguments against the
 * ones that constructor takes, so an arity that does not agree is reported here.
 */
data class TPConstructor(
    val name: String,
    val arguments: List<TPattern>,
    override val range: SourceRange,
    override val type: Type,
) : TPattern

/** [PCons] typed: `head` against the element type and `tail` against the list type. */
data class TPCons(
    val head: TPattern,
    val tail: TPattern,
    override val range: SourceRange,
    override val type: Type,
) : TPattern

/**
 * One arm of a [TExprMatch], as a typed pattern and the body it guards.
 *
 * Unlike the surface [MatchCase] it has no range of its own: inference keeps ranges on expressions
 * and patterns but not on the arm that joins them.
 */
data class TMatchCase(
    val pattern: TPattern,
    val body: TExpr,
)

/**
 * [EMatch] typed.
 *
 * Every pattern unifies with the scrutinee and every arm's body with every other, so the node's type
 * is the one type all the arms agreed on.
 */
data class TExprMatch(
    val scrutinee: TExpr,
    val cases: List<TMatchCase>,
    override val range: SourceRange,
    override val type: Type,
) : TExpr

/**
 * [ERecord] typed, carrying a [TRecord] of its fields' types.
 *
 * The fields are name-and-value pairs here rather than the surface [RecordField], so a field's own
 * range does not survive inference; the value's does.
 */
data class TExprRecord(
    val fields: List<Pair<String, TExpr>>,
    override val range: SourceRange,
    override val type: Type,
) : TExpr

/**
 * [EField] typed: the type reading that field off that target produced.
 *
 * Where it comes from depends on what the target turned out to be. A [TRecord] answers from its own
 * field list, and a field it does not have is an error. A target still open as a [TVar] is unified
 * with a record that has the field, so the access itself is what records that the target is a
 * record. A [THost] answers from the JVM type: a vector's component is `Float`, and any other
 * member is whatever reflection reports, which leaves an opaque host value dynamic.
 */
data class TExprField(
    val target: TExpr,
    val field: String,
    override val range: SourceRange,
    override val type: Type,
) : TExpr

/** [EBinary] typed, with the result type the operator's own rule produced for those operands. */
data class TExprBinary(
    val op: BinaryOp,
    val left: TExpr,
    val right: TExpr,
    override val range: SourceRange,
    override val type: Type,
) : TExpr

/** [ENegate] typed, over the numeric or vector type its operand turned out to have. */
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
