package afield.four

import afield.Named
import afield.four.buffers.VulkanBuffer
import afield.four.device.*
import afield.four.device.Queue.GraphicsQueue
import afield.four.device.Queue.PresentQueue
import afield.four.modules.ComputeModulePointer
import afield.four.modules.ModulePointer
import afield.four.targets.ComputeTarget
import afield.four.targets.RenderTarget
import afield.four.targets.RenderToTexture
import afield.four.util.MapOfSequences
import afield.four.util.Options
import afield.four.util.Window
import afield.logTime
import afield.logTime_
import kotlinx.coroutines.Job
import kotlinx.coroutines.yield
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.VK10
import java.lang.ref.ReferenceQueue
import kotlin.coroutines.coroutineContext


interface Target {
    fun render(command: CommandBuffer, index: Int)
}

fun target(a: (CommandBuffer, Int) -> Unit) = object : Target {
    override fun render(command: CommandBuffer, index: Int) {
        a(command, index)
    }
}

class Renderer(val window: Window) {
    val commandPool: CommandPool
    val computeCommandPool: CommandPool
    val device: Device
    val graphQueue: GraphicsQueue

    //    val computeQueue: Queue.ComputeQueue
    val instance: Instance
    val physicalDevice: PhysicalDevice
    val pipelineCache: PipelineCache
    val presentQueue: PresentQueue
    val surface: Surface
    var swapChain: SwapChain

    var computeFinishedSemaphores: Array<Semaphore>
    var depthAttachments = mutableListOf<Attachment>()

    var renderList: RenderTarget = RenderTarget(this)
    var computeList: ComputeTarget = ComputeTarget(this)

    var numImages: Int = 0
    var currentImage: Int = 0

    var commandBuffers: Array<CommandBuffer>
    var computeCommandBuffers: Array<CommandBuffer>
    var fences: Array<Fence>
    var computeFences: Array<Fence>

    var cleanup = mutableListOf<Runnable>()
    var cleanupStack = mutableListOf<MutableList<Runnable>>()

    var referenceQueue: ReferenceQueue<VulkanBuffer> = ReferenceQueue<VulkanBuffer>()


    var renderStart = MapOfSequences<String>()
    var renderEnd = MapOfSequences<String>()
    var currentCommandBuffer: CommandBuffer? = null


    init {
        instance = Instance(Options.validate)
        physicalDevice = PhysicalDevice.createPhysicalDevice(instance, Options.preferredDevice)
        device = Device(physicalDevice)
        surface = Surface(physicalDevice, window.windowHandle)
        graphQueue = GraphicsQueue(device, 0)
        presentQueue = PresentQueue(device, surface, 0)
        swapChain = SwapChain(
            device, surface, window, Options.requestedImages, Options.vsync, presentQueue, arrayOf<Queue>(graphQueue)
        )
        commandPool = CommandPool(device, graphQueue.queueFamilyIndex)
        computeCommandPool = CommandPool(device, graphQueue.queueFamilyIndex)
        pipelineCache = PipelineCache(device)

        numImages = swapChain.imageViews.size
        computeFinishedSemaphores = (0 until numImages).map { Semaphore(device) }.toTypedArray()

        commandBuffers = buildList(numImages) {
            for (i in 0 until numImages) {
                add(CommandBuffer(commandPool, true, false))
            }
        }.toTypedArray()

        computeCommandBuffers = buildList(numImages) {
            for (i in 0 until numImages) {
                add(CommandBuffer(computeCommandPool, true, false))
            }
        }.toTypedArray()

        fences = buildList(numImages) {
            for (i in 0 until numImages) {
                add(afield.four.device.Fence(device, true))
            }
        }.toTypedArray()

        computeFences = buildList(numImages) {
            for (i in 0 until numImages) {
                add(afield.four.device.Fence(device, true))
            }
        }.toTypedArray()

        createDepthImages()
    }

    fun cleanup() {
        presentQueue.waitIdle()
        graphQueue.waitIdle()
        device.waitIdle()
        pipelineCache.cleanup()
        commandPool.cleanup()
        swapChain.cleanup()
        surface.cleanup()
        device.cleanup()
        physicalDevice.cleanup()
        instance.cleanup()
    }


    fun render(/*scene: Scene*/) {
        if (window.width <= 0 && window.height <= 0) {
            return
        }


        var imageIndex = 0

        fences[numImages % fences.size].fenceWait() // what could this possibly do?

        try {
            if (window.isResized || (swapChain.acquireNextImage().also { imageIndex = it }) < 0) {
                window.resetResized()
                resize(window)
                imageIndex = swapChain.acquireNextImage()
            }

            currentImage = imageIndex
            //        print("$currentImage ")

            computeFences[imageIndex].fenceWait()
            computeFences[imageIndex].reset()


            val ccmd = computeCommandBuffers[imageIndex]
            ccmd.reset()
            ccmd.beginRecording()

            computeList?.render(ccmd, imageIndex)
            ccmd.endRecording()

            submitCompute(ccmd, computeFences[imageIndex], computeFinishedSemaphores[imageIndex], graphQueue)


            // there's a little more sync here — we should wait for compute to finish I think...
            val cmd = commandBuffers[imageIndex]
            cmd.reset()
            cmd.beginRecording()

            currentCommandBuffer = cmd
            renderStart.update()

            renderList?.render(cmd, imageIndex)
            cmd.endRecording()

            submit(commandBuffers[imageIndex], fences[imageIndex], graphQueue, computeFinishedSemaphores[imageIndex])

            if (swapChain.presentImage(presentQueue, imageIndex)) {
                window.isResized = true
            }

            cleanupStack.add(cleanup)
            cleanup = mutableListOf()
            if (cleanupStack.size >= numImages * 8) {
                val cleanup = cleanupStack.removeAt(0)
                if (cleanup.size > 0) {
                    val c = ArrayList(cleanup)
                    cleanup.clear()
                    c.forEach {
                        it.run()
                    }
                }
            }

            renderEnd.update()

            currentImage = (imageIndex + 1) % numImages

            currentCommandBuffer = null

        } catch (e: ImageNotAvailable) {
            println(e.message)
        }
    }

