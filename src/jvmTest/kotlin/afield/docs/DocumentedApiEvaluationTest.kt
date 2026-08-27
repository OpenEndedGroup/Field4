package afield.docs

import afield.examples.DemoTranscriptExecSmokeTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **Compiled is not run.** Which documented APIs does CI actually *evaluate*, and which does it only
 * type-check?
 *
 * ## The gap
 *
 * [DemoTranscriptExecSmokeTest] partitions the transcript corpus into three verification levels, and
 * `AgentTranscriptIndexGuard` publishes the middle one on `public/docs/agent_transcripts.md` in as
 * many words: `COMPILE_ONLY("compile", "compiled, not run")`. That is an honest label on an honest
 * mechanism — a transcript that opens a GLFW window has no business spinning a render loop headless
 * at 2am, and compiling its boxes through the real K2 path still catches the unresolved-reference
 * class of defect the harness was built for.
 *
 * What is *not* stated anywhere is the consequence: an API taught on a published page can be "covered"
 * by a green gate that never calls it. A signature change keeps compiling; a behaviour change is
 * invisible. The page keeps teaching it and the build keeps agreeing.
 *
 * ## Why measuring it is the hard part, and why it keeps being got wrong
 *
 * The obvious measurement — *"which documented APIs appear only in `COMPILE_ONLY` transcripts"* —
 * **over-reports by roughly 3×**, and every previous pass at this question that measured one
 * population got a number it could not act on. Measured 2026-08-27 over camelCase call sites
 * ([CALL], see there for why the population is camelCase):
 *
 * ```text
 * camelCase APIs called by the transcript corpus            506
 *   … also named as a call on a published page              421   <- the denominator
 *   … reached by an EXECUTE or KOTLINJS transcript          407
 *   … reached only by a COMPILE_ONLY transcript              14   <- the tempting number
 *   … of those 14, evaluated by a smoke or a jvmTest          9
 *   … evaluated by something, anything, in CI                416   <- what is floored
 *   … evaluated by nothing at all                             5   <- what is ledgered
 * ```
 *
 * The brief that dispatched this work carried the same error in miniature. Its flagship example was
 * `fromContext`: three transcripts demonstrate it, all three are `COMPILE_ONLY`, five published pages
 * teach it, and — the part a transcripts-only scan cannot see —
 * `ContextSearchOrderLedgerSmokeTest.kt:84` evaluates `fromContext<AtomicInteger>()` in live box
 * Kotlin as the FIELD_TEST `examples.context.search-order-ledger`. The example was a false alarm. The
 * *class* of defect it described was real, and it is the five entries in [COMPILE_ONLY_REMAINDER].
 *
 * So the union of three exercise populations is the only honest denominator, and this class computes
 * it: `EXECUTE`/`KOTLINJS` transcripts, jvm-main FIELD_TEST smokes, and `jvmTest` sources.
 *
 * ## The shape of the guard
 *
 * A **ledger read in both directions**, in the manner of `ServerSurfaceLedgerTest` and
 * [ImpossibilityClaimContractTest.theAnnotatedPageLedgerIsNeitherShortNorStale], rather than a
 * ceiling on a count:
 *
 * - **Forward.** Everything the scan finds compile-only-and-nothing-else must be declared in
 *   [COMPILE_ONLY_REMAINDER] with a reason. A newly documented API that lands with no evaluator is
 *   therefore red until somebody either evaluates it, writes down why they cannot, or stops
 *   documenting it. That is the default this flips.
 * - **Backward.** Every declared row must still be compile-only. A row that quietly acquired an
 *   evaluator is a *stale ledger entry* and fails here, which is what stops this file becoming a list
 *   of problems that used to exist — the exact degradation `DerivedCountGuard.Derivation.candidates`
 *   argues the backward direction is the only cure for.
 *
 * A ceiling on the remainder was the other option and is worse: 5 is small enough that a ceiling of,
 * say, 8 would let three regressions land silently, and a ceiling of 5 would be a ledger with the
 * names left out.
 *
 * ## What this does not claim
 *
 * That the 407 are *well* exercised. A call site in an EXECUTE transcript proves the call happens, not
 * that anything downstream of it is asserted. This measures reachability of evaluation, which is the
 * property `COMPILE_ONLY` does not have, and nothing stronger. The floors below are on the population
 * so that the measurement cannot collapse to a green tick over nothing — the failure this whole class
 * of guard exists against, and the one it is easiest to commit while building one.
 */
