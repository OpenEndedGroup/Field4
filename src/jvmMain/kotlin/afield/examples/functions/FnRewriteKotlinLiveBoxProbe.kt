package afield.examples.functions

import afield.Box
import afield.OperationTranscript
import afield.SavingContext
import afield.SessionInformation
import afield.thisSessionInfo
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import kotlin.system.exitProcess

/*
FIELD_TEST
id: functions.rewrite-kotlin.live-box
runner: jvm-main
entrypoint: afield.examples.functions.FnRewriteKotlinLiveBoxProbe
tags: [smoke, functions, kotlin, fnrewrite]
timeoutSec: 120
expect:
  exitCode: 0
  stdoutContains:
    - "PROBE completed=true"
    - "whose body it therefore re-runs"
*/
/**
 * Runs one `rewriteKotlin` spec inside a live box, headlessly, in seconds.
 *
 * This is the reproduction #86 never had. That blocker is only reachable through
 * `public/docs/functions_examples.md`'s `fn-rewrite-language-plot` fence, which means rediscovering
 * it costs a 71-minute docs build — twice so far, for the same failure. Nothing in the smoke catalog
 * exercises a `@{...}` right-hand side in a box at all: `functions.evolve`, `functions.plot-gallery`,
 * `functions.waveshape` and `kotlin.pattern-rewrite` all avoid it.
 *
 * The box body is the docs fence's, minus the three `plot` calls — the failure is in the rewrite, and
 * dropping the plots keeps this headless. The spec is built by string concatenation rather than a raw
 * string so the whole body can live in one raw string here.
 *
 * What it measures, and why: `SourceBackedFnRewriteNumberExpression.hydrate` compiles on
 * `sourceBackedBlockBox(boxId)`'s engine, and that resolves to the **currently running** box, so
 * hydrating asks the box's own engine to evaluate while it is already mid-evaluation. That is the
 * re-entry `SourceBackedBlock`'s KDoc measured at 327 nested runs. `FN_REWRITE_BODY_ENTER` is printed
 * once per entry to the body, so its count is the direct test: more than one means the body re-ran.
 */
object FnRewriteKotlinLiveBoxProbe {

    private const val BOX = "fn_rewrite_probe"
    private const val HOST = "fn_rewrite_host"
    private const val ENTER = "FN_REWRITE_BODY_ENTER"
    private const val DONE = "FN_REWRITE_DONE"

    /**
     * Body re-entries, counted from inside the box.
     *
     * The box's `println` is macro-rewritten to box output and never reaches this process's stdout
     * ([afield.stdlib.SourceBackedBlock] diagnosis notes the same), and `runMacro` only hands back its
     * output when it returns — which, on the failing path, it does not. So the body calls straight back
     * into this object and a watchdog reads the counter live.
     */
    private val entries = java.util.concurrent.atomic.AtomicInteger()
    private val entryMillis = java.util.concurrent.ConcurrentLinkedQueue<Long>()
    @Volatile private var startedAt = 0L

    /** Called by the box body, once per entry. */
    @JvmStatic
    fun tick() {
        entries.incrementAndGet()
        entryMillis.add(System.currentTimeMillis() - startedAt)
    }

    /**
     * Box bodies are assembled as **lists of lines**, never by interpolating a multi-line string into
     * a raw string. Interpolation dedents the inserted block's continuation lines, and a dedented line
     * inside a YAML literal block scalar **terminates the block** — which produced a malformed
     * transcript whose only symptom was `runMacro` never returning and nothing at all in the log.
     * (Same family as the `trimIndent`-after-interpolation trap in [transcript].)
     */
    private val SPEC_LINES = listOf(
        "val spec = listOf(",
        """    "#fnrewrite",""",
        """    "sin/*/saw => op=triangle",""",
        """    "sin/**/[tag=number][value>0.15] => *= @{1.0 + depth * 0.08}",""",
        // `${'"'}` rather than a fourth quote: a raw string cannot end with `"` before its terminator.
        """    "**/[role=amplitude] => *= @{if (parentOp == \"sin\") 0.75 else 1.0}${'"'}""",
        """).joinToString("\n")""",
    )

