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
import afield.graphics.texture.HasImageView
import afield.graphics.texture.HasImageViewArray
import afield.graphics.texture.PreparesSampledImages
import afield.graphics.texture.TextureSampler
import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.Target
import afield.graphics.vulkan.device.CommandBuffer
import afield.graphics.vulkan.targets.PingPongRenderToTexture
import afield.graphics.vulkan.targets.RenderToTexture
import afield.math.linalg.vec
import afield.runtime.lifecycle.inMain
import afield.ui.output.displayOpen
import afield.ui.visualization.ToHTML
import org.lwjgl.vulkan.VK10.VK_FILTER_LINEAR
import org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_SFLOAT
import org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM
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

    /**
     * How many times a second the program steps. A feedback program's decays are per step
     * (`prev 0.965 *` keeps 96.5% *each frame*), so its look depends on this rate, and the window may
     * draw far faster than a browser does; 60 is what StackTrinket gets in a browser at 60 Hz. 0
     * steps on every frame the window draws.
     */
    var fps: Double = 60.0

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

    /**
     * More pictures the program can read beside `state`, in order: the first is `sample1` /
     * `gather1` / `texelSize1`, the second `sample2`, up to three. Any texture will do — a
     * classmate's WebRTC stream (`webrtcSource`), a camera, another render target. Each is copied,
     * the right way up and at the state's size, once per step, so `0 sample1` reads it at this
     * pixel with y up, exactly as `prev` reads the last frame. See [input].
     */
    val inputs: MutableMap<String, HasImageView> = linkedMapOf()

    /** Add [texture] as the next input, read by `sample1`, `sample2`, …; [name] is its GLSL sampler name. */
    fun input(name: String, texture: HasImageView) {
        require(Regex("[A-Za-z_][A-Za-z0-9_]*").matches(name) && name != StackSLFeedbackShader.STATE) {
            "an input needs a GLSL name other than '${StackSLFeedbackShader.STATE}', got '$name'"
        }
        require(name in inputs || inputs.size < 3) { "at most three inputs: sample1, sample2 and sample3" }
        inputs[name] = texture
    }
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
    private var sinceStep = 0.0
    private var module: ModulePointer? = null
    // One upright copy per input, made the first time the input is used, and kept: nothing frees a target.
    private val copies = linkedMapOf<String, InputCopy>()
    private var pictureCopy: PictureCopy? = null

    /**
     * The picture as Field's other textures keep one: top row first, 8-bit display values (what the
     * window shows, before its display gamma). Hand it to anything that samples a texture —
     * `webrtcPublish(feedback.picture, "stacktrinket")` publishes the program to the class. The state
     * itself is stored bottom row first and in float, which suits `prev` but nothing else. Made the
     * first time it is asked for, and updated on every step after that.
     */
    val picture: HasImageView
        get() = (pictureCopy ?: PictureCopy().also { pictureCopy = it }).target
    private var tried: String? = null
    private var building = false

    override fun toString(): String = "stackFeedback '${settings.name}', ${width}×$height, frame $frame"

    /** Clear both images to black and start time and the frame count again from zero. */
    fun reset() {
        blackFrames = 2
        frame = 0
        seconds = 0.0
        lastNanos = 0L
        sinceStep = 0.0
    }

    /** Put the picture in the window, replacing whatever was shown under the same name. */
    fun show(): ModulePointer {
        val gamma = settings.displayGamma.toFloat()
        // The state is stored with uv.y = 0 in row 0 (see StackSLFeedbackShader.VERTEX); the blit
        // reads rows from the top, so read it bottom-up to put y up in the window.
        val blit = target.blitToScreen(name = settings.name, viewport = settings.viewport, sourceRect = Rect(0.0, 1.0, 1.0, -1.0)) {
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
        // Step at most settings.fps times a second; frames in between keep showing the last step.
        sinceStep += dt
        val interval = if (settings.fps > 0) 1.0 / settings.fps else 0.0
        if (blackFrames == 0 && sinceStep < interval) return
        val stepDt = if (interval > 0) interval else dt
        sinceStep = if (interval > 0) (sinceStep - interval).coerceIn(0.0, interval) else 0.0
        pushedFrame = if (blackFrames > 0) -1 else frame
        settings.inputs.keys.forEach { copies[it]?.target?.render(command, index) }
        target.render(command, index)
        pictureCopy?.target?.render(command, index)
        if (blackFrames > 0) {
            blackFrames--
        } else {
            frame++
            seconds += stepDt * settings.timeScale
        }
    }

    // What this frame pushes: -1 while clearing, otherwise the frame number.
    private var pushedFrame: Long = -1

    /** Compile a new program off the frame, on the main loop; the old one draws until it is ready. */
    private fun follow() {
        val program = source()
        val names = settings.inputs.keys.toList()
        // A new set of inputs is a new shader too: its samplers are part of the compiled program.
        val wanted = program + "\u0000" + names.joinToString(",")
        if (wanted == tried || building) return
        building = true
        inMain {
            try {
                rebuild(program, names)
            } finally {
                tried = wanted
                building = false
            }
        }
    }

    private fun rebuild(program: String, inputs: List<String>) {
        val compiled = StackSLCompiler.compile(program, StackSLFeedbackShader.compileOptions(inputs))
        if (!compiled.ok) {
            publish(StackSLLowering(program, compiled, glslError = null, running = running != null))
            return
        }
        var error: String? = null
        val fresh = Module(parameters(renderer) {
            +target
            name = "${settings.name}-program"
            vertex = StackSLFeedbackShader.VERTEX
            fragment = StackSLFeedbackShader.fragment(compiled.glsl, inputs)
            blend = false
            depthTest = false
            depthWrite = false
            errorHandler = { message, _ -> error = message }
            textureResolver = { tex ->
                when (tex.name) {
                    StackSLFeedbackShader.STATE -> fromViews(listOf(target.otherImageView), tex.name, sampler)
                    in inputs -> fromViews(listOf(copyOf(tex.name).target), tex.name, sampler)
                    else -> null
                }
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

    /** The upright copy of input [name], made on first use. */
    private fun copyOf(name: String): InputCopy = copies.getOrPut(name) { InputCopy(name) }

    /**
     * Input [name] copied into a float image the size of the state, stored the way the state is (row 0
     * is `uv.y = 0`). Field's textures — a stream, a camera, a render target — keep their top row
     * first, so sampled as they are at `uv` they would read upside down next to `state`.
     */
    private inner class InputCopy(val name: String) {
        val target = RenderToTexture(renderer) {
            width = this@StackSLFeedback.width
            height = this@StackSLFeedback.height
            format = VK_FORMAT_R16G16B16A16_SFLOAT
            colorFormats = listOf(VK_FORMAT_R16G16B16A16_SFLOAT)
            useDepth = false
            generateColorMipmaps = false
            debugName = "StackSLFeedback ${settings.name} input $name"
        }
        // Reads whatever settings.inputs holds under this name now, so a re-run can swap the texture.
        private val live = object : HasImageViewArray, PreparesSampledImages {
            override val imageViewArray: List<HasImageView>
                get() = listOfNotNull(settings.inputs[name])

            override fun prepareSampledImagesFor(command: CommandBuffer) {
                (settings.inputs[name] as? PreparesSampledImages)?.prepareSampledImagesFor(command)
            }
        }

        init {
            val copy = Module(parameters(renderer) {
                +target
                this.name = "${settings.name}-input-$name"
                vertex = StackSLFeedbackShader.VERTEX
                fragment = StackSLFeedbackShader.COPY_FRAGMENT
                blend = false
                depthTest = false
                depthWrite = false
                textureResolver = { tex -> if (tex.name == "source") fromViews(live, tex.name, sampler) else null }
            }).pointer()
            copy += "${settings.name}-input-$name-fullscreen" to fullscreenTriangle(copy).apply {
                push.bind("encode") { if (settings.inputs[name]?.let(::isSrgb) == true) 1f else 0f }
            }
            target += "copy" to copy
        }
    }

    /** [picture]: the newest state, copied top row first into 8-bit display values. */
    private inner class PictureCopy {
        val target = RenderToTexture(renderer) {
            width = this@StackSLFeedback.width
            height = this@StackSLFeedback.height
            format = VK_FORMAT_R8G8B8A8_UNORM
            colorFormats = listOf(VK_FORMAT_R8G8B8A8_UNORM)
            useDepth = false
            generateColorMipmaps = false
            debugName = "StackSLFeedback ${settings.name} picture"
        }

        init {
            val copy = Module(parameters(renderer) {
                +target
                this.name = "${settings.name}-picture"
                // The usual fullscreen triangle puts uv.y = 1 in row 0: top row first.
                vertex = StackSLFeedbackShader.PICTURE_VERTEX
                fragment = StackSLFeedbackShader.PICTURE_FRAGMENT
                blend = false
                depthTest = false
                depthWrite = false
                textureResolver = { tex ->
                    if (tex.name == StackSLFeedbackShader.STATE) fromViews(listOf(this@StackSLFeedback.target.latestImageView), tex.name, sampler) else null
                }
            }).pointer()
            copy += "${settings.name}-picture-fullscreen" to fullscreenTriangle(copy)
            target += "copy" to copy
        }
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
    val compileOptions: StackSLCompileOptions = compileOptions(emptyList())

    /** [compileOptions] with [inputs] as the textures `sample1`, `sample2`, … read. */
    fun compileOptions(inputs: List<String>) = StackSLCompileOptions(
        prefix = PREFIX,
        sampleTextures = listOf(STATE) + inputs,
        declareSamplers = false,
        externalizeConstants = true,
    )

    /** The fullscreen triangle as Field draws one elsewhere: `fragUV.y = 1` lands in row 0. */
    val PICTURE_VERTEX: String = """
        #version 450
        layout(location = 0) out vec2 fragUV;

        void main() {
            vec2 pos = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
            gl_Position = vec4(pos * 2.0 - 1.0, 0.0, 1.0);
            fragUV = pos;
        }
    """.trimIndent()

    /** Reads the state at `fragUV`; drawn with [PICTURE_VERTEX] it comes out top row first. */
    val PICTURE_FRAGMENT: String = """
        #version 450
        layout(location = 0) in vec2 fragUV;
        layout(location = 0) out vec4 outColor;
        layout(set = 0, binding = 0) uniform sampler2D $STATE;

        void main() {
            outColor = vec4(clamp(texture($STATE, fragUV).rgb, 0.0, 1.0), 1.0);
        }
    """.trimIndent()

    /**
     * Copies an input texture into an image stored like the state: drawn with [VERTEX], it reads
     * `source` with v flipped, because Field's textures keep their top row first. With `encode` set
     * (an sRGB source, such as a WebRTC stream's browser paints) it re-encodes what sampling decoded,
     * so the program reads the numbers the picture holds — the values a browser ShaderTrinket's
     * `iChannel` gives, and the space `state` is in. Without it a stream reads darker, by the gamma.
     */
    val COPY_FRAGMENT: String = """
        #version 450
        layout(location = 0) in vec2 fragUV;
        layout(location = 0) out vec4 outColor;
        layout(set = 0, binding = 0) uniform sampler2D source;
        layout(push_constant) uniform Push { float encode; } pc;

        vec3 srgbEncode(vec3 c) {
            c = clamp(c, 0.0, 1.0);
            return mix(c * 12.92, 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055, step(vec3(0.0031308), c));
        }

        void main() {
            vec4 c = texture(source, vec2(fragUV.x, 1.0 - fragUV.y));
            if (pc.encode > 0.5) c.rgb = srgbEncode(c.rgb);
            outColor = c;
        }
    """.trimIndent()

    /**
     * The fullscreen-triangle vertex shader: `fragUV` runs 0..1 with y up, and the image is written
     * so that image row 0 holds `uv.y = 0`. That is the row `texture(state, vec2(u, 0))` reads, so
     * `prev` reads back this pixel. Field's render targets put clip `y = +1` in row 0, so the usual
     * `gl_Position = pos * 2 - 1` would store the picture upside down relative to its own sampling
     * and every feedback step would flip it; [StackSLFeedback.show] flips it back for the window.
     */
    val VERTEX: String = """
        #version 450
        layout(location = 0) out vec2 fragUV;

        void main() {
            vec2 pos = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
            gl_Position = vec4(pos.x * 2.0 - 1.0, 1.0 - pos.y * 2.0, 0.0, 1.0);
            fragUV = pos;
        }
    """.trimIndent()

    /**
     * The fragment shader around [lowered], the GLSL the compiler made for a program. `params` is
     * `(seconds, frame, width, height)`; a negative frame draws black, which is how the state is
     * cleared. The hash is StackTrinket's, so a program's grain looks as it does there.
     */
    fun fragment(lowered: String, inputs: List<String> = emptyList()): String = """
        |#version 450
        |layout(location = 0) in vec2 fragUV;
        |layout(location = 0) out vec4 outColor;
        |
        |layout(set = 0, binding = 0) uniform sampler2D $STATE;
        |${inputs.mapIndexed { i, name -> "layout(set = 0, binding = ${i + 1}) uniform sampler2D $name;" }.joinToString("\n|")}
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
 * column; [glslHtml] is the lowered GLSL line by line. It is a [ToHTML], so `display("lowering", it)`
 * draws the summary.
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
        // Fixed width: an output panel sizes itself to its content.
        append("<div style='width:500px;max-width:500px;overflow:hidden;font-family:var(--field-mono, monospace);font-size:11px;line-height:1.45;color:#ccc'>")
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
        append("<div style='color:#888'>lowered GLSL: ${result.glsl.trimEnd().lines().size} lines, in the <i>glsl</i> panel</div>")
        append("</div>")
    }

    /**
     * The numbered GLSL as HTML, scrolling inside a fixed 500 by 300 box. It is a panel of its own
     * ([STACKSL_GLSL_CHANNEL]): a panel is sized to all of its content, folded or not, so inside the
     * summary it held the summary open at full height.
     */
    val glslHtml: String
        get() = "<pre style='margin:0;width:500px;height:300px;overflow:auto;white-space:pre;font-size:11px;line-height:1.4;color:#aaa'>${esc(numberedGlsl)}</pre>"

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "&#39;")
}

private val SRGB_FORMATS = setOf(
    org.lwjgl.vulkan.VK10.VK_FORMAT_B8G8R8A8_SRGB,
    org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_SRGB,
    org.lwjgl.vulkan.VK10.VK_FORMAT_A8B8G8R8_SRGB_PACK32,
)

/** Whether sampling [texture] decodes sRGB, by its view's format. */
private fun isSrgb(texture: HasImageView): Boolean =
    runCatching { texture.imageView.imageViewData.format in SRGB_FORMATS }.getOrDefault(false)

/** The output channel [Box.stackFeedback] shows its [StackSLLowering] in. */
const val STACKSL_LOWERING_CHANNEL = "lowering"

/** The output channel [Box.stackFeedback] shows the lowered GLSL in; it starts closed. */
const val STACKSL_GLSL_CHANNEL = "glsl"

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
 * frame that it compiles, without clearing the picture. After every compile the box's `lowering`
 * output shows the stack depth, helpers, wires and diagnostics, and its `glsl` output (closed until
 * opened) the lowered GLSL.
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
            box.display(STACKSL_GLSL_CHANNEL, l.glslHtml)
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
                this.settings.inputs.clear()
                this.settings.inputs.putAll(settings.inputs)
                this.settings.fps = settings.fps
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
