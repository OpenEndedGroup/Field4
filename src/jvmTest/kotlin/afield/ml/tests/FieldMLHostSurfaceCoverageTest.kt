package afield.ml.tests

import afield.Box
import afield.graphics.FLine
import afield.ml.FieldMLCompiler
import afield.ml.FieldMLRuntimePrelude
import afield.ml.core.Evaluator
import afield.ml.core.FieldMLHostBridge
import afield.ml.core.fieldMLValueToHost
import afield.stdlib.Img
import afield.stdlib.linalg.Vector2f
import afield.stdlib.linalg.Vector3f
import afield.stdlib.linalg.Vector4f
import java.lang.reflect.Array as ReflectArray
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Proxy
import java.lang.reflect.Type as JvmType
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * How much of a real Kotlin API surface can FieldML call, and does it get the same answer?
 *
 * This is a measurement, not an example. For every public method of a fixed set of real Field types
 * it builds **two** calls from one argument list: a FieldML program that goes through the host
 * bridge, and the same method invoked directly through plain `Method.invoke` with argument values of
 * exactly the right JVM types. Both run in this test, and their results are compared.
 *
 * The distinction matters, and an earlier version of this test did not make it. *Callable* only says
 * the program type-checked and the bridge found a candidate — a bridge that silently passed the
 * wrong argument, chose the wrong overload, or lost precision converting a result would score just
 * as well. *Agreed* says the value FieldML got back is the value Kotlin gets back. That is the number
 * to read.
 *
 * Every method lands in exactly one bucket, and the printed table says how many are in each:
 *
 * | Bucket | Meaning |
 * | --- | --- |
 * | `agreed` | both calls ran, and the results compare equal by value |
 * | `agreed (both refused)` | both calls threw the *same* exception class — the method's own precondition, faithfully reproduced |
 * | `incomparable` | both ran and returned the same class, but that class has no value equality to compare with |
 * | `resolved, not run` | type-checked, but the name suggests it writes a file or opens a window, so it is never invoked |
 * | `input-independent` | two *direct* calls with fresh receivers already disagree (a global counter), so there is nothing to compare against |
 * | `disagreed` | ran on both sides and the answers differ, or one threw and the other did not — a bug |
 * | `refused` | no FieldML value could be built for a parameter, or the bridge/type checker said no |
 *
 * The same walk also counts what the *previous* bridge could have reached, so the two coverage
 * numbers are measured in one run rather than one of them being remembered.
 *
 * What it still does not claim: an argument this synthesises is *reachable*, not *meaningful*.
 * `Img.crop(1, 1, 1, 1)` is a real call with a made-up rectangle — but now it is a made-up rectangle
 * that both sides agree about, which is the property the bridge is responsible for.
 * `FieldMLHostAgreementTest` covers the semantically interesting calls by hand.
 */
class FieldMLHostSurfaceCoverageTest {
    private data class Surface(val className: String, val fieldML: String, val kotlin: () -> Any)

    private val instanceSurfaces = listOf(
        Surface(
            "afield.graphics.FLine",
            "fline |> moveTo 0.0 0.0 |> lineTo 10.0 5.0 |> lineTo 20.0 0.0",
        ) { FLine().moveTo(0.0, 0.0).lineTo(10.0, 5.0).lineTo(20.0, 0.0) },
        Surface("afield.stdlib.Img", imgFieldML(), ::imgKotlin),
        Surface("afield.stdlib.linalg.Vector2f", "vec2 1.0 2.0") { Vector2f(1.0f, 2.0f) },
        Surface("afield.stdlib.linalg.Vector3f", "vec3 1.0 2.0 3.0") { Vector3f(1.0f, 2.0f, 3.0f) },
        Surface("afield.stdlib.linalg.Vector4f", "vec4 1.0 2.0 3.0 4.0") { Vector4f(1.0f, 2.0f, 3.0f, 4.0f) },
    )

    /** Static surfaces: the file classes Kotlin generates for Field's imaging extension functions. */
    private val staticSurfaces = listOf(
        "afield.stdlib.ImagingKt",
        "afield.stdlib.Imaging_filtersKt",
    )

