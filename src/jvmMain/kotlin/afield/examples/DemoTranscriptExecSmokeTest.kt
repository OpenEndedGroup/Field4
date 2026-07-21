package afield.examples

import afield.Box
import afield.OperationTranscript
import afield.Rect
import afield.SavingContext
import afield.SessionInformation
import afield.box
import afield.isTrue
import afield.macros.checkAtticAndExecute
import afield.macros.checkIntroductionAndExecute
import afield.macros.checkPreambleAndExecute
import afield.thisSessionInfo
import kotlinx.coroutines.runBlocking
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import kotlin.script.experimental.api.ScriptDiagnostic
import kotlin.system.exitProcess

/*
FIELD_TEST
id: examples.demo-transcript.exec
runner: jvm-main
entrypoint: afield.examples.DemoTranscriptExecSmokeTest
tags: [smoke, agents, transcript, docs, verification]
timeoutSec: 300
expect:
  exitCode: 0
  stdoutContains:
    - "DemoTranscriptExecSmokeTest ok"
*/
/**
 * Verification-integrity harness: executes / compiles the box bodies of *every* shipped demo
 * transcript under `dev_docs/agents/transcripts/` and asserts they carry no diagnostics.
 *
 * Why this exists: on 2026-07-18 `fn_differentiable_fit_demo.yaml` shipped with two compile errors
 * in its box body and merged, because nothing in CI ever *ran* the box Kotlin —
 * [OperationTranscriptMacroSmokeTest] only *applies* transcript ops and diffs the `code` string.
 * This test closes that gap by driving the REAL engine (no toy compiler):
 *
 *  - EXECUTE demos (pure-compute; no Window/Renderer): replayed end-to-end through the identical
 *    path a user/agent uses — [OperationTranscript.runMacro] -> executeFragmentAndWait -> the real
 *    compile+eval pipeline. A box compile error does not throw and does not clear `run.success`; it
 *    surfaces only as an output line with `kind == "error"` (see dispatchExecutionOutput in
 *    execution.kt). So we assert BOTH `run.success` AND zero error-kind output lines. This is the
 *    class of failure the fn-fit demo shipped with.
 *
 *  - COMPILE-ONLY demos (open a GLFW Window/Renderer or depend on a running renderer's context —
 *    unsafe/inappropriate to spin a render loop headless at 2am): the box graph is built with the
 *    real create/connect apply path, then each box's Kotlin `code` is run through the real macro
 *    expander (`applyMacros`), the real preamble machinery (`checkPreambleAndExecute` — which only
 *    evaluates property-delegate declarations, never a Window) and the real K2 compiler
 *    (`compileSnippetAndWait`) with EVAL SKIPPED. GLSL shader properties (`vertex`/`fragment`/
 *    `compute`) are not Kotlin and are not compiled here (they are exercised by the graphics render
 *    smokes). This still catches the fn-fit class of error (unresolved refs, ambiguous overloads).
 *
 * Adding a demo: drop the `*.yaml` in `dev_docs/agents/transcripts/`, then add its name to exactly
 * one of [EXECUTE] or [COMPILE_ONLY] below. An unclassified transcript fails the test on purpose.
 */
object DemoTranscriptExecSmokeTest {

    /** Pure-compute demos that are safe to run to completion headless. */
    private val EXECUTE = setOf(
        "fn_differentiable_fit_demo",
        "linalg_thumbnails_demo",
        "hello_display",
        "hello_display_capture",
        "two_box_context",
    )

    /** Graphics demos: box bodies are compiled (real pipeline, eval skipped) but not launched. */
    private val COMPILE_ONLY = setOf(
        "graphics_renderer_launch",
        "afield_four_traditional_triangle",
        "recstl_playground",
        "threebox",
        "rectsl_dataviz",
        "rectsl_plot_axes",
        "spin_glass",
    )

    /** Ops that build the box graph without compiling/running any box Kotlin. */
    private val GRAPH_VERBS = setOf(
        "create", "new-box", "box", "connect", "disconnect", "edit", "set", "set-string", "move", "frame", "set-rect", "wait",
    )

