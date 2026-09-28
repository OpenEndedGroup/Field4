package afield.ml.core

import afield.ml.ast.BinaryOp
import afield.stdlib.linalg.Vector2f
import afield.stdlib.linalg.Vector3f
import afield.stdlib.linalg.Vector4f

/**
 * The FieldML runtime: it walks a lowered [CoreExpr] tree and returns the [FieldMLValue] the tree
 * evaluates to.
 *
 * This is the last stage of the pipeline. [Lowerer] hands over a tree whose lambdas are already
 * curried and whose source ranges are already gone, so nothing here can report a position and a
 * box blames the fragment it ran rather than the subexpression that failed. An [Evaluator] holds
 * the prelude it was constructed with and builds a fresh root scope for every `evaluate`, so two
 * runs of the same box share no state.
 *
 * Most of the value shapes are what their names say. Two are not. `HostValue` wraps an arbitrary
 * JVM object, which is how a FieldML box reaches Field at all: [FieldMLHostBridge] both produces
 * and consumes these, and `Vec2`, `Vec3` and `Vec4` are `HostValue`s holding the mutable linalg
 * vector types rather than a FieldML shape of their own. Arithmetic on them and access to their
 * `x`, `y`, `z` and `w` components are therefore special-cased here, component-wise between
 * vectors of equal width and broadcast against a scalar, and always into a freshly allocated
 * vector, because the linalg types are mutable and a FieldML value is not. `FactoryValue` is the
 * other: [RuntimeEnv] calls its `create` on every lookup, so each mention of the name is a
 * separate value while a `let`-bound name is one value. The prelude uses this once, for `fline`,
 * and needs to, because `moveTo` and `lineTo` mutate the line they are handed and return it.
 *
 * `hostToFieldMLValue` and `fieldMLValueToHost` are the conversion pair at the JVM boundary, and
 * they are not inverses. A tuple leaves as a plain `List` and comes back a list, so a round trip
 * through the host loses the distinction the type checker made; records, unit and the numeric
 * shapes survive one. Closures, native functions, partially applied constructors and ADT values
 * leave as themselves rather than as anything JVM-shaped, which is what lets the bridge's SAM
 * proxy apply one later.
 *
 * Scope is a chain of [RuntimeEnv]s with one parent each. A `let` always makes a child; a
 * recursive one evaluates its own body in the scope that will hold the name, and a plain one
 * evaluates in the parent, so `let x = x + 1` reads the outer `x`. A match makes one child per
 * case it tries, and a value matching no case fails at this point: nothing earlier checks
 * exhaustiveness, and this runtime error is the only mention of it in FieldML.
 *
 * Two failures here have their cause somewhere else. The walk is directly recursive with no
 * trampoline, so deep FieldML recursion arrives as a JVM `StackOverflowError` and not as a FieldML
 * diagnostic. And an unknown runtime name means the two halves of the prelude have drifted apart:
 * [Inferencer] already rejects any name `FieldMLTypePrelude.schemes` does not list, so a name that
 * type-checked and then went missing is one `FieldMLRuntimePrelude.values` does not define.
 */

/**
 * Everything a FieldML expression can evaluate to.
 *
 * The literal shapes fix FieldML's numeric width: its `Int` is a JVM `Long` and its `Float` a JVM
 * `Double`, one of each, which is why calling into Kotlin needs the conversion ladder
 * [FieldMLHostBridge] scores with.
 */
sealed interface FieldMLValue {
    /** A FieldML `Int`. There is only one integer width, and it is 64-bit. */
    data class IntValue(val value: Long) : FieldMLValue

    /** A FieldML `Float`. There is only one floating-point width, and it is 64-bit. */
    data class FloatValue(val value: Double) : FieldMLValue

    /** A FieldML `Bool`. */
    data class BoolValue(val value: Boolean) : FieldMLValue

    /** A FieldML `String`. */
    data class StringValue(val value: String) : FieldMLValue

