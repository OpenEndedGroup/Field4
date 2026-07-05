package afield.ml.core

import afield.ml.ast.BinaryOp

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
        return eval(expr, env)
    }

    private fun eval(expr: CoreExpr, env: RuntimeEnv): FieldMLValue =
        when (expr) {
            is CInt -> FieldMLValue.IntValue(expr.value)
            is CFloat -> FieldMLValue.FloatValue(expr.value)
            is CBool -> FieldMLValue.BoolValue(expr.value)
            is CString -> FieldMLValue.StringValue(expr.value)
            is CVar -> env.lookup(expr.name)
            is CTuple -> FieldMLValue.TupleValue(expr.elements.map { eval(it, env) })
            is CList -> FieldMLValue.ListValue(expr.elements.map { eval(it, env) })
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
                eval(expr.body, local)
            }
            is CLet -> {
                val local = env.child()
                local.define(expr.name, eval(expr.value, env))
                eval(expr.body, local)
            }
            is CLam -> FieldMLValue.ClosureValue(expr.parameter, expr.body, env)
            is CApp -> apply(eval(expr.function, env), eval(expr.argument, env))
            is CIf -> if (eval(expr.condition, env).asBool()) eval(expr.thenBranch, env) else eval(expr.elseBranch, env)
            is CMatch -> evalMatch(expr, env)
            is CRecord -> FieldMLValue.RecordValue(expr.fields.associate { (name, value) -> name to eval(value, env) })
            is CField -> {
                val record = eval(expr.target, env) as? FieldMLValue.RecordValue
                    ?: error("Field access on non-record value")
                record.fields[expr.field] ?: error("No such field: ${expr.field}")
            }
            is CBinary -> evalBinary(expr.op, eval(expr.left, env), eval(expr.right, env))
        }

    private fun apply(function: FieldMLValue, argument: FieldMLValue): FieldMLValue =
        when (function) {
            is FieldMLValue.ClosureValue -> {
                val local = function.env.child()
                local.define(function.parameter, argument)
                eval(function.body, local)
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

    private fun evalMatch(expr: CMatch, env: RuntimeEnv): FieldMLValue {
        val value = eval(expr.scrutinee, env)
        for (case in expr.cases) {
            val local = env.child()
            if (matchPattern(case.pattern, value, local)) {
                return eval(case.body, local)
            }
        }
        error("Non-exhaustive FieldML match")
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
        }

    private fun evalBinary(op: BinaryOp, left: FieldMLValue, right: FieldMLValue): FieldMLValue =
        when (op) {
            BinaryOp.ADD -> numeric(left, right, Long::plus, Double::plus)
            BinaryOp.SUB -> numeric(left, right, Long::minus, Double::minus)
            BinaryOp.MUL -> numeric(left, right, Long::times, Double::times)
            BinaryOp.DIV -> FieldMLValue.FloatValue(left.asDouble() / right.asDouble())
            BinaryOp.LT -> FieldMLValue.BoolValue(left.asDouble() < right.asDouble())
            BinaryOp.LTE -> FieldMLValue.BoolValue(left.asDouble() <= right.asDouble())
            BinaryOp.GT -> FieldMLValue.BoolValue(left.asDouble() > right.asDouble())
            BinaryOp.GTE -> FieldMLValue.BoolValue(left.asDouble() >= right.asDouble())
            BinaryOp.EQ -> FieldMLValue.BoolValue(left == right)
            BinaryOp.NEQ -> FieldMLValue.BoolValue(left != right)
        }

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
}

private fun FieldMLValue.asBool(): Boolean =
    (this as? FieldMLValue.BoolValue)?.value ?: error("Expected Bool")

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
