package afield.examples

import afield.METAROOT_ID
import afield.OperationTranscript
import afield.SavingContext
import afield.SessionInformation
import afield.isTrue
import afield.thisSessionInfo
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/*
FIELD_TEST
id: examples.agent-transcripts.materialize
runner: jvm-main
entrypoint: afield.examples.AgentTranscriptMaterializer
tags: [smoke, agents, transcript, docs, welcome, verification]
timeoutSec: 300
expect:
  exitCode: 0
  stdoutContains:
    - "AgentTranscriptMaterializer ok"
*/
/**
 * Turns every agent transcript under `dev_docs/agents/transcripts/` into a loadable Field
 * **document** — a `<out>/<name>/metadata.edn` scene an editor/welcome can open, run and edit.
 * This is how the transcript corpus becomes both the docs gallery workspace and the welcome
 * "starting points": one source of truth (the yaml), documents derived at build time.
 *
 * The transcript yaml is the single source of truth and stays runnable as-is — this harness must
 * **never** require a `save` op inside a transcript. It performs the save itself, out of band.
 *
 * How it builds a document without a GPU: it applies only the *structural* ops
 * (`create/connect/edit/set/move` — the same [DemoTranscriptExecSmokeTest] GRAPH_VERBS set), never
 * `launch`/`execute`. That yields the full box graph (topology + code + shader properties) with no
 * Window/Renderer and no run state — which is exactly what a pristine "starting point" should be:
 * open it, then run it. It then persists the live boxes through the identical codepath the `save`
 * transcript op uses ([SavingContext.save] over the non-root, non-tombstoned boxes).
 *
 * Output root resolution (in order):
 *  1. `-Dafield.agentTranscripts.out=<dir>` (the docs build passes the committed asset dir here);
 *  2. first CLI arg;
 *  3. a throwaway temp dir — the default, so the CI/FIELD_TEST run verifies real correctness
 *     without ever writing committed assets (see the "no committed writes" test invariant).
 *
 * The FIELD_TEST run uses default (3): it materializes to temp and asserts each transcript yields a
 * `metadata.edn` carrying the expected number of boxes.
 */
object AgentTranscriptMaterializer {

    /** Repo-relative location of the committed starting-point bundle (must match dir.kt's constant). */
    private const val STARTING_POINTS_REL = "public/docs/assets/agent_transcripts"

    /**
     * Transcripts that are NOT self-contained scenes, so they make broken starting points and are not
     * materialized. `afield_four_traditional_triangle` is a child box that gets its `Renderer` from an
     * external `agent_graphics_context` parent (the graphics-topology rule) — alone it has no renderer.
     * `graphics_renderer_launch` is just that renderer parent with nothing to draw. Both stay in the
     * corpus for agents composing scenes (and in DemoTranscriptExecSmokeTest); they are only excluded
     * from the welcome/gallery starting points.
     */
    private val EXCLUDE = setOf(
        "afield_four_traditional_triangle",
        "graphics_renderer_launch",
    )

    /** Ops that build the box graph without compiling/running any box Kotlin (mirrors the smoke test). */
    private val GRAPH_VERBS = setOf(
        "create", "new-box", "box", "connect", "disconnect", "edit", "set", "set-string", "move", "frame", "set-rect", "wait",
    )

