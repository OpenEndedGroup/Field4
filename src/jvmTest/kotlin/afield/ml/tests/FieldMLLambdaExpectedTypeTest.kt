package afield.ml.tests

import afield.Box
import afield.graphics.FLine
import afield.ml.CompilationResult
import afield.ml.FieldMLCompiler
import afield.ml.FieldMLRuntimePrelude
import afield.ml.core.Evaluator
import afield.ml.core.FieldMLHostBridge
import afield.ml.core.FieldMLValue
import afield.ml.core.renderFieldMLValue
import afield.ml.type.FieldMLHostTypes
import afield.ml.type.TExpr
import afield.ml.type.TExprBinary
import afield.ml.type.TExprCall
import afield.ml.type.TExprField
import afield.ml.type.TExprIf
import afield.ml.type.TExprLambda
import afield.ml.type.TExprLet
import afield.ml.type.TExprList
import afield.ml.type.TExprMatch
import afield.ml.type.TExprNegate
import afield.ml.type.TExprRecord
import afield.ml.type.TExprTuple
import afield.ml.type.TExprTypeDecl
import afield.ml.type.THost
import afield.ml.type.TypePrinter
import afield.stdlib.Img
import afield.stdlib.Vec3
import afield.stdlib.linalg.Vector2f
import afield.stdlib.linalg.Vector3f
import afield.stdlib.linalg.Vector4f
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * What a FieldML lambda's parameters are, when it is the *JVM* that will call it.
 *
 * Until this existed they were nothing: the JVM called back with a value the checker knew nothing
 * about, so every callback body opened with `hostAs "Vec3" raw` before it could touch anything. The
 * reason was structural rather than accidental — FieldML is curried, so
 * `hostCall1 "transform" f line` is `((hostCall1 "transform") f) line` and the inferencer's
 * left-nested walk reaches `f` before it has ever looked at `line`, which is the only thing that
 * says which JVM method is meant.
 *
 * `Inferencer.preInferHostSpine` reorders that, and this test is in two halves, which is the point:
 *
 * - the first half is what is now **claimed** — a parameter with a real type, checked members, and
 *   a misspelling caught where it is written rather than on the first run;
 * - the second is what is deliberately **not** claimed. Reflection is often unable to say what a
 *   callback will be handed — an erased generic, a nullable Kotlin parameter — and in those cases
 *   the parameter keeps the inference variable it has always had. Putting `Host` there instead
 *   would *narrow* the language, because `Host` refuses to unify with a function or a list. The
 *   worst failure mode available here is reporting a correct call as a type error, and the second
 *   half is the guard against introducing one.
 */
class FieldMLLambdaExpectedTypeTest {

    // ---- what is claimed -------------------------------------------------------------------

    @Test
    fun aLambdaParameterTakesTheTypeOfTheJvmParameterItSatisfies() {
        // FLine.transform((Vec3) -> Vec3) is the shape most of Field's geometry API takes, and the
        // one the dev notes named as the biggest remaining ergonomic hole. No `hostAs` anywhere.
        val result = compileOk(
            """
            fline
              |> moveTo 0.0 0.0
              |> lineTo 10.0 0.0
              |> hostCall1 "transform" (fun p -> vec3 (p.x * 2.0) (p.y + 5.0) p.z)
            """,
        )

        assertEquals("FLine", TypePrinter().print(result.typed!!.type))
        assertEquals(listOf("p: Vec3"), parameterSpellings(result))
    }

    @Test
    fun theTypedParameterIsUsedForRealAndAgreesWithTheSameCallInKotlin() {
        // The type is only worth having if the program it enables computes the right thing, so this
        // is the same warp written twice — once in FieldML with no cast, once in Kotlin.
        val value = run(
            """
            fline
              |> moveTo 0.0 0.0
              |> lineTo 10.0 0.0
              |> lineTo 20.0 10.0
              |> hostCall1 "transform" (fun p -> vec3 (p.x * 2.0) (p.y + 5.0) p.z)
            """,
        )

        val inFieldML = assertIs<FLine>(assertIs<FieldMLValue.HostValue>(value).value)
        val inKotlin = FLine().moveTo(0.0, 0.0).lineTo(10.0, 0.0).lineTo(20.0, 10.0)
            .transform { Vec3(it.x * 2.0f, it.y + 5.0f, it.z) }

        assertEquals(inKotlin.nodes.size, inFieldML.nodes.size)
        inKotlin.nodes.indices.forEach { index ->
            assertEquals(inKotlin.nodes[index].to.x, inFieldML.nodes[index].to.x, 1e-5f, "node $index x")
            assertEquals(inKotlin.nodes[index].to.y, inFieldML.nodes[index].to.y, 1e-5f, "node $index y")
        }
    }

