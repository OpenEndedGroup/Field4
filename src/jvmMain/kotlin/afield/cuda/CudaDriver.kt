@file:afield.runtime.completion.CompletionCategory("Internal")
/**
 * A small binding of NVIDIA's CUDA driver API (`libcuda.so`) over `java.lang.foreign`, for running
 * kernels on the GPU Field's Vulkan renderer draws with and sharing that renderer's memory without
 * copies.
 *
 * LWJGL's CUDA module stops at 3.3.6 and Field is on 3.4.x, so this binds the driver library
 * directly, the way `NgxNative` and the OIDN yardstick do. It covers about thirty entry points:
 * initialisation, device lookup by UUID (so the CUDA device is the one behind a Vulkan physical
 * device), the primary context, streams and events, PTX modules and kernel launch, device memory
 * for tests, and external memory and semaphore import, which is how a Vulkan buffer exported as an
 * opaque fd and a Vulkan timeline semaphore become a `CUdeviceptr` and a CUDA wait/signal.
 *
 * Optional everywhere: [CudaDriver.loaded] is a `Result` that fails with a reason on macOS, on a
 * machine without the NVIDIA driver, or with `FIELD_CUDA_DISABLE=1`. Nothing here needs the CUDA
 * toolkit; kernels arrive as PTX, either shipped as text or compiled at runtime by [Nvrtc].
 * Notes and measurements: `dev_docs/graphics/CUDA_DRIVER_FFM.md`.
 */

package afield.cuda

import java.lang.foreign.*
import java.lang.foreign.ValueLayout.*
import java.lang.invoke.MethodHandle
import java.nio.file.Path

/** A `CUresult` other than `CUDA_SUCCESS`, with the driver's name and description for it. */
class CudaException(val code: Int, val call: String, message: String) : RuntimeException("$call failed: $message ($code)")

/**
 * The loaded driver library and its entry points. Obtain it through [loaded]; every wrapper class
 * below holds a reference back to it.
 */
class CudaDriver private constructor(library: Path?) {
    private val lookup = if (library != null) SymbolLookup.libraryLookup(library, Arena.global())
        else SymbolLookup.libraryLookup("libcuda.so.1", Arena.global())
    private val linker = Linker.nativeLinker()

    /** First of [names] the library exports: the `_v2` entry points replaced the originals' ABI. */
    private fun h(names: List<String>, result: MemoryLayout?, vararg args: MemoryLayout): MethodHandle {
        val symbol = names.firstNotNullOfOrNull { lookup.find(it).orElse(null) }
            ?: throw UnsatisfiedLinkError("libcuda exports none of $names")
        return linker.downcallHandle(symbol, if (result == null) FunctionDescriptor.ofVoid(*args) else FunctionDescriptor.of(result, *args))
    }
    private fun h(name: String, vararg args: MemoryLayout) = h(listOf(name), JAVA_INT, *args)

