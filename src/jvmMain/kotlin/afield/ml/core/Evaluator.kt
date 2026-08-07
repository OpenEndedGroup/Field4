package afield.ml.core

import afield.ml.ast.BinaryOp
import afield.stdlib.linalg.Vector2f
import afield.stdlib.linalg.Vector3f
import afield.stdlib.linalg.Vector4f

sealed interface FieldMLValue {
    data class IntValue(val value: Long) : FieldMLValue
    data class FloatValue(val value: Double) : FieldMLValue
    data class BoolValue(val value: Boolean) : FieldMLValue
    data class StringValue(val value: String) : FieldMLValue
    data class TupleValue(val elements: List<FieldMLValue>) : FieldMLValue
    data class ListValue(val elements: List<FieldMLValue>) : FieldMLValue
    data class RecordValue(val fields: Map<String, FieldMLValue>) : FieldMLValue
    data class AdtValue(val tag: String, val arguments: List<FieldMLValue>) : FieldMLValue
    data object UnitValue : FieldMLValue
    data class HostValue(val value: Any?) : FieldMLValue
    data class NativeFunctionValue(val name: String, val apply: (FieldMLValue) -> FieldMLValue) : FieldMLValue
    data class FactoryValue(val name: String, val create: () -> FieldMLValue) : FieldMLValue
    data class ClosureValue(val parameter: String, val body: CoreExpr, val env: RuntimeEnv) : FieldMLValue
    data class ConstructorValue(val tag: String, val arity: Int, val arguments: List<FieldMLValue> = emptyList()) : FieldMLValue
}

class RuntimeEnv(
    private val parent: RuntimeEnv? = null,
    private val values: MutableMap<String, FieldMLValue> = mutableMapOf(),
) {
    fun child(): RuntimeEnv = RuntimeEnv(this)

    fun define(name: String, value: FieldMLValue) {
        values[name] = value
    }

    fun lookup(name: String): FieldMLValue {
        val value = values[name] ?: return parent?.lookup(name) ?: error("Unknown FieldML runtime name '$name'")
        return if (value is FieldMLValue.FactoryValue) value.create() else value
    }
}

class Evaluator(private val prelude: Map<String, FieldMLValue> = emptyMap()) {
    fun evaluate(expr: CoreExpr): FieldMLValue {
        val env = RuntimeEnv()
        prelude.forEach { (name, value) -> env.define(name, value) }
        return evalFieldML(expr, env)
    }
}

private fun evalFieldML(expr: CoreExpr, env: RuntimeEnv): FieldMLValue =
    when (expr) {
        is CInt -> FieldMLValue.IntValue(expr.value)
        is CFloat -> FieldMLValue.FloatValue(expr.value)
        is CBool -> FieldMLValue.BoolValue(expr.value)
        is CString -> FieldMLValue.StringValue(expr.value)
        is CVar -> env.lookup(expr.name)
        is CTuple -> FieldMLValue.TupleValue(expr.elements.map { evalFieldML(it, env) })
        is CList -> FieldMLValue.ListValue(expr.elements.map { evalFieldML(it, env) })
        is CTypeDecl -> {
            val local = env.child()
            expr.constructors.forEach { constructor ->
                local.define(
                    constructor.name,
                    if (constructor.arity == 0) {
                        FieldMLValue.AdtValue(constructor.name, emptyList())
                    } else {
                        FieldMLValue.ConstructorValue(constructor.name, constructor.arity)
                    },
                )
            }
            evalFieldML(expr.body, local)
        }
        is CLet -> {
            val local = env.child()
            // A recursive `let f x = ...` evaluates its own body in the scope that will hold `f`, so the
            // closure can find itself by the time it is called. Plain bindings evaluate in the parent
            // scope, which keeps `let x = x + 1` referring to the outer `x`.
            local.define(expr.name, evalFieldML(expr.value, if (expr.recursive) local else env))
            evalFieldML(expr.body, local)
        }
        is CLam -> FieldMLValue.ClosureValue(expr.parameter, expr.body, env)
        is CApp -> applyFieldMLFunction(evalFieldML(expr.function, env), evalFieldML(expr.argument, env))
        is CIf -> if (evalFieldML(expr.condition, env).asBool()) evalFieldML(expr.thenBranch, env) else evalFieldML(expr.elseBranch, env)
        is CMatch -> {
            val value = evalFieldML(expr.scrutinee, env)
            for (case in expr.cases) {
                val local = env.child()
                if (matchPattern(case.pattern, value, local)) {
                    return evalFieldML(case.body, local)
                }
            }
            error("Non-exhaustive FieldML match")
        }
        is CRecord -> FieldMLValue.RecordValue(expr.fields.associate { (name, value) -> name to evalFieldML(value, env) })
        is CField -> when (val target = evalFieldML(expr.target, env)) {
            is FieldMLValue.RecordValue -> target.fields[expr.field] ?: error("No such field: ${expr.field}")
            is FieldMLValue.HostValue -> readVectorComponent(target.value, expr.field)
                ?: error("No such field: ${expr.field}")
            else -> error("Field access on non-record value")
        }
        is CBinary -> when (expr.op) {
            // Short circuiting: the right-hand side is only evaluated when it can change the answer.
            BinaryOp.AND ->
                if (!evalFieldML(expr.left, env).asBool()) FieldMLValue.BoolValue(false)
                else FieldMLValue.BoolValue(evalFieldML(expr.right, env).asBool())
            BinaryOp.OR ->
                if (evalFieldML(expr.left, env).asBool()) FieldMLValue.BoolValue(true)
                else FieldMLValue.BoolValue(evalFieldML(expr.right, env).asBool())
            else -> evalBinary(expr.op, evalFieldML(expr.left, env), evalFieldML(expr.right, env))
        }
        is CNegate -> when (val value = evalFieldML(expr.value, env)) {
            is FieldMLValue.IntValue -> FieldMLValue.IntValue(-value.value)
            is FieldMLValue.FloatValue -> FieldMLValue.FloatValue(-value.value)
            else -> vectorComponents(value)?.let { parts -> vectorValue(FloatArray(parts.size) { -parts[it] }) }
                ?: error("Cannot negate ${renderFieldMLValue(value)}")
        }
    }

