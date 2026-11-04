package afield.lang.fieldml.tests

import afield.document.graph.Box
import afield.lang.fieldml.FieldMLCompiler
import afield.lang.fieldml.FieldMLRuntimePrelude
import afield.lang.fieldml.core.Evaluator
import afield.lang.fieldml.core.FieldMLValue
import afield.lang.fieldml.core.fieldMLValueToHost
import afield.runtime.lifecycle.Frames
import afield.runtime.lifecycle.Runloop
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.startCoroutine
import kotlin.coroutines.suspendCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A FieldML body that **suspends** — "Suspendable bodies" in `dev_docs/FIELDML_HOST_BRIDGE.md`.
 *
 * Until 2026-11-01 a FieldML closure satisfied `suspend Frames.() -> Unit` by never suspending, so
 * `frames { }` — whose body drives `frame()` itself — was expressible but useless. Now a closure
 * Kotlin calls with a real continuation is walked by the suspending evaluator, and `hostAwait`
 * passes that continuation on to a host suspend member. The tests are about *order across
 * resumptions*: what has been written before the body parks, and what only appears once the host
 * (a hand-held continuation, or Field's runloop turning a frame) resumes it.
 */
class FieldMLSuspendableBodyTest {
    /** A host that records writes, parks a caller on demand, and runs a suspend block itself. */
    class Host {
        val seen = mutableListOf<Any?>()
        var pending: Continuation<Int>? = null
        var finished = false

        fun record(value: Any?) {
            seen += value
        }

        suspend fun park(): Int = suspendCoroutine { pending = it }

        fun start(block: suspend () -> Unit) {
            block.startCoroutine(Continuation(EmptyCoroutineContext) { it.getOrThrow(); finished = true })
        }
    }

    private val host = Host()
    private val box = Box("fieldml-suspendable-body-test").also { it.setPropertyRaw("host", host, false) }
    private val prefix = """let s = thisBox |> hostCall "getPropertyRaw" ("host", hostNull)
let record v = s |> hostCall1 "record" v
let Phased = hostClass "afield.runtime.lifecycle.PhasedKt"
"""

    @Test
    fun aBodyParksAtHostAwaitAndContinuesWithTheValueTheHostResumesWith() {
        evaluate(
            """
            s |> hostCall1 "start" (fun u ->
              let _ = record "before" in
              let v = hostAwait "park" () s |> hostAsInt in
              record (v + 1))
            """,
        )
        // The first write happened, the second has not: the body is parked inside `park`.
        assertEquals(listOf<Any?>("before"), host.seen)
        assertTrue(!host.finished)
        host.pending!!.resume(41)
        assertEquals(listOf<Any?>("before", 42L), host.seen)
        assertTrue(host.finished)
    }

    @Test
    fun aFramesBodyWritesOnceBetweenEachFrameItWaitsFor() {
        // The mission's case: `frames { a; frame(); b; frame(); c }` written in FieldML, driven by
        // the real `Frames.frame()` (which `yield()`s) on Field's runloop dispatcher.
        val frames = assertIs<Frames>(
            evaluate(
                """
                hostStaticCall "frames" (fun f ->
                  let _ = record 1 in
                  let _ = hostAwait "frame" () f in
                  let _ = record 2 in
                  let _ = hostAwait "frame" () f in
                  record 3) Phased
                """,
            ),
        )
        CoroutineScope(Runloop.runLoop.main).launch(start = CoroutineStart.UNDISPATCHED) { frames.body() }
        assertEquals(listOf<Any?>(1L), host.seen, "before any runloop frame")
        Runloop.runLoop.main.frame()
        assertEquals(listOf<Any?>(1L, 2L), host.seen, "after one runloop frame")
        Runloop.runLoop.main.frame()
        assertEquals(listOf<Any?>(1L, 2L, 3L), host.seen, "after two runloop frames")
        assertEquals(2, frames.frameNumber)
    }

    @Test
    fun aRecursiveLoopWaitsAFrameEachTimeRound() {
        val frames = assertIs<Frames>(
            evaluate(
                """
                hostStaticCall "frames" (fun f ->
                  let go i =
                    if i < 4 then
                      let _ = record i in
                      let _ = hostAwait "frame" () f in
                      go (i + 1)
                    else record "done"
                  in go 0) Phased
                """,
            ),
        )
        CoroutineScope(Runloop.runLoop.main).launch(start = CoroutineStart.UNDISPATCHED) { frames.body() }
        val perFrame = mutableListOf(host.seen.toList())
        repeat(5) {
            Runloop.runLoop.main.frame()
            perFrame += host.seen.toList()
        }
        assertEquals(
            listOf(
                listOf<Any?>(0L),
                listOf<Any?>(0L, 1L),
                listOf<Any?>(0L, 1L, 2L),
                listOf<Any?>(0L, 1L, 2L, 3L),
                listOf<Any?>(0L, 1L, 2L, 3L, "done"),
                listOf<Any?>(0L, 1L, 2L, 3L, "done"),
            ),
            perFrame,
        )
    }

    @Test
    fun hostAwaitInsideASynchronousCallbackIsRefusedByName() {
        // `map` calls its function synchronously, so there is no continuation there to suspend.
        evaluate(
            """
            s |> hostCall1 "start" (fun u ->
              let _ = [1, 2] |> map (fun i -> hostAwait "park" () s) in
              record "unreachable")
            """,
            expectFailure = "can only run inside a body Kotlin calls as a suspend function",
        )
        assertEquals(emptyList<Any?>(), host.seen)
    }

    @Test
    fun hostAwaitOutsideAnySuspendBodyIsRefusedByName() {
        evaluate("""hostAwait "park" () s""", expectFailure = "can only run inside a body")
    }

    @Test
    fun bothWalksAgreeOnEveryCoreShape() {
        // The suspending walk mirrors the synchronous one line for line; a body that never
        // suspends must produce the same value either way. One program touching every CoreExpr
        // case: literals, tuple, list, type decl + match, let rec, lambda, if, record, field,
        // short-circuit, binary, negate.
        val expression =
            "let fact n = if n <= 1 then 1 else n * fact (n - 1) in " +
            "let area sh = match sh with | Dot -> 0 | Sq n -> n * n in " +
            "let r = { a = fact 5, b = -(area (Sq 3)), c = (1, \"x\"), d = [true && false, false || true] } in " +
            "show (r, r.a + 1, (fun x -> x * 2.5) 2.0)"
        val types = "type Shape = Dot | Sq Int\n"
        evaluate(types + "record ($expression)")
        evaluate(types + "s |> hostCall1 \"start\" (fun u -> record ($expression))")
        assertEquals(2, host.seen.size)
        assertEquals(host.seen[0], host.seen[1])
        assertTrue(host.finished)
    }

    private fun evaluateRaw(source: String): FieldMLValue {
        val result = FieldMLCompiler.compile(source)
        assertTrue(result.successful, result.diagnostics.joinToString("\n") { "${it.severity}: ${it.message}" })
        return Evaluator(FieldMLRuntimePrelude.values(box = box)).evaluate(result.core!!)
    }

    private fun evaluate(body: String, expectFailure: String? = null): Any? {
        val source = "$prefix\n" + body.trimIndent()
        if (expectFailure == null) return fieldMLValueToHost(evaluateRaw(source))
        val failure = runCatching { evaluateRaw(source) }.exceptionOrNull()
        assertTrue(failure != null && expectFailure in failure.message.orEmpty(), "got $failure")
        return null
    }
}
