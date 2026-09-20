@file:afield.CompletionCategory("Machine learning")
@file:Suppress("MemberVisibilityCanBePrivate")

package afield.stdlib

import afield.four.Renderer
import afield.four.buffers.FrameBufferGroup
import afield.four.buffers.defaultBuffer
import afield.four.modules.ComputeMesh
import afield.four.modules.ComputeModule
import afield.four.modules.ComputeModulePointer
import afield.four.modules.Mesh
import afield.four.modules.Module
import afield.four.modules.ModulePointer
import afield.four.modules.fromContext
import afield.four.modules.parameters
import afield.four.modules.pointer
import afield.four.targets.RenderTarget
import afield.stdlib.linalg.Vector2f
import org.lwjgl.vulkan.VK10.VK_BLEND_FACTOR_ONE
import org.lwjgl.vulkan.VK10.VK_BLEND_FACTOR_SRC_ALPHA
import org.lwjgl.vulkan.VK10.VK_BLEND_OP_ADD
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.random.Random

/** The knobs [ShaderNeuralParticles] takes. */
data class NeuralParticleSettings(
    /** Names the modules and buffers, so two systems in one renderer do not collide. */
    var name: String = "neuralParticles",
    /** How many particles. A few hundred thousand is comfortable; see the class KDoc for cost. */
    var particleCount: Int = 200_000,
    /**
     * Frames a particle takes to travel from `t = 0` to `t = 1`.
     *
     * This is the integrator's step count and the animation's speed at the same time, which is a
     * real constraint rather than a convenience: the field is only accurate integrated finely
     * enough, so a fast morph is a coarse one. Below about 60 the shape at `t = 1` visibly loses
     * its corners.
     */
    var stepsPerCycle: Int = 150,
    /**
     * How long a particle rests on the target once it arrives, as a multiple of the travel time.
     *
     * This is the setting that decides whether you can see the thing that was learned. A particle
     * that resets the instant it reaches `t = 1` is on the target for exactly one frame out of
     * [stepsPerCycle], so with the phases spread out only a hundred and fiftieth of the cloud is
     * ever on the shape and the rest — all still in transit — drowns it. Measured the hard way:
     * at `0f` a 300,000-particle system renders a featureless haze that looks exactly like an
     * undertrained field.
     *
     * At the default of 3 a particle spends three quarters of its life arrived, so three quarters
     * of the cloud is drawing the learned distribution at any moment and the remainder is the
     * visible stream feeding it. Set it to `0f` to see the flow alone, or very high to see only
     * the distribution.
     */
    var dwellCycles: Float = 3f,
    /**
     * How far apart in the cycle the particles start, as a fraction of a whole cycle.
     *
     * At 1 every phase is equally represented at every moment, so the whole trajectory is visible
     * at once as a standing stream and nothing ever pulses. At 0 they all travel together, which
     * reads as a single object morphing. Both are worth having on stage; the default is the
     * former because it is the one that does not have a dead moment.
     */
    var phaseSpread: Float = 1f,
    /** Point size in pixels. */
    var pointSize: Float = 1.5f,
    /** Colour a particle leaves the source with. */
    var sourceColor: Vec4 = vec(0.15, 0.45, 1.0, 1.0),
    /** Colour it arrives at the target with. */
    var targetColor: Vec4 = vec(1.0, 0.62, 0.22, 1.0),
    /** Overall brightness. With additive blending this is the exposure control. */
    var intensity: Float = 0.5f,
    /**
     * How strongly a particle's brightness is weighted toward the end of its journey, 0..1.
     *
     * Purely a display weight — it changes no trajectory. It exists because with
     * [phaseSpread] at 1 the particles are uniform in `t`, so most of them are in transit at any
     * moment and the destination competes with the haze of everything still on its way there.
     * At 0 every particle is equally bright and you are looking at the whole flow; at 1 only the
     * arrivals register and you are looking at the target distribution. The default leans toward
     * the target, because the target is the thing that was learned.
     */
    var arrivalBias: Float = 0.8f,
    /** Half-height of the visible region, in the field's normalised units. */
    var viewScale: Float = 2.6f,
    /** Centre of the visible region, in the field's normalised units. */
    var viewCenter: Vector2f = Vector2f(0f, 0f),
    /**
     * Whether to negate `y` on the way to clip space. On by default.
     *
     * Field's 2D world — FLines, and the glyph outlines `layoutText` produces — has `y` growing
     * *down*, which is AWT's convention. Writing a position straight into `gl_Position` lands it
     * in a space where the sign works out the other way, so a letterform arrives mirrored top to
     * bottom. Nothing about the flow is wrong when that happens, which is exactly why it is worth
     * a named setting rather than a buried minus sign: the picture is a plausible shape, and on a
     * symmetric target like a ring it is not even wrong.
     */
    var flipY: Boolean = true,
    /**
     * Gaussian spread, in the field's normalised units, added to each particle's source point.
     *
     * Not cosmetic. A `mixtureSource` field is trained with its source *exactly* on a set of
     * ellipses — no jitter, because jitter is what broke the curve-to-curve correspondence and
     * cost a factor of four in landing error. But that leaves only as many distinct start points
     * as there were training samples, and 300,000 particles seeded from 3,500 of them collapse
     * onto 3,500 identical trajectories, which reads on screen as a bundle of hard threads rather
     * than a cloud.
     *
     * So: train on the clean correspondence, seed slightly off it. The field is smooth, so a
     * particle starting a little to one side follows a neighbouring path. Keep this small — it is
     * extrapolation, and far from the source the field has never been asked anything.
     */
    var sourceJitter: Float = 0.02f,
    /** Seed for the source draw, so a scene reloads identically. */
    var randomSeed: Long = 1L,
)

