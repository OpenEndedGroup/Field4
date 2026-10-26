package afield.ai.agent

import afield.ai.agent.InFieldAgentHarnessTest.Fake
import afield.ai.agent.InFieldAgentHarnessTest.FakeHost
import afield.ai.tools.ToolExecutionContext
import afield.app.server.SessionInformation
import afield.document.graph.Box
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The corpus miner without a live Field: static mining over a hand-written transcript, then calibration and the
 * mined task's own scripted run through the real [AgentTaskRunner], with execution faked by [FakeHost].
 */
class AgentEvalCorpusTest {

    private val transcript = """
        name: mini
        operations:
          - create:
              box: settings
              name: "Settings"
              properties:
                code: |
                  prop<Int>(size) = 7
          - create:
              box: report
              name: "A report"
              properties:
                code: |
                  /* Prints the size it inherits. Then a second sentence. And a third. */
                  println("size is " + size)
          - create:
              box: drawn
              properties:
                code: |
                  val m = Mesh(renderer)
                  println(m)
          - create:
              box: quiet
              properties:
                code: |
                  val x = 1 // mentions renderer only in a comment
          - create:
              box: orphan
              properties:
                code: println("orphan")
          - create:
              box: under_guide
              properties:
                code: println("hello")
          - create:
              box: shown
              properties:
                code: display("main", "<table><tr><td>squares table</td></tr></table>")
          - connect: { parent: settings, child: report }
          - connect: { parent: some_guide, child: under_guide }
          - execute: { box: settings, property: code }
          - execute: { box: report, property: code }
          - execute: { box: drawn, property: code }
          - execute: { box: quiet, property: code }
          - execute: { box: under_guide, property: code }
          - launch: { box: shown }
          - execute: { box: orphan, property: code, type: fragment }
    """.trimIndent()

    private fun document(): SessionInformation {
        val dir = Files.createTempDirectory("agent-eval-corpus-").toFile().apply { deleteOnExit() }
        val root = Box("root").apply { name = "root" }
        return SessionInformation(filename = dir.resolve("corpus-test").absolutePath, documentName = "corpus-test", root = root, ephemeral = true)
            .also { root.inside = it }
    }

    private fun run(request: AgentTaskRequest, table: Map<String, Fake>) =
        AgentTaskRunner.run(request, document(), FakeHost(table), contextFor = { _, graph, _ -> ToolExecutionContext(fieldGraph = graph) })

    @Test
    fun staticMiningKeepsRunnableBoxesAndSaysWhyItDropsTheRest() {
        val mined = AgentEvalCorpus.mineTranscript("mini", transcript)
        assertEquals(listOf("mini/report", "mini/shown"), mined.candidates.map { it.id })
        val reasons = mined.rejections.associate { it.box to it.reason }
        assertEquals("no-output-call", reasons["settings"])
        assertEquals("deny:renderer", reasons["drawn"])
        assertEquals("no-output-call", reasons["quiet"], "a comment mentioning the renderer does not deny a box")
        assertEquals("not-executed", reasons["orphan"], "a fragment execute does not make a box runnable")
        assertEquals("ancestor-unknown some_guide", reasons["under_guide"])

        val report = mined.candidates.first()
        assertEquals(listOf("settings"), report.parents)
        assertEquals(listOf("settings"), report.setup.map { it.box })
        assertEquals("Prints the size it inherits. Then a second sentence.", report.prose)
        assertEquals("launch", mined.candidates[1].verb)
    }

    @Test
    fun calibrationDerivesChecksTheSolutionThenPasses() {
        val report = AgentEvalCorpus.mineTranscript("mini", transcript).candidates.first { it.box == "report" }
        val table = mapOf(
            "prop<Int>(size) = 7" to Fake(),
            report.code.trim() to Fake(out = listOf("size is 7")),
        )
        val calibrated = AgentEvalCorpus.calibrate(report,
            run(AgentEvalCorpus.calibrationRequest(report), table), run(AgentEvalCorpus.calibrationRequest(report), table))
        val task = assertIs<AgentEvalCorpus.Accepted>(calibrated).task
        assertEquals("stdout", task.kind)
        assertEquals(listOf("box-exists", "last-run-ok", "connected", "output-contains"), task.request.checks.map { it.kind })
        assertEquals("size is 7", task.request.checks.last().text)
        assertTrue(task.request.goal.contains("child of settings") && task.request.goal.contains("«size is 7»"), task.request.goal)

        // The corpus is its own scripted floor: the mined solution passes the mined checks.
        val floor = run(task.request.copy(mode = "scripted", script = task.solution), table)
        assertEquals("pass", floor.verdict, floor.checks.joinToString { "${it.check.kind}: ${it.detail}" })

        // And the checks mean something: code that prints the wrong thing fails them.
        val wrong = InFieldAgentEvals.steps(
            InFieldAgentEvals.call("NewBoxRequest", "name" to "report", "code" to "println(0)", "parent" to "settings"),
            InFieldAgentEvals.call("RunBoxRequest", "box" to "report"), "done")
        assertEquals("fail", run(task.request.copy(mode = "scripted", script = wrong), table + mapOf("println(0)" to Fake(out = listOf("0")))).verdict)
    }

