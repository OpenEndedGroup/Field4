package afield.four.fabricated

import afield.four.Renderer
import afield.four.TexturePreviewCache
import afield.four.modules.Mesh
import afield.four.modules.Module
import afield.four.modules.ModulePointer
import afield.four.modules.parameters
import afield.four.modules.pointer
import afield.four.targets.RenderToTexture
import afield.logger.lprintln
import afield.KotlinInterface2
import afield.KotlinScriptEngine
import afield.rectsl.RECT_ANIMATION_VERTEX_SHADER
import afield.rectsl.RectLangException
import afield.rectsl.RectSLGlsl
import afield.rectsl.RectSLSdfLook
import afield.rectsl.RectSLSdfOptions
import afield.rectsl.RectSLSdfShader
import afield.rectsl.SceneProgram
import afield.rectsl.compileRectSL
import afield.rectsl.prewarmRectSLEscapes
import afield.rectsl.rectMaterialShaderCode
import afield.rectsl.worldToClip
import afield.rectsl.writeFrameToMesh
import afield.stdlib.Functions
import afield.stdlib.fitBest
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield
import org.lwjgl.stb.STBImage
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM
import org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The RectSL analogue of [StackSLGalleryHarness]. RectSL programs are whole
 * *scenes* (wall, duration, fps, rects, tracks, groups, StackSL materials) rather
 * than a single fullscreen fragment, so the render path is the one already used by
 * `afield.rectsl.RectSLRenderRunner`: compile the program, lower each sampled frame
 * to a triangle mesh, and draw it through the [RECT_ANIMATION_VERTEX_SHADER] plus a
 * StackSL-compiled material fragment into an offscreen [RenderToTexture].
 *
 * Both entry points keep **one** renderer / graphics context alive and walk the
 * program list — the "one Field document, many afield.four renders" pattern. A
 * fresh target is built per example because each program's wall has its own aspect
 * ratio (unlike the fixed 512² StackSL fullscreen target).
 *
 * [renderGallery] reads back a single representative still per program.
 * [renderAnimations] sweeps the program's own timeline frame by frame and encodes
 * each `animate: true` program to an mp4 (the docs runner can screenshot the native
 * GPU window but never record it, so the harness owns the readback + ffmpeg encode).
 * The programs are editable Field content in [WORKSPACE_DIR], not literals here.
 */
object RectSLGalleryHarness {

    private const val OUTPUT_LONG = 512      // longest output side in px
    private const val WAIT_FRAMES = 12       // build pipeline + first render
    private const val SETTLE_FRAMES = 2      // renderer frames per new time
    private const val ANIM_FPS = 30
    private const val ANIM_MAX_FRAMES = 150  // cap ~5s per clip regardless of program
    private const val MODULE_NAME = "rectsl-gallery-module"
    private const val MESH_NAME = "rectsl-gallery-mesh"
    private const val PRE_TARGET_NAME = "rectsl-gallery-rtt"
    private val DATA_IMAGE_RE = Regex("base64,([^']+)")

    /** Repo-relative workspace whose `*_rectsl_.rectsl` files are the examples. */
    const val WORKSPACE_DIR = "public/docs/assets/rectsl_gallery"

    /** The gallery entry embedded as the section hero image. */
    private const val HERO_NAME = "orbit-petals"

    /**
     * One gallery entry loaded from the workspace: [name] is the box slug (and the
     * PNG/mp4 stem `rectsl-<name>`), [source] its RectSL program. [stillTime] is the
     * timeline moment sampled for the still. [animate] marks programs that also get
     * an mp4.
     */
    data class Example(
        val name: String,
        val source: String,
        val stillTime: Double? = null,
        val animate: Boolean = false
    )

    private fun defaultWorkspaceDir(): File = File(System.getProperty("user.dir"), WORKSPACE_DIR)

    /**
     * Load every example from the workspace: one box per `<name>_rectsl_.rectsl`
     * file, ordered by the `docOrder` in the sibling `<name>_metadata_.yaml` (which
     * also carries optional `animate:` / `stillTime:`). The programs live in the
     * workspace, not in this file.
     */
    fun loadExamples(workspaceDir: File = defaultWorkspaceDir()): List<Example> {
        val files = workspaceDir.listFiles { f -> f.name.endsWith("_rectsl_.rectsl") }
            ?: error("RectSL gallery workspace not found: ${workspaceDir.absolutePath}")
        require(files.isNotEmpty()) { "RectSL gallery workspace has no *_rectsl_.rectsl files: ${workspaceDir.absolutePath}" }
        return files
            .map { f ->
                val name = f.name.removeSuffix("_rectsl_.rectsl")
                val meta = File(workspaceDir, "${name}_metadata_.yaml").takeIf { it.exists() }?.readLines() ?: emptyList()
                fun value(key: String): String? =
                    meta.firstNotNullOfOrNull { Regex("""^\s*$key:\s*(.+?)\s*$""").find(it)?.groupValues?.get(1) }
                val order = value("docOrder")?.toIntOrNull() ?: Int.MAX_VALUE
                Example(
                    name = name,
                    source = f.readText().trim(),
                    stillTime = value("stillTime")?.toDoubleOrNull(),
                    animate = value("animate")?.toBooleanStrictOrNull() ?: false
                ) to order
            }
            .sortedWith(compareBy({ it.second }, { it.first.name }))
            .map { it.first }
    }

