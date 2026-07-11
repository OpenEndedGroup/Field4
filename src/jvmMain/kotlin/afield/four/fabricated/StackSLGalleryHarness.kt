package afield.four.fabricated

import afield.Box
import afield.four.Renderer
import afield.four.TexturePreviewCache
import afield.four.modules.Mesh
import afield.four.modules.Module
import afield.four.modules.ModulePointer
import afield.four.modules.ShaderKind
import afield.four.modules.parameters
import afield.four.modules.pointer
import afield.four.targets.PingPongRenderToTexture
import afield.four.targets.RenderToTexture
import afield.four.texture.HasImageView
import afield.four.texture.HasImageViewArray
import afield.four.texture.TextureSampler
import afield.logger.lprintln
import afield.stacksl.StackSLCompileOptions
import afield.stacksl.StackSLCompiler
import afield.stacksl.StackSLSeverity
import afield.stdlib.preprocess
import afield.stdlib.stackAutoShaderSources
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield
import org.lwjgl.vulkan.VK10.VK_FILTER_LINEAR
import org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM
import org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
import org.lwjgl.vulkan.VK10.VK_SAMPLER_MIPMAP_MODE_LINEAR
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * A tiny fullscreen StackSL harness for the docs.
 *
 * It keeps **one** renderer, **one** offscreen [RenderToTexture], and **one**
 * fragment slot alive, then walks the StackSL programs from a
 * canonical_graphics-style workspace ([WORKSPACE_DIR]). Each program is injected
 * through the ordinary `{{ stack.auto.program }}` template path (the same
 * live-coding mechanism documented on the StackSL pages), the fullscreen module
 * is rebuilt under a single name — so the new module simply replaces the previous
 * one in the target's module map — and the resulting texture is read back.
 *
 * [renderGallery] reads back a single still per program. [renderAnimations]
 * drives a `time` push constant across frames and encodes each program to an mp4
 * (the docs runner can only screenshot the native GPU window, never record it, so
 * the harness owns the readback + ffmpeg encode). Both are "one Field document,
 * many afield.four renders": the whole page is produced inside a single graphics
 * context instead of restarting the JVM per asset. The example programs are
 * editable Field content in the workspace, not literals in this file.
 */
object StackSLGalleryHarness {

    const val OUTPUT_DIM = 512
    private const val WAIT_FRAMES = 12
    private const val ANIM_FPS = 24
    private const val ANIM_FRAMES = 96          // ~4s per clip
    private const val ANIM_SETTLE_FRAMES = 2    // renderer frames to let each new time render
    private const val MODULE_NAME = "stacksl-gallery-module"
    private const val MESH_NAME = "stacksl-gallery-mesh"
    private const val PRE_TARGET_NAME = "stacksl-gallery-rtt"
    private const val PRE_TARGET_NAME_FEEDBACK = "stacksl-gallery-feedback"
    private const val FEEDBACK_WARMUP_FRAMES = 60
    private const val SLOT = "program"
    private val DATA_IMAGE_RE = Regex("base64,([^']+)")

    /** Repo-relative workspace whose `*_stackSL_.stacksl` files are the examples. */
    const val WORKSPACE_DIR = "public/docs/assets/stacksl_gallery"

    /** The gallery entry embedded as the section hero image. */
    private const val HERO_NAME = "fbm-palette"

    /**
     * One gallery entry loaded from the workspace: [name] is the box slug (and the
     * PNG/mp4 stem `stacksl-<name>`), [program] its StackSL source, [time] is baked
     * into `input.w` for the still. [animate] marks time-driven programs that also
     * get an mp4, and [timeSpeed] scales how fast `input.w` sweeps in that clip.
     */
    data class Example(
        val name: String,
        val program: String,
        val time: Float = 1.75f,
        val animate: Boolean = false,
        val timeSpeed: Float = 1.0f,
        val feedback: Boolean = false
    )

    private fun defaultWorkspaceDir(): File = File(System.getProperty("user.dir"), WORKSPACE_DIR)

