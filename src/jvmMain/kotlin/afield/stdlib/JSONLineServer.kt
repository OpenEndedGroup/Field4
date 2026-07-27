@file:afield.CompletionCategory("Data")

package afield.stdlib

import afield.Box
import afield.ReturnValueRegistry
import afield.RunsAt
import afield.logger.lprintln
import afield.returnValueRegistration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.Closeable
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * A TCP listener that turns newline-delimited JSON into a value a box can read each frame.
 * [JSONLineServer] binds a `ServerSocket`, accepts any number of clients on daemon threads, and
 * keeps only the most recently received valid JSON line — [JSONLineServer.output] is a snapshot,
 * not a queue, so lines that arrive between reads are dropped. Non-JSON lines are counted in
 * [JSONLineServer.invalidLineCount] and logged, never thrown.
 *
 * [jsonl] and [JSONLineServers] hand out one cached, already-started server per
 * (port, bind host, backlog) so that re-running a box does not fight itself for the port;
 * ephemeral port 0 servers are rejected by the cache and must be built with `autoStart = false`.
 * Returning a [JSONLineServer] from a box keeps it running for that box's run —
 * [registerJSONLineServerReturnValue] installs the `ReturnValueRegistry` handler that does it, and
 * an uncached server is stopped when the run ends.
 */

/**
 * Small TCP server for newline-delimited JSON streams.
 *
 * Example sender:
 *
 *     some_process_that_prints_jsonl | nc field-machine.local 7777
 *     printf '%s\n' '{"value":1}' | nc field-machine.local 7777
 *
 * Reconnect wrapper:
 *
 *     while true; do some_process_that_prints_jsonl | nc -N field-machine.local 7777; sleep 1; done
 *
 * Field-side usage:
 *
 *     val stream = JSONLineServer(7777)
 *     loop {
 *         val latest = stream.output
 *         println(latest.optDouble("value"))
 *         frame()
 *     }
 *
 *     stream
 */
