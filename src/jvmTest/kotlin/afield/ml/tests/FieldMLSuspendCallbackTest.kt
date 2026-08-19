package afield.ml.tests

import afield.Box
import afield.Frames
import afield.ml.CompilationResult
import afield.ml.FieldMLCompiler
import afield.ml.FieldMLRuntimePrelude
import afield.ml.core.Evaluator
import afield.ml.core.FieldMLHostBridge
import afield.ml.core.FieldMLValue
import afield.ml.core.fieldMLValueToHost
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Mechanism **D** of `dev_docs/FIELDML_HOST_BRIDGE.md`: a Kotlin `suspend` function type, handed a
 * FieldML closure.
 *
 * The row this closes said a suspend type "erases to a `Function` taking a `Continuation`, which a
 * plain dynamic proxy cannot satisfy". Read against the bytecode that was half true, and the false
 * half was the opening. `eachFrame(block: suspend Frames.() -> Unit)` compiles to
 * `Function2<? super Frames, ? super Continuation<? super Unit>, ? extends Object>` — an ordinary
 * functional interface, which `samProxy` was already willing to proxy. What went wrong was narrower
 * and entirely fixable: the continuation was curried into the FieldML closure as a second argument,
 * and the declared result — which survives only *inside* `Continuation<? super R>` — was read off
 * the erased `Object` return instead.
 *
 * So the tests here are not "a proxy can be constructed". They are "the closure **ran**, this many
 * times, and the JVM got the value it declared". A test that only builds a proxy would have passed
 * before this work as well as after it.
 *
 * The bound is tested too, in [aBodyThatWouldHaveToSuspendIsStillOutOfReach]. The adapter is sound
 * because a suspend function that never suspends returns its value directly — the ordinary
 * completed-synchronously path — and a FieldML body genuinely never suspends. Which means a FieldML
 * body cannot call `Frames.frame()` either, and saying where that line falls is part of the claim.
 */
class FieldMLSuspendCallbackTest {
    /** Parenthesised: FieldML is curried, so a bare `hostClass "…"` here would be two more arguments. */
    private val phased = """(hostClass "afield.PhasedKt")"""

    // ---- the closure runs -------------------------------------------------------------------

    @Test
    fun aSuspendCallbackIsCalledOncePerPassAndItsBooleanResultStopsTheLoop() {
        // `forever(block: suspend Frames.() -> Boolean)` is the sharpest case available: the block is
        // driven repeatedly, and the *loop's* termination depends on the JVM receiving a real
        // `java.lang.Boolean` — which only exists as the type argument of the trailing continuation.
        // Reading `Function2`'s own erased `Object` return, as the proxy did before, hands Kotlin
        // whatever came back and `while (block(v))` fails its cast.
        val counter = AtomicInteger()
        box.setPropertyRaw("suspendCounter", counter, false)

        val frames = assertIs<Frames>(
            hostValueOf(
                """
                let counter = thisBox |> hostCall "getPropertyRaw" ("suspendCounter", hostNull)

                hostStaticCall "forever" (fun f ->
                  (counter |> hostCall0 "incrementAndGet" |> hostAsInt) < 5
                ) $phased
                """,
            ),
        )

        // Nothing has run yet: `forever` only *builds* the Frames. This assertion is what separates
        // "a proxy was constructed" from "the body executed", and it is the reason the counter is
        // read twice.
        assertEquals(0, counter.get(), "forever should not have called the block while building")

        runBlocking { frames.body() }

        assertEquals(5, counter.get(), "the FieldML block should have run five times")
        assertEquals(4, frames.frameNumber, "forever advances one frame per pass that did not")
    }

    @Test
    fun theCallbackIsHandedItsRealArgumentAndNotTheContinuation() {
        // The block's one written parameter is the `Frames` receiver. If the continuation were still
        // curried in, this would fail inside `applyFieldMLFunction` — a one-parameter FieldML lambda
        // applied twice. Reading `frameNumber` off it proves argument zero is the real receiver and
        // not, say, the continuation shifted into its place.
        val frames = assertIs<Frames>(
            hostValueOf("""hostStaticCall "forever" (fun f -> (f |> hostGet "frameNumber") < 3) $phased"""),
        )

        runBlocking { frames.body() }

        assertEquals(3, frames.frameNumber, "the block should have read the live Frames three times")
    }