class DocumentedApiEvaluationTest {

    private companion object {

        /**
         * Floor on the documented APIs the transcript corpus reaches at all. Measured 2026-08-27:
         * **421**.
         *
         * The denominator, floored for the reason [CitationContractTest.MIN_UNIQUE_ANCHORS] is: if
         * the corpus stopped resolving — a moved transcripts directory, a changed yaml shape — every
         * other assertion here would pass over an empty set and read as evidence. 400 rather than 420
         * because retiring one large transcript legitimately moves this by tens; the floor is against
         * collapse, not against drift.
         */
        const val MIN_DOCUMENTED_APIS = 400

        /**
         * Floor on the documented APIs that anything in CI evaluates — the **union** of the three
         * populations, which is what this class prints beside it. Measured 2026-08-27: **416**.
         *
         * Not to be confused with the 407 in the table above, which is the transcript half alone. The
         * nine between them are APIs no EXECUTE transcript reaches and a `FIELD_TEST` smoke or a
         * `jvmTest` does, and they are exactly the population a transcripts-only scan misreports. A
         * floor written against 407 would have been a floor on the wrong number by nine, in the file
         * whose whole subject is floors written against the wrong population.
         *
         * This is the number the class is named after, and the one worth watching: reclassifying a
         * large EXECUTE transcript down to COMPILE_ONLY is a one-line edit in
         * [DemoTranscriptExecSmokeTest] that costs no test its green anywhere else in the tree. Same
         * margin argument as [MIN_DOCUMENTED_APIS].
         */
        const val MIN_EVALUATED_APIS = 380

        /**
         * A call site, restricted to **camelCase** identifiers of five characters or more.
         *
         * The restriction is what makes the census exact rather than indicative, and it is chosen
         * against the obvious alternative. With a plain `\b[a-z]\w+\s*\(` the same scan reports 39
         * compile-only entries, of which `name`, `data`, `list`, `float`, `hash`, `command`,
         * `domain`, `pointer` and `controls` are English words on a documentation page that happen to
         * be followed by a bracket. A census nobody can act on gets ignored, and an ignored census is
         * indistinguishable from no census.
         *
         * camelCase is the right cut because it is Field's own naming convention for the surface
         * these pages teach — `showMesh`, `fromContext`, `renderDirtWav`, `clipEncode`. Single-word
         * APIs (`display`, `prop`, `vec2`) are therefore outside this population and this class does
         * not claim to cover them; that is stated rather than hidden, and it is why the numbers above
         * are labelled *camelCase APIs* and not *APIs*.
         *
         * The optional `<…>` swallows a type argument so `fromContext<Renderer>()` is one call site
         * and not a miss.
         */
        val CALL = Regex("""\b([a-z][a-z0-9]*[A-Z][A-Za-z0-9]{3,})\s*(?:<[^<>\n]{0,60}>)?\s*\(""")

        /** A jvm-main smoke's own registration block — how [evaluatingSmokes] tells a smoke from a file. */
        val FIELD_TEST_ID = Regex("""FIELD_TEST\s*\n\s*id:\s*(\S+)""")

        /**
         * The package this guard and its siblings live in, excluded from every evaluator population.
         *
         * Found the hard way, on the first run: this file's own KDoc contains the phrase
         * `` `clipEncode(` `` — in the sentence *explaining* that `clipEncode` has no evaluator — and
         * that scored as an evaluating jvmTest, so the ledger row reported itself stale. A guard that
         * a documentation guard *mentions* an API is not evidence the API runs, and a guard which
         * counts its own prose as evidence is the failure mode this whole class is named after,
         * reproduced inside the fix for it.
         */
        const val GUARD_PACKAGE_DIR = "/afield/docs/"
    }

