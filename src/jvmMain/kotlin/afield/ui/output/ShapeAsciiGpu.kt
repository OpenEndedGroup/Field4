@file:afield.runtime.completion.CompletionCategory("Visualization")

package afield.ui.output

import afield.app.protocol.Rect
import afield.document.context.fromContext
import afield.graphics.render.Module
import afield.graphics.render.ModulePointer
import afield.graphics.render.fullscreenTriangle
import afield.graphics.render.parameters
import afield.graphics.render.pointer
import afield.graphics.shader.fromViews
import afield.graphics.texture.LoadedTexture
import afield.graphics.texture.TextureSampler
import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.Target
import afield.graphics.vulkan.device.CommandBuffer
import afield.graphics.vulkan.targets.RenderTarget
import afield.graphics.vulkan.targets.RenderToTexture
import afield.math.linalg.Vec4
import afield.math.linalg.vec
import afield.media.image.Img
import org.lwjgl.BufferUtils
import org.lwjgl.vulkan.VK10.VK_FILTER_NEAREST
import org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM
import java.util.concurrent.CompletableFuture

/**
 * [ShapeAscii] as a GPU pass: a live Vulkan scene turned into characters every frame, after the
 * real-time version in Alex Harri's essay [ASCII characters are not pixels](https://alexharri.com/blog/ascii-rendering).
 *
 * Draw any module into [scene], an offscreen target exactly [cols] × [rows] cells of the charset's
 * font in size. Each frame a full-screen pass over a [cols] × [rows] target, one fragment per cell,
 * averages the same circle pixels [ShapeAscii.convert] does, applies the two contrast steps and
 * searches all the glyph vectors, writing the winning glyph's index. A last pass draws those indices
 * through a glyph atlas into the window. The circle pixels and glyph vectors are written into the
 * matching shader as constants, so the GPU and the CPU compare the same numbers: [read] returns a
 * frame's scene and its characters together, and `ascii.convert(scene, cols, rows = rows, exact = true)`
 * on the scene should give the same characters.
 *
 * ```
 * var ascii = ShapeAsciiGpu(cols = 128, rows = 48) { contrast = 3f }
 * var module = moduleHere { +ascii.scene }
 * ascii.scene += "mine" to module
 * ascii.installInto()
 * ```
 */
