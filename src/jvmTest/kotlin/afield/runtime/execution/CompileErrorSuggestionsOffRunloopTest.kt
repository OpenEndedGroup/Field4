package afield.runtime.execution

import afield.app.protocol.Output
import afield.document.graph.Box
import afield.runtime.kotlin.CompletionResult
import afield.runtime.kotlin.CompletionTokenBounds
import afield.runtime.kotlin.KotlinContext
import afield.runtime.kotlin.KotlinScriptEngine
import afield.runtime.lifecycle.inMain
import afield.runtime.lifecycle.Runloop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.script.experimental.api.ScriptDiagnostic
import kotlin.script.experimental.api.SourceCode
import kotlin.script.experimental.api.SourceCodeCompletionVariant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A compile error must reach its box before any "did you mean" work, and that work must never hold
 * the runloop thread.
 *
 * The rows for an unresolved name come from the box engine's completion, which on a fresh engine
 * first bootstraps the K1 completion delegate behind a mutex. Typing in a box starts the same
 * bootstrap as a warm-up on the runloop's dispatcher, and the bootstrap's compile hops to a compile
 * thread and must resume on the runloop. The search used to run under `runBlocking` on the runloop
 * thread the compile error arrives on: it waited for the mutex while the mutex holder waited for
 * that thread. In Field that was a box that never showed its error (an unresolved `Resolve` in a
 * fresh document, 2026-11-09); here it was `oldPathDeadlocks`, which parked in
 * `ModelEngine.completeAndWait` under `suggestResolvableSymbols` until its 10 s join gave up.
 *
 * [ModelEngine] reproduces that shape without a compiler. Its [ModelEngine.warm] holds the
 * completion mutex across a hop off the runloop and back, as the delegate bootstrap does, and its
 * [ModelEngine.complete] needs the same mutex. Each test plays the runloop on a thread of its own,
 * pumping [Runloop.runLoop]'s dispatcher, and the test thread only watches it with a deadline, so a
 * regression fails instead of hanging the suite.
 */
class CompileErrorSuggestionsOffRunloopTest {

    private class ModelEngine : KotlinScriptEngine by unsupportedEngine() {
        private val mutex = Mutex()
        var completions = 0
        override val context = KotlinContext()

        override suspend fun complete(s: String, cursor: Int): CompletionResult = mutex.withLock {
            completions++
            CompletionResult.Success(
                listOf("resolver"), CompletionTokenBounds(cursor, cursor),
                listOf(SourceCodeCompletionVariant("resolver", "resolver", "Int", "property")), s, cursor
            )
        }

        override fun completeAndWait(s: String, cursor: Int): CompletionResult = runBlocking { complete(s, cursor) }

        /** The K1 delegate bootstrap's shape: hold the mutex, leave the runloop, and come back to it. */
        suspend fun warm() = mutex.withLock { withContext(Dispatchers.Default) { delay(300) } }
    }

    private val code = "val r = resolve(1)"
    private val diagnostic = ScriptDiagnostic(
        ScriptDiagnostic.unspecifiedError, "Unresolved reference 'resolve'.",
        ScriptDiagnostic.Severity.ERROR, null,
        SourceCode.Location(SourceCode.Position(1, 9), SourceCode.Position(1, 16))
    )

    private class Played(val reportMs: Long, val outputs: List<Output>, val engine: ModelEngine)

    /**
     * On a thread acting as the runloop: start the warm-up and let it take the mutex, report the
     * compile error, run [between], then pump frames until the search is over.
     */
    private fun playRunloop(boxId: String, between: (Box) -> Unit = {}): Played {
        val box = Box(boxId)
        val engine = ModelEngine()
        box.ki = engine
        val outputs = CopyOnWriteArrayList<Output>()
        var reportMs = -1L
        var failure: Throwable? = null
        var finished = false
        val runloop = Thread({
            try {
                inMain { engine.warm() }
                Runloop.runLoop.main.frame() // the warm-up now holds the mutex, off the runloop
                val start = System.nanoTime()
                val job: Job? = reportFirstCompileError(box, code, diagnostic, "code", 1) { outputs += it }
                reportMs = (System.nanoTime() - start) / 1_000_000
                between(box)
                val deadline = System.nanoTime() + 60_000_000_000L
                while (job != null && !job.isCompleted && System.nanoTime() < deadline) {
                    Runloop.runLoop.main.frame()
                    Thread.sleep(5)
                }
                finished = job == null || job.isCompleted
            } catch (t: Throwable) {
                failure = t
            }
        }, "model-runloop-$boxId").apply { isDaemon = true; start() }

        runloop.join(90_000)
        failure?.let { throw it }
        if (runloop.isAlive) {
            fail(
                "the runloop thread is stuck after reporting a compile error:\n" +
                    runloop.stackTrace.take(30).joinToString("\n") { "  at $it" }
            )
        }
        assertTrue(finished, "the did-you-mean search never finished while the runloop kept pumping")
        return Played(reportMs, outputs.toList(), engine)
    }

    @Test
    fun theErrorGoesOutAtOnceAndItsRowsFollowWithoutBlockingTheRunloop() {
        val played = playRunloop("suggest-off-runloop")

        assertTrue(played.reportMs < 1_000, "reporting the error held the runloop for ${played.reportMs} ms")
        val error = played.outputs.firstOrNull() ?: fail("no output was dispatched")
        assertEquals("error", error.kind)
        assertEquals(1, error.line)
        assertEquals(9, error.startColumn)
        assertTrue("resolve" in error.html, "the error carries the compiler's message: ${error.html}")
        assertTrue(error.suggestions.isEmpty(), "the slow rows are not waited for: ${error.suggestions}")

        val rows = played.outputs.singleOrNull { it.kind == "suggestions" }
        assertNotNull(rows, "the rows follow the error: ${played.outputs.map { it.kind }}")
        assertEquals(error.line, rows.line)
        assertEquals("", rows.html)
        assertTrue(played.engine.completions > 0, "the rows came from the engine's completion")
        val rename = rows.suggestions.firstOrNull { it.label == "resolver" }
        assertNotNull(rename, "the completion's candidate is offered: ${rows.suggestions.map { it.label }}")
        assertEquals("rename", rename.kind)
        assertEquals("resolve", rename.token)
    }

    @Test
    fun rowsForARunTheBoxHasReplacedAreDropped() {
        val played = playRunloop("suggest-stale") { box -> noteRunVerdictOutput(box.id) }

        assertEquals(listOf("error"), played.outputs.map { it.kind })
    }

    @Test
    fun anErrorWithNothingToSearchStartsNoSearch() {
        val box = Box("suggest-none")
        box.ki = ModelEngine()
        val outputs = mutableListOf<Output>()
        val mismatch = ScriptDiagnostic(
            ScriptDiagnostic.unspecifiedError,
            "Argument type mismatch: actual type is 'Int', but 'Double' was expected.",
            ScriptDiagnostic.Severity.ERROR, null,
            SourceCode.Location(SourceCode.Position(1, 5))
        )

        val job = reportFirstCompileError(box, "f(1)", mismatch, "code", 1) { outputs += it }

        assertEquals(null, job)
        assertEquals(listOf("error"), outputs.map { it.kind })
        assertEquals(listOf(".d"), outputs.single().suggestions.map { it.replacement })
    }
}

private fun unsupportedEngine(): KotlinScriptEngine = Proxy.newProxyInstance(
    KotlinScriptEngine::class.java.classLoader,
    arrayOf(KotlinScriptEngine::class.java)
) { _, method, _ -> throw UnsupportedOperationException("ModelEngine.${method.name}") } as KotlinScriptEngine
