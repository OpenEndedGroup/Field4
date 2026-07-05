package afield.ml.core

import afield.ml.ast.BinaryOp
import afield.ml.ast.TypeDecl
import afield.ml.type.Type

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
