package afield.ml.type

import afield.ml.core.FieldMLHostBridge
import java.lang.reflect.Executable
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.TypeVariable
import java.lang.reflect.WildcardType
import kotlin.reflect.jvm.kotlinFunction

/**
 * What a `THost` *is*, and what reflection can therefore say about it at type-check time.
 *
 * Before this, every host value in FieldML had the single type `THost("Host")`: the moment a program
 * touched the JVM the type checker went blind, nothing downstream could be inferred, a misspelled
 * member name was a runtime crash, and completion had nothing to offer. The fix is small in
 * principle — let `THost.name` be a real JVM class name — and this object is the reflection that
 * makes it pay off: the type of a member, the result type of a resolved overload, and the list of
 * members a receiver actually has.
 *
 * Two rules keep it honest.
 *
 * *`Host` stays the dynamic type.* [OPAQUE] is what a host type degrades to whenever reflection
 * cannot justify anything better — a generic erased to `Object`, an array, a `Map`, a `CharSequence`
 * that may or may not be a `String`. It unifies with everything (see `Inferencer.unify`), which is
 * exactly the statement "unknown, checked at run time". Inventing a type here would be worse than
 * saying nothing.
 *
 * *A mapping only claims what the runtime actually does.* [fieldMLTypeOf] mirrors
 * `afield.ml.core.hostToFieldMLValue` case for case. If reflection says `int` then the runtime
 * really will hand back a `FieldMLValue.IntValue`; if it says `List<Double>` the runtime really will
 * hand back a list of floats. Anything the conversion would not turn into a FieldML value of a known
 * shape maps to [OPAQUE] rather than to something plausible.
 */
object FieldMLHostTypes {
    /** The dynamic host type: unknown JVM class, or a class whose shape reflection cannot pin down. */
    const val OPAQUE = "Host"

    private const val CLASS_PREFIX = "Class<"

    /**
     * Short display names FieldML has always used for the host types in its prelude, and the JVM
     * classes behind them. Keeping the names means `Vec2` still prints as `Vec2` while `hostGet` and
     * completion can still reflect on `Vector2f`.
     */
    private val curatedClassNames = mapOf(
        "Vec2" to "afield.stdlib.linalg.Vector2f",
        "Vec3" to "afield.stdlib.linalg.Vector3f",
        "Vec4" to "afield.stdlib.linalg.Vector4f",
        "FLine" to "afield.graphics.FLine",
        "Box" to "afield.Box",
    )

    /** The short names FieldML understands in `hostAs` and `hostClass`, for completion. */
    val curatedTypeNames: List<String> get() = curatedClassNames.keys.toList()

    /** The type of a host *value* of [className]. */
    fun valueType(className: String): THost =
        THost(curatedClassNames.entries.firstOrNull { it.value == className }?.key ?: className)

    fun valueType(owner: Class<*>): THost = valueType(owner.name)

    /** The type of the class handle `hostClass "…"` returns, distinct from a value of that class. */
    fun classHandleType(className: String): THost = THost("$CLASS_PREFIX$className>")

    /** The class named by a `Class<…>` handle type, or null when [type] is not one. */
    fun classHandleName(type: Type): String? =
        (type as? THost)?.name?.takeIf { it.startsWith(CLASS_PREFIX) && it.endsWith(">") }
            ?.removeSurrounding(CLASS_PREFIX, ">")

    /**
     * The JVM class behind a host *value* type, or null when there is none to reflect on — either
     * because the type is [OPAQUE], because it is a class handle rather than a value, or because the
     * class is not on this classpath (a `hostClass` name that was checked when it was written and is
     * being re-checked in a different build, say).
     */
    fun jvmClassOf(type: Type): Class<*>? {
        val name = (type as? THost)?.name ?: return null
        if (name == OPAQUE || name.startsWith(CLASS_PREFIX)) return null
        return loadClass(curatedClassNames[name] ?: name)
    }

    fun loadClass(className: String): Class<*>? = runCatching { Class.forName(className) }.getOrNull()

