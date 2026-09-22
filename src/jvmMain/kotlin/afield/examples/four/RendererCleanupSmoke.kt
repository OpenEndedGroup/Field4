package afield.examples.four

import afield.four.Renderer
import afield.four.buffers.VulkanBuffer
import afield.four.buffers.allocators
import afield.four.device.Fence
import afield.four.targets.RenderToTexture
import afield.four.util.AutoDisposer
import afield.four.util.Options
import afield.four.util.Window
import org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
import org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT
import kotlin.system.exitProcess

/*
FIELD_TEST
id: four.renderer-cleanup.smoke
requiresDisplay: true
runner: jvm-main
entrypoint: afield.examples.four.RendererCleanupSmoke
tags: [smoke, four, gpu, lifecycle]
timeoutSec: 180
expect:
  exitCode: 0
  stdoutContains:
    - "renderer deferred cleanup PASSED"
  stdoutNotContains:
    - "VUID-"
    - "Validation Error"
  stderrNotContains:
    - "VUID-"
    - "Validation Error"
*/
/** Close immediately after buffer replacement, before deferred releases can age out. */
object RendererCleanupSmoke {
    @JvmStatic
    fun main(args: Array<String>) {
        Options.doVulkanSDK()
        Options.enableVR = false
        Options.validate = true
        val window = Window("Renderer cleanup regression")
        val renderer = Renderer(window)
        val released = mutableListOf<String>()
        val retained = VulkanBuffer(
            renderer, 256L, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT,
        )
        val fence = Fence(renderer.device, true)
        val surface = RenderToTexture(renderer) {
            width = 32
            height = 32
        }
        surface.setDefaultSampler()

        try {
            check(renderer.instance.validationActive) {
                "This lifecycle regression requires the Vulkan validation layer and debug messenger"
            }
            val buffer = VulkanBuffer(
                renderer, 64L, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT,
            )
            buffer.realloc(128L)
            renderer.cleanup += Runnable { released += "retired" }
            // One real frame retires the first allocation, but cannot release it yet.
            window.pollEvents()
            renderer.render()
            check(released.isEmpty())
            check(renderer.cleanupStack.any { it.second.isNotEmpty() })

            buffer.pushCleanup()
            // An explicit cleanup after handing ownership off must be harmless too.
            buffer.cleanup()
            renderer.cleanup += Runnable {
                released += "current"
                renderer.cleanup += Runnable { released += "dependent" }
            }
        } finally {
            try {
                renderer.cleanup()
            } finally {
                window.cleanup()
            }
        }

        check(released == listOf("retired", "current", "dependent")) { released }
        check(renderer.cleanup.isEmpty() && renderer.cleanupStack.isEmpty())
        check(AutoDisposer.ownedCount(renderer.device) == 0)
        check(renderer !in allocators)
        // Documents can retain wrappers after closing. Late explicit cleanup must be harmless.
        retained.cleanup()
        fence.cleanup()
        renderer.cleanup()
        check(runCatching { renderer.render() }.exceptionOrNull() is IllegalStateException)
        java.lang.ref.Reference.reachabilityFence(surface)
        println("renderer deferred cleanup PASSED")
        exitProcess(0)
    }
}
