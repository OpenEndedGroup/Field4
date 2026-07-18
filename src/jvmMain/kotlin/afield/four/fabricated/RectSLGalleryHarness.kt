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
import org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM
import org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

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
            lprintln("info,doc", "rectsl fit-to-target RMSE ${"%.4f".format(run.initialRmse)} -> ${"%.4f".format(run.finalRmse)}")
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
        wall 22 12 abstract
        duration 1
        fps 1

        "trow" rect  0 -4 xy  0.5 1 wh  0x2e5a86 solid fill
          40 '( ic 0.55 * 0 offset  target f sample 4 * 0.12 +  h ) array ;

        "srow" rect  0 0 xy  0.5 1 wh  0x8a5a2b solid fill
          40 '( ic 0.55 * 0 offset  seed f sample 4 * 0.12 +  h ) array ;

        "frow" rect  0 4 xy  0.5 1 wh  0xffd23c solid fill
          40 '( ic 0.55 * 0 offset  fit f sample {{ 4.0 }} * 0.12 +  h ) array ;
    """.trimIndent()

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
    ) {
        val html = TexturePreviewCache.capture(
            renderer = renderer,
            vkImage = session.rtt.colorImageHandles[0],
            format = session.rtt.colorFormats[0],
            srcWidth = width,
            srcHeight = height,
            currentLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
            maxDim = max(width, height)
        ) ?: error("could not read back the RectSL gallery texture for '$label'")
        val encoded = DATA_IMAGE_RE.find(html)?.groupValues?.get(1)
            ?: error("could not parse the RectSL gallery texture data for '$label'")
        outputFile.parentFile?.mkdirs()
        outputFile.delete()
        outputFile.writeBytes(Base64.getDecoder().decode(encoded))
        require(outputFile.length() > 0L) { "RectSL gallery readback wrote an empty image for '$label'" }
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
