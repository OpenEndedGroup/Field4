package afield.media.video.diffusion

import afield.graphics.vulkan.Renderer
import afield.graphics.texture.UnixFdPass
import org.json.JSONArray
import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * The worker process behind a [StreamDiffusionView]: [StreamDiffusionSession] starts the
 * Python worker, shares its rings of GPU buffers and semaphores with it, and exchanges JSON control
 * messages.
 *
 * Pixels never pass through the protocol; the messages carry slot indices, semaphore values and
 * frame ids. `status`, `ready`, `capabilities` and `lastStats` report what the worker last said.
 */

/** Owns one isolated worker and its bounded GPU rings. Pixels never enter this protocol. */
class StreamDiffusionSession internal constructor(
    private val renderer: Renderer, val width: Int, val height: Int, val format: Int,
    val config: StreamDiffusionConfig, val motionEnabled: Boolean = false
) : AutoCloseable {
    // Float texels require a 16-byte-aligned copy offset, including odd identity-mode extents.
    internal val motionOffset = (width.toLong() * height * 4 + 15) and -16L
    internal val inputs = mutableListOf<SharedGpuSlot>()
    internal val outputs = mutableListOf<SharedGpuSlot>()
    private val inputFree = Array(6) { AtomicBoolean(true) }
    internal data class Result(val slot: Int, val value: Long, val frameId: Long, val timeNanos: Long, val generation: Long)
    internal val results = ConcurrentLinkedQueue<Result>()
    private val messages = ArrayBlockingQueue<JSONObject>(64)
    private val listening = CountDownLatch(1)
    private val imported = CountDownLatch(1)
    private val closing = AtomicBoolean(false)
    private val closeComplete = CountDownLatch(1)
    val isClosed: Boolean get() = closing.get()
    private val version = AtomicLong(1)
    val generation: Long get() = version.get()
    @Volatile var ready = false; private set
    @Volatile var status = "Starting worker"; private set
    @Volatile var capabilities = JSONObject(); private set
    @Volatile var lastStats = JSONObject(); private set

    /**
     * Whether a TensorRT engine actually executed, as the worker reports it; null until it says.
     * Asking for TensorRT ([StreamDiffusionConfig.useTensorRT]) does not make it active, so when
     * it was asked for and is not, [status] says so.
     */
    val tensorRtActive: Boolean? get() = capabilities.opt("tensorrt_active") as? Boolean

    private fun noteTensorRt() {
        if (config.useTensorRT && tensorRtActive == false && "TensorRT" !in status) {
            status = "$status — TensorRT requested but not active; running without it"
        }
    }
    @Volatile private var process: Process? = null
    private val sessionDir = run {
        val support = StreamDiffusionSupport.probe(renderer, config)
        check(support.available) { support.reason }
        Files.createTempDirectory("field-diffusion-",
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
    }
    val logFile = sessionDir.resolve("worker.log").toFile()
    private val shutdown = Runnable { close() }
    private val setupThread = Thread({ startWorker() }, "field-diffusion-setup").apply { isDaemon = true }

    init {
        check(System.getProperty("os.name").lowercase().contains("linux")) { "Local GPU diffusion currently requires Linux/NVIDIA" }
        require(width > 0 && height > 0)
        check(config.python.canExecute() && config.worker.isFile)
        check(UnixFdPass.available) { "Build native/field-fdpass first" }
        try {
            repeat(inputFree.size) { inputs += SharedGpuSlot(renderer, if (motionEnabled) motionOffset + width.toLong() * height * 16 else width.toLong() * height * 4) }
            repeat(8) { outputs += SharedGpuSlot(renderer, width.toLong() * height * 4) }
        } catch (e: Throwable) { (inputs + outputs).forEach { it.close() }; throw e }
        renderer.beforeDeviceCleanup += shutdown
        setupThread.start()
    }

    private fun startWorker() {
        val fds = mutableListOf<Int>()
        try {
            val socket = sessionDir.resolve("gpu.sock").toString()
            synchronized(this) {
                if (closing.get()) return
                process = ProcessBuilder(config.python.absolutePath, "-u", config.worker.absolutePath,
                    "--root", config.root.absolutePath, "--socket", socket)
                    .redirectError(logFile).start()
            }
            val child = checkNotNull(process)
            Thread({
                try {
                    child.inputStream.bufferedReader().useLines { lines -> lines.forEach { onEvent(JSONObject(it)) } }
                    if (!closing.get()) fail("Worker exited (${child.waitFor()}); see $logFile")
                } catch (e: Exception) { if (!closing.get()) fail("Worker protocol: ${e.message}; see $logFile") }
            }, "field-diffusion-events").apply { isDaemon = true; start() }
            Thread({
                try {
                    child.outputStream.bufferedWriter().use { writer ->
                        while (!closing.get()) {
                            val message = messages.poll(200, TimeUnit.MILLISECONDS) ?: continue
                            writer.write(message.toString()); writer.newLine(); writer.flush()
                        }
                    }
                } catch (e: Exception) { if (!closing.get()) fail("Worker write: ${e.message}") }
            }, "field-diffusion-control").apply { isDaemon = true; start() }
            check(listening.await(15, TimeUnit.SECONDS) && !closing.get()) { "Worker did not open its FD socket" }
            val all = inputs + outputs
            val descriptors = JSONArray()
            all.forEachIndexed { slotIndex, slot ->
                check(!closing.get()) { "Session closed during descriptor export" }
                fds += slot.exportFds().toList()
                descriptors.put(JSONObject().put("size", slot.allocationSize).put("bytes", slot.bytes).put("dedicated", true)
                    .put("motion", motionEnabled && slotIndex < inputs.size))
            }
            enqueue(JSONObject().put("op", "open").put("schema", 1).put("width", width).put("height", height)
                .put("format", format).put("device_uuid", SharedGpuSlot.deviceUuid(renderer))
                .put("inputs", inputs.size).put("slots", descriptors).put("generation", generation)
                .put("settings", config.settings()))
            check(UnixFdPass.sendFds(socket, fds.toIntArray())) { "SCM_RIGHTS handoff failed" }
            check(imported.await(config.startupTimeoutSeconds, TimeUnit.SECONDS) && ready) { "Worker initialization failed: $status" }
        } catch (e: Exception) {
            if (!closing.get()) { fail("${e.message}; see $logFile"); close() }
        } finally { fds.forEach { UnixFdPass.closeFd(it) } }
    }

    private fun onEvent(event: JSONObject) {
        if (isClosed) return
        when (event.getString("event")) {
            "listening" -> listening.countDown()
            "status" -> status = event.getString("message")
            "ready" -> { capabilities = event; status = "Ready"; noteTensorRt(); ready = true; imported.countDown() }
            "input_free" -> {
                val i = event.getInt("slot")
                check(i in inputs.indices && event.getLong("value") == inputs[i].value + 1)
                inputFree[i].set(true)
            }
            "output" -> {
                capabilities = event
                val r = Result(event.getInt("slot"), event.getLong("value"), event.getLong("frame_id"),
                    event.getLong("time_nanos"), event.getLong("generation"))
                check(r.slot in outputs.indices && results.size < outputs.size)
                results.add(r)
            }
            "reset" -> { capabilities = event; status = "Ready (generation ${event.getLong("generation")})"; noteTensorRt() }
            "stats" -> { lastStats = event; capabilities = event }
            "error" -> { fail(event.getString("message")); imported.countDown(); listening.countDown() }
        }
    }
    private fun fail(message: String) { ready = false; status = message }
    internal fun enqueue(message: JSONObject) {
        check(!closing.get() && messages.offer(message)) { "Diffusion worker stopped or control queue full" }
    }
    internal fun reserveInput(): Int = inputFree.indexOfFirst { it.compareAndSet(true, false) }
    internal fun frame(slot: Int, value: Long, id: Long, time: Long, generation: Long, motionReference: Long = -1) {
        if (!closing.get()) enqueue(JSONObject().put("op", "frame").put("slot", slot).put("value", value)
            .put("frame_id", id).put("time_nanos", time).put("generation", generation)
            .put("motion_reference_id", motionReference))
    }
    internal fun outputFree(slot: Int, value: Long) {
        if (!closing.get()) enqueue(JSONObject().put("op", "output_free").put("slot", slot).put("value", value))
    }
    /** Reset prompt/strength/seed at a causal boundary; old outputs are discarded by the view. */
    fun reset(prompt: String = config.prompt, strength: Double = config.strength, seed: Int = config.seed, noiseAdvection: Double = config.noiseAdvection) {
        check(ready && !isClosed) { "Wait for the diffusion worker to become ready before resetting" }
        val settings = config.copy(prompt = prompt, strength = strength, seed = seed, noiseAdvection = noiseAdvection).settings()
        enqueue(JSONObject().put("op", "reset").put("generation", version.incrementAndGet()).put("settings", settings))
    }

    override fun close() {
        if (!closing.compareAndSet(false, true)) {
            if (Thread.currentThread() !== setupThread) closeComplete.await()
            return
        }
        try {
            ready = false
            listening.countDown(); imported.countDown()
            synchronized(this) { process }?.let { child ->
                child.destroy()
                if (!child.waitFor(3, TimeUnit.SECONDS)) { child.destroyForcibly(); child.waitFor() }
            }
            if (Thread.currentThread() !== setupThread) setupThread.join()
            // Process exit retires CUDA mappings before Vulkan allocations can be destroyed.
            renderer.beforeDeviceCleanup.remove(shutdown)
            renderer.cleanup += Runnable { (inputs + outputs).forEach { it.close() } }
        } finally {
            closeComplete.countDown()
        }
    }
}
