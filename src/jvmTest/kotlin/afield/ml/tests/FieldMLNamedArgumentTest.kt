package afield.ml.tests

import afield.Box
import afield.four.Renderer
import afield.four.buffers.BufferParameters
import afield.four.buffers.defaultBuffer
import afield.ml.CompilationResult
import afield.ml.FieldMLCompiler
import afield.ml.FieldMLRuntimePrelude
import afield.ml.core.Evaluator
import afield.ml.core.FieldMLValue
import afield.ml.core.fieldMLValueToHost
import afield.ml.core.renderFieldMLValue
import afield.ml.type.FieldMLHostTypes
import afield.ml.type.TypePrinter
import afield.stdlib.Img
import afield.stdlib.colormap
import java.lang.reflect.Modifier
import kotlin.reflect.KParameter
import kotlin.reflect.jvm.kotlinFunction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Mechanisms **B** and **C** of `dev_docs/FIELDML_HOST_BRIDGE.md` — Kotlin default arguments and
 * named arguments — which turn out to be one mechanism.
 *
 * The table listed them as two rows and answered both with "yes, by spelling everything out". They
 * are really one row, because the reason a default is unreachable is the reason a name is: plain
 * reflection has neither. `Class.getConstructors` shows `Renderer(Window, List)` and says nothing
 * about `additionalWindows = emptyList()`, or even that the parameter is called that.
 * `kotlin-reflect` has both, out of the same metadata, and `KCallable.callBy` is the one entry point
 * that acts on them. So naming the arguments is not a convenience *beside* defaults — it is what
 * makes defaults reachable at all, because a positional call has no way to say which parameter it is
 * skipping.
 *
 * The spelling is a **record** where a tuple would go: `hostNew {mainWindow = w} Renderer`. That
 * continues the dispatch `spreadArguments` already does on the shape of the argument, so there is no
 * new prelude name, no new cheatsheet row, and the same rule at every call shape.
 *
 * Two things are deliberately not claimed, and both are pinned below rather than left to be
 * discovered: a lambda inside a named record does not get its parameters typed, and a record handed
 * to `hostCall1` is still one argument.
 */
class FieldMLNamedArgumentTest {
    // ---- a default that is actually omitted ---------------------------------------------------

    @Test
    fun aKotlinDefaultCanBeLeftOutAndIsFilledFromTheSource() {
        // `Img.index(x, y, c = 0)`. The point is that the omitted `c` is *filled with 0*, not that
        // the call is tolerated: index(1, 0, 0) is 3 and index(1, 0, 2) is 5 on a 3-channel image, so
        // a wrong fill would be visible rather than plausible.
        assertEquals("3", renderOf("""img |> hostCall "index" {x = 1, y = 0}"""))
        assertEquals("5", renderOf("""img |> hostCall "index" {x = 1, y = 0, c = 2}"""))
        assertEquals(3, img.index(1, 0), "the Kotlin call this is compared against")

        // And that the *positional* form still cannot do it, which is the whole point of the row.
        assertRefused(
            """
            let Img = hostClass "afield.stdlib.Img"
            let img = hostNew (2, 2, 3, [0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0]) Img

            img |> hostCall "index" (1, 0)
            """,
            "has no method 'index'",
        )
    }

    @Test
    fun twoDefaultsAndAnExtensionReceiverAtOnce() {
        // `Img.colormap(name = "viridis", lo = null, hi = null)` — an extension with three
        // parameters, all optional. Positionally all three must be written, and `hostNull` twice is
        // the tell that something is missing. Named, one is enough, and the two omitted really are
        // computed from the image (lo = min, hi = max) rather than left null.
        val fullySpelled = assertIs<Img>(
            hostValueOf("$grayPreamble\ngray |> hostExt Imaging \"colormap\" (\"magma\", hostNull, hostNull)"),
        )
        val named = assertIs<Img>(
            hostValueOf("$grayPreamble\ngray |> hostExt Imaging \"colormap\" {name = \"magma\"}"),
        )
        assertEquals(fullySpelled.data.toList(), named.data.toList())
        assertEquals(3, named.channels, "colormap should have produced an RGB image")

        // Every parameter optional also means *no* named argument at all is a legal call — the record
        // is empty and every default applies.
        val allDefaulted = assertIs<Img>(hostValueOf("$grayPreamble\ngray |> hostExt Imaging \"colormap\" {}"))
        assertEquals(
            gray.colormap().data.toList(),
            allDefaulted.data.toList(),
            "an empty record should be `colormap()` with every Kotlin default applied",
        )
    }

