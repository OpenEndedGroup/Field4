package afield.stacksl.cpu

import afield.stacksl.StackSLCompileOptions
import afield.stacksl.StackSLCompileResult
import afield.stacksl.StackSLCompiler
import afield.stacksl.StackSLSeverity
import afield.util.PngEncoder
import java.util.stream.IntStream

// ---------------------------------------------------------------------------------------------
// StackSL's CPU backend: the public surface.
//
// WHAT THIS IS FOR. Until now StackSL could only be *run* on a GPU. Every claim about what a
// StackSL word does was therefore either checked by eye against a screenshot, or checked against
// a hand-transliteration of the emitted GLSL — and a hand-transliteration is how a committed
// volumetric-noise figure came to illustrate GLSL behaviour without any GLSL having run. This
// backend closes that: it takes the emitter's real output and evaluates it, so a StackSL program
// can be rendered, unit-tested, diffed and documented with no graphics context at all.
//
// WHAT IT IS NOT. It is not a fast renderer and it is not bit-exact with a GPU. It is a
// *reference*: roughly 10^5 pixel-shader invocations per second per core against a GPU's 10^9,
// and agreement bounded by GLSL's own promises (see `GlslValue.kt` for why `sin` cannot agree
// even in principle). What it is exact about is the *program*: it reads the same text the GPU
// reads, so it cannot disagree with the emitter about what a word compiles to.
// ---------------------------------------------------------------------------------------------

/**
 * A rendered raster: RGBA floats in row-major order, row 0 at the **top**, matching both a PNG
 * and the framebuffer convention the gallery harness reads back under (see
 * [StackSLCpu.FRAG_UV_ORIGIN_IS_TOP_LEFT]).
 */
class CpuRaster(val width: Int, val height: Int, val rgba: FloatArray) {
    init {
        require(rgba.size == width * height * 4) { "raster is ${rgba.size} floats, expected ${width * height * 4}" }
    }

    /** Component [channel] (0..3) of the pixel at [x], [y]. */
    fun at(x: Int, y: Int, channel: Int): Float = rgba[((y * width) + x) * 4 + channel]

