package afield.four.browser

import afield.four.Renderer
import afield.four.modules.Mesh
import afield.four.modules.Module
import afield.four.modules.ModulePointer
import afield.four.modules.parameters
import afield.four.modules.pointer
import afield.four.texture.TextureSampler
import afield.stdlib.fromViews
import kotlin.math.PI

/**
 * Draws a live [PuppetBrowser] through an animated, independently moving field of triangular shards.
 *
 * This is deliberately a native graphics effect rather than a page effect: Chromium still renders
 * an ordinary composited tab, while Vulkan treats the resulting browser surface as one sampled
 * texture. Every triangle has its own phase and the fragment stage folds, mirrors and
 * chromatically offsets that texture before adding a narrow glass edge.
 */
class PuppetBrowserKaleidoscope(
    val renderer: Renderer,
    val browser: PuppetBrowser,
    val name: String = "puppet-browser-kaleidoscope",
    val columns: Int = 14,
    val rows: Int = 8,
) : AutoCloseable {
    init {
        require(columns > 0 && rows > 0) { "shard grid dimensions must be positive" }
    }

    /** Vertex animation amplitude. Zero leaves a cracked but stationary mosaic. */
    var motion: Float = 1f

    /** Base angular repetition used by the fragment-stage kaleidoscope. */
    var facets: Float = 5f

    /** Size of the RGB sampling separation, in texture-coordinate scale. */
    var chroma: Float = 1f

    val triangleCount: Int get() = columns * rows * 2

    private val sampler = TextureSampler(renderer, mipLevels = 1, anisotropyEnable = true)
    private val startedAt = System.nanoTime()
    private var pointer: ModulePointer? = null

    fun build() {
        check(pointer == null) { "$name is already built" }
        val module = Module(parameters(renderer) {
            this.name = this@PuppetBrowserKaleidoscope.name
            color = 1
            depth = 1
            depthTest = false
            depthWrite = false
            blend = false
            vertex = vertexShader
            fragment = fragmentShader
            textureResolver = { texture ->
                if (texture.name == "browserTexture") {
                    fromViews(listOf(browser), texture.name, sampler)
                } else null
            }
        })
        val createdPointer = module.pointer()
        val mesh = Mesh(renderer, createdPointer)
        val vertices = buildVertices(columns, rows)
        mesh.resize(vertices.size, 0)
        mesh.v[0]!!.mapAndWrite {
            vertices.forEach { vertex ->
                put(vertex.x)
                put(vertex.y)
                put(0f)
            }
            rewind()
        }
        mesh.v[1]!!.mapAndWrite {
            vertices.forEach { vertex ->
                put(vertex.u)
                put(vertex.v)
            }
            rewind()
        }
        mesh.v[2]!!.mapAndWrite {
            vertices.forEach { vertex ->
                put(vertex.centerX)
                put(vertex.centerY)
                put(vertex.phase)
                put(vertex.shard)
            }
            rewind()
        }
        mesh.v[3]!!.mapAndWrite {
            vertices.forEach { vertex ->
                put(vertex.barycentricX)
                put(vertex.barycentricY)
                put(vertex.barycentricZ)
            }
            rewind()
        }
        mesh.push.bind("time") { ((System.nanoTime() - startedAt) / 1_000_000_000.0).toFloat() }
        mesh.push.bind("motion") { motion }
        mesh.push.bind("facets") { facets }
        mesh.push.bind("chroma") { chroma }
        createdPointer.current.meshes[name] = mesh
        renderer.renderList.modules[name] = createdPointer
        pointer = createdPointer
    }

    override fun close() {
        renderer.renderList.modules.remove(name)
        sampler.cleanup()
        pointer = null
    }

    private data class ShardVertex(
        val x: Float,
        val y: Float,
        val u: Float,
        val v: Float,
        val centerX: Float,
        val centerY: Float,
        val phase: Float,
        val shard: Float,
        val barycentricX: Float,
        val barycentricY: Float,
        val barycentricZ: Float,
    )

    private companion object {
        fun buildVertices(columns: Int, rows: Int): List<ShardVertex> {
            val result = ArrayList<ShardVertex>(columns * rows * 6)
            var shard = 0
            for (row in 0 until rows) {
                val top = 1f - 2f * row / rows
                val bottom = 1f - 2f * (row + 1) / rows
                val vTop = row.toFloat() / rows
                val vBottom = (row + 1).toFloat() / rows
                for (column in 0 until columns) {
                    val left = -1f + 2f * column / columns
                    val right = -1f + 2f * (column + 1) / columns
                    val uLeft = column.toFloat() / columns
                    val uRight = (column + 1).toFloat() / columns
                    val a = floatArrayOf(left, top, uLeft, vTop)
                    val b = floatArrayOf(left, bottom, uLeft, vBottom)
                    val c = floatArrayOf(right, bottom, uRight, vBottom)
                    val d = floatArrayOf(right, top, uRight, vTop)
                    val triangles = if (((row + column) and 1) == 0) {
                        arrayOf(arrayOf(a, b, c), arrayOf(a, c, d))
                    } else {
                        arrayOf(arrayOf(a, b, d), arrayOf(b, c, d))
                    }
                    triangles.forEach { triangle ->
                        val centerX = triangle.sumOf { it[0].toDouble() }.div(3.0).toFloat()
                        val centerY = triangle.sumOf { it[1].toDouble() }.div(3.0).toFloat()
                        val phase = ((shard * 2.399963229728653) % (PI * 2.0)).toFloat()
                        triangle.forEachIndexed { index, point ->
                            result += ShardVertex(
                                x = point[0],
                                y = point[1],
                                u = point[2],
                                v = point[3],
                                centerX = centerX,
                                centerY = centerY,
                                phase = phase,
                                shard = shard.toFloat(),
                                barycentricX = if (index == 0) 1f else 0f,
                                barycentricY = if (index == 1) 1f else 0f,
                                barycentricZ = if (index == 2) 1f else 0f,
                            )
                        }
                        shard++
                    }
                }
            }
            return result
        }

        val vertexShader = """
            #version 460
            layout(location = 0) in vec3 inPosition;
            layout(location = 1) in vec2 inUV;
            layout(location = 2) in vec4 inShard;
            layout(location = 3) in vec3 inBarycentric;

            layout(location = 0) out vec2 fragUV;
            layout(location = 1) out vec3 fragBarycentric;
            layout(location = 2) flat out vec4 fragShard;

            layout(push_constant) uniform ShardPush {
                float time;
                float motion;
                float facets;
                float chroma;
            } push;

            mat2 rotate2(float angle) {
                float c = cos(angle);
                float s = sin(angle);
                return mat2(c, -s, s, c);
            }

            void main() {
                vec2 center = inShard.xy;
                float phase = inShard.z;
                vec2 local = inPosition.xy - center;
                float pulse = sin(push.time * 0.73 + phase);
                float angle = push.motion * (0.035 + 0.055 * length(center)) * pulse;
                float scale = 0.925 + push.motion * 0.025 * sin(push.time * 1.11 + phase * 1.7);
                vec2 radial = normalize(center + vec2(0.0001));
                vec2 drift = push.motion * (
                    radial * (0.010 + 0.010 * sin(push.time * 0.47 + phase)) +
                    vec2(cos(phase * 1.7), sin(phase * 1.3)) * 0.006 * sin(push.time * 0.91 + phase)
                );
                gl_Position = vec4(center + rotate2(angle) * local * scale + drift, 0.0, 1.0);
                fragUV = inUV;
                fragBarycentric = inBarycentric;
                fragShard = inShard;
            }
        """.trimIndent()

        val fragmentShader = """
            #version 460
            layout(location = 0) in vec2 fragUV;
            layout(location = 1) in vec3 fragBarycentric;
            layout(location = 2) flat in vec4 fragShard;
            layout(location = 0) out vec4 outColor;

            layout(set = 0, binding = 0) uniform sampler2D browserTexture;
            layout(push_constant) uniform ShardPush {
                float time;
                float motion;
                float facets;
                float chroma;
            } push;

            const float PI = 3.141592653589793;
            const float TAU = 6.283185307179586;

            mat2 rotate2(float angle) {
                float c = cos(angle);
                float s = sin(angle);
                return mat2(c, -s, s, c);
            }

            vec2 foldedBrowserUV(vec2 uv, float shard, float phase) {
                // Work in aspect-correct coordinates so circles stay circular before folding.
                vec2 p = (uv - 0.5) * vec2(16.0 / 9.0, 1.0);
                p = rotate2(phase * 0.23 + push.time * (0.025 + 0.004 * mod(shard, 5.0))) * p;
                float radius = length(p);
                float count = max(3.0, floor(push.facets + mod(shard, 6.0)));
                float sector = TAU / count;
                float angle = abs(mod(atan(p.y, p.x) + 0.5 * sector, sector) - 0.5 * sector);
                vec2 folded = vec2(cos(angle), sin(angle)) * radius;
                folded = rotate2(0.38 * sin(radius * 16.0 - push.time * 0.62 + phase)) * folded;
                float zoom = 0.86 + 0.25 * sin(phase * 1.31 + push.time * 0.13);
                folded *= zoom;
                folded.x /= 16.0 / 9.0;
                return fract(0.5 + folded + 0.035 * vec2(cos(phase), sin(phase * 1.7)));
            }

            void main() {
                float shard = fragShard.w;
                float phase = fragShard.z;
                vec2 sampleUV = foldedBrowserUV(fragUV, shard, phase);
                vec2 direction = normalize(sampleUV - 0.5 + vec2(0.0001));
                vec2 separation = direction * push.chroma * (0.0012 + 0.0008 * sin(phase + push.time));

                vec3 colour;
                colour.r = texture(browserTexture, sampleUV + separation).r;
                colour.g = texture(browserTexture, sampleUV).g;
                colour.b = texture(browserTexture, sampleUV - separation).b;

                // Retain a trace of the page's original spatial layout beneath the folded image.
                vec3 page = texture(browserTexture, fragUV).rgb;
                colour = mix(colour, page, 0.16);
                float luminance = dot(colour, vec3(0.2126, 0.7152, 0.0722));
                colour = mix(vec3(luminance), colour, 1.22);

                float nearestEdge = min(fragBarycentric.x, min(fragBarycentric.y, fragBarycentric.z));
                float edgeWidth = max(fwidth(nearestEdge) * 1.8, 0.001);
                float interior = smoothstep(0.0, edgeWidth, nearestEdge);
                float glint = 1.0 - interior;
                vec3 glass = vec3(0.24, 0.76, 1.0) * (0.45 + 0.55 * sin(phase + push.time * 0.8));
                colour = colour * (0.72 + 0.28 * interior) + glass * glint * 0.42;

                // A slight radial falloff keeps the black cracks legible without dimming the video.
                float vignette = 1.0 - 0.13 * smoothstep(0.25, 1.35, length(fragUV - 0.5));
                outColor = vec4(colour * vignette, 1.0);
            }
        """.trimIndent()
    }
}
