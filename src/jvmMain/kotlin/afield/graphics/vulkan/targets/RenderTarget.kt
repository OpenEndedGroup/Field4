package afield.graphics.vulkan.targets

import afield.graphics.vulkan.device.vkLayout
import afield.app.protocol.Rect
import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.Target
import afield.graphics.vulkan.buffers.MissingRefException
import afield.graphics.vulkan.device.CommandBuffer
import afield.document.context.*
import afield.graphics.render.*
import afield.graphics.scene.*
import afield.graphics.shader.*
import afield.graphics.texture.image_layout_transition
import afield.graphics.vulkan.util.Options
import afield.math.linalg.Rectanglef
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.*
import org.lwjgl.vulkan.KHRDynamicRendering.VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO_KHR
import org.lwjgl.vulkan.KHRDynamicRendering.VK_STRUCTURE_TYPE_RENDERING_INFO_KHR
import org.lwjgl.vulkan.KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.vulkan.VK13.*

/**
 * The render pass that draws a window's swapchain image: an ordered collection of modules, mesh
 * modules and other targets recorded into one frame.
 *
 * Add passes with `+=` on a [RenderTarget]; a [RendersAfter] pass is moved behind the target it
 * depends on before the list is recorded.
 */

/** Records a window's frame from its named modules, mesh modules and other [Target]s, in insertion order. */
class RenderTarget(
    /** The renderer that owns the window this target draws, and that submits the command buffer the passes record into. */
    val renderer: Renderer,
    private val contextProvider: () -> Renderer.WindowContext = { renderer.windowContext(0) },
    /** Which of the renderer's windows this target draws, matching the context it reads its swapchain and depth attachments from. `renderer.renderTargetFor(windowIndex)` is how a box reaches the one it wants, and the main window's is [Renderer.renderList]. */
    val windowIndex: Int = 0
) : Target {

    private val ctx get() = contextProvider()

    /** The modules drawn into this window, by name, recorded in insertion order before [meshModules]. Add with `+=`; passing a name-to-[ModulePointer] pair takes any entry already under that name out first, so re-adding one moves it to the end of the order. */
    var modules = mutableMapOf<String, ModulePointer>()

    /** The mesh-shader modules drawn into this window, by name, recorded after [modules] and in their own insertion order. */
    var meshModules = mutableMapOf<String, MeshModulePointer>()

    /** Targets rendered after this one's pass has ended, by name — the render-to-textures and compute targets chained behind the window. A [RendersAfter] entry is moved behind the target it names before the list runs. */
    var others = mutableMapOf<String, Target>()

    /** Secondary command buffers to execute inside the rendering instance in place of recording [modules] and [meshModules] this frame. It is consumed: the field is set back to null once the frame has used it. */
    var preRecordedInsideRendering: List<CommandBuffer>? = null

    /** Targets rendered before any of this one's render-pass work, by name — where an upload or streaming copy goes, since a transfer cannot be recorded inside dynamic rendering. */
    var preTargets = mutableMapOf<String, Target>()

    @JvmName("plusAssign")
    operator fun plusAssign(m : Pair<String, ModulePointer>) {
        if (m.first in modules) modules.remove(m.first) // allow reordering
        modules.putOwned(m.first, m.second, renderer)
    }

    @JvmName("plusAssign_mp")
    operator fun plusAssign(m : ModulePointer) {
        modules.putOwned(m.current.parameters.name, m, renderer)
    }

    @JvmName("plusAssign_module")
    operator fun plusAssign(m : Pair<String, Module>) {
        modules.putOwned(m.first, m.second.pointer(), renderer)
    }

    @JvmName("plusAssign_mesh")
    operator fun plusAssign(m : Pair<String, MeshModulePointer>) {
        meshModules.putOwned(m.first, m.second, renderer)
    }

    @JvmName("plusAssign_mmp")
    operator fun plusAssign(m : MeshModulePointer) {
        meshModules.putOwned(m.current.parameters.name, m, renderer)
    }

    @JvmName("plusAssign_mmodule")
    operator fun plusAssign(m : Pair<String, MeshModule>) {
        meshModules.putOwned(m.first, m.second.pointer(), renderer)
    }

    @JvmName("plusAssign_target")
    operator fun plusAssign(m : Pair<String, Target>) {
        others.putOwned(m.first, m.second, renderer)
    }

    /**
     * The swapchain image indices this target has already presented, which decide whether a
     * frame's first barrier declares the old layout as present-source or undefined. It is cleared
     * when the swapchain is recreated, since a resize hands back brand-new images.
     */
    var seenBefore = mutableSetOf<Int>()

    // Identity of the swapchain `seenBefore` was populated against. When the swapchain is recreated
    // (resize, or the Wayland fullscreen configure landing after startup) its images are brand-new
    // and start in UNDEFINED layout, making the "seen -> oldLayout = PRESENT_SRC" assumption stale.
    // If we don't reset, the first frame per image submits a barrier with the wrong oldLayout and
    // validation flags SwapchainImage[i] as UNDEFINED-vs-PRESENT_SRC_KHR (once per image).
    private var seenBeforeSwapChain: Long = 0L

    override fun render(command: CommandBuffer, index: Int) {
        val swapChainExtent = ctx.swapChain.swapChainExtent
        val width = swapChainExtent.width()
        val height = swapChainExtent.height()

        if (ctx.swapChain.vkSwapChain != seenBeforeSwapChain) {
            seenBefore.clear()
            seenBeforeSwapChain = ctx.swapChain.vkSwapChain
        }

        MemoryStack.stackPush().use { stack ->

            // Run pre-render targets (e.g., streaming uploads) before any render pass work.
            preTargets.forEach { (_, tgt) -> tgt.render(command, index) }

            // Subresource ranges (stack-allocated, freed at end of scope)
            val colorRange = VkImageSubresourceRange.calloc(stack)
                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0)
                .levelCount(VK_REMAINING_MIP_LEVELS)
                .baseArrayLayer(0)
                .layerCount(VK_REMAINING_ARRAY_LAYERS)

            val depthRange = VkImageSubresourceRange.calloc(stack)
                .aspectMask(Options.depthAspectMask(ctx.depthAttachments[index].image.format))
                .baseMipLevel(0)
                .levelCount(VK_REMAINING_MIP_LEVELS)
                .baseArrayLayer(0)
                .layerCount(VK_REMAINING_ARRAY_LAYERS)
            val depthLayout =
                if (Options.depthHasStencil(ctx.depthAttachments[index].image.format))
                    VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL
                else
                    VK_IMAGE_LAYOUT_DEPTH_ATTACHMENT_OPTIMAL

            // Initial layout transition for the swapchain image to COLOR_ATTACHMENT_OPTIMAL.
            // If we've presented this image before, oldLayout is PRESENT; otherwise UNDEFINED.
            run {
                val (srcStage, srcAccess, oldLayout) =
                    if (seenBefore.contains(index))
                        Triple(VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT, 0L, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR)
                    else
                        // Note: Use COLOR_ATTACHMENT_OUTPUT so this transition is synchronized by the
                        // swapchain acquire semaphore wait (sync validation on MoltenVK).
                        Triple(VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT, 0L, VK_IMAGE_LAYOUT_UNDEFINED)

                image_layout_transition(
                    command.vkCommandBuffer,
                    ctx.swapChain.imageViews[index].image,
                    srcStage,
                    VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT,
                    srcAccess,
                    VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT,
                    oldLayout,
                    VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                    colorRange
                )
            }

            // Depth initial transition (UNDEFINED -> DEPTH_STENCIL_ATTACHMENT_OPTIMAL)
            image_layout_transition(
                command.vkCommandBuffer,
                ctx.depthAttachments[index].image,
                VK_PIPELINE_STAGE_2_TOP_OF_PIPE_BIT,
                VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT or VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT,
                0,
                VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_READ_BIT or VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,
                VK_IMAGE_LAYOUT_UNDEFINED,
                depthLayout,
                depthRange
            )
            seenBefore.add(index)

            // Rendering attachments (KHR)
            val colorAttachment =
                VkRenderingAttachmentInfoKHR.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO_KHR)
                    .imageView(ctx.swapChain.imageViews[index].vkImageView)
                    .imageLayout(vkLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL))
                    .resolveMode(VK_RESOLVE_MODE_NONE)
                    .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR)
                    .storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                    .clearValue(
                        VkClearValue.calloc(stack).color(
                            VkClearColorValue.calloc(stack)
                                .float32(0, 0.0f)
                                .float32(1, 0.0f)
                                .float32(2, 0.0f)
                                .float32(3, 1f)
                        )
                    )

            val depthAttachment =
                VkRenderingAttachmentInfoKHR.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO_KHR)
                    .imageView(ctx.depthAttachments[index].imageView.vkImageView)
                    .imageLayout(vkLayout(depthLayout))
                    .resolveMode(VK_RESOLVE_MODE_NONE)
                    .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR)
                    .storeOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                    .clearValue(
                        VkClearValue.calloc(stack).depthStencil(
                            VkClearDepthStencilValue.calloc(stack)
                                .depth(1f)
                                .stencil(0)
                        )
                    )

            val recorded = preRecordedInsideRendering
            val useSecondaryInsideRendering = !recorded.isNullOrEmpty()
            val renderingFlags = if (useSecondaryInsideRendering) {
                VK_RENDERING_CONTENTS_SECONDARY_COMMAND_BUFFERS_BIT or
                    KHRMaintenance7.VK_RENDERING_CONTENTS_INLINE_BIT_KHR
            } else 0

            // Rendering info (KHR)
            val renderInfo = VkRenderingInfoKHR.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_RENDERING_INFO_KHR)
                .renderArea(
                    VkRect2D.calloc(stack)
                        .offset(VkOffset2D.calloc(stack).x(0).y(0))
                        .extent(VkExtent2D.calloc(stack).width(width).height(height))
                )
                .flags(renderingFlags)
                .layerCount(1)
                .pColorAttachments(colorAttachment)
                .pDepthAttachment(depthAttachment.get(0))

            // Upload dirty vertex/index buffers before beginning rendering (transfer ops are not allowed inside).
            // This keeps Mesh/VertexBuffer updates low-noise and spec-correct.
            modules.forEach { (_, mp) ->
                mp.current.meshes.values.forEach { mesh ->
                    mesh.prepareForDraw(command)
                    mesh.push.primeBufferReferences(command) {
                        throw MissingRefException(afield.graphics.vulkan.buffers.unassignedMemberMessage(it))
                    }
                }
                mp.current.liveUniformStructures.values.forEach { structure ->
                    structure.primeBufferReferences(command) {
                        throw MissingRefException(afield.graphics.vulkan.buffers.unassignedMemberMessage(it))
                    }
                }
            }

            meshModules.forEach { (_, mp) ->
                mp.current.meshes.values.forEach { mesh ->
                    mesh.prepareForDraw(command)
                    mesh.push.primeBufferReferences(command) {
                        throw MissingRefException(afield.graphics.vulkan.buffers.unassignedMemberMessage(it))
                    }
                }
                mp.current.liveUniformStructures.values.forEach { structure ->
                    structure.primeBufferReferences(command) {
                        throw MissingRefException(afield.graphics.vulkan.buffers.unassignedMemberMessage(it))
                    }
                }
            }

            modules.forEach { (_, mp) ->
                mp.current.before.forEach { (_, target) ->
                    target.render(command, index)
                }
            }

            meshModules.forEach { (_, mp) ->
                mp.current.before.forEach { (_, target) ->
                    target.render(command, index)
                }
            }

            // Texture providers may need layout transitions before descriptors are pushed.
            // Dynamic rendering does not allow image barriers inside the rendering instance.
            modules.forEach { (_, mp) -> mp.prepareSampledImagesFor(command) }
            meshModules.forEach { (_, mp) -> mp.prepareSampledImagesFor(command) }

            command.inRenderPassInstance = true
            KHRDynamicRendering.vkCmdBeginRenderingKHR(command.vkCommandBuffer, renderInfo)

            val fullRect = Rect(0.0, 0.0, width.toDouble(), height.toDouble())

            if (recorded.isNullOrEmpty()) {
                modules.forEach { (_, mp) ->
                    setViewportAndScissor(command.vkCommandBuffer, mp.current.parameters.viewport ?: fullRect, stack)
                    mp.render(command, Rectanglef(0f, 0f, width.toFloat(), height.toFloat()))
                }

                meshModules.forEach { (_, mp) ->
                    setViewportAndScissor(command.vkCommandBuffer, mp.current.parameters.viewport ?: fullRect, stack)
                    mp.render(command, Rectanglef(0f, 0f, width.toFloat(), height.toFloat()))
                }
            } else {
                // Execute pre-recorded modules/mesh modules in order
                val pb = stack.mallocPointer(recorded.size)
                recorded.forEach { cb ->
                    pb.put(cb.vkCommandBuffer.address())
                }
                pb.flip()
                VK10.vkCmdExecuteCommands(command.vkCommandBuffer, pb)
            }
            preRecordedInsideRendering = null

            KHRDynamicRendering.vkCmdEndRenderingKHR(command.vkCommandBuffer)
            command.inRenderPassInstance = false
            command.flushDeferredGraphicsHostReadBarriers()

            // Transition swapchain image to PRESENT for presentation engine.
            // With sync2 you can use NONE for dst stage/access.
            image_layout_transition(
                command.vkCommandBuffer,
                ctx.swapChain.imageViews[index].image,
                VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT,
                VK_PIPELINE_STAGE_2_NONE,
                VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT,
                0,
                VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
                colorRange
            )
        }

        // Render any chained targets after we've finished this one, each RendersAfter pass after its target.
        orderRendersAfter(others)
        others.forEach { it.value.render(command, index) }
    }

    private fun setViewportAndScissor(cmd: VkCommandBuffer, rect: Rect, stack: MemoryStack) {
        val viewport = VkViewport.calloc(1, stack)
            .x(rect.x.toFloat())
            .y((rect.y + rect.h).toFloat()) // flip Y
            .height((-rect.h).toFloat())
            .width(rect.w.toFloat())
            .minDepth(0f).maxDepth(1f)
        VK10.vkCmdSetViewport(cmd, 0, viewport)

        val scissor = VkRect2D.calloc(1, stack)
            .extent(
                VkExtent2D.calloc(stack)
                    .width(rect.w.toInt())
                    .height(rect.h.toInt())
            )
            .offset(VkOffset2D.calloc(stack).x(rect.x.toInt()).y(rect.y.toInt()))
        VK10.vkCmdSetScissor(cmd, 0, scissor)
    }
}

/**
 * A pass that has to render after another [Target] in the same list: a publisher after the texture it
 * publishes. Wherever it was added, [RenderTarget] moves it behind [rendersAfter] before it iterates.
 */
interface RendersAfter {
    /** The target this pass has to follow. [RenderTarget] looks for it in the same `others` map and moves this entry behind it; a target that is not in that map leaves the order alone. */
    val rendersAfter: Target
}

/** Moves each [RendersAfter] entry of [list] behind its target, when the target comes later. */
internal fun orderRendersAfter(list: MutableMap<String, Target>) {
    if (list.values.none { it is RendersAfter }) return
    repeat(list.size) {
        val keys = list.keys.toList()
        val values = list.values.toList()
        val late = keys.indices.firstOrNull { i ->
            val after = (values[i] as? RendersAfter)?.rendersAfter ?: return@firstOrNull false
            values.indexOfFirst { it === after } > i
        } ?: return
        val key = keys[late]
        val value = list.remove(key)!!
        list[key] = value
    }
}
