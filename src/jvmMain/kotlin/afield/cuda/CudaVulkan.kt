@file:afield.runtime.completion.CompletionCategory("Graphics")
/**
 * CUDA beside a Field renderer, written for box code: one CUDA context per [Renderer] on the same
 * GPU, buffers both APIs use in place, kernels compiled from CUDA C, and CUDA work ordered with the
 * frames that read its results.
 *
 * ```kotlin
 * val cuda = CudaVulkan.forRenderer(renderer)
 * val particles = cuda.sharedBuffer("particles", count * 16L) // a VulkanBuffer and a CUdeviceptr
 * val step = cuda.compile(source).function("step")
 * cuda.everyFrame("step") { stream -> step.launch(blocks, 256, stream, particles.pointer, count) }
 * ```
 *
 * [CudaVulkan.everyFrame] slots the kernel between frames with a shared timeline semaphore: frame
 * *k* signals when it is done, the kernel waits for that and signals in turn, and frame *k*+1 waits
 * for the kernel before it starts. Neither side waits on the CPU. [CudaVulkan.run] is the one-off
 * version, and it blocks until the work is back on the Vulkan side.
 *
 * Everything is keyed so a rerun replaces it: [CudaVulkan.sharedBuffer] by name and size,
 * [CudaVulkan.compile] by source text, [CudaVulkan.everyFrame] by name. The session closes with the
 * renderer. Linux with an NVIDIA GPU only; elsewhere [CudaVulkan.forRenderer] throws with the reason.
 * The binding underneath is [CudaDriver]; the guide is `public/docs/cuda.md`.
 */

package afield.cuda

import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.Target
import afield.graphics.vulkan.buffers.VulkanBuffer
import afield.graphics.vulkan.device.CommandBuffer
import afield.graphics.vulkan.device.ExportedTimelineSemaphore
import afield.graphics.vulkan.device.Fence
import org.lwjgl.vulkan.VK13.*
import java.util.concurrent.ConcurrentHashMap

/** A CUDA context, stream and shared timeline for one renderer. Get it with [forRenderer]. */
class CudaVulkan private constructor(val renderer: Renderer) : AutoCloseable {
    /** The CUDA device behind the renderer's Vulkan device, matched by UUID. */
    val device: CudaDriver.CudaDevice
    /** The device's primary context, shared with the CUDA runtime and the libraries built on it. */
    val context: CudaDriver.CudaContext
    /** The stream every [run] and [everyFrame] call enqueues on. */
    val stream: CudaDriver.CudaStream
    // Two timelines, because a timeline's signals must be submitted in increasing order: frame
    // signals are submitted after the frame is recorded, so a one-off [run] in between would
    // otherwise signal past them.
    private val frameTimeline = ExportedTimelineSemaphore(renderer)
    private val runTimeline = ExportedTimelineSemaphore(renderer)
    private val cudaFrameTimeline: CudaDriver.CudaExternalSemaphore
    private val cudaRunTimeline: CudaDriver.CudaExternalSemaphore
    private val frameLock = Any()
    private val runLock = Any()
    /** The last value either side has been asked to signal on each timeline. */
    private var frameValue = 0L
    private var runValue = 0L
    private val buffers = ConcurrentHashMap<String, SharedBuffer>()
    private val modules = ConcurrentHashMap<String, CudaDriver.CudaModule>()
    @Volatile private var closed = false

    init {
        val driver = CudaDriver.loaded.getOrThrow()
        val uuid = ExportedTimelineSemaphore.deviceUuid(renderer)
        device = driver.deviceByUuid(uuid) ?: kotlin.run {
            frameTimeline.close(); runTimeline.close()
            error("No CUDA device has this renderer's Vulkan device UUID; CUDA sees ${driver.devices}")
        }
        context = device.primaryContext()
        stream = context.stream()
        cudaFrameTimeline = context.importSemaphore(frameTimeline.exportFd(), timeline = true)
        cudaRunTimeline = context.importSemaphore(runTimeline.exportFd(), timeline = true)
    }