    private val cuGetErrorName = h("cuGetErrorName", JAVA_INT, ADDRESS)
    private val cuGetErrorString = h("cuGetErrorString", JAVA_INT, ADDRESS)
    private val cuInit = h("cuInit", JAVA_INT)
    private val cuDriverGetVersion = h("cuDriverGetVersion", ADDRESS)
    private val cuDeviceGetCount = h("cuDeviceGetCount", ADDRESS)
    private val cuDeviceGet = h("cuDeviceGet", ADDRESS, JAVA_INT)
    private val cuDeviceGetName = h("cuDeviceGetName", ADDRESS, JAVA_INT, JAVA_INT)
    private val cuDeviceGetUuid = h(listOf("cuDeviceGetUuid_v2", "cuDeviceGetUuid"), JAVA_INT, ADDRESS, JAVA_INT)
    private val cuDeviceGetAttribute = h("cuDeviceGetAttribute", ADDRESS, JAVA_INT, JAVA_INT)
    private val cuDevicePrimaryCtxRetain = h("cuDevicePrimaryCtxRetain", ADDRESS, JAVA_INT)
    private val cuDevicePrimaryCtxRelease = h(listOf("cuDevicePrimaryCtxRelease_v2", "cuDevicePrimaryCtxRelease"), JAVA_INT, JAVA_INT)
    private val cuCtxPushCurrent = h(listOf("cuCtxPushCurrent_v2"), JAVA_INT, ADDRESS)
    private val cuCtxPopCurrent = h(listOf("cuCtxPopCurrent_v2"), JAVA_INT, ADDRESS)
    private val cuCtxSynchronize = h("cuCtxSynchronize")
    private val cuStreamCreate = h("cuStreamCreate", ADDRESS, JAVA_INT)
    private val cuStreamDestroy = h(listOf("cuStreamDestroy_v2"), JAVA_INT, ADDRESS)
    private val cuStreamSynchronize = h("cuStreamSynchronize", ADDRESS)
    private val cuEventCreate = h("cuEventCreate", ADDRESS, JAVA_INT)
    private val cuEventRecord = h("cuEventRecord", ADDRESS, ADDRESS)
    private val cuEventSynchronize = h("cuEventSynchronize", ADDRESS)
    private val cuEventElapsedTime = h(listOf("cuEventElapsedTime", "cuEventElapsedTime_v2"), JAVA_INT, ADDRESS, ADDRESS, ADDRESS)
    private val cuEventDestroy = h(listOf("cuEventDestroy_v2"), JAVA_INT, ADDRESS)
    private val cuModuleLoadDataEx = h("cuModuleLoadDataEx", ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS)
    private val cuModuleGetFunction = h("cuModuleGetFunction", ADDRESS, ADDRESS, ADDRESS)
    private val cuModuleUnload = h("cuModuleUnload", ADDRESS)
    private val cuLaunchKernel = h("cuLaunchKernel", ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS)
    private val cuMemAlloc = h(listOf("cuMemAlloc_v2"), JAVA_INT, ADDRESS, JAVA_LONG)
    private val cuMemFree = h(listOf("cuMemFree_v2"), JAVA_INT, JAVA_LONG)
    private val cuMemcpyDtoH = h(listOf("cuMemcpyDtoH_v2"), JAVA_INT, ADDRESS, JAVA_LONG, JAVA_LONG)
    private val cuMemcpyHtoD = h(listOf("cuMemcpyHtoD_v2"), JAVA_INT, JAVA_LONG, ADDRESS, JAVA_LONG)
    private val cuImportExternalMemory = h("cuImportExternalMemory", ADDRESS, ADDRESS)
    private val cuExternalMemoryGetMappedBuffer = h("cuExternalMemoryGetMappedBuffer", ADDRESS, ADDRESS, ADDRESS)
    private val cuDestroyExternalMemory = h("cuDestroyExternalMemory", ADDRESS)
    private val cuImportExternalSemaphore = h("cuImportExternalSemaphore", ADDRESS, ADDRESS)
    private val cuSignalExternalSemaphoresAsync = h("cuSignalExternalSemaphoresAsync", ADDRESS, ADDRESS, JAVA_INT, ADDRESS)
    private val cuWaitExternalSemaphoresAsync = h("cuWaitExternalSemaphoresAsync", ADDRESS, ADDRESS, JAVA_INT, ADDRESS)
    private val cuDestroyExternalSemaphore = h("cuDestroyExternalSemaphore", ADDRESS)

    /** Throw [CudaException] unless [code] is `CUDA_SUCCESS`. */
    fun ok(code: Any?, call: String) {
        val c = code as Int
        if (c == 0) return
        val text = Arena.ofConfined().use { a ->
            val name = a.allocate(ADDRESS); val desc = a.allocate(ADDRESS)
            fun str(p: MemorySegment) = p.get(ADDRESS, 0).let { if (it.address() == 0L) "?" else it.reinterpret(1024).getString(0) }
            val n = if (cuGetErrorName.invokeWithArguments(c, name) as Int == 0) str(name) else "CUresult"
            val d = if (cuGetErrorString.invokeWithArguments(c, desc) as Int == 0) str(desc) else ""
            "$n $d"
        }
        throw CudaException(c, call, text)
    }

    internal fun <T> out(layout: MemoryLayout, call: String, body: (MemorySegment) -> Any?, read: (MemorySegment) -> T): T =
        Arena.ofConfined().use { a -> val m = a.allocate(layout); ok(body(m), call); read(m) }

    init { ok(cuInit.invokeWithArguments(0), "cuInit") }

    /** The driver's CUDA version, e.g. 13000 for CUDA 13.0. */
    val driverVersion: Int = out(JAVA_INT, "cuDriverGetVersion", { cuDriverGetVersion.invokeWithArguments(it) }) { it.get(JAVA_INT, 0) }

