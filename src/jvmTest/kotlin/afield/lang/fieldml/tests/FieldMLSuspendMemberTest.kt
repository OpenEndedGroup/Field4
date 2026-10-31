package afield.lang.fieldml.tests

import afield.document.graph.Box
import afield.lang.fieldml.FieldMLCompiler
import afield.lang.fieldml.FieldMLRuntimePrelude
import afield.lang.fieldml.core.Evaluator
import afield.lang.fieldml.core.FieldMLValue
import afield.lang.fieldml.core.fieldMLValueToHost
import kotlinx.coroutines.Job
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The other half of mechanism **D** in `dev_docs/FIELDML_HOST_BRIDGE.md`: calling *into* a Kotlin
 * `suspend` member from FieldML, through `hostLaunch`.
 *
 * `FieldMLSuspendCallbackTest` covers a FieldML closure *being* a suspend function. This covers a
 * FieldML box *calling* one — `run(Continuation)` and its kind — which the bridge could not do
 * because it had no continuation to pass. `hostLaunch` starts the call as its own coroutine and
 * makes the written FieldML closure its completion, so the tests below are about *when* that closure
 * runs: at once for a member that never suspends, and only on resumption for one that does.
 */
class FieldMLSuspendMemberTest {
    /** A host object with the three shapes that matter: completes at once, suspends, fails. */
    class Suspender {
        var pending: Continuation<Int>? = null
        var seen: Any? = null

        suspend fun twice(x: Int): Int = x * 2

        suspend fun later(x: Int): Int = suspendCoroutine { pending = it } + x

        suspend fun broken(): Int = error("broken on purpose")

        suspend fun brokenLater(): Int = suspendCoroutine { pending = it }

        fun plain(x: Int): Int = x + 1

        fun record(value: Any?) {
            seen = value
        }
    }

    private val suspender = Suspender()
    private val box = Box("fieldml-suspend-member-test").also { it.setPropertyRaw("suspender", suspender, false) }
    private val prefix = """let s = thisBox |> hostCall "getPropertyRaw" ("suspender", hostNull)"""

    @Test
    fun aMemberThatNeverSuspendsHandsItsResultToTheClosureBeforeHostLaunchReturns() {
        val job = evaluate("""hostLaunch "twice" 21 s (fun r -> s |> hostCall1 "record" r)""")
        assertEquals(42L, suspender.seen)
        assertIs<Job>(job)
        assertTrue(job.isCompleted && !job.isCancelled)
    }

    @Test
    fun aMemberThatSuspendsRunsTheClosureOnlyWhenTheHostResumesIt() {
        val job = assertIs<Job>(evaluate("""hostLaunch "later" 2 s (fun r -> s |> hostCall1 "record" r)"""))
        // Nothing blocked and nothing ran: the member is parked on its own continuation.
        assertNull(suspender.seen)
        assertTrue(job.isActive)
        suspender.pending!!.resume(40)
        assertEquals(42L, suspender.seen)
        assertTrue(job.isCompleted)
    }

    @Test
    fun aFailureBeforeTheFirstSuspensionIsThrownInTheCallingBox() {
        val failure = assertFailsWith<IllegalStateException> {
            evaluate("""hostLaunch "broken" () s (fun r -> s |> hostCall1 "record" r)""")
        }
        assertEquals("broken on purpose", failure.message)
        assertNull(suspender.seen)
    }

    @Test
    fun aLaterFailureCompletesTheJobExceptionallyAndSkipsTheClosure() {
        val job = assertIs<Job>(evaluate("""hostLaunch "brokenLater" () s (fun r -> s |> hostCall1 "record" r)"""))
        suspender.pending!!.resumeWithException(IllegalStateException("late"))
        assertNull(suspender.seen)
        assertTrue(job.isCancelled)
    }

    @Test
    fun aPlainMemberIsRefusedByHostLaunchWithAPointerToHostCall() {
        val failure = assertFailsWith<IllegalStateException> {
            evaluate("""hostLaunch "plain" 1 s (fun r -> r)""")
        }
        assertTrue("not a suspend member" in failure.message!!, failure.message)
    }

    @Test
    fun aSuspendMemberIsStillRefusedByHostCallBecauseItsArityCountsTheContinuation() {
        val failure = runCatching { evaluate("""s |> hostCall "twice" 21""") }.exceptionOrNull()
        assertTrue(failure != null && "twice" in failure.message.orEmpty(), "got $failure")
    }

    @Test
    fun theWrongArgumentCountIsRefusedRatherThanPassedAsTheContinuation() {
        val failure = runCatching { evaluate("""hostLaunch "twice" (1, 2) s (fun r -> r)""") }.exceptionOrNull()
        assertTrue(failure != null && "twice" in failure.message.orEmpty(), "got $failure")
    }

    private fun evaluate(body: String): Any? {
        val source = "$prefix\n\n$body"
        val result = FieldMLCompiler.compile(source)
        assertTrue(result.successful, result.diagnostics.joinToString("\n") { "${it.severity}: ${it.message}" })
        val value: FieldMLValue = Evaluator(FieldMLRuntimePrelude.values(box = box)).evaluate(result.core!!)
        return fieldMLValueToHost(value)
    }
}
