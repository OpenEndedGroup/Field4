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
import afield.remotejs.KotlinJsExecution
import afield.remotejs.kotlinJsTopologyNeedsUpdating
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
        "spectral_examples",
        "imaging_cv_examples",
        "imaging_vector_kernels",
        "imaging_visualizers_demo",
        "mesh_geometry_examples",
        "vision_metal_cube",
        // JVM Kotlin that compiles a Live Metal box's Kotlin/JS for real, runs it, and rasterises
        // the mesh it builds. No headset and no Metal toolchain, so it runs to completion here.
        "vision_geometry_desk_preview",
        "pattern_rewrite_worked_example",
        "rewrite_strategies_demo",
        // Six independent pure-CPU WFC boxes; nothing opens a Window or a Renderer.
        "wfc_worked_example",
        // Five independent pure-CPU plane-geometry boxes; showPlaneGrid is drawToSVG -> display,
        // so no AWT, no GPU and nothing to launch.
        "spatial_geometry_examples",
        // Ten independent pure-CPU spatial-ALife boxes; nothing opens a Window or a Renderer.
        "alife_worked_example",
        // Five independent pure-CPU statistics boxes; showPlaneGrid is drawToSVG -> display,
        // showSignals is a plotly HTML fragment -> display, histogramToSvg is just a string.
        "statistics_examples",
        // Two pure-CPU FLine boxes; showPlaneGrid is drawToSVG -> display, so no AWT and no GPU.
        "arclength_spacing_demo",
        // Two more of the same shape: the interior-Close demo reverses, subdivides and dashes
        // closed contours and reports the numbers, all through showPlaneGrid.
        "close_node_demo",
        // Six independent pure-CPU dynamic-graph boxes. Four print and draw through showPlaneGrid /
        // showSignals; the other two build a Frames through animateEvolution, so executing them is a
        // compile check and the animation only runs when the box is launched. No Window, no Renderer.
        "dynamic_graph_systems_examples",
        // Five independent pure-CPU automatic-differentiation boxes; plot, showSignals and
        // showPlaneGrid all end in display(), so no AWT, no GPU and nothing to launch.
        "autodiff_worked_example",
        // Four independent pure-CPU keyframer boxes. A KeyframeScope needs no GPU: a uniform
        // `Structure` is constructible bare and its bindings are plain maps, the camera box does
        // CameraPose arithmetic without ever touching a live `Camera`, and persistence writes to a
        // temp directory. showPlaneGrid is drawToSVG -> display, so no AWT and nothing to launch.
        "keyframer_worked_example",
        // Three pure-compute MorphoHDL circuit-search boxes. Every candidate is compiled by
        // morpho's own JS compiler on GraalJS and simulated by MorphoSim; the figures go through
        // showPlaneGrid (drawToSVG -> display), so no Window and no Renderer. The searches are
        // budgeted in compiler invocations and take a few seconds each.
        "morpho_circuit_search",
        // Four independent pure-CPU heatmap boxes; showHeatmap / showImage encode a PNG and end in
        // display(), so no AWT, no GPU and nothing to launch.
        "viz_showheatmap",
        // One pure-CPU polytope box. It derives polygonal FLines and publishes their payload to the
        // on-canvas Three.js viewer; no native Window or Renderer is opened by threeLines().
        "polytope_conway_demo",
        // Four more pure-CPU polytope boxes, one question each — the demo set POLYTOPE_ALGEBRA.md
        // asks for, and the loadable companion to public/docs/polytope_examples.md. Two end in
        // threeLines() as above; the other two rasterise with TriMesh.renderPng, which is the
        // AWT-free PngEncoder path into display(). No Window, no Renderer, no GPU either way.
        "polytope_conway_gallery",
        // A second pure-CPU polytope box on the same path: evaluate one Conway expression,
        // canonicalize it, and draw the realized and canonical forms side by side.
        "polytope_canonical_gallery",
        // Eight pure-CPU typography boxes. The letters are filled from their own contours by the
        // family's own scanline filler and shown through showImgGrid / showPlaneGrid / showMeshGrid,
        // all of which are PngEncoder or Skia-SVG into `display` — no AWT rasteriser, no GPU. Box 5
        // ends in `animate { }`, which registers an animation rather than blocking, as with the two
        // parameter sweeps in spatial_geometry_examples.
        "typography_worked_example",
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
        "imgui_vr_panel",
    )

    /**
     * KotlinJS "code in the head" demos: the box body is Kotlin compiled to JavaScript for a remote
     * head, NOT JVM Kotlin — so it is compiled through the REAL [KotlinToJsCompiler] via the head
     * profile the `// {{ marker }}` selects (the identical path a live box takes), and asserted to
     * carry no error diagnostics. Faithful replay for markers like `// {{ tone }}` / `// {{ remoteAR }}`.
     */
    private val KOTLINJS = setOf(
        "tone_synth_sequence",
        "remotear_start",
        "remotear_scene_motion",
        "visionos_start",
        "vision_geometry_shapes",
        "vision_geometry_heightfield",
        "vision_geometry_animated",
    )

    /** User-facing starters must carry a real KotlinJS root → child topology, not only compile offline. */
    private val KOTLINJS_STARTERS = setOf("remotear_start", "visionos_start")

    /** Ops that build the box graph without compiling/running any box Kotlin. */
    private val GRAPH_VERBS = setOf(
        "create", "new-box", "box", "connect", "disconnect", "edit", "set", "set-string", "move", "frame", "set-rect", "wait",
    )

    @JvmStatic
    fun main(args: Array<String>) {
        // First line, before anything can touch an AWT class, and for the same reason every jvm-main
        // gallery does it: this harness runs under `-XstartOnFirstThread` (added for GLFW by
        // `requiredAgentJvmArgs` in agent_backdoor.kt:830) and opens no window, so nothing pumps the
        // process's first-thread run loop. Non-headless AWT then hangs the moment a box asks for a
        // glyph outline — `sun.font.CFontManager.loadFonts` dispatches there and waits. Headless AWT
        // does not, and this harness has no use for a screen. See `Typefaces.warm`.
        System.setProperty("java.awt.headless", "true")
        val transcriptsDir = locateTranscriptsDir()
        println("DemoTranscriptExecSmokeTest: transcripts dir = ${transcriptsDir.absolutePath}")

        startRunloopDaemon()

        // Iterating on one demo otherwise means waiting three minutes for the other twenty-odd. The
        // filter is for development only — CI runs the whole set, and an unfiltered run is the
        // default — but without it a single failing demo is painful to debug.
        val only = (args.firstOrNull() ?: System.getenv("DEMO_TRANSCRIPT_ONLY"))
            ?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)?.toSet()
        only?.let { println("DemoTranscriptExecSmokeTest: FILTERED to ${it.joinToString()} — not a full run") }

        val yamls = (transcriptsDir.listFiles { f -> f.isFile && f.extension == "yaml" } ?: emptyArray())
            .sortedBy { it.name }
            .filter { only == null || it.nameWithoutExtension in only }
        require(yamls.isNotEmpty()) { "No transcript yaml files found in ${transcriptsDir.absolutePath}" }

        val failures = mutableListOf<String>()
        for (yaml in yamls) {
            val name = yaml.nameWithoutExtension
            try {
                when (name) {
                    in EXECUTE -> runExecuteDemo(yaml, name, failures)
                    in COMPILE_ONLY -> runCompileOnlyDemo(yaml, name, failures)
                    in KOTLINJS -> runKotlinJsDemo(yaml, name, failures)
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
            "(${EXECUTE.size} executed, ${COMPILE_ONLY.size} compiled, ${KOTLINJS.size} kotlinjs)")
    }

    // ---- KOTLINJS: build the graph, then compile each kotlinjs box body Kotlin -> JS for real ----

    private fun runKotlinJsDemo(yaml: File, name: String, failures: MutableList<String>) {
        val document = freshSession(name)
        val ops = loadOps(yaml)
        val graphOps = ops.filter { (it.keys.firstOrNull()?.lowercase() ?: "") in GRAPH_VERBS }

        val macrosDir = File(document, "macros").also { it.mkdirs() }
        val filtered = linkedMapOf<String, Any?>("name" to name, "operations" to graphOps)
        File(macrosDir, "$name.yaml").writeText(Yaml().dump(filtered))
        val build = OperationTranscript.runMacro(document.absolutePath, name)
        if (!build.success) println("[kotlinjs] $name graph build note: ${build.message}")

        val boxIds = graphOps.mapNotNull { op ->
            if ((op.keys.firstOrNull()?.lowercase() ?: "") in setOf("create", "new-box", "box")) {
                val p = op.values.firstOrNull() as? Map<*, *>
                (p?.get("box") ?: p?.get("uid") ?: p?.get("id") ?: p?.get("target"))?.toString()
            } else null
        }
        if (name in KOTLINJS_STARTERS) {
            kotlinJsTopologyNeedsUpdating(thisSessionInfo.get().boxes.values)
        }

        println("[kotlinjs] $name (${boxIds.size} box(es)) …")
        var demoOk = true
        var compiled = 0
        for (id in boxIds) {
            val target = runCatching { box(id, createIfMissing = false) }.getOrNull() ?: continue
            if (target.getPropertyRaw("_tombstone").isTrue()) continue
            // Language-root boxes intentionally have no code property; do not force the non-null
            // delegated accessor merely to discover that this graph node is structural.
            val code = target.getPropertyRaw("code")?.toString().orEmpty()
            if (code.isBlank()) continue
            // Only KotlinJS-head boxes: a `// {{ marker }}` first line, or mainLanguage == kotlinjs.
            val mainLang = target.getPropertyRaw("mainLanguage", "")?.toString()?.trim().orEmpty()
            val selection = runCatching { afield.remotejs.KotlinJsHeadProfiles.resolve(code) }.getOrNull()
            if (selection == null || (!selection.explicit && !mainLang.equals("kotlinjs", ignoreCase = true))) {
                println("[kotlinjs]   $id: not a kotlinjs-head box — skipped")
                continue
            }
            if (name in KOTLINJS_STARTERS && target.ke !is KotlinJsExecution) {
                demoOk = false
                val msg = "$name/$id: starter code box is not beneath a KotlinJS language root"
                failures.add(msg)
                println("[kotlinjs]   $id: FAIL $msg")
            }
            val prepared = selection.profile.prepareSource(selection.source)
            val result = afield.remotejs.KotlinToJsCompiler.compile(
                prepared, moduleName = "demo_${name}_${id.take(8)}", wrapImplicitMain = false,
            )
            val errors = result.diagnostics.filter { it.severity == "error" }
            when {
                errors.isNotEmpty() -> {
                    demoOk = false
                    errors.forEach {
                        val msg = "$name/$id: ${it.message} (line ${it.line})"
                        failures.add(msg)
                        println("[kotlinjs]   $id: FAIL $msg")
                    }
                }
                !result.success || result.js == null -> {
                    demoOk = false
                    val msg = "$name/$id: Kotlin->JS compile failed: ${result.rawLog.takeLast(400)}"
                    failures.add(msg)
                    println("[kotlinjs]   $id: FAIL $msg")
                }
                else -> {
                    compiled += 1
                    println("[kotlinjs]   $id: PASS (${selection.profile.id}, ${prepared.length} chars Kotlin -> ${result.js.length} chars JS)")
                }
            }
        }
        if (demoOk) {
            if (compiled == 0) {
                val msg = "$name: no kotlinjs-head box compiled — is the marker / mainLanguage set?"
                failures.add(msg)
                println("[kotlinjs] FAIL $msg")
            } else {
                println("[kotlinjs] PASS $name")
            }
        }
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

        // A demo can PASS (no error diagnostics) while printing nonsense. Set
        // AFIELD_DEMO_TRANSCRIPT_VERBOSE=1 — or =<name> for one demo — to echo what the boxes
        // actually printed, so the numbers a worked example claims can be read back and checked.
        val verbose = System.getenv("AFIELD_DEMO_TRANSCRIPT_VERBOSE")
        if (!verbose.isNullOrBlank() && (verbose == "1" || verbose == "all" || verbose == name)) {
            run.output.forEach { println("[execute:$name] ${it.kind}: ${stripHtml(it.html).trim()}") }
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