    /** How a host type reads in a signature: the simple name, since the package is rarely the point. */
    fun display(name: String): String =
        when {
            name.startsWith(CLASS_PREFIX) && name.endsWith(">") ->
                "$CLASS_PREFIX${display(name.removeSurrounding(CLASS_PREFIX, ">"))}>"
            else -> name.substringAfterLast('.').substringAfterLast('$')
        }

    // ---- JVM types to FieldML types --------------------------------------------------------------

    /**
     * The FieldML type a value of the JVM type [jvm] will have once it has been through
     * `hostToFieldMLValue`. [OPAQUE] whenever that cannot be said for certain.
     */
    fun fieldMLTypeOf(jvm: java.lang.reflect.Type?): Type {
        val raw = rawClassOf(jvm) ?: return opaque
        return when {
            raw == Void.TYPE || raw == Void::class.java || raw == Unit::class.java -> TUnit
            raw == Int::class.javaPrimitiveType || raw == Int::class.javaObjectType -> TInt
            raw == Long::class.javaPrimitiveType || raw == Long::class.javaObjectType -> TInt
            raw == Short::class.javaPrimitiveType || raw == Short::class.javaObjectType -> TInt
            raw == Byte::class.javaPrimitiveType || raw == Byte::class.javaObjectType -> TInt
            raw == Float::class.javaPrimitiveType || raw == Float::class.javaObjectType -> TFloat
            raw == Double::class.javaPrimitiveType || raw == Double::class.javaObjectType -> TFloat
            raw == Boolean::class.javaPrimitiveType || raw == Boolean::class.javaObjectType -> TBool
            raw == String::class.java -> TString
            // Only `List` and its subtypes: a declared `Collection` may arrive as a `Set`, which the
            // runtime conversion leaves as an opaque host value rather than a FieldML list.
            List::class.java.isAssignableFrom(raw) -> TList(elementTypeOf(jvm))
            raw.isArray -> opaque
            raw == Any::class.java -> opaque
            Map::class.java.isAssignableFrom(raw) -> opaque
            raw == Class::class.java -> classHandleType(classArgumentName(jvm) ?: Any::class.java.name)
            else -> valueType(raw)
        }
    }

    private val opaque = THost(OPAQUE)

    private fun elementTypeOf(jvm: java.lang.reflect.Type?): Type {
        val argument = (jvm as? ParameterizedType)?.actualTypeArguments?.firstOrNull() ?: return opaque
        return fieldMLTypeOf(argument)
    }

    private fun classArgumentName(jvm: java.lang.reflect.Type?): String? =
        rawClassOf((jvm as? ParameterizedType)?.actualTypeArguments?.firstOrNull())?.name

    private fun rawClassOf(jvm: java.lang.reflect.Type?): Class<*>? =
        when (jvm) {
            null -> null
            is Class<*> -> jvm
            is ParameterizedType -> jvm.rawType as? Class<*>
            is WildcardType -> jvm.upperBounds.firstOrNull()?.let { rawClassOf(it) }
            is TypeVariable<*> -> jvm.bounds.firstOrNull()?.let { rawClassOf(it) }
            else -> null
        }

    // ---- resolution --------------------------------------------------------------------------

    /**
     * The outcome of asking reflection about a member. [Unknown] is not a failure: it means the
     * receiver's class is not known, so the call has to stay dynamic and must *not* be reported as
     * an error.
     */
    sealed interface Resolution {
        data class Found(val type: Type) : Resolution
        data class Refused(val message: String) : Resolution
        data object Unknown : Resolution
    }

    fun memberType(receiver: Type, name: String): Resolution {
        val staticOwner = classHandleName(receiver)?.let { loadClass(it) }
        if (staticOwner != null) {
            FieldMLHostBridge.staticField(staticOwner, name)?.let { return Resolution.Found(fieldMLTypeOf(it.genericType)) }
            FieldMLHostBridge.staticGetter(staticOwner, name)?.let { return Resolution.Found(resultTypeOf(it)) }
            return Resolution.Refused("${staticOwner.simpleName} has no static member '$name'")
        }
        val owner = jvmClassOf(receiver) ?: return Resolution.Unknown
        FieldMLHostBridge.instanceField(owner, name)?.let { return Resolution.Found(fieldMLTypeOf(it.genericType)) }
        FieldMLHostBridge.instanceGetter(owner, name)?.let { return Resolution.Found(resultTypeOf(it)) }
        return Resolution.Refused("${owner.simpleName} has no member '$name'")
    }

