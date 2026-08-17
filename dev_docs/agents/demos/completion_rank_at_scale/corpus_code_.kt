// 1 / Where the corpus is, and what is in it.
//
// `src/jvmTest/resources/fieldSuggest/rank-corpus.tsv.gz` is 578 real completion carets, each one
// recorded from the code-use semantic sidecar's own `/complete` -- the endpoint the editor posts to
// per keystroke. This box reads the checked-in recording and shows you the population that a
// weighting decision now rests on. Nothing here is a summary written by hand.
//
// Run me (cmd-Enter / the run gutter). See dev_docs/completion_rank_at_scale.md.

import java.io.File
import java.util.zip.GZIPInputStream

// Field's working directory is the checkout it was started from; walk up to its root.
val repo = generateSequence(File(".").absoluteFile) { it.parentFile }
    .firstOrNull { File(it, "settings.gradle.kts").exists() }
    ?: error("no Field checkout above ${File(".").absolutePath} — start Field from the repo root")
val corpus = File(repo, "src/jvmTest/resources/fieldSuggest/rank-corpus.tsv.gz")

val lines = GZIPInputStream(corpus.inputStream()).bufferedReader().readLines()
val rows = lines.filter { it.startsWith("R\t") }.map { it.split('\t').drop(1) }
val cases = lines.filter { it.startsWith("C\t") }.map { it.split('\t').drop(1) }

// label, form, receiver, truth, base64(code), row indices
val payloadSize = cases.map { it[5].split(',').size }
val byForm = cases.groupBy { it[1] }

// The last expression is what the box shows in its output panel.
val report = buildString {
    appendLine("${corpus.name}  ${corpus.length() / 1024} KB on disk")
    appendLine("${cases.size} carets, ${payloadSize.sum()} payload rows, ${rows.size} distinct " +
        "(${"%.1f".format(payloadSize.sum().toDouble() / rows.size)}x interning)")
    appendLine()
    for ((form, group) in byForm) {
        val sizes = group.map { it[5].split(',').size }.sorted()
        appendLine("  $form: ${group.size} carets, payload median ${sizes[sizes.size / 2]}, " +
            "max ${sizes.last()}, ${group.count { it[5].split(',').size > 80 }} contested (> 80 rows, " +
            "so the popup had to leave something out)")
    }
    appendLine()
    appendLine("  the receivers a person is actually completing on, most-recorded first:")
    cases.groupingBy { it[2].ifBlank { "(no receiver resolved)" } }.eachCount()
        .entries.sortedByDescending { it.value }.take(12)
        .forEach { appendLine("    ${it.value.toString().padStart(4)}  ${it.key}") }
}

// The distribution the 80-row cut acts on. Everything to the right of 80 is a caret where ranking
// decides what a person can see and what they cannot.
showSignals(
    "payload rows per caret, sorted" to payloadSize.sorted().map { it.toDouble() },
    channel = "plot",  // its own channel: `display` REPLACES one, and the report below owns "output"
    title = "what the 80-row cut acts on",
)

report