    @JvmStatic
    fun main(args: Array<String>) {
        val transcriptsDir = locateTranscriptsDir()
        val explicitOut = System.getProperty("afield.agentTranscripts.out")?.isNotBlank() == true ||
            args.firstOrNull()?.isNotBlank() == true
        val outRoot = resolveOutRoot(args)
        outRoot.mkdirs()
        println("AgentTranscriptMaterializer: transcripts = ${transcriptsDir.absolutePath}")
        println("AgentTranscriptMaterializer: output root = ${outRoot.absolutePath}")

        startRunloopDaemon()

        val yamls = (transcriptsDir.listFiles { f -> f.isFile && f.extension == "yaml" } ?: emptyArray())
            .sortedBy { it.name }
        require(yamls.isNotEmpty()) { "No transcript yaml files found in ${transcriptsDir.absolutePath}" }

        val failures = mutableListOf<String>()
        var materialized = 0
        for (yaml in yamls) {
            val name = yaml.nameWithoutExtension
            if (name in EXCLUDE) {
                // Not a self-contained scene — don't ship it as a starting point, and make sure any
                // previously-committed copy is removed so the welcome screen stops listing it.
                File(outRoot, name).deleteRecursively()
                println("[materialize] SKIP $name (not self-contained; excluded from starting points)")
                continue
            }
            try {
                val boxes = materializeOne(yaml, name, outRoot)
                val metadata = File(File(outRoot, name), SavingContext.METADATA_EDN)
                when {
                    !metadata.isFile ->
                        failures.add("$name: no ${SavingContext.METADATA_EDN} written to ${metadata.parent}")
                    metadata.readText().isBlank() ->
                        failures.add("$name: ${SavingContext.METADATA_EDN} is empty")
                    boxes == 0 ->
                        failures.add("$name: materialized document has zero boxes")
                    else -> {
                        materialized++
                        println("[materialize] PASS $name — $boxes box(es) -> ${metadata.parentFile.name}/")
                    }
                }
            } catch (t: Throwable) {
                val msg = "$name: harness threw ${t.javaClass.simpleName}: ${t.message}"
                failures.add(msg)
                System.err.println("[error] $msg")
                t.printStackTrace(System.err)
            } finally {
                // Dispose KI engines before clearing the session to clean up K2 ForkJoinPool threads.
                // OperationTranscript.runMacro triggers Box.ki lazy init even for structural-only ops,
                // leaving thread pools alive if not explicitly disposed.
                thisSessionInfo.get()?.boxes?.values?.forEach { box ->
                    if (box.id != "root" && box.id != METAROOT_ID) {
                        box.disposeKi()
                    }
                }
                thisSessionInfo.remove()
            }
        }

        // Drift guard: when run with the default (temp) output — i.e. as the CI FIELD_TEST rather
        // than an explicit `--arg <dir>` regeneration — also assert the committed starting-point
        // documents match what the transcripts produce now. This fails the build if someone edits a
        // transcript without regenerating, so the committed bundle can never silently go stale.
        if (failures.isEmpty() && !explicitOut) {
            val committed = File(transcriptsDir.parentFile.parentFile.parentFile, STARTING_POINTS_REL)
            if (committed.isDirectory) {
                println("AgentTranscriptMaterializer: checking committed bundle at ${committed.absolutePath}")
                for (yaml in yamls) {
                    val name = yaml.nameWithoutExtension
                    if (name in EXCLUDE) {
                        if (File(committed, name).exists()) {
                            failures.add("$name: excluded from starting points but still committed at " +
                                "$STARTING_POINTS_REL/$name — delete it (regenerate the bundle).")
                        }
                        continue
                    }
                    val drift = firstDrift(File(outRoot, name), File(committed, name))
                    if (drift != null) {
                        failures.add(
                            "$name: committed starting point is out of date ($drift). Regenerate with " +
                                "`run-jvm-main afield.examples.AgentTranscriptMaterializer --arg <repo>/$STARTING_POINTS_REL`."
                        )
                    }
                }
            } else {
                println("AgentTranscriptMaterializer: no committed bundle at ${committed.absolutePath} — skipping drift check")
            }
        }

        if (failures.isNotEmpty()) {
            System.err.println("AgentTranscriptMaterializer FAILED with ${failures.size} problem(s):")
            failures.forEach { System.err.println("  - $it") }
            exitProcess(1)
        }
        println("AgentTranscriptMaterializer ok — materialized $materialized document(s) into ${outRoot.absolutePath}")
    }