    /**
     * Load every example from the canonical_graphics-style workspace: one box per
     * `<name>_stackSL_.stacksl` file, ordered by the `docOrder` in the sibling
     * `<name>_metadata_.yaml` (which also carries optional `animate:`/`timeSpeed:`).
     * The programs live in the workspace, not in this file.
     */
    fun loadExamples(workspaceDir: File = defaultWorkspaceDir()): List<Example> {
        val files = workspaceDir.listFiles { f -> f.name.endsWith("_stackSL_.stacksl") }
            ?: error("StackSL gallery workspace not found: ${workspaceDir.absolutePath}")
        require(files.isNotEmpty()) { "StackSL gallery workspace has no *_stackSL_.stacksl files: ${workspaceDir.absolutePath}" }
        return files
            .map { f ->
                val name = f.name.removeSuffix("_stackSL_.stacksl")
                val meta = File(workspaceDir, "${name}_metadata_.yaml").takeIf { it.exists() }?.readLines() ?: emptyList()
                fun value(key: String): String? =
                    meta.firstNotNullOfOrNull { Regex("""^\s*$key:\s*(.+?)\s*$""").find(it)?.groupValues?.get(1) }
                val order = value("docOrder")?.toIntOrNull() ?: Int.MAX_VALUE
                val animate = value("animate")?.toBooleanStrictOrNull() ?: false
                val timeSpeed = value("timeSpeed")?.toFloatOrNull() ?: 1.0f
                val feedback = value("feedback")?.toBooleanStrictOrNull() ?: false
                Example(
                    name = name,
                    program = f.readText().trim(),
                    animate = animate,
                    timeSpeed = timeSpeed,
                    feedback = feedback
                ) to order
            }
            .sortedWith(compareBy({ it.second }, { it.first.name }))
            .map { it.first }
    }

    /**
     * Render every workspace example into `<outputDir>/stacksl-<name>.png`,
     * reusing a single renderer + target. Returns the hero PNG for the docs fence.
     */
    suspend fun renderGallery(outputDir: File, renderer: Renderer): File {
        outputDir.mkdirs()
        val examples = loadExamples()
        val rtt = newTarget(renderer)
        renderer.renderList.preTargets[PRE_TARGET_NAME] = rtt
        val templateBox = Box("stacksl-gallery-preprocess")

        var heroFile: File? = null
        try {
            examples.forEach { example ->
                val file = File(outputDir, "stacksl-${example.name}.png")
                runCatching {
                    buildProgramModule(renderer, rtt, templateBox, example.program, fragmentTemplate(example.time), example.name)
                    waitForRendererFrames(renderer, WAIT_FRAMES)
                    captureTo(renderer, rtt, file, example.name)
                }
                    .onSuccess {
                        lprintln("info,doc", "stacksl gallery wrote ${file.name} (${file.length()} bytes)")
                        if (example.name == HERO_NAME) heroFile = file
                    }
                    .onFailure { t -> lprintln("error,doc", "stacksl gallery example '${example.name}' failed: ${t.message}") }
            }
        } finally {
            teardown(renderer, rtt)
        }

        return heroFile
            ?: File(outputDir, "stacksl-${examples.first().name}.png").takeIf { it.exists() }
            ?: error("stacksl gallery produced no images")
    }

    /**
     * Render an mp4 for every `animate: true` workspace example into
     * `<outputDir>/stacksl-<name>.mp4`, reusing one renderer + target. The shader
     * is built once per program; a `time` push constant sweeps over [ANIM_FRAMES]
     * frames, each read back and handed to ffmpeg.
     */
    suspend fun renderAnimations(outputDir: File, renderer: Renderer): List<File> {
        outputDir.mkdirs()
        val examples = loadExamples().filter { it.animate }
        require(examples.isNotEmpty()) {
            "no animated StackSL examples — set `animate: true` in a box's metadata.yaml under $WORKSPACE_DIR"
        }
        val ffmpeg = resolveFfmpeg()
        val templateBox = Box("stacksl-anim-preprocess")
        val written = mutableListOf<File>()

        // Time-driven clips share one plain target (shader rebuilt per program).
        val timeDriven = examples.filter { !it.feedback }
        if (timeDriven.isNotEmpty()) {
            val rtt = newTarget(renderer)
            renderer.renderList.preTargets[PRE_TARGET_NAME] = rtt
            try {
                timeDriven.forEach { example ->
                    runCatching { renderOneAnimation(renderer, rtt, templateBox, example, outputDir, ffmpeg) }
                        .onSuccess { written += it; lprintln("info,doc", "stacksl anim wrote ${it.name} (${it.length()} bytes)") }
                        .onFailure { t -> lprintln("error,doc", "stacksl anim '${example.name}' failed: ${t.message}") }
                }
            } finally {
                teardown(renderer, rtt)
            }
        }

        // Feedback clips each need their own ping-pong target (evolving state).
        examples.filter { it.feedback }.forEach { example ->
            runCatching { renderFeedbackAnimation(renderer, templateBox, example, outputDir, ffmpeg) }
                .onSuccess { written += it; lprintln("info,doc", "stacksl feedback anim wrote ${it.name} (${it.length()} bytes)") }
                .onFailure { t -> lprintln("error,doc", "stacksl feedback anim '${example.name}' failed: ${t.message}") }
        }
        return written
    }

