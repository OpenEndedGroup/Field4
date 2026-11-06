@file:afield.runtime.completion.CompletionCategory("ALife")
@file:Suppress("MemberVisibilityCanBePrivate")

package afield.simulation.agents

import afield.document.graph.Box
import afield.graphics.render.Mesh
import afield.graphics.render.Module
import afield.graphics.render.ModulePointer
import afield.graphics.render.before
import afield.graphics.render.parameters
import afield.graphics.render.pointer
import afield.graphics.shader.attachShader
import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.buffers.FrameBufferGroup
import afield.graphics.vulkan.buffers.defaultBuffer
import afield.graphics.vulkan.targets.RenderTarget
import afield.math.linalg.Vec4
import afield.math.linalg.vec
import org.lwjgl.vulkan.VK10.VK_BLEND_FACTOR_ONE
import org.lwjgl.vulkan.VK10.VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA
import org.lwjgl.vulkan.VK10.VK_BLEND_FACTOR_SRC_ALPHA
import org.lwjgl.vulkan.VK10.VK_BLEND_OP_ADD
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Particles that steer by a [TrailField]: the reading half of stigmergy, for any particle system.
 *
 * [TrailFollowers] keeps its particles (position and velocity, in trail pixels) in an addressable
 * buffer and moves them in one vertex-shader draw per frame. Each particle takes
 * `trailGradient(trailTex, p, weights, h)` from `#include "trail/sample"`, the gradient of
 * `dot(trail, weights)`, adds [TrailFollowerSettings.steering] times its direction to its velocity,
 * damps, clamps to a top speed and steps, then draws itself as a point over the trail's preview. Positive weights climb a channel, negative ones flee it: weights `(1, -1, 0, 0)` ride
 * species 0's roads and keep out of species 1's.
 *
 * The followers only read the field, so they can be added to a running [ShaderPhysarumSystem] (pass
 * its `trail`) or to any [TrailField] another system writes. The rule is the host box's `vertex` tab,
 * unless that tab already holds another system's rule: build the followers in a box of their own
 * (beneath the one publishing the field) to edit theirs live.
 */

/** Everything [TrailFollowers] reads; all but the count and name are read every frame. */
data class TrailFollowerSettings(
    /** Prefix for the module and buffer this registers. */
    var name: String = "followers",
    /** How many particles, 16 bytes each. */
    var count: Int = 20_000,
    /** Channel weights the particles climb: the gradient followed is that of `dot(trail, weights)`. */
    var weights: Vec4 = vec(1f, 0f, 0f, 0f),
    /** Velocity added per frame along the gradient's direction, pixels per frame per frame. */
    var steering: Float = 0.35f,
    /** Gradients weaker than this are ignored, so particles coast across empty field instead of chasing noise. */
    var threshold: Float = 0.01f,
    /** Central-difference spacing for the gradient, pixels; wider sees further and smooths more. */
    var gradientPx: Float = 3f,
    /** Velocity kept per frame, 0 to 1. */
    var damping: Float = 0.92f,
    /** Top speed, pixels per frame. */
    var maxSpeed: Float = 2.5f,
    /** Random kick per frame, pixels per frame, uniformly in `±wander`. */
    var wander: Float = 0.15f,
    /** Point size on screen, pixels. */
    var pointSize: Float = 2f,
    /**
     * Colour of a particle's point, drawn over the trail by its alpha. Pick one the trail's palette
     * does not use: followers sit on the brightest roads, where a pale colour disappears.
     */
    var color: Vec4 = vec(0.95f, 0.1f, 0.55f, 1f),
    /** Seed for the initial scatter. */
    var randomSeed: Long = 7L
)

/**
 * Gradient-following particles over a [TrailField]; see [TrailFollowerSettings]. Build on a running
 * field, then [installInto] the render list after the field's preview so the points draw over it.
 */
