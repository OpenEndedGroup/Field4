package afield.ai.core.program

import afield.ai.core.CarriesToolImages
import afield.ai.core.ToolExecutionContext
import afield.ai.core.ToolImage
import afield.ai.core.ToolRegistry
import afield.ai.core.aiToolMapper
import afield.ai.core.isAiTurnCancellation
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

data class ProgEchoRequest(val text: String = "")
data class ProgFailRequest(val unused: Int = 0)
data class ProgThrowRequest(val unused: Int = 0)
data class ProgSlowRequest(val ms: Long = 100, val timeoutSeconds: Double = 0.0)
data class ProgPictureRequest(val unused: Int = 0)
data class ProgCancelRequest(val unused: Int = 0)

data class ProgPicture(val note: String, override val toolImages: List<ToolImage>) : CarriesToolImages

/**
 * The tool-program sandbox and its bridge, against a registry of small fake tools: what a program can and
 * cannot reach, how each way of ending is reported, and that cancellation ends the turn rather than
 * becoming a tool error.
 */
class ToolProgramTest {

    private val slowSeen = AtomicReference<Double?>(null)

    private val registry = ToolRegistry().also { tools ->
        tools.register(ProgEchoRequest::class.java) { _, r -> mapOf("echo" to r.text) }
        tools.register(ProgFailRequest::class.java) { _, _ -> mapOf("success" to false, "message" to "it did not work") }
        tools.register(ProgThrowRequest::class.java) { _, _ -> throw IllegalStateException("refused on purpose") }
        tools.register(ProgSlowRequest::class.java) { _, r -> slowSeen.set(r.timeoutSeconds); Thread.sleep(r.ms); mapOf("slept" to r.ms) }
        tools.register(ProgPictureRequest::class.java) { _, _ ->
            ProgPicture("a picture", listOf(ToolImage("data:image/png;base64,iVBORw0KGgo=", "caption")))
        }
        tools.register(ProgCancelRequest::class.java) { _, _ -> throw CancellationException("turn cancelled") }
        registerToolProgramTools(tools)
    }

    private fun run(code: String, ctx: ToolExecutionContext = ToolExecutionContext(), timeout: Double = 20.0, maxCalls: Int = 40) =
        ToolProgramRunner(registry).run(ctx, RunToolProgramRequest(code, timeoutSeconds = timeout, maxCalls = maxCalls))

    @Test
    fun aProgramCannotReachJavaModulesTimersOrFiles() {
        val result = run("""
            const seen = {java: typeof Java, polyglot: typeof Polyglot, load: typeof load, require: typeof require,
                          setTimeout: typeof setTimeout, fetch: typeof fetch};
            let imported = "no";
            try { await import("/etc/hosts"); imported = "yes"; } catch (e) { imported = "refused"; }
            return {...seen, imported};
        """)
        assertEquals("ok", result.stage, result.message)
        val returned = result.returned!!
        listOf("java", "polyglot", "load", "require", "setTimeout", "fetch").forEach { assertEquals("undefined", returned[it].asText(), it) }
        assertEquals("refused", returned["imported"].asText())
    }

    @Test
    fun aRunawayLoopStopsAtTheStatementLimit() {
        val result = run("while (true) {}", timeout = 120.0)
        assertEquals("statement-limit", result.stage, result.message)
        assertFalse(result.success)
    }

    @Test
    fun awaitAndPlainCallsBothSettleInOneEval() {
        val result = run("""const a = await tools.ProgEcho({text: "a"}); return a.echo + tools.ProgEcho({text: "b"}).echo;""")
        assertEquals("ok", result.stage, result.message)
        assertEquals("ab", result.returned!!.asText())
        assertEquals(2, result.totalCalls)
    }

    @Test
    fun awaitingSomethingThatNeverResolvesIsUnsettled() {
        assertEquals("unsettled", run("await new Promise(() => {}); return 1;").stage)
    }

    @Test
    fun aSyntaxErrorReportsTheModelsOwnLineNumber() {
        val result = run("const a = 1;\nconst b = 2;\nreturn (;")
        assertEquals("syntax", result.stage)
        assertTrue(result.message.contains("line 3"), result.message)
    }

    @Test
    fun aRefusedCallThrowsCatchablyAndAFailedResultIsReturned() {
        val result = run("""
            let caught = "";
            try { tools.ProgThrow({}); } catch (e) { caught = e.message; }
            const failed = tools.ProgFail({});
            text("after the failures");
            return {caught, success: failed.success};
        """)
        assertEquals("ok", result.stage, result.message)
        assertTrue(result.returned!!["caught"].asText().contains("refused on purpose"))
        assertFalse(result.returned!!["success"].asBoolean())
        assertEquals(listOf(false, false), result.calls.map { it.ok })
        assertEquals(2, result.failedCalls)
        assertEquals("after the failures\n", result.output)
    }

    @Test
    fun anUncaughtThrowIsARuntimeStage() {
        val result = run("tools.ProgThrow({});")
        assertEquals("runtime", result.stage)
        assertTrue(result.message.contains("refused on purpose"), result.message)
    }

