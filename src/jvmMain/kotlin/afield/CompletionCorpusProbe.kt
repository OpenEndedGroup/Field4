package afield

import kotlinx.coroutines.runBlocking
import kotlin.script.experimental.api.SourceCodeCompletionVariant

/*
FIELD_TEST
id: kotlin.completion-corpus.probe
runner: jvm-main
entrypoint: afield.CompletionCorpusProbe
tags: [probe, kotlin, completion]
timeoutSec: 300
expect:
  exitCode: 0
  stdoutContains:
    - "CORPUS SPEC DONE"
*/
/**
 * PHASE 0 completion characterization corpus, framed as a SPEC (per the design call):
 *
 *  - Assertions are ENGINE RECALL + TYPES, order-independent: does the candidate SET contain the
 *    expected item (by call form / class / exact text), and with the right return type? Ranking is
 *    client-side (fieldSuggest.js) and deliberately NOT asserted here — `FieldSuggestPresentationTest`
 *    is the other half of the split, and asserts the ranking, grouping and titling by running that
 *    real JavaScript under node (see dev_docs/completion_popup_cover.md).
 *  - Goals encode the desired behavior, so the current K1 engine is EXPECTED to fail some of them.
 *    Those failures are the target a future K2 Analysis-API engine must satisfy. Anti-goals encode
 *    warts that must be fixed (e.g. the synthetic REPL wrapper class name leaking into candidates).
 *
 * The harness prints, per fixture, every goal as met (✓) or unmet (✗) with the matching candidate's
 * rank in the raw engine list, plus a met/unmet summary. It always exits 0 (report, not gate) — the
 * unmet set is the roadmap, and once we agree the baseline the met set becomes a regression gate.
 *
 * `|` marks the caret. Runs against the real KotlinInterface2.complete → K1 delegate (intro-warmed).
 */
object CompletionCorpusProbe {

    private const val CARET = '|'
    private const val LEAK_MARKER = "Corpus_completion__code_"

    private typealias Meta = List<SourceCodeCompletionVariant>

    // ---- candidate matchers (return rank index, or -1 if absent) ----
    private fun call(m: Meta, name: String): Int =
        m.indexOfFirst { it.displayText.startsWith("$name(") }
    private fun type(m: Meta, name: String): Int =
        m.indexOfFirst { it.text == name && it.icon == "class" }
    private fun exact(m: Meta, name: String): Int =
        m.indexOfFirst { it.text == name }
    /** case-insensitive EXACT presence — matches a capital typealias/class for a lowercase query,
     *  but NOT a longer identifier that merely shares the prefix (e.g. vec2Agglomerative). */
    private fun exactCI(m: Meta, name: String): Int =
        m.indexOfFirst { it.text.equals(name, ignoreCase = true) || it.displayText.startsWith("$name ", ignoreCase = true) }
    /** a named-argument candidate, e.g. `y = ` — how an IDE offers remaining params */
    private fun named(m: Meta, param: String): Int =
        m.indexOfFirst { it.text == param || it.displayText.startsWith("$param =") || it.displayText.startsWith("$param=") }
    private fun leaks(m: Meta): Int =
        m.indexOfFirst { (it.displayText + " " + it.tail).contains(LEAK_MARKER) }

    private sealed interface Check { val desc: String; val find: (Meta) -> Int }
    /** GOAL: expected to be present (rank >= 0). */
    private class Goal(override val desc: String, override val find: (Meta) -> Int) : Check
    /** ANTI: expected to be ABSENT (rank < 0). */
    private class Anti(override val desc: String, override val find: (Meta) -> Int) : Check

    private class Case(val fixture: String, val checks: List<Check>)

