# Field Document: completion_rank_at_scale

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "fileManagerAccessHistory"]}
```

## 1. re-run the decision

<!-- field-box id="sweep" index="0" -->

### Narrative

From `code`

3 / Re-run the decision.

The refusal in dev_docs/completion_rank_at_scale.md is a measurement, so it should be checkable
without reading anything. This box shells out to `tools/rank_weight_lab.py`, which stages the
shipped fieldSuggest.js into a temp directory with one ranking constant rewritten and re-scores all
578 carets through it. Nothing in the repository is touched.

It needs the spec the test drops. If the box tells you it is missing, run once:
./gradlew jvmTest --tests 'afield.FieldSuggestPresentationTest'

```edn [field-box id="sweep" role="properties"]
{"id" "sweep" "index" 0 "name" "re-run the decision" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "re-run the decision" "_tombstone" false "frame" #afield/rect [60.0 2040.0 340.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "sweep_code_.kt" "lines" 42 "characters" 2132 "sha256" "c22f02fa9789409c28a1e81817c701ac4c82e6f68641f8af9d900fe62b0a24a5"}]}
```

```kotlin [field-property box="sweep" name="code" path="sweep_code_.kt"]
// 3 / Re-run the decision.
//
// The refusal in dev_docs/completion_rank_at_scale.md is a measurement, so it should be checkable
// without reading anything. This box shells out to `tools/rank_weight_lab.py`, which stages the
// shipped fieldSuggest.js into a temp directory with one ranking constant rewritten and re-scores all
// 578 carets through it. Nothing in the repository is touched.
//
// It needs the spec the test drops. If the box tells you it is missing, run once:
//   ./gradlew jvmTest --tests 'afield.FieldSuggestPresentationTest'

import java.io.File

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
val spec = File(repo, "build/completion-corpus/rank-spec.json")

val report = if (!spec.exists()) {
    "no spec at ${spec.path}\n\nrun once, then re-run me:\n" +
        "  ./gradlew jvmTest --tests 'afield.FieldSuggestPresentationTest'"
} else {
    val process = ProcessBuilder("python3", "tools/rank_weight_lab.py")
        .directory(repo).redirectErrorStream(true).start()
    val text = process.inputStream.bufferedReader().readText()
    process.waitFor()
    "R0 is the popup as shipped. Compare bare c-recall down the column: teaching CATEGORY_WEIGHT the\n" +
        "server's whole vocabulary gently (R2) moves it by 0.000; doing it hard (R1) costs 5.5 points.\n\n" + text
}

report
```

## 2. category + prior

<!-- field-box id="classify" index="1" -->

### Narrative

From `code`

2 / The classification fix and the prior, live, on a receiver you choose.

Last night's fix made a candidate's category come from WHERE IT IS DECLARED rather than from its bare
name, because `scale` is declared in 42 categorised files in this tree, `close` in 21, `copy` in 16.
This box calls the real thing -- `KotlinSourceIndex.getCompletionCategoryForSymbol`, with the real
`declaredIn` the sidecar recorded -- and puts the real `CommonMemberPrior.rankOf` next to it.

What you should notice is that for `afield.graphics.FLine` the index says NOTHING (`declines`), and
that is the fix working: FLine.kt carries no `@file:CompletionCategory`, so rather than borrowing the
category of an unrelated namesake -- which is how `FLine.moveTo` came to be filed under **Math** and
rendered in a foreign section -- it declines, and the popup's category comes one step later in
`KotlinCompletionSupport.inferCompletionCategory`, from the *package* `afield.graphics` -> Graphics.
That last step is `internal`, so a box cannot call it; the corpus measurement in
FieldSuggestPresentationTest can, and its numbers are quoted at the bottom.

Change RECEIVER and re-run: `java.lang.Math` is a type nobody here can annotate -- no prior, and the
index declines rather than borrowing -- which is exactly the population the deferred decision was
about. `afield.stdlib.TriMesh` is another Field type.

```edn [field-box id="classify" role="properties"]
{"id" "classify" "index" 1 "name" "category + prior" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "category + prior" "_tombstone" false "frame" #afield/rect [60.0 1040.0 340.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "classify_code_.kt" "lines" 88 "characters" 5261 "sha256" "24d3e686cdc5c56661edab6cd03f2611d864b7c2346150a3061c046bbe46465e"}]}
```

```kotlin [field-property box="classify" name="code" path="classify_code_.kt"]
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
```

## 3. the corpus

<!-- field-box id="corpus" index="2" -->

### Narrative

From `code`

1 / Where the corpus is, and what is in it.

`src/jvmTest/resources/fieldSuggest/rank-corpus.tsv.gz` is 578 real completion carets, each one
recorded from the code-use semantic sidecar's own `/complete` -- the endpoint the editor posts to
per keystroke. This box reads the checked-in recording and shows you the population that a
weighting decision now rests on. Nothing here is a summary written by hand.

Run me (cmd-Enter / the run gutter). See dev_docs/completion_rank_at_scale.md.

```edn [field-box id="corpus" role="properties"]
{"id" "corpus" "index" 2 "name" "the corpus" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "the corpus" "_tombstone" false "frame" #afield/rect [60.0 40.0 340.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "corpus_code_.kt" "lines" 65 "characters" 3375 "sha256" "62342e3482faa056f7a2d4f5a5c410f18e57f4060b5f6636b2f828c496380ed7"}]}
```

```kotlin [field-property box="corpus" name="code" path="corpus_code_.kt"]
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
```

