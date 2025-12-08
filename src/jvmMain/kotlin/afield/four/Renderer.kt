package afield.four

import afield.Named
import afield.four.buffers.VulkanBuffer
import afield.four.device.*
import afield.four.device.Queue.GraphicsQueue
import afield.four.device.Queue.PresentQueue
import afield.four.modules.ComputeModulePointer
import afield.four.modules.MeshModulePointer
import afield.four.modules.ModulePointer
import afield.four.targets.ComputeTarget
import afield.four.targets.RenderTarget
import afield.four.texture.ImageView
import afield.four.util.MapOfSequences
import afield.four.util.AutoDisposer
import afield.four.util.Options
import afield.four.util.Window
import afield.logTime
import afield.logTime_
import kotlinx.coroutines.Job
import kotlinx.coroutines.yield
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.KHRSurface
import org.lwjgl.vulkan.VK10
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_BOTTOM_OF_PIPE_BIT
import org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_TOP_OF_PIPE_BIT
import org.lwjgl.vulkan.VK13.*
import java.lang.ref.ReferenceQueue
import kotlin.coroutines.coroutineContext
import org.lwjgl.vulkan.VK13.vkQueueSubmit2
import org.lwjgl.vulkan.VkSubmitInfo2
import org.lwjgl.vulkan.VkSemaphoreSubmitInfo
import org.lwjgl.vulkan.VkCommandBufferSubmitInfo

fun interface Target { fun render(command: CommandBuffer, index: Int) }

fun target(a: (CommandBuffer, Int) -> Unit) = object : Target {
    override fun render(command: CommandBuffer, index: Int) = a(command, index)
}

/** Lightweight CPU span stats. */
object CPUSpan {
    data class Stat(var n: Long = 0, var sumNs: Long = 0, var minNs: Long = Long.MAX_VALUE, var maxNs: Long = 0) {
        fun add(dtNs: Long) { n++; sumNs += dtNs; if (dtNs < minNs) minNs = dtNs; if (dtNs > maxNs) maxNs = dtNs }
        fun avgMs() = (sumNs.toDouble() / n) / 1e6
        fun minMs() = if (n > 0) minNs / 1e6 else 0.0
        fun maxMs() = if (n > 0) maxNs / 1e6 else 0.0
    }
    private val stats = linkedMapOf<String, Stat>()
    fun time(label: String, block: () -> Unit) {
        val t0 = System.nanoTime()
        block()
        stats.getOrPut(label) { Stat() }.add(System.nanoTime() - t0)
    }
    fun snapshotAndMaybePrint(everyNFrames: Int, frame: Long, header: String = "CPU") {
        if (frame % everyNFrames != 0L) return
        val line = buildString {
            append("$header: ")
            append(stats.entries.joinToString("  ") { (k, s) ->
                "$k=${"%.3f".format(s.avgMs())}ms(avg) [${"%.3f".format(s.minMs())},${"%.3f".format(s.maxMs())}]"
            })
        }
        println(line)
    }
}

