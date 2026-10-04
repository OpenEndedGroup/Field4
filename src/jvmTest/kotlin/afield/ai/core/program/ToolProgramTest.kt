package afield.ai.core.program

import afield.ai.core.CarriesToolImages
import afield.ai.core.ToolExecutionContext
import afield.ai.core.ToolImage
import afield.ai.core.ToolRegistry
import afield.ai.core.aiTool
import afield.ai.core.aiToolMapper
import afield.ai.core.isAiTurnCancellation
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.AfterTest
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
data class ProgCheckRequest(val qualified: Boolean = true)
data class ProgCutRequest(val unused: Int = 0)
data class ProgBigRequest(val chars: Int = 16_000)
data class ProgWaitRequest(val waitSeconds: Double = 0.0)

data class ProgPicture(val note: String, override val toolImages: List<ToolImage>) : CarriesToolImages

/**
 * The tool-program sandbox and its bridge, against a registry of small fake tools: what a program can and
 * cannot reach, how each way of ending is reported, and that cancellation ends the turn rather than
 * becoming a tool error.
 */
class ToolProgramTest {

    private val slowSeen = AtomicReference<Double?>(null)
    private val waitSeen = AtomicReference<Double?>(null)
    private val cuts = java.util.concurrent.atomic.AtomicInteger()
    private val evidenceDir = java.nio.file.Files.createTempDirectory("tool-evidence").toFile()
    private val originalStore = ToolEvidence.store.also { ToolEvidence.store = ToolEvidence.FileStore(evidenceDir) }

    @AfterTest
    fun restore() {
        ToolEvidence.store = originalStore
        evidenceDir.deleteRecursively()
    }

