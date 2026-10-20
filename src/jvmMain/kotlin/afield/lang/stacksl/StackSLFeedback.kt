@file:afield.runtime.completion.CompletionCategory("Graphics")

package afield.lang.stacksl

/**
 * StackSL with its own previous frame: [Box.stackFeedback] draws a box's `stackSL` program
 * fullscreen every frame into a float ping-pong target, with the last frame bound as
 * `sampler2D state`, so `prev`, `sample`, `gather` and the store words (`0->` … `3->`) read what the
 * program drew a frame ago. [StackSLFeedback] is the running host; [StackSLLowering] is the panel it
 * shows in the box's output — the lowered GLSL, its helpers, wires, stack depth and diagnostics.
 * `StackSLFeedbackTest` covers the shader it builds and the panel.
 */

import afield.app.protocol.Rect
import afield.document.context.fromContext
import afield.document.graph.Box
import afield.graphics.render.Module
import afield.graphics.render.ModulePointer
import afield.graphics.render.fullscreenTriangle
import afield.graphics.render.parameters
import afield.graphics.render.pointer
import afield.graphics.shader.fromViews
import afield.graphics.texture.TextureSampler
import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.Target
import afield.graphics.vulkan.device.CommandBuffer
import afield.graphics.vulkan.targets.PingPongRenderToTexture
import afield.math.linalg.vec
import afield.runtime.lifecycle.inMain
import afield.ui.output.displayOpen
import afield.ui.visualization.ToHTML
import org.lwjgl.vulkan.VK10.VK_FILTER_LINEAR
import org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_SFLOAT
import org.lwjgl.vulkan.VK10.VK_SAMPLER_ADDRESS_MODE_REPEAT
import java.util.WeakHashMap

/** Settings for a [StackSLFeedback]. All but the size can change while it runs. */
class StackSLFeedbackSettings {
    /**
     * Names what the host puts in the window. Every feedback box in a document uses the same name by
     * default, so launching one replaces whichever was showing — the boxes work as a preset menu.
     */
    var name: String = "stacksl-feedback"

    /** The state's size in pixels; 0 takes the window's. Fixed once the host is built. */
    var width: Int = 0
    var height: Int = 0

    /** Seconds of program time per second of wall time — what `time` and `0.1t` count in. */
    var timeScale: Double = 1.0

    /** Hold the picture: no frame is drawn and time stops until this is false again. */
    var paused: Boolean = false

    /**
     * The exponent the window applies to what the program drew. A browser shows a WebGL canvas's
     * numbers as sRGB, and Field's window expects linear light, so 2.2 shows a program the way the
     * StackTrinket page and the StackSL gallery show it. The state itself is never changed by this.
     */
    var displayGamma: Double = 2.2

    /** Show the [StackSLLowering] panel in the box's `lowering` output channel. */
    var showLowering: Boolean = true

    /** Where in the window the picture goes; null fills it. */
    var viewport: Rect? = null
}

/**
 * A StackSL program running against its own previous frame — the StackTrinket renderer.
 *
 * Each frame draws `program(vec4(uv, hash, seconds))` over a whole float16 image while the image
 * drawn the frame before is bound as `sampler2D state` (linear, repeating), then shows the new one.
 * Every channel, alpha included, is clamped to 0..1 and kept: four independent memories per pixel.
 * `hash` is a fresh per-pixel value every frame, `uv` runs 0..1 with y up, and `seconds` counts from
 * the last [reset] at [StackSLFeedbackSettings.timeScale].
 *
 * The program is read from [source] every frame. A changed program that compiles replaces the
 * running one without touching the state; one that does not keeps the last good picture and its
 * errors show in [lowering]. Build one with [Box.stackFeedback].
 */
