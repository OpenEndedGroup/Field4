package afield.ai.agent

import afield.Box
import afield.SessionInformation
import afield.ai.core.CarriesToolImages
import afield.ai.core.ScriptedLlmBridge
import afield.ai.core.ToolExecutionContext
import afield.ai.core.buildDynamicInstructions
import afield.ai.core.InstructionMode
import afield.ai.core.KOTLIN_SCRIPT
import afield.ai.agent.InFieldAgentHarnessTest.Companion.picture
import afield.ai.agent.InFieldAgentHarnessTest.Companion.visualTable
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * AI4 looking at its boxes' output ([ViewBoxOutputRequest], the picture checks) and learning from its
 * evals ([AgentLessons]) — without a browser and without a model. The live half (a real Playwright
 * capture, a real model turn) is in dev_docs/ai/AI4_SEES_ITS_OUTPUT.md.
 */
class AgentSeesItsOutputTest {

    private fun document(name: String = "sees-output-test"): SessionInformation {
        val dir = Files.createTempDirectory("ai4-sees-").toFile().apply { deleteOnExit() }
        val root = Box("root")
        root.name = "root"
        val info = SessionInformation(filename = dir.resolve(name).absolutePath, documentName = name, root = root, ephemeral = true)
        root.inside = info
        return info
    }

    private val host = InFieldAgentHarnessTest.FakeHost(visualTable)

    private fun runTask(request: AgentTaskRequest, info: SessionInformation = document()) =
        AgentTaskRunner.run(request, info, host, contextFor = { _, graph, _ -> ToolExecutionContext(fieldGraph = graph) })

    // ---- pictures --------------------------------------------------------------------------

    @Test
    fun pictureStatsMeasureInkAndColour() {
        val sun = visualTable.getValue(InFieldAgentEvals.SUN_CODE).picture!!()
        val stats = PictureStats.of(sun)
        // Premise first: the synthetic sun is mostly sky with some sun.
        assertEquals("#0b1a4a", stats.dominant.first().first)
        assertTrue(PictureStats.coverage(sun, "#0a1850") > 0.6, "sky coverage")
        val sunShare = PictureStats.coverage(sun, "#ffe000")
        // pi * 50^2 / (324 * 224) = 0.108
        assertTrue(sunShare in 0.09..0.12, "sun coverage $sunShare")
        val blank = PictureStats.of(picture(100, 50, "#ffffff")())
        assertEquals(0.0, blank.inkFraction)
        assertTrue(stats.inkFraction > 0.1)
        assertTrue(runCatching { PictureStats.parseHex("blue") }.isFailure)
    }

    @Test
    fun viewBoxOutputAttachesThePictureAndSaysWhatItMeasured() {
        val info = document()
        val tools = FieldGraphTools(info, host)
        tools.newBox(NewBoxRequest("Sun", code = InFieldAgentEvals.SUN_CODE))
        val view = tools.viewOutput(ViewBoxOutputRequest("Sun", note = "a sun"))
        assertTrue(view.success, view.message)
        assertTrue(view.imageAttached)
        val images = (view as CarriesToolImages).toolImages
        assertEquals(1, images.size)
        assertTrue(images.single().dataUrl.startsWith("data:image/jpeg;base64,"))
        assertTrue(images.single().caption.contains("a sun"))
        assertTrue(view.dominantColours.first().startsWith("#0b1a4a"), view.dominantColours.toString())
        // The picture is not in the JSON the model reads as text; its facts are.
        val json = afield.ai.core.aiToolMapper.writeValueAsString(view)
        assertFalse(json.contains("base64"))
        assertTrue(json.contains("\"imageAttached\":true"))

        tools.newBox(NewBoxRequest("Silent", code = "println(1)"))
        val none = tools.viewOutput(ViewBoxOutputRequest("Silent"))
        assertFalse(none.success)
        assertTrue((none as CarriesToolImages).toolImages.isEmpty())
    }

    @Test
    fun aBlankPictureIsCalledBlank() {
        val info = document()
        val tools = FieldGraphTools(info, host)
        tools.newBox(NewBoxRequest("Bars", code = InFieldAgentEvals.BARS_CODE))
        val view = tools.viewOutput(ViewBoxOutputRequest("Bars"))
        assertTrue(view.success)
        assertTrue(view.message.contains("BLANK"), view.message)
    }