    /**
     * Names that are type-checked but never invoked: anything that writes a file, opens a window, or
     * would take an unreasonable amount of time on a made-up argument.
     */
    private val doNotInvoke = listOf("save", "write", "load", "read", "show", "display", "open", "encode", "decode")

    @Test
    fun measuresHowMuchOfARealKotlinSurfaceFieldMLCanCallAndWhetherItAgrees() {
        val results = mutableListOf<Outcome>()
        instanceSurfaces.forEach { surface ->
            val owner = Class.forName(surface.className)
            methodsOf(owner, static = false).forEach { method ->
                results += attempt(owner, method, surface, static = false)
            }
        }
        staticSurfaces.forEach { className ->
            val owner = Class.forName(className)
            methodsOf(owner, static = true).forEach { method ->
                results += attempt(owner, method, surface = null, static = true)
            }
        }

        val total = results.size
        val callable = results.count { it.verdict != Verdict.REFUSED }
        val ran = results.count { it.verdict in RAN }
        val agreed = results.count { it.verdict == Verdict.AGREED || it.verdict == Verdict.AGREED_BOTH_REFUSED }
        val disagreed = results.filter { it.verdict == Verdict.DISAGREED }
        val incomparable = results.count { it.verdict == Verdict.INCOMPARABLE }
        val reachableBefore = results.count { it.reachableBeforeThisBridge }
        val callablePercent = 100.0 * callable / total
        val beforePercent = 100.0 * reachableBefore / total
        // The denominator is the calls where a comparison was actually *possible*. Folding the
        // incomparable ones in would read as a failure rate when it is an absence of evidence, and
        // they are reported on their own line instead.
        val comparable = agreed + disagreed.size
        val agreementPercent = if (comparable == 0) 0.0 else 100.0 * agreed / comparable

        val report = buildString {
            appendLine("FieldML host bridge over $total public methods of:")
            (instanceSurfaces.map { it.className } + staticSurfaces).forEach { appendLine("    $it") }
            appendLine()
            appendLine("  reachability")
            appendLine("    callable now:         $callable / $total (%.1f%%)".format(callablePercent))
            appendLine(
                "    reachable before:     $reachableBefore / $total (%.1f%%)  — modelled, not re-run"
                    .format(beforePercent),
            )
            appendLine("    refused:              ${total - callable}")
            appendLine()
            appendLine("  agreement with the same call made directly in Kotlin")
            appendLine("    calls run both ways:  $ran")
            appendLine("    comparable:           $comparable")
            appendLine("    agreed:               $agreed / $comparable (%.1f%%)".format(agreementPercent))
            appendLine("    no way to compare:    $incomparable  (a returned class with no value equality)")
            Verdict.entries.forEach { verdict ->
                val count = results.count { it.verdict == verdict }
                if (count > 0) appendLine("      ${verdict.label.padEnd(20)} ${count.toString().padStart(4)}")
            }
            appendLine()
            appendLine("  why the refusals refuse, most common first:")
            results.filter { it.verdict == Verdict.REFUSED }
                .groupingBy { it.detail.orEmpty().take(60) }
                .eachCount()
                .entries
                .sortedByDescending { it.value }
                .take(8)
                .forEach { (reason, count) -> appendLine("    ${count.toString().padStart(4)}  $reason") }
            appendLine()
            appendLine("  refused signatures — compiler diagnostics first, since those are actionable:")
            results.filter { it.verdict == Verdict.REFUSED }
                .sortedBy { it.detail?.startsWith("no FieldML value") != false }
                .take(14)
                .forEach { appendLine("    ${it.signature}  —  ${it.detail}") }
            if (disagreed.isNotEmpty()) {
                appendLine()
                appendLine("  DISAGREEMENTS — the bridge did not reproduce the direct call:")
                disagreed.forEach { appendLine("    ${it.signature}  —  ${it.detail}") }
            }
        }
        println(report)

        // Floors a point or so under what this run measured, so the numbers above are the claim and
        // these are the guard. Raise them when the bridge learns something new.
        assertTrue(total > 340, "expected a surface of at least 340 methods, found $total\n$report")
        assertTrue(callablePercent >= 94.0, "callable fell to %.1f%%\n%s".format(callablePercent, report))
        // A *modelled* delta, and worth saying so plainly: `reachableBeforeThisBridge` is a
        // hand-written description of what the old bridge could reach, not the old bridge itself. It
        // is accurate about the shapes that were unreachable — arity three and up, statics, lambdas,
        // constructors with arguments — but it is still a description, so read this as "the shapes
        // this bridge added cover at least 45 points of the surface", not as a re-run of the old code.
        assertTrue(
            callablePercent - beforePercent >= 45.0,
            "the bridge only added %.1f points over the *modelled* reach of the old one\n%s"
                .format(callablePercent - beforePercent, report),
        )
        assertTrue(ran >= 300, "only $ran calls were run both ways\n$report")
        assertTrue(comparable >= 300, "only $comparable calls could be compared by value\n$report")
        // A disagreement is a bridge bug, so this floor is not "high", it is "all of them". It is the
        // assertion that turns callability into correctness, and it is the reason this test exists.
        assertTrue(
            disagreed.isEmpty(),
            "${disagreed.size} call(s) disagreed with the same call made directly in Kotlin\n$report",
        )
        assertTrue(agreementPercent == 100.0, "agreement fell to %.1f%%\n%s".format(agreementPercent, report))
    }