    /**
     * A device-local Vulkan buffer of [bytes] that CUDA reads and writes at [SharedBuffer.pointer].
     * The same [name] returns the same buffer until [bytes] changes, so rerunning a box keeps its
     * contents. Pass [SharedBuffer.buffer] to a shader as you would any [VulkanBuffer].
     */
    fun sharedBuffer(name: String, bytes: Long): SharedBuffer {
        check(!closed) { "This CUDA session closed with its renderer" }
        buffers[name]?.let { if (it.bytes == bytes) return it; it.close(); buffers.remove(name) }
        val buffer = VulkanBuffer(renderer, bytes,
            VK_BUFFER_USAGE_STORAGE_BUFFER_BIT or VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT or VK_BUFFER_USAGE_VERTEX_BUFFER_BIT or
                VK_BUFFER_USAGE_TRANSFER_SRC_BIT or VK_BUFFER_USAGE_TRANSFER_DST_BIT,
            VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, needsDeviceAddress = true, exportOpaqueFd = true)
        val fd = buffer.exportOpaqueFd()
        val memory = try { context.importMemory(fd, buffer.exportedAllocationSize, dedicated = true) }
            catch (e: Throwable) { closeFd(fd); buffer.cleanup(); throw e }
        return SharedBuffer(name, bytes, buffer, memory).also { buffers[name] = it }
    }

    /**
     * Compile CUDA C [source] with NVRTC for this device and load it. Kernels must be declared
     * `extern "C"` to be found by name. The same source returns the loaded module without
     * recompiling. Without NVRTC, use [module] with PTX.
     */
    fun compile(source: String, options: List<String> = emptyList()): CudaDriver.CudaModule =
        modules.getOrPut("cu:" + options.joinToString(" ") + "\n" + source) {
            val nvrtc = Nvrtc.loaded.getOrThrow()
            context.module(nvrtc.compile(source, device.computeCapability, options = options))
        }

    /** Load PTX text (from `nvcc -ptx`, NVRTC elsewhere, or written by hand). Cached by text. */
    fun module(ptx: String): CudaDriver.CudaModule = modules.getOrPut("ptx:$ptx") { context.module(ptx) }

    /**
     * Run [work] on [stream] once, after the GPU work Vulkan has already submitted, and wait until
     * Vulkan can see its results. Returns the wall time in milliseconds. Use it to initialise
     * buffers before anything draws from them; frames submitted while it runs are not held back, so
     * for work that frames read use [everyFrame].
     */
    fun run(work: (CudaDriver.CudaStream) -> Unit): Double {
        check(!closed)
        val fence = Fence(renderer.device, false)
        val shared = buffers.values.map { it.buffer }
        fun record(acquire: Boolean) = CommandBuffer(renderer.commandPool, true, true).also {
            it.beginRecording()
            if (shared.isNotEmpty()) {
                if (acquire) ExportedTimelineSemaphore.acquireFromExternal(it, shared) else ExportedTimelineSemaphore.releaseToExternal(it, shared)
            }
            it.endRecording()
        }
        val release = record(false); val acquire = record(true)
        try {
            val t0 = System.nanoTime()
            synchronized(runLock) {
                val v = runValue
                runTimeline.submit(release, null, v + 1)
                cudaRunTimeline.wait(stream, v + 1); work(stream); cudaRunTimeline.signal(stream, v + 2)
                runTimeline.submit(acquire, v + 2, v + 3, fence)
                runValue = v + 3
            }
            fence.fenceWait()
            return (System.nanoTime() - t0) / 1e6
        } finally { fence.cleanup(); release.cleanup(); acquire.cleanup() }
    }

    /**
     * Run [work] on [stream] between every two frames: after the frame being recorded has finished
     * on the GPU, and before the next one starts. The same [name] replaces the previous task, so
     * rerunning a box swaps its kernel in place. [stopEveryFrame] removes it.
     */
    fun everyFrame(name: String, work: (CudaDriver.CudaStream) -> Unit) {
        check(!closed)
        val key = "cuda:$name"
        renderer.renderList.preTargets[key] = FrameTask(work)
    }

