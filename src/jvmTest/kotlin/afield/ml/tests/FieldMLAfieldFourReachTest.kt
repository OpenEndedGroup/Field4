package afield.ml.tests

import afield.Box
import afield.Frames
import afield.four.buffers.BufferParameters
import afield.four.buffers.defaultBuffer
import afield.four.util.Options
import afield.ml.CompilationResult
import afield.ml.FieldMLCompiler
import afield.ml.FieldMLRuntimePrelude
import afield.ml.core.Evaluator
import afield.ml.core.fieldMLValueToHost
import afield.ml.type.TypePrinter
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Can FieldML drive `afield.four` — the API the human's real workspaces are made of?
 *
 * The motivating case is `~/Documents/AFieldWorkspace/threebox-2`, the minimal complete statement of
 * that API: three boxes that open a window, run a compute shader over 100 000 particles, and draw
 * them. This test takes it statement by statement.
 *
 * It is split the way the honesty of the answer splits.
 *
 * **What runs.** A Vulkan device cannot be created in a headless test JVM, and pretending otherwise
 * would be worse than admitting it — so the calls verified by execution here are the `afield.four`
 * calls that genuinely do not need one: `defaultBuffer { size = N.vec4s }`, whose lambda is a Kotlin
 * *receiver* lambda and whose `vec4s` is a *member extension property* of the receiver; the `Options`
 * singleton; and the box-property primitives that `prop` and `once` are sugar over. Each is compared
 * against the same call written in Kotlin.
 *
 * **What is only type-checked.** Everything downstream of a device — `Window`, `Renderer`, `.buffer`,
 * `Mesh`, `Module` — is compiled to prove FieldML can *express* the call, and deliberately not run.
 *
 * **What cannot be expressed at all.** Nine of the names in those three boxes are not JVM members:
 * they are source macros registered in `afield.macros.MacroRegistry.callRewrite` (`macros.kt:338`),
 * expanded by rewriting Kotlin source *before* it is compiled. `prop<Window>(window)` generates
 * `var Box.window by Inheritance<Window>()` into the box's preamble (`props.kt:71`) and the function
 * called `prop` throws `MacroNotExpandedException` if it is ever really invoked (`props.kt:39`).
 * A reflective bridge cannot reach a declaration that does not exist yet, and this test says so with
 * a citation rather than with a shrug. [statementSurvey] is the roadmap.
 */
class FieldMLAfieldFourReachTest {
    // ---- what actually runs ---------------------------------------------------------------------

    @Test
    fun aKotlinReceiverLambdaAndAMemberExtensionPropertyBothWork() {
        // defaultBuffer(from, name) { size = N.vec4s } — three arguments where the last is a
        // `BufferParameters.() -> Unit`, and `vec4s` is an extension property *of that receiver*, so it
        // only exists inside the lambda. On the JVM it is `BufferParameters.getVec4s(int)`, which is why
        // the bridge can reach it at all.
        val throughFieldML = evaluateAs<BufferParameters>(
            """
            let Magic = hostClass "afield.four.buffers.MagicKt"
            let N = 100000

            hostStaticCall "defaultBuffer" (hostNull, "inData", fun p ->
              p |> hostSet "size" (p |> hostCall1 "getVec4s" N)
            ) Magic
            """,
        )
        val inKotlin = defaultBuffer(name = "inData") { size = 100000.vec4s }

        // `defaultBuffer` *defers* its lambda into `customize` rather than running it, so both sides
        // start at size 0 and the lambda has to be driven to prove anything. Doing it symmetrically is
        // what makes the comparison about the bridge instead of about laziness.
        listOf(inKotlin, throughFieldML).forEach { parameters ->
            parameters.customize.forEach { customise -> customise(parameters) }
        }

        assertEquals(inKotlin.size, throughFieldML.size)
        assertEquals(1_600_000L, throughFieldML.size, "the FieldML lambda should have computed 100000.vec4s")
        assertEquals(inKotlin.name, throughFieldML.name)
        // hosted()/storage()/addressable() are what `defaultBuffer` does before handing over, so an
        // equal `size` alone would not prove the right function ran.
        assertEquals(inKotlin.usage, throughFieldML.usage)
    }