    /**
     * Render every workspace example into `<outputDir>/rectsl-<name>.png` (a single
     * representative frame), reusing a single renderer. Returns the hero PNG.
     */
    suspend fun renderGallery(outputDir: File, renderer: Renderer): File {
        outputDir.mkdirs()
        val examples = loadExamples()
        var heroFile: File? = null
        examples.forEach { example ->
            val file = File(outputDir, "rectsl-${example.name}.png")
            runCatching {
                val program = compile(example)
                val stillTime = example.stillTime ?: (program.duration * 0.5)
                renderStill(renderer, program, stillTime, file, example.name)
            }
                .onSuccess {
                    lprintln("info,doc", "rectsl gallery wrote ${file.name} (${file.length()} bytes)")
                    if (example.name == HERO_NAME) heroFile = file
                }
                .onFailure { t -> lprintln("error,doc", "rectsl gallery example '${example.name}' failed: ${t.message}") }
        }
        return heroFile
            ?: File(outputDir, "rectsl-${examples.first().name}.png").takeIf { it.exists() }
            ?: error("rectsl gallery produced no images")
    }

    /**
     * Render an mp4 for every `animate: true` workspace example into
     * `<outputDir>/rectsl-<name>.mp4`, reusing one renderer. The scene is sampled
     * over its own timeline; each frame is read back and handed to ffmpeg.
     */
    suspend fun renderAnimations(outputDir: File, renderer: Renderer): List<File> {
        outputDir.mkdirs()
        val examples = loadExamples().filter { it.animate }
        require(examples.isNotEmpty()) {
            "no animated RectSL examples — set `animate: true` in a box's metadata.yaml under $WORKSPACE_DIR"
        }
        val ffmpeg = resolveFfmpeg()
        val written = mutableListOf<File>()
        examples.forEach { example ->
            runCatching { renderOneAnimation(renderer, compile(example), example.name, outputDir, ffmpeg) }
                .onSuccess { written += it; lprintln("info,doc", "rectsl anim wrote ${it.name} (${it.length()} bytes)") }
                .onFailure { t -> lprintln("error,doc", "rectsl anim '${example.name}' failed: ${t.message}") }
        }
        return written
    }

    /**
     * A dedicated illustration of the hardest bridge idea (#5): a RectSL
     * distribution is a `Functions` graph, so the host can *fit* it to a target and
     * RectSL just samples the winner. We fit a two-lobe target with [fitBest], bind
     * the seed/target/fitted curves by name into a real RectSL program, and render
     * three arrays — target (reference), seed (before), fitted (after) — so the gold
     * "after" row visibly lands on the target silhouette that the muddy "before" row
     * misses. The layout is the ordinary `array` + `sample` path; only the curve was
     * searched for by the host.
     */
    suspend fun renderFitToTarget(outputDir: File, renderer: Renderer): File {
        outputDir.mkdirs()
        val (target, seed, fitted) = with(Functions) {
            // A two-lobe silhouette that is awkward to hand-key; the seed has the
            // same structure but deliberately wrong lobe positions/widths/heights.
            val target = gaussian(0.30, 0.11) + gaussian(0.72, 0.13) * 0.80
            val seed = gaussian(0.45, 0.20) + gaussian(0.60, 0.20) * 0.50
            val run = fitBest(target, seed, restarts = 12)
            lprintln("info,doc", "rectsl fit-to-target RMSE ${"%.4f".format(run.initialError)} -> ${"%.4f".format(run.finalError)}")
            Triple(target, seed, run.fitted)
        }
        val program = compileForRender(
            "fit-to-target",
            FIT_SCENE_SOURCE,
            mapOf("target" to target, "seed" to seed, "fit" to fitted)
        )
        val file = File(outputDir, "rectsl-fit-to-target.png")
        renderStill(renderer, program, 0.0, file, "fit-to-target")
        lprintln("info,doc", "rectsl fit-to-target wrote ${file.name} (${file.length()} bytes)")
        return file
    }

    /** The scene rendered by [renderFitToTarget]; `target`/`seed`/`fit` are host-bound curves. */
    val FIT_SCENE_SOURCE: String = """
        #rectsl
        ->wall 22 12 abstract
        ->duration 1
        ->fps 1

        "trow" rect  0 -4 ->xy  0.5 1 ->wh  0x2e5a86 solid ->fill
          40 '( ic 0.55 * 0 ->offset  target f sample 4 * 0.12 +  ->h ) array ;

        "srow" rect  0 0 ->xy  0.5 1 ->wh  0x8a5a2b solid ->fill
          40 '( ic 0.55 * 0 ->offset  seed f sample 4 * 0.12 +  ->h ) array ;

        "frow" rect  0 4 ->xy  0.5 1 ->wh  0xffd23c solid ->fill
          40 '( ic 0.55 * 0 ->offset  fit f sample {{ 4.0 }} * 0.12 +  ->h ) array ;
    """.trimIndent()

    // ── the signed distance field, on the GPU ───────────────────────────────────

    /**
     * The scene the SDF looks are demonstrated on.
     *
     * Chosen so that every preset has something to show rather than a still life:
     * six copies of one animated blade, close enough together that a smooth union
     * fuses them, sweeping fast enough through the middle of the wall that a
     * 0.14s shutter is a visible streak, and rotating so the outline and the
     * per-pixel normals both have a curved-looking edge to describe. It is an
     * ordinary `array` over an ordinary template — nothing here is SDF-specific,
     * which is the point.
     */
    val SDF_SCENE_SOURCE: String = """
        #rectsl
        ->wall 9 5 abstract
        ->duration 4
        ->fps 30

        "blade" rect  0.85 ->square  0x2f9fe8 solid ->fill
          '( 0=0  4=6.28318 ) ->rot
          '( 0=-2.4  2=2.4  4=-2.4 ) tcb ->x
          '( 0=-0.7  1=0.7  2=-0.7  3=0.7  4=-0.7 ) tcb ->y
          6 '( ic 0.8 * 0 ->offset ) array
        ;
    """.trimIndent()

