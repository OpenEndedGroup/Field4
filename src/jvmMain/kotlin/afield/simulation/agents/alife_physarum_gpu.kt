@file:afield.runtime.completion.CompletionCategory("ALife")
@file:Suppress("MemberVisibilityCanBePrivate")

package afield.simulation.agents

import afield.app.protocol.Rect
import afield.document.context.fromContext
import afield.document.graph.Box
import afield.graphics.render.Mesh
import afield.graphics.render.Module
import afield.graphics.render.ModulePointer
import afield.graphics.render.before
import afield.graphics.render.pointer
import afield.graphics.shader.attachShader
import afield.graphics.texture.HasImageView
import afield.graphics.texture.HasImageViewArray
import afield.graphics.texture.ImageView
import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.Target
import afield.graphics.vulkan.buffers.FrameBufferGroup
import afield.graphics.vulkan.buffers.VulkanBuffer
import afield.graphics.vulkan.buffers.defaultBuffer
import afield.graphics.vulkan.device.CommandBuffer
import afield.graphics.vulkan.targets.RenderTarget
import afield.math.linalg.Vec4
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.VK13
import org.lwjgl.vulkan.VK13.VK_ACCESS_2_SHADER_READ_BIT
import org.lwjgl.vulkan.VK13.VK_ACCESS_2_SHADER_WRITE_BIT
import org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_ALL_GRAPHICS_BIT
import org.lwjgl.vulkan.VK13.VK_QUEUE_FAMILY_IGNORED
import org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER_2
import org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_DEPENDENCY_INFO
import org.lwjgl.vulkan.VkBufferMemoryBarrier2
import org.lwjgl.vulkan.VkDependencyInfo
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Jones' Physarum on the GPU, built on the [TrailField] stigmergy field in `alife_trail.kt`.
 *
 * [ShaderPhysarumSystem] keeps a million agents (by default) in an addressable buffer and steps
 * them in one vertex-shader draw per frame: each agent samples the previous frame's trail at three
 * sensors, turns toward the strongest, steps, writes itself back and deposits one additive point.
 * Up to four species share the field, one per channel, each attracted by its own trail and repelled
 * by the others. `sensing = false` is the random-turn control arm, so [Img.topShare] of the trail
 * read back with [TrailField.read] separates a network from a random walk, as it does for the CPU
 * [PhysarumSwarm].
 *
 * Unlike the CPU model there is no one-agent-per-cell exclusion; at about one agent per pixel the
 * density does that job.
 */

/**
 * Everything [ShaderPhysarumSystem] reads. The defaults are the CPU [PhysarumParams] defaults, so
 * the two can be compared: 45° sensors 9 pixels ahead, 45° turns, one pixel a step, a radius-one
 * blur and `decay = 0.92` (the [trail]'s half-life of 8.3 frames).
 *
 * Read every frame, except the sizes and [spawnRadiusFraction], which are resolved at construction.
 */
