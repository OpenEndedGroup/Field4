package afield.ml.type

import afield.ml.ast.BinaryOp
import afield.ml.ast.TypeDecl
import afield.ml.diag.SourceRange

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
