@file:afield.runtime.completion.CompletionCategory("Animation")

package afield.animation.mocap

import afield.runtime.lifecycle.ReleasePolicy
import afield.runtime.lifecycle.RunScope
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

data class MotionProcessConfig(
    val backend: String = "closd",
    val home: Path = Path.of(System.getenv("FIELD_MOTION_HOME") ?: "${System.getProperty("user.home")}/.field4/motion"),
    val python: Path = home.resolve("environments/$backend/bin/python"),
    val source: Path = home.resolve(if (backend == "closd") "upstream/CLoSD" else "upstream/ardy"),
    val worker: Path = Path.of("optional/motion/worker.py").toAbsolutePath(),
    val overrides: List<String> = emptyList(),
    val startupTimeoutMs: Int = 180_000,
    val requestTimeoutMs: Int = 120_000,
    val offlineAssets: Boolean = true,
    val embeddingCache: Path = home.resolve("embeddings"),
    val encoderUrl: String = System.getenv("FIELD_MOTION_ENCODER_URL") ?: "http://127.0.0.1:9550/",
    val seed: Int = 0,
    val denoisingSteps: Int = 4,
) {
    init { require(backend in setOf("closd", "ardy") && startupTimeoutMs > 0 && requestTimeoutMs > 0 && denoisingSteps > 0) }
}

/** Starts a process, authenticates its capability/skeleton reply, and owns it through box stop/re-run. */
object CLoSD {
    fun openAsync(config: MotionProcessConfig = MotionProcessConfig()): CompletableFuture<MotionSession> {
        require(config.backend == "closd")
        return openMotionProcess(config)
    }
}

object ARDY {
    fun openAsync(config: MotionProcessConfig = MotionProcessConfig(backend = "ardy")): CompletableFuture<MotionSession> {
        require(config.backend == "ardy")
        return openMotionProcess(config)
    }
}

private fun openMotionProcess(config: MotionProcessConfig): CompletableFuture<MotionSession> {
    val process = AtomicReference<Process?>()
    val socket = AtomicReference<Socket?>()
    val session = AtomicReference<MotionSession?>()
    val cancelled = AtomicBoolean()
    val future = CompletableFuture<MotionSession>()
    fun cancel() {
        cancelled.set(true); session.get()?.close()
        runCatching { socket.get()?.close() }
        process.get()?.destroy()
        future.cancel(false)
    }
    RunScope.current()?.own(AutoCloseable { cancel() }, ReleasePolicy.RUNNING)
    Thread({
        try {
            check(!cancelled.get()) { "Motion opening cancelled" }
            val backend = MotionProcessBackend(config, process, socket, cancelled)
            val opened = MotionSession(backend)
            session.set(opened)
            if (cancelled.get() || !future.complete(opened)) opened.close()
        } catch (e: Throwable) {
            runCatching { socket.get()?.close() }
            process.get()?.let { p -> p.destroy(); if (!p.waitFor(2, TimeUnit.SECONDS)) p.destroyForcibly() }
            future.completeExceptionally(e)
        }
    }, "field-${config.backend}-open").apply { isDaemon = true; start() }
    future.whenComplete { _, _ -> if (future.isCancelled) cancel() }
    return future
}

