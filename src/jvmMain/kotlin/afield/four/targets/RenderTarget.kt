package afield.four.targets

import org.joml.primitives.Rectanglef
import org.lwjgl.vulkan.*
import org.lwjgl.vulkan.KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR
import org.lwjgl.vulkan.KHRDynamicRendering
import org.lwjgl.vulkan.KHRDynamicRendering.VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO_KHR
import org.lwjgl.vulkan.KHRDynamicRendering.VK_STRUCTURE_TYPE_RENDERING_INFO_KHR
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.vulkan.VK13.*
import afield.four.Target
import afield.four.Renderer
import afield.four.device.CommandBuffer
import afield.four.modules.ComputeModulePointer
import afield.four.modules.Module
import afield.four.modules.ModulePointer
import afield.four.modules.pointer
import afield.named
import org.lwjgl.system.MemoryStack

class RenderTarget(val renderer: Renderer) : Target {

    var modules = mutableMapOf<String, ModulePointer>()
    var others = mutableMapOf<String, Target>()

    @JvmName("plusAssign")
    operator fun plusAssign(m : Pair<String, ModulePointer>) {
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

    @JvmName("plusAssign_target")
    operator fun plusAssign(m : Pair<String, Target>) {
        others[m.first] = m.second
    }


    var seenBefore = mutableSetOf<Int>()

    override fun render(command: CommandBuffer, index: Int) {
        val swapChainExtent = renderer.swapChain.swapChainExtent
        val width = swapChainExtent.width()
        val height = swapChainExtent.height()

        MemoryStack.stackPush().use { stack ->

            // Subresource ranges (stack-allocated, freed at end of scope)
            val colorRange = VkImageSubresourceRange.calloc(stack)
                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0)
                .levelCount(VK_REMAINING_MIP_LEVELS)
                .baseArrayLayer(0)
                .layerCount(VK_REMAINING_ARRAY_LAYERS)

            val depthRange = VkImageSubresourceRange.calloc(stack)
                .aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT or VK_IMAGE_ASPECT_STENCIL_BIT)
                .baseMipLevel(0)
                .levelCount(VK_REMAINING_MIP_LEVELS)
                .baseArrayLayer(0)
                .layerCount(VK_REMAINING_ARRAY_LAYERS)

            // Initial layout transitions (spec-friendly)
            image_layout_transition(
                command.vkCommandBuffer,
                renderer.swapChain.imageViews[index].vkImage,
                VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                0,
                VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
                if (seenBefore.contains(index)) VK_IMAGE_LAYOUT_PRESENT_SRC_KHR else VK_IMAGE_LAYOUT_UNDEFINED,
                VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                colorRange
            )

            // this logic isn't quite true with a resize is it?

            image_layout_transition(
                command.vkCommandBuffer,
                renderer.depthAttachments[index].image.vkImage,
                VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT,
                0,
                VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,
                VK_IMAGE_LAYOUT_UNDEFINED,
                VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL,
                depthRange
            )
            seenBefore.add(index)

            // Rendering attachments (KHR)
            val colorAttachment =
                VkRenderingAttachmentInfoKHR.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO_KHR)
                    .imageView(renderer.swapChain.imageViews[index].vkImageView)
                    .imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                    .resolveMode(VK_RESOLVE_MODE_NONE)
                    .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR)
                    .storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                    .clearValue(
                        VkClearValue.calloc(stack).color(
                            VkClearColorValue.calloc(stack)
                                .float32(0, 0.01f)
                                .float32(1, 0.01f)
                                .float32(2, 0.01f)
                                .float32(3, 1f)
                        )
                    )

            val depthAttachment =
                VkRenderingAttachmentInfoKHR.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO_KHR)
                    .imageView(renderer.depthAttachments[index].imageView.vkImageView)
                    .imageLayout(VK_IMAGE_LAYOUT_DEPTH_ATTACHMENT_OPTIMAL)
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

            // Rendering info (KHR)
            val renderInfo = VkRenderingInfoKHR.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_RENDERING_INFO_KHR)
                .renderArea(
                    VkRect2D.calloc(stack)
                        .offset(VkOffset2D.calloc(stack).x(0).y(0))
                        .extent(VkExtent2D.calloc(stack).width(width).height(height))
                )
                .layerCount(1)
                .pColorAttachments(colorAttachment)
                .pDepthAttachment(depthAttachment.get(0))

            // Begin/End dynamic rendering (KHR)
            KHRDynamicRendering.vkCmdBeginRenderingKHR(command.vkCommandBuffer, renderInfo)

            modules.forEach { (_, mp) ->
                mp.render(command, Rectanglef(0f, 0f, width.toFloat(), height.toFloat()))
            }

            KHRDynamicRendering.vkCmdEndRenderingKHR(command.vkCommandBuffer)

            // Transition swapchain image to PRESENT
            image_layout_transition(
                command.vkCommandBuffer,
                renderer.swapChain.imageViews[index].vkImage,
                VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, // more robust than BOTTOM_OF_PIPE
                VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
                0,
                VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
                colorRange
            )
        }

        // Render any chained targets after we've finished this one
        others.forEach { it.value.render(command, index) }
    }
}
