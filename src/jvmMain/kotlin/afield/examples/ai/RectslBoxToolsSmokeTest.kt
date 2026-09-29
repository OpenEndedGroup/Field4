package afield.examples.ai

import afield.Box
import afield.OperationTranscript
import afield.SavingContext
import afield.SessionInformation
import afield.ai.core.BoxToolError
import afield.ai.core.BoxToolImages
import afield.ai.core.DocumentToolRegistry
import afield.ai.core.ToolExecutionContext
import afield.ai.core.ToolRegistry
import afield.ai.core.aiTool
import afield.ai.core.aiToolMapper
import afield.rectsl.RectAnimationOracle
import afield.rectsl.RectEscapeContext
import afield.rectsl.prewarmRectSLEscapes
import afield.thisSessionInfo
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import kotlin.system.exitProcess

/*
FIELD_TEST
id: ai.rectsl.tools
runner: jvm-main
entrypoint: afield.examples.ai.RectslBoxToolsSmokeTest
tags: [smoke, ai, tools, rectsl, regression]
timeoutSec: 240
expect:
  exitCode: 0
  stdoutContains:
    - "RectslBoxToolsSmokeTest ok"
*/
/**
 * Step-2 slice for box-authored AI tools: the RectSL studio tool set + the image-return and
 * box-state-escape primitives. Drives the REAL box path — a box whose `code` declares a ki-var
 * and calls `registerRectslStudioTools()` (unqualified, via the intro import) — then asserts,
 * without a live model and without requiring a GPU:
 *   - `rectsl.check` compiles good source and reports diagnostics for bad source;
 *   - a `{{ … }}` escape compiled against the box's engine reads the box's live ki-var (the
 *     "escapes reference box state" requirement) — prewarmed off the runloop, then invoked;
 *   - the image-return primitive marshals correctly (base64 kept out of the JSON output);
 *   - `rectsl.run` fails gracefully (a structured tool error) when no renderer is in context —
 *     the GPU happy-path reuses the gallery-proven RectSLRenderRunner and runs in a live document.
 *
 * Headless against a daemon runloop + fresh ephemeral session; exits naturally.
 */
object RectslBoxToolsSmokeTest {

    private val PROVIDER_CODE = """
        var boxScale = 3.0
        registerRectslStudioTools()
        println("rectsl tools ready; scale=" + boxScale)
    """.trimIndent()

    // Minimal well-formed RectSL whose square size comes from the box's ki-var via a {{ }} escape.
    private val ESCAPE_SOURCE = """
        ->wall 8 8 abstract
        ->duration 4
        ->fps 30
        "r" rect
          -2 0 ->xy
          {{ boxScale }} {{ boxScale }} ->wh
    """.trimIndent()

    // A current, well-formed RectSL program. (`GOOD_SOURCE`, which this
    // used when it was written in July, predates the `->` mutator spelling and no longer compiles.)
    private val GOOD_SOURCE = """
        #rectsl
        ->wall 8 8 abstract
        ->duration 4
        ->fps 30

        "sweep" rect
          0.7 2.4 ->wh
          '( 0=-2.6  4=2.6 ) ->x
    """.trimIndent()

    private fun buildTranscript(): String = """
name: rectsl_box_tools
description: A box registers the RectSL studio tools via registerRectslStudioTools().
operations:
  - create:
      box: rectsl_box
      name: RectSL Box
      properties:
        code: |
${PROVIDER_CODE.trimEnd().prependIndent("          ")}
  - execute:
      box: rectsl_box
      property: code
      type: fragment
  - wait:
      millis: 250
""".trimIndent()

