package afield

import afield.util.NodeTool
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The editor's completion **presentation** path, under test.
 *
 * Everything a user sees about a completion is decided client-side. The server deliberately returns
 * every candidate unranked — [CompletionCorpusProbe] says so in as many words ("Ranking is
 * client-side (fieldSuggest.js) and deliberately NOT asserted here") — so
 * `src/commonMain/resources/fieldSuggest.js` owns the ranking, the grouping, the category titles,
 * the recency weighting, the row markup, the documentation pane and the call-signature badge. Until
 * this test, nothing in the repository executed a line of it. The cost of that showed up on
 * 2026-08-05: commit `9ba22de77` hand-fixed the `Audio AI` group header rendering as **"Audio Ai"**,
 * a defect found by a human looking at a popup, because `titleForCategory` title-cases each word and
 * `Audio AI` was the first two-word category to exist.
 *
 * So this test runs **the real JavaScript**. It does not re-describe the rules in Kotlin: a Kotlin
 * transcription of the ranking formula would agree with itself forever and guard nothing. The
 * verbatim `fieldSuggest.js` is staged into a temp directory next to a minimal browser/Monaco host
 * and a stub of the one module it imports, and driven through its public entry point under `node`:
 * `installFieldSuggest(editor, monaco)` → `controller.open()` → a canned server reply → assertions
 * on the HTML the popup actually wrote. See `src/jvmTest/resources/fieldSuggest/probe.mjs`.
 *
 * Two tests, for two different kinds of failure:
 *
 *  - [`the popup ranks, groups and renders the way the editor expects`] is the behaviour suite —
 *    24 cases covering ranking, grouping, `tail`/return-type display, the request payload, the doc
 *    pane, accepting, escaping and NaN hardening. Add a case in `probe.mjs`.
 *  - [`every completion category has a correct popup title`] is the **class-level** guard behind the
 *    `Audio Ai` defect. That bug was a class, not an instance: any new multi-word or
 *    internally-capitalised category regresses identically. This enumerates every category the
 *    Kotlin side can put on a completion — every `@file:CompletionCategory(…)` in the tree, plus the
 *    fixed vocabulary inside [KotlinCompletionSupport] — renders each one through the real popup and
 *    requires the header to be that category's own spelling. It found `ALife` rendering as "Alife"
 *    the first time it ran.
 *
 * **Deliberately not covered**, so nobody mistakes this for an end-to-end test: Monaco itself (the
 * host is a stub, layout/scrolling is exercised for "does not throw" only), the compiled Kotlin/JS
 * bundle `afield.mjs` (stubbed — so the markdown renderer behind the doc pane is out of scope), the
 * WebSocket transport, and CSS. The live-browser cover for those is `tools/run_ui_regressions.py`,
 * which needs a running Field and is therefore not in `check`. See
 * `dev_docs/completion_popup_cover.md`.
 *
 * Needs a `node` >= [NodeTool.DEFAULT_MIN_MAJOR]. Set `AFIELD_SKIP_JS_TESTS=1` to opt out
 * deliberately and visibly rather than have the gate quietly stop testing anything.
 */
class FieldSuggestPresentationTest {

    @Test
    fun `the popup ranks, groups and renders the way the editor expects`() {
        runProbe("cases")
    }

    @Test
    fun `every completion category has a correct popup title`() {
        val categories = completionCategoryVocabulary()

        // A guard that can silently stop finding anything is not a guard. These two are the ones the
        // defect class is known to bite: `Audio AI` (fixed by hand in 9ba22de77) and `ALife` (found
        // by this test). If the scan below ever stops seeing them, it has rotted.
        for (sentinel in listOf("Audio AI", "ALife")) {
            assertTrue(
                categories.any { it.name == sentinel },
                "the category scan no longer finds `$sentinel`, so it is not scanning anything " +
                    "useful — found ${categories.map { it.name }}"
            )
        }
        assertTrue(
            categories.size >= 15,
            "expected the whole completion-category vocabulary, found ${categories.size}: $categories"
        )

        runProbe("categories", categorySpecJson(categories))
    }

    // ------------------------------------------------------------------ the category vocabulary

    private data class Category(val name: String, val origin: String)

    /**
     * Aliases the popup applies **on purpose**. Anything not listed here must render its own
     * spelling; adding to this map is a deliberate statement that two names share one group header.
     */
    private val intentionalAliases = mapOf(
        // `@file:CompletionCategory("Function")` labels the function-algebra package, and the popup
        // has grouped every callable under "Functions" since long before that package existed.
        "Function" to "Functions",
    )

    private fun completionCategoryVocabulary(): List<Category> =
        (declaredCategories() + derivedCategories())
            .distinctBy { it.name }
            .sortedBy { it.name }

    /** Every `@file:CompletionCategory("…")` (and declaration-level one) in the source tree. */
    private fun declaredCategories(): List<Category> {
        val annotation = Regex("""@(?:file:)?(?:[\w.]+\.)?CompletionCategory\s*\(\s*"([^"]+)"""")
        val found = LinkedHashSet<String>()
        val sourceRoot = repoRoot().resolve("src")
        Files.walk(sourceRoot).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".kt") }
                .forEach { path ->
                    // Only the head of a file can carry a file-level annotation, and reading whole
                    // files would be several megabytes for no gain.
                    val text = runCatching { path.readText() }.getOrNull() ?: return@forEach
                    if ("CompletionCategory" !in text) return@forEach
                    annotation.findAll(text).forEach { found.add(it.groupValues[1].trim()) }
                }
        }
        // Prose about the annotation gets caught too — including this file's own KDoc, which writes
        // it as `CompletionCategory("…")`. A category has letters in it; a placeholder does not.
        return found.filter { value -> value.any(Char::isLetter) }
            .map { Category(it, "@file:CompletionCategory") }
    }

    /**
     * The fixed vocabulary [KotlinCompletionSupport] can invent: the titles on the right of its
     * category `when` branches. Extracted from the source rather than listed here, so a new branch
     * joins the guard without anyone remembering to update it.
     */
    private fun derivedCategories(): List<Category> {
        val source = repoRoot()
            .resolve("src/jvmMain/kotlin/afield/KotlinCompletionSupport.kt")
            .readText()
        val functions = listOf(
            "normalizedCompletionCategory",
            "inferCompletionCategory",
            "categoryFromPath",
            "categoryFromQualifiedName",
        )
        val literal = Regex("""(?:->|return)\s+"([A-Z][A-Za-z ]*)"""")
        val found = LinkedHashSet<String>()
        for (function in functions) {
            val body = functionBody(source, function)
                ?: fail("could not find `$function` in KotlinCompletionSupport.kt — the category " +
                    "vocabulary scan needs updating")
            literal.findAll(body).forEach { found.add(it.groupValues[1].trim()) }
        }
        return found.map { Category(it, "KotlinCompletionSupport") }
    }

    /** From `fun <name>` to the matching close brace (or, for an expression body, end of statement). */
    private fun functionBody(source: String, name: String): String? {
        val start = source.indexOf("fun $name")
        if (start < 0) return null
        val open = source.indexOf('{', start)
        if (open < 0) return null
        var depth = 0
        var i = open
        while (i < source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open, i + 1)
                }
            }
            i++
        }
        return null
    }

    private fun categorySpecJson(categories: List<Category>): String {
        val entries = categories.joinToString(",\n") { category ->
            val expected = intentionalAliases[category.name] ?: category.name
            """  {"category": ${json(category.name)}, "expected": ${json(expected)}, """ +
                """"origin": ${json(category.origin)}}"""
        }
        return "{\n \"categories\": [\n$entries\n ]\n}\n"
    }

    private fun json(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    // ------------------------------------------------------------------ running the probe

    private fun runProbe(mode: String, categorySpec: String? = null) {
        if (System.getenv("AFIELD_SKIP_JS_TESTS") == "1") {
            println("FieldSuggestPresentationTest: skipped by AFIELD_SKIP_JS_TESTS=1")
            return
        }
        val node = NodeTool.resolveNode()
            ?: fail(
                "the completion-popup cover runs the real fieldSuggest.js under node: " +
                    "${NodeTool.unavailableMessage()}. Set AFIELD_SKIP_JS_TESTS=1 to opt out."
            )

        val dir = Files.createTempDirectory("field-suggest-presentation-")
        stage(dir)
        val spec = categorySpec?.let { dir.resolve("categories.json").also { p -> p.writeText(it) } }

        val command = buildList {
            add(node)
            add(dir.resolve("probe.mjs").toString())
            add(mode)
            spec?.let { add(it.toString()) }
        }
        val process = ProcessBuilder(command)
            .directory(dir.toFile())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(180, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            fail("the completion-popup probe ($mode) never finished:\n$output")
        }
        println(output.trimEnd())
        if (process.exitValue() != 0 || PASSED !in output) {
            fail("the completion-popup probe ($mode) failed (exit ${process.exitValue()}):\n$output")
        }
    }

    /**
     * Stage a runnable copy: the verbatim `fieldSuggest.js`, the harness, and the `package.json` that
     * makes node treat a `.js` file as the ES module it is.
     *
     * Everything is read off the **classpath**, which is the point: that makes `fieldSuggest.js` and
     * every harness file a declared input of the `jvmTest` task, so editing one re-runs this test
     * instead of leaving a stale UP-TO-DATE pass.
     */
    private fun stage(dir: Path) {
        for (name in listOf("host.mjs", "afield.mjs", "probe.mjs")) {
            dir.resolve(name).writeText(resource("/fieldSuggest/$name"))
        }
        dir.resolve("fieldSuggest.js").writeText(resource("/fieldSuggest.js"))
        dir.resolve("package.json").writeText("{\"type\": \"module\"}\n")
    }

    private fun resource(path: String): String =
        javaClass.getResource(path)?.readText()
            ?: fail("$path is not on the test classpath (expected it in commonMain or jvmTest resources)")

    private fun repoRoot(): Path {
        var dir = Path.of("").toAbsolutePath()
        while (!dir.resolve("settings.gradle.kts").exists()) {
            dir = dir.parent ?: fail("could not locate the repo root from ${Path.of("").toAbsolutePath()}")
        }
        return dir
    }

    private companion object {
        const val PASSED = "field-suggest presentation probe passed"
    }
}
