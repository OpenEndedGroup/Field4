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
    # Assert the hermeticity, not just the answer. A harvest that quietly fell back to this machine's
    # classpath would still print HARVEST DONE and could still match the fixtures — on this machine.
    - "imports   hermetic"
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

        // Harvest against the repository, not the machine. `prop<T>(name)` writes an import for T only
        // when T resolves to exactly one class, so a class in src/personal sharing a simple name with
        // core Field — one `rectgame.Window` was enough — deletes that import from every preamble that
        // mentions it, and a `.field/fieldPreferencesJson` blacklist edit can do the same. Without this
        // the guard below compares fixtures checked into git against an answer only this machine gives.
        ImportResolution.hermetic =
            System.getenv("AFIELD_IMPORT_HERMETIC")?.trim()?.let { it == "1" } ?: true

        println("mode      ${if (write) "WRITE" else "check"}")
        println("corpus    ${dirs.joinToString(", ")}")
        println("fixtures  ${fixtureDir.path}")
        println("imports   ${ImportResolution.describe()}")

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

        // Every type a `prop<T>` macro asked for an import for. If one of them resolves differently here
        // than in a clean checkout, the fixtures are about to be judged against an answer only this
        // machine gives — which is exactly how the `Window` import got added and removed three times.
        // Print the disagreement where it happens, with the candidates, rather than leaving the next
        // person to infer it from a nineteen-line diff.
        val propTypes = withPreamble.flatMap { c ->
            Regex("""\bby\s+(?:Inheritance|Onceing)<([A-Za-z_][A-Za-z0-9_.]*)""").findAll(c.preamble)
                .map { it.groupValues[1].substringAfterLast('.') }
        }.distinct().sorted()
        val diverging = ImportResolution.divergences(propTypes)
        if (diverging.isEmpty()) {
            println("  import resolution: all ${propTypes.size} prop<T> types agree with a clean checkout")
        } else {
            println("  import resolution: ${diverging.size} of ${propTypes.size} prop<T> types resolve " +
                "differently on this machine. The harvest used the clean-checkout answer:")
            diverging.forEach { d ->
                println("    ${d.name}: clean=${d.hermetic ?: "no import"} | here=${d.machine ?: "no import (ambiguous)"}")
                println("      candidates on this classpath: ${d.candidates.joinToString(", ")}")
            }
        }

        if (write) {
            val files = CompletionCorpus.writeFixtures(harvested, fixtureDir)
            println("wrote ${harvested.size} manifest entries and $files preamble files → ${fixtureDir.path}")
            println("HARVEST DONE")
            return
        }

        val (fixtures, drift) = CompletionCorpus.readFixtures(fixtureDir, dirs)
        val byLabel = fixtures.associateBy { it.label }
        // A fixture is not merely "different from the harvest", it can be *wrong on its own terms*: a
        // preamble that declares `by Inheritance<Window>()` and does not import Window describes a
        // context in which Window does not resolve. That is what hand-deleting the import line left
        // behind, and a diff against the harvest cannot say which side of it is right — this can.
        val unimported = fixtures.mapNotNull { f ->
            CompletionCorpus.typesDeclaredWithoutAnImport(f.preamble).takeIf { it.isNotEmpty() }
                ?.let { f.label to it }
        }
        unimported.forEach { (label, types) ->
            println("  DRIFT $label: fixture declares ${types.joinToString(", ")} but imports " +
                "${if (types.size == 1) "it" else "them"} nowhere — the preamble cannot compile as written")
        }
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
        if (drift.isEmpty() && differing.isEmpty() && unimported.isEmpty()) {
            println("fixtures match the corpus: ${fixtures.size} entries, " +
                "${fixtures.count { it.preamble.isNotBlank() }} with a preamble, " +
                "every declared property type in scope")
        }
        println("HARVEST DONE")
        check(drift.isEmpty() && differing.isEmpty() && unimported.isEmpty()) {
            "completion-corpus fixtures drifted; regenerate with AFIELD_HARVEST_WRITE=1"
        }
    }
}