class StackSLFeedback internal constructor(
    val renderer: Renderer,
    val settings: StackSLFeedbackSettings,
    /** Where the program comes from; read every frame. */
    var source: () -> String,
    /** Told about every compile, good or bad. */
    private val onCompiled: (StackSLLowering) -> Unit,
) : Target {

    /** Width of the state in pixels. */
    val width: Int = settings.width.takeIf { it > 0 } ?: renderer.swapChain.swapChainExtent.width()

    /** Height of the state in pixels. */
    val height: Int = settings.height.takeIf { it > 0 } ?: renderer.swapChain.swapChainExtent.height()

    /** The two images: one is drawn while the other is `state`. */
    val target = PingPongRenderToTexture(renderer) {
        width = this@StackSLFeedback.width
        height = this@StackSLFeedback.height
        format = VK_FORMAT_R16G16B16A16_SFLOAT
        colorFormats = listOf(VK_FORMAT_R16G16B16A16_SFLOAT)
        clearColor = vec(0, 0, 0, 1)
        useDepth = false
        generateColorMipmaps = false
        debugName = "StackSLFeedback ${settings.name}"
    }

    private val sampler = TextureSampler(
        renderer, 1, false, VK_FILTER_LINEAR,
        addressModeU = VK_SAMPLER_ADDRESS_MODE_REPEAT,
        addressModeV = VK_SAMPLER_ADDRESS_MODE_REPEAT,
        addressModeW = VK_SAMPLER_ADDRESS_MODE_REPEAT
    )

    /** The last compile, good or bad. */
    @Volatile
    var lowering: StackSLLowering? = null
        private set

    /** The program the running pipeline was built from. */
    @Volatile
    var running: String? = null
        private set

    /** Frames drawn since the last [reset]. */
    var frame: Long = 0
        private set

    /** Program time, in seconds, since the last [reset]. */
    var seconds: Double = 0.0
        private set

    // Two frames of black before frame 0, one into each image, so frame 0 reads a cleared state.
    private var blackFrames = 2
    private var lastNanos = 0L
    private var module: ModulePointer? = null
    private var tried: String? = null
    private var building = false

    /** Clear both images to black and start time and the frame count again from zero. */
    fun reset() {
        blackFrames = 2
        frame = 0
        seconds = 0.0
        lastNanos = 0L
    }

    /** Put the picture in the window, replacing whatever was shown under the same name. */
    fun show(): ModulePointer {
        val gamma = settings.displayGamma.toFloat()
        val blit = target.blitToScreen(name = settings.name, viewport = settings.viewport) {
            this.gamma = vec(gamma, gamma, gamma, 1f)
        }
        // blitToScreen installs the ping-pong itself as the source; this host is what must run, so
        // that pausing, the clear and the program swap happen around its frames.
        renderer.renderList.preTargets["${settings.name}-source"] = this
        return blit
    }

    override fun render(command: CommandBuffer, index: Int) {
        val now = System.nanoTime()
        val dt = if (lastNanos == 0L) 0.0 else (now - lastNanos) / 1e9
        lastNanos = now
        follow()
        if (settings.paused || module == null) return
        pushedFrame = if (blackFrames > 0) -1 else frame
        target.render(command, index)
        if (blackFrames > 0) {
            blackFrames--
        } else {
            frame++
            seconds += dt * settings.timeScale
        }
    }

    // What this frame pushes: -1 while clearing, otherwise the frame number.
    private var pushedFrame: Long = -1

    /** Compile a new program off the frame, on the main loop; the old one draws until it is ready. */
    private fun follow() {
        val wanted = source()
        if (wanted == tried || building) return
        building = true
        inMain {
            try {
                rebuild(wanted)
            } finally {
                tried = wanted
                building = false
            }
        }
    }

    private fun rebuild(program: String) {
        val compiled = StackSLCompiler.compile(program, StackSLFeedbackShader.compileOptions)
        if (!compiled.ok) {
            publish(StackSLLowering(program, compiled, glslError = null, running = running != null))
            return
        }
        var error: String? = null
        val fresh = Module(parameters(renderer) {
            target.apply()
            name = "${settings.name}-program"
            vertex = StackSLFeedbackShader.VERTEX
            fragment = StackSLFeedbackShader.fragment(compiled.glsl)
            blend = false
            depthTest = false
            depthWrite = false
            errorHandler = { message, _ -> error = message }
            textureResolver = { tex ->
                if (tex.name == StackSLFeedbackShader.STATE) fromViews(listOf(target.otherImageView), tex.name, sampler) else null
            }
        }).pointer()
        if (!fresh.current.valid) {
            renderer.cleanup.add(Runnable { dispose(fresh) })
            publish(StackSLLowering(program, compiled, glslError = error ?: "the GPU refused the shader", running = running != null))
            return
        }
        fresh += "${settings.name}-fullscreen" to fullscreenTriangle(fresh).apply {
            push.bind("params") { vec(seconds.toFloat(), pushedFrame.toFloat(), width.toFloat(), height.toFloat()) }
        }
        val previous = module
        target += "program" to fresh
        module = fresh
        running = program
        // A frame in flight may still be using the old pipeline: retire it once those frames finish.
        previous?.let { old -> renderer.cleanup.add(Runnable { dispose(old) }) }
        publish(StackSLLowering(program, compiled, glslError = null, running = true))
    }

    private fun publish(l: StackSLLowering) {
        lowering = l
        onCompiled(l)
    }

    private fun dispose(module: ModulePointer) {
        runCatching {
            module.current.meshes.values.toSet().forEach { mesh ->
                (mesh.v.values + mesh.vn.values).toSet().forEach { it.cleanup() }
                mesh.t?.cleanup()
            }
            module.current.meshes.clear()
            module.current.cleanup()
        }
    }
}

