package afield.four.device

import afield.four.util.Options
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import org.lwjgl.vulkan.*
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.util.concurrent.ConcurrentHashMap

private val stableDebugStrings = ConcurrentHashMap<String, ByteBuffer>()

private fun stableDebugString(name: String): ByteBuffer =
    stableDebugStrings.computeIfAbsent(name) { MemoryUtil.memUTF8(it, true) }

fun Device.nameObject(handle: Long, type: Int, name: String) {
    if (!Options.validate) return
    if (!Options.debugObjectNames) return
    if (this.vkDevice.getCapabilitiesInstance().vkSetDebugUtilsObjectNameEXT == 0L) return
    MemoryStack.stackPush().use { s ->
        val info = VkDebugUtilsObjectNameInfoEXT.calloc(s)
            .sType(EXTDebugUtils.VK_STRUCTURE_TYPE_DEBUG_UTILS_OBJECT_NAME_INFO_EXT)
            .objectType(type)
            .objectHandle(handle)
            .pObjectName(stableDebugString(name))
        EXTDebugUtils.vkSetDebugUtilsObjectNameEXT(this.vkDevice, info)
    }
}

fun CommandBuffer.beginLabel(name: String, rgba: FloatBuffer) {
    if (!Options.validate) return
    if (!Options.debugLabels) return
    if (Options.isMac) return
    if (this.vkCommandBuffer.getCapabilitiesInstance().vkCmdBeginDebugUtilsLabelEXT == 0L) return
    MemoryStack.stackPush().use { stack ->
        val label = VkDebugUtilsLabelEXT.calloc(stack)
            .sType(EXTDebugUtils.VK_STRUCTURE_TYPE_DEBUG_UTILS_LABEL_EXT)
            .pLabelName(stableDebugString(name))
            .color(rgba)
        EXTDebugUtils.vkCmdBeginDebugUtilsLabelEXT(this.vkCommandBuffer, label)
    }
}

fun CommandBuffer.endLabel() {
    if (!Options.validate) return
    if (!Options.debugLabels) return
    if (Options.isMac) return
    if (this.vkCommandBuffer.getCapabilitiesInstance().vkCmdEndDebugUtilsLabelEXT == 0L) return
    EXTDebugUtils.vkCmdEndDebugUtilsLabelEXT(this.vkCommandBuffer)
}
