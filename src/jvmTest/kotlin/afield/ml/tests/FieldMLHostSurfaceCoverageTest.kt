package afield.ml.tests

import afield.Box
import afield.ml.FieldMLCompiler
import afield.ml.FieldMLRuntimePrelude
import afield.ml.core.Evaluator
import afield.ml.core.FieldMLHostBridge
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type as JvmType
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * How much of a real Kotlin API surface can FieldML actually call?
 *
 * This is a *measurement*, not an example. For every public method of a fixed set of real Field
 * types it synthesises a FieldML program that calls it — building each argument from the parameter's
 * JVM type — then compiles it and, where it is safe to, runs it. A method counts as callable when the
 * program type-checks, which means the inferencer resolved the overload against the real reflected
 * candidate set; it counts as refused when no FieldML argument can be built for some parameter, or
 * when the bridge has no candidate that accepts the ones that can.
 *
 * The same walk also counts what the *previous* bridge could have reached — instance calls of arity
 * two or less whose parameters are numbers, booleans, strings or the four host types FieldML could
 * already produce — so the two numbers are measured in one run rather than one of them being
 * remembered. The assertion is a floor a few points under what was observed, so a real regression
 * fails the build while an incidental change to Field's API surface does not.
 *
 * What it deliberately does not claim: a callable method is one the bridge can *reach*, not one whose
 * arguments are meaningful. `Img.crop(1, 1, 1, 1)` is a real call with a made-up rectangle. Methods
 * whose names suggest they touch the filesystem are resolved but never invoked.
 */
class FieldMLHostSurfaceCoverageTest {
    /** Instance surfaces: a FieldML expression that builds the receiver, and the class to walk. */
    private val instanceSurfaces = listOf(
        "afield.graphics.FLine" to "fline |> moveTo 0.0 0.0 |> lineTo 10.0 5.0 |> lineTo 20.0 0.0",
        "afield.stdlib.Img" to imgExpression(),
        "afield.stdlib.linalg.Vector2f" to "vec2 1.0 2.0",
        "afield.stdlib.linalg.Vector3f" to "vec3 1.0 2.0 3.0",
        "afield.stdlib.linalg.Vector4f" to "vec4 1.0 2.0 3.0 4.0",
    )

    /** Static surfaces: the file classes Kotlin generates for Field's imaging extension functions. */
    private val staticSurfaces = listOf(
        "afield.stdlib.ImagingKt",
        "afield.stdlib.Imaging_filtersKt",
    )

    /**
     * Names that are resolved and type-checked but never invoked: anything that writes a file, opens a
     * window, or would take an unreasonable amount of time on a made-up argument.
     */
    private val doNotInvoke = listOf("save", "write", "load", "read", "show", "display", "open", "encode", "decode")

    @Test
    fun measuresHowMuchOfARealKotlinSurfaceFieldMLCanCall() {
        val results = mutableListOf<Outcome>()
        instanceSurfaces.forEach { (className, receiver) ->
            val owner = Class.forName(className)
            methodsOf(owner, static = false).forEach { method ->
                results += attempt(owner, method, receiver = receiver, static = false)
            }
        }
        staticSurfaces.forEach { className ->
            val owner = Class.forName(className)
            methodsOf(owner, static = true).forEach { method ->
                results += attempt(owner, method, receiver = null, static = true)
            }
        }

        val total = results.size
        val callable = results.count { it.callable }
        val invoked = results.count { it.invoked }
        val reachableBefore = results.count { it.reachableBeforeThisBridge }
        val percent = 100.0 * callable / total
        val percentBefore = 100.0 * reachableBefore / total

        val report = buildString {
            appendLine("FieldML host-bridge surface coverage over $total public methods of:")
            (instanceSurfaces.map { it.first } + staticSurfaces).forEach { appendLine("  $it") }
            appendLine("  callable now:      $callable / $total (%.1f%%)".format(percent))
            appendLine("  of those, invoked: $invoked")
            appendLine("  reachable before:  $reachableBefore / $total (%.1f%%)".format(percentBefore))
            appendLine("  refused now:       ${total - callable}")
            appendLine()
            appendLine("why the refusals refuse, most common first:")
            results.filter { !it.callable }
                .groupingBy { it.refusal.orEmpty().take(60) }
                .eachCount()
                .entries
                .sortedByDescending { it.value }
                .take(8)
                .forEach { (reason, count) -> appendLine("  ${count.toString().padStart(4)}  $reason") }
            appendLine()
            appendLine("a few refused signatures, for a reader deciding whether they matter:")
            // Compiler diagnostics first: those are the bridge or the type checker saying no, which is
            // actionable, while "no FieldML value for X" is a limit of this measurement's synthesiser.
            results.filter { !it.callable }
                .sortedBy { it.refusal?.startsWith("no FieldML value") != false }
                .take(14)
                .forEach { appendLine("  ${it.signature}  —  ${it.refusal}") }
        }
        println(report)

        // Floors, a few points under what this run measured, so the numbers above are the claim and
        // this is the guard. Raise them when the bridge learns something new.
        assertTrue(total > 340, "expected a surface of at least 340 methods, found $total\n$report")
        assertTrue(percent >= 92.0, "callable fell to %.1f%%\n%s".format(percent, report))
        assertTrue(
            percent - percentBefore >= 45.0,
            "the bridge only added %.1f points over what FieldML could already reach\n%s"
                .format(percent - percentBefore, report),
        )
        assertTrue(invoked >= callable / 2, "only $invoked of $callable callable methods ran\n$report")
    }

