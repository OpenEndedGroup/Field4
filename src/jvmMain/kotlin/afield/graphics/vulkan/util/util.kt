package afield.graphics.vulkan.util

/**
 * Small helpers shared by the Vulkan renderer: the host OS, memory-type selection and result checking.
 *
 * [updateForever] builds a sequence for a mesh's `update` map, [OS] reports the platform, [memoryTypeFromProperties] picks a device memory type, and [vkCheck] throws on a failed Vulkan call.
 */

import afield.runtime.lifecycle.Runloop
import org.lwjgl.vulkan.VK10
import afield.graphics.vulkan.device.PhysicalDevice

/** A `Sequence` that calls [function] once per step, for `mesh.update["name"] = updateForever { ... }`. */
fun updateForever(function: () -> Boolean) = sequence {
    while (true) {
        function()
        yield(true)
    }
}

/**
 * Which of the four platforms Field recognises this JVM is running on, read from the `os.name`
 * system property.
 *
 * This is a getter rather than a stored value, so it is classified on every read. Anything the four
 * names below do not match reads as `OTHER`.
 */
val OS: OSType
    get() {
        val result: OSType
        val os = System.getProperty("os.name", "generic").lowercase()
        result = if ((os.indexOf("mac") >= 0) || (os.indexOf("darwin") >= 0)) {
            OSType.MACOS
        } else if (os.indexOf("win") >= 0) {
            OSType.WINDOWS
        } else if (os.indexOf("nux") >= 0) {
            OSType.LINUX
        } else {
            OSType.OTHER
        }

        return result
    }

/**
 * Picks a device memory type index for an allocation, given the types Vulkan says are allowed for it
 * and the properties it needs.
 *
 * [_typeBits] is the `memoryTypeBits` mask out of a memory-requirements query, one bit per type
 * index, and [reqsMask] is a combination of `VK_MEMORY_PROPERTY_*` flags that the chosen type has to
 * carry in full. The search returns the lowest qualifying index and throws when there is none, so a
 * caller that can accept more than one kind of memory asks for the strictest mask first.
 */
fun memoryTypeFromProperties(physDevice: PhysicalDevice, _typeBits: Int, reqsMask: Int): Int {
    var typeBits = _typeBits
    var result = -1
    val memoryTypes = physDevice.vkMemoryProperties.memoryTypes()
    for (i in 0 until VK10.VK_MAX_MEMORY_TYPES) {
        if ((typeBits and 1) == 1 && (memoryTypes[i].propertyFlags() and reqsMask) == reqsMask) {
            result = i
            break
        }
        typeBits = typeBits shr 1
    }
    if (result < 0) {
        throw RuntimeException("Failed to find memoryType")
    }
    return result
}

/**
 * Throws unless [err] is `VK_SUCCESS`, which is how every Vulkan call in the renderer is checked.
 *
 * The exception carries [errMsg], the numeric result code, the calling thread's name and whether
 * that thread is the Runloop's main thread — the last because a Vulkan call made off the main thread
 * is a likely cause rather than a detail of the failure.
 */
fun vkCheck(err: Int, errMsg: String) {
    if (err != VK10.VK_SUCCESS) {
        throw RuntimeException("$errMsg: $err: thread is ${Thread.currentThread().name} ${Runloop.Companion.isMainThread}")
    }
}

/**
 * The host platforms the renderer distinguishes, as reported by [OS].
 *
 * What reads it is extension and feature selection. `MACOS` decides whether the portability
 * enumeration and subset extensions are asked for; `WINDOWS` turns on external memory, present
 * indexing and mesh shaders; `LINUX` is where the OpenXR bootstrap is looked for. `OTHER` covers
 * anything the `os.name` match does not place, and nothing is done differently for it.
 */
enum class OSType {
    WINDOWS, MACOS, LINUX, OTHER
}