    @Test
    fun aRequiredParameterBesideTwoOptionalOnesResolvesToTheRightCall() {
        // `defaultBuffer(from = null, name = from?.name ?: "unnamed", parameters)` — the signature
        // the reach test already exercises positionally, where all three have to be written. Named,
        // only the callback is required, and this both runs and is compared against the Kotlin.
        val throughFieldML = assertIs<BufferParameters>(
            hostValueOf(
                """
                hostStaticCall "defaultBuffer" {parameters = fun p -> p |> hostSet "size" 4096}
                  (hostClass "afield.four.buffers.MagicKt")
                """,
            ),
        )
        val inKotlin = defaultBuffer { size = 4096 }

        listOf(inKotlin, throughFieldML).forEach { parameters ->
            parameters.customize.forEach { customise -> customise(parameters) }
        }
        assertEquals(4096L, throughFieldML.size)
        assertEquals(inKotlin.name, throughFieldML.name)
        assertEquals("unnamed", throughFieldML.name, "the `name` default is derived from `from`, which is also defaulted")
        assertEquals(inKotlin.usage, throughFieldML.usage)
    }

    @Test
    fun theRendererConstructorTheTableCitesNowTypeChecksWithOneArgument() {
        // Not run — a Renderer needs a Vulkan device — but the *resolution* is the claim, and it is
        // exactly what was impossible: one argument against a two-parameter JVM constructor.
        assertEquals("Renderer", typeOf("""hostNew {mainWindow = hostNull} (hostClass "afield.four.Renderer")"""))
        assertEquals("Renderer", typeOf("""hostNew (hostNull, []) (hostClass "afield.four.Renderer")"""))
        assertRefused("""hostNew (hostNull) (hostClass "afield.four.Renderer")""", "has no constructor taking")

        // So that a future signature change fails here rather than quietly making this vacuous.
        assertTrue(
            Renderer::class.constructors.any { c -> c.parameters.count { !it.isOptional } == 1 },
            "Renderer's constructor no longer has exactly one required parameter",
        )
    }

    // ---- names, at every call shape ------------------------------------------------------------

    @Test
    fun aNamedCallReachesTheSameMemberThePositionalOneDoes() {
        assertEquals(
            renderOf("""img |> hostCall "index" (1, 0, 2)"""),
            renderOf("""img |> hostCall "index" {x = 1, y = 0, c = 2}"""),
        )
        // Order is not position: the same names in a different order are the same call.
        assertEquals("5", renderOf("""img |> hostCall "index" {c = 2, y = 0, x = 1}"""))
        // A Kotlin `object`, whose members reach reflection as instance methods on INSTANCE. Field's
        // own surface, deliberately: a *Java* static carries no parameter names at all, so testing
        // one would be a test of whether the JDK was built with `-parameters`. The static case that
        // matters here is `MagicKt.defaultBuffer`, covered above.
        val colormaps = """(hostClass "afield.Colormaps" |> hostGet "INSTANCE")"""
        assertEquals(
            renderOf("""$colormaps |> hostCall "sample" ("viridis", 0.25)"""),
            renderOf("""$colormaps |> hostCall "sample" {name = "viridis", t = 0.25}"""),
        )
    }

    @Test
    fun aNamedCallGetsARealResultTypeRatherThanTheDynamicOne() {
        // The type-level half. Without it a named call would type-check as `Host` and everything
        // downstream would go blind again — which is the state this whole bridge exists to end.
        assertEquals("Int", typeOf("""img |> hostCall "index" {x = 1, y = 0}"""))
        assertEquals(
            "Img",
            typeOf("""img |> hostExt (hostClass "afield.stdlib.Imaging_fieldKt") "colormap" {name = "magma"}"""),
        )
    }

    // ---- the refusals ----------------------------------------------------------------------------