/**
 * The shaders a [StackSLFeedback] draws with: a fullscreen triangle, and a fragment shader that
 * hands the compiled program `vec4(uv, hash, seconds)` with the previous frame bound as [STATE].
 */
object StackSLFeedbackShader {
    /** The sampler the previous frame is bound as, and the texture StackSL's sample words read. */
    const val STATE = "state"

    /** The GLSL name of the compiled program. */
    const val PREFIX = "program"

    /** How a feedback program is compiled: sampling [STATE], with its numbers as dangling wires. */
    val compileOptions = StackSLCompileOptions(
        prefix = PREFIX,
        sampleTextures = listOf(STATE),
        declareSamplers = false,
        externalizeConstants = true,
    )

    /** The fullscreen-triangle vertex shader: `fragUV` runs 0..1 with y up. */
    val VERTEX: String = """
        #version 450
        layout(location = 0) out vec2 fragUV;

        void main() {
            vec2 pos = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
            gl_Position = vec4(pos * 2.0 - 1.0, 0.0, 1.0);
            fragUV = pos;
        }
    """.trimIndent()

    /**
     * The fragment shader around [lowered], the GLSL the compiler made for a program. `params` is
     * `(seconds, frame, width, height)`; a negative frame draws black, which is how the state is
     * cleared. The hash is StackTrinket's, so a program's grain looks as it does there.
     */
    fun fragment(lowered: String): String = """
        |#version 450
        |layout(location = 0) in vec2 fragUV;
        |layout(location = 0) out vec4 outColor;
        |
        |layout(set = 0, binding = 0) uniform sampler2D $STATE;
        |layout(push_constant) uniform Push { vec4 params; } pc;
        |
        |${lowered.trim()}
        |
        |void main() {
        |    if (pc.params.y < 0.0) { outColor = vec4(0.0, 0.0, 0.0, 1.0); return; }
        |    vec2 uv = fragUV;
        |    vec2 resolution = max(pc.params.zw, vec2(1.0));
        |    float hash = fract(sin(dot(uv * resolution + pc.params.y, vec2(12.9898, 78.233))) * 43758.5453);
        |    vec4 c = $PREFIX(vec4(uv, hash, pc.params.x));
        |    if (any(isnan(c)) || any(isinf(c))) c = vec4(0.0);
        |    outColor = clamp(c, 0.0, 1.0);
        |}
    """.trimMargin()
}

/**
 * What one compile of a feedback program came to, as StackTrinket's lowering panel shows it: whether
 * it is drawing, the stack depth with the leftover values under the returned top, the helpers it
 * pulled in, how many dangling wires (tunable numbers) it has, each diagnostic at its line and
 * column, and the lowered GLSL line by line. It is a [ToHTML], so `display("lowering", it)` draws it.
 */
