package afield.examples.four

import afield.four.Renderer
import afield.four.buffers.VulkanBuffer
import afield.four.util.Options
import afield.four.util.Window
import org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT
import org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT
import org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/*
FIELD_TEST
id: four.live-frame-buffer-lifetime.smoke
requiresDisplay: true
runner: jvm-main
entrypoint: afield.examples.four.LiveFrameBufferLifetimeSmoke
tags: [smoke, four, gpu, lifecycle]
timeoutSec: 180
expect:
  exitCode: 0
  stdoutContains:
    - "live-frame buffer lifetime PASSED"
  stdoutNotContains:
    - "VUID-"
    - "Validation Error"
  stderrNotContains:
    - "VUID-"
    - "Validation Error"
*/
/**
 * The three lifetimes behind the live-frame crash in `dev_docs/renderer_teardown_order.md`
 * ("Live-frame crash"), checked on a real device without depending on a driver to fault.
 *
 * 1. While one thread `realloc`s a uniform buffer as fast as it can, another, standing in for the
 *    render worker that records `Module$Uniform.pushDescriptorSet`, never reads a zero handle.
 *    Before the fix `realloc` cleared the handle and then allocated, and on MoltenVK a push in
 *    that window was a SIGSEGV in `MVKBuffer::getMTLBuffer` (3 of 3 crashed in a live Field).
 * 2. A buffer released with `cleanup()` refuses a descriptor binding by name.
 * 3. Once `Renderer.cleanup()` has begun, `Queue.submit` and `descriptorBinding()` refuse by name.
 */
object LiveFrameBufferLifetimeSmoke {
    @JvmStatic
    fun main(args: Array<String>) {
        Options.doVulkanSDK()
        Options.enableVR = false
        Options.validate = true
        val window = Window("Live-frame buffer lifetime")
        val renderer = Renderer(window)
        try {
            check(renderer.instance.validationActive) {
                "This lifecycle regression requires the Vulkan validation layer and debug messenger"
            }
            val ubo = VulkanBuffer(
                renderer, 128L, VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT or VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
            )

            // 1. No zero handle is ever visible across a realloc.
            val reallocs = 20_000
            val done = java.util.concurrent.atomic.AtomicBoolean(false)
            var failure: Throwable? = null
            val writer = thread(name = "realloc-stress") {
                try {
                    for (n in 0 until reallocs) ubo.realloc(128L + (n % 7) * 64L)
                } catch (t: Throwable) {
                    failure = t
                } finally {
                    done.set(true)
                }
            }
            var samples = 0L
            var zeroHandles = 0L
            var zeroBindings = 0L
            while (!done.get()) {
                if (ubo.buffer == 0L) zeroHandles++
                if (ubo.descriptorBinding().buffer == 0L) zeroBindings++
                samples++
            }
            writer.join()
            failure?.let { throw IllegalStateException("realloc thread threw", it) }
            // One real frame, so the retirement queue sees the replaced allocations too.
            window.pollEvents()
            renderer.render()
            println("sampled the handle $samples times across $reallocs reallocs: $zeroHandles zero handles, $zeroBindings zero bindings")
            check(samples > 1_000) { "premise: the reader must actually have overlapped the reallocs (sampled $samples times)" }
            check(zeroHandles == 0L && zeroBindings == 0L) {
                "a recorder could read a VK_NULL_HANDLE mid-realloc ($zeroHandles handles, $zeroBindings bindings)"
            }

            // 2. A released buffer refuses a descriptor binding by name.
            ubo.cleanup()
            val released = runCatching { ubo.descriptorBinding() }.exceptionOrNull()
            check(released is IllegalStateException && "released" in released.message.orEmpty()) {
                "descriptorBinding() after cleanup() must refuse by name, got $released"
            }
            println("released buffer refuses: ${released.message}")

            // 3. After renderer teardown begins, submits and bindings refuse by name.
            val live = VulkanBuffer(
                renderer, 64L, VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT or VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
            )
            renderer.cleanup()
            val submit = runCatching { renderer.graphQueue.submit(null, null, null, null, null) }.exceptionOrNull()
            check(submit is IllegalStateException && "began disposing" in submit.message.orEmpty()) {
                "Queue.submit after Renderer.cleanup() must refuse by name, got $submit"
            }
            println("submit after teardown refuses: ${submit.message}")
            val bound = runCatching { live.descriptorBinding() }.exceptionOrNull()
            check(bound is IllegalStateException && "disposed" in bound.message.orEmpty()) {
                "descriptorBinding() after Renderer.cleanup() must refuse by name, got $bound"
            }
            println("binding after teardown refuses: ${bound.message}")
            java.lang.ref.Reference.reachabilityFence(live)
        } finally {
            try {
                renderer.cleanup()
            } finally {
                window.cleanup()
            }
        }
        println("live-frame buffer lifetime PASSED")
        exitProcess(0)
    }
}
