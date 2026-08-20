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
 * **The source macros, which used to be what could not be expressed at all.** Several names in those
 * three boxes are not JVM members: they are macros registered in
 * `afield.macros.MacroRegistry.callRewrite` (`macros.kt:340`), expanded by rewriting Kotlin source
 * *before* it is compiled. As of 2026-08-20 they are reachable — see `FieldMLSourceMacroTest` and
 * `afield.ml.FieldMLMacroLayer` — two different ways, and the survey records which:
 * `moduleHere`/`computeModuleHere` through `hostMacro`, which expands the macro and then calls the
 * `Box` extension it rewrote to; `prop`/`once` through native prelude forms over the same slot the
 * generated delegate would have read. [statementSurvey] is the roadmap, and it no longer has a
 * mechanism in it.
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

        // And since 2026-08-20 there is a prelude name for it, which must agree with the four lines
        // above. Keeping the longhand *and* comparing is the point: the longhand is what proves the
        // primitives are reachable, and the comparison is what proves `prop` did not quietly become
        // something else — a local-only read would pass on its own and fail here.
        assertEquals(inherited, evaluate("""prop "speed"""", box = child))
        assertEquals(3.5, evaluate("""propOr "speed" 0.0""", box = child))
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
     * One statement of the three `threebox-2` boxes, with what FieldML can do about it — and, when it
     * can do nothing, which *mechanism* is in the way.
     *
     * A translation is compiled, not run, because most of these need a device, so `expressible` means
     * "the bridge and the type checker accept this shape", which is exactly the claim being made.
     *
     * [residue] exists because "13 of 19" is a much weaker statement than the truth. Counting
     * statements measures the sample; naming the mechanism measures the bridge. With B, C and D
     * closed on 2026-08-19 the six remaining refusals are not a long tail, and
     * [theResidueIsOneMechanism] asserts that rather than leaving a reader to notice it.
     */
    private data class Statement(
        val kotlin: String,
        val fieldML: String?,
        val note: String,
        val residue: Residue? = null,
        /** Where the statement's *effect* is reached without its spelling, when it is. */
        val effect: String? = null,
        /**
         * The FieldML mechanism that reaches this statement's **source macro**, for the statements
         * that are one. Recorded positively, so [everySourceMacroStatementSaysHowItIsReached] is a
         * ratchet on what is present rather than on what is absent — which is the shape a claim about
         * a *closed* mechanism has to take.
         */
        val viaMacroLayer: String? = null,
    )

    private enum class Residue {
        /** Mechanism A: a rewrite over Kotlin *source*, so there is no JVM member to reflect on. */
        SOURCE_MACRO,

        /** Not a limitation at all — the statement has no work to do in FieldML. */
        NOT_NEEDED,
    }

    private val statementSurvey = listOf(
        Statement(
            "import afield.four.util.Options",
            null,
            "not needed: FieldML has no imports, every class is named in full by hostClass",
            residue = Residue.NOT_NEEDED,
        ),
        Statement(
            "prop<Window>(window)",
            """prop "window"""",
            "SOURCE MACRO, reached natively since 2026-08-20. The Kotlin statement *declares* — it " +
                "generates `var Box.window by Inheritance<Window>()` into the preamble (props.kt:71) — and " +
                "FieldML has no declarations, so there is nothing to translate it to. There does not need " +
                "to be: the slot is made by writing it, and reading it is the same breadth-first walk " +
                "(afield.stdlib.inheritedPropertyRaw), which both `Inheritance.getValue` and FieldML's " +
                "`prop` now call. `setProp` is the write, `propOr` the read that does not fail",
            effect = "FieldMLSourceMacroTest.propAgreesWithTheGeneratedKotlinDelegateOnTheSameGraph",
            viaMacroLayer = "prop / setProp / propOr",
        ),
        Statement(
            "Options.enableHDR = false",
            """hostClass "afield.four.util.Options" |> hostGet "INSTANCE" |> hostSet "enableHDR" false""",
            "a Kotlin object is a static INSTANCE plus ordinary accessors",
        ),
        Statement(
            "once.renderer { Renderer(window) }",
            """once "renderer" (fun ignored -> hostNew {mainWindow = hostNull} (hostClass "afield.four.Renderer"))""",
            "MACRO-GENERATED DELEGATE — `Once` (scratch.kt:15) plus the `val Once.renderer by " +
                "Onceing<Renderer>()` that prop's expansion wrote — and a prelude name since 2026-08-20. " +
                "Get, or compute and store; `null` is the empty sentinel rather than a cached value. " +
                "Expressible; NOT RUN, because the computation needs a real Window",
            effect = "theOnceSlotIsExpressibleAsGetOrComputeAndStore",
            viaMacroLayer = "once",
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
            """hostMacro "computeModuleHere" "{}" (fun p -> Unit)""",
            "SOURCE MACRO (macros.kt:346), reached since 2026-08-20. Both halves, not one: `hostMacro` " +
                "runs the expansion — which is what writes the shader source into the box's properties and " +
                "compiles the stages — and *then* calls `_computeModuleHere` (shaders.kt:4577), the `Box` " +
                "extension the rewrite names. Expressible; NOT RUN, because the expansion compiles a " +
                "compute stage and the call allocates on the device",
            viaMacroLayer = "hostMacro",
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
            """thisBox |> hostExt (hostClass "afield.stdlib.ShadersKt") "fieldProbeHere" {samples = 100000} """ +
                """|> hostCall "display" {channel = "probe"}""",
            "NOT A MACRO AT ALL, and this row said it was until 2026-08-20 — a stale claim worth " +
                "recording as one. `Box.fieldProbeHere` is an ordinary function (shaders.kt:945); the " +
                "macro with a similar name is `fieldStatsHere` (macros.kt:349). So this is a named " +
                "extension call, which mechanisms B and C made ordinary: `samples` is named and the other " +
                "four parameters keep their Kotlin defaults, and `display(channel = \"probe\")` (FieldProbe" +
                ".kt:587) is the same. Expressible; NOT RUN — the probe allocates an SSBO",
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
            """hostMacro "moduleHere" "{ primitiveType = triangles }" (fun p -> Unit)""",
            "SOURCE MACRO (macros.kt:340), reached since 2026-08-20 — expansion (which seeds this box's " +
                "vertex/fragment properties and compiles both stages, shaders.kt:4039) and then " +
                "`_moduleHere` (shaders.kt:4242). What `hostMacro` does *not* bring across is the " +
                "preamble the expansion also returns: the typed `val Mesh.pos by FromInput()` accessors, " +
                "and `triangles`. FieldML does not need them — every host value is `Host`, which is " +
                "dynamic, so the same members are reached through hostGet/hostSet. Expressible; NOT RUN",
            effect = "public/docs/assets/fieldml_mesh_demo — parameters(renderer) { … } plus Module(params), on a real device",
            viaMacroLayer = "hostMacro",
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
        // 18 of 19 as of 2026-08-20, when mechanism A closed. It was 13 on 2026-08-19. The floor is
        // the measurement minus nothing: a statement that stops being expressible is a regression, and
        // there is no reason to leave slack in a number that only ever goes up.
        assertTrue(
            expressible.size >= 18,
            "only ${expressible.size} of ${statementSurvey.size} statements are expressible\n$report",
        )
        // The rest are the roadmap, and each carries a file-and-line citation.
        statementSurvey.filter { it.fieldML == null }.forEach {
            assertTrue(it.note.isNotBlank(), "${it.kotlin} has no reason recorded")
        }
    }

    /**
     * The closing statement this survey exists to be able to make, and as of 2026-08-20 it is a
     * stronger one than it has ever been: **there is no mechanism left**.
     *
     * "18 of 19" measures the sample. What measures the *bridge* is which mechanisms are in the way,
     * and after mechanism A closed the answer is none. The one statement with no FieldML translation
     * is the `import`, and that is not a limitation — FieldML names every class in full and has
     * nothing for an import to do.
     *
     * This is deliberately kept as an **exact set** rather than a count, and the direction it guards
     * has flipped. It used to catch a new refusal for a *bridge* reason hiding behind a steady count.
     * Now it catches something else: a statement sliding back into `SOURCE_MACRO`, which is what a
     * regression in `FieldMLMacroLayer` or in the `prop` family would look like from here. The
     * positive half of the claim — that five statements are reached *through* the macro layer and say
     * which part of it — is [everySourceMacroStatementSaysHowItIsReached], because an absence is a
     * weak thing to assert about a mechanism you have just closed.
     */
    @Test
    fun theResidueIsNoMechanismAtAll() {
        val refused = statementSurvey.filter { it.fieldML == null }
        val byResidue = refused.groupBy { it.residue }

        val report = buildString {
            appendLine("what is left, by mechanism:")
            byResidue.forEach { (residue, statements) ->
                appendLine("  ${residue ?: "UNCLASSIFIED"}: ${statements.size}")
                statements.forEach { appendLine("    ${it.kotlin}${it.effect?.let { e -> "   [effect reached: $e]" } ?: ""}") }
            }
        }
        println(report)

        assertTrue(refused.none { it.residue == null }, "every refusal must name its mechanism\n$report")
        assertEquals(
            setOf(Residue.NOT_NEEDED),
            byResidue.keys,
            "every remaining refusal must be a non-limitation; a SOURCE_MACRO here means mechanism A " +
                "has regressed\n$report",
        )
        // 1 as of 2026-08-20, and it is the `import`. Asserted exactly: this is the claim.
        assertEquals(1, byResidue[Residue.NOT_NEEDED]?.size, "expected one non-limitation\n$report")
        assertEquals(0, byResidue[Residue.SOURCE_MACRO]?.size ?: 0, "no statement should be blocked on a macro\n$report")
    }

    /**
     * The positive half of the same claim: five of `threebox-2`'s statements are source macros, and
     * each one records **which part** of the macro layer reaches it.
     *
     * Worth separating from [theResidueIsNoMechanismAtAll] because they fail differently. That test
     * fails if a statement becomes unreachable; this one fails if a statement stops being *labelled*,
     * which is what happens when someone rewrites a translation to avoid the macro layer and the
     * survey quietly stops being about mechanism A at all. The two mechanisms are named because they
     * are genuinely different answers — `hostMacro` expands and calls, `prop`/`once` do not expand
     * anything — and a survey that blurred them would be claiming more than is true.
     */
    @Test
    fun everySourceMacroStatementSaysHowItIsReached() {
        val reached = statementSurvey.filter { it.viaMacroLayer != null }

        val report = buildString {
            appendLine("threebox-2 statements reached through the macro layer:")
            reached.forEach { appendLine("  ${it.kotlin}\n      via ${it.viaMacroLayer}") }
        }
        println(report)

        // 5 as of 2026-08-20: prop, once, computeModuleHere, moduleHere, and — no. Four macros plus
        // `prop` counted once; `fieldProbeHere` is deliberately *not* here, because it turned out not
        // to be a macro at all.
        assertTrue(reached.size >= 4, "only ${reached.size} statements record a macro-layer route\n$report")
        reached.forEach { statement ->
            assertTrue(statement.fieldML != null, "${statement.kotlin} claims a route but has no translation")
            assertTrue(
                FieldMLCompiler.compile(statement.fieldML!!).successful,
                "${statement.kotlin}'s translation does not compile: ${statement.fieldML}",
            )
        }
        // Both mechanisms must still be represented. One of them disappearing would mean the survey
        // had drifted onto whichever was easier, and stopped testing the other.
        assertEquals(
            setOf("hostMacro", "once", "prop / setProp / propOr"),
            reached.mapNotNull { it.viaMacroLayer }.toSet(),
            "the survey must keep exercising both halves of the macro layer — expansion-and-call, and " +
                "the native delegate forms\n$report",
        )
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
