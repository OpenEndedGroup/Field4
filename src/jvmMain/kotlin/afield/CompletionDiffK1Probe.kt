package afield

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Paths
import java.util.Base64

/*
FIELD_TEST
id: kotlin.completion-diffdump.probe
runner: jvm-main
entrypoint: afield.CompletionDiffK1Probe
tags: [probe, kotlin, completion]
timeoutSec: 400
expect:
  exitCode: 0
  stdoutContains:
    - "K1 DUMP DONE"
*/
/**
 * STAGE B (K1 side of the differential). Runs the REAL production K1 completion path
 * (`KotlinInterface2.complete` → K1 delegate, intro-warmed) over the shared TSV corpora and dumps the
 * raw candidates per fixture to `build/completion-corpus/k1-candidates.json`. The AA side is dumped
 * out-of-process by the sidecar spike (`--json-out`); `tools/completion_diff.py` reads both and diffs.
 *
 * Kept separate from `CompletionCorpusProbe` (which is the Phase-0 semantic SPEC over its own inline
 * fixtures) — this one is a pure raw-candidate dumper over the same fixtures the AA spike consumes, so
 * the two dumps line up by fixture id.
 */
object CompletionDiffK1Probe {

    private const val CARET = '‸' // ‸ — same marker the spike/TSVs use
    private val dumpJson = Json { encodeDefaults = true; prettyPrint = true }

    private val corpora = listOf(
        "tools/completion-corpus/scraped.tsv",
        "tools/completion-corpus/name-fixtures.tsv",
    )

    @Serializable
    data class K1DumpCandidate(val text: String, val displayText: String, val tail: String, val icon: String)

    @Serializable
    data class K1DumpFixture(
        val id: String,
        val member: String,
        val isDot: Boolean,
        val prefix: String,
        val candidates: List<K1DumpCandidate>,
    )

    @JvmStatic
    fun main(args: Array<String>) {
        val workspace = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize()
        val ki = KotlinInterface2(filePrefix = "diffdump")
        runBlocking { ki.warmCompletion() }

        val dump = mutableListOf<K1DumpFixture>()
        for (corpus in corpora) {
            val tsv = workspace.resolve(corpus)
            if (!Files.exists(tsv)) {
                System.err.println("[k1dump] MISSING corpus: $tsv")
                continue
            }
            tsv.toFile().readLines().filter { it.isNotBlank() }.forEach { line ->
                val cols = line.split('\t')
                val label = cols[0]
                val code = String(Base64.getDecoder().decode(cols[1]))
                val member = cols.getOrElse(2) { "" }
                val caret = code.indexOf(CARET); if (caret < 0) return@forEach
                val source = code.removeRange(caret, caret + 1)

                val prefix = Regex("[A-Za-z0-9_]*$").find(code.substring(0, caret))?.value ?: ""
                val isDot = code.substring(0, caret).dropLast(prefix.length).trimEnd().endsWith(".")

                val meta = when (val r = runBlocking { runCatching { ki.complete(source, caret) }.getOrNull() }) {
                    is CompletionResult.Success -> r.metadata
                    else -> emptyList()
                }
                val hit = meta.any { it.text == member || it.displayText.startsWith("$member(") || it.displayText.startsWith("$member ") }
                dump += K1DumpFixture(label, member, isDot, prefix,
                    meta.map { K1DumpCandidate(it.text, it.displayText, it.tail, it.icon) })
                println("[$label] ${if (isDot) "DOT" else "NAME"} prefix='$prefix' intended .$member ${if (hit) "✓" else "✗"} (${meta.size} cands)")
            }
        }

        val out = workspace.resolve("build/completion-corpus/k1-candidates.json")
        Files.createDirectories(out.parent)
        Files.writeString(out, dumpJson.encodeToString(dump))
        println("wrote ${dump.size} K1 fixtures → $out")
        println("K1 DUMP DONE")
    }
}
