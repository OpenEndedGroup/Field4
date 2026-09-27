package afield.four

import afield.Named
import afield.Rect
import afield.Runloop
import afield.Runloop.Companion.isMainThread
import afield.four.buffers.ParallelGeometryPool
import afield.four.buffers.cleanupAllocatorForRenderer
import afield.four.device.*
import afield.four.device.Queue
import afield.four.device.Queue.GraphicsQueue
import afield.four.device.Queue.PresentQueue
import afield.four.texture.image_layout_transition
import afield.four.modules.ComputeModulePointer
import afield.four.modules.MeshModulePointer
import afield.four.modules.ModulePointer
import afield.four.modules.RayTracingModulePointer
import afield.four.targets.ComputeTarget
import afield.four.targets.RenderTarget
import afield.four.util.AutoDisposer
import afield.four.util.MapOfSequences
import afield.four.util.Options
import afield.four.util.Window
import afield.four.util.drainDeferredCleanup
import afield.util.PngEncoder
import afield.logTime
import afield.logTime_
import afield.logger.SaveReloadLog
import afield.logger.lprintln
import kotlinx.coroutines.*
import afield.stdlib.linalg.Rectanglef
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.*
import org.lwjgl.vulkan.VK13.*
import java.lang.Runnable
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

fun interface Target { fun render(command: CommandBuffer, index: Int) }

fun target(a: (CommandBuffer, Int) -> Unit) = object : Target {
    override fun render(command: CommandBuffer, index: Int) = a(command, index)
}

private const val PERF_LOG_INTERVAL_NS = 5_000_000_000L

/** Lightweight CPU span stats. */
object CPUSpan {
    private const val WINDOW = 500
    private var lastSnapshotLogNs = 0L

    data class Stat(val samples: ArrayDeque<Long> = ArrayDeque()) {
        fun add(dtNs: Long) {
            samples.addLast(dtNs)
            if (samples.size > WINDOW) samples.removeFirst()
        }
    }
    data class Snapshot(
        val label: String,
        val n: Int,
        val avgMs: Double,
        val minMs: Double,
        val maxMs: Double,
        val p1Ms: Double,
        val p10Ms: Double,
        val p50Ms: Double,
        val p90Ms: Double,
        val p99Ms: Double
    )
    val stats = linkedMapOf<String, Stat>()
    val lock = Any()
    val version = AtomicLong(0)
    val listeners = mutableListOf<(Long) -> Unit>()
    inline fun time(label: String, block: () -> Unit) {
        val t0 = System.nanoTime()
        block()
        val dt = System.nanoTime() - t0
        val v = synchronized(lock) {
            stats.getOrPut(label) { Stat() }.add(dt)
            version.incrementAndGet()
        }
        notifyListeners(v)
    }

    fun notifyListeners(v: Long) {
        val targets = synchronized(lock) { listeners.toList() }
        targets.forEach { l -> runCatching { l(v) } }
    }

    fun addListener(listener: (Long) -> Unit) {
        synchronized(lock) { listeners.add(listener) }
    }

    fun snapshot(): List<Snapshot> {
        val copy: Map<String, List<Long>> = synchronized(lock) {
            stats.mapValues { it.value.samples.toList() }
        }
        return copy.map { (label, samples) -> buildSnapshot(label, samples) }
    }

    private fun buildSnapshot(label: String, samples: List<Long>): Snapshot {
        if (samples.isEmpty()) return Snapshot(label, 0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        val sorted = samples.sorted()
        fun perc(frac: Double): Double {
            val idx = ((sorted.size - 1) * frac).toInt().coerceIn(0, sorted.size - 1)
            return sorted[idx] / 1e6
        }
        val avgMs = sorted.average() / 1e6
        return Snapshot(
            label = label,
            n = sorted.size,
            avgMs = avgMs,
            minMs = sorted.first() / 1e6,
            maxMs = sorted.last() / 1e6,
            p1Ms = perc(0.01),
            p10Ms = perc(0.10),
            p50Ms = perc(0.50),
            p90Ms = perc(0.90),
            p99Ms = perc(0.99)
        )
    }

    fun snapshotAndMaybePrint(everyNFrames: Int, frame: Long, header: String = "CPU") {
        val now = System.nanoTime()
        if (lastSnapshotLogNs != 0L && now - lastSnapshotLogNs < PERF_LOG_INTERVAL_NS) return
        lastSnapshotLogNs = now
        val snaps = snapshot()
        if (snaps.isEmpty()) return
        val line = buildString {
            append("$header: ")
            append(snaps.joinToString("  ") { s ->
                "${s.label}=avg ${"%.3f".format(s.avgMs)}ms p50 ${"%.3f".format(s.p50Ms)}ms p90 ${"%.3f".format(s.p90Ms)}ms"
            })
        }
        lprintln("info,perf", line)
    }

    fun version(): Long = version.get()
}

class Renderer(
    private val mainWindow: Window,
    additionalWindows: List<Window> = emptyList()
) {

    data class VertexUploadFrameStats(
        val frameId: Long,
        val recordedBytes: Long,
        val immediateBytes: Long
    ) {
        val totalBytes: Long get() = recordedBytes + immediateBytes
    }

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
        var profilerState: ProfilerState? = null,
        var computeSecondary: MutableMap<String, Array<CommandBuffer>> = mutableMapOf(),
        var graphicsSecondary: MutableMap<String, Array<CommandBuffer>> = mutableMapOf(),
        var computePoolsPerThread: MutableMap<Long, CommandPool> = mutableMapOf(),
        var graphicsPoolsPerThread: MutableMap<Long, CommandPool> = mutableMapOf()
    )

    private data class RecordedWindowCapture(
        val request: WindowCaptureRequest,
        val stagingBuffer: afield.four.buffers.VulkanBuffer,
        val width: Int,
        val height: Int,
        val format: Int
    )

    private data class WindowCapturePixels(
        val request: WindowCaptureRequest,
        val rgba: ByteArray,
        val width: Int,
        val height: Int
    )

    data class ProfilerState(
        val profiler: GpuProfiler,
        var markers: Array<FrameMarkers>,
        var perImageModuleMarks: Array<MutableList<ModuleMarks>>,
        val pending: ArrayDeque<Pending> = ArrayDeque(),
        val lock: Any = Any()
    )

    val windows: List<Window> = listOf(mainWindow) + additionalWindows
    val window: Window get() = windows.first()

    private val uploadStatsLock = Any()
    private var currentUploadRecordedBytes = 0L
    private var currentUploadImmediateBytes = 0L
    @Volatile
    var lastVertexUploadFrameStats: VertexUploadFrameStats = VertexUploadFrameStats(
        frameId = -1L,
        recordedBytes = 0L,
        immediateBytes = 0L
    )

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

