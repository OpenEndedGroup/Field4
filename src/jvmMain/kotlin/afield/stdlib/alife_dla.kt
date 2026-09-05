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

data class ShaderDlaSettings(
    var name: String = "dla",
    var width: Int = 1024,
    var height: Int = 1024,
    var particleCount: Int = 65_536,
    var pointSize: Float = 1f,
    var walkStepPx: Float = 1f,
    var sampleStepPx: Float = 1f,
    var solidThreshold: Float = 0.5f,
    var walkerGreen: Float = 0.12f,
    var stuckRed: Float = 1f,
    var stuckBlue: Float = 0.35f,
    var seedPositionPx: Vector2f? = null,
    var seedRadiusPx: Float = 1.5f,
    var solidDecayPerFrame: Float = 0.001f,
    var ageDecayPerFrame: Float = 0.004f,
    var ageGrowPerFrame: Float = 0.01f,
    var seedBlue: Float = 0.15f,
    var format: Int = VK_FORMAT_R16G16B16A16_SFLOAT,
    var clearColor: Vec4 = vec(0, 0, 0, 1),
    var randomSeed: Long = 1L
)

/**
 * A minimal shader-driven DLA system:
 * - ping-pong render target holds persistent solidity in red
 * - blue stores a simple age / heat channel for solids
 * - point-list particle draw emits walkers in green and one-frame stuck particles in red
 * - particle state lives in an addressable buffer passed via push-constant pointer
 */
class ShaderDlaSystem(
    val renderer: Renderer = fromContext(),
    configure: ShaderDlaSettings.() -> Unit = {}
) : Target, HasImageView, HasImageViewArray {

    val settings = ShaderDlaSettings().apply(configure)

    val particleBuffer: FrameBufferGroup = defaultBuffer(name = "${settings.name}ParticleBuffer") {
        size = (settings.particleCount * PARTICLE_STRIDE_BYTES).toLong()
    }.buffer

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

    val preserveModule: ModulePointer
    val particleModule: ModulePointer
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

    override val imageView: ImageView
        get() = simulation.imageView

    override val imageViewArray: List<HasImageView>
        get() = simulation.imageViewArray

    override fun render(command: CommandBuffer, index: Int) {
        simulation.render(command, index)
    }

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