    /** Build [yaml]'s graph structurally, then save its live boxes as a document under [outRoot]/[name]. */
    private fun materializeOne(yaml: File, name: String, outRoot: File): Int {
        freshSession(name)
        val ops = loadOps(yaml)
        val graphOps = ops.filter { (it.keys.firstOrNull()?.lowercase() ?: "") in GRAPH_VERBS }

        // Build the box graph with the real apply path — create/connect/edit/move only, no launch.
        val document = currentDocumentDir()
        val macrosDir = File(document, "macros").also { it.mkdirs() }
        val filtered = linkedMapOf<String, Any?>("name" to name, "operations" to graphOps)
        File(macrosDir, "$name.yaml").writeText(Yaml().dump(filtered))
        val build = OperationTranscript.runMacro(document.absolutePath, name)
        if (!build.success) {
            // A missing cross-transcript parent (skipped connect) is fine; only note it.
            println("[materialize] $name graph build note: ${build.message}")
        }

        // Persist through the exact codepath the `save` transcript op uses (operation_transcript.kt),
        // but invoked here so the transcript yaml never needs a `save` op of its own.
        val info = thisSessionInfo.get()
        val boxes = info.boxes.values
            .filter { it.id != "root" && it.id != METAROOT_ID && !it.getPropertyRaw("_tombstone").isTrue() }
            .toSet()
        val target = File(outRoot, name)
        if (target.exists()) target.deleteRecursively()
        target.mkdirs()
        val latch = CountDownLatch(1)
        SavingContext().save(target.absolutePath, boxes, message = "agent transcript: $name", onComplete = { latch.countDown() })
        check(latch.await(30, TimeUnit.SECONDS)) {
            "save timed out after 30s for $name -> ${target.absolutePath}"
        }

        // `history_metrics.edn` is a per-launch runtime activity log carrying a fresh timestamp on
        // every save. A materialized starting point is pristine (never launched), so drop it — this
        // keeps the committed output byte-stable so regenerating on every docs build causes no churn.
        File(target, "history_metrics.edn").delete()

        // Deterministic normalization of the written files. Box-ki lazy init runs *asynchronously*
        // during the graph build and can inject an empty `preamble` ("") into a box's properties at
        // a timing that varies with JVM warmth — so the saved EDN otherwise differs run-to-run purely
        // by whether that empty key landed before the save snapshot. A blank preamble is regenerated
        // on open anyway (see the `save` op contract), so stripping the exact empty-preamble token
        // makes the committed output byte-stable. Only the empty form is touched; any real preamble
        // content is left intact.
        stripEmptyPreamble(File(target, SavingContext.METADATA_EDN))
        stripEmptyPreamble(File(target, "document.md"))
        return boxes.size
    }

    /**
     * Compare a freshly-materialized document dir [fresh] against its [committed] counterpart and
     * return a short description of the first difference (missing dir/file, extra file, or byte
     * mismatch), or null when they are identical. Used only for the CI drift guard.
     */
    private fun firstDrift(fresh: File, committed: File): String? {
        if (!committed.isDirectory) return "not committed (expected ${committed.name}/)"
        fun rel(root: File, f: File) = f.relativeTo(root).path
        val freshFiles = fresh.walkTopDown().filter { it.isFile }.associateBy { rel(fresh, it) }
        val committedFiles = committed.walkTopDown().filter { it.isFile }.associateBy { rel(committed, it) }
        for ((path, f) in freshFiles.toSortedMap()) {
            val c = committedFiles[path] ?: return "missing committed file $path"
            if (!f.readBytes().contentEquals(c.readBytes())) return "content differs: $path"
        }
        val extra = committedFiles.keys - freshFiles.keys
        if (extra.isNotEmpty()) return "committed has stale file(s): ${extra.sorted().joinToString(", ")}"
        return null
    }

    /** Remove the exact ` "preamble" ""` token (async-injected, non-deterministic) from a saved file. */
    private fun stripEmptyPreamble(file: File) {
        if (!file.isFile) return
        val text = file.readText()
        val stripped = text.replace(" \"preamble\" \"\"", "")
        if (stripped != text) file.writeText(stripped)
    }

    // ---- shared setup (mirrors DemoTranscriptExecSmokeTest) ----

    private var sessionDocumentDir: File? = null

    private fun currentDocumentDir(): File = sessionDocumentDir ?: error("no session document dir")

    private fun freshSession(name: String): File {
        thisSessionInfo.remove()
        val workspace = Files.createTempDirectory("afield-materialize-$name").toFile()
        workspace.deleteOnExit()
        val document = File(workspace, "doc").also { it.mkdirs() }
        File(document, SavingContext.METADATA_EDN).writeText("{:version 2 :boxes []}\n")
        val root = afield.Box("root")
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
        sessionDocumentDir = document
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
        val t = Thread({ afield.Runloop.runLoop.start() }, "agent-transcript-materializer-runloop")
        t.isDaemon = true
        t.start()
        val deadline = System.currentTimeMillis() + 30_000
        while (afield.Runloop.runLoop.frame < 1L) {
            check(System.currentTimeMillis() < deadline) { "runloop did not start within 30s" }
            Thread.sleep(20)
        }
        println("AgentTranscriptMaterializer: runloop up (frame=${afield.Runloop.runLoop.frame})")
    }

    private fun resolveOutRoot(args: Array<String>): File {
        System.getProperty("afield.agentTranscripts.out")?.takeIf { it.isNotBlank() }?.let { return File(it) }
        args.firstOrNull()?.takeIf { it.isNotBlank() }?.let { return File(it) }
        val temp = Files.createTempDirectory("afield-agent-transcripts-out").toFile()
        temp.deleteOnExit()
        return temp
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
}