    /**
     * The field that gets subtracted by the `carve` look.
     *
     * It rides the blades' own `->y` track so the hole is actually *in* the band
     * rather than passing under it — the first version travelled along y = 0 and
     * only clipped a corner, which made a boolean op look like a rendering
     * artefact.
     */
    val SDF_CARVE_SOURCE: String = """
        #rectsl
        ->wall 9 5 abstract
        ->duration 4

        "hole" rect  1.3 ->square
          '( 0=2.4  2=-2.4  4=2.4 ) tcb ->x
          '( 0=-0.7  1=0.7  2=-0.7  3=0.7  4=-0.7 ) tcb ->y
          '( 0=0  4=-3.14159 ) ->rot
        ;
    """.trimIndent()

    /** Wall units either side of zero the fine probe pass resolves; see [measureSdfAgreement]. */
    private const val PROBE_NEAR_RANGE = 1.0

    /** Range of the coarse pass, which has to cover the whole wall's far field. */
    private const val PROBE_FAR_RANGE = 32.0

    private const val SDF_MODULE_NAME = "rectsl-sdf-module"
    private const val SDF_MESH_NAME = "rectsl-sdf-mesh"
    private const val SDF_PRE_TARGET_NAME = "rectsl-sdf-rtt"

    /** What [measureSdfAgreement] found, so the caller can print it and assert on it. */
    data class SdfAgreement(
        val label: String,
        val comparisons: Int,
        val maxAbsError: Double,
        val meanAbsError: Double,
        /** The 24-bit step at this pass's range — no measurement here can beat it. */
        val quantisation: Double,
        /**
         * Largest deviation of a single decoded coordinate from the fitted
         * pixel→wall line. This is the *coordinate* probe's own quantisation, not
         * an error in the mapping: the fitted line averages hundreds of samples, so
         * it is roughly √N better than this number. Reported because if the mapping
         * were not linear — a flipped axis, a half-pixel offset, a clamped
         * encoding — this is where it would show, and it did once.
         */
        val coordinateResidual: Double
    ) {
        override fun toString(): String =
            ("$label: %d point(s), max |GPU-CPU| = %.3e, mean = %.3e " +
                "(24-bit step %.3e), coordinate residual %.3e")
                .format(comparisons, maxAbsError, meanAbsError, quantisation, coordinateResidual)
    }

    /**
     * Everything the SDF page needs, in one graphics context: a still per look, an
     * animated clip, the raster-vs-SDF counterfactual, and the GPU↔CPU
     * differential. Returns the hero still.
     *
     * The differential runs **first** and throws if the compiled shader and the
     * CPU sampler disagree, so a wrong field cannot quietly become a pretty
     * picture — which is the failure mode every one of these render harnesses has
     * had.
     */
    suspend fun renderSdf(outputDir: File, renderer: Renderer): File {
        outputDir.mkdirs()
        val program = compileForRender("sdf-scene", SDF_SCENE_SOURCE)
        val carveProgram = compileForRender("sdf-carve", SDF_CARVE_SOURCE)

        val agreement = measureSdfAgreement(renderer, program)
        agreement.forEach { lprintln("info,doc", "rectsl sdf ${it}") }
        val near = agreement.first { it.label.startsWith("near") }
        require(near.maxAbsError < 1e-5) {
            "the compiled SDF shader and the CPU sampler disagree by ${near.maxAbsError} wall " +
                "units near the surface, which is past the 1e-6-ish float/double gap the emitter " +
                "documents:\n" + agreement.joinToString("\n")
        }
        val far = agreement.first { it.label.startsWith("far") }
        require(far.maxAbsError < 4.0 * far.quantisation) {
            "the far field disagrees by ${far.maxAbsError}, more than the coarse pass's own " +
                "${far.quantisation} encoding step can explain:\n" + agreement.joinToString("\n")
        }
        File(outputDir, "rectsl-sdf-agreement.txt").writeText(
            agreement.joinToString("\n", postfix = "\n") { it.toString() }
        )

        val written = mutableListOf<File>()
        RectSLSdfLook.PRESETS.forEach { look ->
            val file = File(outputDir, "rectsl-sdf-${look.name}.png")
            val field = RectSLGlsl.emitSdf(program, "scene", look.emitOptions())
            val carve = if (look.carves) {
                RectSLGlsl.emitSdf(carveProgram, "carveScene", RectSLSdfOptions(namePrefix = "carve"))
            } else {
                null
            }
            renderSdfStill(
                renderer, program,
                fragment = RectSLSdfShader.fragment(field, look, carve),
                time = SDF_STILL_TIME,
                outputFile = file,
                label = "sdf-${look.name}"
            )
            written += file
            lprintln("info,doc", "rectsl sdf wrote ${file.name} (${file.length()} bytes)")
        }

        renderSdfAnimation(outputDir, renderer, program)
        renderSdfCounterfactual(outputDir, renderer, program)
        renderSdfResolutionPair(outputDir, renderer, program)

        return written.firstOrNull { it.name.contains("relief") }
            ?: written.firstOrNull()
            ?: error("rectsl sdf produced no images")
    }

    /** The instant every look still is sampled at: mid-sweep, where the blades overlap. */
    private const val SDF_STILL_TIME = 0.95

