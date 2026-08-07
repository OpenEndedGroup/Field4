package afield.ml.core

import java.lang.reflect.Array as ReflectArray
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.GenericArrayType
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Proxy
import java.lang.reflect.TypeVariable
import java.lang.reflect.WildcardType

/**
 * The reflective half of FieldML's JVM bridge: given a name, a receiver and some already-converted
 * FieldML arguments, find the JVM member that the call actually meant and make it happen.
 *
 * Three things here are worth more than the reflection boilerplate they cost.
 *
 * **Scored overload resolution.** Field's Kotlin surface is full of same-named members that differ
 * only in numeric width (`Number` vs `Double` vs `Float`) or in whether they take a lambda. Taking
 * the first name-and-arity match, as the bridge used to, picks whichever one `Class.getMethods`
 * happens to return first — so a call that *has* a working target still fails, or silently reaches
 * the wrong one. Every candidate is scored per argument instead ([scoreArgument]), lowest total
 * wins, and a tie is resolved by declaration depth so an override beats the bridge method Kotlin
 * generates for it.
 *
 * **SAM conversion.** A FieldML function handed to a parameter whose type is a functional interface
 * becomes a [Proxy] ([samProxy]). This is what makes Kotlin's trailing-lambda builders reachable at
 * all: `Img.generate(w, h) { x, y -> … }` is a `Function2` parameter, and without this the FieldML
 * closure arrives as an opaque [FieldMLValue] and no candidate matches. The result has to be
 * coerced to the *generic* return type read off the use site, because `Function2`'s `invoke` erases
 * to `Object` and a `Function2<Int, Int, Float>` parameter genuinely needs a `java.lang.Float`.
 *
 * **Widening, arrays and varargs.** FieldML has one integer type and one float type, so nearly
 * every real call needs a numeric conversion somewhere; a FieldML list is the only way to spell an
 * array; and Kotlin `vararg` is an array parameter that callers do not write as one.
 *
 * FieldML values arrive here already mapped by [fieldMLValueToHost], which is identity-ish for
 * everything except functions — those stay [FieldMLValue]s so that [samProxy] can still see them.
 */
object FieldMLHostBridge {
    /** Score of an argument that cannot reach a parameter at all. */
    private const val INCOMPATIBLE = -1

    /**
     * Tuple- and list-spread, the shape `hostStaticCall` has always used: `hostCall "m" (a, b, c) t`
     * passes three arguments. Anything else is a single argument, which is what makes
     * `hostCall1 "lines" [line]` the way to pass one list *as* a list.
     */
    fun spreadArguments(value: FieldMLValue): List<Any?> =
        when (value) {
            is FieldMLValue.TupleValue -> value.elements.map(::fieldMLValueToHost)
            is FieldMLValue.ListValue -> value.elements.map(::fieldMLValueToHost)
            FieldMLValue.UnitValue -> emptyList()
            else -> listOf(fieldMLValueToHost(value))
        }

    fun callMethod(name: String, target: Any, arguments: List<Any?>): FieldMLValue {
        val candidates = instanceMethods(target.javaClass, name, arguments.size)
        val chosen = choose(candidates, arguments)
            ?: error(refusal("method", name, target.javaClass, candidates, arguments))
        return hostToFieldMLValue(invoke(chosen, target, arguments))
    }

    fun callStaticMethod(name: String, owner: Class<*>, arguments: List<Any?>): FieldMLValue {
        val candidates = staticMethods(owner, name, arguments.size)
        val chosen = choose(candidates, arguments)
            ?: error(refusal("static method", name, owner, candidates, arguments))
        return hostToFieldMLValue(invoke(chosen, null, arguments))
    }

    fun construct(owner: Class<*>, arguments: List<Any?>): FieldMLValue {
        val candidates = constructors(owner, arguments.size)
        val chosen = choose(candidates, arguments)
            ?: error(refusal("constructor", owner.simpleName, owner, candidates, arguments))
        return hostToFieldMLValue(invoke(chosen, null, arguments))
    }

