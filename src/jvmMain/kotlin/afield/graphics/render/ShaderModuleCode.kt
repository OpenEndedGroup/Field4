package afield.graphics.render

import afield.graphics.vulkan.util.vkCheck
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.vulkan.VkDevice
import org.lwjgl.vulkan.VkShaderModuleCreateInfo
import java.nio.ByteBuffer

/**
 * Borrows aligned native SPIR-V, or makes a temporary native copy for heap/unaligned buffers.
 * Shader-sized storage never uses MemoryStack, and the caller's position and limit are preserved.
 * The callback must finish using the borrowed memory before returning.
 */
internal fun <T> withShaderModuleCode(code: ByteBuffer, use: (ByteBuffer) -> T): T {
    require(code.remaining() > 0 && code.remaining() % 4 == 0) {
        "SPIR-V must contain a nonempty whole number of 32-bit words"
    }
    if (code.isDirect && MemoryUtil.memAddress(code) % 4L == 0L) return use(code.duplicate())
    val copy = MemoryUtil.memAlloc(code.remaining())
    try {
        copy.put(code.duplicate()).flip()
        return use(copy)
    } finally {
        MemoryUtil.memFree(copy)
    }
}

/** Shared by graphics, compute, task/mesh and ray tracing, including their reload paths. */
internal fun createVulkanShaderModule(device: VkDevice, code: ByteBuffer): Long =
    withShaderModuleCode(code) { nativeCode ->
        MemoryStack.stackPush().use { stack ->
            // Only the fixed-size descriptor and result handle belong on the scratch stack.
            val info = VkShaderModuleCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO)
                .pCode(nativeCode)
            val result = stack.mallocLong(1)
            vkCheck(vkCreateShaderModule(device, info, null, result), "Failed to create shader module")
            result[0]
        }
    }