    /**
     * Kotlin source with `//` and block comments blanked, string literals left intact.
     *
     * Both halves are deliberate and they pull in opposite directions.
     *
     * **Comments out**, because a KDoc worked example is not an evaluation. `afield/onnx/Clip.kt`
     * carries three `clipEncode("…")` calls in its own documentation, and the un-stripped scan read
     * the declaration site's KDoc as proof that the declaration is exercised.
     *
     * **Strings in**, because in this tree an enormous amount of genuinely-evaluated code *is* a
     * string literal: every jvm-main smoke that stages a live box holds the box's Kotlin in a
     * `""" … """` constant and hands it to the real compiler and evaluator. Stripping those would
     * discard the strongest evidence there is — `ContextSearchOrderLedgerSmokeTest`'s `fromContext`
     * evidence is exactly this shape.
     *
     * Approximate in one known way, stated rather than hidden: a block-comment opener inside a
     * *character* literal is not modelled. (Written as a description rather than as the two
     * characters, because Kotlin block comments nest and a literal opener in this KDoc would swallow
     * the rest of the file — a trap this tree has hit before.) There are none in this tree and the
     * failure would be conservative anyway — it
     * blanks too much, which can only move an API from "evaluated" to "not evaluated", i.e. toward a
     * red build that a person then looks at.
     */
    private fun stripComments(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                text.startsWith("\"\"\"", i) -> {
                    val end = text.indexOf("\"\"\"", i + 3).let { if (it < 0) text.length else it + 3 }
                    out.append(text, i, end); i = end
                }
                c == '"' -> {
                    var j = i + 1
                    while (j < text.length && text[j] != '"') { if (text[j] == '\\') j++; j++ }
                    val end = (j + 1).coerceAtMost(text.length)
                    out.append(text, i, end); i = end
                }
                // A character literal, consumed whole so that the quote in `'"'` cannot open a
                // string. This branch is here because of one line in this very function: without it
                // the `'"'` two cases up opens a bogus string that runs to the next real quote and
                // carries whatever comment text lies between into the output verbatim. Bounded at
                // eight characters, which covers `'A'`, so an apostrophe inside a backtick
                // identifier degrades to being an ordinary character rather than swallowing a line.
                c == '\'' -> {
                    var j = i + 1
                    while (j < text.length && j <= i + 8 && text[j] != '\'') { if (text[j] == '\\') j++; j++ }
                    val end = if (j < text.length && j <= i + 8 && text[j] == '\'') j + 1 else i + 1
                    out.append(text, i, end); i = end
                }
                text.startsWith("//", i) -> {
                    val end = text.indexOf('\n', i).let { if (it < 0) text.length else it }
                    repeat(end - i) { out.append(' ') }; i = end
                }
                text.startsWith("/*", i) -> {
                    // Kotlin block comments nest, and this tree's KDoc relies on it.
                    var depth = 0; var j = i
                    while (j < text.length) {
                        if (text.startsWith("/*", j)) { depth++; j += 2 }
                        else if (text.startsWith("*/", j)) { depth--; j += 2; if (depth == 0) break }
                        else j++
                    }
                    repeat(j - i) { out.append(if (text[i + it] == '\n') '\n' else ' ') }; i = j
                }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }

    private val root = CitationGuard.repoRoot()