    /** Members visible to `hostGet`: a map key, a static member of a `Class`, or an instance member. */
    fun readMember(name: String, target: Any): FieldMLValue {
        if (target is Map<*, *>) return hostToFieldMLValue(target[name])
        if (target is Class<*>) {
            staticField(target, name)?.let { return hostToFieldMLValue(it.get(null)) }
            staticGetter(target, name)?.let { return hostToFieldMLValue(invoke(it, null, emptyList())) }
            error("No readable static member '$name' on ${target.name}")
        }
        instanceField(target.javaClass, name)?.let { return hostToFieldMLValue(it.get(target)) }
        instanceGetter(target.javaClass, name)?.let { return hostToFieldMLValue(invoke(it, target, emptyList())) }
        error("No readable member '$name' on ${target.javaClass.name}")
    }

    fun writeMember(name: String, target: Any, value: Any?) {
        if (target is MutableMap<*, *>) {
            @Suppress("UNCHECKED_CAST")
            (target as MutableMap<String, Any?>)[name] = value
            return
        }
        if (target is Class<*>) {
            staticField(target, name)?.takeIf { !Modifier.isFinal(it.modifiers) }?.let {
                it.set(null, coerce(it.type, it.genericType, value))
                return
            }
            staticSetter(target, name)?.let {
                invoke(it, null, listOf(value))
                return
            }
            error("No writable static member '$name' on ${target.name}")
        }
        instanceField(target.javaClass, name)?.takeIf { !Modifier.isFinal(it.modifiers) }?.let {
            it.set(target, coerce(it.type, it.genericType, value))
            return
        }
        instanceSetter(target.javaClass, name)?.let {
            invoke(it, target, listOf(value))
            return
        }
        error("No writable member '$name' on ${target.javaClass.name}")
    }

    // ---- candidate sets ----------------------------------------------------------------------
    //
    // Arity is `>=` rather than `==` for varargs: `lines(box, lines, channel)` and
    // `hstack(vararg images, gap)` both have a fixed part plus a tail the caller spells out flat.

    fun instanceMethods(owner: Class<*>, name: String, arity: Int): List<Method> =
        owner.methods.filter { !Modifier.isStatic(it.modifiers) && it.name == name && acceptsArity(it, arity) }

    fun staticMethods(owner: Class<*>, name: String, arity: Int): List<Method> =
        owner.methods.filter { Modifier.isStatic(it.modifiers) && it.name == name && acceptsArity(it, arity) }

    fun constructors(owner: Class<*>, arity: Int): List<Constructor<*>> =
        owner.constructors.filter { acceptsArity(it, arity) }

    private fun acceptsArity(executable: Executable, arity: Int): Boolean =
        executable.parameterCount == arity || (executable.isVarArgs && arity >= executable.parameterCount - 1)

    fun instanceField(owner: Class<*>, name: String): Field? =
        owner.fields.firstOrNull { !Modifier.isStatic(it.modifiers) && it.name == name }?.also { it.tryOpen() }

    fun staticField(owner: Class<*>, name: String): Field? =
        owner.fields.firstOrNull { Modifier.isStatic(it.modifiers) && it.name == name }?.also { it.tryOpen() }

    /**
     * Kotlin properties reach reflection as `getFoo()`; Java records and Kotlin's `is`-prefixed
     * booleans as `foo()` and `isFoo()`. A plain `foo()` method is also a legitimate reader, which
     * is what lets `hostGet "pixelCount"` and `hostGet "copy"` both work.
     */
    fun instanceGetter(owner: Class<*>, name: String): Method? =
        getterNames(name).firstNotNullOfOrNull { candidate ->
            owner.methods.firstOrNull {
                !Modifier.isStatic(it.modifiers) && it.name == candidate && it.parameterCount == 0
            }
        }?.also { it.tryOpen() }

    fun staticGetter(owner: Class<*>, name: String): Method? =
        getterNames(name).firstNotNullOfOrNull { candidate ->
            owner.methods.firstOrNull {
                Modifier.isStatic(it.modifiers) && it.name == candidate && it.parameterCount == 0
            }
        }?.also { it.tryOpen() }

    private fun getterNames(name: String): List<String> {
        val capitalised = name.replaceFirstChar(Char::uppercaseChar)
        return listOf(name, "get$capitalised", "is$capitalised")
    }