class TrailFollowers(
    /** The field to read. Its previous frame is bound to the shader's `trailTex`. */
    val trail: TrailField,
    configure: TrailFollowerSettings.() -> Unit = {}
) {
    /** The settings after `configure`; change them while it runs. */
    val settings = TrailFollowerSettings().apply(configure)

    /** The renderer the field is built on. */
    val renderer: Renderer get() = trail.renderer

    /** Particles: position (px), velocity (px/frame); 16 bytes each. */
    val particleBuffer: FrameBufferGroup

    /** The point-list pass that steps and draws every particle. Not registered until [installInto]. */
    val module: ModulePointer

    /** One point per particle; the `push.` constants are bound here. */
    val mesh: Mesh

    init {
        require(settings.count > 0) { "count must be positive" }
        particleBuffer = defaultBuffer(name = "${settings.name}ParticleBuffer") {
            size = settings.count.toLong() * 16L
        }.buffer
        module = Module(
            parameters(trail.renderer) {
                name = settings.name
                depthTest = false
                blend = true
                srcColor = VK_BLEND_FACTOR_SRC_ALPHA
                dstColor = VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA
                colorBlendOp = VK_BLEND_OP_ADD
                srcAlpha = VK_BLEND_FACTOR_ONE
                dstAlpha = VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA
                alphaBlendOp = VK_BLEND_OP_ADD
                primitiveType = points
                vertex = FOLLOWER_VERTEX
                fragment = FOLLOWER_FRAGMENT
                textureResolver = trail.previousTrailResolver()
            }
        ).pointer()
        module.current.before["${settings.name}-ssbo-barrier"] = before { command, _ ->
            ShaderPhysarumSystem.graphicsToGraphicsBarrier(command, particleBuffer.get())
        }.target
        mesh = Mesh(trail.renderer, module).apply {
            resize(settings.count, 0, module)
            push.bind("particles") { particleBuffer }
            push.bind("weights") { settings.weights }
            push.bind("color") { settings.color }
            push.bind("steering") { settings.steering }
            push.bind("threshold") { settings.threshold }
            push.bind("gradientPx") { settings.gradientPx }
            push.bind("damping") { settings.damping }
            push.bind("maxSpeed") { settings.maxSpeed }
            push.bind("wander") { settings.wander }
            push.bind("pointSize") { settings.pointSize }
        }
        module.current.meshes["${settings.name}-points"] = mesh
        reset(settings.randomSeed)
        // one box drives one adopted shader: a box whose own vertex tab already holds another rule
        // (a ShaderPhysarumSystem built in the same box) keeps it, and these particles run unadopted
        Box.context.get()?.let { box ->
            val own = box.getPropertyRaw("vertex", null) as? String
            if (own.isNullOrBlank() || "struct Particle" in own) box.attachShader(module)
        }
    }

    /** Scatter every particle uniformly over the field with a small random velocity. */
    fun reset(seed: Long = settings.randomSeed) {
        val random = Random(seed)
        val w = trail.settings.width.toFloat()
        val h = trail.settings.height.toFloat()
        particleBuffer.get(write = true).mapAndWrite_bytes {
            order(ByteOrder.LITTLE_ENDIAN)
            clear()
            repeat(settings.count) {
                val a = random.nextFloat() * 2f * PI.toFloat()
                putFloat(random.nextFloat() * w)
                putFloat(random.nextFloat() * h)
                putFloat(0.5f * cos(a))
                putFloat(0.5f * sin(a))
            }
            rewind()
        }
    }

    /**
     * Register the particle pass in [target], drawn over the whole of it in the same placement as
     * [TrailField.previewInto]. Register it after the preview so it draws on top.
     */
    fun installInto(target: RenderTarget = renderer.renderList): ModulePointer {
        target += settings.name to module
        return module
    }

    companion object {
        /** The follower rule, as installed into a host box's `vertex` tab. */
        val FOLLOWER_VERTEX: String = """
            #version 450
            #extension GL_EXT_buffer_reference : require
            #extension GL_EXT_scalar_block_layout : require

            struct Particle {
                vec2 positionPx;
                vec2 velocity;
            };

            layout(std430, buffer_reference, scalar) buffer ParticleBuffer {
                Particle particles[];
            };

            // the trail as it was at the end of the previous frame
            layout(set = 0, binding = 0) uniform sampler2D trailTex;

            layout(push_constant) uniform Push {
                ParticleBuffer particles;
                vec4 weights;
                vec4 color;
                float steering;
                float threshold;
                float gradientPx;
                float damping;
                float maxSpeed;
                float wander;
                float pointSize;
            } push;

            layout(location = 0) out vec4 vColor;

            ${TrailShaders.SAMPLE.prependIndent("            ").trimStart()}

            float hash(uint n) {
                n = (n << 13u) ^ n;
                n = n * (n * n * 15731u + 789221u) + 1376312589u;
                return float(n & 0x7fffffffu) / float(0x7fffffff);
            }

            void main() {
                Particle q = push.particles.particles[gl_VertexIndex];
                ivec2 dims = textureSize(trailTex, 0);

                // climb dot(trail, weights): steer along the gradient's direction, if there is one
                vec2 g = trailGradient(trailTex, q.positionPx, push.weights, push.gradientPx);
                float m = length(g);
                vec2 v = q.velocity;
                if (m > push.threshold) v += push.steering * g / m;

                uint seed = uint(gl_VertexIndex) * 1973u + uint(q.positionPx.x * 9277.0) + uint(q.positionPx.y * 26699.0);
                v += push.wander * (vec2(hash(seed), hash(seed + 1u)) * 2.0 - 1.0);
                v *= push.damping;
                float s = length(v);
                if (s > push.maxSpeed) v *= push.maxSpeed / s;

                vec2 p = mod(q.positionPx + v, vec2(dims));
                push.particles.particles[gl_VertexIndex].positionPx = p;
                push.particles.particles[gl_VertexIndex].velocity = v;

                // the same placement as TrailField.previewInto: pixel px of the field sits at clip px/dims*2-1
                gl_Position = vec4(p / vec2(dims) * 2.0 - 1.0, 0.0, 1.0);
                gl_PointSize = push.pointSize;
                vColor = push.color;
            }
        """.trimIndent()

        /** Writes the particle's colour, alpha-blended over the trail. */
        val FOLLOWER_FRAGMENT: String = """
            #version 450

            layout(location = 0) in vec4 vColor;
            layout(location = 0) out vec4 outColor;

            void main() {
                outColor = vColor;
            }
        """.trimIndent()
    }
}
