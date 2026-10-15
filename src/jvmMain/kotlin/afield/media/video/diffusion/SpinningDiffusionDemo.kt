package afield.media.video.diffusion

import afield.graphics.vulkan.Renderer
import afield.document.context.*
import afield.graphics.render.*
import afield.graphics.scene.*
import afield.graphics.shader.*
import afield.graphics.render.Module
import afield.graphics.vulkan.targets.RenderToTexture
import afield.graphics.shader.fromViews
import afield.math.linalg.vec
import org.lwjgl.vulkan.VK10.*
import kotlin.math.*

/** Native Field geometry for the video study's five curves. Left: live source; right: causal output. */
class SpinningDiffusionDemo(val renderer: Renderer, config: StreamDiffusionConfig,
    val whiteOnBlack: Boolean = false, val name: String = "spinning-diffusion") : AutoCloseable {
    private val background = if (whiteOnBlack) doubleArrayOf(8.0, 8.0, 10.0) else doubleArrayOf(244.0, 241.0, 234.0)
    private fun linear(v: Double): Double = (v / 255).let { if (it <= .04045) it / 12.92 else ((it + .055) / 1.055).pow(2.4) }
    val source = RenderToTexture(renderer) {
        width = 512; height = 512; useDepth = false
        clearColor = vec(linear(background[0]), linear(background[1]), linear(background[2]), 1)
    }.also { it.setDefaultSampler(anisotropyEnable = false) }
    private val lines = Module(parameters(renderer) {
        source.apply(); vertex = VERTEX; fragment = FRAGMENT
        depthTest = false; depthWrite = false; blend = false
    })
    private val linePointer = lines.pointer()
    private val mesh = Mesh(renderer, linePointer)
    private var motion: SpinningDiffusionMotion? = null
    val view: StreamDiffusionView
    private val comparison: Module
    private var closed = false
    var angle: Float = 0f
        set(value) { field = value; mesh.push["angle"] = value }

    init {
        check(lines.valid)
        val positions = mutableListOf<Float>()
        val normals = mutableListOf<Float>()
        val indices = mutableListOf<Int>()
        val surfaceIds = mutableListOf<Float>()
        val paths = (0..4).map { curve ->
            val n = if (curve == 4) 1 else 160
            (0..n).map { j ->
                val t = j.toDouble() / n * 2 * PI
                val u = j.toDouble() / n * 2 - 1
                when (curve) {
                    0 -> doubleArrayOf(.85 * cos(t), .65 * sin(t), .16 * sin(2 * t))
                    1 -> doubleArrayOf(.46 * cos(t), .36 * sin(t), .32)
                    2 -> doubleArrayOf(.95 * u, .27 * sin(1.5 * PI * u), -.24 + .10 * cos(PI * u))
                    3 -> doubleArrayOf(.35 * sin(PI * u), .87 * u, .13 * cos(2 * PI * u))
                    else -> if (j == 0) doubleArrayOf(-.70, -.70, .12) else doubleArrayOf(.65, .65, .12)
                }
            }
        }
        for ((curveId, path) in paths.withIndex()) for (i in 0 until path.lastIndex) {
            val a = path[i]; val b = path[i + 1]
            val dx = b[0] - a[0]; val dy = b[1] - a[1]
            val length = hypot(dx, dy).coerceAtLeast(1e-9)
            val nx = -dy / length * .0077; val ny = dx / length * .0077
            val start = positions.size / 3
            for ((point, side) in listOf(a to -1, a to 1, b to -1, b to 1)) {
                positions.addAll(point.map { it.toFloat() })
                surfaceIds += (curveId + 1).toFloat()
                normals += (side * nx).toFloat(); normals += (side * ny).toFloat()
            }
            indices.addAll(listOf(start, start + 1, start + 2, start + 2, start + 1, start + 3))
        }
        mesh.resize(positions.size / 3, indices.size / 3)
        mesh.write("pos") { positions.forEach { put(it) } }
        mesh.write("tc") { normals.forEach { put(it) } }
        mesh.t!!.mapAndWrite_ints { indices.forEach { put(it) } }
        mesh.push["angle"] = 0f; mesh.push["whitePaper"] = if (whiteOnBlack) 0 else 1
        lines.meshes["curves"] = mesh
        source.modules["curves"] = linePointer
        try {
            motion = SpinningDiffusionMotion(renderer, source, positions, normals, surfaceIds, indices) { angle }
            view = StreamDiffusionView(renderer, source, config, name, motion = motion)
        }
        catch (e: Throwable) { motion?.close(); renderer.cleanup += Runnable { lines.cleanup(); cleanupSource() }; throw e }
        comparison = Module(parameters(renderer) {
            vertex = COMPARE_VERTEX; fragment = COMPARE_FRAGMENT
            depthTest = false; depthWrite = false; blend = false
            textureResolver = { t -> when (t.name) {
                "sourceImage" -> fromViews(source, t.name, source.defaultSampler!!)
                "diffusedImage" -> fromViews(view.output, t.name, view.output.defaultSampler!!)
                else -> null
            } }
        })
        if (!comparison.valid) {
            view.close(); motion?.close()
            renderer.cleanup += Runnable { comparison.cleanup(); lines.cleanup(); cleanupSource() }
            error("Diffusion comparison shader failed to create its pipeline")
        }
        val pointer = comparison.pointer()
        val triangle = Mesh(renderer, pointer).resize(3, 0)
        comparison.meshes["comparison"] = triangle
        renderer.renderList.modules[name] = pointer
    }
    private fun cleanupSource() {
        source.imageViewArray.forEach { it.imageView.cleanup() }
        source.colorImageRefs.forEach { it?.cleanup() }; source.defaultSampler?.cleanup()
    }
    override fun close() {
        if (closed) return
        closed = true
        renderer.renderList.modules.remove(name)
        view.close(); motion?.close()
        renderer.cleanup += Runnable { comparison.cleanup(); lines.cleanup(); cleanupSource() }
    }
    companion object {
        private const val VERTEX = """#version 460
            layout(location=0) in vec3 pos;
            layout(location=1) in vec2 tc;
            layout(push_constant) uniform P { float angle; int whitePaper; } p;
            void main() {
                float tilt=.32*sin(p.angle);
                vec3 q=pos+vec3(tc,0);
                q=vec3(cos(tilt)*q.x+sin(tilt)*q.z,q.y,-sin(tilt)*q.x+cos(tilt)*q.z);
                mat2 turn=mat2(cos(p.angle),sin(p.angle),-sin(p.angle),cos(p.angle));
                gl_Position=vec4(turn*q.xy*(3.5/(q.z+3.5))*.76,0,1);
            }
        """
        private const val FRAGMENT = """#version 460
            layout(push_constant) uniform P { float angle; int whitePaper; } p;
            layout(location=0) out vec4 color;
            void main() {
                vec3 c=p.whitePaper==1?vec3(38,36,33)/255.:vec3(244,244,240)/255.;
                color=vec4(mix(c/12.92,pow((c+.055)/1.055,vec3(2.4)),greaterThan(c,vec3(.04045))),1);
            }
        """
        private const val COMPARE_VERTEX = """#version 460
            layout(location=0) out vec2 uv;
            void main(){vec2 p=vec2((gl_VertexIndex<<1)&2,gl_VertexIndex&2);uv=vec2(p.x,1-p.y);gl_Position=vec4(p*2-1,0,1);}
        """
        private const val COMPARE_FRAGMENT = """#version 460
            layout(location=0) in vec2 uv;
            layout(set=0,binding=0) uniform sampler2D sourceImage;
            layout(set=0,binding=1) uniform sampler2D diffusedImage;
            layout(location=0) out vec4 color;
            void main(){vec2 q=vec2(fract(uv.x*2),uv.y);color=uv.x<.5?texture(sourceImage,q):texture(diffusedImage,q);}
        """
    }
}