    /**
     * One clip of the `motion-blur` look, so the docs can show that the streak
     * tracks the motion rather than being a fixed smear. Same encode path as the
     * raster animations.
     */
    private suspend fun renderSdfAnimation(outputDir: File, renderer: Renderer, program: SceneProgram): File {
        val look = RectSLSdfLook.preset("motion-blur")
        val field = RectSLGlsl.emitSdf(program, "scene", look.emitOptions())
        val fragment = RectSLSdfShader.fragment(field, look)
        val (width, height) = outputDims(program)
        val fps = min(program.fps.roundToInt().coerceAtLeast(1), ANIM_FPS)
        val frameCount = min(max(1, ceil(program.duration * fps).toInt()), ANIM_MAX_FRAMES)
        val framesDir = Files.createTempDirectory("rectsl-sdf-anim").toFile()
        val session = SdfSession(renderer, program, fragment, width, height, "sdf-anim")
        try {
            renderer.renderList.preTargets[SDF_PRE_TARGET_NAME] = session.rtt
            session.currentTime = 0.0
            waitForRendererFrames(renderer, WAIT_FRAMES)
            for (i in 0 until frameCount) {
                session.currentTime = if (frameCount > 1) program.duration * i / frameCount else 0.0
                waitForRendererFrames(renderer, SETTLE_FRAMES)
                captureRttTo(
                    renderer, session.rtt, File(framesDir, "frame_%05d.png".format(i)),
                    "sdf-anim", width, height
                )
            }
            val mp4 = File(outputDir, "rectsl-sdf-motion-blur.mp4")
            encodeMp4(resolveFfmpeg(), framesDir, "frame_%05d.png", fps, mp4)
            require(mp4.length() > 0L) { "ffmpeg wrote an empty mp4 for the SDF clip" }
            lprintln("info,doc", "rectsl sdf wrote ${mp4.name} (${mp4.length()} bytes)")
            return mp4
        } finally {
            framesDir.deleteRecursively()
            renderer.renderList.preTargets.remove(SDF_PRE_TARGET_NAME)
            session.dispose()
        }
    }

    /**
     * The counterfactual, computed rather than asserted: the *same* scene at the
     * *same* instant through the ordinary raster path and through the SDF, side by
     * side in one image.
     *
     * This is the honest way to show what the field drops. The left half has
     * per-rect fill, StackSL materials and z order; the right half has none of
     * them, because `min` is union and a union has no layers — and in exchange it
     * has an outline and a glow that the left half cannot express at any
     * resolution.
     */
    private suspend fun renderSdfCounterfactual(outputDir: File, renderer: Renderer, program: SceneProgram): File {
        val (width, height) = outputDims(program)
        val rasterFile = File.createTempFile("rectsl-sdf-raster", ".png")
        val sdfFile = File.createTempFile("rectsl-sdf-field", ".png")
        try {
            renderStill(renderer, program, SDF_STILL_TIME, rasterFile, "sdf-counterfactual-raster")
            val look = RectSLSdfLook.preset("glow")
            val field = RectSLGlsl.emitSdf(program, "scene", look.emitOptions())
            renderSdfStill(
                renderer, program,
                fragment = RectSLSdfShader.fragment(field, look),
                time = SDF_STILL_TIME,
                outputFile = sdfFile,
                label = "sdf-counterfactual-field"
            )
            val out = File(outputDir, "rectsl-sdf-counterfactual.png")
            out.delete()
            hstack(resolveFfmpeg(), rasterFile, sdfFile, out)
            require(out.length() > 0L) { "ffmpeg wrote an empty counterfactual PNG" }
            lprintln(
                "info,doc",
                "rectsl sdf wrote ${out.name} (${out.length()} bytes, ${2 * width + 8}x$height)"
            )
            return out
        } finally {
            rasterFile.delete()
            sdfFile.delete()
        }
    }

    /**
     * "Antialiased at any zoom" is the one claim a single still cannot make, so
     * make it with two.
     *
     * Both halves show the *same* wall region at the same output size. The left is
     * rendered at the gallery's ordinary resolution and then magnified 4× with
     * nearest-neighbour, so its edge is whatever the rasteriser committed to. The
     * right is rendered at 4× and cropped, so its edge was re-resolved from the
     * field. Nothing about the scene, the shader or the look changed between them —
     * only how many fragments asked the question.
     */
    private suspend fun renderSdfResolutionPair(outputDir: File, renderer: Renderer, program: SceneProgram): File {
        val look = RectSLSdfLook.preset("outline")
        val field = RectSLGlsl.emitSdf(program, "scene", look.emitOptions())
        val fragment = RectSLSdfShader.fragment(field, look)
        val (width, height) = outputDims(program)
        val zoom = 4
        val coarse = File.createTempFile("rectsl-sdf-coarse", ".png")
        val fine = File.createTempFile("rectsl-sdf-fine", ".png")
        try {
            renderSdfStill(renderer, program, fragment, SDF_STILL_TIME, coarse, "sdf-zoom-coarse", width, height)
            renderSdfStill(
                renderer, program, fragment, SDF_STILL_TIME, fine, "sdf-zoom-fine",
                width * zoom, height * zoom
            )
            val out = File(outputDir, "rectsl-sdf-zoom.png")
            out.delete()
            // Left: the 1x render's centre ninth, blown up with no interpolation.
            // Right: the same wall region straight out of the 4x render.
            ffmpeg(
                resolveFfmpeg(), "compositing ${out.name}",
                "-i", coarse.absolutePath,
                "-i", fine.absolutePath,
                "-filter_complex",
                "[0:v]crop=${width / zoom}:${height / zoom}:${width * 3 / (2 * zoom)}:${height * 3 / (2 * zoom)}," +
                    "scale=$width:$height:flags=neighbor,pad=iw+8:ih:0:0:color=0x202429[a];" +
                    "[1:v]crop=$width:$height:${width * (zoom - 1) / 2}:${height * (zoom - 1) / 2}[b];" +
                    "[a][b]hstack=inputs=2",
                "-frames:v", "1",
                out.absolutePath
            )
            require(out.length() > 0L) { "ffmpeg wrote an empty zoom comparison" }
            lprintln("info,doc", "rectsl sdf wrote ${out.name} (${out.length()} bytes)")
            return out
        } finally {
            coarse.delete()
            fine.delete()
        }
    }

