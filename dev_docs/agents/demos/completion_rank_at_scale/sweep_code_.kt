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