    @Test
    fun theOptionsSingletonIsReadableAndWritable() {
        // `Options.profiling = false` in a box is a property write on a Kotlin `object`, which reaches
        // reflection as the static `INSTANCE` field plus ordinary accessors.
        val before = Options.profiling
        try {
            val readBack = evaluate(
                """
                let Options = hostClass "afield.four.util.Options" |> hostGet "INSTANCE"

                Options
                  |> hostSet "profiling" true
                  |> hostGet "profiling"
                """,
            )
            assertEquals(true, readBack)
            assertEquals(true, Options.profiling)
            assertEquals("Bool", typeOf("""hostClass "afield.four.util.Options" |> hostGet "INSTANCE" |> hostGet "profiling""""))
        } finally {
            Options.profiling = before
        }
    }

    @Test
    fun theBoxPropertySlotThatPropIsSugarOverIsFullyReachable() {
        // `prop<Window>(window)` cannot be called, but what it *is* — a named slot on a box, read from
        // the box or its nearest ancestor — is `getPropertyRaw`/`setPropertyRaw`, and those are ordinary
        // methods. The inheritance walk has to be written out, which FieldML can do with recursion.
        val parent = Box("fieldml-four-parent")
        val child = Box("fieldml-four-child")
        child.parents.add(parent)

        val stored = evaluate(
            """
            let write name value box = box |> hostCall "setPropertyRaw" (name, value, false)
            let read name box = box |> hostCall "getPropertyRaw" (name, hostNull)

            let parent = thisBox |> hostGet "parents" |> hostAsList |> head

            let _ = parent |> write "speed" 3.5

            parent |> read "speed"
            """,
            box = child,
        )
        assertEquals(3.5, stored)
        assertEquals(3.5, parent.getPropertyRaw("speed"))

        // And the inheritance half: reading a name the child does not have, from its parent.
        val inherited = evaluate(
            """
            let read name box = box |> hostCall "getPropertyRaw" (name, hostNull)

            // A `let f x = ...` binding is already recursive in FieldML; there is no `rec` keyword.
            let inherited name box =
              let here = box |> read name in
              if here != Unit then here
              else match box |> hostGet "parents" |> hostAsList with
                   | [] -> Unit
                   | p :: _ -> inherited name p

            thisBox |> inherited "speed"
            """,
            box = child,
        )
        assertEquals(3.5, inherited)
    }

    @Test
    fun theOnceSlotIsExpressibleAsGetOrComputeAndStore() {
        // `once.renderer { Renderer(window) }` is `Onceing.getValue` (scratch.kt:26): read the box
        // property, and if it is null run the computation and store the result. Written out in FieldML
        // it is the same three lines, and it runs.
        val box = Box("fieldml-four-once")
        val source = """
            let read name b = b |> hostCall "getPropertyRaw" (name, hostNull)
            let write name value b = b |> hostCall "setPropertyRaw" (name, value, false)

            let once name compute b =
              let existing = b |> read name in
              if existing != Unit then existing
              else let made = compute Unit in
                   let _ = b |> write name made in
                   made

            thisBox |> once "counted" (fun ignored -> 17)
        """

        assertEquals(17L, evaluate(source, box = box))
        assertEquals(17L, box.getPropertyRaw("counted"))
        // Second run must not recompute: the stored value wins even though the computation says 99.
        assertEquals(17L, evaluate(source.replace("17", "99"), box = box))
    }

    @Test
    fun theFramesBuildersThatDriveALiveSceneRunHeadlessly() {
        // The moving half of `afield.four`, and the happy accident of it: `Frames` is a plain object
        // with no Vulkan in it, so the per-frame builders every animated Field box is made of can be
        // run *here*, headlessly, against the same JVM the real workspace uses. This is the one part
        // of a live scene this test does not have to take on trust.
        //
        // Mechanism D is what makes this expressible at all — `eachFrame(block: suspend Frames.() ->
        // Unit)` — and the assertion is that the FieldML body ran, not that a proxy exists.
        val ticks = AtomicInteger()
        val box = Box("fieldml-four-frames")
        box.setPropertyRaw("ticks", ticks, false)

        val frames = evaluateAs<Frames>(
            """
            let counter = thisBox |> hostCall "getPropertyRaw" ("ticks", hostNull)

            hostStaticCall "forever" (fun f ->
              (counter |> hostCall0 "incrementAndGet" |> hostAsInt) < 4
            ) (hostClass "afield.PhasedKt")
            """,
            box = box,
        )
        assertEquals(0, ticks.get(), "building a Frames must not run its body")

        runBlocking { frames.body() }

        assertEquals(4, ticks.get())
        assertEquals(3, frames.frameNumber)
    }

    @Test
    fun theOverloadThatThreeboxActuallyWritesRunsButDoesNotTypeItsCallback() {
        // `start(renderer::run)` is the threebox statement, and it is worth being exact about which
        // part of it is now reachable and which is not.
        //
        // The suspend type is no longer the obstacle. What is left is an **overload tie** with
        // nothing to do with coroutines: `start` is declared twice, over `suspend Frames.() -> Unit`
        // and over `suspend () -> Unit` (stdlib.kt:516, :527). To reflection both are `start` taking
        // one functional-interface parameter and a FieldML closure scores the same against both.
        //
        // Two consequences, and they pull in opposite directions, which is why both are pinned here.
        //
        // The *result* is still known: both overloads return `Frames`, and `resolve` reports a type
        // when every candidate agrees on one, ambiguous or not. But the *parameter slot* is not:
        // `parametersOf` returns no claim when the tied candidates have different signatures — the
        // right answer, and the reason a misspelling inside this particular callback is a run-time
        // failure rather than a compile error. That is the ordinary untyped-callback hole, not a new
        // one.
        val phased = """(hostClass "afield.PhasedKt")"""
        val stdlib = """(hostClass "afield.stdlib.StdlibKt")"""

        assertEquals("Frames", typeOf("""hostStaticCall "start" (fun f -> f) $stdlib"""))
        assertTrue(
            FieldMLCompiler.compile("""hostStaticCall "start" (fun f -> f |> hostGet "frameNumbr") $stdlib""").successful,
            "the tied overload set must not claim a parameter type it cannot know",
        )
        // And where there is only one declaration, the same misspelling is caught where it is written.
        assertTrue(
            !FieldMLCompiler.compile("""hostStaticCall "eachFrame" (fun f -> f |> hostGet "frameNumbr") $phased""").successful,
            "eachFrame is declared once, so its callback parameter is Frames and a typo is a type error",
        )
        assertEquals("Frames", typeOf("""hostStaticCall "eachFrame" (fun f -> 0) $phased"""))
        assertEquals("Frames", typeOf("""hostStaticCall "loop" (fun f -> 0) $phased"""))
        assertEquals("Frames", typeOf("""hostStaticCall "forever" (fun f -> true) $phased"""))

        // The other direction: the tie does not stop it *running*, because the adapter is
        // arity-tolerant in both directions. Whichever `start` reflection happens to pick, the proxy
        // drops the continuation and then either curries the `Frames` in or, with nothing left, feeds
        // the `Unit` a zero-argument functional interface gets — and `fun ignored -> …` accepts both.
        val ran = AtomicInteger()
        val box = Box("fieldml-four-start")
        box.setPropertyRaw("ran", ran, false)
        val frames = evaluateAs<Frames>(
            """
            let counter = thisBox |> hostCall "getPropertyRaw" ("ran", hostNull)

            hostStaticCall "start" (fun ignored -> counter |> hostCall0 "incrementAndGet") $stdlib
            """,
            box = box,
        )
        runBlocking { frames.body() }
        assertEquals(1, ran.get(), "start's body should have run exactly once, whichever overload won")
    }

    @Test
    fun theAnimationClockThatDrivesTheLiveSceneIsCallable() {
        assertEquals("Float", typeOf("""hostClass "afield.AnimationClock" |> hostGet "INSTANCE" |> hostCall0 "nowSeconds""""))
        assertTrue(assertIs<Double>(evaluate("""hostClass "afield.AnimationClock" |> hostGet "INSTANCE" |> hostCall0 "nowSeconds"""")) > 0.0)
    }

    // ---- the survey ------------------------------------------------------------------------------

    /**
     * Every statement of the three `threebox-2` boxes, with what FieldML can do about it. A translation
     * is compiled — not run, because most of these need a device — so `expressible` means "the bridge
     * and the type checker accept this shape", which is exactly the claim being made.
     */
    private data class Statement(val kotlin: String, val fieldML: String?, val note: String)

    private val statementSurvey = listOf(
        Statement(
            "import afield.four.util.Options",
            null,
            "not needed: FieldML has no imports, every class is named in full by hostClass",
        ),
        Statement(
            "prop<Window>(window)",
            null,
            "SOURCE MACRO (macros.kt:355 -> props.kt:66): generates `var Box.window by Inheritance<Window>()` " +
                "into the box preamble. `Box.prop` itself throws MacroNotExpandedException (props.kt:39). " +
                "Its *effect* is reachable — see theBoxPropertySlotThatPropIsSugarOverIsFullyReachable",
        ),
        Statement(
            "Options.enableHDR = false",
            """hostClass "afield.four.util.Options" |> hostGet "INSTANCE" |> hostSet "enableHDR" false""",
            "a Kotlin object is a static INSTANCE plus ordinary accessors",
        ),
        Statement(
            "once.renderer { Renderer(window) }",
            null,
            "MACRO-GENERATED DELEGATE: `Once` (scratch.kt:15) plus a `val Once.renderer by Onceing<Renderer>()` " +
                "that prop's expansion wrote. Reachable as get-or-compute over getPropertyRaw/setPropertyRaw",
        ),
        Statement(
            "window = Window(\"Field three-box\")",
            """hostNew ("Field three-box", false, hostNull) (hostClass "afield.four.util.Window")""",
            "expressible; NOT RUN — GLFW and a Vulkan surface are not available headless",
        ),
        Statement(
            "Renderer(window)",
            """hostNew {mainWindow = hostNull} (hostClass "afield.four.Renderer")""",
            "KOTLIN DEFAULT ARGUMENT, and as of 2026-08-19 no longer one that has to be written out: " +
                "a *record* of arguments is a named call, and a named call is the only shape that can " +
                "leave `additionalWindows = emptyList()` (Renderer.kt:147) unsaid. The positional form " +
                "`hostNew (hostNull, []) …` still works. Expressible; NOT RUN — needs a real Window",
        ),
        Statement(
            "start(renderer::run)",
            """hostStaticCall "eachFrame" (fun f -> f |> hostGet "frameNumber") (hostClass "afield.PhasedKt")""",
            "SUSPEND FUNCTION TYPE, and as of 2026-08-19 no longer a blocker: `suspend Frames.() -> " +
                "Unit` is a Function2 whose last parameter is a Continuation, and the bridge now drops " +
                "it (FieldMLHostBridge.suspendShape). RUNS — see theFramesBuildersThatDriveALiveSceneRunHeadlessly. " +
                "Two residues, both named rather than waved at: `start` itself is declared twice " +
                "(stdlib.kt:516, :527) and the overloads tie, so the shape written here is `eachFrame`, " +
                "which is declared once; and `renderer::run` is a suspend *member*, which FieldML still " +
                "cannot call into — see theOverloadThatThreeboxActuallyWritesStaysAmbiguous",
        ),
        Statement(
            "computeModuleHere {}",
            null,
            "SOURCE MACRO (macros.kt:344): expands to `_computeModuleHere(...)` *and* writes shader source " +
                "into box properties at expansion time. The call half is reachable; the document mutation is not",
        ),
        Statement(
            "var m = ComputeMesh()",
            """hostNew {renderer = hostNull} (hostClass "afield.four.modules.ComputeMesh")""",
            "KOTLIN DEFAULT ARGUMENT again. `renderer = fromContext()` is now omittable by name, but it " +
                "is written here anyway on purpose: `fromContext()` reads Field's *box context*, which a " +
                "reflective call is not inside, so letting the default fire would be the wrong renderer " +
                "rather than none. Named arguments make the choice available; they do not make it right. " +
                "Expressible; NOT RUN",
        ),
        Statement(
            "module += \"step\" to m",
            """hostCall1 "plusAssign" (hostNew ("step", hostNull) (hostClass "kotlin.Pair")) hostInput""",
            "an operator is an ordinary method, and a Pair is an ordinary constructor",
        ),
        Statement(
            "defaultBuffer { size = N.vec4s }",
            """hostStaticCall "defaultBuffer" (hostNull, "inData", fun p -> p |> hostSet "size" 1600000) """ +
                """(hostClass "afield.four.buffers.MagicKt")""",
            "RUNS — see aKotlinReceiverLambdaAndAMemberExtensionPropertyBothWork",
        ),
        Statement(
            ".buffer",
            """hostInput |> hostGet "buffer"""",
            "expressible; NOT RUN — allocates on the device",
        ),
        Statement(
            "m.inData = { inData }",
            """hostInput |> hostSet "inData" (fun ignored -> 0)""",
            "a lambda-valued property: hostSet coerces the FieldML function to the field's Function0 type",
        ),
        Statement(
            "fieldProbeHere(samples = 100000).display(\"probe\")",
            null,
            "SOURCE MACRO. The named argument half of this stopped being a blocker on 2026-08-19 — " +
                "`hostCall \"display\" {name = \"probe\"}` is now an ordinary call — but `fieldProbeHere` " +
                "is a macro (mechanism A) and there is still no JVM member to name parameters of",
        ),
        Statement(
            "m.x = N / 64",
            """hostInput |> hostSet "x" 1562""",
            "an ordinary property write",
        ),
        Statement(
            "inData.write { for (i in 0 until N) put(vec(...)) }",
            """hostInput |> hostCall1 "write" (fun buffer -> range 0 4 |> fold (fun b i -> b) buffer)""",
            "the receiver lambda is expressible and a fold replaces the loop; NOT RUN — needs a mapped buffer",
        ),
        Statement(
            "moduleHere { primitiveType = triangles }",
            null,
            "SOURCE MACRO (macros.kt:340), and `triangles` is another name the expansion declares",
        ),
        Statement(
            "m.aspectRatio = { window.width/window.height.toFloat() }",
            """hostInput |> hostSet "aspectRatio" (fun ignored -> 1.0)""",
            "a lambda-valued property again; the *body* needs `window`, which is a macro-declared name",
        ),
        Statement(
            "m.set(N*2*3, 0) {}",
            """hostInput |> hostCall "set" (600000, 0, fun ignored -> Unit)""",
            "three arguments with a trailing lambda — exactly what this bridge added",
        ),
    )

    @Test
    fun surveysWhichThreeboxStatementsFieldMLCanExpress() {
        val expressible = statementSurvey.filter { it.fieldML != null }
        val refused = mutableListOf<Statement>()

        expressible.forEach { statement ->
            // `hostInput` stands in for a receiver that only exists on a device; it is typed opaque
            // `Host`, so these compile as shapes without claiming anything about the receiver's class.
            val result = FieldMLCompiler.compile(statement.fieldML!!)
            if (!result.successful) refused += statement
        }

        val report = buildString {
            appendLine("threebox-2 (the minimal complete afield.four scene), statement by statement:")
            appendLine("  statements:               ${statementSurvey.size}")
            appendLine("  expressible in FieldML:   ${expressible.size}")
            appendLine("  not expressible:          ${statementSurvey.size - expressible.size}")
            appendLine()
            appendLine("  not expressible, and why:")
            statementSurvey.filter { it.fieldML == null }.forEach {
                appendLine("    ${it.kotlin}")
                appendLine("        ${it.note}")
            }
            appendLine()
            appendLine("  expressible:")
            expressible.forEach { appendLine("    ${it.kotlin}  —  ${it.note}") }
            if (refused.isNotEmpty()) {
                appendLine()
                appendLine("  CLAIMED EXPRESSIBLE BUT DID NOT COMPILE:")
                refused.forEach { appendLine("    ${it.kotlin}  —  ${it.fieldML}") }
            }
        }
        println(report)

        assertTrue(refused.isEmpty(), "translations that do not compile\n$report")
        // 13 of 19 as of 2026-08-19, when mechanism D closed and `start(renderer::run)` moved across.
        // The floor is the measurement minus nothing: a statement that stops being expressible is a
        // regression, and there is no reason to leave slack in a number that only ever goes up.
        assertTrue(
            expressible.size >= 13,
            "only ${expressible.size} of ${statementSurvey.size} statements are expressible\n$report",
        )
        // The rest are the roadmap, and each carries a file-and-line citation.
        statementSurvey.filter { it.fieldML == null }.forEach {
            assertTrue(it.note.isNotBlank(), "${it.kotlin} has no reason recorded")
        }
    }

    // ---- harness ---------------------------------------------------------------------------------

    private fun typeOf(source: String): String = TypePrinter().print(compileOk(source).typed!!.type)

    private inline fun <reified T> evaluateAs(source: String, box: Box = defaultBox): T =
        assertIs<T>(evaluate(source, box))

    private fun evaluate(source: String, box: Box = defaultBox): Any? {
        val result = compileOk(source)
        return fieldMLValueToHost(Evaluator(FieldMLRuntimePrelude.values(box = box)).evaluate(result.core!!))
    }

    private val defaultBox = Box("fieldml-afield-four")

    private fun compileOk(source: String): CompilationResult {
        val result = FieldMLCompiler.compile(source.trimIndent())
        assertTrue(result.successful, result.diagnostics.joinToString("\n") { "${it.severity}: ${it.message}" })
        return result
    }
}