    fun methodResultType(receiver: Type, name: String, arguments: List<Type>): Resolution {
        val owner = jvmClassOf(receiver) ?: return Resolution.Unknown
        return resolve(
            FieldMLHostBridge.instanceMethods(owner, name, arguments.size),
            arguments,
        ) { "${owner.simpleName} has no method '$name' taking ${describe(arguments)}" }
    }

    fun staticMethodResultType(classType: Type, name: String, arguments: List<Type>): Resolution {
        val owner = classHandleName(classType)?.let { loadClass(it) } ?: return Resolution.Unknown
        return resolve(
            FieldMLHostBridge.staticMethods(owner, name, arguments.size),
            arguments,
        ) { "${owner.simpleName} has no static method '$name' taking ${describe(arguments)}" }
    }

    fun constructorResultType(classType: Type, arguments: List<Type>): Resolution {
        val owner = classHandleName(classType)?.let { loadClass(it) } ?: return Resolution.Unknown
        val candidates = FieldMLHostBridge.constructors(owner, arguments.size)
        val viable = candidates.filter { scoreCandidate(it, arguments) != null }
        if (viable.isEmpty()) {
            return Resolution.Refused("${owner.simpleName} has no constructor taking ${describe(arguments)}")
        }
        return Resolution.Found(valueType(owner))
    }

    /**
     * The result type shared by every candidate that could take [arguments] — or, when they disagree,
     * the best-scoring one alone. Two equally good candidates with different result types is exactly
     * the case where refining would be a guess, so the answer degrades to [OPAQUE].
     */
    private fun resolve(
        candidates: List<Executable>,
        arguments: List<Type>,
        refusal: () -> String,
    ): Resolution {
        val scored = candidates.mapNotNull { candidate -> scoreCandidate(candidate, arguments)?.let { candidate to it } }
        if (scored.isEmpty()) return Resolution.Refused(refusal())
        val resultTypes = scored.map { resultTypeOf(it.first) }.distinct()
        if (resultTypes.size == 1) return Resolution.Found(resultTypes.single())
        val best = scored.minOf { it.second }
        val bestCandidates = scored.filter { it.second == best }.map { resultTypeOf(it.first) }.distinct()
        return Resolution.Found(bestCandidates.singleOrNull() ?: opaque)
    }

    private fun resultTypeOf(executable: Executable): Type =
        when (executable) {
            is java.lang.reflect.Method -> resultTypeOf(executable)
            else -> valueType(executable.declaringClass)
        }

    /**
     * A Kotlin `T?` return erases to `T`, and the runtime conversion turns a null into `Unit` rather
     * than into a host value of that class — so naming the class would be a type the program can
     * violate on its first run. Nullability is not visible to plain reflection (JetBrains'
     * `@Nullable` is `CLASS`-retention), so this is the one place that reaches for `kotlin-reflect`:
     * once per method, cached, and falling back to non-null when the metadata cannot be read.
     */
    private fun resultTypeOf(method: java.lang.reflect.Method): Type =
        if (returnsNullable(method)) opaque else fieldMLTypeOf(method.genericReturnType)

    private val nullableReturns = java.util.concurrent.ConcurrentHashMap<java.lang.reflect.Method, Boolean>()

    private fun returnsNullable(method: java.lang.reflect.Method): Boolean =
        nullableReturns.getOrPut(method) {
            runCatching { method.kotlinFunction?.returnType?.isMarkedNullable }.getOrNull() ?: false
        }

