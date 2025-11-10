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

interface Target { fun render(command: CommandBuffer, index: Int) }

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

class Renderer(val window: Window) {
    val commandPool: CommandPool
    val computeCommandPool: CommandPool
    val device: Device
    val graphQueue: GraphicsQueue
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

    var numImages = 0
    var currentImage = 0

    var commandBuffers: Array<CommandBuffer>
    var computeCommandBuffers: Array<CommandBuffer>
    var fences: Array<Fence>
    var computeFences: Array<Fence>

    var cleanup = mutableListOf<Runnable>()
    var cleanupStack = mutableListOf<MutableList<Runnable>>()

    var referenceQueue: ReferenceQueue<VulkanBuffer> = ReferenceQueue()

    var renderStart = MapOfSequences<String>()
    var renderEnd = MapOfSequences<String>()
    var currentCommandBuffer: CommandBuffer? = null

    // Profiling
    private var profiler: GpuProfiler? = null
    fun getProfiler(): GpuProfiler? = profiler

    private data class FrameMarkers(
        var tsFrameBegin: Int = -1,
        var tsAfterCompute: Int = -1,
        var tsAfterGraphics: Int = -1,
        var tsFrameEnd: Int = -1,
        var pipeStatsCompute: Int = -1,
        var pipeStatsGraphics: Int = -1,
        var perfFrameIndex: Int = -1
    )
    private lateinit var markers: Array<FrameMarkers>

    // Deferred readback
    private data class Pending(val imageIndex: Int)
    private val pending = ArrayDeque<Pending>()
    private companion object { const val READBACK_LAG = 3 }

    // Per-module instrumentation
    data class ModuleMarks(
        val name: String,
        var tsBegin: Int = -1,
        var tsEnd: Int = -1,
        var pipeStatsIndex: Int = -1,
        var perfIndex: Int = -1
    )
    private lateinit var perImageModuleMarks: Array<MutableList<ModuleMarks>>
    fun registerModuleMarks(imageIndex: Int, marks: ModuleMarks) { perImageModuleMarks[imageIndex].add(marks) }

    init {
        instance = Instance(Options.validate)
        physicalDevice = PhysicalDevice.createPhysicalDevice(instance, Options.preferredDevice)
        device = Device(physicalDevice)
        instance.dumpToolingInfo(instance.vkInstance, physicalDevice.vkPhysicalDevice)
        surface = Surface(physicalDevice, window.windowHandle)
        graphQueue = GraphicsQueue(device, 0)
        presentQueue = PresentQueue(device, surface, 0)
        swapChain = SwapChain(device, surface, window, Options.requestedImages, Options.vsync, presentQueue, arrayOf(graphQueue))
        commandPool = CommandPool(device, graphQueue.queueFamilyIndex)
        computeCommandPool = CommandPool(device, graphQueue.queueFamilyIndex)
        pipelineCache = PipelineCache(device)

        numImages = swapChain.imageViews.size
        computeFinishedSemaphores = Array(numImages) { Semaphore(device) }

        commandBuffers = Array(numImages) { CommandBuffer(commandPool, primary = true, oneTimeSubmit = false) }
        computeCommandBuffers = Array(numImages) { CommandBuffer(computeCommandPool, primary = true, oneTimeSubmit = false) }

        fences = Array(numImages) { Fence(device, signaled = true) }
        computeFences = Array(numImages) { Fence(device, signaled = true) }

        createDepthImages()

        if (Options.profiling) {
            val opts = ProfilingOptions(
                enabled = true,
                timestamps = true,
                pipelineStats = Options.isWindows,
                perfCounters = Options.isWindows
            )
            profiler = GpuProfiler(
                device = device.vkDevice,
                physicalDevice = device.physicalDevice.vkPhysicalDevice,
                queueFamilyIndex = graphQueue.queueFamilyIndex,
                framesInFlight = numImages,
                opts = opts
            )
            markers = Array(numImages) { FrameMarkers() }
            perImageModuleMarks = Array(numImages) { mutableListOf() }
        }
    }

