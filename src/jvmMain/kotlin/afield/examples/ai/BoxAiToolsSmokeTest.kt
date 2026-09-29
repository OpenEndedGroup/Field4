package afield.examples.ai

import afield.Box
import afield.BoxRunScopes
import afield.LifecycleLedger
import afield.ReleasePolicy
import afield.withCurrent
import afield.OperationTranscript
import afield.SavingContext
import afield.SessionInformation
import afield.ai.core.BoxTool
import afield.ai.core.DocumentToolRegistry
import afield.ai.core.ToolExecutionContext
import afield.ai.core.ToolRegistry
import afield.ai.core.aiTool
import afield.ai.core.aiToolMapper
import afield.thisSessionInfo
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import kotlin.system.exitProcess

/*
FIELD_TEST
id: ai.boxtools.mechanism
runner: jvm-main
entrypoint: afield.examples.ai.BoxAiToolsSmokeTest
tags: [smoke, ai, tools, regression]
timeoutSec: 180
expect:
  exitCode: 0
  stdoutContains:
    - "BoxAiToolsSmokeTest ok"
*/
/**
 * Mechanism slice for box-authored AI tools (the `aiTool` DSL, [DocumentToolRegistry], and
 * the [ToolExecutionContext.dynamicTools] wiring into [ToolRegistry]).
 *
 * It drives the REAL box-execution path (a transcript whose box `code` calls `aiTool { }`
 * unqualified — proving the `intro` import + REPL reflection all work), then asserts, without
 * any live model:
 *   - the tool is registered in the document's [DocumentToolRegistry];
 *   - its auto-derived JSON schema (name from the `by param` property, type from the generic,
 *     required vs. defaulted) is correct;
 *   - it surfaces on the provider-neutral MCP face via [ToolRegistry.describe];
 *   - the agent execution path [ToolRegistry.executeByName] marshals args in and the result out;
 *   - the returned handle is directly, `suspend`-invocable from Kotlin — positional
 *     (type-coerced) and named — the "just run it yourself" path;
 *   - arity and missing-required-argument errors are enforced.
 *
 * Runs headless against a daemon runloop and a fresh ephemeral session; exits naturally.
 */
object BoxAiToolsSmokeTest {

    // Box code that registers a tool the way a document author would. No `$` templates so the
    // YAML/Kotlin stays escape-free. `aiTool`, `param`, `action`, `thisBox` all resolve here.
    private val PROVIDER_CODE = """
        val echo = aiTool("echo.box") {
            describe("Echo text with the owning box id.")
            val text by param<String>("text to echo")
            val times by param<Int>("repeat count", default = 2)
            action { mapOf("boxId" to thisBox.id, "echo" to text().repeat(times())) }
        }
        println("tool registered: " + echo.name)
    """.trimIndent()

    private fun buildTranscript(): String = """
name: box_ai_tools_mechanism
description: A box registers an AI tool via the aiTool DSL.
operations:
  - create:
      box: tool_provider
      name: Tool Provider
      properties:
        code: |
${PROVIDER_CODE.trimEnd().prependIndent("          ")}
  - execute:
      box: tool_provider
      property: code
      type: fragment
  - wait:
      millis: 250
""".trimIndent()

