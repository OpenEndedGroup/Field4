package afield.ml.tests

import afield.Box
import afield.ml.FieldMLCompiler
import afield.ml.FieldMLRuntimePrelude
import afield.ml.core.Evaluator
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Where the dynamic type stops.
 *
 * `Host` is what a host value's type degrades to when reflection cannot name its class, and it unifies
 * with other types so that an erased result can be used without a cast. That is a real weakening of
 * what FieldML's checker promises, so it needs a stated boundary and a test that holds the line on both
 * sides of it — programs that *should* now be rejected, and programs that must still compile.
 *
 * The rule, in one sentence: **`Host` stands in for a value of unknown class, not of unknown shape.**
 * An `Object`-erased JVM value may turn out to be a number, a string, a boolean, nothing, or another
 * object, so `Host` unifies with all of those. It is never a FieldML function and never a FieldML
 * structure, because `hostToFieldMLValue` cannot produce one — so those are type errors that name the
 * conversion to use, instead of programs that type-check and fail on their first run.
 *
 * Two known classes that are unrelated do not unify either; two where one is assignable from the other
 * do, because Field's linalg surface is declared half against read-only interfaces and refusing
 * `Vector2f` where a signature says `Vector2fc` would report a sound call as an error.
 */
class FieldMLHostDynamicBoundaryTest {
    /** Each of these was accepted before the boundary existed, and failed at run time instead. */
    private val mustBeRejected = listOf(
        "applying an untyped host value" to """(hostInput |> hostGet "whatever") 42""",
        "an untyped host value as a list" to """hostInput |> hostGet "whatever" |> head""",
        "mapping over an untyped host value" to """hostInput |> hostGet "whatever" |> map (fun x -> x)""",
        "an untyped host value as a list pattern" to """
            match hostInput |> hostGet "whatever" with
            | [] -> 0
            | x :: rest -> 1
        """,
        "an untyped host value as a tuple" to """
            let first pair = match pair with | (a, b) -> a
            first (hostInput |> hostGet "whatever")
        """,
        "an untyped host value as a record" to """
            let label thing = thing.name
            label (hostInput |> hostGet "whatever")
        """,
        "two unrelated known classes" to """
            let img = hostNew (2, 1, 1, [0.0, 1.0]) (hostClass "afield.stdlib.Img")
            if true then img else fline
        """,
        "a known class where another is required" to """
            let img = hostNew (2, 1, 1, [0.0, 1.0]) (hostClass "afield.stdlib.Img")
            fline |> hostCall1 "moveTo" img
        """,
    )

    /** The ergonomics the escape exists for. Every one of these has to keep compiling. */
    private val mustStillCompile = listOf(
        "an untyped host value as a host receiver" to """hostInput |> hostGet "whatever" |> hostCall0 "toString"""",
        "an untyped host value in arithmetic" to """(hostInput |> hostGet "whatever") + 1""",
        "an untyped host value as a float" to """(hostInput |> hostGet "whatever") * 2.0""",
        "an untyped host value in a comparison" to """(hostInput |> hostGet "whatever") == 1""",
        "an untyped host value as a string" to """"x" ++ (hostInput |> hostGet "whatever" |> hostAsString)""",
        "an untyped host value beside a typed one" to """if true then (hostInput |> hostGet "whatever") else fline""",
        "the explicit way to a list" to """hostInput |> hostGet "whatever" |> hostAsList |> length""",
        "the explicit way to a number" to """(hostInput |> hostGet "whatever" |> hostAsInt) + 1""",
        "a nullable Kotlin return, which is opaque" to """
            hostStaticCall "lines" (thisBox, [fline], "main", 10.0, 10.0, false, false, false, false)
              (hostClass "afield.graphics.SomeSkikoKt")
        """,
        // Assignability: `Vector2f` reaching a parameter declared as its read-only interface.
        "a subclass where a supertype is required" to """
            let Vectors = hostClass "afield.stdlib.linalg.Vector2f"
            vec2 1.0 2.0 |> hostCall1 "add" (vec2 3.0 4.0)
        """,
        "an untyped host value into a JVM call" to """
            fline |> hostCall1 "rot" (hostInput |> hostGet "whatever")
        """,
    )

    @Test
    fun theDynamicEscapeIsBoundedAndSaysWhatToDoInstead() {
        val wronglyAccepted = mustBeRejected.filter { (_, source) ->
            FieldMLCompiler.compile(source.trimIndent()).successful
        }
        val unhelpful = mustBeRejected.mapNotNull { (name, source) ->
            val diagnostics = FieldMLCompiler.compile(source.trimIndent()).diagnostics.map { it.message }
            // A refusal that does not say what to do is only half a diagnostic.
            val helpful = diagnostics.any { message ->
                "hostAs" in message || "hostGet" in message || "hostCall" in message ||
                    "unrelated" in message || "has no method" in message || "has no member" in message
            }
            if (helpful) null else "$name: ${diagnostics.joinToString()}"
        }

        val report = buildString {
            appendLine("the Host boundary, over ${mustBeRejected.size} programs that should be refused:")
            mustBeRejected.forEach { (name, source) ->
                val diagnostic = FieldMLCompiler.compile(source.trimIndent())
                    .diagnostics.firstOrNull { it.message.isNotBlank() }?.message
                appendLine("  ${if (diagnostic == null) "ACCEPTED" else "refused "}  $name")
                if (diagnostic != null) appendLine("            $diagnostic")
            }
        }
        println(report)

        assertTrue(wronglyAccepted.isEmpty(), "still accepted: ${wronglyAccepted.map { it.first }}\n$report")
        assertTrue(unhelpful.isEmpty(), "refused without saying what to do instead: $unhelpful")
    }

    @Test
    fun theErgonomicsTheEscapeExistsForAreUntouched() {
        val broken = mustStillCompile.mapNotNull { (name, source) ->
            val result = FieldMLCompiler.compile(source.trimIndent())
            if (result.successful) null else "$name: ${result.diagnostics.joinToString { it.message }}"
        }
        assertTrue(broken.isEmpty(), "the boundary broke ${broken.size} program(s) it should not have:\n${broken.joinToString("\n")}")
    }

    @Test
    fun anUntypedHostValueStillReachesTheRuntimeItAlwaysDid() {
        // The boundary is a *type* rule; nothing about the runtime changed, so a program on the
        // permissive side still evaluates.
        val source = """
            let Img = hostClass "afield.stdlib.Img"
            let img = hostNew (2, 1, 1, [0.25, 0.75]) Img

            (img |> hostCall "at" (0, 0, 0)) + (img |> hostCall "at" (1, 0, 0))
        """.trimIndent()
        val result = FieldMLCompiler.compile(source)
        assertTrue(result.successful, result.diagnostics.joinToString { it.message })
        val value = Evaluator(FieldMLRuntimePrelude.values(box = Box("fieldml-host-boundary"))).evaluate(result.core!!)
        assertTrue(afield.ml.core.renderFieldMLValue(value) == "1.0", afield.ml.core.renderFieldMLValue(value))
    }
}