    private data class Outcome(
        val signature: String,
        val callable: Boolean,
        val invoked: Boolean,
        val refusal: String?,
        val reachableBeforeThisBridge: Boolean,
    )

    private fun attempt(owner: Class<*>, method: Method, receiver: String?, static: Boolean): Outcome {
        val signature = "${owner.simpleName}.${method.name}(${method.parameterTypes.joinToString { it.simpleName }})"
        val before = reachableBeforeThisBridge(method, static)
        val arguments = method.genericParameterTypes.map { synthesise(it) }
        val missing = method.parameterTypes.filterIndexed { index, _ -> arguments[index] == null }
        if (missing.isNotEmpty()) {
            return Outcome(signature, false, false, "no FieldML value for ${missing.first().simpleName}", before)
        }
        val spelled = arguments.map { it!! }
        val source = if (static) {
            """
            let Owner = hostClass "${owner.name}"
            ${callExpression("Owner", method.name, spelled, static = true)}
            """.trimIndent()
        } else {
            """
            let target = $receiver
            ${callExpression("target", method.name, spelled, static = false)}
            """.trimIndent()
        }

        val compiled = FieldMLCompiler.compile(source)
        if (!compiled.successful) {
            return Outcome(
                signature,
                false,
                false,
                compiled.diagnostics.firstOrNull { it.message.isNotBlank() }?.message,
                before,
            )
        }
        if (doNotInvoke.any { method.name.lowercase().contains(it) }) {
            return Outcome(signature, true, false, null, before)
        }
        val ran = runCatching {
            Evaluator(FieldMLRuntimePrelude.values(box = box)).evaluate(compiled.core!!)
        }
        return Outcome(signature, true, ran.isSuccess, ran.exceptionOrNull()?.message, before)
    }

    private fun callExpression(target: String, name: String, arguments: List<String>, static: Boolean): String =
        when {
            static -> """hostStaticCall "$name" (${arguments.joinToString(", ")}) $target"""
            arguments.isEmpty() -> """$target |> hostCall0 "$name""""
            // One argument goes through hostCall1, because hostCall spreads a list and a single list
            // argument would otherwise arrive as no arguments at all.
            arguments.size == 1 -> """$target |> hostCall1 "$name" (${arguments.single()})"""
            else -> """$target |> hostCall "$name" (${arguments.joinToString(", ")})"""
        }

    /**
     * A FieldML expression of the JVM type [jvm], or null when there is no way to write one. This is
     * the honest limit of the measurement: a parameter this cannot build counts as refused even if
     * the bridge would have handled a value someone else produced.
     */
    private fun synthesise(jvm: JvmType, depth: Int = 0): String? {
        if (depth > 2) return null
        val raw = rawOf(jvm) ?: return null
        FieldMLHostBridge.functionalMethod(raw)?.let { return lambdaFor(raw, it, jvm, depth) }
        return when {
            raw == Int::class.javaPrimitiveType || raw == Int::class.javaObjectType -> "1"
            raw == Long::class.javaPrimitiveType || raw == Long::class.javaObjectType -> "1"
            raw == Short::class.javaPrimitiveType || raw == Short::class.javaObjectType -> "1"
            raw == Byte::class.javaPrimitiveType || raw == Byte::class.javaObjectType -> "1"
            raw == Float::class.javaPrimitiveType || raw == Float::class.javaObjectType -> "1.0"
            raw == Double::class.javaPrimitiveType || raw == Double::class.javaObjectType -> "1.0"
            raw == Number::class.java -> "1.0"
            raw == Boolean::class.javaPrimitiveType || raw == Boolean::class.javaObjectType -> "true"
            raw == String::class.java || raw == CharSequence::class.java -> "\"a\""
            raw == Char::class.javaPrimitiveType || raw == Char::class.javaObjectType -> "\"a\""
            raw == Any::class.java -> "1.0"
            raw == Class::class.java -> """hostClass "java.lang.String""""
            raw.isEnum -> enumConstant(raw)
            // `isAssignableFrom` rather than equality: Field's linalg types have read-only interfaces
            // (`Vector3fc` and friends) that half the surface is declared against, and a `Vector3f`
            // satisfies them — so refusing those would understate what FieldML can call.
            raw.isAssignableFrom(afield.stdlib.linalg.Vector2f::class.java) -> "vec2 1.0 2.0"
            raw.isAssignableFrom(afield.stdlib.linalg.Vector3f::class.java) -> "vec3 1.0 2.0 3.0"
            raw.isAssignableFrom(afield.stdlib.linalg.Vector4f::class.java) -> "vec4 1.0 2.0 3.0 4.0"
            raw.isAssignableFrom(afield.graphics.FLine::class.java) -> "(fline |> moveTo 0.0 0.0 |> lineTo 10.0 5.0)"
            raw.isAssignableFrom(afield.stdlib.Img::class.java) -> "(${imgExpression()})"
            raw.isAssignableFrom(Box::class.java) -> "thisBox"
            List::class.java.isAssignableFrom(raw) -> listExpression(jvm, depth)
            raw.isArray -> "[]"
            hasNoArgConstructor(raw) -> """(hostNew0 (hostClass "${raw.name}"))"""
            else -> null
        }
    }