    @Test
    fun aMisspelledParameterNameIsRefusedWhereItIsWritten() {
        assertRefused(
            withPreamble("""img |> hostCall "index" {x = 1, y = 0, cc = 2}"""),
            "has no method 'index' with exactly the named parameters",
        )
        // The diagnostic has to be usable, which means naming what *was* given.
        assertRefused(
            """hostNew {widht = 2, height = 2, channels = 1, data = []} (hostClass "afield.stdlib.Img")""",
            "(channels, data, height, widht)",
        )
    }

    @Test
    fun omittingARequiredParameterIsRefusedRatherThanFilledWithNothing() {
        // `Img(width, height, channels, data)` has no defaults at all, so leaving one out is a
        // mistake and not a defaulted call. Being tolerant here would construct something silently.
        assertRefused(
            """hostNew {width = 2, height = 2} (hostClass "afield.stdlib.Img")""",
            "has no constructor with exactly the named parameters",
        )
    }

    // ---- the measurement ---------------------------------------------------------------------------

    /**
     * How much of a real surface this actually opens, rather than how well the four examples above
     * were chosen.
     *
     * Over the same seven types `FieldMLHostSurfaceCoverageTest` measures, this finds every member
     * with at least one Kotlin default — the members mechanism B was about — and asks the
     * *production* resolver whether a call naming only the **required** parameters reaches it. That
     * is the shortest call a FieldML author can now write, and before this it could not be written
     * at all: positionally, every optional parameter had to be spelled.
     *
     * The floor is all of them, not a fraction. A default the resolver cannot omit is a signature
     * where the promise of this feature is simply false for one member, and the failure mode is a
     * refusal at the point of use with nothing to say why.
     */
    @Test
    fun everyKotlinDefaultOnTheCoveredSurfaceCanBeOmittedByName() {
        val surface = listOf(
            afield.graphics.FLine::class.java,
            Img::class.java,
            afield.stdlib.linalg.Vector2f::class.java,
            afield.stdlib.linalg.Vector3f::class.java,
            afield.stdlib.linalg.Vector4f::class.java,
            Class.forName("afield.stdlib.ImagingKt"),
            Class.forName("afield.stdlib.Imaging_filtersKt"),
        )

        val withDefaults = mutableListOf<String>()
        val unreachable = mutableListOf<String>()
        surface.forEach { owner ->
            owner.methods.filter { it.declaringClass == owner }.forEach { method ->
                val function = runCatching { method.kotlinFunction }.getOrNull() ?: return@forEach
                val valueParameters = function.parameters.filter { it.kind == KParameter.Kind.VALUE }
                if (valueParameters.none { it.isOptional }) return@forEach
                val required = valueParameters.filter { !it.isOptional && !it.isVararg }.mapNotNull { it.name }.toSet()
                val signature = "${owner.simpleName}.${method.name}(" +
                    valueParameters.joinToString(", ") { "${it.name}${if (it.isOptional) "?" else ""}" } + ")"
                withDefaults += signature

                val resolution = if (Modifier.isStatic(method.modifiers)) {
                    FieldMLHostTypes.namedStaticMethodResultType(
                        FieldMLHostTypes.classHandleType(owner.name),
                        method.name,
                        required,
                        wantsExtensionReceiver =
                            function.parameters.any { it.kind == KParameter.Kind.EXTENSION_RECEIVER },
                    )
                } else {
                    FieldMLHostTypes.namedMethodResultType(FieldMLHostTypes.valueType(owner), method.name, required)
                }
                if (resolution is FieldMLHostTypes.Resolution.Refused) {
                    unreachable += "$signature — ${resolution.message}"
                }
            }
        }

        val report = buildString {
            appendLine("Kotlin defaults across the seven types FieldMLHostSurfaceCoverageTest covers:")
            appendLine("  members with at least one default:      ${withDefaults.size}")
            appendLine("  reachable naming only what is required: ${withDefaults.size - unreachable.size}")
            withDefaults.sorted().take(12).forEach { appendLine("    $it") }
            if (withDefaults.size > 12) appendLine("    … and ${withDefaults.size - 12} more")
            if (unreachable.isNotEmpty()) {
                appendLine("  UNREACHABLE:")
                unreachable.forEach { appendLine("    $it") }
            }
        }
        println(report)

        // 60 as of 2026-08-19, every one of which needed all its optional parameters written out
        // before. `FLine.tessellate(samplesPerCubic?, coplanarity?, meshAcceptor)` is the shape that
        // shows why this is not just brevity: the *required* parameter is last, so no positional call
        // can shorten it at all.
        assertTrue(
            withDefaults.size >= 60,
            "expected at least 60 defaulted members on this surface, found ${withDefaults.size}\n$report",
        )
        assertTrue(unreachable.isEmpty(), "${unreachable.size} defaulted member(s) still cannot be reached\n$report")
    }
    // ---- what is deliberately not claimed ---------------------------------------------------------

