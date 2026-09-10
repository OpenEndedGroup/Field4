package afield.examples

import afield.Box
import afield.OperationTranscript
import afield.Rect
import afield.SavingContext
import afield.SessionInformation
import afield.box
import afield.isTrue
import afield.macros.checkAtticAndExecute
import afield.macros.checkIntroductionAndExecute
import afield.macros.checkPreambleAndExecute
import afield.remotejs.KotlinJsExecution
import afield.remotejs.kotlinJsTopologyNeedsUpdating
import afield.thisSessionInfo
import kotlinx.coroutines.runBlocking
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import kotlin.script.experimental.api.ScriptDiagnostic
import kotlin.system.exitProcess

/*
FIELD_TEST
id: examples.demo-transcript.exec
runner: jvm-main
entrypoint: afield.examples.DemoTranscriptExecSmokeTest
tags: [smoke, agents, transcript, docs, verification]
timeoutSec: 1200
expect:
  exitCode: 0
  stdoutContains:
    - "DemoTranscriptExecSmokeTest ok"
*/
/**
 * Verification-integrity harness: executes / compiles the box bodies of *every* shipped demo
 * transcript under `dev_docs/agents/transcripts/` and asserts they carry no diagnostics.
 *
 * ### On the timeout
 *
 * `timeoutSec` was 300 until 2026-08-12, when the 58th transcript pushed the sweep past it: the run
 * died at 4m46s having reached `spatial_geometry_examples`, within a handful of transcripts of the end.
 * The cost is concentrated rather than spread, so trimming is the wrong first instinct —
 * `morpho_circuit_search` alone is **66.5s** of GraalJS compile-and-simulate, five times the next
 * largest, and `faust_voice` is 13.7s of one-off libfaust warm-up. A cap at 1.05× the observed runtime
 * would flake on any machine slower than the one it was measured on, so it is now 600: roughly 2×,
 * with room for the corpus to keep growing.
 *
 * 600 then became marginal on 2026-08-25, with the Kotlin 2.4.20 fork uplift: the Kotlin/JS
 * transcripts (`remotear_*`, `vision_geometry_*`, `tone_synth_sequence`, `visionos_start`) had been
 * failing in milliseconds against a stale `kotlin-stdlib-js` klib, and once that was rebuilt they do
 * real Kotlin->JS compiles. The tell was progress, not the error: the run reached 108 `[execute]`
 * lines in ~300s before, and only 93 in 600s after. A full green sweep then measured **570s** — under
 * the old cap, but by 5%, which is precisely the margin the paragraph above says will flake on a
 * slower machine. Hence 1200: ~2x the measurement, same rule as before.
 *
 * If it times out again, **measure before trimming**. `AFIELD_DEMO_TRANSCRIPT_VERBOSE=1` echoes what
 * the boxes printed, and the `evaluate=<n>s` lines in the run's stdout attribute the time to the
 * transcript being replayed when they were emitted — which is how the two numbers above were found.
 * And note the harness caches the catalog: a `timeoutSec` edit does nothing until
 * `run_field_tests.py --refresh-catalog` (or a run whose `--id` is not in `build/field-tests/catalog.json`),
 * so a stale limit will re-fail at the old number and look like the edit had no effect.
 *
 * Why this exists: on 2026-07-18 `fn_differentiable_fit_demo.yaml` shipped with two compile errors
 * in its box body and merged, because nothing in CI ever *ran* the box Kotlin —
 * [OperationTranscriptMacroSmokeTest] only *applies* transcript ops and diffs the `code` string.
 * This test closes that gap by driving the REAL engine (no toy compiler):
 *
 *  - EXECUTE demos (pure-compute; no Window/Renderer): replayed end-to-end through the identical
 *    path a user/agent uses — [OperationTranscript.runMacro] -> executeFragmentAndWait -> the real
 *    compile+eval pipeline. A box compile error does not throw and does not clear `run.success`; it
 *    surfaces only as an output line with `kind == "error"` (see dispatchExecutionOutput in
 *    execution.kt). So we assert BOTH `run.success` AND zero error-kind output lines. This is the
 *    class of failure the fn-fit demo shipped with.
 *
 *  - COMPILE-ONLY demos (open a GLFW Window/Renderer or depend on a running renderer's context —
 *    unsafe/inappropriate to spin a render loop headless at 2am): the box graph is built with the
 *    real create/connect apply path, then each box's Kotlin `code` is run through the real macro
 *    expander (`applyMacros`), the real preamble machinery (`checkPreambleAndExecute` — which only
 *    evaluates property-delegate declarations, never a Window) and the real K2 compiler
 *    (`compileSnippetAndWait`) with EVAL SKIPPED. GLSL shader properties (`vertex`/`fragment`/
 *    `compute`) are not Kotlin and are not compiled here (they are exercised by the graphics render
 *    smokes). This still catches the fn-fit class of error (unresolved refs, ambiguous overloads).
 *
 * Adding a demo: drop the `*.yaml` in `dev_docs/agents/transcripts/`, then add its name to exactly
 * one of [EXECUTE] or [COMPILE_ONLY] below. An unclassified transcript fails the test on purpose.
 *
 * These three sets are also where the *public* index page's claim about CI comes from:
 * [afield.docs.AgentTranscriptIndexGuard] reads them and fails the build unless
 * `public/docs/agent_transcripts.md` has an entry for every transcript, filed under a section whose
 * declared verification level matches the set the transcript is in. That is why they are `internal`
 * rather than private — classification is decided here and nowhere else.
 */