    /**
     * Render a feedback (ping-pong) clip: the fragment samples the previous frame
     * through `source`, so the image evolves on its own each renderer frame. No
     * `time` sweep is needed — the loop *is* the animation. Uses a
     * [PingPongRenderToTexture] so the previous frame is available to sample while
     * the current one is written.
     */
    private suspend fun renderFeedbackAnimation(
        renderer: Renderer,
        templateBox: Box,
        example: Example,
        outputDir: File,
        ffmpeg: String
    ): File {
        val pp = PingPongRenderToTexture(renderer, frameCount = 2) {
            width = OUTPUT_DIM
            height = OUTPUT_DIM
            format = VK_FORMAT_R8G8B8A8_UNORM
            useDepth = false
            colorSampled = true
            colorTransferSrc = true
            clearColor = afield.stdlib.vec(0f, 0f, 0f, 1f)
            debugName = "stacksl-feedback"
        }
        renderer.renderList.preTargets[PRE_TARGET_NAME_FEEDBACK] = pp
        val sampler = TextureSampler(renderer, 1, true, VK_FILTER_LINEAR, VK_SAMPLER_MIPMAP_MODE_LINEAR)
        // `source` resolves to the previous ping-pong frame each time it is read.
        val sourceProvider = object : HasImageViewArray {
            override val imageViewArray: List<HasImageView> get() = listOf(pp.otherImageView)
        }

        val framesDir = Files.createTempDirectory("stacksl-feedback-${example.name}").toFile()
        try {
            var currentTime = 0f
            val mesh = buildProgramModule(
                renderer, pp, templateBox, example.program, feedbackFragmentTemplate(), example.name,
                source = sourceProvider to sampler
            )
            mesh.push.bind("params") { afield.stdlib.vec(currentTime, 0f, 0f, 0f) }
            waitForRendererFrames(renderer, FEEDBACK_WARMUP_FRAMES) // let the feedback develop structure
            for (i in 0 until ANIM_FRAMES) {
                currentTime = i.toFloat() / ANIM_FPS * example.timeSpeed
                waitForRendererFrames(renderer, ANIM_SETTLE_FRAMES)
                val view = pp.otherImageView.imageView
                captureImageTo(renderer, view.image, pp.colorFormats[0], File(framesDir, "frame_%05d.png".format(i)), example.name)
            }
            val mp4 = File(outputDir, "stacksl-${example.name}.mp4")
            encodeMp4(ffmpeg, framesDir, "frame_%05d.png", ANIM_FPS, mp4)
            require(mp4.length() > 0L) { "ffmpeg wrote an empty mp4 for '${example.name}'" }
            return mp4
        } finally {
            framesDir.deleteRecursively()
            renderer.renderList.preTargets.remove(PRE_TARGET_NAME_FEEDBACK)
            pp.modules[MODULE_NAME]?.let { disposeModule(it) }
            pp.modules.clear()
            runCatching { sampler.cleanup() }
            disposeRtt(pp)
        }
    }