    private fun instanceSetter(owner: Class<*>, name: String): Method? =
        owner.methods.firstOrNull {
            !Modifier.isStatic(it.modifiers) &&
                it.name == "set${name.replaceFirstChar(Char::uppercaseChar)}" &&
                it.parameterCount == 1
        }?.also { it.tryOpen() }

    private fun staticSetter(owner: Class<*>, name: String): Method? =
        owner.methods.firstOrNull {
            Modifier.isStatic(it.modifiers) &&
                it.name == "set${name.replaceFirstChar(Char::uppercaseChar)}" &&
                it.parameterCount == 1
        }?.also { it.tryOpen() }

    // ---- overload resolution ------------------------------------------------------------------

    /**
     * The best candidate for [arguments], or null when none of them can take these arguments.
     *
     * Ties break towards the *most derived* declaring class, which is how a real override wins over
     * the covariant bridge method the compiler emits next to it — both accept the same arguments,
     * but the bridge's parameters are erased, so it scores identically and would otherwise be
     * picked at random.
     */
    fun <T : Executable> choose(candidates: List<T>, arguments: List<Any?>): T? =
        candidates
            .mapNotNull { candidate -> scoreCandidate(candidate, arguments)?.let { candidate to it } }
            .minWithOrNull(
                compareBy<Pair<T, Int>> { it.second }
                    .thenByDescending { inheritanceDepth(it.first.declaringClass) }
                    .thenBy { it.first.parameterCount },
            )
            ?.first

    /** Total conversion cost of calling [candidate] with [arguments], or null if it cannot be. */
    fun scoreCandidate(candidate: Executable, arguments: List<Any?>): Int? {
        val parameters = candidate.parameterTypes
        val generics = candidate.genericParameterTypes
        if (candidate.isVarArgs) {
            val fixed = parameters.size - 1
            if (arguments.size < fixed) return null
            var total = 0
            for (index in 0 until fixed) {
                val score = scoreArgument(parameters[index], generics.getOrNull(index), arguments[index])
                if (score == INCOMPATIBLE) return null
                total += score
            }
            // An argument already of the array type is passed through as the whole vararg tail.
            if (arguments.size == parameters.size) {
                val direct = scoreArgument(parameters[fixed], generics.getOrNull(fixed), arguments[fixed])
                if (direct != INCOMPATIBLE) return total + direct
            }
            val component = parameters[fixed].componentType ?: return null
            for (index in fixed until arguments.size) {
                val score = scoreArgument(component, null, arguments[index])
                if (score == INCOMPATIBLE) return null
                total += score
            }
            return total + VARARG_COST
        }
        if (parameters.size != arguments.size) return null
        var total = 0
        for (index in parameters.indices) {
            val score = scoreArgument(parameters[index], generics.getOrNull(index), arguments[index])
            if (score == INCOMPATIBLE) return null
            total += score
        }
        return total
    }

    private const val VARARG_COST = 8

    /**
     * Cost of putting [argument] into a [parameter] slot, or [INCOMPATIBLE]. Zero is an exact fit;
     * the ordering of the rest is the whole point — a `Double` argument prefers a `double`
     * parameter to a `Number` one, and prefers `Number` to `Object`.
     */
    private fun scoreArgument(parameter: Class<*>, generic: java.lang.reflect.Type?, argument: Any?): Int {
        if (argument == null) return if (parameter.isPrimitive) INCOMPATIBLE else 1
        val boxedParameter = boxed(parameter)
        if (boxedParameter.isInstance(argument)) return if (parameter == Any::class.java) OBJECT_COST else 0
        if (argument is FieldMLValue && isFieldMLFunction(argument)) {
            return if (functionalMethod(parameter) != null) SAM_COST else INCOMPATIBLE
        }
        if (argument is Number && isNumeric(boxedParameter)) {
            return numericAffinity(parameter, argument.javaClass) ?: NUMERIC_COST
        }
        if (argument is Number && boxedParameter == Char::class.javaObjectType) return NUMERIC_COST
        if (argument is CharSequence && boxedParameter == Char::class.javaObjectType) {
            return if (argument.length == 1) NUMERIC_COST else INCOMPATIBLE
        }
        if (argument is List<*> && parameter.isArray) return ARRAY_COST
        if (argument is FieldMLValue) return INCOMPATIBLE
        if (parameter == Any::class.java) return OBJECT_COST
        // A generic parameter erased to its bound still accepts anything that satisfies the bound,
        // and erasure is exactly the case where reflection cannot do better than let it through.
        if (generic is TypeVariable<*> || generic is WildcardType) return OBJECT_COST
        return INCOMPATIBLE
    }