    /** Stop the [everyFrame] task called [name]. */
    fun stopEveryFrame(name: String) { renderer.renderList.preTargets.remove("cuda:$name") }

    /** Frames in which CUDA work was enqueued, across all [everyFrame] tasks. */
    @Volatile var framesWithCuda = 0L; private set

    private inner class FrameTask(val work: (CudaDriver.CudaStream) -> Unit) : Target {
        override fun render(command: CommandBuffer, index: Int) {
            if (closed) return
            synchronized(frameLock) {
                // Wait for the kernel enqueued last frame, signal when this frame is done, and
                // enqueue this frame's kernel behind that signal. Values only grow, so a frame with
                // nothing to wait for waits on a value already reached.
                renderer.externalGraphicsSync(command, frameTimeline.handle, frameValue, wait = true)
                val frameDone = frameValue + 1
                renderer.externalGraphicsSync(command, frameTimeline.handle, frameDone, wait = false)
                cudaFrameTimeline.wait(stream, frameDone)
                try { work(stream) } finally { cudaFrameTimeline.signal(stream, frameDone + 1) }
                frameValue = frameDone + 1
                framesWithCuda++
            }
        }
    }

    /** A Vulkan buffer and the CUDA pointer to the same memory. */
    class SharedBuffer internal constructor(val name: String, val bytes: Long, val buffer: VulkanBuffer, val memory: CudaDriver.CudaExternalMemory) : AutoCloseable {
        /** The `CUdeviceptr` to pass to a kernel. */
        val pointer: Long get() = memory.pointer
        override fun close() { memory.close(); buffer.cleanup() }
    }

    override fun close() {
        if (closed) return
        closed = true
        renderer.renderList.preTargets.keys.filter { it.startsWith("cuda:") }.forEach { renderer.renderList.preTargets.remove(it) }
        runCatching { stream.synchronize() }
        // Called from the renderer's teardown before it waits for the queue; the buffers are freed
        // on the spot once the renderer is closed, so let the last frame finish first.
        if (renderer.isClosed) runCatching { renderer.graphQueue.waitIdle() }
        buffers.values.forEach { runCatching { it.close() } }
        buffers.clear()
        modules.values.forEach { runCatching { it.close() } }
        runCatching { cudaFrameTimeline.close() }
        runCatching { cudaRunTimeline.close() }
        runCatching { stream.close() }
        runCatching { context.close() }
        frameTimeline.close(); runTimeline.close()
        sessions.remove(renderer, this)
    }

    companion object {
        private val sessions = ConcurrentHashMap<Renderer, CudaVulkan>()

        /**
         * The CUDA session for [renderer], created on first use and closed with the renderer.
         * Throws with the reason where there is no CUDA: macOS, no NVIDIA driver, or a renderer on
         * a GPU CUDA cannot see.
         */
        fun forRenderer(renderer: Renderer): CudaVulkan = sessions.computeIfAbsent(renderer) {
            check(!renderer.isClosed) { "Start the renderer before using CUDA with it" }
            check(renderer.device.supportsExternalMemoryFd && renderer.device.supportsExternalSemaphoreFd) {
                "This Vulkan device does not enable external memory and semaphore fds (Linux NVIDIA only)"
            }
            CudaDriver.loaded.getOrThrow()
            CudaVulkan(renderer).also { s -> renderer.beforeDeviceCleanup += Runnable { s.close() } }
        }

        private fun closeFd(fd: Int) = runCatching {
            val close = java.lang.foreign.Linker.nativeLinker().let { l ->
                l.downcallHandle(l.defaultLookup().find("close").orElseThrow(),
                    java.lang.foreign.FunctionDescriptor.of(java.lang.foreign.ValueLayout.JAVA_INT, java.lang.foreign.ValueLayout.JAVA_INT))
            }
            close.invokeWithArguments(fd)
        }
    }
}