    @JvmStatic
    fun main(args: Array<String>) {
        startRunloopDaemon()

        val document = freshSession("box-ai-tools")
        val docKey = document.absolutePath
        val macrosDir = File(document, "macros").also { it.mkdirs() }
        File(macrosDir, "box_ai_tools_mechanism.yaml").writeText(buildTranscript())

        val failures = mutableListOf<String>()
        fun check(cond: Boolean, message: String) {
            if (!cond) failures.add(message)
            println((if (cond) "ok   " else "FAIL ") + message)
        }

        try {
            val run = OperationTranscript.runMacro(document.absolutePath, "box_ai_tools_mechanism")
            println("run.success=${run.success} applied=${run.applied} output lines=${run.output.size}")
            run.output.forEach { println("  [${it.kind}] ${it.uid}: ${stripHtml(it.html)}") }
            check(run.success, "transcript run succeeded")
            check(run.output.none { it.kind == "error" || it.kind == "err" }, "no error-kind box output")

            // --- registration (via real box code) ---
            val tools = DocumentToolRegistry.toolsFor(docKey)
            check(tools.any { it.name == "echo.box" }, "echo.box registered in the document registry")
            val tool: BoxTool = DocumentToolRegistry.get(docKey, "echo.box")
                ?: run {
                    failures.add("echo.box missing from registry; aborting execution checks")
                    report(failures); return
                }

            // --- auto-derived schema ---
            val schema = tool.parametersSchema
            val props = schema["properties"]?.jsonObject
            check(props?.get("text")?.jsonObject?.get("type")?.jsonPrimitive?.content == "string",
                "param 'text' typed as string from the generic")
            check(props?.get("times")?.jsonObject?.get("type")?.jsonPrimitive?.content == "integer",
                "param 'times' typed as integer from the generic")
            val required = schema["required"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
            check("text" in required, "'text' (no default) is required")
            check("times" !in required, "'times' (has default) is not required")

            // --- MCP describe face ---
            val ctx = ToolExecutionContext(dynamicTools = tools)
            val registry = ToolRegistry()
            val described = registry.describe(ctx).map { it.jsonObject["name"]?.jsonPrimitive?.content }
            check("echo.box" in described, "echo.box appears in ToolRegistry.describe()")

            // --- agent execution path (JSON in, marshaled out) ---
            val agentResult = registry.executeByName(ctx, "echo.box", """{"text":"hi","times":3}""")
            val agentJson = aiToolMapper.writeValueAsString(agentResult)
            check(agentJson.contains("hihihi"), "executeByName marshaled args and repeated text (times=3)")
            check(agentJson.contains("boxId"), "executeByName result carries the box id field")

            val defaulted = registry.executeByName(ctx, "echo.box", """{"text":"hi"}""")
            check(aiToolMapper.writeValueAsString(defaulted).contains(""""echo":"hihi""""),
                "default (times=2) applied when omitted")

            // --- direct handle invocation (the 'just run it' path) ---
            val positional = runBlocking { tool("yo", 2) }
            check(mapValue(positional, "echo") == "yoyo", "positional invoke a(src, times) coerces and runs")

            val named = runBlocking { tool.call("text" to "hey") }
            check(mapValue(named, "echo") == "heyhey", "named invoke a.call(\"text\" to ...) uses the default times")

            // --- errors ---
            check(threw { runBlocking { tool("a", "b", "c") } }, "positional over-arity is rejected")
            check(threw { registry.executeByName(ctx, "echo.box", "{}") }, "missing required 'text' is rejected")

            // --- idempotency: re-registering the same name upserts (count stays 1) ---
            val session = thisSessionInfo.get() ?: error("session went away")
            val provider = session.boxes[tool.boxId] ?: session.root
            provider.aiTool("echo.box") { describe("v2"); val t by param<String>("t"); action { t() } }
            check(DocumentToolRegistry.toolsFor(docKey).count { it.name == "echo.box" } == 1,
                "re-registration is idempotent (upsert by name)")

            // --- lifecycle: each registration is a ledger ownership its run scope releases ---
            check(LifecycleLedger.entries().any {
                it.kind == DocumentToolRegistry.AI_TOOL_KIND && it.ownerId == provider.id && it.policy == ReleasePolicy.ACCUMULATING
            }, "the registration is an ACCUMULATING ai-tool ownership in the lifecycle ledger")
            val rerun = provider.runScopes.begin(BoxRunScopes.RUN_STRAND, "smoke-rerun")
            rerun.withCurrent {
                provider.aiTool("echo.box") { describe("v3"); val t by param<String>("t"); action { t() } }
            }
            provider.runScopes.supersede(rerun)
            check(DocumentToolRegistry.get(docKey, "echo.box")?.description == "v3",
                "a re-run that registers the tool again keeps it (the slot passes to the newer run)")
            val rerun2 = provider.runScopes.begin(BoxRunScopes.RUN_STRAND, "smoke-rerun-2")
            provider.runScopes.supersede(rerun2)
            check(DocumentToolRegistry.get(docKey, "echo.box") == null,
                "a re-run that no longer registers the tool drops it")
            rerun2.withCurrent {
                provider.aiTool("echo.box") { describe("v4"); val t by param<String>("t"); action { t() } }
            }
            provider.runScopes.stopAll()
            check(DocumentToolRegistry.get(docKey, "echo.box") != null, "a stop leaves the tool answering")
            provider.runScopes.closeAll()
            check(DocumentToolRegistry.get(docKey, "echo.box") == null, "retiring the box releases its tools")
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
            System.err.println("BoxAiToolsSmokeTest FAILED with ${failures.size} problem(s):")
            failures.forEach { System.err.println("  - $it") }
            exitProcess(1)
        }
        println("BoxAiToolsSmokeTest ok")
        exitProcess(0)
    }

    private fun mapValue(result: Any?, key: String): Any? = (result as? Map<*, *>)?.get(key)

    private inline fun threw(block: () -> Unit): Boolean =
        try { block(); false } catch (t: Throwable) { true }

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
        val t = Thread({ afield.Runloop.runLoop.start() }, "box-ai-tools-harness-runloop")
        t.isDaemon = true
        t.start()
        val deadline = System.currentTimeMillis() + 30_000
        while (afield.Runloop.runLoop.frame < 1L) {
            check(System.currentTimeMillis() < deadline) { "runloop did not start within 30s" }
            Thread.sleep(20)
        }
        println("BoxAiToolsSmokeTest: runloop up (frame=${afield.Runloop.runLoop.frame})")
    }

    private fun stripHtml(html: String): String =
        html.replace(Regex("<[^>]+>"), " ").replace("&lt;", "<").replace("&gt;", ">").trim()
}
