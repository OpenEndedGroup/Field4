package afield.graphics.vulkan.device

import org.lwjgl.glfw.GLFWVulkan
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.KHRSurface

/**
 * The Vulkan presentation surface for a GLFW window.
 *
 * [Surface] asks GLFW to create the surface for a window handle on the instance behind a
 * [PhysicalDevice], and `cleanup` destroys it.
 */

class Surface(physicalDevice: PhysicalDevice, windowHandle: Long) {
    private val physicalDevice: PhysicalDevice
    var vkSurface: Long = 0

    init {
        this.physicalDevice = physicalDevice
        MemoryStack.stackPush().use { stack ->
            val pSurface = stack.mallocLong(1)
            GLFWVulkan.glfwCreateWindowSurface(
                this.physicalDevice.vkPhysicalDevice.instance, windowHandle,
                null, pSurface
            )
            vkSurface = pSurface[0]
        }
    }

    fun cleanup() {
        KHRSurface.vkDestroySurfaceKHR(physicalDevice.vkPhysicalDevice.instance, vkSurface, null)
    }
}