/**
 * A particle system whose velocity field is a trained network: the step-2 deliverable of the
 * neural-particle pilot.
 *
 * Each particle is a struct in a storage buffer, a compute pass integrates it one Euler step along
 * `neuralFieldVelocity`, and a point-list draw reads the same allocation with no copy. That is the
 * `afield_four_graphics.md` "compute particles, then draw them" shape with the fluid term replaced
 * by an MLP, and it is packaged the way `ShaderDlaSystem` is: [installInto] writes the shaders
 * into your box as *source you can edit*, rather than hiding them in a component.
 *
 * ```kotlin
 * val target = flowSamples(myLine, 2000, jitter = 3.0)
 * val field = flowMatch(target, options = FlowMatchOptions(steps = 6000))
 * val particles = ShaderNeuralParticles(export = field.export()) { particleCount = 200_000 }
 * particles.installInto()
 * ```
 *
 * ### The particle
 *
 * ```glsl
 * struct Particle { vec2 position; vec2 start; vec2 velocity; float t; float pad; };
 * ```
 *
 * `start` is where the particle came from and `t` is where it is in its journey, both of which
 * exist because a flow is a *map* and not a field of forces: a particle is not wandering, it is
 * travelling from a specific source point to wherever the field takes it by `t = 1`, and when it
 * gets there it goes back and does it again. Neither could be held in a four-channel texel, which
 * is the concrete reason this is a buffer of structs.
 *
 * ### Positions are stored normalised
 *
 * The buffer holds positions in the *training frame*, not in input coordinates, and the compute
 * pass calls `neuralFieldVelocityNormalised`. Integrating in input coordinates would convert into
 * the frame and back out again on every one of [NeuralParticleSettings.stepsPerCycle] steps to
 * reach the same answer; this way the conversion is one multiply in the vertex shader. The cost
 * is that `viewScale` and `viewCenter` are in normalised units — roughly unit variance, so a
 * `viewScale` of about 2.6 frames a cloud whatever coordinates it was authored in.
 *
 * ### The staggered launch
 *
 * Particles are seeded with `t` in `[-phaseSpread, 0)` and held at `start` until their `t` turns
 * positive, so every particle's *first* cycle is integrated from `t = 0` like every later one.
 * Seeding them at a positive `t` instead would be a frame cheaper and wrong: a particle told it
 * is nine tenths of the way through a journey it has not started reads a velocity meant for
 * somewhere else entirely and is thrown clear of the picture. It recovers within a cycle, which
 * is exactly long enough for the error to look like a design decision.
 *
 * ### Cost
 *
 * One forward pass per particle per frame. The default flow-matching network is
 * `11 -> 64 -> 64 -> 2`, about 5,000 multiply-adds, so 200,000 particles is ~1 GFLOP a frame of
 * fairly unfriendly fragment-style matmul. Comfortable; a million is not obviously so. Width is
 * the cheaper dial than count — a `32 x 32` network is four times less work and a 2D flow rarely
 * needs 64.
 */