    // Dedicated single-thread for all vkQueue* and xrBeginFrame/xrEndFrame calls.
    private val queueThreadRef = java.util.concurrent.atomic.AtomicReference<Thread?>()
    private val queueExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "vk-queue-thread").apply {
            isDaemon = true
            queueThreadRef.set(this)
        }
    }
    private val queueDispatcher = queueExecutor.asCoroutineDispatcher()

    // Dedicated workers for renderer-controlled parallel recording phases only.
    private val workerExecutor = Executors.newFixedThreadPool(
        Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
    ) { r ->
        Thread(r, "renderer-worker").apply { isDaemon = true }
    }
    private val workerDispatcher = workerExecutor.asCoroutineDispatcher()
    private val workerParallelDepth = AtomicInteger(0)

    private val windowContexts = mutableListOf<WindowContext>()
    private val defaultContext get() = windowContexts.first()
    fun windowContext(index: Int = 0): WindowContext = windowContexts[index]
    fun renderTargetFor(windowIndex: Int = 0): RenderTarget = windowContext(windowIndex).renderTarget
    fun windowIndex(window: Window): Int {
        val index = windows.indexOf(window)
        require(index >= 0) { "Window is not owned by this renderer" }
        return index
    }
    fun renderTargetFor(window: Window): RenderTarget = renderTargetFor(windowIndex(window))
    private val windowCaptures = WindowCaptureQueue()
    private val graphicsCompletionLock = Any()
    private val graphicsRecordingContext = ThreadLocal<Pair<Int, Int>?>()
    private val graphicsCompletionCallbacks = mutableMapOf<Pair<Int, Int>, MutableList<() -> Unit>>()
    private val computeSubmissionLock = Any()
    private val computeRecordingContext = ThreadLocal<Pair<Int, Int>?>()
    private val computeSubmissionCallbacks = mutableMapOf<Pair<Int, Int>, MutableList<(Fence) -> Future<*>?>>()
    private val computePostSubmitTasks = mutableMapOf<Pair<Int, Int>, MutableList<Future<*>>>()

    /**
     * Releases to run once the frames now in flight have retired (see [render]). Synchronized:
     * box threads add to it (`VulkanBuffer.realloc`, staged uploads, `renderer.cleanup += …`)
     * while the render thread swaps it out.
     */
    var cleanup: MutableList<Runnable> = java.util.Collections.synchronizedList(mutableListOf())
    var cleanupStack = mutableListOf<Pair<Long, MutableList<Runnable>>>()
    @Volatile
    private var cleanedUp = false
    /**
     * True after cleanup begins; between-frame producers must stop before using this device again.
     * Volatile because the flag is read by [afield.four.buffers.VulkanBuffer.map] on whichever
     * thread writes a buffer, to refuse a write into memory this cleanup has freed.
     */
    val isClosed: Boolean get() = cleanedUp

    internal fun afterCurrentGraphicsFrameComplete(callback: () -> Unit) {
        val key = graphicsRecordingContext.get()
            ?: throw IllegalStateException("afterCurrentGraphicsFrameComplete must be called while recording a graphics frame")
        synchronized(graphicsCompletionLock) {
            graphicsCompletionCallbacks.getOrPut(key) { mutableListOf() }.add(callback)
        }
    }

    internal fun afterCurrentComputeFrameSubmitted(callback: (Fence) -> Future<*>?) {
        val key = computeRecordingContext.get()
            ?: throw IllegalStateException("afterCurrentComputeFrameSubmitted must be called while recording a compute frame")
        synchronized(computeSubmissionLock) {
            computeSubmissionCallbacks.getOrPut(key) { mutableListOf() }.add(callback)
        }
    }

    private fun drainComputeSubmissionCallbacks(ctxIndex: Int, imageIndex: Int, fence: Fence) {
        val key = ctxIndex to imageIndex
        val callbacks = synchronized(computeSubmissionLock) {
            computeSubmissionCallbacks.remove(key)?.toList() ?: emptyList()
        }
        val tasks = mutableListOf<Future<*>>()
        callbacks.forEach { callback ->
            runCatching { callback(fence) }.onSuccess { task ->
                if (task != null) tasks += task
            }.onFailure {
                lprintln("warning,graphics", "compute submission callback failed: ${it.message ?: it::class.simpleName}")
            }
        }
        if (tasks.isNotEmpty()) {
            synchronized(computeSubmissionLock) {
                computePostSubmitTasks.getOrPut(key) { mutableListOf() }.addAll(tasks)
            }
        }
    }

    private fun awaitComputePostSubmitTasks(ctxIndex: Int, imageIndex: Int) {
        val tasks = synchronized(computeSubmissionLock) {
            computePostSubmitTasks.remove(ctxIndex to imageIndex)?.toList() ?: emptyList()
        }
        tasks.forEach { it.get() }
    }

    private fun drainCompletedGraphicsCallbacks(
        ctx: WindowContext,
        ctxIndex: Int,
        imageIndex: Int,
        requireSignaled: Boolean = false
    ) {
        if (requireSignaled) {
            ctx.fences[imageIndex].fenceWait()
        } else if (!ctx.fences[imageIndex].isSignaled()) {
            return
        }
        val key = ctxIndex to imageIndex
        val callbacks = synchronized(graphicsCompletionLock) {
            graphicsCompletionCallbacks.remove(key)?.toList() ?: emptyList()
        }
        callbacks.forEach { callback ->
            runCatching { callback() }.onFailure {
                lprintln("warning,graphics", "graphics completion callback failed: ${it.message ?: it::class.simpleName}")
            }
        }
    }

    // Vulkan queues require external synchronization. Use this lock to serialize all queue operations
    // (submit/present) across any threads that might enter the renderer.
    // Use the same lock as Queue to serialize all vkQueue* calls across the app.
    private val queueLock = Queue.globalQueueLock

    /** Run a block on the dedicated queue thread; if already on it, run inline. */
    fun <T> runOnQueueThread(block: () -> T): T {
        val qt = queueThreadRef.get()
        return if (Thread.currentThread() === qt) {
            block()
        } else {
            try { queueExecutor.submit<T> { block() }.get() }
            catch (e: java.util.concurrent.ExecutionException) { throw e.cause ?: e }
        }
    }

    /** Suspend-friendly variant to hop to the queue thread. */
    suspend fun <T> onQueueThread(block: () -> T): T {
        val qt = queueThreadRef.get()
        return if (Thread.currentThread() === qt) block() else kotlinx.coroutines.withContext(queueDispatcher) { block() }
    }

    var renderStart = MapOfSequences<String>()
    var renderEnd = MapOfSequences<String>()
    var currentCommandBuffer: CommandBuffer? = null

    private val workerParallelismActive: Boolean
        get() = workerParallelDepth.get() > 0

    private inline fun <T> withWorkerParallelism(block: () -> T): T {
        workerParallelDepth.incrementAndGet()
        try {
            return block()
        } finally {
            workerParallelDepth.decrementAndGet()
        }
    }

    private fun <T> CoroutineScope.asyncWorker(block: suspend CoroutineScope.() -> T): Deferred<T> {
        check(workerParallelismActive) {
            "Renderer worker parallelism is not active outside render() parallel sections"
        }
        return async(workerDispatcher, block = block)
    }

    data class GeometryFrameToken(
        val frameId: Long,
        val imageIndex: Int,
        val windowIndex: Int
    )

    inner class GeometryPhaseScope(val token: GeometryFrameToken, private val parallelAllowed: Boolean) {
        private val scope = if (parallelAllowed) CoroutineScope(SupervisorJob() + workerDispatcher) else null
        private val jobs = Collections.synchronizedList(mutableListOf<Job>())

        fun submit(block: () -> Unit): Job {
            val job: Job = if (parallelAllowed) {
                scope!!.launch { block() }
            } else {
                val completed = CompletableDeferred<Unit>()
                runCatching { block() }
                    .onSuccess { completed.complete(Unit) }
                    .onFailure { completed.completeExceptionally(it) }
                completed
            }
            jobs += job
            return job
        }

        fun <T> submitValue(block: () -> T): Deferred<T> {
            val deferred: Deferred<T> = if (parallelAllowed) {
                scope!!.async { block() }
            } else {
                val completed = CompletableDeferred<T>()
                runCatching { block() }
                    .onSuccess { completed.complete(it) }
                    .onFailure { completed.completeExceptionally(it) }
                completed
            }
            jobs += deferred
            return deferred
        }

        internal fun awaitCompletion() {
            runBlocking {
                jobs.toList().joinAll()
            }
        }

        internal fun close() {
            scope?.cancel()
        }
    }

    val geometryProducers = linkedMapOf<String, (GeometryPhaseScope) -> Unit>()
    private val geometryPoolsLock = Any()
    private val geometryPools = LinkedHashSet<ParallelGeometryPool>()
    private val geometryPhaseStateLock = Any()

    private data class ExternalGeometryPhase(val token: GeometryFrameToken)
    private data class PreAcquiredImage(val frameId: Long, val imageIndex: Int)

    @Volatile
    private var openGeometryPhase: ExternalGeometryPhase? = null

    @Volatile
    private var preAcquiredPrimaryImage: PreAcquiredImage? = null

    @Volatile
    private var runloopGeometryHookKey: String? = null

    internal fun registerParallelGeometryPool(pool: ParallelGeometryPool) {
        synchronized(geometryPoolsLock) {
            geometryPools += pool
        }
    }

    internal fun unregisterParallelGeometryPool(pool: ParallelGeometryPool) {
        synchronized(geometryPoolsLock) {
            geometryPools.remove(pool)
        }
    }

    private fun geometryPoolsSnapshot(): List<ParallelGeometryPool> {
        return synchronized(geometryPoolsLock) { geometryPools.toList() }
    }

    private fun beginGeometryPools(token: GeometryFrameToken) {
        val pools = geometryPoolsSnapshot()
        pools.forEach { it.beginFrame(token) }
    }

    private fun sealGeometryPools(token: GeometryFrameToken) {
        val pools = geometryPoolsSnapshot()
        pools.forEach { it.sealFrame(token) }
    }

    private fun runGeometryProducers(token: GeometryFrameToken, parallelAllowed: Boolean) {
        val scope = GeometryPhaseScope(token, parallelAllowed)
        try {
            geometryProducers.forEach { (_, producer) ->
                producer(scope)
            }
            scope.awaitCompletion()
        } finally {
            scope.close()
        }
    }

    private fun runGeometryPhase(token: GeometryFrameToken) {
        beginGeometryPools(token)
        try {
            withWorkerParallelism {
                runGeometryProducers(token, parallelAllowed = true)
            }
        } finally {
            sealGeometryPools(token)
        }
    }

    private fun acquireFrameImage(windowIndex: Int): Int {
        val ctx = windowContexts[windowIndex]
        val w = ctx.window

        // Keeps swapchain pipeline moving; left as-is from the main render path.
        ctx.fences[ctx.swapChain.currentFrame].fenceWait()

        var imageIndex = 0
        CPUSpan.time("acquire[$windowIndex]") {
            imageIndex = runOnQueueThread {
                var idx = ctx.swapChain.acquireNextImage()
                if (w.isResized || idx < 0) {
                    w.resetResized()
                    resize(ctx)
                    idx = ctx.swapChain.acquireNextImage()
                }
                idx
            }
        }

        // Geometry/uniform writes are indexed by the acquired swapchain image. Make sure that
        // image slot is no longer in-flight on the GPU before any CPU-side writes touch its
        // per-image buffers.
        ctx.fences[imageIndex].fenceWait()
        drainCompletedGraphicsCallbacks(ctx, windowIndex, imageIndex, requireSignaled = true)

        unavailableWindows.remove(windowIndex)?.let { since ->
            if(System.nanoTime()-since>250_000_000L)presentationRevision++
        }
        ctx.currentImage = imageIndex
        if (windowIndex == 0) currentImage = imageIndex
        return imageIndex
    }

    /**
     * Opens a geometry-write window for the current frame and returns the token used by pools.
     *
     * Use this to run geometry generation from anywhere in the Runloop before [render], then either:
     * - call [endGeometryWrites], or
     * - let [render] auto-close it at frame start.
     */
    fun beginGeometryWrites(windowIndex: Int = 0): GeometryFrameToken {
        require(windowIndex == 0) {
            "beginGeometryWrites currently supports windowIndex=0 only"
        }

        synchronized(geometryPhaseStateLock) {
            val frameId = frameCounter
            val existing = openGeometryPhase
            if (existing != null) {
                if (existing.token.frameId == frameId && existing.token.windowIndex == windowIndex) {
                    return existing.token
                }
                throw IllegalStateException(
                    "Geometry writes already open for frame ${existing.token.frameId}, window ${existing.token.windowIndex}"
                )
            }
            if (preAcquiredPrimaryImage?.frameId == frameId) {
                throw IllegalStateException("Geometry writes already prepared for frame $frameId")
            }

            val imageIndex = acquireFrameImage(windowIndex)
            val token = GeometryFrameToken(
                frameId = frameId,
                imageIndex = imageIndex,
                windowIndex = windowIndex
            )

            beginGeometryPools(token)
            openGeometryPhase = ExternalGeometryPhase(token)
            if (windowIndex == 0) {
                preAcquiredPrimaryImage = PreAcquiredImage(frameId = frameId, imageIndex = imageIndex)
            }
            return token
        }
    }

    /**
     * Explicitly closes a previously opened geometry-write window.
     *
     * If not called, [render] closes any still-open window for the current frame automatically.
     */
    fun endGeometryWrites(windowIndex: Int = 0) {
        val token = synchronized(geometryPhaseStateLock) {
            val phase = openGeometryPhase ?: return
            require(phase.token.windowIndex == windowIndex) {
                "Geometry writes open for window ${phase.token.windowIndex}, not window $windowIndex"
            }
            openGeometryPhase = null
            phase.token
        }
        try {
            runGeometryProducers(token, parallelAllowed = false)
        } finally {
            sealGeometryPools(token)
        }
    }

    private fun takeOpenGeometryPhaseTokenForFrame(frameId: Long): GeometryFrameToken? {
        synchronized(geometryPhaseStateLock) {
            val phase = openGeometryPhase ?: return null
            if (phase.token.frameId != frameId) return null
            openGeometryPhase = null
            return phase.token
        }
    }

    private fun peekPreAcquiredPrimaryImageForFrame(frameId: Long): Int? {
        return synchronized(geometryPhaseStateLock) {
            preAcquiredPrimaryImage?.takeIf { it.frameId == frameId }?.imageIndex
        }
    }

    private fun clearPreAcquiredPrimaryImageForFrame(frameId: Long) {
        synchronized(geometryPhaseStateLock) {
            val pending = preAcquiredPrimaryImage ?: return
            if (pending.frameId == frameId) {
                preAcquiredPrimaryImage = null
            }
        }
    }

    /**
     * Installs a Runloop pre-frame hook that opens geometry writes every runloop tick.
     *
     * This is useful when geometry workers are launched from arbitrary runloop code before render.
     */
    fun installRunloopGeometryBeginHook(
        runloop: Runloop = Runloop.runLoop,
        key: String = "renderer:${System.identityHashCode(this)}:beginGeometryWrites"
    ) {
        runloop.preRun[key] = sequence {
            while (true) {
                try {
                    beginGeometryWrites()
                } catch (_: ImageNotAvailable) {
                    // Skip this runloop tick; render() handles this case too.
                } catch (ise: IllegalStateException) {
                    // If a frame is already pre-acquired and sealed for this frame, skip quietly.
                    if (ise.message?.contains("already prepared for frame") != true) {
                        lprintln("warning,graphics", "runloop geometry preRun failed: ${ise.message}")
                    }
                } catch (t: Throwable) {
                    lprintln("warning,graphics", "runloop geometry preRun failed: ${t.message}")
                }
                yield(null)
            }
        }
        runloopGeometryHookKey = key
    }

    fun uninstallRunloopGeometryBeginHook(runloop: Runloop = Runloop.runLoop) {
        runloopGeometryHookKey?.let { runloop.preRun.remove(it) }
        runloopGeometryHookKey = null
    }

    /** Optional OpenXR HMD target rendered once per frame (after window presents). */
    var openXRHmdTarget: afield.four.targets.OpenXRHmdTarget? = null

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
        synchronized(ps.lock) {
            ps.perImageModuleMarks.getOrNull(imageIndex)?.add(marks)
        }
    }

    init {
        if (Options.enableVR) {
            // Prepare OpenXR instance/system and fetch graphics requirements before VkDevice creation.
            Instance.prepareOpenXRBootstrap()
        }
        instance = Instance(Options.validate)
        physicalDevice = PhysicalDevice.createPhysicalDevice(instance, Options.preferredDevice)
        device = Device(physicalDevice, instance)
        Options.chooseDefaultDepthFormat(physicalDevice.vkPhysicalDevice)
        if (Options.dumpToolingInfo) {
            instance.dumpToolingInfo(instance.vkInstance, physicalDevice.vkPhysicalDevice)
        }
        graphQueue = GraphicsQueue(device, 0)
        val primarySurface = Surface(physicalDevice, window.windowHandle)
        // Prefer a different queue index for present to avoid contending with the graphics queue (which XR/runtime may also touch).
        val gfxFamily = graphQueue.queueFamilyIndex
        val gfxQueueCount = physicalDevice.vkQueueFamilyProps[gfxFamily].queueCount()
        val presentQueueIndex = if (gfxQueueCount > 1) 1 else 0
        presentQueue = PresentQueue(device, primarySurface, presentQueueIndex)
        commandPool = CommandPool(device, graphQueue.queueFamilyIndex)
        computeCommandPool = CommandPool(device, graphQueue.queueFamilyIndex)
        pipelineCache = PipelineCache(device)
        windows.forEachIndexed { idx, w ->
            w.associatedRenderer = this
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
        if (cleanedUp) return
        cleanedUp = true
        running = false
        // Before the waitIdle below: a Queue.submit from another thread either finished before this
        // (and the waits cover it) or is refused. Renderer's own frame submits use vkQueueSubmit2
        // on this thread and render() already refuses once cleanedUp is set.
        device.closeSubmissions()
        uninstallRunloopGeometryBeginHook()

        presentQueue.waitIdle()
        graphQueue.waitIdle()
        device.waitIdle()
        // Pending releases normally wait for later frames. Shutdown has no later frame;
        // release them now, before their fences, command pools and device disappear.
        var deferredFailure = drainDeferredCleanup(cleanup, cleanupStack)
        if (Options.enableVR) {
            Instance.cleanupOpenXRBootstrap()
        }

        openXRHmdTarget?.cleanup()
        openXRHmdTarget = null

        windowContexts.forEach { ctx ->
            ctx.profilerState?.profiler?.destroy()
            ctx.commandBuffers.forEach { it.cleanup() }
            ctx.computeCommandBuffers.forEach { it.cleanup() }
            ctx.fences.forEach { it.cleanup() }
            ctx.computeFences.forEach { it.cleanup() }
            ctx.computeFinishedSemaphores.forEach { it.cleanup() }
            cleanupSecondaryMap(ctx.computeSecondary)
            cleanupSecondaryMap(ctx.graphicsSecondary)
            cleanupPoolMap(ctx.computePoolsPerThread)
            cleanupPoolMap(ctx.graphicsPoolsPerThread)
            ctx.depthAttachments.forEach { it.cleanup() }
            ctx.swapChain.cleanup()
            ctx.surface.cleanup()
        }
        pipelineCache.cleanup()
        commandPool.cleanup()
        computeCommandPool.cleanup()
        workerDispatcher.close()
        workerExecutor.shutdownNow()
        queueDispatcher.close()
        queueExecutor.shutdownNow()

        // A document can still hold its resources when its renderer closes. GC alone
        // cannot establish the lifetime of native objects owned by this device.
        AutoDisposer.releaseOwner(device) { cleanup.add(it) }
        drainDeferredCleanup(cleanup, cleanupStack)?.let { failure ->
            if (deferredFailure == null) deferredFailure = failure
            else deferredFailure.addSuppressed(failure)
        }
        cleanupAllocatorForRenderer(this)
        device.cleanup()
        physicalDevice.cleanup()
        instance.cleanup()
        windows.forEach { it.associatedRenderer = null }
        deferredFailure?.let { throw it }
    }

    /** Monotonic submitted-frame id; acquisition-only retries do not advance GPU retirement. */
    var frameCounter = 0L
    private var lastVertexUploadStatsLogNs = 0L
    private var lastRunFpsLogNs = 0L
    private var lastRunFpsFrame = 0

    private fun beginFrameUploadStats() {
        synchronized(uploadStatsLock) {
            currentUploadRecordedBytes = 0L
            currentUploadImmediateBytes = 0L
        }
    }

    fun recordVertexBufferUpload(bytes: Long, immediate: Boolean) {
        if (bytes <= 0L) return
        synchronized(uploadStatsLock) {
            if (immediate) {
                currentUploadImmediateBytes += bytes
            } else {
                currentUploadRecordedBytes += bytes
            }
        }
    }

    private fun endFrameUploadStats(frameId: Long): VertexUploadFrameStats {
        return synchronized(uploadStatsLock) {
            VertexUploadFrameStats(
                frameId = frameId,
                recordedBytes = currentUploadRecordedBytes,
                immediateBytes = currentUploadImmediateBytes
            ).also {
                lastVertexUploadFrameStats = it
            }
        }
    }

    private fun maybeLogVertexUploadStats(frame: Long, stats: VertexUploadFrameStats, everyNFrames: Int = 120) {
        val now = System.nanoTime()
        if (lastVertexUploadStatsLogNs != 0L && now - lastVertexUploadStatsLogNs < PERF_LOG_INTERVAL_NS) return
        lastVertexUploadStatsLogNs = now
        fun mib(bytes: Long): Double = bytes.toDouble() / (1024.0 * 1024.0)
        lprintln(
            "info,perf,upload",
            "vertex_upload frame=${stats.frameId} total=${"%.3f".format(mib(stats.totalBytes))}MiB recorded=${"%.3f".format(mib(stats.recordedBytes))}MiB immediate=${"%.3f".format(mib(stats.immediateBytes))}MiB"
        )
    }

    fun supportsDirectWindowCapture(window: Window = this.window): Boolean {
        val windowIndex = windows.indexOf(window)
        if (windowIndex < 0) return false
        return windowContexts.getOrNull(windowIndex)?.swapChain?.supportsTransferSrc == true
    }

    suspend fun captureWindowToFile(
        window: Window = this.window,
        outputPath: String,
        timeoutMs: Long = 15_000L
    ): String {
        val startedAt = System.nanoTime()
        val windowIndex = windows.indexOf(window)
        require(windowIndex >= 0) { "Window is not owned by this renderer" }
        require(supportsDirectWindowCapture(window)) { "Swapchain does not support transfer-source capture" }

        val request = windowCaptures.enqueue(windowIndex, outputPath, startedAt)
        val queueSize = windowCaptures.pendingCount(windowIndex)
        SaveReloadLog.log(
            "window-capture-requested",
            "path=$outputPath windowIndex=$windowIndex queueSize=$queueSize size=${window.width}x${window.height} timeoutMs=$timeoutMs"
        )
        try {
            val result = windowCaptures.await(request, timeoutMs)
            val totalMs = SaveReloadLog.logTiming(
                "window-capture-total",
                startedAt,
                "path=$outputPath windowIndex=$windowIndex queueSizeAtRequest=$queueSize size=${window.width}x${window.height}"
            )
            if (totalMs >= 1000L) {
                lprintln(
                    "warning,graphics",
                    "slow direct window capture path=$outputPath ms=$totalMs size=${window.width}x${window.height}"
                )
            }
            return result
        } catch (t: Throwable) {
            SaveReloadLog.log(
                "window-capture-total",
                "ms=${SaveReloadLog.elapsedMillis(startedAt)} path=$outputPath windowIndex=$windowIndex ok=false error=${t.message ?: t::class.simpleName}"
            )
            throw t
        }
    }

    private fun dequeueWindowCaptureRequests(windowIndex: Int): List<WindowCaptureRequest> {
        return windowCaptures.takeForFrame(windowIndex)
    }

    private fun recordPendingWindowCaptures(
        ctx: WindowContext,
        ctxIndex: Int,
        imageIndex: Int,
        cmd: CommandBuffer
    ): List<RecordedWindowCapture> {
        val startedAt = System.nanoTime()
        val requests = dequeueWindowCaptureRequests(ctxIndex)
        if (requests.isEmpty()) return emptyList()

        val width = ctx.swapChain.swapChainExtent.width()
        val height = ctx.swapChain.swapChainExtent.height()
        if (width <= 0 || height <= 0) {
            val error = IllegalStateException("Window has invalid capture size ${width}x$height")
            requests.forEach { request ->
                request.completion.completeExceptionally(error)
            }
            return emptyList()
        }

        val format = ctx.swapChain.surfaceFormat.imageFormat
        val bytesPerPixel = try {
            GpuImageCapture.bytesPerPixel(format, "window")
        } catch (t: Throwable) {
            // Unknown swapchain format. Fail the requests the way the invalid-size guard above
            // does — throwing out of here would take the frame with it.
            requests.forEach { request ->
                request.completion.completeExceptionally(t)
            }
            return emptyList()
        }
        val byteCount = width.toLong() * height.toLong() * bytesPerPixel.toLong()
        val captures = requests.map { request ->
            RecordedWindowCapture(
                request = request,
                stagingBuffer = afield.four.buffers.VulkanBuffer(
                    renderer = this,
                    requestedSize = byteCount,
                    usage = VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                    reqMask = VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT or VK10.VK_MEMORY_PROPERTY_HOST_CACHED_BIT
                ),
                width = width,
                height = height,
                format = format
            )
        }

        MemoryStack.stackPush().use { stack ->
            val colorRange = VkImageSubresourceRange.calloc(stack)
                .aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0)
                .levelCount(1)
                .baseArrayLayer(0)
                .layerCount(1)

            image_layout_transition(
                cmd.vkCommandBuffer,
                ctx.swapChain.imageViews[imageIndex].image,
                VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT,
                VK_PIPELINE_STAGE_2_COPY_BIT,
                VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT,
                VK_ACCESS_2_TRANSFER_READ_BIT,
                KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
                VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                colorRange
            )

            val copyRegion = VkBufferImageCopy.calloc(1, stack)
            copyRegion[0]
                .bufferOffset(0L)
                .bufferRowLength(0)
                .bufferImageHeight(0)
                .imageSubresource(
                    VkImageSubresourceLayers.calloc(stack)
                        .aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                        .mipLevel(0)
                        .baseArrayLayer(0)
                        .layerCount(1)
                )
                .imageOffset(VkOffset3D.calloc(stack).x(0).y(0).z(0))
                .imageExtent(VkExtent3D.calloc(stack).width(width).height(height).depth(1))

            captures.forEach { capture ->
                VK10.vkCmdCopyImageToBuffer(
                    cmd.vkCommandBuffer,
                    ctx.swapChain.imageViews[imageIndex].image,
                    VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    capture.stagingBuffer.buffer,
                    copyRegion
                )
            }

            image_layout_transition(
                cmd.vkCommandBuffer,
                ctx.swapChain.imageViews[imageIndex].image,
                VK_PIPELINE_STAGE_2_COPY_BIT,
                VK_PIPELINE_STAGE_2_NONE,
                VK_ACCESS_2_TRANSFER_READ_BIT,
                0,
                VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
                colorRange
            )
        }

        SaveReloadLog.logTiming(
            "window-capture-recorded",
            startedAt,
            "windowIndex=$ctxIndex imageIndex=$imageIndex requests=${requests.size} size=${width}x${height} format=$format bytesPerPixel=$bytesPerPixel"
        )
        return captures
    }

    private fun completeWindowCapture(capture: RecordedWindowCapture) {
        val readStartedAt = System.nanoTime()
        val pixels = readWindowCapturePixels(capture)
        if (pixels == null) {
            SaveReloadLog.log(
                "window-capture-readback",
                "ms=${SaveReloadLog.elapsedMillis(readStartedAt)} path=${capture.request.outputPath} ok=false size=${capture.width}x${capture.height} queuedMs=${SaveReloadLog.elapsedMillis(capture.request.requestedAtNs)}"
            )
            return
        }
        val readMs = SaveReloadLog.elapsedMillis(readStartedAt)
        SaveReloadLog.log(
            "window-capture-readback",
            "ms=$readMs path=${capture.request.outputPath} size=${capture.width}x${capture.height} queuedMs=${SaveReloadLog.elapsedMillis(capture.request.requestedAtNs)}"
        )
        if (readMs >= 500L) {
            lprintln(
                "warning,graphics",
                "slow window capture readback path=${capture.request.outputPath} ms=$readMs size=${capture.width}x${capture.height}"
            )
        }
        @OptIn(DelicateCoroutinesApi::class)
        GlobalScope.launch(Runloop.miscPool.asCoroutineDispatcher()) {
            encodeWindowCapture(pixels)
        }
    }

    private fun readWindowCapturePixels(capture: RecordedWindowCapture): WindowCapturePixels? {
        try {
            if (!capture.request.completion.isActive) return null
            var rgba: ByteArray? = null
            capture.stagingBuffer.mapAndRead_bytes {
                rgba = GpuImageCapture.readRgba(this, capture.format, capture.width, capture.height, "window")
            }
            return WindowCapturePixels(capture.request, requireNotNull(rgba), capture.width, capture.height)
        } catch (t: Throwable) {
            capture.request.completion.completeExceptionally(t)
            lprintln("warning,graphics", "direct window capture readback failed: ${t.message}")
            return null
        } finally {
            capture.stagingBuffer.cleanup()
        }
    }

    private fun encodeWindowCapture(capture: WindowCapturePixels) {
        if (!capture.request.completion.isActive) return
        val startedAt = System.nanoTime()
        var extension = ""
        try {
            extension = Path.of(capture.request.outputPath).fileName.toString()
                .substringAfterLast('.', "")
                .lowercase(Locale.getDefault())
            if (extension == "jpg" || extension == "jpeg") {
                writeWindowCaptureJpeg(capture)
            } else {
                writeWindowCapturePng(capture)
            }
            capture.request.completion.complete(capture.request.outputPath)
            val path = Path.of(capture.request.outputPath)
            val byteSize = runCatching { Files.size(path) }.getOrDefault(-1L)
            val totalMs = SaveReloadLog.logTiming(
                "window-capture-encode",
                startedAt,
                "path=${capture.request.outputPath} extension=$extension size=${capture.width}x${capture.height} bytes=$byteSize queuedMs=${SaveReloadLog.elapsedMillis(capture.request.requestedAtNs)}"
            )
            if (totalMs >= 1000L) {
                lprintln(
                    "warning,graphics",
                    "slow window capture encode path=${capture.request.outputPath} ms=$totalMs extension=$extension size=${capture.width}x${capture.height}"
                )
            }
        } catch (t: Throwable) {
            capture.request.completion.completeExceptionally(t)
            SaveReloadLog.log(
                "window-capture-encode",
                "ms=${SaveReloadLog.elapsedMillis(startedAt)} path=${capture.request.outputPath} extension=$extension ok=false error=${t.message ?: t::class.simpleName}"
            )
            lprintln("warning,graphics", "direct window capture encode failed: ${t.message}")
        }
    }

    private fun writeWindowCapturePng(capture: WindowCapturePixels) {
        val startedAt = System.nanoTime()
        val pngBytes = PngEncoder.encodeFromBuffer(
            ByteBuffer.wrap(capture.rgba),
            capture.width,
            capture.height,
            // vkCmdCopyImageToBuffer preserves the presented swapchain's top-to-bottom row order.
            flipY = false,
            // A presented swapchain image is opaque; its alpha is leftover fragment coverage. Keeping
            // it made three docs figures render up to 3.28x brighter on white than on the dark page.
            opaque = true
        )
        val encodeMs = SaveReloadLog.elapsedMillis(startedAt)
        val writeStartedAt = System.nanoTime()
        Files.write(
            Path.of(capture.request.outputPath),
            pngBytes,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE
        )
        SaveReloadLog.log(
            "window-capture-png",
            "ms=${SaveReloadLog.elapsedMillis(startedAt)} path=${capture.request.outputPath} size=${capture.width}x${capture.height} encodedBytes=${pngBytes.size} encodeMs=$encodeMs writeMs=${SaveReloadLog.elapsedMillis(writeStartedAt)}"
        )
    }

    private fun writeWindowCaptureJpeg(capture: WindowCapturePixels) {
        val startedAt = System.nanoTime()
        val rgb = GpuImageCapture.packRgbaToRgbTopDown(capture.rgba, capture.width, capture.height)
        val packMs = SaveReloadLog.elapsedMillis(startedAt)
        val writeStartedAt = System.nanoTime()
        val quality = windowCaptureJpegQuality()
        GpuImageCapture.writeJpegRgb(
            capture.request.outputPath,
            rgb,
            capture.width,
            capture.height,
            quality
        )
        val writeMs = SaveReloadLog.elapsedMillis(writeStartedAt)
        SaveReloadLog.log(
            "window-capture-jpeg",
            "ms=${SaveReloadLog.elapsedMillis(startedAt)} path=${capture.request.outputPath} size=${capture.width}x${capture.height} quality=$quality packMs=$packMs writeMs=$writeMs"
        )
    }

    private fun windowCaptureJpegQuality(): Int {
        val configured = System.getProperty("afield.history.screenshotJpegQuality")
            ?: System.getenv("AFIELD_HISTORY_SCREENSHOT_JPEG_QUALITY")
        return configured?.toIntOrNull()?.coerceIn(1, 100) ?: 82
    }

    /** Changes after a presentation gap; temporal adapters must reject old history. */
    var presentationRevision=0L; private set
    private val unavailableWindows=mutableMapOf<Int,Long>()

    fun render() {
        check(!cleanedUp) { "Renderer has been cleaned up" }
        val maxFramesInFlight = windowContexts.maxOfOrNull { it.numImages } ?: return
        val frameId = frameCounter
        beginFrameUploadStats()

        // If geometry writes were opened earlier in this runloop frame, close them now.
        takeOpenGeometryPhaseTokenForFrame(frameId)?.let { token ->
            try {
                withWorkerParallelism {
                    runGeometryProducers(token, parallelAllowed = true)
                }
            } finally {
                sealGeometryPools(token)
            }
        }

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
        var computeSubmittedThisFrame = false
        windowContexts.forEachIndexed { ctxIndex, ctx ->
            for (imageIndex in 0 until ctx.numImages) {
                drainCompletedGraphicsCallbacks(ctx, ctxIndex, imageIndex)
            }
        }

        windowContexts.forEachIndexed { ctxIndex, ctx ->
            val w = ctx.window
            if (w.width <= 0 || w.height <= 0) return@forEachIndexed

            var imageIndex = 0
            val preAcquiredImage = if (ctxIndex == 0) peekPreAcquiredPrimaryImageForFrame(frameId) else null

            try {
                imageIndex = preAcquiredImage ?: acquireFrameImage(ctxIndex)

                ctx.currentImage = imageIndex
                if (ctxIndex == 0) {
                    currentImage = imageIndex
                    if (preAcquiredImage == null) {
                        runGeometryPhase(
                            GeometryFrameToken(
                                frameId = frameId,
                                imageIndex = imageIndex,
                                windowIndex = ctxIndex
                            )
                        )
                    }
                }
                ctx.profilerState?.let { ps ->
                    synchronized(ps.lock) {
                        ps.profiler.beginFrame(imageIndex)
                        ps.perImageModuleMarks.getOrNull(imageIndex)?.clear()
                    }
                }

                withWorkerParallelism {
                    runBlocking {
                        val runComputeForWindow = !Options.computeOncePerFrame || !computeSubmittedThisFrame
                        val computeRecording = if (runComputeForWindow) {
                            asyncWorker {
                                recordComputePass(ctx, ctxIndex, imageIndex, frameId)
                            }
                        } else null
                        val graphicsRecording = asyncWorker {
                            recordGraphicsPass(ctx, ctxIndex, imageIndex, frameId)
                        }

                        computeRecording?.await()
                        if (runComputeForWindow) {
                            CPUSpan.time("submit_cs[$ctxIndex]") {
                                submitCompute(
                                    ctx,
                                    ctx.computeCommandBuffers[imageIndex],
                                    ctx.computeFences[imageIndex],
                                    ctx.computeFinishedSemaphores[imageIndex],
                                    graphQueue
                                )
                            }
                            drainComputeSubmissionCallbacks(ctxIndex, imageIndex, ctx.computeFences[imageIndex])
                            computeSubmittedThisFrame = true
                        }

                        val recordedWindowCaptures = graphicsRecording.await()
                        CPUSpan.time("submit_gfx[$ctxIndex]") {
                            submit(
                                ctx,
                                ctx.commandBuffers[imageIndex],
                                ctx.fences[imageIndex],
                                graphQueue,
                                ctx.computeFinishedSemaphores[imageIndex].takeIf { runComputeForWindow }
                            )
                        }
                        if (recordedWindowCaptures.isNotEmpty()) {
                            ctx.fences[imageIndex].fenceWait()
                            recordedWindowCaptures.forEach { capture ->
                                completeWindowCapture(capture)
                            }
                        }
                    }
                }

                CPUSpan.time("present[$ctxIndex]") {
                    runOnQueueThread {
                        if (ctx.swapChain.presentImage(presentQueue, imageIndex, frameId, ctxIndex).resize) w.isResized = true
                    }
                }

                ctx.profilerState?.pending?.addLast(Pending(imageIndex))
            } catch (e: ImageNotAvailable) {
                unavailableWindows.putIfAbsent(ctxIndex,System.nanoTime())
                if (!e.retryable) lprintln("warning,graphics", e.message ?: "ImageNotAvailable")
            }
        }

        // Render to OpenXR HMD once per frame.
        openXRHmdTarget?.renderFrame()
        clearPreAcquiredPrimaryImageForFrame(frameId)

        // Deferred cleanup queue (use max frames-in-flight across all contexts)
        if (cleanup.isNotEmpty()) cleanupStack.add(frameCounter to cleanup)
        cleanup = java.util.Collections.synchronizedList(mutableListOf())
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
        AutoDisposer.drain(device) { cleanup.add(it) }

        renderEnd.update()
        CPUSpan.snapshotAndMaybePrint(everyNFrames = 120, frame = frameCounter)
        maybeLogVertexUploadStats(frameCounter, endFrameUploadStats(frameId))

        if (windowContexts.isNotEmpty()) {
            currentImage = (defaultContext.currentImage + 1) % defaultContext.numImages
        }

        // Failed acquire attempts must not age the GPU retirement queue while all windows
        // are hidden. A later successful submission resumes its normal frame accounting.
        if (computeSubmittedThisFrame || openXRHmdTarget != null) frameCounter++
        currentCommandBuffer = null
    }

    private fun recordComputePass(
        ctx: WindowContext,
        ctxIndex: Int,
        imageIndex: Int,
        frameId: Long
    ) {
        CPUSpan.time("record_cs[$ctxIndex]") {
            ctx.computeFences[imageIndex].fenceWait()
            awaitComputePostSubmitTasks(ctxIndex, imageIndex)
            ctx.computeFences[imageIndex].reset()

            val computeElements = mutableListOf<Pair<String, (CommandBuffer) -> Unit>>()
            computeList.modules.forEach { (name, ptr) ->
                computeElements += name to { cb -> ptr.render(cb, Rectanglef()) }
            }
            computeList.others.forEach { (name, target) ->
                computeElements += name to { cb -> target.render(cb, imageIndex) }
            }

            val secondaryBuffers = arrayOfNulls<CommandBuffer>(computeElements.size)
            runBlocking {
                val jobs = computeElements.mapIndexed { idx, (name, action) ->
                    asyncWorker {
                        val cb = getSecondaryCommandBuffer(
                            ctx.computeSecondary,
                            "compute:$name",
                            computeCommandPool,
                            ctx.computePoolsPerThread,
                            ctx.numImages,
                            imageIndex
                        )
                        cb.reset()
                        cb.beginRecording(CommandBuffer.SecondaryInheritance())
                        computeRecordingContext.set(ctxIndex to imageIndex)
                        try {
                            action(cb)
                        } finally {
                            computeRecordingContext.remove()
                        }
                        cb.endRecording()
                        secondaryBuffers[idx] = cb
                    }
                }
                jobs.awaitAll()
            }

            val ccmd = ctx.computeCommandBuffers[imageIndex]
            ccmd.reset(); ccmd.beginRecording()

            // --- Label start (compute pass) ---
            MemoryStack.stackPush().use { s ->
                val c = s.floats(0.25f, 0.65f, 0.85f, 1f) // cyan-ish
                ccmd.beginLabel("Compute Pass [ctx=$ctxIndex]", c)
            }

            val profilerState = ctx.profilerState
            var csScope: GpuProfiler.PipeStatScope? = null
            var pcScope: GpuProfiler.PerfScope? = null
            profilerState?.let { p ->
                synchronized(p.lock) {
                    p.markers[imageIndex].tsFrameBegin = p.profiler.writeTimestamp2(
                        ccmd.vkCommandBuffer, imageIndex, VK_PIPELINE_STAGE_2_TOP_OF_PIPE_BIT
                    )
                    val cs = p.profiler.beginPipelineStats(ccmd.vkCommandBuffer, imageIndex)
                    csScope = cs
                    csScope?.let { p.markers[imageIndex].pipeStatsCompute = it.index }
                    pcScope = p.profiler.beginPerfCounters(ccmd.vkCommandBuffer, imageIndex)
                }
            }

            if (secondaryBuffers.isNotEmpty()) {
                MemoryStack.stackPush().use { stack ->
                    val pb = stack.mallocPointer(secondaryBuffers.size)
                    secondaryBuffers.forEach { cb ->
                        pb.put(cb!!.vkCommandBuffer.address())
                    }
                    pb.flip()
                    VK10.vkCmdExecuteCommands(ccmd.vkCommandBuffer, pb)
                }
            } else {
                computeRecordingContext.set(ctxIndex to imageIndex)
                try {
                    computeList.render(ccmd, imageIndex)
                } finally {
                    computeRecordingContext.remove()
                }
            }

            profilerState?.let { p ->
                synchronized(p.lock) {
                    p.profiler.endPerfCounters(ccmd.vkCommandBuffer, imageIndex, pcScope)
                    p.profiler.endPipelineStats(ccmd.vkCommandBuffer, csScope)
                    p.markers[imageIndex].tsAfterCompute = p.profiler.writeTimestamp2(
                        ccmd.vkCommandBuffer, imageIndex, VK_PIPELINE_STAGE_2_BOTTOM_OF_PIPE_BIT
                    )
                }
            }

            // --- Label end (compute pass) ---
            ccmd.endLabel()

            ccmd.endRecording()
        }
    }

    private fun recordGraphicsPass(
        ctx: WindowContext,
        ctxIndex: Int,
        imageIndex: Int,
        frameId: Long
    ): List<RecordedWindowCapture> {
        var recordedWindowCaptures: List<RecordedWindowCapture> = emptyList()
        CPUSpan.time("record_gfx[$ctxIndex]") {
            // Ensure the per-image graphics fence from any previous use is complete before re-recording
            ctx.fences[imageIndex].fenceWait()
            ctx.fences[imageIndex].reset()

            val cmd = ctx.commandBuffers[imageIndex]
            cmd.reset(); cmd.beginRecording()

            // Upload dirty mesh buffers once on the primary command buffer before any
            // secondary command buffers are recorded with render-pass inheritance.
            prepareTargetMeshesForDraw(ctx.renderTarget, cmd)

            val swapChainExtent = ctx.swapChain.swapChainExtent
            val width = swapChainExtent.width()
            val height = swapChainExtent.height()

            val dynRendering = CommandBuffer.DynamicRenderingInheritance(
                colorFormats = intArrayOf(ctx.swapChain.surfaceFormat.imageFormat),
                depthFormat = ctx.depthAttachments.getOrNull(imageIndex)?.image?.format ?: Options.defaultDepthFormat
            )

            val moduleElements = mutableListOf<Pair<String, (CommandBuffer) -> Unit>>()
            ctx.renderTarget.modules.forEach { (name, mp) ->
                moduleElements += "mod:$name" to { cb ->
                    MemoryStack.stackPush().use { stack ->
                        val viewport = mp.current.parameters.viewport ?: Rect(
                            0.0, 0.0, width.toDouble(), height.toDouble()
                        )
                        setViewportAndScissor(cb.vkCommandBuffer, viewport, stack)
                    }
                    mp.render(cb, Rectanglef(0f, 0f, width.toFloat(), height.toFloat()))
                }
            }
            ctx.renderTarget.meshModules.forEach { (name, mp) ->
                moduleElements += "mesh:$name" to { cb ->
                    MemoryStack.stackPush().use { stack ->
                        val viewport = mp.current.parameters.viewport ?: Rect(
                            0.0, 0.0, width.toDouble(), height.toDouble()
                        )
                        setViewportAndScissor(cb.vkCommandBuffer, viewport, stack)
                    }
                    mp.render(cb, Rectanglef(0f, 0f, width.toFloat(), height.toFloat()))
                }
            }

            val enableSecondaryModules =
                Options.secondaryGraphicsCommandBuffers && device.supportsMaintenance7
            val moduleBuffers: List<CommandBuffer> =
                if (enableSecondaryModules && moduleElements.isNotEmpty()) {
                    val collected = arrayOfNulls<CommandBuffer>(moduleElements.size)
                    runBlocking {
                        val jobs = moduleElements.mapIndexed { idx, (name, action) ->
                            asyncWorker {
                        val cb = getSecondaryCommandBuffer(
                            ctx.graphicsSecondary,
                            "gfx:$name",
                            commandPool,
                            ctx.graphicsPoolsPerThread,
                            ctx.numImages,
                            imageIndex
                        )
                                cb.reset()
                                cb.beginRecording(
                                    CommandBuffer.SecondaryInheritance(
                                        dynamicRendering = dynRendering
                                    )
                                )
                                action(cb)
                                cb.endRecording()
                                collected[idx] = cb
                            }
                        }
                        jobs.awaitAll()
                    }
                    collected.filterNotNull()
                } else emptyList()

            // --- Label start (graphics frame) ---
            MemoryStack.stackPush().use { s ->
                val c = s.floats(0.85f, 0.45f, 0.15f, 1f) // orange-ish
                cmd.beginLabel("Graphics Frame [ctx=$ctxIndex]", c)
            }

            currentCommandBuffer = cmd
            graphicsRecordingContext.set(ctxIndex to imageIndex)
            try {
                renderStart.update()

                val profilerState = ctx.profilerState
                var gsScope: GpuProfiler.PipeStatScope? = null
                var perfFrameScope: GpuProfiler.PerfScope? = null

                profilerState?.let { p ->
                    synchronized(p.lock) {
                        gsScope = p.profiler.beginPipelineStats(cmd.vkCommandBuffer, imageIndex)
                        gsScope?.let { p.markers[imageIndex].pipeStatsGraphics = it.index }
                        perfFrameScope = p.profiler.beginPerfCounters(cmd.vkCommandBuffer, imageIndex)
                    }
                }

                // Bracket the scene render itself
                MemoryStack.stackPush().use { s ->
                    val c = s.floats(0.45f, 0.85f, 0.25f, 1f) // green-ish
                    cmd.beginLabel("RenderTargets", c)
                }

                if (moduleBuffers.isNotEmpty()) {
                    ctx.renderTarget.preRecordedInsideRendering = moduleBuffers
                }

                ctx.renderTarget.render(cmd, imageIndex)
                recordedWindowCaptures = recordPendingWindowCaptures(ctx, ctxIndex, imageIndex, cmd)

                cmd.endLabel() // end "RenderTargets"

                profilerState?.let { p ->
                    synchronized(p.lock) {
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
                }

                // --- Label end (graphics frame) ---
                cmd.endLabel()

                cmd.endRecording()
            } finally {
                graphicsRecordingContext.remove()
                currentCommandBuffer = null
            }
        }
        return recordedWindowCaptures
    }

    class ImageNotAvailable(s: String, val retryable: Boolean = false) : RuntimeException(s)

    fun submit(
        context: WindowContext,
        commandBuffer: CommandBuffer,
        currentFence: Fence,
        queue: Queue,
        computeSemaphore: Semaphore?
    ) {
        runOnQueueThread {
            MemoryStack.stackPush().use { stack ->
                val acquireSync = context.swapChain.syncSemaphoresList[context.swapChain.currentFrame]
                val presentSync = context.swapChain.syncSemaphoresList[context.currentImage]

                // Command buffer info
                val cbInfo = VkCommandBufferSubmitInfo.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_SUBMIT_INFO)
                cbInfo[0]
                    .commandBuffer(commandBuffer.vkCommandBuffer)
                    .deviceMask(0)

                // Always wait for image acquisition; wait for compute only when this window ran it.
                val waits = VkSemaphoreSubmitInfo.calloc(if (computeSemaphore == null) 1 else 2, stack)
                waits[0].sType(VK_STRUCTURE_TYPE_SEMAPHORE_SUBMIT_INFO)
                    .semaphore(acquireSync.imgAcquisitionSemaphore.vkSemaphore)
                    .value(acquireSync.imgAcquisitionSemaphore.currentValue())
                    .stageMask(VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT)
                    .deviceIndex(0)
                if (computeSemaphore != null) {
                    waits[1].sType(VK_STRUCTURE_TYPE_SEMAPHORE_SUBMIT_INFO)
                        .semaphore(computeSemaphore.vkSemaphore)
                        .value(computeSemaphore.currentValue())
                        // conservative: ensure graphics won't start any stage before compute finished
                        .stageMask(VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT)
                        .deviceIndex(0)
                }

                // Signal semaphore: render complete
                val signals = VkSemaphoreSubmitInfo.calloc(1, stack)
                signals[0].sType(VK_STRUCTURE_TYPE_SEMAPHORE_SUBMIT_INFO)
                    .semaphore(presentSync.renderCompleteSemaphore.vkSemaphore)
                    .value(presentSync.renderCompleteSemaphore.nextSignalValue())
                    .stageMask(VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT)
                    .deviceIndex(0)

                val submit2 = VkSubmitInfo2.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO_2)
                submit2[0]
                    .pWaitSemaphoreInfos(waits)
                    .pSignalSemaphoreInfos(signals)
                    .pCommandBufferInfos(cbInfo)

                synchronized(queueLock) {
                    vkQueueSubmit2(queue.vkQueue, submit2, currentFence.vkFence)
                }
            }
        }
    }


    fun submitCompute(
        context: WindowContext,
        commandBuffer: CommandBuffer,
        currentComputeFence: Fence,
        signalSemaphore: Semaphore,
        queue: Queue
    ) {
        runOnQueueThread {
            MemoryStack.stackPush().use { stack ->
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

                synchronized(queueLock) {
                    vkQueueSubmit2(queue.vkQueue, submit2, currentComputeFence.vkFence)
                }
            }
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
            profilerState = profilerState,
            computeSecondary = mutableMapOf(),
            graphicsSecondary = mutableMapOf(),
            computePoolsPerThread = mutableMapOf(),
            graphicsPoolsPerThread = mutableMapOf()
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
        cleanupSecondaryMap(context.computeSecondary)
        cleanupSecondaryMap(context.graphicsSecondary)

        val newImages = context.swapChain.imageViews.size
        context.commandBuffers = Array(newImages) { CommandBuffer(commandPool, primary = true, oneTimeSubmit = false) }
        context.computeCommandBuffers = Array(newImages) { CommandBuffer(computeCommandPool, primary = true, oneTimeSubmit = false) }
        context.fences = Array(newImages) { Fence(device, signaled = true) }
        context.computeFences = Array(newImages) { Fence(device, signaled = true) }
        context.computeFinishedSemaphores = Array(newImages) { Semaphore(device) }
        context.computeSecondary = mutableMapOf()
        context.graphicsSecondary = mutableMapOf()
        cleanupPoolMap(context.computePoolsPerThread)
        cleanupPoolMap(context.graphicsPoolsPerThread)
        context.computePoolsPerThread = mutableMapOf()
        context.graphicsPoolsPerThread = mutableMapOf()
        context.numImages = newImages
        context.currentImage = 0

        context.profilerState?.profiler?.destroy()
        context.profilerState = if (Options.profiling) createProfilerState(newImages) else null

        nameContextObjects(context, windowContexts.indexOf(context))
    }

    @Volatile
    var running = false

    /**
     * Identifies the newest call to [run] or [runForever]. Live coding commonly re-launches the
     * renderer box without stopping it first. Each invocation used to enter the render loop with
     * its own local frame counter, so two coroutines could alternate submissions against the same
     * per-frame fences and command buffers. The newest invocation now takes ownership at the next
     * coroutine yield / loop boundary; an older invocation simply retires.
     */
    private val runInvocation = AtomicLong(0)

    private fun cleanupSecondaryMap(map: MutableMap<String, Array<CommandBuffer>>) {
        map.values.forEach { arr -> arr.forEach { it.cleanup() } }
        map.clear()
    }

    private fun cleanupPoolMap(map: MutableMap<Long, CommandPool>) {
        map.values.forEach { it.cleanup() }
        map.clear()
    }

    private fun getSecondaryCommandBuffer(
        store: MutableMap<String, Array<CommandBuffer>>,
        key: String,
        basePool: CommandPool,
        poolMap: MutableMap<Long, CommandPool>,
        numImages: Int,
        imageIndex: Int
    ): CommandBuffer {
        val threadId = Thread.currentThread().threadId()
        val threadKey = "$key@$threadId"
        val pool = synchronized(poolMap) {
            poolMap.getOrPut(threadId) { CommandPool(basePool.device, basePool.queueFamilyIndex) }
        }
        val existing = store[threadKey]
        val buffers = if (existing == null || existing.size != numImages) {
            existing?.forEach { it.cleanup() }
            Array(numImages) { CommandBuffer(pool, primary = false, oneTimeSubmit = true) }
        } else existing
        store[threadKey] = buffers
        return buffers[imageIndex]
    }

    private fun prepareTargetMeshesForDraw(target: RenderTarget, command: CommandBuffer) {
        target.modules.forEach { (_, mp) ->
            mp.current.meshes.values.forEach { mesh ->
                mesh.prepareForDraw(command)
            }
        }
    }

    private fun setViewportAndScissor(cmd: VkCommandBuffer, rect: Rect, stack: MemoryStack) {
        val viewport = VkViewport.calloc(1, stack)
            .x(rect.x.toFloat())
            .y((rect.y + rect.h).toFloat()) // flip Y
            .height((-rect.h).toFloat())
            .width(rect.w.toFloat())
            .minDepth(0f).maxDepth(1f)
        VK10.vkCmdSetViewport(cmd, 0, viewport)

        val scissor = VkRect2D.calloc(1, stack)
            .extent(
                VkExtent2D.calloc(stack)
                    .width(rect.w.toInt())
                    .height(rect.h.toInt())
            )
            .offset(VkOffset2D.calloc(stack).x(rect.x.toInt()).y(rect.y.toInt()))
        VK10.vkCmdSetScissor(cmd, 0, scissor)
    }

    fun runForever(update: () -> Unit) {
        val invocation = runInvocation.incrementAndGet()
        running = true
        var initialTime = System.nanoTime()
        var frame = 0
        while (running && runInvocation.get() == invocation && windows.any { !it.shouldClose() }) {
            afield.logger.PinnedConsole.beat("render")
            windows.forEach { it.pollEvents() }
            update()
            render()
            frame++
            val now = System.nanoTime()
            if (now - initialTime >= PERF_LOG_INTERVAL_NS) {
                lprintln("info,perf,fps", "fps=${1_000_000_000.0 * frame / (now - initialTime)}")
                initialTime = now
                frame = 0
            }
        }
        // A newer live-code launch owns the same renderer now; do not tear its device down.
        if (runInvocation.get() == invocation) cleanup()
    }

    suspend fun run() {
        val invocation = runInvocation.incrementAndGet()
        running = true
        lastRunFpsLogNs = 0L
        lastRunFpsFrame = 0
        var frame = 0
        while (running && runInvocation.get() == invocation && windows.any { !it.shouldClose() }) {
            logTime("pollEvents") { windows.forEach { it.pollEvents() } }
            logTime("render") { render() }
            frame++
            afield.logger.PinnedConsole.beat("render")
            val now = System.nanoTime()
            if (lastRunFpsLogNs == 0L) {
                lastRunFpsLogNs = now
                lastRunFpsFrame = frame
            } else if (now - lastRunFpsLogNs >= PERF_LOG_INTERVAL_NS) {
                val framesSinceLastLog = frame - lastRunFpsFrame
                lprintln("info,perf,fps", "fps=${1_000_000_000.0 * framesSinceLastLog / (now - lastRunFpsLogNs)} Thread = ${Thread.currentThread().name} = ${System.identityHashCode(Thread.currentThread().name)} ${isMainThread}")
                lastRunFpsLogNs = now
                lastRunFpsFrame = frame
            }
            logTime_("compute") { yield() }
            if (coroutineContext[Job]!!.isCancelled || runInvocation.get() != invocation) break
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

    @JvmName("plusAssign_rayTracingModulePointer")
    operator fun plusAssign(a: Pair<String, RayTracingModulePointer>) { computeList += a }


    @JvmName("plusAssign_computeModulePointerNamed")
    operator fun plusAssign(a: Named<ComputeModulePointer>) { computeList += a }

    @JvmName("plusAssign_rayTracingModulePointerNamed")
    operator fun plusAssign(a: Named<RayTracingModulePointer>) { computeList += a.name to a.value }

    fun getProfiler(): GpuProfiler? = defaultContext.profilerState?.profiler

    private fun resolveProfilingForImage(context: WindowContext, imageIndex: Int) {
        val ps = context.profilerState ?: return
        synchronized(ps.lock) {
            val p = ps.profiler
            val m = ps.markers.getOrNull(imageIndex) ?: return

            if (m.perfFrameIndex >= 0 && p.opts.perfCounters) {
                val meta = p.perfMeta
                val vals = p.fetchPerfCounters(imageIndex, m.perfFrameIndex, meta.size)
                val n = kotlin.math.min(vals.size, meta.size)
                if (n > 0) {
                    val byScope = meta.withIndex().groupBy { it.value.scope }
                    for ((scope, items) in byScope) {
                        lprintln("info,perf", "scope=$scope")
                        for (e in items) {
                            val i = e.index
                            lprintln("info,perf", "  ${e.value.name}: ${p.formatPerfValue(vals[i], e.value.unit)}")
                        }
                    }
                }
            }

            if (m.tsFrameBegin >= 0 && m.tsFrameEnd >= 0) {
                val ts = p.fetchTimestampNsRange(imageIndex, m.tsFrameBegin, m.tsFrameEnd)
                fun at(marker: Int): Long? {
                    val offset = marker - m.tsFrameBegin
                    return ts.getOrNull(offset)
                }
                val frameBegin = at(m.tsFrameBegin)
                val afterCompute = at(m.tsAfterCompute)
                val afterGraphics = at(m.tsAfterGraphics)
                val frameEnd = at(m.tsFrameEnd)
                if (frameBegin != null && afterCompute != null && afterGraphics != null && frameEnd != null) {
                    val frameNs = frameEnd - frameBegin
                    val computeNs = afterCompute - frameBegin
                    val graphicsNs = afterGraphics - afterCompute
                    val postNs = frameEnd - afterGraphics
                    lprintln(
                        "info,perf",
                        "GPU frame=${frameNs/1e6} ms (compute=${computeNs/1e6} ms, graphics=${graphicsNs/1e6} ms, post=${postNs/1e6} ms)"
                    )
                }
            }

            if (m.pipeStatsCompute >= 0 && p.opts.pipelineStats) {
                val ps = p.fetchPipelineStats(imageIndex, m.pipeStatsCompute)
                lprintln("info,perf", "CS invocations: ${ps.getOrNull(10) ?: -1}")
            }
            if (m.pipeStatsGraphics >= 0 && p.opts.pipelineStats) {
                val ps = p.fetchPipelineStats(imageIndex, m.pipeStatsGraphics)
                val iaVerts = ps.getOrNull(0) ?: -1
                val iaPrims = ps.getOrNull(1) ?: -1
                val vsInv   = ps.getOrNull(2) ?: -1
                val fsInv   = ps.getOrNull(7) ?: -1
                lprintln("info,perf", "GFX IA verts=$iaVerts, IA prims=$iaPrims, VS invoc=$vsInv, FS invoc=$fsInv")
            }

            val pm = ps.perImageModuleMarks.getOrNull(imageIndex) ?: mutableListOf()
            if (pm.isNotEmpty()) {
                for (mm in pm) {
                    if (mm.tsBegin >= 0 && mm.tsEnd >= 0) {
                        val ts = p.fetchTimestampNsRange(imageIndex, mm.tsBegin, mm.tsEnd)
                        if (ts.size >= 2) {
                            val dtMs = (ts.last() - ts.first()) / 1e6
                            lprintln("info,perf", "MOD '${mm.name}': ${"%.3f".format(dtMs)} ms")
                        }
                    }
                    if (mm.pipeStatsIndex >= 0 && p.opts.pipelineStats) {
                        val ps = p.fetchPipelineStats(imageIndex, mm.pipeStatsIndex)
                        val fsInv = ps.getOrNull(7) ?: -1
                        lprintln("info,perf", "MOD '${mm.name}': FS invocations=$fsInv")
                    }
                    if (mm.perfIndex >= 0 && p.opts.perfCounters) {
                        val meta = p.perfMeta
                        val vals = p.fetchPerfCounters(imageIndex, mm.perfIndex, meta.size)
                        val n = kotlin.math.min(vals.size, meta.size)
                        if (n > 0) {
                            lprintln("info,perf", "MOD '${mm.name}' PERF:")
                            for (i in 0 until n) {
                                val mi = meta[i]
                                lprintln("info,perf", "  ${mi.name}: ${p.formatPerfValue(vals[i], mi.unit)}")
                            }
                        }
                    }
                }
            }
        }
    }
}
