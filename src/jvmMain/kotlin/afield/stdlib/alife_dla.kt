@file:afield.CompletionCategory("ALife")
@file:Suppress("MemberVisibilityCanBePrivate")

package afield.stdlib

import afield.Box
import afield.Rect
import afield.four.Renderer
import afield.four.Target
import afield.four.buffers.FrameBufferGroup
import afield.four.buffers.defaultBuffer
import afield.four.device.CommandBuffer
import afield.four.modules.Mesh
import afield.four.modules.Module
import afield.four.modules.ModulePointer
import afield.four.modules.Parameters
import afield.four.modules.before
import afield.four.modules.fromContext
import afield.four.modules.parameters
import afield.four.modules.pointer
import afield.four.targets.PingPongRenderToTexture
import afield.four.targets.RenderTarget
import afield.four.texture.HasImageView
import afield.four.texture.HasImageViewArray
import afield.four.texture.ImageView
import afield.four.texture.TextureSampler
import afield.stdlib.linalg.Vector2f
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.VK13
import org.lwjgl.vulkan.VK10.VK_ATTACHMENT_STORE_OP_STORE
import org.lwjgl.vulkan.VK10.VK_BLEND_FACTOR_ONE
import org.lwjgl.vulkan.VK10.VK_BLEND_OP_ADD
import org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_SFLOAT
import org.lwjgl.vulkan.VK13.VK_ACCESS_2_SHADER_READ_BIT
import org.lwjgl.vulkan.VK13.VK_ACCESS_2_SHADER_WRITE_BIT
import org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_ALL_GRAPHICS_BIT
import org.lwjgl.vulkan.VK13.VK_QUEUE_FAMILY_IGNORED
import org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER_2
import org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_DEPENDENCY_INFO
import org.lwjgl.vulkan.VkBufferMemoryBarrier2
import org.lwjgl.vulkan.VkDependencyInfo
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.random.Random

/**
 * Diffusion-limited aggregation on the GPU: a buffer of random walkers stepped, stuck and drawn by a
 * single vertex shader, with the growing cluster held in a texture rather than in any Kotlin data
 * structure.
 *
 * The CPU version of the same process is [dlaCluster] in `alife_agents.kt`, and the two answer
 * different questions. `dlaCluster` returns points you can measure, with [massRadiusDimension] and a
 * control arm to measure them against. What is here returns an image, at a walker count the CPU
 * version cannot reach, and nothing reads it back. Measure DLA with `alife_agents.kt`; watch one grow
 * at window resolution with this.
 *
 * #### The state is three channels of one texture
 *
 * [ShaderDlaSystem] renders into a single RGBA16F ping-pong target, and every part of the simulation
 * except the walkers themselves lives in its channels:
 *
 * - red is solidity, and a pixel counts as part of the cluster once red reaches `solidThreshold`;
 * - green marks where the walkers are this frame, and nothing carries it forward;
 * - blue is age, rising while a pixel is solid and falling back when it stops being, which is what a
 *   colour ramp reads if you want tips to look younger than the interior.
 *
 * #### Two draws per frame, and no compute pass
 *
 * The target is cleared at the start of every frame, so a full-screen `preserve` pass runs first and
 * rebuilds the carried state by sampling the *previous* frame's image: red decays, blue grows or
 * decays, and the seed disc is held solid. The particle pass then draws one additively blended point
 * per walker. Both read the previous frame rather than the one being built, and they run in the order
 * they were added to the target.
 *
 * All of the simulation is in that second pass's *vertex* shader. It reads its walker from an
 * addressable buffer through a push-constant pointer, probes the four neighbours of its pixel in the
 * previous frame's red channel, and either sticks where it stands or takes one axis-aligned random
 * step; then it writes the walker back. Drawing the point and advancing the simulation are the same
 * draw call.
 *
 * #### A stuck walker lasts one frame
 *
 * A walker that sticks emits one red splat and is respawned at a uniformly random position on the
 * next frame, so `particleCount` bounds how many walkers are in flight rather than how large the
 * cluster can get. The cluster is only ever the red channel, which is also why it is mortal: red
 * loses `solidDecayPerFrame` every frame whether or not anything is still attaching to it, so at the
 * defaults a pixel that took one splat and nothing since falls back under the threshold about 500
 * frames later.
 *
 * #### Getting one on screen
 *
 * `installInto` registers the simulation as a pre-target of the render list and blits the result to
 * the window. Constructing the system inside a box also seeds that box's shader properties from the
 * particle pass, so the walk arrives as source you can edit rather than as a closed component; the
 * diffusion-limited aggregation section of
 * [`afield_four_graphics.md`](../afield_four_graphics.md) is the worked version of that, and says why
 * it carries no figure.
 */