    private suspend fun renderOneAnimation(
        renderer: Renderer,
        rtt: RenderToTexture,
        templateBox: Box,
        example: Example,
        outputDir: File,
        ffmpeg: String
    ): File {
        var currentTime = 0f
        val mesh = buildProgramModule(renderer, rtt, templateBox, example.program, animatedFragmentTemplate(), example.name)
        mesh.push.bind("params") { afield.stdlib.vec(currentTime, 0f, 0f, 0f) }
        waitForRendererFrames(renderer, WAIT_FRAMES) // build pipeline + first render

        val framesDir = Files.createTempDirectory("stacksl-anim-${example.name}").toFile()
        try {
            for (i in 0 until ANIM_FRAMES) {
                currentTime = i.toFloat() / ANIM_FPS * example.timeSpeed
                waitForRendererFrames(renderer, ANIM_SETTLE_FRAMES)
                captureTo(renderer, rtt, File(framesDir, "frame_%05d.png".format(i)), example.name)
            }
            val mp4 = File(outputDir, "stacksl-${example.name}.mp4")
            encodeMp4(ffmpeg, framesDir, "frame_%05d.png", ANIM_FPS, mp4)
            require(mp4.length() > 0L) { "ffmpeg wrote an empty mp4 for '${example.name}'" }
            return mp4
        } finally {
            framesDir.deleteRecursively()
        }
    }

    // ── shared plumbing ───────────────────────────────────────────────────────

    private fun newTarget(renderer: Renderer): RenderToTexture = RenderToTexture(renderer) {
        width = OUTPUT_DIM
        height = OUTPUT_DIM
        format = VK_FORMAT_R8G8B8A8_UNORM
        useDepth = false
        colorSampled = true
        colorTransferSrc = true
        clearColor = afield.stdlib.vec(0f, 0f, 0f, 1f)
        debugName = "stacksl-gallery"
    }

    private fun teardown(renderer: Renderer, rtt: RenderToTexture) {
        renderer.renderList.preTargets.remove(PRE_TARGET_NAME)
        rtt.modules[MODULE_NAME]?.let { disposeModule(it) }
        rtt.modules.clear()
        disposeRtt(rtt)
    }

    /**
     * Compile-check [program], expand it through `{{ stack.auto.program }}` into
     * [fragmentTemplate], and (re)register a fullscreen module under one name so it
     * replaces the previous one in the target — the live-coding "modules swap by
     * name" behaviour. Returns the fullscreen [Mesh].
     */
    private fun buildProgramModule(
        renderer: Renderer,
        rtt: RenderToTexture,
        templateBox: Box,
        program: String,
        fragmentTemplate: String,
        label: String,
        source: Pair<HasImageViewArray, TextureSampler>? = null
    ): Mesh {
        // Match the `{{ stack.auto.program }}` expansion, which defaults sample
        // words to the `source` sampler — so sampling (feedback) programs compile.
        val compiled = StackSLCompiler.compile(
            program,
            StackSLCompileOptions(prefix = SLOT, includeHeader = false, declareSamplers = false, sampleTextures = listOf("source"))
        )
        require(compiled.ok) {
            "StackSL '$label' did not compile:\n" +
                compiled.diagnostics.filter { it.severity == StackSLSeverity.ERROR }.joinToString("\n")
        }

        templateBox.stackAutoShaderSources[SLOT] = program
        val fragment = preprocess(templateBox, fragmentTemplate, ShaderKind.FRAGMENT_SHADER)

        val previous = rtt.modules[MODULE_NAME]
        val module = Module(parameters(renderer) {
            name = MODULE_NAME
            vertex = FULLSCREEN_VERTEX
            this.fragment = fragment
            blend = false
            depthTest = false
            rtt.apply()
            if (source != null) {
                val userResolver = textureResolver
                textureResolver = { tex -> if (tex.name == "source") source else userResolver(tex) }
            }
        }).pointer()
        val mesh = Mesh(renderer, module)
        mesh.resize(3, 0, module) // fullscreen triangle, no vertex attributes
        module.current.meshes[MESH_NAME] = mesh

        rtt += MODULE_NAME to module
        previous?.let { if (it !== module) disposeModule(it) }
        return mesh
    }

    private suspend fun captureTo(renderer: Renderer, rtt: RenderToTexture, outputFile: File, label: String) =
        captureImageTo(renderer, rtt.colorImageHandles[0], rtt.colorFormats[0], outputFile, label)