    private const val NUMERIC_COST = 2
    private const val ARRAY_COST = 3
    private const val SAM_COST = 3
    private const val OBJECT_COST = 5

    private const val SAME_KIND_COST = 1
    private const val WIDENING_COST = 2
    private const val NARROWING_COST = 4

    /**
     * How well a numeric argument fits a numeric parameter, or null when either side is not numeric.
     *
     * The ladder exists because FieldML has exactly one integer type and one float type, so almost
     * every call needs a conversion and "needs a conversion" cannot be one flat cost. `Vector2f` is
     * the case that proved it: `set(int, float)` returns nothing and `set(float, float)` returns the
     * vector, both take a FieldML `(1.0, 1.0)`, and with a single cost for "not the same class" the two
     * tied and the winner was whatever `Class.getMethods` happened to list first. Preferring the
     * parameter of the *same kind* — floating point for a FieldML `Float`, integral for an `Int` — and
     * charging most for the lossy direction picks the one a reader means.
     *
     * Shared with `afield.ml.type.FieldMLHostTypes` on purpose: the overload the type checker predicts
     * and the overload the runtime invokes have to be the same one, and the only way to be sure of
     * that is for both to score with this function.
     */
    fun numericAffinity(parameter: Class<*>, argument: Class<*>): Int? {
        val parameterType = boxed(parameter)
        val argumentType = boxed(argument)
        if (!isNumeric(parameterType) || !isNumeric(argumentType)) return null
        if (parameterType == argumentType) return 0
        val parameterIsFloating = parameterType in floatingPointTypes
        val argumentIsFloating = argumentType in floatingPointTypes
        return when {
            parameterIsFloating == argumentIsFloating -> SAME_KIND_COST
            // A float value into an integral slot silently drops the fraction; an integral value into
            // a float slot does not. Only the first should be a last resort.
            argumentIsFloating -> NARROWING_COST
            else -> WIDENING_COST
        }
    }

    private val floatingPointTypes = setOf(Double::class.javaObjectType, Float::class.javaObjectType)

    private fun inheritanceDepth(type: Class<*>): Int {
        var depth = 0
        var current: Class<*>? = type
        while (current != null) {
            depth += 1
            current = current.superclass
        }
        return depth
    }

    // ---- invocation ---------------------------------------------------------------------------

    private fun invoke(executable: Executable, target: Any?, arguments: List<Any?>): Any? {
        val coerced = coerceAll(executable, arguments)
        executable.tryOpen()
        return try {
            when (executable) {
                is Constructor<*> -> executable.newInstance(*coerced)
                is Method -> executable.invoke(target, *coerced)
            }
        } catch (invocation: InvocationTargetException) {
            // The FieldML box should see what the Kotlin code complained about, not a wrapper.
            throw invocation.targetException ?: invocation
        }
    }

    private fun coerceAll(executable: Executable, arguments: List<Any?>): Array<Any?> {
        val parameters = executable.parameterTypes
        val generics = executable.genericParameterTypes
        if (!executable.isVarArgs) {
            return Array(parameters.size) { index -> coerce(parameters[index], generics.getOrNull(index), arguments[index]) }
        }
        val fixed = parameters.size - 1
        val out = arrayOfNulls<Any?>(parameters.size)
        for (index in 0 until fixed) {
            out[index] = coerce(parameters[index], generics.getOrNull(index), arguments[index])
        }
        val tailType = parameters[fixed]
        if (arguments.size == parameters.size && boxed(tailType).isInstance(arguments[fixed])) {
            out[fixed] = arguments[fixed]
            return out
        }
        val component = tailType.componentType
        val tail = arguments.drop(fixed)
        val array = ReflectArray.newInstance(component, tail.size)
        tail.forEachIndexed { index, value -> ReflectArray.set(array, index, coerce(component, null, value)) }
        out[fixed] = array
        return out
    }