class JSONLineServer private constructor(
    localPort: Int,
    val bindHost: String = "0.0.0.0",
    val backlog: Int = 4,
    autoStart: Boolean = true,
    private val cacheKey: JSONLineServerKey? = null
) : Closeable {

    val requestedPort: Int = localPort

    @Volatile
    var port: Int = localPort
        private set

    private val latestJsonLineRef = AtomicReference<String?>(null)
    private val latestRawLineRef = AtomicReference<String?>(null)
    private val latestRemoteRef = AtomicReference<String?>(null)
    private val latestConnectionRemoteRef = AtomicReference<String?>(null)
    private val latestDisconnectedRemoteRef = AtomicReference<String?>(null)
    private val lastErrorRef = AtomicReference<String?>(null)
    private val lineCountRef = AtomicLong(0)
    private val invalidLineCountRef = AtomicLong(0)
    private val connectionCountRef = AtomicLong(0)
    private val activeConnectionCountRef = AtomicLong(0)

    private val clients = CopyOnWriteArrayList<Socket>()
    private val clientThreads = CopyOnWriteArrayList<Thread>()

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var acceptThread: Thread? = null

    @Volatile
    var running: Boolean = false
        private set

    @Volatile
    var latestReceivedAtMillis: Long = 0L
        private set

    @Volatile
    var latestConnectedAtMillis: Long = 0L
        private set

    @Volatile
    var latestDisconnectedAtMillis: Long = 0L
        private set

    val output: JSONObject
        get() = latestJsonLineRef.get()?.let(::JSONObject) ?: JSONObject()

    val latest: JSONObject?
        get() = latestJsonLineRef.get()?.let(::JSONObject)

    val outputText: String?
        get() = latestJsonLineRef.get()

    val latestLine: String?
        get() = latestJsonLineRef.get()

    val latestRawLine: String?
        get() = latestRawLineRef.get()

    val latestRemoteAddress: String?
        get() = latestRemoteRef.get()

    val latestConnectionRemoteAddress: String?
        get() = latestConnectionRemoteRef.get()

    val latestDisconnectedRemoteAddress: String?
        get() = latestDisconnectedRemoteRef.get()

    val lastError: String?
        get() = lastErrorRef.get()

    val lineCount: Long
        get() = lineCountRef.get()

    val invalidLineCount: Long
        get() = invalidLineCountRef.get()

    val connectionCount: Long
        get() = connectionCountRef.get()

    val activeConnectionCount: Long
        get() = activeConnectionCountRef.get()

    init {
        if (autoStart) start()
    }

    companion object {
        operator fun invoke(
            localPort: Int,
            bindHost: String = "0.0.0.0",
            backlog: Int = 4,
            autoStart: Boolean = true
        ): JSONLineServer {
            return if (autoStart && localPort != 0) {
                JSONLineServers.get(localPort, bindHost, backlog)
            } else {
                uncached(localPort, bindHost, backlog, autoStart)
            }
        }

        internal fun uncached(
            localPort: Int,
            bindHost: String = "0.0.0.0",
            backlog: Int = 4,
            autoStart: Boolean = true
        ): JSONLineServer = JSONLineServer(localPort, bindHost, backlog, autoStart, null)

        internal fun cached(
            key: JSONLineServerKey
        ): JSONLineServer = JSONLineServer(key.port, key.bindHost, key.backlog, false, key)
    }

    fun hasOutput(): Boolean = latestJsonLineRef.get() != null

    @Synchronized
    fun start(): JSONLineServer {
        if (running) return this

        val socket = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(bindAddress(), backlog)
            }
        } catch (t: Throwable) {
            val message = "Failed to start JSONLineServer on $bindHost:$requestedPort: ${t.message ?: t}"
            lastErrorRef.set(message)
            lprintln("error,jsonlineserver", message)
            return this
        }

        serverSocket = socket
        port = socket.localPort
        running = true
        lastErrorRef.set(null)

        acceptThread = thread(name = "json-line-server-accept-$port", isDaemon = true) {
            acceptLoop(socket)
        }

        return this
    }

    @Synchronized
    fun stop() {
        running = false

        val socket = serverSocket
        serverSocket = null
        runCatching { socket?.close() }

        clients.forEach { client ->
            runCatching { client.close() }
        }
        clients.clear()

        val current = Thread.currentThread()
        acceptThread?.takeIf { it != current }?.join(250)
        acceptThread = null

        clientThreads.removeIf { thread ->
            if (thread != current) thread.join(50)
            !thread.isAlive
        }
    }

    override fun close() {
        JSONLineServers.remove(this)
        stop()
    }

    override fun toString(): String {
        return "JSONLineServer($bindHost:$port, running=$running, lines=$lineCount, connections=$connectionCount)"
    }

    internal suspend fun runForBox(box: Box, strand: String, generation: Int) {
        start()

        val completionHandle = currentCoroutineContext()[Job]?.invokeOnCompletion {
            stop()
        }

        try {
            while (running && box.runGeneration[strand] == generation) {
                delay(50)
            }
        } finally {
            completionHandle?.dispose()
            if (cacheKey == null) stop()
        }
    }

    private fun bindAddress(): InetSocketAddress {
        return if (bindHost.isBlank() || bindHost == "0.0.0.0" || bindHost == "*") {
            InetSocketAddress(requestedPort)
        } else {
            InetSocketAddress(bindHost, requestedPort)
        }
    }

    private fun acceptLoop(socket: ServerSocket) {
        try {
            while (running && !socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (t: SocketException) {
                    if (running) recordError("JSONLineServer accept failed: ${t.message ?: t}")
                    break
                } catch (t: Throwable) {
                    if (running) recordError("JSONLineServer accept failed: ${t.message ?: t}")
                    break
                }

                clients += client
                connectionCountRef.incrementAndGet()
                activeConnectionCountRef.incrementAndGet()
                latestConnectedAtMillis = System.currentTimeMillis()
                latestConnectionRemoteRef.set(client.remoteSocketAddress?.toString())

                val readerThread = thread(name = "json-line-server-client-$port-${connectionCountRef.get()}", isDaemon = true) {
                    handleClient(client)
                }
                clientThreads += readerThread
            }
        } finally {
            if (serverSocket === socket) {
                running = false
                serverSocket = null
            }
            runCatching { socket.close() }
        }
    }

    private fun handleClient(client: Socket) {
        val remote = client.remoteSocketAddress?.toString()
        try {
            client.getInputStream().bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    handleLine(line, remote)
                }
            }
        } catch (t: Throwable) {
            if (running) recordError("JSONLineServer client read failed: ${t.message ?: t}")
        } finally {
            clients.remove(client)
            activeConnectionCountRef.decrementAndGet()
            latestDisconnectedAtMillis = System.currentTimeMillis()
            latestDisconnectedRemoteRef.set(remote)
            runCatching { client.close() }
            clientThreads.remove(Thread.currentThread())
        }
    }

    private fun handleLine(line: String, remote: String?) {
        latestRawLineRef.set(line)

        val trimmed = line.trim()
        if (trimmed.isEmpty()) return

        try {
            JSONObject(trimmed)
            latestJsonLineRef.set(trimmed)
            latestRemoteRef.set(remote)
            latestReceivedAtMillis = System.currentTimeMillis()
            lineCountRef.incrementAndGet()
            lastErrorRef.set(null)
        } catch (t: Throwable) {
            invalidLineCountRef.incrementAndGet()
            recordError("Ignoring non-JSON line from ${remote ?: "unknown"}: ${t.message ?: trimmed}")
        }
    }

    private fun recordError(message: String) {
        lastErrorRef.set(message)
        lprintln("warn,jsonlineserver", message)
    }
}

internal data class JSONLineServerKey(val port: Int, val bindHost: String, val backlog: Int)

object JSONLineServers {
    private val cache = ConcurrentHashMap<JSONLineServerKey, JSONLineServer>()

    fun get(
        port: Int = 7777,
        bindHost: String = "0.0.0.0",
        backlog: Int = 4
    ): JSONLineServer {
        require(port != 0) { "Use JSONLineServer(..., autoStart = false) for ephemeral port 0 servers; cached JSONL servers need a fixed port." }
        val key = JSONLineServerKey(port, bindHost, backlog)
        val server = cache.computeIfAbsent(key) {
            JSONLineServer.cached(it)
        }
        server.start()
        return server
    }

    fun remove(server: JSONLineServer) {
        cache.entries.removeIf { it.value === server }
    }
}

fun jsonl(port: Int = 7777, bindHost: String = "0.0.0.0", backlog: Int = 4): JSONLineServer =
    JSONLineServers.get(port, bindHost, backlog)

@RunsAt(returnValueRegistration, priority = 12)
fun registerJSONLineServerReturnValue() {
    ReturnValueRegistry.handlers += handler@{ inside, returnValue, strand, generation, notify ->
        val server = returnValue as? JSONLineServer ?: return@handler false

        CoroutineScope(Dispatchers.Default + Box.boxContextElement(inside)).launch {
            notify(true, strand, generation)
            val job = coroutineContext[Job]!!
            inside.jobs += job
            try {
                server.runForBox(inside, strand, generation)
            } finally {
                server.stop()
                notify(false, strand, generation)
            }
        }
        true
    }
}