object DemoTranscriptExecSmokeTest {

    /** Pure-compute demos that are safe to run to completion headless. */
    internal val EXECUTE = setOf(
        // Three bounded CPU/allocation workloads, one real recording, then stop and assert samples.
        "profiling_timeline",
        // The one EXECUTE demo that *launches* rather than executes: its box is a `loop { }`, so the
        // transcript launches it, runs 90 real runloop frames (startRunloopDaemon above is what
        // advances them) and stops it. Safe here because the whole scene is CPU physics plus
        // showPlane — Skia SVG -> display — so there is no Window and no Renderer to spin up.
        "cloth_simdverlet_start",
        // The world cloth_simdverlet_start hangs in: colliders, tearing, wind and a rope. Also a
        // launcher rather than an executor, and for the same reason — four frame loops. All four
        // boxes are launched together, which is the part worth holding down: four independent
        // SimdVerlet worlds stepping in one runloop. Same no-Window, no-Renderer argument as the
        // cloth — CPU physics plus showPlane, Skia SVG -> display.
        "simdverlet_world",
        "fn_differentiable_fit_demo",
        // The two ways to run a `@{...}` Kotlin right-hand side, side by side; all three boxes execute.
        // `fnrw_broken` is the one-box form, which used to re-enter its own box 172 levels deep and
        // never return — it is runnable here only because `FnRewriteSelfBoxReentry` now refuses the
        // second hydration, which breaks the recursion: its body runs exactly twice and the rewrite
        // then completes. Two "one box: body entered" lines in the verbose output are that, and are
        // expected. The refusal goes to the `error,fn-rewrite` log channel and NOT to box output, which
        // is why this stays green under the zero-error-lines assertion. The mechanical pair is
        // functions.rewrite-kotlin.{live-box,two-box}; this transcript is the loadable version, and it
        // is also where `parents.last()` as the host-box idiom is demonstrated.
        // See dev_docs/FN_REWRITE_SELF_BOX_REENTRY.md.
        "fn_rewrite_kotlin_boxes",
        // Blending two code keyframes. Pure CPU: the StackSL goes through the real compiler and then
        // through afield.stacksl.cpu's GLSL interpreter, and the fourth box compiles generated Kotlin
        // through K2 inside its own box — which is the one part of the feature a unit test cannot
        // reach, and the reason this is EXECUTE rather than COMPILE_ONLY.
        "keyframe_code_blend",
        "linalg_thumbnails_demo",
        "hello_display",
        "hello_display_capture",
        "two_box_context",
        // Compiles a data class onto the shared classloader and uses it from three other boxes, one
        // of them through @rootExported. Slower than its neighbours — box 1 shells out to
        // K2JVMCompiler and then recycles the other boxes' engines — but it is pure compute, and the
        // second run of box 1 is what holds the two engine-recycling regressions down.
        "shared_types_demo",
        // Kotlin DataFrame's compiler plugin. All FOUR boxes are executed. This comment used to say
        // three were, with the fourth created and deliberately not executed because it documented a
        // compile error on purpose — which was true of `dataframe_boundary`, the box d7d8dccc2
        // replaced with `dataframe_live`. That commit added the fourth `execute` op and rewrote the
        // public index paragraph, and did not touch this file, so the classification comment outlived
        // what it described. Box 4 is the `typed()` demo: two `//%-` fragments in one execution, which
        // is why running it here exercises the preamble re-entry path as well as the plugin.
        "dataframe_typed_columns",
        "spectral_examples",
        "imaging_cv_examples",
        "imaging_vector_kernels",
        "imaging_visualizers_demo",
        "mesh_geometry_examples",
        // Three pure-compute boxes that read geometry off disk: a committed GLB through Assimp, an
        // OBJ/STL round trip via a temp directory, and an ASCII PLY written and read back. Every
        // picture is showMesh — CPU rasterizer -> display — so no Window and no Renderer. The GLB box
        // is deliberately in CI: it is what makes "Field can look at what its back ends produce" a
        // checked claim rather than a page's assertion.
        "geometry_from_disk",
        "vision_metal_cube",
        // JVM Kotlin that compiles a Live Metal box's Kotlin/JS for real, runs it, and rasterises
        // the mesh it builds. No headset and no Metal toolchain, so it runs to completion here.
        "vision_geometry_desk_preview",
        // Compiles a Faust program with the vendored libfaust-wasm and draws it: a block diagram
        // straight out of the compiler, an offline render, and the UI metadata the compiler reports.
        // The playing box and the deliberately-broken box are NOT executed by the transcript — one
        // opens an audio device, the other fails on purpose — so neither can trip this harness.
        "faust_voice",
        // Faust as a polyphonic instrument that a `Pat` pattern plays. Four of its five boxes render
        // offline through the same `FaustVoiceBank` the audio thread uses, in 128-frame blocks, into WAV
        // bytes and a plot — so no audio device and no GPU. The fifth returns a live launch and is not
        // executed by the transcript.
        "faust_polyphony",
        "pattern_rewrite_worked_example",
        "rewrite_strategies_demo",
        // Three pure-compute boxes over the `pat` Fn graph op: one prints the node, two run a real
        // fitGradient over a rhythm's numeric leaves and plot the result. No Window, no Renderer.
        "pat_fn_graph_fit",
        // Eight pure-compute pattern boxes; showPatRoll is drawToSVG -> display, so no Window, no
        // Renderer, no GPU and no audio device.
        "pattern_language_composition",
        // Five pure-compute boxes closing notation -> sound -> notation. Every buffer is finite and
        // offline (patRenderStereo, doughRenderMono), the figure is drawToSVG -> display, and the
        // analysis is this repo's own YIN. No Window, no Renderer, no GPU, no audio device, and in
        // particular no services/field-audio-ai.
        "pitch_transcription_roundtrip",
        // Six pure-compute Petri boxes. showPetriNet is FLines -> display and showPatAudio renders
        // through the same offline CPU voice, so this plays without an audio device.
        "petri_sound",
        // Five pure-compute boxes over the exhaustive state-space analysis. showPetriStateSpace is
        // FLines -> display like showPetriNet, and nothing here makes a sound.
        "petri_state_space",
        // Three pure-compute boxes drawing one net under FORCE and under TALA. showPetriNet is
        // FLines -> display, and the TALA half falls back to FORCE when native/field-d2-layout is
        // not built, so this runs the same either way.
        "petri_tala_layout",
        // Nine boxes and their connections, created and wired but never executed: the scene is the
        // starting position for the "Lay out selection" commands, which the reader runs.
        "box_layout_demo",
        // Deterministic synthesised audio and token fixtures through the real CodecVisualization
        // renderer; it deliberately does not reach for EnCodec's GPU service, so it completes here.
        "audio_ai_codec",
        // The first box draws the real typed gesture-board renderer. The service-backed second box
        // remains inert until a user runs it with Field Audio available.
        "audio_ai_gesture_board",
        // The guide and synchronized preview gallery use deterministic embedded data. The third
        // box contains the complete service-backed analysis workflow but remains inert in CI.
        "audio_ai_analysis",
        // The guide and synthetic calibration view execute without weights. The third box owns the
        // real emotion2vec/WavLM submission path and remains an explicit user action.
        "audio_ai_speech_embeddings",
        // The guide and synthetic phase portraits are pure compute; the live third box owns the
        // optional librosa service call and is deliberately left for an interactive run.
        "audio_ai_spectral_embeddings",
        // The guide and durable monitor run headless. The second box contains the complete
        // Librosa-backed indexing and retrieval workflow, and remains inert until run by a user.
        "audio_ai_corpus_microscope",
        // Six independent pure-CPU WFC boxes; nothing opens a Window or a Renderer.
        "wfc_worked_example",
        // Five independent pure-CPU plane-geometry boxes; showPlaneGrid is drawToSVG -> display,
        // so no AWT, no GPU and nothing to launch.
        "spatial_geometry_examples",
        // Ten independent pure-CPU spatial-ALife boxes; nothing opens a Window or a Renderer.
        "alife_evolution",
        "alife_worked_example",
        // Five independent pure-CPU statistics boxes; showPlaneGrid is drawToSVG -> display,
        // showSignals is a plotly HTML fragment -> display, histogramToSvg is just a string.
        "statistics_examples",
        // Two pure-CPU FLine boxes; showPlaneGrid is drawToSVG -> display, so no AWT and no GPU.
        "arclength_spacing_demo",
        // Two more of the same shape: the interior-Close demo reverses, subdivides and dashes
        // closed contours and reports the numbers, all through showPlaneGrid.
        "close_node_demo",
        // Six independent pure-CPU dynamic-graph boxes. Four print and draw through showPlaneGrid /
        // showSignals; the other two build a Frames through animateEvolution, so executing them is a
        // compile check and the animation only runs when the box is launched. No Window, no Renderer.
        "dynamic_graph_systems_examples",
        // Five independent pure-CPU automatic-differentiation boxes; plot, showSignals and
        // showPlaneGrid all end in display(), so no AWT, no GPU and nothing to launch.
        "autodiff_worked_example",
        // Four independent pure-CPU keyframer boxes. A KeyframeScope needs no GPU: a uniform
        // `Structure` is constructible bare and its bindings are plain maps, the camera box does
        // CameraPose arithmetic without ever touching a live `Camera`, and persistence writes to a
        // temp directory. showPlaneGrid is drawToSVG -> display, so no AWT and nothing to launch.
        "keyframer_worked_example",
        // Three pure-compute MorphoHDL circuit-search boxes. Every candidate is compiled by
        // morpho's own JS compiler on GraalJS and simulated by MorphoSim; the figures go through
        // showPlaneGrid (drawToSVG -> display), so no Window and no Renderer. The searches are
        // budgeted in compiler invocations, and they are the most expensive thing in this harness by a
        // factor of five: 66.5s of the sweep's ~300s, measured 2026-08-12 (44.6s + 21.9s of
        // `evaluate=`). Not "a few seconds each", as this comment said while that was still true.
        "morpho_circuit_search",
        // Four independent pure-CPU heatmap boxes; showHeatmap / showImage encode a PNG and end in
        // display(), so no AWT, no GPU and nothing to launch.
        "viz_showheatmap",
        // One pure-CPU polytope box. It derives polygonal FLines and publishes their payload to the
        // on-canvas Three.js viewer; no native Window or Renderer is opened by threeLines().
        "polytope_conway_demo",
        // Four more pure-CPU polytope boxes, one question each — the demo set POLYTOPE_ALGEBRA.md
        // asks for, and the loadable companion to public/docs/polytope_examples.md. Two end in
        // threeLines() as above; the other two rasterise with TriMesh.renderPng, which is the
        // AWT-free PngEncoder path into display(). No Window, no Renderer, no GPU either way.
        "polytope_conway_gallery",
        // A second pure-CPU polytope box on the same path: evaluate one Conway expression,
        // canonicalize it, and draw the realized and canonical forms side by side.
        "polytope_canonical_gallery",
        // Three pure-CPU polytope boxes for faces with holes and for the chained Conway vocabulary.
        // Box 1 ends in threeLines() as above; boxes 2 and 3 rasterise with TriMesh.renderPng. Box 1
        // also *catches* four exceptions on purpose — the operations a holed face refuses — which are
        // caught inside the box body and so are not error-kind output lines.
        "polytope_holes_and_chains",
        // Eight pure-CPU typography boxes. The letters are filled from their own contours by the
        // family's own scanline filler and shown through showImgGrid / showPlaneGrid / showMeshGrid,
        // all of which are PngEncoder or Skia-SVG into `display` — no AWT rasteriser, no GPU. Box 5
        // ends in `animate { }`, which registers an animation rather than blocking, as with the two
        // parameter sweeps in spatial_geometry_examples.
        "typography_worked_example",
        // Two pure-CPU Ceres boxes: a canonical realization whose per-face planes are free variables in
        // the residual expression VM, and a sphere fit whose unknowns are not points at all. Both are
        // FFM calls into the native wrapper plus threeLines(), so no Window and no Renderer. Both
        // detect a wrapper older than ABI v3 — which is exactly what the *committed* dylib is, so that
        // is the branch CI takes — and draw the unsolved seed with an explanation rather than failing.
        "ceres_free_variables",
        // Two pure-CPU Manifold boxes: a machined part built out of Booleans, and a Kotlin lambda
        // marched into a guaranteed-closed solid and then sliced. Both are FFM calls into
        // `libmanifoldc` plus showMesh's CPU rasteriser, so no Window and no Renderer. Both detect an
        // absent Manifold — which on a machine without `brew install manifold` is the branch CI takes
        // — and the first one draws the same shape through Field's own marchingCubes instead, with the
        // difference named, rather than failing.
        "manifold_solids",
        // Two pure-CPU boxes that lower a RectSL scene to a GLSL distance field and print it.
        // `emitSdf` is a string builder over the compiled SceneProgram — no shaderc, no Window,
        // no Renderer — so both run to completion, which is also the cheapest check that the
        // emitter's own starting point still compiles against it.
        "rectsl_glsl_sdf",
        "solidsl_notation",
        // Two pure-CPU boxes on the third track backend: one prints a track's symbolic IR with its
        // free parameters still unsolved, the other fits those parameters against waypoints and
        // rasterises target/seed/fitted with TriMesh.renderPng into display(). `toFn` and the Adam
        // fitter are plain arithmetic over Fn — no shader, no Window, no Renderer.
        "rectsl_inverse_animation",
        // The same backend one refusal later: `->face-travel` lowers now, so a heading can be drawn
        // beside its own lowering and beside the quadrant-less form it is *not*, and then solved
        // through. CPU raster into display() and an Adam descent over an Fn — no Window, no
        // Renderer, and no atan2 anywhere except the one in the graph under test.
        "rectsl_heading_fit",
        // The three control-surface affordances: slider laws, a momentary button, a meter fed by a
        // `source` lambda. Pure arithmetic and one daemon sampler thread — no Window, no Renderer, no
        // audio device — so the whole transcript runs to completion here.
        "control_surface_laws",
        // Asks `afield.CommonMemberPrior` and `KotlinSourceIndex` what ranking prior and completion
        // category the popup currently has for a receiver type, and tabulates the answer into
        // display(). Two in-process lookups and some string building — no completion server, no
        // window — so running it here is also the cheapest check that the two entry points
        // public/docs/completion_ranking.md names still exist with the signatures it claims.
        "popup_prior_inspect",
    )