    /** Converts one already-host-side value into exactly what a JVM parameter or field wants. */
    fun coerce(parameter: Class<*>, generic: java.lang.reflect.Type?, argument: Any?): Any? {
        if (argument == null) return null
        val boxedParameter = boxed(parameter)
        if (argument is FieldMLValue && isFieldMLFunction(argument)) {
            return samProxy(parameter, generic, argument)
        }
        if (boxedParameter.isInstance(argument) && !(parameter.isArray && argument is List<*>)) return argument
        if (argument is Number) {
            return when (boxedParameter) {
                Int::class.javaObjectType -> argument.toInt()
                Long::class.javaObjectType -> argument.toLong()
                Float::class.javaObjectType -> argument.toFloat()
                Double::class.javaObjectType -> argument.toDouble()
                Short::class.javaObjectType -> argument.toShort()
                Byte::class.javaObjectType -> argument.toByte()
                Char::class.javaObjectType -> argument.toInt().toChar()
                else -> argument
            }
        }
        if (argument is CharSequence && boxedParameter == Char::class.javaObjectType && argument.length == 1) {
            return argument[0]
        }
        if (argument is List<*> && parameter.isArray) {
            val component = parameter.componentType
            val array = ReflectArray.newInstance(component, argument.size)
            argument.forEachIndexed { index, value -> ReflectArray.set(array, index, coerce(component, null, value)) }
            return array
        }
        return argument
    }

    // ---- SAM conversion -----------------------------------------------------------------------

    /**
     * The single abstract method of a functional interface, or null when [type] is not one. Default
     * and static methods do not count, and neither do the `Object` methods an interface may
     * redeclare — `Comparator`'s `equals` is the usual example.
     */
    fun functionalMethod(type: Class<*>): Method? {
        if (!type.isInterface) return null
        val abstract = type.methods.filter {
            Modifier.isAbstract(it.modifiers) && !Modifier.isStatic(it.modifiers) && !isObjectMethod(it)
        }
        return abstract.singleOrNull()
    }

    private fun isObjectMethod(method: Method): Boolean =
        runCatching { Any::class.java.getMethod(method.name, *method.parameterTypes) }.isSuccess

    private fun isFieldMLFunction(value: FieldMLValue): Boolean =
        value is FieldMLValue.ClosureValue ||
            value is FieldMLValue.NativeFunctionValue ||
            value is FieldMLValue.ConstructorValue

    /**
     * Wraps a FieldML function as an instance of the functional interface [iface].
     *
     * The interesting part is [generic]: the erased signature of `Function2.invoke` is
     * `(Object, Object) -> Object`, so a proxy built from it alone would hand a boxed `Double` to a
     * parameter declared `Function2<Int, Int, Float>` and the Kotlin code would fail its implicit
     * cast. Reading the type arguments off the parameter's *generic* type at the use site is what
     * makes `Img.generate(w, h) { x, y -> … }` land a real `java.lang.Float`.
     */
    private fun samProxy(iface: Class<*>, generic: java.lang.reflect.Type?, function: FieldMLValue): Any {
        val method = functionalMethod(iface) ?: error("${iface.name} is not a functional interface")
        val variables = typeArguments(iface, generic)
        val resultType = resolveClass(method.genericReturnType, variables) ?: method.returnType
        val loader = iface.classLoader ?: FieldMLHostBridge::class.java.classLoader
        val handler = InvocationHandler { proxy, called, rawArguments ->
            when {
                called.name == "hashCode" && called.parameterCount == 0 -> System.identityHashCode(proxy)
                called.name == "equals" && called.parameterCount == 1 -> proxy === rawArguments?.get(0)
                called.name == "toString" && called.parameterCount == 0 -> "<FieldML function as ${iface.simpleName}>"
                else -> {
                    val incoming = rawArguments?.toList().orEmpty()
                    var current = function
                    if (incoming.isEmpty()) {
                        // Nothing to feed a curried FieldML function with; `Runnable`-shaped
                        // interfaces get Unit so `fun ignored -> …` works, and a non-function value
                        // is simply the result.
                        if (isFieldMLFunction(current)) current = applyFieldMLFunction(current, FieldMLValue.UnitValue)
                    } else {
                        incoming.forEach { current = applyFieldMLFunction(current, hostToFieldMLValue(it)) }
                    }
                    when {
                        resultType == Void.TYPE -> null
                        resultType == Unit::class.java -> Unit
                        else -> coerce(resultType, null, fieldMLValueToHost(current))
                    }
                }
            }
        }
        return Proxy.newProxyInstance(loader, arrayOf(iface), handler)
    }

