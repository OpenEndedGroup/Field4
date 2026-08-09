package afield

import kotlin.script.experimental.api.KotlinType
import java.io.File

/*
FIELD_TEST
id: completion.corpus-harvest.probe
runner: jvm-main
entrypoint: afield.CompletionCorpusHarvest
tags: [probe, kotlin, completion]
timeoutSec: 600
expect:
  exitCode: 0
  stdoutContains:
    - "HARVEST DONE"
  # The drift guard. Checked-in fixtures that no longer match the corpus they were harvested from would
  # silently invalidate every number CompletionLoweringProbe reports, so a mismatch fails here instead.
  stdoutNotContains:
    - "DRIFT"
*/
/**
 * Harvests [CompletionCorpus] fixtures — and, run without `AFIELD_HARVEST_WRITE=1`, guards them.
 *
 * Two modes, one entrypoint, because a generator and its drift guard have to agree on what they generate:
 *
 *  - **check** (default, and what the smoke catalog runs): harvest into memory, compare against the
 *    checked-in fixtures, print any difference and fail via the `DRIFT` marker. Writes nothing — a test
 *    that mutates the repo it is testing is not a test.
 *  - **write** (`AFIELD_HARVEST_WRITE=1`): regenerate `tools/completion-corpus/boxes/`. Run this by hand
 *    when the corpus changes or a macro changes what it expands to, and review the diff — the diff *is*
 *    the interesting part, since it shows exactly what context completion gains or loses.
 *
 * Harvesting needs no window, GPU or runloop: it expands macros (`applyMacros(previewOnly = false)`)
 * without evaluating box code. See [CompletionCorpus] for why the harvested preamble is the thing worth
 * checking in.
 */
object CompletionCorpusHarvest {

    @JvmStatic
    fun main(args: Array<String>) {
        val write = System.getenv("AFIELD_HARVEST_WRITE")?.trim() == "1"
        val dirs = System.getenv("AFIELD_LOWERING_CORPUS")?.trim()?.ifBlank { null }
            ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: CompletionCorpus.defaultDirs
        val fixtureDir = File(System.getenv("AFIELD_HARVEST_DIR")?.trim()?.ifBlank { null }
            ?: CompletionCorpus.FIXTURE_DIR)

        println("mode      ${if (write) "WRITE" else "check"}")
        println("corpus    ${dirs.joinToString(", ")}")
        println("fixtures  ${fixtureDir.path}")

        val ki = KotlinInterface2(implicitReceivers = listOf(KotlinType(Box::class)), filePrefix = "corpus-harvest")
        val problems = mutableListOf<String>()
        val harvested = CompletionCorpus.harvest(ki, dirs) { label, t ->
            problems += "$label: ${t.javaClass.simpleName}: ${t.message?.take(120)}"
        }
        val withPreamble = harvested.filter { it.preamble.isNotBlank() }
        println("harvested ${harvested.size} kotlin boxes; ${withPreamble.size} have a preamble after macro expansion")
        if (problems.isNotEmpty()) {
            println("  expansion problems (${problems.size}) — those boxes keep their on-disk preamble:")
            problems.take(10).forEach { println("    $it") }
        }
        // What the harvest actually bought, in the terms the lowering probe reports: these are the names
        // that were unresolved before and are declared now.
        val macroNames = withPreamble.flatMap { c ->
            Regex("""\b(?:val|var)\s+(?:Box|Once)\.(\w+)""").findAll(c.preamble).map { it.groupValues[1] }
        }.distinct().sorted()
        println("  preamble-declared names now in scope (${macroNames.size}): ${macroNames.take(24).joinToString(", ")}" +
            if (macroNames.size > 24) ", …" else "")
        val classes = withPreamble.count { "class " in it.preamble }
        println("  preambles carrying a class declaration (the /*supermap*/ form): $classes")

        if (write) {
            val files = CompletionCorpus.writeFixtures(harvested, fixtureDir)
            println("wrote ${harvested.size} manifest entries and $files preamble files → ${fixtureDir.path}")
            println("HARVEST DONE")
            return
        }

        val (fixtures, drift) = CompletionCorpus.readFixtures(fixtureDir, dirs)
        val byLabel = fixtures.associateBy { it.label }
        val differing = harvested.filter { c ->
            val f = byLabel[c.label] ?: return@filter false      // absence is already reported as drift
            f.preamble.trim() != c.preamble.trim()
        }
        drift.forEach { println("  DRIFT ${it.label}: ${it.what}") }
        differing.forEach { c ->
            println("  DRIFT ${c.label}: harvested preamble differs from the fixture")
            val f = byLabel[c.label]!!
            val a = f.preamble.trim().lines().toSet()
            val b = c.preamble.trim().lines().toSet()
            (b - a).take(3).forEach { println("      + $it") }
            (a - b).take(3).forEach { println("      - $it") }
        }
        if (drift.isEmpty() && differing.isEmpty()) {
            println("fixtures match the corpus: ${fixtures.size} entries, " +
                "${fixtures.count { it.preamble.isNotBlank() }} with a preamble")
        }
        println("HARVEST DONE")
        check(drift.isEmpty() && differing.isEmpty()) {
            "completion-corpus fixtures drifted; regenerate with AFIELD_HARVEST_WRITE=1"
        }
    }
}