    @Test
    fun hostCall1StillPassesARecordAsOneArgument() {
        // The escape, and the reason adding the rule to `hostCall` is safe: a record handed to
        // `hostCall1` is one argument, exactly as before. This matters because a JVM `Map` comes back
        // through `hostToFieldMLValue` as a RecordValue, so "a record I got from the host" and "a
        // record of argument names" are the same FieldML shape and the caller has to choose.
        assertEquals("Host", typeOf("""hostInput |> hostCall1 "whatever" {a = 1, b = 2}"""))
    }

    @Test
    fun aLambdaInsideANamedRecordIsNotGivenItsParameterTypes() {
        // Positionally, `p` is a `BufferParameters` and `getVec5s` is a compile error —
        // FieldMLLambdaExpectedTypeTest pins that. Inside a record it is not, because
        // `Inferencer.preInferHostSpine` looks for a lambda *literal* among a spine's arguments and a
        // record is one argument with a lambda inside it. That is a real cost of the record spelling
        // and the reason to keep reaching for the positional form when a callback is involved.
        assertTrue(
            FieldMLCompiler.compile(
                """
                hostStaticCall "defaultBuffer" {parameters = fun p -> p |> hostCall1 "getVec5s" 100000}
                  (hostClass "afield.four.buffers.MagicKt")
                """.trimIndent(),
            ).successful,
            "expected the named form to leave the callback untyped, which is the documented hole",
        )
        assertRefused(
            """
            hostStaticCall "defaultBuffer" (hostNull, "inData", fun p -> p |> hostCall1 "getVec5s" 100000)
              (hostClass "afield.four.buffers.MagicKt")
            """,
            "has no method 'getVec5s'",
        )
    }

    // ---- harness ----------------------------------------------------------------------------------

    private val imgPreamble = """
        let Img = hostClass "afield.stdlib.Img"
        let img = hostNew (2, 2, 3, [0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0]) Img
    """.trimIndent()

    private val img = Img(2, 2, 3, FloatArray(12))

    /** `colormap` needs one channel, so it gets its own two lines rather than bending [imgPreamble]. */
    private val grayPreamble =
        """let Imaging = hostClass "afield.stdlib.Imaging_fieldKt"
let gray = hostNew (2, 2, 1, [0.0, 0.25, 0.5, 1.0]) (hostClass "afield.stdlib.Img")"""

    private val gray = Img(2, 2, 1, floatArrayOf(0.0f, 0.25f, 0.5f, 1.0f))


    private fun withPreamble(source: String): String =
        if ("let img" in source) source.trimIndent() else "$imgPreamble\n\n${source.trimIndent()}"

    private fun typeOf(source: String): String =
        TypePrinter().print(compileOk(withPreamble(source)).typed!!.type)

    private fun renderOf(source: String): String = renderFieldMLValue(evaluateSource(withPreamble(source)))

    private fun hostValueOf(source: String): Any? = fieldMLValueToHost(evaluateSource(withPreamble(source)))

    private fun evaluateSource(source: String): FieldMLValue =
        Evaluator(FieldMLRuntimePrelude.values(box = box)).evaluate(compileOk(source).core!!)

    private val box = Box("fieldml-named-arguments")

    private fun compileOk(source: String): CompilationResult {
        val result = FieldMLCompiler.compile(source.trimIndent())
        assertTrue(result.successful, result.diagnostics.joinToString("\n") { "${it.severity}: ${it.message}" })
        return result
    }

    private fun assertRefused(source: String, messageContains: String) {
        val result = FieldMLCompiler.compile(source.trimIndent())
        assertTrue(!result.successful, "expected '$source' to be refused, it compiled")
        assertTrue(
            result.diagnostics.any { messageContains in it.message },
            "expected a diagnostic containing '$messageContains', got ${result.diagnostics.joinToString { it.message }}",
        )
    }
}