    /**
     * Every shape of the `host…` family that can carry a lambda, and what each one's parameters are
     * inferred as. The spread forms matter as much as the simple ones: `hostCall "generate" (4, 4, f)`
     * puts the lambda inside a tuple literal, so the flattening has to see through it to know which
     * JVM parameter the lambda lands in.
     */
    @Test
    fun everyHostCallShapeThreadsItsLambdaParameters() {
        val cases = listOf(
            // hostCall1 — one argument, a Kotlin (Vec3) -> Vec3.
            Case(
                "hostCall1 / FLine.transform",
                """fline |> hostCall1 "transform" (fun p -> p)""",
                listOf("p: Vec3"),
            ),
            // hostCall1 — a four-argument callback, which needs Function4's type arguments read
            // off the use site rather than off the erased `invoke`.
            Case(
                "hostCall1 / Img.mapIndexed",
                """$IMG |> hostCall1 "mapIndexed" (fun x y c v -> v + toFloat (x + y + c))""",
                listOf("x: Int, y: Int, c: Int, v: Float"),
            ),
            // hostCall2 — two arguments, the second the lambda.
            Case(
                "hostCall2 / Img.zip",
                """let a = $IMG in a |> hostCall2 "zip" a (fun p q -> p + q)""",
                listOf("p: Float, q: Float"),
            ),
            // hostCall2 — a callback declared to return Unit, so the proxy discards its result.
            Case(
                "hostCall2 / FLine.uniform_linearize",
                """
                fline |> moveTo 0.0 0.0 |> cubicTo 1.0 1.0 2.0 2.0 3.0 0.0
                  |> hostCall2 "uniform_linearize" 8 (fun p t -> p.x + t)
                """,
                listOf("p: Vec3, t: Float"),
            ),
            // hostCall — the lambda arrives inside a tuple-literal spread, at index 2 of three.
            Case(
                "hostCall / Img.Companion.generate",
                """
                hostClass "afield.stdlib.Img"
                  |> hostGet "Companion"
                  |> hostCall "generate" (4, 4, fun x y -> toFloat (x + y) / 6.0)
                """,
                listOf("x: Int, y: Int"),
            ),
            // hostStaticCall — two lambdas in one call, at different JVM positions.
            Case(
                "hostStaticCall / IntStream.iterate",
                """
                hostStaticCall "iterate" (1, fun n -> n < 20, fun n -> n * 2)
                  (hostClass "java.util.stream.IntStream")
                """,
                listOf("n: Int", "n: Int"),
            ),
            // hostExt — a static whose *first* parameter is the piped receiver, so the lambdas are
            // at JVM positions one and two while FieldML writes them first.
            Case(
                "hostExt / IntStream.iterate",
                """
                1 |> hostExt (hostClass "java.util.stream.IntStream") "iterate"
                    (fun n -> n < 20, fun n -> n * 2)
                """,
                listOf("n: Int", "n: Int"),
            ),
        )

        cases.forEach { case ->
            val result = compileOk(case.source, case.label)
            assertEquals(case.parameters, parameterSpellings(result), case.label)
        }
    }