    /**
     * `self` — the docs fence as published: `rewriteKotlin` called from the box body, so hydration
     * targets the box that is running. This is #86.
     */
    private val SELF_BODY = (
        listOf(
            "import afield.stdlib.Functions",
            "import afield.stdlib.rewriteKotlin",
            "afield.examples.functions.FnRewriteKotlinLiveBoxProbe.tick()",
            """println("$ENTER")""",
        ) + SPEC_LINES + listOf(
            "with(Functions) {",
            "    val base = (sin(sin(saw(0.18))) * 0.45) over (0.0..3.0)",
            "    val changed = base.rewriteKotlin(spec)",
            """    println("$DONE " + changed.eval(1.0))""",
            "}",
        )
        ).joinToString("\n")

    /**
     * `host` — the workaround under test: the same spec run against a **different, idle** box's engine
     * via [afield.stdlib.rewriteFn], the receiver form the KDoc offers for exactly this. If this works,
     * `@{...}` is usable with a documented split; if it does not, the string form has no working caller.
     */
    private val HOST_BODY = (
        listOf(
            "import afield.stdlib.Functions",
            "import afield.stdlib.rewriteFn",
            "afield.examples.functions.FnRewriteKotlinLiveBoxProbe.tick()",
            """println("$ENTER")""",
        ) + SPEC_LINES + listOf(
            // Topology, not a hard-coded id -- the same idiom the loadable transcript uses.
            """val host = parents.last()""",
            """println("HOST_BOX_FOUND " + host.name)""",
            "with(Functions) {",
            "    val base = (sin(sin(saw(0.18))) * 0.45) over (0.0..3.0)",
            "    val changed = host.rewriteFn(base, spec)",
            """    println("$DONE " + changed.eval(1.0) + " before=" + base.eval(1.0))""",
            "}",
        )
        ).joinToString("\n")

    /** A body that only has to compile and evaluate, so [HOST] ends up with a warm, idle engine. */
    private val HOST_IDLE_BODY = """println("HOST_READY")"""

    /**
     * `block` — the same question for [afield.stdlib.SourceBackedBlock], the other route into the same
     * trap: a rehydrating block invoked from inside its own box's running body.
     */
    private val BLOCK_SELF_BODY = listOf(
        "import afield.stdlib.sourceBackedBlock",
        "afield.examples.functions.FnRewriteKotlinLiveBoxProbe.tick()",
        """println("$ENTER")""",
        """val b = sourceBackedBlock<Any?>(thisBox.id, "1.0 + 2.0")""",
        """println("$DONE " + b())""",
    ).joinToString("\n")

    /** `blockhost` — the same block, hydrated in the idle parent instead. The positive control. */
    private val BLOCK_HOST_BODY = listOf(
        "import afield.stdlib.sourceBackedBlock",
        "afield.examples.functions.FnRewriteKotlinLiveBoxProbe.tick()",
        """println("$ENTER")""",
        "val host = parents.last()",
        """println("HOST_BOX_FOUND " + host.name)""",
        """val b = sourceBackedBlock<Any?>(host.id, "1.0 + 2.0")""",
        """println("$DONE " + b())""",
    ).joinToString("\n")

    @JvmStatic
    fun main(args: Array<String>) = run("self")