    /**
     * A fixed-width tuple, whose elements may have different types.
     *
     * Structurally identical to [ListValue], and separate from it because the type checker treats
     * them differently. Nothing downstream of `fieldMLValueToHost` can tell them apart.
     */
    data class TupleValue(val elements: List<FieldMLValue>) : FieldMLValue

    /** A list of any length, whose elements all have one type. */
    data class ListValue(val elements: List<FieldMLValue>) : FieldMLValue

    /** A record, keyed by field name. This is also what a JVM `Map` arrives as. */
    data class RecordValue(val fields: Map<String, FieldMLValue>) : FieldMLValue

    /**
     * A fully applied data constructor: the constructor's name and its arguments.
     *
     * A zero-arity constructor is one of these immediately; anything wider becomes a
     * [ConstructorValue] first and turns into this once its last argument arrives.
     */
    data class AdtValue(val tag: String, val arguments: List<FieldMLValue>) : FieldMLValue

    /** The one value of FieldML's unit type, and what a Kotlin `Unit` or a null arrives as. */
    data object UnitValue : FieldMLValue

    /**
     * An arbitrary JVM object, opaque to FieldML itself.
     *
     * This is the whole of FieldML's reach into Field: a `Box`, an `FLine`, an `Img`, a linalg
     * vector. `hostGet` reads members off one, `hostCall` calls methods on one, and the vector
     * widths get component arithmetic on top. The type checker knows such a value only by the host
     * type it was given.
     */
    data class HostValue(val value: Any?) : FieldMLValue

    /**
     * A prelude function, implemented in Kotlin, taking exactly one argument.
     *
     * Multi-argument prelude entries are curried into nested ones, so they compose with FieldML's
     * own one-argument application and partial application works on them unchanged.
     */
    data class NativeFunctionValue(val name: String, val apply: (FieldMLValue) -> FieldMLValue) : FieldMLValue

    /**
     * A prelude entry that is a new value on every mention rather than a shared one.
     *
     * [RuntimeEnv] resolves it by calling `create`, so a factory is never itself the result of
     * looking a name up. This exists for prelude values whose members mutate them in place.
     */
    data class FactoryValue(val name: String, val create: () -> FieldMLValue) : FieldMLValue

    /**
     * A FieldML function: one parameter, an unevaluated body, and the scope it was written in.
     *
     * Every FieldML function has exactly one parameter by the time it gets here, because lowering
     * curries the multi-parameter ones.
     */
    data class ClosureValue(val parameter: String, val body: CoreExpr, val env: RuntimeEnv) : FieldMLValue

    /**
     * A data constructor part-way through being applied, holding the arguments it has so far.
     *
     * Applying the last one yields an [AdtValue]; applying fewer yields another of these, so a
     * constructor can be passed around half-filled like any other function.
     */
    data class ConstructorValue(val tag: String, val arity: Int, val arguments: List<FieldMLValue> = emptyList()) : FieldMLValue
}

/**
 * One FieldML scope: the names bound here, and the scope to fall back on for the rest.
 *
 * Scopes form a chain, one link per `let`, per function call and per match case tried, and
 * shadowing is what falling back up the chain gives for free. A [FieldMLValue.ClosureValue] keeps a
 * reference to the scope it was written in, which is what makes a FieldML function a closure and
 * what makes a recursive `let` able to find itself.
 */
class RuntimeEnv(
    private val parent: RuntimeEnv? = null,
    private val values: MutableMap<String, FieldMLValue> = mutableMapOf(),
) {
    /** A new scope that falls back on this one. */
    fun child(): RuntimeEnv = RuntimeEnv(this)

    /** Binds a name in this scope, shadowing any binding of it further up the chain. */
    fun define(name: String, value: FieldMLValue) {
        values[name] = value
    }

    /**
     * The value of a name, searching up the chain, resolving a [FieldMLValue.FactoryValue] as it
     * goes.
     *
     * Failing here is a prelude problem rather than a program problem: [Inferencer] has already
     * rejected every name the type prelude does not list, so a name that reaches this point and is
     * missing is one the runtime prelude does not define.
     */
    fun lookup(name: String): FieldMLValue {
        val value = values[name] ?: return parent?.lookup(name) ?: error("Unknown FieldML runtime name '$name'")
        return if (value is FieldMLValue.FactoryValue) value.create() else value
    }
}

