// 2 / The classification fix and the prior, live, on a receiver you choose.
//
// Last night's fix made a candidate's category come from WHERE IT IS DECLARED rather than from its bare
// name, because `scale` is declared in 42 categorised files in this tree, `close` in 21, `copy` in 16.
// This box calls the real thing -- `KotlinSourceIndex.getCompletionCategoryForSymbol`, with the real
// `declaredIn` the sidecar recorded -- and puts the real `CommonMemberPrior.rankOf` next to it.
//
// What you should notice is that for `afield.graphics.FLine` the index says NOTHING (`declines`), and
// that is the fix working: FLine.kt carries no `@file:CompletionCategory`, so rather than borrowing the
// category of an unrelated namesake -- which is how `FLine.moveTo` came to be filed under **Math** and
// rendered in a foreign section -- it declines, and the popup's category comes one step later in
// `KotlinCompletionSupport.inferCompletionCategory`, from the *package* `afield.graphics` -> Graphics.
// That last step is `internal`, so a box cannot call it; the corpus measurement in
// FieldSuggestPresentationTest can, and its numbers are quoted at the bottom.
//
// Change RECEIVER and re-run: `java.lang.Math` is a type nobody here can annotate -- no prior, and the
// index declines rather than borrowing -- which is exactly the population the deferred decision was
// about. `afield.stdlib.TriMesh` is another Field type.

import afield.CommonMemberPrior
import afield.ai.KotlinSourceIndex
import java.io.File
import java.util.zip.GZIPInputStream

val RECEIVER = "afield.graphics.FLine"

// Field's working directory is the checkout it was started from; walk up to its root.
// Spelled with explicit types on purpose: in the REPL a `generateSequence(...).firstOrNull { } ?: error(...)`
// chain infers `Any` here, and the first `File(repo, ...)` below then fails to resolve a constructor.
fun findRepoRoot(): File {
    var at: File? = File(".").absoluteFile
    while (at != null) {
        val here: File = at
        if (File(here, "settings.gradle.kts").exists()) return here
        at = here.parentFile
    }
    // `throw` and not `error(...)`: inside a box, `error` resolves to a Box member that returns Unit,
    // so a function ending in it fails to compile with "missing return statement".
    throw IllegalStateException("no Field checkout above ${File(".").absolutePath} - start Field from the repo root")
}
val repo: File = findRepoRoot()

val gz = File(repo, "src/jvmTest/resources/fieldSuggest/rank-corpus.tsv.gz")
val lines = GZIPInputStream(gz.inputStream()).bufferedReader().readLines()
val rows = lines.filter { it.startsWith("R\t") }.map { it.split('\t').drop(1) }
val case = lines.filter { it.startsWith("C\t") }.map { it.split('\t').drop(1) }
    .first { it[2] == RECEIVER && it[1] == "bare" }

// text, displayText, tail, icon, declaredIn, proximity
val shown = case[5].split(',').map { rows[it.toInt()] }
val classified = shown.map { r ->
    val fromIndex = KotlinSourceIndex
        .getCompletionCategoryForSymbol(symbolName = r[0], declaredIn = r[4].ifBlank { null })
    Triple(r, fromIndex, CommonMemberPrior.rankOf(RECEIVER, r[0]))
}

// The rows with a prior first: a prior is the only thing that moves a member up the popup, and
// it would be invisible in a slice of an alphabetical list (FLine's eight declared members all
// sort past the `by…` family).
val ordered = classified.sortedWith(compareBy({ if (it.third > 0) 0 else 1 }, { it.third }))

// The last expression is what the box shows in its output panel.
val report = buildString {
    appendLine("caret: ${case[0]}   receiver: $RECEIVER   the author wrote: ${case[3]}")
    appendLine("${shown.size} rows sent; the popup renders at most 80 of them")
    appendLine()
    appendLine("  ${"member".padEnd(24)}${"index says".padEnd(14)}prior  score  proximity  declaredIn")
    for ((r, fromIndex, prior) in ordered.take(20)) {
        appendLine("  ${r[0].take(23).padEnd(24)}${(fromIndex ?: "declines").padEnd(14)}" +
            "${prior.toString().padStart(5)}  ${CommonMemberPrior.scoreOf(prior).toString().padStart(5)}" +
            "  ${r[5].ifBlank { "-" }.padEnd(10)} ${r[4]}")
    }
    appendLine("  ... ${ordered.size - 20} more")
    appendLine()
    val withPrior = classified.count { it.third > 0 }
    val declines = classified.count { it.second == null }
    appendLine("$withPrior of ${shown.size} rows carry a @CommonMembers prior; the index declines on $declines")
    appendLine("of them rather than borrowing a namesake's category. `prior` is the 1-based position in the")
    appendLine("class's @CommonMembers list; `score` is what fieldSuggest.js adds for it (40, -2 per place).")
    appendLine()
    appendLine("Measured over all 578 recorded carets (FieldSuggestPresentationTest, and box 3 re-runs it):")
    appendLine("  a bare dot on a receiver this tree DECLARES     recall 0.911, median rank  9")
    appendLine("  a bare dot on a receiver it does not            recall 0.540, median rank 39")
    appendLine("See dev_docs/completion_rank_at_scale.md. That gap, and not the category weights, is where")
    appendLine("the remaining loss is: every weight variant tried moves foreign recall by at most 0.010.")
}

report
