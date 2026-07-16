package afield.four.util

import afield.Runloop
import org.lwjgl.vulkan.VK10
import afield.four.device.PhysicalDevice

fun forever(function: () -> Boolean) = sequence {
    while (true) {
        function()
        yield(true)
    }
}

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

fun vkCheck(err: Int, errMsg: String) {
    if (err != VK10.VK_SUCCESS) {
        throw RuntimeException("$errMsg: $err: thread is ${Thread.currentThread().name} ${Runloop.Companion.isMainThread}")
    }
}

enum class OSType {
    WINDOWS, MACOS, LINUX, OTHER
}