    /** Every CUDA device, in driver ordinal order. */
    val devices: List<CudaDevice> by lazy {
        val count = out(JAVA_INT, "cuDeviceGetCount", { cuDeviceGetCount.invokeWithArguments(it) }) { it.get(JAVA_INT, 0) }
        (0 until count).map { ordinal ->
            val handle = out(JAVA_INT, "cuDeviceGet", { cuDeviceGet.invokeWithArguments(it, ordinal) }) { it.get(JAVA_INT, 0) }
            val name = Arena.ofConfined().use { a -> val m = a.allocate(256); ok(cuDeviceGetName.invokeWithArguments(m, 256, handle), "cuDeviceGetName"); m.getString(0) }
            val uuid = Arena.ofConfined().use { a -> val m = a.allocate(16); ok(cuDeviceGetUuid.invokeWithArguments(m, handle), "cuDeviceGetUuid"); m.toArray(JAVA_BYTE) }
            fun attr(id: Int) = out(JAVA_INT, "cuDeviceGetAttribute", { cuDeviceGetAttribute.invokeWithArguments(it, id, handle) }) { it.get(JAVA_INT, 0) }
            CudaDevice(this, ordinal, handle, name, uuid, attr(75) * 10 + attr(76))
        }
    }

    /** The CUDA device whose UUID is [uuid] — for Vulkan, `VkPhysicalDeviceIDProperties.deviceUUID`. */
    fun deviceByUuid(uuid: ByteArray): CudaDevice? = devices.firstOrNull { it.uuid.contentEquals(uuid) }

    /** One CUDA device. [computeCapability] is major × 10 + minor (89 for Ada). */
    class CudaDevice internal constructor(val driver: CudaDriver, val ordinal: Int, internal val handle: Int, val name: String,
                                          val uuid: ByteArray, val computeCapability: Int) {
        /** The UUID in the 8-4-4-4-12 form `nvidia-smi` prints after `GPU-`. */
        val uuidString: String get() = uuid.joinToString("") { "%02x".format(it.toInt() and 255) }
            .let { "${it.substring(0, 8)}-${it.substring(8, 12)}-${it.substring(12, 16)}-${it.substring(16, 20)}-${it.substring(20)}" }

        /** Retain this device's primary context, the one the CUDA runtime (and so OIDN, cuBLAS, ...) also uses. */
        fun primaryContext(): CudaContext {
            val ctx = driver.out(ADDRESS, "cuDevicePrimaryCtxRetain", { driver.cuDevicePrimaryCtxRetain.invokeWithArguments(it, handle) }) { it.get(ADDRESS, 0) }
            return CudaContext(this, ctx)
        }
        override fun toString() = "CudaDevice($ordinal, $name, sm_$computeCapability, GPU-$uuidString)"
    }

    /**
     * A retained primary context. Every method makes it current on the calling thread for its
     * duration (push/pop), so it can be used from any thread, Field's render thread included.
     */
    class CudaContext internal constructor(val device: CudaDevice, internal val handle: MemorySegment) : AutoCloseable {
        private val d = device.driver
        private var closed = false

        /** Run [body] with this context current on this thread. */
        fun <T> current(body: () -> T): T {
            d.ok(d.cuCtxPushCurrent.invokeWithArguments(handle), "cuCtxPushCurrent")
            try { return body() } finally {
                Arena.ofConfined().use { a -> d.ok(d.cuCtxPopCurrent.invokeWithArguments(a.allocate(ADDRESS)), "cuCtxPopCurrent") }
            }
        }

        fun synchronize() = current { d.ok(d.cuCtxSynchronize.invokeWithArguments(), "cuCtxSynchronize") }

        /** A non-blocking stream (flag `CU_STREAM_NON_BLOCKING`), which does not serialise with the legacy default stream. */
        fun stream(): CudaStream = current {
            CudaStream(this, d.out(ADDRESS, "cuStreamCreate", { d.cuStreamCreate.invokeWithArguments(it, 1) }) { it.get(ADDRESS, 0) })
        }

        fun event(): CudaEvent = current {
            CudaEvent(this, d.out(ADDRESS, "cuEventCreate", { d.cuEventCreate.invokeWithArguments(it, 0) }) { it.get(ADDRESS, 0) })
        }