    private val registry = ToolRegistry().also { tools ->
        tools.register(ProgEchoRequest::class.java) { _, r -> mapOf("echo" to r.text) }
        tools.register(ProgFailRequest::class.java) { _, _ -> mapOf("success" to false, "message" to "it did not work") }
        tools.register(ProgThrowRequest::class.java) { _, _ -> throw IllegalStateException("refused on purpose") }
        tools.register(ProgSlowRequest::class.java) { _, r -> slowSeen.set(r.timeoutSeconds); Thread.sleep(r.ms); mapOf("slept" to r.ms) }
        tools.register(ProgPictureRequest::class.java) { _, _ ->
            ProgPicture("a picture", listOf(ToolImage("data:image/png;base64,iVBORw0KGgo=", "caption")))
        }
        tools.register(ProgCancelRequest::class.java) { _, _ -> throw CancellationException("turn cancelled") }
        // A check that ran and either failed outright or returned a verdict, and the edit it guards.
        tools.register(ProgCheckRequest::class.java) { _, r ->
            if (r.qualified) mapOf("success" to true, "qualified" to false, "holds" to listOf("window W3 too close to the corner"))
            else mapOf("success" to false, "message" to "Qualification held")
        }
        tools.register(ProgCutRequest::class.java) { _, _ -> cuts.incrementAndGet(); mapOf("success" to true) }
        tools.register(ProgBigRequest::class.java) { _, r -> mapOf("success" to false, "message" to "big failure", "evidence" to "x".repeat(r.chars) + "THE END") }
        tools.register(ProgWaitRequest::class.java) { _, r -> waitSeen.set(r.waitSeconds); mapOf("job" to "job-1", "done" to false) }
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
    fun aRefusedCallAndAFailedResultBothThrowCatchably() {
        val result = run("""
            let refused = "", failed = null;
            try { tools.ProgThrow({}); } catch (e) { refused = e.name + ": " + e.message; }
            try { tools.ProgFail({}); } catch (e) { failed = {name: e.name, success: e.result.success, call: e.call}; }
            text("after the failures");
            return {refused, failed};
        """)
        assertEquals("ok", result.stage, result.message)
        assertTrue(result.returned!!["refused"].asText().let { it.startsWith("ToolFailure") && it.contains("refused on purpose") }, result.returned.toString())
        assertEquals("ToolFailure", result.returned["failed"]["name"].asText())
        assertFalse(result.returned["failed"]["success"].asBoolean())
        assertEquals(2, result.returned["failed"]["call"].asInt())
        assertEquals(listOf(false, false), result.calls.map { it.ok })
        assertEquals("it did not work", result.calls[1].error)
        assertEquals("after the failures\n", result.output)
    }

    // The review's probe (2026-10-03): a held check followed by a cut, caught by a try/catch meant to stop it.
    // The cut ran and the program reported success. A failed result now throws, so the cut cannot run.
    @Test
    fun aFailedCheckStopsTheEditAfterIt() {
        val caught = run("try { tools.ProgCheck({qualified: false}); tools.ProgCut({}); return 'cut'; } catch (e) { return 'held'; }")
        assertEquals("held", caught.returned!!.asText())
        val uncaught = run("tools.ProgCheck({qualified: false}); tools.ProgCut({}); return 'cut';")
        assertEquals("tool-failed", uncaught.stage)
        assertFalse(uncaught.success)
        assertTrue(uncaught.message.contains("Qualification held"), uncaught.message)
        assertEquals(0, cuts.get(), "no cut ran")
        assertEquals(1, uncaught.totalCalls)
    }

    @Test
    fun aVerdictIsNotAFailureButCheckStopsOnIt() {
        val result = run("const r = tools.ProgCheck({}); check(r.qualified, 'the openings are not qualified', r.holds); tools.ProgCut({});")
        assertEquals("check-failed", result.stage)
        assertTrue(result.message.contains("the openings are not qualified") && result.message.contains("W3"), result.message)
        assertEquals(listOf(true), result.calls.map { it.ok }, "the check itself ran and succeeded")
        assertEquals(0, cuts.get())
    }

    @Test
    fun rawReturnsFailuresWithoutThrowing() {
        val result = run("""
            const held = tools.ProgCheck.raw({qualified: false});
            const refused = tools.ProgThrow.raw({});
            return {held: held.message, refused: refused.error, refusedMessage: refused.message};
        """)
        assertEquals("ok", result.stage, result.message)
        assertEquals("Qualification held", result.returned!!["held"].asText())
        assertTrue(result.returned["refused"].asBoolean())
        assertTrue(result.returned["refusedMessage"].asText().contains("refused on purpose"))
        assertEquals(2, result.failedCalls)
    }

    @Test
    fun anUncaughtJsErrorIsARuntimeStage() {
        val result = run("null.x;")
        assertEquals("runtime", result.stage)
        val failed = run("tools.ProgThrow({});")
        assertEquals("tool-failed", failed.stage)
        assertTrue(failed.message.contains("refused on purpose"), failed.message)
    }

    // The review's probe: a 16,000-character result was cut to 8,000 characters of invalid JSON, with
    // truncated=false and nothing to retrieve. It is now kept whole, and the transcript gets a valid stub.
    @Test
    fun aLargeNestedResultIsKeptWholeAndTheTranscriptSaysWhere() {
        val result = run("const r = tools.ProgBig.raw({}); return r.evidence.length;")
        assertEquals("ok", result.stage, result.message)
        assertEquals(16_007, result.returned!!.asInt(), "the program itself saw the whole result")
        assertTrue(result.truncated)
        assertEquals(1, result.nestedClipped)
        val nested = result.nestedCalls.single()
        val stub = aiToolMapper.readTree(nested.resultJson)
        assertTrue(stub["clipped"].asBoolean())
        assertEquals(nested.resultBytes, stub["bytes"].asInt())
        assertEquals(nested.evidence, result.calls.single().evidence)
        val whole = readToolEvidence(ReadToolEvidenceRequest(nested.evidence!!, limit = 64_000))
        assertTrue(whole.success && !whole.more, whole.message)
        assertTrue(whole.text.endsWith("THE END\"}"), whole.text.takeLast(40))
        assertEquals(nested.resultSha256, whole.sha256)
        assertEquals("big failure", aiToolMapper.readTree(whole.text)["message"].asText())
        val io = nestedToolIoEvents(1, "c", result).last().second
        assertEquals(nested.evidence, io["evidence"])
        assertTrue(nestedTraceMarkdown(result)!!.contains(nested.evidence!!))
    }

    @Test
    fun progressReachesThePersonWhileTheProgramRuns() {
        val seen = java.util.concurrent.CopyOnWriteArrayList<String>()
        val ctx = ToolExecutionContext(progress = { seen += it })
        val result = run("progress('Checking 2 window placements'); tools.ProgEcho({text: 'a'}); text('done'); return 1;", ctx)
        assertEquals("ok", result.stage, result.message)
        assertEquals(listOf("Checking 2 window placements", "Checking 2 window placements · ProgEcho (1)"), seen.toList())
        assertEquals("done\n", result.output, "text() still comes back to the model, not to the panel")
    }

    @Test
    fun aNestedWaitIsCutToTheTimeTheProgramHasLeft() {
        run("tools.ProgWait({waitSeconds: 900});", timeout = 5.0)
        val seen = assertNotNull(waitSeen.get())
        assertTrue(seen <= 5.0, "nested waitSeconds=$seen")
    }

    // The window the 2026-10-02 notes left open: an interrupt while the GraalJS context was being built
    // could be swallowed, and the program then ran to its own timeout.
    @Test
    fun anInterruptWhileTheContextIsBuiltCancelsTheProgram() {
        val original = ToolProgramRunner.contextFactory
        val building = java.util.concurrent.CountDownLatch(1)
        ToolProgramRunner.contextFactory = { building.countDown(); Thread.sleep(1_500); ToolProgramRunner.newContext() }
        try {
            val failure = AtomicReference<Throwable?>(null)
            val worker = thread { failure.set(runCatching { run("return 1;", timeout = 60.0) }.exceptionOrNull()) }
            assertTrue(building.await(10, java.util.concurrent.TimeUnit.SECONDS))
            val t0 = System.nanoTime()
            worker.interrupt()
            worker.join(10_000)
            assertFalse(worker.isAlive)
            assertTrue((System.nanoTime() - t0) / 1_000_000 < 1_000, "it ended at once, not when the build finished")
            assertTrue(isAiTurnCancellation(assertNotNull(failure.get())), "${failure.get()}")
        } finally {
            ToolProgramRunner.contextFactory = original
        }
    }

    @Test
    fun aBoxToolIsCallableByItsOwnNameAndItsWireName() {
        val box = afield.Box("program-alias")
        val tool = box.aiTool("house.inspect") { describe("fixture"); action { "inspected" } }
        try {
            val ctx = ToolExecutionContext(dynamicTools = listOf(tool))
            val result = run("return {dotted: typeof tools['house.inspect'], wire: typeof tools['${tool.wireName}'], raw: typeof tools['house.inspect'].raw};", ctx)
            assertEquals("function", result.returned!!["dotted"].asText())
            assertEquals("function", result.returned["wire"].asText())
            assertEquals("function", result.returned["raw"].asText())
        } finally { afield.ai.core.DocumentToolRegistry.clearBox(box) }
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
        val failure = AtomicReference<Throwable?>(null)
        val worker = thread {
            failure.set(runCatching { run("""for (let i = 0; i < 100; i++) tools.ProgSlow({ms: 50}); return "never";""", timeout = 60.0) }.exceptionOrNull())
        }
        // Interrupt once the program is running its calls. An interrupt that arrives while the GraalJS
        // context is still being built can be lost (the full suite's load made a fixed 300 ms sleep land
        // there), which is a limit of the sandbox, not of this test; the program's own timeout then bounds it.
        val deadline = System.currentTimeMillis() + 20_000
        while (slowSeen.get() == null && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertNotNull(slowSeen.get(), "the program never started")
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
        val result = run("""tools.ProgEcho({text: "one"}); tools.ProgFail.raw({}); return 2;""")
        val entries = nestedTranscriptEntries("call_7", result)
        assertEquals(listOf("call_7/1", "call_7/2"), entries.map { it.callId })
        assertEquals(listOf("ProgEchoRequest", "ProgFailRequest"), entries.map { it.name })
        assertTrue(entries.all { it.parentCallId == "call_7" && it.elapsedMs >= 0 })
        assertTrue(entries[0].resultJson.contains("\"echo\":\"one\""))
        assertEquals(4, nestedToolIoEvents(1, "call_7", result).size)
    }

    @Test
    fun theChatPanelGetsTheProgramsCallsAsATrace() {
        val result = run("""tools.ProgEcho({text: "one"}); try { tools.ProgThrow({}); } catch (e) {} return 1;""")
        val trace = nestedTraceMarkdown(result)!!
        val lines = trace.lines()
        assertTrue(lines[0].startsWith("- ✓ `ProgEcho` `{\"text\":\"one\"}` — "), trace)
        assertTrue(lines[1].startsWith("- ✗ `ProgThrow`") && lines[1].contains("refused on purpose"), trace)
        assertTrue(lines.last().startsWith("program ok: 2 call(s), 1 failed"), trace)
        assertEquals(null, nestedTraceMarkdown(mapOf("not" to "a program")))
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
