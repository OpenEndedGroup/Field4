package afield.docs

import afield.examples.DemoTranscriptExecSmokeTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every loadable document under `dev_docs/agents/demos/` is *verified by something*, and named here.
 *
 * ## The hole this closes
 *
 * [DemoTranscriptExecSmokeTest] iterates every yaml in `dev_docs/agents/transcripts` and fails any
 * transcript that is in none of its three registries — so the transcript corpus cannot grow
 * unverified. The **demo** corpus can. A directory dropped into `dev_docs/agents/demos/` with a
 * `metadata.edn` and no matching transcript is compiled by nothing, replayed by nothing and
 * classified by nothing, and every gate in the tree stays green.
 *
 * That is not hypothetical. Measured on this branch's base (`068055b54`, 2026-09-20):
 * `dev_docs/agents/demos/skinned_characters/` had **seven boxes, six of them Kotlin** — and by the
 * time this merged it was **eight boxes, seven of them Kotlin**, because the sibling branch added
 * `gpu_crowd` in the same night. The debt grew while the exemption describing it stood still, which
 * is the failure mode this class exists to name. No
 * `dev_docs/agents/transcripts/skinned_characters.yaml`, and no entry in `EXECUTE`,
 * `COMPILE_ONLY` or `KOTLINJS`. It landed from a sibling branch on 2026-09-19. The only assertion
 * that noticed anything at all was `tools/test_demo_build_scripts.py`'s corpus count moving
 * `19 -> 20`, which says a demo arrived and nothing about whether it is checked.
 *
 * `dev_docs/DEMO_CORPUS_AUDIT.md` is dated 19 September and was written to warn about precisely
 * this. `skinned_characters` landed the same day.
 *
 * **Both halves of that were paid off on 2026-09-21** — `dev_docs/agents/transcripts/
 * skinned_characters.yaml` and `marf_demo.yaml`, both `COMPILE_ONLY` — so [UNVERIFIED] is empty and
 * every demo document in the corpus has its box Kotlin compiled on every build. The paragraph above
 * is kept in the past tense rather than deleted, because it is the measurement that says what this
 * class is worth: the `skinned_characters` sweep found a box (`twist_compare`) that had not
 * compiled since the day it landed, and a green `check` had said nothing about it for two days.
 *
 * ## The shape of the assertions
 *
 * Both directions, and a named list on each side:
 *
 *  - every demo directory maps to a registered transcript, or sits in [UNVERIFIED] with a reason;
 *  - every [UNVERIFIED] entry is still unverified, so an exemption cannot outlive its cause;
 *  - the three verification levels are *disjoint*, not merely covering — completeness is already
 *    held by [DocumentedApiEvaluationTest.everyTranscriptCarriesAVerificationLevel], disjointness
 *    by nothing, and the harness's `when` silently prefers the first branch.
 *
 * [DEMO_CORPUS] is an explicit list rather than a count on purpose. Two branches adding a demo on
 * one night touch no file in common, so `git merge-tree` is clean by construction and a count
 * assertion fails with `21 != 20` — true, and useless. A set difference fails with the directory's
 * name and what to do about it.
 */
class AgentDemoRegistrationTest {