    /**
     * A Kotlin *receiver* lambda, which is the shape half of `afield.four`'s builder surface takes.
     * `BufferParameters.() -> Unit` compiles to `Function1<BufferParameters, Unit>`, so the receiver
     * is argument zero and reads exactly like any other parameter — which means `defaultBuffer { … }`
     * from `FieldMLAfieldFourReachTest` now has a typed `p` and its members are checked rather than
     * resolved on the first run.
     */
    @Test
    fun aKotlinReceiverLambdaHasItsReceiverTyped() {
        val result = compileOk(
            """
            hostStaticCall "defaultBuffer" (hostNull, "inData", fun p ->
              p |> hostSet "size" (p |> hostCall1 "getVec4s" 100000)
            ) (hostClass "afield.four.buffers.MagicKt")
            """,
        )
        assertEquals(listOf("p: BufferParameters"), parameterSpellings(result))
        // And because it is typed, a member that is not there is now a compile error.
        assertRefused(
            """
            hostStaticCall "defaultBuffer" (hostNull, "inData", fun p ->
              p |> hostCall1 "getVec5s" 100000
            ) (hostClass "afield.four.buffers.MagicKt")
            """,
            "has no method 'getVec5s'",
        )
    }

    /**
     * The `afield.four` case that matters most, because it is the one that does *not* need the source
     * macro layer: `parameters(renderer) { vertex = …; fragment = … }` (`Module.kt:197`) takes plain
     * GLSL strings, and its receiver lambda now arrives with `p : Parameters`. That is a whole shader
     * module described from FieldML with the members checked.
     */
    @Test
    fun theAfieldFourShaderParameterBuilderHasATypedReceiver() {
        val result = compileOk(
            """
            hostStaticCall "parameters" (hostInput, fun p ->
              p |> hostSet "vertex" "#version 450"
                |> hostSet "fragment" "#version 450"
            ) (hostClass "afield.four.modules.ModuleKt")
            """,
        )
        assertEquals(listOf("p: Parameters"), parameterSpellings(result))
        assertRefused(
            """
            hostStaticCall "parameters" (hostInput, fun p -> p |> hostGet "vertexx")
              (hostClass "afield.four.modules.ModuleKt")
            """,
            "Parameters has no member 'vertexx'",
        )
    }

    @Test
    fun aMisspelledMemberOnALambdaParameterIsACompileErrorRatherThanACrash() {
        // The whole payoff of a typed parameter: `.zz` is refused where it is written, and the
        // diagnostic says which components the value actually has.
        assertRefused(
            """fline |> hostCall1 "transform" (fun p -> vec3 p.zz p.y p.z)""",
            "Vec3 has no field 'zz'",
        )
        // And the same for a method reached through hostCall, not just a vector component.
        assertRefused(
            """fline |> hostCall1 "transform" (fun p -> hostCall0 "notAMethodOfVector" p)""",
            "has no method 'notAMethodOfVector'",
        )
    }

    /**
     * Knowing what the JVM wants is also worth something when the lambda cannot possibly be it. This
     * used to be a run-time failure inside a dynamic proxy, with nothing in the message about which
     * box caused it.
     */
    @Test
    fun aLambdaWithMoreParametersThanTheCallbackTakesIsRefusedByName() {
        assertRefused(
            """fline |> hostCall1 "transform" (fun a b -> vec3 0.0 0.0 0.0)""",
            "FLine.transform calls this function with 1 argument(s), but it takes 2 (a, b)",
        )
        assertRefused(
            """
            hostClass "afield.stdlib.Img" |> hostGet "Companion"
              |> hostCall "generate" (4, 4, fun x y z -> 0.0)
            """,
            "calls this function with 2 argument(s), but it takes 3 (x, y, z)",
        )
    }

    @Test
    fun fewerParametersThanTheCallbackTakesIsFineBecauseFieldMLIsCurried() {
        // `fun x -> fun y -> …` *is* a two-argument callback: `samProxy` feeds the arguments in one
        // at a time. And `fun ignored -> …` is the only way to write a zero-argument one at all.
        val curried = run(
            """
            hostClass "afield.stdlib.Img" |> hostGet "Companion"
              |> hostCall "generate" (2, 2, fun x -> fun y -> toFloat (x + y))
            """,
        )
        assertEquals(2f, assertIs<Img>(assertIs<FieldMLValue.HostValue>(curried).value).at(1, 1))
        assertEquals(
            "PersistentDouble",
            typeOf("""hostNew (fun ignored -> 3.0) (hostClass "afield.PersistentDouble")"""),
        )
    }

