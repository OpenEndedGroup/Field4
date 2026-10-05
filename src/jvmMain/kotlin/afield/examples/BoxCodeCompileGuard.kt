package afield.examples

import afield.thisSessionInfo
import java.io.File
import kotlin.system.exitProcess

/**
 * The landing guard that box code still compiles: builds each named transcript's box graph and
 * compiles every box's Kotlin with evaluation skipped, through [DemoTranscriptExecSmokeTest]'s
 * compile-only path. Nothing is evaluated, so no window opens and it runs headless.
 *
 * It is scoped to what a landing changed. The paths come from the file named by
 * `AFIELD_GUARD_CHANGED_PATHS_FILE` (one repo-relative path per line, written by
 * `tools/check_staged_generated_artifacts.py`), or from the arguments; only top-level
 * `dev_docs/agents/transcripts/<name>.yaml` entries select anything. With neither, every
 * transcript is compiled, which takes as long as the smoke test's own sweep.
 *
 * Running the code is the smoke catalog's job (`examples.demo-transcript.exec`), which the
 * nightly runs; this guard only answers "did the edit break resolution or types".
 */
object BoxCodeCompileGuard {
    @JvmStatic
    fun main(args: Array<String>) {
        // As in DemoTranscriptExecSmokeTest: nothing here pumps the first-thread run loop.
        System.setProperty("java.awt.headless", "true")
        val transcriptsDir = DemoTranscriptExecSmokeTest.locateTranscriptsDir()

        val listed: List<String>? = args.toList().ifEmpty { null }
            ?: System.getenv("AFIELD_GUARD_CHANGED_PATHS_FILE")?.let { File(it).readLines() }
        val yamls = if (listed == null) {
            (transcriptsDir.listFiles { f -> f.isFile && f.extension == "yaml" } ?: emptyArray()).sortedBy { it.name }
        } else {
            listed.map(String::trim)
                .filter { it.startsWith("dev_docs/agents/transcripts/") && it.count { c -> c == '/' } == 3 && it.endsWith(".yaml") }
                .map { File(transcriptsDir, it.substringAfterLast('/')) }
                .filter(File::isFile)
                .distinct()
        }
        if (yamls.isEmpty()) {
            println("BoxCodeCompileGuard ok — no changed transcripts")
            return
        }

        DemoTranscriptExecSmokeTest.startRunloopDaemon()
        val failures = mutableListOf<String>()
        for (yaml in yamls) {
            try {
                DemoTranscriptExecSmokeTest.runCompileOnlyDemo(yaml, yaml.nameWithoutExtension, failures)
            } catch (t: Throwable) {
                failures.add("${yaml.nameWithoutExtension}: harness threw ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                thisSessionInfo.get()?.cleanup()
                thisSessionInfo.remove()
            }
        }
        if (failures.isNotEmpty()) {
            System.err.println("BoxCodeCompileGuard FAILED with ${failures.size} problem(s):")
            failures.forEach { System.err.println("  - $it") }
            exitProcess(1)
        }
        println("BoxCodeCompileGuard ok — ${yamls.size} transcript(s) compiled")
        // The runloop daemon and REPL engines hold non-daemon threads.
        exitProcess(0)
    }
}