    @Test
    fun viewBoxOutputIsOfferedWheneverTheGraphToolsAre() {
        assertTrue("ViewBoxOutputRequest" in fieldGraphToolNames)
        val bridge = ScriptedLlmBridge(listOf(ScriptedLlmBridge.Step.Final("x")))
        bridge.send(bridge.startThread("k", ""), "p", ctx = ToolExecutionContext(fieldGraph = FieldGraphTools(document(), host)), maxToolRounds = 1)
        assertTrue("ViewBoxOutputRequest" in bridge.received.single().availableTools)
    }

    @Test
    fun visualTasksPassWithTheirScriptsAndFailWithoutTheFix() {
        for (id in InFieldAgentEvals.visualTaskIds) {
            val task = InFieldAgentEvals.byId(id)
            val result = runTask(task.request.copy(mode = "scripted", script = task.solution))
            assertEquals("pass", result.verdict, "$id: " + result.checks.filter { !it.passed }.joinToString { "${it.check.kind}=${it.detail}" } + result.error)
            assertTrue(result.imagesSeen >= 1, "$id: the scripted solution looks at least once")
        }
        // Counterfactuals: looking without fixing leaves the bars invisible; a wrong colour name is wrong.
        val bars = InFieldAgentEvals.byId("bars-visible")
        val lookOnly = runTask(bars.request.copy(mode = "scripted", script = InFieldAgentEvals.steps(
            InFieldAgentEvals.call("ViewBoxOutputRequest", "box" to "Bars"),
            InFieldAgentEvals.call("RunBoxRequest", "box" to "Bars"), "done")))
        assertEquals("fail", lookOnly.verdict)
        val colourCheck = lookOnly.checks.single { it.check.kind == "output-colour" }
        assertFalse(colourCheck.passed)
        assertTrue(colourCheck.detail.startsWith("0.000"), colourCheck.detail)

        val swatch = InFieldAgentEvals.byId("swatch-colour")
        val table = visualTable + (InFieldAgentHarnessTest.Fake(out = listOf("red")).let { "println(\"red\")" to it })
        val guessed = AgentTaskRunner.run(swatch.request.copy(mode = "scripted", script = InFieldAgentEvals.steps(
            InFieldAgentEvals.call("NewBoxRequest", "name" to "Answer", "code" to "println(\"red\")"),
            InFieldAgentEvals.call("RunBoxRequest", "box" to "Answer"), "red")),
            document(), InFieldAgentHarnessTest.FakeHost(table), contextFor = { _, graph, _ -> ToolExecutionContext(fieldGraph = graph) })
        assertEquals("fail", guessed.verdict)
    }

    @Test
    fun pictureChecksParse() {
        assertEquals(AgentCheck("output-colour", box = "Sun", text = "#ffe000", fraction = 0.01), AgentCheck.parse("output-colour:Sun:#ffe000:0.01"))
        assertEquals(AgentCheck("output-picture", box = "Sun"), AgentCheck.parse("output-picture:Sun"))
        assertTrue(runCatching { AgentCheck.parse("output-colour:Sun:yellow:0.1") }.isFailure)
    }

    // ---- the fileRef lever -----------------------------------------------------------------

    @Test
    fun everyFileRefSpellingTheLiveModelGuessedReachesTheBox() {
        // As a headless task document is: *named* agent-task, stored (and referred to) by a uuid directory.
        val docRef = "5d6f0c1e-7a2b-4c3d-9e8f-0a1b2c3d4e5f"
        val dir = Files.createTempDirectory("ai4-ref-").toFile().apply { deleteOnExit() }
        val root = Box("root").also { it.name = "root" }
        val info = SessionInformation(filename = dir.resolve(docRef).absolutePath, documentName = "agent-task", root = root, ephemeral = true)
        root.inside = info
        val tools = FieldGraphTools(info, host)
        val uid = tools.newBox(NewBoxRequest("Answer", code = "println(1)")).uid
        fun resolve(raw: String) = afield.ai.core.resolveBackdoorFileRef(info, docRef, raw, "code")
        val canonical = afield.ai.core.FileRef(docRef, uid, "code")
        // Premise: the canonical spelling resolved before this change too.
        assertEquals(canonical, resolve("afield://$docRef/$uid/code"))
        // The guesses from the 2026-09-29/30 tool logs, every one of which used to fail.
        for (guess in listOf("afield://$uid/code", "afield://agent-task/$uid/code", "afield://scratch/$uid/code",
                "afield://$docRef/Answer/code", "afield://Answer/code", "Answer", uid)) {
            assertEquals(canonical, resolve(guess), guess)
        }
        // A box that is not here still fails, and names the document it looked in.
        assertEquals("elsewhere", resolve("afield://elsewhere/nobody/code").document)
    }