    /**
     * The five documented APIs that CI compiles and never runs, each with the reason it is that way.
     *
     * Every row was checked by hand against all three exercise populations before being written here.
     * The reasons are not excuses — four of the five are *good* reasons, and the point of writing them
     * down is that "we cannot run this headless" and "nobody got round to it" look identical in a
     * build log and want completely different responses.
     */
    private val COMPILE_ONLY_REMAINDER: Map<String, String> = mapOf(
        // `onnx_webcam_live` is COMPILE_ONLY because CI has no camera, and the transcript's own
        // comment in DemoTranscriptExecSmokeTest adds the second reason: clipEncode sits behind lazy
        // initialisation, so compiling rather than evaluating is what keeps the harness hermetic
        // instead of fetching ~600MB of encoders. Note the trap this row exists to name: grepping the
        // tree for `clipEncode(` outside `afield/onnx/Clip.kt` finds exactly one other file, and it is
        // DemoTranscriptExecSmokeTest itself — the harness that does not run it. An evaluator that is
        // the compile-only harness is not an evaluator, and a scan that did not exclude it would score
        // this row green.
        "clipEncode" to
            "onnx.md; onnx_webcam_live is COMPILE_ONLY (no camera in CI, and evaluating it would " +
                "fetch ~600MB of CLIP encoders). Its only non-declaring call site in the tree is the " +
                "compile-only harness itself.",
        // The one row here that is not a good reason, and the only one a person should act on.
        // `src/jvmMain/kotlin/afield/examples/four/FieldProbeSmokeTest.kt` exists, opens a Window and
        // exercises ShaderFieldProbe — and carries no FIELD_TEST block and no Gradle task, so nothing
        // runs it. Two published pages teach the API. See dev_docs/field_probes.md for why the
        // headless docs runner cannot drive GPU-compute fences either.
        "fieldProbe" to
            "afield_four_graphics.md, field_probes.md; only threebox/spin_glass (COMPILE_ONLY). " +
                "FieldProbeSmokeTest.kt is an unregistered `main` — no FIELD_TEST id, no Gradle task " +
                "— so nothing in CI runs it. This row is a TODO, not a justification.",
        // The audio_render_analysis family. Its transcript is COMPILE_ONLY because four of its boxes
        // reach an external analysis worker or a real-time Sonic Pi recorder, which is a sound reason
        // and also means the *rendering* entry points on the page have no CI evaluation at all. The
        // page's analysis half does: analyzeRenderedAudio and standardAudioAnalyses are evaluated by
        // RenderedAudioSeamTest and RenderedAudioAnalysisTest, which is why they are absent here and
        // is a good illustration of why the union denominator matters.
        "recordSonicPiWav" to
            "audio_render_analysis.md; audio_render_analysis is COMPILE_ONLY — this one drives a " +
                "real-time Sonic Pi recording, which CI has no daemon for.",
        "renderDirtWav" to
            "audio_render_analysis.md; audio_render_analysis is COMPILE_ONLY. Offline and in " +
                "principle runnable, but it needs the SuperCollider Dirt-Samples quark on disk.",
        "renderFaustWavAsync" to
            "audio_render_analysis.md; audio_render_analysis is COMPILE_ONLY. Suspend + GraalWasm " +
                "Faust compile; the 22s cold compiler is the reason, not an inability.",
    )

    // -----------------------------------------------------------------------------------------------
    // The three exercise populations
    // -----------------------------------------------------------------------------------------------

    private fun transcriptsDir() = File(root, "dev_docs/agents/transcripts")

    /** `name -> the camelCase calls its yaml makes`, for the top-level corpus only. */
    private fun transcriptCalls(): Map<String, Set<String>> =
        (transcriptsDir().listFiles { f -> f.isFile && f.extension == "yaml" } ?: emptyArray())
            .associate { f ->
                f.nameWithoutExtension to CALL.findAll(f.readText()).map { it.groupValues[1] }.toSet()
            }

    /**
     * Published pages that name [api] as a call.
     *
     * `public/docs` only, deliberately: `dev_docs` is a developer's notebook and is allowed to discuss
     * an API nobody runs. A *published* page is a promise.
     */
    private val publishedPages by lazy {
        (File(root, "public/docs").listFiles { f -> f.isFile && f.extension == "md" } ?: emptyArray())
            .sortedBy { it.name }
            .map { it.name to it.readText() }
    }

    private fun pagesNaming(api: String): List<String> {
        val rx = Regex("""\b${Regex.escape(api)}\s*\(""")
        return publishedPages.filter { (_, text) -> rx.containsMatchIn(text) }.map { it.first }
    }

    /**
     * Comment-stripped Kotlin sources that call [api].
     *
     * Three exclusions get applied upstream in [sources], and `clipEncode` needed all three, which is
     * why they are worth naming together. Its call sites in this tree are: its own declaration and
     * three KDoc examples in `afield/onnx/Clip.kt` (excluded by [stripComments]); a comment in
     * `DemoTranscriptExecSmokeTest.kt` saying it is deliberately *not* evaluated (excluded by file
     * name); and — on this guard's own first run — a sentence in this very file explaining the
     * preceding two (excluded by [GUARD_PACKAGE_DIR]). Every one of them would have scored the API as
     * exercised. None of them runs it.
     */
    private fun callersUnder(sources: List<Pair<String, String>>, api: String): List<String> {
        val rx = Regex("""\b${Regex.escape(api)}\s*(?:<[^<>\n]{0,60}>)?\s*\(""")
        return sources.filter { (_, text) -> rx.containsMatchIn(text) }.map { it.first }
    }