    private enum class Verdict(val label: String) {
        AGREED("agreed"),
        AGREED_BOTH_REFUSED("both refused"),
        INCOMPARABLE("incomparable"),
        NOT_A_FUNCTION_OF_ITS_INPUTS("input-independent"),
        RESOLVED_NOT_RUN("resolved, not run"),
        DISAGREED("disagreed"),
        REFUSED("refused"),
    }

    private val RAN = setOf(Verdict.AGREED, Verdict.AGREED_BOTH_REFUSED, Verdict.INCOMPARABLE, Verdict.DISAGREED)

    private data class Outcome(
        val signature: String,
        val verdict: Verdict,
        val detail: String?,
        val reachableBeforeThisBridge: Boolean,
    )

    private fun attempt(owner: Class<*>, method: Method, surface: Surface?, static: Boolean): Outcome {
        val signature = "${owner.simpleName}.${method.name}(${method.parameterTypes.joinToString { it.simpleName }})"
        val before = reachableBeforeThisBridge(method, static)
        fun outcome(verdict: Verdict, detail: String? = null) = Outcome(signature, verdict, detail, before)

        val arguments = method.genericParameterTypes.map { synthesise(it) }
        val missing = method.parameterTypes.filterIndexed { index, _ -> arguments[index] == null }
        if (missing.isNotEmpty()) {
            return outcome(Verdict.REFUSED, "no FieldML value for ${missing.first().simpleName}")
        }
        val spelled = arguments.map { it!! }
        val source = if (static) {
            """
            let Owner = hostClass "${owner.name}"
            ${callExpression("Owner", method.name, spelled.map { it.fieldML }, static = true)}
            """.trimIndent()
        } else {
            """
            let target = ${surface!!.fieldML}
            ${callExpression("target", method.name, spelled.map { it.fieldML }, static = false)}
            """.trimIndent()
        }

        val compiled = FieldMLCompiler.compile(source)
        if (!compiled.successful) {
            return outcome(
                Verdict.REFUSED,
                compiled.diagnostics.firstOrNull { it.message.isNotBlank() }?.message,
            )
        }
        if (doNotInvoke.any { method.name.lowercase().contains(it) }) return outcome(Verdict.RESOLVED_NOT_RUN)

        // Two independent calls from one argument list. The direct one is the oracle: it shares no
        // code with the bridge, so an agreement is evidence about the bridge rather than about itself.
        val throughFieldML = runCatching {
            fieldMLValueToHost(Evaluator(FieldMLRuntimePrelude.values(box = box)).evaluate(compiled.core!!))
        }
        val directly = directCall(method, static, surface, spelled)

        // Twice, with a fresh receiver and freshly built arguments: a method whose answer depends on
        // something other than its inputs — `FLine.getUid`, which reads a global counter — cannot be
        // compared against anything, and calling that a disagreement would be wrong.
        val again = directCall(method, static, surface, method.genericParameterTypes.map { synthesise(it)!! })
        if (directly.isSuccess && again.isSuccess && sameValue(directly.getOrNull(), again.getOrNull()) == false) {
            return outcome(
                Verdict.NOT_A_FUNCTION_OF_ITS_INPUTS,
                "two direct calls returned ${describe(directly.getOrNull())} and ${describe(again.getOrNull())}",
            )
        }

        val fieldMLFailure = throughFieldML.exceptionOrNull()
        val directFailure = directly.exceptionOrNull()
        return when {
            fieldMLFailure != null && directFailure != null ->
                if (fieldMLFailure.javaClass == directFailure.javaClass) {
                    outcome(Verdict.AGREED_BOTH_REFUSED, directFailure.javaClass.simpleName)
                } else {
                    outcome(
                        Verdict.DISAGREED,
                        "FieldML threw ${fieldMLFailure.javaClass.simpleName} (${fieldMLFailure.message}), " +
                            "Kotlin threw ${directFailure.javaClass.simpleName} (${directFailure.message})",
                    )
                }
            fieldMLFailure != null ->
                outcome(Verdict.DISAGREED, "FieldML threw ${fieldMLFailure.javaClass.simpleName}: ${fieldMLFailure.message}")
            directFailure != null ->
                outcome(Verdict.DISAGREED, "the direct Kotlin call threw ${directFailure.javaClass.simpleName} but FieldML did not")
            else -> when (sameValue(throughFieldML.getOrNull(), directly.getOrNull())) {
                true -> outcome(Verdict.AGREED)
                false -> outcome(
                    Verdict.DISAGREED,
                    "FieldML got ${describe(throughFieldML.getOrNull())}, Kotlin got ${describe(directly.getOrNull())}",
                )
                null -> outcome(Verdict.INCOMPARABLE, describe(directly.getOrNull()))
            }
        }
    }