    @Test
    fun theCallCapStopsFurtherCalls() {
        val result = run("""for (let i = 0; i < 10; i++) { try { tools.ProgEcho({text: "" + i}); } catch (e) {} } return "done";""", maxCalls = 3)
        assertEquals("call-cap", result.stage)
        assertEquals(3, result.totalCalls)
        assertEquals("\"done\"", aiToolMapper.writeValueAsString(result.returned))
    }

    @Test
    fun aProgramCannotCallItselfCommentaryOrAWithheldTool() {
        val ctx = ToolExecutionContext(withheld = setOf("ProgEchoRequest"))
        val result = run("""return [typeof tools.RunToolProgram, typeof tools.RunToolProgramRequest, typeof tools.Commentary, typeof tools.ProgEcho, typeof tools.ProgFail];""", ctx)
        assertEquals(listOf("undefined", "undefined", "undefined", "undefined", "function"), result.returned!!.map { it.asText() })
    }

    @Test
    fun aWithheldProgramToolIsNotOfferedAtAll() {
        val ctx = ToolExecutionContext(withheld = setOf(RUN_TOOL_PROGRAM))
        assertFalse(registry.isAvailable(ctx, RUN_TOOL_PROGRAM))
        assertTrue(registry.describe(ctx).none { it.toString().contains(RUN_TOOL_PROGRAM) })
        assertTrue(registry.isAvailable(ToolExecutionContext(), RUN_TOOL_PROGRAM))
    }

    @Test
    fun aProgramPastItsTimeIsStoppedWithItsPartialOutput() {
        val result = run("""for (let i = 0; i < 50; i++) { text("tick " + i); tools.ProgSlow({ms: 100}); } return "never";""", timeout = 0.5)
        assertEquals("timeout", result.stage, result.message)
        assertTrue(result.output.startsWith("tick 0"))
        assertTrue(result.totalCalls in 1..10, "calls=${result.totalCalls}")
    }

    @Test
    fun aNestedTimeoutIsCutToTheTimeTheProgramHasLeft() {
        run("""tools.ProgSlow({ms: 1, timeoutSeconds: 999});""", timeout = 5.0)
        val seen = assertNotNull(slowSeen.get())
        assertTrue(seen <= 5.0, "nested timeoutSeconds=$seen")
    }

    @Test
    fun aCancellationInsideAToolEndsTheTurn() {
        val failure = runCatching { run("""try { tools.ProgCancel({}); } catch (e) {} return "carried on";""") }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(isAiTurnCancellation(failure), "$failure")
    }

    @Test
    fun interruptingTheTurnThreadCancelsTheProgram() {
        run("return 1;") // warm the shared engine, so the interrupt lands in the program and not in engine startup
        val failure = AtomicReference<Throwable?>(null)
        val worker = thread {
            failure.set(runCatching { run("""for (let i = 0; i < 100; i++) tools.ProgSlow({ms: 50}); return "never";""", timeout = 60.0) }.exceptionOrNull())
        }
        Thread.sleep(300)
        worker.interrupt()
        worker.join(10_000)
        assertFalse(worker.isAlive)
        val thrown = assertNotNull(failure.get())
        assertTrue(isAiTurnCancellation(thrown), "$thrown")
    }

    @Test
    fun showForwardsImagesWhichStayOutOfTheJson() {
        val result = run("""const p = tools.ProgPicture({}); show(p); return p.note;""")
        assertEquals("ok", result.stage, result.message)
        assertEquals(1, result.toolImages.size)
        assertEquals(1, result.imagesShown)
        assertFalse(aiToolMapper.writeValueAsString(result).contains("iVBORw0KGgo"))
    }

    @Test
    fun aHugeReturnValueIsTruncated() {
        val result = run("""return "x".repeat(50000);""")
        assertTrue(result.truncated)
        assertEquals(50002, result.returned!!["truncated"].asInt())
    }

    @Test
    fun nestedCallsBecomeChildTranscriptEntries() {
        val result = run("""tools.ProgEcho({text: "one"}); tools.ProgFail({}); return 2;""")
        val entries = nestedTranscriptEntries("call_7", result)
        assertEquals(listOf("call_7/1", "call_7/2"), entries.map { it.callId })
        assertEquals(listOf("ProgEchoRequest", "ProgFailRequest"), entries.map { it.name })
        assertTrue(entries.all { it.parentCallId == "call_7" && it.elapsedMs >= 0 })
        assertTrue(entries[0].resultJson.contains("\"echo\":\"one\""))
        assertEquals(4, nestedToolIoEvents(1, "call_7", result).size)
    }

    @Test
    fun jsNamesDropTheRequestSuffixUnlessThatCollides() {
        val names = ToolProgramRunner.jsNames(listOf("RunBoxRequest", "rectsl.run", "Thing", "ThingRequest"))
            .associate { it[0] to it[1] }
        assertEquals("RunBoxRequest", names["RunBox"])
        assertEquals("RunBoxRequest", names["RunBoxRequest"])
        assertEquals("rectsl.run", names["rectsl.run"])
        assertEquals("Thing", names["Thing"])
        assertEquals("ThingRequest", names["ThingRequest"])
    }

    @Test
    fun describeReturnsAToolsInputSchema() {
        val result = run("""const d = describe("ProgEcho"); return d.inputSchema.properties.text.type;""")
        assertEquals("string", result.returned!!.asText(), result.message)
    }
}
