package afield.tools.docs

import afield.examples.DemoTranscriptExecSmokeTest
import java.io.File
import java.nio.file.Files
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
        const val DOCS_ASSETS = "public/docs/assets"

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
            // 2026-10-12: tree()/column()/logs() inspector embeds; registered EXECUTE.
            "browse_embeds",
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
            // Landed 2026-10-02 in `cdb8e2cf8`. Like `flow_grain`, not a document: it holds the
            // sources of `open_dlss_nr.yaml` and `open_dlss_nr_cubes.yaml`, both COMPILE_ONLY.
            "opendlssnr",
            "optical_flow",
            "optical_flow_pingpong",
            "ray_reconstruction",
            "computed_ray_geometry",
            "gpu_shader_learning",
            "path_traced_reconstruction",
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
            // 2026-10-10: Bayesian optimisation and A/B preference learning; registered EXECUTE.
            "gp_instruments",
            // 2026-10-10: Gaussian processes to play with (closed loop, 1-D, 2-D); registered EXECUTE.
            "gp_drawing",
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
            // 2026-10-18: the Clojure counterpart of python_box_parity.
            "clojure_box_parity",
            "python_box_parity",
            "python_cells",
            "read_only_session",
            "skinned_characters",
            "solidsl_inverse",
            // Landed 2026-09-26: the blank starter and three recorded sessions of the StackSL
            // conversation ("Talking a shader into existence"). All four are demo documents, and
            // all four are in UNVERIFIED for the reason the function conversation is.
            "stacksl_conversation",
            "stacksl_conversation_aurora",
            "stacksl_conversation_ink",
            "stacksl_conversation_tiles",
            // Landed 2026-09-27: the RectSL conversation ("Talking a scene into existence"), its blank
            // starter and three recorded sessions, in UNVERIFIED for the same reason.
            "rectsl_conversation",
            "rectsl_conversation_lanterns",
            "rectsl_conversation_score",
            "rectsl_conversation_tide",
            // Landed 2026-09-28: a recorded conversation with the two stack.auto slots inside one box's
            // shaders, and (2026-09-29) its starter, both in UNVERIFIED for the same reason.
            "stackauto_conversation",
            "stackauto_conversation_polytope",
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
         * compiled by nothing. Their only driver, `tools/conversation_demo.py record`, sends a
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
                "Kept out of the transcript registry by decision (Marc, 2026-09-26): the demo is an " +
                "AI4 conversation and needs a provider key to use, so it ships as the zip download on " +
                "public/docs/function_conversation.md rather than as a gallery transcript. Since " +
                "2026-09-26 its study tools are library code (afield.math.functions.FunctionStudyTools, covered " +
                "by FunctionStudyTest and the demo checks in tools/test_conversation_demos.py), so the " +
                "only box Kotlin is the function source itself, which the study compiles when it opens.",
            "function_conversation_example" to
                "The worked example beside the blank starter, kept out for the same reason and shipped " +
                "the same way. Its study recompiles the checked revision when the document opens; nothing " +
                "here compiles it as a transcript.",
            "stacksl_conversation" to
                "The StackSL counterpart of function_conversation, kept out of the transcript registry " +
                "for the same reason (an AI4 conversation needs a provider key) and shipped the same " +
                "way: the starter zip on public/docs/stacksl_conversation.md, built by " +
                "tools/package_stacksl_conversation.py. It holds no Kotlin: since 2026-09-27 its root has no " +
                "code (the language opens the window, afield.runtime.languages.LanguageRoots), and its program is " +
                "StackSL, which StackSLLanguageTest compiles.",
            "stacksl_conversation_aurora" to
                "A recorded session beside the stacksl_conversation starter, with the starter's codeless " +
                "root (tools/stacksl_conversation_documents.py --check holds them equal). " +
                "StackSLConversationExamplesTest replays its revisions and recorded comparisons.",
            "stacksl_conversation_ink" to
                "A recorded session beside the stacksl_conversation starter; same root, same replay " +
                "test, same reason as stacksl_conversation_aurora.",
            "stacksl_conversation_tiles" to
                "A recorded session beside the stacksl_conversation starter; same root, same replay " +
                "test, same reason as stacksl_conversation_aurora.",
            "rectsl_conversation" to
                "The RectSL counterpart of stacksl_conversation, kept out of the transcript registry for " +
                "the same reason and shipped the same way: the starter zip on " +
                "public/docs/rectsl_conversation.md, built by tools/package_rectsl_conversation.py. It " +
                "holds no Kotlin: its root has no code (the language opens the window) and its scene is " +
                "RectSL, which RectSLLanguageTest compiles.",
            "rectsl_conversation_lanterns" to
                "A recorded session beside the rectsl_conversation starter, with the starter's codeless root " +
                "(tools/rectsl_conversation_documents.py --check holds them equal). " +
                "RectSLConversationExamplesTest replays its revisions and recorded comparisons.",
            "rectsl_conversation_score" to
                "A recorded session beside the rectsl_conversation starter; same root, same replay test, " +
                "same reason as rectsl_conversation_lanterns.",
            "rectsl_conversation_tide" to
                "A recorded session beside the rectsl_conversation starter; same root, same replay test, " +
                "same reason as rectsl_conversation_lanterns.",
            "stackauto_conversation" to
                "The starter for stackauto_conversation_polytope, as that recording began, shipped as the zip on " +
                "public/docs/stackauto_conversation.md (tools/package_stackauto_conversation.py). Like the " +
                "recording, its Kotlin needs a window; StackAutoConversationExampleTest checks it holds each " +
                "slot's first revision and the host lines the first Inspect of each slot read.",
            "stackauto_conversation_polytope" to
                "A recorded AI4 conversation, kept out of the transcript registry for the reason the other " +
                "conversations are (a turn needs a provider key). Its box Kotlin builds a mesh under a " +
                "renderer, so it needs a window to run; StackAutoConversationExampleTest compiles every " +
                "recorded revision of both slots into the box's own shaders and checks the code holds each HEAD.",
        )
    }

    /**
     * Saved documents under `public/docs/assets/` that are not yet starting points.
     *
     * A demo is a place to start, or it is not a demo (Marc, 2026-10-09). These documents sit
     * beside the pages that describe them, and until they are starting points a page can only name
     * them by repository path, which DOCS_WRITING_RULES rule 19 forbids and which a reader cannot
     * open. Eleven docs handoffs in one week asked for the same repair one page at a time; this
     * list is that repair's backlog. It may only shrink: [everyDocsAssetDocumentIsAStartingPoint]
     * fails on a new arrival, and [aStartingPointLeavesTheBacklog] fails on an entry that has been
     * promoted.
     *
     * To promote one: `run-jvm-main afield.examples.DocumentToTranscript --arg <asset dir>
     * --arg $TRANSCRIPTS/<name>.yaml`, then the steps in `$TRANSCRIPTS/README.md` (a registry
     * bucket, a gallery entry, `regenerateAgentTranscriptStartingPoints`). The generator writes no
     * launch ops; an EXECUTE transcript needs them added. Measured 2026-10-09.
     */
    private val ASSET_DOCUMENTS_NOT_YET_STARTING_POINTS = setOf(
        "brief_house_demo",
        "canonical_graphics",
        "fline_examples",
        "nano_examples",
        "rectsl_gallery",
        "stacksl_cpu_demo",
        "stacksl_surrogate_demo",
        "three_mesh_pick_demo",
        "three_output_gallery",
    )

    /**
     * Every directory under `dev_docs/agents/demos/` — the population [DEMO_CORPUS] is a list of.
     *
     * Include untracked additions so the guard catches a new demo before it is committed. Git-ignored
     * directories are local scratch data, not candidates for the published demo corpus.
     */
    private fun demoDirectories(root: File = File(DEMOS), gitRoot: File = File(".")): List<String> {
        assertTrue(root.isDirectory, "no demo corpus at ${root.absolutePath}")
        return (root.listFiles() ?: emptyArray())
            .filter { it.isDirectory && !gitIgnored(it, gitRoot) }
            .map { it.name }
            .sorted()
    }

    private fun gitIgnored(directory: File, gitRoot: File): Boolean {
        val status = ProcessBuilder("git", "-C", gitRoot.canonicalPath, "check-ignore", "-q", "--", directory.canonicalPath)
            .start().waitFor()
        check(status == 0 || status == 1) { "git check-ignore failed for ${directory.path}: exit $status" }
        return status == 0
    }

    @Test
    fun gitIgnoredScratchIsNotADemoButAnUntrackedDirectoryIs() {
        val repo = Files.createTempDirectory("demo-corpus-").toFile()
        try {
            val init = ProcessBuilder("git", "-c", "init.defaultBranch=main", "init", "-q", repo.path)
                .start().waitFor()
            assertEquals(0, init, "could not initialize a temporary Git repository")
            File(repo, ".gitignore").writeText("demos/scratch/\n")
            val demos = File(repo, "demos").apply { mkdir() }
            File(demos, "scratch").mkdir()
            File(demos, "new_demo").mkdir()
            assertEquals(listOf("new_demo"), demoDirectories(demos, repo))
        } finally {
            repo.deleteRecursively()
        }
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

    /** The saved documents under `public/docs/assets/`, other than the materialized starting points. */
    private fun docsAssetDocuments(): List<String> =
        (File(DOCS_ASSETS).listFiles() ?: emptyArray())
            .filter { it.isDirectory && it.name != "agent_transcripts" && File(it, "metadata.edn").isFile }
            .map { it.name }
            .sorted()

    private fun isStartingPoint(name: String): Boolean =
        File("$TRANSCRIPTS/$name.yaml").isFile && name in registry() &&
            File("$DOCS_ASSETS/agent_transcripts/$name/metadata.edn").isFile

    /**
     * A document a docs page stands beside is a starting point, so the page can link it.
     *
     * Ranges over the documents saved directly under `public/docs/assets/`. Those under
     * `dev_docs/agents/demos/` are held to the same thing by [everyDemoDocumentIsAStartingPoint].
     */
    @Test
    fun everyDocsAssetDocumentIsAStartingPoint() {
        val missing = docsAssetDocuments().filter { !isStartingPoint(it) && it !in ASSET_DOCUMENTS_NOT_YET_STARTING_POINTS }
        assertEquals(
            emptyList(),
            missing,
            "document(s) $missing under $DOCS_ASSETS are not starting points, so no page can link them. " +
                "Generate `$TRANSCRIPTS/<name>.yaml` with afield.examples.DocumentToTranscript, register " +
                "it in DemoTranscriptExecSmokeTest, give it a gallery entry, and run " +
                "`./gradlew regenerateAgentTranscriptStartingPoints` (see $TRANSCRIPTS/README.md).",
        )
    }

    /** The backlog only shrinks: a promoted document leaves it, and a vanished one cannot stay in it. */
    @Test
    fun aStartingPointLeavesTheBacklog() {
        val promoted = ASSET_DOCUMENTS_NOT_YET_STARTING_POINTS.filter(::isStartingPoint).sorted()
        assertEquals(
            emptyList(),
            promoted,
            "$promoted are starting points now; delete them from ASSET_DOCUMENTS_NOT_YET_STARTING_POINTS",
        )
        val absent = (ASSET_DOCUMENTS_NOT_YET_STARTING_POINTS - docsAssetDocuments().toSet()).sorted()
        assertEquals(emptyList(), absent, "$absent are in the backlog but are not documents under $DOCS_ASSETS")
    }

    /**
     * Every demo document under `dev_docs/agents/demos/` is a starting point, unless [UNVERIFIED]
     * says why not. Registration alone is not enough: [AgentTranscriptMaterializer.EXCLUDE] can keep
     * a registered transcript off the welcome screen.
     */
    @Test
    fun everyDemoDocumentIsAStartingPoint() {
        val missing = demoDocuments().filter { !isStartingPoint(it) && it !in UNVERIFIED }
        assertEquals(
            emptyList(),
            missing,
            "demo document(s) $missing are registered but not materialized as starting points; take " +
                "them out of AgentTranscriptMaterializer.EXCLUDE or run regenerateAgentTranscriptStartingPoints",
        )
    }
}