        /**
         * Load a module from PTX text (or a cubin/fatbin). A PTX error raises a [CudaException]
         * carrying the JIT's error log, which names the offending line.
         */
        fun module(ptx: String): CudaModule = current {
            Arena.ofConfined().use { a ->
                val image = a.allocateFrom(ptx)
                val logSize = 8192
                val log = a.allocate(logSize.toLong())
                // CU_JIT_ERROR_LOG_BUFFER = 5, CU_JIT_ERROR_LOG_BUFFER_SIZE_BYTES = 6.
                val options = a.allocateFrom(JAVA_INT, 5, 6)
                val values = a.allocate(ADDRESS, 2)
                values.setAtIndex(ADDRESS, 0, log)
                values.setAtIndex(ADDRESS, 1, MemorySegment.ofAddress(logSize.toLong()))
                val out = a.allocate(ADDRESS)
                val code = d.cuModuleLoadDataEx.invokeWithArguments(out, image, 2, options, values) as Int
                if (code != 0) {
                    val jit = log.getString(0).trim()
                    try { d.ok(code, "cuModuleLoadDataEx") } catch (e: CudaException) {
                        throw CudaException(e.code, "cuModuleLoadDataEx", e.message + if (jit.isEmpty()) "" else "\n$jit")
                    }
                }
                CudaModule(this, out.get(ADDRESS, 0))
            }
        }

        /** Device memory of [bytes]; for tests and staging, since shared memory comes from [importMemory]. */
        fun allocate(bytes: Long): CudaMemory = current {
            CudaMemory(this, d.out(JAVA_LONG, "cuMemAlloc", { d.cuMemAlloc.invokeWithArguments(it, bytes) }) { it.get(JAVA_LONG, 0) }, bytes)
        }

        /** Blocking copy of [bytes] from device pointer [src] to host. */
        fun copyToHost(src: Long, bytes: Long): MemorySegment = current {
            val host = Arena.ofAuto().allocate(bytes, 16)
            d.ok(d.cuMemcpyDtoH.invokeWithArguments(host, src, bytes), "cuMemcpyDtoH"); host
        }

        fun copyToDevice(dst: Long, host: MemorySegment) = current {
            d.ok(d.cuMemcpyHtoD.invokeWithArguments(dst, host, host.byteSize()), "cuMemcpyHtoD")
        }

        /**
         * Import memory exported by Vulkan as an opaque fd and map all of it as one device pointer.
         *
         * On success the driver owns [fd]; on failure the caller still does. [size] must be the
         * exported allocation's exact size (`vkGetBufferMemoryRequirements`, not the buffer's
         * requested size), and [dedicated] must say whether Vulkan allocated it with
         * `VkMemoryDedicatedAllocateInfo`: the driver rejects a mismatch with
         * `CUDA_ERROR_INVALID_VALUE` / `CUDA_ERROR_UNKNOWN`.
         */
        fun importMemory(fd: Int, size: Long, dedicated: Boolean): CudaExternalMemory = current {
            Arena.ofConfined().use { a ->
                // CUDA_EXTERNAL_MEMORY_HANDLE_DESC: type@0, handle union@8 (16 bytes), size@24, flags@32, reserved[16]; 104 bytes.
                val desc = a.allocate(104, 8)
                desc.set(JAVA_INT, 0, 1) // CU_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD
                desc.set(JAVA_INT, 8, fd)
                desc.set(JAVA_LONG, 24, size)
                desc.set(JAVA_INT, 32, if (dedicated) 1 else 0) // CUDA_EXTERNAL_MEMORY_DEDICATED
                val ext = d.out(ADDRESS, "cuImportExternalMemory", { d.cuImportExternalMemory.invokeWithArguments(it, desc) }) { it.get(ADDRESS, 0) }
                // CUDA_EXTERNAL_MEMORY_BUFFER_DESC: offset@0, size@8, flags@16, reserved[16]; 88 bytes.
                val bd = a.allocate(88, 8)
                bd.set(JAVA_LONG, 0, 0L); bd.set(JAVA_LONG, 8, size)
                val ptr = try {
                    d.out(JAVA_LONG, "cuExternalMemoryGetMappedBuffer", { d.cuExternalMemoryGetMappedBuffer.invokeWithArguments(it, ext, bd) }) { it.get(JAVA_LONG, 0) }
                } catch (e: Throwable) { d.cuDestroyExternalMemory.invokeWithArguments(ext); throw e }
                CudaExternalMemory(this, ext, ptr, size)
            }
        }