    private val CASES: List<Case> = listOf(
        Case("ve|", listOf(
            Goal("vec(…) factory present (lowercase)") { call(it, "vec") },
            Goal("Vec2 typealias present (needs case-insensitive)") { exactCI(it, "Vec2") },
            Goal("Vec3 typealias present (needs case-insensitive)") { exactCI(it, "Vec3") },
        )),
        Case("vec|", listOf(
            Goal("vec(…) factory present (lowercase)") { call(it, "vec") },
            Goal("Vec2 typealias present (needs case-insensitive)") { exactCI(it, "Vec2") },
            Goal("Vec3 typealias present (needs case-insensitive)") { exactCI(it, "Vec3") },
        )),
        Case("vec(|", listOf(
            Anti("no synthetic-class leak in candidates") { leaks(it) },
        )),
        Case("Vector3|", listOf(
            Goal("Vector3f class present") { type(it, "Vector3f") },
            Goal("Vector3d class present") { type(it, "Vector3d") },
        )),
        Case("Vector3f(|", listOf(
            Goal("named arg x present") { named(it, "x") },
            Goal("named arg y present") { named(it, "y") },
            Goal("named arg z present") { named(it, "z") },
            Anti("no synthetic-class leak in candidates") { leaks(it) },
        )),
        Case("Vector3f(x=3.0,|)", listOf(
            Goal("named arg y present") { named(it, "y") },
            Goal("named arg z present") { named(it, "z") },
            Anti("no synthetic-class leak in candidates") { leaks(it) },
        )),
        Case("Vector3f(z=3.0,|)", listOf(
            Goal("named arg x present") { named(it, "x") },
            Goal("named arg y present") { named(it, "y") },
            Anti("no synthetic-class leak in candidates") { leaks(it) },
        )),
        Case("FLine().move|", listOf(
            Goal("moveTo(…) member present") { call(it, "moveTo") },
        )),
        Case("FLine().moveTo(1,2,3).lin|", listOf(
            Goal("lineTo(…) member present") { call(it, "lineTo") },
        )),
        Case("with(afield.stdlib.Functions) {\n   sin(|\n}", listOf(
            Anti("no synthetic-class leak in candidates") { leaks(it) },
        )),
        Case("with(afield.stdlib.Functions) {\n   si|\n}", listOf(
            Goal("sin(…) member present") { call(it, "sin") },
            Goal("sinh(…) member present") { call(it, "sinh") },
        )),
        Case("with(afield.stdlib.Functions) {\n   sin(10).ab|\n}", listOf(
            Goal("abs(…) member present") { call(it, "abs") },
        )),
    )

    @JvmStatic
    fun main(args: Array<String>) {
        val ki = KotlinInterface2(filePrefix = "corpus")
        runBlocking { ki.warmCompletion() }

        var met = 0
        var unmet = 0
        for (case in CASES) {
            val caret = case.fixture.indexOf(CARET)
            val source = case.fixture.removeRange(caret, caret + 1)
            val display = case.fixture.replace("\n", "\\n")

            println("────────────────────────────────────────────────────────────")
            val meta: Meta = when (val r = runBlocking { runCatching { ki.complete(source, caret) }.getOrNull() }) {
                is CompletionResult.Success -> r.metadata
                else -> emptyList()
            }
            println("FIXTURE: $display   (cursor=$caret, ${meta.size} candidates)")

            for (check in case.checks) {
                val rank = check.find(meta)
                val (ok, evidence) = when (check) {
                    is Goal -> (rank >= 0) to (if (rank >= 0) "→ #${rank + 1}: ${meta[rank].displayText}${meta[rank].tail.let { if (it.isBlank()) "" else "  $it" }}" else "ABSENT")
                    is Anti -> (rank < 0) to (if (rank >= 0) "LEAK at #${rank + 1}: ${meta[rank].displayText}${meta[rank].tail}" else "clean")
                }
                if (ok) met++ else unmet++
                val kind = if (check is Anti) "ANTI" else "GOAL"
                println("  ${if (ok) "✓" else "✗"} [$kind] ${check.desc.padEnd(38)} $evidence")
            }
        }
        println("────────────────────────────────────────────────────────────")
        println("SUMMARY: $met met, $unmet unmet  (unmet = current K1 gaps / AA targets)")
        println("CORPUS SPEC DONE")
    }
}
