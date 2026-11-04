package afield.examples.four

import afield.graphics.texture.image_layout_transition
import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.Target
import afield.graphics.vulkan.buffers.VulkanBuffer
import afield.graphics.vulkan.device.CommandBuffer
import afield.graphics.vulkan.targets.FrameCadencedPingPongRenderToTexture
import afield.graphics.vulkan.targets.PingPongRenderToTexture
import afield.graphics.vulkan.targets.RTTParameters
import afield.graphics.vulkan.util.Options
import afield.graphics.vulkan.util.Window
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.VK13.*
import org.lwjgl.vulkan.VkBufferImageCopy
import org.lwjgl.vulkan.VkImageSubresourceRange
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/*
FIELD_TEST
id: four.pingpong-restore.smoke
requiresDisplay: true
runner: jvm-main
entrypoint: afield.examples.four.PingPongRestoreSmoke
tags: [smoke, four, gpu, feedback, lifecycle]
timeoutSec: 180
expect:
  exitCode: 0
  stdoutContains:
    - "ping-pong history restore PASSED"
  stdoutNotContains:
    - "VUID-"
    - "Validation Error"
  stderrNotContains:
    - "VUID-"
    - "Validation Error"
*/
/** Real transfers through every slot of ordinary and cadenced, two-attachment, two-layer rings. */
object PingPongRestoreSmoke {
    private const val SIDE = 8
    private const val LAYERS = 2
    private const val BYTES = SIDE * SIDE * LAYERS * 4

    private val setup: RTTParameters.() -> Unit = {
        width = SIDE
        height = SIDE
        colorAttachments = 2
        colorLayerCount = LAYERS
        multiviewViewMask = 3
        format = VK_FORMAT_R8G8B8A8_UNORM
        useDepth = false
        clearFirst = false
        colorTransferDst = true
    }

    private class WatchedRing(renderer: Renderer) : PingPongRenderToTexture(renderer, 3, setup) {
        var renderHooks = 0
        override fun beforeRender(index: Int) {
            renderHooks++
            super.beforeRender(index)
        }
        fun boundImage() = colorImageHandles[0]
    }

    private fun region(stack: MemoryStack, offset: Long = 0) = VkBufferImageCopy.calloc(1, stack).also {
        it[0].bufferOffset(offset)
        it[0].imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(LAYERS)
        it[0].imageExtent().set(SIDE, SIDE, 1)
    }

    private fun pattern(seed: Int) = ByteArray(BYTES) { i -> ((i * 17 + seed) % 251).toByte() }

    private fun views(target: PingPongRenderToTexture): List<Long> = buildList {
        add(target.latestImageView.imageView.image)
        add(target.previousImageView.imageView.image)
        add(target.thisImageView.imageView.image)
        add(target.otherImageView.imageView.image)
        for (a in 0 until target.colorAttachmentCount) for (i in 0 until target.frameCount) {
            add(target.frameImageView(i, a).imageView.image)
        }
    }

