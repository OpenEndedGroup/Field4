package afield.petri

import afield.examples.petri.PetriGallery
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The caller sweep for `PetriNet.deadTransitions`, which was named as a follow-on and never done.
 *
 * `deadTransitions` was changed to stop at its own `within` argument instead of running on to
 * `maxUnroll`, and the evidence for that being harmless was six nets at two step limits. Six nets is
 * not a sweep, and — more to the point — nobody had written down *which* callers were supposed to be
 * covered, so the six could quietly stop being the right six and nothing would say so.
 *
 * Two halves, and the first is the one that keeps the second honest.
 *
 * ### The ledger, bidirectionally
 *
 * [CALL_SITES] is a declared inventory of every Kotlin file that calls `deadTransitions`, with the
 * number of calls in each; [READER_FACING] is the set of documents that show a reader its output.
 * The test scans the tree and compares **both directions plus set equality**: a declared caller that
 * has vanished fails, an undeclared caller that has appeared fails, and a listed file whose call
 * count moved fails. The failure mode being designed out is the ordinary one — a sweep whose scan
 * silently matches nothing, returns an empty list, and reports that every caller agrees.
 *
 * Call counts are exact for Kotlin and only the *file set* is pinned for prose, which is the right
 * sensitivity in each case: a call site appearing or disappearing is a fact about the code, whereas
 * a rewritten sentence is not. `public/docs/reference/` is excluded by hand because it is generated
 * after a merge rather than written, so a regeneration must not be able to fail this test.
 *
 * ### The replay, over every net those callers build
 *
 * The old behaviour is replayed beside the new one over [corpus] — every net `PetriGallery` builds,
 * every `#petri` block on the examples page, and the philosophers' table across its conflict
 * policies — at each `within` any caller actually passes. That is the six nets replaced by roughly
 * ten times as many, and it is the same claim, checked wider: **no transition in any of these nets
 * first fires between `within` and the replay ceiling**, so nothing any caller prints moves.
 *
 * The ceiling is stated rather than assumed, because it is the limit of the claim. `PetriRun` keeps
 * every step and `PetriMarking.add` copies a place's token list, so replaying the old behaviour costs
 * quadratic memory — which is precisely the trap the fix removed from `report()`. [REPLAY_CEILING]
 * is where this test stops paying for it.
 */
class PetriDeadTransitionsSweepTest {

    // ── the ledger ────────────────────────────────────────────────────────────────────────

    /** Kotlin files that call `deadTransitions`, and how many times each. Both directions checked. */
    private val CALL_SITES: Map<String, Int> = mapOf(
        // The declaration itself, and `report()` — the caller everything user-facing goes through.
        "src/jvmMain/kotlin/afield/petri/PetriAnalysis.kt" to 2,
        "src/jvmMain/kotlin/afield/petri/PetriSmokeTest.kt" to 2,
        "src/jvmMain/kotlin/afield/examples/petri/PetriGallery.kt" to 5,
        "src/jvmTest/kotlin/afield/petri/PetriAnalysisTest.kt" to 3,
        "src/jvmTest/kotlin/afield/petri/PetriStateSpaceTest.kt" to 5,
        // This file: one call in the replay, three in the counterfactual below it.
        "src/jvmTest/kotlin/afield/petri/PetriDeadTransitionsSweepTest.kt" to 4,
    )

    /**
     * Documents that show a reader `deadTransitions`' output, so a change to it changes what they say.
     *
     * The file set is pinned and the mention counts are not: an added sentence is not a fact about
     * the function, but a page that stops mentioning it (or a new one that starts) is a page somebody
     * needs to re-read.
     */
    private val READER_FACING: Set<String> = setOf(
        "public/docs/petri_nets.md",
        "public/docs/petri_cheatsheet.md",
        "public/docs/agent_transcripts.md",
        "public/docs/assets/agent_transcripts/petri_state_space/document.md",
        "public/docs/assets/agent_transcripts/petri_state_space/metadata.edn",
        "public/docs/assets/agent_transcripts/petri_state_space/petri_ss_asymmetric_code_.kt",
        "public/docs/assets/agent_transcripts/petri_sound/document.md",
        "public/docs/assets/agent_transcripts/petri_sound/metadata.edn",
        "public/docs/assets/agent_transcripts/petri_sound/petri_demo_philosophers_code_.kt",
        "dev_docs/agents/transcripts/petri_state_space.yaml",
        "dev_docs/agents/transcripts/petri_sound.yaml",
        "dev_docs/PETRI_DEAD_TRANSITIONS_SWEEP.md",
    )