    /** Graphics demos: box bodies are compiled (real pipeline, eval skipped) but not launched. */
    internal val COMPILE_ONLY = setOf(
        // Persistent optional Kratos worker; exercised separately by the live playground check.
        "kratos_thermal_playground",
        "kratos_flow_playground",
        // Also wants TetGen: it meshes the bracket here before the worker sees it.
        "kratos_structural_playground",
        // The same worker, times fifty: a grid sweep is minutes of solver, so CI compiles it and
        // the `kratos.study.parameter-sweep` catalog entry runs the same code path at 3x3.
        "kratos_parameter_study",
        // Every box is compiled through Field's real K2/preamble path, but none is evaluated: four
        // of them intentionally reach an external analysis worker or real-time Sonic Pi recorder.
        "audio_render_analysis",
        "graphics_renderer_launch",
        // These open a native Vulkan renderer; real pixels and reloads are checked separately.
        "path_tracing_materials",
        "path_tracing_camera",
        "path_tracing_glass",
        "path_tracing_gltf",
        "path_tracing_hidden_environment",
        "path_tracing_material_shader_check",
        // Real Vulkan/OpenXR rendering; binocular depth additionally requires HTC eye hardware.
        "openxr_eye_intersection",
        "afield_four_traditional_triangle",
        // Streams a splat sequence into a native window, and wants a directory of .compressed.ply
        // frames that is deliberately not committed -- so compiled here and no further.
        "splat_sequence_streaming",
        // Builds its own Window + Renderer and a six-pass chain across three resolutions, so it
        // wants a GLFW window and a Vulkan device the lightweight build does not own. The plan
        // arithmetic it prints is covered without a device by FabricatePassChainTest, and the
        // pixels by the four.pass-chain.smoke jvm-main.
        "four_pass_chain",
        "recstl_playground",
        "gpu_state",
        "threebox",
        "rectsl_dataviz",
        "rectsl_plot_axes",
        // Opens its own Window + Renderer to run a RectSL scene on a musical clock.
        "rectsl_musicality",
        // Lowers a RectSL scene to a GLSL distance field and draws it through a generated
        // fullscreen-triangle pipeline. Opens its own Window + Renderer, so compile-only here;
        // its pixels are checked by the `rectsl-sdf` docs capture instead.
        "rectsl_sdf_live",
        "spin_glass",
        "imgui_vr_panel",
        // A hidden Electron window renders Field itself into Chromium shared textures; the live
        // macOS replay crosses IOSurface Mach rights and samples them in Vulkan. CI compiles the
        // self-contained starting scene without starting Electron or opening a native window.
        "field_ui_browser_texture",
        // Desktop puppet-browser scene: an aspect-matched YouTube texture receives the native
        // Window's mouse, wheel, physical keyboard and text-input stream directly through Chromium.
        "youtube_mouse_keyboard_browser",
        // A live YouTube tab sampled across 224 independently animated Vulkan triangles. The live
        // replay opens Electron, reaches the network and starts media; CI compiles the whole graph.
        "youtube_kaleidoscope",
        // Product-facing puppet-browser scene: a YouTube watch page on an aspect-correct world quad.
        // It opens Electron and a native Vulkan stereo window, so CI compiles without launching it.
        "youtube_vr_browser",
        // Exercises IOSurface/Mach-port import, a Vulkan copy, and a graphics-fence callback on
        // macOS. CI still compiles both boxes everywhere; the tracked backdoor replay runs it live.
        "external_browser_surface_macos_smoke",
        // The Linux twin: a DRM-modifier RTT, a dma-buf export, and a foreign-queue import. Same
        // deal — compiled everywhere, run live by the tracked backdoor replay.
        "external_browser_surface_linux_smoke",
        // Needs a camera, which CI does not have. Compiling without evaluating is also what keeps this
        // hermetic: the box's `clipEncode(…)` calls and its Depth Anything session are behind lazy
        // initialisation, so nothing here fetches the ~600MB of encoders that running it would.
        "onnx_webcam_live",
    )