private class MotionProcessBackend(private val config: MotionProcessConfig, processRef: AtomicReference<Process?>,
    socketRef: AtomicReference<Socket?>, cancelled: AtomicBoolean) : MotionBackend {
    private val token = UUID.randomUUID().toString()
    private val log = Files.createTempFile("field-${config.backend}-", ".log")
    private val process: Process
    private val socket: Socket
    private val input: DataInputStream
    private val output: DataOutputStream
    private var sequence = 0L
    private val closed = AtomicBoolean()
    override val skeleton: MotionSkeleton
    override val capabilities: MotionCapabilities
    init {
        require(Files.isExecutable(config.python) && Files.isRegularFile(config.worker)) {
            "Missing ${config.backend} interpreter/worker. See optional/motion/README.md and set FIELD_MOTION_HOME."
        }
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 250
            val command = listOf(config.python.toAbsolutePath().toString(), config.worker.toAbsolutePath().toString(),
                "--backend", config.backend, "--source", config.source.toAbsolutePath().toString(), "--connect", server.localPort.toString(),
                "--embedding-cache", config.embeddingCache.toAbsolutePath().toString(), "--encoder-url", config.encoderUrl,
                "--seed", config.seed.toString(), "--steps", config.denoisingSteps.toString()) +
                config.overrides.flatMap { listOf("--override=$it") }
            process = ProcessBuilder(command).directory(config.source.toFile()).redirectErrorStream(true)
                .redirectOutput(log.toFile()).apply {
                    environment()["FIELD_MOTION_TOKEN"] = token
                    // Managed Python 3.8 has its shared library next to the real interpreter, not the venv.
                    val lib = config.python.toRealPath().parent.parent.resolve("lib").toString()
                    environment()["LD_LIBRARY_PATH"] = lib + (environment()["LD_LIBRARY_PATH"]?.let { ":$it" } ?: "")
                    if (config.offlineAssets) { environment()["HF_HUB_OFFLINE"] = "1"; environment()["TRANSFORMERS_OFFLINE"] = "1" }
                }.start()
            processRef.set(process)
            if (cancelled.get()) { process.destroyForcibly(); error("Motion opening cancelled") }
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.startupTimeoutMs.toLong())
            var connected: Socket? = null
            while (connected == null) {
                check(!cancelled.get() && process.isAlive) { "${config.backend} exited/cancelled before connection: $log\n${tail()}" }
                if (System.nanoTime() > deadline) throw SocketTimeoutException("${config.backend} connection timeout: $log")
                try { connected = server.accept() } catch (_: SocketTimeoutException) { }
            }
            socket = connected; socketRef.set(socket)
        }
        socket.soTimeout = config.startupTimeoutMs
        input = DataInputStream(socket.getInputStream())
        output = DataOutputStream(socket.getOutputStream())
        val hello = call("hello", JSONObject())
        require(hello.getInt("schema") == 1)
        skeleton = MotionSkeleton.fromJson(hello.getJSONObject("skeleton"))
        capabilities = MotionCapabilities.fromJson(hello.getJSONObject("capabilities"))
        require(capabilities.backend == config.backend)
        socket.soTimeout = config.requestTimeoutMs
    }
    private fun call(operation: String, body: JSONObject): JSONObject {
        check(!closed.get()) { "Motion process closed" }
        val identity = ++sequence
        val bytes = body.put("op", operation).put("id", identity).put("token", token).toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES)
        try {
            output.writeInt(bytes.size); output.write(bytes); output.flush()
            val size = input.readInt(); require(size in 2..MAX_BYTES)
            val response = ByteArray(size); input.readFully(response)
            val json = JSONObject(String(response, Charsets.UTF_8))
            require(json.getLong("id") == identity) { "Motion reply ID mismatch" }
            if (!json.getBoolean("ok")) throw IllegalArgumentException(json.getString("error"))
            return json.getJSONObject("result")
        } catch (e: Exception) {
            throw IllegalStateException("${config.backend} $operation failed: ${e.message}. Worker log: $log\n${tail()}", e)
        }
    }
    override fun command(command: MotionCommand): MotionApplied {
        val result = call("command", JSONObject().put("command", JSONObject().put("operation", command.operation.name)
            .put("revision", command.revision).put("requestedTime", command.requestedTime).put("payload", command.payload)))
        return MotionApplied(if (result.isNull("appliedTime")) null else result.getDouble("appliedTime"),
            if (result.isNull("frameIndex")) null else result.getLong("frameIndex"), result.getJSONObject("details"))
    }
    override fun advance(seconds: Double): MotionUpdate {
        val result = call("advance", JSONObject().put("seconds", seconds))
        val frames = result.getJSONArray("frames"); val events = result.getJSONArray("events")
        return MotionUpdate(List(frames.length()) { MotionFrame.fromJson(frames.getJSONObject(it)) },
            List(events.length()) { events.getJSONObject(it) })
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { socket.close() } // Unblocks an in-flight call; worker releases its simulator on EOF.
        if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroy()
        if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
    }
    private fun tail(): String = runCatching {
        Files.newByteChannel(log).use { channel ->
            channel.position((channel.size() - 4096).coerceAtLeast(0))
            val buffer = java.nio.ByteBuffer.allocate(4096); channel.read(buffer)
            String(buffer.array(), 0, buffer.position(), Charsets.UTF_8)
        }
    }.getOrDefault("")
    companion object { private const val MAX_BYTES = 4 * 1024 * 1024 }
}