    @Test
    fun aTypedParameterFlowsOnIntoTheRestOfTheProgram() {
        // Not just members: the parameter's type propagates through the prelude's vector operations,
        // which used to need the cast before they would even resolve.
        val result = compileOk(
            """fline |> hostCall1 "transform" (fun p -> normalize p * vlength p)""",
        )
        assertEquals(listOf("p: Vec3"), parameterSpellings(result))
        assertEquals("FLine", TypePrinter().print(result.typed!!.type))
    }

    @Test
    fun hostAsStillWorksOnAParameterThatNowHasAType() {
        // Programs written before this compile unchanged: `hostAs "Vec3"` on a value already known
        // to be a Vec3 is a no-op rather than a conflict. This is the backward-compatibility case,
        // and the shipped host-bridge demo document is written this way.
        val result = compileOk(
            """
            fline |> moveTo 0.0 0.0 |> lineTo 10.0 0.0
              |> hostCall1 "transform" (fun raw ->
                   let p = hostAs "Vec3" raw in
                   vec3 (p.x * 2.0) p.y p.z
                 )
            """,
        )
        assertEquals("FLine", TypePrinter().print(result.typed!!.type))
        assertEquals(listOf("raw: Vec3"), parameterSpellings(result))
    }

    // ---- what is deliberately not claimed ---------------------------------------------------

    @Test
    fun anErasedCallbackParameterKeepsItsInferenceVariable() {
        // `Collections.max(Collection<? extends T>, Comparator<? super T>)` — reflection cannot say
        // what T is at the use site, so the parameters must stay open. Claiming `Host` here would
        // refuse the subtraction, because `Host` is not numeric-by-inference the way a variable is.
        val result = compileOk(
            """
            hostStaticCall "max" ([5, 1, 9, 3], fun a b -> b - a) (hostClass "java.util.Collections")
              |> hostAsInt
            """,
        )
        assertEquals("1", renderFieldMLValue(evaluate(result)))
        // Unconstrained-but-numeric, which is what `defaultedType` prints as Float.
        assertEquals(listOf("a: Float, b: Float"), parameterSpellings(result))
    }

    @Test
    fun aNullableCallbackParameterKeepsItsInferenceVariable() {
        // `FLine.byFiltering((Vec3?, Node) -> Boolean)`. The JVM really does hand that callback a
        // null for the first node's predecessor, and the runtime turns a null into `Unit` — so
        // naming `Vec3` there would be a type the very first run violates. The *second* parameter is
        // not nullable and is typed, so this is both rules in one signature.
        val result = compileOk(
            """
            fline |> moveTo 0.0 0.0 |> lineTo 10.0 0.0
              |> hostCall1 "byFiltering" (fun previous node -> true)
            """,
        )
        val spelling = parameterSpellings(result).single()
        assertTrue(spelling.startsWith("previous: '"), "expected an open type variable, found $spelling")
        assertTrue(spelling.endsWith("node: Node"), "expected the non-null parameter to be typed, found $spelling")
    }

    @Test
    fun theLambdasResultIsNotConstrained() {
        // `Img.Companion.generate` wants a Function2<…, Float>, and `samProxy` coerces whatever the
        // FieldML body returns. So an `Int` body is a working program and must not become a type
        // error just because the expected result could have been read.
        val value = run(
            """
            hostClass "afield.stdlib.Img"
              |> hostGet "Companion"
              |> hostCall "generate" (2, 2, fun x y -> x + y)
            """,
        )
        val img = assertIs<Img>(assertIs<FieldMLValue.HostValue>(value).value)
        assertEquals(0f, img.at(0, 0))
        assertEquals(2f, img.at(1, 1))
    }

    @Test
    fun aLambdaInsideALambdaIsTypedFromTheInsideOut() {
        // Two host spines nested one inside the other. The inner one is inferred while the outer
        // one's lambda body is being checked, which is the case a memo keyed on anything but node
        // identity would get wrong.
        val result = compileOk(
            """
            fline |> moveTo 0.0 0.0 |> lineTo 10.0 0.0
              |> hostCall1 "transform" (fun p ->
                   let scaled = $IMG |> hostCall1 "map" (fun v -> v * p.x) in
                   vec3 (p.x + toFloat scaled.width) p.y p.z
                 )
            """,
        )
        assertEquals(listOf("p: Vec3", "v: Float"), parameterSpellings(result))
        assertEquals("FLine", TypePrinter().print(result.typed!!.type))
    }

