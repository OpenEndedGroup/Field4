package afield.runtime.completion

import afield.data.table.div
import afield.data.table.get
import afield.data.table.minus
import afield.data.table.plus
import afield.data.table.rem
import afield.data.table.times
import afield.runtime.execution.plus
import afield.runtime.lifecycle.plus
import afield.runtime.lifecycle.rem
import afield.ui.notation.with

import afield.audio.synth.*
import afield.document.workspace.metadata
import afield.graphics.vulkan.*
import afield.media.stream.*
import afield.media.video.*
import afield.runtime.kotlin.CompletionResult
import afield.runtime.kotlin.KotlinInterface2
import afield.ui.output.templates.filter
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.Base64
import kotlin.script.experimental.api.SourceCodeCompletionVariant

/*
FIELD_TEST
id: kotlin.completion-docscrape.probe
runner: jvm-main
entrypoint: afield.runtime.completion.CompletionDocScrapeProbe
tags: [probe, kotlin, completion]
timeoutSec: 300
expect:
  exitCode: 0
  stdoutContains:
    - "DOCSCRAPE PROBE DONE"
*/

/**
 * Asks how completion does on Field's own documentation: the fixtures are real doc code with the
 * caret put back where the author was typing.
 *
 * `tools/scrape_completion_corpus.py` walks the Kotlin fences in `public/docs`, cuts each sampled
 * line at a member access, and records the member the page actually used into
 * `tools/completion-corpus/scraped.tsv` as a label, the code with a `‸` for the caret, and that
 * member's name. This probe replays each one through [KotlinInterface2] and reports whether the
 * member came back and at what rank, with the top candidates printed under it so an answer can be
 * read rather than just scored.
 *
 * Alongside the rank it reports whether the receiver type resolved at all, judged by how many
 * candidates came back: more than two hundred is read as completion having fallen back to offering
 * every name in scope. The two readings belong together, because a member can sit at a good rank in
 * a list that was never about the receiver.
 *
 * It is a reading and not a gate: it always exits 0. With the scraped file absent it says so and
 * still prints its done marker, so the smoke entry shows that the probe ran rather than that it had
 * anything to run on.
 */

/**
 * PHASE 0 (exploratory): run REAL doc code through completion. Fixtures are scraped from
 * `diffstatic{_runner}` kotlin blocks in public/docs, with a caret dropped mid-member-access and
 * the remainder of the line deleted (tools/scrape_completion_corpus.py → tools/completion-corpus/scraped.tsv,
 * columns: label, base64(code with ‸ caret), intendedMember).
 *
 * For each fixture we ask the honest question: does the engine surface the member the doc code
 * ACTUALLY used at that caret (by rank), or does it collapse to an undifferentiated name dump
 * (a sign the receiver type didn't resolve)? Plus the synthetic-class-leak anti-goal. This is a
 * recall/quality read on in-context completion, not a pass/fail gate — it always exits 0.
 */
object CompletionDocScrapeProbe {

    private const val CARET = '‸'
    private const val LEAK_MARKER = "Corpus_completion__code_"
    private const val TOP = 10
    private typealias Meta = List<SourceCodeCompletionVariant>

    @JvmStatic
    fun main(args: Array<String>) {
        val tsv = File(System.getProperty("user.dir"), "tools/completion-corpus/scraped.tsv")
        if (!tsv.exists()) {
            println("MISSING ${tsv.path} — run tools/scrape_completion_corpus.py")
            println("DOCSCRAPE PROBE DONE"); return
        }
        val ki = KotlinInterface2(filePrefix = "docscrape")
        runBlocking { ki.warmCompletion() }

        var surfaced = 0
        var total = 0
        for (line in tsv.readLines().filter { it.isNotBlank() }) {
            val (label, b64, member) = line.split('\t').let { Triple(it[0], it[1], it.getOrElse(2){""}) }
            val fixture = String(Base64.getDecoder().decode(b64))
            val caret = fixture.indexOf(CARET)
            val source = fixture.removeRange(caret, caret + 1)
            val caretLine = source.substring(0, caret).substringAfterLast('\n')
            total++

            val meta: Meta = when (val r = runBlocking { runCatching { ki.complete(source, caret) }.getOrNull() }) {
                is CompletionResult.Success -> r.metadata
                else -> emptyList()
            }
            println("────────────────────────────────────────────────────────────")
            println("[$label]  caret line: `$caretLine`${CARET}   (${meta.size} candidates)")

            // did the member the doc actually used come back?
            val rank = meta.indexOfFirst { it.text == member || it.displayText.startsWith("$member(") }
            if (rank >= 0) {
                surfaced++
                println("  ✓ intended `.$member` present → #${rank + 1}: ${meta[rank].displayText}${tail(meta[rank])}")
            } else {
                println("  ✗ intended `.$member` ABSENT")
            }

            // receiver-type health heuristic: a resolved dot-receiver yields a focused member list;
            // thousands of candidates means the receiver type didn't resolve (fell back to name dump).
            val dumpish = meta.size > 200
            println("  receiver: ${if (dumpish) "UNRESOLVED? (${meta.size} cands — name-dump)" else "resolved (${meta.size} cands)"}")

            val leak = meta.indexOfFirst { (it.displayText + " " + it.tail).contains(LEAK_MARKER) }
            if (leak >= 0) println("  ⚠ leak at #${leak + 1}: ${meta[leak].displayText}${meta[leak].tail}")

            meta.take(TOP).forEachIndexed { i, v ->
                println("     ${(i + 1).toString().padStart(2)}. ${v.displayText.take(46).padEnd(46)}${tail(v)}")
            }
        }
        println("────────────────────────────────────────────────────────────")
        println("SUMMARY: intended member surfaced in $surfaced/$total scraped fixtures")
        println("DOCSCRAPE PROBE DONE")
    }

    private fun tail(v: SourceCodeCompletionVariant): String =
        v.tail.let { if (it.isBlank()) "" else "  $it" }
}