    /**
     * The two modes share this whole harness, and the *only* difference between them is which box's
     * engine the `@{...}` snippet is compiled in. Mode is a parameter and not an environment variable
     * because `run_field_tests.py` drives the entrypoint through a Field server it starts itself, so
     * the caller's environment never reaches this JVM.
     */
    fun run(mode: String) {
        startRunloopDaemon()

        val (document, session) = freshSession(mode)
        val yaml = transcript(mode)
        File(document, "macros").also { it.mkdirs() }
            .let { File(it, "$BOX.yaml") }
            .writeText(yaml)

        // The input, echoed: a probe whose run wedges before it logs anything is unreadable without it.
        yaml.split("\n").forEach { println("PROBE yaml| $it") }

        // The runner is a daemon so a body that never returns cannot hold the JVM open: main prints
        // its verdict at the deadline and returns, and the process exits naturally. `thisSessionInfo`
        // is a ThreadLocal and the operation runs on the calling thread, so the runner has to set it
        // for itself — without this every operation fails with an NPE out of `applyMacroOperation`.
        val result = java.util.concurrent.atomic.AtomicReference<OperationTranscript.MacroRunResult?>(null)
        startedAt = System.currentTimeMillis()
        Thread({
            thisSessionInfo.set(session)
            result.set(OperationTranscript.runMacro(document.absolutePath, BOX))
        }, "$BOX-runner").apply { isDaemon = true }.start()

        // Two phases. First wait for the run to return or for a second body entry, whichever comes
        // first — a second entry is already the whole answer, and unguarded the run takes 90s+ to give
        // up. Then, once re-entry is seen, give it a short grace period to come back anyway: the
        // FnRewriteSelfBoxReentry guard throws on that second hydration, so the run *does* return, and
        // its error line is the evidence the guard fired rather than the stack overflowing.
        val budget = (System.getenv("FN_REWRITE_PROBE_SECONDS")?.toLongOrNull() ?: 60L) * 1000L
        val deadline = startedAt + budget
        var reported = 0
        fun pump(until: Long, stopOnReentry: Boolean) {
            while (result.get() == null && System.currentTimeMillis() < until) {
                Thread.sleep(50)
                val now = entries.get()
                if (now != reported) {
                    reported = now
                    println("PROBE body-entries=$now at %.1fs".format((System.currentTimeMillis() - startedAt) / 1000.0))
                }
                if (stopOnReentry && now >= 2) return
            }
        }
        pump(deadline, stopOnReentry = true)
        if (result.get() == null && entries.get() >= 2) {
            println("PROBE re-entry seen; waiting for the guard to unwind")
            pump(minOf(deadline, System.currentTimeMillis() + 30_000), stopOnReentry = false)
        }

        val run = result.get()
        val entered = entries.get()
        println("PROBE mode=$mode wall-seconds=%.1f".format((System.currentTimeMillis() - startedAt) / 1000.0))
        println("PROBE body-entries=$entered returned=${run != null} entry-millis=${entryMillis.toList()}")

        val text = run?.output?.map { "${it.kind}: ${stripHtml(it.html).trim()}" } ?: emptyList()
        val errors = run?.output?.filter { it.kind == "error" || it.kind == "err" } ?: emptyList()
        if (run != null) {
            println("PROBE success=${run.success} applied=${run.applied} output-lines=${run.output.size} error-lines=${errors.size}")
            if (!run.success) println("PROBE run-message=${run.message} errors=${run.errors.joinToString("; ")}")
            errors.take(4).forEach { println("PROBE error ${it.uid}:${it.line} ${stripHtml(it.html).trim().take(400)}") }
            text.take(12).forEach { println("PROBE out $it") }
        }

        val completed = run != null && run.success && errors.isEmpty() && text.any { DONE in it }
        val verdict = when {
            run == null -> "NO RETURN"
            completed && entered > 1 -> "COMPLETED, BODY RE-ENTERED $entered times"
            completed -> "SINGLE ENTRY"
            else -> "RUN FAILED"
        }
        // `completed` is what the catalog gates on, because it stays true under a future engine fix
        // that removes the re-entry altogether; `entries` is the diagnosis, not the gate. Whether the
        // guard *reported* is gated from the runner instead: its message goes to the `error,fn-rewrite`
        // log channel, which reaches this process's stdout but never `run.output`.
        println("PROBE completed=$completed entries=$entered")
        println("PROBE verdict=$verdict")

        // Non-zero unless the rewrite either completed cleanly or refused legibly. The runner thread is
        // a daemon and its stack may be hundreds of frames deep and wedged, so exit explicitly rather
        // than trusting a return from main to bring the JVM down.
        exitProcess(if (completed) 0 else 1)
    }

    /**
     * Built line by line rather than as one raw string: `trimIndent` runs *after* interpolation, so a
     * template holding an already-indented block scalar re-indents the whole document.
     */
    private fun transcript(mode: String): String {
        fun block(body: String, pad: String) = body.split("\n").joinToString("\n") { "$pad$it" }

        // `connectUnder` has to be wired BEFORE the execute, because the body reads `parents.last()`.
        fun boxOps(id: String, label: String, y: Double, body: String, connectUnder: String? = null) = listOf(
            "  - create:",
            "      box: $id",
            "      name: $label",
            "      frame:",
            "        x: 80.0",
            "        y: $y",
            "        w: 300.0",
            "        h: 120.0",
            "      properties:",
            "        code: |",
            block(body, "          "),
        ) + (
            connectUnder?.let {
                listOf(
                    "  - connect:",
                    "      parent: $it",
                    "      child: $id",
                )
            } ?: emptyList()
            ) + listOf(
            "  - execute:",
            "      box: $id",
            "      property: code",
            "      type: fragment",
            "      code: |",
            block(body, "        "),
            "  - wait:",
            "      millis: 250",
        )

        val header = listOf(
            "name: $BOX",
            "description: One rewriteKotlin spec in a live box, mode=$mode.",
            "operations:",
        )
        // In host mode the idle box is created and executed FIRST, so its engine exists and is not
        // mid-evaluation when the second box asks it to compile.
        val body = when (mode) {
            "host" -> boxOps(HOST, "Fn Rewrite Host", 80.0, HOST_IDLE_BODY) +
                boxOps(BOX, "Fn Rewrite Probe", 260.0, HOST_BODY, connectUnder = HOST)
            "blockhost" -> boxOps(HOST, "Block Host", 80.0, HOST_IDLE_BODY) +
                boxOps(BOX, "Block Probe", 260.0, BLOCK_HOST_BODY, connectUnder = HOST)
            "block" -> boxOps(BOX, "Block Probe", 80.0, BLOCK_SELF_BODY)
            else -> boxOps(BOX, "Fn Rewrite Probe", 80.0, SELF_BODY)
        }
        return (header + body).joinToString("\n") + "\n"
    }