    /**
     * The biggest residual, written down because it is the one a reader will hit first.
     *
     * `let warp line = line |> hostCall1 "transform" (fun p -> …)` gets nothing: `line` is a function
     * parameter, FieldML has no type annotations, and at the point the callback is checked there is
     * no class to reflect on. The expected type flows from a *known receiver*, so it reaches a call
     * written in a pipeline but not one tucked inside a helper. `hostAs` is still the answer there,
     * which is one of several reasons it is not going away.
     */
    @Test
    fun aReceiverThatIsAFunctionParameterHasNoClassToReadTheCallbackFrom() {
        // With no type for `p`, `p.x` falls back to the record rule and pins `p` to a one-field
        // record, so `p.y` is then genuinely missing. The diagnostic is honest — it just is not the
        // one a reader wants, and `hostAs "Vec3" p` is what turns it into the other case.
        assertRefused(
            """
            let warp line = line |> hostCall1 "transform" (fun p -> vec3 p.x p.y p.z) in
            warp (fline |> moveTo 0.0 0.0 |> lineTo 1.0 1.0)
            """,
            "Record is missing field 'y'",
        )
        // Written against a receiver whose class *is* known, the very same body is typed.
        assertEquals(
            listOf("p: Vec3"),
            parameterSpellings(
                compileOk(
                    """
                    fline |> moveTo 0.0 0.0 |> lineTo 1.0 1.0
                      |> hostCall1 "transform" (fun p -> vec3 p.x p.y p.z)
                    """,
                ),
            ),
        )
    }

    @Test
    fun aCallWhoseReceiverIsUnknownStillCompilesAndClaimsNothing() {
        // `hostInput` has no class, so there is no method to reflect on and no parameter to read.
        // The reordering has to be a no-op here rather than a refusal.
        val result = compileOk("""hostInput |> hostCall1 "whatever" (fun v -> v)""")
        assertEquals("Host", TypePrinter().print(result.typed!!.type))
        assertTrue(parameterSpellings(result).single().startsWith("v: '"))
    }

    @Test
    fun aSpreadThatIsNotALiteralIsNotGuessedAt() {
        // The flattening reads the *source*, so a tuple that only exists at run time has an unknown
        // arity here. That has to cost nothing: no claim, and the program still compiles and runs.
        val value = run(
            """
            let arguments = (4, 4, fun x y -> toFloat (x + y) / 6.0) in
            hostClass "afield.stdlib.Img" |> hostGet "Companion" |> hostCall "generate" arguments
            """,
        )
        val img = assertIs<Img>(assertIs<FieldMLValue.HostValue>(value).value)
        assertEquals(4, img.width)
    }

    @Test
    fun aConstructorTakingALambdaStillResolves() {
        // `hostNew` shares the same parameter resolution. `PersistentDouble(() -> Double)` has a
        // zero-argument callback, so there is nothing to type — but the constructor must still be
        // found with a FieldML function in the argument list.
        assertEquals(
            "PersistentDouble",
            typeOf("""hostNew (fun ignored -> 3.0) (hostClass "afield.PersistentDouble")"""),
        )
    }

    // ---- the memo's one invariant ------------------------------------------------------------
    //
    // `preInferHostSpine` computes a result in one environment and hands it to a walk that consumes
    // it somewhere else. That is only sound while the two environments are the same, and the way it
    // is kept sound is structural: the memo is a *parameter* threaded only along the paths that leave
    // `env` alone. These four tests are what that claim looks like from the outside — every shape in
    // today's grammar where a name is rebound near a pre-inferred node, with the types it must have.

    @Test
    fun aCallbackParameterShadowsAnOuterBindingOfTheSameName() {
        // `p` is an Int outside and a Vec3 inside. If the memo ever escaped into an environment where
        // a name had been rebound, this is the shape that would show it.
        val result = compileOk(
            """
            let p = 5 in
            fline |> moveTo (toFloat p) 0.0
              |> hostCall1 "transform" (fun p -> vec3 p.x p.y p.z)
            """,
        )
        assertEquals(listOf("p: Vec3"), parameterSpellings(result))
        assertEquals("FLine", TypePrinter().print(result.typed!!.type))
    }