data class ShaderPhysarumSettings(
    /** Prefix for the modules and buffer this system registers; its trail field is named `"<name>-trail"`. */
    var name: String = "physarum",
    /** Field width in pixels. */
    var width: Int = 1024,
    /** Field height in pixels. */
    var height: Int = 1024,
    /** How many agents, 16 bytes each. A million at 1024x1024 is about one per pixel. */
    var agentCount: Int = 1_000_000,
    /**
     * How many species, 1 to 4. Agent `i` is species `i % speciesCount` and deposits into channel
     * `species` of the trail; it is attracted by its own channel and repelled by the others by
     * [repulsion].
     */
    var speciesCount: Int = 1,
    /** Angle between the centre sensor and each side sensor, radians. */
    var sensorAngle: Float = (PI / 4).toFloat(),
    /** How far ahead the sensors look, pixels. */
    var sensorDistance: Float = 9f,
    /** How far an agent turns when a side sensor wins, radians. */
    var turnAngle: Float = (PI / 4).toFloat(),
    /** Step length per frame, pixels. */
    var stepPx: Float = 1f,
    /** Trail added per agent per frame. Only ratios matter to the agents; this sets the brightness. */
    var deposit: Float = 1f,
    /** How strongly another species' trail counts against a sensor, relative to an agent's own. */
    var repulsion: Float = 1f,
    /** Random extra turn per frame, radians, uniformly in `±jitter`. */
    var jitter: Float = 0f,
    /** `false` turns at random instead of following the trail: the control arm, as in [PhysarumParams.sensing]. */
    var sensing: Boolean = true,
    /** Point size for the deposit splat. */
    var pointSize: Float = 1f,
    /**
     * Agents start in a disc of this fraction of the smaller side, headings random; `0` or less
     * scatters them uniformly over the field instead.
     */
    var spawnRadiusFraction: Float = 0f,
    /** Exposure of the default preview tone map; see [TrailField.previewInto]. */
    var exposure: Float = 0.08f,
    /** Seed for the initial placement, handed to [ShaderPhysarumSystem.resetAgents] at construction. */
    var randomSeed: Long = 1L
)

/**
 * Jones' Physarum on the GPU: up to millions of agents in an addressable buffer that sense, turn,
 * move and deposit into a [TrailField], one vertex-shader invocation per agent per frame.
 *
 * The CPU [PhysarumSwarm] is the one to measure carefully (it has Jones' one-agent-per-cell
 * exclusion, which a GPU cannot do cheaply); this one reaches the agent density where the network
 * forms without exclusion, at window resolution and frame rate. Its [trail] reads back as floats,
 * so [Img.topShare] measures it the same way.
 *
 * As in [ShaderDlaSystem], the agent step and the deposit are one draw: the vertex shader reads its
 * agent, samples the previous frame's trail at three sensors, turns, moves, writes the agent back
 * and emits one additive point where it now stands. Building one inside a box adopts that shader
 * into the box's `vertex`/`fragment` tabs, so the rule is editable source.
 */