    /**
     * KotlinJS "code in the head" demos: the box body is Kotlin compiled to JavaScript for a remote
     * head, NOT JVM Kotlin — so it is compiled through the REAL [KotlinToJsCompiler] via the head
     * profile the `// {{ marker }}` selects (the identical path a live box takes), and asserted to
     * carry no error diagnostics. Faithful replay for markers like `// {{ tone }}` / `// {{ remoteAR }}`.
     */
    internal val KOTLINJS = setOf(
        "tone_synth_sequence",
        "remotear_start",
        "remotear_scene_motion",
        "visionos_start",
        "vision_geometry_shapes",
        "vision_geometry_heightfield",
        "vision_geometry_animated",
    )

    /** User-facing starters must carry a real KotlinJS root → child topology, not only compile offline. */
    private val KOTLINJS_STARTERS = setOf("remotear_start", "visionos_start")

    /** Ops that build the box graph without compiling/running any box Kotlin. */
    private val GRAPH_VERBS = setOf(
        "create", "new-box", "box", "connect", "disconnect", "edit", "set", "set-string", "move", "frame", "set-rect", "wait",
    )

    @JvmStatic
    fun main(args: Array<String>) {
        // First line, before anything can touch an AWT class, and for the same reason every jvm-main
        // gallery does it: this harness runs under `-XstartOnFirstThread` (added for GLFW by
        // `requiredAgentJvmArgs` in agent_backdoor.kt:856) and opens no window, so nothing pumps the
        // process's first-thread run loop. Non-headless AWT then hangs the moment a box asks for a
        // glyph outline — `sun.font.CFontManager.loadFonts` dispatches there and waits. Headless AWT
        // does not, and this harness has no use for a screen. See `Typefaces.warm`.
        System.setProperty("java.awt.headless", "true")
        val transcriptsDir = locateTranscriptsDir()
        println("DemoTranscriptExecSmokeTest: transcripts dir = ${transcriptsDir.absolutePath}")

        startRunloopDaemon()

        // Iterating on one demo otherwise means waiting three minutes for the other twenty-odd. The
        // filter is for development only — CI runs the whole set, and an unfiltered run is the
        // default — but without it a single failing demo is painful to debug.
        val only = (args.firstOrNull() ?: System.getenv("DEMO_TRANSCRIPT_ONLY"))
            ?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)?.toSet()
        only?.let { println("DemoTranscriptExecSmokeTest: FILTERED to ${it.joinToString()} — not a full run") }