    @Test
    fun anOuterBindingIsStillItselfAfterTheCallbackThatShadowedIt() {
        // The other direction: the shadowing must not leak back out. `nodes` and `n` are both read
        // *after* the spine, in the environment the spine did not touch.
        val value = run(
            """
            let n = 7 in
            let warped =
              fline |> moveTo 0.0 0.0 |> lineTo 10.0 0.0
                |> hostCall1 "transform" (fun n -> vec3 n.x n.y n.z)
            in (n + 1, warped |> hostGet "nodes" |> length)
            """,
        )
        assertEquals("(8, 2)", renderFieldMLValue(value))
    }

    @Test
    fun aLetInsideACallbackBodyDoesNotReachItsSiblingArguments() {
        // A `let` is one of the forms the memo is deliberately not threaded into. Two arguments of one
        // call, each binding `q`, each meaning something different.
        val value = run(
            """
            let Img = hostClass "afield.stdlib.Img"
            let a = hostNew (2, 2, 1, [1.0, 2.0, 3.0, 4.0]) Img
            let b = a |> hostCall1 "map" (fun q -> let q = 10.0 in q)
            b |> hostCall2 "zip" a (fun q r -> q + r) |> hostCall "at" (0, 0, 0)
            """,
        )
        assertEquals("11.0", renderFieldMLValue(value))
    }

    @Test
    fun aLambdaBehindALetGetsNoExpectedTypeAndSaysSo() {
        // The expected type reaches a lambda *literal* in argument position, and only that. Behind a
        // `let` the argument is an `ELet`, the memo is never built, and the parameter is on its own —
        // which is the same residual as the function-parameter receiver, from the other end.
        assertRefused(
            """
            fline |> hostCall1 "transform" (let scale = 2.0 in fun p -> vec3 (p.x * scale) p.y p.z)
            """,
            "Record is missing field 'y'",
        )
    }

    // ---- the measurement --------------------------------------------------------------------

    /**
     * How much of a real Kotlin callback surface now has typed parameters.
     *
     * The denominator is every argument slot of every functional-interface parameter of the public
     * methods of the same types `FieldMLHostSurfaceCoverageTest` measures. The numerator is the
     * slots that come back as something other than the dynamic `Host` — that is, the slots where a
     * FieldML author no longer needs `hostAs`. It goes through the production entry points
     * (`FieldMLHostTypes.parameterSlot` and `samParameterTypes`), not a model of them.
     *
     * The floor is a few points under what this run measures, in the same spirit as the coverage
     * test's: the printed number is the claim, the assertion is the ratchet.
     */
    @Test
    fun mostOfTheSurfacesCallbackArgumentsNowHaveAType() {
        val owners = listOf(
            FLine::class.java,
            Img::class.java,
            Img.Companion::class.java,
            Vector2f::class.java,
            Vector3f::class.java,
            Vector4f::class.java,
            Class.forName("afield.stdlib.ImagingKt"),
            Class.forName("afield.stdlib.Imaging_filtersKt"),
        )

        val slots = owners.flatMap { owner ->
            owner.methods
                .filter { !it.isSynthetic && it.declaringClass != Any::class.java && '$' !in it.name }
                .flatMap { method -> callbackSlotsOf(method) }
        }.distinctBy { it.first }

        val typed = slots.filter { it.second }
        val percent = if (slots.isEmpty()) 0.0 else 100.0 * typed.size / slots.size
        val report = buildString {
            appendLine("callback arguments across ${owners.size} real types:")
            appendLine("  slots:  ${slots.size}")
            appendLine("  typed:  ${typed.size} (%.1f%%)".format(percent))
            appendLine("  still Host (erased or nullable):")
            slots.filterNot { it.second }.take(12).forEach { appendLine("    ${it.first}") }
            appendLine("  typed, a sample:")
            typed.take(12).forEach { appendLine("    ${it.first}") }
        }
        println(report)

        assertTrue(slots.size >= 20, "expected at least 20 callback argument slots, found ${slots.size}\n$report")
        assertTrue(typed.size >= 20, "only ${typed.size} callback arguments have a type\n$report")
        // Under one slot of slack against the 91.3% this run measures: the two that are still `Host`
        // are the nullable and erased ones, and there is no third that ought to be typed.
        assertTrue(percent >= 88.0, "typed callback arguments fell to %.1f%%\n%s".format(percent, report))
    }