    @JvmStatic
    fun main(args: Array<String>) {
        val transcriptsDir = locateTranscriptsDir()
        println("DemoTranscriptExecSmokeTest: transcripts dir = ${transcriptsDir.absolutePath}")

        startRunloopDaemon()

        val yamls = (transcriptsDir.listFiles { f -> f.isFile && f.extension == "yaml" } ?: emptyArray())
            .sortedBy { it.name }
        require(yamls.isNotEmpty()) { "No transcript yaml files found in ${transcriptsDir.absolutePath}" }

        val failures = mutableListOf<String>()
        for (yaml in yamls) {
            val name = yaml.nameWithoutExtension
            try {
                when (name) {
                    in EXECUTE -> runExecuteDemo(yaml, name, failures)
                    in COMPILE_ONLY -> runCompileOnlyDemo(yaml, name, failures)
                    else -> {
                        val msg = "$name: UNCLASSIFIED — add to EXECUTE or COMPILE_ONLY in DemoTranscriptExecSmokeTest"
                        failures.add(msg)
                        println("[unclassified] $msg")
                    }
                }
            } catch (t: Throwable) {
                val msg = "$name: harness threw ${t.javaClass.simpleName}: ${t.message}"
                failures.add(msg)
                System.err.println("[error] $msg")
                t.printStackTrace(System.err)
            } finally {
                thisSessionInfo.remove()
            }
        }

        if (failures.isNotEmpty()) {
            System.err.println("DemoTranscriptExecSmokeTest FAILED with ${failures.size} problem(s):")
            failures.forEach { System.err.println("  - $it") }
            exitProcess(1)
        }
        println("DemoTranscriptExecSmokeTest ok — ${yamls.size} demos verified " +
            "(${EXECUTE.size} executed, ${COMPILE_ONLY.size} compiled)")
    }

    // ---- EXECUTE: run the whole transcript through the real runner and assert no error output ----

    private fun runExecuteDemo(yaml: File, name: String, failures: MutableList<String>) {
        val document = freshSession(name)
        val macrosDir = File(document, "macros").also { it.mkdirs() }
        File(macrosDir, "$name.yaml").writeText(yaml.readText())

        println("[execute] $name …")
        val run = OperationTranscript.runMacro(document.absolutePath, name)
        val errorLines = run.output.filter { it.kind == "error" || it.kind == "err" }

        when {
            !run.success -> {
                val msg = "$name: transcript run failed: ${run.errors.joinToString("; ").ifBlank { run.message }}"
                failures.add(msg)
                println("[execute] FAIL $msg")
            }
            errorLines.isNotEmpty() -> {
                val detail = errorLines.joinToString(" | ") { "${it.uid}:${it.line} ${stripHtml(it.html)}" }
                val msg = "$name: box execution emitted ${errorLines.size} error diagnostic(s): $detail"
                failures.add(msg)
                println("[execute] FAIL $msg")
            }
            else -> println("[execute] PASS $name (applied=${run.applied}, output lines=${run.output.size})")
        }
    }

    // ---- COMPILE-ONLY: build the graph, then compile each box's Kotlin with eval skipped ----

    private fun runCompileOnlyDemo(yaml: File, name: String, failures: MutableList<String>) {
        val document = freshSession(name)
        val ops = loadOps(yaml)
        val graphOps = ops.filter { (it.keys.firstOrNull()?.lowercase() ?: "") in GRAPH_VERBS }

        // Build the box graph with the real apply path — create/connect/edit/move only, no launch.
        val macrosDir = File(document, "macros").also { it.mkdirs() }
        val filtered = linkedMapOf<String, Any?>("name" to name, "operations" to graphOps)
        File(macrosDir, "$name.yaml").writeText(Yaml().dump(filtered))
        val build = OperationTranscript.runMacro(document.absolutePath, name)
        if (!build.success) {
            // A missing cross-transcript parent (skipped connect) is fine; only hard errors matter.
            println("[compile] $name graph build note: ${build.message}")
        }

        // Compile each created box, parent-first (creation order), so parent preamble (prop<>
        // delegate declarations) is populated before a child gathers it upward.
        val boxIds = graphOps.mapNotNull { op ->
            if ((op.keys.firstOrNull()?.lowercase() ?: "") in setOf("create", "new-box", "box")) {
                val p = op.values.firstOrNull() as? Map<*, *>
                (p?.get("box") ?: p?.get("uid") ?: p?.get("id") ?: p?.get("target"))?.toString()
            } else null
        }

        println("[compile] $name (${boxIds.size} box(es)) …")
        var demoOk = true
        for (id in boxIds) {
            val target = runCatching { box(id, createIfMissing = false) }.getOrNull()
            if (target == null) {
                println("[compile]   $id: not created (skipped)")
                continue
            }
            if (target.getPropertyRaw("_tombstone").isTrue()) continue
            val code = target.code
            if (code.isBlank()) {
                println("[compile]   $id: no Kotlin `code` (shader-only box) — skipped")
                continue
            }
            val diags = compileBoxOnly(target)
            if (diags.isEmpty()) {
                println("[compile]   $id: PASS")
            } else {
                demoOk = false
                diags.forEach {
                    val msg = "$name/$id: $it"
                    failures.add(msg)
                    println("[compile]   $id: FAIL $it")
                }
            }
        }
        if (demoOk) println("[compile] PASS $name")
    }