    /**
     * Ask the *compiled shader* what the field is, and compare it against the CPU
     * sampler that the raster path draws from.
     *
     * How it avoids the usual trap: the probe shader
     * ([RectSLSdfShader.probeFragment]) also reports `p.x` and `p.y`, so the
     * pixel→wall mapping is recovered from the shader's own answers — a linear fit
     * over every column and row — instead of being re-derived on the CPU, where a
     * flipped axis or a half-pixel is exactly the kind of mistake that makes a
     * differential agree with itself. The coordinate channels are encoded over the
     * *wall's* size, never over the distance range, so narrowing the distance range
     * cannot saturate them.
     *
     * Two passes per instant, differing only in how finely the distance is
     * encoded. The fine pass resolves `[-1, 1]` wall units to 1.2e-7 and compares
     * only inside that band — which is where every geometric claim lives, and is
     * fine enough that what it measures is the shader's own `float` arithmetic
     * rather than the readback. The coarse pass covers `[-32, 32]` at 3.8e-6 so the
     * far field cannot be silently wrong; it cannot resolve better than that, and
     * its numbers should be read as "consistent to the encoding", not as an error.
     *
     * The blended union is deliberately *not* measured here: `smin` is not
     * associative, so agreeing with a CPU port would mean re-deriving the
     * emitter's chunked union tree on the CPU, and a differential that has to
     * replicate the structure it is checking proves less than the algebraic bound
     * `RectSLSdfLookTest` already holds it to.
     */
    suspend fun measureSdfAgreement(
        renderer: Renderer,
        program: SceneProgram,
        times: List<Double> = listOf(0.0, 0.37, 0.95, 2.05, 3.6)
    ): List<SdfAgreement> {
        val field = RectSLGlsl.emitSdf(program, "scene")
        val fragment = RectSLSdfShader.probeFragment(field)
        val (width, height) = outputDims(program)
        val session = SdfSession(renderer, program, fragment, width, height, "sdf-probe")
        val ranges = listOf("near" to PROBE_NEAR_RANGE, "far" to PROBE_FAR_RANGE)
        val comparisons = IntArray(ranges.size)
        val maxErr = DoubleArray(ranges.size)
        val sumErr = DoubleArray(ranges.size)
        var maxResidual = 0.0
        try {
            renderer.renderList.preTargets[SDF_PRE_TARGET_NAME] = session.rtt
            waitForRendererFrames(renderer, WAIT_FRAMES)
            for (time in times) {
                session.currentTime = time
                // Channels 1 and 2 are the shader's own world coordinates, encoded
                // over the wall rather than over probeRange, so they are read once
                // per instant and shared by both distance passes.
                session.probeChannel = 1.0
                waitForRendererFrames(renderer, SETTLE_FRAMES)
                val xs = decodeProbe(renderer, session, width, height, program.wall.width, "probe-x")
                session.probeChannel = 2.0
                waitForRendererFrames(renderer, SETTLE_FRAMES)
                val ys = decodeProbe(renderer, session, width, height, program.wall.height, "probe-y")

                // p.x depends only on the column and p.y only on the row, both
                // linearly. Fitting recovers the mapping far better than the 24-bit
                // quantisation of any single pixel.
                val xFit = fitLinear(width) { col -> xs[col] }
                val yFit = fitLinear(height) { row -> ys[row * width] }
                val coordStep = max(
                    RectSLSdfShader.quantisation(program.wall.width),
                    RectSLSdfShader.quantisation(program.wall.height)
                )
                val residual = maxOf(
                    (0 until width).maxOf { abs(xFit(it.toDouble()) - xs[it]) },
                    (0 until height).maxOf { abs(yFit(it.toDouble()) - ys[it * width]) }
                )
                require(residual < 2.0 * coordStep) {
                    "the pixel->wall mapping is not the straight line it must be: residual " +
                        "$residual against a 24-bit step of $coordStep. Either an axis is " +
                        "non-linear or the coordinate encoding is clamping."
                }
                maxResidual = max(maxResidual, residual)

                val sampled = program.sample(time).rects.filter { it.alpha > 0.0 }
                ranges.forEachIndexed { index, (_, range) ->
                    session.probeRange = range
                    session.probeChannel = 0.0
                    waitForRendererFrames(renderer, SETTLE_FRAMES)
                    val ds = decodeProbe(renderer, session, width, height, range, "probe-d")
                    var row = 2
                    while (row < height - 2) {
                        var col = 2
                        while (col < width - 2) {
                            val p = afield.rectsl.Vec2d(xFit(col.toDouble()), yFit(row.toDouble()))
                            val cpu = sampled.minOfOrNull { boxDistance(p, it) } ?: 1e9
                            // Only where the encoding was not clamped: at the rail the
                            // shader's answer says "at least this far", not "this far".
                            if (abs(cpu) < range * 0.9) {
                                val err = abs(ds[row * width + col] - cpu)
                                maxErr[index] = max(maxErr[index], err)
                                sumErr[index] += err
                                comparisons[index]++
                            }
                            col += 7
                        }
                        row += 7
                    }
                }
            }
            return ranges.mapIndexed { index, (rangeName, range) ->
                require(comparisons[index] > 500) {
                    "the $rangeName probe pass compared only ${comparisons[index]} points — the " +
                        "encoding range is probably wrong, so this measured nothing"
                }
                SdfAgreement(
                    label = "$rangeName (±$range wall units)",
                    comparisons = comparisons[index],
                    maxAbsError = maxErr[index],
                    meanAbsError = sumErr[index] / comparisons[index],
                    quantisation = RectSLSdfShader.quantisation(range),
                    coordinateResidual = maxResidual
                )
            }
        } finally {
            renderer.renderList.preTargets.remove(SDF_PRE_TARGET_NAME)
            session.dispose()
        }
    }

    private suspend fun renderSdfStill(
        renderer: Renderer,
        program: SceneProgram,
        fragment: String,
        time: Double,
        outputFile: File,
        label: String,
        outWidth: Int = -1,
        outHeight: Int = -1
    ) {
        val (defaultWidth, defaultHeight) = outputDims(program)
        val width = if (outWidth > 0) outWidth else defaultWidth
        val height = if (outHeight > 0) outHeight else defaultHeight
        val session = SdfSession(renderer, program, fragment, width, height, label)
        try {
            session.currentTime = time
            renderer.renderList.preTargets[SDF_PRE_TARGET_NAME] = session.rtt
            waitForRendererFrames(renderer, WAIT_FRAMES)
            captureRttTo(renderer, session.rtt, outputFile, label, width, height)
        } finally {
            renderer.renderList.preTargets.remove(SDF_PRE_TARGET_NAME)
            session.dispose()
        }
    }