    private suspend fun captureImageTo(renderer: Renderer, vkImage: Long, format: Int, outputFile: File, label: String) {
        val html = TexturePreviewCache.capture(
            renderer = renderer,
            vkImage = vkImage,
            format = format,
            srcWidth = OUTPUT_DIM,
            srcHeight = OUTPUT_DIM,
            currentLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
            maxDim = OUTPUT_DIM
        ) ?: error("could not read back the StackSL gallery texture for '$label'")
        val encoded = DATA_IMAGE_RE.find(html)?.groupValues?.get(1)
            ?: error("could not parse the StackSL gallery texture data for '$label'")
        outputFile.parentFile?.mkdirs()
        outputFile.delete()
        outputFile.writeBytes(Base64.getDecoder().decode(encoded))
        require(outputFile.length() > 0L) { "StackSL gallery readback wrote an empty image for '$label'" }
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

    private fun disposeModule(module: ModulePointer) {
        runCatching {
            module.current.meshes.values.toSet().forEach { mesh ->
                (mesh.v.values + mesh.vn.values).toSet().forEach { it.cleanup() }
                mesh.t?.cleanup()
            }
            module.current.meshes.clear()
            module.current.cleanup()
        }.onFailure { lprintln("error,doc", "stacksl gallery module dispose failed: ${it.message}") }
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
        }.onFailure { lprintln("error,doc", "stacksl gallery RTT dispose failed: ${it.message}") }
    }

    private fun fragmentTemplate(time: Float): String = """
        #version 450
        layout(location = 0) in vec2 fragUV;
        layout(location = 0) out vec4 outColor;

        float gallery_hash12(vec2 p) {
            vec3 p3 = fract(vec3(p.xyx) * 0.1031);
            p3 += dot(p3, p3.yzx + 33.33);
            return fract((p3.x + p3.y) * p3.z);
        }

        {{ stack.auto.program }}

        void main() {
            vec2 uv = fragUV;
            vec4 stackInput = vec4(uv, gallery_hash12(uv), ${"%.4f".format(time)});
            vec3 c = program(stackInput).rgb;
            if (any(isnan(c)) || any(isinf(c))) c = vec3(0.0);
            outColor = vec4(clamp(c, 0.0, 1.0), 1.0);
        }
    """.trimIndent()

    // Same as [fragmentTemplate] but drives `input.w` from a push constant so the
    // shader is built once and animated by updating `pc.params.x` per frame. The
    // block is named `Push` (not `pc`) to avoid glslang's block/instance clash.
    private fun animatedFragmentTemplate(): String = """
        #version 450
        layout(location = 0) in vec2 fragUV;
        layout(location = 0) out vec4 outColor;

        layout(push_constant) uniform Push { vec4 params; } pc;

        float gallery_hash12(vec2 p) {
            vec3 p3 = fract(vec3(p.xyx) * 0.1031);
            p3 += dot(p3, p3.yzx + 33.33);
            return fract((p3.x + p3.y) * p3.z);
        }

        {{ stack.auto.program }}

        void main() {
            vec2 uv = fragUV;
            vec4 stackInput = vec4(uv, gallery_hash12(uv), pc.params.x);
            vec3 c = program(stackInput).rgb;
            if (any(isnan(c)) || any(isinf(c))) c = vec3(0.0);
            outColor = vec4(clamp(c, 0.0, 1.0), 1.0);
        }
    """.trimIndent()

    // Feedback: `source` is the previous ping-pong frame; the program samples it
    // (`sample`, `prev`, `ema`, …) so the image evolves each frame. No push time.
    private fun feedbackFragmentTemplate(): String = """
        #version 450
        layout(location = 0) in vec2 fragUV;
        layout(location = 0) out vec4 outColor;

        layout(set = 0, binding = 0) uniform sampler2D source;
        layout(push_constant) uniform Push { vec4 params; } pc;

        float gallery_hash12(vec2 p) {
            vec3 p3 = fract(vec3(p.xyx) * 0.1031);
            p3 += dot(p3, p3.yzx + 33.33);
            return fract((p3.x + p3.y) * p3.z);
        }

        {{ stack.auto.program }}

        void main() {
            vec2 uv = fragUV;
            vec4 stackInput = vec4(uv, gallery_hash12(uv), pc.params.x);
            vec3 c = program(stackInput).rgb;
            if (any(isnan(c)) || any(isinf(c))) c = vec3(0.0);
            outColor = vec4(clamp(c, 0.0, 1.0), 1.0);
        }
    """.trimIndent()

    private val FULLSCREEN_VERTEX = """
        #version 450
        layout(location = 0) out vec2 fragUV;

        void main() {
            // Fullscreen triangle from gl_VertexIndex, no vertex inputs.
            vec2 pos = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
            gl_Position = vec4(pos * 2.0 - 1.0, 0.0, 1.0);
            fragUV = pos;
        }
    """.trimIndent()
}
