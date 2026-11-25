package afield.examples

import afield.app.server.thisSessionInfo
import java.io.File
import kotlin.system.exitProcess

/**
 * The landing guard that box code still compiles: each named transcript goes down the path
 * [DemoTranscriptExecSmokeTest] classifies it for. COMPILE_ONLY transcripts (the ones that would
 * open a window) build their graph and compile with evaluation skipped; EXECUTE transcripts are
 * headless-safe and run, because a later box may need what an earlier one defines when evaluated;
 * KOTLINJS transcripts compile through the Kotlin/JS path.
 *
 * It is scoped to what a landing changed. The paths come from the file named by
 * `AFIELD_GUARD_CHANGED_PATHS_FILE` (one repo-relative path per line, written by
 * `tools/check_staged_generated_artifacts.py`), or from the arguments; only top-level
 * `dev_docs/agents/transcripts/<name>.yaml` entries select anything. With neither, every
 * transcript is compiled, which takes as long as the smoke test's own sweep.
 *
 * Running the code is the smoke catalog's job (`examples.demo-transcript.exec`), which the
 * nightly runs; this guard only answers "did the edit break resolution or types".
 *
 * [COMPILE_ONLY_HERE] names the EXECUTE transcripts this guard compiles instead of running: ones
 * whose boxes are independent (no box reads a value another box defines when evaluated) and whose
 * run is long enough to trip the Runloop watchdog on a loaded machine. The smoke test still
 * executes them.
 */
object BoxCodeCompileGuard {
    /**
     * EXECUTE transcripts compiled, not run, here — each with why. An entry must have independent
     * boxes; the smoke test's EXECUTE sweep keeps running it.
     */
    internal val COMPILE_ONLY_HERE: Map<String, String> = mapOf(
        // Ten independent evolution boxes; on 2026-11-21 at load 44, evolve_ca held the Runloop
        // 29.2 s of the watchdog's 30 s, so at load ~60 it was interrupted every night.
        "alife_evolution" to "independent boxes; evolve_lenia/evolve_ca/map_elites hold the Runloop 17-29 s each",
        // Five independent vehicle boxes; evolved_wiring alone holds the Runloop 18 s.
        "braitenberg_where_next" to "independent boxes; evolved_wiring holds the Runloop 18 s",
    )

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
            val name = yaml.nameWithoutExtension
            try {
                // Same routing as the smoke test. Compile-only is wrong for the other classes: a
                // KOTLINJS transcript's code is Kotlin/JS against the browser API (onStart, THREE,
                // Tone), and an EXECUTE transcript may read types or values an earlier box defines
                // only when evaluated (shared_kotlin_garden, the DataFrame plugin's generated schema).
                when (name) {
                    in COMPILE_ONLY_HERE -> DemoTranscriptExecSmokeTest.runCompileOnlyDemo(yaml, name, failures)
                    in DemoTranscriptExecSmokeTest.COMPILE_ONLY -> DemoTranscriptExecSmokeTest.runCompileOnlyDemo(yaml, name, failures)
                    in DemoTranscriptExecSmokeTest.EXECUTE -> DemoTranscriptExecSmokeTest.runExecuteDemo(yaml, name, failures)
                    in DemoTranscriptExecSmokeTest.KOTLINJS -> DemoTranscriptExecSmokeTest.runKotlinJsDemo(yaml, name, failures)
                    else -> failures.add("$name: UNCLASSIFIED — add to EXECUTE or COMPILE_ONLY in DemoTranscriptExecSmokeTest")
                }
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