    /**
     * `file name -> text`, read once. Fourteen APIs against fourteen hundred files is twenty thousand
     * reads if the walk is inside the loop, and this runs inside a docs gate that is supposed to be
     * the cheap one.
     */
    private fun sources(rel: String, requireFieldTest: Boolean): List<Pair<String, String>> =
        DerivedCountGuard.kotlinSourcesUnder(root, rel)
            .filter { it.name != "DemoTranscriptExecSmokeTest.kt" }
            .filterNot { it.path.replace(File.separatorChar, '/').contains(GUARD_PACKAGE_DIR) }
            // The FIELD_TEST block is itself a comment, so this test runs on the raw text and the
            // call search runs on the stripped text. Reading them off the same string would need the
            // registration block to survive comment stripping, which is the wrong trade.
            .map { it.name to it.readText() }
            .filter { (_, raw) -> !requireFieldTest || FIELD_TEST_ID.containsMatchIn(raw) }
            .map { (name, raw) -> name to stripComments(raw) }

    private val smokeSources by lazy { sources("src/jvmMain/kotlin", requireFieldTest = true) }
    private val testSources by lazy { sources("src/jvmTest/kotlin", requireFieldTest = false) }

    private fun evaluatingSmokes(api: String) = callersUnder(smokeSources, api)

    private fun evaluatingTests(api: String) = callersUnder(testSources, api)

    // -----------------------------------------------------------------------------------------------
    // The census
    // -----------------------------------------------------------------------------------------------

    /** One documented API and how CI reaches it. */
    private data class Reach(
        val api: String,
        val pages: List<String>,
        val evaluatedByTranscript: Boolean,
        val smokes: List<String>,
        val tests: List<String>,
    ) {
        val evaluated get() = evaluatedByTranscript || smokes.isNotEmpty() || tests.isNotEmpty()
    }

    private fun census(): List<Reach> {
        val calls = transcriptCalls()
        val evaluatingTranscripts =
            DemoTranscriptExecSmokeTest.EXECUTE + DemoTranscriptExecSmokeTest.KOTLINJS
        val byApi = mutableMapOf<String, MutableSet<String>>()
        calls.forEach { (name, ids) -> ids.forEach { byApi.getOrPut(it) { mutableSetOf() } += name } }
        return byApi.mapNotNull { (api, transcripts) ->
            val pages = pagesNaming(api)
            if (pages.isEmpty()) return@mapNotNull null
            val evaluatedByTranscript = transcripts.any { it in evaluatingTranscripts }
            Reach(
                api = api,
                pages = pages,
                evaluatedByTranscript = evaluatedByTranscript,
                // Only worth the file walk for the ones the transcript corpus does not already settle.
                smokes = if (evaluatedByTranscript) emptyList() else evaluatingSmokes(api),
                tests = if (evaluatedByTranscript) emptyList() else evaluatingTests(api),
            )
        }.sortedBy { it.api }
    }

