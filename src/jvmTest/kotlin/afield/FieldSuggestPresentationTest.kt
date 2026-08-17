package afield

import afield.ai.FilestoreDir
import afield.ai.KotlinSourceIndex
import afield.ai.KotlinSourceIndexDb
import afield.ai.OpenAiFileStoreCache
import afield.util.NodeTool
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
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

    /**
     * **Recall at a rank a person can actually see.**
     *
     * Everything else that measures completion in this tree measures the *payload*:
     * `completion.lowering-invariant.probe` reports 95% caret recall, meaning the answer the author wrote
     * came back in the response. The popup renders at most `MAX_VISIBLE_ITEMS` (80) rows, so those are two
     * different numbers and the second one is the user's. Measured 2026-08-07, they diverge hard: at a bare
     * `f.` on an FLine, `moveTo` was in the response — index 159 of 249 — and **not on screen at all**.
     *
     * The mechanism, measured rather than assumed: at an EMPTY PREFIX every row scores alike in
     * `fieldSuggest.js`, its sort is stable, and the 80-row cut then keeps whatever order the server sent.
     * So "ranking is client-side" is true with a prefix and false without one, and at a bare dot the
     * server's order IS the user-visible order. (Replaying the same payloads with the answer moved to the
     * front puts every case at rank 1, which is what pins that down.) The sidecar therefore orders an
     * empty-prefix response by proximity to the receiver — its own members, then extensions written for its
     * type, then the `Any`/type-variable extensions that apply to everything — and this test holds it.
     *
     * Real payloads, real ranker, real categories: `rank-payloads.json` is recorded from the AA sidecar's
     * own `/complete` (see `dev_docs/completion_aa_sidecar.md` for the recording command), every row is
     * classified here through the server's own [KotlinCompletionSupport.classifyMetadata] so the categories
     * — a ranking input — are the ones a browser would receive, and the ranking is done by the verbatim
     * `fieldSuggest.js` staged off the classpath.
     *
     * **Hermetic on purpose.** `classifyMetadata` falls back to
     * [KotlinSourceIndex.getCompletionCategoryForSymbol], which until 2026-08-16 read
     * `~/.afield/filestore/kotlin_source_index.json` — machine-global, outside every checkout, and
     * rewritten by whichever Field ran last. The ranks below moved with it. Measured 2026-08-16, same
     * payloads, same commit, only the index state differing:
     *
     * | case | recorded | machine's index | no index at all | this checkout's source |
     * | --- | --- | --- | --- | --- |
     * | FLine bare dot, `moveTo` | 10 | 12 | 15 | off-screen |
     * | List bare dot, `filter` | 57 | 63 | 64 | 47 |
     * | Math qualifier, `toRadians` | 52 | 45 | off-screen | 45 |
     * | kotlin.math, `sin` | off-screen | off-screen | 73 | off-screen |
     *
     * Four states, four answers, one payload — so `moveTo` "falling from rank 10 to 12" was never a
     * code change at all; it was `~/.afield/filestore` being rewritten between the day the fixture was
     * cut and the day it was next run.
     *
     * So [withHermeticSourceIndex] builds the index from **this checkout's own source** into a temp
     * directory and pins [FilestoreDir] at it for the duration: three runs across two different
     * ambient machine states now give one answer. See `dev_docs/HERMETIC_COMPLETION_INPUTS.md`.
     */
    @Test
    fun `the answer a person typed is among the rows a person can see`() {
        withHermeticSourceIndex { runProbe("rank", classifiedRankSpec()) }
    }

    /**
     * Run [block] with the completion path reading a source index built here, from this checkout.
     *
     * Not a canned JSON blob: a blob would freeze the categories at the moment it was cut and quietly
     * stop describing the tree, and the point of classifying rows through the real
     * [KotlinCompletionSupport.classifyMetadata] is that a change to the categories moves this
     * measurement — which is correct, because it moves the real popup too. So the index is a function
     * of this checkout's source and of nothing else: same checkout, same index, same ranks.
     *
     * The roots are `KotlinSourceIndex.runSync`'s, minus `src/personal/kotlin`: personal code is
     * gitignored, so indexing it would put this measurement back outside the checkout by a different
     * door. Dropping `src/generatedLinalg/kotlin` as well was measured and changes none of the six
     * ranks, so it stays in for fidelity rather than because it matters.
     *
     * Asserts that the seed took effect, because a seam that is silently ignored is worse than none:
     * `ifft` must come back as `Math`, which only a source index that read
     * `src/jvmMain/kotlin/afield/stdlib/spectral.kt` and its `@file:CompletionCategory("Math")` can
     * say. `ifft` and not `fft` because `fft` is declared in more than one categorised file, and
     * [KotlinSourceIndex.getCompletionCategoryForSymbol] resolves a contested simple name by first
     * match — a sentinel has to be unambiguous or it is measuring the walk order.
     */
    private fun <T> withHermeticSourceIndex(block: () -> T): T {
        val dir = Files.createTempDirectory("field-suggest-source-index-")
        return FilestoreDir.withDirectory(dir) {
            val root = repoRoot()
            val definitions = KotlinSourceIndex.scanDefinitions(
                roots = listOf(root.resolve("src/jvmMain/kotlin"), root.resolve("src/generatedLinalg/kotlin")),
                relativeTo = root,
            )
            assertTrue(
                definitions.size >= 5000,
                "the seeded source index looks empty (${definitions.size} definitions) — " +
                    "scanDefinitions is not reading $root"
            )
            val db = KotlinSourceIndexDb(
                root = ".",
                apiKeyHash = null,
                updatedAtEpochMs = 0L,
                files = emptyList(),
                definitions = definitions,
            )
            dir.resolve("kotlin_source_index.json")
                .writeText(OpenAiFileStoreCache.defaultMapper().writeValueAsString(db))

            // Assert the hermeticity itself. If `latestIndex` from some earlier test in this JVM were
            // still answering, or if the pin were not reaching KotlinSourceIndex, this would read
            // whatever the machine holds — and the ranks below would be that machine's, silently.
            val seen = KotlinSourceIndex.loadIndex()
            assertTrue(
                seen != null && seen.definitions.size == definitions.size,
                "the pinned filestore is not the one being read: seeded ${definitions.size} " +
                    "definitions into ${FilestoreDir.describe()}, read back ${seen?.definitions?.size}"
            )
            assertEquals(
                "Math",
                KotlinSourceIndex.getCompletionCategoryForSymbol("ifft"),
                "the seeded index answers no category for `ifft`, so the ranks below are not measuring it"
            )
            block()
        }
    }

    /**
     * The recorded payloads, with each row's `category` and `commonMemberRank` filled in by the server's
     * own logic.
     *
     * The fixture holds what the *sidecar* sends (text/displayText/tail/icon); the browser receives those
     * rows after `KotlinCompletionSupport.classifyMetadata` plus [CommonMemberPrior], and both are popup
     * ranking inputs. Deriving them here rather than baking them into the fixture keeps the two in step: a
     * change to the classifier or to a `@CommonMembers` list moves this measurement, which is correct — it
     * moves the real popup too.
     *
     * `commonMemberRank` is per *case*, not per row, because it depends on the receiver the caret sits on
     * — so each case declares its `receiver` and rows are rewritten case by case. A row-at-a-time rewrite
     * cannot see which case it belongs to, which is why this is grouped rather than one regex over the
     * whole file.
     */
    private fun classifiedRankSpec(): String {
        val raw = resource("/fieldSuggest/rank-payloads.json")
        val caseHead = Regex(""""label":"([^"]+)","code":"(?:[^"\\]|\\.)*","receiver":"((?:[^"\\]|\\.)*)"""")
        val heads = caseHead.findAll(raw).toList()
        assertTrue(heads.size >= 4, "the recorded rank payloads look empty (found ${heads.size} cases)")
        // Deliberately a targeted rewrite rather than a JSON round-trip: this file has no JSON library on
        // its test classpath, and the fixture is machine-written with one row object per `{"text":…}`.
        val row = Regex(
            """\{"text":"((?:[^"\\]|\\.)*)","displayText":"((?:[^"\\]|\\.)*)","tail":"((?:[^"\\]|\\.)*)","icon":"([a-z]*)","declaredIn":"((?:[^"\\]|\\.)*)"}"""
        )
        var classified = 0
        var withPrior = 0
        var withDeclaredIn = 0
        val out = StringBuilder()
        var cursor = 0
        // Each case owns the text from its own header up to the next case's header.
        for ((index, head) in heads.withIndex()) {
            val segmentEnd = if (index + 1 < heads.size) heads[index + 1].range.first else raw.length
            out.append(raw, cursor, head.range.first)
            val receiver = unescape(head.groupValues[2])
            val segment = raw.substring(head.range.first, segmentEnd)
            out.append(row.replace(segment) { m ->
                val text = m.groupValues[1]
                val displayText = m.groupValues[2]
                val tail = m.groupValues[3]
                val icon = m.groupValues[4]
                val declaredIn = unescape(m.groupValues[5])
                val name = unescape(text)
                if (declaredIn.isNotBlank()) withDeclaredIn++
                // `declaredIn` is the whole reason a category can be right: it turns "what category is the
                // name `scale`?" — 42 answers in this tree — into "what category is `FLine.scale`?".
                // Recorded per row from the sidecar, and passed exactly as `execution.kt` passes it.
                val category = KotlinCompletionSupport.classifyMetadata(
                    Metadata(icon = icon, tail = unescape(tail), text = name,
                        displayText = unescape(displayText), category = ""),
                    name,
                    if (declaredIn.isBlank()) emptyMap()
                    else mapOf(AaCompletionClient.DECLARED_IN to declaredIn),
                ).category
                // Exactly what `execution.kt` forwards: the receiver's own declared prior, matched on the
                // bare member name so one annotation entry covers every overload.
                val rank = CommonMemberPrior.rankOf(receiver, name)
                if (rank > 0) withPrior++
                classified++
                // `declaredIn` is deliberately NOT forwarded to the popup — it is a server-side
                // classification input, not something `fieldSuggest.js` ranks on. What crosses the wire is
                // its consequence: the category.
                """{"text":"$text","displayText":"$displayText","tail":"$tail","icon":"$icon",""" +
                    """"category":${json(category)},"commonMemberRank":$rank}"""
            })
            cursor = segmentEnd
        }
        out.append(raw, cursor, raw.length)
        assertTrue(classified >= 500, "expected to classify the recorded rows, classified only $classified")
        // A prior that resolves for nothing would leave every rank below unchanged and this test would
        // still pass, so assert the seam carried. FLine's own cases supply these.
        assertTrue(
            withPrior >= 5,
            "no recorded row resolved a @CommonMembers prior ($withPrior) — CommonMemberPrior is not " +
                "reaching the fixture, so the ranks below are not measuring it"
        )
        // Same reasoning for the other seam. If `declaredIn` stopped being recorded, every category would
        // silently fall back to name-voting and these ranks would quietly become the old wrong ones.
        assertTrue(
            withDeclaredIn >= 900,
            "only $withDeclaredIn recorded rows carry `declaredIn`, so the categories below are being " +
                "resolved by bare name — re-enrich the fixture (see dev_docs/completion_aa_sidecar.md)"
        )
        return out.toString()
    }

    /**
     * The prior's magnitude lives in two places — `CommonMemberPrior` (server) and `fieldSuggest.js`
     * (client) — because the server sends a rank and the client turns it into points. If they drift, the
     * server keeps ranking members the popup then scores differently, and every rank in this file becomes
     * a measurement of the drift rather than of the design.
     */
    @Test
    fun `the common-member prior agrees between the server and the popup`() {
        val js = resource("/fieldSuggest.js")
        val base = Regex("""const COMMON_MEMBER_BASE = (\d+);""").find(js)?.groupValues?.get(1)?.toInt()
        val step = Regex("""const COMMON_MEMBER_STEP = (\d+);""").find(js)?.groupValues?.get(1)?.toInt()
        assertEquals(
            CommonMemberPrior.BASE, base,
            "fieldSuggest.js's COMMON_MEMBER_BASE and CommonMemberPrior.BASE have drifted"
        )
        assertEquals(
            CommonMemberPrior.STEP, step,
            "fieldSuggest.js's COMMON_MEMBER_STEP and CommonMemberPrior.STEP have drifted"
        )
    }

    private fun unescape(value: String): String =
        value.replace("\\\"", "\"").replace("\\\\", "\\").replace("\\n", "\n")

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

        // Half of the guard's honesty rests on the JavaScript being handed the same string the
        // annotation declares. If the server respelled `ALife` on the way out, the case below would be
        // grading a string no popup ever sees. `classifyMetadata` is the server's last word on a
        // candidate's category, so ask it, for every category, in process.
        for (category in categories) {
            val classified = KotlinCompletionSupport.classifyMetadata(
                Metadata(icon = "method", tail = "", text = "probe", displayText = "probe", category = category.name),
                "probe"
            )
            assertTrue(
                classified.category == category.name,
                "the server respells `${category.name}` (${category.origin}) as " +
                    "`${classified.category}` before the popup ever sees it, so the popup-title guard " +
                    "below would be testing the wrong string. Reconcile " +
                    "KotlinCompletionSupport.normalizedCompletionCategory with the declared spelling."
            )
        }

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