        val yamls = (transcriptsDir.listFiles { f -> f.isFile && f.extension == "yaml" } ?: emptyArray())
            .sortedBy { it.name }
            .filter { only == null || it.nameWithoutExtension in only }
        require(yamls.isNotEmpty()) { "No transcript yaml files found in ${transcriptsDir.absolutePath}" }

        val failures = mutableListOf<String>()
        for (yaml in yamls) {
            val name = yaml.nameWithoutExtension
            try {
                when (name) {
                    in EXECUTE -> runExecuteDemo(yaml, name, failures)
                    in COMPILE_ONLY -> runCompileOnlyDemo(yaml, name, failures)
                    in KOTLINJS -> runKotlinJsDemo(yaml, name, failures)
                    else -> {
                        val msg = "$name: UNCLASSIFIED — add to EXECUTE or COMPILE_ONLY in DemoTranscriptExecSmokeTest"
                        failures.add(msg)
                        println("[unclassified] $msg")
                    }
                }
            } catch (t: Throwable) {
                val msg = "$name: harness threw ${t.javaClass.simpleName}: ${t.message}"
                failures.add(msg)
                System.err.println("[error] $msg")
                t.printStackTrace(System.err)
            } finally {
                // Each transcript owns a disposable document session. Dropping only the thread-local
                // reference leaves every box's K2 REPL engine alive, so the full corpus accumulates
                // dozens of FIR sessions and eventually loses otherwise-standard imports in later
                // boxes. Use the same lifecycle boundary as closing a real Field document.
                thisSessionInfo.get()?.cleanup()
                thisSessionInfo.remove()
            }
        }