    /**
     * The SDF twin of [Session]: the same offscreen target and the same readback,
     * but a fullscreen triangle with **no vertex attributes and no per-frame mesh
     * rewrite**. That absence is the whole difference between the two paths — the
     * raster session re-lowers the scene to triangles every frame, this one
     * uploads three vertices once and moves a float.
     */
    private class SdfSession(
        renderer: Renderer,
        program: SceneProgram,
        fragment: String,
        width: Int,
        height: Int,
        label: String
    ) {
        var currentTime: Double = 0.0
        var probeChannel: Double = 0.0
        var probeRange: Double = PROBE_NEAR_RANGE
        val rtt: RenderToTexture
        private val module: ModulePointer

        init {
            rtt = RenderToTexture(renderer) {
                this.width = width
                this.height = height
                // UNORM, not SRGB: the probe's bytes have to survive the readback
                // unchanged, and the look shaders write the colour they mean.
                this.format = VK_FORMAT_R8G8B8A8_UNORM
                this.useDepth = false
                this.colorSampled = true
                this.colorTransferSrc = true
                this.clearColor = afield.stdlib.vec(0f, 0f, 0f, 1f)
                this.debugName = "rectsl-$label"
            }
            module = Module(parameters(renderer) {
                name = SDF_MODULE_NAME
                vertex = RectSLSdfShader.VERTEX
                this.fragment = fragment
                blend = false
                depthTest = false
                primitiveType = triangles
                rtt.apply()
            }).pointer()
            val mesh = Mesh(renderer, module)
            mesh.resize(3, 0, module) // fullscreen triangle, no vertex attributes
            mesh.push.bind("time") { currentTime.toFloat() }
            mesh.push["wallWidth"] = program.wall.width.toFloat()
            mesh.push["wallHeight"] = program.wall.height.toFloat()
            mesh.push.bind("probeChannel") { probeChannel.toFloat() }
            mesh.push.bind("probeRange") { probeRange.toFloat() }
            module.current.meshes[SDF_MESH_NAME] = mesh
            rtt += SDF_MODULE_NAME to module
        }

        fun dispose() {
            runCatching {
                rtt.modules.remove(SDF_MODULE_NAME)
                module.current.meshes.values.toSet().forEach { mesh ->
                    (mesh.v.values + mesh.vn.values).toSet().forEach { it.cleanup() }
                    mesh.t?.cleanup()
                }
                module.current.meshes.clear()
                module.current.cleanup()
            }.onFailure { lprintln("error,doc", "rectsl sdf module dispose failed: ${it.message}") }
            disposeRtt(rtt)
        }
    }

    /** Read the target back and unpack the 24-bit payload of every pixel. */
    private fun decodeProbe(
        renderer: Renderer,
        session: SdfSession,
        width: Int,
        height: Int,
        range: Double,
        label: String
    ): DoubleArray {
        val png = readbackPng(renderer, session.rtt, label, width, height)
        val out = DoubleArray(width * height)
        val buffer = MemoryUtil.memAlloc(png.size)
        try {
            buffer.put(png).flip()
            MemoryStack.stackPush().use { stack ->
                val w = stack.mallocInt(1)
                val h = stack.mallocInt(1)
                val ch = stack.mallocInt(1)
                // STB rather than ImageIO on purpose: this runs inside a live GLFW
                // process, and pulling AWT up on macOS to read four bytes is not worth
                // the risk. LWJGL's decoder is already loaded.
                val decoded = STBImage.stbi_load_from_memory(buffer, w, h, ch, 4)
                    ?: error("could not decode the RectSL SDF probe readback for '$label'")
                try {
                    require(w[0] == width && h[0] == height) {
                        "probe readback was ${w[0]}x${h[0]}, expected ${width}x$height"
                    }
                    for (i in 0 until width * height) {
                        val off = i * 4
                        out[i] = RectSLSdfShader.unpack24(
                            decoded[off].toInt() and 0xFF,
                            decoded[off + 1].toInt() and 0xFF,
                            decoded[off + 2].toInt() and 0xFF,
                            range
                        )
                    }
                } finally {
                    STBImage.stbi_image_free(decoded)
                }
            }
        } finally {
            MemoryUtil.memFree(buffer)
        }
        return out
    }

    /** Ordinary least squares through [n] samples of an exactly-linear sequence. */
    private fun fitLinear(n: Int, value: (Int) -> Double): (Double) -> Double {
        require(n >= 2) { "need at least two samples to fit a line" }
        val meanX = (n - 1) / 2.0
        var meanY = 0.0
        for (i in 0 until n) meanY += value(i)
        meanY /= n
        var num = 0.0
        var den = 0.0
        for (i in 0 until n) {
            val dx = i - meanX
            num += dx * (value(i) - meanY)
            den += dx * dx
        }
        val slope = num / den
        val intercept = meanY - slope * meanX
        return { x -> intercept + slope * x }
    }

    /** Exact distance to one sampled rect — the CPU side of the differential. */
    private fun boxDistance(p: afield.rectsl.Vec2d, r: afield.rectsl.SampledRect): Double {
        val halfX = abs(0.5 * r.size.x * r.scale.x)
        val halfY = abs(0.5 * r.size.y * r.scale.y)
        val qx = p.x - r.center.x
        val qy = p.y - r.center.y
        val c = cos(r.rotation)
        val s = sin(r.rotation)
        val lx = c * qx + s * qy
        val ly = -s * qx + c * qy
        val dx = abs(lx) - halfX
        val dy = abs(ly) - halfY
        return hypot(max(dx, 0.0), max(dy, 0.0)) + min(max(dx, dy), 0.0)
    }

