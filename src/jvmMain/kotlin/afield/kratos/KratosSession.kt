@file:afield.CompletionCategory("Simulation")
package afield.kratos

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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Optional environment; neither construction nor Field startup imports any native Kratos code. */
data class KratosConfig(
    val python: String = System.getenv("AFIELD_KRATOS_PYTHON") ?: "optional/kratos/.venv/bin/python",
    val worker: Path = Path.of(System.getenv("AFIELD_KRATOS_WORKER") ?: "optional/kratos/python/worker.py").toAbsolutePath(),
    val startupTimeoutMs: Int = 15_000,
    val requestTimeoutMs: Int = 60_000,
    val threads: Int = 2,
    val setupScript: Path? = null,
) {
    init { require(startupTimeoutMs > 0 && requestTimeoutMs > 0 && threads > 0) }
}

data class KratosCapability(val available: Boolean, val details: Map<String, Any?>, val message: String)

/** Cold-start probe is asynchronous, bounded, and closes its worker even when imports fail. */
object Kratos {
    fun probe(config: KratosConfig = KratosConfig()): CompletableFuture<KratosCapability> = CompletableFuture.supplyAsync {
        try {
            KratosConnection(config).use { connection ->
                val detail = connection.call("hello", 0, 0)
                KratosCapability(detail["available"] == true, detail, detail["installation"].toString())
            }
        } catch (e: Exception) {
            KratosCapability(false, emptyMap(), "Kratos unavailable: ${e.message}. Run uv sync --project optional/kratos and set AFIELD_KRATOS_PYTHON.")
        }
    }
}

internal class KratosConnection(private val config: KratosConfig) : AutoCloseable {
    val sessionId: String = UUID.randomUUID().toString()
    private val token = UUID.randomUUID().toString()
    private val requests = AtomicLong()
    var lastWriteMs: Double = 0.0; private set
    var lastReadMs: Double = 0.0; private set
    private val lock = ReentrantLock()
    private val directory = Files.createTempDirectory("field-kratos-")
    private val log = directory.resolve("worker.log")
    private var process: Process? = null
    private var socket: Socket? = null
    @Volatile private var closed = false
    private val hook = Thread({ close() }, "Kratos shutdown")

    init {
        try {
            require(Files.isRegularFile(config.worker)) { "Worker script missing: ${config.worker}" }
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                server.soTimeout = 100
                process = ProcessBuilder(config.python, "-u", config.worker.toString(), "--port", server.localPort.toString())
                    .redirectErrorStream(true).redirectOutput(log.toFile()).also {
                        it.environment()["FIELD_KRATOS_TOKEN"] = token
                        it.environment()["OMP_NUM_THREADS"] = config.threads.toString()
                        config.setupScript?.let { setup -> it.environment()["FIELD_KRATOS_SETUP"] = setup.toAbsolutePath().toString() }
                    }.start()
                val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.startupTimeoutMs.toLong())
                while (socket == null) {
                    check(process!!.isAlive) { "Worker exited during startup: ${logTail()}" }
                    if (System.nanoTime() > deadline) throw SocketTimeoutException("Kratos worker startup timed out")
                    try { socket = server.accept() } catch (_: SocketTimeoutException) { /* check process and deadline */ }
                }
                socket!!.soTimeout = config.requestTimeoutMs
                socket!!.tcpNoDelay = true
            }
            Runtime.getRuntime().addShutdownHook(hook)
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    private fun logTail(): String = if (Files.exists(log)) {
        Files.newByteChannel(log).use { channel ->
            channel.position((channel.size() - 4096).coerceAtLeast(0))
            val bytes = java.nio.ByteBuffer.allocate(4096)
            channel.read(bytes)
            String(bytes.array(), 0, bytes.position())
        }
    } else ""