    /** Members `hostGet` can read, for completion. Static members when [receiver] is a class handle. */
    fun readableMembers(receiver: Type): List<HostMember> {
        val staticOwner = classHandleName(receiver)?.let { loadClass(it) }
        val owner = staticOwner ?: jvmClassOf(receiver) ?: return emptyList()
        val wantStatic = staticOwner != null
        val fields = owner.fields
            .filter { Modifier.isStatic(it.modifiers) == wantStatic }
            .map { HostMember(it.name, fieldMLTypeOf(it.genericType), owner.simpleName) }
        val getters = owner.methods
            .filter { Modifier.isStatic(it.modifiers) == wantStatic && it.parameterCount == 0 }
            .map { HostMember(propertyNameOf(it.name), resultTypeOf(it), owner.simpleName) }
        return (fields + getters).distinctBy { it.name }.sortedBy { it.name }
    }

    /** Methods `hostCall` can reach, for completion. Static methods when [receiver] is a class handle. */
    fun callableMethods(receiver: Type): List<HostMethod> {
        val staticOwner = classHandleName(receiver)?.let { loadClass(it) }
        val owner = staticOwner ?: jvmClassOf(receiver) ?: return emptyList()
        val wantStatic = staticOwner != null
        return owner.methods
            .filter { Modifier.isStatic(it.modifiers) == wantStatic && !it.isSynthetic }
            .map {
                HostMethod(
                    name = it.name,
                    parameters = it.parameterTypes.map { parameter -> parameter.simpleName },
                    resultType = resultTypeOf(it),
                    owner = owner.simpleName,
                )
            }
            .distinctBy { it.name to it.parameters }
            .sortedWith(compareBy({ it.name }, { it.parameters.size }))
    }

    private fun propertyNameOf(methodName: String): String =
        when {
            methodName.length > 3 && methodName.startsWith("get") && methodName[3].isUpperCase() ->
                methodName.removePrefix("get").replaceFirstChar(Char::lowercaseChar)
            else -> methodName
        }

    data class HostMember(val name: String, val type: Type, val owner: String)

    data class HostMethod(val name: String, val parameters: List<String>, val resultType: Type, val owner: String)

    // ---- type-level argument compatibility -------------------------------------------------------

    /**
     * The same scoring `FieldMLHostBridge` does at run time, but over FieldML *types* rather than
     * values, so that an overload set can be narrowed before the program runs. Deliberately more
     * permissive than the runtime in one direction: a `TVar` or an [OPAQUE] host is unknown, and
     * unknown must never refuse a candidate — otherwise a correct call would be reported as a type
     * error just because inference had not pinned an argument down yet.
     */
    private fun scoreCandidate(candidate: Executable, arguments: List<Type>): Int? {
        val parameters = candidate.parameterTypes
        val generics = candidate.genericParameterTypes
        if (candidate.isVarArgs) {
            val fixed = parameters.size - 1
            if (arguments.size < fixed) return null
            var total = VARARG_COST
            for (index in 0 until fixed) {
                total += scoreArgument(parameters[index], generics.getOrNull(index), arguments[index]) ?: return null
            }
            if (arguments.size == parameters.size) {
                scoreArgument(parameters[fixed], generics.getOrNull(fixed), arguments[fixed])?.let { return total + it }
            }
            val component = parameters[fixed].componentType ?: return null
            for (index in fixed until arguments.size) {
                total += scoreArgument(component, null, arguments[index]) ?: return null
            }
            return total
        }
        if (parameters.size != arguments.size) return null
        var total = 0
        for (index in parameters.indices) {
            total += scoreArgument(parameters[index], generics.getOrNull(index), arguments[index]) ?: return null
        }
        return total
    }

    private const val VARARG_COST = 8
    private const val UNKNOWN_COST = 1
    private const val NUMERIC_COST = 2
    private const val ARRAY_COST = 3
    private const val SAM_COST = 3
    private const val OBJECT_COST = 5