class Renderer(
    private val mainWindow: Window,
    additionalWindows: List<Window> = emptyList()
) {

    data class WindowContext(
        val window: Window,
        val surface: Surface,
        var swapChain: SwapChain,
        var depthAttachments: MutableList<Attachment>,
        var commandBuffers: Array<CommandBuffer>,
        var computeCommandBuffers: Array<CommandBuffer>,
        var fences: Array<Fence>,
        var computeFences: Array<Fence>,
        var computeFinishedSemaphores: Array<Semaphore>,
        var numImages: Int,
        var currentImage: Int = 0,
        var renderTarget: RenderTarget,
        var profilerState: ProfilerState? = null
    )

    data class ProfilerState(
        val profiler: GpuProfiler,
        var markers: Array<FrameMarkers>,
        var perImageModuleMarks: Array<MutableList<ModuleMarks>>,
        val pending: ArrayDeque<Pending> = ArrayDeque()
    )

    val windows: List<Window> = listOf(mainWindow) + additionalWindows
    val window: Window get() = windows.first()

    // --- Core Vulkan objects ---
    val commandPool: CommandPool
    val computeCommandPool: CommandPool
    val device: Device
    val graphQueue: GraphicsQueue
    val instance: Instance
    val physicalDevice: PhysicalDevice
    val pipelineCache: PipelineCache
    val presentQueue: PresentQueue
    val surface: Surface get() = defaultContext.surface
    val swapChain: SwapChain get() = defaultContext.swapChain

    var computeList: ComputeTarget = ComputeTarget(this)

    var currentImage: Int
        get() = defaultContext.currentImage
        private set(value) { defaultContext.currentImage = value }

    val numImages: Int
        get() = defaultContext.numImages

    val depthAttachments: MutableList<Attachment> get() = defaultContext.depthAttachments

    var renderList: RenderTarget
        get() = defaultContext.renderTarget
        set(value) { defaultContext.renderTarget = value }

    private val windowContexts = mutableListOf<WindowContext>()
    private val defaultContext get() = windowContexts.first()
    fun windowContext(index: Int = 0): WindowContext = windowContexts[index]
    fun renderTargetFor(windowIndex: Int = 0): RenderTarget = windowContext(windowIndex).renderTarget

    var cleanup = mutableListOf<Runnable>()
    var cleanupStack = mutableListOf<Pair<Long, MutableList<Runnable>>>()

    var renderStart = MapOfSequences<String>()
    var renderEnd = MapOfSequences<String>()
    var currentCommandBuffer: CommandBuffer? = null

    data class FrameMarkers(
        var tsFrameBegin: Int = -1,
        var tsAfterCompute: Int = -1,
        var tsAfterGraphics: Int = -1,
        var tsFrameEnd: Int = -1,
        var pipeStatsCompute: Int = -1,
        var pipeStatsGraphics: Int = -1,
        var perfFrameIndex: Int = -1
    )
    data class Pending(val imageIndex: Int)
    private companion object { const val READBACK_LAG = 3 }

    // Per-module instrumentation
    data class ModuleMarks(
        val name: String,
        var tsBegin: Int = -1,
        var tsEnd: Int = -1,
        var pipeStatsIndex: Int = -1,
        var perfIndex: Int = -1
    )
    fun registerModuleMarks(imageIndex: Int, marks: ModuleMarks) {
        registerModuleMarks(windowIndex = 0, imageIndex = imageIndex, marks = marks)
    }

    fun registerModuleMarks(windowIndex: Int, imageIndex: Int, marks: ModuleMarks) {
        val ctx = windowContexts.getOrNull(windowIndex) ?: return
        val ps = ctx.profilerState ?: return
        ps.perImageModuleMarks.getOrNull(imageIndex)?.add(marks)
    }

    init {
        instance = Instance(Options.validate)
        physicalDevice = PhysicalDevice.createPhysicalDevice(instance, Options.preferredDevice)
        device = Device(physicalDevice)
        instance.dumpToolingInfo(instance.vkInstance, physicalDevice.vkPhysicalDevice)
        graphQueue = GraphicsQueue(device, 0)
        val primarySurface = Surface(physicalDevice, window.windowHandle)
        presentQueue = PresentQueue(device, primarySurface, 0)
        commandPool = CommandPool(device, graphQueue.queueFamilyIndex)
        computeCommandPool = CommandPool(device, graphQueue.queueFamilyIndex)
        pipelineCache = PipelineCache(device)
        windows.forEachIndexed { idx, w ->
            val surfaceOverride = if (idx == 0) primarySurface else null
            windowContexts += createWindowContext(w, idx, surfaceOverride)
        }

        // ---- Name core objects for tooling ----
        device.nameObject(instance.vkInstance.address(), VK10.VK_OBJECT_TYPE_INSTANCE, "Instance")
        device.nameObject(physicalDevice.vkPhysicalDevice.address(), VK10.VK_OBJECT_TYPE_PHYSICAL_DEVICE, "PhysicalDevice")
        device.nameObject(device.vkDevice.address(), VK10.VK_OBJECT_TYPE_DEVICE, "Device")
        device.nameObject(graphQueue.vkQueue.address(), VK10.VK_OBJECT_TYPE_QUEUE, "GraphicsQueue")
        device.nameObject(presentQueue.vkQueue.address(), VK10.VK_OBJECT_TYPE_QUEUE, "PresentQueue")
        device.nameObject(commandPool.vkCommandPool, VK10.VK_OBJECT_TYPE_COMMAND_POOL, "GraphicsCmdPool")
        device.nameObject(computeCommandPool.vkCommandPool, VK10.VK_OBJECT_TYPE_COMMAND_POOL, "ComputeCmdPool")
        device.nameObject(pipelineCache.vkPipelineCache, VK10.VK_OBJECT_TYPE_PIPELINE_CACHE, "PipelineCache")
    }

    fun cleanup() {
        presentQueue.waitIdle()
        graphQueue.waitIdle()
        device.waitIdle()
        windowContexts.forEach { ctx ->
            ctx.profilerState?.profiler?.destroy()
            ctx.commandBuffers.forEach { it.cleanup() }
            ctx.computeCommandBuffers.forEach { it.cleanup() }
            ctx.fences.forEach { it.cleanup() }
            ctx.computeFences.forEach { it.cleanup() }
            ctx.computeFinishedSemaphores.forEach { it.cleanup() }
            ctx.depthAttachments.forEach { it.cleanup() }
            ctx.swapChain.cleanup()
            ctx.surface.cleanup()
        }
        pipelineCache.cleanup()
        commandPool.cleanup()
        computeCommandPool.cleanup()
        device.cleanup()
        physicalDevice.cleanup()
        instance.cleanup()
    }

    var frameCounter = 0L

    fun render() {
        val maxFramesInFlight = windowContexts.maxOfOrNull { it.numImages } ?: return

        // Drain ready frames (no stalls) per window.
        windowContexts.forEach { ctx ->
            val ps = ctx.profilerState ?: return@forEach
            while (ps.pending.isNotEmpty()) {
                val head = ps.pending.first()
                val enoughLag = ps.pending.size > READBACK_LAG
                val signaled = ctx.fences[head.imageIndex].isSignaled()
                if (!enoughLag && !signaled) break
                if (signaled) {
                    resolveProfilingForImage(ctx, head.imageIndex)
                    ps.pending.removeFirst()
                } else break
            }
        }

        windowContexts.forEachIndexed { ctxIndex, ctx ->
            val w = ctx.window
            if (w.width <= 0 && w.height <= 0) return@forEachIndexed

            var imageIndex = 0

            // Keeps swapchain pipeline moving; left as-is from original code.
            ctx.fences[ctx.swapChain.currentFrame].fenceWait()

            try {
                CPUSpan.time("acquire[$ctxIndex]") {
                    if (w.isResized || (ctx.swapChain.acquireNextImage().also { imageIndex = it }) < 0) {
                        w.resetResized()
                        resize(ctx)
                        imageIndex = ctx.swapChain.acquireNextImage()
                    }
                }

                ctx.currentImage = imageIndex
                if (ctxIndex == 0) currentImage = imageIndex
                ctx.profilerState?.let {
                    it.profiler.beginFrame(imageIndex)
                    it.perImageModuleMarks.getOrNull(imageIndex)?.clear()
                }

                // Compute pass
                var csScope: GpuProfiler.PipeStatScope? = null
                var pcScope: GpuProfiler.PerfScope? = null
                CPUSpan.time("record_cs[$ctxIndex]") {
                    ctx.computeFences[imageIndex].fenceWait()
                    ctx.computeFences[imageIndex].reset()

                    val ccmd = ctx.computeCommandBuffers[imageIndex]
                    ccmd.reset(); ccmd.beginRecording()

                    // --- Label start (compute pass) ---
                    MemoryStack.stackPush().use { s ->
                        val c = s.floats(0.25f, 0.65f, 0.85f, 1f) // cyan-ish
                        ccmd.beginLabel("Compute Pass [frame $frameCounter ctx=$ctxIndex]", c)
                    }

                    ctx.profilerState?.let { p ->
                        p.markers[imageIndex].tsFrameBegin = p.profiler.writeTimestamp2(
                            ccmd.vkCommandBuffer, imageIndex, VK_PIPELINE_STAGE_2_TOP_OF_PIPE_BIT
                        )
                        val cs = p.profiler.beginPipelineStats(ccmd.vkCommandBuffer, imageIndex)
                        csScope = cs
                        csScope?.let { p.markers[imageIndex].pipeStatsCompute = it.index }
                        pcScope = p.profiler.beginPerfCounters(ccmd.vkCommandBuffer, imageIndex)
                    }

                    computeList.render(ccmd, imageIndex)

                    ctx.profilerState?.let { p ->
                        p.profiler.endPerfCounters(ccmd.vkCommandBuffer, imageIndex, pcScope)
                        p.profiler.endPipelineStats(ccmd.vkCommandBuffer, csScope)
                        p.markers[imageIndex].tsAfterCompute = p.profiler.writeTimestamp2(
                            ccmd.vkCommandBuffer, imageIndex, VK_PIPELINE_STAGE_2_BOTTOM_OF_PIPE_BIT
                        )
                    }

                    // --- Label end (compute pass) ---
                    ccmd.endLabel()

                    ccmd.endRecording()
                }

                CPUSpan.time("submit_cs[$ctxIndex]") {
                    submitCompute(
                        ctx,
                        ctx.computeCommandBuffers[imageIndex],
                        ctx.computeFences[imageIndex],
                        ctx.computeFinishedSemaphores[imageIndex],
                        graphQueue
                    )
                }

                // Graphics pass
                CPUSpan.time("record_gfx[$ctxIndex]") {
                    val cmd = ctx.commandBuffers[imageIndex]
                    cmd.reset(); cmd.beginRecording()

                    // --- Label start (graphics frame) ---
                    MemoryStack.stackPush().use { s ->
                        val c = s.floats(0.85f, 0.45f, 0.15f, 1f) // orange-ish
                        cmd.beginLabel("Graphics Frame [frame $frameCounter ctx=$ctxIndex]", c)
                    }

                    currentCommandBuffer = cmd
                    renderStart.update()

                    var gsScope: GpuProfiler.PipeStatScope? = null
                    var perfFrameScope: GpuProfiler.PerfScope? = null

                    ctx.profilerState?.let { p ->
                        gsScope = p.profiler.beginPipelineStats(cmd.vkCommandBuffer, imageIndex)
                        gsScope?.let { p.markers[imageIndex].pipeStatsGraphics = it.index }
                        perfFrameScope = p.profiler.beginPerfCounters(cmd.vkCommandBuffer, imageIndex)
                    }

                    // Bracket the scene render itself
                    MemoryStack.stackPush().use { s ->
                        val c = s.floats(0.45f, 0.85f, 0.25f, 1f) // green-ish
                        cmd.beginLabel("RenderTargets", c)
                    }

                    ctx.renderTarget.render(cmd, imageIndex)

                    cmd.endLabel() // end "RenderTargets"

                    ctx.profilerState?.let { p ->
                        p.profiler.endPerfCounters(cmd.vkCommandBuffer, imageIndex, perfFrameScope)
                        perfFrameScope?.let { p.markers[imageIndex].perfFrameIndex = it.index }
                        p.profiler.endPipelineStats(cmd.vkCommandBuffer, gsScope)
                        p.markers[imageIndex].tsAfterGraphics = p.profiler.writeTimestamp2(
                            cmd.vkCommandBuffer, imageIndex, VK_PIPELINE_STAGE_2_TOP_OF_PIPE_BIT
                        )
                        p.markers[imageIndex].tsFrameEnd = p.profiler.writeTimestamp2(
                            cmd.vkCommandBuffer, imageIndex, VK_PIPELINE_STAGE_2_BOTTOM_OF_PIPE_BIT
                        )
                    }

                    // --- Label end (graphics frame) ---
                    cmd.endLabel()

                    cmd.endRecording()
                }

                CPUSpan.time("submit_gfx[$ctxIndex]") {
                    submit(
                        ctx,
                        ctx.commandBuffers[imageIndex],
                        ctx.fences[imageIndex],
                        graphQueue,
                        ctx.computeFinishedSemaphores[imageIndex]
                    )
                }

                CPUSpan.time("present[$ctxIndex]") {
                    if (ctx.swapChain.presentImage(presentQueue, imageIndex)) w.isResized = true
                }

                ctx.profilerState?.pending?.addLast(Pending(imageIndex))
            } catch (e: ImageNotAvailable) {
                println(e.message)
            }
        }

        // Deferred cleanup queue (use max frames-in-flight across all contexts)
        cleanupStack.add(frameCounter to cleanup)
        cleanup = mutableListOf()
        val thresholdFrame = frameCounter - maxFramesInFlight * 8
        while (cleanupStack.isNotEmpty() && cleanupStack.first().first <= thresholdFrame) {
            val (_, toRun) = cleanupStack.removeAt(0)
            if (toRun.isNotEmpty()) {
                val copy = ArrayList(toRun)
                toRun.clear()
                copy.forEach { it.run() }
            }
        }

        // Drain GC-driven disposals onto the render-thread cleanup queue
        AutoDisposer.drain { cleanup.add(it) }

        renderEnd.update()
        CPUSpan.snapshotAndMaybePrint(everyNFrames = 120, frame = frameCounter)

        if (windowContexts.isNotEmpty()) {
            currentImage = (defaultContext.currentImage + 1) % defaultContext.numImages
        }

        frameCounter++
        currentCommandBuffer = null
    }

    class ImageNotAvailable(s: String) : RuntimeException(s)

    fun submit(
        context: WindowContext,
        commandBuffer: CommandBuffer,
        currentFence: Fence,
        queue: Queue,
        computeSemaphore: Semaphore
    ) {
        MemoryStack.stackPush().use { stack ->
            val idx = context.swapChain.currentFrame
            currentFence.reset()
            val sync = context.swapChain.syncSemaphoresList[idx]

            // Command buffer info
            val cbInfo = VkCommandBufferSubmitInfo.calloc(1, stack)
                .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_SUBMIT_INFO)
            cbInfo[0]
                .commandBuffer(commandBuffer.vkCommandBuffer)
                .deviceMask(0)

            // Wait semaphores: image-acquired + compute-finished
            val waits = VkSemaphoreSubmitInfo.calloc(2, stack)
            waits[0].sType(VK_STRUCTURE_TYPE_SEMAPHORE_SUBMIT_INFO)
                .semaphore(sync.imgAcquisitionSemaphore.vkSemaphore)
                .value(sync.imgAcquisitionSemaphore.currentValue())
                .stageMask(VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT)
                .deviceIndex(0)
            waits[1].sType(VK_STRUCTURE_TYPE_SEMAPHORE_SUBMIT_INFO)
                .semaphore(computeSemaphore.vkSemaphore)
                .value(computeSemaphore.currentValue())
                // conservative: ensure graphics won't start any stage before compute finished
                .stageMask(VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT)
                .deviceIndex(0)

            // Signal semaphore: render complete
            val signals = VkSemaphoreSubmitInfo.calloc(1, stack)
            signals[0].sType(VK_STRUCTURE_TYPE_SEMAPHORE_SUBMIT_INFO)
                .semaphore(sync.renderCompleteSemaphore.vkSemaphore)
                .value(sync.renderCompleteSemaphore.nextSignalValue())
                .stageMask(VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT)
                .deviceIndex(0)

            val submit2 = VkSubmitInfo2.calloc(1, stack)
                .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO_2)
            submit2[0]
                .pWaitSemaphoreInfos(waits)
                .pSignalSemaphoreInfos(signals)
                .pCommandBufferInfos(cbInfo)

            vkQueueSubmit2(queue.vkQueue, submit2, currentFence.vkFence)
        }
    }


    fun submitCompute(
        context: WindowContext,
        commandBuffer: CommandBuffer,
        currentComputeFence: Fence,
        signalSemaphore: Semaphore,
        queue: Queue
    ) {
        MemoryStack.stackPush().use { stack ->
            currentComputeFence.reset()

            val cbInfo = VkCommandBufferSubmitInfo.calloc(1, stack)
                .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_SUBMIT_INFO)
            cbInfo[0]
                .commandBuffer(commandBuffer.vkCommandBuffer)
                .deviceMask(0)

            // No waits; just signal on completion of the compute work
            val signals = VkSemaphoreSubmitInfo.calloc(1, stack)
            signals[0].sType(VK_STRUCTURE_TYPE_SEMAPHORE_SUBMIT_INFO)
                .semaphore(signalSemaphore.vkSemaphore)
                .value(signalSemaphore.nextSignalValue())
                .stageMask(VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT)
                .deviceIndex(0)

            val submit2 = VkSubmitInfo2.calloc(1, stack)
                .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO_2)
            submit2[0]
                .pCommandBufferInfos(cbInfo)
                .pSignalSemaphoreInfos(signals)

            vkQueueSubmit2(queue.vkQueue, submit2, currentComputeFence.vkFence)
        }
    }

    private fun createDepthImages(swapChain: SwapChain): MutableList<Attachment> {
        val count = swapChain.numImages
        val extent = swapChain.swapChainExtent
        return MutableList(count) {
            Attachment(
                device,
                extent.width(),
                extent.height(),
                Options.defaultDepthFormat,
                VK10.VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT
            )
        }
    }

    private fun createProfilerState(numImages: Int): ProfilerState {
        val opts = ProfilingOptions(
            enabled = true,
            timestamps = true,
            pipelineStats = Options.isWindows,
            perfCounters = Options.isWindows
        )
        val profiler = GpuProfiler(
            device = device.vkDevice,
            physicalDevice = device.physicalDevice.vkPhysicalDevice,
            queueFamilyIndex = graphQueue.queueFamilyIndex,
            framesInFlight = numImages,
            opts = opts
        )
        val markers = Array(numImages) { FrameMarkers() }
        val perImageModuleMarks = Array(numImages) { mutableListOf<ModuleMarks>() }
        return ProfilerState(profiler, markers, perImageModuleMarks)
    }

    private fun createWindowContext(window: Window, index: Int, surfaceOverride: Surface? = null): WindowContext {
        lateinit var ctxRef: WindowContext
        val ctxProvider = { ctxRef }
        val surface = surfaceOverride ?: Surface(physicalDevice, window.windowHandle)
        val swapChain = SwapChain(
            device,
            surface,
            window,
            Options.requestedImages,
            Options.vsync,
            presentQueue,
            arrayOf(graphQueue)
        )
        val numImages = swapChain.imageViews.size
        val commandBuffers = Array(numImages) { CommandBuffer(commandPool, primary = true, oneTimeSubmit = false) }
        val computeCommandBuffers = Array(numImages) { CommandBuffer(computeCommandPool, primary = true, oneTimeSubmit = false) }
        val fences = Array(numImages) { Fence(device, signaled = true) }
        val computeFences = Array(numImages) { Fence(device, signaled = true) }
        val computeFinishedSemaphores = Array(numImages) { Semaphore(device) }
        val depthAttachments = createDepthImages(swapChain)
        val profilerState = if (Options.profiling) createProfilerState(numImages) else null
        val renderTarget = RenderTarget(this, ctxProvider, index)

        ctxRef = WindowContext(
            window = window,
            surface = surface,
            swapChain = swapChain,
            depthAttachments = depthAttachments,
            commandBuffers = commandBuffers,
            computeCommandBuffers = computeCommandBuffers,
            fences = fences,
            computeFences = computeFences,
            computeFinishedSemaphores = computeFinishedSemaphores,
            numImages = numImages,
            currentImage = 0,
            renderTarget = renderTarget,
            profilerState = profilerState
        )
        nameContextObjects(ctxRef, index)
        return ctxRef
    }

    private fun nameContextObjects(ctx: WindowContext, index: Int) {
        device.nameObject(ctx.surface.vkSurface, KHRSurface.VK_OBJECT_TYPE_SURFACE_KHR, "Surface[$index]")
        ctx.commandBuffers.forEachIndexed { i, cb ->
            device.nameObject(cb.vkCommandBuffer.address(), VK10.VK_OBJECT_TYPE_COMMAND_BUFFER, "GFX CB[$index,$i]")
        }
        ctx.computeCommandBuffers.forEachIndexed { i, cb ->
            device.nameObject(cb.vkCommandBuffer.address(), VK10.VK_OBJECT_TYPE_COMMAND_BUFFER, "COMPUTE CB[$index,$i]")
        }
        ctx.fences.forEachIndexed { i, f ->
            device.nameObject(f.vkFence, VK10.VK_OBJECT_TYPE_FENCE, "GFX Fence[$index,$i]")
        }
        ctx.computeFences.forEachIndexed { i, f ->
            device.nameObject(f.vkFence, VK10.VK_OBJECT_TYPE_FENCE, "Compute Fence[$index,$i]")
        }
        ctx.computeFinishedSemaphores.forEachIndexed { i, s ->
            device.nameObject(s.vkSemaphore, VK10.VK_OBJECT_TYPE_SEMAPHORE, "Compute->Graphics Semaphore[$index,$i]")
        }
        ctx.depthAttachments.forEachIndexed { i, a ->
            device.nameObject(a.image.vkImage, VK10.VK_OBJECT_TYPE_IMAGE, "DepthImage[$index,$i]")
            device.nameObject(a.imageView.vkImageView, VK10.VK_OBJECT_TYPE_IMAGE_VIEW, "DepthView[$index,$i]")
        }
    }

    private fun resize(context: WindowContext) {
        device.waitIdle()
        graphQueue.waitIdle()

        context.swapChain.cleanup()
        context.swapChain = SwapChain(
            device, context.surface, context.window, Options.requestedImages, Options.vsync, presentQueue, arrayOf(graphQueue)
        )

        context.depthAttachments.forEach { it.cleanup() }
        context.depthAttachments = createDepthImages(context.swapChain)

        context.commandBuffers.forEach { it.cleanup() }
        context.computeCommandBuffers.forEach { it.cleanup() }
        context.fences.forEach { it.cleanup() }
        context.computeFences.forEach { it.cleanup() }
        context.computeFinishedSemaphores.forEach { it.cleanup() }

        val newImages = context.swapChain.imageViews.size
        context.commandBuffers = Array(newImages) { CommandBuffer(commandPool, primary = true, oneTimeSubmit = false) }
        context.computeCommandBuffers = Array(newImages) { CommandBuffer(computeCommandPool, primary = true, oneTimeSubmit = false) }
        context.fences = Array(newImages) { Fence(device, signaled = true) }
        context.computeFences = Array(newImages) { Fence(device, signaled = true) }
        context.computeFinishedSemaphores = Array(newImages) { Semaphore(device) }
        context.numImages = newImages
        context.currentImage = 0

        context.profilerState?.profiler?.destroy()
        context.profilerState = if (Options.profiling) createProfilerState(newImages) else null

        nameContextObjects(context, windowContexts.indexOf(context))
    }

    var running = false

    fun runForever(update: () -> Unit) {
        running = true
        var initialTime = System.currentTimeMillis()
        var frame = 0
        while (running && windows.any { !it.shouldClose() }) {
            print(".")
            windows.forEach { it.pollEvents() }
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
        var frame = 0
        while (running && windows.any { !it.shouldClose() }) {
            logTime("pollEvents") { windows.forEach { it.pollEvents() } }
            logTime("render") { render() }
            frame++
            if (frame % 100 == 0) {
                print(" fps=${1000.0 * 100 / (System.currentTimeMillis() - initialTime)} ")
                initialTime = System.currentTimeMillis()
            }
            logTime_("compute") { yield() }
            if (coroutineContext[Job]!!.isCancelled) break
        }
    }

    @JvmName("plusAssign_modulePointer")
    operator fun plusAssign(a: Pair<String, ModulePointer>) { renderList += a }

    @JvmName("plusAssign_modulePointer_defaultName")
    operator fun plusAssign(a: ModulePointer) { renderList += a }

    @JvmName("plusAssign_meshModulePointer")
    operator fun plusAssign(a: Pair<String, MeshModulePointer>) { renderList += a }

    @JvmName("plusAssign_modulePointer_defaultName")
    operator fun plusAssign(a: MeshModulePointer) { renderList += a }

    operator fun plusAssign(a: Named<ModulePointer>) { this += a.name to a.value }

    @JvmName("plusAssign_computeModulePointer")
    operator fun plusAssign(a: Pair<String, ComputeModulePointer>) { computeList += a }

    fun getProfiler(): GpuProfiler? = defaultContext.profilerState?.profiler

    private fun resolveProfilingForImage(context: WindowContext, imageIndex: Int) {
        val ps = context.profilerState ?: return
        val p = ps.profiler
        val m = ps.markers.getOrNull(imageIndex) ?: return

        if (m.perfFrameIndex >= 0 && p.opts.perfCounters) {
            val meta = p.perfMeta
            val vals = p.fetchPerfCounters(imageIndex, m.perfFrameIndex, meta.size)
            val n = kotlin.math.min(vals.size, meta.size)
            if (n > 0) {
                val byScope = meta.withIndex().groupBy { it.value.scope }
                for ((scope, items) in byScope) {
                    println("PERF scope=$scope")
                    for (e in items) {
                        val i = e.index
                        println("  ${e.value.name}: ${p.formatPerfValue(vals[i], e.value.unit)}")
                    }
                }
            }
        }

        if (m.tsFrameBegin >= 0 && m.tsFrameEnd >= 0) {
            val ts = p.fetchTimestampNsRange(imageIndex, m.tsFrameBegin, m.tsFrameEnd)
            if (ts.size >= 4) {
                val frameNs = ts[3] - ts[0]
                val computeNs = ts[1] - ts[0]
                val graphicsNs = ts[2] - ts[1]
                val postNs = ts[3] - ts[2]
                println("GPU frame=${frameNs/1e6} ms (compute=${computeNs/1e6} ms, graphics=${graphicsNs/1e6} ms, post=${postNs/1e6} ms)")
            }
        }

        if (m.pipeStatsCompute >= 0 && p.opts.pipelineStats) {
            val ps = p.fetchPipelineStats(imageIndex, m.pipeStatsCompute)
            println("CS invocations: ${ps.getOrNull(10) ?: -1}")
        }
        if (m.pipeStatsGraphics >= 0 && p.opts.pipelineStats) {
            val ps = p.fetchPipelineStats(imageIndex, m.pipeStatsGraphics)
            val iaVerts = ps.getOrNull(0) ?: -1
            val iaPrims = ps.getOrNull(1) ?: -1
            val vsInv   = ps.getOrNull(2) ?: -1
            val fsInv   = ps.getOrNull(7) ?: -1
            println("GFX IA verts=$iaVerts, IA prims=$iaPrims, VS invoc=$vsInv, FS invoc=$fsInv")
        }

        val pm = ps.perImageModuleMarks.getOrNull(imageIndex) ?: mutableListOf()
        if (pm.isNotEmpty()) {
            for (mm in pm) {
                if (mm.tsBegin >= 0 && mm.tsEnd >= 0) {
                    val ts = p.fetchTimestampNsRange(imageIndex, mm.tsBegin, mm.tsEnd)
                    if (ts.size >= 2) {
                        val dtMs = (ts.last() - ts.first()) / 1e6
                        println("MOD '${mm.name}': ${"%.3f".format(dtMs)} ms")
                    }
                }
                if (mm.pipeStatsIndex >= 0 && p.opts.pipelineStats) {
                    val ps = p.fetchPipelineStats(imageIndex, mm.pipeStatsIndex)
                    val fsInv = ps.getOrNull(7) ?: -1
                    println("MOD '${mm.name}': FS invocations=$fsInv")
                }
                if (mm.perfIndex >= 0 && p.opts.perfCounters) {
                    val meta = p.perfMeta
                    val vals = p.fetchPerfCounters(imageIndex, mm.perfIndex, meta.size)
                    val n = kotlin.math.min(vals.size, meta.size)
                    if (n > 0) {
                        println("MOD '${mm.name}' PERF:")
                        for (i in 0 until n) {
                            val mi = meta[i]
                            println("  ${mi.name}: ${p.formatPerfValue(vals[i], mi.unit)}")
                        }
                    }
                }
            }
        }
    }
}
