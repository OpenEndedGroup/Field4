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
import kotlin.coroutines.Continuation
import kotlin.reflect.KFunction
import kotlin.reflect.KParameter
import kotlin.reflect.jvm.isAccessible
import kotlin.reflect.jvm.javaType
import kotlin.reflect.jvm.kotlinFunction

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

    // ---- named arguments, and therefore Kotlin defaults ----------------------------------------

    /**
     * Calls written with a **record** of named arguments instead of a tuple of positional ones —
     * `hostNew {window = w} Renderer` rather than `hostNew (w, []) Renderer`.
     *
     * This is mechanisms B and C of `dev_docs/FIELDML_HOST_BRIDGE.md`, and they turn out to be one
     * mechanism. Plain reflection sees neither a parameter's *name* nor its *default*: a Kotlin
     * `Renderer(window, additionalWindows = emptyList())` is, to `Class.getConstructors`, a
     * two-argument constructor and nothing else, which is why every optional parameter had to be
     * written out. `kotlin-reflect` sees both — `KParameter.name` and `KParameter.isOptional` — and
     * `KCallable.callBy` is the one entry point that does something with them, filling every
     * parameter absent from the map with the default the source declared. Naming the arguments is
     * therefore not a convenience *beside* defaults; it is the thing that makes defaults reachable
     * at all, because a positional call has no way to say which parameter it is skipping.
     *
     * Three things about this are deliberate.
     *
     * **The record replaces the tuple rather than adding a name.** `spreadArguments` already
     * dispatches on the shape of the argument — a tuple and a list spread, `Unit` is no arguments,
     * anything else is one — so a record spreading *by name* is the same rule continued, and
     * `hostCall1`, which never spreads, is the escape it always was for passing one of something.
     * The cost is real and worth stating: a `Map` that arrived from the JVM is a `RecordValue`, so
     * handing one to `hostCall` as a single argument now needs `hostCall1`.
     *
     * **Coercion still happens here, not in `callBy`.** `callBy` type-checks against the declared
     * `KType` and will not narrow a FieldML `Float` (a JVM `Double`) into a `Float` parameter, so
     * every supplied value goes through [coerce] against the erased parameter class first. That is
     * also what keeps SAM conversion working in a named call.
     *
     * **An ambiguous name set is refused rather than guessed.** Two overloads that both accept the
     * names given is exactly the case where picking one is a coin toss, and a wrong reflective call
     * is much harder to diagnose than a diagnostic that lists the candidates.
     */
    fun callMethodNamed(name: String, target: Any, arguments: Map<String, Any?>): FieldMLValue {
        val candidates = kotlinCandidates(instanceMethods(target.javaClass, name))
        val chosen = chooseNamed(candidates, arguments)
            ?: error(namedRefusal("method", name, target.javaClass, candidates, arguments))
        return hostToFieldMLValue(invokeNamed(chosen, target, null, arguments))
    }

    fun callStaticMethodNamed(
        name: String,
        owner: Class<*>,
        extensionReceiver: Any?,
        arguments: Map<String, Any?>,
    ): FieldMLValue {
        val candidates = kotlinCandidates(staticMethods(owner, name))
            .filter { (it.parameters.any { p -> p.kind == KParameter.Kind.EXTENSION_RECEIVER }) == (extensionReceiver != null) }
        val chosen = chooseNamed(candidates, arguments)
            ?: error(namedRefusal("static method", name, owner, candidates, arguments))
        return hostToFieldMLValue(invokeNamed(chosen, null, extensionReceiver, arguments))
    }

    fun constructNamed(owner: Class<*>, arguments: Map<String, Any?>): FieldMLValue {
        val candidates = owner.constructors.mapNotNull { runCatching { it.kotlinFunction }.getOrNull() }
        val chosen = chooseNamed(candidates, arguments)
            ?: error(namedRefusal("constructor", owner.simpleName, owner, candidates, arguments))
        return hostToFieldMLValue(invokeNamed(chosen, null, null, arguments))
    }

    /** All same-named members, any arity: a named call chooses by name set, not by count. */
    fun instanceMethods(owner: Class<*>, name: String): List<Method> =
        owner.methods.filter { !Modifier.isStatic(it.modifiers) && it.name == name }

    fun staticMethods(owner: Class<*>, name: String): List<Method> =
        owner.methods.filter { Modifier.isStatic(it.modifiers) && it.name == name }

    /**
     * The Kotlin view of some JVM methods, dropping the ones there is no Kotlin view of.
     *
     * `kotlinFunction` is null for a Java method and for the `name$default` synthetics the compiler
     * emits, and it *throws* on a few shapes it cannot model — both are "this member has no named
     * parameters", so both are simply not candidates.
     *
     * Cached, for the reason [FieldMLHostTypes.nullableFunctionArguments] is: reading Kotlin metadata
     * costs far more than the reflective call it precedes, this runs once per candidate per call
     * site, and completion re-compiles on every keystroke. `Method` has value-equal `hashCode`, and
     * the map is bounded by the number of distinct methods a document actually names.
     */
    fun kotlinFunctionOf(method: Method): KFunction<*>? =
        kotlinFunctions
            .getOrPut(method) { runCatching { method.kotlinFunction }.getOrNull() ?: absentKotlinFunction }
            .let { it as? KFunction<*> }

    private val kotlinFunctions = java.util.concurrent.ConcurrentHashMap<Method, Any>()

    /** A `ConcurrentHashMap` cannot hold a null, so "there is no Kotlin view" needs a stand-in. */
    private val absentKotlinFunction = Any()

    private fun kotlinCandidates(methods: List<Method>): List<KFunction<*>> =
        methods.mapNotNull { kotlinFunctionOf(it) }

    /**
     * The one candidate whose value parameters the given names satisfy, or null.
     *
     * Viable means every name given is a parameter of this candidate, and every parameter *not*
     * given either has a default or is a `vararg` (which Kotlin also treats as optional). Among the
     * viable, the one whose arguments need the least conversion wins — the same ladder positional
     * resolution uses, so `set(x = 1.0, y = 1.0)` picks the same overload `set(1.0, 1.0)` does. A
     * remaining tie is refused by the caller rather than broken arbitrarily.
     */
    private fun chooseNamed(candidates: List<KFunction<*>>, arguments: Map<String, Any?>): KFunction<*>? {
        val viable = candidates.filter { namedScore(it, arguments) != null }
        if (viable.isEmpty()) return null
        val best = viable.minOf { namedScore(it, arguments)!! }
        val winners = viable.filter { namedScore(it, arguments) == best }
        return winners.singleOrNull()
    }

    private fun namedScore(candidate: KFunction<*>, arguments: Map<String, Any?>): Int? {
        val valueParameters = candidate.parameters.filter { it.kind == KParameter.Kind.VALUE }
        val byName = valueParameters.associateBy { it.name }
        if (arguments.keys.any { it !in byName }) return null
        if (valueParameters.any { it.name !in arguments.keys && !it.isOptional && !it.isVararg }) return null
        var total = 0
        arguments.forEach { (name, value) ->
            val parameter = byName.getValue(name)
            val raw = erasedClass(parameter) ?: return@forEach
            val score = scoreArgument(raw, javaTypeOrNull(parameter), value)
            if (score == INCOMPATIBLE) return null
            total += score
        }
        return total
    }

    private fun invokeNamed(
        candidate: KFunction<*>,
        instance: Any?,
        extensionReceiver: Any?,
        arguments: Map<String, Any?>,
    ): Any? {
        val bound = mutableMapOf<KParameter, Any?>()
        candidate.parameters.forEach { parameter ->
            when (parameter.kind) {
                KParameter.Kind.INSTANCE -> instance?.let { bound[parameter] = it }
                KParameter.Kind.EXTENSION_RECEIVER -> extensionReceiver?.let { bound[parameter] = it }
                // A newer Kotlin adds `CONTEXT` for context parameters. Nothing in FieldML can name
                // one, and leaving it unbound is the same as leaving out a required parameter —
                // `callBy` says so, which is a better message than anything invented here.
                else -> {
                    if (parameter.kind != KParameter.Kind.VALUE) return@forEach
                    if (parameter.name !in arguments) return@forEach
                    val raw = erasedClass(parameter)
                    val value = arguments[parameter.name]
                    bound[parameter] = if (raw == null) value else coerce(raw, javaTypeOrNull(parameter), value)
                }
            }
        }
        candidate.isAccessible = true
        return try {
            candidate.callBy(bound)
        } catch (invocation: InvocationTargetException) {
            throw invocation.targetException ?: invocation
        }
    }

    private fun erasedClass(parameter: KParameter): Class<*>? =
        (parameter.type.classifier as? kotlin.reflect.KClass<*>)?.java

    private fun javaTypeOrNull(parameter: KParameter): java.lang.reflect.Type? =
        runCatching { parameter.type.javaType }.getOrNull()

    /**
     * Why a named call found nothing, in the terms the author can act on: what they named, and what
     * each candidate would have accepted. The optional/required split is the useful part — "you left
     * out `renderer`, which has no default" is a different mistake from "there is no `rendrer`".
     */
    private fun namedRefusal(
        kind: String,
        name: String,
        owner: Class<*>,
        candidates: List<KFunction<*>>,
        arguments: Map<String, Any?>,
    ): String {
        val given = arguments.keys.sorted().joinToString(", ")
        if (candidates.isEmpty()) return "No $kind '$name' on ${owner.name} has named parameters"
        val offered = candidates.take(4).joinToString("; ") { candidate ->
            candidate.parameters
                .filter { it.kind == KParameter.Kind.VALUE }
                .joinToString(", ", prefix = "(", postfix = ")") {
                    if (it.isOptional || it.isVararg) "${it.name}?" else "${it.name}"
                }
        }
        return "No $kind '$name' on ${owner.name} accepts exactly the names ($given)." +
            " Candidates (a trailing ? means the parameter has a default): $offered"
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
        val callable = if (executable is Method) invocable(executable) else executable
        callable.tryOpen()
        return try {
            // No `else`: `java.lang.reflect.Executable` is sealed and permits exactly `Method` and
            // `Constructor`, so a third kind cannot exist and a defensive branch here would only be
            // unreachable code. If the JDK ever widens it, let this fail to compile.
            when (callable) {
                is Constructor<*> -> callable.newInstance(*coerced)
                is Method -> callable.invoke(target, *coerced)
            }
        } catch (invocation: InvocationTargetException) {
            // The FieldML box should see what the Kotlin code complained about, not a wrapper.
            throw invocation.targetException ?: invocation
        }
    }

    /**
     * The same method, re-resolved onto a type this code can actually invoke through.
     *
     * Candidates are found on `target.javaClass`, and a value's **runtime** class is very often not
     * the type its API is declared on. `FloatBuffer.put(float)` is a public method of a public
     * class, but the object `MemoryUtil.memFloatBuffer` hands back is a `java.nio.DirectFloatBufferU`
     * — package-private, in a package `java.base` does not export. So `Class.getMethods` returns a
     * `Method` whose `declaringClass` is *that*, `setAccessible` cannot open it, and `Method.invoke`
     * throws `IllegalAccessException`. Inside a [Proxy] that arrives as an
     * `UndeclaredThrowableException` with a null message, which is about as unhelpful as a failure
     * gets — the box reports the wrapper and the cause exists only in the server log. That is how
     * this was found: writing vertex data from a FieldML `eachFrame` body, where every value in
     * sight is a JDK buffer.
     *
     * The fix is deliberately not to force access. `java.base` is closed on purpose and
     * `--add-opens` is not something a FieldML box should need. It is to *ask a different type*:
     * walk the public supertypes breadth-first, so the nearest one wins, and take the first that
     * declares the same name and parameters somewhere invocable. For the buffer that is
     * `java.nio.FloatBuffer` — exactly the type the author had in mind.
     *
     * Best effort by design: when nothing public declares it the original comes back and the call
     * fails as it did before, because a poor diagnostic is still better than the wrong method.
     */
    fun invocable(method: Method): Method {
        if (isInvocable(method.declaringClass)) return method
        return invocableMethods.getOrPut(method) {
            publicSupertypes(method.declaringClass)
                .firstNotNullOfOrNull { owner ->
                    runCatching { owner.getMethod(method.name, *method.parameterTypes) }
                        .getOrNull()
                        ?.takeIf { isInvocable(it.declaringClass) }
                }
                ?: method
        }
    }

    private val invocableMethods = java.util.concurrent.ConcurrentHashMap<Method, Method>()

    /**
     * Whether reflection may invoke a member declared on [type]: it has to be public *and* its
     * package exported from its module. The second half is the half that bites — a public class in
     * a non-exported package of a named module is unreachable however public the member is.
     */
    private fun isInvocable(type: Class<*>): Boolean {
        if (!Modifier.isPublic(type.modifiers)) return false
        val module = type.module ?: return true
        if (!module.isNamed) return true
        return module.isExported(type.packageName)
    }

    /** Superclasses and interfaces, breadth-first, so the closest usable declaration wins. */
    private fun publicSupertypes(type: Class<*>): List<Class<*>> {
        val seen = linkedSetOf<Class<*>>()
        val queue = ArrayDeque<Class<*>>()
        type.superclass?.let { queue += it }
        queue += type.interfaces
        while (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            if (!seen.add(next)) continue
            next.superclass?.let { queue += it }
            queue += next.interfaces
        }
        return seen.toList()
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
     * A Kotlin `suspend` function type as plain reflection sees it, or null for an ordinary one.
     *
     * `suspend Frames.() -> Unit` is not its own JVM type. It compiles to
     * `Function2<? super Frames, ? super Continuation<? super Unit>, ? extends Object>`: the
     * parameters as written, then a trailing [kotlin.coroutines.Continuation], then a return erased
     * to `Object` because a suspend call may hand back either its value *or* the
     * `COROUTINE_SUSPENDED` marker. Two consequences follow, and both are why this exists:
     *
     * - the continuation is a **calling-convention** argument, not one the author wrote, so it must
     *   not be curried into the FieldML closure;
     * - the declared result survives *only* as the continuation's type argument, so
     *   `Function2.invoke`'s own return says nothing. `forever`'s block really returns `Boolean`,
     *   and reading `? extends Object` instead is the difference between a loop and a cast failure.
     *
     * Detection has to read the *substituted* parameter, and it has to take the **lower** bound of a
     * wildcard: Kotlin emits parameters contravariantly, so `? super Continuation<…>` carries its
     * information below, not above. That is the same rule `FieldMLHostTypes.substituteVariables`
     * follows, and for the same reason — these two remain deliberate twins.
     */
    class SuspendShape(
        /** How many arguments the author actually writes: the SAM's arity minus the continuation. */
        val parameterCount: Int,
        /** The declared result, read out of `Continuation<? super R>`, or `Object` when erased. */
        val resultType: Class<*>,
    )

    fun suspendShape(iface: Class<*>, generic: java.lang.reflect.Type?): SuspendShape? {
        val method = functionalMethod(iface) ?: return null
        val parameters = method.genericParameterTypes
        val last = parameters.lastOrNull() ?: return null
        val variables = typeArguments(iface, generic)
        val continuation = contravariant(last, variables) ?: return null
        if (rawClass(continuation) != Continuation::class.java) return null
        val declared = (continuation as? ParameterizedType)
            ?.actualTypeArguments?.firstOrNull()
            ?.let { contravariant(it, variables) }
            ?.let { rawClass(it) }
        return SuspendShape(parameters.size - 1, declared ?: Any::class.java)
    }

    /**
     * Substitutes a functional interface's type variables and then reads a wildcard from the side
     * that carries the information for a *parameter* position — the lower bound first. See
     * [SuspendShape] for why that direction matters here and [resolveClass] for the covariant twin.
     */
    private fun contravariant(
        type: java.lang.reflect.Type?,
        variables: Map<String, java.lang.reflect.Type>,
    ): java.lang.reflect.Type? =
        when (type) {
            null -> null
            is TypeVariable<*> -> variables[type.name]?.let { contravariant(it, emptyMap()) }
            is WildcardType ->
                (type.lowerBounds.firstOrNull() ?: type.upperBounds.firstOrNull())
                    ?.let { contravariant(it, variables) }
            else -> type
        }

    private fun rawClass(type: java.lang.reflect.Type?): Class<*>? =
        when (type) {
            is Class<*> -> type
            is ParameterizedType -> type.rawType as? Class<*>
            else -> null
        }

    /**
     * Wraps a FieldML function as an instance of the functional interface [iface].
     *
     * The interesting part is [generic]: the erased signature of `Function2.invoke` is
     * `(Object, Object) -> Object`, so a proxy built from it alone would hand a boxed `Double` to a
     * parameter declared `Function2<Int, Int, Float>` and the Kotlin body would fail its implicit
     * cast. Reading the type arguments off the parameter's *generic* type at the use site is what
     * makes `Img.generate(w, h) { x, y -> … }` land a real `java.lang.Float`.
     *
     * A `suspend` functional type ([suspendShape]) is the same proxy with two edits, and the reason
     * it is only two is worth stating. On the JVM a `suspend` function is an ordinary method that
     * takes a trailing [kotlin.coroutines.Continuation] and returns *either* its value or the
     * `COROUTINE_SUSPENDED` marker; returning the value is the ordinary "completed without
     * suspending" path, which is exactly what a suspend body containing no suspension points
     * compiles to. FieldML has no suspension points — no `await`, no `yield`, and no reflective
     * route to a `suspend` JVM member, because such a member's arity counts the continuation the
     * bridge cannot construct — so an adapted FieldML closure *always* completes synchronously and
     * this proxy can never need to return the marker or resume the continuation it drops.
     *
     * The bound that buys, stated plainly: the FieldML body itself cannot suspend. `Frames.frame()`
     * and `Frames.delay()` stay out of reach, so `frames { }` — whose contract is that the body
     * drives `frame()` — is still not FieldML-writable, while `eachFrame`, `loop`, `forever`,
     * `Frames.end` and `Box.launchFrames`, where the enclosing Kotlin owns the suspension points and
     * the block is a plain per-frame body, all are.
     */
    private fun samProxy(iface: Class<*>, generic: java.lang.reflect.Type?, function: FieldMLValue): Any {
        val method = functionalMethod(iface) ?: error("${iface.name} is not a functional interface")
        val variables = typeArguments(iface, generic)
        val suspend = suspendShape(iface, generic)
        val resultType = suspend?.resultType ?: resolveClass(method.genericReturnType, variables) ?: method.returnType
        val loader = iface.classLoader ?: FieldMLHostBridge::class.java.classLoader
        val handler = InvocationHandler { proxy, called, rawArguments ->
            when {
                called.name == "hashCode" && called.parameterCount == 0 -> System.identityHashCode(proxy)
                called.name == "equals" && called.parameterCount == 1 -> proxy === rawArguments?.get(0)
                called.name == "toString" && called.parameterCount == 0 -> "<FieldML function as ${iface.simpleName}>"
                else -> {
                    val arrived = rawArguments?.toList().orEmpty()
                    // Drop the continuation, and only when one really arrived: a proxy is reachable
                    // from Kotlin that erased the type differently, and silently eating a real
                    // argument would be far worse than passing an extra one.
                    val incoming =
                        if (suspend != null && arrived.lastOrNull() is Continuation<*>) arrived.dropLast(1) else arrived
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