    /**
     * `public/docs/reference/` is generated after a merge, not written, so it is scanned past. A
     * regeneration that starts listing `deadTransitions` must not be able to fail this test.
     */
    private val EXCLUDED = listOf("public/docs/reference/", "/build/", "/.git/")

    private val root: File by lazy {
        var at = File(System.getProperty("user.dir")).absoluteFile
        while (!File(at, "build.gradle.kts").isFile && at.parentFile != null) at = at.parentFile
        at
    }

    private fun scan(under: String, extensions: Set<String>, needle: Regex): Map<String, Int> {
        val base = File(root, under)
        if (!base.isDirectory) return emptyMap()
        return base.walkTopDown()
            .filter { it.isFile && it.extension in extensions }
            .map { it.relativeTo(root).path }
            .filterNot { path -> EXCLUDED.any { path.replace('\\', '/').contains(it.trim('/')) } }
            .mapNotNull { path ->
                val count = needle.findAll(File(root, path).readText()).count()
                if (count == 0) null else path to count
            }
            .toMap()
    }

    @Test
    fun `the ledger of Kotlin call sites is complete in both directions`() {
        // Assert the premise before the result: a scan rooted in the wrong place finds nothing and
        // would otherwise report a perfectly empty agreement.
        assertTrue(File(root, "src/jvmMain/kotlin/afield/petri").isDirectory, "scan root wrong: $root")

        val found = scan("src", setOf("kt"), Regex("""\bdeadTransitions\s*\("""))
        assertTrue(found.isNotEmpty(), "the scan found no call sites at all, which cannot be right")

        assertEquals(emptySet(), CALL_SITES.keys - found.keys, "declared callers that no longer exist")
        assertEquals(emptySet(), found.keys - CALL_SITES.keys, "callers nobody has swept")
        assertEquals(CALL_SITES.keys, found.keys)
        assertEquals(CALL_SITES.toSortedMap(), found.toSortedMap(), "a listed file's call count moved")
    }

    @Test
    fun `the ledger of reader-facing documents is complete in both directions`() {
        val found = scan("public/docs", setOf("md", "edn", "kt", "yaml"), Regex("""\bdeadTransitions\b""")) +
            scan("dev_docs", setOf("md", "edn", "kt", "yaml"), Regex("""\bdeadTransitions\b"""))
        assertTrue(found.isNotEmpty(), "the scan found no documents at all, which cannot be right")

        assertEquals(emptySet(), READER_FACING - found.keys, "documents listed here that no longer mention it")
        assertEquals(emptySet(), found.keys - READER_FACING, "documents that mention it and are not on the list")
        assertEquals(READER_FACING, found.keys)
    }

    // ── the replay ────────────────────────────────────────────────────────────────────────

    /**
     * How far the *old* behaviour is replayed. See the class KDoc: replaying it at its real default
     * of 200 000 steps costs quadratic memory and exhausts the heap inside the full suite, so this is
     * the limit of the claim rather than a tuning knob.
     */
    private val REPLAY_CEILING = 20_000

    /** Every `within` any caller actually passes: the default, and `report()`'s `maxSteps`. */
    private val LIMITS = listOf(2_048, 4_096)

    /**
     * `deadTransitions` as it behaved before the `within` fix — run to `maxUnroll`, then look at
     * every step recorded rather than the first `within` of them.
     *
     * Verbatim from `PetriStateSpaceTest`, where the original six-net pin lives, so that the two are
     * comparing against the same thing.
     */
    private fun before(net: PetriNet, within: Int): List<String> {
        val run = net.run(maxUnroll = REPLAY_CEILING)
        run.lasso(within)
        val fired = run.steps(Int.MAX_VALUE).flatMap { step -> step.firings.map { it.transition } }.toSet()
        return net.transitionNames.filter { it !in fired }
    }

    /** The philosophers' table, which is where every conflict policy is visible at once. */
    private fun table(n: Int, reverseLast: Boolean, conflict: PetriConflict) = petri {
        this.conflict = conflict
        grid = cyc(1, 4)
        for (i in 0 until n) place("fork.$i", tokens = 1)
        for (i in 0 until n) {
            val left = i
            val right = (i + 1) % n
            val reversed = reverseLast && i == n - 1
            t("grab.$i") { from("fork.${if (reversed) right else left}"); to("holding.$i") }
            t("eat.$i") { from("holding.$i"); from("fork.${if (reversed) left else right}"); to("eating.$i") }
            t("done.$i") { from("eating.$i"); to("fork.$left"); to("fork.$right") }
        }
    }

