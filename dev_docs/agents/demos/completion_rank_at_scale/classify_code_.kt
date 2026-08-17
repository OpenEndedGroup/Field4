// 2 / The two server-side ranking inputs, live, on a receiver you choose.
//
// Last night's fix made a candidate's category come from WHERE IT IS DECLARED rather than from its
// bare name, because `scale` is declared in 42 categorised files in this tree and `close` in 21. This
// box calls that function -- `KotlinSourceIndex.getCompletionCategoryForSymbol`, the real one, with
// the real `declaredIn` the sidecar recorded -- over every row of one recorded caret, and puts
// `CommonMemberPrior`'s prior next to it.
//
// `w` marks a category `fieldSuggest.js`'s CATEGORY_WEIGHT scores above zero. Change RECEIVER and
// re-run: try java.lang.Math, kotlin.collections.List, afield.stdlib.TriMesh.

import afield.CommonMemberPrior
import afield.ai.KotlinSourceIndex
import java.io.File
import java.util.zip.GZIPInputStream

val RECEIVER = "afield.graphics.FLine"

// CATEGORY_WEIGHT's own keys, read out of the shipped popup source rather than copied.
// Field's working directory is the checkout it was started from; walk up to its root.
val repo = generateSequence(File(".").absoluteFile) { it.parentFile }
    .firstOrNull { File(it, "settings.gradle.kts").exists() }
    ?: error("no Field checkout above ${File(".").absolutePath} — start Field from the repo root")
val js = File(repo, "src/commonMain/resources/fieldSuggest.js").readText()
val weighted = Regex("""\["([^"]+)",\s*(\d+)]""")
    .findAll(Regex("""CATEGORY_WEIGHT = new Map\(\[[\s\S]*?]\);""").find(js)!!.value)
    .filter { it.groupValues[2].toInt() > 0 }.map { it.groupValues[1] }.toSet()

val gz = File(repo, "src/jvmTest/resources/fieldSuggest/rank-corpus.tsv.gz")
val lines = GZIPInputStream(gz.inputStream()).bufferedReader().readLines()
val rows = lines.filter { it.startsWith("R\t") }.map { it.split('\t').drop(1) }
val case = lines.filter { it.startsWith("C\t") }.map { it.split('\t').drop(1) }
    .first { it[2] == RECEIVER && it[1] == "bare" }

val shown = case[5].split(',').map { rows[it.toInt()] }
val classified = shown.map { r ->
    val category = KotlinSourceIndex
        .getCompletionCategoryForSymbol(symbolName = r[0], declaredIn = r[4].ifBlank { null })
        .orEmpty()
    Triple(r, category, CommonMemberPrior.rankOf(RECEIVER, r[0]))
}

// The last expression is what the box shows in its output panel.
val report = buildString {
    appendLine("caret: ${case[0]}   receiver: $RECEIVER   the author wrote: ${case[3]}")
    appendLine("${shown.size} rows sent; the popup renders at most 80 of them")
    appendLine()
    appendLine("  ${"member".padEnd(26)}${"category".padEnd(18)}w  prior  declaredIn")
    for ((r, category, prior) in classified.take(28)) {
        appendLine("  ${r[0].take(25).padEnd(26)}${category.ifBlank { "-" }.padEnd(18)}" +
            "${if (category.lowercase() in weighted) "w" else "."}  ${prior.toString().padStart(5)}  ${r[4]}")
    }
    if (classified.size > 28) appendLine("  ... ${classified.size - 28} more")
    appendLine()
    val unweighted = classified.count { it.second.lowercase() !in weighted }
    appendLine("$unweighted of ${classified.size} rows carry a category the popup scores 0. Over the whole")
    appendLine("corpus that is 89.6% of 39221 rows -- and the measurement says closing it moves visible")
    appendLine("recall by 0.000. See dev_docs/completion_rank_at_scale.md for why, and for what does move it.")
}

report