    // ---- lessons ---------------------------------------------------------------------------

    private val doc = "3f1c2b9a-1111-4222-8333-944455556666"
    private val box1 = "a5ef4e98-d101-4d5c-b2b3-1dfe7e530ead"

    private fun call(i: Int, tool: String, args: String, ok: Boolean, result: String) = AgentToolCallRecord(i, tool, args, ok, result)

    private fun result(id: String, calls: List<AgentToolCallRecord>) = AgentTaskResult(
        taskId = id, goal = "g", mode = "live", provider = "openai", model = "m", verdict = "pass", checks = emptyList(),
        toolCalls = calls, finalText = "", rounds = calls.size, maxToolRounds = 24, hitToolRoundCap = false, elapsedMs = 1,
        documentName = "scratch", documentRef = doc)

    /** The fileRef guess the live model made in every run on 2026-09-29, as the tool log recorded it. */
    private fun fileRefGuesses(id: String, box: String = box1) = result(id, listOf(
        call(1, "NewBoxRequest", """{"name":"T","code":"x"}""", true, """{"success":true,"uid":"$box"}"""),
        call(2, "CompileKotlinRequest", """{"file":"afield://$box/code","source":"x","runSetupBlocks":false}""", false,
            """{"success":false,"file":"afield://$box/code","message":"Unknown document '$box'.","diagnostics":[]}"""),
        call(3, "CompileKotlinRequest", """{"file":"afield://scratch/$box/code","source":"x","runSetupBlocks":false}""", false,
            """{"success":false,"file":"afield://scratch/$box/code","message":"Unknown document 'scratch'.","diagnostics":[]}"""),
        call(4, "CompileKotlinRequest", """{"file":"afield://$doc/$box/code","source":"x","runSetupBlocks":false}""", true,
            """{"success":true,"diagnostics":[]}"""),
        // The box's own compile error: the task, not a misuse.
        call(5, "RunBoxRequest", """{"box":"T","code":""}""", false, """{"success":false,"stage":"compile","error":"Unresolved reference"}"""),
    ))

    @Test
    fun misuseIsATooLMistakeNotTheBoxsOwnError() {
        val r = fileRefGuesses("a")
        assertEquals(listOf(false, true, true, false, false), r.toolCalls.map(AgentLessons::isMisuse))
        assertEquals(2, AgentLessons.misuseCount(r))
        val compileError = call(1, "CompileKotlinRequest", "{}", false, """{"success":false,"diagnostics":[{"severity":"ERROR","message":"x"}]}""")
        assertFalse(AgentLessons.isMisuse(compileError))
        val refused = call(1, "RunBoxRequest", "{}", false, """{"success":false,"message":"'T' has no code to run.","stage":""}""")
        assertTrue(AgentLessons.isMisuse(refused))
    }

    @Test
    fun distilTurnsRecurringMisuseIntoALessonWithTheArgumentThatWorked() {
        val second = "b1b1b1b1-2222-4333-8444-955566667777"
        val lessons = AgentLessons.distil(listOf(fileRefGuesses("primes"), fileRefGuesses("html-table", second)))
        // Premise: two tasks, one guess each of each spelling; each spelling is its own lesson.
        assertEquals(2, lessons.size, lessons.joinToString("\n") { it.bullet() })
        val unknownUid = lessons.single { it.message == "Unknown document '<uid>'." }
        assertEquals(2, unknownUid.count)
        assertEquals(listOf("primes", "html-table"), unknownUid.tasks)
        assertEquals("file `afield://<uid>/code` → `afield://<document>/<uid>/code`", unknownUid.change)
        assertTrue(unknownUid.bullet().startsWith("- `CompileKotlinRequest` failed 2 times in 2 tasks"))
        // A misuse seen once is not yet a lesson.
        assertTrue(AgentLessons.distil(listOf(fileRefGuesses("primes"))).isEmpty())
    }