    private fun freshSession(mode: String): Pair<File, SessionInformation> {
        thisSessionInfo.remove()
        val workspace = Files.createTempDirectory("afield-fn-rewrite-$mode").toFile()
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
        return document to session
    }

    private fun startRunloopDaemon() {
        val t = Thread({ afield.Runloop.runLoop.start() }, "fn-rewrite-probe-runloop")
        t.isDaemon = true
        t.start()
        val deadline = System.currentTimeMillis() + 30_000
        while (afield.Runloop.runLoop.frame < 1L) {
            check(System.currentTimeMillis() < deadline) { "runloop did not start within 30s" }
            Thread.sleep(20)
        }
    }

    private fun stripHtml(html: String): String =
        html.replace(Regex("<[^>]*>"), " ").replace("&nbsp;", " ").replace("&amp;", "&")
            .replace("&lt;", "<").replace("&gt;", ">").replace(Regex("\\s+"), " ")
}

/*
FIELD_TEST
id: functions.rewrite-kotlin.two-box
runner: jvm-main
entrypoint: afield.examples.functions.FnRewriteKotlinTwoBoxProbe
tags: [smoke, functions, kotlin, fnrewrite]
timeoutSec: 120
expect:
  exitCode: 0
  stdoutContains:
    - "PROBE completed=true entries=1"
  stdoutNotContains:
    - "whose body it therefore re-runs"
*/
/**
 * The same spec as [FnRewriteKotlinLiveBoxProbe], compiled in a **second, idle** box instead of the
 * running one — the positive control, and the pair that makes the one-box failure mean something.
 *
 * Without this, "the rewrite blows up" and "`@{...}` cannot work at all" are the same observation.
 * The box body calls `host.rewriteFn(base, spec)` ([afield.stdlib.rewriteFn]), the receiver form whose
 * KDoc exists for callers "not running inside the box whose context the snippets should compile in".
 */
object FnRewriteKotlinTwoBoxProbe {
    @JvmStatic
    fun main(args: Array<String>) = FnRewriteKotlinLiveBoxProbe.run("host")
}

/*
FIELD_TEST
id: kotlin.source-backed-block.self-box
runner: jvm-main
entrypoint: afield.examples.functions.SourceBackedBlockSelfBoxProbe
tags: [smoke, kotlin, sourceblock]
timeoutSec: 120
expect:
  exitCode: 0
  stdoutContains:
    - "PROBE completed=true"
    - "whose body it therefore re-runs"
*/
/**
 * [afield.stdlib.SourceBackedBlock]'s own route into the self-box trap, measured rather than inferred.
 *
 * Its KDoc has documented the hazard at 327 nested runs since the `keyframe_code_blend` split; this is
 * the first test that actually drives it, and what gates `SourceBackedBlockSelfBoxReentry`.
 */
object SourceBackedBlockSelfBoxProbe {
    @JvmStatic
    fun main(args: Array<String>) = FnRewriteKotlinLiveBoxProbe.run("block")
}

/*
FIELD_TEST
id: kotlin.source-backed-block.two-box
runner: jvm-main
entrypoint: afield.examples.functions.SourceBackedBlockTwoBoxProbe
tags: [smoke, kotlin, sourceblock]
timeoutSec: 120
expect:
  exitCode: 0
  stdoutContains:
    - "PROBE completed=true entries=1"
  stdoutNotContains:
    - "whose body it therefore re-runs"
*/
/** The same block hydrated in an idle parent box — the positive control for the one above. */
object SourceBackedBlockTwoBoxProbe {
    @JvmStatic
    fun main(args: Array<String>) = FnRewriteKotlinLiveBoxProbe.run("blockhost")
}