    private fun typeArguments(iface: Class<*>, generic: java.lang.reflect.Type?): Map<String, java.lang.reflect.Type> {
        val parameterized = generic as? ParameterizedType ?: return emptyMap()
        if (parameterized.rawType != iface) return emptyMap()
        val names = iface.typeParameters.map { it.name }
        val actual = parameterized.actualTypeArguments
        return names.indices
            .filter { it < actual.size }
            .associate { names[it] to actual[it] }
    }

    private fun resolveClass(
        type: java.lang.reflect.Type?,
        variables: Map<String, java.lang.reflect.Type>,
    ): Class<*>? =
        when (type) {
            null -> null
            is Class<*> -> type
            is ParameterizedType -> type.rawType as? Class<*>
            is TypeVariable<*> -> variables[type.name]?.let { resolveClass(it, emptyMap()) }
            is WildcardType -> type.upperBounds.firstOrNull()?.let { resolveClass(it, variables) }
            is GenericArrayType -> Array<Any?>::class.java
            else -> null
        }

    // ---- diagnostics --------------------------------------------------------------------------

    /**
     * Why nothing matched, in the terms a FieldML author can act on: the arguments they passed and
     * the shapes that were on offer. A bare "no compatible method" sends people to read Kotlin.
     */
    private fun refusal(
        kind: String,
        name: String,
        owner: Class<*>,
        candidates: List<Executable>,
        arguments: List<Any?>,
    ): String {
        val given = arguments.joinToString(", ") { describeArgument(it) }
        if (candidates.isEmpty()) return "No $kind '$name' taking ${arguments.size} argument(s) on ${owner.name}"
        val offered = candidates.take(6).joinToString("; ") { candidate ->
            candidate.parameterTypes.joinToString(", ", prefix = "(", postfix = ")") { it.simpleName }
        }
        return "No $kind '$name' on ${owner.name} accepts ($given). Candidates: $offered"
    }

    private fun describeArgument(argument: Any?): String =
        when (argument) {
            null -> "null"
            is FieldMLValue -> if (isFieldMLFunction(argument)) "function" else renderFieldMLValue(argument)
            else -> argument.javaClass.simpleName
        }

    // ---- primitives ---------------------------------------------------------------------------

    private fun isNumeric(boxedType: Class<*>): Boolean =
        Number::class.java.isAssignableFrom(boxedType)

    fun boxed(type: Class<*>): Class<*> =
        when (type) {
            java.lang.Integer.TYPE -> Int::class.javaObjectType
            java.lang.Long.TYPE -> Long::class.javaObjectType
            java.lang.Float.TYPE -> Float::class.javaObjectType
            java.lang.Double.TYPE -> Double::class.javaObjectType
            java.lang.Boolean.TYPE -> Boolean::class.javaObjectType
            java.lang.Short.TYPE -> Short::class.javaObjectType
            java.lang.Byte.TYPE -> Byte::class.javaObjectType
            java.lang.Character.TYPE -> Char::class.javaObjectType
            else -> type
        }

    /**
     * Opening a member is best effort on purpose: Field's own classes are open to us, but a JDK or
     * library member in a closed module throws here, and refusing the call outright would be worse
     * than trying it — a public method of a public class usually works without any of this.
     */
    private fun java.lang.reflect.AccessibleObject.tryOpen() {
        runCatching { trySetAccessible() }
    }
}