fun applyFieldMLFunction(function: FieldMLValue, argument: FieldMLValue): FieldMLValue =
    when (function) {
        is FieldMLValue.ClosureValue -> {
            val local = function.env.child()
            local.define(function.parameter, argument)
            evalFieldML(function.body, local)
        }
        is FieldMLValue.NativeFunctionValue -> function.apply(argument)
        is FieldMLValue.ConstructorValue -> {
            val args = function.arguments + argument
            if (args.size == function.arity) {
                FieldMLValue.AdtValue(function.tag, args)
            } else {
                function.copy(arguments = args)
            }
        }
        else -> error("Attempted to call ${renderFieldMLValue(function)}")
    }

private fun matchPattern(pattern: CorePattern, value: FieldMLValue, env: RuntimeEnv): Boolean =
    when (pattern) {
        is CPWildcard -> true
        is CPVar -> {
            env.define(pattern.name, value)
            true
        }
        is CPInt -> value is FieldMLValue.IntValue && value.value == pattern.value
        is CPBool -> value is FieldMLValue.BoolValue && value.value == pattern.value
        is CPString -> value is FieldMLValue.StringValue && value.value == pattern.value
        is CPTuple -> {
            value is FieldMLValue.TupleValue &&
                value.elements.size == pattern.elements.size &&
                pattern.elements.zip(value.elements).all { (subPattern, subValue) -> matchPattern(subPattern, subValue, env) }
        }
        is CPList -> {
            value is FieldMLValue.ListValue &&
                value.elements.size == pattern.elements.size &&
                pattern.elements.zip(value.elements).all { (subPattern, subValue) -> matchPattern(subPattern, subValue, env) }
        }
        is CPConstructor -> {
            value is FieldMLValue.AdtValue &&
                value.tag == pattern.name &&
                value.arguments.size == pattern.arguments.size &&
                pattern.arguments.zip(value.arguments).all { (subPattern, subValue) -> matchPattern(subPattern, subValue, env) }
        }
        is CPCons -> {
            value is FieldMLValue.ListValue &&
                value.elements.isNotEmpty() &&
                matchPattern(pattern.head, value.elements.first(), env) &&
                matchPattern(pattern.tail, FieldMLValue.ListValue(value.elements.drop(1)), env)
        }
    }