class ShapeAsciiGpu(
    /** Columns of characters. */
    val cols: Int = 128,
    /** Rows of characters. */
    val rows: Int = 48,
    /** The charset and font to match against; its glyph vectors and circles are baked into the shader. */
    val ascii: ShapeAscii = ShapeAscii(),
    /** The renderer to build on; by default the one this box is running in. */
    val renderer: Renderer = fromContext(),
    /** A prefix for the passes' names, distinct per instance on one renderer. */
    val name: String = "shape-ascii",
    configure: ShapeAsciiGpu.() -> Unit = {},
) : Target {

    /** Contrast exponent, as [ShapeAscii.convert]'s `contrast`; read every frame. */
    var contrast: Float = 1f

    /** Directional contrast exponent, as [ShapeAscii.convert]'s `directional`; read every frame. */
    var directional: Float = 1f

    /** Read dark pixels as ink, for a dark scene on a light ground; read every frame. */
    var invert: Boolean = false

    /** The characters' colour in [drawInto]. */
    var ink: Vec4 = vec(0.91f, 0.91f, 0.91f, 1f)

    /** The ground behind the characters in [drawInto]. */
    var background: Vec4 = vec(0.086f, 0.086f, 0.10f, 1f)

    /** The scene target's size in pixels: [cols] × the font's cell width by [rows] × its cell height. */
    val sceneWidth: Int = cols * ascii.cellWidth

    /** See [sceneWidth]. */
    val sceneHeight: Int = rows * ascii.cellHeight

    /** Where the scene goes: draw modules into it (`moduleHere { +ascii.scene }`, then `ascii.scene += "name" to module`). */
    val scene: RenderToTexture

    /** One texel per cell; the red channel holds the chosen glyph's index / 255. */
    val cells: RenderToTexture

    /** Every glyph of the charset side by side, one cell each, as [ShapeAscii.glyphImg] rasterised it. */
    val atlas: LoadedTexture

    /** The matching pass, drawing into [cells]. */
    val matchModule: ModulePointer

    private val nearest = TextureSampler(renderer, 1, false, VK_FILTER_NEAREST)
    private val linear = TextureSampler(renderer, 1, false)
    private val readRequests = ArrayList<CompletableFuture<ShapeAsciiReadback>>()

    init {
        require(cols >= 1 && rows >= 1) { "need at least one cell, got ${cols}x$rows" }
        require(ascii.glyphs.length <= 256) { "the GPU pass stores a glyph index in one byte; ${ascii.glyphs.length} glyphs is too many" }
        scene = RenderToTexture(renderer) {
            width = sceneWidth
            height = sceneHeight
            format = VK_FORMAT_R8G8B8A8_UNORM
            colorFormats = listOf(VK_FORMAT_R8G8B8A8_UNORM)
            clearColor = vec(0, 0, 0, 1)
            clearFirst = true
            useDepth = true
            colorTransferSrc = true
            debugName = "$name-scene"
        }
        cells = RenderToTexture(renderer) {
            width = cols
            height = rows
            format = VK_FORMAT_R8G8B8A8_UNORM
            colorFormats = listOf(VK_FORMAT_R8G8B8A8_UNORM)
            clearColor = vec(0, 0, 0, 1)
            useDepth = false
            colorTransferSrc = true
            debugName = "$name-cells"
        }
        atlas = buildAtlas()
        matchModule = Module(
            parameters(renderer) {
                +cells
                this.name = "$name-match"
                depthTest = false
                blend = false
                vertex = FULLSCREEN_VERTEX
                fragment = matchFragment(ascii)
                textureResolver = { tex -> if (tex.name == "sceneTex") fromViews(listOf(scene), tex.name, nearest) else null }
            }
        ).pointer()
        matchModule.current.meshes["$name-match-quad"] = fullscreenTriangle(matchModule).apply {
            push.bind("contrast") { contrast }
            push.bind("directional") { directional }
            push.bind("invert") { if (invert) 1f else 0f }
        }
        cells += "match" to matchModule
        configure()
    }

    /** GPU time of the matching pass in milliseconds, once [profile] is on and a frame has been timed. */
    val matchMilliseconds: Double? get() = cells.gpuSample?.milliseconds

    /** GPU time of the scene pass in milliseconds, once [profile] is on and a frame has been timed. */
    val sceneMilliseconds: Double? get() = scene.gpuSample?.milliseconds

    /** Time the scene and matching passes on the GPU every frame (timestamp queries); see [matchMilliseconds]. */
    var profile: Boolean
        get() = cells.profileGpu
        set(value) {
            cells.profileGpu = value
            scene.profileGpu = value
        }

    override fun render(command: CommandBuffer, index: Int) {
        val requests = synchronized(readRequests) { readRequests.toList().also { readRequests.clear() } }
        if (requests.isNotEmpty()) {
            // both copies are queued before either target renders, so they come from the same frame
            val pixels = scene.readPixelsAsync(0)
            val glyphs = cells.readPixelsAsync(0)
            pixels.thenCombine(glyphs) { p, g -> decode(p, g) }.whenComplete { r, t ->
                for (f in requests) if (t != null) f.completeExceptionally(t) else f.complete(r)
            }
        }
        scene.render(command, index)
        cells.render(command, index)
    }

    /**
     * The next frame's scene and the characters the GPU chose for it, read back together. Completes
     * once that frame's GPU work has finished, so keep frames coming while you wait.
     */
    fun read(): CompletableFuture<ShapeAsciiReadback> {
        val f = CompletableFuture<ShapeAsciiReadback>()
        synchronized(readRequests) { readRequests += f }
        return f
    }

    /**
     * Register the scene and matching passes as a pre-target of [renderTarget], so they run once per
     * frame, and with [draw] also draw the characters over the whole window.
     */
    fun installInto(renderTarget: RenderTarget = renderer.renderList, draw: Boolean = true) {
        renderTarget.preTargets[name] = this
        if (draw) drawInto(renderTarget)
    }

    /** Stop the passes [installInto] registered and remove the drawing modules this added to [renderTarget]. */
    fun uninstallFrom(renderTarget: RenderTarget = renderer.renderList) {
        renderTarget.preTargets.remove(name)
        renderTarget.modules.keys.filter { it == "$name-draw" || it.startsWith("$name-draw-") || it.startsWith("$name-scene-view") }
            .forEach { renderTarget.modules.remove(it) }
    }

    /** Draw the characters into [target], over [viewport] or the whole target, in [ink] on [background]. */
    fun drawInto(target: RenderTarget = renderer.renderList, viewport: Rect? = null, name: String = "${this.name}-draw"): ModulePointer {
        val module = Module(
            parameters(renderer) {
                this.name = name
                depthTest = false
                blend = false
                this.viewport = viewport
                vertex = UV_VERTEX
                fragment = drawFragment(ascii)
                textureResolver = { tex ->
                    when (tex.name) {
                        "cellsTex" -> fromViews(listOf(cells), tex.name, nearest)
                        "atlasTex" -> fromViews(listOf(atlas), tex.name, linear)
                        else -> null
                    }
                }
            }
        ).pointer()
        module.current.meshes["$name-quad"] = fullscreenTriangle(module).apply {
            push.bind("ink") { ink }
            push.bind("background") { background }
        }
        target += name to module
        return module
    }

    /** Draw the scene itself, as the matcher sees it, into [target] over [viewport]: for showing the two side by side. */
    fun drawSceneInto(target: RenderTarget = renderer.renderList, viewport: Rect? = null, name: String = "${this.name}-scene-view"): ModulePointer {
        val module = Module(
            parameters(renderer) {
                this.name = name
                depthTest = false
                blend = false
                this.viewport = viewport
                vertex = UV_VERTEX
                fragment = SCENE_FRAGMENT
                textureResolver = { tex -> if (tex.name == "sceneTex") fromViews(listOf(scene), tex.name, linear) else null }
            }
        ).pointer()
        module.current.meshes["$name-quad"] = fullscreenTriangle(module)
        target += name to module
        return module
    }

    private fun buildAtlas(): LoadedTexture {
        val cw = ascii.cellWidth
        val ch = ascii.cellHeight
        val n = ascii.glyphs.length
        val w = n * cw
        val bytes = BufferUtils.createByteBuffer(w * ch * 4)
        val glyphs = Array(n) { ascii.glyphImg(it) }
        for (y in 0 until ch) for (x in 0 until w) {
            val v = (glyphs[x / cw].data[y * cw + x % cw].coerceIn(0f, 1f) * 255 + 0.5f).toInt().toByte()
            bytes.put(v).put(v).put(v).put(v)
        }
        bytes.flip()
        return LoadedTexture.fromRawRGBA("$name-atlas", w, ch, bytes, renderer, VK_FORMAT_R8G8B8A8_UNORM).also { it.transferNow() }
    }

    private fun decode(pixels: ByteArray, glyphs: ByteArray): ShapeAsciiReadback {
        val n = sceneWidth * sceneHeight
        val data = FloatArray(n * 3)
        for (i in 0 until n) for (c in 0 until 3) data[i * 3 + c] = (pixels[i * 4 + c].toInt() and 0xff) / 255f
        val indices = IntArray(cols * rows) { glyphs[it * 4].toInt() and 0xff }
        return ShapeAsciiReadback(Img(sceneWidth, sceneHeight, 3, data), AsciiFrame(ascii, cols, rows, indices))
    }

    companion object {
        private val FULLSCREEN_VERTEX = """
            #version 450

            void main() {
                vec2 p = gl_VertexIndex == 0 ? vec2(-1.0, -1.0) : (gl_VertexIndex == 1 ? vec2(3.0, -1.0) : vec2(-1.0, 3.0));
                gl_Position = vec4(p, 0.0, 1.0);
            }
        """.trimIndent()

        private val UV_VERTEX = """
            #version 450

            layout(location = 0) out vec2 uv;

            void main() {
                vec2 p = gl_VertexIndex == 0 ? vec2(-1.0, -1.0) : (gl_VertexIndex == 1 ? vec2(3.0, -1.0) : vec2(-1.0, 3.0));
                uv = p * 0.5 + 0.5;
                gl_Position = vec4(p, 0.0, 1.0);
            }
        """.trimIndent()

        private val SCENE_FRAGMENT = """
            #version 450

            layout(set = 0, binding = 0) uniform sampler2D sceneTex;
            layout(location = 0) in vec2 uv;
            layout(location = 0) out vec4 outColor;

            void main() {
                outColor = vec4(texture(sceneTex, uv).rgb, 1.0);
            }
        """.trimIndent()

        private fun f(v: Double) = "%.9e".format(java.util.Locale.ROOT, v)

        /**
         * The matching shader for [ascii]: its circle pixels and glyph vectors as constants, then per
         * cell the same averages, contrast steps and exhaustive nearest-glyph search as [ShapeAscii.convert]
         * with `exact = true`.
         */
        internal fun matchFragment(ascii: ShapeAscii): String {
            val circles = (0 until 12).map { ascii.circlePixels(it) }
            val starts = circles.runningFold(0) { a, c -> a + c.size / 2 }
            val offsets = circles.flatMap { c -> (c.indices step 2).map { "ivec2(${c[it]}, ${c[it + 1]})" } }
            val n = ascii.glyphs.length
            val vectors = (0 until n).flatMap { g -> ascii.glyphVector(g).map(::f) }
            return """
                #version 450

                layout(set = 0, binding = 0) uniform sampler2D sceneTex;

                layout(push_constant) uniform Push {
                    float contrast;
                    float directional;
                    float invert;
                } push;

                layout(location = 0) out vec4 outColor;

                const int CELL_W = ${ascii.cellWidth};
                const int CELL_H = ${ascii.cellHeight};
                const int GLYPHS = $n;
                const int START[13] = int[13](${starts.joinToString()});
                const ivec2 OFFSETS[${offsets.size}] = ivec2[${offsets.size}](${offsets.joinToString()});
                const float VECTORS[${vectors.size}] = float[${vectors.size}](${vectors.joinToString()});

                float lum(ivec2 p, ivec2 size) {
                    vec3 c = texelFetch(sceneTex, clamp(p, ivec2(0), size - 1), 0).rgb;
                    float v = clamp((c.r + c.g + c.b) / 3.0, 0.0, 1.0);
                    return push.invert > 0.5 ? 1.0 - v : v;
                }

                float circle(int k, ivec2 origin, ivec2 size) {
                    float s = 0.0;
                    for (int i = START[k]; i < START[k + 1]; i++) s += lum(origin + OFFSETS[i], size);
                    return s / float(START[k + 1] - START[k]);
                }

                void main() {
                    ivec2 size = textureSize(sceneTex, 0);
                    ivec2 origin = ivec2(gl_FragCoord.xy) * ivec2(CELL_W, CELL_H);
                    float v[6];
                    for (int k = 0; k < 6; k++) v[k] = circle(k, origin, size);
                    if (push.directional != 1.0) {
                        for (int k = 0; k < 6; k++) {
                            float m = max(v[k], circle(k + 6, origin, size));
                            if (m > 0.0) v[k] = v[k] > 0.0 ? pow(v[k] / m, push.directional) * m : 0.0;
                        }
                    }
                    if (push.contrast != 1.0) {
                        float m = 0.0;
                        for (int k = 0; k < 6; k++) m = max(m, v[k]);
                        if (m > 0.0) for (int k = 0; k < 6; k++) v[k] = v[k] > 0.0 ? pow(v[k] / m, push.contrast) * m : 0.0;
                    }
                    int best = 0;
                    float bestD = 1e30;
                    for (int g = 0; g < GLYPHS; g++) {
                        float d = 0.0;
                        for (int k = 0; k < 6; k++) {
                            float t = VECTORS[g * 6 + k] - v[k];
                            d += t * t;
                        }
                        if (d < bestD) {
                            bestD = d;
                            best = g;
                        }
                    }
                    outColor = vec4(float(best) / 255.0, 0.0, 0.0, 1.0);
                }
            """.trimIndent()
        }

        internal fun drawFragment(ascii: ShapeAscii): String = """
            #version 450

            layout(set = 0, binding = 0) uniform sampler2D cellsTex;
            layout(set = 0, binding = 1) uniform sampler2D atlasTex;

            layout(push_constant) uniform Push {
                vec4 ink;
                vec4 background;
            } push;

            layout(location = 0) in vec2 uv;
            layout(location = 0) out vec4 outColor;

            const vec2 CELL = vec2(${ascii.cellWidth}.0, ${ascii.cellHeight}.0);

            void main() {
                ivec2 grid = textureSize(cellsTex, 0);
                vec2 g = uv * vec2(grid);
                ivec2 cell = clamp(ivec2(floor(g)), ivec2(0), grid - 1);
                float glyph = floor(texelFetch(cellsTex, cell, 0).r * 255.0 + 0.5);
                vec2 inCell = clamp(fract(g) * CELL, vec2(0.5), CELL - 0.5);
                vec2 atlasSize = vec2(textureSize(atlasTex, 0));
                float a = texture(atlasTex, (vec2(glyph * CELL.x, 0.0) + inCell) / atlasSize).r;
                outColor = mix(push.background, push.ink, a);
            }
        """.trimIndent()
    }
}

/** One frame of a [ShapeAsciiGpu], read back: the [scene] as the matcher saw it, and the characters it chose. */
class ShapeAsciiReadback(
    /** The scene target's pixels, RGB in `0..1`, row 0 at the top. */
    val scene: Img,
    /** The characters the GPU chose for that scene. */
    val frame: AsciiFrame,
)