    /**
     * Compile [box]'s Kotlin `code` through the real pipeline with evaluation skipped, returning any
     * ERROR-severity diagnostics. Reuses the real macro expander, preamble evaluation and K2
     * compiler; only the terminal `eval` of the main body is omitted (that is where a graphics box
     * would open a Window). `//%-` fragment breaks are Kotlin comments, so compiling the whole body
     * as one snippet keeps cross-fragment declarations resolvable without evaluating anything.
     */
    private fun compileBoxOnly(box: Box): List<String> = runBlocking {
        val errors = mutableListOf<String>()
        Box.withBoxContext(box) {
            // Expand macros first: this both rewrites moduleHere/prop/println/… AND writes the
            // prop<T>() delegate declarations into the box's preamble (applyInsertToPreamble).
            // Then substitute the `__line__` sentinel the print macros emit — standardExecutionPractice
            // (the real path) does this between applyMacros and eval; skipping it leaves an unresolved
            // `__line__` reference. The exact number is irrelevant to compilation.
            val expanded = box.ki.applyMacros(box, box.code)
                .split("\n")
                .mapIndexed { i, line -> line.replace("__line__", "$i") }
                .joinToString("\n")

            // Evaluate setup blocks (preamble/introduction/attic). These are declaration-only and
            // never open a Window; evaluating them puts the Box.<prop> extension symbols (gathered
            // upward from parents too) into this engine's REPL context so the body resolves them.
            val swallow: (List<ScriptDiagnostic>) -> Unit = { }
            val swallowEx: (Throwable) -> Unit = { }
            checkPreambleAndExecute(box, swallow, swallow, swallowEx)
            checkIntroductionAndExecute(box, swallow, swallow, swallowEx)
            checkAtticAndExecute(box, swallow, swallow, swallowEx)

            box.ki.compileSnippetAndWait(expanded, implicits = listOf(box)) { diagnostics ->
                diagnostics
                    .filter { it.severity.ordinal >= ScriptDiagnostic.Severity.ERROR.ordinal }
                    .forEach { d ->
                        val loc = d.location?.start?.let { " (${it.line}:${it.col})" } ?: ""
                        errors.add("${d.message}$loc".trim())
                    }
            }
        }
        errors
    }

    // ---- shared setup ----

    private fun freshSession(name: String): File {
        thisSessionInfo.remove()
        val workspace = Files.createTempDirectory("afield-demo-exec-$name").toFile()
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

    @Suppress("UNCHECKED_CAST")
    private fun loadOps(yaml: File): List<Map<String, Any?>> {
        val loaded = Yaml().load<Any?>(yaml.readText())
        val raw = when (loaded) {
            is List<*> -> loaded
            is Map<*, *> -> (loaded["operations"] as? List<*>) ?: (loaded["ops"] as? List<*>) ?: emptyList<Any?>()
            else -> emptyList<Any?>()
        }
        return raw.mapNotNull { it as? Map<String, Any?> }
    }

    private fun startRunloopDaemon() {
        val t = Thread({ afield.Runloop.runLoop.start() }, "demo-transcript-harness-runloop")
        t.isDaemon = true
        t.start()
        val deadline = System.currentTimeMillis() + 30_000
        while (afield.Runloop.runLoop.frame < 1L) {
            check(System.currentTimeMillis() < deadline) { "runloop did not start within 30s" }
            Thread.sleep(20)
        }
        println("DemoTranscriptExecSmokeTest: runloop up (frame=${afield.Runloop.runLoop.frame})")
    }

    private fun locateTranscriptsDir(): File {
        val rel = "dev_docs/agents/transcripts"
        val candidates = buildList {
            System.getProperty("afield.repo")?.let { add(File(it, rel)) }
            var dir: File? = File(System.getProperty("user.dir")).absoluteFile
            while (dir != null) {
                add(File(dir, rel))
                dir = dir.parentFile
            }
        }
        return candidates.firstOrNull { it.isDirectory }
            ?: error("Could not locate $rel from user.dir=${System.getProperty("user.dir")}")
    }

    private fun stripHtml(html: String): String =
        html.replace(Regex("<[^>]+>"), " ").replace("&lt;", "<").replace("&gt;", ">").trim()
}
