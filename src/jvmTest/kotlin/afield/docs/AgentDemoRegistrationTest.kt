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
         * The twenty-five demo directories this file was measured against, 2026-09-23.
         *
         * Twenty-five directories, twenty-four of them documents; see [demoDocuments] for why the
         * two populations came apart and which assertions range over which.
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
            // 2026-09-23: interval-arithmetic envelopes for Fn; registered EXECUTE.
            "certified_envelope",
            "chart_clip",
            "chart_primitive",
            "completion_rank_at_scale",
            "confluence_grid",
            // Transcript sources, registered COMPILE_ONLY with separate Vulkan GPU checks.
            "direct_lighting",
            "learned_from_module",
            "learned_shader",
            "neural_reconstruction",
            "optical_flow",
            "optical_flow_pingpong",
            "ray_reconstruction",
            "reconstruction_plumbing",
            "rr_plumbing",
            // Landed 2026-09-22 in `b5b9ad9e0`. Not a document — no `metadata.edn` — so it is named
            // here (this set is the directory listing) and is invisible to the two assertions that
            // range over `demoDocuments()`. It is verified: `flow_grain.yaml` under
            // `dev_docs/agents/transcripts/` is registered COMPILE_ONLY, so its box Kotlin
            // compiles on every build. It does not
            // belong in UNVERIFIED, and would fail `anUnverifiedEntryThatIsNowVerifiedFails` if
            // put there, twice over: it is not a demo document, and it is not unverified.
            "flow_grain",
            "frame_history_reprojection",
            // Both landed 2026-09-23, the twenty-fourth and twenty-fifth, in `948602d5a` and
            // `7fc49b2ed`: the blank starter and the worked example of one AI4 function
            // conversation. They are demo *documents* — both carry a `metadata.edn` — so unlike
            // `flow_grain` they are inside [everyDemoDocumentIsRegisteredForVerification]'s
            // population and need a registered transcript or an [UNVERIFIED] entry.
            "function_conversation",
            "function_conversation_example",
            // Promoted 2026-09-22 out of `dev_docs/agents/artifacts/`, where its author parked it
            // because this class's sibling `DemoTranscriptExecSmokeTest` was held by a concurrent
            // session that night. That routing was declared at the time and it cost exactly what
            // was predicted: nothing under `artifacts/` is named by any registry, so for one day
            // the only evidence its four boxes ran was that a human had run them once, by hand.
            "gpu_probe_bench",
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
         *
         * It survived its first real test on 2026-09-22 by *not* growing. `flow_grain` arrived in
         * `b5b9ad9e0` and three corpus ledgers went stale, so the question was put as: does it need
         * an entry here? No, on two independent grounds — it is not a demo document (no
         * `metadata.edn`, so [anUnverifiedEntryThatIsNowVerifiedFails] rejects the entry outright),
         * and its transcript was registered `COMPILE_ONLY` in the same commit, so the Kotlin this
         * list would have been excusing is compiled on every build already. The commit's gap was
         * the three name-lists, not the verification.
         *
         * **It grew again on 2026-09-23, and this time the prescribed fix is not free.** The two
         * `function_conversation` documents are demo documents, so the `flow_grain` reasoning does
         * not reach them: their box Kotlin — a 198-line tools box, byte-identical in both — is
         * compiled by nothing. Their only driver, `tools/run_function_conversation.py`, sends a
         * real AI4 model turn, is referenced by no Gradle task and carries no `FIELD_TEST`, so it
         * can never be the gate. A `COMPILE_ONLY` transcript each is still the right fix and would
         * compile both bodies without a model. What stops it being a nightly-CI edit is the
         * cascade: every *top-level* transcript must have a `### name` entry on
         * `public/docs/agent_transcripts.md` — the index guard holds those equal in both
         * directions, 146 = 146 — and `EXCLUDE` only suppresses the materialized bundle, not the
         * entry. So writing them publishes two new starting points in the public gallery for
         * documents already published a different way, as zip downloads from
         * `public/docs/function_conversation.md` built by `tools/package_function_conversation.py`.
         * That is an editorial call about the gallery, not a CI repair, so it is written down here
         * and left for a person.
         */
        val UNVERIFIED = mapOf(
            "function_conversation" to
                "Landed 2026-09-23 in `948602d5a` with no transcript; its box Kotlin is compiled by " +
                "nothing. Fix: write `dev_docs/agents/transcripts/function_conversation.yaml` as " +
                "COMPILE_ONLY (the tools box opens no window and needs no model to compile) and add " +
                "its `### function_conversation` entry to public/docs/agent_transcripts.md — or " +
                "decide the gallery should not carry it and say so here instead.",
            "function_conversation_example" to
                "Landed 2026-09-23 in `7fc49b2ed`, the worked example beside the blank starter; same " +
                "byte-identical tools box, same gap. Fix: the same COMPILE_ONLY transcript plus " +
                "gallery entry, or a recorded decision that the zip download on " +
                "public/docs/function_conversation.md is how these two ship.",
        )
    }

    /**
     * Every directory under `dev_docs/agents/demos/` — the population [DEMO_CORPUS] is a list of.
     *
     * Unfiltered on purpose. This is the thing a sibling branch grows, and the corpus assertion has
     * to see an arrival whatever shape it is in; a directory that this method skipped would be
     * invisible to every corpus guard in the tree, which is a worse failure than an unclassified one.
     */
    private fun demoDirectories(): List<String> {
        val root = File(DEMOS)
        assertTrue(root.isDirectory, "no demo corpus at ${root.absolutePath}")
        return (root.listFiles() ?: emptyArray())
            .filter { it.isDirectory }
            .map { it.name }
            .sorted()
    }

    /**
     * The subset of [demoDirectories] that is a loadable *document*, i.e. ships a `metadata.edn`.
     *
     * The two were the same method until 2026-09-22, when `flow_grain` landed in `b5b9ad9e0` as the
     * first entry under `demos/` that is not a saved document: it holds the hand-authored `.kt` and
     * GLSL sources that `tools/demos/build_flow_grain.py` assembles into
     * `dev_docs/agents/transcripts/flow_grain.yaml`. Only documents can be replayed or classified, so only
     * documents are what [everyDemoDocumentIsRegisteredForVerification] and [UNVERIFIED] range over
     * — while [DEMO_CORPUS] ranges over the directory listing. Collapsing the two again makes one of
     * those two assertions state a rule over a population it does not hold, which is the exact
     * defect `dev_docs/GUARD_CORPORA.md` exists to name.
     */
    private fun demoDocuments(): List<String> =
        demoDirectories().filter { File("$DEMOS/$it/metadata.edn").isFile }

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
        assertTrue(found.size >= 22, "the demo corpus has shrunk to ${found.size}; it was 22 on 2026-09-22")
    }

    /**
     * Nothing may be *in* the corpus without being verified by something, or listed as not.
     *
     * This is the assertion the demo corpus never had. It was held open by [UNVERIFIED] until
     * 2026-09-21; with that map empty, the next demo *document* arriving with no transcript fails
     * it outright, and the failure message is the whole remedy.
     *
     * Ranges over [demoDocuments], not [demoDirectories]: a directory with no `metadata.edn` cannot
     * be replayed or classified, so demanding a transcript of one would be a rule stated over a
     * population that cannot satisfy it.
     */
    @Test
    fun everyDemoDocumentIsRegisteredForVerification() {
        val registry = registry()
        val unregistered = demoDocuments().filter { demo ->
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
        val absent = (UNVERIFIED.keys - demoDocuments().toSet()).sorted()
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
     * Population measured 2026-09-22: 145 transcripts, 145 registry entries, no overlaps.
     */
    @Test
    fun theThreeVerificationLevelsAreDisjoint() {
        val root = File(TRANSCRIPTS)
        assertTrue(root.isDirectory, "no transcript corpus at ${root.absolutePath}")
        val onDisk = (root.listFiles() ?: emptyArray())
            .filter { it.isFile && it.extension == "yaml" }
            .map { it.nameWithoutExtension }
            .toSet()
        assertTrue(onDisk.size >= 145, "the transcript corpus has shrunk to ${onDisk.size}; it was 145 on 2026-09-22")
        val entries = DemoTranscriptExecSmokeTest.EXECUTE.size +
            DemoTranscriptExecSmokeTest.COMPILE_ONLY.size +
            DemoTranscriptExecSmokeTest.KOTLINJS.size
        assertTrue(
            entries >= 145,
            "the verification registries have shrunk to $entries entries; they held 145 on 2026-09-22",
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