        /**
         * Import a semaphore exported by Vulkan as an opaque fd. [timeline] selects
         * `CU_EXTERNAL_SEMAPHORE_HANDLE_TYPE_TIMELINE_SEMAPHORE_FD`; a binary semaphore is
         * `..._OPAQUE_FD`. On success the driver owns [fd].
         */
        fun importSemaphore(fd: Int, timeline: Boolean = true): CudaExternalSemaphore = current {
            Arena.ofConfined().use { a ->
                // CUDA_EXTERNAL_SEMAPHORE_HANDLE_DESC: type@0, handle union@8 (16 bytes), flags@24, reserved[16]; 96 bytes.
                val desc = a.allocate(96, 8)
                desc.set(JAVA_INT, 0, if (timeline) 9 else 1)
                desc.set(JAVA_INT, 8, fd)
                CudaExternalSemaphore(this, d.out(ADDRESS, "cuImportExternalSemaphore", { d.cuImportExternalSemaphore.invokeWithArguments(it, desc) }) { it.get(ADDRESS, 0) })
            }
        }

        override fun close() {
            if (closed) return; closed = true
            d.ok(d.cuDevicePrimaryCtxRelease.invokeWithArguments(device.handle), "cuDevicePrimaryCtxRelease")
        }
    }

    class CudaStream internal constructor(val context: CudaContext, val handle: MemorySegment) : AutoCloseable {
        private val d = context.device.driver
        fun synchronize() = context.current { d.ok(d.cuStreamSynchronize.invokeWithArguments(handle), "cuStreamSynchronize") }
        override fun close() = context.current { d.ok(d.cuStreamDestroy.invokeWithArguments(handle), "cuStreamDestroy") }
    }

    /** A timing event. [elapsedMs] needs both events recorded and the later one complete. */
    class CudaEvent internal constructor(val context: CudaContext, val handle: MemorySegment) : AutoCloseable {
        private val d = context.device.driver
        fun record(stream: CudaStream?) = context.current {
            d.ok(d.cuEventRecord.invokeWithArguments(handle, stream?.handle ?: MemorySegment.NULL), "cuEventRecord")
        }
        fun synchronize() = context.current { d.ok(d.cuEventSynchronize.invokeWithArguments(handle), "cuEventSynchronize") }
        fun elapsedMs(since: CudaEvent): Float = context.current {
            d.out(JAVA_FLOAT, "cuEventElapsedTime", { d.cuEventElapsedTime.invokeWithArguments(it, since.handle, handle) }) { it.get(JAVA_FLOAT, 0) }
        }
        override fun close() = context.current { d.ok(d.cuEventDestroy.invokeWithArguments(handle), "cuEventDestroy") }
    }

    class CudaModule internal constructor(val context: CudaContext, val handle: MemorySegment) : AutoCloseable {
        private val d = context.device.driver
        fun function(name: String): CudaFunction = context.current {
            Arena.ofConfined().use { a ->
                CudaFunction(this, name, d.out(ADDRESS, "cuModuleGetFunction($name)", { d.cuModuleGetFunction.invokeWithArguments(it, handle, a.allocateFrom(name)) }) { it.get(ADDRESS, 0) })
            }
        }
        override fun close() = context.current { d.ok(d.cuModuleUnload.invokeWithArguments(handle), "cuModuleUnload") }
    }

    /**
     * A kernel. [launch] takes its arguments in declaration order as [Int], [Float], [Long]
     * (a `CUdeviceptr` or 64-bit integer) or [Double], which must match the kernel's parameter types.
     */
    class CudaFunction internal constructor(val module: CudaModule, val name: String, val handle: MemorySegment) {
        private val d = module.context.device.driver
        fun launch(grid: Triple<Int, Int, Int>, block: Triple<Int, Int, Int>, stream: CudaStream?, vararg args: Any, sharedBytes: Int = 0) =
            module.context.current {
                Arena.ofConfined().use { a ->
                    val params = a.allocate(ADDRESS, args.size.toLong().coerceAtLeast(1))
                    args.forEachIndexed { i, v ->
                        val slot = when (v) {
                            is Int -> a.allocateFrom(JAVA_INT, v)
                            is Float -> a.allocateFrom(JAVA_FLOAT, v)
                            is Long -> a.allocateFrom(JAVA_LONG, v)
                            is Double -> a.allocateFrom(JAVA_DOUBLE, v)
                            else -> throw IllegalArgumentException("Kernel argument $i of $name: ${v::class.simpleName} is not Int, Float, Long or Double")
                        }
                        params.setAtIndex(ADDRESS, i.toLong(), slot)
                    }
                    d.ok(d.cuLaunchKernel.invokeWithArguments(handle, grid.first, grid.second, grid.third, block.first, block.second, block.third,
                        sharedBytes, stream?.handle ?: MemorySegment.NULL, params, MemorySegment.NULL), "cuLaunchKernel($name)")
                }
            }
    }