    @Test
    fun displayOnlyBoxesAreCheckedOnTheirTextAndUnstableOutputIsRefused() {
        val shown = AgentEvalCorpus.mineTranscript("mini", transcript).candidates.first { it.box == "shown" }
        val html = "<table><tr><td>squares table</td></tr></table>"
        val table = mapOf(shown.code.trim() to Fake(display = mapOf("main" to html), keepsRunning = true))
        val task = assertIs<AgentEvalCorpus.Accepted>(AgentEvalCorpus.calibrate(shown,
            run(AgentEvalCorpus.calibrationRequest(shown), table), run(AgentEvalCorpus.calibrationRequest(shown), table))).task
        assertEquals("display", task.kind)
        assertEquals("squares table", task.request.checks.last().text)

        val report = AgentEvalCorpus.mineTranscript("mini", transcript).candidates.first { it.box == "report" }
        val first = run(AgentEvalCorpus.calibrationRequest(report), mapOf("prop<Int>(size) = 7" to Fake(), report.code.trim() to Fake(out = listOf("took 12 ms"))))
        val second = run(AgentEvalCorpus.calibrationRequest(report), mapOf("prop<Int>(size) = 7" to Fake(), report.code.trim() to Fake(out = listOf("took 15 ms"))))
        val refused = assertIs<AgentEvalCorpus.Refused>(AgentEvalCorpus.calibrate(report, first, second))
        assertTrue(refused.rejection.reason.startsWith("no-stable-output"), refused.rejection.reason)

        val broken = run(AgentEvalCorpus.calibrationRequest(report), mapOf("prop<Int>(size) = 7" to Fake(), report.code.trim() to Fake(error = "Unresolved reference: size")))
        assertTrue(assertIs<AgentEvalCorpus.Refused>(AgentEvalCorpus.calibrate(report, broken, broken)).rejection.reason.startsWith("solution-fails"))
    }

    /** Box code full of Kotlin string templates (a template over `xs.size`) is not mistaken for a script placeholder. */
    @Test
    fun stringTemplatesInMinedCodeSurviveTheScript() {
        val code = "val xs = listOf(1, 2)\nprintln(\"n = ${'$'}{xs.size}\")"
        val c = AgentEvalCorpus.Candidate("t/tpl", "t", "tpl", "tpl", code, "run", emptyList(), emptyList(), "")
        val table = mapOf(code to Fake(out = listOf("n = 2")))
        val task = assertIs<AgentEvalCorpus.Accepted>(AgentEvalCorpus.calibrate(c,
            run(AgentEvalCorpus.calibrationRequest(c), table), run(AgentEvalCorpus.calibrationRequest(c), table))).task
        assertEquals("n = 2", task.request.checks.last().text)
    }

    @Test
    fun machineSpecificLinesAreNeverChecks() {
        assertTrue(AgentEvalCorpus.checkable("size is 7"))
        assertTrue(!AgentEvalCorpus.checkable("rig  /Users/marc/field4/build/rig.gltf"))
        assertTrue(!AgentEvalCorpus.checkable("box 62d3560c-8a9c-47c6-9135-3d9fed129e99 ran"))
        assertTrue(AgentEvalCorpus.checkable("ratio 1/2 of the area"))
    }

    @Test
    fun splitAndSampleAreDeterministic() {
        val tasks = (1..60).map { i ->
            val id = "t$i/box"
            AgentEvalCorpus.CorpusTask(id, "t$i", AgentEvalCorpus.splitOf(id), "stdout", AgentTaskRequest(goal = id), "[]", true)
        }
        val corpus = AgentEvalCorpus.Corpus(tasks = tasks)
        val held = AgentEvalCorpus.select(corpus, "heldout", 0, 0)
        val train = AgentEvalCorpus.select(corpus, "train", 0, 0)
        assertEquals(60, held.size + train.size)
        assertTrue(held.size in 10..30, "held out ${held.size} of 60")
        assertEquals(AgentEvalCorpus.select(corpus, "train", 10, 7).map { it.id }, AgentEvalCorpus.select(corpus, "train", 10, 7).map { it.id })
        assertTrue(AgentEvalCorpus.select(corpus, "train", 10, 7).all { it.split == "train" })
    }

    /** The checked-in corpus, once calibrated, parses and keeps the floor's shape: every task has a solution and an output check. */
    @Test
    fun theCheckedInCorpusIsWellFormed() {
        val corpus = AgentEvalCorpus.load()
        if (corpus.tasks.isEmpty()) return
        assertTrue(corpus.tasks.map { it.id }.toSet().size == corpus.tasks.size, "ids unique")
        corpus.tasks.forEach { t ->
            assertTrue(t.request.checks.any { it.kind == "output-contains" }, t.id)
            assertEquals(AgentEvalCorpus.splitOf(t.id), t.split, t.id)
            assertTrue(t.solution.contains("NewBoxRequest"), t.id)
        }
    }
}
