package afield.ai.agent

import afield.Box
import afield.BoxOutputLog
import afield.Execute
import afield.ExecutionTrace
import afield.KotlinExecution
import afield.Message
import afield.Output
import afield.Rect
import afield.SessionInformation
import afield.actionTable
import afield.ai.core.ScriptedLlmBridge
import afield.ai.core.ToolExecutionContext
import afield.createAgentBox
import afield.dispatchExecutionOutput
import afield.payload
import afield.Set_output
import afield.thisSessionInfo
import kotlinx.coroutines.Job
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The in-Field agent harness without a model and without the K2 REPL.
 *
 * What is real here: the tool registry and dispatch, [FieldGraphTools], [ScriptedLlmBridge],
 * [AgentTaskRunner] and its checks, [BoxOutputLog] fed through the real [dispatchExecutionOutput] and
 * [ExecutionTrace], and the box graph ([createAgentBox], the actionTable's connect/delete).
 *
 * What is fake: execution. [FakeHost] "runs" a box by looking its code up in a table that says what
 * that code prints — it does not interpret Kotlin, and says so by failing on any code it was not told
 * about. Real execution of the same scripted solutions is verified against a live Field through
 * `field_backdoor.py agent-eval --mode scripted` (see dev_docs/ai/IN_FIELD_AGENT_HARNESS.md).
 */
class InFieldAgentHarnessTest {

    /** What a piece of code does when the fake host runs it. */
    data class Fake(
        val out: List<String> = emptyList(),
        val display: Map<String, String> = emptyMap(),
        val error: String? = null,
        val keepsRunning: Boolean = false,
        /** What its output area looks like, for ViewBoxOutputRequest and the picture checks. */
        val picture: (() -> java.awt.image.BufferedImage)? = null,
    )

    class FakeHost(private val table: Map<String, Fake>) : FieldGraphHost {
        override fun <T> onOwner(info: SessionInformation, action: () -> T): T {
            val previous = thisSessionInfo.get()
            thisSessionInfo.set(info)
            try { return action() } finally { if (previous == null) thisSessionInfo.remove() else thisSessionInfo.set(previous) }
        }

        override fun newBox(info: SessionInformation, name: String, frame: Rect, properties: Map<String, String>): Box =
            createAgentBox(info, name, frame, properties.mapValues { JsonPrimitive(it.value) }, initKi = false)

        override fun delete(info: SessionInformation, box: Box) { actionTable["-delete-box"]!!.invoke(listOf(box)) }

        private fun execute(box: Box, exec: Execute, reply: (Message) -> Unit, mode: String) {
            thread(isDaemon = true) {
                val trace = ExecutionTrace(box, exec.property, exec.code, exec.requestId, mode, reply)
                val fake = table[exec.code.trim()] ?: Fake(error = "fake host: no behaviour recorded for this code")
                fake.out.forEach { line ->
                    dispatchExecutionOutput(box, trace.reply, Output(uid = box.id, line = 1, html = line + "\n", kind = "normal", frame = 0), null)
                }
                fake.display.forEach { (channel, html) ->
                    BoxOutputLog.observeBroadcast(Message("set-output").payload(Set_output(box.id, "outputarea", channel, html)))
                }
                if (fake.error != null) {
                    dispatchExecutionOutput(box, trace.reply, Output(uid = box.id, line = 1, html = fake.error, kind = "err", frame = 0), null)
                }
                if (fake.keepsRunning && fake.error == null) box.jobs.add(Job())
                trace.finish(fake.error == null)
            }
        }

        override fun run(info: SessionInformation, box: Box, exec: Execute, reply: (Message) -> Unit) = execute(box, exec, reply, "run")
        override fun launch(info: SessionInformation, box: Box, exec: Execute, reply: (Message) -> Unit) = execute(box, exec, reply, "launch")
        override fun stop(info: SessionInformation, box: Box) = KotlinExecution().stop(box)

        /** The picture recorded for the box's current code; none recorded = "no output panel". */
        override fun captureOutput(info: SessionInformation, box: Box, settleMs: Int): BoxOutputShot {
            val code = (box.getPropertyRaw("code") as? String).orEmpty().trim()
            val picture = table[code]?.picture ?: return BoxOutputShot.failed("fake host: no picture recorded for '${box.name}'")
            return BoxOutputShot(true, "captured", picture(), channels = listOf("main"))
        }
    }

    companion object {
        /** A [w]x[h] picture filled with [bg], with [rects] (x, y, w, h, #rrggbb) and [circles] (cx, cy, r, #rrggbb) drawn on it. */
        fun picture(w: Int, h: Int, bg: String, rects: List<List<Any>> = emptyList(), circles: List<List<Any>> = emptyList()) = {
            val image = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB)
            val g = image.createGraphics()
            fun colour(hex: String) = PictureStats.parseHex(hex).let { java.awt.Color(it[0], it[1], it[2]) }
            g.color = colour(bg); g.fillRect(0, 0, w, h)
            rects.forEach { (x, y, rw, rh, c) -> g.color = colour(c as String); g.fillRect(x as Int, y as Int, rw as Int, rh as Int) }
            circles.forEach { (cx, cy, r, c) -> g.color = colour(c as String); g.fillOval((cx as Int) - (r as Int), (cy as Int) - r, 2 * r, 2 * r) }
            g.dispose()
            image
        }

        /** Bars of [heights] left to right, 24 px wide, on white; the one at [redAt] red. */
        fun chart(heights: List<Int>, redAt: Int = -1) = picture(160, 130, "#ffffff",
            rects = heights.mapIndexed { i, h -> listOf(10 + i * 30, 118 - h, 24, h, if (i == redAt) "#d62728" else "#1f77b4") })

        /** What the visual tasks' code looks like in real Field — recorded here, captured live elsewhere. */
        val visualTable: Map<String, Fake> = mapOf(
            InFieldAgentEvals.ROW_CODE to Fake(display = mapOf("main" to "<div></div>"), picture = chart(listOf(20, 30, 40, 50, 60))),
            // Wrapped: the second row sits under the first, so the columns hold both rows' bars.
            InFieldAgentEvals.ROW_FIRST_TRY to Fake(display = mapOf("main" to "<div></div>"),
                picture = picture(170, 260, "#ffffff", rects = (0 until 5).flatMap { i -> listOf(
                    listOf(10 + i * 30, 100 - (20 + i * 10), 24, 20 + i * 10, "#1f77b4"),
                    listOf(10 + i * 30, 250 - (70 + i * 10), 24, 70 + i * 10, "#1f77b4")) })),
            InFieldAgentEvals.ROW_FIXED to Fake(display = mapOf("main" to "<div></div>"),
                picture = picture(320, 130, "#ffffff", rects = (0 until 10).map { i -> listOf(10 + i * 30, 118 - (20 + i * 10), 24, 20 + i * 10, "#1f77b4") })),
            InFieldAgentEvals.THEME_CODE to Fake(display = mapOf("main" to "<style></style>")),
            // Theme's row-reverse draws the list back to front.
            InFieldAgentEvals.CHART_CODE to Fake(display = mapOf("main" to "<div></div>"), picture = chart(listOf(70, 40, 90, 25, 55))),
            InFieldAgentEvals.CHART_FIRST_TRY to Fake(display = mapOf("main" to "<div></div>"), picture = chart(listOf(90, 70, 55, 40, 25), redAt = 0)),
            InFieldAgentEvals.CHART_FIXED to Fake(display = mapOf("main" to "<div></div>"), picture = chart(listOf(25, 40, 55, 70, 90), redAt = 4)),
            InFieldAgentEvals.SWATCH_CODE to Fake(display = mapOf("main" to "<div id=sw></div>"),
                picture = picture(264, 144, "#ffffff", rects = listOf(listOf(12, 12, 240, 120, "#1f77b4")))),
            InFieldAgentEvals.ANSWER_CODE to Fake(out = listOf("blue")),
            InFieldAgentEvals.SUN_CODE to Fake(display = mapOf("main" to "<svg></svg>"),
                picture = picture(324, 224, "#f4f4f4", rects = listOf(listOf(12, 12, 300, 200, "#0b1a4a")),
                    circles = listOf(listOf(162, 112, 50, "#ffd700")))),
            // The broken bars are zero pixels tall: a white panel.
            InFieldAgentEvals.BARS_CODE to Fake(display = mapOf("main" to "<div></div>"), picture = picture(220, 40, "#ffffff")),
            InFieldAgentEvals.BARS_FIXED to Fake(display = mapOf("main" to "<div></div>"),
                picture = picture(220, 140, "#ffffff", rects = (0 until 5).map { i -> listOf(12 + i * 38, 128 - (i + 1) * 20, 30, (i + 1) * 20, "#1f77b4") })),
        )
    }

    /** What each eval solution's code prints in real Field — recorded here, checked live elsewhere. */
    private val evalTable = mapOf(
        InFieldAgentEvals.PRIMES_CODE to Fake(out = listOf("[2, 3, 5, 7, 11, 13, 17, 19, 23, 29]")),
        InFieldAgentEvals.BROKEN_CODE to Fake(error = "Type mismatch: inferred type is String but Int was expected"),
        InFieldAgentEvals.FIXED_CODE to Fake(out = listOf("count is 3")),
        InFieldAgentEvals.SOURCE_CODE to Fake(),
        InFieldAgentEvals.READER_CODE to Fake(out = listOf("4217")),
        InFieldAgentEvals.TABLE_CODE to Fake(display = mapOf("main" to
            "<table><tr><td>1</td><td>1</td></tr><tr><td>2</td><td>4</td></tr><tr><td>3</td><td>9</td></tr></table>")),
        InFieldAgentEvals.TICKER_CODE to Fake(keepsRunning = true),
        InFieldAgentEvals.DIVIDER_CODE to Fake(error = "java.util.NoSuchElementException: List is empty."),
        InFieldAgentEvals.DIVIDER_FIXED to Fake(out = listOf("empty")),
    ) + visualTable

    private fun document(name: String = "harness-test"): SessionInformation {
        val dir = Files.createTempDirectory("in-field-agent-").toFile().apply { deleteOnExit() }
        val root = Box("root")
        root.name = "root"
        val info = SessionInformation(filename = dir.resolve(name).absolutePath, documentName = name, root = root, ephemeral = true)
        root.inside = info
        return info
    }

    private fun runTask(request: AgentTaskRequest, host: FieldGraphHost = FakeHost(evalTable), info: SessionInformation = document()) =
        AgentTaskRunner.run(request, info, host, contextFor = { _, graph, _ -> ToolExecutionContext(fieldGraph = graph) })

    // ---- the graph tools -------------------------------------------------------------------

    @Test
    fun graphToolsBuildAndReadTheBoxGraph() {
        val info = document()
        val tools = FieldGraphTools(info, FakeHost(evalTable))
        val alpha = tools.newBox(NewBoxRequest("Alpha"))
        val beta = tools.newBox(NewBoxRequest("Beta", code = "println(1)", parent = "Alpha"))
        assertTrue(alpha.success && beta.success)
        assertTrue(info.boxes[beta.uid]!! in info.boxes[alpha.uid]!!.children, "parent= connects under the parent")
        assertEquals("println(1)", tools.getProperty(GetBoxPropertyRequest("Beta")).value)

        assertTrue(tools.setProperty(SetBoxPropertyRequest("Beta", value = "println(2)")).success)
        assertEquals("println(2)", info.boxes[beta.uid]!!.getPropertyRaw("code"))

        assertTrue(tools.disconnect(DisconnectBoxesRequest("Alpha", "Beta")).success)
        assertFalse(info.boxes[beta.uid]!! in info.boxes[alpha.uid]!!.children)
        assertTrue(tools.connect(ConnectBoxesRequest("Alpha", "Beta")).success)

        val listed = tools.listBoxes()
        assertEquals(listOf("Alpha", "Beta"), listed.boxes.map { it.name })
        assertEquals(listOf("Alpha"), listed.boxes[1].parents)

        assertTrue(tools.deleteBox(DeleteBoxRequest("Alpha")).success)
        assertEquals(listOf("Beta"), tools.listBoxes().boxes.map { it.name })
    }

    @Test
    fun ambiguousAndUnknownBoxNamesAreRefusedNotGuessed() {
        val info = document()
        val tools = FieldGraphTools(info, FakeHost(evalTable))
        tools.newBox(NewBoxRequest("Twin"))
        tools.newBox(NewBoxRequest("Twin"))
        val ambiguous = runCatching { tools.resolve("Twin") }.exceptionOrNull()
        assertNotNull(ambiguous)
        val unknown = runCatching { tools.resolve("Nobody") }.exceptionOrNull()
        assertTrue(unknown?.message.orEmpty().contains("No box 'Nobody'"))
    }

    @Test
    fun aFailingRunIsReportedAsAFailureWithItsDiagnostic() {
        val tools = FieldGraphTools(document(), FakeHost(evalTable))
        tools.newBox(NewBoxRequest("Broken", code = InFieldAgentEvals.BROKEN_CODE))
        val result = tools.run(RunBoxRequest("Broken", timeoutSeconds = 10.0))
        assertFalse(result.success, "a compile error must never come back as ok")
        assertEquals("failed", result.stage)
        assertTrue(result.errors.single().contains("Type mismatch"))
        assertTrue(result.error.contains("Type mismatch"), "the verdict carries the diagnostic too")
    }

    @Test
    fun aSucceedingRunReturnsWhatItPrinted() {
        val tools = FieldGraphTools(document(), FakeHost(evalTable))
        tools.newBox(NewBoxRequest("Primes"))
        val result = tools.run(RunBoxRequest("Primes", code = InFieldAgentEvals.PRIMES_CODE, timeoutSeconds = 10.0))
        assertTrue(result.success)
        assertEquals("setup-complete", result.stage)
        assertEquals(listOf("[2, 3, 5, 7, 11, 13, 17, 19, 23, 29]"), result.stdout)
        val read = tools.readOutput(ReadBoxOutputRequest("Primes", since = 0))
        assertEquals(listOf("out", "verdict"), read.entries.map { it.kind })
        val after = tools.readOutput(ReadBoxOutputRequest("Primes", since = read.outputMarker))
        assertTrue(after.entries.isEmpty(), "reading from the returned marker returns only newer entries")
    }

    @Test
    fun blankCodeMeansRunWhatIsThereNotEraseIt() {
        val info = document()
        val tools = FieldGraphTools(info, FakeHost(evalTable))
        val made = tools.newBox(NewBoxRequest("Primes", code = InFieldAgentEvals.PRIMES_CODE))
        val result = tools.run(RunBoxRequest("Primes", code = "", timeoutSeconds = 10.0))
        assertTrue(result.success, result.message)
        assertEquals(InFieldAgentEvals.PRIMES_CODE, info.boxes[made.uid]!!.getPropertyRaw("code"))
    }

    @Test
    fun aRunWithNoVerdictTimesOutInsteadOfClaimingSuccess() {
        val silent = object : FieldGraphHost by FakeHost(evalTable) {
            override fun run(info: SessionInformation, box: Box, exec: Execute, reply: (Message) -> Unit) {}
        }
        val tools = FieldGraphTools(document(), silent)
        tools.newBox(NewBoxRequest("Stuck", code = "while (true) {}"))
        val result = tools.run(RunBoxRequest("Stuck", timeoutSeconds = 0.5))
        assertFalse(result.success)
        assertTrue(result.timedOut)
        assertEquals("timeout", result.stage)
    }

    @Test
    fun readBoxOutputWaitsForText() {
        val info = document()
        val tools = FieldGraphTools(info, FakeHost(evalTable))
        val box = tools.newBox(NewBoxRequest("Later"))
        val marker = BoxOutputLog.mark()
        thread(isDaemon = true) {
            Thread.sleep(200)
            BoxOutputLog.record(box.uid, "out", "arrived")
        }
        val found = tools.readOutput(ReadBoxOutputRequest("Later", since = marker, contains = "arrived", waitSeconds = 5.0))
        assertTrue(found.success && found.found == true)
        val missing = tools.readOutput(ReadBoxOutputRequest("Later", since = found.outputMarker, contains = "never", waitSeconds = 0.2))
        assertFalse(missing.success)
        assertTrue(missing.timedOut)
    }

    @Test
    fun launchAndStopReportRunningState() {
        val tools = FieldGraphTools(document(), FakeHost(evalTable))
        tools.newBox(NewBoxRequest("Ticker", code = InFieldAgentEvals.TICKER_CODE))
        val launched = tools.launch(LaunchBoxRequest("Ticker", settleSeconds = 0.0))
        assertTrue(launched.success)
        assertEquals(true, launched.running)
        val stopped = tools.stop(StopBoxRequest("Ticker"))
        assertTrue(stopped.success)
        assertEquals(false, stopped.running)
    }

    // ---- the output log --------------------------------------------------------------------

    @Test
    fun outputLogIsBoundedPerBox() {
        val uid = "bounded-" + System.nanoTime()
        val start = BoxOutputLog.mark()
        repeat(BoxOutputLog.maxEntriesPerBox + 50) { BoxOutputLog.record(uid, "out", "line $it") }
        val page = BoxOutputLog.since(uid, start)
        assertEquals(BoxOutputLog.maxEntriesPerBox, page.entries.size)
        assertTrue(page.dropped, "a reader that fell behind is told so")
        assertEquals("line ${BoxOutputLog.maxEntriesPerBox + 49}", page.entries.last().text)
        BoxOutputLog.record(uid, "out", "x".repeat(BoxOutputLog.maxTextChars + 100))
        assertTrue(BoxOutputLog.since(uid, page.marker).entries.single().text.length <= BoxOutputLog.maxTextChars + 1)
    }

    // ---- the scripted bridge ---------------------------------------------------------------

    @Test
    fun scriptedBridgeSubstitutesEarlierResultsAndRecordsWhatItWasGiven() {
        val info = document()
        val graph = FieldGraphTools(info, FakeHost(evalTable))
        val ctx = ToolExecutionContext(fieldGraph = graph)
        val bridge = ScriptedLlmBridge(ScriptedLlmBridge.parse(InFieldAgentEvals.steps(
            InFieldAgentEvals.call("NewBoxRequest", "name" to "Made"),
            InFieldAgentEvals.call("SetBoxPropertyRequest", "box" to "\${NewBoxRequest.uid}", "value" to "hello"),
            "done"
        )))
        val sent = bridge.send(bridge.startThread("k", "", "INSTR"), "PROMPT", ctx = ctx, maxToolRounds = 5)
        assertEquals("done", sent.text)
        assertEquals(2, sent.rounds)
        assertFalse(sent.hitToolRoundCap)
        val made = info.boxes.values.single { it.name == "Made" }
        assertEquals("hello", made.getPropertyRaw("code"), "the uid placeholder resolved to the new box")
        val received = bridge.received.single()
        assertEquals("INSTR", received.instructions)
        assertEquals("PROMPT", received.prompt)
        assertTrue(received.availableTools.containsAll(fieldGraphToolNames), "every graph tool is offered when fieldGraph is wired")
        assertEquals("scripted", sent.modelIdentity?.provider)
    }

    @Test
    fun graphToolsAreHiddenWithoutAFieldGraph() {
        val bridge = ScriptedLlmBridge(listOf(ScriptedLlmBridge.Step.Final("x")))
        bridge.send(bridge.startThread("k", ""), "p", ctx = ToolExecutionContext(), maxToolRounds = 1)
        assertTrue(bridge.received.single().availableTools.none { it in fieldGraphToolNames })
    }

    @Test
    fun scriptedBridgeStopsAtTheRoundCap() {
        val bridge = ScriptedLlmBridge(List(5) { ScriptedLlmBridge.Step.Call("ListBoxesRequest") })
        val sent = bridge.send(bridge.startThread("k", ""), "p",
            ctx = ToolExecutionContext(fieldGraph = FieldGraphTools(document(), FakeHost(evalTable))), maxToolRounds = 3)
        assertEquals(3, sent.rounds)
        assertTrue(sent.hitToolRoundCap)
    }

    // ---- tasks and the eval suite ----------------------------------------------------------

    @Test
    fun everyEvalTaskPassesWithItsScriptedSolution() {
        val report = AgentEvalRunner.run(AgentEvalRequest(mode = "scripted")) { request -> runTask(request) }
        val failures = report.results.filter { it.verdict != "pass" }.map { r ->
            "${r.taskId}: ${r.verdict} ${r.error} " + r.checks.filter { !it.passed }.joinToString { "${it.check.kind}=${it.detail}" }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
        assertEquals(InFieldAgentEvals.tasks.size, report.population)
        assertEquals(report.population, report.passed)
    }

    @Test
    fun aWrongSolutionFailsItsChecks() {
        val task = InFieldAgentEvals.byId("fix-compile-error")
        // "Fixes" nothing, then claims success in its final text. The checks read the document instead.
        val result = runTask(task.request.copy(mode = "scripted", script = InFieldAgentEvals.steps(
            InFieldAgentEvals.call("RunBoxRequest", "box" to "Broken"), "Fixed it!")))
        assertEquals("fail", result.verdict)
        assertTrue(result.checks.any { !it.passed && it.check.kind == "last-run-ok" })
        assertFalse(result.toolCalls.single().ok, "the failing run is marked not-ok in the tool log")
        assertEquals("Fixed it!", result.finalText)
    }

    @Test
    fun setupOutputDoesNotSatisfyAnOutputCheck() {
        // The setup prints the answer; the agent does nothing. Output checks only see the agent's turn.
        val result = runTask(AgentTaskRequest(
            goal = "print count is 3",
            setup = InFieldAgentEvals.steps(InFieldAgentEvals.call("NewBoxRequest", "name" to "B", "code" to InFieldAgentEvals.FIXED_CODE),
                InFieldAgentEvals.call("RunBoxRequest", "box" to "B")),
            checks = listOf(AgentCheck.parse("output-contains:B:count is 3")),
            mode = "scripted", script = InFieldAgentEvals.steps("nothing to do")))
        assertEquals("fail", result.verdict)
        assertEquals(2, result.setupCalls.size)
    }

    @Test
    fun runningOutOfRoundsIsBudgetExhaustedNotFail() {
        val task = InFieldAgentEvals.byId("primes")
        val result = runTask(task.request.copy(mode = "scripted", maxToolRounds = 1, script = task.solution))
        assertEquals("budget-exhausted", result.verdict)
        assertTrue(result.hitToolRoundCap)
        assertEquals(1, result.rounds)
    }

    @Test
    fun checksParseFromTheCliSpelling() {
        assertEquals(AgentCheck("output-contains", box = "P", text = "a:b"), AgentCheck.parse("output-contains:P:a:b"))
        assertEquals(AgentCheck("connected", parent = "A", child = "B"), AgentCheck.parse("connected:A:B"))
        assertEquals(listOf(2L, 3L, 5L), AgentCheck.parse("output-ints-include:P:2,3, 5").ints)
        assertEquals(AgentCheck("property-lacks", box = "R", property = "code", text = "42"), AgentCheck.parse("property-lacks:R:code:42"))
        assertTrue(runCatching { AgentCheck.parse("nonsense:x") }.isFailure)
    }

    @Test
    fun aLiveEvalIsCapped() {
        val tooMany = AgentEvalRequest(ids = List(AgentEvalRunner.MAX_LIVE_TASKS + 1) { "primes" }, mode = "live")
        assertTrue(runCatching { AgentEvalRunner.run(tooMany) { error("must not run") } }.isFailure)
        var rounds = -1
        AgentEvalRunner.run(AgentEvalRequest(ids = listOf("primes"), mode = "live", maxToolRounds = 99)) { r ->
            rounds = r.maxToolRounds
            runTask(r.copy(mode = "scripted", script = InFieldAgentEvals.byId("primes").solution))
        }
        assertEquals(AgentEvalRunner.MAX_LIVE_ROUNDS, rounds)
    }
}