    /** A FieldML lambda of the right arity whose body is a value of the interface's result type. */
    private fun lambdaFor(iface: Class<*>, method: Method, jvm: JvmType, depth: Int): String? {
        val arity = method.parameterCount
        if (arity > 4) return null
        val parameters = (1..arity).map { "a$it" }
        val resultType = samResultType(iface, jvm) ?: method.returnType
        val body = when {
            resultType == Void.TYPE || resultType == Unit::class.java -> "Unit"
            else -> synthesise(resultType, depth + 1) ?: return null
        }
        val head = if (arity == 0) "fun ignored ->" else "fun ${parameters.joinToString(" ")} ->"
        return "($head $body)"
    }

    /** The interface's result type as written at the use site, since `Function2.invoke` erases it. */
    private fun samResultType(iface: Class<*>, jvm: JvmType): Class<*>? {
        val parameterized = jvm as? ParameterizedType ?: return null
        if (parameterized.rawType != iface) return null
        return rawOf(parameterized.actualTypeArguments.lastOrNull())
    }

    private fun listExpression(jvm: JvmType, depth: Int): String {
        val element = (jvm as? ParameterizedType)?.actualTypeArguments?.firstOrNull()
        val value = element?.let { synthesise(it, depth + 1) } ?: return "[]"
        return "[$value]"
    }

    private fun enumConstant(raw: Class<*>): String? {
        // `name`, not `toString()`: an enum that overrides its rendering would otherwise be spelled
        // with something that is not the static field's name.
        val constant = raw.enumConstants?.firstOrNull() as? Enum<*> ?: return null
        return """(hostGet "${constant.name}" (hostClass "${raw.name}"))"""
    }

    private fun hasNoArgConstructor(raw: Class<*>): Boolean =
        !raw.isInterface &&
            !Modifier.isAbstract(raw.modifiers) &&
            raw.constructors.any { it.parameterCount == 0 }

    /**
     * What FieldML could reach before this bridge: an instance call of arity two or less, every
     * parameter a number, boolean, string, or one of the four host types the prelude could build.
     * Constructors with arguments, lambdas, arrays, `null`, static members and arity three and up
     * were all simply unreachable.
     */
    private fun reachableBeforeThisBridge(method: Method, static: Boolean): Boolean {
        if (static) return false
        if (method.parameterCount > 2) return false
        return method.parameterTypes.all { parameter ->
            val boxed = FieldMLHostBridge.boxed(parameter)
            Number::class.java.isAssignableFrom(boxed) ||
                boxed == Boolean::class.javaObjectType ||
                boxed == String::class.java ||
                boxed == afield.stdlib.linalg.Vector2f::class.java ||
                boxed == afield.stdlib.linalg.Vector3f::class.java ||
                boxed == afield.stdlib.linalg.Vector4f::class.java ||
                boxed == afield.graphics.FLine::class.java
        }
    }

    /**
     * The public surface, minus the machinery a caller never writes: `Object`'s methods, Kotlin's
     * `…$default` and `…$lambda` bridges, synthetic members, and the internal-name mangling.
     */
    private fun methodsOf(owner: Class<*>, static: Boolean): List<Method> =
        owner.methods
            .filter { Modifier.isStatic(it.modifiers) == static }
            .filter { !it.isSynthetic && it.declaringClass != Any::class.java }
            .filter { '$' !in it.name }
            .filter { it.name !in setOf("equals", "hashCode", "toString", "wait", "notify", "notifyAll", "getClass") }
            .distinctBy { it.name to it.parameterTypes.toList() }

    private fun rawOf(jvm: JvmType?): Class<*>? =
        when (jvm) {
            null -> null
            is Class<*> -> jvm
            is ParameterizedType -> jvm.rawType as? Class<*>
            is java.lang.reflect.WildcardType -> jvm.upperBounds.firstOrNull()?.let { rawOf(it) }
            is java.lang.reflect.TypeVariable<*> -> jvm.bounds.firstOrNull()?.let { rawOf(it) }
            else -> null
        }

    private val box = Box("fieldml-host-surface-coverage")

    private fun imgExpression(): String {
        val samples = (0 until 16).joinToString(", ") { "%.2f".format(it / 15.0) }
        return """hostNew (4, 4, 1, [$samples]) (hostClass "afield.stdlib.Img")"""
    }
}