    fun cleanup() {
        presentQueue.waitIdle()
        graphQueue.waitIdle()
        device.waitIdle()
        profiler?.destroy()
        pipelineCache.cleanup()
        commandPool.cleanup()
        swapChain.cleanup()
        surface.cleanup()
        device.cleanup()
        physicalDevice.cleanup()
        instance.cleanup()
    }

    var frameCounter = 0L

    fun render() {
        if (window.width <= 0 && window.height <= 0) return

        // Drain ready frames (no stalls).
        if (Options.profiling) {
            while (pending.isNotEmpty()) {
                val head = pending.first()
                val enoughLag = pending.size > READBACK_LAG
                val signaled = fences[head.imageIndex].isSignaled()
                if (!enoughLag && !signaled) break
                if (signaled) {
                    resolveProfilingForImage(head.imageIndex)
                    pending.removeFirst()
                } else break
            }
        }

        var imageIndex = 0

        // Keeps swapchain pipeline moving; left as-is from original code.
        fences[numImages % fences.size].fenceWait()

        try {
            CPUSpan.time("acquire") {
                if (window.isResized || (swapChain.acquireNextImage().also { imageIndex = it }) < 0) {
                    window.resetResized()
                    resize(window)
                    imageIndex = swapChain.acquireNextImage()
                }
            }

            currentImage = imageIndex
            profiler?.let {
                it.beginFrame(imageIndex)
                if (::perImageModuleMarks.isInitialized) perImageModuleMarks[imageIndex].clear()
            }

            // Compute pass
            var csScope: GpuProfiler.PipeStatScope? = null
            var pcScope: GpuProfiler.PerfScope? = null
            CPUSpan.time("record_cs") {
                computeFences[imageIndex].fenceWait()
                computeFences[imageIndex].reset()

                val ccmd = computeCommandBuffers[imageIndex]
                ccmd.reset(); ccmd.beginRecording()

                profiler?.let { p ->
                    markers[imageIndex].tsFrameBegin = p.writeTimestamp(
                        ccmd.vkCommandBuffer, imageIndex, VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT
                    )
                    csScope = p.beginPipelineStats(ccmd.vkCommandBuffer, imageIndex)
                    csScope?.let { markers[imageIndex].pipeStatsCompute = it.index }
                    pcScope = p.beginPerfCounters(ccmd.vkCommandBuffer, imageIndex)
                }

                computeList.render(ccmd, imageIndex)

                profiler?.let { p ->
                    p.endPerfCounters(ccmd.vkCommandBuffer, imageIndex, pcScope)
                    p.endPipelineStats(ccmd.vkCommandBuffer, csScope)
                    markers[imageIndex].tsAfterCompute = p.writeTimestamp(
                        ccmd.vkCommandBuffer, imageIndex, VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT
                    )
                }
                ccmd.endRecording()
            }

            CPUSpan.time("submit_cs") {
                submitCompute(
                    computeCommandBuffers[imageIndex],
                    computeFences[imageIndex],
                    computeFinishedSemaphores[imageIndex],
                    graphQueue
                )
            }

            // Graphics pass
            CPUSpan.time("record_gfx") {
                val cmd = commandBuffers[imageIndex]
                cmd.reset(); cmd.beginRecording()

                currentCommandBuffer = cmd
                renderStart.update()

                var gsScope: GpuProfiler.PipeStatScope? = null
                var perfFrameScope: GpuProfiler.PerfScope? = null

                profiler?.let { p ->
                    gsScope = p.beginPipelineStats(cmd.vkCommandBuffer, imageIndex)
                    gsScope?.let { markers[imageIndex].pipeStatsGraphics = it.index }
                    perfFrameScope = p.beginPerfCounters(cmd.vkCommandBuffer, imageIndex)
                }

                renderList.render(cmd, imageIndex)

                profiler?.let { p ->
                    p.endPerfCounters(cmd.vkCommandBuffer, imageIndex, perfFrameScope)
                    perfFrameScope?.let { markers[imageIndex].perfFrameIndex = it.index }
                    p.endPipelineStats(cmd.vkCommandBuffer, gsScope)
                    markers[imageIndex].tsAfterGraphics = p.writeTimestamp(
                        cmd.vkCommandBuffer, imageIndex, VK10.VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT
                    )
                    markers[imageIndex].tsFrameEnd = p.writeTimestamp(
                        cmd.vkCommandBuffer, imageIndex, VK10.VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT
                    )
                }

                cmd.endRecording()
            }

            CPUSpan.time("submit_gfx") {
                submit(
                    commandBuffers[imageIndex],
                    fences[imageIndex],
                    graphQueue,
                    computeFinishedSemaphores[imageIndex]
                )
            }

            CPUSpan.time("present") {
                if (swapChain.presentImage(presentQueue, imageIndex)) window.isResized = true
            }

            // Deferred cleanup queue
            cleanupStack.add(cleanup)
            cleanup = mutableListOf()
            if (cleanupStack.size >= numImages * 8) {
                val toRun = cleanupStack.removeAt(0)
                if (toRun.isNotEmpty()) {
                    val copy = ArrayList(toRun)
                    toRun.clear()
                    copy.forEach { it.run() }
                }
            }

            renderEnd.update()
            CPUSpan.snapshotAndMaybePrint(everyNFrames = 120, frame = frameCounter)

            if (Options.profiling) pending.addLast(Pending(imageIndex))

            currentImage = (imageIndex + 1) % numImages
            frameCounter++
            currentCommandBuffer = null

        } catch (e: ImageNotAvailable) {
            println(e.message)
        }
    }