    /** One entry per argument of every functional-interface parameter of [method]: label to typed. */
    private fun callbackSlotsOf(method: Method): List<Pair<String, Boolean>> =
        method.parameterTypes.indices.flatMap { index ->
            val slot = FieldMLHostTypes.parameterSlot(method, index)
            if (FieldMLHostBridge.functionalMethod(slot.jvmClass) == null) return@flatMap emptyList()
            val types = FieldMLHostTypes.samParameterTypes(slot).orEmpty()
            types.mapIndexed { argument, type ->
                val label = "${method.declaringClass.simpleName}.${method.name} " +
                    "param $index arg $argument: ${TypePrinter().print(type)}"
                label to (type !is THost || type.name != FieldMLHostTypes.OPAQUE)
            }
        }

    // ---- harness -----------------------------------------------------------------------------

    private data class Case(val label: String, val source: String, val parameters: List<String>)

    /** Every lambda in the typed tree, spelled `name: Type`, in source order. */
    private fun parameterSpellings(result: CompilationResult): List<String> =
        lambdasOf(result.typed!!).map { lambda ->
            lambda.parameters.joinToString(", ") { "${it.first}: ${TypePrinter().print(it.second)}" }
        }

    private fun lambdasOf(expr: TExpr): List<TExprLambda> = buildList { collectLambdas(expr, this) }

    private fun collectLambdas(expr: TExpr, into: MutableList<TExprLambda>) {
        if (expr is TExprLambda) into += expr
        when (expr) {
            is TExprCall -> { collectLambdas(expr.function, into); collectLambdas(expr.argument, into) }
            is TExprLambda -> collectLambdas(expr.body, into)
            is TExprLet -> { collectLambdas(expr.value, into); collectLambdas(expr.body, into) }
            is TExprIf -> {
                collectLambdas(expr.condition, into)
                collectLambdas(expr.thenBranch, into)
                collectLambdas(expr.elseBranch, into)
            }
            is TExprTuple -> expr.elements.forEach { collectLambdas(it, into) }
            is TExprList -> expr.elements.forEach { collectLambdas(it, into) }
            is TExprRecord -> expr.fields.forEach { collectLambdas(it.second, into) }
            is TExprField -> collectLambdas(expr.target, into)
            is TExprBinary -> { collectLambdas(expr.left, into); collectLambdas(expr.right, into) }
            is TExprNegate -> collectLambdas(expr.value, into)
            is TExprMatch -> {
                collectLambdas(expr.scrutinee, into)
                expr.cases.forEach { collectLambdas(it.body, into) }
            }
            is TExprTypeDecl -> collectLambdas(expr.body, into)
            else -> Unit
        }
    }

    private fun typeOf(source: String): String = TypePrinter().print(compileOk(source).typed!!.type)

    private fun assertRefused(source: String, messageContains: String) {
        val result = FieldMLCompiler.compile(source.trimIndent())
        assertTrue(!result.successful, "expected '$source' to be refused, it compiled")
        assertTrue(
            result.diagnostics.any { messageContains in it.message },
            "expected a diagnostic containing '$messageContains', got ${result.diagnostics.joinToString { it.message }}",
        )
    }

    private fun run(source: String): FieldMLValue = evaluate(compileOk(source))

    private fun evaluate(result: CompilationResult): FieldMLValue =
        Evaluator(FieldMLRuntimePrelude.values(box = box)).evaluate(result.core!!)

    private val box = Box("fieldml-lambda-expected-type-test")

    private fun compileOk(source: String, label: String = "program"): CompilationResult {
        val result = FieldMLCompiler.compile(source.trimIndent())
        assertTrue(
            result.successful,
            "$label failed:\n${result.diagnostics.joinToString("\n") { "${it.severity}: ${it.message}" }}",
        )
        return result
    }

    private companion object {
        /** A small real `Img`, spelled once because half the callback surface hangs off one. */
        private const val IMG = """hostNew (2, 2, 1, [0.0, 0.25, 0.5, 1.0]) (hostClass "afield.stdlib.Img")"""
    }
}