    fun call(command: String, geometryRevision: Long, simulationRevision: Long, data: Map<String, Any?> = emptyMap()): Map<String, Any?> = lock.withLock {
        check(!closed) { "Kratos worker is closed; open a new session to restart" }
        val request = linkedMapOf<String, Any?>("schemaVersion" to KRATOS_SCHEMA, "units" to KRATOS_UNITS,
            "sessionId" to sessionId, "geometryRevision" to geometryRevision, "simulationRevision" to simulationRevision,
            "requestId" to requests.incrementAndGet(), "cancelled" to false, "command" to command, "token" to token)
        require(data.keys.none { it in request }) { "Payload cannot override protocol envelope" }
        request.putAll(data)
        val response = try {
            val writing = System.nanoTime()
            writeKratosFrame(DataOutputStream(socket!!.getOutputStream()), request)
            lastWriteMs = (System.nanoTime() - writing) / 1e6
            readKratosFrame(DataInputStream(socket!!.getInputStream())) { lastReadMs = it }
        } catch (e: Exception) {
            val detail = logTail()
            close()
            throw IllegalStateException("Kratos transport failed ($command): ${e.message}. $detail. Open a new session to restart.", e)
        }
        for (key in listOf("schemaVersion", "units", "sessionId", "geometryRevision", "simulationRevision", "requestId", "cancelled")) {
            val expected = request[key]
            val actual = response[key]
            val agrees = if (expected is Number) {
                (actual is Long || actual is Int) && expected.toLong() == actual.fieldInteger()
            } else expected == actual
            if (!agrees) { close(); error("Kratos protocol mismatch in $key: expected $expected, got $actual") }
        }
        if (response["ok"] != true) {
            val error = response["error"].fieldMap()
            throw IllegalArgumentException("Kratos ${error["type"]}: ${error["message"]}")
        }
        check(response["obsolete"] == false) { "Kratos marked result obsolete" }
        response["data"].fieldMap()
    }

    override fun close() {
        if (closed) return
        closed = true
        // On an idle connection, ask the worker to release its ModelPart and exit.
        // During a solve, close the socket and terminate only this owned process.
        if (lock.tryLock()) {
            try {
                runCatching {
                    socket?.let { s ->
                        s.soTimeout = 300
                        writeKratosFrame(DataOutputStream(s.getOutputStream()), mapOf(
                            "schemaVersion" to KRATOS_SCHEMA, "units" to KRATOS_UNITS, "sessionId" to sessionId,
                            "geometryRevision" to 0L, "simulationRevision" to 0L, "requestId" to requests.incrementAndGet(),
                            "cancelled" to false, "command" to "shutdown", "token" to token))
                        readKratosFrame(DataInputStream(s.getInputStream()))
                    }
                }
            } finally { lock.unlock() }
        }
        runCatching { socket?.close() }
        process?.let {
            it.destroy()
            if (!it.waitFor(500, TimeUnit.MILLISECONDS)) { it.destroyForcibly(); it.waitFor(500, TimeUnit.MILLISECONDS) }
        }
        runCatching { Runtime.getRuntime().removeShutdownHook(hook) }
        runCatching { Files.deleteIfExists(log); Files.deleteIfExists(directory) }
    }
}

/** User-visible orchestration states. Preview geometry itself remains ordinary Manifold/TriMesh data. */
enum class KratosState { GEOMETRY_PREVIEW, COARSE_PREVIEW, COMMITTED_SOLVE, READY, FAILED, CLOSED }
enum class SimulationEdit { GEOMETRY, MATERIAL, BOUNDARY, LOAD }
data class ThermalParameters(val conductivity: Double = 1.0, val source: Double = 1.0, val boundaryTemperature: Double = 0.0, val penalty: Double = 10.0,
                             val materialId: String = "body-material", val boundaryConditionId: String = "skin-temperature") {
    init { require(materialId.isNotBlank() && boundaryConditionId.isNotBlank()); require(listOf(conductivity, source, boundaryTemperature, penalty).all(Double::isFinite) && conductivity > 0 && penalty > 0) }
    internal fun wire(): Map<String, Any?> = mapOf("conductivity" to conductivity, "source" to source, "boundary_value" to boundaryTemperature, "penalty" to penalty)
}

data class SimulationRevision(val geometry: Long, val simulation: Long)

/**
 * One persistent Python process, one stable background ModelPart. All work runs off Field's runloop.
 * New requests immediately obsolete old ones; in-flight Kratos solves finish without publishing.
 * Read [latest] on the Field runloop. Never use an unguarded future callback to update a scene.
 */