        if (failures.isNotEmpty()) {
            System.err.println("DemoTranscriptExecSmokeTest FAILED with ${failures.size} problem(s):")
            failures.forEach { System.err.println("  - $it") }
            exitProcess(1)
        }
        println("DemoTranscriptExecSmokeTest ok — ${yamls.size} demos verified " +
            "(${EXECUTE.size} executed, ${COMPILE_ONLY.size} compiled, ${KOTLINJS.size} kotlinjs)")
    }

    // ---- KOTLINJS: build the graph, then compile each kotlinjs box body Kotlin -> JS for real ----

    private fun runKotlinJsDemo(yaml: File, name: String, failures: MutableList<String>) {
        val document = freshSession(name)
        val ops = loadOps(yaml)
        val graphOps = ops.filter { (it.keys.firstOrNull()?.lowercase() ?: "") in GRAPH_VERBS }

        val macrosDir = File(document, "macros").also { it.mkdirs() }
        val filtered = linkedMapOf<String, Any?>("name" to name, "operations" to graphOps)
        File(macrosDir, "$name.yaml").writeText(Yaml().dump(filtered))
        val build = OperationTranscript.runMacro(document.absolutePath, name)
        if (!build.success) println("[kotlinjs] $name graph build note: ${build.message}")

        val boxIds = graphOps.mapNotNull { op ->
            if ((op.keys.firstOrNull()?.lowercase() ?: "") in setOf("create", "new-box", "box")) {
                val p = op.values.firstOrNull() as? Map<*, *>
                (p?.get("box") ?: p?.get("uid") ?: p?.get("id") ?: p?.get("target"))?.toString()
            } else null
        }
        if (name in KOTLINJS_STARTERS) {
            kotlinJsTopologyNeedsUpdating(thisSessionInfo.get().boxes.values)
        }

        println("[kotlinjs] $name (${boxIds.size} box(es)) …")
        var demoOk = true
        var compiled = 0
        for (id in boxIds) {
            val target = runCatching { box(id, createIfMissing = false) }.getOrNull() ?: continue
            if (target.getPropertyRaw("_tombstone").isTrue()) continue
            // Language-root boxes intentionally have no code property; do not force the non-null
            // delegated accessor merely to discover that this graph node is structural.
            val code = target.getPropertyRaw("code")?.toString().orEmpty()
            if (code.isBlank()) continue
            // Only KotlinJS-head boxes: a `// {{ marker }}` first line, or mainLanguage == kotlinjs.
            val mainLang = target.getPropertyRaw("mainLanguage", "")?.toString()?.trim().orEmpty()
            val selection = runCatching { afield.remotejs.KotlinJsHeadProfiles.resolve(code) }.getOrNull()
            if (selection == null || (!selection.explicit && !mainLang.equals("kotlinjs", ignoreCase = true))) {
                println("[kotlinjs]   $id: not a kotlinjs-head box — skipped")
                continue
            }
            if (name in KOTLINJS_STARTERS && target.ke !is KotlinJsExecution) {
                demoOk = false
                val msg = "$name/$id: starter code box is not beneath a KotlinJS language root"
                failures.add(msg)
                println("[kotlinjs]   $id: FAIL $msg")
            }
            val prepared = selection.profile.prepareSource(selection.source)
            val result = afield.remotejs.KotlinToJsCompiler.compile(
                prepared, moduleName = "demo_${name}_${id.take(8)}", wrapImplicitMain = false,
            )
            val errors = result.diagnostics.filter { it.severity == "error" }
            when {
                errors.isNotEmpty() -> {
                    demoOk = false
                    errors.forEach {
                        val msg = "$name/$id: ${it.message} (line ${it.line})"
                        failures.add(msg)
                        println("[kotlinjs]   $id: FAIL $msg")
                    }
                }
                !result.success || result.js == null -> {
                    demoOk = false
                    val msg = "$name/$id: Kotlin->JS compile failed: ${result.rawLog.takeLast(400)}"
                    failures.add(msg)
                    println("[kotlinjs]   $id: FAIL $msg")
                }
                else -> {
                    compiled += 1
                    println("[kotlinjs]   $id: PASS (${selection.profile.id}, ${prepared.length} chars Kotlin -> ${result.js.length} chars JS)")
                }
            }
        }
        if (demoOk) {
            if (compiled == 0) {
                val msg = "$name: no kotlinjs-head box compiled — is the marker / mainLanguage set?"
                failures.add(msg)
                println("[kotlinjs] FAIL $msg")
            } else {
                println("[kotlinjs] PASS $name")
            }
        }
    }

    // ---- EXECUTE: run the whole transcript through the real runner and assert no error output ----

    private fun runExecuteDemo(yaml: File, name: String, failures: MutableList<String>) {
        val document = freshSession(name)
        val macrosDir = File(document, "macros").also { it.mkdirs() }
        File(macrosDir, "$name.yaml").writeText(yaml.readText())

        println("[execute] $name …")
        val run = OperationTranscript.runMacro(document.absolutePath, name)
        val errorLines = run.output.filter { it.kind == "error" || it.kind == "err" }

        when {
            !run.success -> {
                val msg = "$name: transcript run failed: ${run.errors.joinToString("; ").ifBlank { run.message }}"
                failures.add(msg)
                println("[execute] FAIL $msg")
            }
            errorLines.isNotEmpty() -> {
                val detail = errorLines.joinToString(" | ") { "${it.uid}:${it.line} ${stripHtml(it.html)}" }
                val msg = "$name: box execution emitted ${errorLines.size} error diagnostic(s): $detail"
                failures.add(msg)
                println("[execute] FAIL $msg")
            }
            else -> println("[execute] PASS $name (applied=${run.applied}, output lines=${run.output.size})")
        }

        // A demo can PASS (no error diagnostics) while printing nonsense. Set
        // AFIELD_DEMO_TRANSCRIPT_VERBOSE=1 — or =<name> for one demo — to echo what the boxes
        // actually printed, so the numbers a worked example claims can be read back and checked.
        val verbose = System.getenv("AFIELD_DEMO_TRANSCRIPT_VERBOSE")
        if (!verbose.isNullOrBlank() && (verbose == "1" || verbose == "all" || verbose == name)) {
            run.output.forEach { println("[execute:$name] ${it.kind}: ${stripHtml(it.html).trim()}") }
        }
    }

    // ---- COMPILE-ONLY: build the graph, then compile each box's Kotlin with eval skipped ----

    private fun runCompileOnlyDemo(yaml: File, name: String, failures: MutableList<String>) {
        val document = freshSession(name)
        val ops = loadOps(yaml)
        val graphOps = ops.filter { (it.keys.firstOrNull()?.lowercase() ?: "") in GRAPH_VERBS }

        // Build the box graph with the real apply path — create/connect/edit/move only, no launch.
        val macrosDir = File(document, "macros").also { it.mkdirs() }
        val filtered = linkedMapOf<String, Any?>("name" to name, "operations" to graphOps)
        File(macrosDir, "$name.yaml").writeText(Yaml().dump(filtered))
        val build = OperationTranscript.runMacro(document.absolutePath, name)
        if (!build.success) {
            // A missing cross-transcript parent (skipped connect) is fine; only hard errors matter.
            println("[compile] $name graph build note: ${build.message}")
        }

        // Compile each created box, parent-first (creation order), so parent preamble (prop<>
        // delegate declarations) is populated before a child gathers it upward.
        val boxIds = graphOps.mapNotNull { op ->
            if ((op.keys.firstOrNull()?.lowercase() ?: "") in setOf("create", "new-box", "box")) {
                val p = op.values.firstOrNull() as? Map<*, *>
                (p?.get("box") ?: p?.get("uid") ?: p?.get("id") ?: p?.get("target"))?.toString()
            } else null
        }

        println("[compile] $name (${boxIds.size} box(es)) …")
        var demoOk = true
        for (id in boxIds) {
            val target = runCatching { box(id, createIfMissing = false) }.getOrNull()
            if (target == null) {
                println("[compile]   $id: not created (skipped)")
                continue
            }
            if (target.getPropertyRaw("_tombstone").isTrue()) continue
            val code = target.code
            if (code.isBlank()) {
                println("[compile]   $id: no Kotlin `code` (shader-only box) — skipped")
                continue
            }
            val diags = compileBoxOnly(target)
            if (diags.isEmpty()) {
                println("[compile]   $id: PASS")
            } else {
                demoOk = false
                diags.forEach {
                    val msg = "$name/$id: $it"
                    failures.add(msg)
                    println("[compile]   $id: FAIL $it")
                }
            }
        }
        if (demoOk) println("[compile] PASS $name")
    }

    /**
     * Compile [box]'s Kotlin `code` through the real pipeline with evaluation skipped, returning any
     * ERROR-severity diagnostics. Reuses the real macro expander, preamble evaluation and K2
     * compiler; only the terminal `eval` of the main body is omitted (that is where a graphics box
     * would open a Window). `//%-` fragment breaks are Kotlin comments, so compiling the whole body
     * as one snippet keeps cross-fragment declarations resolvable without evaluating anything.
     */
    private fun compileBoxOnly(box: Box): List<String> = runBlocking {
        val errors = mutableListOf<String>()
        Box.withBoxContext(box) {
            // Expand macros first: this both rewrites moduleHere/prop/println/… AND writes the
            // prop<T>() delegate declarations into the box's preamble (applyInsertToPreamble).
            // Then substitute the `__line__` sentinel the print macros emit — standardExecutionPractice
            // (the real path) does this between applyMacros and eval; skipping it leaves an unresolved
            // `__line__` reference. The exact number is irrelevant to compilation.
            val expanded = box.ki.applyMacros(box, box.code)
                .split("\n")
                .mapIndexed { i, line -> line.replace("__line__", "$i") }
                .joinToString("\n")

            // Evaluate setup blocks (preamble/introduction/attic). These are declaration-only and
            // never open a Window; evaluating them puts the Box.<prop> extension symbols (gathered
            // upward from parents too) into this engine's REPL context so the body resolves them.
            val swallow: (List<ScriptDiagnostic>) -> Unit = { }
            val swallowEx: (Throwable) -> Unit = { }
            checkPreambleAndExecute(box, swallow, swallow, swallowEx)
            checkIntroductionAndExecute(box, swallow, swallow, swallowEx)
            checkAtticAndExecute(box, swallow, swallow, swallowEx)

            box.ki.compileSnippetAndWait(expanded, implicits = listOf(box)) { diagnostics ->
                diagnostics
                    .filter { it.severity.ordinal >= ScriptDiagnostic.Severity.ERROR.ordinal }
                    .forEach { d ->
                        val loc = d.location?.start?.let { " (${it.line}:${it.col})" } ?: ""
                        errors.add("${d.message}$loc".trim())
                    }
            }
        }
        errors
    }

    // ---- shared setup ----

    private fun freshSession(name: String): File {
        thisSessionInfo.remove()
        val workspace = Files.createTempDirectory("afield-demo-exec-$name").toFile()
        workspace.deleteOnExit()
        val document = File(workspace, "doc").also { it.mkdirs() }
        File(document, SavingContext.METADATA_EDN).writeText("{:version 2 :boxes []}\n")
        val root = Box("root")
        val session = SessionInformation(
            filename = document.absolutePath,
            documentName = document.name,
            boxes = ConcurrentHashMap(),
            root = root,
            ephemeral = true,
        )
        root.inside = session
        session.boxes[root.id] = root
        thisSessionInfo.set(session)
        return document
    }

    @Suppress("UNCHECKED_CAST")
    private fun loadOps(yaml: File): List<Map<String, Any?>> {
        val loaded = Yaml().load<Any?>(yaml.readText())
        val raw = when (loaded) {
            is List<*> -> loaded
            is Map<*, *> -> (loaded["operations"] as? List<*>) ?: (loaded["ops"] as? List<*>) ?: emptyList<Any?>()
            else -> emptyList<Any?>()
        }
        return raw.mapNotNull { it as? Map<String, Any?> }
    }

    private fun startRunloopDaemon() {
        val t = Thread({ afield.Runloop.runLoop.start() }, "demo-transcript-harness-runloop")
        t.isDaemon = true
        t.start()
        val deadline = System.currentTimeMillis() + 30_000
        while (afield.Runloop.runLoop.frame < 1L) {
            check(System.currentTimeMillis() < deadline) { "runloop did not start within 30s" }
            Thread.sleep(20)
        }
        println("DemoTranscriptExecSmokeTest: runloop up (frame=${afield.Runloop.runLoop.frame})")
    }

    private fun locateTranscriptsDir(): File {
        val rel = "dev_docs/agents/transcripts"
        val candidates = buildList {
            System.getProperty("afield.repo")?.let { add(File(it, rel)) }
            var dir: File? = File(System.getProperty("user.dir")).absoluteFile
            while (dir != null) {
                add(File(dir, rel))
                dir = dir.parentFile
            }
        }
        return candidates.firstOrNull { it.isDirectory }
            ?: error("Could not locate $rel from user.dir=${System.getProperty("user.dir")}")
    }

    private fun stripHtml(html: String): String =
        html.replace(Regex("<[^>]+>"), " ").replace("&lt;", "<").replace("&gt;", ">").trim()
}