    /** Two PNGs side by side with a hairline gap, via the ffmpeg the mp4s already use. */
    private fun hstack(ffmpeg: String, left: File, right: File, output: File) {
        output.parentFile?.mkdirs()
        output.delete()
        ffmpeg(
            ffmpeg, "compositing ${output.name}",
            "-i", left.absolutePath,
            "-i", right.absolutePath,
            "-filter_complex",
            "[0:v]pad=iw+8:ih:0:0:color=0x202429[a];[a][1:v]hstack=inputs=2",
            "-frames:v", "1",
            output.absolutePath
        )
    }

    /** Run ffmpeg with `-y`, and fail loudly with its own log rather than silently. */
    private fun ffmpeg(ffmpeg: String, what: String, vararg args: String) {
        val process = ProcessBuilder(listOf(ffmpeg, "-y") + args)
            .redirectErrorStream(true).start()
        val log = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("ffmpeg timed out $what")
        }
        require(process.exitValue() == 0) {
            "ffmpeg failed (${process.exitValue()}) $what:\n${log.takeLast(800)}"
        }
    }

    // ── render paths ────────────────────────────────────────────────────────────

    private suspend fun renderStill(
        renderer: Renderer,
        program: SceneProgram,
        time: Double,
        outputFile: File,
        label: String
    ) {
        val (width, height) = outputDims(program)
        val session = Session(renderer, program, width, height)
        try {
            session.currentTime = time
            renderer.renderList.preTargets[PRE_TARGET_NAME] = session.rtt
            waitForRendererFrames(renderer, WAIT_FRAMES)
            captureTo(renderer, session, outputFile, label, width, height)
        } finally {
            renderer.renderList.preTargets.remove(PRE_TARGET_NAME)
            session.dispose()
        }
    }

    private suspend fun renderOneAnimation(
        renderer: Renderer,
        program: SceneProgram,
        name: String,
        outputDir: File,
        ffmpeg: String
    ): File {
        val (width, height) = outputDims(program)
        val fps = min(program.fps.roundToInt().coerceAtLeast(1), ANIM_FPS)
        val frameCount = min(max(1, ceil(program.duration * fps).toInt()), ANIM_MAX_FRAMES)
        val session = Session(renderer, program, width, height)
        val framesDir = Files.createTempDirectory("rectsl-anim-$name").toFile()
        try {
            renderer.renderList.preTargets[PRE_TARGET_NAME] = session.rtt
            session.currentTime = 0.0
            waitForRendererFrames(renderer, WAIT_FRAMES)
            for (i in 0 until frameCount) {
                session.currentTime = if (frameCount > 1) program.duration * i / frameCount else 0.0
                waitForRendererFrames(renderer, SETTLE_FRAMES)
                captureTo(renderer, session, File(framesDir, "frame_%05d.png".format(i)), name, width, height)
            }
            val mp4 = File(outputDir, "rectsl-$name.mp4")
            encodeMp4(ffmpeg, framesDir, "frame_%05d.png", fps, mp4)
            require(mp4.length() > 0L) { "ffmpeg wrote an empty mp4 for '$name'" }
            return mp4
        } finally {
            framesDir.deleteRecursively()
            renderer.renderList.preTargets.remove(PRE_TARGET_NAME)
            session.dispose()
        }
    }

    // ── one program's render state (target + module + mesh) ──────────────────────

    /**
     * The offscreen target, fullscreen-style rect-scene module, and mesh for a single
     * program. Mirrors `RectSLRenderRunner.renderJpgSequence`'s setup but captures
     * through [TexturePreviewCache] (the readback path proven headless by the StackSL
     * gallery) instead of `saveJpgAsync`, and lets the caller step [currentTime].
     */
    private class Session(
        renderer: Renderer,
        program: SceneProgram,
        width: Int,
        height: Int
    ) {
        var currentTime: Double = 0.0
        val rtt: RenderToTexture
        private val module: ModulePointer

        init {
            val shaderCode = rectMaterialShaderCode(program)
            require(shaderCode.ok) {
                "RectSL materials did not compile:\n" + shaderCode.diagnostics.joinToString("\n")
            }
            rtt = RenderToTexture(renderer) {
                this.width = width
                this.height = height
                this.format = VK_FORMAT_R8G8B8A8_UNORM
                this.useDepth = false
                this.colorSampled = true
                this.colorTransferSrc = true
                this.clearColor = afield.stdlib.vec(0f, 0f, 0f, 1f)
                this.debugName = "rectsl-gallery"
            }
            module = Module(parameters(renderer) {
                name = MODULE_NAME
                vertex = RECT_ANIMATION_VERTEX_SHADER
                fragment = shaderCode.exampleFragmentShader
                blend = true
                depthTest = false
                primitiveType = triangles
                rtt.apply()
            }).pointer()
            val mesh = Mesh(renderer, module)
            mesh.push["worldToClip"] = program.wall.worldToClip()
            mesh.push.bind("time") { currentTime.toFloat() }
            mesh.push["wallWidth"] = program.wall.width.toFloat()
            mesh.push["wallHeight"] = program.wall.height.toFloat()
            mesh.update["rectsl-frame"] = sequence {
                while (true) {
                    program.writeFrameToMesh(currentTime, mesh)
                    yield(Unit)
                }
            }
            module.current.meshes[MESH_NAME] = mesh
            rtt += MODULE_NAME to module
        }

        fun dispose() {
            runCatching {
                rtt.modules.remove(MODULE_NAME)
                module.current.meshes.values.toSet().forEach { mesh ->
                    (mesh.v.values + mesh.vn.values).toSet().forEach { it.cleanup() }
                    mesh.t?.cleanup()
                }
                module.current.meshes.clear()
                module.current.cleanup()
            }.onFailure { lprintln("error,doc", "rectsl gallery module dispose failed: ${it.message}") }
            disposeRtt(rtt)
        }
    }

    // ── shared plumbing ───────────────────────────────────────────────────────────

    private suspend fun compile(example: Example): SceneProgram = compileForRender(example.name, example.source)

    // A dedicated Kotlin engine for pre-warming `{{ … }}` escapes. It must NOT be the
    // render box's own engine: that one is busy *executing* the render code that calls
    // us, so compiling a new snippet on it re-enters a single-threaded REPL and
    // deadlocks. A fresh, idle engine compiles fine while the box engine runs.
    private val escapeEngine: KotlinScriptEngine by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        KotlinInterface2(filePrefix = "RECTSL_ESCAPE_RENDER_")
    }

    /**
     * Compile a program for rendering, pre-warming any `{{ … }}` escapes off the
     * runloop first (see [prewarmRectSLEscapes]) so the render never triggers a
     * blocking K2 compile inside the non-reentrant graphics runloop.
     */
    private suspend fun compileForRender(
        name: String,
        source: String,
        curves: Map<String, Functions.Fn> = emptyMap()
    ): SceneProgram {
        val precompiled = if ("{{" in source) prewarmRectSLEscapes(source, escapeEngine) else emptyMap()
        val result = compileRectSL(source, curves, precompiledEscapes = precompiled)
        return result.programOrNull?.takeIf { result.ok }
            ?: throw RectLangException("RectSL '$name' did not compile:\n${result.diagnostics.joinToString("\n")}")
    }

    /** Longest side [OUTPUT_LONG]; the other derived from the wall aspect, both even. */
    private fun outputDims(program: SceneProgram): Pair<Int, Int> {
        val aspect = program.wall.width / program.wall.height
        val (w, h) = if (aspect >= 1.0) OUTPUT_LONG.toDouble() to OUTPUT_LONG / aspect
        else OUTPUT_LONG * aspect to OUTPUT_LONG.toDouble()
        fun even(v: Double) = max(2, (v.roundToInt() / 2) * 2)
        return even(w) to even(h)
    }

    private suspend fun captureTo(
        renderer: Renderer,
        session: Session,
        outputFile: File,
        label: String,
        width: Int,
        height: Int
    ) = captureRttTo(renderer, session.rtt, outputFile, label, width, height)

    /** Read back one offscreen target as PNG bytes on disk. Shared by both sessions. */
    private fun captureRttTo(
        renderer: Renderer,
        rtt: RenderToTexture,
        outputFile: File,
        label: String,
        width: Int,
        height: Int
    ) {
        outputFile.parentFile?.mkdirs()
        outputFile.delete()
        outputFile.writeBytes(readbackPng(renderer, rtt, label, width, height))
        require(outputFile.length() > 0L) { "RectSL gallery readback wrote an empty image for '$label'" }
    }

    /** The same readback, kept in memory — the SDF differential never wants a file. */
    private fun readbackPng(
        renderer: Renderer,
        rtt: RenderToTexture,
        label: String,
        width: Int,
        height: Int
    ): ByteArray {
        val html = TexturePreviewCache.capture(
            renderer = renderer,
            vkImage = rtt.colorImageHandles[0],
            format = rtt.colorFormats[0],
            srcWidth = width,
            srcHeight = height,
            currentLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
            maxDim = max(width, height)
        ) ?: error("could not read back the RectSL gallery texture for '$label'")
        val encoded = DATA_IMAGE_RE.find(html)?.groupValues?.get(1)
            ?: error("could not parse the RectSL gallery texture data for '$label'")
        return Base64.getDecoder().decode(encoded)
    }

    private fun resolveFfmpeg(): String {
        val candidates = listOfNotNull(
            System.getenv("FFMPEG"),
            "/opt/homebrew/bin/ffmpeg",
            "/usr/local/bin/ffmpeg",
            "/usr/bin/ffmpeg"
        )
        return candidates.firstOrNull { File(it).canExecute() } ?: "ffmpeg"
    }

    private fun encodeMp4(ffmpeg: String, framesDir: File, pattern: String, fps: Int, output: File) {
        output.parentFile?.mkdirs()
        output.delete()
        val process = ProcessBuilder(
            ffmpeg, "-y",
            "-framerate", fps.toString(),
            "-i", pattern,
            "-c:v", "libx264",
            "-pix_fmt", "yuv420p",
            "-crf", "20",
            "-movflags", "+faststart",
            output.absolutePath
        ).directory(framesDir).redirectErrorStream(true).start()
        val log = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("ffmpeg timed out encoding ${output.name}")
        }
        require(process.exitValue() == 0) {
            "ffmpeg failed (${process.exitValue()}) for ${output.name}:\n${log.takeLast(600)}"
        }
    }

    private suspend fun waitForRendererFrames(renderer: Renderer, frames: Int) {
        val target = renderer.frameCounter + frames
        val deadline = System.nanoTime() + 15_000_000_000L
        while (renderer.frameCounter < target) {
            if (System.nanoTime() > deadline) {
                error("timed out waiting for renderer frames: current=${renderer.frameCounter} target=$target")
            }
            delay(8)
            yield()
        }
    }

    private fun disposeRtt(rtt: RenderToTexture) {
        runCatching {
            rtt.defaultSampler?.cleanup()
            rtt.imageViewArray.map { it.imageView }.distinctBy { it.vkImageView }.forEach { it.cleanup() }
            rtt.colorImageRefs.filterNotNull().distinctBy { it.vkImage }.forEach { it.cleanup() }
            listOfNotNull(rtt.depthSampledImageView, rtt.depthImageView).distinctBy { it.vkImageView }
                .forEach { it.cleanup() }
            rtt.depthImage?.cleanup()
            rtt.commandBuffers.forEach { it.cleanup() }
            rtt.fences.forEach { it.cleanup() }
        }.onFailure { lprintln("error,doc", "rectsl gallery RTT dispose failed: ${it.message}") }
    }
}
