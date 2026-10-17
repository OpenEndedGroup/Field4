package afield.lang.fieldml.core

import afield.lang.fieldml.type.TExpr
import afield.lang.fieldml.type.TExprBinary
import afield.lang.fieldml.type.TExprBool
import afield.lang.fieldml.type.TExprCall
import afield.lang.fieldml.type.TExprField
import afield.lang.fieldml.type.TExprFloat
import afield.lang.fieldml.type.TExprIf
import afield.lang.fieldml.type.TExprInt
import afield.lang.fieldml.type.TExprLambda
import afield.lang.fieldml.type.TExprLet
import afield.lang.fieldml.type.TExprList
import afield.lang.fieldml.type.TExprMatch
import afield.lang.fieldml.type.TExprNegate
import afield.lang.fieldml.type.TExprRecord
import afield.lang.fieldml.type.TExprString
import afield.lang.fieldml.type.TExprTuple
import afield.lang.fieldml.type.TExprTypeDecl
import afield.lang.fieldml.type.TExprVar
import afield.lang.fieldml.type.TFun
import afield.lang.fieldml.type.TPBool
import afield.lang.fieldml.type.TPCons
import afield.lang.fieldml.type.TPConstructor
import afield.lang.fieldml.type.TPInt
import afield.lang.fieldml.type.TPList
import afield.lang.fieldml.type.TPString
import afield.lang.fieldml.type.TPTuple
import afield.lang.fieldml.type.TPVar
import afield.lang.fieldml.type.TPWildcard
import afield.lang.fieldml.type.TPattern

/**
 * The last stage of the FieldML front end: the typed tree becomes the [CoreExpr] tree the evaluator
 * walks.
 *
 * Almost all of it is a rename. Each case of the typed tree has one case in the core tree, the
 * inferred type is copied across, and the source range is dropped. Two things inference needed go
 * no further: a `let`'s parameter list, which by this point is the lambda sitting in its value,
 * and a constructor's type scheme, which reduces to the name and arity [CoreConstructor] keeps.
 *
 * The one real transformation is currying. A lambda written `fun x y -> ...` becomes two nested
 * one-parameter [CLam] nodes, so the evaluator only ever binds one parameter at a time. Each of
 * those nodes carries its own type and they are different types: the outer node's is the whole
 * arrow, the inner node's is the arrow that starts at the second parameter. So the lowering walks
 * the arrow outside in, one step per parameter, keeping the type each parameter starts from, and
 * then builds the nodes from the innermost parameter back out.
 *
 * Nothing here reports anything. A tree that reaches this stage has already been typed, so there is
 * no result to check: [FieldMLCompiler] runs the lowerer and returns what comes back.
 */

/**
 * Turns a typed tree into the core tree the evaluator runs.
 *
 * An instance holds no state, so one will lower any number of trees.
 */
class Lowerer {
    /** The [CoreExpr] equivalent of [expr], with its lambdas curried and its ranges dropped. */
    fun lower(expr: TExpr): CoreExpr =
        when (expr) {
            is TExprInt -> CInt(expr.value, expr.type)
            is TExprFloat -> CFloat(expr.value, expr.type)
            is TExprBool -> CBool(expr.value, expr.type)
            is TExprString -> CString(expr.value, expr.type)
            is TExprVar -> CVar(expr.name, expr.type)
            is TExprTuple -> CTuple(expr.elements.map(::lower), expr.type)
            is TExprList -> CList(expr.elements.map(::lower), expr.type)
            is TExprTypeDecl -> CTypeDecl(
                expr.declaration,
                expr.constructors.map { CoreConstructor(it.name, it.arity) },
                lower(expr.body),
                expr.type,
            )
            is TExprLet -> CLet(expr.name, lower(expr.value), lower(expr.body), expr.type, expr.recursive)
            is TExprLambda -> lowerLambda(expr)
            is TExprCall -> CApp(lower(expr.function), lower(expr.argument), expr.type)
            is TExprIf -> CIf(lower(expr.condition), lower(expr.thenBranch), lower(expr.elseBranch), expr.type)
            is TExprMatch -> CMatch(
                lower(expr.scrutinee),
                expr.cases.map { CMatchCase(lowerPattern(it.pattern), lower(it.body)) },
                expr.type,
            )
            is TExprRecord -> CRecord(expr.fields.map { it.first to lower(it.second) }, expr.type)
            is TExprField -> CField(lower(expr.target), expr.field, expr.type)
            is TExprBinary -> CBinary(expr.op, lower(expr.left), lower(expr.right), expr.type)
            is TExprNegate -> CNegate(lower(expr.value), expr.type)
        }

    private fun lowerLambda(expr: TExprLambda): CoreExpr {
        val lambdaTypes = mutableListOf<afield.lang.fieldml.type.Type>()
        var cursor = expr.type
        repeat(expr.parameters.size) {
            lambdaTypes += cursor
            cursor = (cursor as? TFun)?.to ?: cursor
        }

        var body = lower(expr.body)
        for (index in expr.parameters.indices.reversed()) {
            val (name, type) = expr.parameters[index]
            body = CLam(name, type, body, lambdaTypes[index])
        }
        return body
    }

    private fun lowerPattern(pattern: TPattern): CorePattern =
        when (pattern) {
            is TPWildcard -> CPWildcard(pattern.type)
            is TPVar -> CPVar(pattern.name, pattern.type)
            is TPInt -> CPInt(pattern.value, pattern.type)
            is TPBool -> CPBool(pattern.value, pattern.type)
            is TPString -> CPString(pattern.value, pattern.type)
            is TPTuple -> CPTuple(pattern.elements.map(::lowerPattern), pattern.type)
            is TPList -> CPList(pattern.elements.map(::lowerPattern), pattern.type)
            is TPConstructor -> CPConstructor(pattern.name, pattern.arguments.map(::lowerPattern), pattern.type)
            is TPCons -> CPCons(lowerPattern(pattern.head), lowerPattern(pattern.tail), pattern.type)
        }
}