    private fun directCall(method: Method, static: Boolean, surface: Surface?, arguments: List<Argument>) =
        runCatching {
            val target = if (static) null else surface!!.kotlin()
            try {
                method.invoke(target, *arguments.map { it.kotlin }.toTypedArray())
            } catch (invocation: InvocationTargetException) {
                throw invocation.targetException ?: invocation
            }
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

    // ---- value comparison ----------------------------------------------------------------------

    /**
     * True when the two results are the same value, false when they differ, **null** when the class
     * offers no way to tell — which is a distinct answer, not a pass. `hostToFieldMLValue` widens on
     * the way out (an `int` comes back as a `Long`, a `float` as a `Double`), so numbers are compared
     * numerically; that widening is also why this is the place a precision bug would show up.
     */
    private fun sameValue(fromFieldML: Any?, fromKotlin: Any?): Boolean? {
        if (fromKotlin == null || fromKotlin == Unit) return fromFieldML == null || fromFieldML == Unit
        if (fromFieldML == null || fromFieldML == Unit) return false
        return when (fromKotlin) {
            is Number -> (fromFieldML as? Number)?.let { sameNumber(it, fromKotlin) } ?: false
            is Boolean, is String, is Char, is Enum<*> -> fromFieldML == fromKotlin
            is Img -> (fromFieldML as? Img)?.let { sameImg(it, fromKotlin) } ?: false
            is FLine -> (fromFieldML as? FLine)?.let { sameFLine(it, fromKotlin) } ?: false
            is Vector2f -> (fromFieldML as? Vector2f)?.let { sameFloats(floats(it), floats(fromKotlin)) } ?: false
            is Vector3f -> (fromFieldML as? Vector3f)?.let { sameFloats(floats(it), floats(fromKotlin)) } ?: false
            is Vector4f -> (fromFieldML as? Vector4f)?.let { sameFloats(floats(it), floats(fromKotlin)) } ?: false
            is List<*> -> sameList(fromFieldML as? List<*> ?: return false, fromKotlin)
            is Map<*, *> -> sameMap(fromFieldML, fromKotlin)
            else -> when {
                fromKotlin.javaClass.isArray -> sameArray(fromFieldML, fromKotlin)
                fromFieldML.javaClass != fromKotlin.javaClass -> false
                hasValueEquality(fromKotlin.javaClass) -> fromFieldML == fromKotlin
                else -> null
            }
        }
    }

    /** Exact, not approximate: both sides come from the same JVM computation, so any drift is a bug. */
    private fun sameNumber(fromFieldML: Number, fromKotlin: Number): Boolean {
        val a = fromFieldML.toDouble()
        val b = fromKotlin.toDouble()
        if (a.isNaN() && b.isNaN()) return true
        return a == b
    }

    private fun sameImg(a: Img, b: Img): Boolean =
        a.width == b.width && a.height == b.height && a.channels == b.channels && a.data.contentEquals(b.data)

    private fun sameFLine(a: FLine, b: FLine): Boolean {
        if (a.nodes.size != b.nodes.size) return false
        return a.nodes.indices.all { index ->
            val left = a.nodes[index]
            val right = b.nodes[index]
            // `toOrNull`, not `to`: a Close node has no control points and `to` throws for it.
            val leftTo = left.toOrNull
            val rightTo = right.toOrNull
            left.javaClass == right.javaClass &&
                if (leftTo == null || rightTo == null) leftTo == rightTo else sameFloats(floats(leftTo), floats(rightTo))
        }
    }

    private fun sameFloats(a: FloatArray, b: FloatArray): Boolean = a.contentEquals(b)

    private fun floats(vector: Any): FloatArray =
        when (vector) {
            is Vector2f -> floatArrayOf(vector.x, vector.y)
            is Vector3f -> floatArrayOf(vector.x, vector.y, vector.z)
            is Vector4f -> floatArrayOf(vector.x, vector.y, vector.z, vector.w)
            else -> floatArrayOf()
        }

    private fun sameList(a: List<*>, b: List<*>): Boolean? {
        if (a.size != b.size) return false
        var incomparable = false
        a.indices.forEach { index ->
            when (sameValue(a[index], b[index])) {
                false -> return false
                null -> incomparable = true
                true -> Unit
            }
        }
        return if (incomparable) null else true
    }

    private fun sameMap(fromFieldML: Any, fromKotlin: Map<*, *>): Boolean? {
        // A JVM map comes back through the bridge as a FieldML record, so the shape changes on the way.
        val asMap = fromFieldML as? Map<*, *> ?: return false
        if (asMap.size != fromKotlin.size) return false
        return fromKotlin.entries.fold<Map.Entry<*, *>, Boolean?>(true) { accumulated, (key, value) ->
            when (sameValue(asMap[key.toString()] ?: asMap[key], value)) {
                false -> return false
                null -> null
                else -> accumulated
            }
        }
    }

    private fun sameArray(fromFieldML: Any, fromKotlin: Any): Boolean? {
        if (!fromFieldML.javaClass.isArray) return false
        val length = ReflectArray.getLength(fromKotlin)
        if (ReflectArray.getLength(fromFieldML) != length) return false
        return sameList(
            (0 until length).map { ReflectArray.get(fromFieldML, it) },
            (0 until length).map { ReflectArray.get(fromKotlin, it) },
        )
    }

    private fun hasValueEquality(type: Class<*>): Boolean =
        runCatching { type.getMethod("equals", Any::class.java).declaringClass != Any::class.java }.getOrDefault(false)

    private fun describe(value: Any?): String =
        when {
            value == null -> "null"
            value is Img -> "Img(${value.width}x${value.height}x${value.channels})"
            value is FLine -> "FLine(${value.nodes.size} nodes)"
            value.javaClass.isArray -> "${value.javaClass.simpleName}(${ReflectArray.getLength(value)})"
            else -> "${value.javaClass.simpleName}($value)".take(80)
        }

    // ---- argument synthesis --------------------------------------------------------------------

    /**
     * One argument, spelled twice: as FieldML source and as the JVM value the direct call gets. They
     * have to be built together — the whole comparison rests on both sides genuinely being the same
     * argument, so a table that spelled them in two places would be the first thing to drift.
     */
    private data class Argument(val fieldML: String, val kotlin: Any?)

    private fun synthesise(jvm: JvmType, depth: Int = 0): Argument? {
        if (depth > 2) return null
        val raw = rawOf(jvm) ?: return null
        FieldMLHostBridge.functionalMethod(raw)?.let { return lambdaFor(raw, it, jvm, depth) }
        return when {
            raw == Int::class.javaPrimitiveType || raw == Int::class.javaObjectType -> Argument("1", 1)
            raw == Long::class.javaPrimitiveType || raw == Long::class.javaObjectType -> Argument("1", 1L)
            raw == Short::class.javaPrimitiveType || raw == Short::class.javaObjectType -> Argument("1", 1.toShort())
            raw == Byte::class.javaPrimitiveType || raw == Byte::class.javaObjectType -> Argument("1", 1.toByte())
            raw == Float::class.javaPrimitiveType || raw == Float::class.javaObjectType -> Argument("1.0", 1.0f)
            raw == Double::class.javaPrimitiveType || raw == Double::class.javaObjectType -> Argument("1.0", 1.0)
            // A `Number` parameter takes whatever FieldML hands it, and FieldML hands it a Double.
            raw == Number::class.java -> Argument("1.0", 1.0)
            raw == Boolean::class.javaPrimitiveType || raw == Boolean::class.javaObjectType -> Argument("true", true)
            raw == String::class.java || raw == CharSequence::class.java -> Argument("\"a\"", "a")
            raw == Char::class.javaPrimitiveType || raw == Char::class.javaObjectType -> Argument("\"a\"", 'a')
            raw == Any::class.java -> Argument("1.0", 1.0)
            raw == Class::class.java -> Argument("""hostClass "java.lang.String"""", String::class.java)
            raw.isEnum -> enumConstant(raw)
            // `isAssignableFrom` rather than equality: Field's linalg types have read-only interfaces
            // (`Vector3fc` and friends) that much of the surface is declared against, and a `Vector3f`
            // satisfies them — refusing those would understate what FieldML can call.
            raw.isAssignableFrom(Vector2f::class.java) -> Argument("vec2 1.0 2.0", Vector2f(1.0f, 2.0f))
            raw.isAssignableFrom(Vector3f::class.java) -> Argument("vec3 1.0 2.0 3.0", Vector3f(1.0f, 2.0f, 3.0f))
            raw.isAssignableFrom(Vector4f::class.java) ->
                Argument("vec4 1.0 2.0 3.0 4.0", Vector4f(1.0f, 2.0f, 3.0f, 4.0f))
            raw.isAssignableFrom(FLine::class.java) -> Argument(
                "(fline |> moveTo 0.0 0.0 |> lineTo 10.0 5.0)",
                FLine().moveTo(0.0, 0.0).lineTo(10.0, 5.0),
            )
            raw.isAssignableFrom(Img::class.java) -> Argument("(${imgFieldML()})", imgKotlin())
            raw.isAssignableFrom(Box::class.java) -> Argument("thisBox", box)
            List::class.java.isAssignableFrom(raw) -> listArgument(jvm, depth)
            raw.isArray -> Argument("[]", ReflectArray.newInstance(raw.componentType, 0))
            hasNoArgConstructor(raw) -> Argument(
                """(hostNew0 (hostClass "${raw.name}"))""",
                raw.getDeclaredConstructor().also { it.trySetAccessible() }.newInstance(),
            )
            else -> null
        }
    }

    /**
     * A FieldML lambda of the right arity, and — for the direct call — a proxy over the same interface
     * that returns the same constant. Comparing the two checks the part of SAM conversion that the
     * *result* travels through: `Function2.invoke` erases to `Object`, so a bridge that returned a
     * `Double` where the parameter said `Float` would fail here and pass a reachability-only test.
     * Arguments travelling *in* to a lambda are checked by hand in `FieldMLHostAgreementTest`, where a
     * body that depends on them can be written for a known interface.
     */
    private fun lambdaFor(iface: Class<*>, method: Method, jvm: JvmType, depth: Int): Argument? {
        val arity = method.parameterCount
        if (arity > 4) return null
        val resultType = samResultType(iface, jvm) ?: method.returnType
        val result = when {
            resultType == Void.TYPE -> Argument("Unit", null)
            resultType == Unit::class.java -> Argument("Unit", Unit)
            else -> synthesise(resultType, depth + 1) ?: return null
        }
        val head = if (arity == 0) "fun ignored ->" else "fun ${(1..arity).joinToString(" ") { "a$it" }} ->"
        val proxy = Proxy.newProxyInstance(
            iface.classLoader ?: javaClass.classLoader,
            arrayOf(iface),
            InvocationHandler { self, called, _ ->
                when {
                    called.name == "hashCode" && called.parameterCount == 0 -> System.identityHashCode(self)
                    called.name == "toString" && called.parameterCount == 0 -> "<test constant>"
                    else -> result.kotlin
                }
            },
        )
        return Argument("($head ${result.fieldML})", proxy)
    }

    /** The interface's result type as written at the use site, since `Function2.invoke` erases it. */
    private fun samResultType(iface: Class<*>, jvm: JvmType): Class<*>? {
        val parameterized = jvm as? ParameterizedType ?: return null
        if (parameterized.rawType != iface) return null
        return rawOf(parameterized.actualTypeArguments.lastOrNull())
    }

    /**
     * An `ArrayList` on the Kotlin side, not `listOf`: a FieldML list crosses as the `ArrayList` that
     * `Iterable.map` returns, and `Method.invoke` refuses a `kotlin.collections.EmptyList` for an
     * `ArrayList` parameter. That made `FLine.setNodes` look like a disagreement when it was the
     * oracle, not the bridge, passing the wrong thing.
     */
    private fun listArgument(jvm: JvmType, depth: Int): Argument {
        val element = (jvm as? ParameterizedType)?.actualTypeArguments?.firstOrNull()
        val value = element?.let { synthesise(it, depth + 1) } ?: return Argument("[]", ArrayList<Any?>())
        return Argument("[${value.fieldML}]", arrayListOf(value.kotlin))
    }

    private fun enumConstant(raw: Class<*>): Argument? {
        // `name`, not `toString()`: an enum that overrides its rendering would otherwise be spelled
        // with something that is not the static field's name.
        val constant = raw.enumConstants?.firstOrNull() as? Enum<*> ?: return null
        return Argument("""(hostGet "${constant.name}" (hostClass "${raw.name}"))""", constant)
    }

    private fun hasNoArgConstructor(raw: Class<*>): Boolean =
        !raw.isInterface &&
            !Modifier.isAbstract(raw.modifiers) &&
            raw.constructors.any { it.parameterCount == 0 }

    /**
     * What FieldML could reach before this bridge: an instance call of arity two or less, every
     * parameter a number, boolean, string, or one of the four host types the prelude could build.
     * Constructors with arguments, lambdas, arrays, `null`, static members and arity three and up were
     * all simply unreachable.
     */
    private fun reachableBeforeThisBridge(method: Method, static: Boolean): Boolean {
        if (static) return false
        if (method.parameterCount > 2) return false
        return method.parameterTypes.all { parameter ->
            val boxed = FieldMLHostBridge.boxed(parameter)
            Number::class.java.isAssignableFrom(boxed) ||
                boxed == Boolean::class.javaObjectType ||
                boxed == String::class.java ||
                boxed == Vector2f::class.java ||
                boxed == Vector3f::class.java ||
                boxed == Vector4f::class.java ||
                boxed == FLine::class.java
        }
    }

    /**
     * The public surface, minus the machinery a caller never writes: `Object`'s methods, Kotlin's
     * `…$default` and `…$lambda` bridges, synthetic members, and internal-name mangling.
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

    private companion object {
        /**
         * The samples both sides of an `Img` argument are built from. `Float.toString` is the shortest
         * decimal that round-trips, so parsing it back as a FieldML float literal and narrowing to
         * `Float` gives the identical bits — which is what makes the two `Img`s comparable at all.
         */
        private val imgSamples = FloatArray(16) { it / 15.0f }

        private fun imgFieldML(): String =
            """hostNew (4, 4, 1, [${imgSamples.joinToString(", ") { it.toString() }}]) (hostClass "afield.stdlib.Img")"""

        private fun imgKotlin(): Img = Img(4, 4, 1, imgSamples.copyOf())
    }
}