    /**
     * The population, printed and floored; the compile-only remainder, held to the ledger both ways.
     *
     * One test rather than three, because the three numbers are one measurement and splitting them
     * would let a reader see a green tick on the ledger while the denominator underneath it had
     * collapsed.
     */
    @Test
    fun everyDocumentedApiIsEvaluatedSomewhereOrDeclaredAsCompileOnly() {
        val all = census()
        val evaluated = all.filter { it.evaluated }
        val remainder = all.filterNot { it.evaluated }

        println("--- documented camelCase APIs, by how CI reaches them ---")
        println("  named on a published page and called by a transcript : ${all.size} (floor $MIN_DOCUMENTED_APIS)")
        println("  evaluated (EXECUTE/KOTLINJS transcript, FIELD_TEST smoke, or jvmTest) : " +
            "${evaluated.size} (floor $MIN_EVALUATED_APIS)")
        println("  compiled but never run, anywhere : ${remainder.size} (ledger: ${COMPILE_ONLY_REMAINDER.size})")
        remainder.forEach { println("    %-24s %s".format(it.api, it.pages.joinToString(", "))) }

        val undeclared = remainder.map { it.api }.filterNot { it in COMPILE_ONLY_REMAINDER }.sorted()
        // Two ways a row stops being true, and they want opposite repairs — so they are separated
        // rather than lumped into one "stale" list. Measured while demonstrating this test's own red:
        // with the transcript corpus removed, every row reported as "now evaluated somewhere", which
        // is the one thing that had not happened. A diagnostic that misnames the cause of a red build
        // is how a person learns to stop reading them.
        val promoted = COMPILE_ONLY_REMAINDER.keys.filter { k -> all.any { it.api == k && it.evaluated } }.sorted()
        val vanished = COMPILE_ONLY_REMAINDER.keys.filterNot { k -> all.any { it.api == k } }.sorted()

        val problems = listOfNotNull(
            if (all.size >= MIN_DOCUMENTED_APIS) null else
                "the transcript corpus now reaches only ${all.size} documented API(s), below the " +
                    "floor of $MIN_DOCUMENTED_APIS. Every other assertion here is then a tick over " +
                    "an almost-empty set — suspect the scan before believing the number.",
            if (evaluated.size >= MIN_EVALUATED_APIS) null else
                "only ${evaluated.size} documented API(s) are evaluated anywhere in CI, below the " +
                    "floor of $MIN_EVALUATED_APIS. Reclassifying a transcript EXECUTE -> COMPILE_ONLY " +
                    "is a one-line edit that costs no other test its green; this is the line that " +
                    "notices.",
            if (undeclared.isEmpty()) null else
                "${undeclared.size} documented API(s) are compiled by CI and never run by it, and " +
                    "are not declared in COMPILE_ONLY_REMAINDER: $undeclared. Three ways to green, in " +
                    "order of preference: evaluate it (an EXECUTE transcript, a FIELD_TEST jvm-main " +
                    "smoke, or a jvmTest); declare it here with the reason it cannot be evaluated; or " +
                    "stop teaching it on a published page.",
            if (promoted.isEmpty()) null else
                "${promoted.size} row(s) in COMPILE_ONLY_REMAINDER are stale — the API is now " +
                    "evaluated somewhere: $promoted. Good news; delete the row. Left in place the " +
                    "ledger degrades into a list of problems that used to exist, which is the failure " +
                    "the backward direction of every ledger in this package exists to refuse.",
            if (vanished.isEmpty()) null else
                "${vanished.size} row(s) in COMPILE_ONLY_REMAINDER name an API the census cannot see " +
                    "at all: $vanished. It is no longer called by any transcript, or no longer named " +
                    "as a call on a published page, or the scan has broken. Check the population " +
                    "figures above before deleting the row — a collapsed denominator presents exactly " +
                    "like a tidy-up.",
        )
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    /**
     * The three exclusions are load-bearing, and this is what says so.
     *
     * Assertions on the *mechanism* rather than on today's corpus, in the shape of
     * `CitationContractTest.theInputThatRulesOutTheGradleMarkerIsReallyInThisRepo`: each exclusion is
     * only worth its lines while the thing it excludes is still there to be excluded, and when one of
     * these fails the argument has gone stale and wants re-reading rather than patching.
     *
     * `clipEncode` is the witness for all three because it is the only API in the tree whose every
     * mention outside its own declaration is prose about it not being run.
     */
    @Test
    fun theThreeNonEvaluatorExclusionsAreStillNeededAndStillWork() {
        val harness = File(root, "src/jvmMain/kotlin/afield/examples/DemoTranscriptExecSmokeTest.kt")
        assertTrue(harness.isFile, "the compile-only harness moved; ${harness.path} no longer exists")
        assertTrue(
            "clipEncode" in harness.readText(),
            "DemoTranscriptExecSmokeTest no longer mentions clipEncode, so the by-name exclusion is " +
                "no longer demonstrated by anything."
        )
        val declaration = File(root, "src/jvmMain/kotlin/afield/onnx/Clip.kt")
        assertTrue(declaration.isFile, "afield/onnx/Clip.kt moved; the comment-stripping witness is gone")
        val rx = Regex("""\bclipEncode\s*\(""")
        val raw = rx.findAll(declaration.readText()).count()
        val kept = rx.findAll(stripComments(declaration.readText())).count()
        assertTrue(
            raw > kept,
            "afield/onnx/Clip.kt carries $raw clipEncode( call site(s) and stripComments keeps $kept " +
                "of them. With nothing dropped, nothing here demonstrates that a call written in a " +
                "KDoc example is not an evaluation."
        )
        assertTrue(
            "clipEncode(" in File(root, "src/jvmTest/kotlin/afield/docs/DocumentedApiEvaluationTest.kt").readText(),
            "this file no longer mentions clipEncode( in its own prose, so the GUARD_PACKAGE_DIR " +
                "exclusion — added because that prose scored as an evaluator on the first run — is no " +
                "longer demonstrated."
        )
        val found = evaluatingSmokes("clipEncode") + evaluatingTests("clipEncode")
        assertTrue(
            found.isEmpty(),
            "clipEncode now has an evaluator: $found. If that is real, delete its " +
                "COMPILE_ONLY_REMAINDER row; if one of the three exclusions has broken, fix it — the " +
                "three are: the harness by name, comments by stripComments, this package by path."
        )
    }

    /**
     * [stripComments] on a fixture with every shape that matters, so the scan's precision is not taken
     * on trust.
     *
     * The last two cases are the pair that decides whether this mechanism means anything: a call in a
     * KDoc must vanish, and a call inside a raw string must survive. Get the second wrong and every
     * live-box smoke in the tree stops counting as evidence, which would silently move dozens of APIs
     * into the compile-only remainder and make the ledger unmaintainable.
     */
    @Test
    fun theCommentStripperKeepsBoxCodeAndDropsDocumentation() {
        val src = """
            /** KDoc: call docOnly() here. */
            // line comment: call lineOnly()
            /* outer /* nested */ still a comment: call nestedOnly() */
            val boxCode = ""${'"'}
                stringOnly()
                // this looks like a comment but is box Kotlin: keptAnyway()
            ""${'"'}
            val quoted = "not/*a*/comment"
            val quoteChar = '"' // and a trailing comment: hiddenByChar()
            fun real() = realCall()
        """.trimIndent()
        val stripped = stripComments(src)
        listOf("docOnly", "lineOnly", "nestedOnly", "hiddenByChar").forEach {
            assertTrue("$it(" !in stripped, "$it( survived comment stripping: $stripped")
        }
        listOf("stringOnly", "keptAnyway", "realCall").forEach {
            assertTrue("$it(" in stripped, "$it( was lost, so evaluated code stopped counting: $stripped")
        }
        assertTrue("not/*a*/comment" in stripped, "a `/*` inside a string literal opened a comment")
        assertEquals(
            src.count { it == '\n' }, stripped.count { it == '\n' },
            "stripComments changed the line count, so a future line-reporting caller would be wrong"
        )
    }

    /**
     * The three verification levels still partition the corpus, so the census's denominator is whole.
     *
     * [DemoTranscriptExecSmokeTest] fails on an unclassified transcript at *run* time, which is a
     * three-minute jvm-main smoke. This is the same completeness claim for a tenth of a second, and it
     * is what stops a transcript added tonight from being silently absent from the population above —
     * a missing transcript lowers every number here and raises no alarm on its own.
     */
    @Test
    fun everyTranscriptCarriesAVerificationLevel() {
        val classified = DemoTranscriptExecSmokeTest.EXECUTE +
            DemoTranscriptExecSmokeTest.COMPILE_ONLY +
            DemoTranscriptExecSmokeTest.KOTLINJS
        val corpus = transcriptCalls().keys
        val unclassified = (corpus - classified).sorted()
        val ghosts = (classified - corpus).sorted()
        assertTrue(
            unclassified.isEmpty() && ghosts.isEmpty(),
            "the transcript corpus and its verification levels have come apart. Unclassified " +
                "(present as yaml, in no set): $unclassified. Ghosts (in a set, no yaml): $ghosts."
        )
    }
}
