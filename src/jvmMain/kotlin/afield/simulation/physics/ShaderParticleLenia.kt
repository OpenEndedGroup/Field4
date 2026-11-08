package afield.simulation.physics

import afield.document.context.fromContext
import afield.graphics.render.ComputeMesh
import afield.graphics.render.ComputeModule
import afield.graphics.render.ComputeModulePointer
import afield.graphics.render.Mesh
import afield.graphics.render.Module
import afield.graphics.render.computeMemoryBarrier
import afield.graphics.render.parameters
import afield.graphics.render.pointer
import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.Target
import afield.graphics.vulkan.buffers.FrameBufferGroup
import afield.graphics.vulkan.buffers.buffer
import afield.graphics.vulkan.device.CommandBuffer
import afield.graphics.vulkan.util.GpuNeighbourGrid
import afield.math.linalg.Rectanglef
import afield.math.linalg.Vector2f
import afield.simulation.agents.ParticleLeniaParams
import org.lwjgl.vulkan.VK10.VK_BLEND_FACTOR_ONE
import org.lwjgl.vulkan.VK10.VK_BLEND_FACTOR_SRC_ALPHA
import org.lwjgl.vulkan.VK10.VK_BLEND_OP_ADD
import kotlin.math.ceil

/**
 * Particle Lenia as Vulkan compute, over a [GpuNeighbourGrid], for thousands of particles.
 *
 * The same law as the CPU reference [afield.simulation.agents.ParticleLenia] (Mordvintsev, Niklasson
 * and Randazzo, Google Research 2022): each particle sums the ring kernel `K` and the repulsion over
 * its neighbours, and moves by `−∇E` with `E = R − G(U)`. The kernel is a Gaussian, so it is cut at
 * [cutoff] (`muK + 3 sigmaK` by default, where `K` has fallen to `e^−9` of its peak) and the grid's
 * cell must be at least that wide; the constructor checks it.
 *
 * #### Two passes per step, not one
 *
 * Every step runs a *force* pass, which reads positions and writes each particle's velocity, `U`
 * and `E`, then a barrier, then an *integrate* pass, which adds `dt · velocity` to the position.
 * Writing positions inside the force pass would let a particle see some neighbours before and some
 * after their move, which is a different (order-dependent) integrator from the CPU reference's.
 * [substeps] repeats the pair inside one frame; the grid is rebuilt once per frame, which stays
 * exact as long as no particle moves further than the cell's slack beyond [cutoff] in that frame.
 *
 * #### Checked against the CPU
 *
 * `four.particle-lenia.smoke` seeds positions, runs with `dt = 0`, and compares each particle's
 * GPU velocity with `ParticleLenia.sample` over the same positions.
 *
 * ```
 * val grid = GpuNeighbourGrid(capacity = 4000, cellsPerSide = 48, domainMin = Vector2f(-200f, -200f),
 *     domainSize = 400f, renderer = renderer, name = "plGrid")
 * val pl = ShaderParticleLenia(grid, 4000, renderer)
 * pl.reset(creatures = 20, perCreature = 200, spacing = 60f)
 * pl.install(renderer.renderList.others)
 * pl.installDrawInto()
 * ```
 */