    private companion object {
        const val DEMOS = "dev_docs/agents/demos"
        const val TRANSCRIPTS = "dev_docs/agents/transcripts"

        /**
         * The twenty-one demo documents this file was measured against, 2026-09-21 at `bc71ed216`.
         *
         * Recount with `ls -1 dev_docs/agents/demos`. Adding a demo means adding its name here and
         * registering it in [DemoTranscriptExecSmokeTest] — two lines, and the failure below tells
         * you both.
         *
         * It went 20 -> 21 the morning after this class landed, and the class did the job it was
         * written for: `bc71ed216` added `marf_demo` from a direction its author could not see, and
         * both assertions below named it and said what to do about it. What an assertion cannot do
         * is get itself run. Nothing runs `check` on commit, `bc71ed216`'s author ran the sibling
         * guard they *had* noticed (`checkAgentTranscriptLayouts`) and not this one, and `main` was
         * red for hours. That gap is argued about in `dev_docs/GUARD_CORPORA.md`; it is not fixed by
         * a longer list here.
         */
        val DEMO_CORPUS = setOf(
            "alife_evolution",
            "alife_script",
            "alife_walls",
            "chart_clip",
            "chart_primitive",
            "completion_rank_at_scale",
            "confluence_grid",
            "frame_history_reprojection",
            "interaction_nets_lambda",
            "keyframe_key_discovery",
            "logging_layer",
            "marf_demo",
            "midi_clip",
            "path_tracer_instrument",
            "path_tracing_emitters",
            "path_tracing_medium",
            "pattern_condense",
            "python_cells",
            "read_only_session",
            "skinned_characters",
            "solidsl_inverse",
        )

        /**
         * Demo documents that no harness compiles or replays, with why, and what would fix it.
         *
         * **Empty, since 2026-09-21, and that is the point of the list rather than a reason to
         * delete it.** It held one entry when it landed (`skinned_characters`) and two the next
         * morning (`marf_demo` arrived in `bc71ed216`); both were paid off by writing the
         * `COMPILE_ONLY` transcripts the entries themselves prescribed, which is the fix the text
         * of an exemption is supposed to name. Eleven Kotlin box bodies — seven and four — went
         * from compiled-by-nothing to compiled on every build.
         *
         * Empty is also the only state in which the *other* assertion here is load-bearing:
         * [everyDemoDocumentIsRegisteredForVerification] now fails outright for any demo that
         * arrives without a transcript, with nothing in the way. Adding an entry back is a decision
         * somebody makes on purpose, in writing, with a reason and a fix — not a default.
         */
        val UNVERIFIED = emptyMap<String, String>()
    }

    private fun demoDirectories(): List<String> {
        val root = File(DEMOS)
        assertTrue(root.isDirectory, "no demo corpus at ${root.absolutePath}")
        return (root.listFiles() ?: emptyArray())
            .filter { it.isDirectory && File(it, "metadata.edn").isFile }
            .map { it.name }
            .sorted()
    }

    private fun registry(): Map<String, String> =
        DemoTranscriptExecSmokeTest.EXECUTE.associateWith { "EXECUTE" } +
            DemoTranscriptExecSmokeTest.COMPILE_ONLY.associateWith { "COMPILE_ONLY" } +
            DemoTranscriptExecSmokeTest.KOTLINJS.associateWith { "KOTLINJS" }

    /**
     * The corpus is the one named above — by name, so a sibling's demo is reported as itself.
     */
    @Test
    fun theDemoCorpusIsTheListThisFileWasMeasuredAgainst() {
        val found = demoDirectories().toSet()
        val added = (found - DEMO_CORPUS).sorted()
        val gone = (DEMO_CORPUS - found).sorted()
        assertEquals(
            emptyList(),
            added,
            "new demo document(s) $added under $DEMOS. Add each name to DEMO_CORPUS in this file, " +
                "and register it for verification: write `$TRANSCRIPTS/<name>.yaml` and add `<name>` " +
                "to EXECUTE, COMPILE_ONLY or KOTLINJS in DemoTranscriptExecSmokeTest. If it genuinely " +
                "cannot be verified yet, add it to UNVERIFIED with the reason and the fix.",
        )
        assertEquals(
            emptyList(),
            gone,
            "demo document(s) $gone are in DEMO_CORPUS but not on disk; if they were removed or " +
                "renamed, update DEMO_CORPUS, UNVERIFIED and the counts in dev_docs/DEMO_CORPUS_AUDIT.md",
        )
        assertTrue(found.size >= 21, "the demo corpus has shrunk to ${found.size}; it was 21 on 2026-09-21")
    }