    class ImageNotAvailable(val s: String) : RuntimeException(s);

    fun submit(
        commandBuffer: CommandBuffer, currentFence: afield.four.device.Fence, queue: Queue, computeSemaphore: Semaphore
    ) {
        MemoryStack.stackPush().use { stack ->
            val idx = swapChain.currentFrame
            currentFence.reset()
            val syncSemaphores = swapChain.syncSemaphoresList[idx]
            queue.submit(
                stack.pointers(commandBuffer.vkCommandBuffer),
                stack.longs(syncSemaphores.imgAcquisitionSemaphore.vkSemaphore, computeSemaphore.vkSemaphore),
                stack.ints(
                    VK10.VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT
                ),
                stack.longs(syncSemaphores.renderCompleteSemaphore.vkSemaphore),
                currentFence
            )
        }
    }

    fun submitCompute(
        commandBuffer: CommandBuffer,
        currentComputeFence: afield.four.device.Fence,
        signalSemaphore: afield.four.device.Semaphore,
        queue: Queue
    ) {
        MemoryStack.stackPush().use { stack ->
            val idx = swapChain.currentFrame
            currentComputeFence.reset()
            queue.submit(
                stack.pointers(commandBuffer.vkCommandBuffer),
                null,
                null,
                stack.longs(signalSemaphore.vkSemaphore),
                currentComputeFence
            )
        }

        // graphics needs to wait for currentComputeFence
    }


    private fun createDepthImages() {
        val numImages = swapChain.numImages
        val swapChainExtent = swapChain.swapChainExtent
        depthAttachments = buildList(numImages) {
            for (i in 0 until numImages) {
                add(
                    Attachment(
                        device,
                        swapChainExtent.width(),
                        swapChainExtent.height(),
                        VK10.VK_FORMAT_D24_UNORM_S8_UINT,
                        VK10.VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT
                    )
                )
            }
        }.toMutableList()
    }

    private fun resize(window: Window) {

        device.waitIdle()
        graphQueue.waitIdle()

        swapChain.cleanup()

        swapChain = SwapChain(
            device, surface, window, Options.requestedImages, Options.vsync, presentQueue, arrayOf<Queue>(graphQueue)
        )

        depthAttachments.forEach { it.cleanup() }
        createDepthImages()

    }

    var running = false
    fun runForever(update: () -> Unit) {
        running = true
        var initialTime = System.currentTimeMillis()
        var frame = 0
        while (running && !window.shouldClose()) {
            print(".")
            window.pollEvents()

            update()

            render()

            frame++
            if (frame % 200 == 0) {
                print(" fps=${1000.0 * 200 / (System.currentTimeMillis() - initialTime)} ")
                initialTime = System.currentTimeMillis()
            }
        }

        cleanup()
    }

    suspend fun run() {
        running = true
        var initialTime = System.currentTimeMillis()
        var lastTime = System.currentTimeMillis()
        var frame = 0
        while (running && !window.shouldClose()) {
            logTime("pollEvents") { window.pollEvents() }

            logTime("render") { render() }

            frame++
            if (frame % 100 == 0) {
                print(" fps=${1000.0 * 100 / (System.currentTimeMillis() - initialTime)} ")
                initialTime = System.currentTimeMillis()
            }
            logTime_("compute") {
                yield()
            }

            if (coroutineContext[Job]!!.isCancelled) {
                break
            }
        }
    }

    @JvmName("plusAssign_modulePointer")
    operator fun plusAssign(a: Pair<String, ModulePointer>) {
        renderList.apply {
            when (this) {
                is RenderTarget -> this += a
                is RenderToTexture -> this += a
                else -> throw IllegalArgumentException("Unsupported type ${a.first}, ${a.second}")
            }
        }
    }

    @JvmName("plusAssign_modulePointer_defaultName")
    operator fun plusAssign(a: ModulePointer) {
        renderList.apply {
            when (this) {
                is RenderTarget -> this += a
                is RenderToTexture -> this += a
                else -> throw IllegalArgumentException("Unsupported type ${this}")
            }
        }
    }

    operator fun plusAssign(a: Named<ModulePointer>) {
        renderList.apply {
            when (this) {
                is RenderTarget -> this += a.name to a.value
                is RenderToTexture -> this += a.name to a.value
                else -> throw IllegalArgumentException("Unsupported type ${a}")
            }
        }
    }

    @JvmName("plusAssign_computeModulePointer")
    operator fun plusAssign(a: Pair<String, ComputeModulePointer>) {
        computeList.apply {
            when (this) {
                is ComputeTarget -> this += a
                else -> throw IllegalArgumentException("Unsupported type ${a.first}, ${a.second}")
            }
        }
    }

}
