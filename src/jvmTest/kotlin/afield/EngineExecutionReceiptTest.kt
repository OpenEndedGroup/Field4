package afield

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The receipt every engine now builds, held to the two things that make it worth having.
 *
 * `b5b9ad9e0` added correlated receipts and, because only Kotlin produced one, refused to execute
 * any other engine that was asked for one. That refusal was deliberate — better to run nothing than
 * to invent a receipt — but it meant no Clojure, Python, Datalog, FieldML, Forth, Nano or Morpho box
 * could be run through an awaited backdoor call at all, which took out 32 documentation fences
 * across 8 pages. The repair was to give those engines real receipts rather than to let them run
 * without one, so these tests pin the part that makes that repair honest: a receipt is *earned*.
 *
 * They exercise [ExecutionTrace] directly rather than a language engine, because every engine
 * dispatches its work onto the runloop and there is no runloop here. What each engine is
 * responsible for — finishing the trace inside that dispatch instead of when the handler returns —
 * is covered end-to-end by the documentation captures on those eight pages.
 */
class EngineExecutionReceiptTest {

    private fun box(): Box {
        val root = Box("root")
        val session = SessionInformation(filename = "<receipts>", documentName = "receipts", root = root, ephemeral = true)
        root.inside = session
        session.loading = false
        thisSessionInfo.set(session)
        return root
    }

    private fun errorOutput(uid: String) =
        Message("output").payload(Output(uid = uid, kind = "error", html = "NameError: no such name", line = 3, frame = 0))

    /**
     * A `Message` carries its payload already serialized, so a receipt has to be decoded back out
     * rather than cast — casting compiles and then silently never matches, which is a good way to
     * write a test that passes because it saw nothing.
     */
    private fun receipts(seen: MutableList<ExecutionReport>): (Message) -> Unit = { message ->
        if (message.name == "execution-verdict") {
            seen.add(Json.decodeFromJsonElement(ExecutionReport.serializer(), message.payload as JsonObject))
        }
    }

    @Test
    fun `an engine that succeeds quietly earns a setup-complete receipt`() {
        val seen = mutableListOf<ExecutionReport>()
        val trace = ExecutionTrace.forEngine(box(), Execute(uid = "root", type = "all", line = 0, code = "1 + 1", singleLine = false, requestId = "req-1"), "run", receipts(seen))
        trace.finish(true)
        assertEquals(1, seen.size, "an awaited run must answer exactly once")
        assertTrue(seen[0].success, seen[0].error)
        assertEquals("setup-complete", seen[0].stage)
        assertEquals("req-1", seen[0].requestId, "the receipt is useless if it does not correlate")
    }

    @Test
    fun `an engine reporting success while the box printed an error still fails`() {
        // The whole point of routing output through the trace: this is the case an engine's own
        // boolean cannot see, and the one where inventing a receipt would do real damage.
        val seen = mutableListOf<ExecutionReport>()
        val subject = box()
        val trace = ExecutionTrace.forEngine(subject, Execute(uid = "root", type = "all", line = 0, code = "boom()", singleLine = false, requestId = "req-2"), "run", receipts(seen))
        trace.reply(errorOutput(subject.id))
        trace.finish(true)
        assertEquals(1, seen.size)
        assertTrue(!seen[0].success, "an error output must outrank the engine's optimism")
        assertEquals("failed", seen[0].stage)
        assertTrue(seen[0].error.contains("NameError"), seen[0].error)
    }

    @Test
    fun `a caller that did not ask for a receipt is not sent one`() {
        // Ordinary frontend execution sends no request id. It must keep its existing dispatch and
        // must not start receiving execution-verdict traffic it never asked for.
        val seen = mutableListOf<ExecutionReport>()
        val subject = box()
        val trace = ExecutionTrace.forEngine(subject, Execute(uid = "root", type = "all", line = 0, code = "1 + 1", singleLine = false, requestId = ""), "run", receipts(seen))
        trace.reply(errorOutput(subject.id))
        trace.finish(true)
        assertTrue(seen.isEmpty(), "an unawaited execution must stay silent, got ${seen.size} receipt(s)")
    }

    @Test
    fun `output still reaches the caller through the trace`() {
        // The trace wraps reply; if it swallowed anything the box would go blank in the editor,
        // which is a far worse regression than the one it exists to catch.
        val forwarded = mutableListOf<Message>()
        val subject = box()
        val trace = ExecutionTrace.forEngine(subject, Execute(uid = "root", type = "all", line = 0, code = "x", singleLine = false, requestId = "req-3"), "run") { forwarded.add(it) }
        trace.reply(errorOutput(subject.id))
        assertTrue(forwarded.any { it.name == "output" }, "the engine's own output must still be delivered")
        trace.finish(false)
        assertTrue(forwarded.any { it.name == "execution-verdict" })
    }

    @Test
    fun `a run records a setup but a fragment does not`() {
        // ExecutionReports only files a success under "setup:" for a non-fragment mode, which is
        // why every engine's run and launch paths pass their own mode instead of reusing the
        // fragment path's. Getting this wrong makes a run look like it never set the box up.
        val runBox = box()
        ExecutionTrace.forEngine(runBox, Execute(uid = "root", type = "all", line = 0, code = "1", singleLine = false, requestId = "r"), "run", null).finish(true)
        assertNotNull(ExecutionReports.latest(runBox, "code", successful = true), "a run must register as a setup")

        val fragmentBox = box()
        ExecutionTrace.forEngine(fragmentBox, Execute(uid = "root", type = "all", line = 0, code = "1", singleLine = false, requestId = "f"), "fragment", null).finish(true)
        assertNull(
            ExecutionReports.latest(fragmentBox, "code", successful = true),
            "a fragment is not a setup, so it must not be filed as one"
        )
    }
}
