/*
3 / Re-run the decision.

The refusal in dev_docs/completion_rank_at_scale.md is a measurement, so it should be checkable
without reading anything. This box shells out to `tools/rank_weight_lab.py`, which stages the
shipped fieldSuggest.js into a temp directory with one ranking constant rewritten and re-scores all
578 carets through it. Nothing in the repository is touched.

On a fresh checkout the tool first writes the spec it measures, which takes a minute or two.
*/

import java.io.File

/*
Field's working directory is the checkout it was started from; walk up to its root.
Spelled with explicit types on purpose: in the REPL a `generateSequence(...).firstOrNull { } ?: error(...)`
chain infers `Any` here, and the first `File(repo, ...)` below then fails to resolve a constructor.
*/
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
val process =
    ProcessBuilder("uv", "run", "--project", repo.absolutePath, "python", "tools/rank_weight_lab.py")
        .directory(repo)
        .redirectErrorStream(true)
        .start()
val text = process.inputStream.bufferedReader().use { it.readText() }
val exit = process.waitFor()
check(exit == 0) { "Ranking measurement exited with status $exit:\n$text" }
val report = "R0 is the popup as shipped. Compare the measured bare c-recall across the ranking variants.\n\n" + text

report