    class CudaMemory internal constructor(val context: CudaContext, val pointer: Long, val bytes: Long) : AutoCloseable {
        override fun close() = context.current { context.device.driver.let { it.ok(it.cuMemFree.invokeWithArguments(pointer), "cuMemFree") } }
    }

    /** Vulkan memory seen from CUDA: [pointer] addresses all [size] bytes of it. Close before Vulkan frees the memory. */
    class CudaExternalMemory internal constructor(val context: CudaContext, private val handle: MemorySegment, val pointer: Long, val size: Long) : AutoCloseable {
        private var closed = false
        override fun close() {
            if (closed) return; closed = true
            val d = context.device.driver
            context.current {
                d.ok(d.cuMemFree.invokeWithArguments(pointer), "cuMemFree(mapped external buffer)")
                d.ok(d.cuDestroyExternalMemory.invokeWithArguments(handle), "cuDestroyExternalMemory")
            }
        }
    }

    /** A Vulkan semaphore seen from CUDA. For a timeline, [signal] and [wait] take the counter value. */
    class CudaExternalSemaphore internal constructor(val context: CudaContext, private val handle: MemorySegment) : AutoCloseable {
        private val d = context.device.driver
        // CUDA_EXTERNAL_SEMAPHORE_{SIGNAL,WAIT}_PARAMS: params.fence.value@0, flags@72; 144 bytes.
        private fun params(a: Arena, value: Long) = a.allocate(144, 8).also { it.set(JAVA_LONG, 0, value) }
        private fun handles(a: Arena) = a.allocate(ADDRESS).also { it.set(ADDRESS, 0, handle) }

        /** Enqueue on [stream]: the semaphore reaches [value] once prior work on the stream completes. */
        fun signal(stream: CudaStream?, value: Long) = context.current {
            Arena.ofConfined().use { a ->
                d.ok(d.cuSignalExternalSemaphoresAsync.invokeWithArguments(handles(a), params(a, value), 1, stream?.handle ?: MemorySegment.NULL), "cuSignalExternalSemaphoresAsync")
            }
        }

        /** Enqueue on [stream]: later work waits for the semaphore to reach [value]. */
        fun wait(stream: CudaStream?, value: Long) = context.current {
            Arena.ofConfined().use { a ->
                d.ok(d.cuWaitExternalSemaphoresAsync.invokeWithArguments(handles(a), params(a, value), 1, stream?.handle ?: MemorySegment.NULL), "cuWaitExternalSemaphoresAsync")
            }
        }

        override fun close() = context.current { d.ok(d.cuDestroyExternalSemaphore.invokeWithArguments(handle), "cuDestroyExternalSemaphore") }
    }

    companion object {
        /**
         * The driver, loaded on first use, or the reason it is not available. `FIELD_CUDA_LIBRARY`
         * names a specific `libcuda.so`; otherwise the loader's `libcuda.so.1` is used.
         */
        val loaded: Result<CudaDriver> by lazy {
            runCatching {
                check(System.getProperty("os.name").lowercase().contains("linux")) {
                    "Field binds CUDA on Linux only (libcuda.so); this is ${System.getProperty("os.name")}"
                }
                check(System.getenv("FIELD_CUDA_DISABLE") != "1" && !java.lang.Boolean.getBoolean("afield.cuda.disabled")) { "CUDA explicitly disabled" }
                val explicit = System.getenv("FIELD_CUDA_LIBRARY")?.let { Path.of(it) }
                try { CudaDriver(explicit) } catch (e: IllegalArgumentException) {
                    throw IllegalStateException("libcuda.so.1 not found: is the NVIDIA driver installed? (${e.message})", e)
                }
            }
        }
    }
}
