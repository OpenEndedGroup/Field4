package afield.stacksl.cpu

import afield.stacksl.StackSLCompileOptions
import afield.stacksl.StackSLCompileResult
import afield.stacksl.StackSLCompiler
import afield.stacksl.StackSLSeverity
import afield.util.PngEncoder
import java.util.stream.IntStream

/**
 * StackSL's CPU backend: the same GLSL a GPU would run, evaluated one pixel at a time on the JVM,
 * so a StackSL program can be rendered, unit-tested, diffed and documented with no graphics context
 * at all.
 *
 * [StackSLCpu] is the entry point and [CpuRaster] is what comes back: RGBA floats with row 0 at the
 * top, quantised the way a `VK_FORMAT_R8G8B8A8_UNORM` attachment does, and encodable straight to
 * PNG. `StackSLCpu.renderStill` is the one-liner a box wants.
 *
 * What the backend is exact about is the *program*. `StackSLCpu.renderFragment` takes a complete
 * fragment shader as text and knows nothing about StackSL; the StackSL half compiles a program with
 * `StackSLCompiler` and substitutes the result into a `{{ stack.auto.<slot> }}` template, which is
 * the same tag the production expansion path in `afield.stdlib.shaders` fills. Both paths therefore
 * receive the same string, and nothing about a word's behaviour is restated here: change what the
 * emitter makes of `fbm` and this backend changes with it on the next call, with no edit. Tests pin
 * the two copies that make that true: `StackSLCpuExpansionTest` compares the compile options against
 * the production path's, and `StackSLCpuStillTemplateTest` compares the gallery's fragment template
 * against the committed one in the gallery workspace.
 *
 * It is a reference, not a renderer. It is an interpreter, so it is orders of magnitude short of a
 * GPU, and `StackSLCpu.renderStill` states a cost for one program at one size. It is not bit-exact
 * either: GLSL leaves `sin`, `cos`, `exp`, `log`, `pow` and `atan` implementation-defined, so a
 * program that reaches one of them can only be compared under a tolerance. [GlslValue] has that
 * argument in full, including the case where operations that *are* exact still diverge visibly.
 */

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
     * `pos` in `{0, 2}`. It is tempting to stop at "in Vulkan, clip-space `y = -1` is the top of
     * the framebuffer" and conclude `fragUV.y = 0` is the top row — that is what this constant
     * said until 2026-08-30, and it is wrong. Every afield.four render path also applies the
     * **negative-height viewport Y-flip** (`Renderer.setViewportAndScissor`,
     * `RenderTarget.setViewportAndScissor`, `RenderToTexture.setViewportAndScissorArray`), which
     * inverts that: clip `y = +1` maps to image row 0. So `fragUV.y = 1` is the **top** row and
     * `fragUV.y` grows *upwards* as a PNG's rows run downwards. See `f44227393`, which established
     * the same for the readback and fixed `TexturePreviewCache.capture`.
     *
     * This was not caught earlier because the oracle was wrong in the same direction: the
     * committed gallery PNGs this backend is measured against were themselves inverted, so a
     * flipped CPU raster agreed with them. Regenerating those figures is what surfaced it.
     *
     * [renderFragment] reads this, so the convention is stated once and honoured rather than
     * being an off-by-a-flip that only shows up as an upside-down image in one of 31 comparisons.
     */
    const val FRAG_UV_ORIGIN_IS_TOP_LEFT = false

    /**
     * `fragUV` for pixel ([x], [y]) of a [width] by [height] raster, at the pixel centre — which
     * is where a rasteriser evaluates a fullscreen triangle's interpolants.
     *
     * Stated once and read by every render path, so a second one cannot quietly pick a different
     * flip. That this flip was *wrong* here until 2026-08-30 while agreeing with an oracle that
     * was wrong the same way ([FRAG_UV_ORIGIN_IS_TOP_LEFT] has the story) is the reason it is a
     * function rather than three lines repeated per caller.
     */
    fun fragUvAt(x: Int, y: Int, width: Int, height: Int): GlslValue {
        val vTopDown = (y + 0.5f) / height
        val v = if (FRAG_UV_ORIGIN_IS_TOP_LEFT) vTopDown else 1.0f - vTopDown
        return GlslValue.vec2((x + 0.5f) / width, v)
    }

    /**
     * The compile options a `{{ stack.auto.<slot> }}` expansion uses.
     *
     * These are a deliberate, literal copy of the ones in
     * `afield.stdlib.shaders.compileStackAutoShaderFunctionReporting`, which is the production
     * expansion path. They cannot be *shared* without this package depending on
     * `afield.stdlib.shaders`, and that file drags in the whole `afield.four` graphics stack —
     * which would defeat the purpose of a backend that runs with no graphics context. So the two
     * copies are pinned against each other by `StackSLCpuExpansionTest`, which reads the option
     * list out of `shaders.kt`'s source text and compares it against this one. A blunt instrument —
     * it only sees a literal argument list and breaks on reformatting — but it catches the one
     * failure that is otherwise silent: an option added or changed on the production side, leaving
     * this backend compiling programs under different rules from the shader it claims to mirror.
     * (Red-first checked: flipping `tolerant` here turns that test red.)
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
        return substituteSlot(template, result.glsl, slot)
    }

    /**
     * Put [glsl] into [template] at `{{ stack.auto.<slot> }}`.
     *
     * Separate from [expandStackAuto] because a wired program's GLSL comes out of
     * [StackSLWires.lift] already compiled and lowered, and substituting it must not go back
     * through the compiler with different options.
     */
    fun substituteSlot(template: String, glsl: String, slot: String = "program"): String {
        val tag = Regex("""\{\{\s*stack\.auto\.${Regex.escape(slot)}\s*}}""")
        if (!tag.containsMatchIn(template)) {
            throw IllegalArgumentException("template has no {{ stack.auto.$slot }} slot")
        }
        // The lambda overload of `replace` treats its result literally, so the emitted GLSL goes
        // in byte for byte; the `String` overload would interpret `$` and `\` inside it.
        return tag.replace(template) { glsl }
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
            for (x in 0 until width) {
                // Honour the one stated convention; see [fragUvAt].
                invocation.setGlobal(fragUvName, fragUvAt(x, y, width, height))
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
     * The still template the docs gallery renders through, with [time] baked into `input.w`.
     *
     * This is a duplicate of `StackSLGalleryHarness.fragmentTemplate`, which is private and lives
     * in a file that drags in the whole `afield.four` graphics stack — so it cannot be shared with
     * a backend whose point is to need no graphics context. What keeps the copy honest is not
     * discipline: `StackSLCpuStillTemplateTest` asserts this is **byte-identical** to the committed
     * `definitions_fragment_.glsl` in the gallery workspace, which is what the GPU actually
     * consumed. If the harness changes its template and the workspace is regenerated, that test
     * goes red here.
     *
     * `gallery_hash12` is part of it: the harness feeds a per-pixel hash into `input.z`, so a
     * program that reads `z` gets noise rather than a constant.
     */
    fun stillTemplate(time: Float = 1.75f): String = """
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

    /**
     * Render a StackSL program on the CPU, at [dim] square, exactly as the docs gallery renders it.
     *
     * The one-liner a box wants: `display(StackSLCpu.renderStill("uv 6 * fbm palette saturate").toPngBytes())`
     * puts the picture in a box output with no graphics context, no window and no AWT.
     *
     * Cost, so nobody is surprised: this is an interpreter, and 512x512 of a five-octave `fbm`
     * takes single-digit seconds across ten cores — 5.9 s measured. 256 is a good size for a box.
     *
     * Per core that is **1.3–1.6 x 10^4** pixel-shader invocations per second, and the aggregate on
     * a ten-core machine is **4.4 x 10^4/s**, which is 3.3x the single-core figure rather than 10x.
     * This KDoc said 10^5/core until 2026-09-16 and was wrong by about 7x; the two halves of the
     * claim were never consistent, and the wall-clock half was the true one.
     * `StackSLCpuThroughputMeasurements` states the method and is where the numbers come from.
     */
    fun renderStill(program: String, dim: Int = 256, time: Float = 1.75f, parallel: Boolean = true): CpuRaster =
        renderProgram(program, stillTemplate(time), dim, dim, parallel = parallel)

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