private fun evalBinary(op: BinaryOp, left: FieldMLValue, right: FieldMLValue): FieldMLValue {
    evalVectorBinary(op, left, right)?.let { return it }
    return when (op) {
        BinaryOp.ADD -> numeric(left, right, Long::plus, Double::plus)
        BinaryOp.SUB -> numeric(left, right, Long::minus, Double::minus)
        BinaryOp.MUL -> numeric(left, right, Long::times, Double::times)
        BinaryOp.DIV -> FieldMLValue.FloatValue(left.asDouble() / right.asDouble())
        BinaryOp.CONCAT -> FieldMLValue.StringValue(left.asStringValue() + right.asStringValue())
        BinaryOp.CONS -> {
            val tail = right as? FieldMLValue.ListValue ?: error("':: expects a list on the right")
            FieldMLValue.ListValue(listOf(left) + tail.elements)
        }
        BinaryOp.LT -> FieldMLValue.BoolValue(left.asDouble() < right.asDouble())
        BinaryOp.LTE -> FieldMLValue.BoolValue(left.asDouble() <= right.asDouble())
        BinaryOp.GT -> FieldMLValue.BoolValue(left.asDouble() > right.asDouble())
        BinaryOp.GTE -> FieldMLValue.BoolValue(left.asDouble() >= right.asDouble())
        BinaryOp.EQ -> FieldMLValue.BoolValue(left == right)
        BinaryOp.NEQ -> FieldMLValue.BoolValue(left != right)
        BinaryOp.AND -> FieldMLValue.BoolValue(left.asBool() && right.asBool())
        BinaryOp.OR -> FieldMLValue.BoolValue(left.asBool() || right.asBool())
    }
}

/**
 * Arithmetic over `Vec2`/`Vec3`/`Vec4`: component by component between two vectors of the same
 * width, or broadcast when one side is a scalar. Returns null when neither side is a vector, so the
 * caller can fall through to the ordinary numeric rules. Results are always freshly allocated —
 * the underlying linalg vectors are mutable, and FieldML values are not.
 */
private fun evalVectorBinary(op: BinaryOp, left: FieldMLValue, right: FieldMLValue): FieldMLValue? {
    val leftParts = vectorComponents(left)
    val rightParts = vectorComponents(right)
    if (leftParts == null && rightParts == null) return null
    if (op !in setOf(BinaryOp.ADD, BinaryOp.SUB, BinaryOp.MUL, BinaryOp.DIV)) {
        if (op == BinaryOp.EQ) return FieldMLValue.BoolValue(leftParts?.toList() == rightParts?.toList())
        if (op == BinaryOp.NEQ) return FieldMLValue.BoolValue(leftParts?.toList() != rightParts?.toList())
        error("Operator '${op.text}' is not defined for vectors")
    }
    val width = leftParts?.size ?: rightParts!!.size
    if (leftParts != null && rightParts != null && leftParts.size != rightParts.size) {
        error("Cannot combine a Vec${leftParts.size} and a Vec${rightParts.size}")
    }
    val leftAt = componentReader(leftParts, left)
    val rightAt = componentReader(rightParts, right)
    val out = FloatArray(width) { index ->
        val a = leftAt(index)
        val b = rightAt(index)
        when (op) {
            BinaryOp.ADD -> a + b
            BinaryOp.SUB -> a - b
            BinaryOp.MUL -> a * b
            else -> a / b
        }
    }
    return vectorValue(out)
}

private fun componentReader(parts: FloatArray?, value: FieldMLValue): (Int) -> Float {
    if (parts != null) return { index -> parts[index] }
    val scalar = value.asDouble().toFloat()
    return { scalar }
}

internal fun vectorComponents(value: FieldMLValue): FloatArray? =
    (value as? FieldMLValue.HostValue)?.let { vectorComponents(it.value) }

private fun vectorComponents(host: Any?): FloatArray? =
    when (host) {
        is Vector2f -> floatArrayOf(host.x, host.y)
        is Vector3f -> floatArrayOf(host.x, host.y, host.z)
        is Vector4f -> floatArrayOf(host.x, host.y, host.z, host.w)
        else -> null
    }

internal fun readVectorComponent(host: Any?, field: String): FieldMLValue? {
    val parts = vectorComponents(host) ?: return null
    val index = listOf("x", "y", "z", "w").indexOf(field)
    if (index < 0 || index >= parts.size) return null
    return FieldMLValue.FloatValue(parts[index].toDouble())
}

internal fun vectorValue(parts: FloatArray): FieldMLValue =
    FieldMLValue.HostValue(
        when (parts.size) {
            2 -> Vector2f(parts[0], parts[1])
            3 -> Vector3f(parts[0], parts[1], parts[2])
            4 -> Vector4f(parts[0], parts[1], parts[2], parts[3])
            else -> error("Unsupported vector width ${parts.size}")
        },
    )