    /**
     * Every `#petri` program on the examples page — the nets a reader can copy out and run.
     *
     * Scraped from `#petri` to whichever comes first of the closing triple quote and the end of the
     * fence, because on that page the notation lives inside Kotlin raw strings rather than in
     * notation fences of its own. Anything that does not parse is dropped, which is how the two
     * places the page merely *mentions* `#petri` in prose are skipped; the count is floored in
     * `the corpus is wider than the six nets it replaces` so a scrape that silently stopped working
     * fails rather than quietly shrinking the sweep.
     */
    private fun documentedNets(): List<Pair<String, PetriNet>> {
        val page = File(root, "public/docs/petri_nets.md")
        if (!page.isFile) return emptyList()
        val text = page.readText()
        val out = mutableListOf<Pair<String, PetriNet>>()
        var from = text.indexOf("#petri")
        var index = 0
        while (from >= 0) {
            val stop = listOf(text.indexOf("\"\"\"", from), text.indexOf("\n```", from))
                .filter { it > from }
                .minOrNull() ?: text.length
            val parsed = petriParse(text.substring(from, stop))
            val net = parsed.net
            if (parsed.errors.isEmpty() && net != null && net.transitions.isNotEmpty()) {
                out += "petri_nets.md#$index" to net
            }
            index++
            from = text.indexOf("#petri", stop)
        }
        return out
    }

    /** Every net a caller of `deadTransitions` actually builds, or shows a reader. */
    private fun corpus(): List<Pair<String, PetriNet>> = buildList {
        addAll(PetriGallery.nets())
        addAll(documentedNets())
        for (n in listOf(3, 4, 5)) for (reverse in listOf(false, true)) {
            for (conflict in PetriConflict.entries) {
                add("table n=$n reversed=$reverse $conflict" to table(n, reverse, conflict))
            }
        }
    }

    @Test
    fun `the corpus is wider than the six nets it replaces`() {
        val corpus = corpus()
        // Assert the premise. A regex that stopped matching, or a gallery accessor that started
        // returning nothing, would otherwise turn the sweep below into a loop over an empty list.
        assertTrue(PetriGallery.nets().size >= 12, "the gallery contributed only ${PetriGallery.nets().size} nets")
        assertTrue(documentedNets().size >= 3, "only ${documentedNets().size} `#petri` programs on the examples page")
        assertTrue(corpus.size >= 36, "only ${corpus.size} nets — that is not a sweep")
        assertEquals(corpus.size, corpus.distinctBy { it.first }.size, "duplicate names in the corpus")
        // And that the nets are not all trivial: a net with one transition cannot have a transition
        // that first fires late.
        assertTrue(
            corpus.count { it.second.transitions.size >= 4 } >= 20,
            "only ${corpus.count { it.second.transitions.size >= 4 }} nets have four or more transitions",
        )
    }

    @Test
    fun `stopping at within changes nothing any caller of deadTransitions prints`() {
        val corpus = corpus()
        var compared = 0
        val differences = mutableListOf<String>()
        for ((name, net) in corpus) for (within in LIMITS) {
            val old = before(net, within)
            val new = net.deadTransitions(within)
            compared++
            if (old != new) differences += "$name at within=$within: was $old, now $new"
        }
        println(
            "petri deadTransitions sweep: ${corpus.size} nets x ${LIMITS.size} limits = $compared comparisons, " +
                "replay ceiling $REPLAY_CEILING, ${differences.size} differences"
        )
        assertTrue(compared >= 72, "only $compared comparisons ran")
        assertTrue(
            differences.isEmpty(),
            "${differences.size} nets print something different:\n" + differences.joinToString("\n") { "  $it" },
        )
    }

    @Test
    fun `and the sweep would have noticed, on a net built to be noticed`() {
        // The counterfactual, which is the part that makes the green tick above worth anything: a net
        // where a transition genuinely first fires late. `slow` needs eight tokens in `pool` and one
        // arrives per step, so it does not fire until step eight — well inside every limit — and a
        // `within` of four would name it dead where a longer run does not.
        val net = petri {
            place("tick", tokens = 1)
            place("pool")
            t("fill") { from("tick"); to("tick"); to("pool") }
            t("slow") { from("pool", weight = 8); to("done") }
        }
        assertEquals(listOf("slow"), net.deadTransitions(4), "at four steps `slow` has not fired yet")
        assertEquals(emptyList(), net.deadTransitions(64), "…and by sixty-four it has")
        // Which is exactly the shape of difference the sweep is looking for, so the sweep can see it.
        assertTrue(before(net, 4) != net.deadTransitions(4), "the replay and the fix must differ here")
    }
}