class ShaderPhysarumSystem(
    /** The renderer to build on; by default the one this box is running in. */
    val renderer: Renderer = fromContext(),
    configure: ShaderPhysarumSettings.() -> Unit = {}
) : Target, HasImageView, HasImageViewArray {

    /** The settings after `configure`; change them while it runs. */
    val settings = ShaderPhysarumSettings().apply(configure)

    /** The trail the agents write and read. Its own settings (half-life, diffusion, wind) are live too. */
    val trail: TrailField

    /** Agents: position (px), heading (radians), RNG state; 16 bytes each. */
    val agentBuffer: FrameBufferGroup

    /** The point-list pass that steps and deposits every agent. */
    val agentModule: ModulePointer

    /** One point per agent; the `push.` constants of the agent shader are bound here. */
    val agentMesh: Mesh

    private var lastSeed = settings.randomSeed

    init {
        require(settings.agentCount > 0) { "agentCount must be positive" }
        require(settings.speciesCount in 1..4) { "speciesCount must be 1..4" }
        trail = TrailField(renderer) {
            name = "${settings.name}-trail"
            width = settings.width
            height = settings.height
        }
        agentBuffer = defaultBuffer(name = "${settings.name}AgentBuffer") {
            size = settings.agentCount.toLong() * AGENT_STRIDE_BYTES
        }.buffer

        agentModule = Module(
            trail.depositParameters("${settings.name}-agents") {
                primitiveType = points
                vertex = AGENT_VERTEX
                fragment = AGENT_FRAGMENT
            }
        ).pointer()
        agentModule.current.before["${settings.name}-agent-ssbo-barrier"] = before { command, _ ->
            graphicsToGraphicsBarrier(command, agentBuffer.get())
        }.target
        agentMesh = Mesh(renderer, agentModule).apply {
            resize(settings.agentCount, 0, agentModule)
            push.bind("agentBuffer") { agentBuffer }
            push.bind("sensorAngle") { settings.sensorAngle }
            push.bind("sensorDistance") { settings.sensorDistance }
            push.bind("turnAngle") { settings.turnAngle }
            push.bind("stepPx") { settings.stepPx }
            push.bind("deposit") { settings.deposit }
            push.bind("repulsion") { settings.repulsion }
            push.bind("jitter") { settings.jitter }
            push.bind("sensing") { if (settings.sensing) 1f else 0f }
            push.bind("pointSize") { settings.pointSize }
            push.bind("speciesCount") { settings.speciesCount.toFloat() }
        }
        agentModule.current.meshes["${settings.name}-agents"] = agentMesh
        trail.addDeposit("agents", agentModule)

        resetAgents(settings.randomSeed)
        Box.context.get()?.attachShader(agentModule)
    }

    override val imageView: ImageView get() = trail.imageView
    override val imageViewArray: List<HasImageView> get() = trail.imageViewArray

    override fun render(command: CommandBuffer, index: Int) = trail.render(command, index)

    /** Place every agent again (see [ShaderPhysarumSettings.spawnRadiusFraction]); the trail is untouched. Call [TrailField.clear] too for a fresh start. */
    fun resetAgents(seed: Long = lastSeed) {
        lastSeed = seed
        val random = Random(seed)
        val w = settings.width.toFloat()
        val h = settings.height.toFloat()
        val radius = settings.spawnRadiusFraction * minOf(w, h) * 0.5f
        agentBuffer.get(write = true).mapAndWrite_bytes {
            order(ByteOrder.LITTLE_ENDIAN)
            clear()
            repeat(settings.agentCount) {
                if (radius > 0f) {
                    val r = radius * sqrt(random.nextFloat())
                    val a = random.nextFloat() * 2f * PI.toFloat()
                    putFloat(w * 0.5f + r * cos(a))
                    putFloat(h * 0.5f + r * sin(a))
                } else {
                    putFloat(random.nextFloat() * w)
                    putFloat(random.nextFloat() * h)
                }
                putFloat(random.nextFloat() * 2f * PI.toFloat())
                putInt(random.nextInt().let { if (it == 0) 1 else it })
            }
            rewind()
        }
    }

    /**
     * Register the system so it steps once a frame and, by default, draw it tone-mapped (species
     * coloured by [TrailField.DEFAULT_PALETTE] unless [palette] says otherwise). Returns the preview
     * module, or `null` with `withPreview = false`.
     */
    fun installInto(
        renderTarget: RenderTarget = renderer.renderList,
        withPreview: Boolean = true,
        previewViewport: Rect? = null,
        palette: List<Vec4> = TrailField.DEFAULT_PALETTE
    ): ModulePointer? {
        renderTarget.preTargets["${settings.name}-simulation"] = this
        return if (withPreview) trail.previewInto(renderTarget, "${settings.name}-preview", previewViewport,
            exposure = { settings.exposure }, palette = palette) else null
    }

    companion object {
        private const val AGENT_STRIDE_BYTES = 16

        /** The agent rule, as installed into a host box's `vertex` tab. */
        val AGENT_VERTEX: String = """
            #version 450
            #extension GL_EXT_buffer_reference : require
            #extension GL_EXT_scalar_block_layout : require

            struct Agent {
                vec2 positionPx;
                float heading;
                uint rngState;
            };

            layout(std430, buffer_reference, scalar) buffer AgentBuffer {
                Agent agents[];
            };

            // the trail as it was at the end of the previous frame
            layout(set = 0, binding = 0) uniform sampler2D trailTex;

            layout(push_constant) uniform Push {
                AgentBuffer agentBuffer;
                float sensorAngle;
                float sensorDistance;
                float turnAngle;
                float stepPx;
                float deposit;
                float repulsion;
                float jitter;
                float sensing;
                float pointSize;
                float speciesCount;
            } push;

            layout(location = 0) out vec4 vColor;

            ${TrailShaders.SAMPLE.prependIndent("            ").trimStart()}

            uint nextRandom(inout uint state) {
                state = state * 747796405u + 2891336453u;
                uint word = ((state >> ((state >> 28u) + 4u)) ^ state) * 277803737u;
                return (word >> 22u) ^ word;
            }

            float random01(inout uint state) {
                return float(nextRandom(state)) / 4294967295.0;
            }

            float sense(vec2 p, float angle, vec4 weights) {
                vec2 at = p + push.sensorDistance * vec2(cos(angle), sin(angle));
                return dot(trailAt(trailTex, at), weights);
            }

            void main() {
                Agent agent = push.agentBuffer.agents[gl_VertexIndex];
                ivec2 dims = textureSize(trailTex, 0);
                uint rng = agent.rngState;
                int species = int(mod(float(gl_VertexIndex), max(push.speciesCount, 1.0)));
                vec4 own = vec4(0.0);
                own[species] = 1.0;
                vec4 weights = own * (1.0 + push.repulsion) - vec4(push.repulsion);
                if (push.speciesCount < 1.5) weights = own;

                float a = agent.heading;
                if (push.sensing > 0.5) {
                    float f = sense(agent.positionPx, a, weights);
                    float l = sense(agent.positionPx, a + push.sensorAngle, weights);
                    float r = sense(agent.positionPx, a - push.sensorAngle, weights);
                    if (f > l && f > r) {
                        // straight on
                    } else if (f < l && f < r) {
                        a += (random01(rng) < 0.5 ? -1.0 : 1.0) * push.turnAngle;
                    } else if (l > r) {
                        a += push.turnAngle;
                    } else if (r > l) {
                        a -= push.turnAngle;
                    }
                } else {
                    a += (floor(random01(rng) * 3.0) - 1.0) * push.turnAngle;
                }
                a += (random01(rng) * 2.0 - 1.0) * push.jitter;

                vec2 p = mod(agent.positionPx + push.stepPx * vec2(cos(a), sin(a)), vec2(dims));

                push.agentBuffer.agents[gl_VertexIndex].positionPx = p;
                push.agentBuffer.agents[gl_VertexIndex].heading = mod(a, 6.28318530718);
                push.agentBuffer.agents[gl_VertexIndex].rngState = max(rng, 1u);

                gl_Position = trailClip(p, dims);
                gl_PointSize = push.pointSize;
                vColor = own * push.deposit;
            }
        """.trimIndent()

        /** Writes the agent's deposit; additive blending accumulates it. */
        val AGENT_FRAGMENT: String = """
            #version 450

            layout(location = 0) in vec4 vColor;
            layout(location = 0) out vec4 outColor;

            void main() {
                outColor = vColor;
            }
        """.trimIndent()

        private fun graphicsToGraphicsBarrier(command: CommandBuffer, buffer: VulkanBuffer) {
            MemoryStack.stackPush().use { stack ->
                val barrier = VkBufferMemoryBarrier2.calloc(1, stack)
                barrier[0]
                    .sType(VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER_2)
                    .srcStageMask(VK_PIPELINE_STAGE_2_ALL_GRAPHICS_BIT)
                    .srcAccessMask(VK_ACCESS_2_SHADER_WRITE_BIT)
                    .dstStageMask(VK_PIPELINE_STAGE_2_ALL_GRAPHICS_BIT)
                    .dstAccessMask(VK_ACCESS_2_SHADER_READ_BIT or VK_ACCESS_2_SHADER_WRITE_BIT)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .buffer(buffer.buffer)
                    .offset(0L)
                    .size(buffer.requestedSize)
                val dependency = VkDependencyInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DEPENDENCY_INFO)
                    .pBufferMemoryBarriers(barrier)
                VK13.vkCmdPipelineBarrier2(command.vkCommandBuffer, dependency)
            }
        }
    }
}