    @Test
    fun eachFrameRunsAFieldMLBodyEveryFrameUntilTheFramesAreEnded() {
        // `eachFrame` is the entry point the animation demo uses, and it differs from `forever` in
        // the way that matters for a real box: the enclosing Kotlin owns *all* the suspension points
        // (`while (frame()) { block() }`), so the FieldML body is a plain per-frame body. Ending it
        // is Field's own mechanism — `shouldend`, which is what `Box.launchFrames` sets — and it
        // arrives as the CancellationException `frame()` throws.
        val counter = AtomicInteger()
        box.setPropertyRaw("suspendCounter", counter, false)

        val frames = assertIs<Frames>(
            hostValueOf(
                """
                let counter = thisBox |> hostCall "getPropertyRaw" ("suspendCounter", hostNull)

                hostStaticCall "eachFrame" (fun f -> counter |> hostCall0 "incrementAndGet") $phased
                """,
            ),
        )
        frames.shouldend = { counter.get() >= 6 }

        // In its own child job, because ending a Frames *is* cancelling the job it is running on —
        // `Frames.frame` calls `coroutineContext[Job]!!.cancel()` — and doing that to `runBlocking`'s
        // own coroutine would fail the test with the cancellation rather than run to the end.
        runBlocking { launch { frames.body() }.join() }

        assertEquals(6, counter.get(), "eachFrame should have run the FieldML body six times")
    }

    @Test
    fun aZeroArgumentSuspendTypeAlsoWorksAndItsResultIsUnit() {
        // `Frames.atEnd` is `suspend () -> Unit`, i.e. `Function1<Continuation<Unit>, Object>` — a
        // functional interface whose *only* JVM parameter is the continuation. After dropping it
        // there is nothing left to curry, so the closure takes the same `Unit` a `Runnable`-shaped
        // interface gets, which is why `fun ignored -> …` is how it is written. The result must be
        // Kotlin's `Unit` object rather than whatever the FieldML body returned; anything else and
        // the suspend caller reads it as a resumption value.
        val counter = AtomicInteger()
        box.setPropertyRaw("suspendCounter", counter, false)

        val frames = assertIs<Frames>(
            hostValueOf(
                """
                let counter = thisBox |> hostCall "getPropertyRaw" ("suspendCounter", hostNull)
                let frames = hostStaticCall "eachFrame" (fun f -> 0) $phased

                frames |> hostSet "atEnd" (fun ignored -> counter |> hostCall0 "incrementAndGet")
                """,
            ),
        )

        runBlocking { frames.atEnd() }

        assertEquals(1, counter.get(), "the atEnd closure should have run exactly once")
    }

    // ---- what the shape reader says ----------------------------------------------------------

    @Test
    fun theSuspendShapeReportsTheWrittenArityAndTheDeclaredResult() {
        // The reflective claim, on its own, so that a failure here points at the reader rather than
        // at the proxy. `eachFrame` and `forever` differ only in the continuation's type argument,
        // which is exactly the thing the erased signature cannot tell you.
        val phasedKt = Class.forName("afield.PhasedKt")
        val eachFrame = phasedKt.methods.single { it.name == "eachFrame" }
        val forever = phasedKt.methods.single { it.name == "forever" }

        val eachFrameShape = FieldMLHostBridge.suspendShape(
            eachFrame.parameterTypes[0],
            eachFrame.genericParameterTypes[0],
        )
        val foreverShape = FieldMLHostBridge.suspendShape(
            forever.parameterTypes[0],
            forever.genericParameterTypes[0],
        )

        assertEquals(1, eachFrameShape?.parameterCount, "eachFrame's block takes one written argument")
        assertEquals(Unit::class.java, eachFrameShape?.resultType)
        assertEquals(1, foreverShape?.parameterCount)
        assertEquals(
            Boolean::class.javaObjectType,
            foreverShape?.resultType,
            "forever's declared Boolean only exists inside Continuation<? super Boolean>",
        )

        // And an ordinary functional interface is not mistaken for a suspend one. `Img.generate`'s
        // callback is the `Function2` that motivated SAM conversion in the first place, and it must
        // keep both of its parameters.
        val generate = afield.stdlib.Img.Companion::class.java.methods.single { it.name == "generate" }
        val callback = generate.parameterTypes.lastIndex
        assertEquals(
            null,
            FieldMLHostBridge.suspendShape(
                generate.parameterTypes[callback],
                generate.genericParameterTypes[callback],
            ),
            "a plain Function2 must not be read as a suspend type",
        )
    }

