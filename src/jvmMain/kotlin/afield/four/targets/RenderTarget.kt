package afield.four.targets

import afield.Rect
import afield.four.Renderer
import afield.four.Target
import afield.four.buffers.MissingRefException
import afield.four.device.CommandBuffer
import afield.four.modules.*
import afield.four.texture.image_layout_transition
import afield.four.util.Options
import org.joml.primitives.Rectanglef
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.*
import org.lwjgl.vulkan.KHRDynamicRendering.VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO_KHR
import org.lwjgl.vulkan.KHRDynamicRendering.VK_STRUCTURE_TYPE_RENDERING_INFO_KHR
import org.lwjgl.vulkan.KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.vulkan.VK13.*

class RenderTarget(
    val renderer: Renderer,
    private val contextProvider: () -> Renderer.WindowContext = { renderer.windowContext(0) },
    val windowIndex: Int = 0
) : Target {

    private val ctx get() = contextProvider()

    var modules = mutableMapOf<String, ModulePointer>()
    var meshModules = mutableMapOf<String, MeshModulePointer>()
    var others = mutableMapOf<String, Target>()
    var preRecordedInsideRendering: List<CommandBuffer>? = null
    var preTargets = mutableMapOf<String, Target>()

    @JvmName("plusAssign")
    operator fun plusAssign(m : Pair<String, ModulePointer>) {
        if (m.first in modules) modules.remove(m.first) // allow reordering
        modules[m.first] = m.second
    }

    @JvmName("plusAssign_mp")
    operator fun plusAssign(m : ModulePointer) {
        modules[m.current.parameters.name] = m
    }

    @JvmName("plusAssign_module")
    operator fun plusAssign(m : Pair<String, Module>) {
        modules[m.first] = m.second.pointer()
    }

    @JvmName("plusAssign_mesh")
    operator fun plusAssign(m : Pair<String, MeshModulePointer>) {
        meshModules[m.first] = m.second
    }

    @JvmName("plusAssign_mmp")
    operator fun plusAssign(m : MeshModulePointer) {
        meshModules[m.current.parameters.name] = m
    }

    @JvmName("plusAssign_mmodule")
    operator fun plusAssign(m : Pair<String, MeshModule>) {
        meshModules[m.first] = m.second.pointer()
    }

    @JvmName("plusAssign_target")
    operator fun plusAssign(m : Pair<String, Target>) {
        others[m.first] = m.second
    }

    var seenBefore = mutableSetOf<Int>()

    override fun render(command: CommandBuffer, index: Int) {
        val swapChainExtent = ctx.swapChain.swapChainExtent
        val width = swapChainExtent.width()
        val height = swapChainExtent.height()

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
                    .imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
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
                    .imageLayout(depthLayout)
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

            // Rendering info (KHR)
            val renderInfo = VkRenderingInfoKHR.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_RENDERING_INFO_KHR)
                .renderArea(
                    VkRect2D.calloc(stack)
                        .offset(VkOffset2D.calloc(stack).x(0).y(0))
                        .extent(VkExtent2D.calloc(stack).width(width).height(height))
                )
                .flags(if (useSecondaryInsideRendering) VK_RENDERING_CONTENTS_SECONDARY_COMMAND_BUFFERS_BIT else 0)
                .layerCount(1)
                .pColorAttachments(colorAttachment)
                .pDepthAttachment(depthAttachment.get(0))

            // Upload dirty vertex/index buffers before beginning rendering (transfer ops are not allowed inside).
            // This keeps Mesh/VertexBuffer updates low-noise and spec-correct.
            modules.forEach { (_, mp) ->
                mp.current.meshes.values.forEach { mesh ->
                    mesh.prepareForDraw(command)
                    mesh.push.primeBufferReferences(command) {
                        throw MissingRefException("risk of GPU null ref `$it`")
                    }
                }
                mp.current.liveUniformStructures.values.forEach { structure ->
                    structure.primeBufferReferences(command) {
                        throw MissingRefException("risk of GPU null ref `$it`")
                    }
                }
            }

            meshModules.forEach { (_, mp) ->
                mp.current.meshes.values.forEach { mesh ->
                    mesh.push.primeBufferReferences(command) {
                        throw MissingRefException("risk of GPU null ref `$it`")
                    }
                }
                mp.current.liveUniformStructures.values.forEach { structure ->
                    structure.primeBufferReferences(command) {
                        throw MissingRefException("risk of GPU null ref `$it`")
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

        // Render any chained targets after we've finished this one
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