class ShaderParticleLenia(
    /** The neighbour grid; its cell must be at least [cutoff] wide. */
    val grid: GpuNeighbourGrid,
    /** How many particles; at most `grid.capacity`. */
    val count: Int,
    /** Where buffers and modules are allocated. */
    val renderer: Renderer = fromContext(),
    /** The rule; read every dispatch, so it can be changed while running. */
    var params: ParticleLeniaParams = ParticleLeniaParams(),
    /** Prefix for buffer, module and target names, so two systems can share a renderer. */
    val name: String = "particleLenia",
) {
    /** Distance beyond which the kernel is treated as zero. */
    var cutoff: Float = (params.muK + 3 * params.sigmaK).toFloat()

    /** Steps per frame. */
    var substeps: Int = 4

    /** Point size of the draw pass, in pixels. */
    var pointSize: Float = 3f

    /** World units from the window's centre to its top edge, for the draw pass. */
    var viewScale: Float = 60f

    /** World point at the window's centre, for the draw pass; a lambda, so it can follow something. */
    var viewCenter: () -> Vector2f = { Vector2f(0f, 0f) }

    /** Per particle: position (2), velocity (2), U, E, and two spare floats. */
    val particles: FrameBufferGroup = buffer(name = "${name}Particles") {
        size = count.toLong() * STRIDE_FLOATS * 4L
        storage(); addressable(); hosted(); dest()
    }.toBuffer(renderer)

    private var forceModule: ComputeModulePointer? = null
    private var integrateModule: ComputeModulePointer? = null
    private var drawModule: afield.graphics.render.ModulePointer? = null

    init {
        require(count in 1..grid.capacity) { "count $count is outside the grid's 1..${grid.capacity}" }
    }

    /**
     * Seeds [creatures] clusters of [perCreature] particles each (the rest, if any, go into the last),
     * every cluster uniform in a square of side [side] (the article's seed is 12 for 200 particles),
     * the clusters on a square lattice [spacing] apart and centred on the origin.
     */
    fun reset(creatures: Int = 1, perCreature: Int = count, side: Float = 12f, spacing: Float = 60f, seed: Long = 1L) {
        val random = java.util.Random(seed)
        val columns = ceil(kotlin.math.sqrt(creatures.toDouble())).toInt().coerceAtLeast(1)
        particles.get(write = true).mapAndWrite {
            repeat(count) { i ->
                val c = minOf(i / perCreature.coerceAtLeast(1), creatures - 1)
                val cx = (c % columns - (columns - 1) / 2f) * spacing
                val cy = (c / columns - (columns - 1) / 2f) * spacing
                put(cx + (random.nextFloat() - 0.5f) * side)
                put(cy + (random.nextFloat() - 0.5f) * side)
                repeat(STRIDE_FLOATS - 2) { put(0f) }
            }
        }
    }

    /** Writes positions from an interleaved `x0, y0, x1, y1, …` array of length `2 * count`. */
    fun setPositions(xy: DoubleArray) {
        require(xy.size == 2 * count) { "need ${2 * count} coordinates, got ${xy.size}" }
        particles.get(write = true).mapAndWrite {
            repeat(count) { i ->
                put(xy[2 * i].toFloat()); put(xy[2 * i + 1].toFloat())
                repeat(STRIDE_FLOATS - 2) { put(0f) }
            }
        }
    }

    /** Reads the whole particle buffer back, [STRIDE_FLOATS] floats per particle. */
    fun read(): FloatArray {
        val out = FloatArray(count * STRIDE_FLOATS)
        particles.get().mapAndRead { get(out) }
        return out
    }

    /** Installs the grid rebuild, then [substeps] force/integrate pairs, into [into] under [name]. */
    fun install(into: MutableMap<String, Target>) {
        require(cutoff <= grid.cellSize + 1e-6f) {
            "a cutoff of $cutoff does not fit a cell of ${grid.cellSize}; the 3x3 query would miss neighbours"
        }
        grid.install(into, particles, count, strideFloats = STRIDE_FLOATS, offsetFloats = 0)
        val force = forceModule ?: ComputeModule(parameters(renderer) { name = "$name-force"; compute = FORCE_SHADER(grid) })
            .pointer().also { forceModule = it }
        val integrate = integrateModule ?: ComputeModule(parameters(renderer) { name = "$name-integrate"; compute = INTEGRATE_SHADER })
            .pointer().also { integrateModule = it }
        val groups = ceil(count / LOCAL_SIZE.toDouble()).toInt().coerceAtLeast(1)
        ComputeMesh(renderer, force).apply {
            x = groups; y = 1; z = 1
            push.bind("particles") { this@ShaderParticleLenia.particles }
            push.bind("values") { grid.values }
            push.bind("cellStart") { grid.cellStart }
            push.bind("cellEnd") { grid.cellEnd }
            push.bind("domainMin") { grid.domainMin }
            push.bind("invCellSize") { grid.cellsPerSide / grid.domainSize }
            push.bind("cutoff") { cutoff }
            push.bind("muK") { params.muK.toFloat() }
            push.bind("sigmaK") { params.sigmaK.toFloat() }
            push.bind("wK") { params.wK.toFloat() }
            push.bind("muG") { params.muG.toFloat() }
            push.bind("sigmaG") { params.sigmaG.toFloat() }
            push.bind("cRep") { params.cRep.toFloat() }
            push.bind("count") { count }
            force.current.meshes["$name-force"] = this
        }
        ComputeMesh(renderer, integrate).apply {
            x = groups; y = 1; z = 1
            push.bind("particles") { this@ShaderParticleLenia.particles }
            push.bind("dt") { params.dt.toFloat() }
            push.bind("count") { count }
            integrate.current.meshes["$name-integrate"] = this
        }
        val bounds = Rectanglef(0f, 0f, 0f, 0f)
        into["$name-step"] = Target { command: CommandBuffer, _: Int ->
            repeat(substeps.coerceAtLeast(1)) {
                force.render(command, bounds)
                barrier(command)
                integrate.render(command, bounds)
                barrier(command)
            }
        }
    }

    /** Takes the grid and the step back out of [into]. */
    fun removeFrom(into: MutableMap<String, Target>) {
        grid.removeFrom(into)
        into.remove("$name-step")
    }

    /** Adds a point pass drawing every particle, tinted by its energy, into [target]. */
    fun installDrawInto(target: afield.graphics.vulkan.targets.RenderTarget = renderer.renderList) {
        val draw = drawModule ?: Module(
            parameters(renderer) {
                name = "$name-draw"
                depthTest = false
                blend = true
                srcColor = VK_BLEND_FACTOR_SRC_ALPHA
                dstColor = VK_BLEND_FACTOR_ONE
                colorBlendOp = VK_BLEND_OP_ADD
                srcAlpha = VK_BLEND_FACTOR_ONE
                dstAlpha = VK_BLEND_FACTOR_ONE
                alphaBlendOp = VK_BLEND_OP_ADD
                primitiveType = points
                vertex = VERTEX_SHADER
                fragment = FRAGMENT_SHADER
            }
        ).pointer().also { drawModule = it }
        Mesh(renderer, draw).apply {
            resize(count, 0, draw)
            push.bind("particles") { this@ShaderParticleLenia.particles }
            push.bind("pointSize") { pointSize }
            push.bind("aspectRatio") { renderer.window.width / renderer.window.height.toFloat() }
            push.bind("viewScale") { viewScale }
            push.bind("viewCenter") { viewCenter() }
            draw.current.meshes["$name-points"] = this
        }
        target += "$name-draw" to draw
    }

    private fun barrier(command: CommandBuffer) {
        computeMemoryBarrier(command, particles.get().buffer, particles.get().requestedSize)
    }

    companion object {
        /** Floats per particle in [particles]. */
        const val STRIDE_FLOATS = 8
        private const val LOCAL_SIZE = 64

        private val STRUCT = """
            struct Particle { vec2 position; vec2 velocity; float u; float e; float spare0; float spare1; };
            layout(std430, buffer_reference, scalar) buffer Particles { Particle p[]; };
        """.trimIndent()

        @Suppress("FunctionName")
        private fun FORCE_SHADER(grid: GpuNeighbourGrid) = """
#version 450
#extension GL_EXT_scalar_block_layout : require
${grid.glsl("grid")}

$STRUCT
layout(push_constant) uniform Push {
    Particles particles;
    gridUints values;
    gridUints cellStart;
    gridUints cellEnd;
    vec2 domainMin;
    float invCellSize;
    float cutoff;
    float muK; float sigmaK; float wK;
    float muG; float sigmaG; float cRep;
    uint count;
} pc;
layout(local_size_x = $LOCAL_SIZE) in;

void main() {
    uint i = gl_GlobalInvocationID.x;
    if (i >= pc.count) return;
    vec2 x = pc.particles.p[i].position;
    // U and its gradient, R and its gradient, summed over the 3x3 block of cells. The particle's
    // own term is included, as in the CPU reference: K(0) adds to U, cRep/2 to R, nothing to either
    // gradient.
    float u = 0.0; vec2 du = vec2(0.0);
    float r = 0.0; vec2 dr = vec2(0.0);
    ivec2 home = gridCellOf(x, pc.domainMin, pc.invCellSize);
    for (int oy = -1; oy <= 1; ++oy)
    for (int ox = -1; ox <= 1; ++ox) {
        ivec2 c = home + ivec2(ox, oy);
        if (c.x < 0 || c.y < 0 || c.x >= gridCells || c.y >= gridCells) continue;
        uint ci = gridCellIndex(c);
        for (uint k = pc.cellStart.v[ci]; k < pc.cellEnd.v[ci]; ++k) {
            uint j = pc.values.v[k];
            vec2 d = x - pc.particles.p[j].position;
            float dist = length(d);
            if (dist > pc.cutoff) continue;
            float t = (dist - pc.muK) / pc.sigmaK;
            float kk = pc.wK * exp(-t * t);
            u += kk;
            if (dist > 1e-10) {
                du += (-2.0 * t / pc.sigmaK * kk / dist) * d;
                if (dist < 1.0) {
                    float s = 1.0 - dist;
                    r += 0.5 * pc.cRep * s * s;
                    dr += (-pc.cRep * s / dist) * d;
                }
            } else {
                r += 0.5 * pc.cRep;
            }
        }
    }
    float tg = (u - pc.muG) / pc.sigmaG;
    float g = exp(-tg * tg);
    float dg = -2.0 * tg / pc.sigmaG * g;
    vec2 gradE = dr - dg * du;
    pc.particles.p[i].velocity = -gradE;
    pc.particles.p[i].u = u;
    pc.particles.p[i].e = r - g;
}
"""

        private val INTEGRATE_SHADER = """
#version 450
#extension GL_EXT_buffer_reference : require
#extension GL_EXT_scalar_block_layout : require
$STRUCT
layout(push_constant) uniform Push { Particles particles; float dt; uint count; } pc;
layout(local_size_x = $LOCAL_SIZE) in;
void main() {
    uint i = gl_GlobalInvocationID.x;
    if (i >= pc.count) return;
    pc.particles.p[i].position += pc.dt * pc.particles.p[i].velocity;
}
"""

        private val VERTEX_SHADER = """
#version 450
#extension GL_EXT_buffer_reference : require
#extension GL_EXT_scalar_block_layout : require
$STRUCT
layout(push_constant) uniform Push {
    Particles particles;
    float pointSize;
    float aspectRatio;
    float viewScale;
    vec2 viewCenter;
} pc;
layout(location = 0) out vec4 tint;
void main() {
    Particle me = pc.particles.p[gl_VertexIndex];
    vec2 c = (me.position - pc.viewCenter) / pc.viewScale;
    c.x /= pc.aspectRatio;
    gl_Position = vec4(c.x, -c.y, 0.0, 1.0);
    gl_PointSize = pc.pointSize;
    // low energy (a well-placed particle) is warm, high energy cool
    float w = clamp(-me.e, 0.0, 1.0);
    tint = vec4(mix(vec3(0.25, 0.45, 1.0), vec3(1.0, 0.75, 0.3), w), 0.9);
}
"""

        private val FRAGMENT_SHADER = """
#version 450
layout(location = 0) in vec4 tint;
layout(location = 0) out vec4 outColor;
void main() { outColor = tint; }
"""
    }
}