private fun numeric(
    left: FieldMLValue,
    right: FieldMLValue,
    ints: (Long, Long) -> Long,
    floats: (Double, Double) -> Double,
): FieldMLValue =
    when {
        left is FieldMLValue.IntValue && right is FieldMLValue.IntValue -> FieldMLValue.IntValue(ints(left.value, right.value))
        left is FieldMLValue.FloatValue && right is FieldMLValue.FloatValue -> FieldMLValue.FloatValue(floats(left.value, right.value))
        else -> error("Mismatched numeric runtime values")
    }

private fun FieldMLValue.asBool(): Boolean =
    (this as? FieldMLValue.BoolValue)?.value ?: error("Expected Bool")

private fun FieldMLValue.asStringValue(): String =
    (this as? FieldMLValue.StringValue)?.value ?: error("Expected String")

private fun FieldMLValue.asDouble(): Double =
    when (this) {
        is FieldMLValue.IntValue -> value.toDouble()
        is FieldMLValue.FloatValue -> value
        else -> error("Expected number")
    }

fun fieldMLValueToHost(value: FieldMLValue): Any? =
    when (value) {
        is FieldMLValue.IntValue -> value.value
        is FieldMLValue.FloatValue -> value.value
        is FieldMLValue.BoolValue -> value.value
        is FieldMLValue.StringValue -> value.value
        is FieldMLValue.TupleValue -> value.elements.map(::fieldMLValueToHost)
        is FieldMLValue.ListValue -> value.elements.map(::fieldMLValueToHost)
        is FieldMLValue.RecordValue -> value.fields.mapValues { fieldMLValueToHost(it.value) }
        is FieldMLValue.AdtValue -> value
        FieldMLValue.UnitValue -> Unit
        is FieldMLValue.HostValue -> value.value
        is FieldMLValue.NativeFunctionValue -> value
        is FieldMLValue.FactoryValue -> value.create().let(::fieldMLValueToHost)
        is FieldMLValue.ClosureValue -> value
        is FieldMLValue.ConstructorValue -> value
    }

fun hostToFieldMLValue(value: Any?): FieldMLValue =
    when (value) {
        null -> FieldMLValue.UnitValue
        Unit -> FieldMLValue.UnitValue
        is FieldMLValue -> value
        is Int -> FieldMLValue.IntValue(value.toLong())
        is Long -> FieldMLValue.IntValue(value)
        is Short -> FieldMLValue.IntValue(value.toLong())
        is Byte -> FieldMLValue.IntValue(value.toLong())
        is Float -> FieldMLValue.FloatValue(value.toDouble())
        is Double -> FieldMLValue.FloatValue(value)
        is Boolean -> FieldMLValue.BoolValue(value)
        is String -> FieldMLValue.StringValue(value)
        is List<*> -> FieldMLValue.ListValue(value.map(::hostToFieldMLValue))
        is Map<*, *> -> FieldMLValue.RecordValue(
            value.entries.associate { (key, field) -> key.toString() to hostToFieldMLValue(field) },
        )
        else -> FieldMLValue.HostValue(value)
    }

fun renderFieldMLValue(value: FieldMLValue): String =
    when (value) {
        is FieldMLValue.IntValue -> value.value.toString()
        is FieldMLValue.FloatValue -> value.value.toString()
        is FieldMLValue.BoolValue -> value.value.toString()
        is FieldMLValue.StringValue -> "\"${value.value}\""
        is FieldMLValue.TupleValue -> value.elements.joinToString(prefix = "(", postfix = ")") { renderFieldMLValue(it) }
        is FieldMLValue.ListValue -> value.elements.joinToString(prefix = "[", postfix = "]") { renderFieldMLValue(it) }
        is FieldMLValue.RecordValue -> value.fields.entries.joinToString(prefix = "{", postfix = "}") { (name, field) ->
            "$name = ${renderFieldMLValue(field)}"
        }
        is FieldMLValue.AdtValue -> if (value.arguments.isEmpty()) {
            value.tag
        } else {
            value.arguments.joinToString(prefix = "${value.tag} ") { renderFieldMLValue(it) }
        }
        FieldMLValue.UnitValue -> "()"
        is FieldMLValue.HostValue -> value.value?.toString() ?: "null"
        is FieldMLValue.NativeFunctionValue -> "<native ${value.name}>"
        is FieldMLValue.FactoryValue -> "<factory ${value.name}>"
        is FieldMLValue.ClosureValue -> "<function>"
        is FieldMLValue.ConstructorValue -> "<constructor ${value.tag}>"
    }