    class ImageNotAvailable(s: String) : RuntimeException(s)

    fun submit(
        commandBuffer: CommandBuffer,
        currentFence: Fence,
        queue: Queue,
        computeSemaphore: Semaphore
    ) {
        MemoryStack.stackPush().use { stack ->
            val idx = swapChain.currentFrame
            currentFence.reset()
            val sync = swapChain.syncSemaphoresList[idx]
            queue.submit(
                stack.pointers(commandBuffer.vkCommandBuffer),
                stack.longs(sync.imgAcquisitionSemaphore.vkSemaphore, computeSemaphore.vkSemaphore),
                stack.ints(VK10.VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT),
                stack.longs(sync.renderCompleteSemaphore.vkSemaphore),
                currentFence
            )
        }
    }

    fun submitCompute(
        commandBuffer: CommandBuffer,
        currentComputeFence: Fence,
        signalSemaphore: Semaphore,
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
    }

    private fun createDepthImages() {
        val count = swapChain.numImages
        val extent = swapChain.swapChainExtent
        depthAttachments = MutableList(count) {
            Attachment(
                device,
                extent.width(),
                extent.height(),
                Options.defaultDepthFormat,
                VK10.VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT
            )
        }
    }

    private fun resize(window: Window) {
        device.waitIdle()
        graphQueue.waitIdle()

        swapChain.cleanup()
        swapChain = SwapChain(
            device, surface, window, Options.requestedImages, Options.vsync, presentQueue, arrayOf(graphQueue)
        )

        depthAttachments.forEach { it.cleanup() }
        createDepthImages()

        if (Options.profiling) {
            profiler?.destroy()
            val newImages = swapChain.imageViews.size
            val opts = ProfilingOptions(
                enabled = true,
                timestamps = true,
                pipelineStats = Options.isWindows,
                perfCounters = Options.isWindows
            )
            profiler = GpuProfiler(
                device = device.vkDevice,
                physicalDevice = device.physicalDevice.vkPhysicalDevice,
                queueFamilyIndex = graphQueue.queueFamilyIndex,
                framesInFlight = newImages,
                opts = opts
            )
            markers = Array(newImages) { FrameMarkers() }
            perImageModuleMarks = Array(newImages) { mutableListOf() }
            numImages = newImages
        }
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
        var frame = 0
        while (running && !window.shouldClose()) {
            logTime("pollEvents") { window.pollEvents() }
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

    private fun resolveProfilingForImage(imageIndex: Int) {
        val p = profiler ?: return
        val m = markers.getOrNull(imageIndex) ?: return

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

        val pm = if (::perImageModuleMarks.isInitialized) perImageModuleMarks[imageIndex] else mutableListOf()
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
