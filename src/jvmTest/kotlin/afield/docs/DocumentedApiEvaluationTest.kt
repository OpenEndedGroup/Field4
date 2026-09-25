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

    private val root = CitationGuard.repoRoot()

    /**
     * The documented APIs that CI compiles and never runs, each with the reason it is that way.
     *
     * Every row was checked by hand against all three exercise populations before being written here.
     * The reasons are not excuses, and the point of writing them down is that "this needs hardware CI
     * does not have" and "nobody got round to it" look identical in a build log and want completely
     * different responses.
     *
     * It was five on the day the class landed. `clipEncode` came off the same day and is the example
     * worth keeping in mind: `public/docs/onnx.md` teaches it six times, `ClipSmokeTest` already had
     * the text encoder resident and asserted against `ClipText.encodeAll` underneath it, and closing
     * the gap was one line. The lesson is not that the ledger was wrong — it was right — but that a
     * row's reason is worth re-reading, because "the transcript that demonstrates it is COMPILE_ONLY"
     * and "no cheap evaluator exists" are different sentences.
     */
    private val COMPILE_ONLY_REMAINDER: Map<String, String> = mapOf(
        // Visible since the page census includes bare API names, not only call syntax (W1).
        // The 2026-09-24 NVIDIA/RTXDI landing. Every one of these needs hardware this repository's
        // CI does not have — an NVIDIA device with the RTXDI and DLSS paths — so the pages teach
        // them and nothing headless can run them. Recorded here rather than left to fail, and
        // recorded as six rows rather than a raised ceiling so that each says what it is waiting
        // for and a reader can tell when one becomes reachable.
        "enableRtxdi" to
            "direct_lighting.md; DirectLighting.enableRtxdi installs NVIDIA's RTXDI resampling. " +
                "It needs an NVIDIA device exposing that path; MoltenVK does not, and the page's " +
                "transcript compiles the Kotlin only.",
        "rtxdiSettings" to
            "direct_lighting.md; the Bundle extension that builds the settings enableRtxdi takes. " +
                "Same gate as enableRtxdi — reachable only where the RTXDI path exists.",
        "trainAsync" to
            "learned_shader.md; ShaderTraining.trainAsync starts a 12000-step training run against " +
                "a GPU and returns a handle. Minutes of device work, so no headless evaluator " +
                "invokes it; the page's transcript compiles the call.",
        "fromViews" to
            "reconstruction_plumbing.md; builds a mip-aware TextureSampler over live image views, " +
                "and its own KDoc says that case 'needs a live graphics context on this thread'. " +
                "The reconstruction transcripts are COMPILE_ONLY for exactly that reason.",
        "evaluateUniform" to
            "keyframe_autosweep.md, reconstruction_plumbing.md, ray_reconstruction.md; an " +
                "Interpolant override sampled by the reconstruction jitter path. The documents that " +
                "teach it are reconstruction documents, and those transcripts need a supporting " +
                "device.",
        "modelView" to
            "afield_four_graphics.md and three reconstruction pages; this is the scene-local view " +
                "matrix a caller passes in, not a Field entry point — the same shape as modelWorld " +
                "above. The lexical census sees the name; the containing graphics transcripts are " +
                "COMPILE_ONLY.",
        "computeMemoryBarrier" to
            "agent_transcripts.md; a Vulkan command-buffer barrier. Its compiled graphics " +
                "transcripts need a live device to submit and observe the dependency.",
        "flowSmoothVelocity" to
            "flow_grain.md; a GLSL helper evaluated in the native flow_grain capture. " +
                "Headless transcript verification compiles the Kotlin, not the shader execution.",
        "flowStrokeClip" to
            "flow_grain.md; a GLSL stroke projection helper used by the native drawing pass. " +
                "The transcript is COMPILE_ONLY; no registered evaluator reaches this shader.",
        "takeFocus" to
            "puppet_browser.md; PuppetBrowserKeyboard needs a native Window and browser producer. " +
                "youtube_vr_browser compiles this focus handoff but does not execute it in CI.",
        "cvtColor" to
            "imaging_examples.md names OpenCV's conversion as a comparison. onnx_webcam_live " +
                "calls it but is COMPILE_ONLY because it needs a camera. A synthetic Mat test " +
                "could cover the conversion independently; this row records that remaining gap.",
        "modelWorld" to
            "openxr_eye_intersection.md; this is the scene-local matrix callback parameter of " +
                "lineMesh, not a global Field API. The lexical census includes its invocation; " +
                "the containing OpenXR transcript is COMPILE_ONLY and needs a live headset.",
        "flowMask" to
            "flow_grain.md; this is an editable GLSL obstacle callback. The native flow_grain " +
                "capture evaluates it on a GPU; headless CI compiles the document's Kotlin only.",
        "flowVelocity" to
            "flow_grain.md; this is an editable GLSL velocity callback over live fluid buffers. " +
                "The native flow_grain capture evaluates it outside headless CI.",
        // The five the path-tracer instrument teaches. They became visible to this guard when
        // `path_tracer_instrument` was promoted out of `dev_docs/agents/demos/` into the corpus:
        // the scene was always documented, and until then nothing in CI compiled it either.
        "integratorSource" to
            "path_tracing.md; path_tracer_instrument is COMPILE_ONLY. Handing back the packaged " +
                "integrator with this device's HARDWARE_QUERY define applied needs the device.",
        "reloadIntegrator" to
            "path_tracing.md; path_tracer_instrument is COMPILE_ONLY. A hot-swap recompiles ~30 KB " +
                "of GLSL against a live pipeline, and its three outcomes are outcomes of a GPU.",
        "recordConvergence" to
            "path_tracing.md; path_tracer_instrument is COMPILE_ONLY. There is no convergence to " +
                "record without a tracer accumulating samples on a device.",
        "showConvergence" to
            "path_tracing.md; path_tracer_instrument is COMPILE_ONLY. It plots what " +
                "recordConvergence collected, so it cannot be evaluated where that cannot.",
        "getName" to
            "path_tracing.md; path_tracer_instrument is COMPILE_ONLY. The name is the live " +
                "integrator's, read off the pipeline currently installed on the device.",
        "pathTracingHere" to
            "path_tracing.md; creating the tracer needs a live renderer in Field graph context. " +
                "Live material-box checks exercise installation and shader reload outside headless CI.",
        "shadeMaterial" to
            "path_tracing.md; this is a GLSL material hook in the editable compute shader. " +
                "Live material-box checks exercise it on a GPU; headless CI compiles the transcript.",
        "eyeGazeState" to
            "vision.md; openxr_eye_intersection is COMPILE_ONLY. The target requires a live " +
                "Vulkan/OpenXR session. Native binding calls and state validity are tested separately.",
        "htcEyeState" to
            "vision.md; openxr_eye_intersection is COMPILE_ONLY. The target requires a live " +
                "Vulkan/OpenXR session and HTC eye tracker. Native calls and triangulation have JVM tests.",
        "appendChild" to
            "puppet_browser.md reaches this inside JavaScript executed by the native Electron " +
                "producer. The graphics transcript is COMPILE_ONLY because CI has no window or " +
                "cross-process browser-texture transport to run it.",
        "createElement" to
            "puppet_browser.md reaches this inside JavaScript executed by the native Electron " +
                "producer. The graphics transcript is COMPILE_ONLY because CI has no window or " +
                "cross-process browser-texture transport to run it.",
        "enableDesktopInteraction" to
            "puppet_browser.md; youtube_vr_browser is COMPILE_ONLY because it creates a native " +
                "Vulkan/OpenXR window and routes its live controller stand-in into Chromium.",
        // `flushInput` and `onPageRequest` were rows here until 2026-08-29, both justified by "CI has
        // no native producer process". HeadlessPuppetBrowserTest now spawns one — no GPU, no network,
        // a committed file:// fixture — and asserts on both. The backward direction of this ledger is
        // what noticed; the rows are deleted rather than reworded.
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
        // Added 2026-08-29 with gpu_state.md, which exists to document buffer residency and is the
        // first page in the repo to teach either of these. Both are one-line members of the buffer
        // block and both are exercised by `gpu_state`, whose transcript is classified COMPILE_ONLY
        // because it opens a GLFW window and a Vulkan renderer the lightweight build does not own —
        // the same reason `threebox` is. Evaluating them needs a GPU in CI, not a new test.
        "writeOnce" to
            "gpu_state.md; gpu_state is COMPILE_ONLY — a device-local buffer uploaded once needs a " +
                "real Vulkan device, which the lightweight build has no renderer for.",
        "zeroInit" to
            "gpu_state.md; gpu_state is COMPILE_ONLY. Zero-initialised device-local storage is a " +
                "residency choice made at allocation, so there is nothing to assert without a device.",
        // Added when skinned_characters entered the transcript corpus on 2026-09-21.
        // Its device requirement remains real. The accompanying displayClearAll debt was removed
        // when marf_field_clocks entered EXECUTE: that scene needs no audio device or renderer.
        "gltfModelHere" to
            "afield_four_graphics.md, geometry_from_disk.md; skinned_characters is COMPILE_ONLY " +
                "because it opens a GLFW window and a Vulkan renderer. The call installs a loaded " +
                "glTF into a live renderer's scene graph, so there is no device-free thing to assert.",
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

    private fun pagesNaming(api: String, pages: List<Pair<String, String>> = publishedPages): List<String> {
        // Prose and inline code name APIs without calling them. Match whole identifiers so
        // marksOf is visible but marksOfTree and _marksOf are not mistaken for that API.
        val rx = Regex("""(?<![\p{L}\p{N}_$])${Regex.escape(api)}(?![\p{L}\p{N}_$])""")
        return pages.filter { (_, text) -> rx.containsMatchIn(text) }.map { it.first }
    }

    @Test
    fun documentationNamesNeedNotBeCalls() {
        val pages = listOf(
            "prose.md" to "Use marksOf to inspect the result.",
            "code.md" to "The `marksOf` API.",
            "call.md" to "marksOf(term)",
            "qualified.md" to "Lambda.marksOf",
            "other.md" to "marksOfTree _marksOf marksOf_ remarksOf λmarksOf marksOfλ",
        )
        assertEquals(listOf("prose.md", "code.md", "call.md", "qualified.md"), pagesNaming("marksOf", pages))
        assertEquals(emptyList(), pagesNaming("missingApi", pages))
        assertTrue("interaction_nets_examples.md" in pagesNaming("marksOf"))
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
        val rx = Regex("""\b${Regex.escape(api)}\s*(?:<[^<>\n]{0,60}>)?\s*[({]""")
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
            .map { (name, raw) -> name to KotlinSourceText.stripComments(raw) }

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
                    "on a published page, or the scan has broken. Check the population " +
                    "figures above before deleting the row — a collapsed denominator presents exactly " +
                    "like a tidy-up.",
        )
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    /**
     * The three non-evaluator exclusions, each asserted against the mechanism rather than an API.
     *
     * An earlier version pinned all three through `clipEncode`, which was the only API in the tree
     * whose every mention outside its own declaration was prose about it not being run. That stopped
     * being true the moment `ClipSmokeTest` was given the documented one-liner, and a fixture that
     * dissolves the first time somebody fixes the underlying problem is the wrong fixture. So each
     * exclusion is now pinned twice: the mechanism still applies, and the thing it excludes is still
     * there to be excluded. Same shape as
     * `CitationContractTest.theInputThatRulesOutTheGradleMarkerIsReallyInThisRepo`.
     */
    @Test
    fun theThreeNonEvaluatorExclusionsAreStillNeededAndStillWork() {
        // 1. Comments are not evaluations. `afield/onnx/Clip.kt` documents its own entry point in its
        //    own KDoc, which scored the declaration site as its own evaluator before stripping.
        val declaration = File(root, "src/jvmMain/kotlin/afield/onnx/Clip.kt")
        assertTrue(declaration.isFile, "afield/onnx/Clip.kt moved; the comment-stripping witness is gone")
        val rx = Regex("""\bclipEncode\s*\(""")
        val raw = rx.findAll(declaration.readText()).count()
        val kept = rx.findAll(KotlinSourceText.stripComments(declaration.readText())).count()
        assertTrue(
            raw > kept,
            "afield/onnx/Clip.kt carries $raw clipEncode( call site(s) and stripComments keeps $kept " +
                "of them. With nothing dropped, nothing here demonstrates that a call written in a " +
                "KDoc example is not an evaluation."
        )

        // 2. The compile-only harness is not an evaluator. It is excluded by file name, before its
        //    text is even read, because it names APIs precisely in order to say it does not run them.
        val harness = File(root, "src/jvmMain/kotlin/afield/examples/DemoTranscriptExecSmokeTest.kt")
        assertTrue(harness.isFile, "the compile-only harness moved; ${harness.path} no longer exists")
        assertTrue(
            "DemoTranscriptExecSmokeTest.kt" !in smokeSources.map { it.first },
            "the compile-only harness is being scanned as a smoke. Whatever it mentions would score " +
                "as evaluated, and what it mentions is exactly the set of things it does not run."
        )

        // 3. This package's own prose is not evidence. Found on run one: the sentence in this file
        //    explaining exclusion 2 scored as an evaluating jvmTest.
        assertTrue(
            testSources.none { it.first == "DocumentedApiEvaluationTest.kt" || it.first == "GateInputDeclarationTest.kt" },
            "the afield.docs guard package is being scanned as evidence of evaluation. A guard that " +
                "counts its own documentation as proof is the failure this class is named after."
        )
        assertTrue(
            COMPILE_ONLY_REMAINDER.keys.any { api ->
                api in File(root, "src/jvmTest/kotlin/afield/docs/DocumentedApiEvaluationTest.kt").readText()
            },
            "no COMPILE_ONLY_REMAINDER key is named in this file's own text any more, so exclusion 3 " +
                "is no longer demonstrated by anything. Re-read the argument for it before trusting it."
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
        val stripped = KotlinSourceText.stripComments(src)
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
