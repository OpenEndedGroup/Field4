package afield

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/*
FIELD_TEST
id: kotlin.dataframe-plugin-cost.probe
runner: jvm-main
entrypoint: afield.DataFramePluginCostProbe
tags: [probe, kotlin, repl, dataframe, perf]
timeoutSec: 600
expect:
  exitCode: 0
  stdoutContains:
    - "DataFrame plugin cost probe complete"
*/
/**
 * What does wiring the DataFrame compiler plugin cost *every other box*?
 *
 * Field builds one [KotlinInterface2] per box, so anything added to the compilation state is paid
 * per box, not per DataFrame user. That made the wiring look expensive:
 * `examples.agent-transcripts.materialize` creates ~243 boxes and appeared to go from a 39–43s pass
 * to a 284s failure once the plugin and `dataframe-core` were added.
 *
 * **That reading was wrong, and this probe is what showed it.** Toggling the plugin moves per-engine
 * cost by ~4% (7449ms vs 7785ms over three engines), and `dataframe-core` adds four entries to a
 * 471-entry classpath. The 284s run had two Field JVMs and a Gradle daemon competing for the machine;
 * run alone the same test passes in ~103s. Engine cost is dominated by warm-up, not by either
 * addition — the first engine costs ~4.6s to construct and ~13s to first-evaluate, the second and
 * third ~200ms and ~1-3s. Treat any perf number taken on a shared machine as noise until it is
 * reproduced idle.
 *
 * Kept because it is the cheapest way to answer "what did this cost every other box?", and because
 * the two candidate costs need different fixes if one ever does dominate:
 *
 *  - the **plugin**: `-Xplugin=libs/dataframe-plugin.jar` (~9.7MB) is loaded and its FIR extensions
 *    are registered into every compilation state. Fix: make it opt-in per document.
 *  - the **classpath**: `dataframe-core` is an `implementation` dependency, so it joins the
 *    `dependenciesFromClassloader(wholeClasspath = true)` snapshot every engine takes. Fix: attach it
 *    on demand the way `@DependsOn` already attaches maven artifacts, so only documents that ask pay.
 *
 * Run with `--arg on` / `--arg off` to toggle just the plugin (the classpath is fixed at build time,
 * so the difference between the two runs isolates the plugin's share). The property is read through a
 * `lazy`, so it must be set before the first engine is built — which is why this is an entrypoint
 * rather than something a box can call.
 */
object DataFramePluginCostProbe {

    private const val ENGINES = 3

    @JvmStatic
    fun main(args: Array<String>) {
        val mode = args.firstOrNull() ?: "on"
        require(mode == "on" || mode == "off") { "expected --arg on|off, got '$mode'" }
        System.setProperty("afield.dataframePlugin", if (mode == "on") "true" else "false")

        println("DataFrame plugin cost probe: mode=$mode")
        println("  plugin args: ${KotlinInterface2.compilerPluginArgs}")

        val constructionMs = mutableListOf<Long>()
        val firstEvalMs = mutableListOf<Long>()

        repeat(ENGINES) { index ->
            val t0 = System.nanoTime()
            val ki = KotlinInterface2(filePrefix = "cost-probe-$mode-$index")
            val t1 = System.nanoTime()
            // The first eval is what forces the compilation state to do its real work.
            val result = runBlocking { withTimeout(240_000) { ki.exec("1 + 1") } }
            val t2 = System.nanoTime()
            require(result.toString().contains("2")) { "engine $index did not evaluate: $result" }

            constructionMs += (t1 - t0) / 1_000_000
            firstEvalMs += (t2 - t1) / 1_000_000
            println("  engine $index: construct=${constructionMs.last()}ms firstEval=${firstEvalMs.last()}ms")
        }

        println(
            "  totals mode=$mode construct=${constructionMs.sum()}ms firstEval=${firstEvalMs.sum()}ms " +
                "perEngine=${(constructionMs.sum() + firstEvalMs.sum()) / ENGINES}ms"
        )
        println("DataFrame plugin cost probe complete")
    }
}