/**
 * Everything [ShaderDlaSystem] reads, from texture size down to the two colours a walker writes.
 *
 * The system binds most of these as lambdas over this object rather than copying them, so changing
 * one while the simulation runs takes effect on the next frame. `seedPositionPx` is the exception: it
 * is resolved once, in the constructor.
 */
data class ShaderDlaSettings(
    /**
     * Prefix for everything this system registers by name: its two modules, their meshes, the
     * particle buffer, the barrier, and the entries it makes in a render target's pre-target and
     * module maps. Two systems in one renderer need two names.
     */
    var name: String = "dla",
    /**
     * Width of the simulation texture in pixels, which is also the coordinate system the walkers step
     * in: positions are pixels, not normalised.
     */
    var width: Int = 1024,
    /** Height of the simulation texture in pixels. Must be positive, as must [width]. */
    var height: Int = 1024,
    /**
     * How many walkers are in flight at once, one 16-byte record each. Because a stuck walker is
     * respawned immediately this is a growth rate rather than a bound on the cluster, and it trades
     * off against [width] and [height]: too few walkers for the area and the cluster takes thousands
     * of frames to become visible, too many and the whole field sticks in the first few.
     */
    var particleCount: Int = 65_536,
    /** `gl_PointSize` for the drawn walkers and splats. 1 draws a single pixel each. */
    var pointSize: Float = 1f,
    /** How far a walker moves each frame, along one of the four axis directions. */
    var walkStepPx: Float = 1f,
    /**
     * How far from a walker the four neighbour probes are taken, rounded and floored to one pixel.
     * Raising it lets walkers stick before they touch, which thickens the branches.
     */
    var sampleStepPx: Float = 1f,
    /**
     * The red value at which a pixel counts as solid. Both the neighbour probe and the age channel
     * ask this question, so it is one threshold for sticking and for colouring.
     */
    var solidThreshold: Float = 0.5f,
    /**
     * The green a walker adds to the pixel it is standing on. Nothing reads green back, and the
     * preserve pass does not carry it forward, so this is a live view of the diffusing cloud.
     */
    var walkerGreen: Float = 0.12f,
    /**
     * The red a walker adds at the moment it sticks. This is the only thing that grows the cluster,
     * and it is added to what is already there, so a pixel several walkers have found reads above 1
     * and takes proportionally longer to decay away.
     */
    var stuckRed: Float = 1f,
    /** The blue added alongside [stuckRed], so a new branch tip starts with some age rather than none. */
    var stuckBlue: Float = 0.35f,
    /**
     * Centre of the disc the preserve pass holds solid, in pixels; `null` puts it at the middle of the
     * texture. Something has to be solid before anything can stick to it, so this is where the cluster
     * starts. Resolved once in the constructor, unlike the rest of these.
     */
    var seedPositionPx: Vector2f? = null,
    /** Radius of that disc in pixels. */
    var seedRadiusPx: Float = 1.5f,
    /**
     * How much red every pixel outside the seed disc loses per frame. At 0 the cluster is permanent;
     * at the default of 0.001 a branch fades once walkers stop reinforcing it, which is what keeps a
     * long-running field from filling in.
     */
    var solidDecayPerFrame: Float = 0.001f,
    /** How much blue a pixel below [solidThreshold] loses per frame. */
    var ageDecayPerFrame: Float = 0.004f,
    /** How much blue a solid pixel gains per frame, up to 1. At the default that is 100 frames to full age. */
    var ageGrowPerFrame: Float = 0.01f,
    /** The blue the seed disc is held at, so the oldest part of the cluster reads as old from frame one. */
    var seedBlue: Float = 0.15f,
    /**
     * Colour format of the ping-pong target. A float format is doing work here: `solidDecayPerFrame`
     * at its default is smaller than one step of an 8-bit channel, so an 8-bit target would round each
     * frame's decay away and nothing would ever fade.
     */
    var format: Int = VK_FORMAT_R16G16B16A16_SFLOAT,
    /**
     * Clear value for the target's colour attachment. The preserve pass covers every pixel, so this is
     * what the first frame starts from rather than a background that stays visible behind the cluster.
     */
    var clearColor: Vec4 = vec(0, 0, 0, 1),
    /** Seed for the initial scatter of walkers, handed to [ShaderDlaSystem.resetParticles] at construction. */
    var randomSeed: Long = 1L
)