/**
 * Evaluates lowered FieldML trees against one prelude.
 *
 * Cheap to construct and safe to reuse: the prelude is read per call, not held as mutable state.
 */
class Evaluator(private val prelude: Map<String, FieldMLValue> = emptyMap()) {
    /**
     * Walks [expr] and returns what it evaluates to, starting from a fresh scope holding the
     * prelude.
     *
     * Every call starts clean, so nothing a previous call bound is visible to this one. Failures
     * arrive as an `IllegalStateException` with no source position, because lowering has already
     * dropped the ranges; a deep enough recursion arrives as a `StackOverflowError` instead, since
     * the walk is the JVM stack.
     */
    fun evaluate(expr: CoreExpr): FieldMLValue {
        val env = RuntimeEnv()
        prelude.forEach { (name, value) -> env.define(name, value) }
        val observe = onTopLevelLet ?: return evalFieldML(expr, env)
        // The program's spine of top-level lets, walked here rather than by recursion so each binding's
        // value can be reported as it is made — exactly as the CLet case below evaluates one.
        var scope = env
        var rest = expr
        var index = 0
        while (rest is CLet) {
            val local = scope.child()
            val value = evalFieldML(rest.value, if (rest.recursive) local else scope)
            local.define(rest.name, value)
            observe(index++, rest.name, value)
            scope = local
            rest = rest.body
        }
        return evalFieldML(rest, scope)
    }

    /**
     * Called with each top-level `let` binding's position in the program's spine of lets (0 first), its
     * name and the value it bound, as [evaluate] makes it; the box records these for its line notes.
     */
    var onTopLevelLet: ((index: Int, name: String, value: FieldMLValue) -> Unit)? = null
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
            // Vector components first: they are the hot path and read straight off the value. Anything
            // else on a host value is a JVM member, which is what makes `img.width` work.
            is FieldMLValue.HostValue -> readVectorComponent(target.value, expr.field)
                ?: target.value?.let { FieldMLHostBridge.readMember(expr.field, it) }
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

/**
 * Applies one argument to one callable value, which is all application FieldML has.
 *
 * A closure binds its parameter in a child of the scope it captured and evaluates its body. A
 * native function runs its Kotlin. A constructor collects the argument and yields either a
 * finished [FieldMLValue.AdtValue] or a wider partial one. Anything else is not callable and
 * fails here.
 *
 * Public because the host bridge needs it: a FieldML function handed to a Kotlin parameter becomes
 * a proxy, and the proxy feeds its arguments in one at a time through this.
 */
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

/**
 * Unwraps a FieldML value into the JVM value a Kotlin caller should see.
 *
 * Literals unwrap to their boxed primitives, a record to a `Map`, a `HostValue` to whatever it was
 * holding, and both tuples and lists to a `List` — which is where the round trip with
 * `hostToFieldMLValue` stops being faithful, since a tuple comes back a list. The callable shapes
 * and a finished ADT value are returned as themselves, so that the host bridge can still recognise
 * a function it is expected to adapt.
 */
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

/**
 * Wraps a JVM value as the nearest FieldML value, falling back to an opaque
 * [FieldMLValue.HostValue].
 *
 * Every integral width collapses to FieldML's one `Int` and both floating widths to its one
 * `Float`, so the width a Kotlin method declared does not survive the return trip. A null and a
 * `Unit` both arrive as unit, a `List` as a list and a `Map` as a record with its keys stringified.
 * Anything else stays a JVM object that FieldML can only pass along or reach into with `hostGet`.
 */
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

/**
 * Renders a value the way FieldML source would spell it, for a box's output and for `show`.
 *
 * Tuples print in parentheses and lists in brackets, so this is the one path out of the runtime
 * that keeps them apart. A `HostValue` prints its object's `toString`, so what a box
 * shows for one is whatever Kotlin decided. The callable shapes have no source spelling and print
 * as an angle-bracketed placeholder naming the kind.
 */
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