class StackSLLowering(
    val source: String,
    val result: StackSLCompileResult,
    /** Why the GPU refused GLSL the compiler accepted, or null. */
    val glslError: String?,
    /** Whether a program is drawing — this one if it compiled, otherwise the last good one. */
    val running: Boolean,
) : ToHTML {
    /** The program compiled and the GPU took it. */
    val ok: Boolean get() = result.ok && glslError == null

    /** How many values the program leaves on the stack; the top is what is drawn. */
    val depth: Int get() = root?.finalStackDepth ?: 0

    private val root: StackSLFunctionAnalysis?
        get() = result.analysis.functions.firstOrNull { it.name == "<root>" } ?: result.analysis.functions.lastOrNull()

    /** StackTrinket's one-line reading of [depth]. */
    val guidance: String
        get() {
            val leftovers = (depth - 1).coerceAtLeast(0)
            return "stack depth $depth after compile; $leftovers leftover ${if (leftovers == 1) "value" else "values"} under the returned top."
        }

    /** The lowered GLSL with three-digit line numbers, as the panel shows it. */
    val numberedGlsl: String
        get() = result.glsl.trimEnd().lines().mapIndexed { i, line -> "${(i + 1).toString().padStart(3)} | $line" }.joinToString("\n")

    override fun toHTML(): String = buildString {
        val status = when {
            ok -> "<span style='color:#9c9'>drawing</span>"
            running -> "<span style='color:#e96'>not compiled — the last good program is still drawing</span>"
            else -> "<span style='color:#e96'>not compiled</span>"
        }
        append("<div style='font-family:var(--field-mono, monospace);font-size:11px;line-height:1.45;color:#ccc'>")
        append("<div>$status · ${esc(guidance)}</div>")
        append("<div style='color:#888'>helpers ${esc(result.analysis.requiredHelpers.sorted().joinToString(", ").ifEmpty { "none" })}")
        append(" · wires ${result.analysis.danglingWires.size} · stack $depth/${(depth - 1).coerceAtLeast(0)}</div>")
        result.diagnostics.forEach { d ->
            val colour = if (d.severity == StackSLSeverity.ERROR) "#e96" else "#db6"
            val where = d.location?.let { "${it.line}:${it.column} " } ?: ""
            val near = d.token?.let { " near '${esc(it)}'" } ?: ""
            append("<div style='color:$colour'>${d.severity.name.lowercase()} $where${esc(d.message)}$near</div>")
        }
        glslError?.let { append("<div style='color:#e96'>GPU: ${esc(it)}</div>") }
        append("<details><summary style='color:#888;cursor:pointer'>lowered GLSL, ${result.glsl.trimEnd().lines().size} lines</summary>")
        append("<pre style='margin:4px 0 0;max-height:420px;overflow:auto;color:#aaa'>${esc(numberedGlsl)}</pre></details>")
        append("</div>")
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "&#39;")
}

/** The output channel [Box.stackFeedback] shows its [StackSLLowering] in. */
const val STACKSL_LOWERING_CHANNEL = "lowering"

private val feedbackHosts = WeakHashMap<Box, StackSLFeedback>()

/**
 * Draw this box's StackSL fullscreen with its previous frame as `state`: the StackTrinket renderer
 * in one call. The box needs a renderer above it, and its program in the `stackSL` property.
 *
 * ```
 * // stackSL:  prev 0.965 *  p length 0.32 0.05 pulse  0.08t palette  *  +
 * stackFeedback()
 * ```
 *
 * The program is followed while it runs: edit `stackSL` and the new program takes over on the next
 * frame that it compiles, without clearing the picture. The box's `lowering` output shows the
 * lowered GLSL, stack depth, wires and diagnostics after every compile.
 *
 * Calling this again — re-running the box — reuses the same images (nothing frees a render target,
 * so a fresh pair every run would leak) and, with [reset], clears them, so a run starts from black.
 * Every feedback box shows under one name unless [StackSLFeedbackSettings.name] says otherwise, so
 * launching one replaces the last. [configure] sets [StackSLFeedbackSettings]; the size settings only
 * take effect the first time, or when they change.
 */
fun Box.stackFeedback(
    renderer: Renderer = fromContext(),
    reset: Boolean = true,
    configure: StackSLFeedbackSettings.() -> Unit = {},
): StackSLFeedback {
    val box = this
    val settings = StackSLFeedbackSettings().apply(configure)
    val showLowering: (StackSLLowering) -> Unit = { l ->
        if (settings.showLowering) {
            box.display(STACKSL_LOWERING_CHANNEL, l)
            box.displayOpen(STACKSL_LOWERING_CHANNEL)
        }
    }
    val host = synchronized(feedbackHosts) {
        val existing = feedbackHosts[box]
        val reusable = existing != null && existing.renderer === renderer &&
            (settings.width <= 0 || settings.width == existing.width) &&
            (settings.height <= 0 || settings.height == existing.height) &&
            existing.settings.name == settings.name
        if (reusable) {
            existing!!.apply {
                this.settings.timeScale = settings.timeScale
                this.settings.paused = settings.paused
                this.settings.displayGamma = settings.displayGamma
                this.settings.showLowering = settings.showLowering
                this.settings.viewport = settings.viewport
            }
        } else {
            StackSLFeedback(renderer, settings, { box.stackSL ?: "" }, showLowering).also { feedbackHosts[box] = it }
        }
    }
    if (reset) host.reset()
    host.lowering?.let(showLowering)
    host.show()
    return host
}