    @Test
    fun theDocumentsNameIsNotMistakenForItsRef() {
        // A headless task document is *named* "scratch"; the model's guess afield://scratch/<uid>/code
        // is wrong and must stay literal. The first live distil substituted the name and reported the
        // right spelling as the failing one.
        val second = "b1b1b1b1-2222-4333-8444-955566667777"
        val lessons = AgentLessons.distil(listOf(fileRefGuesses("primes"), fileRefGuesses("html-table", second)))
        val scratchGuess = lessons.single { it.message == "Unknown document 'scratch'." }
        assertEquals("file `afield://scratch/<uid>/code` → `afield://<document>/<uid>/code`", scratchGuess.change)
        // An old report with no documentRef: a three-part ref's uuid first segment is the document.
        val old = listOf(fileRefGuesses("a"), fileRefGuesses("b", second)).map { it.copy(documentRef = "") }
        assertEquals("file `afield://<uid>/code` → `afield://<document>/<uid>/code`",
            AgentLessons.distil(old).single { it.message == "Unknown document '<uid>'." }.change)
    }

    @Test
    fun anIdenticalRepeatIsCalledOut() {
        val same = call(2, "PatchBoxTextRequest", """{"box":"T","find":"zz"}""", false, """{"success":false,"message":"`find` does not occur in code of 'T'."}""")
        val lessons = AgentLessons.distil(listOf(result("t", listOf(same, same.copy(index = 3), same.copy(index = 4)))))
        val lesson = lessons.single()
        assertEquals(3, lesson.count)
        assertEquals(2, lesson.repeatedUnchanged)
        assertTrue(lesson.bullet().contains("repeated unchanged 2 times"))
    }

    @Test
    fun renderKeepsCuratedBumpsTheVersionAndParsesBack() {
        val start = AgentLessons.DEFAULT_HEADER + "\n\nversion: 3\ndistilled: before\n\n## Curated\n\n- Keep me.\n\n## Distilled\n\n- old lesson\n"
        val lessons = AgentLessons.distil(listOf(fileRefGuesses("a"), fileRefGuesses("b")))
        val text = AgentLessons.render(start, lessons, "now")
        val parsed = AgentLessons.parse(text)
        assertEquals(4, parsed.version)
        assertEquals(listOf("- Keep me."), parsed.curated)
        assertEquals(lessons.map { it.bullet() }, parsed.distilled)
        assertEquals("now", parsed.provenance)
        assertFalse(text.contains("old lesson"))
        // Rendering twice is stable apart from the version.
        assertEquals(parsed.distilled, AgentLessons.parse(AgentLessons.render(text, lessons, "now")).distilled)
    }

    @Test
    fun lessonsReachTheInstructionsOnlyWhenOnAndCapped() {
        val file = Files.createTempFile("lessons", ".md").toFile().apply { deleteOnExit() }
        val many = (1..200).joinToString("\n") { "- lesson number $it about a tool that was misused in some way or other." }
        file.writeText(AgentLessons.DEFAULT_HEADER + "\n\nversion: 7\n\n## Curated\n\n- curated first\n\n## Distilled\n\n$many\n")
        val text = withLessonsFile(file) { AgentLessons.instructionText() }
        assertNotNull(text)
        assertTrue(text.length <= AgentLessons.MAX_INSTRUCTION_CHARS, "${text.length}")
        assertTrue(text.contains("v7"))
        assertTrue(text.lines()[1] == "- curated first", text.lines().take(3).toString())

        fun instructions(lessons: Boolean) = withLessonsFile(file) {
            buildDynamicInstructions(InstructionMode.Chat, KOTLIN_SCRIPT, "p", null, null,
                ToolExecutionContext(fieldGraph = FieldGraphTools(document(), host, lessons = lessons)))
        }
        assertTrue(instructions(true).contains("- curated first"))
        assertFalse(instructions(false).contains("- curated first"))
        assertTrue(instructions(false).contains("ViewBoxOutputRequest"))
        assertTrue(AgentLessons.resolve("off").not() && AgentLessons.resolve("on"))
        assertNull(AgentLessons.parse("no sections").curated.firstOrNull())
    }

    /** [AgentLessons.sourceFile] reads `AFIELD_AI4_LESSONS_FILE`; a test cannot set env, so it swaps the hook. */
    private fun <T> withLessonsFile(file: java.io.File, block: () -> T): T {
        val previous = AgentLessons.fileOverride
        AgentLessons.fileOverride = file
        try { return block() } finally { AgentLessons.fileOverride = previous }
    }
}