    // ---- the type checker agrees --------------------------------------------------------------

    @Test
    fun theCallbackParameterIsTypedFramesSoAMisspelledMemberIsACompileError() {
        // The type-level twin. Before this the slot list included the continuation, so the *shape*
        // the checker had was wrong in both directions at once — and a misspelling inside a suspend
        // callback was a run-time crash.
        assertRefused(
            """hostStaticCall "eachFrame" (fun f -> f |> hostGet "frameNumbr") $phased""",
            "frameNumbr",
        )
    }

    @Test
    fun aSuspendCallbackWithTooManyParametersIsRefusedWithTheWrittenArity() {
        // The arity in this message is the one an author can act on: one, not the two the JVM
        // signature has. With the continuation still counted, `fun f cont -> …` type-checked and
        // then failed on its first frame, and `fun f -> …` — the correct spelling — was fine only by
        // accident of the "fewer is legitimate" rule.
        assertRefused(
            """hostStaticCall "eachFrame" (fun f cont -> 0) $phased""",
            "calls this function with 1 argument(s), but it takes 2 (f, cont)",
        )
    }

    // ---- the bound, stated as a test -----------------------------------------------------------

    @Test
    fun aBodyThatWouldHaveToSuspendIsStillOutOfReach() {
        // The honest half. `Frames.frame()` is `suspend`, so on the JVM it is `frame(Continuation)`
        // — arity one — and the bridge has no continuation to pass, so no candidate matches at
        // arity zero. That refusal is correct rather than unfortunate: this adapter is sound *only*
        // because a FieldML body never suspends, and being able to call `frame()` from FieldML is
        // exactly the thing that would make it unsound. `frames { }`, whose contract is that the
        // body drives `frame()` itself, therefore stays out of reach; `eachFrame`/`loop`/`forever`,
        // where the enclosing Kotlin owns the suspension, do not.
        val failure = runCatching {
            evaluateSource("""hostStaticCall "eachFrame" (fun f -> f |> hostCall0 "frame") $phased""")
        }
        val message = compileFailure("""hostStaticCall "eachFrame" (fun f -> f |> hostCall0 "frame") $phased""")
            ?: failure.exceptionOrNull()?.message
        assertTrue(
            message != null && "frame" in message,
            "expected the suspend member to be unreachable at arity zero, got $message",
        )
    }

    // ---- harness --------------------------------------------------------------------------------

    private val box = Box("fieldml-suspend-callback-test")

    private fun hostValueOf(source: String): Any? = fieldMLValueToHost(evaluateSource(source))

    private fun evaluateSource(source: String): FieldMLValue {
        val result = FieldMLCompiler.compile(source.trimIndent())
        assertTrue(
            result.successful,
            result.diagnostics.joinToString("\n") { "${it.severity}: ${it.message}" },
        )
        return evaluate(result)
    }

    private fun evaluate(result: CompilationResult): FieldMLValue =
        Evaluator(FieldMLRuntimePrelude.values(box = box)).evaluate(result.core!!)

    /** The first diagnostic of a program that does not compile, or null when it does. */
    private fun compileFailure(source: String): String? {
        val result = FieldMLCompiler.compile(source.trimIndent())
        return if (result.successful) null else result.diagnostics.joinToString("; ") { it.message }
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