/**
 * A shader-driven DLA system: two draws into a ping-pong target, with the walkers in an addressable
 * buffer and the cluster in the target's red channel.
 *
 * Build one, call [installInto], and it runs. The file overview above describes what the channels
 * mean and why a stuck walker only lasts a frame; the members below are the pieces of it that a
 * caller can reach.
 *
 * Constructing this inside a box adopts the particle pass into that box's hot-reload machinery, so
 * the box's `vertex` and `fragment` tabs are seeded from the walk itself and editing them re-runs it.
 * The preserve pass stays out of the way. A box that already has shader properties keeps them.
 */
class ShaderDlaSystem(
    /** The renderer to build the target, modules and buffer on; by default the one this box is running in. */
    val renderer: Renderer = fromContext(),
    configure: ShaderDlaSettings.() -> Unit = {}
) : Target, HasImageView, HasImageViewArray {

    /**
     * The settings this system was built with, after `configure` ran. Most of them are read fresh
     * from here every frame, so this is the handle for changing the simulation while it runs.
     */
    val settings = ShaderDlaSettings().apply(configure)

    /**
     * The walkers: position, RNG state and a stuck flag, 16 bytes each, host-visible and addressable
     * so the vertex shader can reach them through a push-constant pointer. It is one allocation
     * shared by all frames in flight rather than one per frame, which is what makes the walk persist
     * and what the buffer barrier in front of the particle pass is for.
     */
    val particleBuffer: FrameBufferGroup = defaultBuffer(name = "${settings.name}ParticleBuffer") {
        size = (settings.particleCount * PARTICLE_STRIDE_BYTES).toLong()
    }.buffer

    /** The ping-pong target holding the cluster. Both passes render into it and both sample the frame before. */
    val simulation = PingPongRenderToTexture(renderer) {
        width = settings.width
        height = settings.height
        format = settings.format
        colorFormats = listOf(settings.format)
        clearColor = settings.clearColor
        clearFirst = true
        colorStoreOp = VK_ATTACHMENT_STORE_OP_STORE
        colorSampled = true
        useDepth = false
    }

    /**
     * The full-screen pass that carries the cluster forward: it samples the previous frame, decays
     * red and blue, and holds the seed disc solid. Without it the per-frame clear would erase
     * everything the walkers have built.
     */
    val preserveModule: ModulePointer

    /**
     * The point-list pass that both advances and draws the walkers. This is the module a surrounding
     * box adopts, so its GLSL is what appears in the box's shader tabs.
     */
    val particleModule: ModulePointer

    /**
     * One point per walker, and where the settings reach the shader: every `push.` constant the walk
     * reads is bound here as a lambda over [settings].
     */
    val particleMesh: Mesh

    private val sourceSampler = TextureSampler(renderer, 1, false)
    private var lastRandomSeed = settings.randomSeed

    init {
        require(settings.width > 0) { "width must be positive" }
        require(settings.height > 0) { "height must be positive" }
        require(settings.particleCount > 0) { "particleCount must be positive" }

        val seedPosition = settings.seedPositionPx ?: Vector2f(settings.width * 0.5f, settings.height * 0.5f)

        preserveModule = Module(
            parameters(renderer) {
                this@ShaderDlaSystem.simulation.apply()
                name = "${settings.name}-preserve"
                depthTest = false
                blend = false
                vertex = FULLSCREEN_TRIANGLE_VERTEX
                fragment = PRESERVE_FRAGMENT
                textureResolver = sourceTextureResolver()
            }
        ).pointer()
        Mesh(renderer, preserveModule).apply {
            resize(3, 0, preserveModule)
            push.bind("seedPositionPx") { seedPosition }
            push.bind("seedRadiusPx") { settings.seedRadiusPx }
            push.bind("solidThreshold") { settings.solidThreshold }
            push.bind("solidDecayPerFrame") { settings.solidDecayPerFrame }
            push.bind("ageDecayPerFrame") { settings.ageDecayPerFrame }
            push.bind("ageGrowPerFrame") { settings.ageGrowPerFrame }
            push.bind("seedBlue") { settings.seedBlue }
            preserveModule.current.meshes["${settings.name}-preserve-quad"] = this
        }

        particleModule = Module(
            parameters(renderer) {
                this@ShaderDlaSystem.simulation.apply()
                name = "${settings.name}-particles"
                depthTest = false
                blend = true
                srcColor = VK_BLEND_FACTOR_ONE
                dstColor = VK_BLEND_FACTOR_ONE
                colorBlendOp = VK_BLEND_OP_ADD
                srcAlpha = VK_BLEND_FACTOR_ONE
                dstAlpha = VK_BLEND_FACTOR_ONE
                alphaBlendOp = VK_BLEND_OP_ADD
                primitiveType = points
                vertex = PARTICLE_VERTEX
                fragment = PARTICLE_FRAGMENT
                textureResolver = sourceTextureResolver()
            }
        ).pointer()
        particleModule.current.before["${settings.name}-particle-ssbo-barrier"] = before { command, _ ->
            graphicsToGraphicsBarrier(command, particleBuffer.get())
        }.target

        particleMesh = Mesh(renderer, particleModule).apply {
            resize(settings.particleCount, 0, particleModule)
            push.bind("particleBuffer") { particleBuffer }
            push.bind("pointSize") { settings.pointSize }
            push.bind("walkStepPx") { settings.walkStepPx }
            push.bind("sampleStepPx") { settings.sampleStepPx }
            push.bind("solidThreshold") { settings.solidThreshold }
            push.bind("walkerGreen") { settings.walkerGreen }
            push.bind("stuckRed") { settings.stuckRed }
            push.bind("stuckBlue") { settings.stuckBlue }
        }
        particleModule.current.meshes["${settings.name}-points"] = particleMesh

        simulation += "preserve" to preserveModule
        simulation += "particles" to particleModule

        resetParticles(settings.randomSeed)
        Box.context.get()?.attachShader(particleModule)
    }

    /**
     * The cluster as a texture, so a system can be sampled by any other module the way a loaded image
     * would be. Red is solidity, blue is age, and green is whatever the walkers did this frame.
     */
    override val imageView: ImageView
        get() = simulation.imageView

    /** The same single colour attachment, in the list form the array-sampling path asks for. */
    override val imageViewArray: List<HasImageView>
        get() = simulation.imageViewArray

    /**
     * Runs both passes for one frame. Called by whatever the system was registered with rather than
     * by hand; [installInto] is the usual way to arrange that.
     */
    override fun render(command: CommandBuffer, index: Int) {
        simulation.render(command, index)
    }

    /**
     * Scatter the walkers uniformly again, all of them unstuck. The cluster is in the texture and is
     * untouched by this, so it restarts the diffusing half of the process against the structure
     * already grown. Called once at construction with [ShaderDlaSettings.randomSeed]; passing no seed
     * reuses the last one.
     */
    fun resetParticles(seed: Long = lastRandomSeed) {
        lastRandomSeed = seed
        val random = Random(seed)
        val maxX = max(0f, settings.width - 1f)
        val maxY = max(0f, settings.height - 1f)

        particleBuffer.get(write = true).mapAndWrite_bytes {
            order(ByteOrder.LITTLE_ENDIAN)
            clear()
            repeat(settings.particleCount) {
                putFloat(random.nextFloat() * maxX)
                putFloat(random.nextFloat() * maxY)
                val state = random.nextInt().let { if (it == 0) 1 else it }
                putInt(state)
                putInt(0)
            }
            rewind()
        }
    }

    /**
     * Wire the simulation into a render target and, by default, show it. The system is added as a
     * pre-target, so both of its passes run before that target draws its own contents, and the
     * result is then blitted to the window.
     *
     * Pass `withPreview = false` to run the simulation without drawing it, for a scene that samples
     * [imageView] itself. Returns the preview module, or `null` when there is no preview.
     */
    fun installInto(
        renderTarget: RenderTarget = renderer.renderList,
        withPreview: Boolean = true,
        previewName: String = "${settings.name}-preview",
        previewViewport: Rect? = null
    ): ModulePointer? {
        renderTarget.preTargets["${settings.name}-simulation"] = this
        return if (withPreview) {
            blitToScreen(
                target = renderTarget,
                name = previewName,
                viewport = previewViewport
            )
        } else {
            null
        }
    }

    /**
     * Draw the cluster to a target without registering the simulation with it, for a second view of
     * a system [installInto] is already running, or for placing the picture by hand.
     *
     * `viewport` is where on the destination it lands and `sourceRect` which part of the simulation
     * texture is taken, both defaulting to the whole of each. `blend` and `additive` choose how it
     * combines with what is already there; with neither, it overwrites.
     */
    fun blitToScreen(
        target: RenderTarget = renderer.renderList,
        name: String = "${settings.name}-preview",
        viewport: Rect? = null,
        sourceRect: Rect? = null,
        blend: Boolean = false,
        additive: Boolean = false
    ): ModulePointer {
        return simulation.blitToScreen(
            renderer = renderer,
            target = target,
            name = name,
            viewport = viewport,
            sourceRect = sourceRect,
            blend = blend,
            additive = additive
        )
    }

    private fun sourceTextureResolver(): (afield.four.modules.Introspected.Texture) -> Pair<HasImageViewArray, TextureSampler>? = { tex ->
        if (tex.name == "sourceTex") {
            object : HasImageViewArray {
                override val imageViewArray: List<HasImageView>
                    get() = listOf(simulation.otherImageView)
            } to sourceSampler
        } else {
            null
        }
    }

    companion object {
        private const val PARTICLE_STRIDE_BYTES = 16

        private val FULLSCREEN_TRIANGLE_VERTEX = """
            #version 450

            vec2 fullscreenVertex(uint index) {
                if (index == 0u) return vec2(-1.0, -1.0);
                if (index == 1u) return vec2(3.0, -1.0);
                return vec2(-1.0, 3.0);
            }

            void main() {
                gl_Position = vec4(fullscreenVertex(uint(gl_VertexIndex)), 0.0, 1.0);
            }
        """.trimIndent()

        private val PRESERVE_FRAGMENT = """
            #version 450

            layout(set = 0, binding = 0) uniform sampler2D sourceTex;

            layout(push_constant) uniform Push {
                vec2 seedPositionPx;
                float seedRadiusPx;
                float solidThreshold;
                float solidDecayPerFrame;
                float ageDecayPerFrame;
                float ageGrowPerFrame;
                float seedBlue;
            } push;

            layout(location = 0) out vec4 outColor;

            void main() {
                ivec2 dims = textureSize(sourceTex, 0);
                ivec2 pixel = clamp(ivec2(gl_FragCoord.xy), ivec2(0), dims - 1);
                vec4 previous = texelFetch(sourceTex, pixel, 0);
                bool inSeed = distance(gl_FragCoord.xy, push.seedPositionPx) <= push.seedRadiusPx;

                float decayedSolid = max(previous.r - push.solidDecayPerFrame, 0.0);
                float solid = max(decayedSolid, inSeed ? 1.0 : 0.0);

                float decayedAge = max(previous.b - push.ageDecayPerFrame, 0.0);
                float age = solid >= push.solidThreshold
                    ? min(decayedAge + push.ageGrowPerFrame, 1.0)
                    : decayedAge;
                if (inSeed) age = max(age, push.seedBlue);

                outColor = vec4(solid, 0.0, age, 1.0);
            }
        """.trimIndent()

        private val PARTICLE_VERTEX = """
            #version 450
            #extension GL_EXT_buffer_reference : require
            #extension GL_EXT_scalar_block_layout : require

            struct Particle {
                vec2 positionPx;
                uint rngState;
                uint mode;
            };

            layout(std430, buffer_reference, scalar) buffer ParticleBuffer {
                Particle particles[];
            };

            layout(set = 0, binding = 0) uniform sampler2D sourceTex;

            layout(push_constant) uniform Push {
                ParticleBuffer particleBuffer;
                float pointSize;
                float walkStepPx;
                float sampleStepPx;
                float solidThreshold;
                float walkerGreen;
                float stuckRed;
                float stuckBlue;
            } push;

            layout(location = 0) out vec4 vColor;

            uint nextRandom(inout uint state) {
                state = state * 747796405u + 2891336453u;
                uint word = ((state >> ((state >> 28u) + 4u)) ^ state) * 277803737u;
                return (word >> 22u) ^ word;
            }

            float random01(inout uint state) {
                return float(nextRandom(state)) / 4294967295.0;
            }

            vec2 randomPosition(inout uint state, ivec2 dims) {
                return vec2(
                    random01(state) * float(max(dims.x - 1, 0)),
                    random01(state) * float(max(dims.y - 1, 0))
                );
            }

            vec2 randomWalkStep(inout uint state, float stepPx) {
                uint choice = nextRandom(state) & 3u;
                if (choice == 0u) return vec2(stepPx, 0.0);
                if (choice == 1u) return vec2(-stepPx, 0.0);
                if (choice == 2u) return vec2(0.0, stepPx);
                return vec2(0.0, -stepPx);
            }

            bool isSolidAt(ivec2 pixel, ivec2 dims, float threshold) {
                ivec2 clampedPixel = clamp(pixel, ivec2(0), dims - 1);
                return texelFetch(sourceTex, clampedPixel, 0).r >= threshold;
            }

            void main() {
                ivec2 dims = textureSize(sourceTex, 0);
                vec2 maxPos = vec2(max(dims.x - 1, 0), max(dims.y - 1, 0));

                Particle particle = push.particleBuffer.particles[gl_VertexIndex];
                uint rng = particle.rngState ^ (uint(gl_VertexIndex) * 2246822519u + 3266489917u);
                vec2 positionPx = clamp(particle.positionPx, vec2(0.0), maxPos);
                uint mode = particle.mode;
                bool becameSolid = false;

                if (mode != 0u) {
                    positionPx = randomPosition(rng, dims);
                    mode = 0u;
                } else {
                    ivec2 center = clamp(ivec2(round(positionPx)), ivec2(0), dims - 1);
                    int probe = max(int(round(push.sampleStepPx)), 1);
                    bool solidNeighbor =
                        isSolidAt(center + ivec2(probe, 0), dims, push.solidThreshold) ||
                        isSolidAt(center + ivec2(-probe, 0), dims, push.solidThreshold) ||
                        isSolidAt(center + ivec2(0, probe), dims, push.solidThreshold) ||
                        isSolidAt(center + ivec2(0, -probe), dims, push.solidThreshold);

                    if (solidNeighbor) {
                        mode = 1u;
                        becameSolid = true;
                    } else {
                        positionPx = clamp(positionPx + randomWalkStep(rng, push.walkStepPx), vec2(0.0), maxPos);
                    }
                }

                push.particleBuffer.particles[gl_VertexIndex].positionPx = positionPx;
                push.particleBuffer.particles[gl_VertexIndex].rngState = max(rng, 1u);
                push.particleBuffer.particles[gl_VertexIndex].mode = mode;

                vec2 uv = (positionPx + vec2(0.5)) / vec2(max(dims.x, 1), max(dims.y, 1));
                vec2 clip = uv * 2.0 - 1.0;
                gl_Position = vec4(clip.x, -clip.y, 0.0, 1.0);
                gl_PointSize = push.pointSize;
                vColor = becameSolid
                    ? vec4(push.stuckRed, 0.0, push.stuckBlue, 0.0)
                    : vec4(0.0, push.walkerGreen, 0.0, 0.0);
            }
        """.trimIndent()

        private val PARTICLE_FRAGMENT = """
            #version 450

            layout(location = 0) in vec4 vColor;
            layout(location = 0) out vec4 outColor;

            void main() {
                outColor = vColor;
            }
        """.trimIndent()

        private fun graphicsToGraphicsBarrier(command: CommandBuffer, buffer: afield.four.buffers.VulkanBuffer) {
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