    @JvmStatic
    fun main(args: Array<String>) {
        startRunloopDaemon()

        val document = freshSession("rectsl-box-tools")
        val docKey = document.absolutePath
        File(document, "macros").mkdirs()
        File(document, "macros/rectsl_box_tools.yaml").writeText(buildTranscript())

        val failures = mutableListOf<String>()
        fun check(cond: Boolean, message: String) {
            if (!cond) failures.add(message)
            println((if (cond) "ok   " else "FAIL ") + message)
        }

        try {
            val run = OperationTranscript.runMacro(document.absolutePath, "rectsl_box_tools")
            println("run.success=${run.success} applied=${run.applied} output lines=${run.output.size}")
            run.output.forEach { println("  [${it.kind}] ${it.uid}: ${stripHtml(it.html)}") }
            check(run.success, "transcript run succeeded")
            check(run.output.none { it.kind == "error" || it.kind == "err" }, "no error-kind box output")

            val checkTool = DocumentToolRegistry.get(docKey, "rectsl.check")
            check(checkTool != null, "rectsl.check registered via registerRectslStudioTools() in box code")
            check(DocumentToolRegistry.get(docKey, "rectsl.run") != null, "rectsl.run registered")
            if (checkTool == null) { report(failures); return }

            val box: Box = checkTool.box
            val ctx = ToolExecutionContext(dynamicTools = DocumentToolRegistry.toolsFor(docKey))
            val registry = ToolRegistry()

            // --- rectsl.check: good vs. bad source ---
            val good = registry.executeByName(
                ctx, "rectsl.check",
                aiToolMapper.writeValueAsString(mapOf("source" to GOOD_SOURCE))
            )
            val goodJson = aiToolMapper.writeValueAsString(good)
            check(goodJson.contains("compiles cleanly"),
                "rectsl.check accepts a valid program" + if (goodJson.contains("compiles cleanly")) "" else " (got $goodJson)")
            val bad = registry.executeByName(
                ctx, "rectsl.check",
                aiToolMapper.writeValueAsString(mapOf("source" to "arra 1 2 totally-not-rectsl"))
            )
            check(bad is BoxToolError && bad.messages.isNotEmpty(),
                "rectsl.check returns diagnostics for a bad program")

            // --- {{ }} escape reads the box's live ki-var (prewarmed off the runloop) ---
            val escapes = runBlocking {
                withContext(box.ki.compilationDispatcher) { prewarmRectSLEscapes(ESCAPE_SOURCE, box.ki) }
            }
            check(escapes.isNotEmpty(), "{{ boxScale }} escape compiled against the box's engine")
            val escapeValue = escapes.values.first().invoke(RectEscapeContext(0.0, 1.0, 0.0, 0.0, 0.0, 0.0))
            check((escapeValue as? Number)?.toDouble() == 3.0,
                "escape {{ boxScale }} reads the box's live ki-var (=3.0), got $escapeValue")

            // --- image-return primitive: base64 stays out of the JSON output ---
            box.aiTool("img.test") {
                describe("Return a tiny fixed image.")
                action { image("data:image/png;base64,QUJDREVG", "a test image") }
            }
            val imgCtx = ToolExecutionContext(dynamicTools = DocumentToolRegistry.toolsFor(docKey))
            val imgResult = registry.executeByName(imgCtx, "img.test", "{}")
            check(imgResult is BoxToolImages && imgResult.imageDataUrls.size == 1,
                "image() returns a BoxToolImages carrying the data URL")
            val imgJson = aiToolMapper.writeValueAsString(imgResult)
            check(imgJson.contains("\"count\":1") && !imgJson.contains("QUJDREVG"),
                "image data URL is JsonIgnored in the tool output (only count/note serialized)")

            // --- rectsl.run without a renderer: graceful structured error (GPU path runs in a live doc) ---
            val runResult = registry.executeByName(
                imgCtx, "rectsl.run",
                aiToolMapper.writeValueAsString(mapOf("source" to GOOD_SOURCE))
            )
            check(runResult is BoxToolError,
                "rectsl.run degrades to a tool error when no afield.four renderer is in context")
        } catch (t: Throwable) {
            failures.add("harness threw ${t.javaClass.simpleName}: ${t.message}")
            t.printStackTrace(System.err)
        } finally {
            thisSessionInfo.remove()
        }

        report(failures)
    }

    private fun report(failures: List<String>) {
        if (failures.isNotEmpty()) {
            System.err.println("RectslBoxToolsSmokeTest FAILED with ${failures.size} problem(s):")
            failures.forEach { System.err.println("  - $it") }
            exitProcess(1)
        }
        println("RectslBoxToolsSmokeTest ok")
        exitProcess(0)
    }

    private fun freshSession(name: String): File {
        thisSessionInfo.remove()
        val workspace = Files.createTempDirectory("afield-$name").toFile()
        workspace.deleteOnExit()
        val document = File(workspace, "doc").also { it.mkdirs() }
        File(document, SavingContext.METADATA_EDN).writeText("{:version 2 :boxes []}\n")
        val root = Box("root")
        val session = SessionInformation(
            filename = document.absolutePath,
            documentName = document.name,
            boxes = ConcurrentHashMap(),
            root = root,
            ephemeral = true,
        )
        root.inside = session
        session.boxes[root.id] = root
        thisSessionInfo.set(session)
        return document
    }

    private fun startRunloopDaemon() {
        val t = Thread({ afield.Runloop.runLoop.start() }, "rectsl-box-tools-harness-runloop")
        t.isDaemon = true
        t.start()
        val deadline = System.currentTimeMillis() + 30_000
        while (afield.Runloop.runLoop.frame < 1L) {
            check(System.currentTimeMillis() < deadline) { "runloop did not start within 30s" }
            Thread.sleep(20)
        }
        println("RectslBoxToolsSmokeTest: runloop up (frame=${afield.Runloop.runLoop.frame})")
    }

    private fun stripHtml(html: String): String =
        html.replace(Regex("<[^>]+>"), " ").replace("&lt;", "<").replace("&gt;", ">").trim()
}