    /**
     * Nothing may be *in* the corpus without being verified by something, or listed as not.
     *
     * This is the assertion the demo corpus never had. It was held open by [UNVERIFIED] until
     * 2026-09-21; with that map empty, a twenty-second demo arriving with no transcript fails it
     * outright, and the failure message is the whole remedy.
     */
    @Test
    fun everyDemoDocumentIsRegisteredForVerification() {
        val registry = registry()
        val unregistered = demoDirectories().filter { demo ->
            val transcript = File("$TRANSCRIPTS/$demo.yaml").isFile
            !(transcript && demo in registry) && demo !in UNVERIFIED
        }
        assertEquals(
            emptyList(),
            unregistered,
            "demo document(s) $unregistered are compiled and replayed by nothing. A demo is verified " +
                "when `$TRANSCRIPTS/<name>.yaml` exists AND <name> is in one of " +
                "DemoTranscriptExecSmokeTest's EXECUTE / COMPILE_ONLY / KOTLINJS sets — that is the " +
                "only path by which a demo's box Kotlin is ever compiled. Write the transcript, or " +
                "add the demo to UNVERIFIED in this file with a reason and the fix.",
        )
    }

    /** An exemption may not outlive its reason: a demo that has gained a transcript leaves [UNVERIFIED]. */
    @Test
    fun anUnverifiedEntryThatIsNowVerifiedFails() {
        val registry = registry()
        val repaired = UNVERIFIED.keys.filter { demo ->
            File("$TRANSCRIPTS/$demo.yaml").isFile && demo in registry
        }.sorted()
        assertEquals(
            emptyList(),
            repaired,
            "$repaired now have registered transcripts; delete their UNVERIFIED entries so the list " +
                "keeps meaning what it says",
        )
        val absent = (UNVERIFIED.keys - demoDirectories().toSet()).sorted()
        assertEquals(emptyList(), absent, "$absent are in UNVERIFIED but are not demo documents at all")
    }

    /**
     * The three registries partition the corpus — they do not merely cover it.
     *
     * Both *completeness* directions are already held, a tenth of a second away, by
     * [DocumentedApiEvaluationTest.everyTranscriptCarriesAVerificationLevel]: an unclassified
     * transcript and a registry entry with no yaml both fail there. What nothing held is
     * *disjointness*, and the `when (name)` in `DemoTranscriptExecSmokeTest.main` takes the first
     * matching branch — so a transcript listed in both `COMPILE_ONLY` and `EXECUTE` is compiled
     * only, while its file says it is run. A weaker verification than the registry advertises is
     * exactly the failure mode this branch is about, in miniature.
     *
     * Population measured 2026-09-21: 143 transcripts, 143 registry entries, no overlaps.
     */
    @Test
    fun theThreeVerificationLevelsAreDisjoint() {
        val root = File(TRANSCRIPTS)
        assertTrue(root.isDirectory, "no transcript corpus at ${root.absolutePath}")
        val onDisk = (root.listFiles() ?: emptyArray())
            .filter { it.isFile && it.extension == "yaml" }
            .map { it.nameWithoutExtension }
            .toSet()
        assertTrue(onDisk.size >= 143, "the transcript corpus has shrunk to ${onDisk.size}; it was 143 on 2026-09-21")
        val entries = DemoTranscriptExecSmokeTest.EXECUTE.size +
            DemoTranscriptExecSmokeTest.COMPILE_ONLY.size +
            DemoTranscriptExecSmokeTest.KOTLINJS.size
        assertTrue(
            entries >= 143,
            "the verification registries have shrunk to $entries entries; they held 143 on 2026-09-21",
        )

        val classifiedTwice = DemoTranscriptExecSmokeTest.EXECUTE
            .intersect(DemoTranscriptExecSmokeTest.COMPILE_ONLY)
            .plus(DemoTranscriptExecSmokeTest.EXECUTE.intersect(DemoTranscriptExecSmokeTest.KOTLINJS))
            .plus(DemoTranscriptExecSmokeTest.COMPILE_ONLY.intersect(DemoTranscriptExecSmokeTest.KOTLINJS))
            .sorted()
        assertEquals(
            emptyList(),
            classifiedTwice,
            "$classifiedTwice appear in more than one registry; the `when` in " +
                "DemoTranscriptExecSmokeTest.main takes the first branch, so the second classification " +
                "is silently dead and the transcript is verified more weakly than the file says",
        )
    }
}