    /**
     * Quantise to 8-bit ARGB the way a `VK_FORMAT_R8G8B8A8_UNORM` attachment does: clamp to
     * `[0,1]` then **round** to nearest, which is what the Vulkan spec requires for a float to
     * unorm conversion. Truncating instead would bias every channel down by half a level and
     * turn an exact match into a one-level miss across the whole image.
     */
    fun toArgb(): IntArray = IntArray(width * height) { i ->
        val r = quantise(rgba[i * 4])
        val g = quantise(rgba[i * 4 + 1])
        val b = quantise(rgba[i * 4 + 2])
        val a = quantise(rgba[i * 4 + 3])
        (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    fun toPngBytes(): ByteArray = PngEncoder.encode(toArgb(), width, height)

    private fun quantise(v: Float): Int = Math.round(v.coerceIn(0f, 1f) * 255f)
}

/**
 * Render StackSL on the CPU.
 *
 * The entry points come in two layers, and the layering is the point:
 *
 *  - [renderFragment] takes a **complete GLSL fragment shader as text** and runs its `main()`
 *    once per pixel. It knows nothing about StackSL.
 *  - [expandStackAuto] and [renderProgram] add the StackSL half: compile a program with
 *    `StackSLCompiler`, substitute it into a `{{ stack.auto.<slot> }}` template, and hand the
 *    result to [renderFragment].
 *
 * So the CPU path and the GPU path receive the *same string*. Nothing about a word's behaviour is
 * restated here; if the emitter changes what `fbm` compiles to, this backend changes with it on
 * the next call, with no edit.
 */
object StackSLCpu {

    /**
     * Which framebuffer row `fragUV.y == 0` lands on, for the gallery harness's fullscreen
     * triangle.
     *
     * The vertex shader is `gl_Position = vec4(pos * 2.0 - 1.0, 0, 1); fragUV = pos;` with
     * `pos` in `{0, 2}`. In Vulkan, clip-space `y = -1` is the **top** of the framebuffer, so
     * `fragUV.y = 0` is the top row and `fragUV.y` grows downwards — the same direction a PNG's
     * rows run. This constant exists so the convention is stated once and asserted, rather than
     * being an off-by-a-flip that only shows up as an upside-down image in one of 31 comparisons.
     */
    const val FRAG_UV_ORIGIN_IS_TOP_LEFT = true

    /**
     * The compile options a `{{ stack.auto.<slot> }}` expansion uses.
     *
     * These are a deliberate, literal copy of the ones in
     * `afield.stdlib.shaders.compileStackAutoShaderFunctionReporting`, which is the production
     * expansion path. They cannot be *shared* without this package depending on
     * `afield.stdlib.shaders`, and that file drags in the whole `afield.four` graphics stack —
     * which would defeat the purpose of a backend that runs with no graphics context. So the two
     * copies are pinned against each other by a source-text drift guard in the tests
     * (`StackSLCpuExpansionTest`) rather than by the compiler.
     */
    fun stackAutoOptions(slot: String, sampleTexture: String = "source"): StackSLCompileOptions =
        StackSLCompileOptions(
            prefix = slot,
            entrypoint = "main",
            useEntrypointAsRoot = false,
            tolerant = false,
            includeHeader = false,
            declareSamplers = false,
            externalizeConstants = false,
            sampleTextures = listOf(sampleTexture)
        )

    /** Compile [program] exactly as a `stack.auto` slot named [slot] would. */
    fun compile(program: String, slot: String = "program", sampleTexture: String = "source"): StackSLCompileResult =
        StackSLCompiler.compile(program, stackAutoOptions(slot, sampleTexture))

    /**
     * Substitute [program]'s compiled GLSL into [template] at `{{ stack.auto.<slot> }}`.
     *
     * Throws with the compiler's own error text if the program does not compile, which is what
     * the production path does too — a shader built around a broken program is not a thing worth
     * rendering.
     */
    fun expandStackAuto(
        template: String,
        program: String,
        slot: String = "program",
        sampleTexture: String = "source"
    ): String {
        val result = compile(program, slot, sampleTexture)
        if (!result.ok) {
            val errors = result.diagnostics.filter { it.severity == StackSLSeverity.ERROR }
            throw IllegalArgumentException(
                "StackSL program does not compile:\n" + errors.joinToString("\n") { it.toString() }
            )
        }
        val tag = Regex("""\{\{\s*stack\.auto\.${Regex.escape(slot)}\s*}}""")
        if (!tag.containsMatchIn(template)) {
            throw IllegalArgumentException("template has no {{ stack.auto.$slot }} slot")
        }
        // The lambda overload of `replace` treats its result literally, so the emitted GLSL goes
        // in byte for byte; the `String` overload would interpret `$` and `\` inside it.
        return tag.replace(template) { result.glsl }
    }

    /**
     * Run a whole fragment shader over a [width] by [height] raster.
     *
     * [uniforms] are set once; [perPixel] can add or override globals for each pixel and is
     * given the pixel's integer coordinates. `fragUV` is supplied automatically at pixel centres
     * — `(x + 0.5) / width`, `(y + 0.5) / height` — which is where a rasteriser evaluates a
     * fullscreen triangle's interpolants, and `y` grows downwards
     * ([FRAG_UV_ORIGIN_IS_TOP_LEFT]).
     *
     * Rows are rendered in parallel with one [GlslInvocation] each, because an invocation holds
     * mutable frames and globals and is emphatically not thread-safe. The output is a plain
     * array indexed by pixel, so there is no contention.
     *
     * @param outName the `out` variable to read the pixel from; the harness templates write `outColor`
     * @param onDiscard what to store for a pixel whose shader executed `discard`
     */
    fun renderFragment(
        source: String,
        width: Int,
        height: Int,
        uniforms: Map<String, GlslValue> = emptyMap(),
        samplers: GlslSamplers = GlslSamplers.REFUSE,
        outName: String = "outColor",
        fragUvName: String = "fragUV",
        parallel: Boolean = true,
        perPixel: ((x: Int, y: Int, invocation: GlslInvocation) -> Unit)? = null,
        onDiscard: FloatArray = floatArrayOf(0f, 0f, 0f, 0f)
    ): CpuRaster {
        val shader = parseGlslShader(source)
        require("main" in shader.functionNames) { "fragment shader has no main()" }
        val rgba = FloatArray(width * height * 4)

        val rows = IntStream.range(0, height)
        val stream = if (parallel) rows.parallel() else rows
        stream.forEach { y ->
            val invocation = shader.newInvocation(uniforms, samplers)
            val v = (y + 0.5f) / height
            for (x in 0 until width) {
                invocation.setGlobal(fragUvName, GlslValue.vec2((x + 0.5f) / width, v))
                perPixel?.invoke(x, y, invocation)
                val ok = invocation.runMain()
                val base = (y * width + x) * 4
                if (!ok) {
                    onDiscard.copyInto(rgba, base, 0, 4)
                } else {
                    val out = invocation.global(outName)
                        ?: throw GlslEvalError("shader never wrote '$outName'")
                    for (ch in 0 until minOf(4, out.c.size)) rgba[base + ch] = out.c[ch]
                }
            }
        }
        return CpuRaster(width, height, rgba)
    }

    /**
     * The one-call path: compile [program], substitute it into [template], render it.
     *
     * [pushParams] fills a `layout(push_constant) uniform Push { vec4 params; } pc;` block if the
     * template has one, which is how the gallery's animated template gets its time.
     */
    fun renderProgram(
        program: String,
        template: String,
        width: Int,
        height: Int,
        slot: String = "program",
        sampleTexture: String = "source",
        pushParams: FloatArray? = null,
        samplers: GlslSamplers = GlslSamplers.REFUSE,
        parallel: Boolean = true
    ): CpuRaster {
        val expanded = expandStackAuto(template, program, slot, sampleTexture)
        val uniforms = buildMap {
            pushParams?.let {
                require(it.size == 4) { "pushParams must be 4 floats" }
                put("pc.params", GlslValue(GlslType.VEC4, it.copyOf()))
            }
        }
        return renderFragment(expanded, width, height, uniforms, samplers, parallel = parallel)
    }
}
