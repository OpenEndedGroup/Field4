package afield.stdlib

import kotlin.reflect.KClass
import kotlin.reflect.KFunction1
import kotlin.reflect.KMutableProperty0
import kotlin.reflect.KParameter
import kotlin.reflect.KType
import kotlin.reflect.full.createType
import kotlin.reflect.full.isSubtypeOf

fun boundReceiverForCallable(k: Any): Any? {
    val m = kotlin.jvm.internal.CallableReference::class.java.getDeclaredMethod("getBoundReceiver")
    m.trySetAccessible()
    return m.invoke(k)
}

interface BoundContext {
    val bindings: MutableMap<String, BoundBinding>
    val previousValues: MutableMap<String, Any?>
    fun bindNow() {
        if (bindings.isEmpty()) return

        val toRemove = mutableListOf<String>()
        val entries = bindings.toList()
        entries.forEach { (k, v) ->
            when (val next = v.source.next()) {
                BoundNext.Done -> {
                    toRemove.add(k)
                    previousValues.remove(k)
                }
                is BoundNext.Value -> {
                    val a = next.value
                    val hasPrev = previousValues.containsKey(k)
                    val b = previousValues[k]
                    if (!hasPrev || a != b) {
                        val checked = v.check(a)
                        v.apply(checked)
                        previousValues[k] = a
                    }
                }
            }
        }
        toRemove.forEach { bindings.remove(it) }
    }

    fun clearBinding(name: String): Boolean {
        previousValues.remove(name)
        return bindings.remove(name) != null
    }
}

sealed interface BoundNext {
    data class Value(val value: Any?) : BoundNext
    object Done : BoundNext
}

sealed interface BoundSource {
    fun next(): BoundNext
}

class FunctionSource(private val fn: () -> Any?) : BoundSource {
    override fun next(): BoundNext = BoundNext.Value(fn())
}

class SequenceSource(private val iterator: Iterator<Any?>) : BoundSource {
    override fun next(): BoundNext = if (iterator.hasNext()) {
        BoundNext.Value(iterator.next())
    } else {
        BoundNext.Done
    }
}

data class BoundBinding(
    val source: BoundSource,
    val check: (Any?) -> Any?,
    val apply: (Any?) -> Unit
)

private fun boundContextFor(callable: Any): BoundContext {
    return boundReceiverForCallable(callable) as? BoundContext
        ?: error("Callable must be bound to a BoundContext receiver")
}

private fun BoundContext.registerBinding(
    name: String,
    source: BoundSource,
    check: (Any?) -> Any?,
    apply: (Any?) -> Unit
): Int {
    val replaced = bindings.put(name, BoundBinding(source, check, apply)) != null
    previousValues.remove(name)
    return if (replaced) 1 else 0
}

private fun typeMismatch(label: String, expected: KType, value: Any?): Nothing {
    val actualType = value?.let { it::class.qualifiedName } ?: "null"
    val actualValue = safeToString(value)
    throw IllegalArgumentException(
        "Binding $label expected ${expected} but got $actualType = $actualValue"
    )
}

private fun typeChecker(label: String, expected: KType): (Any?) -> Any? {
    val nullable = expected.isMarkedNullable
    val expectedClass = expected.classifier as? KClass<*>
    if (expectedClass == Any::class && nullable) {
        return { it }
    }
    if (expectedClass != null) {
        return { value ->
            if (value == null) {
                if (!nullable) typeMismatch(label, expected, value)
                null
            } else {
                if (!expectedClass.isInstance(value)) {
                    typeMismatch(label, expected, value)
                }
                value
            }
        }
    }
    return { value ->
        if (value == null) {
            if (!nullable) typeMismatch(label, expected, value)
            null
        } else {
            val actualType = value::class.createType()
            if (!actualType.isSubtypeOf(expected)) {
                typeMismatch(label, expected, value)
            }
            value
        }
    }
}

private fun KFunction1<*, *>.valueParameterType(): KType {
    val param = this.parameters.firstOrNull { it.kind == KParameter.Kind.VALUE }
        ?: error("Function ${this.name} has no value parameter")
    return param.type
}

private fun safeToString(v: Any?): String = when (v) {
    null -> "null"
    is String -> "\"$v\""
    is Char -> "'$v'"
    is Boolean, is Number -> v.toString()
    is Array<*> -> v.contentDeepToString()
    is ByteArray -> v.contentToString()
    is ShortArray -> v.contentToString()
    is IntArray -> v.contentToString()
    is LongArray -> v.contentToString()
    is FloatArray -> v.contentToString()
    is DoubleArray -> v.contentToString()
    is CharArray -> v.contentToString()
    is BooleanArray -> v.contentToString()
    else -> runCatching { v.toString() }
        .getOrElse { "<toString failed: ${it::class.java.simpleName}: ${it.message}>" }
}

operator fun <A, B> KFunction1<A, B>.compareTo(other: () -> A): Int {
    val b = boundContextFor(this)
    val paramType = valueParameterType()
    val check = typeChecker("function '${this.name}'", paramType)
    return b.registerBinding(
        this.name,
        FunctionSource(other),
        check
    ) { value ->
        @Suppress("UNCHECKED_CAST")
        this.invoke(value as A)
    }
}

operator fun <A, B> KFunction1<A, B>.compareTo(other: Sequence<A>): Int {
    val b = boundContextFor(this)
    val paramType = valueParameterType()
    val check = typeChecker("function '${this.name}'", paramType)
    return b.registerBinding(
        this.name,
        SequenceSource(other.iterator()),
        check
    ) { value ->
        @Suppress("UNCHECKED_CAST")
        this.invoke(value as A)
    }
}

operator fun <T> KMutableProperty0<T>.compareTo(other: () -> T): Int {
    val b = boundContextFor(this)
    val check = typeChecker("property '${this.name}'", this.returnType)
    return b.registerBinding(
        this.name,
        FunctionSource(other),
        check
    ) { value ->
        @Suppress("UNCHECKED_CAST")
        this.set(value as T)
    }
}

operator fun <T> KMutableProperty0<T>.compareTo(other: Sequence<T>): Int {
    val b = boundContextFor(this)
    val check = typeChecker("property '${this.name}'", this.returnType)
    return b.registerBinding(
        this.name,
        SequenceSource(other.iterator()),
        check
    ) { value ->
        @Suppress("UNCHECKED_CAST")
        this.set(value as T)
    }
}

fun KFunction1<*, *>.clear(): Boolean {
    val b = boundContextFor(this)
    return b.clearBinding(this.name)
}

fun KMutableProperty0<*>.clear(): Boolean {
    val b = boundContextFor(this)
    return b.clearBinding(this.name)
}