    private fun readRing(command: CommandBuffer, target: PingPongRenderToTexture, output: VulkanBuffer) {
        MemoryStack.stackPush().use { stack ->
            val range = VkImageSubresourceRange.calloc(stack).aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(LAYERS)
            for (a in 0 until target.colorAttachmentCount) for (i in 0 until target.frameCount) {
                val image = target.frameImageView(i, a).imageView.image
                image_layout_transition(command.vkCommandBuffer, image,
                    VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_2_COPY_BIT,
                    VK_ACCESS_2_MEMORY_READ_BIT or VK_ACCESS_2_MEMORY_WRITE_BIT, VK_ACCESS_2_TRANSFER_READ_BIT,
                    VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, range)
                vkCmdCopyImageToBuffer(command.vkCommandBuffer, image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    output.buffer, region(stack, (a * target.frameCount + i) * BYTES.toLong()))
                image_layout_transition(command.vkCommandBuffer, image,
                    VK_PIPELINE_STAGE_2_COPY_BIT, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,
                    VK_ACCESS_2_TRANSFER_READ_BIT, VK_ACCESS_2_MEMORY_READ_BIT or VK_ACCESS_2_MEMORY_WRITE_BIT,
                    VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, range)
            }
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        Options.doVulkanSDK()
        Options.enableVR = false
        Options.validate = true
        val window = Window("Ping-pong history restore")
        val renderer = Renderer(window)
        try {
            check(renderer.instance.validationActive) { "This regression requires Vulkan validation" }
            check(runCatching { renderer.whenCurrentGraphicsFrameComplete {} }.exceptionOrNull() is IllegalStateException)
            val ordinary = WatchedRing(renderer)
            val cadenced = FrameCadencedPingPongRenderToTexture(renderer, 4, 3, setup)
            val patterns = listOf(pattern(3), pattern(73), pattern(149))
            val uploads = patterns.map { bytes ->
                VulkanBuffer(renderer, BYTES.toLong(), VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT or VK_MEMORY_PROPERTY_HOST_COHERENT_BIT).also {
                    it.mapAndWrite_bytes { put(bytes) }
                }
            }
            val readback = VulkanBuffer(renderer, BYTES * 6L, VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT or VK_MEMORY_PROPERTY_HOST_CACHED_BIT)
            var action: ((CommandBuffer, Int) -> Unit)? = null
            renderer.renderTargetFor().preTargets["restore-test"] = Target { command, index -> action?.invoke(command, index) }
            fun frame() { window.pollEvents(); renderer.render() }
            fun await(done: CompletableFuture<Unit>) {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
                while (!done.isDone && System.nanoTime() < deadline) frame()
                done.get(1, TimeUnit.SECONDS)
            }
            fun upload(command: CommandBuffer, image: Long, which: Int) {
                MemoryStack.stackPush().use { stack ->
                    vkCmdCopyBufferToImage(command.vkCommandBuffer, uploads[which].buffer, image,
                        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region(stack))
                }
            }
            for (target in listOf(ordinary, cadenced)) {
                action = { command, index -> target.render(command, index) }
                repeat(5) { frame() }
                val identities = views(target)
                val bound = ordinary.boundImage()
                val hooks = ordinary.renderHooks
                val cadence = cadenced.pingPongCount to cadenced.framesSincePingPong
                var done = CompletableFuture<Unit>()
                action = { command, _ ->
                    action = null
                    target.recordReplaceHistory(command, 0) { upload(command, it, 0) }
                    target.recordReplaceHistory(command, 1) { upload(command, it, 1) }
                    target.recordReplaceHistory(command, 0) { upload(command, it, 2) }
                    check(views(target) == identities) { "restore rotated a public view" }
                    check(ordinary.boundImage() == bound && ordinary.renderHooks == hooks) { "restore ran render hooks or changed the bound image" }
                    check(cadenced.pingPongCount to cadenced.framesSincePingPong == cadence) { "restore advanced cadence" }
                    check(runCatching { target.recordReplaceHistory(command, 2) {} }.exceptionOrNull() is IllegalArgumentException)
                    readRing(command, target, readback)
                    renderer.whenCurrentGraphicsFrameComplete { done.complete(Unit) }
                    check(!done.isDone) { "completion ran while commands were still recording" }
                }
                await(done)
                fun verify() = readback.mapAndRead_bytes {
                    for (a in 0..1) for (i in 0..2) for (b in 0 until BYTES) {
                        val expected = patterns[if (a == 0) 2 else 1][b]
                        check(get((a * 3 + i) * BYTES + b) == expected) { "wrong byte at attachment $a slot $i byte $b" }
                    }
                }
                verify()
                // Normal renders must retain the replacement and use the correct tracked layouts.
                action = { command, index -> target.render(command, index) }
                repeat(13) { frame() }
                done = CompletableFuture()
                action = { command, _ ->
                    action = null
                    readRing(command, target, readback)
                    renderer.whenCurrentGraphicsFrameComplete { done.complete(Unit) }
                }
                await(done)
                verify()
                println("${target.javaClass.simpleName}: all slots, attachments and layers restored; cadence retained")
            }
            // Cleanup retires submitted callbacks even if there will be no next render.
            val finalCompletion = CompletableFuture<Unit>()
            var finalRecorded = false
            action = { _, _ ->
                action = null
                renderer.whenCurrentGraphicsFrameComplete { finalCompletion.complete(Unit) }
                finalRecorded = true
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
            while (!finalRecorded && System.nanoTime() < deadline) frame()
            check(finalRecorded) { "The final frame was never recorded" }
            renderer.cleanup()
            check(finalCompletion.isDone) { "The final submitted capture was not completed at shutdown" }
            println("ping-pong history restore PASSED")
        } finally {
            try { renderer.cleanup() } finally { window.cleanup() }
        }
        exitProcess(0)
    }
}