    private fun scoreArgument(parameter: Class<*>, generic: java.lang.reflect.Type?, argument: Type): Int? {
        val boxedParameter = FieldMLHostBridge.boxed(parameter)
        val erased = generic is TypeVariable<*> || generic is WildcardType || parameter == Any::class.java
        return when (argument) {
            is TVar -> UNKNOWN_COST
            // A FieldML `Int` *is* a `Long` at run time and a `Float` *is* a `Double`, so scoring the
            // natural class through the ladder the bridge uses is what keeps the overload the type
            // checker predicts and the overload the runtime invokes the same one.
            TInt -> numeric(parameter, Long::class.javaObjectType, erased)
            TFloat -> numeric(parameter, Double::class.javaObjectType, erased)
            TBool -> if (boxedParameter == Boolean::class.javaObjectType) 0 else objectish(boxedParameter, erased)
            TString -> when {
                boxedParameter == String::class.java -> 0
                CharSequence::class.java.isAssignableFrom(boxedParameter) -> 1
                boxedParameter == Char::class.javaObjectType -> NUMERIC_COST
                else -> objectish(boxedParameter, erased)
            }
            TUnit -> if (parameter.isPrimitive) null else OBJECT_COST
            // A FieldML list crosses as the `ArrayList` that `Iterable.map` returns, so the parameter
            // types it satisfies are the ones an `ArrayList` satisfies — including `ArrayList` itself,
            // which a `List`-only rule refused even though the call worked.
            is TList -> when {
                parameter == Any::class.java -> OBJECT_COST
                boxedParameter.isAssignableFrom(ArrayList::class.java) -> 0
                parameter.isArray -> ARRAY_COST
                else -> objectish(boxedParameter, erased)
            }
            is TTuple -> when {
                parameter == Any::class.java -> OBJECT_COST
                boxedParameter.isAssignableFrom(ArrayList::class.java) -> 0
                else -> objectish(boxedParameter, erased)
            }
            is TRecord -> if (boxedParameter.isAssignableFrom(Map::class.java)) 0 else objectish(boxedParameter, erased)
            is TFun -> if (FieldMLHostBridge.functionalMethod(parameter) != null) SAM_COST else objectish(boxedParameter, erased)
            is TAdt -> objectish(boxedParameter, erased)
            is THost -> hostArgument(argument, boxedParameter, erased)
        }
    }

    /**
     * The cost of a FieldML number reaching [parameter], where [natural] is the JVM class the value
     * really has once it crosses. A parameter the natural class already satisfies — `Number`,
     * `Comparable`, or the class itself — is exact; anything else numeric goes through the bridge's
     * affinity ladder so that the two scorers cannot drift apart.
     */
    private fun numeric(parameter: Class<*>, natural: Class<*>, erased: Boolean): Int? {
        val boxedParameter = FieldMLHostBridge.boxed(parameter)
        if (parameter == Any::class.java) return OBJECT_COST
        if (boxedParameter.isAssignableFrom(natural)) return 0
        return FieldMLHostBridge.numericAffinity(parameter, natural) ?: objectish(boxedParameter, erased)
    }

    /** Anything reaches `Object` or an erased generic; nothing else reaches an unrelated class. */
    private fun objectish(boxedParameter: Class<*>, erased: Boolean): Int? =
        if (erased || boxedParameter == Any::class.java) OBJECT_COST else null

    private fun hostArgument(argument: THost, boxedParameter: Class<*>, erased: Boolean): Int? {
        if (argument.name == OPAQUE) return UNKNOWN_COST
        if (classHandleName(argument) != null) {
            return if (boxedParameter.isAssignableFrom(Class::class.java)) 0 else objectish(boxedParameter, erased)
        }
        val owner = jvmClassOf(argument) ?: return UNKNOWN_COST
        return when {
            boxedParameter == owner -> 0
            boxedParameter.isAssignableFrom(owner) -> 1
            else -> objectish(boxedParameter, erased)
        }
    }

    private fun describe(arguments: List<Type>): String =
        if (arguments.isEmpty()) {
            "no arguments"
        } else {
            arguments.joinToString(", ", prefix = "(", postfix = ")") { TypePrinter().printSignature(it) }
        }
}