class KratosSession private constructor(private val connection: KratosConnection, val domain: BackgroundDomain, val capabilities: Map<String, Any?>) : AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "Field Kratos").apply { isDaemon = true } }
    private val gate = Any()
    private val pending = ConcurrentHashMap.newKeySet<CompletableFuture<ThermalResult?>>()
    @Volatile var revision = SimulationRevision(0, 0); private set
    @Volatile var latest: ThermalResult? = null; private set
    @Volatile var state = KratosState.GEOMETRY_PREVIEW; private set
    @Volatile var error: String? = null; private set

    fun solveAsync(geometry: SimulationGeometry, thermal: ThermalParameters = ThermalParameters(),
                   edit: SimulationEdit = SimulationEdit.GEOMETRY, preview: Boolean = false,
                   dirtyBounds: List<SimulationBounds> = emptyList()): CompletableFuture<ThermalResult?> {
        val future = CompletableFuture<ThermalResult?>()
        val ticket: SimulationRevision
        synchronized(gate) {
            check(state != KratosState.CLOSED) { "Session is closed" }
            revision = SimulationRevision(revision.geometry + if (edit == SimulationEdit.GEOMETRY) 1 else 0, revision.simulation + 1)
            ticket = revision
            latest = null
            error = null
            state = if (preview) KratosState.COARSE_PREVIEW else KratosState.COMMITTED_SOLVE
            pending.add(future)
            executor.execute {
                try {
                    // Preview debounce coalesces rapid edits without occupying Field's runloop.
                    if (ticket != revision || future.isCancelled) { future.complete(null); return@execute }
                    if (preview) Thread.sleep(120)
                    if (ticket != revision || future.isCancelled) { future.complete(null); return@execute }
                    val start = System.nanoTime()
                    val geometryData = geometry.workerData(domain)
                    val geometryMs = (System.nanoTime() - start) / 1e6
                    val transferStart = System.nanoTime()
                    val payload = connection.call("solve", ticket.geometry, ticket.simulation,
                        geometryData + mapOf("thermal" to thermal.wire(), "regions" to mapOf("solid" to geometry.id, "volume" to "${geometry.id}:volume", "surface" to "${geometry.id}:skin", "material" to thermal.materialId, "boundaryCondition" to thermal.boundaryConditionId, "output" to "temperature"), "dirtyBounds" to dirtyBounds.map { it.wire() }, "editClass" to edit.name))
                    val elapsed = (System.nanoTime() - transferStart) / 1e6
                    val result = ThermalResult.decode(domain, ticket, payload, geometryMs, elapsed, connection.lastWriteMs, connection.lastReadMs)
                    synchronized(gate) {
                        if (ticket == revision && state != KratosState.CLOSED && !future.isCancelled) {
                            latest = result
                            state = KratosState.READY
                            future.complete(result)
                        } else future.complete(null)
                    }
                } catch (e: Exception) {
                    synchronized(gate) {
                        if (ticket == revision && state != KratosState.CLOSED) { error = e.message; state = KratosState.FAILED }
                    }
                    future.completeExceptionally(e)
                } finally { pending.remove(future) }
            }
        }
        return future
    }

    /** Invalidates in-flight and queued work immediately, without interrupting native solver code. */
    fun cancel() = synchronized(gate) {
        if (state != KratosState.CLOSED) {
            revision = revision.copy(simulation = revision.simulation + 1)
            latest = null
            state = KratosState.GEOMETRY_PREVIEW
        }
    }

    /** Guard publication at the moment Field draws, including after a future has already completed. */
    fun withLatest(draw: (ThermalResult) -> Unit) = synchronized(gate) { latest?.takeIf { it.revision == revision }?.let(draw) }

    override fun close() {
        synchronized(gate) {
            if (state == KratosState.CLOSED) return
            cancel()
            state = KratosState.CLOSED
            pending.forEach { it.completeExceptionally(IllegalStateException("Session closed")) }
            pending.clear()
        }
        connection.close()
        executor.shutdownNow()
    }

    companion object {
        fun openAsync(bounds: SimulationBounds, resolution: Int = 12, config: KratosConfig = KratosConfig()): CompletableFuture<KratosSession> {
            val opening = CompletableFuture<KratosSession>()
            CompletableFuture.runAsync {
                if (opening.isCancelled) return@runAsync
                var connection: KratosConnection? = null
                try {
                    val worker = KratosConnection(config)
                    connection = worker
                    val capability = worker.call("hello", 0, 0)
                    check(capability["available"] == true) { "Kratos dependencies unavailable: $capability" }
                    if (opening.isCancelled) { worker.close(); return@runAsync }
                    val payload = worker.call("createBackgroundDomain", 0, 0, mapOf("bounds" to bounds.wire(), "resolution" to resolution))
                    val session = KratosSession(worker, BackgroundDomain.decode(bounds, resolution, payload), capability)
                    // Cancellation during startup must not orphan the newly created worker.
                    if (!opening.complete(session)) session.close()
                } catch (e: Exception) {
                    connection?.close()
                    opening.completeExceptionally(e)
                }
            }
            return opening
        }
    }
}