class ShaderNeuralParticles(
    /** The trained field, from `FlowMatchField.export()`. */
    val export: FlowMatchExport,
    val renderer: Renderer = fromContext(),
    configure: NeuralParticleSettings.() -> Unit = {},
) {
    val settings = NeuralParticleSettings().apply(configure)

    /** The particle state. Read by both passes, owned by neither. */
    val particleBuffer: FrameBufferGroup

    /** The network's weights, swappable between frames without recompiling anything. */
    val weightBuffer: FrameBufferGroup

    /** The integrator. */
    val computeModule: ComputeModulePointer

    /** The draw. */
    val drawModule: ModulePointer

    /** The compute shader source, as installed — editable. */
    val computeSource: String

    /** The vertex shader source, as installed — editable. */
    val vertexSource: String

    /** The fragment shader source, as installed — editable. */
    val fragmentSource: String

    init {
        require(export.dimension == 2) {
            "ShaderNeuralParticles draws a 2D field; this one is ${export.dimension}D"
        }
        require(settings.particleCount > 0) { "particleCount must be positive" }
        require(settings.stepsPerCycle > 0) { "stepsPerCycle must be positive" }

        val fieldGlsl = export.glsl(FIELD_NAME)
        computeSource = computeShader(fieldGlsl)
        vertexSource = VERTEX_SHADER
        fragmentSource = FRAGMENT_SHADER

        particleBuffer = defaultBuffer(name = "${settings.name}Particles") {
            size = (settings.particleCount.toLong() * PARTICLE_STRIDE_BYTES)
        }.toBuffer(renderer)

        weightBuffer = defaultBuffer(name = "${settings.name}Weights") {
            size = export.network.parameterCount * 4L
        }.toBuffer(renderer)

        weightBuffer.get(write = true).mapAndWrite { put(export.network.flat) }
        reset(settings.randomSeed)

        computeModule = ComputeModule(
            parameters(renderer) {
                name = "${settings.name}-integrate"
                compute = computeSource
            }
        ).pointer()
        ComputeMesh(renderer, computeModule).apply {
            x = ceil(settings.particleCount / LOCAL_SIZE.toDouble()).toInt()
            y = 1
            z = 1
            push.bind("particleBuffer") { particleBuffer }
            push.bind("weights") { weightBuffer }
            push.bind("dt") { 1f / settings.stepsPerCycle }
            push.bind("dwell") { settings.dwellCycles }
            push.bind("count") { settings.particleCount }
            computeModule.current.meshes["${settings.name}-dispatch"] = this
        }

        drawModule = Module(
            parameters(renderer) {
                name = "${settings.name}-draw"
                depthTest = false
                blend = true
                // Additive, weighted by alpha: overlapping particles accumulate rather than
                // occluding, which is what makes a density visible at all at this count. Straight
                // ONE/ONE would saturate the shape to white the moment the flow converges.
                srcColor = VK_BLEND_FACTOR_SRC_ALPHA
                dstColor = VK_BLEND_FACTOR_ONE
                colorBlendOp = VK_BLEND_OP_ADD
                srcAlpha = VK_BLEND_FACTOR_ONE
                dstAlpha = VK_BLEND_FACTOR_ONE
                alphaBlendOp = VK_BLEND_OP_ADD
                primitiveType = points
                vertex = vertexSource
                fragment = fragmentSource
            }
        ).pointer()

        Mesh(renderer, drawModule).apply {
            resize(settings.particleCount, 0, drawModule)
            push.bind("particleBuffer") { particleBuffer }
            push.bind("pointSize") { settings.pointSize }
            push.bind("aspectRatio") { renderer.window.width / renderer.window.height.toFloat() }
            push.bind("viewScale") { settings.viewScale }
            push.bind("viewCenter") { settings.viewCenter }
            push.bind("yFlip") { if (settings.flipY) -1f else 1f }
            push.bind("sourceColor") { settings.sourceColor }
            push.bind("targetColor") { settings.targetColor }
            push.bind("intensity") { settings.intensity }
            push.bind("arrivalBias") { settings.arrivalBias }
            drawModule.current.meshes["${settings.name}-points"] = this
        }
    }

    /**
     * Redraws every particle's source point and restarts the cycle.
     *
     * Draws from `export.source` when the field has one — a `mixtureSource` field's source is one
     * ellipse per component, not a blob — and from a standard Gaussian otherwise, which is what
     * `flowMatch` trains a plain generator against. Draw from anything else and the field is
     * being asked a question it was never shown.
     */
    fun reset(seed: Long = settings.randomSeed) {
        val random = java.util.Random(seed)
        val spread = settings.phaseSpread.coerceIn(0f, 1f)
        val cloud = export.source
        val cloudCount = export.sourceCount
        particleBuffer.get(write = true).mapAndWrite_bytes {
            order(ByteOrder.LITTLE_ENDIAN)
            clear()
            repeat(settings.particleCount) {
                // Draw from the field's own source when it has one — see FlowMatchExport.source.
                val sx: Float
                val sy: Float
                if (cloud != null) {
                    val k = random.nextInt(cloudCount) * export.dimension
                    sx = cloud[k] + (settings.sourceJitter * random.nextGaussian()).toFloat()
                    sy = cloud[k + 1] + (settings.sourceJitter * random.nextGaussian()).toFloat()
                } else {
                    sx = random.nextGaussian().toFloat()
                    sy = random.nextGaussian().toFloat()
                }
                putFloat(sx); putFloat(sy)                       // position, at the source
                putFloat(sx); putFloat(sy)                       // start
                putFloat(0f); putFloat(0f)                       // velocity
                // Spread over travel *and* dwell, so the population is even across the whole
                // life cycle rather than piling up at the start of it.
                putFloat(-random.nextFloat() * spread * (1f + settings.dwellCycles))
                putFloat(0f)                                     // pad
            }
            rewind()
        }
    }

    /** Replaces the weights in place, with no recompilation — the network may be retrained live. */
    fun updateWeights(network: MlpWeights) {
        require(network.parameterCount == export.network.parameterCount) {
            "this system was built for a ${export.network.parameterCount}-parameter network; " +
                "a ${network.parameterCount}-parameter one is a different shader"
        }
        weightBuffer.get(write = true).mapAndWrite { put(network.flat) }
    }

    /** Adds both passes to [renderTarget]'s renderer: the integrator, then the draw. */
    fun installInto(renderTarget: RenderTarget = renderer.renderList) {
        renderer += "${settings.name}-integrate" to computeModule
        renderTarget += "${settings.name}-draw" to drawModule
    }

    /** Takes both passes back out again. */
    fun removeFrom(renderTarget: RenderTarget = renderer.renderList) {
        renderer.computeList.modules.remove("${settings.name}-integrate")
        renderTarget.modules.remove("${settings.name}-draw")
    }

    /**
     * `#version`, then *every* extension, then the generated field, then this file's own
     * declarations.
     *
     * The order is load-bearing and not obvious: `FlowMatchExport.glsl` emits its own
     * `#extension GL_EXT_buffer_reference` at the top of its block, so the scalar-layout
     * directive this file needs has to come before that block rather than after it. glslang is
     * lenient about a late `#extension` right up until it is not.
     */
    private fun computeShader(fieldGlsl: String) = buildString {
        append("#version 450\n")
        append("#extension GL_EXT_scalar_block_layout : require\n")
        append(fieldGlsl)
        append("\n")
        append(computeBody())
    }

    private fun computeBody() = buildString {
        append(PARTICLE_STRUCT).append("\n\n")
        append(
            """
            layout(push_constant) uniform Push {
                ParticleBuffer particleBuffer;
                ${FIELD_NAME}Weights weights;
                float dt;
                float dwell;
                uint count;
            } pc;

            layout(local_size_x = $LOCAL_SIZE) in;

            void main() {
                uint idx = gl_GlobalInvocationID.x;
                if (idx >= pc.count) return;

                Particle p = pc.particleBuffer.particles[idx];
                float t = p.t;

                if (t < 0.0) {
                    // Waiting to launch. Held at the source, drawn at zero alpha.
                    p.position = p.start;
                    p.velocity = vec2(0.0);
                    p.t = t + pc.dt;
                    pc.particleBuffer.particles[idx] = p;
                    return;
                }
                if (t >= 1.0 + pc.dwell) {
                    // Rested long enough. Back to the source, keeping the phase so the stream
                    // stays even.
                    p.position = p.start;
                    p.velocity = vec2(0.0);
                    p.t = t - (1.0 + pc.dwell) + pc.dt;
                    pc.particleBuffer.particles[idx] = p;
                    return;
                }
                if (t >= 1.0) {
                    // Arrived. Hold still on the target — this is the part you are meant to be
                    // looking at, and a particle that bounced straight off it would be visible
                    // for one frame in a hundred and fifty.
                    p.velocity = vec2(0.0);
                    p.t = t + pc.dt;
                    pc.particleBuffer.particles[idx] = p;
                    return;
                }

                vec2 v = ${FIELD_NAME}VelocityNormalised(pc.weights, p.position, t);
                p.position += v * pc.dt;
                p.velocity = v;
                p.t = t + pc.dt;
                pc.particleBuffer.particles[idx] = p;
            }
            """.trimIndent()
        )
        append("\n")
    }

    companion object {
        /** 8 floats: position, start, velocity, t, pad. */
        private const val PARTICLE_STRIDE_BYTES = 32

        private const val LOCAL_SIZE = 64

        /** The name the emitted field functions take. */
        private const val FIELD_NAME = "neuralField"

        private val PARTICLE_STRUCT = """
            struct Particle {
                vec2 position;
                vec2 start;
                vec2 velocity;
                float t;
                float pad;
            };

            layout(std430, buffer_reference, scalar) buffer ParticleBuffer { Particle particles[]; };
        """.trimIndent()

        private val VERTEX_SHADER = buildString {
            append(
                """
                #version 450
                #extension GL_EXT_buffer_reference : require
                #extension GL_EXT_scalar_block_layout : require

                """.trimIndent()
            )
            append(PARTICLE_STRUCT).append("\n\n")
            append(
                """
                layout(push_constant) uniform Push {
                    ParticleBuffer particleBuffer;
                    float pointSize;
                    float aspectRatio;
                    float viewScale;
                    vec2 viewCenter;
                    float yFlip;
                    vec4 sourceColor;
                    vec4 targetColor;
                    float intensity;
                    float arrivalBias;
                } pc;

                layout(location = 0) out vec4 tint;

                void main() {
                    Particle p = pc.particleBuffer.particles[gl_VertexIndex];

                    vec2 c = (p.position - pc.viewCenter) / pc.viewScale;
                    c.x /= pc.aspectRatio;
                    c.y *= pc.yFlip;
                    gl_Position = vec4(c, 0.0, 1.0);
                    gl_PointSize = pc.pointSize;

                    // Past 1 the particle has arrived and is resting; clamping means it keeps the
                    // target colour and full weight for the whole dwell.
                    float t = clamp(p.t, 0.0, 1.0);
                    // Fade in over the first twentieth of the journey so a particle that has just
                    // restarted does not pop back into a picture it left from somewhere else, and
                    // hold a particle that has not launched yet at zero.
                    float alpha = p.t < 0.0 ? 0.0 : smoothstep(0.0, 0.05, t);
                    // Weight toward the arrival. `t * t` rather than `t` because the flow's own
                    // convergence is not linear in t either: most of the tightening onto the
                    // shape happens in the last third.
                    alpha *= mix(1.0, t * t, pc.arrivalBias);
                    tint = vec4(mix(pc.sourceColor.rgb, pc.targetColor.rgb, t), alpha * pc.intensity);
                }
                """.trimIndent()
            )
            append("\n")
        }

        private val FRAGMENT_SHADER = """
            #version 450

            layout(location = 0) in vec4 tint;
            layout(location = 0) out vec4 outColor;

            void main() {
                outColor = tint;
            }
        """.trimIndent()
    }
}
