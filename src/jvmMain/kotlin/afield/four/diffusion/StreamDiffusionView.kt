package afield.four.diffusion

import afield.four.Renderer
import afield.four.Target
import afield.four.device.CommandBuffer
import afield.four.targets.RenderToTexture
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.*
import org.lwjgl.vulkan.VK13.*

/**
 * GPU RTT -> optional CUDA worker -> ordinary sampled RTT. Construct/close between frames.
 * With renderSource=true this target owns scheduling of source; do not also register source.
 * With renderSource=false the caller renders source earlier in the same command buffer.
 */
class StreamDiffusionView(
    val renderer: Renderer, val source: RenderToTexture, val config: StreamDiffusionConfig,
    val name: String = "stream-diffusion", val renderSource: Boolean = true,
    val sourceTimeNanos: () -> Long = System::nanoTime
) : Target, AutoCloseable {
    val output: RenderToTexture
    val session: StreamDiffusionSession
    var capturedFrames = 0L; private set
    var skippedCaptures = 0L; private set
    var presentedFrames = 0L; private set
    var droppedOutputs = 0L; private set
    var lastSourceFrameId = -1L; private set
    var lastSourceTimeNanos = 0L; private set
    private var frameId = 0L
    private var captureDue = 0L
    private var presentDue = 0L
    private val interval = 1_000_000_000L / config.fps
    private var initialized = false
    private var hasOutput = false
    @Volatile private var closed = false

    init {
        check(renderer.currentCommandBuffer == null && !renderer.isClosed)
        val support = StreamDiffusionSupport.probe(renderer, config)
        check(support.available) { support.reason }
        if (config.backend == "diffusion") require(source.width % 32 == 0 && source.height % 32 == 0) {
            "Diffusion input dimensions must be multiples of 32"
        }
        require(source.colorLayerCount == 1 && source.colorImageRefs[0]?.imageData?.sampleCount == VK_SAMPLE_COUNT_1_BIT)
        require(source.colorFormats[0] in setOf(VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_R8G8B8A8_SRGB,
            VK_FORMAT_B8G8R8A8_UNORM, VK_FORMAT_B8G8R8A8_SRGB)) { "Diffusion input must be RGBA8/BGRA8" }
        require(source.parameters.colorTransferSrc)
        check(!renderer.renderList.preTargets.containsKey(name))
        output = RenderToTexture(renderer) {
            width = source.width; height = source.height; format = source.colorFormats[0]
            useDepth = false; colorSampled = true; colorTransferDst = true
        }
        output.setDefaultSampler(anisotropyEnable = false)
        try { session = StreamDiffusionSession(renderer, source.width, source.height, source.colorFormats[0], config) }
        catch (e: Throwable) { cleanupOutput(); throw e }
        renderer.renderList.preTargets[name] = this
    }

    override fun render(command: CommandBuffer, index: Int) {
        if (closed) return
        if (renderSource) source.render(command, index)
        // Show an actual source frame while the backend loads, using a GPU image copy.
        if (!hasOutput) {
            source.recordColorTransferSource(command) { from ->
                output.recordColorTransferDestination(command) { to ->
                    MemoryStack.stackPush().use { s ->
                        val r = VkImageCopy.calloc(1, s)
                            .srcSubresource { it.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1) }
                            .dstSubresource { it.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1) }
                            .extent { it.width(source.width).height(source.height).depth(1) }
                        vkCmdCopyImage(command.vkCommandBuffer, from, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                            to, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, r)
                    }
                }
            }
        }
        if (session.isClosed) return
        if (!initialized) {
            session.outputs.forEach { it.barrier(command, acquire = false, access = 0) }
            initialized = true
        }
        val now = System.nanoTime()
        while (true) {
            val result = session.results.peek() ?: break
            val stale = result.generation != session.generation || session.results.size > 4
            if (!stale && now < presentDue) break
            session.results.poll()
            val slot = session.outputs[result.slot]
            renderer.externalGraphicsSync(command, slot.semaphore, result.value, wait = true)
            slot.barrier(command, acquire = true, access = VK_ACCESS_2_TRANSFER_READ_BIT)
            if (!stale) {
                output.recordColorTransferDestination(command) { image ->
                    MemoryStack.stackPush().use { s ->
                        vkCmdCopyBufferToImage(command.vkCommandBuffer, slot.buffer, image,
                            VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, slot.copyRegion(s, source.width, source.height))
                    }
                }
                hasOutput = true; presentedFrames++
                lastSourceFrameId = result.frameId; lastSourceTimeNanos = result.timeNanos
                presentDue = if (presentDue == 0L) now + interval else presentDue + ((now - presentDue) / interval + 1) * interval
            } else {
                droppedOutputs++
            }
            slot.barrier(command, acquire = false, access = VK_ACCESS_2_TRANSFER_READ_BIT)
            renderer.externalGraphicsSync(command, slot.semaphore, result.value + 1, wait = false)
            renderer.afterCurrentGraphicsFrameComplete { session.outputFree(result.slot, result.value + 1) }
            if (!stale) break
        }
        if (!session.ready || now < captureDue) return
        captureDue = if (captureDue == 0L) now + interval else captureDue + ((now - captureDue) / interval + 1) * interval
        val id = frameId++
        val inputIndex = session.reserveInput()
        if (inputIndex < 0) { skippedCaptures++; return }
        val slot = session.inputs[inputIndex]
        if (slot.used) {
            renderer.externalGraphicsSync(command, slot.semaphore, slot.value + 1, wait = true)
            slot.barrier(command, acquire = true, access = VK_ACCESS_2_TRANSFER_WRITE_BIT)
        }
        slot.value += if (slot.used) 2 else 1
        slot.used = true
        source.recordColorTransferSource(command) { image ->
            MemoryStack.stackPush().use { s ->
                vkCmdCopyImageToBuffer(command.vkCommandBuffer, image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    slot.buffer, slot.copyRegion(s, source.width, source.height))
            }
        }
        slot.barrier(command, acquire = false, access = VK_ACCESS_2_TRANSFER_WRITE_BIT)
        renderer.externalGraphicsSync(command, slot.semaphore, slot.value, wait = false)
        val value = slot.value
        val generation = session.generation
        val time = sourceTimeNanos()
        renderer.afterCurrentGraphicsFrameComplete { session.frame(inputIndex, value, id, time, generation) }
        capturedFrames++
    }

    /** Change artistic controls; preserves the last valid image during causal warm-up. */
    fun reset(prompt: String = config.prompt, strength: Double = config.strength, seed: Int = config.seed) =
        session.reset(prompt, strength, seed)

    private fun cleanupOutput() {
        renderer.cleanup += Runnable {
            output.imageViewArray.forEach { it.imageView.cleanup() }
            output.colorImageRefs.forEach { it?.cleanup() }
            output.defaultSampler?.cleanup()
        }
    }
    override fun close() {
        check(renderer.currentCommandBuffer == null) { "Close diffusion between graphics frames" }
        if (closed) return
        closed = true
        renderer.renderList.preTargets.remove(name, this)
        session.close()
        cleanupOutput()
    }
}
